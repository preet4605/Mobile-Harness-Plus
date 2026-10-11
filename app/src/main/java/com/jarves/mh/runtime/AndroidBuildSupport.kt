package com.jarves.mh.runtime

import java.io.File

/**
 * The bounded, no-daemon Gradle invocation used for one-tap Android builds.
 * A project's own wrapper wins when it ships one; otherwise the installed Gradle runs.
 */
internal fun androidGradleCommand(root: File, task: String): String {
    val launcher = if (File(root, "gradlew").isFile) "bash ./gradlew" else "gradle"
    // Gradle daemons can stay attached to deleted PRoot paths after an interrupted
    // build, so every build is a single bounded, no-daemon invocation (upstream 6440f40).
    return "$launcher -Dorg.gradle.jvmargs= --no-daemon --max-workers=2 " +
        "--init-script /root/.gradle/init.d/pocketdev-android.gradle " +
        "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
        "$task --console=plain --stacktrace"
}

private val IGNORED_BUILD_DIRECTORIES = setOf("build", ".gradle", ".idea", ".git")

/** The newest debug APK Gradle produced under [projectRoot], if any. */
internal fun findDebugApk(projectRoot: File): File? = projectRoot.walkTopDown()
    .onEnter { it == projectRoot || it.name !in setOf(".gradle", ".idea", ".git") }
    .filter {
        it.isFile && it.extension.equals("apk", ignoreCase = true) &&
            "/outputs/apk/debug/" in it.invariantSeparatorsPath && it.length() > 0L
    }
    .maxByOrNull(File::lastModified)

/**
 * Returns the last debug APK only when no build input under [projectRoot] is newer than it,
 * so an unchanged project can skip Gradle entirely. Build outputs and IDE/VCS folders are ignored.
 */
internal fun findReusableDebugApk(projectRoot: File): File? {
    val apk = findDebugApk(projectRoot) ?: return null
    val builtAt = apk.lastModified()
    val buildInputExtensions = setOf(
        "kt", "java", "xml", "gradle", "kts", "properties", "toml", "pro", "json", "jar", "aar",
    )
    val changedAfterApk = projectRoot.walkTopDown()
        .onEnter { it == projectRoot || it.name !in IGNORED_BUILD_DIRECTORIES }
        .filter { file ->
            if (!file.isFile) return@filter false
            val relative = file.relativeTo(projectRoot).invariantSeparatorsPath
            relative.startsWith("src/") || "/src/" in relative ||
                file.extension.lowercase() in buildInputExtensions ||
                file.name == "gradlew" || file.name == "gradlew.bat"
        }
        .any { it.lastModified() > builtAt }
    return apk.takeUnless { changedAfterApk }
}

/** Coarse Gradle progress for an Android debug build, in the order the tasks normally run. */
enum class AndroidBuildStage(val label: String) {
    PREPARING("Starting Gradle"),
    CONFIGURING("Configuring project"),
    RESOURCES("Processing resources"),
    COMPILING("Compiling sources"),
    DEXING("Converting to DEX"),
    PACKAGING("Packaging APK"),
}

