package com.jarves.mh.ui

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.Project
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
import com.jarves.mh.runtime.task.TaskExecutionStatus
import com.jarves.mh.runtime.task.TaskSupervisor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Regression for N2 (Phase 1 re-audit): after a failed attempt the chat kept filtering on the
 * failed attempt's sessionId, so every event of the retry (SessionStarted, output, completion,
 * the answer) was dropped and never persisted. The retry also lost its request because a
 * failed session clears the live request.
 *
 * [MainViewModel.onRuntimeEvent] gates every event through [RuntimeSessionRouting.accepts], and
 * the sendPrompt execution block calls [RuntimeSessionRouting.prepareForAttempt] and
 * [RuntimeSessionRouting.requestForAttempt] before each attempt. MainViewModel itself needs an
 * Android Application, so these tests drive those exact functions; the last test drives them
 * from the real [TaskSupervisor.executeTask] retry loop.
 */
class RuntimeRetrySessionRoutingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val project = Project(id = "proj-retry", name = "Retry", description = "", language = "Kotlin")
    private val taskId = "task-retry"

    /** State right after sendPrompt: running, no session bound yet. */
    private fun afterSendPrompt() = AppUiState(
        activeProject = project,
        isRunning = true,
        activeSessionId = null,
        currentTaskRequest = "Fix the build",
        messages = listOf(ChatMessage(fromUser = true, text = "Fix the build")),
    )

    /**
     * The session-identity effects MainViewModel applies to accepted events. Only the fields the
     * routing contract depends on are modelled; every event first passes the real gate.
     */
    private fun apply(state: AppUiState, event: RuntimeEvent, willRetry: Boolean = true): AppUiState {
        if (!RuntimeSessionRouting.accepts(state, event)) return state
        return when (event) {
            is RuntimeEvent.SessionStarted -> state.copy(activeSessionId = event.sessionId)
            is RuntimeEvent.AssistantDelta -> state.copy(messages = state.messages + ChatMessage(fromUser = false, text = event.text))
            is RuntimeEvent.SessionCompleted -> state.copy(isRunning = false, activeSessionId = null, currentTaskRequest = null)
            is RuntimeEvent.SessionFailed -> state.copy(
                isRunning = willRetry,
                activeSessionId = if (willRetry) state.activeSessionId else null,
                currentTaskRequest = if (willRetry) state.currentTaskRequest else null,
            )
            else -> state
        }
    }

    private fun prepare(state: AppUiState, previousSessionId: String? = null, tracked: String? = taskId) =
        RuntimeSessionRouting.prepareForAttempt(state, tracked, taskId, project.id, previousSessionId, "Fix the build")

    @Test
    fun `retry session is accepted after a failure the UI expected to retry`() {
        var state = prepare(afterSendPrompt())
        state = apply(state, RuntimeEvent.SessionStarted("s1"))
        state = apply(state, RuntimeEvent.AssistantDelta("s1", "partial"))
        state = apply(state, RuntimeEvent.SessionFailed("s1", "HTTP 503 service unavailable"), willRetry = true)
        assertEquals("s1", state.activeSessionId)

        // Old behaviour: the retry's SessionStarted did not match the failed session and was dropped.
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s2")))

        state = prepare(state, previousSessionId = "s1")
        assertTrue(state.isRunning)
        assertNull(state.activeSessionId)

        state = apply(state, RuntimeEvent.SessionStarted("s2"))
        assertEquals("Retry's sessionId must become the active session", "s2", state.activeSessionId)
        assertFalse("Late events from the failed attempt must be dropped",
            RuntimeSessionRouting.accepts(state, RuntimeEvent.AssistantDelta("s1", "stale")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.ToolStarted("s2", "Bash", "gradle")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.FilesChanged("s2", emptyList())))
        state = apply(state, RuntimeEvent.AssistantDelta("s2", "Build fixed."))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionCompleted("s2")))
        state = apply(state, RuntimeEvent.SessionCompleted("s2"))

        assertFalse(state.isRunning)
        assertEquals("Build fixed.", state.messages.last().text)
        assertFalse(state.messages.any { it.text == "stale" })
    }

    @Test
    fun `retry session is accepted after a failure the UI classified as final`() {
        var state = prepare(afterSendPrompt())
        state = apply(state, RuntimeEvent.SessionStarted("s1"))
        // e.g. a PROCESS_FAILURE: the UI stops showing "running", but the supervisor retries.
        state = apply(state, RuntimeEvent.SessionFailed("s1", "process exited with code 1"), willRetry = false)
        assertFalse(state.isRunning)
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s2")))

        state = prepare(state, previousSessionId = "s1")
        assertTrue(state.isRunning)
        assertEquals("Fix the build", state.currentTaskRequest)
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s1")))
        state = apply(state, RuntimeEvent.SessionStarted("s2"))
        state = apply(state, RuntimeEvent.AssistantDelta("s2", "Done on retry."))
        state = apply(state, RuntimeEvent.SessionCompleted("s2"))
        assertEquals("Done on retry.", state.messages.last().text)
        assertFalse(state.isRunning)
    }

    @Test
    fun `attempt preparation only rebinds the task the chat is tracking`() {
        val failed = afterSendPrompt().copy(activeSessionId = "s1")
        assertSame("Another task's retry must not take over the chat",
            failed, RuntimeSessionRouting.prepareForAttempt(failed, "other-task", taskId, project.id, "s1", "x"))
        val stopping = failed.copy(isStopping = true)
        assertSame("A retry must not override a user stop",
            stopping, RuntimeSessionRouting.prepareForAttempt(stopping, taskId, taskId, project.id, "s1", "x"))
        val switched = failed.copy(activeProject = project.copy(id = "proj-other"))
        assertSame("A retry must not bind into a different project's chat",
            switched, RuntimeSessionRouting.prepareForAttempt(switched, taskId, taskId, project.id, "s1", "x"))
    }

    @Test
    fun `late SessionStarted from the failed attempt cannot take over the retry`() {
        var state = prepare(afterSendPrompt())
        assertTrue("Initial attempt: first SessionStarted binds",
            RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s1")))
        state = apply(state, RuntimeEvent.SessionStarted("s1"))
        assertEquals("s1", state.activeSessionId)
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.AssistantDelta("s1", "a")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.ToolStarted("s1", "Bash", "ls")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.FilesChanged("s1", emptyList())))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionCompleted("s1")))

        state = apply(state, RuntimeEvent.SessionFailed("s1", "HTTP 503 service unavailable"), willRetry = true)
        state = prepare(state, previousSessionId = "s1")

        assertFalse("Late SessionStarted(s1) must be rejected",
            RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s1")))
        state = apply(state, RuntimeEvent.SessionStarted("s1"))
        assertNull("Late SessionStarted(s1) must not take ownership", state.activeSessionId)

        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s2")))
        state = apply(state, RuntimeEvent.SessionStarted("s2"))
        assertEquals("s2", state.activeSessionId)

        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.AssistantDelta("s1", "stale")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.AssistantDelta("s2", "fresh")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.ToolStarted("s2", "Bash", "ls")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.FilesChanged("s2", emptyList())))
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.ToolStarted("s1", "Bash", "ls")))
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionFailed("s1", "late")))
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionCompleted("s1")))
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionCompleted("s2")))
        state = apply(state, RuntimeEvent.AssistantDelta("s1", "stale"))
        state = apply(state, RuntimeEvent.AssistantDelta("s2", "fresh"))
        state = apply(state, RuntimeEvent.SessionCompleted("s1"))
        assertTrue("SessionCompleted(s1) must not end the retry", state.isRunning)
        state = apply(state, RuntimeEvent.SessionCompleted("s2"))
        assertFalse(state.isRunning)
        assertEquals("fresh", state.messages.last().text)
        assertFalse(state.messages.any { it.text == "stale" })
    }

    @Test
    fun `failed session the chat never saw is still retired by the task binding`() {
        // Retry prepared before the chat processed any s1 event: s1 is known only as the
        // session the supervisor had bound to the task.
        var state = prepare(prepare(afterSendPrompt()), previousSessionId = "s1")
        assertNull(state.activeSessionId)
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s1")))
        assertFalse(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionFailed("s1", "HTTP 503")))
        state = apply(state, RuntimeEvent.SessionStarted("s1"))
        state = apply(state, RuntimeEvent.SessionFailed("s1", "HTTP 503"), willRetry = false)
        assertTrue("A late failure of s1 must not stop the retry", state.isRunning)
        state = apply(state, RuntimeEvent.SessionStarted("s2"))
        assertEquals("s2", state.activeSessionId)
    }

    @Test
    fun `first session still binds when nothing has been retired`() {
        val state = prepare(afterSendPrompt(), previousSessionId = null)
        assertTrue(state.retiredSessionIds.isEmpty())
        assertTrue(RuntimeSessionRouting.accepts(state, RuntimeEvent.SessionStarted("s1")))
        assertEquals("s1", apply(state, RuntimeEvent.SessionStarted("s1")).activeSessionId)
    }

    @Test
    fun `request case A - live request of this task is used`() {
        assertSame("live", RuntimeSessionRouting.requestForAttempt("live", taskId, "last", taskId))
    }

    @Test
    fun `request case B - cleared live request falls back to the task's last request`() {
        assertSame("last", RuntimeSessionRouting.requestForAttempt(null, null, "last", taskId))
    }

    @Test
    fun `request case C - key or provider fallback request of this task wins`() {
        assertSame("fallback", RuntimeSessionRouting.requestForAttempt("fallback", taskId, "last", taskId))
    }

    @Test
    fun `request of another task is never reused`() {
        assertSame("last", RuntimeSessionRouting.requestForAttempt("other", "other-task", "last", taskId))
    }

    @Test
    fun `supervisor retry loop delivers the retried answer to the chat`() = runBlocking {
        ControlledBrainInjector.clearAll()
        val db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        val workspace = tempFolder.newFolder("workspace")
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder("checkpoints"))
        supervisor.workspaceDirectoryResolver = { workspace }
        supervisor.checkpointsResolver = { checkpoints }
        supervisor.createTask(
            taskId = taskId,
            projectId = project.id,
            projectSlug = project.slug,
            chatId = "chat-1",
            agentKind = "CLAUDE_CODE",
            providerJson = "{}",
            prompt = "Fix the build",
        )

        val ui = AtomicReference(afterSendPrompt())
        val initialRequest = "request-attempt-0"
        val liveRequest = AtomicReference<String?>(initialRequest)
        var lastUsed = initialRequest
        val requestsUsed = mutableListOf<String>()
        val attempts = AtomicInteger(0)

        supervisor.executeTask(taskId) { task ->
            // Mirrors the sendPrompt execution block.
            val request = RuntimeSessionRouting.requestForAttempt(liveRequest.get(), task.taskId, lastUsed, task.taskId)
            lastUsed = request
            requestsUsed += request
            val previousSessionId = supervisor.stateStore.get(task.taskId)?.sessionId
            ui.updateAndGet {
                RuntimeSessionRouting.prepareForAttempt(it, taskId, task.taskId, task.projectId, previousSessionId, "Fix the build")
            }
            val sessionId = "s${attempts.incrementAndGet()}"
            if (sessionId == "s2") {
                // A late SessionStarted from the failed attempt arrives after the retry was prepared.
                ui.updateAndGet { apply(it, RuntimeEvent.SessionStarted("s1")) }
            }
            supervisor.bindSession(task.taskId, sessionId)
            ui.updateAndGet { apply(it, RuntimeEvent.SessionStarted(sessionId)) }
            if (sessionId == "s1") {
                ui.updateAndGet { apply(it, RuntimeEvent.AssistantDelta(sessionId, "partial")) }
                ui.updateAndGet { apply(it, RuntimeEvent.SessionFailed(sessionId, "HTTP 503 service unavailable")) }
                liveRequest.set(null) // onRuntimeEvent clears the live request on SessionFailed
                throw RuntimeException("HTTP 503 service unavailable")
            }
            ui.updateAndGet { apply(it, RuntimeEvent.AssistantDelta(sessionId, "Retried answer")) }
            ui.updateAndGet { apply(it, RuntimeEvent.SessionCompleted(sessionId)) }
        }.join()

        assertEquals("The supervisor must run a second attempt", 2, attempts.get())
        assertEquals("The retry must run the task's request, not nothing", listOf(initialRequest, initialRequest), requestsUsed)
        assertEquals("s2", supervisor.stateStore.get(taskId)!!.sessionId)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)!!.status)
        val finalUi = ui.get()
        assertEquals("Retried answer", finalUi.messages.last().text)
        assertFalse(finalUi.isRunning)
        assertNull(finalUi.activeSessionId)
    }
}
