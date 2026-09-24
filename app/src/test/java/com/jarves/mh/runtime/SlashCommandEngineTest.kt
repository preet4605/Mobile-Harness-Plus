package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandEngineTest {

    @Test
    fun parseCommand_returnsNullForInvalidOrEmptyInputs() {
        assertNull(SlashCommandEngine.parseCommand(""))
        assertNull(SlashCommandEngine.parseCommand("    "))
        assertNull(SlashCommandEngine.parseCommand("/"))
        assertNull(SlashCommandEngine.parseCommand("/   "))
        assertNull(SlashCommandEngine.parseCommand("hello world"))
        assertNull(SlashCommandEngine.parseCommand("Please /plan this"))
        assertNull(SlashCommandEngine.parseCommand("/nonexistentCommand"))
    }

    @Test
    fun parseCommand_parsesCommandsCaseInsensitively() {
        val result1 = SlashCommandEngine.parseCommand("/plan Build user authentication")
        assertNotNull(result1)
        assertEquals("plan", result1!!.first.name)
        assertEquals("Build user authentication", result1.second)

        val result2 = SlashCommandEngine.parseCommand("/PLAN   Build user authentication   ")
        assertNotNull(result2)
        assertEquals("plan", result2!!.first.name)
        assertEquals("Build user authentication", result2.second)

        val result3 = SlashCommandEngine.parseCommand("/HeLp")
        assertNotNull(result3)
        assertEquals("help", result3!!.first.name)
        assertEquals("", result3.second)
    }

    @Test
    fun tokenizeArgs_handlesQuotesAndSpacesCorrectly() {
        val tokens = SlashCommandEngine.tokenizeArgs(""" "project name" 'mobile harness' regularArg """)
        assertEquals(listOf("project name", "mobile harness", "regularArg"), tokens)

        val emptyTokens = SlashCommandEngine.tokenizeArgs("    ")
        assertTrue(emptyTokens.isEmpty())

        val unquoted = SlashCommandEngine.tokenizeArgs("add user_key some_value")
        assertEquals(listOf("add", "user_key", "some_value"), unquoted)
    }

    @Test
    fun filterCommands_handlesWhitespaceAndRanksPrefixMatches() {
        val filtered = SlashCommandEngine.filterCommands("  /pl  ", AgentKind.ANTIGRAVITY)
        assertTrue(filtered.isNotEmpty())
        assertEquals("plan", filtered.first().name)

        val allAgy = SlashCommandEngine.filterCommands("/", AgentKind.ANTIGRAVITY)
        val allDsh = SlashCommandEngine.filterCommands("/", AgentKind.DEEPSEEK_HARNESS)

        // Browser command is Antigravity only
        assertTrue(allAgy.any { it.name == "browser" })
        assertTrue(allDsh.none { it.name == "browser" })

        // Status and skills exist
        assertTrue(allAgy.any { it.name == "status" })
        assertTrue(allAgy.any { it.name == "skills" })
    }

    @Test
    fun buildPromptForCommand_formatsCorrectlyWithOrWithoutArgs() {
        val planCmd = SlashCommandEngine.ALL_SLASH_COMMANDS.first { it.name == "plan" }
        val promptWithArgs = SlashCommandEngine.buildPromptForCommand(planCmd, "Refactor database", AgentKind.ANTIGRAVITY)
        assertTrue(promptWithArgs.contains("[WORKFLOW: IMPLEMENTATION PLAN]"))
        assertTrue(promptWithArgs.contains("Refactor database"))

        val goalCmd = SlashCommandEngine.ALL_SLASH_COMMANDS.first { it.name == "goal" }
        val blankGoalPrompt = SlashCommandEngine.buildPromptForCommand(goalCmd, "", AgentKind.ANTIGRAVITY)
        assertTrue(blankGoalPrompt.contains("Autonomous Goal:"))
        assertTrue(blankGoalPrompt.contains("Achieve and verify the primary task in this workspace"))

        val browserCmd = SlashCommandEngine.ALL_SLASH_COMMANDS.first { it.name == "browser" }
        val browserPrompt = SlashCommandEngine.buildPromptForCommand(browserCmd, "https://developer.android.com", AgentKind.ANTIGRAVITY)
        assertTrue(browserPrompt.contains("[WORKFLOW: WEB RESEARCH]"))
        assertTrue(browserPrompt.contains("https://developer.android.com"))
    }
}
