package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeReliabilityAndRecoveryTest {

    private lateinit var db: BrainDatabase
    private lateinit var store: TaskStateStore
    private lateinit var processSupervisor: ProcessSupervisor
    private lateinit var healthMonitor: RuntimeHealthMonitor

    @Before
    fun setUp() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        store = TaskStateStore(db)
        processSupervisor = ProcessSupervisor()
        healthMonitor = RuntimeHealthMonitor()
    }

    @Test
    fun `test RUNNING to successful completion flow`() {
        val task = DurableTaskRecord(
            taskId = "task-complete-flow",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Run test suite",
            status = TaskExecutionStatus.CREATED
        )
        store.save(task)
        healthMonitor.onTaskStarted(task.taskId, null)

        store.transition(task.taskId, TaskExecutionStatus.STARTING)
        val running = store.transition(task.taskId, TaskExecutionStatus.RUNNING)
        assertEquals(TaskExecutionStatus.RUNNING, running.status)
        assertNotNull(running.startedAt)

        store.transition(task.taskId, TaskExecutionStatus.COMPLETING)
        val completed = store.transition(task.taskId, TaskExecutionStatus.COMPLETED)
        assertEquals(TaskExecutionStatus.COMPLETED, completed.status)
        assertNotNull(completed.completedAt)
        assertTrue(completed.status.isTerminal)

        healthMonitor.onTaskCompleted(task.taskId, null)
        assertEquals(1, healthMonitor.snapshot.value.totalTasksCompleted)
        assertEquals(0, healthMonitor.snapshot.value.activeTaskCount)
    }

    @Test
    fun `test RUNNING to user cancellation flow`() {
        val task = DurableTaskRecord(
            taskId = "task-cancel-flow",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Long running compile",
            status = TaskExecutionStatus.RUNNING
        )
        store.save(task)

        processSupervisor.markCancellationRequested(task.taskId)
        val cancelled = store.transition(task.taskId, TaskExecutionStatus.CANCELLED) {
            it.copy(lastError = "Cancelled by user")
        }

        assertEquals(TaskExecutionStatus.CANCELLED, cancelled.status)
        assertEquals("Cancelled by user", cancelled.lastError)
        assertTrue(cancelled.status.isTerminal)
    }

    @Test
    fun `test RUNNING process crash with retry recovery flow`() {
        val task = DurableTaskRecord(
            taskId = "task-crash-flow",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Fix bug",
            status = TaskExecutionStatus.RUNNING,
            retryCount = 0,
            maxRetries = 2
        )
        store.save(task)

        // Process crashed: classify exit
        val exitResult = processSupervisor.classifyExit(139) // SIGSEGV
        assertEquals(ProcessExitType.CRASH, exitResult.exitType)

        // Attempt 1 -> transitions to RECOVERING
        val recovering = store.recordError(task.taskId, "Process crashed with exit code 139", canRetry = true)
        assertEquals(TaskExecutionStatus.RECOVERING, recovering?.status)
        assertEquals(1, recovering?.retryCount)
        assertTrue(recovering?.recoveryRequired == true)
        healthMonitor.onTaskRecovered(task.taskId)

        // Retry resumes execution -> STARTING -> RUNNING
        store.transition(task.taskId, TaskExecutionStatus.STARTING)
        val resumed = store.transition(task.taskId, TaskExecutionStatus.RUNNING)
        assertEquals(TaskExecutionStatus.RUNNING, resumed.status)

        // Succeeded on retry
        store.transition(task.taskId, TaskExecutionStatus.COMPLETING)
        val completed = store.transition(task.taskId, TaskExecutionStatus.COMPLETED)
        assertEquals(TaskExecutionStatus.COMPLETED, completed.status)
        assertEquals(1, completed.retryCount)
    }

    @Test
    fun `test retry limit reached transitions to FAILED`() {
        val task = DurableTaskRecord(
            taskId = "task-exhausted",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Broken task",
            status = TaskExecutionStatus.RUNNING,
            retryCount = 2,
            maxRetries = 2
        )
        store.save(task)

        // Attempt 3 failure with maxRetries = 2 -> FAILED
        val failed = store.recordError(task.taskId, "Fatal failure", canRetry = true)
        assertEquals(TaskExecutionStatus.FAILED, failed?.status)
        assertEquals(3, failed?.retryCount)
        assertFalse(failed?.recoveryRequired == true)
        assertTrue(failed?.status?.isTerminal == true)

        healthMonitor.onTaskFailed(task.taskId, "Fatal failure", null)
        assertEquals(1, healthMonitor.snapshot.value.totalTasksFailed)
    }

    @Test
    fun `test Android OS process death simulation and startup reconciliation`() {
        // Task was RUNNING when Android killed the process
        val deadTask = DurableTaskRecord(
            taskId = "task-zombie",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Active when process died",
            status = TaskExecutionStatus.RUNNING,
            pid = 999999 // Non-existent dead PID
        )
        store.save(deadTask)

        val activeBefore = store.getActiveTasks()
        assertEquals(1, activeBefore.size)

        // Reconcile on startup: dead process is detected
        for (task in activeBefore) {
            val isAlive = task.pid != null && processSupervisor.isProcessAlive(task.pid)
            assertFalse(isAlive)
            val reconciled = store.transition(task.taskId, TaskExecutionStatus.ABANDONED) {
                it.copy(
                    lastError = "Process terminated due to application process death / system restart",
                    recoveryRequired = true
                )
            }
            healthMonitor.onTaskAbandoned(task.taskId, task.pid)
            assertEquals(TaskExecutionStatus.ABANDONED, reconciled.status)
            assertTrue(reconciled.recoveryRequired)
        }

        // Active task query must now be empty (no zombie tasks)
        val activeAfter = store.getActiveTasks()
        assertTrue(activeAfter.isEmpty())
        assertEquals(1, healthMonitor.snapshot.value.totalTasksAbandoned)
    }

    @Test
    fun `test STARTING to failure when process never launches`() {
        val task = DurableTaskRecord(
            taskId = "task-no-launch",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Task with missing executable",
            status = TaskExecutionStatus.STARTING
        )
        store.save(task)

        val failed = store.transition(task.taskId, TaskExecutionStatus.FAILED) {
            it.copy(lastError = "Executable not found")
        }

        assertEquals(TaskExecutionStatus.FAILED, failed.status)
        assertEquals("Executable not found", failed.lastError)
        assertTrue(failed.status.isTerminal)
    }
}
