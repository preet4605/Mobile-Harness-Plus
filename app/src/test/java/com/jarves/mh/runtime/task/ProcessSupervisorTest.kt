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
}
