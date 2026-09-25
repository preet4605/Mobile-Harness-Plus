package com.jarves.mh.data

import java.time.Instant
import java.util.Locale

object MemoryConflictResolver {

    fun normalizeKey(key: String): String =
        key.trim().lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "-").replace(Regex("-+"), "-")

    fun normalizeValue(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun trustRank(source: MemorySource): Int = when (source) {
        MemorySource.TOOL_VERIFIED -> 100
        MemorySource.USER, MemorySource.USER_PROVIDED -> 90
        MemorySource.PROJECT_OBSERVED -> 80
        MemorySource.AUTO -> 60
        MemorySource.AGENT_INFERRED -> 40
    }

    sealed class Resolution {
        data class NoChange(val existing: MemoryEntry) : Resolution()
        data class Deduplicate(val updated: MemoryEntry) : Resolution()
        data class Supersede(val oldEntryId: String, val newEntry: MemoryEntry) : Resolution()
        data class RejectedLowerTrust(val existing: MemoryEntry, val rejected: MemoryEntry) : Resolution()
        data class InsertNew(val newEntry: MemoryEntry) : Resolution()
    }

    fun resolve(
        incoming: MemoryEntry,
        existingEntries: List<MemoryEntry>,
        now: Instant = Instant.now()
    ): Resolution {
        val incomingNormKey = normalizeKey(incoming.key)
        val matchingActive = existingEntries.firstOrNull {
            it.status == MemoryStatus.ACTIVE && normalizeKey(it.key) == incomingNormKey
        }

        if (matchingActive == null) {
            return Resolution.InsertNew(incoming.copy(updatedAt = now, lastAccessedAt = now))
        }

        val matchingNormVal = normalizeValue(matchingActive.value)
        val incomingNormVal = normalizeValue(incoming.value)

        // Case 1: Identical / equivalent value -> Deduplication
        if (matchingNormVal == incomingNormVal) {
            val upgradedSource = if (trustRank(incoming.source) > trustRank(matchingActive.source)) {
                incoming.source
            } else {
                matchingActive.source
            }
            val higherConfidence = maxOf(matchingActive.confidence, incoming.confidence)
            val higherImportance = maxOf(matchingActive.importance, incoming.importance)
            val mergedTags = (matchingActive.tags + incoming.tags).distinct()

            val deduplicated = matchingActive.copy(
                source = upgradedSource,
                confidence = higherConfidence,
                importance = higherImportance,
                tags = mergedTags,
                lastAccessedAt = now,
                updatedAt = now
            )
            return Resolution.Deduplicate(deduplicated)
        }

        // Case 2: Conflicting value for same key
        val incomingTrust = trustRank(incoming.source)
        val existingTrust = trustRank(matchingActive.source)

        return if (incomingTrust >= existingTrust) {
            // Incoming memory wins and supersedes the older memory
            val replacement = incoming.copy(
                id = if (incoming.id == matchingActive.id) java.util.UUID.randomUUID().toString() else incoming.id,
                version = matchingActive.version + 1,
                status = MemoryStatus.ACTIVE,
                createdAt = incoming.createdAt,
                updatedAt = now,
                lastAccessedAt = now
            )
            Resolution.Supersede(oldEntryId = matchingActive.id, newEntry = replacement)
        } else {
            // Existing memory is more trusted (e.g. USER or TOOL_VERIFIED vs AGENT_INFERRED)
            Resolution.RejectedLowerTrust(existing = matchingActive, rejected = incoming)
        }
    }
}
