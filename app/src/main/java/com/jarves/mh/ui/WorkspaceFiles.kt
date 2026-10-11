package com.jarves.mh.ui

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Files larger than this are previewed (truncated) and stay read-only. */
internal const val MAX_EDITABLE_FILE_BYTES = 512_000

internal const val WORKSPACE_BUSY_MESSAGE = "Wait for the running task, terminal command, build or export to finish"

internal const val PROTECTED_ENTRY_MESSAGE = "This item is protected or outside the project"

/** Largest single file the Files tab uploads. */
internal const val MAX_UPLOAD_BYTES = 100L * 1024L * 1024L

/** The project-relative path of [name] inside the folder at [parentPath] ("" for the root). */
internal fun workspaceChildPath(parentPath: String, name: String): String =
    parentPath.trim('/').let { if (it.isEmpty()) name else "$it/$name" }

/**
 * True while a task, terminal command, build, export or another file operation could be reading or
 * writing the project, so files are not changed by hand.
 */
internal fun workspaceFilesBusy(state: AppUiState): Boolean =
    state.isRunning || state.projectTerminalRunning || state.androidBuildRunning ||
        state.projectExportRunning || state.fileOperationRunning

/**
 * Why [name] can't be used for a new or renamed file or folder, or null when it can. Names are
 * one path segment: no separators, NUL or control characters, not "." or "..", at most 255 bytes.
 * Leading or trailing spaces are refused rather than silently trimmed.
 */
internal fun entryNameProblem(name: String): String? = when {
    name.isBlank() -> "Enter a name"
    name != name.trim() -> "Names can't start or end with a space"
    name == "." || name == ".." -> "\"$name\" can't be used as a name"
    name.any { it == '/' || it == '\\' } -> "Names can't contain / or \\"
    name.any { it.isISOControl() } -> "Names can't contain control characters"
    name.toByteArray(Charsets.UTF_8).size > 255 -> "That name is too long"
    else -> null
}

/** [name] in [folder], or "stem-2.ext", "stem-3.ext"… when something (even a dangling link) already has that name. */
internal fun uniqueChild(folder: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val (stem, extension) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
    var candidate = File(folder, name)
    var suffix = 2
    while (Files.exists(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) candidate = File(folder, "$stem-${suffix++}$extension")
    return candidate
}

/**
 * Deletes [target] and everything below it without following symbolic links: a link is removed
 * itself, never what it points to, so nothing outside the folder can be deleted.
 */
internal fun deleteWorkspaceTree(target: File) {
    val path = target.toPath()
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.delete(path)
        return
    }
    Files.walkFileTree(
        path,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

/** Why an open file is shown read-only. */
enum class FileReadOnlyReason { TOO_LARGE, BINARY, INVALID_UTF8, UNSAFE, READ_ERROR }

/** The edit session of the open file. [dirty] is true when [draft] differs from the file as read. */
data class FileEditState(
    val draft: String,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    /** The file changed on disk since it was read, so saving would overwrite newer changes. */
    val conflict: Boolean = false,
)

/** What the viewer shows for a file's bytes, and why it can't be edited when it can't. */
internal data class DecodedFile(val text: String?, val readOnlyReason: FileReadOnlyReason?)

/**
 * Decodes file bytes for the viewer. NUL bytes mean binary (nothing shown). Text that is not valid
 * UTF-8 is shown with replacement characters but can't be edited, so saving never rewrites bytes
 * the editor could not represent. [truncated] means only the first part was read.
 */
internal fun decodeFileForViewer(bytes: ByteArray, truncated: Boolean): DecodedFile {
    if (bytes.any { it == 0.toByte() }) return DecodedFile(null, FileReadOnlyReason.BINARY)
    val strict = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
    return when {
        truncated -> DecodedFile(strict ?: String(bytes, Charsets.UTF_8), FileReadOnlyReason.TOO_LARGE)
        strict == null -> DecodedFile(String(bytes, Charsets.UTF_8), FileReadOnlyReason.INVALID_UTF8)
        else -> DecodedFile(strict, null)
    }
}

/** The text the editor works on: line breaks as "\n" only. */
internal fun editableText(original: String): String = original.replace("\r\n", "\n").replace('\r', '\n')

/**
 * Turns the editor's [draft] back into file text in the style of [original]: CRLF line breaks when
 * the original mostly used them, and the original's final newline kept when the draft lost it.
 */
internal fun textForSave(draft: String, original: String): String {
    var text = editableText(draft)
    val hadTrailingNewline = original.endsWith("\n") || original.endsWith("\r")
    if (hadTrailingNewline && text.isNotEmpty() && !text.endsWith("\n")) text += "\n"
    val crlf = original.windowed(2).count { it == "\r\n" }
    val lf = original.count { it == '\n' }
    val useCrlf = crlf > 0 && crlf * 2 >= lf
    return if (useCrlf) text.replace("\n", "\r\n") else text
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** Reads up to [limit] bytes. Returns the bytes and whether the file held more. */
internal fun readFilePrefix(file: File, limit: Int): Pair<ByteArray, Boolean> {
    file.inputStream().use { input ->
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = input.read(buffer, filled, limit - filled)
            if (read < 0) break
            filled += read
        }
        val more = filled == limit && input.read() >= 0
        return (if (filled == limit) buffer else buffer.copyOf(filled)) to more
    }
}

/**
 * Replaces [target]'s contents with [bytes]: written to a temporary file in the same folder first,
 * then renamed over the target, so a crash leaves either the old or the new file, never half of one.
 */
internal fun writeFileAtomically(target: File, bytes: ByteArray) {
    val folder = target.parentFile ?: error("The file has no folder")
    val temporary = File(folder, ".${target.name}.${UUID.randomUUID().toString().take(8)}.tmp")
    try {
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (target.canExecute()) temporary.setExecutable(true, false)
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}

/** Hidden from the Files tab and never changed by hand: the root `.git` and the agent's own metadata. */
internal fun isProtectedWorkspacePath(relativePath: String): Boolean {
    val clean = relativePath.trim('/')
    return clean == ".git" || clean.startsWith(".git/") ||
        clean == ".claude" || clean == ".claude.json" || clean.startsWith(".claude/")
}

/**
 * The one guard for every manual file operation. Resolves [relativePath] (separated by "/") below
 * [root] and returns null unless the result stays inside the project: empty, "." and ".." segments,
 * backslashes, NUL, protected paths, and a symlink anywhere along the way are all refused. The path
 * itself need not exist (for a new file). The root itself is returned only when [allowRoot] is set.
 */
internal fun resolveWorkspacePath(root: File, relativePath: String, allowRoot: Boolean = false): File? {
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return null
    if (relativePath.any { it == '\\' || it == '\u0000' }) return null
    val clean = relativePath.trim('/')
    if (clean.isEmpty()) return canonicalRoot.takeIf { allowRoot && it.isDirectory }
    if (isProtectedWorkspacePath(clean)) return null
    var cursor = canonicalRoot
    for (segment in clean.split('/')) {
        if (segment.isEmpty() || segment == "." || segment == "..") return null
        cursor = File(cursor, segment)
        if (Files.isSymbolicLink(cursor.toPath())) return null
    }
    val canonical = runCatching { cursor.canonicalFile }.getOrNull() ?: return null
    return canonical.takeIf { it != canonicalRoot && it.toPath().startsWith(canonicalRoot.toPath()) }
}
