package com.jarves.mh.mods

import com.jarves.mh.model.brain.ExecutionPlan
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * The `$` calls mod code can make in this version (spec v2 §4.3), each with the capability it
 * needs. [needsProject] calls work only while a project is open (spec v2 §3.3 app scope).
 * `$.log` and `$.clock.now` are answered inside the mod's isolate and never reach the app.
 */
enum class ModOp(
    val id: String,
    val capability: ModCapability?,
    val needsProject: Boolean = false,
    /** Waits on something outside the mod (a timer, an agent task), so it runs beside the mod's other calls. */
    val slow: Boolean = false,
) {
    STORE_GET("store.get", ModCapability.STORAGE),
    STORE_SET("store.set", ModCapability.STORAGE),
    STORE_DELETE("store.delete", ModCapability.STORAGE),
    STORE_KEYS("store.keys", ModCapability.STORAGE),
    CLOCK_SLEEP("clock.sleep", null, slow = true),
    CLOCK_AFTER("clock.after", null),
    CLOCK_EVERY("clock.every", null),
    CLOCK_CANCEL("clock.cancel", null),
    UI_TOAST("ui.toast", ModCapability.UI_TOAST),
    UI_STATUS("ui.status", ModCapability.UI_STATUS),
    UI_SUMMARY("ui.summary", ModCapability.UI_STATUS),
    UI_NOTIFY("ui.notify", ModCapability.NOTIFY),
    UI_OPEN("ui.open", ModCapability.UI_PANE),
    UI_CLOSE("ui.close", ModCapability.UI_PANE),
    UI_REDRAW("ui.redraw", null),
    DASHBOARD_POST("dashboard.post", null),
    UI_POST("ui.post", ModCapability.UI_WEBPANE),
    COMMAND_REGISTER("command.register", null),
    COMMAND_UNREGISTER("command.unregister", null),
    APP_INFO("app.info", ModCapability.APP_INFO),
    PROJECT_INFO("project.info", ModCapability.PROJECT_INFO),
    PLAN_CURRENT("plan.current", ModCapability.PROJECT_INFO, needsProject = true),
    CHAT_MESSAGES("chat.messages", ModCapability.CHAT_READ, needsProject = true),
    CHAT_APPEND("chat.append", ModCapability.CHAT_POST, needsProject = true),
    PROMPT_DRAFT("prompt.draft", ModCapability.PROMPT_DRAFT, needsProject = true),
    CHANGES_LIST("changes.list", ModCapability.CHANGES_READ, needsProject = true),
    CHANGES_UNDO("changes.undo", ModCapability.CHANGES_UNDO, needsProject = true, slow = true),
    TOOL_REGISTER("tool.register", ModCapability.TOOLS_PROVIDE),
    TOOL_UNREGISTER("tool.unregister", ModCapability.TOOLS_PROVIDE),
    FS_READ("fs.read", ModCapability.FILES_READ, needsProject = true),
    FS_LIST("fs.list", ModCapability.FILES_READ, needsProject = true),
    FS_STAT("fs.stat", ModCapability.FILES_READ, needsProject = true),
    FS_EXISTS("fs.exists", ModCapability.FILES_READ, needsProject = true),
    FS_WRITE("fs.write", ModCapability.FILES_WRITE, needsProject = true),
    SECRETS_GET("secrets.get", ModCapability.SECRETS),
    SECRETS_SET("secrets.set", ModCapability.SECRETS),
    PROMPT_SEND("prompt.send", ModCapability.PROMPT_SEND, needsProject = true),
    BROWSER_OPEN("browser.open", ModCapability.BROWSER_OPEN),
    HTTP_FETCH("http.fetch", ModCapability.NET_FETCH, slow = true),
    MODEL_COMPLETE("model.complete", ModCapability.MODEL_COMPLETE, slow = true),
    MODEL_USAGE("model.usage", ModCapability.MODEL_COMPLETE),
    AGENT_LIST("agent.list", ModCapability.AGENT_ASK),
    AGENT_ASK("agent.ask", ModCapability.AGENT_ASK, needsProject = true, slow = true),
    AGENT_STOP("agent.stop", ModCapability.AGENT_ASK),
    MODS_TOOLS("mods.tools", ModCapability.MODS_CALL),
    MODS_CALL("mods.call", ModCapability.MODS_CALL, slow = true),
    GIT_STATUS("git.status", ModCapability.GIT_READ, needsProject = true, slow = true),
    GIT_DIFF("git.diff", ModCapability.GIT_READ, needsProject = true, slow = true),
    GIT_LOG("git.log", ModCapability.GIT_READ, needsProject = true, slow = true),
    GIT_COMMIT("git.commit", ModCapability.GIT_WRITE, needsProject = true, slow = true),
    GIT_BRANCH("git.branch", ModCapability.GIT_WRITE, needsProject = true, slow = true),
    GIT_SWITCH("git.switch", ModCapability.GIT_WRITE, needsProject = true, slow = true),
    TERMINAL_RUN("terminal.run", ModCapability.TERMINAL_RUN, needsProject = true, slow = true),
    BUILD_RUN("build.run", ModCapability.BUILD_RUN, needsProject = true, slow = true),
    MEMORY_LIST("memory.list", ModCapability.MEMORY_READ, needsProject = true),
    UI_THEME("ui.theme", ModCapability.UI_THEME),
    PROJECTS_LIST("projects.list", ModCapability.PROJECTS),
    PROJECTS_OPEN("projects.open", ModCapability.PROJECTS, slow = true),
    PROJECTS_CREATE("projects.create", ModCapability.PROJECTS, slow = true),
    CHATS_LIST("chats.list", ModCapability.PROJECTS, needsProject = true),
    CHATS_OPEN("chats.open", ModCapability.PROJECTS, needsProject = true, slow = true),
    CHATS_CREATE("chats.create", ModCapability.PROJECTS, needsProject = true, slow = true),
    CHATS_READ("chats.read", ModCapability.CHAT_READ, needsProject = true, slow = true),
    CLIPBOARD_READ("clipboard.read", ModCapability.CLIPBOARD),
    CLIPBOARD_WRITE("clipboard.write", ModCapability.CLIPBOARD),
    INTENT_SHARE("intent.share", ModCapability.DEVICE_SHARE),
    INTENT_OPEN("intent.open", ModCapability.DEVICE_SHARE),
    STORAGE_PICK("storage.pick", ModCapability.STORAGE_PICK, slow = true),
    SETTINGS_GET("settings.get", ModCapability.SETTINGS_READ),
    SETTINGS_SET("settings.set", ModCapability.SETTINGS_WRITE, slow = true),
    RUNTIME_INSTALL("runtime.install", ModCapability.RUNTIME, needsProject = true, slow = true),
    RUNTIME_SERVE("runtime.serve", ModCapability.RUNTIME, needsProject = true, slow = true),
    RUNTIME_STOP("runtime.stop", ModCapability.RUNTIME),
    RUNTIME_SERVERS("runtime.servers", ModCapability.RUNTIME),
    ;

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: String): ModOp? = byId[id]
    }
}

