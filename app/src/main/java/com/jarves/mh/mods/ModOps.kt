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
    AGENT_LIST("agent.list", ModCapability.AGENT_ASK),
    AGENT_ASK("agent.ask", ModCapability.AGENT_ASK, needsProject = true, slow = true),
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

/** What a mod tool answered: the text the agent reads, and whether it is an error. */
data class ModToolResult(val text: String, val isError: Boolean = false)

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
        else -> null
    }

    /** A payload made from the event's template variables: `terminal.exitCode` becomes `exitCode`. */
    fun payload(vars: Map<String, String>): JSONObject = JSONObject().apply {
        vars.forEach { (key, value) -> put(key.substringAfter('.'), value) }
    }

    /** Fields a mod sees only with the capability: the agent's reply needs chat.read, as for simple hooks. */
    fun private(event: ModEvent): Map<String, ModCapability> =
        if (event == ModEvent.ASSISTANT_MESSAGE_COMPLETED) mapOf("text" to ModCapability.CHAT_READ) else emptyMap()
}
