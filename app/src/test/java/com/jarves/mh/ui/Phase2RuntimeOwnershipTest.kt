package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.Project
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus
import org.junit.Assert.*
import org.junit.Test

class Phase2RuntimeOwnershipTest {
    private val project = Project(id = "p", name = "Project", description = "", language = "Kotlin")
    private val ui = AppUiState(activeProject = project, activeChatId = "chat", agentKind = AgentKind.CODEX,
        isRunning = true, activeSessionId = null)
    private val task = DurableTaskRecord(taskId = "task", projectId = "p", projectSlug = "p", chatId = "chat",
        agentKind = AgentKind.CODEX.name, providerJson = "{}", prompt = "Explain", status = TaskExecutionStatus.RUNNING,
        sessionId = "session")

    @Test fun foreignStartCannotBindWhileAwaitingOwnSession() {
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, RuntimeEvent.SessionStarted("foreign"), "task", task, AgentKind.CODEX))
        assertTrue(RuntimeSessionRouting.acceptsOwned(ui, RuntimeEvent.SessionStarted("session"), "task", task, AgentKind.CODEX))
    }

    @Test fun everyOwnershipDimensionIsRequired() {
        val event = RuntimeEvent.SessionStarted("session")
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, event, "other", task, AgentKind.CODEX))
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, event, "task", null, AgentKind.CODEX))
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, event, "task", task.copy(projectId = "other"), AgentKind.CODEX))
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, event, "task", task.copy(chatId = "other"), AgentKind.CODEX))
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, event, "task", task, AgentKind.CLAUDE_CODE))
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui.copy(agentKind = AgentKind.ANTIGRAVITY), event, "task", task, AgentKind.CODEX))
    }

    @Test fun retiredFailureCannotInvalidateCurrentAttempt() {
        val retry = ui.copy(activeSessionId = "next", retiredSessionIds = setOf("session"))
        val next = task.copy(sessionId = "next")
        assertFalse(RuntimeSessionRouting.acceptsOwned(retry, RuntimeEvent.SessionFailed("session", "not signed in"), "task", next, AgentKind.CODEX))
        assertTrue(RuntimeSessionRouting.acceptsOwned(retry, RuntimeEvent.SessionFailed("next", "not signed in"), "task", next, AgentKind.CODEX))
    }

    @Test fun ownedFailureBeforeSessionStartedStillClosesAttempt() {
        assertTrue(RuntimeSessionRouting.acceptsOwned(ui, RuntimeEvent.SessionFailed("session", "Stopped by user"), "task", task, AgentKind.CODEX))
        assertFalse(RuntimeSessionRouting.acceptsOwned(ui, RuntimeEvent.SessionFailed("foreign", "not signed in"), "task", task, AgentKind.CODEX))
    }

    @Test fun terminalOutcomeWaitsForBufferedSessionClose() {
        val final = task.copy(status = TaskExecutionStatus.COMPLETED)
        assertFalse(RuntimeSessionRouting.canFinish(ui.copy(activeSessionId = "session"), "task", final))
        val consumed = RuntimeSessionRouting.closeAttempt(ui.copy(activeSessionId = "session"), "session")
        assertTrue(RuntimeSessionRouting.canFinish(consumed, "task", final))
        assertFalse(RuntimeSessionRouting.canFinish(consumed, "other", final))
        assertFalse(RuntimeSessionRouting.canFinish(consumed, "task", task))
    }

    @Test fun refusedLaunchWithoutSessionCanFinish() {
        assertTrue(RuntimeSessionRouting.canFinish(ui, "task", task.copy(sessionId = null, status = TaskExecutionStatus.FAILED)))
    }
    @Test fun failedFallbackPredictionStillAllowsAuthoritativeFailure() {
        val following = ui.copy(activeSessionId = "session")
        val consumed = RuntimeSessionRouting.closeAttempt(following, "session")
        assertTrue(consumed.isRunning)
        assertNull(consumed.activeSessionId)
        assertFalse(RuntimeSessionRouting.acceptsOwned(consumed, RuntimeEvent.SessionFailed("session", "stale"), "task", task, AgentKind.CODEX))
        assertTrue(RuntimeSessionRouting.canFinish(consumed, "task", task.copy(status = TaskExecutionStatus.FAILED)))
    }

}
