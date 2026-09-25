package com.jarves.mh.model.brain

import com.jarves.mh.data.MemoryEntry
import com.jarves.mh.data.MemoryScope
import com.jarves.mh.data.MemorySource
import com.jarves.mh.data.MemoryStatus
import com.jarves.mh.data.MemoryType
import java.time.Instant
import java.util.UUID

/**
 * Status lifecycle of an individual execution step within an agent plan.
 */
enum class StepStatus {
    PENDING,
    RUNNING,
    VERIFYING,
    COMPLETED,
    FAILED,
    SKIPPED
}

/**
 * Single executable milestone within a structured task execution plan.
 */
data class ExecutionStep(
    val stepId: String = UUID.randomUUID().toString(),
    val stepOrder: Int,
    val title: String,
    val description: String,
    val expectedFiles: List<String> = emptyList(),
    val status: StepStatus = StepStatus.PENDING,
    val attempts: Int = 0,
    val maxAttempts: Int = 2,
    val resultSummary: String? = null,
    val checkpointTag: String? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
)

/**
 * Multi-step execution roadmap tracking progress and current active step.
 */
data class ExecutionPlan(
    val planId: String = UUID.randomUUID().toString(),
    val title: String = "Default Execution Plan",
    val steps: List<ExecutionStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    val currentStep: ExecutionStep?
        get() = steps.getOrNull(currentStepIndex)

    val isFinished: Boolean
        get() = steps.isNotEmpty() && steps.all { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED }

    val progressFraction: Float
        get() = if (steps.isEmpty()) 0f else steps.count { it.status == StepStatus.COMPLETED }.toFloat() / steps.size
}

/**
 * Final execution report for a canonical task.
 */
data class TaskOutcome(
    val success: Boolean,
    val summary: String,
    val filesModified: List<String> = emptyList(),
    val testsExecuted: Boolean = false,
    val testsPassed: Boolean = false,
    val durationSeconds: Long = 0L,
    val completedAt: Instant = Instant.now(),
)

/**
 * Rich project knowledge classification taxonomy for persistent memory.
 */
enum class BrainKnowledgeType {
    FACT,             // Verifiable project truths (e.g., "Build tool is Gradle 8.14")
    DECISION,         // Architecture or design choices (e.g., "Use SQLite over Room for zero KSP overhead")
    PREFERENCE,       // Developer preferences (e.g., "Always use GitHub-style file:// markdown links")
    CONSTRAINT,       // Non-negotiable restrictions (e.g., "Never modify code outside /workspace/clever-kalam")
    TASK,             // Canonical task objective and acceptance goals
    PROGRESS,         // Confirmed milestone completed during execution
    DISCOVERY,        // Learnings about project peculiarities (e.g., "AAPT2 maven override required on ARM64")
    FAILURE,          // Root cause of a confirmed bug, error, or build breakage
    SOLUTION,         // Proven fix or workaround associated with a failure pattern
    WORKSPACE_STATE,  // Workspace baseline snapshot (e.g., "Clean working tree at commit 7118c77")
}

/**
 * Strongly typed persistent project brain knowledge item.
 */
data class BrainKnowledgeEntry(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val sessionId: String? = null,
    val taskId: String? = null,
    val scope: MemoryScope = MemoryScope.PROJECT,
    val knowledgeType: BrainKnowledgeType = BrainKnowledgeType.FACT,
    val key: String,
    val content: String,
    val summary: String? = null,
    val importance: Float = 0.5f,
    val confidence: Float = 0.8f,
    val source: MemorySource = MemorySource.AUTO,
    val sourceReference: String? = null,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val version: Int = 1,
    val supersededBy: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val lastAccessedAt: Instant = Instant.now(),
)

/**
 * Self-recovery strategy determining how an execution failure is remedied.
 */
