package com.jarves.mh.mods

import com.jarves.mh.model.AgentKind
import com.jarves.mh.runtime.SlashCommandEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** A parsed and validated `mod.json` (spec v2 §6.2, `mod.schema.v2.json`). */
data class ModManifest(
    val id: String,
    val name: String,
    val version: String,
    val apiVersion: Int,
    val description: String = "",
    val author: String = "",
    val minAppVersionCode: Int? = null,
    val permissions: List<ModPermission> = emptyList(),
    val slashCommands: List<ModSlashCommand> = emptyList(),
    val promptTemplates: List<ModPromptTemplate> = emptyList(),
    val settings: List<ModSetting> = emptyList(),
    val rules: List<ModTextFile> = emptyList(),
    val skills: List<ModSkillDir> = emptyList(),
    val hooks: List<ModDeclarativeHook> = emptyList(),
    val hooksModule: String? = null,
    val types: String? = null,
    val dashboard: ModDashboard? = null,
    val integrity: Map<String, String> = emptyMap(),
    /** Programs the agents run in the Linux guest (mod plan row 11; `guest.tools`, Developer mode). */
    val guestTools: List<ModGuestProgram> = emptyList(),
    /** MCP servers Claude and Codex start in the Linux guest (`guest.tools`, Developer mode). */
    val mcpServers: List<ModGuestProgram> = emptyList(),
    /** Web panels: HTML pages from the package, shown one at a time (mod plan row 12; `ui.webpane`). */
    val webPanels: List<ModWebPanel> = emptyList(),
    /** Items in the chat, file and project menus that run one of its commands (mod plan row 18). */
    val menuItems: List<ModMenuItem> = emptyList(),
    /** Times the app wakes to fire `schedule.run` (mod plan row 21; `schedules`, Developer mode). */
    val schedules: List<ModSchedule> = emptyList(),
    /** Agent roles its Dashboard console shows, each on its own harness and chat (mod dashboards phase 3). */
    val roles: List<ModRole> = emptyList(),
) {
    val requested: Set<ModCapability> get() = permissions.map { it.capability }.toSet()

    fun permission(capability: ModCapability): ModPermission? = permissions.firstOrNull { it.capability == capability }

    /** Files the manifest itself names; each must exist in the package. */
    val referencedFiles: List<String>
        get() = rules.map { it.file } + skills.map { "${it.dir}/SKILL.md" } + listOfNotNull(hooksModule, types) +
            (guestTools + mcpServers).flatMap { it.command }.filter { it.startsWith("./") }.map { it.removePrefix("./") }.distinct() +
            webPanels.map { it.entry }
}

/** Where a mod's menu item shows: the composer's + menu, a file's menu in Files, or the project's view menu. */
enum class ModMenuPlace(val id: String) {
    CHAT("chat"), FILE("file"), PROJECT("project");

    companion object {
        fun fromId(id: String): ModMenuPlace? = entries.firstOrNull { it.id == id }
    }
}

/** A menu item that runs the mod's slash command [command]; in a file's menu with the file's path as its arguments. */
data class ModMenuItem(val id: String, val title: String, val place: ModMenuPlace, val command: String)

/** A menu item of an active mod, as the project's menus list it. */
data class ModMenuEntry(val modId: String, val modName: String, val item: ModMenuItem)

/** A periodic wake-up: `schedule.run` fires about every [everyMinutes] minutes (at least 15). */
data class ModSchedule(val id: String, val everyMinutes: Int, val description: String = "")

/** An agent role: the console sends its prompts to [harness] (with [model] and [effort] when set) in the role's own chat. */
data class ModRole(val id: String, val title: String, val harness: String, val model: String? = null, val effort: String? = null)

/** A web panel: [entry] is an `.html` file in the package, served from the mod's own origin. */
data class ModWebPanel(val id: String, val title: String, val entry: String)

/**
 * A program a mod ships for the Linux guest: a guest tool (a command the agents run) or an MCP
 * server. [command] is the argv; elements starting with `./` are files in the mod's package.
 */
data class ModGuestProgram(
    val name: String,
    val description: String,
    val command: List<String>,
    val env: Map<String, String> = emptyMap(),
)

data class ModPermission(
    val capability: ModCapability,
    val reason: String? = null,
    val paths: List<String> = emptyList(),
    val events: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
)

data class ModSlashCommand(
    val name: String,
    val description: String,
    val template: String?,
    /** Null means every harness. */
    val agents: Set<AgentKind>? = null,
)

data class ModPromptTemplate(val id: String, val title: String, val template: String)

enum class ModSettingType(val id: String) { BOOL("bool"), ENUM("enum"), STRING("string"), NUMBER("number"), SECRET("secret") }

data class ModSetting(
    val key: String,
    val title: String,
    val type: ModSettingType,
    /** Boolean, Double or String by [type]; null only for secrets. */
    val default: Any?,
    val options: List<String> = emptyList(),
    val maxLength: Int? = null,
    val description: String? = null,
)

data class ModTextFile(val id: String, val file: String)

data class ModSkillDir(val id: String, val dir: String)

data class ModDashboard(val pane: String, val actions: List<String>)

data class ModDeclarativeHook(
    val id: String,
    val on: ModEvent,
    val action: ModHookAction,
    val status: String? = null,
    val harness: AgentKind? = null,
)

