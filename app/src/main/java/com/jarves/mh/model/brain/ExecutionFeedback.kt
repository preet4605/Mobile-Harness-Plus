package com.jarves.mh.model.brain

import com.jarves.mh.data.MemorySource
import java.time.Instant
import java.util.UUID

/**
 * Execution outcome classification for a task attempt.
 */
enum class ExecutionOutcome {
    SUCCESS,
    FAILED,
    CANCELLED,
    PARTIAL,
    BLOCKED;

    val isSuccessful: Boolean get() = this == SUCCESS
    val isCancelled: Boolean get() = this == CANCELLED
    val isFailed: Boolean get() = this == FAILED
    val isPartial: Boolean get() = this == PARTIAL
    val isBlocked: Boolean get() = this == BLOCKED
}

/**
 * A verified or candidate solution discovered or executed during task attempts.
 */
data class LearnedSolution(
    val solutionId: String = UUID.randomUUID().toString(),
    val key: String,
    val summary: String,
    val procedure: String = summary,
    val failureClassification: String? = null,
    val stepId: String? = null,
    val isVerified: Boolean = false,
    val source: MemorySource = if (isVerified) MemorySource.TOOL_VERIFIED else MemorySource.AGENT_INFERRED,
    val confidence: Float = if (isVerified) 0.95f else 0.40f,
    val createdAt: Instant = Instant.now()
)

/**
 * Objectively observed discovery or finding in project context.
 */
data class LearnedDiscovery(
    val discoveryId: String = UUID.randomUUID().toString(),
    val key: String,
    val content: String,
    val summary: String? = null,
    val isObjectivelyObserved: Boolean = true,
    val source: MemorySource = if (isObjectivelyObserved) MemorySource.PROJECT_OBSERVED else MemorySource.AGENT_INFERRED,
    val confidence: Float = if (isObjectivelyObserved) 0.90f else 0.40f,
    val createdAt: Instant = Instant.now()
)

/**
 * Safe, bounded workspace state snapshot based on existing checkpoint observations.
 */
data class ExecutionWorkspaceState(
    val headCommitSha: String? = null,
    val modifiedFiles: List<String> = emptyList(),
    val branch: String? = null,
    val summary: String? = null,
    val isObjectivelyObserved: Boolean = true,
    val createdAt: Instant = Instant.now()
)

/**
 * Immutable model representing the execution results, outcomes, and observations
 * of a single task execution attempt.
 */
data class ExecutionFeedback(
    val taskId: String,
    val projectId: String,
    val attemptId: String,
    val outcome: ExecutionOutcome,
    val summary: String,
    val completedStepIds: List<String> = emptyList(),
    val failedStepIds: List<String> = emptyList(),
    val verifiedCriteria: List<String> = emptyList(),
    val failures: List<TaskFailureRecord> = emptyList(),
    val solutions: List<LearnedSolution> = emptyList(),
    val discoveries: List<LearnedDiscovery> = emptyList(),
    val workspaceState: ExecutionWorkspaceState? = null,
    val source: MemorySource = MemorySource.AUTO,
    val contextFingerprint: String? = null,
    val createdAt: Instant = Instant.now()
)

/**
 * Aggregate summary report returned by BrainLearningService.
 */
data class LearningResult(
    val feedbackId: String,
    val entries: List<BrainKnowledgeEntry> = emptyList(),
    val insertedCount: Int = 0,
    val deduplicatedCount: Int = 0,
    val supersededCount: Int = 0,
    val rejectedCount: Int = 0,
    val ignoredCount: Int = 0,
    val errors: List<String> = emptyList()
) {
    val totalProcessed: Int get() = insertedCount + deduplicatedCount + supersededCount + rejectedCount + ignoredCount
    val isSuccessful: Boolean get() = errors.isEmpty()
}
