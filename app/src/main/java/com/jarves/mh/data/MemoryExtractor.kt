package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import java.time.Instant
import java.util.Locale

object MemoryExtractor {

    private val STRUCTURED_LINE_REGEX = Regex(
        """(?im)^[\s\-\*]*(Language|Framework|Build system|Architecture|Database|Testing|Goal|Project summary):\s*([^\r\n]{2,200})"""
    )

    private val PROJECT_SUMMARY_REGEX = Regex(
        """(?im)(?:The goal of this project is to|This project is an?|This project is|Project goal:)\s+([^\r\n\.]{8,150})"""
    )

    private val USER_TECH_INSTRUCTION_REGEX = Regex(
        """(?im)^\s*(?:Please\s+)?(?:use|switch to|set)\s+(SQLite|Room|Postgres|MySQL|MongoDB|Ktor|Retrofit|Compose|Kotlin|Java|Swift|Flutter)\b(?:\s+(?:for|as)\s+([^\r\n\.]+))?"""
    )

    private val DECISION_REGEX = Regex(
        """(?im)^[\s\-\*]*(?:Decided to|Decision:|We decided to|Selected approach:)\s*([^\r\n]{5,250})"""
    )

    private val FAILURE_REGEX = Regex(
        """(?im)^[\s\-\*]*(?:Error:|Failed to|Failure:|Build failure:|Exception:)\s*([^\r\n]{5,250})"""
    )

    private val FIX_REGEX = Regex(
        """(?im)^[\s\-\*]*(?:Fixed by|Fix:|Resolved by|Solution:)\s*([^\r\n]{5,250})"""
    )

    private val TASK_GOAL_REGEX = Regex(
        """(?im)(?:Current goal|Active task|Goal):\s*([^\r\n\.]{4,200})"""
    )

    private val NEXT_ACTION_REGEX = Regex(
        """(?im)(?:Next action|Next step|Up next):\s*([^\r\n\.]{4,200})"""
    )

