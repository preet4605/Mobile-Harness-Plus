package com.jarves.mh.runtime.task

import android.util.Log
import com.jarves.mh.runtime.NativeSpawnProcess
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ProcessExitType {
    NORMAL,
    USER_CANCELLED,
    INTERRUPTED_SIGINT,
    FORCED_TERMINATION,
    CRASH,
    UNEXPECTED_DEATH
}

data class ProcessExitResult(
    val exitCode: Int,
    val exitType: ProcessExitType,
    val description: String
)

/**
 * Superintends native agent processes, captures exit codes, classifies termination reasons,
 * and deterministically cleans up process resources.
 */
class ProcessSupervisor {

    private data class TrackedProcess(
        val taskId: String,
        val process: Process,
        val pid: Int?,
        val startedAt: Long = System.currentTimeMillis()
    )

    private val trackedProcesses = ConcurrentHashMap<String, TrackedProcess>()
    private val sessionToTaskMap = ConcurrentHashMap<String, String>()
    private val userCancellationRequests = ConcurrentHashMap.newKeySet<String>()

    fun register(taskId: String, process: Process, pid: Int? = null, sessionId: String? = null) {
        val resolvedPid = pid ?: (process as? NativeSpawnProcess)?.processPid
        trackedProcesses[taskId] = TrackedProcess(taskId, process, resolvedPid)
        if (sessionId != null) {
            sessionToTaskMap[sessionId] = taskId
            // Also store sessionId key directly as alias
            trackedProcesses[sessionId] = TrackedProcess(taskId, process, resolvedPid)
        }
        userCancellationRequests.remove(taskId)
        if (sessionId != null) userCancellationRequests.remove(sessionId)
        runCatching { Log.d("ProcessSupervisor", "Registered process for task $taskId (PID: $resolvedPid, session: $sessionId)") }
    }

    fun bindProcess(taskId: String, sessionId: String, process: Process, pid: Int? = null) {
        register(taskId = taskId, process = process, pid = pid, sessionId = sessionId)
    }

    fun getTaskIdForSession(sessionId: String): String? = sessionToTaskMap[sessionId]

    suspend fun requestStop(taskIdOrSessionId: String, force: Boolean = false): Boolean {
        return terminate(taskIdOrSessionId, force = force)
    }

    fun unregister(taskIdOrSessionId: String): Process? {
        val canonicalId = sessionToTaskMap[taskIdOrSessionId] ?: taskIdOrSessionId
        val removed = trackedProcesses.remove(canonicalId)
        userCancellationRequests.remove(canonicalId)
        sessionToTaskMap.entries.removeIf { it.value == canonicalId || it.key == taskIdOrSessionId }
        trackedProcesses.remove(taskIdOrSessionId)
        return removed?.process
    }

    private fun resolveId(taskIdOrSessionId: String): String =
        sessionToTaskMap[taskIdOrSessionId] ?: taskIdOrSessionId

    fun getProcess(taskIdOrSessionId: String): Process? =
        trackedProcesses[resolveId(taskIdOrSessionId)]?.process ?: trackedProcesses[taskIdOrSessionId]?.process

    fun getPid(taskIdOrSessionId: String): Int? =
        trackedProcesses[resolveId(taskIdOrSessionId)]?.pid ?: trackedProcesses[taskIdOrSessionId]?.pid

    fun isAlive(taskIdOrSessionId: String): Boolean {
        val tracked = trackedProcesses[resolveId(taskIdOrSessionId)]
            ?: trackedProcesses[taskIdOrSessionId]
            ?: return false
        val nativeProc = tracked.process as? NativeSpawnProcess
        if (nativeProc != null) {
            return nativeProc.isAlive
        }
        return runCatching { tracked.process.exitValue(); false }.getOrDefault(true)
    }

    fun isProcessAlive(pid: Int): Boolean {
        return NativeSpawnProcess.isPidAlive(pid)
    }

