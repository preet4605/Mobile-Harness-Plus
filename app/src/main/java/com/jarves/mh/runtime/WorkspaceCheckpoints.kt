package com.jarves.mh.runtime

import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.model.isAttachmentStoragePath
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Each project workspace gets a baseline copy before a session runs; after the
 * run the baseline is diffed to produce reviewable [ChangeItem]s with
 * per-file Undo/Keep. Semantics mirror the original Claude bridge store so
 * both agents behave identically in the Changes tab.
 */
class WorkspaceCheckpoints(
    private val filesDir: File,
    private val rootPathResolver: ((String) -> String)? = null,
) {
    private val projectRoots = ConcurrentHashMap<String, String>()

    fun ensureWorkspace(projectId: String): File {
        val base = File(filesDir, "workspaces/$projectId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[projectId] ?: rootPathResolver?.invoke(projectId).orEmpty()
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val normalized = rootPath.trim().trim('/')
        require(normalized.isBlank() || (!normalized.contains("..") && !normalized.startsWith('/'))) {
            "Unsafe project root"
        }
        val previous = projectRoots[projectId] ?: rootPathResolver?.invoke(projectId).orEmpty()
        projectRoots[projectId] = normalized
        val savedRoot = readMetadata(projectId)?.workspaceRoot
        val selectedRoot = ensureWorkspace(projectId).canonicalPath
        // Reopening the same nested project must retain its pending Undo baseline.
        if ((savedRoot != null && savedRoot != selectedRoot) || (savedRoot == null && previous != normalized)) {
            checkpointDir(projectId).deleteRecursively()
        }
    }

    fun checkpointDir(projectId: String): File = checkpointDir(projectId, DEFAULT_CHECKPOINT_TAG)

    fun checkpointDir(projectId: String, checkpointTag: String): File {
        validateCheckpointTag(checkpointTag)
        val projectDir = File(filesDir, "checkpoints/$projectId").canonicalFile
        val target = File(projectDir, checkpointTag).canonicalFile
        require(target.toPath().startsWith(projectDir.toPath())) { "Unsafe checkpoint directory" }
        return target
    }

    fun checkpointExists(projectId: String, checkpointTag: String = DEFAULT_CHECKPOINT_TAG): Boolean {
        if (!isValidCheckpointTag(checkpointTag)) return false
        val checkpoint = checkpointDir(projectId, checkpointTag)
        return File(checkpoint, "project").isDirectory && !File(checkpoint, INCOMPLETE_MARKER).exists()
    }

    fun listCheckpoints(projectId: String): List<String> {
        val base = File(filesDir, "checkpoints/$projectId")
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            ?.filter { it.isDirectory && File(it, "project").isDirectory && !File(it, INCOMPLETE_MARKER).exists() }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun createCheckpoint(projectId: String, workspace: File) =
        createCheckpoint(projectId, workspace, DEFAULT_CHECKPOINT_TAG)

    fun createCheckpoint(projectId: String, checkpointTag: String = DEFAULT_CHECKPOINT_TAG) =
        createCheckpoint(projectId, ensureWorkspace(projectId), checkpointTag)

    fun createCheckpoint(
        projectId: String,
        workspace: File,
        checkpointTag: String,
        taskId: String? = null,
        stepId: String? = null,
        attempt: Int? = null
    ) {
        validateCheckpointTag(checkpointTag)
        val checkpoint = checkpointDir(projectId, checkpointTag)

        // For the legacy default checkpoint, keep the original baseline until every pending file is accepted or undone.
        if (checkpointTag == DEFAULT_CHECKPOINT_TAG) {
            if (checkpointExists(projectId, checkpointTag) && File(checkpoint, "changes.json").isFile) return
        }

        checkpoint.deleteRecursively()
        require(checkpoint.mkdirs()) { "Cannot create checkpoint directory" }
        val incomplete = File(checkpoint, INCOMPLETE_MARKER).apply { writeText("") }
        val backup = File(checkpoint, "project").apply { mkdirs() }
        val workspacePath = workspace.canonicalFile.toPath()
        val originalPaths = mutableListOf<String>()
        val excludedDirectories = mutableListOf<String>()
        val fingerprints = mutableMapOf<String, String>()
        val backedUpFiles = mutableListOf<String>()
        require(workspace.isDirectory && backup.isDirectory) { "Cannot create workspace checkpoint" }
        workspace.walkTopDown()
            .onFail { _, exception -> throw exception }
            .onEnter { directory ->
                if (directory == workspace) return@onEnter true
                val relative = directory.relativeTo(workspace).invariantSeparatorsPath
                originalPaths.add(relative)
                if (directory.name.lowercase(Locale.ROOT) in IGNORED_DIRECTORY_NAMES ||
                    isInternalRuntimePath(relative) || java.nio.file.Files.isSymbolicLink(directory.toPath())) {
                    excludedDirectories.add(relative)
                    return@onEnter false
                }
                require(directory.canonicalFile.toPath().startsWith(workspacePath)) { "Unsafe checkpoint source" }
                true
            }
            .forEach { source ->
                if (source == workspace || source.isDirectory) return@forEach
                val relative = source.relativeTo(workspace).invariantSeparatorsPath
                if (isInternalRuntimePath(relative)) return@forEach
                // Inventory first: skipped, unreadable and oversized originals are never new files.
                originalPaths.add(relative)
                if (java.nio.file.Files.isSymbolicLink(source.toPath()) || !source.isFile) return@forEach
                require(source.canonicalFile.toPath().startsWith(workspacePath)) { "Unsafe checkpoint source" }
                if (source.length() > MAX_CHECKPOINT_COPY_BYTES) {
                    fingerprints[relative] = digest(source)
                    return@forEach
                }
                val destination = safeWorkspaceFile(backup, relative)
                runCatching {
                    destination.parentFile?.mkdirs()
                    source.copyTo(destination, overwrite = true)
                    destination.setLastModified(source.lastModified())
                    // Record only completed copies, with their actual backup fingerprint.
                    fingerprints[relative] = digest(destination)
                    backedUpFiles.add(relative)
                }.onFailure {
                    destination.delete()
                    runCatching { digest(source) }.getOrNull()?.let { fingerprints[relative] = it }
                }
            }

        val metadata = CheckpointMetadata(
            projectId = projectId,
            checkpointTag = checkpointTag,
            createdAt = System.currentTimeMillis(),
            backedUpFiles = backedUpFiles.sorted(),
            fingerprints = fingerprints,
            originalPaths = originalPaths.distinct().sorted(),
            excludedDirectories = excludedDirectories.sorted(),
            workspaceRoot = workspace.canonicalPath,
            changes = readChangedPaths(projectId, checkpointTag),
            taskId = taskId,
            stepId = stepId,
            attempt = attempt,
        )
        writeMetadata(checkpoint, metadata)
        check(incomplete.delete()) { "Cannot finalize checkpoint" }

        // Enforce step checkpoint retention if applicable
        if (isStepTag(checkpointTag)) {
            pruneStepCheckpoints(projectId)
        }
    }

    fun restoreCheckpoint(projectId: String, checkpointTag: String = DEFAULT_CHECKPOINT_TAG): Boolean =
        restoreCheckpoint(projectId, ensureWorkspace(projectId), checkpointTag)

    fun restoreCheckpoint(
        projectId: String,
        workspace: File,
        checkpointTag: String = DEFAULT_CHECKPOINT_TAG
    ): Boolean {
        validateCheckpointTag(checkpointTag)
        val checkpoint = checkpointDir(projectId, checkpointTag)
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory) return false

        val metadata = readMetadata(projectId, checkpointTag) ?: return false
        val paths = metadata.backedUpFiles + readChangedPaths(projectId, checkpointTag)
        return restorePaths(projectId, workspace, paths, checkpointTag)
    }

    /** Validate the entire request before changing files; unknown originals remain pending. */
    private fun restorePaths(
        projectId: String,
        workspace: File,
        paths: List<String>,
        checkpointTag: String = DEFAULT_CHECKPOINT_TAG,
    ): Boolean {
        return runCatching {
            val metadata = readMetadata(projectId, checkpointTag) ?: return false
            val root = workspace.canonicalFile
            if (metadata.workspaceRoot != null && metadata.workspaceRoot != root.path) return false
            val backup = File(checkpointDir(projectId, checkpointTag), "project")
            if (!backup.isDirectory) return false
            val originals = metadata.originalPaths?.toSet()
            val backedUp = metadata.backedUpFiles.toSet()
            val actions = paths.filterNot(::isInternalRuntimePath).distinct().map { path ->
                require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe restore path" }
                require(!path.contains('\\')) { "Unsafe restore path" }
                val target = safeWorkspaceFile(root, path)
                val original = safeWorkspaceFile(backup, path)
                // Do not follow symlinks, even when their destination remains inside the root.
                var entry: File? = target
                while (entry != null && entry != root) {
                    if (java.nio.file.Files.isSymbolicLink(entry.toPath())) return false
                    entry = entry.parentFile
                }
                if (target.exists() && !target.isFile) return false
                if (path in backedUp) {
                    if (!original.isFile || java.nio.file.Files.isSymbolicLink(original.toPath())) return false
                    if (metadata.fingerprints[path] != digest(original)) return false
                    target to original
                } else {
                    // Legacy/incomplete inventories can restore backed-up files but cannot prove absence.
                    if (originals == null || path in originals || metadata.excludedDirectories.any {
                            path == it || path.startsWith("$it/")
                        }) return false
                    target to null
                }
            }
            actions.forEach { (target, original) ->
                if (original != null) {
                    target.parentFile?.mkdirs()
                    val staged = File.createTempFile(".mh-restore-", ".tmp", target.parentFile)
                    try {
                        original.copyTo(staged, overwrite = true)
                        java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally {
                        staged.delete()
                    }
                } else if (target.exists() && !target.delete()) {
                    return false
                }
            }
            true
        }.getOrDefault(false)
    }

    fun undoLastChanges(projectId: String): Boolean {
        val paths = readChangedPaths(projectId)
        if (paths.isEmpty() || !restorePaths(projectId, ensureWorkspace(projectId), paths)) return false
        return deleteCheckpoint(projectId)
    }

    fun undoFileChange(projectId: String, path: String): Boolean {
        if (isInternalRuntimePath(path) || path !in readChangedPaths(projectId)) return false
        if (!restorePaths(projectId, ensureWorkspace(projectId), listOf(path))) return false
        removeChangedPath(projectId, path)
        return true
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) =
        saveChangedPaths(projectId, paths, DEFAULT_CHECKPOINT_TAG)

    fun saveChangedPaths(projectId: String, paths: List<String>, checkpointTag: String) {
        validateCheckpointTag(checkpointTag)
        val checkpoint = checkpointDir(projectId, checkpointTag)
        val manifest = File(checkpoint, "changes.json")
        manifest.parentFile?.mkdirs()
        val merged = (readChangedPaths(projectId, checkpointTag) + paths)
            .filterNot(::isInternalRuntimePath)
            .distinct()
            .sorted()
        manifest.writeText(JSONArray(merged).toString())
        updateMetadataChanges(projectId, checkpointTag, merged)
    }

    fun readChangedPaths(projectId: String): List<String> =
        readChangedPaths(projectId, DEFAULT_CHECKPOINT_TAG)

    fun readChangedPaths(projectId: String, checkpointTag: String): List<String> {
        validateCheckpointTag(checkpointTag)
        val manifest = File(checkpointDir(projectId, checkpointTag), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    fun removeChangedPath(projectId: String, path: String) =
        removeChangedPath(projectId, path, DEFAULT_CHECKPOINT_TAG)

    fun removeChangedPath(projectId: String, path: String, checkpointTag: String) {
        validateCheckpointTag(checkpointTag)
        val remaining = readChangedPaths(projectId, checkpointTag).filterNot { it == path }
        val checkpoint = checkpointDir(projectId, checkpointTag)
        if (checkpointTag == DEFAULT_CHECKPOINT_TAG) {
            if (remaining.isEmpty()) {
                checkpoint.deleteRecursively()
            } else {
                File(checkpoint, "changes.json").writeText(JSONArray(remaining).toString())
                updateMetadataChanges(projectId, checkpointTag, remaining, baselinePath = path)
            }
        } else {
            val manifest = File(checkpoint, "changes.json")
            if (remaining.isEmpty()) {
                manifest.delete()
            } else {
                manifest.writeText(JSONArray(remaining).toString())
            }
            updateMetadataChanges(projectId, checkpointTag, remaining, baselinePath = path)
        }
    }

    fun deleteCheckpoint(projectId: String, checkpointTag: String = DEFAULT_CHECKPOINT_TAG): Boolean {
        validateCheckpointTag(checkpointTag)
        val dir = checkpointDir(projectId, checkpointTag)
        if (!dir.exists()) return false
        return dir.deleteRecursively()
    }

    private fun writeMetadata(checkpointDir: File, metadata: CheckpointMetadata) {
        val json = JSONObject().apply {
            put("projectId", metadata.projectId)
            put("checkpointTag", metadata.checkpointTag)
            put("createdAt", metadata.createdAt)
            put("backedUpFiles", JSONArray(metadata.backedUpFiles))
            metadata.originalPaths?.let { put("originalPaths", JSONArray(it)) }
            put("excludedDirectories", JSONArray(metadata.excludedDirectories))
            metadata.workspaceRoot?.let { put("workspaceRoot", it) }
            val fingerprintsObj = JSONObject()
            metadata.fingerprints.forEach { (k, v) -> fingerprintsObj.put(k, v) }
            put("fingerprints", fingerprintsObj)
            put("changes", JSONArray(metadata.changes))
            metadata.taskId?.let { put("taskId", it) }
            metadata.stepId?.let { put("stepId", it) }
            metadata.attempt?.let { put("attempt", it) }
        }
        val staged = File.createTempFile(".metadata-", ".tmp", checkpointDir)
        try {
            staged.writeText(json.toString(2))
            java.nio.file.Files.move(
                staged.toPath(),
                File(checkpointDir, "metadata.json").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            staged.delete()
        }
    }

    private fun updateMetadataChanges(
        projectId: String,
        checkpointTag: String,
        changes: List<String>,
        baselinePath: String? = null,
    ) {
        val checkpoint = checkpointDir(projectId, checkpointTag)
        val existing = readMetadata(projectId, checkpointTag) ?: return
        var updated = existing.copy(changes = changes)
        if (baselinePath != null) {
            // Keep mutates the baseline before removing a pending path. Preserve that
            // accepted content (or deletion) as the original for subsequent edits.
            val baseline = safeWorkspaceFile(File(checkpoint, "project"), baselinePath)
            val backedUp = existing.backedUpFiles.toMutableSet()
            val fingerprints = existing.fingerprints.toMutableMap()
            val originals = existing.originalPaths?.toMutableSet()
            if (baseline.isFile) {
                backedUp.add(baselinePath)
                fingerprints[baselinePath] = digest(baseline)
                originals?.add(baselinePath)
            } else {
                backedUp.remove(baselinePath)
                fingerprints.remove(baselinePath)
                originals?.remove(baselinePath)
            }
            updated = updated.copy(
                backedUpFiles = backedUp.sorted(),
                fingerprints = fingerprints,
                originalPaths = originals?.sorted(),
            )
        }
        writeMetadata(checkpoint, updated)
    }

    fun readMetadata(projectId: String, checkpointTag: String = DEFAULT_CHECKPOINT_TAG): CheckpointMetadata? {
        val checkpoint = runCatching { checkpointDir(projectId, checkpointTag) }.getOrNull() ?: return null
        if (File(checkpoint, INCOMPLETE_MARKER).exists()) return null
        val metaFile = File(checkpoint, "metadata.json")
        if (metaFile.isFile) {
            return runCatching {
                val json = JSONObject(metaFile.readText())
                val filesArray = json.optJSONArray("backedUpFiles") ?: JSONArray()
                val files = (0 until filesArray.length()).map { filesArray.getString(it) }
                val fpObj = json.optJSONObject("fingerprints") ?: JSONObject()
                val fpMap = mutableMapOf<String, String>()
                fpObj.keys().forEach { key -> fpMap[key] = fpObj.getString(key) }
                val changesArray = json.optJSONArray("changes") ?: JSONArray()
                val changes = (0 until changesArray.length()).map { changesArray.getString(it) }
                val taskId = json.optString("taskId").takeIf { it.isNotBlank() }
                val stepId = json.optString("stepId").takeIf { it.isNotBlank() }
                val attempt = if (json.has("attempt")) json.optInt("attempt") else null
                CheckpointMetadata(
                    projectId = json.optString("projectId", projectId),
                    checkpointTag = json.optString("checkpointTag", checkpointTag),
                    createdAt = json.optLong("createdAt", metaFile.lastModified()),
                    backedUpFiles = files,
                    fingerprints = fpMap,
                    originalPaths = json.optJSONArray("originalPaths")?.let { paths ->
                        (0 until paths.length()).map(paths::getString)
                    },
                    workspaceRoot = json.optString("workspaceRoot").takeIf { it.isNotBlank() },
                    excludedDirectories = json.optJSONArray("excludedDirectories")?.let { paths ->
                        (0 until paths.length()).map(paths::getString)
                    }.orEmpty(),
                    changes = changes,
                    taskId = taskId,
                    stepId = stepId,
                    attempt = attempt,
                )
            }.getOrNull()
        }
        val backup = File(checkpoint, "project")
        if (!backup.isDirectory) return null
        val fpMap = snapshot(backup)
        val changes = readChangedPaths(projectId, checkpointTag)
        return CheckpointMetadata(
            projectId = projectId,
            checkpointTag = checkpointTag,
            createdAt = backup.lastModified(),
            backedUpFiles = fpMap.keys.toList().sorted(),
            fingerprints = fpMap,
            changes = changes,
        )
    }

    fun pruneStepCheckpoints(projectId: String, retainCount: Int = MAX_RETAINED_STEP_CHECKPOINTS): List<String> {
        require(retainCount >= 0) { "retainCount must be non-negative" }
        val projectDir = File(filesDir, "checkpoints/$projectId")
        if (!projectDir.isDirectory) return emptyList()

        val subdirs = projectDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        val stepDirs = subdirs.filter { isStepTag(it.name) }
        if (stepDirs.size <= retainCount) return emptyList()

        val sorted = stepDirs.sortedWith(
            Comparator { a, b ->
                val numA = extractStepNumber(a.name)
                val numB = extractStepNumber(b.name)
                if (numA != null && numB != null && numA != numB) {
                    return@Comparator numA.compareTo(numB)
                }
                val metaA = readMetadata(projectId, a.name)
                val metaB = readMetadata(projectId, b.name)
                val timeA = metaA?.createdAt ?: a.lastModified()
                val timeB = metaB?.createdAt ?: b.lastModified()
                if (timeA != timeB) {
                    return@Comparator timeA.compareTo(timeB)
                }
                a.name.compareTo(b.name)
            }
        )

        val toPurge = sorted.dropLast(retainCount)
        val purgedTags = mutableListOf<String>()
        toPurge.forEach { dir ->
            purgedTags.add(dir.name)
            dir.deleteRecursively()
        }
        return purgedTags
    }

    fun isBaselineTag(tag: String): Boolean {
        val lower = tag.lowercase(Locale.ROOT)
        return lower == TASK_BASELINE_TAG || lower == "baseline" || lower.endsWith("-baseline") || lower.startsWith("baseline-") || lower.contains("baseline")
    }

    fun isStepTag(tag: String): Boolean {
        val lower = tag.lowercase(Locale.ROOT)
        if (isBaselineTag(lower) || lower == DEFAULT_CHECKPOINT_TAG) return false
        return lower.startsWith("step-") || lower.startsWith("step_") || STEP_TAG_REGEX.matches(lower)
    }

    fun isValidCheckpointTag(tag: String): Boolean {
        if (tag.isBlank()) return false
        if (tag.contains('/') || tag.contains('\\')) return false
        if (tag.startsWith('/') || tag.startsWith('\\')) return false
        if (tag.contains("..")) return false
        if (tag == "." || tag == "..") return false
        if (tag.any { it.isISOControl() || it < ' ' || it.code == 127 }) return false
        return CHECKPOINT_TAG_REGEX.matches(tag)
    }

    fun validateCheckpointTag(tag: String) {
        require(tag.isNotBlank()) { "Checkpoint tag cannot be blank" }
        require(!tag.startsWith('/') && !tag.startsWith('\\')) { "Checkpoint tag cannot be an absolute path: $tag" }
        require(!tag.contains('/') && !tag.contains('\\')) { "Checkpoint tag cannot contain path separators: $tag" }
        require(!tag.contains("..")) { "Checkpoint tag cannot contain path traversal: $tag" }
        require(tag != "." && tag != "..") { "Checkpoint tag cannot be a relative directory pointer: $tag" }
        require(!tag.any { it.isISOControl() || it < ' ' || it.code == 127 }) { "Checkpoint tag contains control characters: $tag" }
        require(CHECKPOINT_TAG_REGEX.matches(tag)) { "Invalid checkpoint tag: $tag" }
    }

    private fun extractStepNumber(tag: String): Long? {
        val match = Regex("\\d+").find(tag)
        return match?.value?.toLongOrNull()
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val backup = File(checkpointDir(projectId), "project")
        return paths.map { path ->
            val beforeFile = safeWorkspaceFile(backup, path).takeIf(File::isFile)
            val afterFile = safeWorkspaceFile(workspace, path).takeIf(File::isFile)
            val beforeLength = beforeFile?.length() ?: 0L
            val afterLength = afterFile?.length() ?: 0L
            val isKnownBin = isKnownBinaryPath(path)
            val isTooLarge = beforeLength > MAX_DIFF_FILE_BYTES || afterLength > MAX_DIFF_FILE_BYTES

            if (isKnownBin || isTooLarge || isBinaryFile(beforeFile) || isBinaryFile(afterFile)) {
                val binary = isKnownBin || isBinaryFile(beforeFile) || isBinaryFile(afterFile)
                val displaySize = afterLength.takeIf { it > 0 } ?: beforeLength
                val infoText = when {
                    binary -> "Binary file changed (${formatFileSize(displaySize)})"
                    else -> "Diff omitted: file too large (${formatFileSize(displaySize)})"
                }
                ChangeItem(
                    path = path,
                    additions = if (afterFile != null) 1 else 0,
                    deletions = if (beforeFile != null) 1 else 0,
                    diffLines = listOf(DiffLine(DiffLineType.INFO, infoText)),
                    binary = binary,
                )
            } else {
                val beforeBytes = beforeFile?.readBytes() ?: ByteArray(0)
                val afterBytes = afterFile?.readBytes() ?: ByteArray(0)
                val binary = beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }
                if (binary) {
                    ChangeItem(
                        path = path,
                        additions = if (afterBytes.isNotEmpty()) 1 else 0,
                        deletions = if (beforeBytes.isNotEmpty()) 1 else 0,
                        diffLines = listOf(DiffLine(DiffLineType.INFO, "Binary file changed")),
                        binary = true,
                    )
                } else {
                    val (additions, deletions) = lineChanges(beforeBytes, afterBytes)
                    ChangeItem(
                        path = path,
                        additions = additions,
                        deletions = deletions,
                        diffLines = buildDiffLines(beforeBytes, afterBytes),
                        binary = false,
                    )
                }
            }
        }
    }

    private fun isBinaryFile(file: File?): Boolean {
        if (file == null || !file.isFile || file.length() == 0L) return false
        return runCatching {
            file.inputStream().use { stream ->
                val buffer = ByteArray(8192)
                val read = stream.read(buffer)
                if (read <= 0) false else (0 until read).any { buffer[it] == 0.toByte() }
            }
        }.getOrDefault(false)
    }

    private fun isKnownBinaryPath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext in BINARY_EXTENSIONS
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (beforeBytes.any { it == 0.toByte() } || afterBytes.any { it == 0.toByte() }) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        val lines = bytes.decodeToString().split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe workspace path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        return file
    }

    fun snapshot(root: File): Map<String, String> {
        if (!root.isDirectory) return emptyMap()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .onEnter { directory ->
                if (directory == root) return@onEnter true
                val dirName = directory.name.lowercase(Locale.ROOT)
                if (dirName in IGNORED_DIRECTORY_NAMES) return@onEnter false
                val relative = directory.relativeTo(root).invariantSeparatorsPath
                if (isInternalRuntimePath(relative)) return@onEnter false
                if (java.nio.file.Files.isSymbolicLink(directory.toPath())) return@onEnter false
                runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(root).invariantSeparatorsPath) &&
                    !java.nio.file.Files.isSymbolicLink(it.toPath()) &&
                    runCatching { it.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }
    }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/')
        return segments.any { it in IGNORED_DIRECTORY_NAMES } ||
            isAttachmentStoragePath(normalized) ||
            normalized == ".claude.json" ||
            normalized.endsWith(".apk.part")
    }

    private fun digest(file: File): String {
        if (file.length() > MAX_HASH_FILE_BYTES) {
            return "${file.length()}_${file.lastModified()}"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val DEFAULT_CHECKPOINT_TAG = "latest"
        const val TASK_BASELINE_TAG = "task-baseline"
        const val MAX_RETAINED_STEP_CHECKPOINTS = 2

        private val CHECKPOINT_TAG_REGEX = Regex("^[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)*$")
        private val STEP_TAG_REGEX = Regex("^step[-_]?[0-9]+.*", RegexOption.IGNORE_CASE)

        private const val INCOMPLETE_MARKER = "incomplete"
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val DIFF_CONTEXT_LINES = 3
        const val MAX_DIFF_FILE_BYTES = 512 * 1024L // 512 KB
        const val MAX_HASH_FILE_BYTES = 10 * 1024 * 1024L // 10 MB
        const val MAX_CHECKPOINT_COPY_BYTES = 25 * 1024 * 1024L // 25 MB

        val IGNORED_DIRECTORY_NAMES = setOf(
            ".git", ".claude", ".gradle", ".idea", ".next", ".cache",
            "node_modules", ".venv", "venv", "__pycache__", "build",
            "dist", ".gemini",
        )

        val BINARY_EXTENSIONS = setOf(
            "apk", "aab", "jar", "aar", "so", "zip", "tar", "gz", "zst",
            "7z", "bz2", "xz", "png", "jpg", "jpeg", "webp", "gif", "ico",
            "class", "dex", "pyc", "exe", "bin", "pdf", "woff", "woff2",
            "ttf", "otf", "mp3", "mp4", "wav", "ogg",
        )
    }
}

data class CheckpointMetadata(
    val projectId: String,
    val checkpointTag: String,
    val createdAt: Long,
    val backedUpFiles: List<String>,
    val fingerprints: Map<String, String>,
    val changes: List<String> = emptyList(),
    val taskId: String? = null,
    val stepId: String? = null,
    val attempt: Int? = null,
    /** Null means a legacy/incomplete inventory; absence must never authorize deletion. */
    val originalPaths: List<String>? = null,
    val workspaceRoot: String? = null,
    val excludedDirectories: List<String> = emptyList(),
)
