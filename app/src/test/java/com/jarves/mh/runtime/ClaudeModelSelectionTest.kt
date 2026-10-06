package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS
import com.jarves.mh.model.ClaudeAuthMode
import com.jarves.mh.model.ClaudeThinkingLevel
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeModelSelectionTest {

    @Test
    fun claudeSubscriptionModelsContainExpectedAliases() {
        val modelIds = CLAUDE_SUBSCRIPTION_MODELS.map { it.id }
        assertEquals(listOf("default", "sonnet", "opus", "haiku", "fable"), modelIds)
    }

    @Test
    fun claudeThinkingLevelMappingAndParsing() {
        // Safe parsing
        assertEquals(ClaudeThinkingLevel.DEFAULT, ClaudeThinkingLevel.fromStored(null))
        assertEquals(ClaudeThinkingLevel.DEFAULT, ClaudeThinkingLevel.fromStored(""))
        assertEquals(ClaudeThinkingLevel.DEFAULT, ClaudeThinkingLevel.fromStored("default"))
        assertEquals(ClaudeThinkingLevel.DEFAULT, ClaudeThinkingLevel.fromStored("standard"))
        assertEquals(ClaudeThinkingLevel.DEFAULT, ClaudeThinkingLevel.fromStored("unknown_xyz"))

        assertEquals(ClaudeThinkingLevel.LOW, ClaudeThinkingLevel.fromStored("low"))
        assertEquals(ClaudeThinkingLevel.LOW, ClaudeThinkingLevel.fromStored("LOW"))
        assertEquals(ClaudeThinkingLevel.MEDIUM, ClaudeThinkingLevel.fromStored("medium"))
        assertEquals(ClaudeThinkingLevel.HIGH, ClaudeThinkingLevel.fromStored("high"))
        assertEquals(ClaudeThinkingLevel.MAX, ClaudeThinkingLevel.fromStored("max"))
        assertEquals(ClaudeThinkingLevel.MAX, ClaudeThinkingLevel.fromStored("xhigh"))

        // CLI arguments mapping
        assertNull(ClaudeThinkingLevel.DEFAULT.effortArg)
        assertEquals("low", ClaudeThinkingLevel.LOW.effortArg)
        assertEquals("medium", ClaudeThinkingLevel.MEDIUM.effortArg)
        assertEquals("high", ClaudeThinkingLevel.HIGH.effortArg)
        assertEquals("max", ClaudeThinkingLevel.MAX.effortArg)

        // Storage and Display accessors
        assertEquals("high", ClaudeThinkingLevel.HIGH.storageValue)
        assertEquals("High", ClaudeThinkingLevel.HIGH.displayName)
    }

    @Test
    fun buildClaudeCommandWithNativeAliasesAndEffortLevels() {
        val aliases = listOf("default", "sonnet", "opus", "haiku", "fable")
        for (alias in aliases) {
            val cmd = ClaudeRuntimeBridge.buildClaudeCommand(
                executable = "/usr/local/bin/claude",
                model = alias,
                effort = null,
            )
            assertFalse("Command must never contain --bare", cmd.contains("--bare"))
            assertTrue("Command must contain --model", cmd.contains("--model"))
            val modelIdx = cmd.indexOf("--model")
            assertEquals(alias, cmd[modelIdx + 1])
            assertFalse("Command should not contain --effort when null", cmd.contains("--effort"))
        }

        // Test with effort levels
        val effortLevels = listOf("low", "medium", "high", "max")
        for (level in effortLevels) {
            val cmd = ClaudeRuntimeBridge.buildClaudeCommand(
                executable = "/usr/local/bin/claude",
                model = "sonnet",
                effort = level,
            )
            assertFalse("Command must never contain --bare", cmd.contains("--bare"))
            assertTrue("Command must contain --effort", cmd.contains("--effort"))
            val effortIdx = cmd.indexOf("--effort")
            assertEquals(level, cmd[effortIdx + 1])
        }

        // Test with invalid effort level (e.g. off / invalid) -> should be omitted
        val cmdWithInvalid = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = "/usr/local/bin/claude",
            model = "sonnet",
            effort = "off",
        )
        assertFalse("Invalid effort must not be passed to CLI", cmdWithInvalid.contains("--effort"))
    }

    @Test
    fun runtimeLaunchConfigSetsEffortEnvironmentVariable() {
        val profileWithEffort = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
            claudeThinkingLevel = "high",
        )
        val config = RuntimeLaunchConfigBuilder.build(profileWithEffort)
        assertEquals("high", config.environment["CLAUDE_CODE_EFFORT_LEVEL"])
        assertFalse(config.environment.containsKey("ANTHROPIC_API_KEY"))
        assertFalse(config.environment.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse(config.environment.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))

        val profileDefault = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
            claudeThinkingLevel = "default",
        )
        val configDefault = RuntimeLaunchConfigBuilder.build(profileDefault)
        assertFalse(configDefault.environment.containsKey("CLAUDE_CODE_EFFORT_LEVEL"))
    }

    @Test
    fun slashCommandEngineIncludesModelAndThinkingForClaude() {
        val claudeCommands = SlashCommandEngine.filterCommands("", AgentKind.CLAUDE_CODE)
        val names = claudeCommands.map { it.name }
        assertTrue("Claude should have /model command", names.contains("model"))
        assertTrue("Claude should have /thinking command", names.contains("thinking"))
    }

    @Test
    fun providerRuntimeErrorDetectorCatchesModelUnavailableErrors() {
        val errorText = "Error: Opus model is not available on your tier. Upgrade to Claude Pro or higher."
        val detected = ProviderRuntimeErrorDetector.detect(errorText)
        assertNotNull(detected)
        assertTrue(detected!!.contains("Opus model is not available on your tier"))
    }
}
