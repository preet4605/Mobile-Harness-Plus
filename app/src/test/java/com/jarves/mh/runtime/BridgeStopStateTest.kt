package com.jarves.mh.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeStopStateTest {

    @Test
    fun stopDoesNotBlockNextSessionForDifferentTask() {
        val state = BridgeStopState()
        assertFalse(state.beginSession("task-a", cancellationActive = false))
        state.requestStop("task-a")
        assertTrue(state.userStopRequested)

        assertFalse(state.beginSession("task-b", cancellationActive = false))
        assertFalse(state.userStopRequested)
    }

    @Test
    fun stopWithoutTaskDoesNotBlockNextSession() {
        val state = BridgeStopState()
        // stopActiveSession with no active session only sets the flag.
        state.userStopRequested = true

        assertFalse(state.beginSession(null, cancellationActive = false))
        assertFalse(state.beginSession("task-a", cancellationActive = false))
        assertFalse(state.userStopRequested)
    }

    @Test
    fun repeatedSessionsAfterStopAllStart() {
        val state = BridgeStopState()
        state.requestStop(null)
        repeat(3) { i ->
            assertFalse(state.beginSession("task-$i", cancellationActive = false))
        }
    }

    @Test
    fun stoppedTaskCannotRestart() {
        val state = BridgeStopState()
        state.requestStop("task-a")

        assertTrue(state.beginSession("task-a", cancellationActive = false))
        assertTrue(state.userStopRequested)
        assertFalse(state.beginSession("task-b", cancellationActive = false))
    }

    @Test
    fun supervisorCancellationBlocksSessionAndIsRemembered() {
        val state = BridgeStopState()
        assertTrue(state.beginSession("task-a", cancellationActive = true))
        assertTrue(state.userStopRequested)
        assertTrue(state.beginSession("task-a", cancellationActive = false))
    }

    @Test
    fun stopDuringSessionIsVisibleUntilNextSession() {
        val state = BridgeStopState()
        assertFalse(state.beginSession("task-a", cancellationActive = false))
        assertFalse(state.userStopRequested)
        state.requestStop("task-a")
        assertTrue(state.userStopRequested)
    }
}
