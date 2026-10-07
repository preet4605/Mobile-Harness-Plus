package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.runtime.ControlledBrainInjector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression for N1 (Phase 1 re-audit): a second `executeTask` in a project whose lock is
 * still held used to throw [DuplicateExecutionException] out of the supervisor job, and the
 * job's `finally` then attempted the illegal CREATED -> ABANDONED transition. Both escaped
 * `launch` with no handler, which crashes the app on Android.
 *
 * These tests drive the real [TaskSupervisor.executeTask] path, not [TaskExecutionLock] alone.
 */
class ExecuteTaskLockConflictRegressionTest {

    private lateinit var db: BrainDatabase
    private lateinit var supervisor: TaskSupervisor
    private val uncaught = Collections.synchronizedList(mutableListOf<Throwable>())
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        ControlledBrainInjector.clearAll()
        db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        // Coroutine exceptions with no handler reach the thread's uncaught-exception handler,
        // which is what kills the process on Android.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught += t }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun createTask(taskId: String, projectId: String) = supervisor.createTask(
        taskId = taskId,
        projectId = projectId,
        projectSlug = "slug-$projectId",
        chatId = "chat-1",
        agentKind = "CLAUDE_CODE",
        providerJson = "{}",
        prompt = "Do work for $taskId"
    )

    private suspend fun awaitProjectLock(projectId: String) {
        withTimeout(5_000) {
            while (!supervisor.executionLock.isProjectActive(projectId)) delay(10)
        }
    }

    @Test
    fun `second task in a locked project fails cleanly instead of crashing`() = runBlocking {
        val projectId = "proj-lock-conflict"
        createTask("task-first", projectId)
        createTask("task-second", projectId)
        assertFalse(
            "Precondition: CREATED -> ABANDONED must be illegal (this is what the old finally block attempted)",
            TaskStateMachine.canTransition(TaskExecutionStatus.CREATED, TaskExecutionStatus.ABANDONED)
        )

        val releaseFirst = CompletableDeferred<Unit>()
        val firstJob = supervisor.executeTask("task-first") { _ -> releaseFirst.await() }
        awaitProjectLock(projectId)

        val secondRuns = AtomicInteger(0)
        val secondJob = supervisor.executeTask("task-second") { _ -> secondRuns.incrementAndGet() }
        secondJob.join()

        assertTrue("No exception may escape the supervisor job: $uncaught", uncaught.isEmpty())
        assertFalse("Second job must complete normally, not fail", secondJob.isCancelled)
        assertEquals("Second task's execution block must never run", 0, secondRuns.get())

        val second = supervisor.stateStore.get("task-second")
        assertNotNull(second)
        assertEquals(TaskExecutionStatus.FAILED, second!!.status)
        assertTrue(second.lastError.orEmpty().contains("Another task is still running"))

        // The first task is untouched and still owns the project lock.
        val firstWhileRunning = supervisor.stateStore.get("task-first")!!
        assertFalse("First task must keep running", firstWhileRunning.status.isTerminal)
        assertTrue(supervisor.executionLock.isProjectActive(projectId))

        releaseFirst.complete(Unit)
        firstJob.join()
        assertTrue("No exception may escape the first job: $uncaught", uncaught.isEmpty())
        val first = supervisor.stateStore.get("task-first")!!
        assertTrue("First task must reach a terminal state", first.status.isTerminal)
        assertFalse("Project lock must be released", supervisor.executionLock.isProjectActive(projectId))

        // After the lock is released, a new prompt in the same project runs normally.
        createTask("task-third", projectId)
        val thirdRuns = AtomicInteger(0)
        supervisor.executeTask("task-third") { _ -> thirdRuns.incrementAndGet() }.join()
        assertEquals(1, thirdRuns.get())
        assertTrue(uncaught.isEmpty())
    }

    @Test
    fun `duplicate launch of a running task does not finalize the running execution`() = runBlocking {
        val projectId = "proj-same-task"
        createTask("task-running", projectId)

        val release = CompletableDeferred<Unit>()
        val firstJob = supervisor.executeTask("task-running") { _ -> release.await() }
        awaitProjectLock(projectId)

        val duplicateRuns = AtomicInteger(0)
        val duplicateJob = supervisor.executeTask("task-running") { _ -> duplicateRuns.incrementAndGet() }
        duplicateJob.join()

        assertTrue("No exception may escape the duplicate job: $uncaught", uncaught.isEmpty())
        assertFalse(duplicateJob.isCancelled)
        assertEquals(0, duplicateRuns.get())
        val whileRunning = supervisor.stateStore.get("task-running")!!
        assertFalse(
            "Duplicate launch must not finalize the task that is still running (was ${whileRunning.status})",
            whileRunning.status.isTerminal
        )

        release.complete(Unit)
        firstJob.join()
        assertTrue(uncaught.isEmpty())
        assertTrue(supervisor.stateStore.get("task-running")!!.status.isTerminal)
    }
}
