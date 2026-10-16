package com.jarves.mh.ui

import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.Project
import com.jarves.mh.model.WorkStatus
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import org.junit.Test

class WorkPresentationTest {
    @get:Rule val temp = TemporaryFolder()
    private val project = Project(id = "p", name = "Project", description = "", language = "Kotlin")
    private val task = DurableTaskRecord(taskId = "task", projectId = "p", projectSlug = "p", chatId = "c",
        agentKind = AgentKind.CODEX.name, providerJson = "{}", prompt = "Build", status = TaskExecutionStatus.COMPLETED,
        sessionId = "session", completedAt = 5000L)
    private val ui = AppUiState(activeProject = project, activeChatId = "c", agentKind = AgentKind.CODEX,
        isRunning = true, taskStartedAtMillis = 1000L, workSegmentStartedAtMillis = 4000L,
        retiredSessionIds = setOf("session"),
        messages = listOf(ChatMessage(id = "answer", fromUser = false, text = "APK built")),
        liveProcess = listOf(ActivityItem("Checking result", "Waiting for supervisor verification", false)))

    @Test fun finalizationWritesOutcomeAfterAnswerAndRoundTripsThroughStorage() {
        val result = finalizeTaskWork(ui, "task", task, "Task completed", "Runtime verification passed", 5000L)
        assertEquals(ui.messages.single(), result.messages.first())
        val work = result.messages.last()
        assertEquals(WorkStatus.COMPLETED, work.workStatus)
        assertEquals("Completed · 4s", workBlockHeadline(work))
        assertEquals("Task completed", work.workItems.last().title)
        assertFalse(work.workItems.any { it.detail == "Waiting for supervisor verification" })
        assertTrue(result.liveProcess.isEmpty())
        val root = temp.newFolder()
        AppPreferences(baseChatsDir = root).saveMessages("p", "c", result.messages)
        assertEquals(result.messages, AppPreferences(baseChatsDir = root).loadMessages("p", "c"))
    }

    @Test fun everyTerminalOutcomeRemainsDistinctEvenWithoutAnswerOrWork() {
        val outcomes = mapOf(TaskExecutionStatus.COMPLETED to WorkStatus.COMPLETED,
            TaskExecutionStatus.UNVERIFIED to WorkStatus.UNVERIFIED, TaskExecutionStatus.FAILED to WorkStatus.FAILED,
            TaskExecutionStatus.CANCELLED to WorkStatus.STOPPED, TaskExecutionStatus.ABANDONED to WorkStatus.INTERRUPTED)
        outcomes.forEach { (status, expected) ->
            val result = finalizeTaskWork(ui.copy(messages = emptyList(), liveProcess = emptyList()), "task",
                task.copy(status = status), expected.label, "Outcome details", 5000L)
            assertEquals(expected, result.messages.single().workStatus)
            assertTrue(workBlockHeadline(result.messages.single()).startsWith(expected.label))
        }
    }

    @Test fun terminalPresentationWaitsForOwnedCloseAndRejectsForeignOrActiveTask() {
        val following = ui.copy(activeSessionId = "session", retiredSessionIds = emptySet())
        assertEquals(following, finalizeTaskWork(following, "task", task, "Completed", "Passed", 5000L))
        assertEquals(WorkStatus.CHECKING, supervisorWorkStatus(following, "task", task))
        listOf(task.copy(projectId = "other"), task.copy(chatId = "other"),
            task.copy(agentKind = AgentKind.CLAUDE_CODE.name), task.copy(status = TaskExecutionStatus.RUNNING)).forEach {
            assertEquals(ui, finalizeTaskWork(ui, "task", it, "Completed", "Passed", 5000L))
        }
        assertEquals(ui, finalizeTaskWork(ui, null, task, "Completed", "Passed", 5000L))
    }