/** A slash command a running mod registered, or declared in mod.json without a template. */
data class ModCodeCommand(
    val modId: String,
    val modName: String,
    val name: String,
    val description: String,
    val argumentHint: String? = null,
)

/**
 * A tool a running mod gave the agents with `$.tool.register` (spec v2 P8). Agents see it as
 * [exposed]: Claude as `mcp__mods__<exposed>`, Codex through its harness-tools MCP server, and the
 * other harnesses through `harness-tools mod <exposed> '<json>'`.
 */
data class ModTool(
    val modId: String,
    val modName: String,
    val name: String,
    val description: String,
    /** The JSON Schema of its input, as JSON text. */
    val inputSchema: String,
) {
    val exposed: String get() = exposedName(modId, name)

    companion object {
        val NAME = Regex("^[a-z][a-z0-9_]{1,39}$")
        fun exposedName(modId: String, name: String): String =
            ("mod_" + modId.substringAfterLast('.').lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20) + "_" + name).take(64)
    }
}

/**
 * A command a mod runs in the project's Linux guest, in the project folder. [exclusive] commands
 * (`terminal.run`, git changes) wait for no task, terminal command or build to be running, and run one at a time.
 */
data class ModGuestCommand(val argv: List<String>, val timeoutMs: Long, val maxBytes: Int, val exclusive: Boolean = false)

/** How a [ModGuestCommand] ended: its exit code (-1 on timeout) and the tail of its output. */
data class ModGuestResult(val exitCode: Int, val output: String, val timedOut: Boolean = false, val truncated: Boolean = false)

/** A message between a mod's code and one of its open web panels. */
data class ModPanelMessage(val modId: String, val panelId: String, val text: String)

/** What a mod tool answered: the text the agent reads, and whether it is an error. */
data class ModToolResult(val text: String, val isError: Boolean = false)

/** Where a mod's secrets live ([ModSecrets] on the device). */
interface ModSecretStore {
    fun get(modId: String, key: String): String?
    fun set(modId: String, key: String, value: String?)
}

/** What a mod command's `command.run` hook answered. */
sealed interface ModCodeCommandOutcome {
    /** Shown in the chat as the mod's reply; the agent does not see it. */
    data class Reply(val text: String) : ModCodeCommandOutcome
    /** Sent to the agent as if the user typed it. */
    data class Prompt(val text: String) : ModCodeCommandOutcome
    /** Nothing to show. */
    data object Nothing : ModCodeCommandOutcome
    data class Failed(val message: String) : ModCodeCommandOutcome
}

