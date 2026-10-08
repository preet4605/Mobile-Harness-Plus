package com.jarves.mh.model

import com.jarves.mh.network.DiscoveredModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffortChoiceTest {

    private val gpt6 = listOf(
        DiscoveredModel("gpt-6-astra", "GPT-6-Astra", reasoningEfforts = listOf("low", "max", "ultra")),
    )

    @Test
    fun deepSeekHarnessHasNoEffortSetting() {
        assertNull(effortLevelsFor(AgentKind.DEEPSEEK_HARNESS, emptyList()))
        assertNull(normalizeEffortChoice(AgentKind.DEEPSEEK_HARNESS, "high", emptyList()))
    }

    @Test
    fun levelsComeFromEachAgent() {
        assertEquals(listOf("default", "low", "medium", "high", "max"), effortLevelsFor(AgentKind.CLAUDE_CODE, emptyList()))
        assertEquals(listOf("low", "medium", "high"), effortLevelsFor(AgentKind.ANTIGRAVITY, emptyList()))
        assertEquals(listOf("default", "low", "max", "ultra"), effortLevelsFor(AgentKind.CODEX, listOf("low", "max", "ultra")))
        assertEquals(listOf("default"), effortLevelsFor(AgentKind.CODEX, emptyList()))
    }

    @Test
    fun argumentsAreNormalisedAndCodexDefaultIsBlank() {
        assertEquals("max", normalizeEffortChoice(AgentKind.CLAUDE_CODE, " MAX ", emptyList()))
        assertEquals("", normalizeEffortChoice(AgentKind.CODEX, "default", listOf("low")))
        assertEquals("max", normalizeEffortChoice(AgentKind.CODEX, "max", listOf("low", "max")))
    }

    @Test
    fun unknownLevelsAreRefusedForEveryAgent() {
        assertNull(normalizeEffortChoice(AgentKind.CLAUDE_CODE, "turbo", emptyList()))
        assertNull(normalizeEffortChoice(AgentKind.ANTIGRAVITY, "max", emptyList()))
        // Codex only accepts what the selected model lists; with no catalog only "default" is valid.
        assertNull(normalizeEffortChoice(AgentKind.CODEX, "max", listOf("low")))
        assertNull(normalizeEffortChoice(AgentKind.CODEX, "high", emptyList()))
    }

    @Test
    fun labelsReadNaturally() {
        assertEquals("Default", effortLabel(AgentKind.CODEX, ""))
        assertEquals("Default", effortLabel(AgentKind.CODEX, "default"))
        assertEquals("XHigh", effortLabel(AgentKind.CODEX, "xhigh"))
        assertEquals("Max", effortLabel(AgentKind.CODEX, "max"))
        assertEquals("Standard", effortLabel(AgentKind.CLAUDE_CODE, "default"))
    }

    @Test
    fun modelChoiceIsCheckedAgainstTheKnownList() {
        val known = gpt6.map { it.id }
        assertEquals(ModelChoiceCheck.APPLIED, checkModelChoice("GPT-6-ASTRA", known))
        assertEquals(ModelChoiceCheck.REJECTED, checkModelChoice("gpt-9-unknown", known))
        assertEquals(ModelChoiceCheck.UNCHECKED, checkModelChoice("my-custom-model", emptyList()))
        assertEquals(ModelChoiceCheck.REJECTED, checkModelChoice("   ", known))
    }
}
