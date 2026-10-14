package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.runtime.CodexNativeControl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodexNativeTaskLifecycleTest {
    @Before fun loadJdbcDriverForThisClassLoader() { Class.forName("org.sqlite.JDBC") }

    private fun liveChild(): Process = object : Process() {
        private var alive = true
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor() = 0
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
        override fun destroy() { alive = false }
    }

    @Test fun terminalTaskRejectsAndReapsLateRuntimeChild() {
        val db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        try {
            val supervisor = TaskSupervisor.createForTesting(database = db)
            supervisor.createTask(taskId = "task", projectId = "project", projectSlug = "slug", chatId = "chat",
                agentKind = "CLAUDE_CODE", providerJson = "CLAUDE", prompt = "Execute")
            supervisor.finalizeTask("task", TaskExecutionStatus.CANCELLED)
            val child = liveChild()

            assertFalse(supervisor.bindProcess("task", "late-session", child, 42))
            assertFalse("Rejected child must be terminated", child.isAlive)
            assertNull(supervisor.processSupervisor.getProcess("task"))
            assertNull(supervisor.processSupervisor.getProcess("late-session"))
            assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get("task")!!.status)
        } finally { db.close() }
    }

    @Test fun missingTaskCannotClaimAnotherTasksSession() {
        val db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        try {
            val supervisor = TaskSupervisor.createForTesting(database = db)
            supervisor.createTask(taskId = "owner", projectId = "project", projectSlug = "slug", chatId = "chat",
                agentKind = "CLAUDE_CODE", providerJson = "CLAUDE", prompt = "Execute")
            supervisor.bindSession("owner", "owned-session")
            val child = liveChild()

            assertFalse(supervisor.bindProcess("missing", "owned-session", child, 42))
            assertFalse(child.isAlive)
            assertNull(supervisor.processSupervisor.getProcess("missing"))
            assertNull(supervisor.processSupervisor.getProcess("owned-session"))
            assertNull(supervisor.stateStore.get("owner")!!.pid)
        } finally { db.close() }
    }

    @Test fun activeTaskCannotClaimAnotherTasksSession() {
        val db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        try {
            val supervisor = TaskSupervisor.createForTesting(database = db)
            listOf("owner", "other").forEach { id ->
                supervisor.createTask(taskId = id, projectId = id, projectSlug = id, chatId = "chat",
                    agentKind = "CLAUDE_CODE", providerJson = "CLAUDE", prompt = "Execute")
            }
            supervisor.bindSession("owner", "owned-session")
            val child = liveChild()
            assertFalse(supervisor.bindProcess("other", "owned-session", child, 42))
            assertFalse(child.isAlive)
            assertNull(supervisor.processSupervisor.getProcess("other"))
            assertNull(supervisor.stateStore.get("other")!!.pid)
            assertEquals("owner", supervisor.stateStore.getBySessionId("owned-session")!!.taskId)
        } finally { db.close() }
    }

    @Test fun multiStepObjectiveStillAdmitsOneNativeOperationWithoutAutomaticReplay() {
        val db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        val supervisor = TaskSupervisor.createForTesting(database = db)
        val record = supervisor.createTask(taskId = "task", projectId = "project", projectSlug = "slug", chatId = "chat", agentKind = "CODEX", providerJson = "CHATGPT",
            prompt = "1. inspect source\n2. run tests", maxRetries = 0,
            nativeControlJson = CodexNativeControl.parse("goal", "1. inspect source\n2. run tests").encode())
        val steps = supervisor.canonicalTaskRepository.getTask(record.taskId)!!.plan.steps
        assertEquals(1, steps.size)
        assertEquals(1, steps.single().maxAttempts)
        assertEquals(0, record.maxRetries)
        db.close()
    }

    @Test fun stopPersistsNativePauseBeforeGenericTerminationAndIsScopedToOwner() = runBlocking {
        val db = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
        val supervisor = TaskSupervisor.createForTesting(database = db)
        val order = mutableListOf<String>()
        supervisor.createTask(taskId = "task", projectId = "project", projectSlug = "slug", chatId = "chat", agentKind = "CODEX", providerJson = "CHATGPT", prompt = "Execute native goal")
        supervisor.stateStore.transition("task", TaskExecutionStatus.STARTING)
        supervisor.bindSession("task", "session")
        val process = object : Process() {
            private var alive = true
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
            override fun destroy() { order += "terminate"; alive = false }
        }
        supervisor.bindProcess("task", "session", process)
        supervisor.registerStopHandler("other") { order += "wrong owner" }
        supervisor.registerStopHandler("task") { force -> assertFalse(force); order += "native pause" }
        supervisor.requestStop("session")
        assertEquals(listOf("native pause", "terminate"), order)
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get("task")!!.status)
        supervisor.unregisterStopHandler("other")
        db.close()
    }
}