sealed interface ModHookAction {
    val capability: ModCapability
    val templates: List<String>

    data class Notify(val title: String, val body: String) : ModHookAction {
        override val capability get() = ModCapability.NOTIFY
        override val templates get() = listOf(title, body)
    }
    data class Toast(val text: String) : ModHookAction {
        override val capability get() = ModCapability.UI_TOAST
        override val templates get() = listOf(text)
    }
    data class ChatPost(val text: String) : ModHookAction {
        override val capability get() = ModCapability.CHAT_POST
        override val templates get() = listOf(text)
    }
    data class PromptDraft(val text: String) : ModHookAction {
        override val capability get() = ModCapability.PROMPT_DRAFT
        override val templates get() = listOf(text)
    }
    data class FilesAppend(val path: String, val line: String) : ModHookAction {
        override val capability get() = ModCapability.FILES_WRITE
        override val templates get() = listOf(line)
    }
}

/** Events a declarative hook can name; [vars] are the template variables each one provides. */
enum class ModEvent(val id: String, val vars: Set<String>, val statuses: Set<String> = emptySet(), val label: String) {
    TASK_STARTED("task.started", setOf("task.prompt"), label = "When a task starts"),
    TASK_FINISHED("task.finished", setOf("task.status", "task.duration", "task.error"), setOf("completed", "failed", "stopped"), "When a task finishes"),
    SESSION_COMPLETED("session.completed", emptySet(), label = "When an agent attempt completes"),
    SESSION_FAILED("session.failed", setOf("session.error"), label = "When an agent attempt fails"),
    FILES_CHANGED("files.changed", setOf("files.count", "files.paths"), label = "When the agent changes files"),
    TOOL_COMPLETED("tool.completed", setOf("tool.name", "tool.summary"), label = "When an agent tool finishes"),
    TOOL_REQUESTED("tool.requested", setOf("tool.name", "tool.risk"), label = "When the agent asks for approval"),
    TERMINAL_COMMAND_FINISHED("terminal.command.finished", setOf("terminal.command", "terminal.exitCode"), label = "When a terminal command finishes"),
    ASSISTANT_MESSAGE_COMPLETED("assistant.message.completed", setOf("message.text"), label = "When the agent finishes its reply"),
    PROJECT_OPENED("project.opened", emptySet(), label = "When a project opens"),
    PROJECT_CLOSED("project.closed", emptySet(), label = "When a project closes"),
    CHAT_SWITCHED("chat.switched", setOf("chat.title"), label = "When you switch chats"),
    APP_RESUMED("app.resumed", emptySet(), label = "When you return to the app"),
    RUN_STARTED("run.started", emptySet(), label = "When you build and run the Android app"),
    RUN_FINISHED("run.finished", setOf("run.status", "run.error"), setOf("completed", "failed"), "When build and run ends"),
    BUILD_FINISHED("build.finished", setOf("build.status", "build.detail"), setOf("completed", "failed", "stopped"), "When an Android build finishes"),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ModEvent? = byId[id]

        /** Variables every hook template may use. */
        val COMMON_VARS = setOf("project.name", "harness", "time", "date", "mod.name")
    }
}

class ModManifestException(val problems: List<String>) :
    Exception(problems.joinToString("\n"))

/** Parses `mod.json` and checks it against the v2 schema rules that matter on the device. */
object ModManifestParser {
    const val MAX_MANIFEST_BYTES = 64 * 1024
    const val MIN_API_VERSION = 1
    const val MAX_API_VERSION = 2

    private val ID = Regex("^[a-z0-9-]{1,30}(\\.[a-z0-9-]{1,30}){1,5}$")
    private val VERSION = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
    private val COMMAND_NAME = Regex("^[a-z][a-z0-9-]{1,30}$")
    private val ITEM_ID = Regex("^[a-z0-9-]{1,40}$")

    /** At most this many `contributes.roles`. */
    const val MAX_ROLES = 6
    private val GUEST_PROGRAM_NAME = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val ENV_NAME = Regex("^[A-Z_][A-Z0-9_]{0,63}$")
    /** Names the app sets for every guest program, or that would change how the guest runs it. */
    private val RESERVED_ENV = setOf("PATH", "HOME", "MOD_DIR", "LD_PRELOAD", "LD_LIBRARY_PATH", "NODE_OPTIONS", "POCKET_INSPECTION_TOKEN", "POCKET_WORKSPACE")
    private val PANE_ID = Regex("^[a-z0-9-]{1,30}$")
    private val SETTING_KEY = Regex("^[a-z][a-z0-9_]{0,39}$")
    /** A host name, or `*.` and one for all of its subdomains. */
    private val HOST = Regex("^(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+$")
    private val COMMAND = Regex("^[A-Za-z0-9._+-]+$")
    private val TEMPLATE_VAR = Regex("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*\\}\\}")

