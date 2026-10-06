package com.jarves.mh.runtime.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessSupervisorTest {

    private lateinit var supervisor: ProcessSupervisor

    @Before
    fun setUp() {
        supervisor = ProcessSupervisor()
    }

    @Test
    fun `test exit code classification for normal, cancel, sigint, force kill, and crash`() {
        // Exit 0 -> NORMAL
        val normal = supervisor.classifyExit(0)
        assertEquals(ProcessExitType.NORMAL, normal.exitType)

        // Exit 130 -> INTERRUPTED_SIGINT
        val sigint = supervisor.classifyExit(130)
        assertEquals(ProcessExitType.INTERRUPTED_SIGINT, sigint.exitType)

        // User cancellation request
        supervisor.markCancellationRequested("t-user-cancel")
        val userCancelled = supervisor.classifyExit(130, "t-user-cancel")
        assertEquals(ProcessExitType.USER_CANCELLED, userCancelled.exitType)

        // Exit 137 / 143 -> FORCED_TERMINATION
        val sigkill = supervisor.classifyExit(137)
        assertEquals(ProcessExitType.FORCED_TERMINATION, sigkill.exitType)

        val sigterm = supervisor.classifyExit(143)
        assertEquals(ProcessExitType.FORCED_TERMINATION, sigterm.exitType)

        // Non-zero 1 -> CRASH
        val crash = supervisor.classifyExit(1)
        assertEquals(ProcessExitType.CRASH, crash.exitType)

        // Unusual exit code
        val unexpected = supervisor.classifyExit(255)
        assertEquals(ProcessExitType.UNEXPECTED_DEATH, unexpected.exitType)
    }

    @Test
    fun `test cancellation state tracking`() {
        assertFalse(supervisor.isCancellationRequested("task-cancel-test"))
        supervisor.markCancellationRequested("task-cancel-test")
        assertTrue(supervisor.isCancellationRequested("task-cancel-test"))

        supervisor.unregister("task-cancel-test")
        assertFalse(supervisor.isCancellationRequested("task-cancel-test"))
    }

    @Test
    fun `test register does not clear pending cancel and immediately terminates process`() {
        val taskId = "task-cancelled-before-register"
        supervisor.markCancellationRequested(taskId)
        assertTrue(supervisor.isCancellationRequested(taskId))

        var destroyedForcibly = false
        val dummyProcess = object : Process() {
            override fun getOutputStream(): java.io.OutputStream = java.io.ByteArrayOutputStream()
            override fun getInputStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream(): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = 137
            override fun exitValue(): Int = 137
            override fun destroy() {}
            override fun destroyForcibly(): Process {
                destroyedForcibly = true
                return this
            }
        }

        supervisor.register(taskId, dummyProcess, pid = 12345, sessionId = "session-test")

        // Cancel flag must remain sticky (F-02)
        assertTrue("Cancellation flag must remain set after register", supervisor.isCancellationRequested(taskId))
        assertTrue("Cancellation flag must be propagated to sessionId", supervisor.isCancellationRequested("session-test"))
        assertTrue("Process must be forcibly terminated on registration if cancel was pending", destroyedForcibly)
    }
}
