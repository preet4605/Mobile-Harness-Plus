package com.jarves.mh.runtime.task

import android.content.Context
import android.util.Log
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.ContextMemoryStore
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.runtime.RuntimeExecutionService
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Top-level authoritative supervisor for background development tasks.
 * Owns task lifecycle, process supervision, durable execution state,
 * power/wake-lock management, and crash recovery.
 */
class TaskSupervisor private constructor(private val appContext: Context) {

    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dbFile = File(File(appContext.filesDir, "memory"), "project_brain.db")
    private val database: BrainDatabase by lazy {
        dbFile.parentFile?.mkdirs()
        val driver = BrainDatabaseDriverFactory.createDriver(dbFile)
        BrainDatabase(driver)
    }

    val stateStore: TaskStateStore by lazy { TaskStateStore(database) }
    val processSupervisor: ProcessSupervisor by lazy { ProcessSupervisor() }
    val executionLock: TaskExecutionLock by lazy { TaskExecutionLock() }
    val wakeLockManager: WakeLockManager by lazy { WakeLockManager(appContext) }
    val healthMonitor: RuntimeHealthMonitor by lazy { RuntimeHealthMonitor() }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val outputBuffers = ConcurrentHashMap<String, BoundedOutputBuffer>()

    private val _activeTasks = MutableStateFlow<Map<String, DurableTaskRecord>>(emptyMap())
    val activeTasks: StateFlow<Map<String, DurableTaskRecord>> = _activeTasks.asStateFlow()

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RuntimeEvent> = _events.asSharedFlow()

    companion object {
        private const val TAG = "TaskSupervisor"
        @Volatile private var instance: TaskSupervisor? = null

        fun getInstance(context: Context): TaskSupervisor {
            return instance ?: synchronized(this) {
                instance ?: TaskSupervisor(context.applicationContext).also {
                    instance = it
                    it.supervisorScope.launch {
                        it.reconcileOnStartup()
                    }
                }
            }
        }

        // Visible for testing with injected BrainDatabase
        internal fun createForTesting(context: Context, database: BrainDatabase): TaskSupervisor {
            return TaskSupervisor(context.applicationContext).apply {
                // Testing instance
            }
        }
    }

    /**
     * Reconciles persisted task states on application startup.
     * Recovers from OS process death or crashes without leaving zombie tasks.
     */
    fun reconcileOnStartup(): List<DurableTaskRecord> {
        val reconciled = mutableListOf<DurableTaskRecord>()
        try {
            val active = stateStore.getActiveTasks()
            Log.d(TAG, "Reconciling ${active.size} active tasks from database on startup")

            for (task in active) {
                val pid = task.pid
                val isAlive = pid != null && pid > 1 && processSupervisor.isProcessAlive(pid)
                if (!isAlive) {
                    val terminal = stateStore.transition(task.taskId, TaskExecutionStatus.ABANDONED) { record ->
                        record.copy(
                            lastError = "Process terminated due to application process death / system restart",
                            recoveryRequired = true
                        )
                    }
                    healthMonitor.onTaskAbandoned(task.taskId, pid)
                    reconciled.add(terminal)
                    Log.i(TAG, "Reconciled dead task ${task.taskId} -> ABANDONED (recoveryRequired=true)")
                } else {
                    // PID is alive. Verify process identity to avoid killing an innocent recycled PID.
                    val isVerified = pid != null && processSupervisor.isVerifiedExpectedProcess(pid)
                    if (isVerified) {
                        Log.w(TAG, "Found verified orphaned task process (PID $pid) for task ${task.taskId}. Terminating orphan.")
                        runCatching {
                            com.jarves.mh.runtime.NativeSpawn.kill(pid, 9)
                        }
                    } else {
                        Log.w(TAG, "Found task ${task.taskId} with alive PID $pid, but process identity could not be verified.")
                    }
                    val terminal = stateStore.transition(task.taskId, TaskExecutionStatus.ABANDONED) { record ->
                        record.copy(
                            lastError = if (isVerified) {
                                "Application restarted while task was active; orphaned process terminated (PID $pid)"
                            } else {
                                "Application restarted while task was active; process unverifiable or detached (PID $pid)"
                            },
                            recoveryRequired = true
                        )
                    }
                    healthMonitor.onTaskAbandoned(task.taskId, pid)
                    reconciled.add(terminal)
                    Log.i(TAG, "Reconciled orphaned/unverifiable task ${task.taskId} -> ABANDONED (recoveryRequired=true)")
                }
            }

            wakeLockManager.releaseAll()
            healthMonitor.onWakeLockChanged(false, 0)
            refreshActiveTasks()
        } catch (t: Throwable) {
            Log.e(TAG, "Error during startup reconciliation", t)
        }
        return reconciled
    }

