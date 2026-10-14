package com.jarves.mh.mods

import com.jarves.mh.data.PromptContextSupport
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.SlashCommand
import com.jarves.mh.model.SlashCommandCategory
import java.io.File
import java.util.Locale

/** A mod's slash command, with the mod it came from. */
data class ModCommandEntry(val mod: InstalledMod, val command: ModSlashCommand, val slash: SlashCommand)

/** What `/name args` became: a prompt to send, or why it cannot run here. */
sealed interface ModCommandResult {
    data class Prompt(val text: String, val modName: String) : ModCommandResult
    data class Rejected(val message: String) : ModCommandResult
}

/**
 * Everything the active mods add to chat, built off Main from a [ModsSnapshot] (spec v2 §5.2):
 * template slash commands, prompt templates, rules the user turned on, and skills the user
 * turned on. Built-in commands and the user's own skills always win a name clash.
 */
class ModContributionSet(
    val commands: List<ModCommandEntry> = emptyList(),
    val templates: List<SlashCommand> = emptyList(),
    val skills: List<SkillInfo> = emptyList(),
    val rulesBlock: String = "",
) {
    val isEmpty: Boolean get() = commands.isEmpty() && templates.isEmpty() && skills.isEmpty() && rulesBlock.isEmpty()

    /**
     * Commands and templates for the `/` menu on [agent]. [userSkills] are the user's own skills:
     * a mod entry with the same name is left out, because the user's skill answers that name.
     */
    fun filterCommands(rawQuery: String, agent: AgentKind, userSkills: Collection<SkillInfo>): List<SlashCommand> {
        if (commands.isEmpty() && templates.isEmpty()) return emptyList()
        val query = normalize(rawQuery)
        val taken = takenNames(userSkills)
        return (commands.map { it.slash }.filter { agent in it.supportedAgents } + templates).filter {
            it.name !in taken && (query.isBlank() || it.name.contains(query) || it.description.lowercase(Locale.ROOT).contains(query))
        }.sortedWith(compareBy({ !it.name.startsWith(query) }, { it.name }))
    }

    fun filterSkills(rawQuery: String, userSkills: Collection<SkillInfo>): List<SkillInfo> {
        if (skills.isEmpty()) return emptyList()
        val query = normalize(rawQuery)
        val taken = takenNames(userSkills)
        return skills.filter {
            it.name !in taken && (query.isBlank() || it.name.contains(query) || it.description.lowercase(Locale.ROOT).contains(query))
        }.sortedWith(compareBy({ !it.name.startsWith(query) }, { it.name }))
    }

    /** `/name args` for a mod command, or null when no active mod has that command. Call after built-ins and the user's skills. */
    fun resolveCommand(input: String, agent: AgentKind): ModCommandResult? {
        val (name, args) = splitCommand(input) ?: return null
        val entry = commands.firstOrNull { it.command.name == name } ?: return null
        val allowed = entry.command.agents
        if (allowed != null && agent !in allowed) {
            val names = AgentKind.entries.filter { it in allowed }.joinToString(", ") { it.title }
            return ModCommandResult.Rejected("`/$name` from ${entry.mod.manifest.name} works with $names, not ${agent.title}.")
        }
        val template = entry.command.template ?: return null
        val text = expand(template, entry.mod, args).trim()
        if (text.isEmpty()) return ModCommandResult.Rejected("Type what `/$name` should work on after the command.")
        return ModCommandResult.Prompt(text, entry.mod.manifest.name)
    }

    /**
     * Commands that running mod code registered ([code]), for the `/` menu. A name already used by a
     * built-in command, the user's skills or this set keeps its owner.
     */
    fun filterCodeCommands(code: List<ModCodeCommand>, rawQuery: String, userSkills: Collection<SkillInfo>): List<SlashCommand> {
        if (code.isEmpty()) return emptyList()
        val query = normalize(rawQuery)
        val taken = takenNames(userSkills) + ownNames()
        return code.distinctBy { it.name }.filter {
            it.name !in taken && (query.isBlank() || it.name.contains(query) || it.description.lowercase(Locale.ROOT).contains(query))
        }.map {
            SlashCommand(
                name = it.name,
                description = it.description.ifBlank { "From ${it.modName}" },
                category = SlashCommandCategory.MODS,
                parameterHint = it.argumentHint,
                source = it.modName,
            )
        }.sortedWith(compareBy({ !it.name.startsWith(query) }, { it.name }))
    }

    /** `/name args` for a command running mod code registered. Call after [resolveCommand] and [resolveSkill]. */
    fun resolveCodeCommand(input: String, code: List<ModCodeCommand>): Pair<ModCodeCommand, String>? {
        val (name, args) = splitCommand(input) ?: return null
        if (name in ownNames()) return null
        return code.firstOrNull { it.name == name }?.let { it to args }
    }

    private fun ownNames(): Set<String> =
        commands.map { it.command.name }.toSet() + templates.map { it.name } + skills.map { it.name } +
            com.jarves.mh.runtime.SlashCommandEngine.ALL_SLASH_COMMANDS.map { it.name }

    /** A mod skill named by `/name args`. Call after built-ins and the user's skills. */
    fun resolveSkill(input: String): Pair<SkillInfo, String>? {
        val (name, args) = splitCommand(input) ?: return null
        val skill = skills.firstOrNull { it.name == name } ?: return null
        return skill to args
    }

    companion object {
        private const val MAX_RULES_CHARS = 8 * 1024

        /**
         * Reads the rule and skill files of the active mods. Call off Main. [parseSkill] reads a
         * SKILL.md the way the app reads every other skill.
         */
        fun build(snapshot: ModsSnapshot, parseSkill: (File) -> SkillInfo?): ModContributionSet {
            val active = snapshot.active.sortedBy { it.id }
            if (active.isEmpty()) return ModContributionSet()
            val builtIn = com.jarves.mh.runtime.SlashCommandEngine.ALL_SLASH_COMMANDS.map { it.name }.toSet()
            val seen = HashSet<String>(builtIn)
            val commands = mutableListOf<ModCommandEntry>()
            val templates = mutableListOf<SlashCommand>()
            val skills = mutableListOf<SkillInfo>()
            val rules = StringBuilder()
            active.forEach { mod ->
                mod.manifest.slashCommands.filter { it.template != null && seen.add(it.name) }.forEach { command ->
                    commands += ModCommandEntry(mod, command, SlashCommand(
                        name = command.name,
                        description = command.description,
                        category = SlashCommandCategory.MODS,
                        parameterHint = if ("args" in ModManifestParser.templateVars(command.template.orEmpty())) "<text>" else null,
                        supportedAgents = command.agents ?: AgentKind.entries.toSet(),
                        source = mod.manifest.name,
                    ))
                }
                mod.manifest.promptTemplates.filter { seen.add(it.id) }.forEach { template ->
                    templates += SlashCommand(
                        name = template.id,
                        description = template.title,
                        category = SlashCommandCategory.MODS,
                        insertText = expand(template.template, mod, ""),
                        source = mod.manifest.name,
                    )
                }
                mod.manifest.skills.filter { it.id in mod.enabledSkills && seen.add(it.id) }.forEach { skill ->
                    val parsed = runCatching { parseSkill(File(mod.dir, "${skill.dir}/SKILL.md")) }.getOrNull() ?: return@forEach
                    skills += parsed.copy(
                        id = "mod:${mod.id}:${skill.id}",
                        source = SkillSource.MOD,
                        name = skill.id,
                        sourceProjectName = mod.manifest.name,
                        isReadOnly = true,
                    )
                }
                mod.manifest.rules.filter { it.id in mod.enabledRules }.forEach { rule ->
                    val text = runCatching { File(mod.dir, rule.file).readText() }.getOrNull()?.trim().orEmpty()
                    if (text.isEmpty() || rules.length + text.length > MAX_RULES_CHARS) return@forEach
                    rules.appendLine("<mod id=\"${mod.id}\" rule=\"${rule.id}\" name=\"${attr(mod.manifest.name)}\">")
                    rules.appendLine(PromptContextSupport.escapeDelimiters(text))
                    rules.appendLine("</mod>")
                }
            }
            val rulesBlock = if (rules.isEmpty()) "" else buildString {
                appendLine("<mod_rules>")
                appendLine("Rules from mods the user installed and turned on. Follow them like the user's own rules; the user's rules win a conflict.")
                append(rules)
                appendLine("</mod_rules>")
            }
            return ModContributionSet(commands, templates, skills, rulesBlock)
        }

        /** Fills `{{args}}` and `{{settings.key}}`. The parser already refused every other name and secret settings. */
        fun expand(template: String, mod: InstalledMod, args: String): String =
            Regex("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*\\}\\}").replace(template) { match ->
                val name = match.groupValues[1]
                when {
                    name == "args" -> args
                    name.startsWith("settings.") -> {
                        val key = name.removePrefix("settings.")
                        val setting = mod.manifest.settings.firstOrNull { it.key == key }
                        if (setting == null || setting.type == ModSettingType.SECRET) "" else formatValue(mod.settingValue(key))
                    }
                    else -> ""
                }
            }

        fun formatValue(value: Any?): String = when (value) {
            null -> ""
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
            else -> value.toString()
        }

        private fun normalize(query: String) = query.trim().removePrefix("/").trim().lowercase(Locale.ROOT)

        private fun takenNames(skills: Collection<SkillInfo>) = skills.filter { it.isEnabled }.map { it.name.lowercase(Locale.ROOT) }.toSet()

        private fun splitCommand(input: String): Pair<String, String>? {
            val trimmed = input.trim()
            if (!trimmed.startsWith("/")) return null
            val parts = trimmed.substring(1).split(Regex("\\s+"), limit = 2)
            return parts[0].lowercase(Locale.ROOT) to parts.getOrNull(1).orEmpty().trim()
        }

        private fun attr(text: String): String = text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