enum class RecoveryStrategy {
    RETRY_STEP_DIRECT,           // Step failed transiently without mutating files: retry with backoff
    REVERT_AND_RETRY_STEP,       // Step failed after partially mutating files: revert checkpoint diff, re-prompt step
    FORWARD_FIX_WITH_CONTEXT,    // Step partially succeeded: retain mutations, prompt agent with error diff to fix forward
    SAFE_ABORT_AND_CLEANUP,      // Non-recoverable error: revert workspace to task start baseline and halt
    MANUAL_USER_INTERVENTION     // Ambiguous state: wait for explicit user choice in UI
}

/**
 * Diagnostic failure record capturing execution failure context.
 */
data class TaskFailureRecord(
    val failureId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val stepId: String? = null,
    val classification: String,
    val errorMessage: String,
    val errorSnippet: String? = null,
    val mutatedFiles: List<String> = emptyList(),
    val matchedSolutionId: String? = null,
    val timestamp: Instant = Instant.now(),
)

/**
 * Prescriptive recovery plan generated to remediate a task step failure.
 */
data class RecoveryPlan(
    val recoveryId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val failureRecordId: String,
    val strategy: RecoveryStrategy,
    val rationale: String,
    val filesToRollback: List<String> = emptyList(),
    val forwardFixInstructions: String? = null,
    val targetStepIndex: Int = 0,
    val approvedByUser: Boolean = false,
    val createdAt: Instant = Instant.now(),
)

/**
 * Canonical top-level task model holding the execution plan, failure history,
 * workspace baseline states, and final outcome.
 */
data class CanonicalTask(
    val taskId: String = UUID.randomUUID().toString(),
    val projectId: String,
    val projectSlug: String,
    val objective: String,
    val constraints: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val plan: ExecutionPlan = ExecutionPlan(title = "Default Execution Plan"),
    val initialWorkspaceSha: String? = null,
    val currentWorkspaceSha: String? = null,
    val failureHistory: List<TaskFailureRecord> = emptyList(),
    val activeRecoveryPlan: RecoveryPlan? = null,
    val outcome: TaskOutcome? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

/**
 * Conversion extensions between BrainKnowledgeEntry and legacy MemoryEntry
 * for backward compatibility across repositories.
 */
fun BrainKnowledgeEntry.toMemoryEntry(): MemoryEntry = MemoryEntry(
    id = id,
    projectId = projectId,
    sessionId = sessionId,
    scope = scope,
    type = when (knowledgeType) {
        BrainKnowledgeType.FACT -> MemoryType.PROJECT
        BrainKnowledgeType.DECISION -> MemoryType.DECISION
        BrainKnowledgeType.PREFERENCE -> MemoryType.PROJECT
        BrainKnowledgeType.CONSTRAINT -> MemoryType.PROJECT
        BrainKnowledgeType.TASK -> MemoryType.TASK
        BrainKnowledgeType.PROGRESS -> MemoryType.EPISODIC
        BrainKnowledgeType.DISCOVERY -> MemoryType.PROJECT
        BrainKnowledgeType.FAILURE -> MemoryType.EPISODIC
        BrainKnowledgeType.SOLUTION -> MemoryType.PROJECT
        BrainKnowledgeType.WORKSPACE_STATE -> MemoryType.PROJECT
    },
    key = key,
    value = content,
    summary = summary,
    importance = importance,
    confidence = confidence,
    source = source,
    sourceReference = sourceReference,
    status = status,
    version = version,
    supersededBy = supersededBy,
    tags = tags,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
    knowledgeType = knowledgeType,
    taskId = taskId,
)

fun MemoryEntry.toBrainKnowledgeEntry(): BrainKnowledgeEntry = BrainKnowledgeEntry(
    id = id,
    projectId = projectId,
    sessionId = sessionId,
    taskId = taskId,
    scope = scope,
    knowledgeType = knowledgeType,
    key = key,
    content = value,
    summary = summary,
    importance = importance,
    confidence = confidence,
    source = source,
    sourceReference = sourceReference,
    status = status,
    version = version,
    supersededBy = supersededBy,
    tags = tags,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastAccessedAt = lastAccessedAt,
)
