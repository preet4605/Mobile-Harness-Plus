package com.jarves.mh.mods

import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Path rules for anything a mod touches inside a project (spec v1 §8.3). */
object ModPaths {
    /** Files that configure agents, git or the app itself. No grant opens them. */
    private val DENIED = listOf(
        ".claude/**", ".claude.json", ".codex/**", ".agents/**", ".gemini/**", ".mcp.json",
        "CLAUDE.md", "AGENTS.md", "GEMINI.md", ".git/**", ".github/workflows/**", "**/.gitattributes", ".mh/**",
    )

    fun alwaysDeniedReason(path: String): String? {
        val lower = path.lowercase(Locale.ROOT)
        val hit = DENIED.firstOrNull { matchesGlob(it.lowercase(Locale.ROOT), lower) } ?: return null
        return "$path is an agent, git or app settings file ($hit), which mods can never change."
    }

    /** `*` matches within one path segment, `**` matches any number of segments. */
    fun matchesGlob(glob: String, path: String): Boolean = globRegex(glob).matches(path)

    private fun globRegex(glob: String): Regex {
        val out = StringBuilder("^")
        var i = 0
        while (i < glob.length) {
            val c = glob[i]
            when {
                glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3; continue }
                glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                c == '*' -> out.append("[^/]*")
                c == '?' -> out.append("[^/]")
                else -> out.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(out.append('$').toString())
    }

    /**
     * The file [relative] names inside [root], refusing denied paths, any link on the way (the
     * last component included) and anything that resolves outside [root].
     */
    fun resolveForWrite(root: File, relative: String): File {
        require(ModManifestParser.isSafeRelativePath(relative)) { "Unsafe path $relative" }
        alwaysDeniedReason(relative)?.let { throw IllegalArgumentException(it) }
        val rootPath = root.canonicalFile.toPath()
        var current = root.canonicalFile
        relative.split('/').forEach { segment ->
            current = File(current, segment)
            require(!Files.isSymbolicLink(current.toPath())) { "$relative goes through a link" }
        }
        val parent = (current.parentFile ?: root).canonicalFile.toPath()
        require(parent.startsWith(rootPath)) { "$relative is outside the project" }
        return current
    }
}
