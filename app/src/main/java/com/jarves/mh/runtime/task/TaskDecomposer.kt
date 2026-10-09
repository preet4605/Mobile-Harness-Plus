package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContext
import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import java.time.Instant

/**
 * Step specification input for structured task decomposition.
 */
data class StepSpecification(
    val title: String,
    val objective: String = title,
    val acceptanceCriteria: List<String> = emptyList(),
    val verificationCommand: String? = null,
    val expectedFiles: List<String> = emptyList(),
    val forbiddenFiles: List<String> = emptyList(),
    val expectedContent: Map<String, String> = emptyMap(),
    val stepId: String? = null,
    val stepOrder: Int? = null,
)

/**
 * Contextual input for deterministic task decomposition.
 */
data class TaskDecompositionContext(
    val projectId: String = "",
    val projectSlug: String = "",
    val constraints: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val brainContext: BrainContext? = null,
    val plannedSteps: List<StepSpecification> = emptyList(),
    val maxSteps: Int = TaskDecomposer.MAX_STEPS,
    val baseTimestamp: Instant = Instant.EPOCH
)

/**
 * Authoritative interface for deterministic decomposition of a CanonicalTask
 * into an ordered, validated ExecutionPlan.
 */
interface TaskDecomposer {
    /**
     * Decomposes a task objective and context into a validated ExecutionPlan.
     * Deterministic: Identical input + context produces identical output.
     */
    fun decompose(
        taskId: String,
        objective: String,
        context: TaskDecompositionContext = TaskDecompositionContext()
    ): ExecutionPlan

    /**
     * Overload decomposing an existing CanonicalTask.
     */
    fun decompose(
        task: CanonicalTask,
        context: TaskDecompositionContext = TaskDecompositionContext(
            projectId = task.projectId,
            projectSlug = task.projectSlug,
            constraints = task.constraints,
            acceptanceCriteria = task.acceptanceCriteria,
            baseTimestamp = task.createdAt
        )
    ): ExecutionPlan {
        return decompose(task.taskId, task.objective, context)
    }

    companion object {
        const val MAX_STEPS = BrainContextAssembler.MAX_KNOWLEDGE_COUNT // 20
        const val DEFAULT_MAX_STEPS = 20

        fun create(): TaskDecomposer = DefaultTaskDecomposer()
    }
}

/**
 * Default implementation of TaskDecomposer.
 * Deterministically analyzes objectives and task context into ordered, bounded ExecutionSteps.
 * Never relies on timestamps, process IDs, filesystem ordering, or random seeds.
 */
class DefaultTaskDecomposer : TaskDecomposer {

    override fun decompose(
        taskId: String,
        objective: String,
        context: TaskDecompositionContext
    ): ExecutionPlan {
        if (taskId.isBlank()) {
            throw InvalidDecompositionException("Task ID cannot be blank")
        }
        val trimmedObjective = objective.trim()
        if (trimmedObjective.isBlank() || PlanBuilder.isAmbiguousObjective(trimmedObjective)) {
            throw InvalidDecompositionException("Objective cannot be blank or ambiguous: '$objective'")
        }

        val planId = "plan-$taskId"
        val builder = PlanBuilder(
            planId = planId,
            taskId = taskId,
            title = "Execution Plan for $taskId",
            maxSteps = context.maxSteps,
            baseTimestamp = context.baseTimestamp
        )

        if (context.plannedSteps.isNotEmpty()) {
            for ((index, spec) in context.plannedSteps.withIndex()) {
                val stepObj = spec.objective.ifBlank { spec.title }.trim()
                if (stepObj.isBlank() || PlanBuilder.isAmbiguousObjective(stepObj)) {
                    throw InvalidDecompositionException("Step '${spec.title}' has ambiguous or blank objective: '$stepObj'")
                }
                val stepCriteria = spec.acceptanceCriteria.filter { it.isNotBlank() }
                    .ifEmpty { context.acceptanceCriteria.filter { it.isNotBlank() } }
                if (stepCriteria.isEmpty()) {
                    throw InvalidDecompositionException("Step '${spec.title}' is missing explicit acceptance criteria")
                }
                builder.addStep(
                    title = spec.title.ifBlank { "Step ${index + 1}" },
                    objective = stepObj,
                    acceptanceCriteria = stepCriteria,
                    verificationCommand = spec.verificationCommand,
                    expectedFiles = spec.expectedFiles,
                    forbiddenFiles = spec.forbiddenFiles,
                    expectedContent = spec.expectedContent,
                    stepId = spec.stepId,
                    stepOrder = spec.stepOrder ?: index
                )
            }
            return builder.build()
        }

        val (cleanObjective, extractedCriteria) = extractCriteriaFromText(trimmedObjective)
        val effectiveCriteria = context.acceptanceCriteria.filter { it.isNotBlank() }
            .ifEmpty { extractedCriteria }

        if (effectiveCriteria.isEmpty()) {
            throw InvalidDecompositionException(
                "Missing acceptance criteria: task decomposition requires explicit acceptance criteria for objective '$objective'"
            )
        }

        val parsedMilestones = parseMilestones(cleanObjective, context.maxSteps)
        if (parsedMilestones.size >= 2) {
            for ((index, milestone) in parsedMilestones.withIndex()) {
                builder.addStep(
                    title = milestone.title,
                    objective = milestone.objective,
                    acceptanceCriteria = effectiveCriteria,
                    stepOrder = index
                )
            }
        } else {
            builder.addStep(
                title = "Execute Task",
                objective = cleanObjective,
                acceptanceCriteria = effectiveCriteria,
                stepOrder = 0
            )
        }

        return try {
            builder.build()
        } catch (e: InvalidDecompositionException) {
            if (parsedMilestones.size >= 2) {
                // If building multi-step plan fails validation, fall back to single-step plan
                val fallbackBuilder = PlanBuilder(
                    planId = planId,
                    taskId = taskId,
                    title = "Execution Plan for $taskId",
                    maxSteps = context.maxSteps,
                    baseTimestamp = context.baseTimestamp
                )
                fallbackBuilder.addStep(
                    title = "Execute Task",
                    objective = cleanObjective,
                    acceptanceCriteria = effectiveCriteria,
                    stepOrder = 0
                )
                fallbackBuilder.build()
            } else {
                throw e
            }
        }
    }

