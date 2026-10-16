package com.jarves.mh.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFullScreenProgramsTest {

    @Test
    fun `agent apps and full-screen tools are named before they run`() {
        listOf("codex", "codex resume", "codex --model gpt-5", "/usr/local/bin/codex", "FOO=1 codex", "claude", "claude \"fix it\"",
            "gemini", "vim README.md", "sudo nano a.txt", "htop", "top", "tmux").forEach { command ->
            assertNotNull(command, fullScreenProgramNotice(command))
        }
        assertTrue(fullScreenProgramNotice("codex")!!.contains("codex exec"))
    }

    @Test
    fun `one-shot commands, info flags and pipes run as before`() {
        listOf("codex exec \"list files\"", "codex login", "codex --version", "claude -p hi", "claude --print hi",
            "claude doctor", "gemini -p hi", "vim --version", "ls -la", "cat a | less", "less README.md", "man ls",
            "echo vim", "git log", "codex exec hi && vim x", "", "   ").forEach { command ->
            assertNull(command, fullScreenProgramNotice(command))
        }
    }
}
