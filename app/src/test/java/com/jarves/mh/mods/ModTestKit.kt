package com.jarves.mh.mods

import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Builds mod folders and `.mhmod` archives for tests. */
object ModTestKit {
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A manifest with [extra] merged in and `integrity` computed for [files]. */
    fun manifest(files: Map<String, String> = emptyMap(), extra: JSONObject.() -> Unit = {}): String {
        val json = JSONObject()
            .put("id", "dev.test.sample")
            .put("name", "Sample")
            .put("version", "1.0.0")
            .put("apiVersion", 2)
        json.extra()
        if (files.isNotEmpty() && !json.has("integrity")) {
            json.put("integrity", JSONObject().apply { files.forEach { (path, text) -> put(path, sha256(text.toByteArray())) } })
        }
        return json.toString(2)
    }

    fun writeDir(dir: File, manifest: String, files: Map<String, String> = emptyMap()): File {
        dir.mkdirs()
        File(dir, "mod.json").writeText(manifest)
        files.forEach { (path, text) -> File(dir, path).apply { parentFile?.mkdirs(); writeText(text) } }
        return dir
    }

    /** A ZIP with the given entries; [links] become Unix symlink entries pointing at their value. */
    fun zip(entries: Map<String, ByteArray>, links: Map<String, String> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipArchiveOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putArchiveEntry(ZipArchiveEntry(name))
                zip.write(bytes)
                zip.closeArchiveEntry()
            }
            links.forEach { (name, target) ->
                val entry = ZipArchiveEntry(name).apply { unixMode = UnixStat.LINK_FLAG or 0b111_111_111 }
                zip.putArchiveEntry(entry)
                zip.write(target.toByteArray())
                zip.closeArchiveEntry()
            }
        }
        return out.toByteArray()
    }

    fun modZip(manifest: String, files: Map<String, String> = emptyMap(), prefix: String = ""): ByteArray =
        zip(mapOf("${prefix}mod.json" to manifest.toByteArray()) + files.map { (p, t) -> "$prefix$p" to t.toByteArray() })
}
