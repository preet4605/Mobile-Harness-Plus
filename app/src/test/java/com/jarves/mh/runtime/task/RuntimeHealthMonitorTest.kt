package com.jarves.mh.runtime.task

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeHealthMonitorTest {
    @Test fun finalizingAnAlreadyClosedProcessPreservesOtherLiveProcesses() {
        val endings: List<(RuntimeHealthMonitor, Int?) -> Unit> = listOf(
            { monitor, pid -> monitor.onTaskCompleted("a", pid) },
            { monitor, pid -> monitor.onTaskFailed("a", "failure", pid) },
            { monitor, pid -> monitor.onTaskAbandoned("a", pid) },
        )
        endings.forEach { end ->
            val monitor = RuntimeHealthMonitor()
            monitor.onTaskStarted("a", null)
            monitor.onTaskStarted("b", null)
            monitor.onTaskProcessBound("a", 11)
            monitor.onTaskProcessBound("b", 22)
            monitor.onTaskProcessClosed(11)
            end(monitor, 11)
            assertEquals(1, monitor.snapshot.value.activeProcessCount)
            assertEquals(listOf(22), monitor.snapshot.value.activePids)
        }
    }

    @Test fun failureBeforeProcessBindingPreservesOtherLiveProcesses() {
        val monitor = RuntimeHealthMonitor()
        monitor.onTaskStarted("a", null)
        monitor.onTaskStarted("b", null)
        monitor.onTaskProcessBound("b", 22)
        monitor.onTaskFailed("a", "binding failed", null)
        assertEquals(1, monitor.snapshot.value.activeProcessCount)
        assertEquals(listOf(22), monitor.snapshot.value.activePids)
    }
}
