package com.jarves.mh.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputTailTest {
    @Test fun matchesWholeBufferSanitizeForSplitEscapeSequences() {
        val raw = "a\u001B[31mred\u001B[0m b\u001B]0;title\u0007c\u001B(Bd\u0001e\n"
        // Every split point, including inside each escape sequence.
        for (split in 0..raw.length) {
            val tail = TerminalOutputTail(1_000)
            tail.append(raw.substring(0, split))
            tail.append(raw.substring(split))
            tail.finish()
            assertEquals("split at $split", sanitizeTerminalOutput(raw), tail.text())
        }
    }

    @Test fun keepsOnlyTheTail() {
        val tail = TerminalOutputTail(100)
        repeat(1_000) { tail.append("line $it\n") }
        val text = tail.text()
        assertEquals(100, text.length)
        assertTrue(text.endsWith("line 999\n"))
        assertEquals("999\n", tail.recent(4))
    }

    @Test fun completeSequenceAtEndIsNotHeldBack() {
        val tail = TerminalOutputTail(100)
        tail.append("Continue? [Y/n] \u001B[0m")
        assertEquals("Continue? [Y/n] ", tail.text())
    }

    @Test fun terminalLoopsNoLongerResanitizeTheWholeBuffer() {
        val source = listOf("MainViewModel.kt", "QuickTerminalController.kt")
            .joinToString("\n") { File("src/main/java/com/jarves/mh/ui/$it").readText() }
        assertFalse(source.contains("sanitizeTerminalOutput(streamed.toString())"))
        assertFalse(source.contains("sanitizeTerminalOutput(output.toString().substringBefore(marker))"))
        assertFalse("terminal loops must back off when idle", source.contains("Thread.sleep(50)"))
    }
}
