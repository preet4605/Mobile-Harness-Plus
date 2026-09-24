package com.jarves.mh.model

/**
 * Registry and state machine invariants for Subagents and Background Tasks (PRD §3.4).
 * Ensures deterministic state transitions, fuzzy reconciliation of UUID vs Role,
 * and strict termination guarantees (no zombie subagents).
 */
object SubagentRegistry {

    /**
     * Reconciles an incoming subagent update event against the active list.
     * Implements fuzzy-matching by conversation ID or role, and enforces terminal invariants:
     * once a subagent is TERMINATED or the session is stopping, it cannot be revived into a running state.
     */
    fun update(
        currentList: List<SubagentInfo>,
        incoming: SubagentInfo,
        isStopping: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): List<SubagentInfo> {
        if (incoming.conversationId == "*") {
            return currentList.map { it.copy(state = incoming.state, finishedAtMillis = now) }
        }

        val existingIndex = currentList.indexOfFirst {
            it.conversationId == incoming.conversationId ||
                (it.role.equals(incoming.role, ignoreCase = true) && !it.role.equals("Subagent", ignoreCase = true))
        }

        return if (existingIndex >= 0) {
            val old = currentList[existingIndex]
            val nextState = if ((old.state == SubagentState.TERMINATED || isStopping) && !incoming.state.isTerminal) {
                SubagentState.TERMINATED
            } else {
                incoming.state
            }
            currentList.toMutableList().also {
                it[existingIndex] = incoming.copy(
                    state = nextState,
                    role = if (incoming.role == "Subagent") old.role else incoming.role,
                    typeName = if (incoming.typeName == "subagent") old.typeName else incoming.typeName,
                    currentActivity = incoming.currentActivity.ifBlank { old.currentActivity },
                    finishedAtMillis = if (nextState.isTerminal) old.finishedAtMillis ?: now else null,
                )
            }
        } else {
            if (isStopping) {
                currentList + incoming.copy(state = SubagentState.TERMINATED, finishedAtMillis = now)
            } else {
                currentList + incoming
            }
        }
    }

    /**
     * Transitions all non-terminal subagents to DONE upon session completion.
     */
    fun completeAll(
        currentList: List<SubagentInfo>,
        finishedAt: Long = System.currentTimeMillis(),
    ): List<SubagentInfo> {
        return currentList.map { subagent ->
            if (!subagent.state.isTerminal) {
                subagent.copy(state = SubagentState.DONE, finishedAtMillis = finishedAt)
            } else subagent
        }
    }

    /**
     * Transitions all non-terminal subagents to ERRORED upon session failure.
     */
    fun failAll(
        currentList: List<SubagentInfo>,
        error: String?,
        finishedAt: Long = System.currentTimeMillis(),
    ): List<SubagentInfo> {
        return currentList.map { subagent ->
            if (!subagent.state.isTerminal) {
                subagent.copy(state = SubagentState.ERRORED, error = error, finishedAtMillis = finishedAt)
            } else subagent
        }
    }

    /**
     * Terminates a single subagent by conversationId (or all if wildcard "*").
     */
    fun terminate(
        currentList: List<SubagentInfo>,
        conversationId: String,
        finishedAt: Long = System.currentTimeMillis(),
    ): List<SubagentInfo> {
        return currentList.map { subagent ->
            if ((conversationId == "*" || subagent.conversationId == conversationId) && !subagent.state.isTerminal) {
                subagent.copy(state = SubagentState.TERMINATED, finishedAtMillis = finishedAt)
            } else subagent
        }
    }

    /**
     * Removes all terminal (DONE, ERRORED, TERMINATED) subagents, keeping active ones.
     */
    fun clearCompleted(currentList: List<SubagentInfo>): List<SubagentInfo> {
        return currentList.filter { !it.state.isTerminal }
    }
}

/**
 * Task state machine invariants for background execution.
 */
object TaskRegistry {

    fun completeRunning(currentList: List<BackgroundTaskInfo>): List<BackgroundTaskInfo> {
        return currentList.map { task ->
            if (task.status == BackgroundTaskStatus.RUNNING) {
                task.copy(status = BackgroundTaskStatus.COMPLETED)
            } else task
        }
    }

    fun failRunning(currentList: List<BackgroundTaskInfo>): List<BackgroundTaskInfo> {
        return currentList.map { task ->
            if (task.status == BackgroundTaskStatus.RUNNING) {
                task.copy(status = BackgroundTaskStatus.FAILED)
            } else task
        }
    }

    fun terminate(currentList: List<BackgroundTaskInfo>, taskId: String): List<BackgroundTaskInfo> {
        return currentList.map { task ->
            if ((taskId == "*" || task.taskId == taskId) && task.status == BackgroundTaskStatus.RUNNING) {
                task.copy(status = BackgroundTaskStatus.TERMINATED)
            } else task
        }
    }

    fun clearCompleted(currentList: List<BackgroundTaskInfo>): List<BackgroundTaskInfo> {
        return currentList.filter { it.status == BackgroundTaskStatus.RUNNING }
    }
}

/**
 * Trigger parser for slash commands and file mentions (PRD §3.3).
 */
object TriggerParser {
    val slashTriggerRegex = Regex("""(?:^|\s)/([a-zA-Z0-9_-]*)$""")
    val mentionTriggerRegex = Regex("""(?:^|\s)@([a-zA-Z0-9_./-]*)$""")

    fun parseTrigger(text: String): InputTriggerState {
        val slashMatch = slashTriggerRegex.find(text)
        if (slashMatch != null) {
            val query = slashMatch.groupValues[1]
            val startIndex = slashMatch.range.first + (slashMatch.value.length - query.length - 1)
            return InputTriggerState(
                type = TriggerType.SLASH_COMMAND,
                query = query,
                triggerStartIndex = startIndex,
            )
        }
        val mentionMatch = mentionTriggerRegex.find(text)
        if (mentionMatch != null) {
            val query = mentionMatch.groupValues[1]
            val startIndex = mentionMatch.range.first + (mentionMatch.value.length - query.length - 1)
            return InputTriggerState(
                type = TriggerType.FILE_MENTION,
                query = query,
                triggerStartIndex = startIndex,
            )
        }
        return InputTriggerState(TriggerType.NONE)
    }
}