    @Test fun retryAndWaitStatesFollowSupervisorWithoutInventingCompletion() {
        listOf(TaskExecutionStatus.WAITING_FOR_INPUT to WorkStatus.WAITING_FOR_INPUT,
            TaskExecutionStatus.WAITING_FOR_APPROVAL to WorkStatus.WAITING_FOR_APPROVAL,
            TaskExecutionStatus.RECOVERING to WorkStatus.RECOVERING,
            TaskExecutionStatus.COMPLETING to WorkStatus.CHECKING).forEach { (status, expected) ->
            assertEquals(expected, supervisorWorkStatus(ui, "task", task.copy(status = status)))
        }
        val retry = RuntimeSessionRouting.prepareForAttempt(ui.copy(liveWorkStatus = WorkStatus.CHECKING), "task", "task", "p", "session", "Build")
        assertEquals(WorkStatus.WORKING, retry.liveWorkStatus)
        assertEquals(WorkStatus.WORKING, supervisorWorkStatus(retry, "task", task.copy(status = TaskExecutionStatus.RUNNING, sessionId = "next")))
        assertEquals(WorkStatus.STOPPING, supervisorWorkStatus(ui.copy(isStopping = true), "task", task))
    }

    @Test fun legacyAndUnknownOutcomeStayNeutralAndKeepTheirHistory() {
        val root = temp.newFolder()
        val saved = File(root, "p/c.json").apply { parentFile?.mkdirs() }
        saved.writeText(JSONArray().put(JSONObject().put("id", "old").put("fromUser", false).put("text", "")
            .put("workedMillis", 1000L).put("workStatus", "FUTURE_STATUS")
            .put("workItems", JSONArray().put(JSONObject().put("title", "Checking result").put("detail", "Waiting for supervisor verification")))).toString())
        val message = AppPreferences(baseChatsDir = root).loadMessages("p", "c").single()
        assertNull(message.workStatus)
        assertEquals("View activity · 1s", workBlockHeadline(message))
        assertEquals("Waiting for supervisor verification", message.workItems.single().detail)
        assertEquals("View activity", workBlockHeadline(ChatMessage(fromUser = false, text = "")))
    }

    @Test fun durationShowsOnceWhenTheTurnEndsInAStatusBlock() {
        val reply = ChatMessage(fromUser = false, text = "Done.", workedMillis = 57_000)
        val block = ChatMessage(fromUser = false, text = "", workItems = listOf(ActivityItem("Task completed", "ok")),
            workStatus = WorkStatus.UNVERIFIED, workedMillis = 57_000)
        val legacyReply = ChatMessage(fromUser = false, text = "Old answer", workedMillis = 9_000)
        val messages = listOf(
            ChatMessage(fromUser = true, text = "old"), legacyReply,
            ChatMessage(fromUser = true, text = "analyse"), reply, block,
        )
        // The turn's block carries "· 57s"; the older reply has no block, so it keeps "Worked for".
        assertEquals(setOf(reply.id), repliesWithDurationShownBelow(messages))
    }

    @Test fun elapsedTimeFreezesAtFinishAndCannotGoNegative() {
        assertEquals(4L, elapsedWorkSeconds(1000L, 5000L, 99000L))
        assertEquals(5L, elapsedWorkSeconds(1000L, null, 6000L))
        assertEquals(0L, elapsedWorkSeconds(6000L, 1000L, 99000L))
    }
    @Test fun interruptedHistoryDoesNotKeepClaimingVerificationIsPending() {
        val result = MainViewModel.createInterruptedMessage(1L, listOf(
            ActivityItem("Checking result", "Waiting for supervisor verification", false),
        ))
        assertFalse(result.workItems.any { it.detail == "Waiting for supervisor verification" })
        assertEquals("Task interrupted", result.workItems.last().title)
    }

    @Test fun interruptionPreservesCommandsAndDoesNotClaimCompletion() {
        val command = ActivityItem("Running Bash", "gradle assembleOnlineDebug", false, true)
        val result = MainViewModel.createInterruptedMessage(1L, listOf(command))
        assertEquals(command.copy(isComplete = true), result.workItems.first())
        assertFalse(result.workItems.any { it.title == "Task completed" })
    }
}
