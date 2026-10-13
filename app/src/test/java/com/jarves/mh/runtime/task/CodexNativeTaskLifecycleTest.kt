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
