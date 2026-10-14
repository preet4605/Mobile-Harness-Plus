package com.jarves.mh.ui

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.AgentKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelSlashCommandTest {
    @Test fun codexCannotChangeClaudeApprovalsByTypingHiddenCommand() {
        val app = robolectricApplication()
        val preferences = AppPreferences(app)
        preferences.claudeInteractiveApprovals = false
        val project = testProject("Commands")
        app.workspaceDir(project).mkdirs()
        val viewModel = MainViewModel(app, ManualDispatcher())
        viewModel.updateStateForTest { it.copy(activeProject = project, projects = listOf(project), agentKind = AgentKind.CODEX) }
        viewModel.sendPrompt("/approvals on")
        assertFalse(preferences.claudeInteractiveApprovals)
        assertTrue(viewModel.state.value.messages.last().text.contains("not supported", ignoreCase = true))
    }
    @Test fun quotedProjectLinkUsesDiscoveredFolderBelowSelectedRoot() {
        val app = robolectricApplication()
        val source = testProject("Project Source", "service")
        val target = testProject("Target")
        val file = app.writeWorkspaceFile(source, "service/.claude/skills/folder/SKILL.md", "---\nname: visible\ndescription: Fixture\n---\nBody")
        app.workspaceDir(target).mkdirs()
        val manager = com.jarves.mh.runtime.SkillManager(app)
        val skill = manager.parseSkillFile(file, com.jarves.mh.model.SkillSource.OTHER_PROJECT)!!
        val viewModel = MainViewModel(app, ManualDispatcher())
        viewModel.updateStateForTest { it.copy(activeProject = target, projects = listOf(source, target), agentKind = AgentKind.CODEX,
            activeCustomizationConfig = com.jarves.mh.model.ProjectCustomizationConfig(projectId = target.id),
            otherProjectsSkills = mapOf(source to listOf(skill))) }
        viewModel.sendPrompt("/skills link \"Project Source\" visible")
        val link = AppPreferences(app).loadProjectCustomizationConfig(target.id).linkedSkills.single()
        org.junit.Assert.assertEquals(".claude/skills/folder", link.relativeSkillPath)
        org.junit.Assert.assertEquals(source.id, link.sourceProjectId)
    }

}