    /**
     * Backward-compatible key-value extractor used across the app.
     */
    fun extractMemories(messages: List<ChatMessage>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()

        // Also check explicit user tech instructions
        val userTexts = messages.filter { it.fromUser }.takeLast(3).map { it.text }
        for (text in userTexts) {
            USER_TECH_INSTRUCTION_REGEX.find(text)?.let { match ->
                val tech = match.groupValues[1].trim()
                val target = match.groupValues[2].trim().lowercase(Locale.ROOT)
                val key = when {
                    target.contains("database") || tech.equals("sqlite", ignoreCase = true) || tech.equals("room", ignoreCase = true) -> "database"
                    target.contains("framework") -> "project-framework"
                    target.contains("language") -> "project-language"
                    else -> "database"
                }
                results.add(key to tech)
            }
        }

        val assistantTexts = messages
            .filter { !it.fromUser }
            .takeLast(3)
            .map { it.text }

        if (assistantTexts.isEmpty() && results.isEmpty()) return emptyList()

        for (text in assistantTexts) {
            // 1. Structured lines (e.g. "- Language: Kotlin", "Framework: Jetpack Compose")
            STRUCTURED_LINE_REGEX.findAll(text).forEach { match ->
                val rawKey = match.groupValues[1].trim().lowercase(Locale.ROOT)
                val rawValue = match.groupValues[2].trim()
                val key = when (rawKey) {
                    "language" -> "project-language"
                    "framework" -> "project-framework"
                    "build system" -> "build-system"
                    "architecture" -> "architecture"
                    "database" -> "database"
                    "testing" -> "testing-framework"
                    "goal", "project summary" -> "project-goal"
                    else -> "project-$rawKey"
                }
                if (rawValue.isNotBlank()) {
                    results.add(key to rawValue)
                }
            }

            // 2. Project summary / goal detection
            PROJECT_SUMMARY_REGEX.find(text)?.let { match ->
                val summary = match.groupValues[1].trim()
                if (summary.isNotBlank() && results.none { it.first == "project-goal" }) {
                    results.add("project-goal" to summary)
                }
            }

            // 3. Keyword / stack heuristics
            if (results.none { it.first == "project-language" }) {
                when {
                    text.contains("Kotlin", ignoreCase = false) -> results.add("project-language" to "Kotlin")
                    text.contains("Swift", ignoreCase = false) -> results.add("project-language" to "Swift")
                    text.contains("Rust", ignoreCase = false) -> results.add("project-language" to "Rust")
                    text.contains("TypeScript", ignoreCase = false) -> results.add("project-language" to "TypeScript")
                    text.contains("Python", ignoreCase = false) -> results.add("project-language" to "Python")
                    text.contains("Java ", ignoreCase = false) || text.contains("Java\n") -> results.add("project-language" to "Java")
                }
            }

            if (results.none { it.first == "project-framework" }) {
                when {
                    text.contains("Jetpack Compose", ignoreCase = true) -> results.add("project-framework" to "Jetpack Compose")
                    text.contains("SwiftUI", ignoreCase = true) -> results.add("project-framework" to "SwiftUI")
                    text.contains("React Native", ignoreCase = true) -> results.add("project-framework" to "React Native")
                    text.contains("Flutter", ignoreCase = true) -> results.add("project-framework" to "Flutter")
                    text.contains("Next.js", ignoreCase = true) -> results.add("project-framework" to "Next.js")
                }
            }

            if (results.none { it.first == "build-system" }) {
                when {
                    text.contains("Gradle", ignoreCase = true) -> {
                        val agpMatch = Regex("""AGP\s*(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE).find(text)
                        val gradleMatch = Regex("""Gradle\s*(\d+(?:\.\d+)*)""", RegexOption.IGNORE_CASE).find(text)
                        val desc = if (agpMatch != null && gradleMatch != null) {
                            "Gradle ${gradleMatch.groupValues[1]}, AGP ${agpMatch.groupValues[1]}"
                        } else if (gradleMatch != null) {
                            "Gradle ${gradleMatch.groupValues[1]}"
                        } else {
                            "Gradle"
                        }
                        results.add("build-system" to desc)
                    }
                    text.contains("Cargo", ignoreCase = true) -> results.add("build-system" to "Cargo")
                    text.contains("Maven", ignoreCase = true) -> results.add("build-system" to "Maven")
                }
            }
        }

        // Return distinct by key, keeping the last observed value
        return results.associateBy({ it.first }, { it.second }).toList()
    }

    /**
     * Layered extractor creating enriched MemoryEntry objects across
     * WORKING, EPISODIC, PROJECT, DECISION, and TASK domains.
     */
    fun extractRichMemories(messages: List<ChatMessage>, projectId: String): List<MemoryEntry> {
        val entries = mutableListOf<MemoryEntry>()
        val now = Instant.now()

        // 1. Extract explicit user preferences and constraints
        messages.filter { it.fromUser }.takeLast(5).forEach { msg ->
            USER_TECH_INSTRUCTION_REGEX.find(msg.text)?.let { match ->
                val tech = match.groupValues[1].trim()
                val target = match.groupValues[2].trim().lowercase(Locale.ROOT)
                val key = when {
                    target.contains("database") || tech.equals("sqlite", ignoreCase = true) || tech.equals("room", ignoreCase = true) -> "database"
                    target.contains("framework") -> "project-framework"
                    target.contains("language") -> "project-language"
                    else -> "database"
                }
                entries.add(
                    MemoryEntry(
                        projectId = projectId,
                        scope = MemoryScope.PROJECT,
                        type = MemoryType.PROJECT,
                        key = key,
                        value = tech,
                        importance = 0.9f,
                        confidence = 0.95f,
                        source = MemorySource.USER_PROVIDED,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        // 2. Structured assistant observations
        val kvPairs = extractMemories(messages)
        kvPairs.forEach { (k, v) ->
            if (entries.none { it.key == k }) {
                entries.add(
                    MemoryEntry(
                        projectId = projectId,
                        scope = MemoryScope.PROJECT,
                        type = MemoryType.PROJECT,
                        key = k,
                        value = v,
                        importance = if (k.contains("language") || k.contains("database") || k.contains("framework")) 0.85f else 0.65f,
                        confidence = 0.80f,
                        source = MemorySource.AUTO,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        // 3. Decisions & Episodic events (failures/fixes)
        messages.takeLast(6).forEach { msg ->
            DECISION_REGEX.findAll(msg.text).forEach { match ->
                val decisionText = match.groupValues[1].trim()
                entries.add(
                    MemoryEntry(
                        projectId = projectId,
                        scope = MemoryScope.PROJECT,
                        type = MemoryType.DECISION,
                        key = "decision-${decisionText.take(30).trim().replace(Regex("[^a-zA-Z0-9]"), "-").lowercase(Locale.ROOT)}",
                        value = decisionText,
                        importance = 0.80f,
                        confidence = if (msg.fromUser) 0.90f else 0.70f,
                        source = if (msg.fromUser) MemorySource.USER_PROVIDED else MemorySource.AGENT_INFERRED,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            FAILURE_REGEX.findAll(msg.text).forEach { match ->
                val failText = match.groupValues[1].trim()
                entries.add(
                    MemoryEntry(
                        projectId = projectId,
                        scope = MemoryScope.SESSION,
                        type = MemoryType.EPISODIC,
                        key = "failure-${failText.take(30).trim().replace(Regex("[^a-zA-Z0-9]"), "-").lowercase(Locale.ROOT)}",
                        value = failText,
                        importance = 0.70f,
                        confidence = 0.85f,
                        source = MemorySource.TOOL_VERIFIED,
                        status = MemoryStatus.FAILED,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }

            FIX_REGEX.findAll(msg.text).forEach { match ->
                val fixText = match.groupValues[1].trim()
                entries.add(
                    MemoryEntry(
                        projectId = projectId,
                        scope = MemoryScope.SESSION,
                        type = MemoryType.EPISODIC,
                        key = "fix-${fixText.take(30).trim().replace(Regex("[^a-zA-Z0-9]"), "-").lowercase(Locale.ROOT)}",
                        value = fixText,
                        importance = 0.75f,
                        confidence = 0.85f,
                        source = MemorySource.TOOL_VERIFIED,
                        status = MemoryStatus.COMPLETED,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        }

        return entries.distinctBy { MemoryConflictResolver.normalizeKey(it.key) }
    }

    /**
     * Extracts active task checkpoint from recent conversation history.
     */
    fun extractTaskCheckpoint(messages: List<ChatMessage>, projectId: String): TaskCheckpoint? {
        if (messages.isEmpty()) return null
        val recent = messages.takeLast(10)
        var goal: String? = null
        var nextAction: String? = null

        // Detect user request as goal if simple command
        val lastUser = recent.lastOrNull { it.fromUser }?.text?.trim()
        if (lastUser != null && !lastUser.startsWith("/") && lastUser.length in 5..120) {
            goal = lastUser
        }

        for (msg in recent.reversed()) {
            if (goal == null) {
                TASK_GOAL_REGEX.find(msg.text)?.let {
                    goal = it.groupValues[1].trim()
                }
            }
            if (nextAction == null) {
                NEXT_ACTION_REGEX.find(msg.text)?.let {
                    nextAction = it.groupValues[1].trim()
                }
            }
        }

        if (goal == null && nextAction == null) return null

        return TaskCheckpoint(
            projectId = projectId,
            goal = goal ?: "Current active task",
            currentStep = nextAction,
            nextAction = nextAction,
            updatedAt = Instant.now()
        )
    }
}
