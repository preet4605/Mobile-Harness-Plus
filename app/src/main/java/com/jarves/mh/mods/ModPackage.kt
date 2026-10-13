package com.jarves.mh.mods

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest

class ModInstallException(message: String, val problems: List<String> = listOf(message)) : Exception(message)

/** A package unpacked into a private staging folder and checked, waiting for the review sheet. */
data class StagedMod(
    val dir: File,
    val manifest: ModManifest,
    /** Every file except `mod.json`, with its SHA-256. */
    val files: Map<String, String>,
    val totalBytes: Long,
    val warnings: List<String>,
)

/**
 * Reads `.mhmod` archives (a ZIP of the mod folder) and mod folders, with the limits from spec
 * v2 §6.3 and §6.8: at most 300 files and 20 MB unpacked, no links, safe relative names, a
 * module of at most 512 KB, and `integrity` that lists every file with its SHA-256.
 */
object ModPackage {
    const val MANIFEST = "mod.json"
    const val MAX_FILES = 300
    const val MAX_UNPACKED_BYTES = 20L * 1024 * 1024
    const val MAX_ARCHIVE_BYTES = 25L * 1024 * 1024
    const val MAX_MODULE_BYTES = 512L * 1024
    const val MAX_RULES_BYTES = 8L * 1024
    const val MAX_SKILL_BYTES = 64L * 1024