    private data class ParsedMilestone(
        val order: Int,
        val title: String,
        val objective: String
    )

    private fun parseMilestones(text: String, maxSteps: Int = TaskDecomposer.MAX_STEPS): List<ParsedMilestone> {
        val lines = text.lines()
        val numberedRegex = Regex("""^\s*(?:Step\s+|Phase\s+)?(\d+)[\.\:\-]\s*(.+)$""", RegexOption.IGNORE_CASE)

        val milestones = mutableListOf<ParsedMilestone>()
        val seenObjectives = mutableSetOf<String>()
        var expectedOrder = -1

        for (line in lines) {
            val trimmedLine = line.trim()
            val match = numberedRegex.matchEntire(trimmedLine) ?: continue
            val orderNum = match.groupValues[1].toIntOrNull() ?: continue
            val content = match.groupValues[2].trim()

            // If any numbered line has blank or ambiguous content, it is not a valid milestone plan
            if (content.isBlank() || PlanBuilder.isAmbiguousObjective(content)) {
                return emptyList()
            }

            if (expectedOrder == -1) {
                // A valid milestone plan must start at step 0 or 1
                if (orderNum != 0 && orderNum != 1) {
                    return emptyList()
                }
                expectedOrder = orderNum
            } else {
                expectedOrder++
                // If numbering restarted, skipped, or is out of order, gracefully fall back
                if (orderNum != expectedOrder) {
                    return emptyList()
                }
            }

            val normObj = content.lowercase()
            if (!seenObjectives.add(normObj)) {
                // Duplicate milestone objectives indicate repetitive prose or multi-lists, not valid milestones
                return emptyList()
            }

            val title = if (content.length > 60) content.take(60).substringBeforeLast(' ') else content
            milestones.add(
                ParsedMilestone(
                    order = orderNum,
                    title = title.ifBlank { "Milestone $orderNum" },
                    objective = content
                )
            )

            if (milestones.size > maxSteps) {
                return emptyList()
            }
        }

        return if (milestones.size >= 2) milestones else emptyList()
    }

    private fun extractCriteriaFromText(text: String): Pair<String, List<String>> {
        val markerRegex = Regex("""(?i)(?:acceptance\s+criteria|criteria|verify):""")
        val match = markerRegex.find(text) ?: return Pair(text, emptyList())

        val objectivePart = text.substring(0, match.range.first).trim()
        val criteriaPart = text.substring(match.range.last + 1).trim()

        val criteriaList = criteriaPart.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
            .filter { it.isNotBlank() }

        return Pair(objectivePart.ifBlank { text }, criteriaList)
    }
}
