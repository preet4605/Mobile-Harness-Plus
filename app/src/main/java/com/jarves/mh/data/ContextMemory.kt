package com.jarves.mh.data

import java.time.Instant
import java.util.UUID

enum class MemorySource {
    USER,
    USER_PROVIDED,
    AUTO,
    TOOL_VERIFIED,
    PROJECT_OBSERVED,
    AGENT_INFERRED;

    val isUser: Boolean get() = this == USER || this == USER_PROVIDED
    val isAuto: Boolean get() = !isUser
}

enum class MemoryScope {
    GLOBAL,
    PROJECT,
    WORKSPACE,
    SESSION,
    TASK,
    SUBAGENT,
}

enum class MemoryType {
    WORKING,
    EPISODIC,
    PROJECT,
    DECISION,
    TASK,
}

enum class MemoryStatus {
    ACTIVE,
    SUPERSEDED,
    COMPLETED,
    ARCHIVED,
    FAILED,
    OBSOLETE,
}

data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String = "",
    val sessionId: String? = null,
    val scope: MemoryScope = MemoryScope.PROJECT,
    val type: MemoryType = MemoryType.PROJECT,
    val key: String,
    val value: String,
    val summary: String? = null,
    val importance: Float = 0.5f,
    val confidence: Float = 0.8f,
    val source: MemorySource = MemorySource.AUTO,
    val sourceReference: String? = null,
    val status: MemoryStatus = MemoryStatus.ACTIVE,
    val version: Int = 1,
    val supersededBy: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val lastAccessedAt: Instant = Instant.now(),
)

data class TaskCheckpoint(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val sessionId: String? = null,
    val goal: String = "",
    val plan: List<String> = emptyList(),
    val currentStep: String? = null,
    val completedSteps: List<String> = emptyList(),
    val pendingSteps: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
    val recentActions: List<String> = emptyList(),
    val currentFiles: List<String> = emptyList(),
    val lastError: String? = null,
    val lastSuccess: String? = null,
    val nextAction: String? = null,
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
    val activeEntries = memory.entries.filter { it.status == MemoryStatus.ACTIVE }
    if (activeEntries.isEmpty()) return ""
    return buildString {
        appendLine("<persistent_memory>")
        appendLine("The following facts were remembered from previous sessions. They remain true across model and harness switches:")
        activeEntries.forEach { entry ->
            appendLine("- ${entry.key}: ${entry.value}")
        }
        appendLine("</persistent_memory>")
    }
}
