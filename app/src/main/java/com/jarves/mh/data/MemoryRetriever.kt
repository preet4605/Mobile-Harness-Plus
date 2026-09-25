package com.jarves.mh.data

class MemoryRetriever(
    private val repository: ContextMemoryRepository,
    private val taskRepository: TaskStateRepository
) {

    /**
     * Answers: "What do we know?"
     * Returns high-confidence active project facts, architecture, dependencies, and conventions.
     */
    fun whatDoWeKnow(projectId: String, limit: Int = 25): List<MemoryEntry> {
        val candidates = repository.filter(
            projectId = projectId,
            status = MemoryStatus.ACTIVE,
            minConfidence = 0.5f,
            limit = 100
        ).filter { it.type == MemoryType.PROJECT || it.type == MemoryType.DECISION }

        return MemoryRanker.rank(
            entries = candidates,
            query = null,
            targetType = MemoryType.PROJECT,
            limit = limit
        )
    }

    /**
     * Answers: "What did we decide?"
     * Returns architectural and design decisions, optionally filtered by topic.
     */
    fun whatDidWeDecide(
        projectId: String,
        topic: String? = null,
        limit: Int = 10
    ): List<MemoryEntry> {
        val decisions = repository.filter(
            projectId = projectId,
            type = MemoryType.DECISION,
            status = MemoryStatus.ACTIVE,
            limit = 50
        )

        return MemoryRanker.rank(
            entries = decisions,
            query = topic,
            targetType = MemoryType.DECISION,
            limit = limit
        )
    }

    /**
     * Answers: "What are we doing?"
     * Returns the active task checkpoint and any active working memory entries.
     */
    fun whatAreWeDoing(projectId: String): Pair<TaskCheckpoint?, List<MemoryEntry>> {
        val checkpoint = taskRepository.getActiveCheckpoint(projectId)
        val workingEntries = repository.filter(
            projectId = projectId,
            type = MemoryType.WORKING,
            status = MemoryStatus.ACTIVE,
            limit = 20
        )
        return Pair(checkpoint, workingEntries)
    }

    /**
     * Answers: "What failed?"
     * Returns episodic error memories and the last recorded failure from task state.
     */
    fun whatFailed(projectId: String, limit: Int = 10): List<MemoryEntry> {
        val episodicFailures = repository.filter(
            projectId = projectId,
            type = MemoryType.EPISODIC,
            status = null,
            limit = 50
        ).filter {
            it.status == MemoryStatus.FAILED ||
            it.key.contains("error", ignoreCase = true) ||
            it.key.contains("fail", ignoreCase = true) ||
            it.value.contains("failed", ignoreCase = true) ||
            it.value.contains("error", ignoreCase = true)
        }

        return MemoryRanker.rank(
            entries = episodicFailures,
            query = "error failure bug crash",
            targetType = MemoryType.EPISODIC,
            limit = limit
        )
    }

    /**
     * Answers: "What remains?"
     * Returns pending steps, blockers, and next action from task checkpoint and working memory.
     */
    fun whatRemains(projectId: String): List<String> {
        val items = mutableListOf<String>()
        val checkpoint = taskRepository.getActiveCheckpoint(projectId)
        if (checkpoint != null) {
            checkpoint.nextAction?.let { if (it.isNotBlank()) items.add("Next: $it") }
            checkpoint.pendingSteps.forEach { items.add("Pending step: $it") }
            checkpoint.blockers.forEach { items.add("Blocker: $it") }
        }

        val workingEntries = repository.filter(
            projectId = projectId,
            type = MemoryType.WORKING,
            status = MemoryStatus.ACTIVE,
            limit = 20
        )
        workingEntries.filter {
            it.key.contains("pending", ignoreCase = true) ||
            it.key.contains("next", ignoreCase = true) ||
            it.key.contains("blocker", ignoreCase = true)
        }.forEach {
            items.add("${it.key}: ${it.value}")
        }

        return items.distinct()
    }

    /**
     * Hybrid search and ranking answering specific questions, such as:
     * "What database does this project use?"
     */
    fun searchAndRank(
        projectId: String,
        query: String,
        targetType: MemoryType? = null,
        limit: Int = 10
    ): List<MemoryEntry> {
        // 1. Direct exact key match attempt
        val exact = repository.findExact(projectId, query, MemoryStatus.ACTIVE)

        // 2. FTS5 / Search results
        val searchResults = repository.search(projectId, query, limit = 30)

        // 3. Broad active candidates
        val allActive = repository.getByProject(projectId, MemoryStatus.ACTIVE, limit = 100)

        // Combine candidates
        val candidatePool = (listOfNotNull(exact) + searchResults + allActive).distinctBy { it.id }

        // Rank candidates
        return MemoryRanker.rank(
            entries = candidatePool,
            query = query,
            targetType = targetType,
            limit = limit
        )
    }
}
