package com.jarves.mh.data

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
}
