package com.jarves.mh.ui

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.WorkStatus
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus

internal fun TaskExecutionStatus.workStatus(): WorkStatus = when (this) {
    TaskExecutionStatus.CREATED, TaskExecutionStatus.STARTING, TaskExecutionStatus.RUNNING -> WorkStatus.WORKING
    TaskExecutionStatus.WAITING_FOR_INPUT -> WorkStatus.WAITING_FOR_INPUT
    TaskExecutionStatus.WAITING_FOR_APPROVAL -> WorkStatus.WAITING_FOR_APPROVAL
    TaskExecutionStatus.RECOVERING -> WorkStatus.RECOVERING
    TaskExecutionStatus.COMPLETING -> WorkStatus.CHECKING
    TaskExecutionStatus.COMPLETED -> WorkStatus.COMPLETED
    TaskExecutionStatus.UNVERIFIED -> WorkStatus.UNVERIFIED
    TaskExecutionStatus.FAILED -> WorkStatus.FAILED
    TaskExecutionStatus.CANCELLED -> WorkStatus.STOPPED
    TaskExecutionStatus.ABANDONED -> WorkStatus.INTERRUPTED
}

/** Presentation follows the existing owner and close gates; it never ends runtime work. */
internal fun supervisorWorkStatus(current: AppUiState, trackedId: String?, task: DurableTaskRecord): WorkStatus {
    if (!RuntimeSessionRouting.followsTask(current, trackedId, task)) return current.liveWorkStatus
    return when {
        current.isStopping -> WorkStatus.STOPPING
        task.status.isTerminal -> WorkStatus.CHECKING // finalization still has to consume session close
        task.status == TaskExecutionStatus.RUNNING && task.sessionId in current.retiredSessionIds -> WorkStatus.CHECKING
        else -> task.status.workStatus()
    }
}

/** Convert transient status wording to a past event when saved as history. */
internal fun historicalWorkItems(items: List<ActivityItem>): List<ActivityItem> = items.map {
    if (it.title == "Checking result") it.copy(title = "Result check", detail = "Supervisor verification was requested.", isComplete = true)
    else it.copy(isComplete = true)
}

internal fun finalizeTaskWork(
    current: AppUiState,
    trackedId: String?,
    task: DurableTaskRecord,
    title: String,
    detail: String,
    finishedAt: Long,
): AppUiState {
    if (!RuntimeSessionRouting.canFinish(current, trackedId, task)) return current
    return finishWorkSegment(
        current, finishedAt,
        outcome = ActivityItem(title, detail), status = task.status.workStatus(),
    )
}

internal fun workBlockHeadline(message: ChatMessage): String = buildString {
    append(message.workStatus?.label ?: "View activity")
    if (message.workedMillis > 0) append(" · ${formatDuration(message.workedMillis / 1000)}")
}

internal fun elapsedWorkSeconds(startedAt: Long, finishedAt: Long?, now: Long): Long =
    (((finishedAt ?: now) - startedAt).coerceAtLeast(0L) / 1000L)

internal fun isNoisyRuntimeItem(item: ActivityItem): Boolean {
    val combined = "${item.title} ${item.detail}"
    return combined.contains("Starting Claude Code", true) ||
        combined.contains("Agent process started", true) ||
        combined.contains("Claude Code connected", true) ||
        combined.contains("Runtime warning", true) ||
        combined.contains("unrecognized_model", true) ||
        combined.contains("Writing response", true) ||
        combined.contains("Claude Code finished", true) ||
        combined.contains("Task completed", true)
}


internal fun finishWorkSegment(
    current: AppUiState,
    finishedAt: Long = System.currentTimeMillis(),
    outcome: ActivityItem? = null,
    status: WorkStatus? = null,
): AppUiState {
    val meaningfulItems = historicalWorkItems(current.liveProcess.filterNot(::isNoisyRuntimeItem))
        .takeLast(32)
    if (!current.liveThinking && meaningfulItems.isEmpty() && outcome == null) {
        return current.copy(liveProcess = emptyList(), workSegmentStartedAtMillis = null)
    }
    val startedAt = if (outcome != null) current.taskStartedAtMillis ?: finishedAt
        else current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: finishedAt
    val block = ChatMessage(
        fromUser = false,
        text = "",
        workItems = meaningfulItems + listOfNotNull(outcome),
        workStatus = status,
        workedMillis = (finishedAt - startedAt).coerceAtLeast(0L),
    )
    return current.copy(
        messages = current.messages + block,
        liveProcess = emptyList(),
        liveThinking = false,
        activeThinkingBlockId = null,
        workSegmentStartedAtMillis = null,
    )
}
