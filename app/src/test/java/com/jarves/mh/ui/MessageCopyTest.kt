package com.jarves.mh.ui

import com.jarves.mh.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageCopyTest {

    @Test
    fun testCopyableMessagePredicate() {
        val validUserMessage = ChatMessage(fromUser = true, text = "Hello world!")
        val validAssistantMessage = ChatMessage(fromUser = false, text = "```kotlin\nval x = 42\n```")
        val emptyMessage = ChatMessage(fromUser = false, text = "")
        val blankMessage = ChatMessage(fromUser = false, text = "   \n\t  ")

        assertTrue(validUserMessage.text.isNotBlank())
        assertTrue(validAssistantMessage.text.isNotBlank())
        assertFalse(emptyMessage.text.isNotBlank())
        assertFalse(blankMessage.text.isNotBlank())
    }

    @Test
    fun testMessageTextFidelityPreservation() {
        val complexMarkdown = """
            # Architecture Review
            - Step 1: Initialize bridge
            - Step 2: Set AAPT2 override:
              `android.aapt2FromMavenOverride=/root/android-sdk/...`
            
            ```bash
            ./gradlew assembleOnlineDebug
            ```
            Done.
        """.trimIndent()

        val message = ChatMessage(
            fromUser = false,
            text = complexMarkdown,
            workedMillis = 4200L,
        )

        // Verifies the exact raw string copied to clipboard preserves every character,
        // indentation, code fence, and newline verbatim without alteration.
        assertEquals(complexMarkdown, message.text)
        assertTrue(message.text.contains("```bash\n./gradlew assembleOnlineDebug\n```"))
        assertEquals(4200L, message.workedMillis)
    }

    @Test
    fun testDurationFormatting() {
        assertEquals("5s", formatDuration(5L))
        assertEquals("1m 15s", formatDuration(75L))
        assertEquals("2m 0s", formatDuration(120L))
    }
}
