package com.jarves.mh.model

/**
 * Chat attachments: any file type up to [MAX_ATTACHMENT_BYTES], stored under the project's
 * `attachments/<chatId>/` folder. Pure Kotlin so the rules are unit-testable without Android.
 */
const val ATTACHMENTS_DIRECTORY = "attachments"
const val MAX_ATTACHMENT_MB = 100L
const val MAX_ATTACHMENT_BYTES = MAX_ATTACHMENT_MB * 1024L * 1024L

private const val MAX_STORED_NAME_BYTES = 200
private const val MAX_KEPT_EXTENSION_BYTES = 32
private const val OCTET_STREAM = "application/octet-stream"

private val MIME_TYPE_SHAPE = Regex("[a-z0-9][a-z0-9.+-]*/[a-z0-9][a-z0-9.+-]*")
private val CHAT_FOLDER_SHAPE = Regex("^$ATTACHMENTS_DIRECTORY/(default|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(/|$)")

enum class AttachmentKind(val label: String) { IMAGE("image"), TEXT("text"), ARCHIVE("archive"), BINARY("binary") }

private val ARCHIVE_EXTENSIONS = setOf("zip", "tar", "gz", "tgz", "bz2", "xz", "zst", "7z", "rar", "apk", "aab", "jar", "aar")
private val ARCHIVE_MIME_TYPES = setOf(
    "application/zip", "application/x-tar", "application/gzip", "application/x-gzip", "application/x-7z-compressed",
    "application/x-rar-compressed", "application/x-bzip2", "application/x-xz", "application/vnd.android.package-archive",
)
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonl", "csv", "tsv", "xml", "yaml", "yml", "log",
    "kt", "kts", "java", "py", "js", "mjs", "cjs", "ts", "tsx", "jsx", "html", "htm",
    "css", "scss", "sass", "less", "c", "cc", "cpp", "h", "hpp", "sh", "bash", "zsh",
    "gradle", "properties", "toml", "ini", "conf", "sql",
)
private val TEXT_MIME_TYPES = setOf(
    "application/json", "application/xml", "application/javascript", "application/x-sh",
    "application/sql", "application/x-yaml", "application/toml",
)

/** Splits a file name into stem and extension (with its dot). A leading dot alone is not an extension. */
fun splitAttachmentName(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) name to "" else name.substring(0, dot) to name.substring(dot)
}

/** Lower-cased extension without the dot, or "" when the name has none. */
fun attachmentExtension(name: String): String = splitAttachmentName(name).second.removePrefix(".").lowercase()

/**
 * Keeps the name as the user knows it, including unicode, spaces and unusual extensions. Only path
 * separators and control characters change. Returns "" when nothing usable is left.
 */
fun cleanAttachmentName(raw: String?): String {
    val cleaned = buildString {
        raw.orEmpty().forEach { ch -> append(if (ch == '/' || ch == '\\' || ch.isISOControl()) '_' else ch) }
    }.trim()
    return if (cleaned == "." || cleaned == "..") "" else cleaned
}

/** Name on disk: the original name, shortened only when it would exceed the file-name limit. */
fun storedAttachmentName(displayName: String): String {
    if (utf8Length(displayName) <= MAX_STORED_NAME_BYTES) return displayName
    val (stem, extension) = splitAttachmentName(displayName)
    val keptExtension = if (utf8Length(extension) <= MAX_KEPT_EXTENSION_BYTES) extension else ""
    return truncateUtf8(stem, MAX_STORED_NAME_BYTES - utf8Length(keptExtension)) + keptExtension
}

/**
 * Uses the provider's type when it is specific. When it is missing or generic, [lookup] infers one
 * from [extension] (without the dot). Anything unusable becomes application/octet-stream.
 */
fun resolveAttachmentMimeType(reported: String?, extension: String, lookup: (String) -> String?): String {
    val declared = reported?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    if (declared != OCTET_STREAM && MIME_TYPE_SHAPE.matches(declared)) return declared
    val inferred = extension.lowercase().takeIf { it.isNotBlank() }?.let(lookup)?.trim()?.lowercase().orEmpty()
    return if (MIME_TYPE_SHAPE.matches(inferred)) inferred else OCTET_STREAM
}

