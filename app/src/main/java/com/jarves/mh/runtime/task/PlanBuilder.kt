package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.PlanStatus
import com.jarves.mh.model.brain.StepStatus
import java.time.Instant

/**
 * Exception thrown when task decomposition or plan construction violates
 * determinism, bounds, acceptance criteria, or ordering rules.
 */
class InvalidDecompositionException(message: String) : IllegalArgumentException(message)

/**
 * Fluent, strictly validated builder for deterministic ExecutionPlans.
 * Enforces sequential step order, unique IDs, explicit acceptance criteria,
 * non-ambiguous objectives, and maximum step bounds.
 */
class PlanBuilder(
    val planId: String,
    val taskId: String? = null,
    var title: String = "Execution Plan",
    var maxSteps: Int = TaskDecomposer.MAX_STEPS,
    var baseTimestamp: Instant = Instant.EPOCH
) {
    private val steps = mutableListOf<ExecutionStep>()

    fun title(title: String): PlanBuilder = apply {
        this.title = title
    }

    fun maxSteps(max: Int): PlanBuilder = apply {
        this.maxSteps = max
    }

    fun timestamp(instant: Instant): PlanBuilder = apply {
        this.baseTimestamp = instant
    }

    fun addStep(
        title: String,
        objective: String,
        acceptanceCriteria: List<String>,
        verificationCommand: String? = null,
        expectedFiles: List<String> = emptyList(),
        forbiddenFiles: List<String> = emptyList(),
        expectedContent: Map<String, String> = emptyMap(),
        stepId: String? = null,
        stepOrder: Int? = null
    ): PlanBuilder = apply {
        val nextIndex = steps.size
        val effectiveOrder = stepOrder ?: nextIndex
        val effectiveStepId = stepId?.takeIf { it.isNotBlank() }
            ?: (taskId?.let { "$it-step-$effectiveOrder" } ?: "$planId-step-$effectiveOrder")
        steps.add(
            ExecutionStep(
                stepId = effectiveStepId,
                stepOrder = effectiveOrder,
                title = title,
                description = objective,
                objective = objective,
                acceptanceCriteria = acceptanceCriteria,
                verificationCommand = verificationCommand,
                expectedFiles = expectedFiles,
                forbiddenFiles = forbiddenFiles,
                expectedContent = expectedContent,
                status = StepStatus.PENDING,
                checkpointTag = "step-${effectiveOrder + 1}",
                planId = planId
            )
        )
    }

    fun addStep(step: ExecutionStep): PlanBuilder = apply {
        steps.add(step)
    }

    fun addSteps(stepsToAdd: List<ExecutionStep>): PlanBuilder = apply {
        steps.addAll(stepsToAdd)
    }

    fun build(): ExecutionPlan {
        if (steps.isEmpty()) {
            throw InvalidDecompositionException("Execution plan contains no steps: empty decomposition rejected")
        }
        if (steps.size > maxSteps) {
            throw InvalidDecompositionException(
                "Execution plan exceeds maximum step bound of $maxSteps (found ${steps.size} steps)"
            )
        }

        val seenStepIds = mutableSetOf<String>()
        val seenObjectives = mutableSetOf<String>()
        val seenOrders = mutableSetOf<Int>()

        val validatedSteps = steps.mapIndexed { index, step ->
            if (step.stepId.isBlank()) {
                throw InvalidDecompositionException("Step at index $index has blank stepId")
            }
            if (!seenStepIds.add(step.stepId)) {
                throw InvalidDecompositionException("Duplicate stepId '${step.stepId}' at index $index")
            }
            if (step.stepOrder != index) {
                throw InvalidDecompositionException(
                    "Invalid ordering: step '${step.stepId}' has stepOrder ${step.stepOrder}, expected sequential index $index"
                )
            }
            if (!seenOrders.add(step.stepOrder)) {
                throw InvalidDecompositionException("Duplicate stepOrder ${step.stepOrder} for step '${step.stepId}'")
            }
            if (step.title.isBlank()) {
                throw InvalidDecompositionException("Step '${step.stepId}' has blank title")
            }
            val effObj = step.objective.ifBlank { step.description }
            if (effObj.isBlank() || isAmbiguousObjective(effObj)) {
                throw InvalidDecompositionException("Step '${step.stepId}' has ambiguous or blank objective: '$effObj'")
            }
            val normObj = effObj.trim().lowercase()
            if (!seenObjectives.add(normObj)) {
                throw InvalidDecompositionException("Duplicate step objective detected: '$effObj'")
            }
            val validCriteria = step.acceptanceCriteria.filter { it.isNotBlank() }
            if (validCriteria.isEmpty()) {
                throw InvalidDecompositionException("Step '${step.stepId}' is missing explicit acceptance criteria")
            }
            if (step.status != StepStatus.PENDING) {
                throw InvalidDecompositionException("New step '${step.stepId}' must have status PENDING (was ${step.status})")
            }

            step.copy(
                stepId = step.stepId,
                stepOrder = index,
                objective = effObj,
                acceptanceCriteria = validCriteria,
                status = StepStatus.PENDING,
                planId = planId,
                checkpointTag = step.checkpointTag ?: "step-${index + 1}"
            )
        }

        return ExecutionPlan(
            planId = planId,
            taskId = taskId,
            title = title,
            steps = validatedSteps,
            currentStepIndex = 0,
            status = PlanStatus.PENDING,
            createdAt = baseTimestamp,
            updatedAt = baseTimestamp
        )
    }

    companion object {
        fun isAmbiguousObjective(obj: String): Boolean {
            val trimmed = obj.trim()
            if (trimmed.length < 3) return true
            val lower = trimmed.lowercase()
            val ambiguousTokens = setOf(
                "todo", "tbd", "fix", "do", "task", "work", "?", "...", "unspecified", "none", "placeholder"
            )
            return lower in ambiguousTokens
        }
    }
}
