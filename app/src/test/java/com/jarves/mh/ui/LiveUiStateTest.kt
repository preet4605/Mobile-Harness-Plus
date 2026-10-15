package com.jarves.mh.ui

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.SessionTokenMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveUiStateTest {
    private val base = AppUiState(activeChatId = "chat-1", isRunning = true)

    @Test fun streamingChangesLeaveTheShellEqual() {
        val streamed = base.copy(
            messages = base.messages + ChatMessage(fromUser = false, text = "partial reply"),
            liveProcess = listOf(ActivityItem("Read", "Main.kt")),
            liveThinking = true,
            workSegmentStartedAtMillis = 123L,
            tokenMetrics = SessionTokenMetrics(promptTokens = 10),
            projectTerminalLiveOutput = "npm install…",
        )
        assertEquals(base.withoutLiveFields(), streamed.withoutLiveFields())
        assertNotEquals(LiveUiState.of(base), LiveUiState.of(streamed))
    }

    @Test fun otherChangesReachTheShell() {
        assertNotEquals(base.withoutLiveFields(), base.copy(isRunning = false).withoutLiveFields())
        assertNotEquals(base.withoutLiveFields(), base.copy(activeChatId = "chat-2").withoutLiveFields())
    }

    @Test fun liveStateKeepsTheChatItsMessagesBelongTo() {
        val live = LiveUiState.of(base.copy(activeChatId = "chat-2", messages = listOf(ChatMessage(fromUser = true, text = "hi"))))
        assertEquals("chat-2", live.activeChatId)
        assertEquals("hi", live.messages.single().text)
    }

    @Test fun terminalBlocksStackBackIntoTheSameText() {
        val text = (1..200).joinToString("\n") { "line $it" } + "\n\nend"
        val blocks = terminalOutputBlocks(text, linesPerBlock = 64)
        assertEquals(text, blocks.joinToString("\n"))
        assertEquals(4, blocks.size)
        // Appending changes only the last block.
        val longer = terminalOutputBlocks(text + " more", linesPerBlock = 64)
        assertEquals(blocks.dropLast(1), longer.dropLast(1))
    }

    @Test fun terminalBlocksOfShortOutputIsOneBlock() {
        assertEquals(listOf("one\ntwo"), terminalOutputBlocks("one\ntwo"))
        assertEquals(listOf(""), terminalOutputBlocks(""))
    }
}
