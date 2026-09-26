package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.ExecutionFeedback
import com.jarves.mh.model.brain.ExecutionOutcome
import com.jarves.mh.model.brain.LearnedDiscovery
import com.jarves.mh.model.brain.LearnedSolution
import com.jarves.mh.model.brain.LearningResult
import java.time.Instant
import java.util.UUID

/**
 * Deterministic learning service that extracts typed Brain knowledge from execution feedback
 * and persists it through the conflict-aware BrainKnowledgeRepository.
 *
 * Enforces the core principles:
 * - WRITE BROADLY. READ NARROWLY. TRUST CONSERVATIVELY.
 * - Execution feedback is evidence, not automatic truth.
 * - Arbitrary agent claims are NEVER promoted into trusted FACT, CONSTRAINT, DECISION, or PREFERENCE.
 * - Idempotent, bounded, and side-effect safe.
 */
open class BrainLearningService(
    private val repository: BrainKnowledgeRepository
) {
    companion object {
        const val MAX_LEARNED_ENTRIES = 20
        const val MAX_GENERAL_SUMMARY_CHARS = 2_000
        const val MAX_FAILURE_CHARS = 1_000
        const val MAX_SOLUTION_CHARS = 1_000
        const val MAX_DISCOVERY_CHARS = 1_000
        const val MAX_WORKSPACE_STATE_CHARS = 1_000
        const val MAX_PROGRESS_CHARS = 1_000
        const val TRUNCATION_MARKER = "...[truncated]"

        fun truncate(text: String, maxChars: Int): String {
            if (text.length <= maxChars) return text
            val cutLen = (maxChars - TRUNCATION_MARKER.length).coerceAtLeast(0)
            return text.take(cutLen) + TRUNCATION_MARKER
        }

        fun generateDeterministicId(taskId: String, attemptId: String, type: BrainKnowledgeType, key: String): String {
            val normKey = MemoryConflictResolver.normalizeKey(key)
            val seed = "$taskId:$attemptId:${type.name}:$normKey"
            return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
        }
    }

    /**
     * Extracts and persists bounded, typed knowledge entries from the provided execution feedback.
     * Guaranteed to be idempotent, conflict-aware, and safe against execution failure.
     */
    open fun learn(feedback: ExecutionFeedback): LearningResult {
        val candidates = mutableListOf<BrainKnowledgeEntry>()
        val feedbackRef = "execution:${feedback.taskId}:${feedback.attemptId}"
        val now = feedback.createdAt

        // 1. Progress learning
        when (feedback.outcome) {
            ExecutionOutcome.CANCELLED -> {
                // Cancelled executions must NOT create verified completion progress, criteria, or solutions.
                // We record an objective cancellation diagnostic record.
                val cancelledContent = truncate("Execution attempt ${feedback.attemptId} was cancelled: ${feedback.summary}", MAX_PROGRESS_CHARS)
                candidates.add(
                    BrainKnowledgeEntry(
                        id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "cancellation"),
                        projectId = feedback.projectId,
                        taskId = feedback.taskId,
                        scope = MemoryScope.PROJECT,
                        knowledgeType = BrainKnowledgeType.PROGRESS,
                        key = "progress:${feedback.taskId}:${feedback.attemptId}:cancelled",
                        content = cancelledContent,
                        summary = "Cancelled execution attempt",
                        importance = 0.5f,
                        confidence = 0.90f,
                        source = MemorySource.PROJECT_OBSERVED,
                        sourceReference = feedbackRef,
                        tags = listOf("progress", "cancelled", "execution"),
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )
            }
            ExecutionOutcome.SUCCESS -> {
                // Overall verified task progress
                val successSummary = truncate(feedback.summary, MAX_GENERAL_SUMMARY_CHARS)
                val successContent = truncate("Task ${feedback.taskId} completed successfully on attempt ${feedback.attemptId}. Summary: ${feedback.summary}", MAX_PROGRESS_CHARS)
                val progressSource = if (feedback.source == MemorySource.TOOL_VERIFIED) MemorySource.TOOL_VERIFIED else MemorySource.PROJECT_OBSERVED
                val progressConfidence = if (feedback.source == MemorySource.TOOL_VERIFIED) 0.95f else 0.90f

                candidates.add(
                    BrainKnowledgeEntry(
                        id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "verified-outcome"),
                        projectId = feedback.projectId,
                        taskId = feedback.taskId,
                        scope = MemoryScope.PROJECT,
                        knowledgeType = BrainKnowledgeType.PROGRESS,
                        key = "progress:${feedback.taskId}:verified-outcome",
                        content = successContent,
                        summary = successSummary,
                        importance = 0.85f,
                        confidence = progressConfidence,
                        source = progressSource,
                        sourceReference = feedbackRef,
                        tags = listOf("progress", "completed", "outcome"),
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )

                // Verified criteria
                feedback.verifiedCriteria.forEach { criterion ->
                    if (criterion.isNotBlank()) {
                        val normCriterion = MemoryConflictResolver.normalizeKey(criterion)
                        candidates.add(
                            BrainKnowledgeEntry(
                                id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "criterion-$normCriterion"),
                                projectId = feedback.projectId,
                                taskId = feedback.taskId,
                                scope = MemoryScope.PROJECT,
                                knowledgeType = BrainKnowledgeType.PROGRESS,
                                key = "progress:${feedback.taskId}:criterion:$normCriterion",
                                content = truncate("Verified acceptance criterion: $criterion", MAX_PROGRESS_CHARS),
                                summary = "Verified criterion: ${truncate(criterion, 200)}",
                                importance = 0.80f,
                                confidence = 0.95f,
                                source = MemorySource.TOOL_VERIFIED,
                                sourceReference = feedbackRef,
                                tags = listOf("progress", "acceptance-criterion", "verified"),
                                createdAt = now,
                                updatedAt = now,
                                lastAccessedAt = now
                            )
                        )
                    }
                }

                // Completed step IDs
                feedback.completedStepIds.forEach { stepId ->
                    if (stepId.isNotBlank()) {
                        val normStep = MemoryConflictResolver.normalizeKey(stepId)
                        candidates.add(
                            BrainKnowledgeEntry(
                                id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "step-$normStep"),
                                projectId = feedback.projectId,
                                taskId = feedback.taskId,
                                scope = MemoryScope.PROJECT,
                                knowledgeType = BrainKnowledgeType.PROGRESS,
                                key = "progress:${feedback.taskId}:step:$normStep",
                                content = truncate("Execution step $stepId was verified and completed on attempt ${feedback.attemptId}.", MAX_PROGRESS_CHARS),
                                summary = "Completed step $stepId",
                                importance = 0.75f,
                                confidence = 0.95f,
                                source = MemorySource.TOOL_VERIFIED,
                                sourceReference = feedbackRef,
                                tags = listOf("progress", "step", "completed"),
                                createdAt = now,
                                updatedAt = now,
                                lastAccessedAt = now
                            )
                        )
                    }
                }
            }
            ExecutionOutcome.PARTIAL -> {
                // Record partial progress observation
                candidates.add(
                    BrainKnowledgeEntry(
                        id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "partial-progress"),
                        projectId = feedback.projectId,
                        taskId = feedback.taskId,
                        scope = MemoryScope.PROJECT,
                        knowledgeType = BrainKnowledgeType.PROGRESS,
                        key = "progress:${feedback.taskId}:${feedback.attemptId}:partial",
                        content = truncate("Partial progress on attempt ${feedback.attemptId}: ${feedback.summary}", MAX_PROGRESS_CHARS),
                        summary = "Partial execution progress",
                        importance = 0.60f,
                        confidence = 0.85f,
                        source = MemorySource.PROJECT_OBSERVED,
                        sourceReference = feedbackRef,
                        tags = listOf("progress", "partial"),
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )

                // Only record explicitly verified steps in partial outcome
                feedback.completedStepIds.forEach { stepId ->
                    if (stepId.isNotBlank()) {
                        val normStep = MemoryConflictResolver.normalizeKey(stepId)
                        candidates.add(
                            BrainKnowledgeEntry(
                                id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "step-$normStep"),
                                projectId = feedback.projectId,
                                taskId = feedback.taskId,
                                scope = MemoryScope.PROJECT,
                                knowledgeType = BrainKnowledgeType.PROGRESS,
                                key = "progress:${feedback.taskId}:step:$normStep",
                                content = truncate("Execution step $stepId completed on attempt ${feedback.attemptId}.", MAX_PROGRESS_CHARS),
                                summary = "Completed step $stepId",
                                importance = 0.75f,
                                confidence = 0.95f,
                                source = MemorySource.TOOL_VERIFIED,
                                sourceReference = feedbackRef,
                                tags = listOf("progress", "step", "partial"),
                                createdAt = now,
                                updatedAt = now,
                                lastAccessedAt = now
                            )
                        )
                    }
                }
            }
            ExecutionOutcome.BLOCKED -> {
                // Record blocked status without fabricating completion
                candidates.add(
                    BrainKnowledgeEntry(
                        id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.PROGRESS, "blocked-progress"),
                        projectId = feedback.projectId,
                        taskId = feedback.taskId,
                        scope = MemoryScope.PROJECT,
                        knowledgeType = BrainKnowledgeType.PROGRESS,
                        key = "progress:${feedback.taskId}:${feedback.attemptId}:blocked",
                        content = truncate("Execution blocked on attempt ${feedback.attemptId}: ${feedback.summary}", MAX_PROGRESS_CHARS),
                        summary = "Execution blocked",
                        importance = 0.60f,
                        confidence = 0.85f,
                        source = MemorySource.PROJECT_OBSERVED,
                        sourceReference = feedbackRef,
                        tags = listOf("progress", "blocked"),
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )
            }
            ExecutionOutcome.FAILED -> {
                // Failed executions do not record verified progress entries
            }
        }

        // 2. Failure learning
        feedback.failures.forEach { failure ->
            val normClassification = MemoryConflictResolver.normalizeKey(failure.classification)
            val stepSuffix = failure.stepId?.let { ":${MemoryConflictResolver.normalizeKey(it)}" } ?: ""
            val failureKey = "failure:${feedback.taskId}:${feedback.attemptId}:$normClassification$stepSuffix"
            val snippetText = if (!failure.errorSnippet.isNullOrBlank()) " Snippet: ${failure.errorSnippet}" else ""
            val fullContent = "Failure [${failure.classification}]: ${failure.errorMessage}$snippetText"

            candidates.add(
                BrainKnowledgeEntry(
                    id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.FAILURE, "$normClassification$stepSuffix"),
                    projectId = feedback.projectId,
                    taskId = feedback.taskId,
                    scope = MemoryScope.PROJECT,
                    knowledgeType = BrainKnowledgeType.FAILURE,
                    key = failureKey,
                    content = truncate(fullContent, MAX_FAILURE_CHARS),
                    summary = truncate(failure.errorMessage, MAX_FAILURE_CHARS),
                    importance = 0.80f,
                    confidence = 0.95f,
                    source = MemorySource.TOOL_VERIFIED,
                    sourceReference = feedbackRef,
                    tags = listOf("failure", failure.classification) + if (failure.stepId != null) listOf("step:${failure.stepId}") else emptyList(),
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now
                )
            )
        }

        // 3. Solution learning
        if (!feedback.outcome.isCancelled) {
            feedback.solutions.forEach { solution ->
                val normKey = MemoryConflictResolver.normalizeKey(solution.key)
                val isVerified = solution.isVerified || solution.source == MemorySource.TOOL_VERIFIED
                val source = if (isVerified) MemorySource.TOOL_VERIFIED else MemorySource.AGENT_INFERRED
                val confidence = if (isVerified) 0.95f else 0.40f
                val importance = if (isVerified) 0.85f else 0.30f
                val solKey = "solution:${feedback.taskId}:$normKey"
                val prefix = if (isVerified) "Verified solution: " else "Agent-inferred solution: "

                candidates.add(
                    BrainKnowledgeEntry(
                        id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.SOLUTION, normKey),
                        projectId = feedback.projectId,
                        taskId = feedback.taskId,
                        scope = MemoryScope.PROJECT,
                        knowledgeType = BrainKnowledgeType.SOLUTION,
                        key = solKey,
                        content = truncate("$prefix${solution.procedure}", MAX_SOLUTION_CHARS),
                        summary = truncate(solution.summary, MAX_SOLUTION_CHARS),
                        importance = importance,
                        confidence = confidence,
                        source = source,
                        sourceReference = feedbackRef,
                        tags = listOf("solution") + if (solution.failureClassification != null) listOf("fixes:${solution.failureClassification}") else emptyList(),
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )
            }
        }

        // 4. Discovery learning
        feedback.discoveries.forEach { discovery ->
            val normKey = MemoryConflictResolver.normalizeKey(discovery.key)
            val isObserved = discovery.isObjectivelyObserved || discovery.source in setOf(
                MemorySource.PROJECT_OBSERVED,
                MemorySource.TOOL_VERIFIED,
                MemorySource.USER,
                MemorySource.USER_PROVIDED
            )
            val source = if (isObserved) discovery.source else MemorySource.AGENT_INFERRED
            val confidence = when (source) {
                MemorySource.TOOL_VERIFIED, MemorySource.USER, MemorySource.USER_PROVIDED -> 0.95f
                MemorySource.PROJECT_OBSERVED -> 0.90f
                MemorySource.AUTO -> 0.70f
                MemorySource.AGENT_INFERRED -> 0.40f
            }
            val discKey = "discovery:${feedback.projectId}:$normKey"

            candidates.add(
                BrainKnowledgeEntry(
                    id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.DISCOVERY, normKey),
                    projectId = feedback.projectId,
                    taskId = feedback.taskId,
                    scope = MemoryScope.PROJECT,
                    knowledgeType = BrainKnowledgeType.DISCOVERY,
                    key = discKey,
                    content = truncate(discovery.content, MAX_DISCOVERY_CHARS),
                    summary = discovery.summary?.let { truncate(it, MAX_DISCOVERY_CHARS) },
                    importance = if (isObserved) 0.60f else 0.30f,
                    confidence = confidence,
                    source = source,
                    sourceReference = feedbackRef,
                    tags = listOf("discovery"),
                    createdAt = now,
                    updatedAt = now,
                    lastAccessedAt = now
                )
            )
        }

        // 5. Workspace state learning
        feedback.workspaceState?.let { ws ->
            val details = buildString {
                if (!ws.headCommitSha.isNullOrBlank()) append("Commit: ${ws.headCommitSha}. ")
                if (!ws.branch.isNullOrBlank()) append("Branch: ${ws.branch}. ")
                if (ws.modifiedFiles.isNotEmpty()) append("Modified files (${ws.modifiedFiles.size}): ${ws.modifiedFiles.take(20).joinToString(", ")}. ")
                if (!ws.summary.isNullOrBlank()) append("State: ${ws.summary}. ")
            }.trim()

            if (details.isNotBlank()) {
                val isObserved = ws.isObjectivelyObserved
                val source = if (isObserved) MemorySource.PROJECT_OBSERVED else MemorySource.AGENT_INFERRED
                val confidence = if (isObserved) 0.90f else 0.40f

                candidates.add(
                    BrainKnowledgeEntry(
                        id = generateDeterministicId(feedback.taskId, feedback.attemptId, BrainKnowledgeType.WORKSPACE_STATE, "state"),
                        projectId = feedback.projectId,
                        taskId = feedback.taskId,
                        scope = MemoryScope.PROJECT,
                        knowledgeType = BrainKnowledgeType.WORKSPACE_STATE,
                        key = "workspace-state:${feedback.projectId}:${feedback.taskId}",
                        content = truncate(details, MAX_WORKSPACE_STATE_CHARS),
                        summary = "Workspace state snapshot for task ${feedback.taskId}",
                        importance = 0.50f,
                        confidence = confidence,
                        source = source,
                        sourceReference = feedbackRef,
                        tags = listOf("workspace-state"),
                        createdAt = now,
                        updatedAt = now,
                        lastAccessedAt = now
                    )
                )
            }
        }

        // Safety enforcement: reject any entry attempting to fabricate FACT, CONSTRAINT, DECISION, PREFERENCE from agent claims
        val safeCandidates = candidates.filter { entry ->
            !(entry.source == MemorySource.AGENT_INFERRED &&
              entry.knowledgeType in setOf(
                  BrainKnowledgeType.FACT,
                  BrainKnowledgeType.CONSTRAINT,
                  BrainKnowledgeType.DECISION,
                  BrainKnowledgeType.PREFERENCE
              ))
        }

        // 6. Bound candidate entries to MAX_LEARNED_ENTRIES (20)
        val boundedEntries = safeCandidates.take(MAX_LEARNED_ENTRIES)
        val excessCount = safeCandidates.size - boundedEntries.size

        val persisted = mutableListOf<BrainKnowledgeEntry>()
        var inserted = 0
        var deduplicated = 0
        var superseded = 0
        var rejected = 0
        var ignored = excessCount
        val errors = mutableListOf<String>()

        for (entry in boundedEntries) {
            try {
                val (saved, resolution) = repository.saveWithResolution(entry)
                persisted.add(saved)
                when (resolution) {
                    is MemoryConflictResolver.KnowledgeResolution.InsertNew,
                    is MemoryConflictResolver.KnowledgeResolution.Coexisting -> inserted++
                    is MemoryConflictResolver.KnowledgeResolution.Deduplicate -> deduplicated++
                    is MemoryConflictResolver.KnowledgeResolution.Supersede -> superseded++
                    is MemoryConflictResolver.KnowledgeResolution.RejectedIncompatible,
                    is MemoryConflictResolver.KnowledgeResolution.RejectedLowerTrust -> rejected++
                    is MemoryConflictResolver.KnowledgeResolution.NoChange -> ignored++
                }
            } catch (t: Throwable) {
                errors.add("Failed to persist learned entry ${entry.key}: ${t.message}")
            }
        }

        return LearningResult(
            feedbackId = feedbackRef,
            entries = persisted,
            insertedCount = inserted,
            deduplicatedCount = deduplicated,
            supersededCount = superseded,
            rejectedCount = rejected,
            ignoredCount = ignored,
            errors = errors
        )
    }
}
