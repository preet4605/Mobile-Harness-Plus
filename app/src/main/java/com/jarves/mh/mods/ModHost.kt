package com.jarves.mh.mods

import android.content.Context
import com.jarves.mh.BuildConfig
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.coroutines.CoroutineContext

/** Where mod actions that need the app's screen land; set by the screen while it exists. */
interface ModHostEffects : ModHookEffects {
    /** The newest [limit] messages of [context]'s chat, oldest first, as (from, text); null when that chat is not open. */
    fun chatMessages(context: ModEventContext, limit: Int): List<Pair<String, String>>?

    /** The plan of the task in [context]'s chat, when the app made one. */
    fun currentPlan(context: ModEventContext): ExecutionPlan?
}

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
)

data class ModHostSnapshot(
    val engine: ModEngineState = ModEngineState.OFF,
    val engineMessage: String? = null,
    /** Logic mods only (mods with a hooks module), by mod id. */
    val mods: Map<String, ModRuntimeInfo> = emptyMap(),
) {
    val commands: List<ModCodeCommand> get() = mods.values.flatMap { it.commands }
}

data class ModLogEntry(val time: Long, val level: String, val text: String)

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
) {
    private val scope = CoroutineScope(SupervisorJob() + hostContext + CoroutineExceptionHandler { _, error ->
        runCatching { android.util.Log.w(TAG, "Mod host error", error) }
    })

    @Volatile var effects: ModHostEffects? = null

    private val _snapshot = MutableStateFlow(ModHostSnapshot())
    val snapshot: StateFlow<ModHostSnapshot> = _snapshot.asStateFlow()
    private val _logVersion = MutableStateFlow(0)
    /** Changes whenever a mod log gains a line; read the lines with [log]. */
    val logVersion: StateFlow<Int> = _logVersion.asStateFlow()

    /** Events at least one running mod hooks; read from any thread to skip building unused events. */
    @Volatile private var listening: Set<String> = emptySet()
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
        var ready = false
        var closed = false
        val dispatches = HashMap<Long, Dispatch>()
        val timers = HashMap<Long, Job>()
        val commands = LinkedHashMap<String, ModCodeCommand>()
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
            reconcile()
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
            unload(loaded, sendEnd = false)
            val message = when (e) {
                is ModCodeException, is ModScriptException -> errorLine(e.message.orEmpty())
                else -> e.message ?: e.javaClass.simpleName
            }
            crash(mod.id, "Its code didn't load: $message")
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
        loaded.dispatches.values.forEach { it.watchdog?.cancel(); it.result?.complete(null) }
        loaded.dispatches.clear()
        loaded.settle?.cancel()
        loaded.inbox.close()
        loaded.consumer?.cancel()
        runCatching { loaded.isolate.close() }
        refreshListening()
        setInfo(loaded.id) { it.copy(state = ModRunState.WAITING, commands = emptyList()) }
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
        running.values.filter { it.ready && event in it.events }.forEach { loaded ->
            val mod = currentMod(loaded)
            val visible = JSONObject(payload.toString())
            private.forEach { (field, capability) -> if (!allowed(mod, capability)) visible.remove(field) }
            dispatch(loaded, event, visible, budget)
        }
    }

    private suspend fun dispatch(
        loaded: Loaded,
        event: String,
        payload: JSONObject,
        budgetMs: Long,
        eventScope: Any = scopeOf(context),
        result: CompletableDeferred<JSONObject?>? = null,
    ) {
        if (!loaded.ready || loaded.closed) {
            result?.complete(null)
            return
        }
        if (event != "clock.tick" && event !in loaded.events) {
            result?.complete(JSONObject().put("value", JSONObject.NULL))
            return
        }
        if (!payload.has("project")) payload.put("project", eventScope)
        payload.put("event", event)
        val dispatch = Dispatch(nextDispatch++, event, budgetMs, clock(), result)
        loaded.dispatches[dispatch.id] = dispatch
        dispatch.watchdog = scope.launch { watch(loaded, dispatch) }
        call(loaded, "__mh.dispatch(${dispatch.id}, ${JSONObject.quote(event)}, $payload)")
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
        if (op == ModOp.CLOCK_SLEEP) {
            val ms = args.optLong(0, -1)
            if (ms !in 0..MAX_SLEEP_MS) {
                complete(Result.failure(IllegalArgumentException("\$.clock.sleep(ms) takes 0 to ${MAX_SLEEP_MS / 60_000} minutes.")))
                return
            }
            scope.launch {
                delay(ms)
                complete(Result.success(null))
            }
            return
        }
        complete(runOp(loaded, mod, op, args, scoped))
    }

    private suspend fun runOp(loaded: Loaded, mod: InstalledMod, op: ModOp, args: JSONArray, scoped: ModEventContext?): Result<Any?> = try {
        Result.success(when (op) {
            ModOp.STORE_GET -> withContext(ioContext) { loaded.store.get(args.optString(0)) }
            ModOp.STORE_SET -> withContext(ioContext) { loaded.store.set(args.optString(0), args.opt(1)); null }
            ModOp.STORE_DELETE -> withContext(ioContext) { loaded.store.delete(args.optString(0)); null }
            ModOp.STORE_KEYS -> withContext(ioContext) { JSONArray(loaded.store.keys()) }
            ModOp.CLOCK_AFTER, ModOp.CLOCK_EVERY -> addTimer(loaded, args.optLong(0), args.optLong(1, -1), op == ModOp.CLOCK_EVERY)
            ModOp.CLOCK_CANCEL -> loaded.timers.remove(args.optLong(0))?.cancel() != null
            ModOp.CLOCK_SLEEP -> null
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

    // ---- helpers -------------------------------------------------------------------------

    private fun currentMod(loaded: Loaded): InstalledMod = mods.mods.firstOrNull { it.id == loaded.id } ?: loaded.mod

    private fun allowed(mod: InstalledMod, capability: ModCapability): Boolean = capability in mod.granted && mod.canHold(capability)

    private fun keyOf(mod: InstalledMod): String = "${mod.manifest.version}:${mod.installedAt}:${options(mod)}"

    /** The mod's settings except secrets, as `register(on, options)` receives them. */
    private fun options(mod: InstalledMod): JSONObject = JSONObject().apply {
        mod.manifest.settings.filter { it.type != ModSettingType.SECRET }.forEach { setting ->
            put(setting.key, mod.settingValue(setting.key) ?: JSONObject.NULL)
        }
    }

    private fun scopeOf(context: ModEventContext?): Any = ModJson.project(context)

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
                    info.copy(state = ModRunState.WAITING, message = "Runs while a project is open or Mods is showing.", commands = emptyList())
                else -> info
            }
            mod.id to adjusted
        }
        _snapshot.value = ModHostSnapshot(engineState, engineMessage, shown)
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

        private fun firstLine(text: String) = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(300) ?: text.take(300)

        /** The line of an engine error that names the error, such as "SyntaxError: Unexpected token". */
        private fun errorLine(text: String) =
            text.lineSequence().firstOrNull { ERROR_NAME.containsMatchIn(it) }?.trim()?.take(300) ?: firstLine(text)

        private val ERROR_NAME = Regex("^\\s*(?:Uncaught\\s+)?\\w*Error\\b")

        @Volatile private var instance: ModHost? = null

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
                ).also {
                    it.start()
                    instance = it
                }
            }
        }
    }
}
