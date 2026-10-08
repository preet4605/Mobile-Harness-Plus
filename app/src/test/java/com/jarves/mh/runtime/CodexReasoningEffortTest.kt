package com.jarves.mh.runtime

import com.jarves.mh.model.codexReasoningEffortOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexReasoningEffortTest {

    @Test
    fun onlyLevelsCodexAcceptsAreKept() {
        assertEquals("high", codexReasoningEffortOrNull("high"))
        assertEquals("xhigh", codexReasoningEffortOrNull(" XHigh "))
        assertNull(codexReasoningEffortOrNull(""))
        assertNull(codexReasoningEffortOrNull(null))
        assertNull(codexReasoningEffortOrNull("max"))
    }

    @Test
    fun defaultLaunchLeavesTheEffortToCodex() {
        val args = CodexLaunchBuilder.command(CodexRoute.ChatGptLogin("gpt-5"), "/workspace", "/tmp/out.txt")
        assertFalse(args.any { it.startsWith("model_reasoning_effort=") })
    }

    @Test
    fun chosenEffortIsPassedAsAConfigOverrideBeforeThePromptMarker() {
        val args = CodexLaunchBuilder.command(
            CodexRoute.ChatGptLogin("gpt-5"),
            "/workspace",
            "/tmp/out.txt",
            reasoningEffort = "high",
        )
        val index = args.indexOf("model_reasoning_effort=\"high\"")
        assertTrue(index > 0)
        assertEquals("-c", args[index - 1])
        assertEquals("-", args.last())
    }
}