    fun getOutputBuffer(taskIdOrSessionId: String): BoundedOutputBuffer {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        return outputBuffers.computeIfAbsent(targetTaskId) { BoundedOutputBuffer() }
    }

    private fun refreshActiveTasks() {
        val map = stateStore.getActiveTasks().associateBy { it.taskId }
        _activeTasks.value = map
    }

    /**
     * Pre-binds runtime sessionId to taskId so subsequent operations can resolve by either ID.
     */
    fun bindSession(taskId: String, sessionId: String) {
        stateStore.markSessionId(taskId, sessionId)
        refreshActiveTasks()
    }

    /**
     * Canonical, authoritative process binding method for runtime tasks.
     * Guarantees process registration in ProcessSupervisor and persistent PID/session recording in SQLite.
     */
    fun bindProcess(
        taskId: String,
        sessionId: String,
        process: Process,
        pid: Int? = null
    ): Boolean {
        return try {
            val resolvedPid = pid ?: (process as? com.jarves.mh.runtime.NativeSpawnProcess)?.processPid
            if (resolvedPid == null || resolvedPid <= 0) {
                Log.w(TAG, "Process PID is missing or invalid for task $taskId (session $sessionId)")
            }
            processSupervisor.register(taskId, process, resolvedPid, sessionId)
            val updated = stateStore.bindProcess(taskId, sessionId, resolvedPid ?: -1)
            if (resolvedPid != null && resolvedPid > 0) {
                healthMonitor.onTaskProcessBound(updated.taskId, resolvedPid)
            }
            refreshActiveTasks()
            Log.i(TAG, "Successfully bound process (PID $resolvedPid) for task ${updated.taskId}, session $sessionId")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to bind process for task $taskId, session $sessionId", t)
            false
        }
    }

    /**
     * Backward-compatible binding adapter that safely resolves taskId from sessionId.
     */
    fun markProcessBound(taskIdOrSessionId: String, process: Process, pid: Int? = null): Boolean {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val resolvedTaskId = task?.taskId ?: taskIdOrSessionId
        val resolvedSessionId = task?.sessionId ?: taskIdOrSessionId
        return bindProcess(resolvedTaskId, resolvedSessionId, process, pid)
    }

    fun markSessionBound(taskId: String, sessionId: String) {
        bindSession(taskId, sessionId)
    }

