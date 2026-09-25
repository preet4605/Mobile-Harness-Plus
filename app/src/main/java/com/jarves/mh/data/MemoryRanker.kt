package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import java.time.Instant
import java.util.Locale

object MemoryRanker {

    data class ScoredMemory(
        val entry: MemoryEntry,
        val totalScore: Double,
        val matchScore: Double,
        val importanceScore: Double,
        val confidenceScore: Double,
        val recencyScore: Double,
        val typeBonus: Double
    )

    fun score(
        entry: MemoryEntry,
        query: String?,
        targetType: MemoryType? = null,
        now: Instant = Instant.now()
    ): ScoredMemory {
        val queryTokens = query
            ?.lowercase(Locale.ROOT)
            ?.split(Regex("[\\s,._\\-?]+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val keyLower = entry.key.lowercase(Locale.ROOT)
        val valLower = entry.value.lowercase(Locale.ROOT)
        val summaryLower = entry.summary?.lowercase(Locale.ROOT) ?: ""
        val tagsJoined = entry.tags.joinToString(" ").lowercase(Locale.ROOT)

        val matchScore: Double = when {
            query.isNullOrBlank() -> 0.5
            keyLower == query.trim().lowercase(Locale.ROOT) -> 1.0
            queryTokens.isNotEmpty() && queryTokens.all { keyLower.contains(it) } -> 0.95
            queryTokens.isNotEmpty() && queryTokens.any { keyLower.contains(it) } -> 0.80
            queryTokens.isNotEmpty() && queryTokens.all { valLower.contains(it) || summaryLower.contains(it) } -> 0.65
            queryTokens.isNotEmpty() && queryTokens.any { valLower.contains(it) || summaryLower.contains(it) || tagsJoined.contains(it) } -> 0.45
            else -> 0.10
        }

        val importanceScore = entry.importance.toDouble().coerceIn(0.0, 1.0)
        val confidenceScore = entry.confidence.toDouble().coerceIn(0.0, 1.0)

        // Time decay: 7 days half-life
        val ageSeconds = (now.epochSecond - entry.updatedAt.epochSecond).coerceAtLeast(0)
        val recencyScore = 1.0 / (1.0 + (ageSeconds / 604800.0))

        val typeBonus = if (targetType == null || entry.type == targetType) 1.0 else 0.4

        // Multi-axis weighted scoring function
        val totalScore = (matchScore * 0.35) +
                (importanceScore * 0.25) +
                (confidenceScore * 0.20) +
                (recencyScore * 0.10) +
                (typeBonus * 0.10)

        return ScoredMemory(
            entry = entry,
            totalScore = totalScore,
            matchScore = matchScore,
            importanceScore = importanceScore,
            confidenceScore = confidenceScore,
            recencyScore = recencyScore,
            typeBonus = typeBonus
        )
    }

    fun rank(
        entries: List<MemoryEntry>,
        query: String?,
        targetType: MemoryType? = null,
        now: Instant = Instant.now(),
        limit: Int = 20
    ): List<MemoryEntry> {
        return entries
            .map { score(it, query, targetType, now) }
            .sortedByDescending { it.totalScore }
            .take(limit)
            .map { it.entry }
    }

    data class ScoredBrainKnowledge(
        val entry: BrainKnowledgeEntry,
        val totalScore: Double,
        val matchScore: Double,
        val importanceScore: Double,
        val confidenceScore: Double,
        val recencyScore: Double,
        val accessScore: Double,
        val typeBonus: Double,
        val taskScore: Double,
    )

    fun scoreKnowledge(
        entry: BrainKnowledgeEntry,
        query: String?,
        taskId: String? = null,
        targetKnowledgeTypes: Set<BrainKnowledgeType>? = null,
        now: Instant = Instant.now()
    ): ScoredBrainKnowledge {
        val queryTokens = query
            ?.lowercase(Locale.ROOT)
            ?.split(Regex("[\\s,._\\-?]+"))
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val keyLower = entry.key.lowercase(Locale.ROOT)
        val contentLower = entry.content.lowercase(Locale.ROOT)
        val summaryLower = entry.summary?.lowercase(Locale.ROOT) ?: ""
        val tagsJoined = entry.tags.joinToString(" ").lowercase(Locale.ROOT)
        val cleanQuery = query?.trim()?.lowercase(Locale.ROOT) ?: ""

        val matchScore: Double = when {
            cleanQuery.isBlank() -> 0.50
            keyLower == cleanQuery -> 1.00
            queryTokens.isNotEmpty() && queryTokens.all { keyLower.contains(it) } -> 0.95
            queryTokens.isNotEmpty() && queryTokens.any { keyLower.contains(it) } -> 0.80
            queryTokens.isNotEmpty() && queryTokens.all { contentLower.contains(it) || summaryLower.contains(it) } -> 0.65
            queryTokens.isNotEmpty() && queryTokens.any { contentLower.contains(it) || summaryLower.contains(it) || tagsJoined.contains(it) } -> 0.45
            else -> 0.10
        }

        val importanceScore = entry.importance.toDouble().coerceIn(0.0, 1.0)
        val confidenceScore = entry.confidence.toDouble().coerceIn(0.0, 1.0)

        // Time decay: 7 days half-life
        val ageSeconds = (now.epochSecond - entry.updatedAt.epochSecond).coerceAtLeast(0)
        val recencyScore = 1.0 / (1.0 + (ageSeconds / 604800.0))

        val accessAgeSeconds = (now.epochSecond - entry.lastAccessedAt.epochSecond).coerceAtLeast(0)
        val accessScore = 1.0 / (1.0 + (accessAgeSeconds / 604800.0))

        val typeBonus: Double = if (targetKnowledgeTypes != null) {
            if (entry.knowledgeType in targetKnowledgeTypes) 1.00 else 0.40
        } else {
            when (entry.knowledgeType) {
                BrainKnowledgeType.CONSTRAINT -> 1.00
                BrainKnowledgeType.DECISION -> 0.95
                BrainKnowledgeType.SOLUTION -> 0.90
                BrainKnowledgeType.FAILURE -> 0.85
                BrainKnowledgeType.TASK -> 0.80
                BrainKnowledgeType.FACT -> 0.75
                BrainKnowledgeType.PREFERENCE -> 0.70
                BrainKnowledgeType.DISCOVERY -> 0.65
                BrainKnowledgeType.PROGRESS -> 0.60
                BrainKnowledgeType.WORKSPACE_STATE -> 0.55
            }
        }

        val taskScore: Double = if (taskId != null) {
            when {
                entry.taskId == taskId -> 1.00
                entry.taskId == null -> 0.70
                else -> 0.40
            }
        } else {
            if (entry.taskId == null) 1.00 else 0.70
        }

        // Multi-axis deterministic weighted scoring function
        val totalScore = (matchScore * 0.30) +
                (importanceScore * 0.20) +
                (typeBonus * 0.15) +
                (confidenceScore * 0.15) +
                (recencyScore * 0.08) +
                (taskScore * 0.08) +
                (accessScore * 0.04)

        return ScoredBrainKnowledge(
            entry = entry,
            totalScore = totalScore,
            matchScore = matchScore,
            importanceScore = importanceScore,
            confidenceScore = confidenceScore,
            recencyScore = recencyScore,
            accessScore = accessScore,
            typeBonus = typeBonus,
            taskScore = taskScore
        )
    }

    fun rankKnowledge(
        entries: List<BrainKnowledgeEntry>,
        query: String?,
        taskId: String? = null,
        targetKnowledgeTypes: Set<BrainKnowledgeType>? = null,
        now: Instant = Instant.now(),
        limit: Int = 20
    ): List<BrainKnowledgeEntry> {
        val comparator = compareByDescending<ScoredBrainKnowledge> { it.totalScore }
            .thenByDescending { it.entry.importance }
            .thenByDescending { it.entry.updatedAt }
            .thenByDescending { it.entry.createdAt }
            .thenBy { it.entry.id }

        return entries
            .map { scoreKnowledge(it, query, taskId, targetKnowledgeTypes, now) }
            .sortedWith(comparator)
            .take(limit)
            .map { it.entry }
    }
}