internal object ModJson {
    /** [value] as a JavaScript literal for a script the host evaluates. */
    fun literal(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Boolean -> value.toString()
        is Number -> if (value is Double && !value.isFinite()) "null" else JSONObject.numberToString(value)
        is JSONObject, is JSONArray -> value.toString()
        is Map<*, *> -> JSONObject(value).toString()
        is Collection<*> -> JSONArray(value).toString()
        else -> JSONObject.quote(value.toString())
    }

    /** Where an event happened, as mods see it in `e.project`. */
    fun project(context: ModEventContext?): Any {
        val id = context?.projectId ?: return JSONObject.NULL
        return JSONObject()
            .put("id", id)
            .put("name", context.projectName.orEmpty())
            .put("harness", context.harness?.let(ModManifestParser::harnessId) ?: JSONObject.NULL)
            .put("chatId", context.chatId ?: JSONObject.NULL)
    }

    fun plan(plan: ExecutionPlan?): Any {
        plan ?: return JSONObject.NULL
        return JSONObject()
            .put("title", plan.title)
            .put("status", plan.status.name.lowercase(Locale.ROOT))
            .put("current", plan.currentStepIndex)
            .put("steps", JSONArray(plan.steps.sortedBy { it.stepOrder }.map { step ->
                JSONObject()
                    .put("title", step.title)
                    .put("status", step.status.name.lowercase(Locale.ROOT))
                    .put("summary", step.resultSummary ?: JSONObject.NULL)
            }))
    }
}

/**
 * The events simple hooks know (`ModEvent`, v1 names) under their mod-code names (spec v2 §4.2).
 * Events with no entry here reach mod code through their own call sites, or not yet.
 */
/**
 * The app settings mods may read (`settings.read`) and, in Developer mode, change (`settings.write`;
 * mod plan row 17). Changes go through the same setters as the app's own screens. API keys,
 * sign-ins and the keystore are not settings here, so no mod can read or change them.
 */
object ModAppSettings {
    val KEYS = listOf("harness", "model", "effort", "theme", "antigravityBalancing", "antigravityFailover", "codexAccountBalancing")
    private val THEMES = setOf("system", "light", "dark")
    private val BALANCING = setOf("least_recently_used", "round_robin", "primary_only")

    fun requireKey(key: String) = require(key in KEYS) {
        "\"$key\" isn't a setting mods can use. They can use ${KEYS.joinToString()}; API keys, sign-ins and the keystore never."
    }

    /** [value] for [key] as the app stores it, or an error that says what fits. */
    fun check(key: String, value: Any?): Any {
        requireKey(key)
        fun text(max: Int): String {
            val text = (value as? String)?.trim()
            require(text != null && text.length <= max && text.none { it.isISOControl() }) { "\$.settings.set: $key must be text up to $max characters" }
            return text
        }
        fun oneOf(options: Set<String>): String {
            val text = (value as? String)?.trim()?.lowercase()
            require(text != null && text in options) { "\$.settings.set: $key must be one of ${options.joinToString()}" }
            return text
        }
        return when (key) {
            "harness" -> oneOf(setOf("claude", "deepseek", "antigravity", "codex"))
            "model" -> text(200)
            "effort" -> text(40).also { require(it.isNotEmpty()) { "\$.settings.set: effort can't be empty" } }
            "theme" -> oneOf(THEMES)
            "antigravityBalancing" -> oneOf(BALANCING)
            else -> value as? Boolean ?: throw IllegalArgumentException("\$.settings.set: $key must be true or false")
        }
    }
}

/** Links `$.intent.open` may hand to other apps; the user picks the app in the system chooser. */
object ModIntents {
    val SCHEMES = setOf("https", "http", "mailto", "tel", "sms", "geo")

    fun check(uri: String): String {
        val text = uri.trim()
        val scheme = text.substringBefore(':', "").lowercase()
        require(text.length in 4..2_000 && scheme in SCHEMES && text.none { it.isISOControl() }) {
            "\$.intent.open(uri) opens ${SCHEMES.joinToString { "$it:" }} links only"
        }
        return text
    }
}

/**
 * Guest runtime control (mod plan row 20, `runtime`, Developer mode): package installs through
 * apt, pip or npm, and the checks on them. Package names are checked so nothing reaches the
 * package manager as an option.
 */
