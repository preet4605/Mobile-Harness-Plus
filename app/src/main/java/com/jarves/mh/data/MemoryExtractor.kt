package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import java.util.Locale

object MemoryExtractor {

    private val STRUCTURED_LINE_REGEX = Regex(
        """(?im)^[\s\-\*]*(Language|Framework|Build system|Architecture|Database|Testing|Goal|Project summary):\s*([^\r\n]{2,200})"""
    )

    private val PROJECT_SUMMARY_REGEX = Regex(
        """(?im)(?:The goal of this project is to|This project is an?|This project is|Project goal:)\s+([^\r\n\.]{8,150})"""
    )

    fun extractMemories(messages: List<ChatMessage>): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val assistantTexts = messages
            .filter { !it.fromUser }
            .takeLast(3)
            .map { it.text }

        if (assistantTexts.isEmpty()) return emptyList()

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
}
