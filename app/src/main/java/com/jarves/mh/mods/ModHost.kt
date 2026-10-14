package com.jarves.mh.mods

import android.content.Context
import com.jarves.mh.BuildConfig
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.runtime.SlashCommandEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** Where mod actions that need the app's screen land; set by the screen while it exists. */
interface ModHostEffects : ModHookEffects {
    /** The newest [limit] messages of [context]'s chat, oldest first, as (from, text); null when that chat is not open. */
    fun chatMessages(context: ModEventContext, limit: Int): List<Pair<String, String>>?

    /**
     * One answer from the user's active provider (spec v2 P10 `$.model.complete`): `{ text,
     * inputTokens, outputTokens, model }`. Throws with a reason the mod can show when there is none.
     */
    suspend fun modelComplete(mod: InstalledMod, prompt: String, system: String?, maxTokens: Int): JSONObject

    /** Opens an https page in the browser; false when it can't. */
    fun openUrl(mod: InstalledMod, url: String): Boolean

    /** The plan of the task in [context]'s chat, when the app made one. */
    fun currentPlan(context: ModEventContext): ExecutionPlan?

    /** The harnesses, as `{ id, name, installed, current }`, for `$.agent.list()`. */
    fun agents(): JSONArray

    /**
     * Runs [prompt] as a task in [context]'s chat on [harness] (null: the chat's current one),
     * once the chat is free, and answers when the task ends: `{ status, text, taskId, harness,
     * chatId }`. [options] may pick a model and effort, and another chat of the project.
     * Fails when the chat closes, the task can't start, or the user stopped the agent since their
     * last message; answers `status: "timeout"` after [timeoutMs] while the task goes on.
     */
    suspend fun askAgent(mod: InstalledMod, harness: AgentKind?, prompt: String, context: ModEventContext, timeoutMs: Long, options: ModAskOptions): JSONObject

    /** Stops [taskId] when it is a running task a mod started with `$.agent.ask`; false otherwise. */
    fun stopAgent(mod: InstalledMod, taskId: String): Boolean

    /**
     * A `run.end` hook's `{ followUp }`: queued in [context]'s chat as the next prompt. False when
     * that chat is no longer open, the user stopped the agent since their last message, or mods
     * already chained [limit] follow-ups since that message.
     */
    fun followUp(mod: InstalledMod, text: String, context: ModEventContext, limit: Int): Boolean

    /** The agent's file changes in [context]'s project that the user hasn't kept or undone; null when it isn't open. */
    fun pendingChanges(context: ModEventContext): JSONArray?

    /** Undoes the agent's change to [path] in [context]'s project from its checkpoint; false when there is none. */
    suspend fun undoChange(context: ModEventContext, path: String): Boolean

    /** Runs [command] in [context]'s project in the Linux guest; cancelling the call kills it. */
    suspend fun guestRun(context: ModEventContext, command: ModGuestCommand): ModGuestResult

    /** Builds [context]'s Android app (and installs it when [install]) and answers `{ status, message }` when done. */
    suspend fun runBuild(mod: InstalledMod, context: ModEventContext, install: Boolean): JSONObject

    /** The project memory of [context]'s project: `{ key, value, source, updatedAt }`; null when it isn't open. */
    fun projectMemory(context: ModEventContext): JSONArray?

    /**
     * Runs [write] (a mod's `$.fs.write` to [path]) so the change is kept like an agent's: the
     * project's checkpoint keeps the file as it was, and the path shows in Changes, where Undo
     * restores it. Answers what [write] answered.
     */
    suspend fun checkpointedWrite(context: ModEventContext, path: String, write: () -> Int): Int
}

/** What `$.agent.ask` may pick besides the harness: model and effort ids, and [chat] ("new" or a chat id). */
data class ModAskOptions(val model: String? = null, val effort: String? = null, val chat: String? = null)

enum class ModEngineState(val label: String) {
    OFF("Off"),
    STARTING("Starting"),
    ON("Running"),
    PAUSED("Paused"),
    UNAVAILABLE("Not available"),
}

enum class ModRunState(val label: String) {
    WAITING("Waiting"),
    STARTING("Starting"),
    RUNNING("Running"),
    RESTARTING("Restarting"),
    CRASHED("Crashed"),
    BLOCKED("Not running"),
}

/** How one logic mod is doing. [message] explains a state other than running. */
data class ModRuntimeInfo(
    val state: ModRunState,
    val message: String? = null,
    val status: String? = null,
    val summary: String? = null,
    val strikes: Int = 0,
    val commands: List<ModCodeCommand> = emptyList(),
    val tools: List<ModTool> = emptyList(),
)

data class ModHostSnapshot(
    val engine: ModEngineState = ModEngineState.OFF,
    val engineMessage: String? = null,
    /** Logic mods only (mods with a hooks module), by mod id. */
    val mods: Map<String, ModRuntimeInfo> = emptyMap(),
) {
    val commands: List<ModCodeCommand> get() = mods.values.flatMap { it.commands }
    val tools: List<ModTool> get() = mods.values.flatMap { it.tools }
}

data class ModLogEntry(val time: Long, val level: String, val text: String)

/** A panel a mod draws: its dashboard (manifest `dashboard.pane`) or one it opened with `$.ui.open`. */
data class ModPane(val id: String, val title: String) {
    val surface: String get() = ModHost.paneSurface(id)
}

/** What one surface shows: the mod's last tree, or why there is none. */
data class ModDrawing(val tree: ModNode? = null, val message: String? = null, val drawn: Boolean = false)

/** One logic mod's surfaces (spec v2 §4.4, §6.6), for the Mods screen and for projects. */
data class ModSurfaces(
    val modId: String,
    val name: String,
    /** The mod's folder; Image elements load files from it. */
    val dir: File,
    /** The dashboard pane first, then panes the mod opened. */
    val panes: List<ModPane>,
    val dashboard: String?,
    /** By surface key ([ModHost.paneSurface] or [ModHost.BAND]). */
    val drawings: Map<String, ModDrawing>,
    /** Places it shows inside projects, switched on and allowed. */
    val shownIn: Set<ModPlace>,
    val status: String?,
)

/**
 * What the mods' gate hooks decided about an action (spec v2 §4.2 host gates). Every gate fails
 * open: a hook that throws, runs out of time or answers nonsense passes the action on unchanged.
 */
data class ModGateOutcome(
    /** The action's input after the mods' rewrites (only fields the app sent can change). */
    val input: JSONObject,
    /** "deny", "drop" or "confirm" when a mod answered so; null when every mod passed it on. */
    val verdict: String? = null,
    val reason: String? = null,
    val modId: String? = null,
    val modName: String? = null,
    /** `{ context }` answers and `prompt.compose` sections, as (mod id, mod name, text). */
    val notes: List<Triple<String, String, String>> = emptyList(),
)

/** Time limits; tests shorten them. */
data class ModHostLimits(
    /** A call into the isolate must return within this, or the isolate is stuck. */
    val evalTimeoutMs: Long = 3_000,
    /** After activity, the isolate must answer a ping within [evalTimeoutMs] once this has passed. */
    val settleCheckMs: Long = 2_000,
    val loadTimeoutMs: Long = 10_000,
    val stopGraceMs: Long = 60_000,
    val endBudgetMs: Long = 1_500,
    val eventBudgetMs: Long = 10_000,
    val quickBudgetMs: Long = 1_000,
    /** Own time for a gate on the user's tap path: prompt.submit, terminal.command, files.op, build.start. */
    val gateBudgetMs: Long = 3_000,
    /** Wall time a gate may wait on one mod, including its `$` calls, before passing the action on. */
    val gateWaitMs: Long = 8_000,
    /** Model tokens one mod may use a day through `$.model.complete` (prompt and answer). */
    val modelTokensPerDay: Long = 50_000,
    /** Wall time a mod tool may take, including its `$` calls, before the agent hears it timed out. */
    val toolWaitMs: Long = 25_000,
    /** Own time for `prompt.compose`. */
    val composeBudgetMs: Long = 2_000,
    /** Own time for Claude's `tool.call` gate. */
    val toolBudgetMs: Long = 5_000,
    /** Own time for one `ui.render` (spec v2 §4.2). */
    val renderBudgetMs: Long = 250,
    /** At most this often per surface: 10 redraws a second. */
    val redrawGapMs: Long = 100,
    val backoffMs: List<Long> = listOf(1_000, 5_000, 30_000),
    val heapBytes: Long = 16L * 1024 * 1024,
)

