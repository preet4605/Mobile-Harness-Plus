package com.jarves.mh.mods

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * `$.fs` (spec v2 §6.1): a mod reads the open project's files within its `files.read` scope (the
 * whole project when the permission names no paths) and writes whole files within its required
 * `files.write` scope. Agent, git and app settings files stay closed ([ModPaths]); links are never
 * followed.
 */
internal object ModFiles {
    const val MAX_READ_BYTES = 1024 * 1024
    const val MAX_WRITE_BYTES = 1024 * 1024
    const val MAX_LIST = 500

    fun read(root: File, path: String, scope: List<String>): String {
        val file = resolve(root, path, scope, write = false)
        require(file.isFile) { "$path is not a file" }
        require(file.length() <= MAX_READ_BYTES) { "$path is larger than 1 MB" }
        return file.readBytes().toString(Charsets.UTF_8)
    }

    fun exists(root: File, path: String, scope: List<String>): Boolean =
        Files.exists(resolve(root, path, scope, write = false).toPath(), LinkOption.NOFOLLOW_LINKS)

    /** `{ path, dir, size, modified }`, or null when nothing is there. */
    fun stat(root: File, path: String, scope: List<String>): JSONObject? {
        val file = resolve(root, path, scope, write = false)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        return entry(path, file)
    }

    /** The entries of the folder [path] ("" for the project root) the scope lets the mod see, by name. */
    fun list(root: File, path: String, scope: List<String>): JSONArray {
        val dir = path.trim().trim('/')
        val folder = if (dir.isEmpty()) root.canonicalFile else resolve(root, dir, scope, write = false, folder = true)
        require(folder.isDirectory) { "$path is not a folder" }
        val children = folder.listFiles().orEmpty()
            .filter { !Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.name.lowercase() }
        val out = JSONArray()
        for (child in children) {
            if (out.length() >= MAX_LIST) break
            val relative = if (dir.isEmpty()) child.name else "$dir/${child.name}"
            if (ModPaths.alwaysDeniedReason(relative) != null || ModPaths.alwaysDeniedReason("$relative/x") != null) continue
            val visible = if (child.isDirectory) mayEnter(relative, scope) else inScope(relative, scope)
            if (visible) out.put(entry(relative, child))
        }
        return out
    }

    /** Replaces (or creates) the file at [path] with [text], atomically; returns the bytes written. */
    fun write(root: File, path: String, text: String, scope: List<String>): Int {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) { "That is larger than 1 MB" }
        val file = resolve(root, path, scope, write = true)
        require(!file.exists() || file.isFile) { "$path is not a file" }
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, ".${file.name}.mod-${UUID.randomUUID()}")
        try {
            staging.writeBytes(bytes)
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staging.delete()
        }
        return bytes.size
    }

    private fun entry(path: String, file: File) = JSONObject()
        .put("path", path)
        .put("name", file.name)
        .put("dir", file.isDirectory)
        .put("size", if (file.isFile) file.length() else 0)
        .put("modified", file.lastModified())

    private fun inScope(path: String, scope: List<String>) = scope.isEmpty() || scope.any { ModPaths.matchesGlob(it, path) }

    /** Whether a folder may hold files the scope covers. */
    private fun mayEnter(dir: String, scope: List<String>) = scope.isEmpty() || scope.any { glob ->
        val fixed = glob.substringBefore('*').substringBefore('?')
        glob.startsWith("**") || ModPaths.matchesGlob(glob, dir) || fixed.startsWith("$dir/") ||
            (fixed.contains('/') && dir.startsWith(fixed.substringBeforeLast('/') + "/"))
    }

    private fun resolve(root: File, path: String, scope: List<String>, write: Boolean, folder: Boolean = false): File {
        val relative = path.trim().trim('/')
        require(ModManifestParser.isSafeRelativePath(relative)) { "\"$path\" is not a path inside the project" }
        val label = if (write) "files.write" else "files.read"
        require(if (folder) mayEnter(relative, scope) else inScope(relative, scope)) { "$relative is outside the mod's $label scope" }
        return ModPaths.resolveForWrite(root, relative)
    }
}
