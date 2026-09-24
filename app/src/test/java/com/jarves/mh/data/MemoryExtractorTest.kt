package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {

    @Test
    fun `returns empty list when no assistant messages exist`() {
        val messages = listOf(
            ChatMessage(fromUser = true, text = "Hello AI"),
            ChatMessage(fromUser = true, text = "Can you help me?"),
        )
        val extracted = MemoryExtractor.extractMemories(messages)
        assertTrue(extracted.isEmpty())
    }

    @Test
    fun `extracts structured bullet points from assistant response`() {
        val messages = listOf(
            ChatMessage(fromUser = true, text = "What is this repo?"),
            ChatMessage(
                fromUser = false,
                text = """
                    Here is the project overview:
                    - Language: Kotlin
                    - Framework: Jetpack Compose + Material 3
                    - Build system: Gradle 8.14.3, AGP 8.11.0
                    - Architecture: MVI with StateFlow
                    - Database: Room SQLite
                    - Testing: JUnit 4 + MockK
                    - Goal: Build a fast Android coding assistant
                """.trimIndent(),
            ),
        )
        val extracted = MemoryExtractor.extractMemories(messages).toMap()

        assertEquals("Kotlin", extracted["project-language"])
        assertEquals("Jetpack Compose + Material 3", extracted["project-framework"])
        assertEquals("Gradle 8.14.3, AGP 8.11.0", extracted["build-system"])
        assertEquals("MVI with StateFlow", extracted["architecture"])
        assertEquals("Room SQLite", extracted["database"])
        assertEquals("JUnit 4 + MockK", extracted["testing-framework"])
        assertEquals("Build a fast Android coding assistant", extracted["project-goal"])
    }

    @Test
    fun `extracts project goal from descriptive sentence`() {
        val messages = listOf(
            ChatMessage(
                fromUser = false,
                text = "This project is an advanced AI development harness running natively on Android devices.",
            ),
        )
        val extracted = MemoryExtractor.extractMemories(messages).toMap()
        assertEquals(
            "advanced AI development harness running natively on Android devices",
            extracted["project-goal"],
        )
    }

    @Test
    fun `extracts language and framework from keywords when not formatted as bullets`() {
        val messages = listOf(
            ChatMessage(
                fromUser = false,
                text = "We are using Kotlin and Jetpack Compose for the mobile client, built with Gradle.",
            ),
        )
        val extracted = MemoryExtractor.extractMemories(messages).toMap()
        assertEquals("Kotlin", extracted["project-language"])
        assertEquals("Jetpack Compose", extracted["project-framework"])
        assertEquals("Gradle", extracted["build-system"])
    }
}
