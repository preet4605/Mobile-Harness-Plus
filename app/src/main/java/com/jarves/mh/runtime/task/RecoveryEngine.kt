package com.jarves.mh.runtime.task

import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of deterministic recovery action execution.
 */
data class RecoveryExecutionResult(
    val success: Boolean,
    val strategy: RecoveryStrategy,
    val checkpointRestored: Boolean = false,
    val shouldRetryStep: Boolean = false,
    val message: String
)

/**
 * Result of checkpoint validation ensuring strict ownership and boundary safety.
 */
sealed class CheckpointValidationResult {
    object Valid : CheckpointValidationResult()
    data class Invalid(val reason: String) : CheckpointValidationResult()
}

/**
 * Validates that a checkpoint belongs to the failed step, matching project, task, and attempt.
 * Enforces Precondition 2 and Precondition 6 step checkpoint invariants and prevents cross-task,
 * cross-project, or cross-step restores.
 */
fun validateCheckpointForStep(
    task: CanonicalTask,
    step: ExecutionStep,
    checkpointTag: String,
    checkpoints: WorkspaceCheckpoints,
    expectedAttempt: Int? = null
): CheckpointValidationResult {
    if (!checkpoints.isValidCheckpointTag(checkpointTag)) {
        return CheckpointValidationResult.Invalid("Checkpoint tag '$checkpointTag' is invalid or unsafe")
    }

    val expectedStepTag1 = step.checkpointTag ?: "step-${step.stepOrder + 1}"
    val expectedStepTag2 = "step-${step.stepOrder}"
    val validTags = setOfNotNull(expectedStepTag1, expectedStepTag2, step.checkpointTag)

    if (checkpointTag !in validTags && !validTags.any { checkpointTag.startsWith(it) }) {
        return CheckpointValidationResult.Invalid(
            "Checkpoint tag '$checkpointTag' does not belong to step '${step.stepId}' (order ${step.stepOrder})"
        )
    }

    if (!checkpoints.checkpointExists(task.projectId, checkpointTag)) {
        return CheckpointValidationResult.Invalid(
            "Checkpoint '$checkpointTag' does not exist for project '${task.projectId}'"
        )
    }

    val meta = checkpoints.readMetadata(task.projectId, checkpointTag)
        ?: return CheckpointValidationResult.Invalid(
            "Metadata missing for checkpoint '$checkpointTag' in project '${task.projectId}'"
        )

    if (meta.projectId != task.projectId) {
        return CheckpointValidationResult.Invalid(
            "Checkpoint project mismatch: owned by '${meta.projectId}', task is '${task.projectId}'"
        )
    }

    if (meta.taskId != null && meta.taskId != task.taskId) {
        return CheckpointValidationResult.Invalid(
            "Checkpoint task mismatch: owned by '${meta.taskId}', task is '${task.taskId}'"
        )
    }

    if (meta.stepId != null && meta.stepId != step.stepId) {
        return CheckpointValidationResult.Invalid(
            "Checkpoint step mismatch: owned by step '${meta.stepId}', step is '${step.stepId}'"
        )
    }

    if (expectedAttempt != null && meta.attempt != null && meta.attempt != expectedAttempt) {
        return CheckpointValidationResult.Invalid(
            "Checkpoint attempt mismatch: owned by attempt '${meta.attempt}', expected '$expectedAttempt'"
        )
    }

    return CheckpointValidationResult.Valid
}

/**
 * Authoritative interface for the deterministic self-recovery engine.
 * Determines bounded, deterministic recovery plans from trusted failure classifications
 * without arbitrary LLM decisions or unrestricted shell execution.
 */
interface RecoveryEngine {

    val allowedStrategies: Set<RecoveryStrategy>
        get() = ALLOWED_RECOVERY_STRATEGIES

    fun planRecovery(
        task: CanonicalTask,
        step: ExecutionStep,
        classification: TaskSupervisor.TaskErrorClassification,
        errorMessage: String,
        mutatedFiles: List<String> = emptyList(),
        attemptCount: Int = step.attempts
    ): RecoveryPlan?

