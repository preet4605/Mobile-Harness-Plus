package com.jarves.mh.runtime

import java.util.concurrent.ConcurrentHashMap

/**
 * Stop state shared by a runtime bridge instance. The user-stop flag is scoped to the
 * current session (reset by [beginSession]); stopped task ids persist so a stopped task
 * cannot be restarted on the same bridge.
 */
internal class BridgeStopState {
    @Volatile var userStopRequested: Boolean = false
    private val stoppedTaskIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Clears the previous session's stop flag, then returns true if [taskId] was already
     * stopped or cancelled, in which case the flag is set again for this session.
     */
    fun beginSession(taskId: String?, cancellationActive: Boolean): Boolean {
        userStopRequested = false
        val stopped = cancellationActive || (taskId != null && stoppedTaskIds.contains(taskId))
        if (stopped) requestStop(taskId)
        return stopped
    }

    fun requestStop(taskId: String?) {
        userStopRequested = true
        if (taskId != null) stoppedTaskIds.add(taskId)
    }
}