    /**
     * Inspects /proc/$pid/cmdline to verify whether the live PID belongs to
     * our PRoot / CLI toolchain rather than an unrelated recycled system process.
     */
    fun isVerifiedExpectedProcess(pid: Int): Boolean {
        if (!isProcessAlive(pid)) return false
        return runCatching {
            val cmdline = java.io.File("/proc/$pid/cmdline").readText()
            cmdline.contains("proot") ||
                cmdline.contains("node") ||
                cmdline.contains("claude") ||
                cmdline.contains("dsh") ||
                cmdline.contains("agy") ||
                cmdline.contains("python") ||
                cmdline.contains("pocket")
        }.getOrDefault(false)
    }

    fun markCancellationRequested(taskIdOrSessionId: String) {
        val id = resolveId(taskIdOrSessionId)
        userCancellationRequests.add(id)
        userCancellationRequests.add(taskIdOrSessionId)
    }

    fun isCancellationRequested(taskIdOrSessionId: String): Boolean =
        userCancellationRequests.contains(resolveId(taskIdOrSessionId)) ||
            userCancellationRequests.contains(taskIdOrSessionId)

    /**
     * Graceful termination:
     * 1. Send SIGINT (interrupt) or destroy().
     * 2. Wait up to gracePeriodMs (default 1000ms).
     * 3. If still alive, call destroyForcibly() (SIGKILL).
     */
    suspend fun terminate(taskIdOrSessionId: String, force: Boolean = false, gracePeriodMs: Long = 1000L): Boolean =
        withContext(Dispatchers.IO) {
            markCancellationRequested(taskIdOrSessionId)
            val resolved = resolveId(taskIdOrSessionId)
            val tracked = trackedProcesses[resolved] ?: trackedProcesses[taskIdOrSessionId] ?: return@withContext false
            val process = tracked.process

            if (force) {
                runCatching { process.destroyForcibly() }
                return@withContext true
            }

            val native = process as? NativeSpawnProcess
            if (native != null) {
                runCatching { native.interrupt() }
            } else {
                runCatching { process.destroy() }
            }

            // Wait for grace period
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < gracePeriodMs) {
                if (!isAlive(resolved)) return@withContext true
                kotlinx.coroutines.delay(50)
            }

            if (isAlive(resolved)) {
                runCatching { Log.w("ProcessSupervisor", "Process for task $resolved did not exit gracefully; force killing (SIGKILL)") }
                runCatching { process.destroyForcibly() }
            }
            true
        }

    fun classifyExit(exitCode: Int, taskId: String? = null): ProcessExitResult {
        val userCancelled = taskId != null && isCancellationRequested(taskId)
        val exitType = when {
            userCancelled -> ProcessExitType.USER_CANCELLED
            exitCode == 0 -> ProcessExitType.NORMAL
            exitCode == 130 -> ProcessExitType.INTERRUPTED_SIGINT
            exitCode in setOf(137, 143) -> ProcessExitType.FORCED_TERMINATION
            exitCode in 1..127 || exitCode in setOf(132, 133, 134, 135, 136, 138, 139, 140, 141, 159) -> ProcessExitType.CRASH
            else -> ProcessExitType.UNEXPECTED_DEATH
        }
        val description = when (exitType) {
            ProcessExitType.NORMAL -> "Process exited normally (0)"
            ProcessExitType.USER_CANCELLED -> "Process was cancelled by the user"
            ProcessExitType.INTERRUPTED_SIGINT -> "Process received SIGINT interrupt (130)"
            ProcessExitType.FORCED_TERMINATION -> "Process was forcibly terminated ($exitCode)"
            ProcessExitType.CRASH -> "Process crashed with exit code $exitCode"
            ProcessExitType.UNEXPECTED_DEATH -> "Process exited unexpectedly ($exitCode)"
        }
        return ProcessExitResult(exitCode, exitType, description)
    }

    fun getActiveProcesses(): Map<String, Int?> {
        return trackedProcesses.mapValues { it.value.pid }
    }

    fun cleanupAll() {
        trackedProcesses.forEach { (taskId, tracked) ->
            runCatching {
                if (tracked.process.isAlive) {
                    tracked.process.destroyForcibly()
                }
            }
        }
        trackedProcesses.clear()
        userCancellationRequests.clear()
    }
}
