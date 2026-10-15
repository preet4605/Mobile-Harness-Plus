package com.jarves.mh.runtime

import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.model.isAttachmentStoragePath
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
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
    ): Unit = PerfStats.time("mh.checkpoint") {
        validateCheckpointTag(checkpointTag)
        val checkpoint = checkpointDir(projectId, checkpointTag)

        // For the legacy default checkpoint, keep the original baseline until every pending file is accepted or undone.
        if (checkpointTag == DEFAULT_CHECKPOINT_TAG) {
            if (checkpointExists(projectId, checkpointTag) && File(checkpoint, "changes.json").isFile) return@time
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
                // One stat without following links. A regular file inside a directory the walk
                // entered (each checked canonical above) cannot resolve outside the workspace.
                val attributes = regularFileAttributes(source) ?: return@forEach
                if (attributes.size() > MAX_CHECKPOINT_COPY_BYTES) {
                    fingerprints[relative] = digest(source, attributes)
                    return@forEach
                }
                val destination = safeWorkspaceFile(backup, relative)
                runCatching {
                    destination.parentFile?.mkdirs()
                    // Copy and hash in one read; the fingerprint is of the bytes the backup holds.
                    val hashedAt = System.currentTimeMillis()
                    val copiedHash = copyAndHash(source, destination)
                    destination.setLastModified(attributes.lastModifiedTime().toMillis())
                    // Record only completed copies, with their actual backup fingerprint.
                    val fingerprint = if (destination.length() > MAX_HASH_FILE_BYTES) digest(destination) else copiedHash
                    fingerprints[relative] = fingerprint
                    backedUpFiles.add(relative)
                    // The source is what was copied when it did not change during the copy.
                    if (regularFileAttributes(source)?.let { statOf(it) } == statOf(attributes)) {
                        rememberHash(source, attributes, fingerprint, hashedAt)
                    }
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

    /** Keep: the current file becomes the baseline. False, never an exception, for a path that leaves the project. */
    fun acceptFileChange(projectId: String, path: String): Boolean = runCatching {
        if (isInternalRuntimePath(path) || path !in readChangedPaths(projectId)) return false
        val current = safeWorkspaceFile(ensureWorkspace(projectId), path)
        val baseline = safeWorkspaceFile(File(checkpointDir(projectId), "project"), path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        removeChangedPath(projectId, path)
        true
    }.getOrDefault(false)

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

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> = PerfStats.time("mh.diff") {
        val backup = File(checkpointDir(projectId), "project")
        paths.map { path ->
            val beforeFile = regularChangeFile(backup, path)
            val afterFile = regularChangeFile(workspace, path)
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

    /** Diffs never read through a final symlink: snapshot() skips links, so a link that replaced a tracked file reads as absent. */
    private fun regularChangeFile(root: File, relative: String): File? {
        if (java.nio.file.Files.isSymbolicLink(File(root, relative).toPath())) return null
        return safeWorkspaceFile(root, relative).takeIf { it.isFile }
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

        // Most edits touch a small region: the unchanged top and bottom are context without a table.
        val prefix = LineDiff.commonPrefix(before, after)
        val suffix = LineDiff.commonSuffix(before, after, prefix)
        val oldEnd = before.size - suffix
        val newEnd = after.size - suffix
        val middleOld = oldEnd - prefix
        val middleNew = newEnd - prefix
        val lcs = Array(middleOld + 1) { IntArray(middleNew + 1) }
        for (oldOffset in middleOld - 1 downTo 0) {
            for (newOffset in middleNew - 1 downTo 0) {
                lcs[oldOffset][newOffset] = if (before[prefix + oldOffset] == after[prefix + newOffset]) {
                    lcs[oldOffset + 1][newOffset + 1] + 1
                } else {
                    maxOf(lcs[oldOffset + 1][newOffset], lcs[oldOffset][newOffset + 1])
                }
            }
        }

        val result = ArrayList<DiffLine>(maxOf(before.size, after.size) + 8)
        for (index in 0 until prefix) {
            result += DiffLine(DiffLineType.CONTEXT, before[index], index + 1, index + 1)
        }
        var oldIndex = prefix
        var newIndex = prefix
        while (oldIndex < oldEnd || newIndex < newEnd) {
            when {
                oldIndex < oldEnd && newIndex < newEnd && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < newEnd && (oldIndex == oldEnd ||
                    lcs[oldIndex - prefix][newIndex - prefix + 1] >= lcs[oldIndex - prefix + 1][newIndex - prefix]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < oldEnd -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        for (offset in 0 until suffix) {
            result += DiffLine(DiffLineType.CONTEXT, before[oldEnd + offset], oldEnd + offset + 1, newEnd + offset + 1)
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
        val common = LineDiff.longestCommonSubsequence(before, after)
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
        // The parent check does not cover the last component: a link there (dangling included)
        // would let a read or write follow it out of the project.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            val target = runCatching { file.toPath().toRealPath() }.getOrNull()
            require(target != null && target.startsWith(rootPath)) { "Workspace path escapes project" }
        }
        return file
    }

    /**
     * Fingerprint of every tracked file. Files whose size and modified time match a recent hash are
     * not read again (see [cachedHash]), so the second walk of a prompt reads only changed files.
     */
    fun snapshot(root: File): Map<String, String> = PerfStats.time("mh.snapshot") {
        if (!root.isDirectory) return@time emptyMap()
        val rootPath = root.canonicalFile.toPath()
        val fingerprints = LinkedHashMap<String, String>()
        root.walkTopDown()
            .onEnter { directory ->
                if (directory == root) return@onEnter true
                val dirName = directory.name.lowercase(Locale.ROOT)
                if (dirName in IGNORED_DIRECTORY_NAMES) return@onEnter false
                val relative = directory.relativeTo(root).invariantSeparatorsPath
                if (isInternalRuntimePath(relative)) return@onEnter false
                if (java.nio.file.Files.isSymbolicLink(directory.toPath())) return@onEnter false
                runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .forEach { file ->
                if (file == root) return@forEach
                // Links and special files are skipped; a regular file in an entered (checked) directory
                // is inside the root.
                val attributes = regularFileAttributes(file) ?: return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (isInternalRuntimePath(relative)) return@forEach
                fingerprints[relative] = digest(file, attributes)
            }
        fingerprints
    }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (normalized.isEmpty()) return false
        val segments = normalized.split('/')
        // Case-insensitive, like the directory walks, so a path the walks skip is never listed as a change.
        return segments.any { it.lowercase(Locale.ROOT) in IGNORED_DIRECTORY_NAMES } ||
            isAttachmentStoragePath(normalized) ||
            normalized == ".claude.json" ||
            normalized.endsWith(".apk.part")
    }

    private fun digest(file: File): String {
        if (file.length() > MAX_HASH_FILE_BYTES) {
            return "${file.length()}_${file.lastModified()}"
        }
        fileHashReads.incrementAndGet()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    /** [digest] of a regular file whose [attributes] were just read, reusing a still-valid cached hash. */
    private fun digest(file: File, attributes: BasicFileAttributes): String {
        if (attributes.size() > MAX_HASH_FILE_BYTES) return digest(file)
        cachedHash(file, attributes)?.let { return it }
        val hashedAt = System.currentTimeMillis()
        val hash = digest(file)
        if (regularFileAttributes(file)?.let { statOf(it) } == statOf(attributes)) rememberHash(file, attributes, hash, hashedAt)
        return hash
    }

    /** Copies [source] over [destination] and returns the SHA-256 hex of the bytes copied. */
    private fun copyAndHash(source: File, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return hex(digest.digest())
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

        /** Never checkpointed, hashed or offered for undo: tool state and build output that tools regenerate. */
        val IGNORED_DIRECTORY_NAMES = setOf(
            ".git", ".claude", ".gradle", ".idea", ".next", ".cache",
            "node_modules", ".venv", "venv", "__pycache__", "build",
            "dist", ".gemini",
            // Build output and caches of Rust/Maven, Flutter, CocoaPods, Kotlin, Turborepo,
            // Parcel, pytest, mypy, coverage tools and Xcode.
            "target", ".dart_tool", "pods", ".kotlin", ".turbo", ".parcel-cache",
            ".pytest_cache", ".mypy_cache", "coverage", "deriveddata",
        )

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
        private const val HASH_BUFFER_BYTES = 64 * 1024

        /**
         * Hashes of workspace files by absolute path, shared by every bridge and the supervisor (each
         * holds its own [WorkspaceCheckpoints]). An entry is used only while the file's size and
         * modified time are unchanged AND the file was last modified at least [RACY_WINDOW_MS] before
         * it was hashed, the rule git uses for "racily clean" files: an edit that lands within the
         * filesystem's timestamp resolution of the hash can keep the same size and time, so such a
         * file is always read again.
         */
        private val hashCache = ConcurrentHashMap<String, CachedHash>()
        private const val RACY_WINDOW_MS = 2_000L
        /** Whole-file hash reads (not copies), for tests. */
        internal val fileHashReads = java.util.concurrent.atomic.AtomicLong()
        private const val MAX_CACHED_HASHES = 200_000

        private data class FileStat(val size: Long, val modifiedMillis: Long)
        private class CachedHash(val stat: FileStat, val hash: String)

        private fun statOf(attributes: BasicFileAttributes) =
            FileStat(attributes.size(), attributes.lastModifiedTime().toMillis())

        /** Attributes of [file] when it is a regular file, without following a final link; null otherwise. */
        internal fun regularFileAttributes(file: File): BasicFileAttributes? = runCatching {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        }.getOrNull()?.takeIf { it.isRegularFile }

        private fun cachedHash(file: File, attributes: BasicFileAttributes): String? =
            hashCache[file.absolutePath]?.takeIf { it.stat == statOf(attributes) }?.hash

        private fun rememberHash(file: File, attributes: BasicFileAttributes, hash: String, hashedAtMillis: Long) {
            val stat = statOf(attributes)
            if (stat.modifiedMillis > hashedAtMillis - RACY_WINDOW_MS) {
                hashCache.remove(file.absolutePath)
                return
            }
            if (hashCache.size >= MAX_CACHED_HASHES) hashCache.clear()
            hashCache[file.absolutePath] = CachedHash(stat, hash)
        }

        /** Lowercase hex, as `"%02x".format` per byte gave, without a formatter per byte. */
        internal fun hex(bytes: ByteArray): String {
            val chars = CharArray(bytes.size * 2)
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                chars[index * 2] = HEX_DIGITS[value ushr 4]
                chars[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
            }
            return String(chars)
        }

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
