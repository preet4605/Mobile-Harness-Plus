package com.jarves.mh.runtime.task

/**
 * Explicit state machine for durable background tasks in Mobile Harness+.
 */
enum class TaskExecutionStatus {
    CREATED,
    STARTING,
    RUNNING,
    WAITING_FOR_INPUT,
    WAITING_FOR_APPROVAL,
    RECOVERING,
    COMPLETING,
    COMPLETED,
    FAILED,
    CANCELLED,
    ABANDONED;

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, FAILED, CANCELLED, ABANDONED)

    val isActive: Boolean
        get() = this in setOf(STARTING, RUNNING, WAITING_FOR_INPUT, WAITING_FOR_APPROVAL, RECOVERING, COMPLETING)
}

/**
 * State transition rules enforcing valid lifecycle transitions.
 */
object TaskStateMachine {
    fun canTransition(from: TaskExecutionStatus, to: TaskExecutionStatus): Boolean {
        if (from == to) return true
        if (from.isTerminal) return false

        return when (from) {
            TaskExecutionStatus.CREATED -> to in setOf(
                TaskExecutionStatus.STARTING,
                TaskExecutionStatus.CANCELLED,
                TaskExecutionStatus.FAILED
            )
            TaskExecutionStatus.STARTING -> to in setOf(
                TaskExecutionStatus.RUNNING,
                TaskExecutionStatus.RECOVERING,
                TaskExecutionStatus.FAILED,
                TaskExecutionStatus.CANCELLED,
                TaskExecutionStatus.ABANDONED
            )
            TaskExecutionStatus.RUNNING -> to in setOf(
                TaskExecutionStatus.WAITING_FOR_INPUT,
                TaskExecutionStatus.WAITING_FOR_APPROVAL,
                TaskExecutionStatus.RECOVERING,
                TaskExecutionStatus.COMPLETING,
                TaskExecutionStatus.COMPLETED,
                TaskExecutionStatus.FAILED,
                TaskExecutionStatus.CANCELLED,
                TaskExecutionStatus.ABANDONED
            )
            TaskExecutionStatus.WAITING_FOR_INPUT -> to in setOf(
                TaskExecutionStatus.RUNNING,
                TaskExecutionStatus.CANCELLED,
                TaskExecutionStatus.FAILED,
                TaskExecutionStatus.ABANDONED
            )
            TaskExecutionStatus.WAITING_FOR_APPROVAL -> to in setOf(
                TaskExecutionStatus.RUNNING,
                TaskExecutionStatus.CANCELLED,
                TaskExecutionStatus.FAILED,
                TaskExecutionStatus.ABANDONED
            )
            TaskExecutionStatus.RECOVERING -> to in setOf(
                TaskExecutionStatus.STARTING,
                TaskExecutionStatus.RUNNING,
                TaskExecutionStatus.FAILED,
                TaskExecutionStatus.CANCELLED,
                TaskExecutionStatus.ABANDONED
            )
            TaskExecutionStatus.COMPLETING -> to in setOf(
                TaskExecutionStatus.COMPLETED,
                TaskExecutionStatus.FAILED,
                TaskExecutionStatus.CANCELLED
            )
            TaskExecutionStatus.COMPLETED,
            TaskExecutionStatus.FAILED,
            TaskExecutionStatus.CANCELLED,
            TaskExecutionStatus.ABANDONED -> false
        }
    }
}

/**
 * Persistent task execution state model. Survives ViewModel/Activity destruction
 * and Android OS process death.
 */
data class DurableTaskRecord(
    val taskId: String,
    val projectId: String,
    val projectSlug: String,
    val chatId: String,
    val agentKind: String,
    val providerJson: String,
    val prompt: String,
    val status: TaskExecutionStatus,
    val createdAt: Long = System.currentTimeMillis(),
    val startedAt: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val sessionId: String? = null,
    val pid: Int? = null,
    val retryCount: Int = 0,
    val maxRetries: Int = 2,
    val lastKnownStep: String? = null,
    val lastError: String? = null,
    val cancellationRequested: Boolean = false,
    val recoveryRequired: Boolean = false,
    val checkpointId: String? = null,
)
