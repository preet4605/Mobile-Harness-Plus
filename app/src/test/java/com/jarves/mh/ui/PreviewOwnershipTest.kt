package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.Project
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus
import org.junit.Assert.*
import org.junit.Test

class PreviewOwnershipTest {
    private val ui = AppUiState(activeProject = Project("p", "Project", "", "Kotlin"), activeChatId = "chat",
        agentKind = AgentKind.CODEX, taskStartedAtMillis = 10, isRunning = true, activeSessionId = "s")
    private val task = DurableTaskRecord(taskId = "task", projectId = "p", projectSlug = "p", chatId = "chat",
        agentKind = AgentKind.CODEX.name, providerJson = "{}", prompt = "Start server", status = TaskExecutionStatus.RUNNING, sessionId = "s")

    @Test fun readyProbeMayPublishAfterSuccessfulFinalization() {
        assertTrue(RuntimeSessionRouting.acceptsPreview(ui, "task", task, "s", 10))
        for (status in listOf(TaskExecutionStatus.COMPLETED, TaskExecutionStatus.UNVERIFIED)) {
            assertTrue(RuntimeSessionRouting.acceptsPreview(ui.copy(isRunning = false, activeSessionId = null), null, task.copy(status = status), "s", 10))
        }
    }

    @Test fun navigationRetryCancellationAndNewTasksRejectOldProbes() {
        assertFalse(RuntimeSessionRouting.acceptsPreview(ui.copy(activeChatId = "other"), "task", task, "s", 10))
        assertFalse(RuntimeSessionRouting.acceptsPreview(ui.copy(activeProject = null), "task", task, "s", 10))
        assertFalse(RuntimeSessionRouting.acceptsPreview(ui, "task", task.copy(sessionId = "retry"), "s", 10))
        assertFalse(RuntimeSessionRouting.acceptsPreview(ui.copy(isStopping = true), "task", task, "s", 10))
        assertFalse(RuntimeSessionRouting.acceptsPreview(ui, "new", task, "s", 10))
        assertFalse(RuntimeSessionRouting.acceptsPreview(ui.copy(taskStartedAtMillis = 20), "task", task, "s", 10))
        for (status in listOf(TaskExecutionStatus.CANCELLED, TaskExecutionStatus.FAILED, TaskExecutionStatus.ABANDONED)) {
            assertFalse(RuntimeSessionRouting.acceptsPreview(ui.copy(isRunning = false), null, task.copy(status = status), "s", 10))
        }
    }
}
