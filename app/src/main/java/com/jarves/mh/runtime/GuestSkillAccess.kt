package com.jarves.mh.runtime

import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID

/** Keeps app-side source paths separate from paths usable by a launched guest. */
internal class GuestSkillAccess(private val appFiles: File) {
    companion object {
        private const val MAX_FILES = 256
        private const val MAX_SKILL_BYTES = 8 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 64L * 1024 * 1024
        fun linkedDirectory(appFiles: File, workspace: File): File =
            File(appFiles, "runtime-skills/${hash(workspace.canonicalPath.toByteArray())}")
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun prepare(skills: List<SkillInfo>, workspace: File, rootfs: File?, guestWorkspace: String): List<SkillInfo> =
        skills.map { skill ->
            val guestPath = if (!skill.isEnabled || skill.isMissingSource) null else runCatching {
                val source = File(skill.filePath)
                check(source.isFile && source.name == "SKILL.md")
                when (skill.source) {
                    SkillSource.PROJECT -> mapped(source, workspace, guestWorkspace)
                    SkillSource.BUNDLED -> {
                        check(mapped(source, File(appFiles, "builtin_skills"), "") != null)
                        snapshot(source, linkedDirectory(appFiles, workspace))
                    }
                    SkillSource.GLOBAL -> {
                        check(mapped(source, File(appFiles, "skills"), "") != null || rootfs?.let { mapped(source, it, "") } != null)
                        snapshot(source, linkedDirectory(appFiles, workspace))
                    }
                    SkillSource.LINKED -> snapshot(source, linkedDirectory(appFiles, workspace))
                    SkillSource.OTHER_PROJECT -> null
                }
            }.getOrNull()
            skill.copy(guestFilePath = guestPath)
        }

    private fun mapped(source: File, base: File, guestBase: String): String? {
        val path = source.canonicalFile.toPath()
        val root = base.canonicalFile.toPath()
        if (!path.startsWith(root)) return null
        return "$guestBase/${root.relativize(path)}"
    }

    /** Complete, bounded snapshots keep references stable while a task is reading them. */
    private fun snapshot(skillFile: File, cache: File): String {
        val source = skillFile.parentFile!!.canonicalFile
        val root = source.toPath()
        val contents = sortedMapOf<String, ByteArray>()
        val executables = mutableSetOf<String>()
        var entries = 0
        var bytes = 0
        source.walkTopDown().forEach { file ->
            check(++entries <= MAX_FILES && !Files.isSymbolicLink(file.toPath())) { "Skill contains too many files or symbolic links" }
            check(file.canonicalFile.toPath().startsWith(root)) { "Skill resource escapes its folder" }
            if (file.isFile) {
                check(file.length() <= MAX_SKILL_BYTES - bytes) { "Skill resources are too large" }
                val content = file.inputStream().use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= MAX_SKILL_BYTES - bytes) { "Skill resources are too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
                bytes += content.size
                val name = root.relativize(file.toPath()).toString()
                contents[name] = content
                if (file.canExecute()) executables += name
            } else check(file.isDirectory) { "Unsupported skill resource" }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        contents.forEach { (name, content) ->
            digest.update(name.toByteArray()); digest.update(0.toByte())
            digest.update(content.size.toString().toByteArray()); digest.update(0.toByte()); digest.update(content)
            digest.update(if (name in executables) 1.toByte() else 0.toByte())
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }
        check(cache.canonicalFile.toPath().startsWith(appFiles.canonicalFile.toPath()))
        val existing = File(cache, id)
        val intact = existing.isDirectory && runCatching {
            val files = existing.walkTopDown().take(MAX_FILES + 1).toList()
            check(files.size <= MAX_FILES && files.none { Files.isSymbolicLink(it.toPath()) })
            val names = files.filter(File::isFile).map { existing.toPath().relativize(it.toPath()).toString() }.toSet()
            names == contents.keys && contents.all { (name, bytes) ->
                val file = File(existing, name)
                file.canonicalFile.toPath().startsWith(existing.canonicalFile.toPath()) && file.length() == bytes.size.toLong() &&
                    file.canExecute() == (name in executables) &&
                    file.inputStream().use { input ->
                        val actual = ByteArray(bytes.size)
                        var offset = 0
                        while (offset < actual.size) { val count = input.read(actual, offset, actual.size - offset); if (count < 0) break; offset += count }
                        offset == actual.size && input.read() == -1 && actual.contentEquals(bytes)
                    }
            }
        }.getOrDefault(false)
        // A guest can edit a bind-mounted copy. Never let that replace the app's source or overwrite
        // a running task's snapshot; publish a fresh copy if the cached content changed.
        val destination = if (existing.exists() && !intact) File(cache, "$id-${UUID.randomUUID()}") else existing
        if (!destination.isDirectory) {
            check(cache.isDirectory || cache.mkdirs())
            check(cachedBytes(cache) + bytes <= MAX_CACHE_BYTES) { "Linked skill cache is full" }
            val staging = File(cache, ".staging-${UUID.randomUUID()}")
            try {
                check(staging.mkdir())
                contents.forEach { (name, content) ->
                    File(staging, name).apply {
                        parentFile!!.mkdirs(); writeBytes(content)
                        if (name in executables) check(setExecutable(true, false))
                    }
                }
                check(staging.renameTo(destination) || destination.isDirectory) { "Could not publish linked skill" }
            } finally { staging.deleteRecursively() }
        }
        check(File(destination, "SKILL.md").isFile)
        return "/opt/pocket/skills/linked/${destination.name}/SKILL.md"
    }

    /** Cached copies are guest-editable: never follow their links or scan an unbounded tree. */
    private fun cachedBytes(cache: File): Long {
        val root = cache.toPath()
        check(!Files.isSymbolicLink(root))
        var bytes = 0L
        var entries = 0
        var snapshots = 0
        fun countEntry() { check(++entries <= 1 + 128 * MAX_FILES) { "Linked skill cache contains too many entries" } }
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                countEntry()
                if (path.parent == root) check(++snapshots < 128) { "Linked skill snapshot limit reached" }
                return FileVisitResult.CONTINUE
            }
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                countEntry()
                if (attributes.isRegularFile) {
                    check(attributes.size() <= MAX_CACHE_BYTES - bytes) { "Linked skill cache is full" }
                    bytes += attributes.size()
                }
                return FileVisitResult.CONTINUE
            }
        })
        return bytes
    }
}
