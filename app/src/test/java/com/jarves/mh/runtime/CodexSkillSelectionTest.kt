package com.jarves.mh.runtime

import com.jarves.mh.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexSkillSelectionTest {
    private val path = "/workspace/demo/.agents/skills/fixture/SKILL.md"
    private val native = CodexSkillSelection.NativeSkill("fixture", path)
    private val skill = SkillInfo("project:fixture", "fixture", "Fixture", path, SkillSource.PROJECT, guestFilePath = path)

    @Test fun disabledAndUnavailableSkillsDisableNativePath() {
        for (selected in listOf(skill.copy(isEnabled = false), skill.copy(guestFilePath = null))) {
            val config = CodexSkillSelection.config(listOf(native), listOf(selected), CustomizationScopeMode.INHERIT_AND_MERGE)
            assertTrue(config.contains("enabled=false")); assertTrue(config.contains(path))
            val command = CodexLaunchBuilder.command(CodexRoute.ChatGptLogin(""), "/workspace/demo", "/tmp/final", skillConfig = config)
            assertEquals("-c", command[command.indexOf(config) - 1])
        }
    }
    @Test fun enabledExactPathAndScopeAreEnforced() {
        assertTrue(CodexSkillSelection.config(listOf(native), listOf(skill), CustomizationScopeMode.CUSTOM).contains("enabled=true"))
        assertTrue(CodexSkillSelection.config(listOf(native.copy(path = "/root/.agents/skills/fixture/SKILL.md")), listOf(skill), CustomizationScopeMode.INHERIT_AND_MERGE).contains("enabled=false"))
        assertTrue(CodexSkillSelection.config(listOf(native), emptyList(), CustomizationScopeMode.CUSTOM).contains("enabled=false"))
        assertTrue(CodexSkillSelection.config(listOf(native.copy(scope = "system")), emptyList(), CustomizationScopeMode.INHERIT_AND_MERGE).contains("enabled=true"))
    }
    @Test fun skillInventoryRequiresCorrectWorkspaceAndNoErrors() {
        val result = JSONObject("""{"data":[{"cwd":"/workspace/demo","errors":[],"skills":[{"name":"fixture","path":"$path","scope":"repo","enabled":true}]}]}""")
        assertEquals(listOf(native), CodexAppServerProtocol.parseSkills(result, "/workspace/demo"))
        assertTrue(runCatching { CodexAppServerProtocol.parseSkills(result, "/workspace/other") }.isFailure)
        result.getJSONArray("data").getJSONObject(0).getJSONArray("errors").put(JSONObject().put("message", "bad manifest"))
        assertTrue(runCatching { CodexAppServerProtocol.parseSkills(result, "/workspace/demo") }.isFailure)
    }
    @Test fun manualOnlyAndUnmanagedRepoSkillsAreNotNativeImplicitCandidates() {
        assertTrue(CodexSkillSelection.config(listOf(native), listOf(skill.copy(allowImplicitInvocation = false)), CustomizationScopeMode.INHERIT_AND_MERGE).contains("enabled=false"))
        assertTrue(CodexSkillSelection.config(listOf(native), emptyList(), CustomizationScopeMode.INHERIT_AND_MERGE).contains("enabled=false"))
    }

    @Test fun previouslyDisabledNativeSkillsAreNotReenabledByLaunchSelection() {
        assertTrue(CodexSkillSelection.config(listOf(native.copy(scope = "system", enabled = false)), emptyList(), CustomizationScopeMode.INHERIT_AND_MERGE).contains("enabled=false"))
        assertTrue(CodexSkillSelection.config(listOf(native.copy(enabled = false)), listOf(skill), CustomizationScopeMode.CUSTOM).contains("enabled=false"))
    }

}
