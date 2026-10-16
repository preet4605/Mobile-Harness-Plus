package com.jarves.mh.runtime

import java.io.File

/**
 * The directory rules of a workspace's root `.gitignore`, so checkpoints, the change list and
 * Undo/Keep skip the folders the project itself calls generated (build trees, caches, local
 * Gradle homes, test leftovers) on top of [WorkspaceCheckpoints.IGNORED_DIRECTORY_NAMES].
 *
 * Only directories are matched. An ignored directory holds output that tools regenerate; an
 * ignored single file (`local.properties`, a keystore, `.env`) can be the only copy of something
 * the user cares about, so it stays checkpointed and undoable. Nested `.gitignore` files are not
 * read.
 *
 * Patterns follow gitignore: `#` comments, `!` re-includes, a trailing `/` (directories only,
 * which is all this matches), a `/` anywhere else anchors the pattern to the root, `*`, `?`,
 * `[...]` and `**`. The last matching rule wins.
 */
internal class WorkspaceIgnoreRules private constructor(private val rules: List<Rule>) {

    private class Rule(val regex: Regex, val anchored: Boolean, val negated: Boolean)

    val isEmpty: Boolean get() = rules.isEmpty()

    /** Whether the directory at [relative] (root-relative, `/`-separated) is ignored by its own path. */
    fun isIgnoredDirectory(relative: String): Boolean {
        if (rules.isEmpty()) return false
        val path = relative.trim('/')
        if (path.isEmpty()) return false
        val name = path.substringAfterLast('/')
        var ignored = false
        for (rule in rules) {
            val matches = if (rule.anchored) rule.regex.matches(path) else rule.regex.matches(name)
            if (matches) ignored = !rule.negated
        }
        return ignored
    }

    /** Whether any folder on the way to the file at [relative] is ignored; git never looks inside such a folder. */
    fun isInIgnoredDirectory(relative: String): Boolean {
        if (rules.isEmpty()) return false
        val segments = relative.trim('/').split('/')
        for (depth in 1 until segments.size) {
            if (isIgnoredDirectory(segments.take(depth).joinToString("/"))) return true
        }
        return false
    }

    companion object {
        val NONE = WorkspaceIgnoreRules(emptyList())

        /** A larger file is not read (nothing is ignored); a real `.gitignore` is a few KB. */
        private const val MAX_BYTES = 64 * 1024
        private const val MAX_RULES = 2_000

        /** The rules of [root]'s `.gitignore`, or [NONE] when it is absent or unreadable. */
        fun load(root: File): WorkspaceIgnoreRules = runCatching {
            val file = File(root, ".gitignore")
            if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) return NONE
            if (file.length() > MAX_BYTES) return NONE
            parse(file.readText())
        }.getOrDefault(NONE)

        fun parse(text: String): WorkspaceIgnoreRules {
            val rules = text.lineSequence().mapNotNull(::parseLine).take(MAX_RULES).toList()
            return if (rules.isEmpty()) NONE else WorkspaceIgnoreRules(rules)
        }

        private fun parseLine(raw: String): Rule? {
            var line = raw.trimEnd('\r').trimEnd()
            if (line.isEmpty() || line.startsWith("#")) return null
            val negated = line.startsWith("!")
            if (negated) line = line.substring(1)
            if (line.startsWith("\\#") || line.startsWith("\\!")) line = line.substring(1)
            line = line.trimEnd('/')
            if (line.isEmpty()) return null
            val anchored = line.contains('/')
            line = line.trimStart('/')
            if (line.isEmpty()) return null
            val regex = runCatching { Regex(globToRegex(line)) }.getOrNull() ?: return null
            return Rule(regex, anchored, negated)
        }

        private fun globToRegex(glob: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    glob.startsWith("**/", i) -> { out.append("(?:.*/)?"); i += 3; continue }
                    glob.startsWith("**", i) -> { out.append(".*"); i += 2; continue }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    c == '[' -> {
                        val end = glob.indexOf(']', i + 2)
                        if (end > 0) {
                            var set = glob.substring(i + 1, end).replace("\\", "\\\\").replace("[", "\\[")
                            if (set.startsWith("!")) set = "^" + set.substring(1)
                            out.append('[').append(set).append(']')
                            i = end + 1
                            continue
                        }
                        out.append("\\[")
                    }
                    c == '\\' && i + 1 < glob.length -> { out.append(literal(glob[i + 1])); i += 2; continue }
                    else -> out.append(literal(c))
                }
                i++
            }
            return out.toString()
        }

        private fun literal(c: Char): String = if (c.isLetterOrDigit()) c.toString() else "\\$c"
    }
}
