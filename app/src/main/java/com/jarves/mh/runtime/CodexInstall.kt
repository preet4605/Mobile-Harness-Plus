package com.jarves.mh.runtime

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * The one Codex release this app installs. The JSONL contract in [CodexJsonlParser] was verified
 * against exactly this version, so it is pinned rather than auto-updated. The values come from the
 * official npm registry metadata for `@openai/codex@0.161.0-linux-arm64` (dist.integrity); the
 * SHA-512 below was re-computed over the downloaded tarball before being pinned.
 */
internal object CodexInstallSpec {
    const val VERSION = "0.161.0"
    const val ARCHIVE_URL = "https://registry.npmjs.org/@openai/codex/-/codex-0.161.0-linux-arm64.tgz"
    const val ARCHIVE_SHA512 =
        "481bb49f80b53dbcbdac6294340d36c87120fd271b1028224c888a88a85b6168945aa18f575858d01afb8e8127b5bcf4772373496a8d3c3f2e3aac5c3abcbf16"
    const val ARCHIVE_BYTES = 157_136_568L

    /** Static musl build: runs under PRoot without any guest libraries. */
    const val BINARY_ENTRY = "package/vendor/aarch64-unknown-linux-musl/bin/codex"
    const val BINARY_BYTES = 252_899_528L

    /** Download plus extracted binary, with headroom so a nearly full disk fails before any download. */
    const val REQUIRED_FREE_BYTES = ARCHIVE_BYTES + BINARY_BYTES + 256L * 1024L * 1024L
}

internal object CodexArchive {
    /**
     * Streams [archive] (a gzip tar) and writes only [entryName] to [destination]. The entry size
     * must equal [expectedBytes]; any mismatch, missing entry or I/O failure removes the partial
     * [destination] and throws.
     */
    fun extractEntry(archive: File, entryName: String, destination: File, expectedBytes: Long) {
        destination.parentFile?.mkdirs()
        try {
            var found = false
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./") == entryName) {
                        require(entry.size == expectedBytes) { "The Codex archive entry has an unexpected size." }
                        FileOutputStream(destination).use { output -> tar.copyTo(output) }
                        found = true
                        break
                    }
                    entry = tar.nextEntry
                }
            }
            check(found) { "The Codex archive did not contain the expected binary." }
            check(destination.length() == expectedBytes) { "The Codex binary was not extracted completely." }
        } catch (e: Throwable) {
            runCatching { destination.delete() }
            throw e
        }
    }

    /** False only when the free space is known and too small; an unreadable value does not block. */
    fun hasFreeSpace(directory: File, requiredBytes: Long): Boolean =
        runCatching { directory.usableSpace >= requiredBytes }.getOrDefault(true)
}
