package com.jarves.mh.mods

import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A mod's saved data (`$.store`, spec v2 §4.3): one JSON object in the mod's data folder, which
 * survives updates, restarts and process death. Each write replaces the file atomically (temp
 * file, sync, rename). Values are plain JSON, and the whole store is capped at [MAX_BYTES].
 * Not thread-safe: the host calls it from one thread.
 */
class ModStore(private val dir: File) {
    private val file = File(dir, "store.json")
    private var cache: JSONObject? = null

    fun get(key: String): Any? = load().opt(checkKey(key))

    fun keys(): List<String> = load().keys().asSequence().sorted().toList()

    /** Throws [IllegalArgumentException] when the store would pass [MAX_BYTES]. */
    fun set(key: String, value: Any?) {
        checkKey(key)
        if (value == null || value == JSONObject.NULL) return delete(key)
        val next = JSONObject(load().toString()).put(key, value)
        val text = next.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Saved data would pass 1 MB" }
        write(text)
        cache = next
    }

    fun delete(key: String) {
        val current = load()
        if (!current.has(checkKey(key))) return
        val next = JSONObject(current.toString()).apply { remove(key) }
        write(next.toString())
        cache = next
    }

    private fun load(): JSONObject = cache ?: run {
        val loaded = if (file.isFile) {
            runCatching { JSONTokener(file.readText()).nextValue() as? JSONObject }.getOrNull() ?: JSONObject()
        } else JSONObject()
        loaded.also { cache = it }
    }

    private fun write(text: String) {
        dir.mkdirs()
        val temp = File(dir, "store.json.tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun checkKey(key: String): String {
        require(key.isNotEmpty() && key.length <= MAX_KEY) { "A saved-data key must be 1 to $MAX_KEY characters" }
        return key
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_KEY = 200
    }
}
