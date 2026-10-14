package com.jarves.mh.mods

/** Why a hooks module cannot be loaded, in words for the mod's log and page. */
class ModCodeException(message: String) : Exception(message)

/**
 * Turns a mod's hooks module (spec v2 §4.1: `export function register(on, options)`) into a script
 * the engine can run. The engine runs plain scripts, not ES modules, so `export` becomes an
 * assignment to `exports` inside one function, and `import` is refused: a mod is one file.
 * The first line of the mod stays line 1, so engine errors point at the mod's own lines.
 */
object ModModuleSource {
    private val IMPORT = Regex("""^[ \t]*import\s*(?:[\w{*$]|["'])""", RegexOption.MULTILINE)
    private val EXPORT_DEFAULT = Regex("""^([ \t]*)export\s+default\s+""", RegexOption.MULTILINE)
    private val EXPORT_DECLARATION = Regex(
        """^([ \t]*)export\s+((?:async\s+)?function\s*\*?\s*|class\s+|const\s+|let\s+|var\s+)([A-Za-z_$][\w$]*)""",
        RegexOption.MULTILINE,
    )
    private val EXPORT_LIST = Regex("""^([ \t]*)export\s*\{([^}]*)}\s*;?""", RegexOption.MULTILINE)
    private val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")

    fun wrap(source: String): String {
        IMPORT.find(source)?.let { match ->
            throw ModCodeException("Line ${lineOf(source, match.range.first)}: a mod can't import other files. Put all of its code in the hooks module.")
        }
        val exported = mutableListOf<String>()
        var body = EXPORT_DECLARATION.replace(source) { match ->
            exported += match.groupValues[3]
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3]
        }
        body = EXPORT_DEFAULT.replace(body) { match -> match.groupValues[1] + "exports.default = " }
        body = EXPORT_LIST.replace(body) { match ->
            match.groupValues[1] + match.groupValues[2].split(',').map(String::trim).filter(String::isNotEmpty).joinToString(" ") { item ->
                val parts = item.split(Regex("\\s+as\\s+"))
                val local = parts[0].trim()
                val name = parts.getOrNull(1)?.trim() ?: local
                if (!IDENTIFIER.matches(local) || !(IDENTIFIER.matches(name))) {
                    throw ModCodeException("Line ${lineOf(source, match.range.first)}: can't read the export list \"${match.value.trim()}\".")
                }
                "exports.$name = $local;"
            }
        }
        return buildString(body.length + 200) {
            // The module's first line shares the wrapper's line, so line numbers stay the mod's own.
            append("__mh.define(function (exports, module) { 'use strict'; ")
            append(body)
            append("\n;")
            exported.distinct().forEach { append("exports.").append(it).append(" = ").append(it).append(';') }
            append("\n});\n'ok'")
        }
    }

    private fun lineOf(text: String, index: Int): Int = text.substring(0, index).count { it == '\n' } + 1
}