    suspend fun executeRecovery(
        task: CanonicalTask,
        step: ExecutionStep,
        plan: RecoveryPlan,
        workspaceDir: File?,
        checkpoints: WorkspaceCheckpoints?
    ): RecoveryExecutionResult

    fun calculateBackoffMillis(attempt: Int): Long

    companion object {
        val ALLOWED_RECOVERY_STRATEGIES = setOf(
            RecoveryStrategy.RESTORE_CHECKPOINT,
            RecoveryStrategy.RETRY_STEP,
            RecoveryStrategy.RETRY_STEP_DIRECT,
            RecoveryStrategy.REVERT_AND_RETRY_STEP,
            RecoveryStrategy.RECREATE_WORKSPACE_STATE,
            RecoveryStrategy.REBUILD_AND_RETEST,
            RecoveryStrategy.SAFE_ABORT_AND_CLEANUP
        )
    }
}

/**
 * Default implementation of deterministic self-recovery engine enforcing:
 * 1. Fixed strategy allowlist
 * 2. Trusted runtime failure classification mapping
 * 3. Strict checkpoint validation and ownership
 * 4. Bounded retries and deterministic backoff
 * 5. Idempotent recovery execution
 */
class DefaultRecoveryEngine(
    val maxStepRetries: Int = 2,
    val baseBackoffMillis: Long = 50L,
    val maxBackoffMillis: Long = 1000L
) : RecoveryEngine {

    private val executedRecoveries = ConcurrentHashMap<String, RecoveryExecutionResult>()

    override fun calculateBackoffMillis(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 10)
        val multiplier = 1L shl shift
        return (baseBackoffMillis * multiplier).coerceAtMost(maxBackoffMillis)
    }

    override fun planRecovery(
        task: CanonicalTask,
        step: ExecutionStep,
        classification: TaskSupervisor.TaskErrorClassification,
        errorMessage: String,
        mutatedFiles: List<String>,
        attemptCount: Int
    ): RecoveryPlan? {
        // 1. Cancellation check: Never recover USER_CANCELLED or ABANDONED tasks
        if (classification == TaskSupervisor.TaskErrorClassification.USER_CANCELLED) {
            return null
        }

        // 2. Permanent failure check: Permanent auth/config errors must not retry
        if (classification == TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG) {
            return null
        }

        // 3. Retry bounds check: ensure attempts do not exceed step.maxAttempts or bounds
        val effectiveMaxAttempts = step.maxAttempts.coerceAtLeast(1)
        if (attemptCount >= effectiveMaxAttempts) {
            return null
        }

        val nextAttempt = attemptCount + 1
        val failureRecordId = "fail-${task.taskId}-${step.stepId}-$nextAttempt"
        val recoveryPlanId = "rec-${task.taskId}-${step.stepId}-$nextAttempt"
        val stepTag = step.checkpointTag ?: "step-${step.stepOrder + 1}"

        // 4. Deterministic strategy selection based on trusted runtime failure classification
        return when (classification) {
            TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR -> {
                if (mutatedFiles.isNotEmpty()) {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
                        rationale = "Transient API failure after workspace mutation. Restore checkpoint '$stepTag' and retry step.",
                        filesToRollback = mutatedFiles,
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        checkpointTag = stepTag,
                        attemptNumber = nextAttempt
                    )
                } else {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RETRY_STEP,
                        rationale = "Transient API failure with clean workspace. Retry step with deterministic backoff.",
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        attemptNumber = nextAttempt
                    )
                }
            }
            TaskSupervisor.TaskErrorClassification.WORKSPACE_MUTATED_FAILURE -> {
                RecoveryPlan(
                    recoveryId = recoveryPlanId,
                    taskId = task.taskId,
                    failureRecordId = failureRecordId,
                    strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
                    rationale = "Execution failed after modifying files (${mutatedFiles.size} mutated). Restore step checkpoint '$stepTag' to revert dirty state.",
                    filesToRollback = mutatedFiles,
                    targetStepIndex = step.stepOrder,
                    stepId = step.stepId,
                    checkpointTag = stepTag,
                    attemptNumber = nextAttempt
                )
            }
            TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE -> {
                if (mutatedFiles.isNotEmpty()) {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
                        rationale = "Process failure after workspace mutation. Restore checkpoint '$stepTag' and retry step.",
                        filesToRollback = mutatedFiles,
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        checkpointTag = stepTag,
                        attemptNumber = nextAttempt
                    )
                } else {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RETRY_STEP,
                        rationale = "Process execution failure. Retry step with deterministic backoff.",
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        attemptNumber = nextAttempt
                    )
                }
            }
            TaskSupervisor.TaskErrorClassification.STEP_VERIFICATION_FAILURE -> {
                if (mutatedFiles.isNotEmpty()) {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
                        rationale = "Step verification failed with mutated files. Restore checkpoint '$stepTag' to clean workspace and retry step.",
                        filesToRollback = mutatedFiles,
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        checkpointTag = stepTag,
                        attemptNumber = nextAttempt
                    )
                } else {
                    // Deterministic verification failure with clean workspace and no recovery evidence:
                    // Repeating the identical empty execution is futile; do not auto-retry.
                    null
                }
            }
            TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT -> {
                if (mutatedFiles.isNotEmpty()) {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
                        rationale = "Transient system fault after workspace mutation. Restore checkpoint '$stepTag' and retry step.",
                        filesToRollback = mutatedFiles,
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        checkpointTag = stepTag,
                        attemptNumber = nextAttempt
                    )
                } else {
                    RecoveryPlan(
                        recoveryId = recoveryPlanId,
                        taskId = task.taskId,
                        failureRecordId = failureRecordId,
                        strategy = RecoveryStrategy.RETRY_STEP_DIRECT,
                        rationale = "Transient system fault with clean workspace. Retry step directly without checkpoint restoration.",
                        filesToRollback = emptyList(),
                        targetStepIndex = step.stepOrder,
                        stepId = step.stepId,
                        checkpointTag = null,
                        attemptNumber = nextAttempt
                    )
                }
            }
            else -> null
        }
    }

    override suspend fun executeRecovery(
        task: CanonicalTask,
        step: ExecutionStep,
        plan: RecoveryPlan,
        workspaceDir: File?,
        checkpoints: WorkspaceCheckpoints?
    ): RecoveryExecutionResult {
        // 1. Idempotency: return cached execution result for duplicate recovery plan invocations
        executedRecoveries[plan.recoveryId]?.let { return it }

        // 2. Trust boundary & Allowlist validation
        if (plan.strategy !in RecoveryEngine.ALLOWED_RECOVERY_STRATEGIES) {
            val result = RecoveryExecutionResult(
                success = false,
                strategy = plan.strategy,
                shouldRetryStep = false,
                message = "Strategy '${plan.strategy}' rejected: not in trusted allowlist"
            )
            executedRecoveries[plan.recoveryId] = result
            return result
        }

        // 3. Step target validation
        if (plan.targetStepIndex != step.stepOrder) {
            val result = RecoveryExecutionResult(
                success = false,
                strategy = plan.strategy,
                shouldRetryStep = false,
                message = "Plan targetStepIndex (${plan.targetStepIndex}) does not match step.stepOrder (${step.stepOrder})"
            )
            executedRecoveries[plan.recoveryId] = result
            return result
        }

        // 4. Execute deterministic recovery action
        val executionResult = when (plan.strategy) {
            RecoveryStrategy.RESTORE_CHECKPOINT,
            RecoveryStrategy.REVERT_AND_RETRY_STEP -> {
                val tag = plan.checkpointTag ?: step.checkpointTag ?: "step-${step.stepOrder + 1}"
                if (checkpoints == null || workspaceDir == null) {
                    RecoveryExecutionResult(
                        success = false,
                        strategy = plan.strategy,
                        shouldRetryStep = false,
                        message = "Cannot restore checkpoint: checkpoints or workspace directory is null"
                    )
                } else {
                    val expectedAttempt = if (plan.attemptNumber > 1) plan.attemptNumber - 1 else step.attempts.takeIf { it > 0 }
                    val validation = validateCheckpointForStep(task, step, tag, checkpoints, expectedAttempt)
                    if (validation !is CheckpointValidationResult.Valid) {
                        RecoveryExecutionResult(
                            success = false,
                            strategy = plan.strategy,
                            shouldRetryStep = false,
                            message = "Checkpoint validation failed: ${(validation as CheckpointValidationResult.Invalid).reason}"
                        )
                    } else {
                        if (plan.filesToRollback.isNotEmpty()) {
                            checkpoints.saveChangedPaths(task.projectId, plan.filesToRollback, tag)
                        }
                        val restored = checkpoints.restoreCheckpoint(task.projectId, workspaceDir, tag)
                        if (restored) {
                            RecoveryExecutionResult(
                                success = true,
                                strategy = plan.strategy,
                                checkpointRestored = true,
                                shouldRetryStep = true,
                                message = "Successfully restored checkpoint '$tag' for step '${step.title}'"
                            )
                        } else {
                            RecoveryExecutionResult(
                                success = false,
                                strategy = plan.strategy,
                                shouldRetryStep = false,
                                message = "Failed to restore checkpoint '$tag'"
                            )
                        }
                    }
                }
            }
            RecoveryStrategy.RETRY_STEP,
            RecoveryStrategy.RETRY_STEP_DIRECT -> {
                RecoveryExecutionResult(
                    success = true,
                    strategy = plan.strategy,
                    shouldRetryStep = true,
                    message = "Step retry approved for step '${step.title}' (attempt ${plan.attemptNumber})"
                )
            }
            RecoveryStrategy.RECREATE_WORKSPACE_STATE -> {
                if (workspaceDir != null && checkpoints != null) {
                    val baselineTag = "baseline"
                    if (checkpoints.checkpointExists(task.projectId, baselineTag)) {
                        val restored = checkpoints.restoreCheckpoint(task.projectId, workspaceDir, baselineTag)
                        RecoveryExecutionResult(
                            success = restored,
                            strategy = plan.strategy,
                            checkpointRestored = restored,
                            shouldRetryStep = restored,
                            message = if (restored) "Recreated workspace state from baseline" else "Failed to restore baseline checkpoint"
                        )
                    } else {
                        RecoveryExecutionResult(
                            success = false,
                            strategy = plan.strategy,
                            shouldRetryStep = false,
                            message = "Baseline checkpoint not found"
                        )
                    }
                } else {
                    RecoveryExecutionResult(
                        success = false,
                        strategy = plan.strategy,
                        shouldRetryStep = false,
                        message = "Workspace directory or checkpoints null"
                    )
                }
            }
            RecoveryStrategy.REBUILD_AND_RETEST -> {
                RecoveryExecutionResult(
                    success = true,
                    strategy = plan.strategy,
                    shouldRetryStep = true,
                    message = "Rebuild and retest scheduled"
                )
            }
            RecoveryStrategy.SAFE_ABORT_AND_CLEANUP -> {
                RecoveryExecutionResult(
                    success = true,
                    strategy = plan.strategy,
                    shouldRetryStep = false,
                    message = "Safe abort and cleanup approved"
                )
            }
            else -> {
                RecoveryExecutionResult(
                    success = false,
                    strategy = plan.strategy,
                    shouldRetryStep = false,
                    message = "Strategy '${plan.strategy}' execution not supported"
                )
            }
        }

        executedRecoveries[plan.recoveryId] = executionResult
        return executionResult
    }
}
