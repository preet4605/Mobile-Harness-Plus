package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.brain.GlobalExecutionPolicies
import com.jarves.mh.runtime.CodexPromptNotes
import com.jarves.mh.runtime.SkillManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromptCompactionTest {
    @get:Rule val temp = TemporaryFolder()
    private val workspace = "/workspace/demo"

    @Test fun generatedHeadersHaveASmallCharacterBudget() {
        val manager = SkillManager(temp.newFolder())
        val skill = SkillInfo("fixture", "fixture", "Project instructions", "/host/SKILL.md", SkillSource.PROJECT,
            guestFilePath = "$workspace/.agents/skills/fixture/SKILL.md")
        val sizes = linkedMapOf(
            "workspace" to PromptContextSupport.workspaceBlock(workspace, ProjectKind.QUICK_PROJECT, true).length,
            "phone" to CodexPromptNotes.PHONE_ENVIRONMENT.length,
            "policies" to GlobalExecutionPolicies.DEFAULT_POLICIES.joinToString("\n\n").length,
            "skills" to manager.buildProgressiveDisclosureIndex(listOf(skill)).length,
        )
        println("Generated prompt header characters: $sizes; total=${sizes.values.sum()}")
        assertTrue("Workspace header exceeds budget: $sizes", sizes.getValue("workspace") <= 1_100)
        assertTrue("Phone header exceeds budget: $sizes", sizes.getValue("phone") <= 600)
        assertTrue("Policy header exceeds budget: $sizes", sizes.getValue("policies") <= 550)
        assertTrue("Skill header exceeds budget: $sizes", sizes.getValue("skills") <= 240)
        assertTrue("Combined headers exceed budget: $sizes", sizes.values.sum() <= 2_300)
    }

    @Test fun compactWorkspaceRetainsToolchainAndWorkspaceConstraints() {
        for (kind in ProjectKind.entries) {
            val block = PromptContextSupport.workspaceBlock(workspace, kind, true)
            assertTrue(block.contains(workspace))
            for (fact in listOf("JDK 17", "Android SDK 36", "ARM64 Build Tools 35.0.0", "Gradle 8.14.3",
                "AGP 8.11.0", "Kotlin 1.9.22", "compileSdk 36", "Java 17", "offline Maven",
                "android.useAndroidX=true", "x86_64", "android.aapt2FromMavenOverride", "pgrep")) {
                assertTrue("Missing workspace guidance: $fact", block.contains(fact))
            }
            assertTrue(block.contains("`gradle`"))
            assertTrue(block.length <= 1_100)
        }
    }

    @Test fun absentToolchainNeverClaimsItIsAvailable() {
        val block = PromptContextSupport.workspaceBlock(workspace, ProjectKind.PROJECT, false)
        assertTrue(block.contains("not installed"))
        assertTrue(block.contains("Settings"))
        assertFalse(block.contains("Gradle 8.14.3"))
        assertFalse(block.contains("AGP 8.11.0"))
        assertTrue(block.length <= 600)
    }

    @Test fun phoneHeaderKeepsLongCommandAndInspectionGuidance() {
        val block = CodexPromptNotes.PHONE_ENVIRONMENT
        for (fact in listOf("PRoot Ubuntu", "ps", "top", "free", "uptime", "build.log 2>&1", "tail",
            "harness-tools doctor", "harness-tools preview", "Brain", "dom", "console", "screenshot")) {
            assertTrue("Missing phone guidance: $fact", block.contains(fact))
        }
        val task = "Run a build\nthen inspect its output"
        assertTrue(CodexPromptNotes.withPhoneEnvironment(task).endsWith(task))
    }

    @Test fun compactEnvelopePreservesUserRulesRequestAndHistory() {
        val manager = SkillManager(temp.newFolder())
        val content = "Use the project build command exactly.\n" + "Important custom requirement.\n".repeat(100)
        val rule = RuleInfo(id = "rule", name = "custom", title = "custom", description = "",
            content = content, filePath = "/rules/custom.md", source = RuleSource.PROJECT)
        val task = manager.buildRulesBlock(listOf(rule)) + "\nFix the parser; preserve its behavior."
        val prompt = PromptContextSupport.buildPrompt(task,
            listOf(ChatMessage(fromUser = true, text = "Keep previous edits and use the existing parser.")),
            workspace, ProjectKind.PROJECT, ContextMemory("project"), true)
        assertTrue(prompt.contains(content.trimEnd()))
        assertTrue(prompt.contains("Keep previous edits and use the existing parser."))
        assertTrue(prompt.trimEnd().endsWith(task))
        assertEquals(1, Regex("<project_workspace>").findAll(prompt).count())
        assertEquals(1, Regex("<user_rules>").findAll(prompt).count())
    }
}
