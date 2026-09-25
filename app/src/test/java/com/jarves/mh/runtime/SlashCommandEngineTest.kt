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

        // Status, skills, and rules exist
        assertTrue(allAgy.any { it.name == "status" })
        assertTrue(allAgy.any { it.name == "skills" })
        assertTrue(allAgy.any { it.name == "rules" })
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

    @Test
    fun filterSkills_matchesQueryAndRanksPrefixMatchesFirst() {
        val skills = listOf(
            com.jarves.mh.model.SkillInfo(
                id = "s1",
                name = "android-developer",
                description = "Android Gradle and Compose development",
                filePath = "/path/to/android-developer",
                source = com.jarves.mh.model.SkillSource.BUNDLED,
                isEnabled = true,
            ),
            com.jarves.mh.model.SkillInfo(
                id = "s2",
                name = "code-reviewer",
                description = "Code review and quality audit for Android",
                filePath = "/path/to/code-reviewer",
                source = com.jarves.mh.model.SkillSource.BUNDLED,
                isEnabled = true,
            ),
            com.jarves.mh.model.SkillInfo(
                id = "s3",
                name = "git-expert",
                description = "Git commits and branches",
                filePath = "/path/to/git-expert",
                source = com.jarves.mh.model.SkillSource.BUNDLED,
                isEnabled = false, // disabled
            ),
        )

        // Query "android" should return android-developer first (prefix match on name), and code-reviewer second (description match)
        val filtered = SlashCommandEngine.filterSkills("/android", skills)
        assertEquals(2, filtered.size)
        assertEquals("android-developer", filtered[0].name)
        assertEquals("code-reviewer", filtered[1].name)

        // Disabled skill is excluded
        val gitFiltered = SlashCommandEngine.filterSkills("/git", skills)
        assertTrue(gitFiltered.isEmpty())

        // Blank query returns all enabled skills sorted by name
        val allFiltered = SlashCommandEngine.filterSkills("/", skills)
        assertEquals(2, allFiltered.size)
        assertEquals("android-developer", allFiltered[0].name)
        assertEquals("code-reviewer", allFiltered[1].name)
    }

    @Test
    fun parseSkillInvocation_extractsSkillAndArgs() {
        val skill = com.jarves.mh.model.SkillInfo(
            id = "s1",
            name = "android-developer",
            description = "Android expert",
            filePath = "/path/to/android",
            source = com.jarves.mh.model.SkillSource.BUNDLED,
            isEnabled = true,
        )
        val activeSkills = listOf(skill)

        val result1 = SlashCommandEngine.parseSkillInvocation("/android-developer Fix AAPT2 error", activeSkills)
        assertNotNull(result1)
        assertEquals("android-developer", result1!!.first.name)
        assertEquals("Fix AAPT2 error", result1.second)

        // Case insensitivity
        val result2 = SlashCommandEngine.parseSkillInvocation("/ANDROID-DEVELOPER", activeSkills)
        assertNotNull(result2)
        assertEquals("android-developer", result2!!.first.name)
        assertEquals("", result2.second)

        // Non-existent skill returns null
        val result3 = SlashCommandEngine.parseSkillInvocation("/unknown-skill some task", activeSkills)
        assertNull(result3)

        // Normal text returns null
        val result4 = SlashCommandEngine.parseSkillInvocation("android-developer check gradle", activeSkills)
        assertNull(result4)
    }

    @Test
    fun buildPromptForSkill_formatsSkillEnvelopeAndDirectives() {
        val skill = com.jarves.mh.model.SkillInfo(
            id = "s1",
            name = "android-developer",
            description = "Android expert",
            filePath = "/path/to/android",
            source = com.jarves.mh.model.SkillSource.BUNDLED,
            isEnabled = true,
        )

        val promptWithArgs = SlashCommandEngine.buildPromptForSkill(skill, "Resolve build timeout", "# Guide\nRun gradle --daemon")
        assertTrue(promptWithArgs.contains("<active_skill name=\"android-developer\" source=\"BUNDLED\">"))
        assertTrue(promptWithArgs.contains("Run gradle --daemon"))
        assertTrue(promptWithArgs.contains("[USER DIRECTIVE - ACTIVE SKILL APPLIED: android-developer]"))
        assertTrue(promptWithArgs.contains("Resolve build timeout"))

        val promptWithoutArgs = SlashCommandEngine.buildPromptForSkill(skill, "", "Instructions")
        assertTrue(promptWithoutArgs.contains("Please analyze this project workspace and apply the instructions and guidelines from the android-developer skill."))
    }
}
