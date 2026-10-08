package com.jarves.mh.runtime

import com.jarves.mh.model.codexEffortToLaunch
import com.jarves.mh.model.codexReasoningEffortOrNull
import com.jarves.mh.network.DiscoveredModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexReasoningEffortTest {

    @Test
    fun storedValueMustLookLikeALevelName() {
        assertEquals("high", codexReasoningEffortOrNull("high"))
        assertEquals("xhigh", codexReasoningEffortOrNull(" XHigh "))
        assertEquals("max", codexReasoningEffortOrNull("max"))
        assertNull(codexReasoningEffortOrNull(""))
        assertNull(codexReasoningEffortOrNull(null))
        assertNull(codexReasoningEffortOrNull("x"))
        assertNull(codexReasoningEffortOrNull("high; rm -rf /"))
    }

    @Test
    fun levelIsSentOnlyWhenTheSelectedModelListsIt() {
        val catalog = listOf(
            DiscoveredModel("gpt-5.5", "GPT-5.5", reasoningEfforts = listOf("low", "medium", "high", "xhigh")),
            DiscoveredModel("gpt-6-astra", "GPT-6-Astra", reasoningEfforts = listOf("low", "high", "max", "ultra")),
        )
        assertEquals("max", codexEffortToLaunch("max", "gpt-6-astra", catalog))
        assertNull(codexEffortToLaunch("max", "gpt-5.5", catalog))
        assertNull(codexEffortToLaunch("", "gpt-5.5", catalog))
    }

    @Test
    fun withoutCatalogDataTheStoredLevelIsPassedAsIs() {
        // Custom endpoints have no per-model effort list; the stored level is kept as before.
        assertEquals("high", codexEffortToLaunch("high", "my-model", emptyList()))
        assertNull(codexEffortToLaunch("", "my-model", emptyList()))
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
