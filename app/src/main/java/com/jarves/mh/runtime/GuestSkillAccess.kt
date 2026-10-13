package com.jarves.mh.runtime

import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import java.io.File
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
        private const val MAX_FILES = SkillFiles.MAX_FILES
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
                        val guestGlobal = rootfs?.canonicalFile?.let { guestRoot ->
                            listOf("root/.gemini/config/skills", "root/.agents/skills", "root/.gemini/skills").any { relative ->
                                source.canonicalFile.toPath().startsWith(File(guestRoot, relative).toPath())
                            }
                        } == true
                        check(mapped(source, File(appFiles, "skills"), "") != null || guestGlobal)
                        snapshot(source, linkedDirectory(appFiles, workspace))
                    }
                    SkillSource.LINKED -> {
                        val origin = skill.sourceWorkspacePath?.let(::File) ?: error("Linked skill origin is missing")
                        check(mapped(source, origin, "") != null)
                        snapshot(source, linkedDirectory(appFiles, workspace))
                    }
                    // Mods provide explicitly invoked bodies through ModContributionSet, not guest paths.
                    SkillSource.MOD, SkillSource.OTHER_PROJECT -> null
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
        val contents = sortedMapOf<String, ByteArray>()
        val executables = mutableSetOf<String>()
        SkillFiles.resources(source).filter { it.content != null }.forEach { resource ->
            contents[resource.path] = resource.content!!
            if (resource.executable) executables += resource.path
        }
        val bytes = contents.values.sumOf { it.size }
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