object ModRuntime {
    /** Installs are refused when less than this is free where the guest lives. */
    const val MIN_FREE_BYTES = 1024L * 1024 * 1024
    const val MAX_PACKAGES = 16
    val MANAGERS = listOf("apt", "pip", "npm")
    private val PACKAGE = mapOf(
        "apt" to Regex("^[a-z0-9][a-z0-9.+-]{0,99}(=[A-Za-z0-9.+:~-]{1,60})?$"),
        "pip" to Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,99}(\\[[A-Za-z0-9,._-]{1,60}\\])?((==|>=|<=|~=|!=|>|<)[A-Za-z0-9.*+!-]{1,40})?$"),
        "npm" to Regex("^(@[a-z0-9][a-z0-9._-]{0,60}/)?[a-z0-9][a-z0-9._-]{0,99}(@[A-Za-z0-9.^~*<>=+-]{1,40})?$"),
    )

    /** The bash script that installs [packages] with [manager]; throws when one is not a plain package name. */
    fun installScript(manager: String, packages: List<String>): String {
        val names = PACKAGE[manager] ?: throw IllegalArgumentException("\$.runtime.install: manager is ${MANAGERS.joinToString()}")
        require(packages.size in 1..MAX_PACKAGES) { "\$.runtime.install takes 1 to $MAX_PACKAGES packages" }
        packages.firstOrNull { !names.matches(it) }?.let { throw IllegalArgumentException("\$.runtime.install: \"${it.take(60)}\" isn't a valid $manager package name") }
        val list = packages.joinToString(" ") { "'$it'" }
        return when (manager) {
            "apt" -> "export DEBIAN_FRONTEND=noninteractive\napt-get install -y --no-install-recommends $list || { apt-get update && apt-get install -y --no-install-recommends $list; }"
            "pip" -> "python3 -m pip install --no-input --disable-pip-version-check $list"
            else -> "npm install -g --no-fund --no-audit $list"
        }
    }
}

/** Accent colors a mod may give the app (`$.ui.theme`): design-token colors that keep white text readable. */
object ModTheme {
    val ACCENTS = listOf("blue", "indigo", "purple", "pink", "red", "orange", "green", "teal", "brown")
}

internal object ModCodeEvents {
    fun name(event: ModEvent): String? = when (event) {
        ModEvent.TASK_STARTED -> "run.start"
        ModEvent.TASK_FINISHED -> "run.end"
        ModEvent.FILES_CHANGED -> "files.changed"
        ModEvent.TOOL_COMPLETED -> "tool.result"
        ModEvent.TERMINAL_COMMAND_FINISHED -> "terminal.end"
        ModEvent.ASSISTANT_MESSAGE_COMPLETED -> "message.end"
        ModEvent.CHAT_SWITCHED -> "chat.switch"
        ModEvent.BUILD_FINISHED -> "build.end"
        ModEvent.PROJECT_OPENED -> "project.open"
        else -> null
    }

    /** A payload made from the event's template variables: `terminal.exitCode` becomes `exitCode`. */
    fun payload(vars: Map<String, String>): JSONObject = JSONObject().apply {
        vars.forEach { (key, value) -> put(key.substringAfter('.'), value) }
    }

    /**
     * Fields a mod sees only with the capability: the agent's reply needs chat.read, as for simple
     * hooks; a build's log (which quotes project code) needs files.read.
     */
    fun private(event: ModEvent): Map<String, ModCapability> = when (event) {
        ModEvent.ASSISTANT_MESSAGE_COMPLETED -> mapOf("text" to ModCapability.CHAT_READ)
        ModEvent.BUILD_FINISHED -> mapOf("log" to ModCapability.FILES_READ)
        else -> emptyMap()
    }
}

/**
 * Whether an approval request stays inside the project folder, so a mod allowed `tools.approve`
 * may approve it. Paths must be relative (no `..`) or under one of the project's roots. A command
 * may not use `..`, `~`, `$`, backticks, `cd -`, or an absolute path outside the roots
 * (`/dev/null` aside). It is a cautious check, not a sandbox: anything unsure counts as outside,
 * and then the user decides as before.
 */
object ModApproval {
    private val ABSOLUTE = Regex("""(?<![\w.~-])/[^\s'"`;|&<>(){}]*""")

    fun inside(paths: List<String>, command: String?, roots: List<String>): Boolean {
        val clean = roots.map { it.trimEnd('/') }.filter { it.length > 1 }
        if (clean.isEmpty()) return false
        fun under(path: String) = path.split('/').none { it == ".." } && clean.any { path == it || path.startsWith("$it/") }
        val pathsOk = paths.all { raw ->
            val path = raw.trim()
            path.isEmpty() || (!path.startsWith("~") && path.split('/').none { it == ".." } && (!path.startsWith("/") || under(path)))
        }
        if (!pathsOk) return false
        val text = command ?: return true
        if (".." in text || '~' in text || '$' in text || '`' in text || Regex("""\bcd\s+-""").containsMatchIn(text)) return false
        return ABSOLUTE.findAll(text).all { it.value == "/dev/null" || under(it.value) }
    }
}
