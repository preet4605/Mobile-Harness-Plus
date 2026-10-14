package com.jarves.mh.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeIdleCpuTest {
    private fun source(name: String) = File("src/main/java/com/jarves/mh/runtime/$name").readText()

    @Test fun idlePollBacksOffAndResets() = runBlocking {
        val poll = IdlePoll(minMs = 1, maxMs = 4)
        val waits = mutableListOf<Long>()
        repeat(4) {
            val start = System.nanoTime()
            poll.await()
            waits += (System.nanoTime() - start) / 1_000_000
        }
        assertTrue("waits grow toward the cap: $waits", waits.last() >= 3)
        poll.reset()
        val start = System.nanoTime()
        poll.await()
        assertTrue((System.nanoTime() - start) / 1_000_000 < 50)
    }

    @Test fun harnessReadLoopsBackOffWhenIdle() {
        for (name in listOf("ClaudeRuntimeBridge.kt", "DshRuntimeBridge.kt", "AntigravityRuntimeBridge.kt", "CodexTurnRunner.kt")) {
            val code = source(name)
            assertTrue("$name idles with backoff", code.contains("idle.await()"))
            assertTrue("$name resets after output", code.contains("idle.reset()"))
        }
    }

    @Test fun claudeThinkingChecksThrottleBeforeTextWork() {
        val code = source("ClaudeRuntimeBridge.kt")
        assertFalse(code.contains("emitReasoningSummary(sessionId, streamedThinking.toString())\n"))
        val body = code.substringAfter("private suspend fun emitReasoningSummary(").substringBefore("private suspend fun emitReasoningProgress(")
        assertTrue(body.indexOf("lastThinkingUpdateAt >= 150")  < body.indexOf("sanitizeForDisplay("))
    }

    @Test fun sanitizeStillMasksSecrets() {
        assertEquals("key sk-•••• and api_key: ••••", ClaudeRuntimeBridge.sanitizeForDisplay("key  sk-abcdefghij and api_key=xyz"))
    }
}
