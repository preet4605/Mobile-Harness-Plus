package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord

/**
 * Structured, immutable context model representing the aggregated task state,
 * active execution milestone, constraints, criteria, and project memory.
 */
data class BrainContext(
    val task: CanonicalTask? = null,
    val currentStep: ExecutionStep? = null,
    val constraints: List<String> = emptyList(),
    val acceptanceCriteria: List<String> = emptyList(),
    val progress: List<String> = emptyList(),
    val relevantKnowledge: List<BrainKnowledgeEntry> = emptyList(),
    val recentFailures: List<TaskFailureRecord> = emptyList(),
    val solutions: List<BrainKnowledgeEntry> = emptyList(),
    val workspaceState: String? = null,
    val maxCharacters: Int = BrainContextAssembler.DEFAULT_MAX_CONTEXT_CHARS,
) {
    val isEmpty: Boolean
        get() = task == null &&
                currentStep == null &&
                constraints.isEmpty() &&
                acceptanceCriteria.isEmpty() &&
                progress.isEmpty() &&
                relevantKnowledge.isEmpty() &&
                recentFailures.isEmpty() &&
                solutions.isEmpty() &&
                workspaceState.isNullOrBlank()
}

/**
 * Deterministic, bounded Brain Context Assembler layer.
 * Transforms canonical task state and relevant persistent brain knowledge
 * into a compact, sanitized, bounded representation for agent runtime injection.
 */