/**
 * Runs mod code (spec v2 §3, P4). Application-scoped: one engine process for the app, one isolate
 * per enabled mod that has a hooks module, at most [MAX_LOGIC_MODS] of them.
 *
 * - **Lazy:** the engine runs only while a logic mod is enabled and a project is open, the Mods
 *   screen is showing, or a task runs; it stops [ModHostLimits.stopGraceMs] after none of those
 *   holds. No logic mods means no engine, no hooks and no cost.
 * - **Budgets:** each event has a time budget that counts only the mod's own time, not time the app
 *   spends on its `$` calls. A hook past its budget is skipped and gets a strike. A script that
 *   holds its isolate (a busy loop) is found by a time limit on every call into it and stopped.
 * - **Faults:** a stopped or failed mod restarts with backoff; [MAX_STRIKES] strikes in
 *   [STRIKE_WINDOW_MS] stop it until the user restarts it. A mod over its memory cap takes the
 *   whole engine down (library behaviour, `audits/mod-engine-test-2026-10-13.md`): it gets the
 *   strike, every other mod reloads on a fresh engine, and [MAX_ENGINE_DEATHS] deaths in
 *   [STRIKE_WINDOW_MS] pause mod code until the user resumes it.
 * - **Process death:** isolates, `$.state` and timers are gone; `$.store` is on disk.
 *
 * Every piece of host state is touched only on one thread ([hostContext]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModHost(
    private val registry: ModRegistry,
    private val engineFactory: () -> ModEngine,
    private val shimSource: () -> String,
    private val dataDir: (String) -> File,
    private val limits: ModHostLimits = ModHostLimits(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val hostContext: CoroutineContext = Dispatchers.Default.limitedParallelism(1),
    private val ioContext: CoroutineContext = Dispatchers.IO,
    private val secrets: ModSecretStore? = null,
    /** Sends a checked `$.http.fetch` request; replaced in tests. */
    private val http: (ModHttpRequest) -> JSONObject = ModNet::send,
) {
    private val scope = CoroutineScope(SupervisorJob() + hostContext + CoroutineExceptionHandler { _, error ->
        runCatching { android.util.Log.w(TAG, "Mod host error", error) }
    })

    @Volatile var effects: ModHostEffects? = null

    private val _snapshot = MutableStateFlow(ModHostSnapshot())
    val snapshot: StateFlow<ModHostSnapshot> = _snapshot.asStateFlow()
    private val _surfaces = MutableStateFlow<Map<String, ModSurfaces>>(emptyMap())
    /** Logic mods' panels and drawings, by mod id. */
    val surfaces: StateFlow<Map<String, ModSurfaces>> = _surfaces.asStateFlow()
    private val _logVersion = MutableStateFlow(0)
    /** Changes whenever a mod log gains a line; read the lines with [log]. */
    val logVersion: StateFlow<Int> = _logVersion.asStateFlow()

    /** Events at least one running mod hooks; read from any thread to skip building unused events. */
    @Volatile private var listening: Set<String> = emptySet()
    /**
     * The tools Claude's PreToolUse hook must send to mods (spec v2 §7 gate channel): a matcher
     * like "Bash|Edit", "*" for every tool, or null when no running mod may gate tools.
     */
    @Volatile var toolGateMatcher: String? = null
        private set
    /** A running mod may give the agents tools, so harnesses start with the mods MCP server and see tools added later. */
    @Volatile var toolsPossible: Boolean = false
        private set
    /** `$.mods.call` calls in flight across all mods; bounds chains of mods calling each other. */
    private var modCallsInFlight = 0
    private val logs = HashMap<String, ArrayDeque<ModLogEntry>>()
    private val logsLock = Any()

    // ---- host-thread state ---------------------------------------------------------------
    private var mods = ModsSnapshot()
    private var engine: ModEngine? = null
    private var engineGeneration = 0
    private var engineState = ModEngineState.OFF
    private var engineMessage: String? = null
    private var engineJob: Job? = null
    private var stopJob: Job? = null
    private val engineDeaths = ArrayDeque<Long>()
    private val running = HashMap<String, Loaded>()
    private val infos = HashMap<String, ModRuntimeInfo>()
    private val strikes = HashMap<String, ArrayDeque<Long>>()
    private val crashed = HashMap<String, String>()
    private val restartJobs = HashMap<String, Job>()
    private val restarts = HashMap<String, Int>()
    private val lastFault = HashMap<String, Long>()
    private val lastCall = HashMap<String, Long>()
    private var context: ModEventContext? = null
    private var taskRunning = false
    private var foreground = true
    private val screens = HashSet<String>()
    private var nextDispatch = 1L
    private val coalesced = HashMap<String, JSONObject>()
    private var started = false
    private var syncJob: Job? = null
    private var syncAgain = false
    private val panes = HashMap<String, LinkedHashMap<String, String>>()
    private val slots = HashMap<Pair<String, String>, Slot>()
    private val drawings = HashMap<String, HashMap<String, ModDrawing>>()
    private val runLog = ArrayDeque<JSONObject>()
    private var runLogDropped = 0
    private var runLogJob: Job? = null

    /** One surface of one mod: who shows it, and its render loop. */
    private class Slot {
        val holders = HashSet<String>()
        var job: Job? = null
        var again = false
        var lastAt = Long.MIN_VALUE / 2
    }

    private class Dispatch(
        val id: Long,
        val event: String,
        val budgetMs: Long,
        val startedAt: Long,
        val result: CompletableDeferred<JSONObject?>?,
    ) {
        var hostMs = 0L
        var callsInFlight = 0
        var callsSince = 0L
        var watchdog: Job? = null
    }

    private inner class Loaded(val mod: InstalledMod, val key: String, val isolate: ModIsolate, val inbox: Channel<String>) {
        var events: Set<String> = emptySet()
        /** Tools its tool.call hooks match; "*" for any. */
        var tools: Set<String> = emptySet()
        var ready = false
        var closed = false
        val dispatches = HashMap<Long, Dispatch>()
        val timers = HashMap<Long, Job>()
        val commands = LinkedHashMap<String, ModCodeCommand>()
        /** `$.http.fetch` requests in flight. */
        var fetching = 0
        /** Tools it gave the agents with `$.tool.register`, by name. */
        val provided = LinkedHashMap<String, ModTool>()
        /** Its slow `$` calls in flight; stopping the mod cancels them, which also kills its guest commands. */
        val slowJobs = HashSet<Job>()
        var consumer: Job? = null
        var settle: Job? = null
        var callsInFlight = 0
        /** Calls into the isolate that have not returned; a stuck isolate is stopped by [call], not the watchdog. */
        var evaluating = 0
        val store by lazy { ModStore(dataDir(mod.id)) }
        val id: String get() = mod.id
    }

    // ---- inputs (any thread) -------------------------------------------------------------

    /** Starts following the registry. Safe to call more than once. */
    fun start() = scope.launch {
        if (started) return@launch
        started = true
        registry.snapshot.collect { snapshot ->
            mods = snapshot
            refreshListening()
            reconcile()
            // Grants and places may have changed what a shown surface may draw.
            slots.filterValues { it.holders.isNotEmpty() }.keys.forEach { (id, surface) -> requestRender(id, surface) }
        }
    }

    /** The open project and chat, and whether a task runs there. A project change ends and starts the mods' session. */
    fun updateContext(newContext: ModEventContext?, taskIsRunning: Boolean) = scope.launch {
        val previous = context
        context = newContext
        taskRunning = taskIsRunning
        if (previous?.projectId != newContext?.projectId) {
            running.values.filter { it.ready }.forEach { loaded ->
                if (previous?.projectId != null) dispatch(loaded, "session.end", JSONObject(), limits.endBudgetMs, scopeOf(previous))
                dispatch(loaded, "session.start", JSONObject(), limits.eventBudgetMs)
            }
        }
        reconcile()
    }

    /** A screen that shows mods (the Mods tab) is visible; the engine runs while one is. */
    fun setScreenVisible(key: String, visible: Boolean) = scope.launch {
        if (visible) screens += key else screens -= key
        reconcile()
    }

    fun setForeground(isForeground: Boolean) = scope.launch {
        if (foreground == isForeground) return@launch
        foreground = isForeground
        if (engine != null) emitNow(if (isForeground) "app.foreground" else "app.background", JSONObject())
        reconcile()
    }

    /** True when a running mod hooks [event]; lets callers skip building its payload. */
    fun listens(event: String): Boolean = event in listening

    /**
     * Sends an observe event (spec v2 §4.2) to every running mod that hooks it. [private] names
     * payload fields a mod sees only with that capability granted, such as a message's text.
     */
    fun emit(event: String, payload: JSONObject, private: Map<String, ModCapability> = emptyMap()) {
        if (!listens(event)) return
        scope.launch { emitNow(event, payload, private) }
    }

    /** Like [emit], at most once per second; the newest payload wins. */
    fun emitCoalesced(event: String, payload: JSONObject) {
        if (!listens(event)) return
        scope.launch {
            val pending = coalesced.containsKey(event)
            coalesced[event] = payload
            if (pending) return@launch
            delay(COALESCE_MS)
            coalesced.remove(event)?.let { emitNow(event, it) }
        }
    }

    /** Runs a mod command through the mod's `command.run` hooks and returns what it answered. */
    suspend fun runCommand(modId: String, name: String, args: String): ModCodeCommandOutcome = withContext(hostContext) {
        val loaded = running[modId]?.takeIf { it.ready }
            ?: return@withContext ModCodeCommandOutcome.Failed("${modName(modId)} isn't running right now. Open Mods to see why.")
        val result = CompletableDeferred<JSONObject?>()
        dispatch(loaded, "command.run", JSONObject().put("name", name).put("args", args), limits.eventBudgetMs, result = result)
        val message = result.await()
            ?: return@withContext ModCodeCommandOutcome.Failed("/$name from ${loaded.mod.manifest.name} didn't answer in time.")
        if (message.has("error")) return@withContext ModCodeCommandOutcome.Failed("/$name failed: ${firstLine(message.optString("error"))}")
        val value = message.opt("value") as? JSONObject ?: return@withContext ModCodeCommandOutcome.Nothing
        when {
            value.optString("prompt").isNotBlank() -> ModCodeCommandOutcome.Prompt(value.optString("prompt").take(MAX_TEXT))
            value.optString("text").isNotBlank() -> ModCodeCommandOutcome.Reply(value.optString("text").take(MAX_TEXT))
            else -> ModCodeCommandOutcome.Nothing
        }
    }

    /**
     * Runs a mod tool an agent called (spec v2 P8): the owning mod's `tool.call` hooks answer it,
     * matched by `{ tool: name }`. The hooks have [ModHostLimits.toolBudgetMs] of their own time
     * and [ModHostLimits.toolWaitMs] in all, including their `$` calls. Never throws.
     */
    suspend fun callTool(exposed: String, input: JSONObject): ModToolResult = withContext(hostContext) {
        val loaded = running.values.firstOrNull { it.ready && it.provided.values.any { tool -> tool.exposed == exposed } }
            ?: return@withContext ModToolResult("The mod tool $exposed isn't available right now; its mod may have stopped.", isError = true)
        val tool = loaded.provided.values.first { it.exposed == exposed }
        val mod = currentMod(loaded)
        if (!allowed(mod, ModCapability.TOOLS_PROVIDE)) {
            registry.recordDenied(mod.id, "tool ${tool.name}", ModCapability.TOOLS_PROVIDE, "permission is off")
            return@withContext ModToolResult("${mod.manifest.name} may no longer give tools.", isError = true)
        }
        registry.recordCall(mod.id, "tool ${tool.name}", ModCapability.TOOLS_PROVIDE, "called", null)
        val result = CompletableDeferred<JSONObject?>()
        val startedAt = wallClock()
        val payload = JSONObject().put("tool", tool.name).put("input", input).put("provided", true).put("canGate", false)
        dispatch(loaded, "tool.call", payload, limits.toolBudgetMs, result = result)
        val message = withTimeoutOrNull(limits.toolWaitMs) { result.await() }
            ?: return@withContext ModToolResult("${mod.manifest.name}'s tool ${tool.name} didn't answer in time.", isError = true)
        if (message.has("error")) return@withContext ModToolResult("${tool.name} failed: ${firstLine(message.optString("error"))}", isError = true)
        when (val value = message.opt("value")) {
            null, JSONObject.NULL -> {
                // A hook that threw was logged (and struck); tell the agent why rather than nothing.
                val failure = log(loaded.id).lastOrNull { it.level == "error" && it.time >= startedAt }?.text
                ModToolResult(if (failure != null) "${tool.name} failed: ${firstLine(failure)}" else "${tool.name} gave no answer.", isError = true)
            }
            is String -> ModToolResult(value.take(MAX_TOOL_TEXT))
            is JSONObject -> when {
                value.has("error") -> ModToolResult(value.optString("error").take(MAX_TOOL_TEXT).ifBlank { "${tool.name} failed." }, isError = true)
                value.opt("text") is String -> ModToolResult(value.getString("text").take(MAX_TOOL_TEXT))
                value.optBoolean("__mhPass") -> ModToolResult("${tool.name} gave no answer.", isError = true)
                else -> ModToolResult(value.toString().take(MAX_TOOL_TEXT))
            }
            else -> ModToolResult(value.toString().take(MAX_TOOL_TEXT))
        }
    }

    /**
     * Asks the running mods that hook [event] and may use [capability] whether an action goes on
     * (spec v2 §4.2 gates), one mod after another in id order; each sees the input as the mods
     * before it left it. [verdicts] are the answers this gate takes, such as "deny" or "confirm".
     * Returns at once, unchanged, when no mod hooks [event].
     */
    suspend fun gate(
        event: String,
        input: JSONObject,
        capability: ModCapability,
        verdicts: Set<String>,
        budgetMs: Long = limits.gateBudgetMs,
    ): ModGateOutcome {
        if (!listens(event)) return ModGateOutcome(input)
        return withContext(hostContext) { runGate(event, input, capability, verdicts, budgetMs, compose = false) }
    }

    /**
     * `prompt.compose`: each mod may add sections the agent reads before the prompt, at most
     * [MAX_SECTION_BYTES] per mod; they come back as [ModGateOutcome.notes].
     */
    suspend fun compose(prompt: String, harness: String): ModGateOutcome {
        val input = JSONObject().put("prompt", prompt).put("harness", harness).put("sections", JSONArray())
        if (!listens("prompt.compose")) return ModGateOutcome(input)
        return withContext(hostContext) {
            runGate("prompt.compose", input, ModCapability.PROMPT_COMPOSE, emptySet(), limits.composeBudgetMs, compose = true)
        }
    }

    /**
     * Claude's PreToolUse hook (spec v2 §7): the `tool.call` gate with `canGate: true`. Answers the
     * hook output that denies the call, or that rewrites its input when a mod changed it, or null
     * to let it go on. A rewrite never approves anything: with interactive approvals on it asks the
     * user ("ask"), and without them the app already lets the tool run ("allow"). A truncated input
     * (too large for the bridge) can be denied but never rewritten.
     */
    suspend fun gateClaudeTool(tool: String, input: JSONObject, truncated: Boolean, interactive: Boolean): JSONObject? {
        val outcome = gate(
            "tool.call",
            JSONObject().put("tool", tool).put("input", input).put("canGate", true).put("harness", "claude").put("inputTruncated", truncated),
            ModCapability.TOOLS_GATE,
            setOf("deny"),
            limits.toolBudgetMs,
        )
        val output = JSONObject().put("hookEventName", "PreToolUse")
        val changed = outcome.input.optJSONObject("input")?.takeIf { !truncated && !sameJson(it, input) }
        return when {
            outcome.verdict == "deny" ->
                JSONObject().put("hookSpecificOutput", output.put("permissionDecision", "deny").put("permissionDecisionReason", "${outcome.modName}: ${outcome.reason}"))
            changed != null -> JSONObject().put("hookSpecificOutput", output
                .put("permissionDecision", if (interactive) "ask" else "allow")
                .put("permissionDecisionReason", "A mod changed this tool call")
                .put("updatedInput", changed))
            else -> null
        }
    }

    /**
     * `tool.approval` (spec v2 §4.2, Claude): mods with `tools.gate` may deny an approval request
     * before the user answers it, never approve it (D4). Other mods that hook it only hear of it.
     */
    suspend fun gateApproval(payload: JSONObject): ModGateOutcome {
        if (!listens("tool.approval")) return ModGateOutcome(payload)
        return withContext(hostContext) {
            runGate("tool.approval", payload, ModCapability.TOOLS_GATE, setOf("deny"), limits.toolBudgetMs, compose = false, observeWithout = true)
        }
    }

    private suspend fun runGate(
        event: String,
        input: JSONObject,
        capability: ModCapability,
        verdicts: Set<String>,
        budgetMs: Long,
        compose: Boolean,
        /** Mods without [capability] still hear of the event, as an observe event. */
        observeWithout: Boolean = false,
    ): ModGateOutcome {
        var current = input
        val notes = mutableListOf<Triple<String, String, String>>()
        for (loaded in running.values.filter { it.ready && event in it.events }.sortedBy { it.id }) {
            val mod = currentMod(loaded)
            if (!allowed(mod, capability)) {
                if (observeWithout) dispatch(loaded, event, JSONObject(input.toString()).put("canGate", false), limits.eventBudgetMs)
                else if (throttle("denied:${mod.id}/$event", 60_000)) registry.recordDenied(mod.id, event, capability, "permission is off")
                continue
            }
            val result = CompletableDeferred<JSONObject?>()
            dispatch(loaded, event, JSONObject(current.toString()), budgetMs, result = result) { id, body ->
                "__mh.gate($id, ${JSONObject.quote(event)}, $body)"
            }
            val answer = withTimeoutOrNull(limits.gateWaitMs) { result.await() }
            if (answer == null) {
                addLog(loaded.id, "warn", "Its $event hook didn't answer in time, so the action went on.")
                continue
            }
            // A hook that failed was logged and counted; gates fail open.
            val value = answer.opt("value") as? JSONObject ?: continue
            val passed = if (value.optBoolean("__mhPass")) value.optJSONObject("e") ?: continue else null
            if (compose) {
                val sections = (passed ?: value).optJSONArray("sections") ?: continue
                val added = addedSections(current.optJSONArray("sections") ?: JSONArray(), sections)
                if (added.isNotEmpty()) {
                    val text = utf8Take(added.joinToString("\n\n"), MAX_SECTION_BYTES)
                    current.getJSONArray("sections").put(JSONObject().put("mod", mod.id).put("text", text))
                    notes += Triple(mod.id, mod.manifest.name, text)
                }
                continue
            }
            // A hook may pass the action on and add context too: `{ ...(await next(e)), context }`.
            val context = (value.opt("context") as? String)?.trim()
            if (!context.isNullOrEmpty()) notes += Triple(mod.id, mod.manifest.name, utf8Take(context, MAX_SECTION_BYTES))
            if (passed != null) {
                current = mergeInput(current, passed)
                continue
            }
            val verdict = verdicts.firstOrNull { value.has(it) && !value.isNull(it) && value.opt(it) != false }
            if (verdict != null) {
                val given = value.opt(verdict) as? String
                val reason = ModHookRunner.clean(given.orEmpty().take(300)).ifBlank { "${mod.manifest.name} stopped it." }
                addLog(loaded.id, "info", "$event: $verdict ($reason)")
                registry.recordCall(mod.id, event, capability, verdict, reason)
                return ModGateOutcome(current, verdict, reason, mod.id, mod.manifest.name, notes)
            }
            if (!context.isNullOrEmpty()) continue
            addLog(loaded.id, "warn", "Its $event hook answered something the app doesn't use, so the action went on.")
        }
        return ModGateOutcome(current, notes = notes)
    }

    /** Clears a crash and its strikes, or resumes paused mod code, and starts the mod again. */
    fun restart(modId: String?) = scope.launch {
        if (modId == null) {
            engineDeaths.clear()
            if (engineState == ModEngineState.PAUSED) {
                engineState = ModEngineState.OFF
                engineMessage = null
            }
        } else {
            crashed.remove(modId)
            strikes.remove(modId)
            restarts.remove(modId)
            restartJobs.remove(modId)?.cancel()
            running[modId]?.let { unload(it, sendEnd = true) }
            setInfo(modId) { it.copy(state = ModRunState.WAITING, message = null, strikes = 0) }
        }
        reconcile()
    }

    /** Stops the engine while [block] runs (the engine test needs the app's one sandbox), then starts it again. */
    suspend fun <T> withEngineStopped(block: suspend () -> T): T {
        withContext(hostContext) {
            engineJob?.cancel()
            screens += HOLD_TEST
            stopEngine()
        }
        try {
            return block()
        } finally {
            scope.launch {
                screens -= HOLD_TEST
                reconcile()
            }
        }
    }

    /** The newest lines of [modId]'s log, oldest first. */
    fun log(modId: String): List<ModLogEntry> = synchronized(logsLock) { logs[modId]?.toList().orEmpty() }

    /** [holder] shows or stops showing [surface] of [modId]; only shown surfaces are drawn. */
    fun show(modId: String, surface: String, holder: String, visible: Boolean) = scope.launch {
        val key = modId to surface
        val slot = slots.getOrPut(key) { Slot() }
        val wasShown = slot.holders.isNotEmpty()
        if (visible) slot.holders += holder else slot.holders -= holder
        when {
            !wasShown && slot.holders.isNotEmpty() -> requestRender(modId, surface)
            wasShown && slot.holders.isEmpty() -> {
                slot.job?.cancel()
                slots.remove(key)
                running[modId]?.takeIf { it.ready }?.let { call(it, "__mh.forget(${JSONObject.quote(surface)})") }
            }
        }
    }

    /** A tap or an edit in a drawn tree: runs the mod's function behind [handler], then redraws. */
    fun press(modId: String, surface: String, handler: String, value: Any?) = scope.launch {
        val loaded = running[modId]?.takeIf { it.ready } ?: return@launch
        val result = CompletableDeferred<JSONObject?>()
        dispatch(loaded, "ui.press", JSONObject(), limits.eventBudgetMs, result = result) { id, _ ->
            "__mh.press($id, ${JSONObject.quote(surface)}, ${JSONObject.quote(handler)}, ${ModJson.literal(value)})"
        }
        val answer = result.await()
        if (answer == null || answer.has("error")) {
            effects?.let { e -> runCatching { e.toast(loaded.mod, "${loaded.mod.manifest.name}: that action failed. Its log says why.") } }
        }
        requestRender(modId, surface)
    }

    /**
     * A line of the running task's live output (spec v2 §6.9 `run.log`): batched, at most once a
     * second and 200 lines, for mods allowed to watch it while one of their surfaces is showing.
     */
    fun emitRunLog(kind: String, text: String) {
        if (!listens("run.log") || text.isEmpty()) return
        val time = wallClock()
        scope.launch {
            val last = runLog.lastOrNull()
            if (kind == "reply" && last?.optString("kind") == "reply" && last.optString("text").length < MAX_TEXT) {
                last.put("text", (last.optString("text") + text).take(MAX_TEXT))
            } else {
                runLog.addLast(JSONObject().put("kind", kind).put("text", text.take(MAX_TEXT)).put("time", time))
                while (runLog.size > MAX_RUN_LOG_LINES) {
                    runLog.removeFirst()
                    runLogDropped++
                }
            }
            if (runLogJob?.isActive == true) return@launch
            runLogJob = scope.launch {
                delay(COALESCE_MS)
                val lines = JSONArray(runLog.toList())
                val dropped = runLogDropped
                runLog.clear()
                runLogDropped = 0
                running.values.filter { loaded ->
                    loaded.ready && "run.log" in loaded.events && allowed(currentMod(loaded), ModCapability.EVENTS_SUBSCRIBE) &&
                        slots.any { (key, slot) -> key.first == loaded.id && slot.holders.isNotEmpty() }
                }.forEach { loaded ->
                    dispatch(loaded, "run.log", JSONObject().put("lines", JSONArray(lines.toString())).put("dropped", dropped), limits.eventBudgetMs)
                }
            }
        }
    }

    // ---- lifecycle -----------------------------------------------------------------------

    private fun logicMods(): List<InstalledMod> = mods.active.filter { it.manifest.hooksModule != null }.sortedBy { it.id }

    private fun wanted(): Boolean =
        HOLD_TEST !in screens && engineState != ModEngineState.PAUSED && engineState != ModEngineState.UNAVAILABLE &&
            logicMods().isNotEmpty() && (taskRunning || (foreground && (context?.projectId != null || screens.isNotEmpty())))

    private fun reconcile() {
        if (!wanted()) {
            if (engine != null && stopJob == null) {
                stopJob = scope.launch {
                    delay(if (HOLD_TEST in screens || logicMods().isEmpty()) 0 else limits.stopGraceMs)
                    stopJob = null
                    if (!wanted()) stopEngine()
                }
            }
            publish()
            return
        }
        stopJob?.cancel()
        stopJob = null
        if (engine == null) {
            if (engineJob?.isActive != true) engineJob = scope.launch { startEngine() }
        } else {
            requestSync()
        }
        publish()
    }

    /** Runs [syncMods] once more after any change, one pass at a time. */
    private fun requestSync() {
        if (syncJob?.isActive == true) {
            syncAgain = true
            return
        }
        syncJob = scope.launch {
            do {
                syncAgain = false
                syncMods()
            } while (syncAgain)
        }
    }

    private suspend fun startEngine() {
        engineState = ModEngineState.STARTING
        engineMessage = null
        publish()
        val created = engineFactory()
        try {
            withTimeout(limits.loadTimeoutMs) { created.connect() }
        } catch (e: ModEngineUnavailableException) {
            runCatching { created.close() }
            engineState = ModEngineState.UNAVAILABLE
            engineMessage = e.message
            publish()
            return
        } catch (e: CancellationException) {
            runCatching { created.close() }
            engineState = ModEngineState.OFF
            throw e
        } catch (e: Exception) {
            runCatching { created.close() }
            engineState = ModEngineState.OFF
            engineMessage = "The engine didn't start (${e.message ?: e.javaClass.simpleName})."
            onEngineDeath(null)
            return
        }
        engine = created
        engineGeneration++
        engineState = ModEngineState.ON
        reconcile()
    }

    private suspend fun stopEngine() {
        val current = engine ?: return
        running.values.toList().forEach { unload(it, sendEnd = true) }
        engine = null
        engineGeneration++
        runCatching { current.close() }
        if (engineState == ModEngineState.ON || engineState == ModEngineState.STARTING) engineState = ModEngineState.OFF
        publish()
    }

    /** Loads the logic mods that should run and unloads the rest, one at a time. Host thread. */
    private suspend fun syncMods() {
        val desired = logicMods()
        val allowed = desired.take(MAX_LOGIC_MODS).associateBy { it.id }
        running.values.toList().forEach { loaded ->
            val now = allowed[loaded.id]
            if (now == null || keyOf(now) != loaded.key) unload(loaded, sendEnd = true)
        }
        desired.drop(MAX_LOGIC_MODS).forEach {
            infos[it.id] = ModRuntimeInfo(ModRunState.BLOCKED, "Only $MAX_LOGIC_MODS mods with code can run at once. Turn another one off.")
        }
        publish()
        for (mod in allowed.values) {
            if (engine == null || !wanted()) break
            if (running.containsKey(mod.id) || mod.id in crashed || restartJobs[mod.id]?.isActive == true) continue
            load(mod)
        }
    }

    private suspend fun load(mod: InstalledMod) {
        val current = engine ?: return
        if (running.containsKey(mod.id)) return
        val generation = engineGeneration
        setInfo(mod.id) { it.copy(state = ModRunState.STARTING, message = null) }
        val inbox = Channel<String>(INBOX_CAPACITY)
        lateinit var loaded: Loaded
        val listener = object : ModIsolateListener {
            override fun onMessage(text: String) {
                if (inbox.trySend(text).isFailure) scope.launch { fault(loaded, "It sent more messages than the app can take.") }
            }

            override fun onTerminated(reason: ModTermination, detail: String) {
                scope.launch { onTerminated(loaded, generation, reason, detail) }
            }
        }
        val isolate = try {
            current.createIsolate(limits.heapBytes, listener)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog(mod.id, "error", "Couldn't start: ${e.message ?: e.javaClass.simpleName}")
            if (generation == engineGeneration) onEngineDeath(null)
            return
        }
        loaded = Loaded(mod, keyOf(mod), isolate, inbox)
        running[mod.id] = loaded
        loaded.consumer = scope.launch { for (text in inbox) handleMessage(loaded, text) }
        val events = try {
            withTimeout(limits.loadTimeoutMs) {
                check(isolate.evaluate(shimSource()) == "ok") { "The mod shim didn't load" }
                check(isolate.evaluate("__mh.connect()") == "ready") { "The mod shim didn't connect" }
                val source = withContext(ioContext) { File(mod.dir, mod.manifest.hooksModule!!).readText() }
                isolate.evaluate(ModModuleSource.wrap(source))
                val answer = JSONObject(isolate.evaluate("__mh.start(${ModJson.literal(options(mod))})"))
                answer.optJSONArray("tools")?.let { array -> (0 until minOf(array.length(), 64)).map { array.optString(it) }.toSet() }
                    ?.let { tools -> loaded.tools = tools }
                answer.optJSONArray("events")?.let { array -> (0 until array.length()).map { array.optString(it) }.toSet() }.orEmpty()
            }
        } catch (e: CancellationException) {
            if (loaded.closed) return
            unload(loaded, sendEnd = false)
            if (e is kotlinx.coroutines.TimeoutCancellationException) {
                crash(mod.id, "It took longer than ${limits.loadTimeoutMs / 1000} s to start, so it was stopped.")
                return
            }
            throw e
        } catch (e: Exception) {
            if (loaded.closed) return
            if (e is ModCodeException || e is ModScriptException) {
                unload(loaded, sendEnd = false)
                crash(mod.id, "Its code didn't load: ${errorLine(e.message.orEmpty())}")
                return
            }
            // Not the mod's code: the isolate or engine died mid-load. Let the queued termination
            // handling run first; whatever is left is a fault that restarts, not a crash.
            yield()
            if (loaded.closed || generation != engineGeneration) return
            fault(loaded, "Couldn't start: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (loaded.closed) return
        loaded.events = events
        loaded.ready = true
        mod.manifest.slashCommands.filter { it.template == null }.forEach { command ->
            loaded.commands[command.name] = ModCodeCommand(mod.id, mod.manifest.name, command.name, command.description)
        }
        addLog(mod.id, "info", "Started" + if (events.isEmpty()) "; it hooks no events" else "; hooks ${events.sorted().joinToString()}")
        setInfo(mod.id) { it.copy(state = ModRunState.RUNNING, message = null, commands = loaded.commands.values.toList()) }
        refreshListening()
        dispatch(loaded, "session.start", JSONObject(), limits.eventBudgetMs)
        slots.filter { (key, slot) -> key.first == mod.id && slot.holders.isNotEmpty() }.keys.forEach { requestRender(it.first, it.second) }
    }

    /** Ends [loaded]: optional `session.end`, then timers, pending calls and the isolate. Host thread. */
    private suspend fun unload(loaded: Loaded, sendEnd: Boolean) {
        if (loaded.closed) return
        if (sendEnd && loaded.ready && engine != null) {
            val done = CompletableDeferred<JSONObject?>()
            dispatch(loaded, "session.end", JSONObject(), limits.endBudgetMs, result = done)
            withTimeoutOrNull(limits.endBudgetMs + 200) { done.await() }
        }
        if (loaded.closed) return
        loaded.closed = true
        loaded.ready = false
        if (running[loaded.id] === loaded) running.remove(loaded.id)
        loaded.timers.values.forEach { it.cancel() }
        loaded.timers.clear()
        loaded.slowJobs.toList().forEach { it.cancel() }
        loaded.slowJobs.clear()
        loaded.dispatches.values.forEach { it.watchdog?.cancel(); it.result?.complete(null) }
        loaded.dispatches.clear()
        loaded.settle?.cancel()
        loaded.inbox.close()
        loaded.consumer?.cancel()
        runCatching { loaded.isolate.close() }
        refreshListening()
        drawings.remove(loaded.id)
        setInfo(loaded.id) { it.copy(state = ModRunState.WAITING, commands = emptyList(), tools = emptyList()) }
    }

    private suspend fun onTerminated(loaded: Loaded, generation: Int, reason: ModTermination, detail: String) {
        if (loaded.closed || generation != engineGeneration) return
        when (reason) {
            ModTermination.MEMORY_LIMIT -> {
                addLog(loaded.id, "error", "Stopped: it used more than ${limits.heapBytes / (1024 * 1024)} MB of memory, which also stopped the other mods.")
                strike(loaded.id, "used too much memory")
                if (loaded.id !in crashed) scheduleRestart(loaded.id)
                onEngineDeath(loaded.id)
            }
            ModTermination.SANDBOX_DEAD -> onEngineDeath(null)
            ModTermination.OTHER -> fault(loaded, "It stopped unexpectedly${if (detail.isBlank()) "" else " ($detail)"}.")
        }
    }

    /** The engine process died: every mod reloads on a new one, with backoff, or mod code pauses. */
    private suspend fun onEngineDeath(culprit: String?) {
        val dead = engine
        engine = null
        engineGeneration++
        running.values.toList().forEach { loaded ->
            if (loaded.id != culprit) addLog(loaded.id, "warn", "The mod engine restarted, so this mod reloads and loses its in-memory state.")
            unload(loaded, sendEnd = false)
            if (loaded.id != culprit) setInfo(loaded.id) { it.copy(state = ModRunState.RESTARTING, message = "Waiting for the mod engine to restart.") }
        }
        runCatching { dead?.close() }
        val now = clock()
        engineDeaths.addLast(now)
        while (engineDeaths.isNotEmpty() && now - engineDeaths.first() > STRIKE_WINDOW_MS) engineDeaths.removeFirst()
        if (engineDeaths.size >= MAX_ENGINE_DEATHS) {
            engineState = ModEngineState.PAUSED
            engineMessage = "Mod code stopped the mod engine $MAX_ENGINE_DEATHS times in 10 minutes, so mod code is paused. Simple mod features keep working."
            effects?.let { e -> mods.mods.firstOrNull()?.let { runCatching { e.toast(it, "Mod code is paused after repeated crashes. Open Mods to resume.") } } }
            publish()
            return
        }
        engineState = ModEngineState.STARTING
        publish()
        val wait = limits.backoffMs[(engineDeaths.size - 1).coerceIn(0, limits.backoffMs.lastIndex)]
        engineJob?.cancel()
        engineJob = scope.launch {
            delay(wait)
            if (wanted() && engine == null) startEngine() else {
                engineState = ModEngineState.OFF
                reconcile()
            }
        }
    }

    /** [loaded] misbehaved: strike, stop it, and restart it with backoff unless it has crashed. */
    private suspend fun fault(loaded: Loaded, reason: String) {
        if (loaded.closed) return
        addLog(loaded.id, "error", reason)
        strike(loaded.id, reason)
        if (loaded.id !in crashed) scheduleRestart(loaded.id)
        unload(loaded, sendEnd = false)
    }

    private fun scheduleRestart(id: String) {
        val now = clock()
        if (now - (lastFault[id] ?: now) > STRIKE_WINDOW_MS) restarts.remove(id)
        lastFault[id] = now
        val count = (restarts[id] ?: 0) + 1
        restarts[id] = count
        val wait = limits.backoffMs[(count - 1).coerceIn(0, limits.backoffMs.lastIndex)]
        setInfo(id) { it.copy(state = ModRunState.RESTARTING, message = "Restarting in ${(wait + 999) / 1000} s.") }
        restartJobs.remove(id)?.cancel()
        restartJobs[id] = scope.launch {
            delay(wait)
            restartJobs.remove(id)
            reconcile()
        }
    }

    private suspend fun strike(id: String, reason: String) {
        val now = clock()
        val list = strikes.getOrPut(id) { ArrayDeque() }
        list.addLast(now)
        while (list.isNotEmpty() && now - list.first() > STRIKE_WINDOW_MS) list.removeFirst()
        setInfo(id) { it.copy(strikes = list.size) }
        if (list.size >= MAX_STRIKES && id !in crashed) {
            crash(id, "Stopped after $MAX_STRIKES problems in 10 minutes. The last one: $reason")
        }
    }

    private suspend fun crash(id: String, reason: String) {
        crashed[id] = reason
        restartJobs.remove(id)?.cancel()
        running[id]?.let { unload(it, sendEnd = false) }
        addLog(id, "error", reason)
        registry.recordCall(id, "code", null, "stopped", reason)
        setInfo(id) { it.copy(state = ModRunState.CRASHED, message = reason) }
    }

    // ---- dispatch ------------------------------------------------------------------------

    private suspend fun emitNow(event: String, payload: JSONObject, private: Map<String, ModCapability> = emptyMap()) {
        if (engine == null) return
        val budget = if (event.startsWith("app.")) limits.quickBudgetMs else limits.eventBudgetMs
        val where = context
        running.values.filter { it.ready && event in it.events }.forEach { loaded ->
            val mod = currentMod(loaded)
            val visible = JSONObject(payload.toString())
            private.forEach { (field, capability) -> if (!allowed(mod, capability)) visible.remove(field) }
            if (event == "run.end") {
                // A run.end hook may answer { followUp } to keep the agent working (spec v2 §4.2).
                val result = CompletableDeferred<JSONObject?>()
                dispatch(loaded, event, visible, budget, result = result)
                scope.launch { followUp(loaded, (result.await()?.opt("value") as? JSONObject)?.opt("followUp"), where) }
            } else {
                dispatch(loaded, event, visible, budget)
            }
        }
    }

    private suspend fun dispatch(
        loaded: Loaded,
        event: String,
        payload: JSONObject,
        budgetMs: Long,
        eventScope: Any = scopeOf(context),
        result: CompletableDeferred<JSONObject?>? = null,
        /** The entry point to call instead of `__mh.dispatch`; the event need not be hooked. */
        script: ((Long, JSONObject) -> String)? = null,
    ) {
        if (!loaded.ready || loaded.closed) {
            result?.complete(null)
            return
        }
        if (script == null && event != "clock.tick" && event !in loaded.events) {
            result?.complete(JSONObject().put("value", JSONObject.NULL))
            return
        }
        if (!payload.has("project")) payload.put("project", eventScope)
        payload.put("event", event)
        val dispatch = Dispatch(nextDispatch++, event, budgetMs, clock(), result)
        loaded.dispatches[dispatch.id] = dispatch
        dispatch.watchdog = scope.launch { watch(loaded, dispatch) }
        call(loaded, script?.invoke(dispatch.id, payload) ?: "__mh.dispatch(${dispatch.id}, ${JSONObject.quote(event)}, $payload)")
    }

    /** Skips a dispatch that runs past its budget of the mod's own time. */
    private suspend fun watch(loaded: Loaded, dispatch: Dispatch) {
        while (true) {
            val now = clock()
            val hostTime = dispatch.hostMs + if (dispatch.callsInFlight > 0) now - dispatch.callsSince else 0
            val left = dispatch.budgetMs - (now - dispatch.startedAt - hostTime)
            if (left <= 0) break
            delay(left.coerceAtLeast(WATCH_MIN_MS))
        }
        if (loaded.dispatches.remove(dispatch.id) == null) return
        dispatch.result?.complete(null)
        val seconds = String.format(Locale.US, "%.1f", dispatch.budgetMs / 1000.0)
        addLog(loaded.id, "warn", "A ${dispatch.event} hook took longer than $seconds s and was skipped.")
        if (loaded.evaluating == 0) strike(loaded.id, "a ${dispatch.event} hook took too long")
    }

    /** Evaluates [script] in [loaded]; an isolate that doesn't answer in time is stuck and gets stopped. */
    private suspend fun call(loaded: Loaded, script: String): String? {
        if (loaded.closed) return null
        loaded.evaluating++
        val answer = try {
            withTimeoutOrNull(limits.evalTimeoutMs) { loaded.isolate.evaluate(script) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModScriptException) {
            addLog(loaded.id, "error", errorLine(e.message.orEmpty()))
            return null
        } catch (e: Exception) {
            // A terminated isolate: its termination callback decides what happens next.
            return null
        } finally {
            loaded.evaluating--
        }
        if (answer == null) {
            fault(loaded, "Stopped: its code ran for more than ${limits.evalTimeoutMs / 1000} s without a break.")
            return null
        }
        if (loaded.settle?.isActive != true) {
            loaded.settle = scope.launch {
                delay(limits.settleCheckMs)
                if (loaded.closed) return@launch
                val pong = try {
                    withTimeoutOrNull(limits.evalTimeoutMs) { loaded.isolate.evaluate("'1'") }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch
                }
                if (pong == null) fault(loaded, "Stopped: its code ran for more than ${limits.evalTimeoutMs / 1000} s without a break.")
            }
        }
        return answer
    }

    // ---- messages from mods --------------------------------------------------------------

    private suspend fun handleMessage(loaded: Loaded, text: String) {
        if (loaded.closed) return
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (message.optString("t")) {
            "log" -> addLog(loaded.id, message.optString("level", "info"), message.optString("text"))
            "error" -> {
                val event = message.optString("event", "hook")
                addLog(loaded.id, "error", "$event hook failed: ${message.optString("error")}")
                strike(loaded.id, "a $event hook failed: ${firstLine(message.optString("error"))}")
            }
            "done" -> {
                val dispatch = loaded.dispatches.remove(message.optLong("d")) ?: return
                dispatch.watchdog?.cancel()
                if (message.has("error")) {
                    addLog(loaded.id, "error", "${dispatch.event} failed: ${message.optString("error")}")
                    strike(loaded.id, "a ${dispatch.event} hook failed")
                }
                dispatch.result?.complete(message)
            }
            "op" -> handleCall(loaded, message)
            "redraw" -> message.optJSONArray("surfaces")?.let { list ->
                (0 until minOf(list.length(), MAX_PANES + 1)).forEach { requestRender(loaded.id, list.optString(it)) }
            }
        }
    }

    private suspend fun handleCall(loaded: Loaded, message: JSONObject) {
        val callId = message.optLong("id")
        val name = message.optString("op")
        val args = message.optJSONArray("args") ?: JSONArray()
        val dispatch = loaded.dispatches[message.optLong("d")]
        if (loaded.callsInFlight >= MAX_CALLS_IN_FLIGHT) {
            answer(loaded, callId, Result.failure(IllegalStateException("Too many \$ calls at once; await some of them first.")))
            return
        }
        val op = ModOp.fromId(name)
        if (op == null) {
            answer(loaded, callId, Result.failure(IllegalArgumentException("\$.$name is not available in this version of the app.")))
            return
        }
        val mod = currentMod(loaded)
        val capability = op.capability
        if (capability != null && !allowed(mod, capability)) {
            if (throttle("denied:${mod.id}/${op.id}", 60_000)) registry.recordDenied(mod.id, "\$.${op.id}", capability, "permission is off")
            val reason = if (capability in mod.manifest.requested) "which is off. Turn it on in the mod's Permissions tab."
            else "which this mod didn't ask for in mod.json."
            answer(loaded, callId, Result.failure(SecurityException("\$.${op.id} needs the ${capability.id} permission, $reason")))
            return
        }
        val scoped = context
        if (op.needsProject && scoped?.projectId == null) {
            answer(loaded, callId, Result.failure(IllegalStateException("\$.${op.id} works only while a project is open.")))
            return
        }
        loaded.callsInFlight++
        dispatch?.let { if (it.callsInFlight++ == 0) it.callsSince = clock() }
        suspend fun complete(result: Result<Any?>) {
            loaded.callsInFlight--
            dispatch?.let { if (--it.callsInFlight == 0) it.hostMs += clock() - it.callsSince }
            if (capability != null && capability.risk != com.jarves.mh.model.RiskLevel.SAFE && result.isSuccess &&
                throttle("audit:${mod.id}/${op.id}", 10_000)) {
                registry.recordCall(mod.id, "\$.${op.id}", capability, "allowed", null)
            }
            answer(loaded, callId, result)
        }
        // A slow call must not hold up the mod's other messages, which are handled one at a time.
        if (op.slow) {
            val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    complete(runOp(loaded, mod, op, args, scoped))
                } finally {
                    coroutineContext[Job]?.let(loaded.slowJobs::remove)
                }
            }
            loaded.slowJobs += job
            job.start()
        } else {
            complete(runOp(loaded, mod, op, args, scoped))
        }
    }

    private suspend fun runOp(loaded: Loaded, mod: InstalledMod, op: ModOp, args: JSONArray, scoped: ModEventContext?): Result<Any?> = try {
        Result.success(when (op) {
            ModOp.STORE_GET -> withContext(ioContext) { loaded.store.get(args.optString(0)) }
            ModOp.STORE_SET -> withContext(ioContext) { loaded.store.set(args.optString(0), args.opt(1)); null }
            ModOp.STORE_DELETE -> withContext(ioContext) { loaded.store.delete(args.optString(0)); null }
            ModOp.STORE_KEYS -> withContext(ioContext) { JSONArray(loaded.store.keys()) }
            ModOp.CLOCK_AFTER, ModOp.CLOCK_EVERY -> addTimer(loaded, args.optLong(0), args.optLong(1, -1), op == ModOp.CLOCK_EVERY)
            ModOp.CLOCK_CANCEL -> loaded.timers.remove(args.optLong(0))?.cancel() != null
            ModOp.CLOCK_SLEEP -> {
                val ms = args.optLong(0, -1)
                require(ms in 0..MAX_SLEEP_MS) { "\$.clock.sleep(ms) takes 0 to ${MAX_SLEEP_MS / 60_000} minutes." }
                delay(ms)
                null
            }
            ModOp.UI_TOAST -> {
                val text = ModHookRunner.clean(text(args).take(200))
                require(text.isNotEmpty()) { "\$.ui.toast(text) needs text" }
                val e = effects ?: error("The app screen is closed")
                if (throttle("toast:${mod.id}", 1_000)) { e.toast(mod, "${mod.manifest.name}: $text"); true } else false
            }
            ModOp.UI_STATUS -> {
                val text = optionalLine(args)
                setInfo(mod.id) { it.copy(status = text) }
                null
            }
            ModOp.UI_SUMMARY -> {
                val text = optionalLine(args)
                setInfo(mod.id) { it.copy(summary = text) }
                null
            }
            ModOp.UI_NOTIFY -> {
                val spec = args.optJSONObject(0) ?: JSONObject().put("title", args.optString(0)).put("body", args.optString(1))
                val title = ModHookRunner.clean(spec.optString("title").take(80)).ifBlank { mod.manifest.name }
                val body = spec.optString("body").take(400)
                val e = effects ?: error("The app screen is closed")
                if (!throttle("notify:${mod.id}", 10_000)) false
                else if (!e.notify(mod, title, body)) error("Notifications are off for this app")
                else true
            }
            ModOp.UI_OPEN -> openPane(loaded, args)
            ModOp.UI_CLOSE -> {
                val id = args.optJSONObject(0)?.optString("id") ?: args.optString(0)
                val removed = panes[mod.id]?.remove(id) != null
                if (removed) drawings[mod.id]?.remove(paneSurface(id))
                publish()
                removed
            }
            ModOp.UI_REDRAW -> {
                val id = args.optString(0).takeIf { !args.isNull(0) && it.isNotBlank() }
                slots.filter { (key, slot) -> key.first == mod.id && slot.holders.isNotEmpty() && (id == null || key.second == paneSurface(id)) }
                    .keys.forEach { requestRender(it.first, it.second) }
                null
            }
            ModOp.COMMAND_REGISTER -> registerCommand(loaded, args.optJSONObject(0) ?: error("\$.command.register({ name, description }) needs an object"))
            ModOp.COMMAND_UNREGISTER -> {
                val removed = loaded.commands.remove(args.optString(0)) != null
                setInfo(mod.id) { it.copy(commands = loaded.commands.values.toList()) }
                removed
            }
            ModOp.APP_INFO -> JSONObject().put("version", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
            ModOp.PROJECT_INFO -> ModJson.project(scoped)
            ModOp.PLAN_CURRENT -> ModJson.plan((effects ?: error("The app screen is closed")).currentPlan(scoped!!))
            ModOp.CHAT_MESSAGES -> {
                val limit = args.optInt(0, 20).coerceIn(1, 50)
                val lines = (effects ?: error("The app screen is closed")).chatMessages(scoped!!, limit) ?: error("That chat is not open")
                JSONArray(lines.map { (from, text) -> JSONObject().put("from", from).put("text", text.take(MAX_TEXT)) })
            }
            ModOp.CHAT_APPEND -> {
                val text = text(args).trim().take(MAX_TEXT)
                require(text.isNotEmpty()) { "\$.chat.append(text) needs text" }
                val e = effects ?: error("The app screen is closed")
                if (throttle("chat:${mod.id}", 2_000)) { e.chatPost(mod, text, scoped!!); true } else false
            }
            ModOp.CHANGES_LIST -> (effects ?: error("The app screen is closed")).pendingChanges(scoped!!) ?: error("That project is not open")
            ModOp.CHANGES_UNDO -> {
                val path = text(args).trim().trim('/')
                require(ModManifestParser.isSafeRelativePath(path)) { "\$.changes.undo(path) needs a path inside the project" }
                val scope = mod.manifest.permission(ModCapability.CHANGES_UNDO)?.paths.orEmpty()
                require(scope.isEmpty() || scope.any { ModPaths.matchesGlob(it, path) }) { "$path is outside the mod's changes.undo scope" }
                val undone = (effects ?: error("The app screen is closed")).undoChange(scoped!!, path)
                if (undone) addLog(mod.id, "info", "Undid the agent's change to $path")
                undone
            }
            ModOp.TOOL_REGISTER -> registerTool(loaded, args.optJSONObject(0) ?: error("\$.tool.register({ name, description, inputSchema }) needs an object"))
            ModOp.TOOL_UNREGISTER -> {
                val removed = loaded.provided.remove(args.optString(0)) != null
                setInfo(mod.id) { it.copy(tools = loaded.provided.values.toList()) }
                removed
            }
            ModOp.FS_READ, ModOp.FS_LIST, ModOp.FS_STAT, ModOp.FS_EXISTS -> {
                val root = scoped?.projectRoot?.invoke() ?: error("No project is open")
                val scope = mod.manifest.permission(ModCapability.FILES_READ)?.paths.orEmpty()
                val path = if (args.isNull(0)) "" else args.optString(0)
                withContext(ioContext) {
                    when (op) {
                        ModOp.FS_READ -> ModFiles.read(root, path, scope)
                        ModOp.FS_LIST -> ModFiles.list(root, path, scope)
                        ModOp.FS_STAT -> ModFiles.stat(root, path, scope) ?: JSONObject.NULL
                        else -> ModFiles.exists(root, path, scope)
                    }
                }
            }
            ModOp.FS_WRITE -> {
                val root = scoped?.projectRoot?.invoke() ?: error("No project is open")
                val scope = mod.manifest.permission(ModCapability.FILES_WRITE)?.paths.orEmpty()
                require(scope.isNotEmpty()) { "files.write needs scope.paths" }
                val path = args.optString(0).trim().trim('/')
                require(!args.isNull(1) && args.opt(1) is String) { "\$.fs.write(path, text) needs text" }
                val content = args.getString(1)
                val written = effects?.checkpointedWrite(scoped, path) { ModFiles.write(root, path, content, scope) }
                    ?: withContext(ioContext) { ModFiles.write(root, path, content, scope) }
                registry.recordCall(mod.id, "fs.write $path", ModCapability.FILES_WRITE, "allowed", null)
                addLog(mod.id, "info", "Wrote $path ($written bytes)")
                written
            }
            ModOp.SECRETS_GET -> {
                val store = secrets ?: error("Secrets aren't available here")
                withContext(ioContext) { store.get(mod.id, args.optString(0)) } ?: JSONObject.NULL
            }
            ModOp.SECRETS_SET -> {
                val store = secrets ?: error("Secrets aren't available here")
                val value = if (args.isNull(1)) null else args.opt(1) as? String ?: error("\$.secrets.set(key, value) takes a string, or null to remove it")
                withContext(ioContext) { store.set(mod.id, args.optString(0), value) }
                null
            }
            ModOp.PROMPT_SEND -> {
                val text = text(args).trim().take(MAX_TEXT)
                require(text.isNotEmpty()) { "\$.prompt.send(text) needs text" }
                val e = effects ?: error("The app screen is closed")
                // Same limits as run.end follow-ups: the open chat only, at most 3 (25 with agent.chain) after a message from the user.
                val limit = followUpLimit(mod)
                if (!e.followUp(mod, text, scoped!!, limit)) error("The message wasn't sent: its chat isn't open, the user stopped the agent, or mods already sent $limit since the user's last message")
                registry.recordCall(mod.id, "prompt.send", ModCapability.PROMPT_SEND, "allowed", null)
                true
            }
            ModOp.BROWSER_OPEN -> {
                val url = text(args).trim()
                val parsed = runCatching { java.net.URI(url) }.getOrNull()
                require(url.length <= 2_000 && parsed?.scheme == "https" && !parsed.host.isNullOrBlank()) { "\$.browser.open(url) opens https:// pages only" }
                if (!throttle("browser:${mod.id}", 5_000)) false
                else (effects ?: error("The app screen is closed")).openUrl(mod, url)
            }
            ModOp.HTTP_FETCH -> {
                val hosts = mod.manifest.permission(ModCapability.NET_FETCH)?.hosts.orEmpty()
                val request = ModNet.check(text(args), args.optJSONObject(1), hosts)
                require(loaded.fetching < MAX_FETCHES) { "At most $MAX_FETCHES requests at a time" }
                val host = java.net.URI(request.url).host
                if (throttle("fetch-audit:${mod.id}/$host", 60_000)) registry.recordCall(mod.id, "http.fetch $host", ModCapability.NET_FETCH, "allowed", null)
                loaded.fetching++
                try {
                    withContext(ioContext) { http(request) }
                } finally {
                    loaded.fetching--
                }
            }
            ModOp.MODEL_COMPLETE -> {
                val spec = args.optJSONObject(0) ?: JSONObject().put("prompt", text(args))
                val prompt = spec.optString("prompt")
                require(prompt.isNotBlank() && prompt.length <= MAX_MODEL_PROMPT) { "\$.model.complete({ prompt }) needs a prompt of up to $MAX_MODEL_PROMPT characters" }
                val system = spec.optString("system").take(MAX_MODEL_SYSTEM).ifBlank { null }
                val maxTokens = spec.optInt("maxTokens", 1_024).coerceIn(1, MAX_MODEL_TOKENS)
                val e = effects ?: error("The app screen is closed")
                // The estimate is held while the call runs, so calls side by side can't pass the cap together.
                val estimate = (prompt.length + (system?.length ?: 0)) / 4L + maxTokens
                val used = modelTokensToday(mod.id)
                require(used + estimate <= limits.modelTokensPerDay) {
                    "${mod.manifest.name} has used $used of its ${limits.modelTokensPerDay} model tokens today; this call could need $estimate"
                }
                addModelTokens(mod.id, estimate)
                val answer = try {
                    e.modelComplete(mod, prompt, system, maxTokens)
                } catch (error: Throwable) {
                    addModelTokens(mod.id, -estimate)
                    throw error
                }
                val spent = answer.optLong("inputTokens", 0) + answer.optLong("outputTokens", 0)
                addModelTokens(mod.id, (if (spent > 0) spent else estimate) - estimate)
                registry.recordCall(mod.id, "model.complete", ModCapability.MODEL_COMPLETE, "allowed", "${if (spent > 0) spent else estimate} tokens")
                answer.put("usedToday", modelTokensToday(mod.id)).put("dailyLimit", limits.modelTokensPerDay)
            }
            ModOp.MODEL_USAGE -> JSONObject().put("usedToday", modelTokensToday(mod.id)).put("dailyLimit", limits.modelTokensPerDay)
            ModOp.AGENT_LIST -> (effects ?: error("The app screen is closed")).agents()
            ModOp.AGENT_ASK -> {
                val spec = args.optJSONObject(0) ?: JSONObject().put("prompt", text(args))
                val prompt = spec.optString("prompt").trim()
                require(prompt.isNotEmpty()) { "\$.agent.ask({ prompt }) needs a prompt" }
                require(prompt.length <= MAX_PROMPT) { "\$.agent.ask: the prompt is longer than $MAX_PROMPT characters" }
                val harnessId = spec.optString("harness").takeIf { it.isNotBlank() && !spec.isNull("harness") }
                val harness = harnessId?.let {
                    ModManifestParser.harnessFromId(it) ?: throw IllegalArgumentException("\$.agent.ask: harness must be claude, deepseek, antigravity or codex")
                }
                val timeout = spec.optLong("timeoutMs", DEFAULT_ASK_MS).coerceIn(MIN_ASK_MS, MAX_ASK_MS)
                fun option(key: String, max: Int): String? = spec.optString(key).trim().takeIf { !spec.isNull(key) && it.isNotEmpty() }?.also {
                    require(it.length <= max && it.none(Char::isISOControl)) { "\$.agent.ask: $key is not valid" }
                }
                val options = ModAskOptions(model = option("model", 120), effort = option("effort", 40), chat = option("chat", 80))
                addLog(mod.id, "info", "Asked ${harnessId ?: "the current harness"} to work on: ${firstLine(prompt).take(120)}")
                registry.recordCall(mod.id, "\$.agent.ask", ModCapability.AGENT_ASK, "allowed", listOfNotNull(harnessId, options.model, options.chat).joinToString(" ").ifEmpty { null })
                (effects ?: error("The app screen is closed")).askAgent(mod, harness, prompt, scoped!!, timeout, options)
            }
            ModOp.AGENT_STOP -> {
                val taskId = text(args).trim()
                require(taskId.isNotEmpty()) { "\$.agent.stop(taskId) needs the taskId \$.agent.ask answered" }
                val stopped = (effects ?: error("The app screen is closed")).stopAgent(mod, taskId)
                if (stopped) addLog(mod.id, "info", "Stopped the agent task it started")
                stopped
            }
            ModOp.GIT_STATUS -> {
                val result = git(scoped!!, listOf("status", "--porcelain=v1", "--branch", "--untracked-files=all"))
                val lines = result.output.lines().filter { it.isNotBlank() }
                val branch = lines.firstOrNull { it.startsWith("## ") }?.removePrefix("## ")
                val files = lines.filterNot { it.startsWith("## ") }.take(MAX_GIT_FILES).map { line ->
                    JSONObject().put("index", line.take(1).trim()).put("worktree", line.drop(1).take(1).trim())
                        .put("path", line.drop(3).substringAfter(" -> ").trim('"'))
                }
                JSONObject()
                    .put("branch", branch?.substringBefore("...")?.substringBefore(" ")?.removePrefix("No commits yet on ") ?: JSONObject.NULL)
                    .put("tracking", branch?.substringAfter("...", "")?.ifEmpty { null } ?: JSONObject.NULL)
                    .put("clean", files.isEmpty())
                    .put("files", JSONArray(files))
            }
            ModOp.GIT_DIFF -> {
                val spec = args.optJSONObject(0) ?: JSONObject().put("path", if (args.isNull(0)) "" else args.optString(0))
                val path = spec.optString("path").trim().trim('/')
                require(path.isEmpty() || ModManifestParser.isSafeRelativePath(path)) { "\$.git.diff: path must be inside the project" }
                val argv = listOf("diff", "--no-color", "--no-ext-diff") + (if (spec.optBoolean("staged")) listOf("--cached") else emptyList()) +
                    (if (path.isEmpty()) emptyList() else listOf("--", path))
                val result = git(scoped!!, argv)
                JSONObject().put("text", result.output).put("truncated", result.truncated)
            }
            ModOp.GIT_LOG -> {
                val count = (if (args.isNull(0)) 20 else args.optInt(0, 20)).coerceIn(1, 100)
                val result = git(scoped!!, listOf("log", "-n", count.toString(), "--format=%H%x1f%an%x1f%at%x1f%s"))
                JSONArray(result.output.lines().filter { it.contains('\u001f') }.map { line ->
                    val parts = line.split('\u001f')
                    JSONObject().put("hash", parts[0]).put("author", parts.getOrElse(1) { "" })
                        .put("time", (parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L) * 1000).put("subject", parts.getOrElse(3) { "" })
                })
            }
            ModOp.GIT_COMMIT -> {
                val spec = args.optJSONObject(0) ?: JSONObject().put("message", text(args))
                val message = spec.optString("message").trim()
                require(message.isNotEmpty() && message.length <= 2_000) { "\$.git.commit({ message }) needs a message of up to 2,000 characters" }
                if (spec.optBoolean("all", true)) git(scoped!!, listOf("add", "-A"), exclusive = true)
                git(scoped!!, listOf("commit", "-m", message), exclusive = true)
                val hash = git(scoped, listOf("rev-parse", "HEAD")).output.trim()
                addLog(mod.id, "info", "Committed ${hash.take(12)}: ${firstLine(message).take(120)}")
                registry.recordCall(mod.id, "git.commit", ModCapability.GIT_WRITE, "allowed", hash.take(12))
                JSONObject().put("hash", hash)
            }
            ModOp.GIT_BRANCH, ModOp.GIT_SWITCH -> {
                val name = text(args).trim()
                require(GIT_BRANCH_NAME.matches(name) && ".." !in name && !name.endsWith(".lock") && !name.endsWith("/")) {
                    "\$.${op.id}(name): \"$name\" is not a branch name this app accepts"
                }
                // Never forced: git refuses a switch that would lose changes, and that error is the answer.
                git(scoped!!, if (op == ModOp.GIT_BRANCH) listOf("switch", "-c", name) else listOf("switch", name), exclusive = true)
                addLog(mod.id, "info", if (op == ModOp.GIT_BRANCH) "Made and switched to branch $name" else "Switched to branch $name")
                registry.recordCall(mod.id, op.id, ModCapability.GIT_WRITE, "allowed", name)
                name
            }
            ModOp.TERMINAL_RUN -> {
                val command = text(args).trim()
                require(command.isNotEmpty() && command.length <= MAX_COMMAND) { "\$.terminal.run(command) needs a command of up to $MAX_COMMAND characters" }
                val spec = args.optJSONObject(1) ?: JSONObject()
                val cwd = spec.optString("cwd").trim().trim('/')
                require(cwd.isEmpty() || ModManifestParser.isSafeRelativePath(cwd)) { "\$.terminal.run: cwd must be a folder inside the project" }
                val timeout = spec.optLong("timeoutMs", DEFAULT_COMMAND_MS).coerceIn(1_000, MAX_COMMAND_MS)
                // The same gate the user's terminal commands pass; a mod's command is never confirmed for it.
                val gated = runGate("terminal.command", JSONObject().put("command", command), ModCapability.TERMINAL_GATE,
                    setOf("deny", "confirm"), limits.gateBudgetMs, compose = false)
                if (gated.verdict != null) error("${gated.modName} stopped the command: ${gated.reason}")
                val script = (if (cwd.isEmpty()) "" else "cd -- ${shellQuote(cwd)} || exit 1\n") + gated.input.optString("command", command)
                addLog(mod.id, "info", "Ran: ${firstLine(command).take(120)}")
                registry.recordCall(mod.id, "terminal.run", ModCapability.TERMINAL_RUN, "allowed", firstLine(command).take(200))
                val result = (effects ?: error("The app screen is closed"))
                    .guestRun(scoped!!, ModGuestCommand(listOf("/usr/bin/bash", "-lc", script), timeout, MAX_COMMAND_OUTPUT, exclusive = true))
                JSONObject().put("exitCode", result.exitCode).put("output", result.output)
                    .put("timedOut", result.timedOut).put("truncated", result.truncated)
            }
            ModOp.BUILD_RUN -> {
                val install = args.optJSONObject(0)?.optBoolean("install") ?: false
                val gated = runGate("build.start", JSONObject().put("install", install).put("force", false), ModCapability.TERMINAL_GATE,
                    setOf("deny", "confirm"), limits.gateBudgetMs, compose = false)
                if (gated.verdict != null) error("${gated.modName} stopped the build: ${gated.reason}")
                registry.recordCall(mod.id, "build.run", ModCapability.BUILD_RUN, "allowed", if (install) "install" else null)
                (effects ?: error("The app screen is closed")).runBuild(mod, scoped!!, install)
            }
            ModOp.MEMORY_LIST -> (effects ?: error("The app screen is closed")).projectMemory(scoped!!) ?: error("That project is not open")
            ModOp.MODS_TOOLS -> JSONArray(running.values.filter { it.ready }.flatMap { it.provided.values }.map { tool ->
                JSONObject().put("name", tool.exposed).put("mod", tool.modId).put("description", tool.description).put("inputSchema", JSONObject(tool.inputSchema))
            })
            ModOp.MODS_CALL -> {
                val name = text(args).trim()
                require(name.startsWith("mod_")) { "\$.mods.call(name, input) takes a tool name from \$.mods.tools()" }
                val input = if (args.isNull(1)) JSONObject() else args.opt(1) as? JSONObject ?: error("\$.mods.call(name, input): input must be an object")
                require(modCallsInFlight < MAX_MOD_CALLS) { "Too many mod-to-mod calls at once ($MAX_MOD_CALLS); one may be calling itself in a loop." }
                modCallsInFlight++
                val result = try {
                    callTool(name, input)
                } finally {
                    modCallsInFlight--
                }
                if (throttle("modcall:${mod.id}/$name", 10_000)) registry.recordCall(mod.id, "mods.call $name", ModCapability.MODS_CALL, if (result.isError) "failed" else "allowed", null)
                JSONObject().put("text", result.text).put("isError", result.isError)
            }
            ModOp.PROMPT_DRAFT -> {
                val text = text(args).trim().take(MAX_TEXT)
                require(text.isNotEmpty()) { "\$.prompt.draft(text) needs text" }
                (effects ?: error("The app screen is closed")).promptDraft(mod, text, scoped!!)
            }
        })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun followUp(loaded: Loaded, value: Any?, where: ModEventContext?) {
        val text = (value as? String)?.trim()?.take(MAX_PROMPT)
        if (text.isNullOrEmpty() || where?.projectId == null) return
        val mod = currentMod(loaded)
        if (!allowed(mod, ModCapability.PROMPT_SEND)) {
            addLog(loaded.id, "warn", "Its run.end hook asked for a follow-up, which needs the prompt.send permission.")
            registry.recordDenied(mod.id, "run.end followUp", ModCapability.PROMPT_SEND, "permission is off")
            return
        }
        val limit = followUpLimit(mod)
        val queued = runCatching { effects?.followUp(mod, text, where, limit) == true }.getOrDefault(false)
        addLog(loaded.id, "info", if (queued) "Queued a follow-up: ${firstLine(text).take(120)}" else "Its follow-up wasn't queued: the chat changed, the user stopped the agent, or mods already chained $limit follow-ups.")
        if (queued) registry.recordCall(mod.id, "run.end followUp", ModCapability.PROMPT_SEND, "allowed", null)
    }

    private suspend fun answer(loaded: Loaded, callId: Long, result: Result<Any?>) {
        val script = result.fold(
            onSuccess = { "__mh.answer($callId, true, ${ModJson.literal(it)})" },
            onFailure = { "__mh.answer($callId, false, ${ModJson.literal(it.message ?: it.javaClass.simpleName)})" },
        )
        call(loaded, script)
    }

    private fun addTimer(loaded: Loaded, timer: Long, ms: Long, repeat: Boolean): Boolean {
        val name = if (repeat) "every" else "after"
        require(loaded.timers.size < MAX_TIMERS) { "A mod can have at most $MAX_TIMERS timers" }
        require(ms in (if (repeat) MIN_EVERY_MS else 0L)..MAX_TIMER_MS) {
            if (repeat) "\$.clock.every(ms, fn) needs at least 1000 ms" else "\$.clock.after(ms, fn) takes up to 24 hours"
        }
        loaded.timers[timer]?.cancel()
        loaded.timers[timer] = scope.launch {
            do {
                delay(ms)
                if (loaded.closed) return@launch
                // Timers wait while the app is in the background with no task running (spec v2 §8).
                if (foreground || taskRunning) dispatch(loaded, "clock.tick", JSONObject().put("timer", timer), limits.eventBudgetMs)
            } while (repeat)
            loaded.timers.remove(timer)
        }
        addLog(loaded.id, "debug", "Timer $timer set: $name $ms ms")
        return true
    }

    private fun openPane(loaded: Loaded, args: JSONArray): JSONObject {
        val spec = args.optJSONObject(0) ?: JSONObject().put("id", args.optString(0)).put("title", args.optString(1))
        val id = spec.optString("id")
        require(PANE_ID.matches(id)) { "\$.ui.open({ id, title }): id must be 1 to 40 lowercase letters, digits or dashes." }
        val title = ModHookRunner.clean(spec.optString("title").take(MAX_LINE)).ifBlank { id }
        val list = panes.getOrPut(loaded.id) { LinkedHashMap() }
        require(id in list || list.size < MAX_PANES) { "A mod can have at most $MAX_PANES panes open." }
        list[id] = title
        publish()
        val placed = slots[loaded.id to paneSurface(id)]?.holders?.isNotEmpty() == true
        if (placed) requestRender(loaded.id, paneSurface(id))
        return JSONObject().put("isPlaced", placed)
    }

    /** Draws [surface] again soon if someone shows it, at most every [ModHostLimits.redrawGapMs]. */
    private fun requestRender(modId: String, surface: String) {
        val slot = slots[modId to surface]?.takeIf { it.holders.isNotEmpty() } ?: return
        if (slot.job?.isActive == true) {
            slot.again = true
            return
        }
        slot.job = scope.launch {
            do {
                slot.again = false
                val wait = slot.lastAt + limits.redrawGapMs - clock()
                if (wait > 0) delay(wait)
                slot.lastAt = clock()
                render(modId, surface)
            } while (slot.again && slot.holders.isNotEmpty())
        }
    }

    private suspend fun render(modId: String, surface: String) {
        val loaded = running[modId]?.takeIf { it.ready }
        val mod = loaded?.let(::currentMod)
        val paneId = surface.removePrefix(PANE_PREFIX).takeIf { surface.startsWith(PANE_PREFIX) }
        val blocked = when {
            loaded == null || mod == null -> "Not running right now."
            paneId == null && surface != BAND -> "Unknown surface."
            paneId != null && !allowed(mod, ModCapability.UI_PANE) -> "Needs the “${ModCapability.UI_PANE.summary}” permission."
            surface == BAND && !mod.shows(ModPlace.BAND) -> "Off."
            "ui.render" !in loaded.events -> "This mod's code has no ui.render hook, so it draws nothing."
            else -> null
        }
        if (blocked != null || loaded == null) {
            setDrawing(modId, surface, ModDrawing(message = blocked))
            return
        }
        val payload = if (paneId != null) {
            JSONObject().put("component", "Pane").put("requestId", paneId).put("title", paneTitle(modId, paneId))
        } else {
            JSONObject().put("component", "AbovePrompt")
        }
        payload.put("surface", "mobile")
        val result = CompletableDeferred<JSONObject?>()
        dispatch(loaded, "ui.render", payload, limits.renderBudgetMs, result = result) { id, body ->
            "__mh.render($id, ${JSONObject.quote(surface)}, $body)"
        }
        val answer = result.await()
        val component = if (paneId != null) "Pane" else "AbovePrompt"
        val previous = drawings[modId]?.get(surface)?.tree
        val drawing = when {
            answer == null -> ModDrawing(previous, "It took too long to draw.", drawn = true)
            answer.has("error") -> ModDrawing(previous, "It couldn't draw: ${firstLine(answer.optString("error"))}", drawn = true)
            else -> try {
                ModDrawing(ModTree.parse(answer.opt("value")), drawn = true)
            } catch (e: ModTreeException) {
                addLog(modId, "error", "ui.render ($component) refused: ${e.message}")
                ModDrawing(null, "It drew something the app can't show: ${e.message}", drawn = true)
            }
        }
        setDrawing(modId, surface, drawing)
    }

    private fun setDrawing(modId: String, surface: String, drawing: ModDrawing) {
        if (running[modId] == null && drawing.drawn) return
        drawings.getOrPut(modId) { HashMap() }[surface] = drawing
        publish()
    }

    private fun paneTitle(modId: String, paneId: String): String =
        panes[modId]?.get(paneId) ?: mods.mods.firstOrNull { it.id == modId }?.manifest?.name ?: paneId

    private fun registerCommand(loaded: Loaded, spec: JSONObject): Boolean {
        val name = spec.optString("name").lowercase(Locale.ROOT)
        require(ModManifestParser.isCommandName(name)) { "Command name \"$name\" must be 2 to 31 lowercase letters, digits or dashes, starting with a letter." }
        require(SlashCommandEngine.ALL_SLASH_COMMANDS.none { it.name.equals(name, ignoreCase = true) }) { "/$name is a built-in command." }
        require(name in loaded.commands || loaded.commands.size < MAX_COMMANDS) { "A mod can add at most $MAX_COMMANDS commands." }
        val description = ModHookRunner.clean(spec.optString("description").take(120))
        val hint = spec.optString("argumentHint").takeIf { it.isNotBlank() }?.let { ModHookRunner.clean(it.take(40)) }
        loaded.commands[name] = ModCodeCommand(loaded.id, loaded.mod.manifest.name, name, description, hint)
        setInfo(loaded.id) { it.copy(commands = loaded.commands.values.toList()) }
        return true
    }

    /** Model tokens each mod used today (UTC), by mod id; kept in its data folder. Host thread. */
    private val modelUsage = HashMap<String, Pair<String, Long>>()

    private fun usageDay(): String = java.time.Instant.ofEpochMilli(wallClock()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()

    private suspend fun modelTokensToday(modId: String): Long {
        val day = usageDay()
        val known = modelUsage[modId] ?: withContext(ioContext) {
            runCatching { JSONObject(File(dataDir(modId), MODEL_USAGE_FILE).readText()) }.getOrNull()
                ?.let { it.optString("day") to it.optLong("tokens") } ?: (day to 0L)
        }.also { modelUsage[modId] = it }
        return if (known.first == day) known.second else 0L
    }

    private suspend fun addModelTokens(modId: String, tokens: Long) {
        val day = usageDay()
        val total = (modelTokensToday(modId) + tokens).coerceAtLeast(0)
        modelUsage[modId] = day to total
        withContext(ioContext) {
            runCatching {
                val dir = dataDir(modId).apply { mkdirs() }
                val staging = File(dir, "$MODEL_USAGE_FILE.tmp")
                staging.writeText(JSONObject().put("day", day).put("tokens", total).toString())
                check(staging.renameTo(File(dir, MODEL_USAGE_FILE)))
            }
        }
    }

    private fun registerTool(loaded: Loaded, spec: JSONObject): String {
        val name = spec.optString("name")
        require(ModTool.NAME.matches(name)) { "Tool name \"$name\" must be 2 to 40 lowercase letters, digits or underscores, starting with a letter." }
        require(name in loaded.provided || loaded.provided.size < MAX_TOOLS) { "A mod can give at most $MAX_TOOLS tools." }
        val description = ModHookRunner.clean(spec.optString("description").take(1_000))
        require(description.isNotBlank()) { "Tool $name needs a description; the agent reads it to decide when to call it." }
        val schema = when (val given = spec.opt("inputSchema")) {
            null, JSONObject.NULL -> JSONObject().put("type", "object")
            is JSONObject -> given
            else -> error("Tool $name's inputSchema must be a JSON Schema object.")
        }
        require(schema.optString("type", "object") == "object") { "Tool $name's inputSchema must have type \"object\"." }
        val schemaText = schema.toString()
        require(schemaText.length <= MAX_TOOL_SCHEMA) { "Tool $name's inputSchema is larger than ${MAX_TOOL_SCHEMA / 1024} KB." }
        val tool = ModTool(loaded.id, loaded.mod.manifest.name, name, description, schemaText)
        val clash = running.values.firstOrNull { other -> other !== loaded && other.provided.values.any { it.exposed == tool.exposed } }
        require(clash == null) { "Another mod (${clash?.mod?.manifest?.name}) already gives a tool named ${tool.exposed}." }
        loaded.provided[name] = tool
        setInfo(loaded.id) { it.copy(tools = loaded.provided.values.toList()) }
        addLog(loaded.id, "info", "Gave the agents the tool ${tool.exposed}")
        return tool.exposed
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun currentMod(loaded: Loaded): InstalledMod = mods.mods.firstOrNull { it.id == loaded.id } ?: loaded.mod

    private fun allowed(mod: InstalledMod, capability: ModCapability): Boolean = capability in mod.granted && mod.canHold(capability)

    /** Follow-ups mods may chain after one message from the user: more for a mod allowed `agent.chain`. */
    /**
     * Runs git in [context]'s project; a non-zero exit throws with git's own words. [exclusive]
     * (changes to the repository) waits for no task, terminal command or build to be running.
     */
    private suspend fun git(context: ModEventContext, argv: List<String>, exclusive: Boolean = false): ModGuestResult {
        val command = ModGuestCommand(listOf("git", "-c", "core.quotepath=off", "--no-pager") + argv, GIT_TIMEOUT_MS, MAX_COMMAND_OUTPUT, exclusive)
        val result = (effects ?: error("The app screen is closed")).guestRun(context, command)
        check(!result.timedOut) { "git ${argv.first()} took longer than ${GIT_TIMEOUT_MS / 1000} s" }
        check(result.exitCode == 0) { "git ${argv.first()} failed: ${result.output.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").take(500)}" }
        return result
    }

    private fun shellQuote(text: String) = "'" + text.replace("'", "'\\''") + "'"

    private fun followUpLimit(mod: InstalledMod): Int = if (allowed(mod, ModCapability.AGENT_CHAIN)) MAX_CHAIN_FOLLOW_UPS else MAX_FOLLOW_UPS

    private fun keyOf(mod: InstalledMod): String = "${mod.manifest.version}:${mod.installedAt}:${options(mod)}"

    /** The mod's settings except secrets, as `register(on, options)` receives them. */
    private fun options(mod: InstalledMod): JSONObject = JSONObject().apply {
        mod.manifest.settings.filter { it.type != ModSettingType.SECRET }.forEach { setting ->
            put(setting.key, mod.settingValue(setting.key) ?: JSONObject.NULL)
        }
    }

    private fun scopeOf(context: ModEventContext?): Any = ModJson.project(context)

    /** True when [a] and [b] hold the same JSON, whatever the order of their keys. */
    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && sameJson(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    /** [passed] as a mod handed it on, kept to the fields the app sent and their kinds. */
    private fun mergeInput(current: JSONObject, passed: JSONObject): JSONObject {
        val merged = JSONObject(current.toString())
        for (key in current.keys()) {
            if (!passed.has(key)) continue
            val before = current.opt(key)
            val after = passed.opt(key)
            val sameKind = when (before) {
                is String -> after is String
                is Boolean -> after is Boolean
                is Number -> after is Number
                is JSONObject -> after is JSONObject
                is JSONArray -> after is JSONArray
                else -> false
            }
            if (sameKind) merged.put(key, after)
        }
        return merged
    }

    /** The texts a `prompt.compose` hook added after the sections it was given. */
    private fun addedSections(before: JSONArray, after: JSONArray): List<String> =
        (before.length() until minOf(after.length(), before.length() + 8)).mapNotNull { i ->
            when (val item = after.opt(i)) {
                is String -> item
                is JSONObject -> item.optString("text").takeIf { it.isNotBlank() }
                else -> null
            }?.trim()?.takeIf { it.isNotEmpty() }
        }

    private fun utf8Take(text: String, maxBytes: Int): String {
        if (text.length * 3 <= maxBytes || text.toByteArray().size <= maxBytes) return text
        var end = minOf(text.length, maxBytes)
        while (end > 0 && text.substring(0, end).toByteArray().size > maxBytes) end -= maxOf(1, (end - maxBytes / 3) / 2)
        return text.substring(0, end)
    }

    private fun modName(id: String) = mods.mods.firstOrNull { it.id == id }?.manifest?.name ?: id

    private fun text(args: JSONArray): String = if (args.isNull(0)) "" else args.opt(0).toString()

    private fun optionalLine(args: JSONArray): String? =
        if (args.length() == 0 || args.isNull(0)) null else ModHookRunner.clean(args.opt(0).toString()).take(MAX_LINE).ifEmpty { null }

    private fun throttle(key: String, intervalMs: Long): Boolean {
        val now = clock()
        val last = lastCall[key]
        if (last != null && now - last < intervalMs) return false
        lastCall[key] = now
        return true
    }

    private fun refreshListening() {
        listening = running.values.filter { it.ready }.flatMap { it.events }.toSet()
        toolsPossible = running.values.any { it.ready && allowed(currentMod(it), ModCapability.TOOLS_PROVIDE) }
        val tools = running.values
            .filter { it.ready && "tool.call" in it.events && allowed(currentMod(it), ModCapability.TOOLS_GATE) }
            .flatMap { it.tools }
            .toSet()
        toolGateMatcher = when {
            tools.isEmpty() -> null
            "*" in tools -> "*"
            else -> tools.sorted().joinToString("|") { it.replace(".", "\\.") }
        }
    }

    private fun addLog(id: String, level: String, text: String) {
        synchronized(logsLock) {
            val lines = logs.getOrPut(id) { ArrayDeque() }
            lines.addLast(ModLogEntry(wallClock(), level, text.take(MAX_LOG_TEXT)))
            while (lines.size > MAX_LOG_LINES) lines.removeFirst()
        }
        _logVersion.value++
    }

    private fun setInfo(id: String, change: (ModRuntimeInfo) -> ModRuntimeInfo) {
        infos[id] = change(infos[id] ?: ModRuntimeInfo(ModRunState.WAITING))
        publish()
    }

    private fun publish() {
        val logic = logicMods()
        val logicIds = logic.map { it.id }.toSet()
        infos.keys.retainAll(logicIds)
        val engineOn = engine != null
        val shown = logic.associate { mod ->
            val info = infos[mod.id] ?: ModRuntimeInfo(ModRunState.WAITING)
            val adjusted = when {
                mod.id in crashed -> info.copy(state = ModRunState.CRASHED, message = crashed[mod.id])
                engineState == ModEngineState.UNAVAILABLE -> info.copy(state = ModRunState.BLOCKED, message = engineMessage)
                engineState == ModEngineState.PAUSED -> info.copy(state = ModRunState.BLOCKED, message = engineMessage)
                !engineOn && info.state != ModRunState.RESTARTING && info.state != ModRunState.BLOCKED ->
                    info.copy(state = ModRunState.WAITING, message = "Runs while a project is open or Mods is showing.", commands = emptyList(), tools = emptyList())
                else -> info
            }
            mod.id to adjusted
        }
        _snapshot.value = ModHostSnapshot(engineState, engineMessage, shown)
        panes.keys.retainAll(logicIds)
        drawings.keys.retainAll(logicIds)
        _surfaces.value = logic.associate { mod ->
            val dashboard = mod.manifest.dashboard?.pane
            val opened = panes[mod.id].orEmpty()
            val list = buildList {
                if (dashboard != null) add(ModPane(dashboard, opened[dashboard] ?: "Dashboard"))
                opened.forEach { (id, title) -> if (id != dashboard) add(ModPane(id, title)) }
            }
            mod.id to ModSurfaces(
                modId = mod.id,
                name = mod.manifest.name,
                dir = mod.dir,
                panes = list,
                dashboard = dashboard,
                drawings = drawings[mod.id]?.toMap().orEmpty(),
                shownIn = ModPlace.entries.filter(mod::shows).toSet(),
                status = shown[mod.id]?.status,
            )
        }
    }

    companion object {
        private const val TAG = "ModHost"
        const val MAX_LOGIC_MODS = 8
        const val MAX_STRIKES = 5
        const val MAX_ENGINE_DEATHS = 3
        const val STRIKE_WINDOW_MS = 10 * 60_000L
        private const val HOLD_TEST = "#engine-test"
        private const val INBOX_CAPACITY = 1024
        private const val MAX_CALLS_IN_FLIGHT = 64
        private const val MAX_TIMERS = 16
        private const val MAX_COMMANDS = 16
        private const val MIN_EVERY_MS = 1_000L
        private const val MAX_TIMER_MS = 24 * 60 * 60_000L
        private const val MAX_SLEEP_MS = 10 * 60_000L
        private const val MAX_TEXT = 4_000
        private const val MAX_LINE = 80
        private const val MAX_LOG_LINES = 200
        private const val MAX_LOG_TEXT = 2_000
        private const val COALESCE_MS = 1_000L
        private const val WATCH_MIN_MS = 50L
        private const val MAX_PANES = 8
        private const val MAX_RUN_LOG_LINES = 200
        private const val MAX_PROMPT = 20_000
        /** What one mod may add to a prompt (spec v2 §7 prompt injection). */
        const val MAX_SECTION_BYTES = 8 * 1024
        const val MAX_FETCHES = 4
        const val MAX_MODEL_PROMPT = 100_000
        const val MAX_MODEL_SYSTEM = 20_000
        const val MAX_MODEL_TOKENS = 4_096
        private const val MODEL_USAGE_FILE = "model-usage.json"
        const val MAX_TOOLS = 16
        const val MAX_TOOL_SCHEMA = 8 * 1024
        const val MAX_TOOL_TEXT = 48_000
        private const val MAX_COMMAND = 8_000
        private const val MAX_COMMAND_OUTPUT = 1024 * 1024
        private const val DEFAULT_COMMAND_MS = 2 * 60_000L
        private const val MAX_COMMAND_MS = 10 * 60_000L
        private const val GIT_TIMEOUT_MS = 60_000L
        private const val MAX_GIT_FILES = 1_000
        private val GIT_BRANCH_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._/-]{0,99}$")
        const val MAX_FOLLOW_UPS = 3
        const val MAX_CHAIN_FOLLOW_UPS = 25
        private const val MAX_MOD_CALLS = 8
        private const val MIN_ASK_MS = 10_000L
        private const val DEFAULT_ASK_MS = 30 * 60_000L
        private const val MAX_ASK_MS = 2 * 60 * 60_000L
        private const val PANE_PREFIX = "pane:"
        private val PANE_ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")

        /** The surface above the message box in a project. */
        const val BAND = "band"

        /** The surface key of pane [id]. */
        fun paneSurface(id: String) = "$PANE_PREFIX$id"

        private fun firstLine(text: String) = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(300) ?: text.take(300)

        /** The line of an engine error that names the error, such as "SyntaxError: Unexpected token". */
        private fun errorLine(text: String) =
            text.lineSequence().firstOrNull { ERROR_NAME.containsMatchIn(it) }?.trim()?.take(300) ?: firstLine(text)

        private val ERROR_NAME = Regex("^\\s*(?:Uncaught\\s+)?\\w*Error\\b")

        @Volatile private var instance: ModHost? = null
        private val _current = MutableStateFlow<ModHost?>(null)
        /** The app's mod host once something created it; screens inside projects read surfaces from it. */
        val current: StateFlow<ModHost?> = _current.asStateFlow()

        /** The app's mod host. The first call reads the mod registry, so call it off Main. */
        fun getInstance(context: Context): ModHost = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                val registry = ModRegistry.getInstance(app)
                ModHost(
                    registry = registry,
                    engineFactory = { JsSandboxEngine(app) },
                    shimSource = { app.assets.open("mods/mh-shim.js").bufferedReader().use { it.readText() } },
                    dataDir = registry::dataDir,
                    secrets = ModSecrets(app),
                ).also {
                    it.start()
                    instance = it
                    _current.value = it
                }
            }
        }
    }
}