    fun pauseForApproval(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.WAITING_FOR_APPROVAL)
            wakeLockManager.pause(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager.isHeld, wakeLockManager.activeTaskCount)
            refreshActiveTasks()
        }.onFailure { Log.w(TAG, "Failed to pauseForApproval for task $targetTaskId", it) }
    }

    fun resumeFromApproval(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.RUNNING)
            wakeLockManager.resume(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager.isHeld, wakeLockManager.activeTaskCount)
            refreshActiveTasks()
        }.onFailure { Log.w(TAG, "Failed to resumeFromApproval for task $targetTaskId", it) }
    }

    fun pauseForInput(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.WAITING_FOR_INPUT)
            wakeLockManager.pause(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager.isHeld, wakeLockManager.activeTaskCount)
            refreshActiveTasks()
        }.onFailure { Log.w(TAG, "Failed to pauseForInput for task $targetTaskId", it) }
    }

    fun resumeFromInput(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.RUNNING)
            wakeLockManager.resume(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager.isHeld, wakeLockManager.activeTaskCount)
            refreshActiveTasks()
        }.onFailure { Log.w(TAG, "Failed to resumeFromInput for task $targetTaskId", it) }
    }

    /**
     * Prepares and registers a task for execution. Returns the initialized DurableTaskRecord.
     */
    fun createTask(
        taskId: String = UUID.randomUUID().toString(),
        projectId: String,
        projectSlug: String,
        chatId: String,
        agentKind: String,
        providerJson: String,
        prompt: String,
        maxRetries: Int = 2
    ): DurableTaskRecord {
        val record = DurableTaskRecord(
            taskId = taskId,
            projectId = projectId,
            projectSlug = projectSlug,
            chatId = chatId,
            agentKind = agentKind,
            providerJson = providerJson,
            prompt = prompt,
            status = TaskExecutionStatus.CREATED,
            maxRetries = maxRetries
        )
        stateStore.save(record)
        refreshActiveTasks()
        return record
    }

    /**
     * Error classification distinguishing user actions, transient service failures,
     * workspace mutations, and permanent configuration/auth errors.
     */
    enum class TaskErrorClassification {
        USER_CANCELLED,
        TRANSIENT_API_ERROR,
        PERMANENT_AUTH_OR_CONFIG,
        WORKSPACE_MUTATED_FAILURE,
        PROCESS_FAILURE
    }

    fun classifyError(errorMsg: String, workspaceMutated: Boolean, isCancelled: Boolean): TaskErrorClassification {
        if (isCancelled || errorMsg.contains("stopped by user", ignoreCase = true) || errorMsg.contains("cancelled", ignoreCase = true)) {
            return TaskErrorClassification.USER_CANCELLED
        }
        if (workspaceMutated) {
            return TaskErrorClassification.WORKSPACE_MUTATED_FAILURE
        }
        val lower = errorMsg.lowercase()
        if (lower.contains("api key") || lower.contains("user not found") ||
            lower.contains("authentication failed") || lower.contains("not signed in") ||
            lower.contains("http 401") || lower.contains("http 403") ||
            lower.contains("code: 401") || lower.contains("code: 403")) {
            return TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG
        }
        if (lower.contains("503") || lower.contains("unavailable") || lower.contains("service is currently unavailable") ||
            lower.contains("500") || lower.contains("502") || lower.contains("504") ||
            lower.contains("socket timeout") || lower.contains("network error") ||
            lower.contains("rate limit") || lower.contains("http 429") || lower.contains("resource_exhausted")) {
            return TaskErrorClassification.TRANSIENT_API_ERROR
        }
        return TaskErrorClassification.PROCESS_FAILURE
    }

    /**
     * Canonical, authoritative, and idempotent terminal state finalization operation.
     * Guaranteed to persist state, release wake locks, unregister processes, and update
     * health monitoring and foreground service without throwing or corrupting terminal states.
     */
    fun finalizeTask(
        taskIdOrSessionId: String,
        status: TaskExecutionStatus,
        error: String? = null,
        recoveryRequired: Boolean = false,
        pid: Int? = null,
        exitCode: Int? = null
    ): DurableTaskRecord? {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId) ?: return null
        val targetTaskId = task.taskId

        if (task.status.isTerminal) {
            Log.d(TAG, "Task $targetTaskId is already in terminal state ${task.status}; ignoring finalizeTask($status)")
            return task
        }

        val finalizedRecord = when (status) {
            TaskExecutionStatus.COMPLETED -> {
                if (task.status != TaskExecutionStatus.COMPLETING) {
                    runCatching { stateStore.transition(targetTaskId, TaskExecutionStatus.COMPLETING) }
                }
                val completed = stateStore.transition(targetTaskId, TaskExecutionStatus.COMPLETED)
                healthMonitor.onTaskCompleted(targetTaskId, pid ?: completed.pid)
                RuntimeExecutionService.finish(
                    context = appContext,
                    title = "Task completed",
                    detail = "Mobile Harness finished working in ${task.projectSlug}.",
                    failed = false
                )
                completed
            }
            TaskExecutionStatus.FAILED -> {
                val failureDetail = error ?: task.lastError ?: "Task failed"
                val failed = stateStore.transition(targetTaskId, TaskExecutionStatus.FAILED) {
                    it.copy(
                        lastError = failureDetail,
                        recoveryRequired = recoveryRequired
                    )
                }
                healthMonitor.onTaskFailed(targetTaskId, failureDetail, pid ?: failed.pid)
                RuntimeExecutionService.finish(
                    context = appContext,
                    title = if (recoveryRequired) "Task needs attention" else "Task failed",
                    detail = failureDetail,
                    failed = true
                )
                failed
            }
            TaskExecutionStatus.CANCELLED -> {
                val cancelDetail = error ?: "Cancelled by user"
                val cancelled = stateStore.transition(targetTaskId, TaskExecutionStatus.CANCELLED) {
                    it.copy(lastError = cancelDetail)
                }
                healthMonitor.onTaskCompleted(targetTaskId, pid ?: cancelled.pid)
                RuntimeExecutionService.cancel(appContext)
                cancelled
            }
            TaskExecutionStatus.ABANDONED -> {
                val abandoned = stateStore.transition(targetTaskId, TaskExecutionStatus.ABANDONED) {
                    it.copy(
                        lastError = error ?: "Task abandoned across process lifecycle",
                        recoveryRequired = recoveryRequired
                    )
                }
                healthMonitor.onTaskAbandoned(targetTaskId, pid ?: abandoned.pid)
                abandoned
            }
            else -> {
                Log.w(TAG, "finalizeTask called with non-terminal status $status for task $targetTaskId")
                stateStore.transition(targetTaskId, status) { it.copy(lastError = error) }
            }
        }

        wakeLockManager.release(targetTaskId)
        healthMonitor.onWakeLockChanged(wakeLockManager.isHeld, wakeLockManager.activeTaskCount)
        processSupervisor.unregister(targetTaskId)
        outputBuffers.remove(targetTaskId)
        activeJobs.remove(targetTaskId)
        refreshActiveTasks()

        return finalizedRecord
    }

    /**
     * Executes a task under strict process, lifecycle, and concurrency supervision.
     */
    fun executeTask(
        taskId: String,
        executionBlock: suspend (task: DurableTaskRecord) -> Unit
    ): Job {
        val task = stateStore.get(taskId) ?: error("Task $taskId not found in store")

        val job = supervisorScope.launch {
            try {
                executionLock.withExecutionLock(task.taskId, task.projectId) {
                    var current = stateStore.transition(taskId, TaskExecutionStatus.STARTING)
                    refreshActiveTasks()
                    wakeLockManager.acquire(taskId)
                    healthMonitor.onTaskStarted(taskId, current.pid)
                    healthMonitor.onWakeLockChanged(wakeLockManager.isHeld, wakeLockManager.activeTaskCount)

                    // Start foreground service for notifications
                    RuntimeExecutionService.start(
                        context = appContext,
                        taskId = taskId,
                        projectName = task.projectSlug,
                        detail = "Starting ${task.agentKind} in ${task.projectSlug}…"
                    )

                    var attempt = 0
                    var succeeded = false

                    while (!succeeded && attempt <= task.maxRetries) {
                        try {
                            if (attempt > 0) {
                                current = stateStore.transition(taskId, TaskExecutionStatus.RECOVERING) {
                                    it.copy(retryCount = attempt, lastError = "Retrying attempt $attempt/${task.maxRetries}...")
                                }
                                refreshActiveTasks()
                                healthMonitor.onTaskRecovered(taskId)
                                val backoffMs = (1000L * (1 shl (attempt - 1))).coerceAtMost(5000L)
                                delay(backoffMs)
                                current = stateStore.transition(taskId, TaskExecutionStatus.STARTING)
                                refreshActiveTasks()
                            }

                            // Run execution block
                            executionBlock(current)
                            succeeded = true

                            finalizeTask(taskId, TaskExecutionStatus.COMPLETED)
                        } catch (t: Throwable) {
                            attempt++
                            val isCancelled = processSupervisor.isCancellationRequested(taskId) ||
                                t.message?.contains("stopped by user", ignoreCase = true) == true
                            val errorMsg = t.localizedMessage ?: t.message ?: "Task execution failed"

                            // Terminate any running child process from this attempt before retrying
                            processSupervisor.terminate(taskId, force = true)

                            val checkpoints = com.jarves.mh.runtime.WorkspaceCheckpoints(appContext.filesDir)
                            val mutatedFiles = runCatching { checkpoints.readChangedPaths(task.projectId) }.getOrDefault(emptyList())
                            val workspaceIsMutated = mutatedFiles.isNotEmpty()

                            val classification = classifyError(errorMsg, workspaceIsMutated, isCancelled)
                            when (classification) {
                                TaskErrorClassification.USER_CANCELLED -> {
                                    finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user")
                                    break
                                }
                                TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG -> {
                                    finalizeTask(taskId, TaskExecutionStatus.FAILED, error = errorMsg, recoveryRequired = false)
                                    break
                                }
                                TaskErrorClassification.WORKSPACE_MUTATED_FAILURE -> {
                                    val failureDetail = "Task failed after modifying files (${mutatedFiles.size} changed). Auto-retry disabled ($errorMsg)."
                                    finalizeTask(taskId, TaskExecutionStatus.FAILED, error = failureDetail, recoveryRequired = true)
                                    break
                                }
                                TaskErrorClassification.TRANSIENT_API_ERROR,
                                TaskErrorClassification.PROCESS_FAILURE -> {
                                    if (attempt <= task.maxRetries) {
                                        Log.w(TAG, "Transient error on attempt $attempt for task $taskId: $errorMsg. Retrying in ${1000L * attempt}ms...")
                                    } else {
                                        val failureDetail = if (classification == TaskErrorClassification.TRANSIENT_API_ERROR) {
                                            "Service unavailable after $attempt attempts ($errorMsg)"
                                        } else {
                                            "Task failed after $attempt attempts: $errorMsg"
                                        }
                                        finalizeTask(taskId, TaskExecutionStatus.FAILED, error = failureDetail, recoveryRequired = false)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                val currentRecord = stateStore.get(taskId)
                if (currentRecord != null && !currentRecord.status.isTerminal) {
                    val finalStatus = if (processSupervisor.isCancellationRequested(taskId)) {
                        TaskExecutionStatus.CANCELLED
                    } else {
                        TaskExecutionStatus.ABANDONED
                    }
                    finalizeTask(taskId, finalStatus, error = "Execution ended prematurely")
                }
                activeJobs.remove(taskId)
            }
        }

        activeJobs[taskId] = job
        return job
    }

    suspend fun requestStop(taskIdOrSessionId: String, force: Boolean = false): Boolean {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val resolvedTaskId = task?.taskId ?: taskIdOrSessionId
        val resolvedSessionId = task?.sessionId

        processSupervisor.markCancellationRequested(resolvedTaskId)
        if (resolvedSessionId != null) {
            processSupervisor.markCancellationRequested(resolvedSessionId)
        }
        val stopped = processSupervisor.terminate(resolvedTaskId, force = force)
        activeJobs[resolvedTaskId]?.cancel()
        finalizeTask(resolvedTaskId, TaskExecutionStatus.CANCELLED, error = "Stop requested by user")
        return stopped
    }

    suspend fun requestStopActive(force: Boolean = false): Boolean {
        var stoppedAny = false
        activeTasks.value.keys.forEach { taskId ->
            if (requestStop(taskId, force)) {
                stoppedAny = true
            }
        }
        return stoppedAny
    }
}
