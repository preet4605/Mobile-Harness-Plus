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

    /**
     * Helper process Codex starts, from the directory of its own executable, whenever a model's
     * catalog entry selects "code mode" (the ChatGPT account catalog does for some models). Without
     * it every tool call of such a model fails with "failed to spawn code-mode host". Also a static
     * musl build.
     */
    const val HOST_ENTRY = "package/vendor/aarch64-unknown-linux-musl/bin/codex-code-mode-host"
    const val HOST_BYTES = 66_790_400L

    /** Download plus extracted files, with headroom so a nearly full disk fails before any download. */
    const val REQUIRED_FREE_BYTES = ARCHIVE_BYTES + BINARY_BYTES + HOST_BYTES + 256L * 1024L * 1024L

    /** Space needed when the main binary is already in place and only the helper is missing. */
    const val REQUIRED_FREE_BYTES_HELPER_ONLY = ARCHIVE_BYTES + HOST_BYTES + 128L * 1024L * 1024L

    fun requiredFreeBytes(includeBinary: Boolean): Long =
        if (includeBinary) REQUIRED_FREE_BYTES else REQUIRED_FREE_BYTES_HELPER_ONLY
}

/** What is on disk for Codex, judged from the guest root file system and the version marker. */
internal object CodexInstallLayout {
    /** Guest paths without the leading slash, relative to the root file system. */
    private fun relative(guestPath: String) = guestPath.removePrefix("/")

    fun binary(rootfs: File) = File(rootfs, relative(CodexLaunchBuilder.CODEX_GUEST_PATH))
    fun helper(rootfs: File) = File(rootfs, relative(CodexLaunchBuilder.CODEX_CODE_MODE_HOST_GUEST_PATH))

    /** The pinned main binary is installed and marked; it is kept when only the helper is missing. */
    fun binaryUsable(rootfs: File, marker: String?): Boolean =
        marker?.trim() == CodexInstallSpec.VERSION && binary(rootfs).canExecute()

    /** Everything Codex needs: main binary, helper and a version marker. */
    fun isComplete(rootfs: File, marker: String?): Boolean =
        !marker.isNullOrBlank() && binary(rootfs).canExecute() && helper(rootfs).canExecute()
}

/** One tar member to pull out of a Codex archive. */
internal data class CodexArchiveTarget(val entryName: String, val destination: File, val expectedBytes: Long)

internal object CodexArchive {
    /**
     * Streams [archive] (a gzip tar) and writes only [entryName] to [destination]. The entry size
     * must equal [expectedBytes]; any mismatch, missing entry or I/O failure removes the partial
     * [destination] and throws.
     */
    fun extractEntry(archive: File, entryName: String, destination: File, expectedBytes: Long) =
        extractEntries(archive, listOf(CodexArchiveTarget(entryName, destination, expectedBytes)))

    /**
     * One pass over [archive] writing every target. Each entry size must equal its expected size; a
     * missing entry, a size mismatch or any I/O failure removes every partial destination and throws.
     */
    fun extractEntries(archive: File, targets: List<CodexArchiveTarget>) {
        require(targets.isNotEmpty()) { "Nothing to extract." }
        targets.forEach { it.destination.parentFile?.mkdirs() }
        try {
            val remaining = targets.toMutableList()
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
                var entry = tar.nextEntry
                while (entry != null && remaining.isNotEmpty()) {
                    val name = entry.name.removePrefix("./")
                    val target = if (entry.isFile) remaining.firstOrNull { it.entryName == name } else null
                    if (target != null) {
                        require(entry.size == target.expectedBytes) { "The Codex archive entry has an unexpected size." }
                        FileOutputStream(target.destination).use { output -> tar.copyTo(output) }
                        check(target.destination.length() == target.expectedBytes) {
                            "The Codex file was not extracted completely."
                        }
                        remaining.remove(target)
                    }
                    entry = tar.nextEntry
                }
            }
            check(remaining.isEmpty()) { "The Codex archive did not contain the expected files." }
        } catch (e: Throwable) {
            targets.forEach { runCatching { it.destination.delete() } }
            throw e
        }
    }

    /** False only when the free space is known and too small; an unreadable value does not block. */
    fun hasFreeSpace(directory: File, requiredBytes: Long): Boolean =
        runCatching { directory.usableSpace >= requiredBytes }.getOrDefault(true)
}
