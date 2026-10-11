package com.jarves.mh.ui

import com.jarves.mh.model.ChatAttachment
import java.util.UUID

/**
 * A message sent while a task was running. It keeps the raw composer text: slash commands and
 * skills are parsed when it is sent, exactly as if it had been typed then.
 *
 * Queued follow-ups live only in memory ([AppUiState.queuedFollowUps]). If Android kills the
 * process they are lost, like any unsent composer text; the running task itself is recovered by
 * the task supervisor, but nothing is resent automatically.
 */
data class QueuedFollowUp(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val chatId: String,
    val prompt: String,
    val attachments: List<ChatAttachment> = emptyList(),
)

/** Pure queue operations for follow-ups; the view model owns when they run. */
object FollowUpQueue {

    fun enqueue(queue: List<QueuedFollowUp>, item: QueuedFollowUp): List<QueuedFollowUp> = queue + item

    /** Moves [id] to the front so it runs next. Unknown ids leave the queue unchanged. */
    fun steerToFront(queue: List<QueuedFollowUp>, id: String): List<QueuedFollowUp> {
        val selected = queue.firstOrNull { it.id == id } ?: return queue
        return listOf(selected) + queue.filterNot { it.id == id }
    }

    /** The chat key a follow-up belongs to; matches the task supervisor's chat id. */
    fun chatKey(activeChatId: String?): String = activeChatId ?: "default"

    fun remove(queue: List<QueuedFollowUp>, id: String): List<QueuedFollowUp> = queue.filterNot { it.id == id }

    /**
     * Takes the first follow-up for [projectId] and [chatId]. Follow-ups queued for another
     * project or chat are stale (they must never be sent somewhere else) and are dropped.
     * Returns the follow-up to send, or null, and the queue that remains.
     */
    fun popFor(queue: List<QueuedFollowUp>, projectId: String, chatId: String): Pair<QueuedFollowUp?, List<QueuedFollowUp>> {
        val matching = queue.filter { it.projectId == projectId && it.chatId == chatId }
        return matching.firstOrNull() to matching.drop(1)
    }

    /**
     * Whether the next follow-up may start now: no task is running or stopping, the chat has
     * loaded, and no project terminal command is running.
     */
    fun canStartNext(state: AppUiState): Boolean =
        state.queuedFollowUps.isNotEmpty() && state.activeProject != null &&
            !state.isRunning && !state.isStopping && !state.chatLoading && !state.projectTerminalRunning &&
            !state.androidBuildRunning
}
