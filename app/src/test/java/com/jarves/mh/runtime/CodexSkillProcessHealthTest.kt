package com.jarves.mh.runtime

import com.jarves.mh.runtime.task.RuntimeHealthMonitor
import org.junit.Assert.*
import org.junit.Test

class CodexSkillProcessHealthTest {
    @Test fun reapedDiscoveryProcessDoesNotFinishTaskOrLeaveGhostPid() {
        val monitor = RuntimeHealthMonitor()
        monitor.onTaskStarted("task", null)
        monitor.onTaskProcessBound("task", 11)
        monitor.onTaskProcessClosed(11)
        monitor.onTaskProcessClosed(11)
        assertEquals(listOf("task"), monitor.snapshot.value.activeTaskIds)
        assertEquals(1, monitor.snapshot.value.activeTaskCount)
        assertEquals(0, monitor.snapshot.value.activeProcessCount)
        assertEquals(0L, monitor.snapshot.value.totalTasksCompleted)
        monitor.onTaskProcessBound("task", 22)
        monitor.onTaskCompleted("task", 22)
        assertTrue(monitor.snapshot.value.activePids.isEmpty())
        assertEquals(0, monitor.snapshot.value.activeProcessCount)
        assertEquals(1L, monitor.snapshot.value.totalTasksCompleted)
    }
}