    private val TOP_KEYS = setOf(
        "id", "name", "version", "description", "author", "apiVersion", "minAppVersionCode",
        "permissions", "contributes", "integrity", "hooks", "types", "dashboard",
    )
    private val CONTRIBUTES_KEYS = setOf(
        "panels", "menuItems", "schedules", "roles", "slashCommands", "promptTemplates", "settings", "rules", "skills", "hooks", "guestTools", "mcpServers",
    )
    private val HARNESS_IDS = mapOf(
        "claude" to AgentKind.CLAUDE_CODE,
        "deepseek" to AgentKind.DEEPSEEK_HARNESS,
        "antigravity" to AgentKind.ANTIGRAVITY,
        "codex" to AgentKind.CODEX,
    )

    fun harnessId(kind: AgentKind): String = HARNESS_IDS.entries.first { it.value == kind }.key

    /** The harness a mod names with [id] (claude, deepseek, antigravity, codex), or null. */
    fun harnessFromId(id: String): AgentKind? = HARNESS_IDS[id]

    /** A slash command name a mod may use: 2 to 31 lowercase letters, digits or dashes, starting with a letter. */
    fun isCommandName(name: String): Boolean = COMMAND_NAME.matches(name)

    /** Relative, forward slashes, no `..`, no empty segment, no backslash, no NUL, at most 200 characters. */
    fun isSafeRelativePath(path: String): Boolean =
        path.isNotEmpty() && path.length <= 200 && !path.startsWith("/") && !path.contains('\\') &&
            !path.contains('\u0000') && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Template variables used in [text]. */
    fun templateVars(text: String): Set<String> = TEMPLATE_VAR.findAll(text).map { it.groupValues[1] }.toSet()

