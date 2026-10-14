package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

/** Bounded, no-follow resource reads shared by import and guest publication. */
internal object SkillFiles {
    const val MAX_FILES = 256
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 128 * 1024
    data class Resource(val path: String, val content: ByteArray?, val executable: Boolean)

    fun contained(root: File, relative: String): File? = runCatching {
        require(relative.isNotBlank() && !File(relative).isAbsolute && '\\' !in relative)
        require(relative.split('/').none { it == ".." || it == "." })
        File(root, relative).takeIf { it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }
    }.getOrNull()

    fun readText(file: File): String = read(file, MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)

    private fun read(file: File, limit: Int): ByteArray {
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() <= limit) { "Unsafe or oversized skill resource" }
        return Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= limit) { "Skill resource is too large" }
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= limit) { "Skill resource is too large" }
            bytes
        }
    }

    fun resources(source: File): List<Resource> {
        require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "Unsafe skill folder" }
        val root = source.canonicalFile.toPath()
        val result = mutableListOf<Resource>()
        var count = 0
        var bytes = 0
        source.walkTopDown().forEach { file ->
            require(++count <= MAX_FILES && !Files.isSymbolicLink(file.toPath())) { "Skill contains too many files or symbolic links" }
            require(file.canonicalFile.toPath().startsWith(root)) { "Skill resource escapes its folder" }
            if (file == source) return@forEach
            val name = source.toPath().relativize(file.toPath()).toString()
            val content = if (file.isFile) read(file, MAX_BYTES - bytes).also { bytes += it.size }
                else { require(file.isDirectory) { "Unsupported skill resource" }; null }
            result += Resource(name, content, file.canExecute())
        }
        require(result.any { it.path == "SKILL.md" && it.content != null }) { "Skill manifest is missing" }
        return result
    }

    fun copy(source: File, target: File) {
        val resources = resources(source) // Validate all sources before modifying the destination.
        require(!target.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) { "Skill destination is inside its source" }
        fun destination(name: String): File {
            val file = contained(target, name) ?: error("Skill destination escapes its folder")
            var parent: File? = file
            while (parent != null) {
                require(!Files.isSymbolicLink(parent.toPath())) { "Skill destination contains a symbolic link" }
                if (parent == target) break
                parent = parent.parentFile
            }
            return file
        }
        resources.forEach { destination(it.path) }
        require(target.isDirectory || target.mkdirs()) { "Could not create skill folder" }
        resources.forEach { resource ->
            val file = destination(resource.path)
            if (resource.content == null) {
                require(file.isDirectory || file.mkdirs())
            } else {
                require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
                val temporary = File(file.parentFile, ".skill-copy-${UUID.randomUUID()}")
                try {
                    Files.write(temporary.toPath(), resource.content, java.nio.file.StandardOpenOption.CREATE_NEW)
                    require(temporary.setExecutable(resource.executable, false)) { "Could not set skill permissions" }
                    Files.move(temporary.toPath(), destination(resource.path).toPath(), REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary.toPath()) }
            }
        }
    }
}
