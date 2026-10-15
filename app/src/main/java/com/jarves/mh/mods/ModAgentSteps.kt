package com.jarves.mh.mods

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * The agent tasks mods start (`$.agent.ask`) and that the user sends from a mod's console, as
 * dashboards show them: what is queued, waiting, running and done, newest last. Process-wide, in
 * memory. The user's sends wait in a queue they can reorder and cancel; the screen that runs agent
 * tasks takes them one at a time ([takeNext]), since a project runs one task at a time.
 */
class ModAgentSteps(private val clock: () -> Long = System::currentTimeMillis) {
    enum class State(val label: String, val done: Boolean) {
        QUEUED("Queued", false), WAITING("Waiting", false), RUNNING("Running", false), COMPLETED("Done", true),
        STOPPED("Stopped", true), FAILED("Failed", true), TIMEOUT("Timed out", true), CANCELLED("Cancelled", true),
    }

    data class Step(
        val id: Long,
        val modId: String,
        /** The mod's role this step ran as (`contributes.roles`), when it had one. */
        val role: String?,
        /** The harness id (`claude`, `codex`, ...); null until known. */
        val harness: String?,
        val prompt: String,
        /** True when the user sent it from the dashboard, false when the mod's code did. */
        val fromUser: Boolean,
        val state: State = State.WAITING,
        val chatId: String? = null,
        val taskId: String? = null,
        val startedAt: Long,
        val endedAt: Long? = null,
        val reply: String? = null,
        val error: String? = null,
        /** The project a queued send runs in. */
        val projectId: String? = null,
        val model: String? = null,
        val effort: String? = null,
    )

    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()
    private var nextId = 1L
    private val _paused = MutableStateFlow<Set<String>>(emptySet())
    /** Mods whose chains are paused: their queued sends and new `$.agent.ask` calls wait for Resume. */
    val paused: StateFlow<Set<String>> = _paused.asStateFlow()

    /** Stops a running step's task; set by the screen that runs agent tasks. True when it stopped. */
    @Volatile var stopper: ((Step) -> Boolean)? = null

    /** Tells the screen that runs agent tasks that a queued send may be ready ([takeNext]). */
    @Volatile var wake: (() -> Unit)? = null

    fun add(modId: String, role: String?, harness: String?, prompt: String, fromUser: Boolean, projectId: String? = null): Long =
        insert(Step(0, modId, role, harness, prompt.take(MAX_PROMPT), fromUser, startedAt = clock(), projectId = projectId))

    /** Queues the user's send to [role] of [modId] in [projectId]; it runs after the sends before it. */
    fun enqueue(modId: String, role: String?, harness: String?, prompt: String, projectId: String, model: String? = null, effort: String? = null): Long =
        insert(Step(0, modId, role, harness, prompt.take(MAX_PROMPT), fromUser = true, state = State.QUEUED, startedAt = clock(),
            projectId = projectId, model = model, effort = effort)).also { wake?.invoke() }

    /** Sends [id]'s prompt again, to the same role and harness, as the user's send in [projectId]. */
    fun retry(id: Long, projectId: String): Long? {
        val step = _steps.value.firstOrNull { it.id == id && it.state.done } ?: return null
        return enqueue(step.modId, step.role, step.harness, step.prompt, projectId, step.model, step.effort)
    }

    /** The first queued send whose mod isn't paused, now waiting to run; null when there is none. */
    fun takeNext(): Step? = synchronized(this) {
        val next = _steps.value.firstOrNull { it.state == State.QUEUED && it.modId !in _paused.value } ?: return null
        change(next.id) { it.copy(state = State.WAITING) }
        next.copy(state = State.WAITING)
    }

    /** Drops a queued send; true when it was queued. */
    fun cancel(id: Long): Boolean = synchronized(this) {
        if (_steps.value.none { it.id == id && it.state == State.QUEUED }) return false
        change(id) { it.copy(state = State.CANCELLED, endedAt = clock()) }
        true
    }

    /** Moves a queued send one place earlier ([earlier]) or later among its mod's queued sends. */
    fun move(id: Long, earlier: Boolean): Boolean = synchronized(this) {
        val list = _steps.value
        val step = list.firstOrNull { it.id == id && it.state == State.QUEUED } ?: return false
        val queued = list.filter { it.modId == step.modId && it.state == State.QUEUED }
        val other = queued.getOrNull(queued.indexOf(step) + if (earlier) -1 else 1) ?: return false
        val a = list.indexOf(step)
        val b = list.indexOf(other)
        _steps.value = list.toMutableList().also { it[a] = other; it[b] = step }
        true
    }

    fun pause(modId: String) = _paused.update { it + modId }

    fun resume(modId: String) {
        _paused.update { it - modId }
        wake?.invoke()
    }

    /** Waits while [modId]'s chain is paused. */
    suspend fun awaitResumed(modId: String) {
        _paused.first { modId !in it }
    }

    /** Stop chain: pauses [modId], drops its queued sends and stops its running step. */
    fun stopChain(modId: String) {
        pause(modId)
        _steps.value.filter { it.modId == modId && it.state == State.QUEUED }.forEach { cancel(it.id) }
        _steps.value.filter { it.modId == modId && it.state == State.RUNNING }.forEach { stop(it.id) }
    }

    private fun insert(step: Step): Long = synchronized(this) {
        val id = nextId++
        _steps.update { trim(it + step.copy(id = id)) }
        id
    }

    /** Keeps every unfinished step and the newest finished ones, [MAX_STEPS] in all. */
    private fun trim(list: List<Step>): List<Step> {
        var extra = list.size - MAX_STEPS
        if (extra <= 0) return list
        return list.filter { step -> !(step.state.done && extra-- > 0) }
    }

    fun running(id: Long, harness: String?, taskId: String?, chatId: String?) =
        change(id) { it.copy(state = State.RUNNING, harness = harness ?: it.harness, taskId = taskId, chatId = chatId ?: it.chatId) }

    fun ended(id: Long, state: State, reply: String? = null, error: String? = null) =
        change(id) { if (it.state.done) it else it.copy(state = state, endedAt = clock(), reply = reply?.take(MAX_REPLY), error = error?.take(500)) }

    fun of(modId: String): List<Step> = _steps.value.filter { it.modId == modId }

    /** Stops [id] when it is running; true when it was. */
    fun stop(id: Long): Boolean {
        val step = _steps.value.firstOrNull { it.id == id && it.state == State.RUNNING } ?: return false
        return runCatching { stopper?.invoke(step) == true }.getOrDefault(false)
    }

    private fun change(id: Long, transform: (Step) -> Step) {
        _steps.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    companion object {
        const val MAX_STEPS = 100
        /** A handoff carries a whole reply, so prompts keep as much as `$.agent.ask` takes. */
        const val MAX_PROMPT = 20_000
        const val MAX_REPLY = 8_000

        /** The app's steps. */
        val shared = ModAgentSteps()

        /** The step state for an `agent.ask` answer's `status`. */
        fun stateOf(status: String?): State = when (status) {
            "completed" -> State.COMPLETED
            "stopped" -> State.STOPPED
            "timeout" -> State.TIMEOUT
            else -> State.FAILED
        }
    }
}