    fun parse(text: String, appVersionCode: Int = Int.MAX_VALUE): ModManifest {
        if (text.toByteArray().size > MAX_MANIFEST_BYTES) throw ModManifestException(listOf("mod.json is larger than 64 KB."))
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw ModManifestException(listOf("mod.json is not valid JSON: ${e.message}"))
        }
        val p = Problems()
        val manifest = parseRoot(root, p, appVersionCode)
        if (p.list.isNotEmpty() || manifest == null) throw ModManifestException(p.list.ifEmpty { listOf("mod.json is not valid.") })
        return manifest
    }

    private class Problems {
        val list = mutableListOf<String>()
        fun add(message: String) { if (list.size < 50) list += message }
    }

    private fun parseRoot(root: JSONObject, p: Problems, appVersionCode: Int): ModManifest? {
        root.keys().asSequence().filter { it !in TOP_KEYS }.forEach { p.add("Unknown field \"$it\".") }
        val id = string(root, "id", p, required = true, max = 100)
        if (id != null && !ID.matches(id)) p.add("\"id\" must look like dev.you.mod-name (lowercase letters, digits and dashes).")
        val name = string(root, "name", p, required = true, min = 1, max = 60)
        val version = string(root, "version", p, required = true)
        if (version != null && !VERSION.matches(version)) p.add("\"version\" must be like 1.0.0.")
        val description = string(root, "description", p, max = 300).orEmpty()
        val author = string(root, "author", p, max = 80).orEmpty()
        val apiVersion = int(root, "apiVersion", p, required = true)
        if (apiVersion != null && apiVersion !in MIN_API_VERSION..MAX_API_VERSION) {
            p.add("\"apiVersion\" $apiVersion is not supported; this app supports $MIN_API_VERSION to $MAX_API_VERSION.")
        }
        val minApp = int(root, "minAppVersionCode", p)
        if (minApp != null && minApp < 1) p.add("\"minAppVersionCode\" must be at least 1.")
        if (minApp != null && minApp > appVersionCode) p.add("This mod needs a newer version of the app.")

        val permissions = parsePermissions(root.opt("permissions"), p)
        val contributes = root.opt("contributes")
        var commands = emptyList<ModSlashCommand>()
        var templates = emptyList<ModPromptTemplate>()
        var settings = emptyList<ModSetting>()
        var rules = emptyList<ModTextFile>()
        var skills = emptyList<ModSkillDir>()
        var hooks = emptyList<ModDeclarativeHook>()
        var guestTools = emptyList<ModGuestProgram>()
        var mcpServers = emptyList<ModGuestProgram>()
        var webPanels = emptyList<ModWebPanel>()
        var menuItems = emptyList<ModMenuItem>()
        var schedules = emptyList<ModSchedule>()
        var roles = emptyList<ModRole>()
        if (contributes != null) {
            if (contributes !is JSONObject) {
                p.add("\"contributes\" must be an object.")
            } else {
                contributes.keys().asSequence().filter { it !in CONTRIBUTES_KEYS }.forEach { p.add("Unknown field \"contributes.$it\".") }
                guestTools = array(contributes, "guestTools", p, 8).mapIndexedNotNull { i, o -> parseGuestProgram(o, "contributes.guestTools[$i]", p) }
                mcpServers = array(contributes, "mcpServers", p, 4).mapIndexedNotNull { i, o -> parseGuestProgram(o, "contributes.mcpServers[$i]", p) }
                webPanels = array(contributes, "panels", p, 4).mapIndexedNotNull { i, o -> parseWebPanel(o, i, p) }
                menuItems = array(contributes, "menuItems", p, 8).mapIndexedNotNull { i, o -> parseMenuItem(o, i, p) }
                schedules = array(contributes, "schedules", p, 3).mapIndexedNotNull { i, o -> parseSchedule(o, i, p) }
                roles = array(contributes, "roles", p, MAX_ROLES).mapIndexedNotNull { i, o -> parseRole(o, i, p) }
                commands = array(contributes, "slashCommands", p, 32).mapIndexedNotNull { i, o -> parseCommand(o, i, p) }
                templates = array(contributes, "promptTemplates", p, 32).mapIndexedNotNull { i, o -> parseTemplate(o, i, p) }
                settings = array(contributes, "settings", p, 32).mapIndexedNotNull { i, o -> parseSetting(o, i, p) }
                rules = array(contributes, "rules", p, 8).mapIndexedNotNull { i, o -> parseTextFile(o, i, p) }
                skills = array(contributes, "skills", p, 8).mapIndexedNotNull { i, o -> parseSkill(o, i, p) }
                hooks = array(contributes, "hooks", p, 16).mapIndexedNotNull { i, o -> parseHook(o, i, p) }
            }
        }
        val integrity = parseIntegrity(root.opt("integrity"), p)
        val hooksModule = root.opt("hooks")?.let { value ->
            if (value !is JSONObject) { p.add("\"hooks\" must be an object with \"module\"."); null } else {
                value.keys().asSequence().filter { it != "module" }.forEach { p.add("Unknown field \"hooks.$it\".") }
                string(value, "module", p, required = true, label = "hooks.module")?.also {
                    if (!isSafeRelativePath(it) || !(it.endsWith(".js") || it.endsWith(".mjs"))) {
                        p.add("\"hooks.module\" must be a relative .js or .mjs path.")
                    }
                }
            }
        }
        val types = string(root, "types", p)?.also {
            if (!isSafeRelativePath(it) || !it.endsWith(".d.ts")) p.add("\"types\" must be a relative .d.ts path.")
        }
        val dashboard = root.opt("dashboard")?.let { parseDashboard(it, p) }

        checkUnique(commands.map { it.name }, "command name", p)
        checkUnique(templates.map { it.id }, "prompt template id", p)
        checkUnique(settings.map { it.key }, "setting key", p)
        checkUnique(rules.map { it.id }, "rule id", p)
        checkUnique(skills.map { it.id }, "skill id", p)
        checkUnique(hooks.map { it.id }, "hook id", p)
        checkUnique(guestTools.map { it.name } + mcpServers.map { it.name }, "guest tool or MCP server name", p)
        checkUnique(webPanels.map { it.id }, "web panel id", p)
        checkUnique(menuItems.map { it.id }, "menu item id", p)
        checkUnique(schedules.map { it.id }, "schedule id", p)
        checkUnique(roles.map { it.id }, "role id", p)
        menuItems.filter { item -> commands.none { it.name == item.command } }.forEach {
            p.add("Menu item ${it.id} runs /${it.command}, which isn't one of the mod's slashCommands.")
        }

        val builtIn = SlashCommandEngine.ALL_SLASH_COMMANDS.map { it.name.lowercase(Locale.ROOT) }.toSet()
        commands.filter { it.name in builtIn }.forEach { p.add("Command /${it.name} would replace a built-in command.") }
        templates.filter { it.id in builtIn }.forEach { p.add("Prompt template ${it.id} would be hidden by the built-in /${it.id}; give it another id.") }
        skills.filter { it.id in builtIn }.forEach { p.add("Skill ${it.id} would be hidden by the built-in /${it.id}; give it another id.") }
        (commands.map { it.name }.distinct() + templates.map { it.id }.distinct() + skills.map { it.id }.distinct())
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { p.add("/$it is used by more than one command, prompt template or skill.") }
        if (hooksModule == null) {
            commands.filter { it.template.isNullOrBlank() }.forEach { p.add("Command /${it.name} needs a \"template\".") }
        }

        val plainSettings = settings.filter { it.type != ModSettingType.SECRET }.map { "settings.${it.key}" }.toSet()
        commands.forEach { command ->
            checkVars(command.template.orEmpty(), setOf("args") + plainSettings, "command /${command.name}", p)
        }
        templates.forEach { template -> checkVars(template.template, plainSettings, "prompt template ${template.id}", p) }
        val requested = permissions.map { it.capability }.toSet()
        hooks.forEach { hook ->
            val allowed = ModEvent.COMMON_VARS + hook.on.vars + plainSettings
            hook.action.templates.forEach { checkVars(it, allowed, "hook ${hook.id}", p) }
            if (hook.action.capability !in requested) {
                p.add("Hook ${hook.id} uses ${hook.action.capability.id}, so the mod must ask for that permission.")
            }
            if (hook.on == ModEvent.ASSISTANT_MESSAGE_COMPLETED && ModCapability.CHAT_READ !in requested &&
                hook.action.templates.any { "message.text" in templateVars(it) }) {
                p.add("Hook ${hook.id} reads the agent's message, so the mod must ask for chat.read.")
            }
            if (hook.status != null && hook.status !in hook.on.statuses) {
                p.add("Hook ${hook.id}: \"${hook.on.id}\" has no status \"${hook.status}\".")
            }
            val append = hook.action as? ModHookAction.FilesAppend
            if (append != null) {
                val scope = permissions.firstOrNull { it.capability == ModCapability.FILES_WRITE }?.paths.orEmpty()
                if (scope.none { ModPaths.matchesGlob(it, append.path) }) {
                    p.add("Hook ${hook.id} writes ${append.path}, which the files.write permission's \"paths\" do not cover.")
                }
                ModPaths.alwaysDeniedReason(append.path)?.let { p.add("Hook ${hook.id}: $it") }
            }
        }
        if (dashboard != null && hooksModule == null) p.add("\"dashboard\" needs a hooks module to draw it.")
        if (webPanels.isNotEmpty() && ModCapability.UI_WEBPANE !in requested) {
            p.add("Web panels need the ui.webpane permission.")
        }
        if (schedules.isNotEmpty() && ModCapability.SCHEDULES !in requested) p.add("Schedules need the schedules permission.")
        if (schedules.isNotEmpty() && hooksModule == null) p.add("Schedules need a hooks module to answer schedule.run.")
        if ((guestTools.isNotEmpty() || mcpServers.isNotEmpty()) && ModCapability.GUEST_TOOLS !in requested) {
            p.add("Guest tools and MCP servers run in the Linux guest, so the mod must ask for guest.tools.")
        }

        if (id == null || name == null || version == null || apiVersion == null) return null
        return ModManifest(
            id = id,
            name = name,
            version = version,
            apiVersion = apiVersion,
            description = description,
            author = author,
            minAppVersionCode = minApp,
            permissions = permissions,
            slashCommands = commands,
            promptTemplates = templates,
            settings = settings,
            rules = rules,
            skills = skills,
            hooks = hooks,
            hooksModule = hooksModule,
            types = types,
            dashboard = dashboard,
            integrity = integrity,
            guestTools = guestTools,
            mcpServers = mcpServers,
            webPanels = webPanels,
            menuItems = menuItems,
            schedules = schedules,
            roles = roles,
        )
    }

    private fun parseRole(o: JSONObject, i: Int, p: Problems): ModRole? {
        val at = "contributes.roles[$i]"
        unknownKeys(o, setOf("id", "title", "harness", "model", "effort"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val title = string(o, "title", p, required = true, min = 1, max = 40, label = "$at.title") ?: return null
        val harness = string(o, "harness", p, required = true, label = "$at.harness") ?: return null
        if (harnessFromId(harness) == null) p.add("$at.harness must be claude, deepseek, antigravity or codex.")
        val model = string(o, "model", p, max = 120, label = "$at.model")?.takeIf { it.isNotBlank() }
        val effort = string(o, "effort", p, max = 40, label = "$at.effort")?.takeIf { it.isNotBlank() }
        return ModRole(id, title, harness, model, effort)
    }

    private fun parseSchedule(o: JSONObject, i: Int, p: Problems): ModSchedule? {
        val at = "contributes.schedules[$i]"
        unknownKeys(o, setOf("id", "everyMinutes", "description"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val minutes = int(o, "everyMinutes", p, required = true, label = "$at.everyMinutes") ?: return null
        if (minutes !in 15..1440) p.add("$at.everyMinutes must be 15 to 1440: Android wakes apps at most every 15 minutes.")
        val description = string(o, "description", p, max = 200, label = "$at.description").orEmpty()
        return ModSchedule(id, minutes, description)
    }

    private fun parseMenuItem(o: JSONObject, i: Int, p: Problems): ModMenuItem? {
        val at = "contributes.menuItems[$i]"
        unknownKeys(o, setOf("id", "title", "place", "command"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val title = string(o, "title", p, required = true, min = 1, max = 40, label = "$at.title") ?: return null
        val place = string(o, "place", p, required = true, label = "$at.place")?.let { value ->
            ModMenuPlace.fromId(value) ?: run { p.add("$at.place must be chat, file or project."); null }
        } ?: return null
        val command = string(o, "command", p, required = true, label = "$at.command")?.removePrefix("/") ?: return null
        return ModMenuItem(id, title, place, command)
    }

    private fun parseWebPanel(o: JSONObject, i: Int, p: Problems): ModWebPanel? {
        val at = "contributes.panels[$i]"
        unknownKeys(o, setOf("id", "title", "entry"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val title = string(o, "title", p, required = true, min = 1, max = 40, label = "$at.title") ?: return null
        val entry = string(o, "entry", p, required = true, label = "$at.entry") ?: return null
        if (!isSafeRelativePath(entry) || !entry.endsWith(".html")) p.add("$at.entry must be a relative .html file in the mod.")
        return ModWebPanel(id, title, entry)
    }

    private fun parseGuestProgram(o: JSONObject, at: String, p: Problems): ModGuestProgram? {
        unknownKeys(o, setOf("name", "description", "command", "env"), at, p)
        val name = string(o, "name", p, required = true, label = "$at.name") ?: return null
        if (!GUEST_PROGRAM_NAME.matches(name)) p.add("$at.name must be 2 to 32 lowercase letters, digits or underscores, starting with a letter.")
        val description = string(o, "description", p, required = true, max = 300, label = "$at.description") ?: return null
        val argv = (o.opt("command") as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } }
        if (argv == null || argv.isEmpty() || argv.size > 32 || argv.any { it !is String || it.isEmpty() || it.length > 1000 || '\u0000' in it }) {
            p.add("$at.command must be a list of 1 to 32 strings, such as [\"node\", \"./server.js\"].")
            return null
        }
        val command = argv.map { it as String }
        command.filter { it.startsWith("./") }.forEach { arg ->
            if (!isSafeRelativePath(arg.removePrefix("./"))) p.add("$at.command: $arg must be a file inside the mod.")
        }
        if (command.first().startsWith("/")) p.add("$at.command must start with a program name or a ./ file in the mod, not an absolute path.")
        val env = when (val value = o.opt("env")) {
            null -> emptyMap()
            !is JSONObject -> { p.add("$at.env must be an object of strings."); emptyMap() }
            else -> value.keys().asSequence().toList().also { keys ->
                if (keys.size > 16) p.add("$at.env has more than 16 entries.")
                keys.filter { !ENV_NAME.matches(it) || it in RESERVED_ENV }.forEach { p.add("$at.env: $it is not a name a mod may set.") }
            }.associateWith { key -> value.opt(key) as? String ?: "".also { p.add("$at.env.$key must be a string.") } }
                .also { env -> if (env.values.any { it.length > 1000 }) p.add("$at.env values must be at most 1,000 characters.") }
        }
        return ModGuestProgram(name, description, command, env)
    }

    private fun parsePermissions(value: Any?, p: Problems): List<ModPermission> {
        if (value == null) return emptyList()
        if (value !is JSONArray) { p.add("\"permissions\" must be a list."); return emptyList() }
        if (value.length() > 32) p.add("\"permissions\" has more than 32 entries.")
        val seen = mutableSetOf<ModCapability>()
        return (0 until minOf(value.length(), 32)).mapNotNull { i ->
            val o = value.opt(i) as? JSONObject ?: run { p.add("permissions[$i] must be an object."); return@mapNotNull null }
            o.keys().asSequence().filter { it !in setOf("capability", "reason", "scope") }.forEach { p.add("Unknown field \"permissions[$i].$it\".") }
            val capId = string(o, "capability", p, required = true, label = "permissions[$i].capability") ?: return@mapNotNull null
            val capability = ModCapability.fromId(capId) ?: run { p.add("Unknown capability \"$capId\"."); return@mapNotNull null }
            if (!seen.add(capability)) p.add("Capability \"$capId\" is listed twice.")
            val reason = string(o, "reason", p, min = 1, max = 200, label = "permissions[$i].reason")
            var paths = emptyList<String>()
            var events = emptyList<String>()
            var hosts = emptyList<String>()
            var commands = emptyList<String>()
            o.opt("scope")?.let { scope ->
                if (scope !is JSONObject) { p.add("permissions[$i].scope must be an object."); return@let }
                scope.keys().asSequence().filter { it !in setOf("paths", "events", "hosts", "commands") }
                    .forEach { p.add("Unknown field \"permissions[$i].scope.$it\".") }
                paths = strings(scope, "paths", p, 32, "permissions[$i].scope.paths") { path ->
                    isSafeRelativePath(path) && !Regex("(^|/)\\.git(/|$)").containsMatchIn(path)
                }
                events = strings(scope, "events", p, 32, "permissions[$i].scope.events") { it.length <= 64 }
                hosts = strings(scope, "hosts", p, 16, "permissions[$i].scope.hosts") { it.length <= 253 && HOST.matches(it) }
                commands = strings(scope, "commands", p, 16, "permissions[$i].scope.commands") { it.length <= 64 && COMMAND.matches(it) }
            }
            if (capability == ModCapability.FILES_WRITE && paths.isEmpty()) {
                p.add("files.write needs \"scope.paths\" naming the files it may change.")
            }
            ModPermission(capability, reason, paths, events, hosts, commands)
        }
    }

    private fun parseCommand(o: JSONObject, i: Int, p: Problems): ModSlashCommand? {
        val at = "contributes.slashCommands[$i]"
        unknownKeys(o, setOf("name", "description", "template", "agents"), at, p)
        val name = string(o, "name", p, required = true, label = "$at.name") ?: return null
        if (!COMMAND_NAME.matches(name)) p.add("$at.name must be 2 to 31 lowercase letters, digits or dashes, starting with a letter.")
        val description = string(o, "description", p, required = true, max = 120, label = "$at.description") ?: return null
        val template = string(o, "template", p, max = 4096, label = "$at.template")
        val agents = o.opt("agents")?.let { value ->
            val ids = (value as? JSONArray)?.let { a -> (0 until a.length()).map { a.opt(it) } }
            if (ids == null || ids.size > 4 || ids.any { it !is String || it !in HARNESS_IDS }) {
                p.add("$at.agents must list harnesses from claude, deepseek, antigravity, codex.")
                null
            } else ids.map { HARNESS_IDS.getValue(it as String) }.toSet()
        }
        return ModSlashCommand(name, description, template, agents)
    }

    private fun parseTemplate(o: JSONObject, i: Int, p: Problems): ModPromptTemplate? {
        val at = "contributes.promptTemplates[$i]"
        unknownKeys(o, setOf("id", "title", "template"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val title = string(o, "title", p, required = true, min = 1, max = 60, label = "$at.title") ?: return null
        val template = string(o, "template", p, required = true, max = 4096, label = "$at.template") ?: return null
        return ModPromptTemplate(id, title, template)
    }

    private fun parseSetting(o: JSONObject, i: Int, p: Problems): ModSetting? {
        val at = "contributes.settings[$i]"
        unknownKeys(o, setOf("key", "title", "type", "default", "options", "maxLength", "description"), at, p)
        val key = string(o, "key", p, required = true, label = "$at.key") ?: return null
        if (!SETTING_KEY.matches(key)) p.add("$at.key must start with a letter and use lowercase letters, digits or _.")
        val title = string(o, "title", p, required = true, min = 1, max = 60, label = "$at.title") ?: return null
        val typeId = string(o, "type", p, required = true, label = "$at.type") ?: return null
        val type = ModSettingType.entries.firstOrNull { it.id == typeId }
            ?: run { p.add("$at.type must be bool, enum, string, number or secret."); return null }
        val options = strings(o, "options", p, 16, "$at.options") { it.length <= 40 }
        val maxLength = int(o, "maxLength", p, label = "$at.maxLength")
        if (maxLength != null && maxLength !in 1..1024) p.add("$at.maxLength must be 1 to 1024.")
        val description = string(o, "description", p, max = 200, label = "$at.description")
        val hasDefault = o.has("default")
        val raw = o.opt("default")
        val default: Any? = when (type) {
            ModSettingType.SECRET -> { if (hasDefault) p.add("$at: a secret setting cannot have a default."); null }
            ModSettingType.BOOL -> (raw as? Boolean) ?: run { p.add("$at.default must be true or false."); null }
            ModSettingType.NUMBER -> (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: run { p.add("$at.default must be a number."); null }
            ModSettingType.STRING -> (raw as? String)?.also {
                if (maxLength != null && it.length > maxLength) p.add("$at.default is longer than maxLength.")
            } ?: run { p.add("$at.default must be text."); null }
            ModSettingType.ENUM -> {
                if (options.isEmpty()) p.add("$at needs \"options\".")
                (raw as? String)?.also { if (it !in options) p.add("$at.default must be one of its options.") }
                    ?: run { p.add("$at.default must be one of its options."); null }
            }
        }
        return ModSetting(key, title, type, default, options, maxLength, description)
    }

    private fun parseTextFile(o: JSONObject, i: Int, p: Problems): ModTextFile? {
        val at = "contributes.rules[$i]"
        unknownKeys(o, setOf("id", "file", "defaultEnabled"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val file = string(o, "file", p, required = true, label = "$at.file") ?: return null
        if (!isSafeRelativePath(file) || !file.endsWith(".md")) p.add("$at.file must be a relative .md path.")
        if (o.has("defaultEnabled") && o.opt("defaultEnabled") != false) p.add("$at.defaultEnabled can only be false; rules start off.")
        return ModTextFile(id, file)
    }

    private fun parseSkill(o: JSONObject, i: Int, p: Problems): ModSkillDir? {
        val at = "contributes.skills[$i]"
        unknownKeys(o, setOf("id", "dir"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val dir = string(o, "dir", p, required = true, label = "$at.dir") ?: return null
        if (!isSafeRelativePath(dir)) p.add("$at.dir must be a relative path.")
        return ModSkillDir(id, dir)
    }

    private fun parseHook(o: JSONObject, i: Int, p: Problems): ModDeclarativeHook? {
        val at = "contributes.hooks[$i]"
        unknownKeys(o, setOf("id", "on", "action", "filter"), at, p)
        val id = string(o, "id", p, required = true, label = "$at.id") ?: return null
        if (!ITEM_ID.matches(id)) p.add("$at.id must be lowercase letters, digits or dashes.")
        val onId = string(o, "on", p, required = true, label = "$at.on") ?: return null
        val event = ModEvent.fromId(onId) ?: run { p.add("$at.on: unknown event \"$onId\"."); return null }
        val action = o.opt("action") as? JSONObject ?: run { p.add("$at.action must be an object."); return null }
        val type = string(action, "type", p, required = true, label = "$at.action.type") ?: return null
        val parsed: ModHookAction = when (type) {
            "notify" -> {
                unknownKeys(action, setOf("type", "title", "body"), "$at.action", p)
                ModHookAction.Notify(
                    string(action, "title", p, required = true, max = 80, label = "$at.action.title") ?: return null,
                    string(action, "body", p, max = 300, label = "$at.action.body").orEmpty(),
                )
            }
            "ui.toast" -> {
                unknownKeys(action, setOf("type", "text"), "$at.action", p)
                ModHookAction.Toast(string(action, "text", p, required = true, max = 160, label = "$at.action.text") ?: return null)
            }
            "chat.post" -> {
                unknownKeys(action, setOf("type", "text"), "$at.action", p)
                ModHookAction.ChatPost(string(action, "text", p, required = true, max = 2048, label = "$at.action.text") ?: return null)
            }
            "prompt.draft" -> {
                unknownKeys(action, setOf("type", "text"), "$at.action", p)
                ModHookAction.PromptDraft(string(action, "text", p, required = true, max = 4096, label = "$at.action.text") ?: return null)
            }
            "files.append" -> {
                unknownKeys(action, setOf("type", "path", "line"), "$at.action", p)
                val path = string(action, "path", p, required = true, label = "$at.action.path") ?: return null
                if (!isSafeRelativePath(path) || path.contains('*')) p.add("$at.action.path must be one relative file path.")
                ModHookAction.FilesAppend(path, string(action, "line", p, required = true, max = 1024, label = "$at.action.line") ?: return null)
            }
            else -> { p.add("$at.action.type \"$type\" is not one of notify, ui.toast, chat.post, prompt.draft, files.append."); return null }
        }
        var status: String? = null
        var harness: AgentKind? = null
        o.opt("filter")?.let { filter ->
            if (filter !is JSONObject) { p.add("$at.filter must be an object."); return@let }
            unknownKeys(filter, setOf("status", "harness"), "$at.filter", p)
            status = string(filter, "status", p, label = "$at.filter.status")
            if (status != null && status !in setOf("completed", "failed", "stopped")) p.add("$at.filter.status must be completed, failed or stopped.")
            harness = string(filter, "harness", p, label = "$at.filter.harness")?.let { HARNESS_IDS[it] ?: run { p.add("$at.filter.harness is not a harness."); null } }
        }
        return ModDeclarativeHook(id, event, parsed, status, harness)
    }

    private fun parseIntegrity(value: Any?, p: Problems): Map<String, String> {
        if (value == null) return emptyMap()
        if (value !is JSONObject) { p.add("\"integrity\" must be an object of path to SHA-256."); return emptyMap() }
        if (value.length() > 300) p.add("\"integrity\" lists more than 300 files.")
        val result = linkedMapOf<String, String>()
        value.keys().forEach { path ->
            val hash = value.opt(path) as? String
            if (!isSafeRelativePath(path)) p.add("integrity path \"$path\" is not a safe relative path.")
            if (hash == null || !Regex("^[a-f0-9]{64}$").matches(hash)) p.add("integrity[\"$path\"] must be a lowercase SHA-256.")
            else result[path] = hash
        }
        return result
    }

    private fun parseDashboard(value: Any, p: Problems): ModDashboard? {
        if (value !is JSONObject) { p.add("\"dashboard\" must be an object."); return null }
        unknownKeys(value, setOf("pane", "actions"), "dashboard", p)
        val pane = string(value, "pane", p, required = true, label = "dashboard.pane") ?: return null
        if (!PANE_ID.matches(pane)) p.add("dashboard.pane must be lowercase letters, digits or dashes.")
        val actions = strings(value, "actions", p, 8, "dashboard.actions") { COMMAND_NAME.matches(it) }
        checkUnique(actions, "dashboard action", p)
        return ModDashboard(pane, actions)
    }

    private fun checkVars(text: String, allowed: Set<String>, where: String, p: Problems) {
        templateVars(text).filter { it !in allowed }.forEach { p.add("$where uses {{$it}}, which is not available there.") }
    }

    private fun checkUnique(values: List<String>, what: String, p: Problems) {
        values.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { p.add("Duplicate $what \"$it\".") }
    }

    private fun unknownKeys(o: JSONObject, allowed: Set<String>, at: String, p: Problems) {
        o.keys().asSequence().filter { it !in allowed }.forEach { p.add("Unknown field \"$at.$it\".") }
    }

    private fun string(
        o: JSONObject,
        key: String,
        p: Problems,
        required: Boolean = false,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        label: String = key,
    ): String? {
        if (!o.has(key) || o.isNull(key)) {
            if (required) p.add("\"$label\" is required.")
            return null
        }
        val value = o.opt(key) as? String ?: run { p.add("\"$label\" must be text."); return null }
        if (value.length < min) p.add("\"$label\" is empty.")
        if (value.length > max) p.add("\"$label\" is longer than $max characters.")
        return value
    }

    private fun int(o: JSONObject, key: String, p: Problems, required: Boolean = false, label: String = key): Int? {
        if (!o.has(key) || o.isNull(key)) {
            if (required) p.add("\"$label\" is required.")
            return null
        }
        val value = o.opt(key)
        if (value !is Int && !(value is Long && value in Int.MIN_VALUE..Int.MAX_VALUE)) {
            p.add("\"$label\" must be a whole number.")
            return null
        }
        return (value as Number).toInt()
    }

    private fun array(o: JSONObject, key: String, p: Problems, max: Int): List<JSONObject> {
        val value = o.opt(key) ?: return emptyList()
        if (value !is JSONArray) { p.add("\"contributes.$key\" must be a list."); return emptyList() }
        if (value.length() > max) p.add("\"contributes.$key\" has more than $max entries.")
        return (0 until minOf(value.length(), max)).mapNotNull { i ->
            value.opt(i) as? JSONObject ?: run { p.add("contributes.$key[$i] must be an object."); null }
        }
    }

    private fun strings(o: JSONObject, key: String, p: Problems, max: Int, label: String, valid: (String) -> Boolean): List<String> {
        val value = o.opt(key) ?: return emptyList()
        if (value !is JSONArray) { p.add("\"$label\" must be a list."); return emptyList() }
        if (value.length() > max) p.add("\"$label\" has more than $max entries.")
        return (0 until minOf(value.length(), max)).mapNotNull { i ->
            val item = value.opt(i) as? String
            if (item == null || !valid(item)) { p.add("$label[$i] is not valid."); null } else item
        }
    }
}
