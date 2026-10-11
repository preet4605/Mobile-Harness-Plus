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
