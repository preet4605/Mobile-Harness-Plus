package com.jarves.mh.ui

import com.jarves.mh.model.RuntimeEvent

/**
 * Decides which runtime session the chat follows. Every attempt of a task (first run,
 * supervisor step retry, key/provider fallback) starts a new bridge session with a new
 * sessionId, so the chat must rebind to the new attempt instead of filtering on the
 * failed one. Kept free of Android types so the routing contract is unit-testable.
 *
 * States, per task:
 * - awaiting first session: activeSessionId == null, nothing retired. The first SessionStarted binds.
 * - following a session: only events of activeSessionId are accepted.
 * - awaiting a retry session: [prepareForAttempt] retired every earlier session and cleared
 *   activeSessionId. Only a SessionStarted from a session that is not retired binds, so a late
 *   SessionStarted (or any other event) from a failed attempt can never take over the retry.
 */
internal object RuntimeSessionRouting {

    /** True when [event] belongs to the session the chat is currently showing. */
    fun accepts(state: AppUiState, event: RuntimeEvent): Boolean {
        if (!state.isRunning) return false
        if (event.sessionId in state.retiredSessionIds) return false
        if (event is RuntimeEvent.SessionStarted && state.activeSessionId == null) return true
        return state.activeSessionId == event.sessionId
    }

    /**
     * Called before each attempt of [attemptTaskId] starts its bridge session. When the chat
     * is tracking that task, retires the session the chat followed and [previousSessionId]
     * (the session the task was last bound to), then awaits the attempt's new session so its
     * SessionStarted, events, completion and answer reach the chat.
     */
    fun prepareForAttempt(
        state: AppUiState,
        trackedTaskId: String?,
        attemptTaskId: String,
        attemptProjectId: String,
        previousSessionId: String?,
        requestText: String?,
    ): AppUiState {
        if (trackedTaskId != attemptTaskId || state.isStopping) return state
        if (state.activeProject?.id != attemptProjectId) return state
        return state.copy(
            isRunning = true,
            activeSessionId = null,
            retiredSessionIds = state.retiredSessionIds + listOfNotNull(state.activeSessionId, previousSessionId),
            taskFinishedAtMillis = null,
            currentTaskRequest = state.currentTaskRequest ?: requestText,
        )
    }

    /**
     * The request an attempt must run. The live request (which key/provider fallback may have
     * replaced) wins while it belongs to this task; otherwise the last request this task used.
     * The live request is cleared when a session fails, so a retry must not depend on it.
     */
    fun <T : Any> requestForAttempt(live: T?, liveTaskId: String?, lastUsed: T, attemptTaskId: String): T =
        if (live != null && liveTaskId == attemptTaskId) live else lastUsed
}
