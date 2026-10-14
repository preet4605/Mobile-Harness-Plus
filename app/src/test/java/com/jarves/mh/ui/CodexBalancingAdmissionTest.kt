package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.CodexAccounts
import com.jarves.mh.runtime.CodexConversationHandoff
import com.jarves.mh.runtime.NativeConversationScope
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.task.TaskSupervisor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodexBalancingAdmissionTest {
    private fun fixture(test: (MainViewModel, CodexAccounts) -> Unit) {
        val app = robolectricApplication()
        val project = testProject("Balancing")
        app.workspaceDir(project).mkdirs()
        val vm = MainViewModel(app, ManualDispatcher())
        val accounts = vm.codexAuthController.accounts
        val a = accounts.create().also { accounts.complete(it.id, "A"); accounts.credentialFile(it.id).writeText("fixture") }
        accounts.create().also { accounts.complete(it.id, "B"); accounts.credentialFile(it.id).writeText("fixture") }
        vm.updateStateForTest { it.copy(activeProject = project, activeChatId = "chat", projects = listOf(project), agentKind = AgentKind.CODEX,
            provider = ProviderProfile(ProviderKind.CHATGPT), selectedCodexAccountId = a.id) }
        test(vm, accounts)
    }
    @Test fun invalidInputDoesNotConsumeAnAccountRotation() = fixture { vm, accounts ->
        accounts.balancingEnabled = true
        vm.sendPrompt("/unknown-command")
        assertEquals(accounts.list().first().id, accounts.selectForTask().id)
        assertFalse(vm.state.value.isRunning)
    }
    @Test fun balancedQueueDefersSelectionWhileManualQueueRetainsItsAccount() = fixture { vm, accounts ->
        accounts.balancingEnabled = true
        vm.updateStateForTest { it.copy(isRunning = true) }
        vm.sendPrompt("balanced follow-up")
        assertNull(vm.state.value.queuedFollowUps.single().codexAccountId)
        assertEquals(accounts.list().first().id, accounts.selectForTask().id)
        accounts.balancingEnabled = false
        vm.updateStateForTest { it.copy(queuedFollowUps = emptyList()) }
        vm.sendPrompt("manual follow-up")
        assertEquals(vm.state.value.selectedCodexAccountId, vm.state.value.queuedFollowUps.single().codexAccountId)
    }
    @Test fun taskAdmissionPersistsOneAccountAndOneOperationWithoutReplay() = fixture { vm, accounts ->
        accounts.balancingEnabled = true
        val expected = accounts.list().first().id
        vm.sendPrompt("1. inspect local files\n2. explain the results")
        val supervisor = TaskSupervisor.getInstance(robolectricApplication())
        val task = supervisor.stateStore.getTasksForProject(vm.state.value.activeProject!!.id).single()
        assertEquals(CodexAccounts.taskProvider(expected), task.providerJson)
        assertEquals(0, task.maxRetries)
        val steps = supervisor.canonicalTaskRepository.getTask(task.taskId)!!.plan.steps
        assertEquals(1, steps.size); assertEquals(1, steps.single().maxAttempts)
        runBlocking { supervisor.requestStop(task.taskId) }
    }
    @Test fun clearKeepsDurableResetSoOldTaskHistoryCannotBeReadopted() = fixture { vm, accounts ->
        accounts.balancingEnabled = true
        val app = robolectricApplication()
        val project = vm.state.value.activeProject!!
        val key = NativeConversationScope.key("chat", app.workspaceDir(project), vm.state.value.provider, "codex-account-pool")
        val handoff = CodexConversationHandoff(RuntimeInstaller(app).rootfs)
        handoff.savePlanMode(key, "${project.id}:chat", true)
        vm.sendPrompt("/clear")
        assertTrue(handoff.wasReset(key)); assertFalse(handoff.planMode(key))
        assertTrue(vm.state.value.messages.last().text.contains("history cleared", true))
        handoff.claim(key, "fresh", accounts.requireUsable(accounts.list().first().id), "/workspace/${project.slug}", "${project.id}:chat")
        assertNull(handoff.importForTask(key, "fresh", accounts.requireUsable(accounts.list().first().id), "/workspace/${project.slug}"))
    }
}
