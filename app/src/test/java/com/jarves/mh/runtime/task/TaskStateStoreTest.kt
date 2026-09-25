package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TaskStateStoreTest {

    private lateinit var db: BrainDatabase
    private lateinit var store: TaskStateStore

    @Before
    fun setUp() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        store = TaskStateStore(db)
    }

    @Test
    fun `test initial task save and retrieval`() {
        val record = DurableTaskRecord(
            taskId = "task-1",
            projectId = "p-1",
            projectSlug = "test-project",
            chatId = "chat-1",
            agentKind = "CLAUDE",
            providerJson = "CLAUDE_LOGIN",
            prompt = "Refactor network layer",
            status = TaskExecutionStatus.CREATED
        )
        store.save(record)

        val retrieved = store.get("task-1")
        assertNotNull(retrieved)
        assertEquals("p-1", retrieved?.projectId)
        assertEquals("Refactor network layer", retrieved?.prompt)
        assertEquals(TaskExecutionStatus.CREATED, retrieved?.status)
    }

    @Test
    fun `test valid centralized state transitions`() {
        val record = DurableTaskRecord(
            taskId = "task-flow",
            projectId = "p-1",
            projectSlug = "test-project",
            chatId = "chat-1",
            agentKind = "CLAUDE",
            providerJson = "CLAUDE_LOGIN",
            prompt = "Run tests",
            status = TaskExecutionStatus.CREATED
        )
        store.save(record)

        // CREATED -> STARTING
        val starting = store.transition("task-flow", TaskExecutionStatus.STARTING)
        assertEquals(TaskExecutionStatus.STARTING, starting.status)

        // STARTING -> RUNNING
        val running = store.transition("task-flow", TaskExecutionStatus.RUNNING)
        assertEquals(TaskExecutionStatus.RUNNING, running.status)
        assertNotNull(running.startedAt)

        // RUNNING -> WAITING_FOR_APPROVAL
        val waiting = store.transition("task-flow", TaskExecutionStatus.WAITING_FOR_APPROVAL)
        assertEquals(TaskExecutionStatus.WAITING_FOR_APPROVAL, waiting.status)

        // WAITING_FOR_APPROVAL -> RUNNING
        val resumed = store.transition("task-flow", TaskExecutionStatus.RUNNING)
        assertEquals(TaskExecutionStatus.RUNNING, resumed.status)

        // RUNNING -> COMPLETING
        val completing = store.transition("task-flow", TaskExecutionStatus.COMPLETING)
        assertEquals(TaskExecutionStatus.COMPLETING, completing.status)

        // COMPLETING -> COMPLETED
        val completed = store.transition("task-flow", TaskExecutionStatus.COMPLETED)
        assertEquals(TaskExecutionStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)
        assertTrue(completed.status.isTerminal)
    }

    @Test(expected = IllegalStateException::class)
    fun `test contradictory transition from terminal state throws IllegalStateException`() {
        val record = DurableTaskRecord(
            taskId = "task-terminal",
            projectId = "p-1",
            projectSlug = "test-project",
            chatId = "chat-1",
            agentKind = "CLAUDE",
            providerJson = "CLAUDE_LOGIN",
            prompt = "Build app",
            status = TaskExecutionStatus.COMPLETED
        )
        store.save(record)

        // Trying to transition COMPLETED back to RUNNING must fail
        store.transition("task-terminal", TaskExecutionStatus.RUNNING)
    }

    @Test
    fun `test query active tasks ignores terminal states`() {
        store.save(DurableTaskRecord(
            taskId = "t-active-1", projectId = "p-1", projectSlug = "slug", chatId = "c-1",
            agentKind = "CLAUDE", providerJson = "{}", prompt = "A", status = TaskExecutionStatus.RUNNING
        ))
        store.save(DurableTaskRecord(
            taskId = "t-active-2", projectId = "p-1", projectSlug = "slug", chatId = "c-1",
            agentKind = "CLAUDE", providerJson = "{}", prompt = "B", status = TaskExecutionStatus.STARTING
        ))
        store.save(DurableTaskRecord(
            taskId = "t-done", projectId = "p-1", projectSlug = "slug", chatId = "c-1",
            agentKind = "CLAUDE", providerJson = "{}", prompt = "C", status = TaskExecutionStatus.COMPLETED
        ))
        store.save(DurableTaskRecord(
            taskId = "t-failed", projectId = "p-1", projectSlug = "slug", chatId = "c-1",
            agentKind = "CLAUDE", providerJson = "{}", prompt = "D", status = TaskExecutionStatus.FAILED
        ))

        val active = store.getActiveTasks()
        assertEquals(2, active.size)
        assertTrue(active.any { it.taskId == "t-active-1" })
        assertTrue(active.any { it.taskId == "t-active-2" })
        assertFalse(active.any { it.taskId == "t-done" })
        assertFalse(active.any { it.taskId == "t-failed" })
    }

    @Test
    fun `test error recording transitions to RECOVERING when retries remain and FAILED when exhausted`() {
        val record = DurableTaskRecord(
            taskId = "t-retry",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Compile code",
            status = TaskExecutionStatus.RUNNING,
            retryCount = 0,
            maxRetries = 2
        )
        store.save(record)

        // Attempt 1 failure -> RECOVERING
        val recovering1 = store.recordError("t-retry", "Network socket timeout", canRetry = true)
        assertEquals(TaskExecutionStatus.RECOVERING, recovering1?.status)
        assertEquals(1, recovering1?.retryCount)
        assertTrue(recovering1?.recoveryRequired == true)

        // Reset to RUNNING
        store.transition("t-retry", TaskExecutionStatus.RUNNING)

        // Attempt 2 failure -> RECOVERING
        val recovering2 = store.recordError("t-retry", "Network socket timeout", canRetry = true)
        assertEquals(TaskExecutionStatus.RECOVERING, recovering2?.status)
        assertEquals(2, recovering2?.retryCount)

        // Reset to RUNNING
        store.transition("t-retry", TaskExecutionStatus.RUNNING)

        // Attempt 3 failure -> FAILED (maxRetries exhausted)
        val failed = store.recordError("t-retry", "Network socket timeout", canRetry = true)
        assertEquals(TaskExecutionStatus.FAILED, failed?.status)
        assertEquals(3, failed?.retryCount)
        assertFalse(failed?.recoveryRequired == true)
    }
}