/** One readable build problem; [filePath] is relative to the Gradle root when it points inside it. */
data class AndroidBuildIssue(
    val title: String,
    val detail: String,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

/**
 * The furthest stage that [output] (plain-console Gradle text) shows, never moving back from [current],
 * since Gradle interleaves tasks from different stages.
 */
internal fun inferAndroidBuildStage(output: String, current: AndroidBuildStage): AndroidBuildStage {
    var stage = current
    output.lineSequence().forEach { raw ->
        val line = raw.trim()
        val found = when {
            line.startsWith("> Task ") ->
                androidTaskStage(line.removePrefix("> Task ").substringBefore(' ').substringAfterLast(':'))
            line.startsWith("> Configure project") || line.startsWith("Download ") -> AndroidBuildStage.CONFIGURING
            else -> null
        }
        if (found != null && found > stage) stage = found
    }
    return stage
}

private fun androidTaskStage(taskName: String): AndroidBuildStage {
    val task = taskName.lowercase()
    return when {
        task.startsWith("assemble") -> AndroidBuildStage.PACKAGING
        task.startsWith("package") && !task.endsWith("resources") -> AndroidBuildStage.PACKAGING
        "dex" in task -> AndroidBuildStage.DEXING
        task.startsWith("compile") && ("kotlin" in task || "java" in task) -> AndroidBuildStage.COMPILING
        task.startsWith("ksp") || task.startsWith("kapt") -> AndroidBuildStage.COMPILING
        "resource" in task || "manifest" in task || "assets" in task || "aidl" in task -> AndroidBuildStage.RESOURCES
        else -> AndroidBuildStage.CONFIGURING
    }
}

private val KOTLIN_ISSUE = Regex("""^e: (?:file://)?(\S+?\.kts?):(?:(\d+):(\d+)|\s?\((\d+), (\d+)\)):?\s*(.*)$""")
private val JAVA_ISSUE = Regex("""^(\S+?\.java):(\d+): error: (.*)$""")
private val RESOURCE_ISSUE =
    Regex("""^(?:ERROR:\s*)?(\S+?\.(?:kt|kts|java|xml|gradle|toml|pro)):(\d+)(?::(\d+))?:?\s*(?:AAPT: )?error: (.*)$""")
private val BUILD_FILE_ISSUE = Regex("""^Build file '([^']+)' line: (\d+)$""")

/**
 * Turns a failed Gradle run's [output] into readable issues. Source locations under [guestRoot]
 * (the Gradle root as the build sees it, e.g. /workspace/app) become root-relative paths.
 */
internal fun parseAndroidBuildIssues(output: String, guestRoot: String, exitCode: Int): List<AndroidBuildIssue> {
    val issues = mutableListOf<AndroidBuildIssue>()
    val lines = output.lines()
    lines.forEachIndexed { index, raw ->
        val line = raw.trim()
        KOTLIN_ISSUE.find(line)?.let { match ->
            val g = match.groupValues
            issues += sourceIssue(
                "Kotlin error", g[1], guestRoot,
                g[2].ifEmpty { g[4] }.toIntOrNull(), g[3].ifEmpty { g[5] }.toIntOrNull(), g[6],
            )
            return@forEachIndexed
        }
        JAVA_ISSUE.find(line)?.let { match ->
            val g = match.groupValues
            issues += sourceIssue("Java error", g[1], guestRoot, g[2].toIntOrNull(), null, g[3])
            return@forEachIndexed
        }
        RESOURCE_ISSUE.find(line)?.let { match ->
            val g = match.groupValues
            issues += sourceIssue("Resource error", g[1], guestRoot, g[2].toIntOrNull(), g[3].toIntOrNull(), g[4])
            return@forEachIndexed
        }
        BUILD_FILE_ISSUE.find(line)?.let { match ->
            val g = match.groupValues
            issues += sourceIssue("Build script error", g[1], guestRoot, g[2].toIntOrNull(), null, whatWentWrong(lines, index))
        }
    }
    val distinct = issues.distinctBy { listOf(it.filePath, it.line, it.column, it.detail) }.take(MAX_BUILD_ISSUES)
    if (distinct.isNotEmpty()) return distinct
    return listOf(
        AndroidBuildIssue(
            title = androidBuildFailureSummary(output, exitCode),
            detail = whatWentWrong(lines, 0).ifBlank { lastMeaningfulLines(lines) },
        ),
    )
}

/** A one-line reason for a failed build, for the status message and notification. */
internal fun androidBuildFailureSummary(output: String, exitCode: Int): String {
    val text = output.lowercase()
    return when {
        "no space left on device" in text -> "The device ran out of storage during the build."
        "outofmemoryerror" in text || "java heap space" in text -> "Gradle ran out of memory. Close other apps and retry."
        "could not resolve" in text || "could not get resource" in text ->
            "A dependency could not be downloaded. Check the connection and retry."
        "manifest merger failed" in text -> "The Android manifest could not be merged."
        "android resource linking failed" in text || "aapt: error" in text -> "Android resources could not be compiled."
        "compilation error" in text || "compilation failed" in text || text.lineSequence().any { it.trim().startsWith("e: ") } ->
            "Kotlin or Java compilation failed."
        "minimum supported gradle version" in text || "maximum supported gradle" in text ->
            "The project's Gradle and Android plugin versions do not match."
        else -> "Gradle build failed (exit code $exitCode)."
    }
}

private fun sourceIssue(
    title: String,
    rawPath: String,
    guestRoot: String,
    line: Int?,
    column: Int?,
    detail: String,
): AndroidBuildIssue {
    val root = guestRoot.trimEnd('/')
    val relative = when {
        rawPath.startsWith("$root/") -> rawPath.removePrefix("$root/")
        !rawPath.startsWith("/") -> rawPath
        else -> null
    }?.takeIf { path -> path.isNotBlank() && path.split('/').none { it == ".." } }
    return AndroidBuildIssue(
        title = title,
        detail = detail.trim().ifBlank { rawPath }.take(MAX_ISSUE_DETAIL),
        filePath = relative,
        line = line,
        column = column,
    )
}

/** The text under Gradle's "* What went wrong:" heading at or after [from], up to the next heading. */
private fun whatWentWrong(lines: List<String>, from: Int): String {
    val start = (from until lines.size).firstOrNull { lines[it].trim() == "* What went wrong:" } ?: return ""
    return lines.drop(start + 1)
        .takeWhile { !it.trim().startsWith("* ") }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(6)
        .joinToString("\n")
        .take(MAX_ISSUE_DETAIL)
}

private fun lastMeaningfulLines(lines: List<String>): String = lines
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("at ") && !it.startsWith("> Task ") }
    .takeLast(6)
    .joinToString("\n")
    .take(MAX_ISSUE_DETAIL)

private const val MAX_BUILD_ISSUES = 20
private const val MAX_ISSUE_DETAIL = 600

/** The typical duration of this project's recent successful builds (their median), or null before the first. */
internal fun typicalAndroidBuildMillis(recentDurationsMillis: List<Long>): Long? =
    recentDurationsMillis.filter { it > 0L }.sorted().takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }

/** "Usually about 2 min" before a build, "About 40 s remaining" during one. */
internal fun androidBuildEstimateText(running: Boolean, elapsedMillis: Long, typicalMillis: Long?): String? {
    if (typicalMillis == null) return if (running) "Timing this first build" else null
    if (!running) return "Usually about ${formatBuildDuration(typicalMillis)}"
    val remaining = typicalMillis - elapsedMillis
    return if (remaining >= 1_000L) "About ${formatBuildDuration(remaining)} remaining" else "Taking longer than usual"
}

internal fun formatBuildDuration(millis: Long): String {
    val seconds = ((millis + 500L) / 1_000L).coerceAtLeast(1L)
    if (seconds < 60L) return "$seconds s"
    val minutes = seconds / 60L
    val rest = seconds % 60L
    return if (rest == 0L) "$minutes min" else "$minutes min $rest s"
}
