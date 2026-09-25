package com.jarves.mh.runtime.task

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RuntimeHealthSnapshot(
    val activeTaskCount: Int = 0,
    val activeProcessCount: Int = 0,
    val activeTaskIds: List<String> = emptyList(),
    val activePids: List<Int> = emptyList(),
    val wakeLockHeld: Boolean = false,
    val wakeLockActiveCount: Int = 0,
    val lastEventTimestamp: Long = 0L,
    val lastOutputTimestamp: Long = 0L,
    val totalTasksStarted: Long = 0L,
    val totalTasksCompleted: Long = 0L,
    val totalTasksFailed: Long = 0L,
    val totalTasksRecovered: Long = 0L,
    val totalTasksAbandoned: Long = 0L,
    val lastFailureReason: String? = null,
    val lastFailureTimestamp: Long = 0L,
    val recoveryAttempts: Int = 0,
    val memoryUsageMb: Long = 0L
)

/**
 * Event-driven observability monitor for runtime health, process supervision,
 * and system resources.
 */
class RuntimeHealthMonitor {

    private val _snapshot = MutableStateFlow(RuntimeHealthSnapshot())
    val snapshot: StateFlow<RuntimeHealthSnapshot> = _snapshot.asStateFlow()

    fun onTaskStarted(taskId: String, pid: Int?) {
        _snapshot.update { current ->
            current.copy(
                activeTaskCount = current.activeTaskCount + 1,
                activeTaskIds = (current.activeTaskIds + taskId).distinct(),
                activePids = (if (pid != null) current.activePids + pid else current.activePids).distinct(),
                totalTasksStarted = current.totalTasksStarted + 1,
                lastEventTimestamp = System.currentTimeMillis(),
                memoryUsageMb = getApproxMemoryUsageMb()
            )
        }
    }

    fun onTaskProcessBound(taskId: String, pid: Int) {
        _snapshot.update { current ->
            current.copy(
                activeProcessCount = current.activeProcessCount + 1,
                activePids = (current.activePids + pid).distinct(),
                lastEventTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun onTaskCompleted(taskId: String, pid: Int?) {
        _snapshot.update { current ->
            current.copy(
                activeTaskCount = (current.activeTaskCount - 1).coerceAtLeast(0),
                activeProcessCount = (current.activeProcessCount - 1).coerceAtLeast(0),
                activeTaskIds = current.activeTaskIds - taskId,
                activePids = if (pid != null) current.activePids - pid else current.activePids,
                totalTasksCompleted = current.totalTasksCompleted + 1,
                lastEventTimestamp = System.currentTimeMillis(),
                memoryUsageMb = getApproxMemoryUsageMb()
            )
        }
    }

    fun onTaskFailed(taskId: String, reason: String, pid: Int?) {
        val now = System.currentTimeMillis()
        _snapshot.update { current ->
            current.copy(
                activeTaskCount = (current.activeTaskCount - 1).coerceAtLeast(0),
                activeProcessCount = (current.activeProcessCount - 1).coerceAtLeast(0),
                activeTaskIds = current.activeTaskIds - taskId,
                activePids = if (pid != null) current.activePids - pid else current.activePids,
                totalTasksFailed = current.totalTasksFailed + 1,
                lastFailureReason = reason,
                lastFailureTimestamp = now,
                lastEventTimestamp = now,
                memoryUsageMb = getApproxMemoryUsageMb()
            )
        }
    }

    fun onTaskRecovered(taskId: String) {
        _snapshot.update { current ->
            current.copy(
                totalTasksRecovered = current.totalTasksRecovered + 1,
                recoveryAttempts = current.recoveryAttempts + 1,
                lastEventTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun onTaskAbandoned(taskId: String, pid: Int?) {
        _snapshot.update { current ->
            current.copy(
                activeTaskCount = (current.activeTaskCount - 1).coerceAtLeast(0),
                activeProcessCount = (current.activeProcessCount - 1).coerceAtLeast(0),
                activeTaskIds = current.activeTaskIds - taskId,
                activePids = if (pid != null) current.activePids - pid else current.activePids,
                totalTasksAbandoned = current.totalTasksAbandoned + 1,
                lastEventTimestamp = System.currentTimeMillis()
            )
        }
    }

    fun onWakeLockChanged(isHeld: Boolean, activeCount: Int) {
        _snapshot.update { current ->
            current.copy(
                wakeLockHeld = isHeld,
                wakeLockActiveCount = activeCount
            )
        }
    }

    fun onOutputReceived() {
        _snapshot.update { it.copy(lastOutputTimestamp = System.currentTimeMillis()) }
    }

    fun onEventReceived() {
        _snapshot.update { it.copy(lastEventTimestamp = System.currentTimeMillis()) }
    }

    private fun getApproxMemoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }
}
