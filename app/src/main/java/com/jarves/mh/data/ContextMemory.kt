package com.jarves.mh.data

import java.time.Instant
import java.util.UUID

enum class MemorySource { AUTO, USER }

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val key: String,
    val value: String,
    val source: MemorySource,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class ContextMemory(
    val projectId: String,
    val entries: List<MemoryEntry> = emptyList(),
    val updatedAt: Instant = Instant.now(),
)

/**
 * Formats persistent memory entries into a structured XML block
 * for injection into LLM prompts across all runtime bridges.
 */
fun renderMemoryBlock(memory: ContextMemory): String {
    if (memory.entries.isEmpty()) return ""
    return buildString {
        appendLine("<persistent_memory>")
        appendLine("The following facts were remembered from previous sessions. They remain true across model and harness switches:")
        memory.entries.forEach { entry ->
            appendLine("- ${entry.key}: ${entry.value}")
        }
        appendLine("</persistent_memory>")
    }
}
