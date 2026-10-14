package com.jarves.mh.runtime

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Verified ARM64 search tool from the already-pinned Codex archive. No network or user PATH replacement. */
internal object RuntimeSearchTool {
    private const val BYTES = 4_506_992L
    private const val SHA256 = "e36d0eb52e70696bdf1781392722e05a21bb91d3b7b762ef5ec20e5df2ec687b"
    private var verified: Triple<String, Long, Long>? = null
    @Synchronized fun ensure(rootfs: File, asset: () -> InputStream): Boolean = runCatching {
        val destination = File(rootfs, "opt/pocket/tools/rg")
        check(destination.parentFile!!.canonicalFile.toPath().startsWith(rootfs.canonicalFile.toPath()))
        fun signature() = Triple(destination.canonicalPath, destination.length(), destination.lastModified())
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                var copied = 0L
                while (true) { val count = input.read(buffer); if (count < 0) break; copied += count; check(copied <= BYTES); hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (destination.isFile && destination.length() == BYTES && destination.canExecute() &&
            (verified == signature() || digest(destination) == SHA256)) {
            verified = signature(); return true
        }
        destination.parentFile!!.mkdirs()
        val staging = File(destination.parentFile, ".rg-${UUID.randomUUID()}")
        try {
            GZIPInputStream(asset()).use { input -> staging.outputStream().use { output ->
                val buffer = ByteArray(65_536); var copied = 0L
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    copied += count; check(copied <= BYTES); output.write(buffer, 0, count)
                }
            } }
            check(staging.length() == BYTES && digest(staging) == SHA256)
            check(staging.setExecutable(true, false) && staging.renameTo(destination))
            verified = signature()
        } finally { staging.delete() }
        true
    }.getOrDefault(false)
}
