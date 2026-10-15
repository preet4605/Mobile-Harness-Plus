package com.jarves.mh.mods

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The agent tasks mods start (`$.agent.ask`) and that the user sends from a mod's dashboard, as
 * dashboards show them: what is waiting, running and done, newest last. Process-wide, in memory.
 */
class ModAgentSteps(private val clock: () -> Long = System::currentTimeMillis) {
    enum class State(val label: String, val done: Boolean) {
        WAITING("Waiting", false), RUNNING("Running", false), COMPLETED("Done", true),
        STOPPED("Stopped", true), FAILED("Failed", true), TIMEOUT("Timed out", true),
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
    )

    private val _steps = MutableStateFlow<List<Step>>(emptyList())
    val steps: StateFlow<List<Step>> = _steps.asStateFlow()
    private var nextId = 1L

    /** Stops a running step's task; set by the screen that runs agent tasks. True when it stopped. */
    @Volatile var stopper: ((Step) -> Boolean)? = null

    fun add(modId: String, role: String?, harness: String?, prompt: String, fromUser: Boolean): Long = synchronized(this) {
        val id = nextId++
        _steps.update { (it + Step(id, modId, role, harness, prompt.take(MAX_PROMPT), fromUser, startedAt = clock())).takeLast(MAX_STEPS) }
        id
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
        const val MAX_PROMPT = 2_000
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
