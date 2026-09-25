package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class ConflictType {
    NONE,
    SAME_KEY_SAME_CONTENT,
    SAME_KEY_DIFFERENT_CONTENT,
    SUPERSEDES,
    COEXISTING_FACTS,
    INCOMPATIBLE_CONSTRAINTS
}

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
        open val conflictType: ConflictType = ConflictType.NONE
        data class NoChange(val existing: MemoryEntry, override val conflictType: ConflictType = ConflictType.NONE) : Resolution()
        data class Deduplicate(val updated: MemoryEntry, override val conflictType: ConflictType = ConflictType.SAME_KEY_SAME_CONTENT) : Resolution()
        data class Supersede(val oldEntryId: String, val newEntry: MemoryEntry, override val conflictType: ConflictType = ConflictType.SUPERSEDES) : Resolution()
        data class RejectedLowerTrust(val existing: MemoryEntry, val rejected: MemoryEntry, override val conflictType: ConflictType = ConflictType.SAME_KEY_DIFFERENT_CONTENT) : Resolution()
        data class InsertNew(val newEntry: MemoryEntry, override val conflictType: ConflictType = ConflictType.NONE) : Resolution()
    }

    sealed class KnowledgeResolution {
        abstract val conflictType: ConflictType

        data class InsertNew(
            val newEntry: BrainKnowledgeEntry,
            override val conflictType: ConflictType = ConflictType.NONE
        ) : KnowledgeResolution()

        data class Deduplicate(
            val updated: BrainKnowledgeEntry,
            override val conflictType: ConflictType = ConflictType.SAME_KEY_SAME_CONTENT
        ) : KnowledgeResolution()

        data class Supersede(
            val oldEntryId: String,
            val newEntry: BrainKnowledgeEntry,
            override val conflictType: ConflictType = ConflictType.SUPERSEDES
        ) : KnowledgeResolution()

        data class Coexisting(
            val newEntry: BrainKnowledgeEntry,
            override val conflictType: ConflictType = ConflictType.COEXISTING_FACTS
        ) : KnowledgeResolution()

        data class RejectedIncompatible(
            val existing: BrainKnowledgeEntry,
            val rejected: BrainKnowledgeEntry,
            val reason: String,
            override val conflictType: ConflictType = ConflictType.INCOMPATIBLE_CONSTRAINTS
        ) : KnowledgeResolution()

        data class RejectedLowerTrust(
            val existing: BrainKnowledgeEntry,
            val rejected: BrainKnowledgeEntry,
            override val conflictType: ConflictType = ConflictType.SAME_KEY_DIFFERENT_CONTENT
        ) : KnowledgeResolution()

        data class NoChange(
            val existing: BrainKnowledgeEntry,
            override val conflictType: ConflictType = ConflictType.NONE
        ) : KnowledgeResolution()
    }

    fun detectConflict(incoming: BrainKnowledgeEntry, existing: BrainKnowledgeEntry): ConflictType {
        val incomingNormKey = normalizeKey(incoming.key)
        val existingNormKey = normalizeKey(existing.key)
        if (incomingNormKey != existingNormKey) {
            return if (incoming.knowledgeType == BrainKnowledgeType.FACT && existing.knowledgeType == BrainKnowledgeType.FACT) {
                ConflictType.COEXISTING_FACTS
            } else {
                ConflictType.NONE
            }
        }

        val incomingNormContent = normalizeValue(incoming.content)
        val existingNormContent = normalizeValue(existing.content)

        if (incomingNormContent == existingNormContent) {
            return ConflictType.SAME_KEY_SAME_CONTENT
        }

        if (incoming.knowledgeType == BrainKnowledgeType.CONSTRAINT && existing.knowledgeType == BrainKnowledgeType.CONSTRAINT) {
            if (trustRank(incoming.source) < trustRank(existing.source)) {
                return ConflictType.INCOMPATIBLE_CONSTRAINTS
            }
        }

        if (incoming.taskId != null && existing.taskId != null && incoming.taskId != existing.taskId) {
            return ConflictType.COEXISTING_FACTS
        }

        if (trustRank(incoming.source) >= trustRank(existing.source)) {
            return ConflictType.SUPERSEDES
        }

        return ConflictType.SAME_KEY_DIFFERENT_CONTENT
    }

    fun resolveKnowledge(
        incoming: BrainKnowledgeEntry,
        existingEntries: List<BrainKnowledgeEntry>,
        now: Instant = Instant.now()
    ): KnowledgeResolution {
        val incomingNormKey = normalizeKey(incoming.key)
        val matchingActive = existingEntries.firstOrNull {
            it.status == MemoryStatus.ACTIVE && normalizeKey(it.key) == incomingNormKey
        }

        if (matchingActive == null) {
            return if (incoming.knowledgeType == BrainKnowledgeType.FACT &&
                existingEntries.any { it.status == MemoryStatus.ACTIVE && it.knowledgeType == BrainKnowledgeType.FACT }
            ) {
                KnowledgeResolution.Coexisting(incoming.copy(updatedAt = now, lastAccessedAt = now), ConflictType.COEXISTING_FACTS)
            } else {
                KnowledgeResolution.InsertNew(incoming.copy(updatedAt = now, lastAccessedAt = now), ConflictType.NONE)
            }
        }

        val conflictType = detectConflict(incoming, matchingActive)

        return when (conflictType) {
            ConflictType.SAME_KEY_SAME_CONTENT -> {
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
                KnowledgeResolution.Deduplicate(deduplicated, ConflictType.SAME_KEY_SAME_CONTENT)
            }
            ConflictType.INCOMPATIBLE_CONSTRAINTS -> {
                KnowledgeResolution.RejectedIncompatible(
                    existing = matchingActive,
                    rejected = incoming,
                    reason = "Incoming constraint from source ${incoming.source} cannot override existing constraint from ${matchingActive.source}",
                    conflictType = ConflictType.INCOMPATIBLE_CONSTRAINTS
                )
            }
            ConflictType.COEXISTING_FACTS -> {
                KnowledgeResolution.Coexisting(
                    newEntry = incoming.copy(updatedAt = now, lastAccessedAt = now),
                    conflictType = ConflictType.COEXISTING_FACTS
                )
            }
            ConflictType.SUPERSEDES -> {
                val replacement = incoming.copy(
                    id = if (incoming.id == matchingActive.id) UUID.randomUUID().toString() else incoming.id,
                    version = matchingActive.version + 1,
                    status = MemoryStatus.ACTIVE,
                    createdAt = incoming.createdAt,
                    updatedAt = now,
                    lastAccessedAt = now
                )
                KnowledgeResolution.Supersede(
                    oldEntryId = matchingActive.id,
                    newEntry = replacement,
                    conflictType = ConflictType.SUPERSEDES
                )
            }
            ConflictType.SAME_KEY_DIFFERENT_CONTENT -> {
                KnowledgeResolution.RejectedLowerTrust(
                    existing = matchingActive,
                    rejected = incoming,
                    conflictType = ConflictType.SAME_KEY_DIFFERENT_CONTENT
                )
            }
            ConflictType.NONE -> {
                KnowledgeResolution.InsertNew(
                    newEntry = incoming.copy(updatedAt = now, lastAccessedAt = now),
                    conflictType = ConflictType.NONE
                )
            }
        }
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

