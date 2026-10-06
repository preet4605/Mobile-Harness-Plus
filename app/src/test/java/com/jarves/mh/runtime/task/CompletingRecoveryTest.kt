package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CompletingRecoveryTest {
    private lateinit var supervisor: TaskSupervisor

    @Before
    fun setUp() {
        supervisor = TaskSupervisor.createForTesting(
            context = null,
            database = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver()),
        )
    }

    private fun task(id: String, target: TaskExecutionStatus): String {
        supervisor.createTask(
            taskId = id, projectId = "p-$id", projectSlug = "s-$id", chatId = "c", agentKind = "CLAUDE_CODE",
            providerJson = "{}", prompt = "x",
            plan = ExecutionPlan(steps = listOf(ExecutionStep(stepOrder = 0, title = "s", description = "d"))),
        )
        val store = supervisor.stateStore
        store.transition(id, TaskExecutionStatus.STARTING)
        store.transition(id, TaskExecutionStatus.RUNNING)
        if (target == TaskExecutionStatus.COMPLETING) store.transition(id, TaskExecutionStatus.COMPLETING)
        return id
    }

    private fun status(id: String) = supervisor.stateStore.get(id)!!.status

    @Test
    fun completingTaskIsReconciledToFailedWithRecoveryRequired() {
        val id = task("c1", TaskExecutionStatus.COMPLETING)
        val reconciled = supervisor.reconcileOnStartup()
        assertTrue(reconciled.any { it.taskId == id })
        val rec = supervisor.stateStore.get(id)!!
        assertEquals(TaskExecutionStatus.FAILED, rec.status)
        assertTrue(rec.recoveryRequired)
    }

    @Test
    fun completingDoesNotBlockReconciliationOfOtherTasks() {
        val a = task("a", TaskExecutionStatus.COMPLETING)
        val b = task("b", TaskExecutionStatus.RUNNING)
        val c = task("c", TaskExecutionStatus.COMPLETING)
        supervisor.reconcileOnStartup()
        assertEquals(TaskExecutionStatus.FAILED, status(a))
        assertEquals(TaskExecutionStatus.ABANDONED, status(b))
        assertEquals(TaskExecutionStatus.FAILED, status(c))
    }

    @Test
    fun reconciliationIsIdempotent() {
        val id = task("i", TaskExecutionStatus.COMPLETING)
        supervisor.reconcileOnStartup()
        val second = supervisor.reconcileOnStartup()
        assertTrue(second.none { it.taskId == id })
        assertEquals(TaskExecutionStatus.FAILED, status(id))
    }

    @Test
    fun normalCompletingTransitionsStillWork() {
        listOf(
            TaskExecutionStatus.COMPLETED, TaskExecutionStatus.FAILED, TaskExecutionStatus.CANCELLED,
        ).forEach { to ->
            assertTrue(TaskStateMachine.canTransition(TaskExecutionStatus.COMPLETING, to))
        }
        assertTrue(!TaskStateMachine.canTransition(TaskExecutionStatus.COMPLETING, TaskExecutionStatus.ABANDONED))
        assertTrue(!TaskStateMachine.canTransition(TaskExecutionStatus.COMPLETING, TaskExecutionStatus.RUNNING))
        val id = task("n", TaskExecutionStatus.COMPLETING)
        supervisor.stateStore.transition(id, TaskExecutionStatus.COMPLETED)
        assertEquals(TaskExecutionStatus.COMPLETED, status(id))
    }
}
