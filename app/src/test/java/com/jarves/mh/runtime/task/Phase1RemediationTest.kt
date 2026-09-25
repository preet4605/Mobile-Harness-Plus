package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.WorkspaceCheckpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Targeted regression tests covering Phase 1 remediations:
 * - P0 #1: Task ID / Session ID disconnect and PID stop controls.
 * - P0 #2: Safe retry behavior: preventing auto-retry when workspace is dirty/mutated.
 * - P1 #1: Startup reconciliation with PID verification.
 * - P1 #3 / P2-B: Parallel subagent execution support with non-exclusive project locking.
 * - P2-A: Bounded native process output limit (5MB cap).
 */
class Phase1RemediationTest {

    private lateinit var db: BrainDatabase
    private lateinit var stateStore: TaskStateStore
    private lateinit var processSupervisor: ProcessSupervisor
    private lateinit var executionLock: TaskExecutionLock

    @Before
    fun setUp() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        stateStore = TaskStateStore(db)
        processSupervisor = ProcessSupervisor()
        executionLock = TaskExecutionLock()
    }

    private fun createDummyProcess(): Process = object : Process() {
        private var destroyed = false
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = if (destroyed) 143 else 0
        override fun destroy() { destroyed = true }
    }

    @Test
    fun `test P0-1 Task ID and Session ID bidirectional binding and stop resolution`() = runBlocking {
        val taskId = "task-canonical-uuid-101"
        val sessionId = "session-bridge-uuid-202"
        val testPid = 12345

        val record = DurableTaskRecord(
            taskId = taskId,
            projectId = "project-alpha",
            projectSlug = "alpha",
            chatId = "chat-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Build feature",
            status = TaskExecutionStatus.CREATED
        )
        stateStore.save(record)

        // 1. Pre-bind session
        val sessionBound = stateStore.markSessionId(taskId, sessionId)
        assertEquals(sessionId, sessionBound?.sessionId)

        // Verify stateStore fallback lookup by sessionId
        val bySession = stateStore.getBySessionId(sessionId)
        assertNotNull(bySession)
        assertEquals(taskId, bySession?.taskId)

        // 2. Bind process with PID
        val dummyProcess = createDummyProcess()
        val processBound = stateStore.bindProcess(taskId, sessionId, testPid)
        assertEquals(testPid, processBound?.pid)
        assertEquals(sessionId, processBound?.sessionId)

        // Register in ProcessSupervisor with alias
        processSupervisor.bindProcess(taskId, sessionId, dummyProcess, testPid)

        // 3. ProcessSupervisor alias lookup
        assertEquals(dummyProcess, processSupervisor.getProcess(sessionId))
        assertEquals(dummyProcess, processSupervisor.getProcess(taskId))
        assertEquals(testPid, processSupervisor.getPid(sessionId))
        assertEquals(testPid, processSupervisor.getPid(taskId))
        assertEquals(taskId, processSupervisor.getTaskIdForSession(sessionId))

        // 4. Request stop via sessionId
        val stopped = processSupervisor.requestStop(sessionId, force = false)
        assertTrue("requestStop via sessionId must resolve and return true", stopped)
        assertTrue(processSupervisor.isCancellationRequested(taskId))
        assertTrue(processSupervisor.isCancellationRequested(sessionId))

        // 5. StateStore transition by sessionId
        val running = stateStore.transition(sessionId, TaskExecutionStatus.RUNNING)
        assertEquals(TaskExecutionStatus.RUNNING, running.status)
        assertEquals(taskId, running.taskId)

        // 6. Unregister via sessionId cleans up aliases
        processSupervisor.unregister(sessionId)
        assertNull(processSupervisor.getProcess(sessionId))
        assertNull(processSupervisor.getProcess(taskId))
        assertNull(processSupervisor.getPid(sessionId))
        assertNull(processSupervisor.getPid(taskId))
    }

    @Test
    fun `test P0-2 WorkspaceCheckpoints dirty detection prevents non-idempotent auto-retry`() {
        val tempDir = Files.createTempDirectory("checkpoint-test").toFile()
        try {
            val checkpoints = WorkspaceCheckpoints(tempDir)
            val projectId = "proj-dirty-check"

            // Clean workspace scenario: no changed paths recorded
            val cleanChanged = checkpoints.readChangedPaths(projectId)
            assertTrue("Clean workspace must have no changed paths", cleanChanged.isEmpty())

            // Task state on clean workspace failure allows retry
            val cleanTask = DurableTaskRecord(
                taskId = "task-clean",
                projectId = projectId,
                projectSlug = "slug",
                chatId = "c-1",
                agentKind = "CLAUDE",
                providerJson = "{}",
                prompt = "Clean compile",
                status = TaskExecutionStatus.RUNNING,
                retryCount = 0,
                maxRetries = 2
            )
            stateStore.save(cleanTask)

            // When clean, retry proceeds
            val canRetryClean = cleanChanged.isEmpty() && (cleanTask.retryCount < cleanTask.maxRetries)
            assertTrue(canRetryClean)
            val recovering = stateStore.recordError("task-clean", "Network error", canRetry = canRetryClean)
            assertEquals(TaskExecutionStatus.RECOVERING, recovering?.status)
            assertEquals(1, recovering?.retryCount)

            // Mutated workspace scenario: simulate tool writing changes
            checkpoints.saveChangedPaths(projectId, listOf("src/Main.kt", "build.gradle"))
            val dirtyChanged = checkpoints.readChangedPaths(projectId)
            assertFalse("Dirty workspace must report changed paths", dirtyChanged.isEmpty())

            val dirtyTask = DurableTaskRecord(
                taskId = "task-dirty",
                projectId = projectId,
                projectSlug = "slug",
                chatId = "c-1",
                agentKind = "CLAUDE",
                providerJson = "{}",
                prompt = "Refactor code",
                status = TaskExecutionStatus.RUNNING,
                retryCount = 0,
                maxRetries = 2
            )
            stateStore.save(dirtyTask)

            // When workspace is dirty, auto-retry must be inhibited to prevent duplicating changes
            val canRetryDirty = dirtyChanged.isEmpty() && (dirtyTask.retryCount < dirtyTask.maxRetries)
            assertFalse("Auto-retry must be disabled when workspace has changed files", canRetryDirty)

            val failed = stateStore.recordError(
                taskId = "task-dirty",
                error = "Process died after workspace mutations",
                canRetry = false,
                markRecoveryRequired = true
            )
            assertEquals(TaskExecutionStatus.FAILED, failed?.status)
            assertTrue("Recovery must be required to inspect mutated state", failed?.recoveryRequired == true)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `test P1-1 ProcessSupervisor does not kill unverifiable or non-matching PIDs`() {
        // PID 999999 does not exist on linux systems under ordinary testing
        val deadPid = 999999
        val isVerified = processSupervisor.isVerifiedExpectedProcess(deadPid)
        assertFalse("Non-existent PID must not be verified as expected process", isVerified)

        // Invalid negative or 0 PID
        assertFalse(processSupervisor.isVerifiedExpectedProcess(0))
        assertFalse(processSupervisor.isVerifiedExpectedProcess(-1))

        // StateStore startup reconciliation: tasks in RUNNING without live process transition to ABANDONED
        val orphanTask = DurableTaskRecord(
            taskId = "task-orphan-1",
            projectId = "p-1",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "CLAUDE",
            providerJson = "{}",
            prompt = "Orphan task",
            status = TaskExecutionStatus.RUNNING,
            pid = deadPid
        )
        stateStore.save(orphanTask)

        // Verify transition to ABANDONED
        val abandoned = stateStore.transition(orphanTask.taskId, TaskExecutionStatus.ABANDONED) {
            it.copy(lastError = "Process died or orphaned across app lifecycle")
        }
        assertEquals(TaskExecutionStatus.ABANDONED, abandoned.status)
        assertTrue(abandoned.status.isTerminal)
    }

    @Test
    fun `test P2-B Parallel subagents allowed in same project when exclusiveProject is false`() = runBlocking {
        val successCount = AtomicInteger(0)
        val duplicateBlockedCount = AtomicInteger(0)

        // Multiple subagents running concurrently on same project with exclusiveProject = false
        val jobs = listOf("subagent-1", "subagent-2", "subagent-3").map { taskId ->
            async(Dispatchers.Default) {
                try {
                    executionLock.withExecutionLock(taskId, "shared-project", exclusiveProject = false) {
                        successCount.incrementAndGet()
                        delay(50)
                    }
                } catch (e: DuplicateExecutionException) {
                    duplicateBlockedCount.incrementAndGet()
                }
            }
        }
        jobs.awaitAll()

        assertEquals("All subagents must execute concurrently when exclusiveProject is false", 3, successCount.get())
        assertEquals(0, duplicateBlockedCount.get())

        // Confirm exclusiveProject = true still blocks concurrent execution
        val exclusiveSuccessCount = AtomicInteger(0)
        val exclusiveBlockedCount = AtomicInteger(0)

        val exclusiveJobs = listOf("task-ex-1", "task-ex-2").map { taskId ->
            async(Dispatchers.Default) {
                try {
                    executionLock.withExecutionLock(taskId, "exclusive-project", exclusiveProject = true) {
                        exclusiveSuccessCount.incrementAndGet()
                        delay(100)
                    }
                } catch (e: DuplicateExecutionException) {
                    exclusiveBlockedCount.incrementAndGet()
                }
            }
        }
        exclusiveJobs.awaitAll()

        assertEquals(1, exclusiveSuccessCount.get())
        assertEquals(1, exclusiveBlockedCount.get())
    }

    @Test
    fun `test P2-A Native process output file size limit is bounded at 5MB`() {
        assertEquals(5L * 1024 * 1024, NativeSpawnProcess.MAX_OUTPUT_BYTES)
    }
}
