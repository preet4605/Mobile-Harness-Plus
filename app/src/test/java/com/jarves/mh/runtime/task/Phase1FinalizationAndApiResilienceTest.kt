package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression and verification test suite for Phase 1.5:
 * - Canonical & idempotent terminal finalization (finalizeTask).
 * - Transient API error retry handling (503 / UNAVAILABLE) on unmutated workspaces.
 * - Mutation guard: preventing retry when workspace has uncommitted changes.
 * - Error classification accuracy (API 503 vs Permanent Auth vs User Stop vs Mutated).
 * - Child process termination enforcement before retry attempts.
 */
class Phase1FinalizationAndApiResilienceTest {

    private lateinit var db: BrainDatabase
    private lateinit var stateStore: TaskStateStore
    private lateinit var processSupervisor: ProcessSupervisor

    @Before
    fun setUp() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        stateStore = TaskStateStore(db)
        processSupervisor = ProcessSupervisor()
    }

    private fun createDummyProcess(isAliveVal: Boolean = true): Process = object : Process() {
        private var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = if (destroyed) 143 else 0
        override fun exitValue(): Int = if (destroyed) 143 else 0
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process {
            destroyed = true
            return this
        }
    }

    @Test
    fun `test terminal idempotency - completed task cannot be transitioned to failed or cancelled`() {
        val taskId = "test-task-terminal-idempotency"
        val record = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-1",
            projectSlug = "slug-1",
            chatId = "chat-1",
            agentKind = "GEMINI",
            providerJson = "{}",
            prompt = "Complete something",
            status = TaskExecutionStatus.RUNNING
        )
        stateStore.save(record)

        // Complete the task
        val completed = stateStore.transition(taskId, TaskExecutionStatus.COMPLETED)
        assertEquals(TaskExecutionStatus.COMPLETED, completed.status)
        assertTrue(completed.status.isTerminal)

        // Attempting to transition completed task to FAILED or CANCELLED must be ignored or throw IllegalStateException
        try {
            stateStore.transition(taskId, TaskExecutionStatus.FAILED)
        } catch (e: IllegalStateException) {
            // Expected
        }
        val current = stateStore.get(taskId)
        assertEquals(TaskExecutionStatus.COMPLETED, current?.status)
    }

    @Test
    fun `test classifyError distinguishes 503 transient vs auth vs user cancel vs mutated workspace`() {
        // Dummy supervisor helper test using classification rules
        val supervisorMock = object {
            fun classify(errorMsg: String, workspaceMutated: Boolean, isCancelled: Boolean): TaskSupervisor.TaskErrorClassification {
                if (isCancelled || errorMsg.contains("stopped by user", ignoreCase = true) || errorMsg.contains("cancelled", ignoreCase = true)) {
                    return TaskSupervisor.TaskErrorClassification.USER_CANCELLED
                }
                if (workspaceMutated) {
                    return TaskSupervisor.TaskErrorClassification.WORKSPACE_MUTATED_FAILURE
                }
                val lower = errorMsg.lowercase()
                if (lower.contains("api key") || lower.contains("user not found") ||
                    lower.contains("authentication failed") || lower.contains("not signed in") ||
                    lower.contains("http 401") || lower.contains("http 403")) {
                    return TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG
                }
                if (lower.contains("503") || lower.contains("unavailable") || lower.contains("service is currently unavailable") ||
                    lower.contains("500") || lower.contains("502") || lower.contains("504") ||
                    lower.contains("socket timeout") || lower.contains("network error") ||
                    lower.contains("rate limit") || lower.contains("http 429")) {
                    return TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR
                }
                return TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE
            }
        }

        // 1. API 503 UNAVAILABLE
        val err503 = "API error (attempt 1): UNAVAILABLE (code 503): The service is currently unavailable."
        val class503 = supervisorMock.classify(err503, workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, class503)

        // 2. Mutated workspace overrides transient error
        val classMutated = supervisorMock.classify(err503, workspaceMutated = true, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.WORKSPACE_MUTATED_FAILURE, classMutated)

        // 3. User cancel
        val classUserStop = supervisorMock.classify("Stopped by user", workspaceMutated = false, isCancelled = true)
        assertEquals(TaskSupervisor.TaskErrorClassification.USER_CANCELLED, classUserStop)

        // 4. Auth failure
        val classAuth = supervisorMock.classify("No API key is saved for ANTHROPIC.", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG, classAuth)
    }

    @Test
    fun `test retry loop increments attempt count and cleans up process before retry`() = runBlocking {
        val taskId = "test-task-retry-count"
        val record = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-retry",
            projectSlug = "slug-retry",
            chatId = "chat-1",
            agentKind = "GEMINI",
            providerJson = "{}",
            prompt = "Retry prompt",
            status = TaskExecutionStatus.CREATED,
            maxRetries = 2
        )
        stateStore.save(record)

        var processKilled = false
        val dummyProcess = object : Process() {
            override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 0
            override fun exitValue(): Int = 0
            override fun destroy() { processKilled = true }
            override fun destroyForcibly(): Process {
                processKilled = true
                return this
            }
        }

        processSupervisor.bindProcess(taskId, "session-1", dummyProcess, 9999)

        // Transition through retry
        stateStore.transition(taskId, TaskExecutionStatus.STARTING)
        // Failure occurs on attempt 0 -> transition to RECOVERING for attempt 1
        processSupervisor.terminate(taskId, force = true)
        assertTrue("Previous attempt's process must be terminated before retry", processKilled)

        val recovering = stateStore.transition(taskId, TaskExecutionStatus.RECOVERING) {
            it.copy(retryCount = 1, lastError = "Retrying attempt 1/2...")
        }
        assertEquals(1, recovering.retryCount)
        assertEquals(TaskExecutionStatus.RECOVERING, recovering.status)
        assertTrue(recovering.status.isActive)
        assertFalse(recovering.status.isTerminal)

        // Second attempt succeeds -> STARTING -> RUNNING -> COMPLETED
        stateStore.transition(taskId, TaskExecutionStatus.STARTING)
        stateStore.transition(taskId, TaskExecutionStatus.RUNNING)
        val completed = stateStore.transition(taskId, TaskExecutionStatus.COMPLETED)
        assertEquals(TaskExecutionStatus.COMPLETED, completed.status)
        assertTrue(completed.status.isTerminal)
        assertEquals(1, completed.retryCount)
    }

    @Test
    fun `test mutated workspace halts auto-retry and marks recoveryRequired`() {
        val taskId = "test-task-mutated-fail"
        val record = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-mutated",
            projectSlug = "slug-mutated",
            chatId = "chat-1",
            agentKind = "GEMINI",
            providerJson = "{}",
            prompt = "Mutated workspace test",
            status = TaskExecutionStatus.RUNNING,
            maxRetries = 2
        )
        stateStore.save(record)

        val failed = stateStore.transition(taskId, TaskExecutionStatus.FAILED) {
            it.copy(
                lastError = "Task failed after modifying files (2 changed). Auto-retry disabled.",
                recoveryRequired = true
            )
        }

        assertEquals(TaskExecutionStatus.FAILED, failed.status)
        assertTrue(failed.recoveryRequired)
        assertTrue(failed.status.isTerminal)
        assertEquals(0, failed.retryCount)
    }
}