    /** Copies [input] to [workDir], then unpacks and checks it into [stagingDir]. */
    fun stageArchive(input: InputStream, workDir: File, stagingDir: File, appVersionCode: Int): StagedMod {
        workDir.mkdirs()
        val archive = File.createTempFile("import-", ".mhmod", workDir)
        try {
            archive.outputStream().use { out -> copyBounded(input, out, MAX_ARCHIVE_BYTES, "The file is larger than 25 MB.") }
            unpack(archive, stagingDir)
        } catch (e: ModInstallException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw ModInstallException("This is not a mod file (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            archive.delete()
        }
        return check(stagingDir, appVersionCode)
    }

    /** Copies a mod folder (a Studio draft) into [stagingDir] and checks it. Links are refused, never followed. */
    fun stageDirectory(source: File, stagingDir: File, appVersionCode: Int): StagedMod {
        try {
            require(source.isDirectory && !Files.isSymbolicLink(source.toPath())) { "The mod folder is missing." }
            var count = 0
            var total = 0L
            source.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) || it == source }.forEach { file ->
                if (file == source) return@forEach
                val relative = file.relativeTo(source).invariantSeparatorsPath
                if (Files.isSymbolicLink(file.toPath())) throw ModInstallException("$relative is a link; mods cannot contain links.")
                if (file.isDirectory) return@forEach
                if (!ModManifestParser.isSafeRelativePath(relative)) throw ModInstallException("Unsafe file name: $relative")
                if (++count > MAX_FILES) throw ModInstallException("A mod can contain at most $MAX_FILES files.")
                total += file.length()
                if (total > MAX_UNPACKED_BYTES) throw ModInstallException("A mod can be at most 20 MB.")
                val target = File(stagingDir, relative)
                target.parentFile?.mkdirs()
                file.inputStream().use { input -> target.outputStream().use { copyBounded(input, it, MAX_UNPACKED_BYTES, "A mod can be at most 20 MB.") } }
            }
        } catch (e: ModInstallException) {
            stagingDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            throw ModInstallException("Could not read the mod folder (${e.message ?: e.javaClass.simpleName}).")
        }
        return check(stagingDir, appVersionCode)
    }

    private fun unpack(archive: File, stagingDir: File) {
        stagingDir.mkdirs()
        val stagingRoot = stagingDir.canonicalFile.toPath()
        ZipFile.builder().setFile(archive).get().use { zip ->
            val entries = zip.entries.toList().filterNot { it.isDirectory }
            if (entries.size > MAX_FILES + 1) throw ModInstallException("A mod can contain at most $MAX_FILES files.")
            // A ZIP of the mod's folder has one top folder around mod.json; drop it.
            val names = entries.map { it.name }
            val prefix = if (MANIFEST in names) "" else {
                val top = names.map { it.substringBefore('/', "") }.distinct()
                if (top.size == 1 && top[0].isNotEmpty() && "${top[0]}/$MANIFEST" in names) "${top[0]}/" else ""
            }
            val seen = mutableSetOf<String>()
            var total = 0L
            entries.forEach { entry ->
                if (entry.isUnixSymlink) throw ModInstallException("${entry.name} is a link; mods cannot contain links.")
                val relative = entry.name.removePrefix(prefix)
                if (!entry.name.startsWith(prefix) || !ModManifestParser.isSafeRelativePath(relative)) {
                    throw ModInstallException("Unsafe file name in the archive: ${entry.name}")
                }
                if (!seen.add(relative)) throw ModInstallException("The archive lists $relative twice.")
                val target = File(stagingDir, relative)
                require(target.canonicalFile.toPath().startsWith(stagingRoot)) { "Unsafe file name in the archive: ${entry.name}" }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { out ->
                        total += copyBounded(input, out, MAX_UNPACKED_BYTES - total, "A mod can be at most 20 MB unpacked.")
                    }
                }
            }
        }
    }

    /** Validates the unpacked files in [dir] against `mod.json`. */
    fun check(dir: File, appVersionCode: Int): StagedMod {
        val manifestFile = File(dir, MANIFEST)
        if (!manifestFile.isFile) throw ModInstallException("The mod has no mod.json at its top level.")
        if (manifestFile.length() > ModManifestParser.MAX_MANIFEST_BYTES) throw ModInstallException("mod.json is larger than 64 KB.")
        val manifest = try {
            ModManifestParser.parse(manifestFile.readText(), appVersionCode)
        } catch (e: ModManifestException) {
            throw ModInstallException("mod.json has ${e.problems.size} problem(s).", e.problems)
        }
        val files = linkedMapOf<String, String>()
        var total = manifestFile.length()
        dir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            if (relative == MANIFEST) return@forEach
            if (Files.isSymbolicLink(file.toPath())) throw ModInstallException("$relative is a link; mods cannot contain links.")
            total += file.length()
            files[relative] = sha256(file)
        }
        if (files.size > MAX_FILES) throw ModInstallException("A mod can contain at most $MAX_FILES files.")
        if (total > MAX_UNPACKED_BYTES) throw ModInstallException("A mod can be at most 20 MB unpacked.")

        val problems = mutableListOf<String>()
        files.keys.filter { it !in manifest.integrity }.forEach { problems += "$it is not listed in \"integrity\"." }
        manifest.integrity.forEach { (path, hash) ->
            when (val actual = files[path]) {
                null -> problems += "integrity lists $path, but the mod does not contain it."
                hash -> Unit
                else -> problems += "$path does not match its integrity hash (expected ${hash.take(12)}…, found ${actual.take(12)}…)."
            }
        }
        manifest.referencedFiles.filter { it !in files }.forEach { problems += "mod.json names $it, but the mod does not contain it." }
        manifest.hooksModule?.let { module ->
            File(dir, module).takeIf { it.isFile && it.length() > MAX_MODULE_BYTES }?.let { problems += "$module is larger than 512 KB." }
        }
        val rulesBytes = manifest.rules.sumOf { File(dir, it.file).takeIf(File::isFile)?.length() ?: 0L }
        if (rulesBytes > MAX_RULES_BYTES) problems += "Rules add up to more than 8 KB."
        manifest.skills.forEach { skill ->
            File(dir, "${skill.dir}/SKILL.md").takeIf { it.isFile && it.length() > MAX_SKILL_BYTES }
                ?.let { problems += "${skill.dir}/SKILL.md is larger than 64 KB." }
        }
        (manifest.rules.map { it.file } + manifest.skills.map { "${it.dir}/SKILL.md" }).forEach { path ->
            val file = File(dir, path)
            if (file.isFile && !isUtf8Text(file)) problems += "$path is not UTF-8 text."
        }
        if (problems.isNotEmpty()) throw ModInstallException("The mod's files do not match mod.json.", problems)

        val warnings = buildList {
            if (manifest.hooksModule != null) add("This mod has a code part (${manifest.hooksModule}). This version of the app cannot run mod code yet, so only its commands, templates, rules, skills and simple hooks work.")
            val unusable = manifest.permissions.map { it.capability }.filterNot { it.usable }
            if (unusable.isNotEmpty()) add("It asks for ${unusable.joinToString { it.id }}, which this version of the app does not offer yet. They stay off.")
        }
        return StagedMod(dir, manifest, files, total, warnings)
    }

    /** Writes the installed mod in [dir] as a `.mhmod` archive: the same files, so hashes match on re-import. */
    fun export(dir: File, out: OutputStream) {
        val files = dir.walkTopDown().filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .map { it.relativeTo(dir).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        ZipArchiveOutputStream(out).use { zip ->
            files.forEach { (relative, file) ->
                val entry = ZipArchiveEntry(relative)
                zip.putArchiveEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeArchiveEntry()
            }
            zip.finish()
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isUtf8Text(file: File): Boolean = runCatching {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        val bytes = file.readBytes()
        decoder.decode(java.nio.ByteBuffer.wrap(bytes))
        bytes.none { it == 0.toByte() }
    }.getOrDefault(false)

    /** Copies at most [limit] bytes; more than that fails with [tooLarge]. Returns the bytes copied. */
    private fun copyBounded(input: InputStream, out: OutputStream, limit: Long, tooLarge: String): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return copied
            copied += read
            if (copied > limit) throw ModInstallException(tooLarge)
            out.write(buffer, 0, read)
        }
    }
}