fun attachmentKindOf(mimeType: String, extension: String): AttachmentKind {
    val mime = mimeType.lowercase()
    val ext = extension.lowercase()
    return when {
        mime.startsWith("image/") -> AttachmentKind.IMAGE
        mime in ARCHIVE_MIME_TYPES || ext in ARCHIVE_EXTENSIONS -> AttachmentKind.ARCHIVE
        mime.startsWith("text/") || mime in TEXT_MIME_TYPES || mime.endsWith("+json") || mime.endsWith("+xml") ||
            ext in TEXT_EXTENSIONS -> AttachmentKind.TEXT
        else -> AttachmentKind.BINARY
    }
}

fun attachmentTooLargeMessage(name: String): String = "$name is larger than $MAX_ATTACHMENT_MB MB"

/** True for `attachments/<chatId>` and everything under it. A project's own `attachments/` folder is not matched. */
fun isAttachmentStoragePath(relativePath: String): Boolean =
    CHAT_FOLDER_SHAPE.containsMatchIn(relativePath.replace('\\', '/').trimStart('/'))

/**
 * The `<attached_files>` block appended to the prompt. Each file is two lines: a description, then
 * `  path: <guest path>`, so the path can be read back exactly even when the name holds odd characters.
 */
object AttachmentPrompt {
    private const val OPEN = "<attached_files>"
    private const val CLOSE = "</attached_files>"
    private const val PATH_PREFIX = "  path: "
    private val HEADER = Regex("""- (.*) \(([^()]*), (\d+) bytes, (image|text|archive|binary)\)""")

    data class AttachedFile(val displayName: String, val path: String, val mimeType: String, val kind: AttachmentKind)

    fun render(attachments: List<ChatAttachment>, guestRoot: String): String = buildString {
        appendLine(OPEN)
        val kinds = attachments.map { attachmentKindOf(it.mimeType, attachmentExtension(it.displayName)) }
        attachments.zip(kinds).forEach { (attachment, kind) ->
            appendLine("- ${attachment.displayName} (${attachment.mimeType}, ${attachment.sizeBytes} bytes, ${kind.label})")
            appendLine("$PATH_PREFIX$guestRoot/${attachment.relativePath}")
        }
        appendLine("These files were explicitly attached by the user. Inspect them only as needed for the request.")
        if (kinds.any { it == AttachmentKind.ARCHIVE || it == AttachmentKind.BINARY }) {
            appendLine(
                "Archive and binary files are not text. Inspect them with shell tools " +
                    "(for example file, unzip -l, tar -tf, head -c, xxd) instead of reading them whole.",
            )
        }
        appendLine(CLOSE)
    }

    /** Reads the last `<attached_files>` block of [prompt]. Text the user typed earlier cannot shadow it. */
    fun parse(prompt: String): List<AttachedFile> {
        val start = prompt.lastIndexOf(OPEN)
        if (start < 0) return emptyList()
        val end = prompt.indexOf(CLOSE, start)
        if (end < 0) return emptyList()
        val lines = prompt.substring(start + OPEN.length, end).lines()
        return lines.indices.mapNotNull { index ->
            val match = HEADER.matchEntire(lines[index]) ?: return@mapNotNull null
            val path = lines.getOrNull(index + 1)
                ?.takeIf { it.startsWith(PATH_PREFIX) }
                ?.removePrefix(PATH_PREFIX)
                ?: return@mapNotNull null
            val kind = AttachmentKind.values().firstOrNull { it.label == match.groupValues[4] } ?: return@mapNotNull null
            AttachedFile(match.groupValues[1], path, match.groupValues[2], kind)
        }
    }
}

private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

private fun truncateUtf8(text: String, maxBytes: Int): String {
    var bytes = 0
    var end = 0
    while (end < text.length) {
        val codePoint = text.codePointAt(end)
        val size = when {
            codePoint < 0x80 -> 1
            codePoint < 0x800 -> 2
            codePoint < 0x10000 -> 3
            else -> 4
        }
        if (bytes + size > maxBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return text.substring(0, end)
}