class BrainContextAssembler(
    private val knowledgeRepository: BrainKnowledgeRepository? = null
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT_CHARS = 8_000
        const val TRUNCATION_MARKER = "...[truncated]"
        const val MAX_SINGLE_ENTRY_CHARS = 1_000
        const val MAX_RETRIEVAL_LIMIT = 20
        const val MAX_FAILURES_COUNT = 10
        const val MAX_SOLUTIONS_COUNT = 10
        const val MAX_KNOWLEDGE_COUNT = 20

        const val NOMINAL_TASK_CHARS = 700
        const val NOMINAL_STEP_CHARS = 700
        const val NOMINAL_ACCEPTANCE_CHARS = 600
        const val NOMINAL_CONSTRAINTS_CHARS = 1_200
        const val NOMINAL_PROGRESS_CHARS = 800
        const val NOMINAL_FAILURES_CHARS = 1_000
        const val NOMINAL_SOLUTIONS_CHARS = 1_000
        const val NOMINAL_KNOWLEDGE_CHARS = 1_500
        const val NOMINAL_WORKSPACE_CHARS = 500

        private val SECTION_TAG_REGEX = Regex(
            "\\[(/?(?:BRAIN_CONTEXT|TASK|CURRENT_STEP|CONSTRAINTS|ACCEPTANCE_CRITERIA|PROGRESS|RELEVANT_KNOWLEDGE|FAILURES|SOLUTIONS|WORKSPACE_STATE))\\]",
            RegexOption.IGNORE_CASE
        )

        private val CONTROL_CHARS_REGEX = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]")
        private val MULTI_NEWLINE_REGEX = Regex("\n{3,}")
        private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")

        /**
         * Sanitizes text to prevent section boundary injection, strip non-printable
         * control characters, normalize newlines, and limit runaway whitespace.
         */
        fun sanitize(text: String?): String {
            if (text == null) return ""
            var cleaned = text.replace(CONTROL_CHARS_REGEX, "")
            cleaned = cleaned.replace("\r\n", "\n").replace('\r', '\n')
            cleaned = cleaned.replace(MULTI_NEWLINE_REGEX, "\n\n")
            cleaned = cleaned.replace(SECTION_TAG_REGEX) { match ->
                "\\[${match.groupValues[1]}\\]"
            }
            return cleaned.trim()
        }

        fun sanitizeSingleLine(text: String?): String {
            if (text == null) return ""
            var cleaned = sanitize(text)
            cleaned = cleaned.replace(WHITESPACE_COLLAPSE_REGEX, " ")
            return cleaned.trim()
        }
    }

    private val knowledgeComparator = compareByDescending<BrainKnowledgeEntry> {
        when (it.knowledgeType) {
            BrainKnowledgeType.CONSTRAINT -> 10
            BrainKnowledgeType.DECISION -> 9
            BrainKnowledgeType.SOLUTION -> 8
            BrainKnowledgeType.FAILURE -> 7
            BrainKnowledgeType.TASK -> 6
            BrainKnowledgeType.FACT -> 5
            BrainKnowledgeType.PREFERENCE -> 4
            BrainKnowledgeType.DISCOVERY -> 3
            BrainKnowledgeType.PROGRESS -> 2
            BrainKnowledgeType.WORKSPACE_STATE -> 1
        }
    }.thenByDescending { it.importance }
        .thenByDescending { it.updatedAt.toEpochMilli() }
        .thenByDescending { it.createdAt.toEpochMilli() }
        .thenBy { it.id }

    private val solutionComparator = compareByDescending<BrainKnowledgeEntry> { it.importance }
        .thenByDescending { it.updatedAt.toEpochMilli() }
        .thenByDescending { it.createdAt.toEpochMilli() }
        .thenBy { it.id }

    private val failureComparator = compareByDescending<TaskFailureRecord> { it.timestamp.toEpochMilli() }
        .thenBy { it.failureId }

    /**
     * Assembles a structured BrainContext from the canonical task and relevant knowledge.
     */
    fun assemble(
        task: CanonicalTask? = null,
        currentStep: ExecutionStep? = null,
        projectId: String? = null,
        query: String? = null,
        maxCharacters: Int = DEFAULT_MAX_CONTEXT_CHARS
    ): BrainContext {
        val effectiveMaxChars = maxCharacters.coerceIn(1, DEFAULT_MAX_CONTEXT_CHARS)
        val effectiveProjectId = task?.projectId?.trim().takeIf { !it.isNullOrBlank() }
            ?: projectId?.trim().orEmpty()
        val step = currentStep ?: task?.plan?.currentStep

        val effectiveQuery = query?.trim().takeIf { !it.isNullOrBlank() }

        val retrievedEntries = if (effectiveProjectId.isNotBlank() && knowledgeRepository != null) {
            val knowledgeTypes = setOf(
                BrainKnowledgeType.CONSTRAINT,
                BrainKnowledgeType.DECISION,
                BrainKnowledgeType.SOLUTION,
                BrainKnowledgeType.FAILURE,
                BrainKnowledgeType.TASK,
                BrainKnowledgeType.FACT,
                BrainKnowledgeType.PREFERENCE,
                BrainKnowledgeType.DISCOVERY,
                BrainKnowledgeType.PROGRESS,
                BrainKnowledgeType.WORKSPACE_STATE
            )
            knowledgeRepository.retrieveRelevant(
                projectId = effectiveProjectId,
                query = effectiveQuery,
                taskId = task?.taskId,
                knowledgeTypes = knowledgeTypes,
                limit = MAX_RETRIEVAL_LIMIT,
                maxCharacters = effectiveMaxChars
            )
        } else {
            emptyList()
        }

        val distinctEntries = retrievedEntries.distinctBy { it.id }

        // 1. Constraints (task constraints prioritized, then knowledge constraints)
        val taskConstraints = task?.constraints.orEmpty().map { sanitizeSingleLine(it) }.filter { it.isNotBlank() }
        val knowledgeConstraints = distinctEntries
            .filter { it.knowledgeType == BrainKnowledgeType.CONSTRAINT }
            .sortedWith(knowledgeComparator)
            .map { entry ->
                val key = sanitizeSingleLine(entry.key)
                val content = sanitizeSingleLine(entry.content)
                if (content.isNotBlank() && content != key) "$key: $content" else key
            }
        val allConstraints = (taskConstraints + knowledgeConstraints).distinct()

        // 2. Acceptance Criteria (task criteria + step expected files)
        val taskCriteria = task?.acceptanceCriteria.orEmpty().map { sanitizeSingleLine(it) }.filter { it.isNotBlank() }
        val stepExpected = if (step?.expectedFiles?.isNotEmpty() == true) {
            listOf("Expected files: ${step.expectedFiles.joinToString(", ") { sanitizeSingleLine(it) }}")
        } else emptyList()
        val allCriteria = (taskCriteria + stepExpected).distinct()

        // 3. Progress (completed plan steps + progress entries)
        val completedSteps = task?.plan?.steps.orEmpty()
            .filter { it.status == StepStatus.COMPLETED }
            .sortedBy { it.stepOrder }
            .map { "Step ${it.stepOrder}: ${sanitizeSingleLine(it.title)} - ${sanitizeSingleLine(it.resultSummary ?: "Completed")}" }
        val progressKnowledge = distinctEntries
            .filter { it.knowledgeType == BrainKnowledgeType.PROGRESS }
            .sortedWith(knowledgeComparator)
            .map { entry ->
                val key = sanitizeSingleLine(entry.key)
                val content = sanitizeSingleLine(entry.content)
                if (content.isNotBlank() && content != key) "$key: $content" else key
            }
        val allProgress = (completedSteps + progressKnowledge).distinct()

        // 4. Failures (canonical task failure history + retrieved failure knowledge)
        val taskFailures = task?.failureHistory.orEmpty()
        val knowledgeFailures = distinctEntries
            .filter { it.knowledgeType == BrainKnowledgeType.FAILURE }
            .map { entry ->
                TaskFailureRecord(
                    failureId = entry.id,
                    taskId = entry.taskId ?: task?.taskId ?: "",
                    stepId = null,
                    classification = entry.key,
                    errorMessage = entry.content,
                    errorSnippet = entry.summary,
                    timestamp = entry.updatedAt
                )
            }
        val allFailures = (taskFailures + knowledgeFailures)
            .distinctBy { it.failureId }
            .sortedWith(failureComparator)
            .take(MAX_FAILURES_COUNT)

        // 5. Solutions (retrieved solution knowledge)
        val allSolutions = distinctEntries
            .filter { it.knowledgeType == BrainKnowledgeType.SOLUTION }
            .sortedWith(solutionComparator)
            .take(MAX_SOLUTIONS_COUNT)

        // 6. Relevant general knowledge (FACT, DECISION, PREFERENCE, DISCOVERY, TASK)
        val generalKnowledgeTypes = setOf(
            BrainKnowledgeType.FACT,
            BrainKnowledgeType.DECISION,
            BrainKnowledgeType.PREFERENCE,
            BrainKnowledgeType.DISCOVERY,
            BrainKnowledgeType.TASK
        )
        val allKnowledge = distinctEntries
            .filter { it.knowledgeType in generalKnowledgeTypes }
            .sortedWith(knowledgeComparator)
            .take(MAX_KNOWLEDGE_COUNT)

        // 7. Workspace State
        val workspaceParts = mutableListOf<String>()
        if (!task?.currentWorkspaceSha.isNullOrBlank()) {
            workspaceParts.add("current_sha: ${sanitizeSingleLine(task?.currentWorkspaceSha)}")
        }
        if (!task?.initialWorkspaceSha.isNullOrBlank() && task?.initialWorkspaceSha != task?.currentWorkspaceSha) {
            workspaceParts.add("initial_sha: ${sanitizeSingleLine(task?.initialWorkspaceSha)}")
        }
        val wsKnowledge = distinctEntries
            .filter { it.knowledgeType == BrainKnowledgeType.WORKSPACE_STATE }
            .sortedWith(knowledgeComparator)
        for (ws in wsKnowledge) {
            val key = sanitizeSingleLine(ws.key)
            val content = sanitizeSingleLine(ws.content)
            workspaceParts.add(if (content.isNotBlank() && content != key) "$key: $content" else key)
        }
        val workspaceState = if (workspaceParts.isNotEmpty()) {
            workspaceParts.distinct().joinToString("\n")
        } else null

        return BrainContext(
            task = task,
            currentStep = step,
            constraints = allConstraints,
            acceptanceCriteria = allCriteria,
            progress = allProgress,
            relevantKnowledge = allKnowledge,
            recentFailures = allFailures,
            solutions = allSolutions,
            workspaceState = workspaceState,
            maxCharacters = effectiveMaxChars
        )
    }

    /**
     * Renders a BrainContext into a deterministic, bounded text block.
     */
    fun render(context: BrainContext, maxCharacters: Int = context.maxCharacters): String {
        val effectiveLimit = maxCharacters.coerceIn(1, DEFAULT_MAX_CONTEXT_CHARS)

        val wrapperStart = "[BRAIN_CONTEXT]\n\n"
        val wrapperEnd = "\n\n[/BRAIN_CONTEXT]"
        val fixedOverhead = wrapperStart.length + wrapperEnd.length + (8 * 2) // 35 + 16 = 51

        if (effectiveLimit <= 35) {
            return "[BRAIN_CONTEXT]".take(effectiveLimit)
        }

        val availableBudget = effectiveLimit - fixedOverhead
        val budgets = calculateBudgets(context, availableBudget)

        val taskStr = renderTask(context.task, budgets.taskBudget)
        val stepStr = renderCurrentStep(context.currentStep, budgets.stepBudget)
        val constraintsStr = renderConstraints(context.constraints, budgets.constraintsBudget)
        val acceptanceStr = renderAcceptanceCriteria(context.acceptanceCriteria, budgets.acceptanceBudget)
        val progressStr = renderProgress(context.progress, budgets.progressBudget)
        val knowledgeStr = renderRelevantKnowledge(context.relevantKnowledge, budgets.knowledgeBudget)
        val failuresStr = renderFailures(context.recentFailures, budgets.failuresBudget)
        val solutionsStr = renderSolutions(context.solutions, budgets.solutionsBudget)
        val workspaceStr = renderWorkspaceState(context.workspaceState, budgets.workspaceBudget)

        val sections = listOf(
            taskStr,
            stepStr,
            constraintsStr,
            acceptanceStr,
            progressStr,
            knowledgeStr,
            failuresStr,
            solutionsStr,
            workspaceStr
        )

        val fullOutput = buildString {
            append(wrapperStart)
            append(sections.joinToString("\n\n"))
            append(wrapperEnd)
        }

        return if (fullOutput.length > effectiveLimit) {
            if (effectiveLimit >= TRUNCATION_MARKER.length) {
                fullOutput.take(effectiveLimit - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
            } else {
                fullOutput.take(effectiveLimit)
            }
        } else {
            fullOutput
        }
    }

    private data class SectionBudgets(
        val taskBudget: Int,
        val stepBudget: Int,
        val constraintsBudget: Int,
        val acceptanceBudget: Int,
        val progressBudget: Int,
        val knowledgeBudget: Int,
        val failuresBudget: Int,
        val solutionsBudget: Int,
        val workspaceBudget: Int
    )

    private fun calculateBudgets(
        context: BrainContext,
        availableBudget: Int
    ): SectionBudgets {
        val available = maxOf(0, availableBudget)

        val needTask = renderTask(context.task, NOMINAL_TASK_CHARS).length
        val needStep = renderCurrentStep(context.currentStep, NOMINAL_STEP_CHARS).length
        val needConstraints = renderConstraints(context.constraints, NOMINAL_CONSTRAINTS_CHARS).length
        val needAcceptance = renderAcceptanceCriteria(context.acceptanceCriteria, NOMINAL_ACCEPTANCE_CHARS).length

        val needProgress = renderProgress(context.progress, NOMINAL_PROGRESS_CHARS).length
        val needFailures = renderFailures(context.recentFailures, NOMINAL_FAILURES_CHARS).length
        val needSolutions = renderSolutions(context.solutions, NOMINAL_SOLUTIONS_CHARS).length

        val needKnowledge = renderRelevantKnowledge(context.relevantKnowledge, NOMINAL_KNOWLEDGE_CHARS).length
        val needWorkspace = renderWorkspaceState(context.workspaceState, NOMINAL_WORKSPACE_CHARS).length

        val totalNeeded = needTask + needStep + needConstraints + needAcceptance +
                needProgress + needFailures + needSolutions + needKnowledge + needWorkspace

        // If all content fits comfortably, allocate full needed budgets
        if (totalNeeded <= available) {
            return SectionBudgets(
                taskBudget = needTask,
                stepBudget = needStep,
                constraintsBudget = needConstraints,
                acceptanceBudget = needAcceptance,
                progressBudget = needProgress,
                knowledgeBudget = needKnowledge,
                failuresBudget = needFailures,
                solutionsBudget = needSolutions,
                workspaceBudget = needWorkspace
            )
        }

        // Budget pressure: Allocate strictly by priority (P0 -> P1 -> P2 -> P3)
        var rem = available

        // 1. P0 allocations (TASK, CURRENT_STEP, CONSTRAINTS, ACCEPTANCE_CRITERIA)
        val p0Needed = needTask + needStep + needConstraints + needAcceptance
        val taskB: Int
        val stepB: Int
        val constrB: Int
        val acceptB: Int

        if (rem >= p0Needed) {
            taskB = needTask
            stepB = needStep
            constrB = needConstraints
            acceptB = needAcceptance
            rem -= p0Needed
        } else {
            // Extreme budget pressure even on P0
            taskB = minOf(needTask, rem / 4)
            rem = maxOf(0, rem - taskB)
            stepB = minOf(needStep, rem / 3)
            rem = maxOf(0, rem - stepB)
            constrB = minOf(needConstraints, rem / 2)
            rem = maxOf(0, rem - constrB)
            acceptB = minOf(needAcceptance, rem)
            rem = 0
        }

        // 2. P1 allocations (PROGRESS, FAILURES, SOLUTIONS)
        val p1Needed = needProgress + needFailures + needSolutions
        val progB: Int
        val failB: Int
        val solB: Int

        if (rem >= p1Needed) {
            progB = needProgress
            failB = needFailures
            solB = needSolutions
            rem -= p1Needed
        } else {
            progB = minOf(needProgress, rem / 3)
            rem = maxOf(0, rem - progB)
            failB = minOf(needFailures, rem / 2)
            rem = maxOf(0, rem - failB)
            solB = minOf(needSolutions, rem)
            rem = 0
        }

        // 3. P2 allocation (RELEVANT_KNOWLEDGE)
        val knowB = minOf(needKnowledge, rem)
        rem = maxOf(0, rem - knowB)

        // 4. P3 allocation (WORKSPACE_STATE)
        val wsB = minOf(needWorkspace, rem)

        return SectionBudgets(
            taskBudget = taskB,
            stepBudget = stepB,
            constraintsBudget = constrB,
            acceptanceBudget = acceptB,
            progressBudget = progB,
            knowledgeBudget = knowB,
            failuresBudget = failB,
            solutionsBudget = solB,
            workspaceBudget = wsB
        )
    }

    private fun renderTask(task: CanonicalTask?, budget: Int): String {
        val header = "[TASK]\n"
        if (task == null) {
            return fitContentWithHeader(header, "none", budget)
        }
        val status = when {
            task.outcome != null -> if (task.outcome.success) "SUCCESS" else "FAILED"
            task.plan.isFinished -> "COMPLETED"
            task.plan.currentStep != null -> task.plan.currentStep?.status?.name ?: "IN_PROGRESS"
            else -> "IN_PROGRESS"
        }
        val lines = mutableListOf<String>()
        lines.add("id: ${sanitizeSingleLine(task.taskId)}")
        val slugStr = if (task.projectSlug.isNotBlank()) " (${sanitizeSingleLine(task.projectSlug)})" else ""
        lines.add("project: ${sanitizeSingleLine(task.projectId)}$slugStr")
        lines.add("objective: ${sanitize(task.objective)}")
        lines.add("status: $status")

        val fullContent = lines.joinToString("\n")
        return fitContentWithHeader(header, fullContent, budget)
    }

    private fun renderCurrentStep(step: ExecutionStep?, budget: Int): String {
        val header = "[CURRENT_STEP]\n"
        if (step == null) {
            return fitContentWithHeader(header, "none", budget)
        }
        val lines = mutableListOf<String>()
        lines.add("id: ${sanitizeSingleLine(step.stepId)}")
        lines.add("order: ${step.stepOrder}")
        lines.add("title: ${sanitizeSingleLine(step.title)}")
        lines.add("description: ${sanitize(step.description)}")
        lines.add("status: ${step.status.name}")
        lines.add("attempts: ${step.attempts}/${step.maxAttempts}")
        if (step.expectedFiles.isNotEmpty()) {
            lines.add("expected_files: ${step.expectedFiles.joinToString(", ") { sanitizeSingleLine(it) }}")
        }
        if (!step.resultSummary.isNullOrBlank()) {
            lines.add("result: ${sanitize(step.resultSummary)}")
        }

        val fullContent = lines.joinToString("\n")
        return fitContentWithHeader(header, fullContent, budget)
    }

    private fun renderConstraints(constraints: List<String>, budget: Int): String {
        val header = "[CONSTRAINTS]\n"
        if (constraints.isEmpty()) {
            return fitContentWithHeader(header, "none", budget)
        }
        val items = constraints.map { sanitizeSingleLine(it) }.filter { it.isNotBlank() }
        return fitListWithHeader(header, items, budget)
    }

    private fun renderAcceptanceCriteria(criteria: List<String>, budget: Int): String {
        val header = "[ACCEPTANCE_CRITERIA]\n"
        if (criteria.isEmpty()) {
            return fitContentWithHeader(header, "none", budget)
        }
        val items = criteria.map { sanitizeSingleLine(it) }.filter { it.isNotBlank() }
        return fitListWithHeader(header, items, budget)
    }

    private fun renderProgress(progress: List<String>, budget: Int): String {
        val header = "[PROGRESS]\n"
        if (progress.isEmpty()) {
            return fitContentWithHeader(header, "none", budget)
        }
        val items = progress.map { sanitizeSingleLine(it) }.filter { it.isNotBlank() }
        return fitListWithHeader(header, items, budget)
    }

    private fun renderRelevantKnowledge(entries: List<BrainKnowledgeEntry>, budget: Int): String {
        val header = "[RELEVANT_KNOWLEDGE]\n"
        if (entries.isEmpty()) {
            return fitContentWithHeader(header, "none", budget)
        }
        val sortedEntries = entries.sortedWith(knowledgeComparator)
        val items = sortedEntries.map { entry ->
            val typeTag = "[${entry.knowledgeType.name}]"
            val key = sanitizeSingleLine(entry.key)
            val content = sanitize(entry.content)
            val truncatedContent = if (content.length > MAX_SINGLE_ENTRY_CHARS) {
                content.take(MAX_SINGLE_ENTRY_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
            } else content
            "$typeTag $key: $truncatedContent"
        }
        return fitListWithHeader(header, items, budget)
    }

    private fun renderFailures(failures: List<TaskFailureRecord>, budget: Int): String {
        val header = "[FAILURES]\n"
        if (failures.isEmpty()) {
            return fitContentWithHeader(header, "none", budget)
        }
        val sortedFailures = failures.sortedWith(failureComparator)
        val items = sortedFailures.map { failure ->
            val stepInfo = if (!failure.stepId.isNullOrBlank()) " (step: ${sanitizeSingleLine(failure.stepId)})" else ""
            val classification = sanitizeSingleLine(failure.classification)
            val message = sanitize(failure.errorMessage)
            val truncatedMsg = if (message.length > MAX_SINGLE_ENTRY_CHARS) {
                message.take(MAX_SINGLE_ENTRY_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
            } else message
            val solutionRef = if (!failure.matchedSolutionId.isNullOrBlank()) " [sol: ${sanitizeSingleLine(failure.matchedSolutionId)}]" else ""
            "[${sanitizeSingleLine(failure.failureId)}]$stepInfo $classification: $truncatedMsg$solutionRef"
        }
        return fitListWithHeader(header, items, budget)
    }

    private fun renderSolutions(solutions: List<BrainKnowledgeEntry>, budget: Int): String {
        val header = "[SOLUTIONS]\n"
        if (solutions.isEmpty()) {
            return fitContentWithHeader(header, "none", budget)
        }
        val sortedSolutions = solutions.sortedWith(solutionComparator)
        val items = sortedSolutions.map { solution ->
            val key = sanitizeSingleLine(solution.key)
            val content = sanitize(solution.content)
            val truncatedContent = if (content.length > MAX_SINGLE_ENTRY_CHARS) {
                content.take(MAX_SINGLE_ENTRY_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
            } else content
            val sourceRef = if (!solution.sourceReference.isNullOrBlank()) " (ref: ${sanitizeSingleLine(solution.sourceReference)})" else ""
            "$key: $truncatedContent$sourceRef"
        }
        return fitListWithHeader(header, items, budget)
    }

    private fun renderWorkspaceState(workspaceState: String?, budget: Int): String {
        val header = "[WORKSPACE_STATE]\n"
        if (workspaceState.isNullOrBlank()) {
            return fitContentWithHeader(header, "unavailable", budget)
        }
        return fitContentWithHeader(header, sanitize(workspaceState), budget)
    }

    private fun fitContentWithHeader(header: String, content: String, budget: Int): String {
        val total = header + content
        if (total.length <= budget) return total
        if (budget <= header.length) {
            return header.take(budget)
        }
        val available = budget - header.length
        if (available <= TRUNCATION_MARKER.length) {
            return header + TRUNCATION_MARKER.take(maxOf(0, available))
        }
        val truncatedContent = content.take(available - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        return header + truncatedContent
    }

    private fun fitListWithHeader(
        header: String,
        items: List<String>,
        budget: Int
    ): String {
        if (budget <= header.length) {
            return header.take(budget)
        }
        val sb = StringBuilder(header)
        var remaining = budget - header.length

        for (item in items) {
            val prefix = "- "
            val itemContent = prefix + item
            val itemWithNewline = itemContent + "\n"

            if (itemWithNewline.length <= remaining) {
                sb.append(itemWithNewline)
                remaining -= itemWithNewline.length
            } else if (itemContent.length <= remaining) {
                sb.append(itemContent)
                remaining -= itemContent.length
            } else {
                val neededMin = prefix.length + TRUNCATION_MARKER.length
                if (remaining >= neededMin) {
                    val allowed = remaining - prefix.length - TRUNCATION_MARKER.length
                    sb.append(prefix).append(item.take(allowed)).append(TRUNCATION_MARKER)
                } else if (remaining >= TRUNCATION_MARKER.length) {
                    sb.append(TRUNCATION_MARKER)
                }
                break
            }
        }

        val result = sb.toString().trimEnd('\n')
        return if (result.length > budget) result.take(budget) else result
    }
}
