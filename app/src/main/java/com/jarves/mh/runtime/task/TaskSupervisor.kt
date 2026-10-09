package com.jarves.mh.runtime.task

import android.content.Context
import android.util.Log
import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainContextAssemblyException
import com.jarves.mh.data.BrainContextSnapshot
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.data.ContextMemoryStore
import com.jarves.mh.data.BrainLearningService
import com.jarves.mh.data.MemorySource
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionFeedback
import com.jarves.mh.model.brain.ExecutionOutcome
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.ExecutionWorkspaceState
import com.jarves.mh.model.brain.LearningResult
import com.jarves.mh.model.brain.PlanStatus
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStatus
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.validateExecutionPlan
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.model.brain.TaskOutcome
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.RuntimeExecutionService
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.time.Instant
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
import kotlinx.coroutines.runInterruptible

/**
 * Top-level authoritative supervisor for background development tasks.
 * Owns task lifecycle, process supervision, durable execution state,
 * power/wake-lock management, and crash recovery.
 */
class TaskSupervisor private constructor(private val appContext: Context?) {

    private val supervisorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dbFile by lazy { File(File(appContext?.filesDir ?: File("memory"), "memory"), "project_brain.db") }
    private var testDatabase: BrainDatabase? = null
    val database: BrainDatabase
        get() = testDatabase ?: defaultDatabase

    private val defaultDatabase: BrainDatabase by lazy {
        dbFile.parentFile?.mkdirs()
        val driver = BrainDatabaseDriverFactory.createDriver(dbFile)
        BrainDatabase(driver)
    }

    val stateStore: TaskStateStore by lazy { TaskStateStore(database) }
    val canonicalTaskRepository: CanonicalTaskRepository by lazy { CanonicalTaskRepository(database) }
    val brainKnowledgeRepository: BrainKnowledgeRepository by lazy { BrainKnowledgeRepository(database) }

    private var customBrainContextAssembler: BrainContextAssembler? = null
    var brainContextAssembler: BrainContextAssembler
        get() = customBrainContextAssembler ?: defaultAssembler
        set(value) { customBrainContextAssembler = value }

    private val defaultAssembler by lazy { BrainContextAssembler(brainKnowledgeRepository) }

    private var customBrainLearningService: BrainLearningService? = null
    var brainLearningService: BrainLearningService
        get() = customBrainLearningService ?: defaultLearningService
        set(value) { customBrainLearningService = value }

    private val defaultLearningService by lazy { BrainLearningService(brainKnowledgeRepository) }

    private var customTaskDecomposer: TaskDecomposer? = null
    var taskDecomposer: TaskDecomposer
        get() = customTaskDecomposer ?: defaultDecomposer
        set(value) { customTaskDecomposer = value }

    private val defaultDecomposer by lazy { DefaultTaskDecomposer() }

    val processSupervisor: ProcessSupervisor by lazy { ProcessSupervisor() }
    val executionLock: TaskExecutionLock by lazy { TaskExecutionLock() }
    val wakeLockManager: WakeLockManager by lazy { WakeLockManager(appContext) }
    val healthMonitor: RuntimeHealthMonitor by lazy { RuntimeHealthMonitor() }

    var checkpointsResolver: (() -> com.jarves.mh.runtime.WorkspaceCheckpoints)? = null

    private val defaultCheckpoints by lazy {
        val preferences = appContext?.let { com.jarves.mh.data.AppPreferences(it) }
        WorkspaceCheckpoints(appContext?.filesDir ?: File("."), rootPathResolver = { projectId ->
            preferences?.loadProjects()?.firstOrNull { it.id == projectId }?.rootPath.orEmpty()
        })
    }

    fun getCheckpoints(): WorkspaceCheckpoints = checkpointsResolver?.invoke() ?: defaultCheckpoints

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val outputBuffers = ConcurrentHashMap<String, BoundedOutputBuffer>()

    private val brainSnapshots = ConcurrentHashMap<String, BrainContextSnapshot>()
    private val activeBrainSnapshots = ConcurrentHashMap<String, BrainContextSnapshot>()

    private val _activeTasks = MutableStateFlow<Map<String, DurableTaskRecord>>(emptyMap())
    val activeTasks: StateFlow<Map<String, DurableTaskRecord>> = _activeTasks.asStateFlow()

    private val _events = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<RuntimeEvent> = _events.asSharedFlow()

    fun interface TaskFallbackDecider {
        fun decideFallback(taskId: String, errorMsg: String): Boolean
    }

    private val fallbackDeciders = ConcurrentHashMap<String, TaskFallbackDecider>()

    fun registerFallbackDecider(taskId: String, decider: TaskFallbackDecider) {
        fallbackDeciders[taskId] = decider
    }

    fun unregisterFallbackDecider(taskId: String) {
        fallbackDeciders.remove(taskId)
    }

    fun getTaskIdForSession(sessionId: String): String? =
        processSupervisor.getTaskIdForSession(sessionId) ?: stateStore.getBySessionId(sessionId)?.taskId

    private var startupReconciliation: Job? = null

    companion object {
        private const val TAG = "TaskSupervisor"
        @Volatile private var instance: TaskSupervisor? = null

        fun getInstance(context: Context): TaskSupervisor {
            return instance ?: synchronized(this) {
                instance ?: TaskSupervisor(context.applicationContext).also {
                    val startupTasks = it.stateStore.getActiveTasks()
                    it.startupReconciliation = it.supervisorScope.launch {
                        it.reconcileOnStartup(startupTasks)
                    }
                    instance = it
                }
            }
        }

        // Visible for testing with injected BrainDatabase
        internal fun createForTesting(context: Context? = null, database: BrainDatabase): TaskSupervisor {
            return TaskSupervisor(context?.applicationContext ?: context).apply {
                testDatabase = database
            }
        }
    }

    /**
     * Reconciles persisted task states on application startup.
     * Recovers from OS process death or crashes without leaving zombie tasks.
     */
    fun reconcileOnStartup(startupTasks: List<DurableTaskRecord> = stateStore.getActiveTasks()): List<DurableTaskRecord> {
        val reconciled = mutableListOf<DurableTaskRecord>()
        try {
            val active = startupTasks
            runCatching { Log.d(TAG, "Reconciling ${active.size} active tasks from database on startup") }

            for (task in active) try {
                // COMPLETING cannot legally move to ABANDONED; a dead COMPLETING task never
                // finished finalization, so it is failed (recoveryRequired) instead.
                val recoveryTarget = if (task.status == TaskExecutionStatus.COMPLETING) {
                    TaskExecutionStatus.FAILED
                } else {
                    TaskExecutionStatus.ABANDONED
                }
                val pid = task.pid
                val isAlive = pid != null && pid > 1 && processSupervisor.isProcessAlive(pid)
                if (!isAlive) {
                    val terminal = stateStore.transition(task.taskId, recoveryTarget) { record ->
                        record.copy(
                            lastError = "Process terminated due to application process death / system restart",
                            recoveryRequired = true
                        )
                    }
                    reconcileCanonicalTaskStepsOnAbandonment(task.taskId, terminal.lastError ?: "Process terminated")
                    healthMonitor.onTaskAbandoned(task.taskId, pid)
                    reconciled.add(terminal)
                    runCatching { Log.i(TAG, "Reconciled dead task ${task.taskId} -> $recoveryTarget (recoveryRequired=true)") }
                } else {
                    // PID is alive. Verify process identity to avoid killing an innocent recycled PID.
                    val isVerified = processSupervisor.isVerifiedExpectedProcess(pid)
                    if (isVerified) {
                        runCatching { Log.w(TAG, "Found verified orphaned task process (PID $pid) for task ${task.taskId}. Terminating orphan.") }
                        runCatching {
                            com.jarves.mh.runtime.NativeSpawn.kill(pid, 9)
                        }
                    } else {
                        runCatching { Log.w(TAG, "Found task ${task.taskId} with alive PID $pid, but process identity could not be verified.") }
                    }
                    val terminal = stateStore.transition(task.taskId, recoveryTarget) { record ->
                        record.copy(
                            lastError = if (isVerified) {
                                "Application restarted while task was active; orphaned process terminated (PID $pid)"
                            } else {
                                "Application restarted while task was active; process unverifiable or detached (PID $pid)"
                            },
                            recoveryRequired = true
                        )
                    }
                    reconcileCanonicalTaskStepsOnAbandonment(task.taskId, terminal.lastError ?: "Process terminated")
                    healthMonitor.onTaskAbandoned(task.taskId, pid)
                    reconciled.add(terminal)
                    runCatching { Log.i(TAG, "Reconciled orphaned/unverifiable task ${task.taskId} -> $recoveryTarget (recoveryRequired=true)") }
                }
            } catch (t: Throwable) {
                runCatching { Log.e(TAG, "Failed to reconcile task ${task.taskId}; continuing with remaining tasks", t) }
            }

            wakeLockManager?.releaseAll()
            healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)
            refreshActiveTasks()
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "Error during startup reconciliation", t) }
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
            wakeLockManager?.pause(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)
            refreshActiveTasks()
        }.onFailure { runCatching { Log.w(TAG, "Failed to pauseForApproval for task $targetTaskId", it) } }
    }

    fun resumeFromApproval(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.RUNNING)
            wakeLockManager?.resume(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)
            refreshActiveTasks()
        }.onFailure { runCatching { Log.w(TAG, "Failed to resumeFromApproval for task $targetTaskId", it) } }
    }

    fun pauseForInput(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.WAITING_FOR_INPUT)
            wakeLockManager?.pause(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)
            refreshActiveTasks()
        }.onFailure { runCatching { Log.w(TAG, "Failed to pauseForInput for task $targetTaskId", it) } }
    }

    fun resumeFromInput(taskIdOrSessionId: String) {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val targetTaskId = task?.taskId ?: taskIdOrSessionId
        runCatching {
            stateStore.transition(targetTaskId, TaskExecutionStatus.RUNNING)
            wakeLockManager?.resume(targetTaskId)
            healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)
            refreshActiveTasks()
        }.onFailure { runCatching { Log.w(TAG, "Failed to resumeFromInput for task $targetTaskId", it) } }
    }

    fun getBrainSnapshot(taskIdOrSessionId: String): BrainContextSnapshot? {
        val task = stateStore.get(taskIdOrSessionId) ?: stateStore.getBySessionId(taskIdOrSessionId)
        val resolvedTaskId = task?.taskId ?: taskIdOrSessionId
        return activeBrainSnapshots[resolvedTaskId]
    }

    fun getBrainSnapshotByAttempt(attemptId: String): BrainContextSnapshot? {
        return brainSnapshots[attemptId]
    }

    fun getOrCreateBrainSnapshot(
        taskId: String,
        attempt: Int = 0,
        attemptId: String = "$taskId:attempt-$attempt",
        projectId: String? = null,
        query: String? = null,
        currentStep: ExecutionStep? = null
    ): BrainContextSnapshot {
        val existing = brainSnapshots[attemptId]
        if (existing != null) {
            activeBrainSnapshots[taskId] = existing
            return existing
        }

        try {
            val taskRecord = stateStore.get(taskId)
            val effectiveProjectId = taskRecord?.projectId ?: projectId.orEmpty()
            val effectiveQuery = query ?: taskRecord?.prompt

            val canonicalTask = canonicalTaskRepository.getTask(taskId)
            val effectiveStep = currentStep ?: canonicalTask?.plan?.currentStep

            val context = brainContextAssembler.assemble(
                task = canonicalTask,
                currentStep = effectiveStep,
                projectId = effectiveProjectId,
                query = effectiveQuery,
                maxCharacters = BrainContextAssembler.DEFAULT_BRAIN_CONTEXT_CHARS - ControlledBrainInjector.WRAPPER_OVERHEAD
            )
            val rendered = brainContextAssembler.render(context)
            val snapshot = BrainContextSnapshot.create(
                taskId = taskId,
                attemptId = attemptId,
                context = context,
                renderedContext = rendered
            )

            brainSnapshots[attemptId] = snapshot
            activeBrainSnapshots[taskId] = snapshot

            // Persist fingerprint and timestamp to durable state for observability
            runCatching {
                val currentStatus = taskRecord?.status ?: TaskExecutionStatus.STARTING
                stateStore.transition(taskId, currentStatus) {
                    it.copy(lastKnownStep = "brain:${snapshot.fingerprint}@${snapshot.createdAt}")
                }
            }

            return snapshot
        } catch (e: BrainContextAssemblyException) {
            throw e
        } catch (t: Throwable) {
            throw BrainContextAssemblyException("Failed to assemble Brain context for task $taskId (attempt $attemptId): ${t.message}", t)
        }
    }

    var stepVerifier: StepVerifier = DefaultStepVerifier(
        confinementRunner = appContext?.let { context ->
            GuestStepCommandRunner(context) { task, process ->
                val owner = stateStore.get(task.taskId)
                val sessionId = owner?.sessionId
                owner != null && owner.projectId == task.projectId && sessionId != null &&
                    !owner.status.isTerminal && !isCancellationActive(task.taskId) &&
                    bindProcess(task.taskId, sessionId, process)
            }
        } ?: ControlledStepCommandRunner()
    )
    var recoveryEngine: RecoveryEngine = DefaultRecoveryEngine(checkpointsProvider = { getCheckpoints() })

    fun detectStepMutatedFiles(
        projectId: String,
        stepTag: String,
        wsDir: File?
    ): List<String> {
        val checkpoints = getCheckpoints()
        val recorded = checkpoints.readChangedPaths(projectId, stepTag).toMutableSet()
        val meta = checkpoints.readMetadata(projectId, stepTag)
        if (meta != null) {
            recorded.addAll(meta.changes)
            if (wsDir != null && wsDir.isDirectory) {
                recorded.addAll(checkpoints.changedFiles(wsDir, meta.fingerprints))
            }
        }
        return recorded.filterNot(checkpoints::isInternalRuntimePath).distinct().sorted()
    }

    private fun reconcileCanonicalTaskStepsOnAbandonment(taskId: String, reason: String) {
        runCatching {
            val canonical = canonicalTaskRepository.getTask(taskId) ?: return
            val updatedSteps = canonical.plan.steps.map { s ->
                if (s.status == StepStatus.RUNNING || s.status == StepStatus.VERIFYING || s.status == StepStatus.RECOVERING) {
                    s.copy(
                        status = StepStatus.FAILED,
                        completedAt = java.time.Instant.now(),
                        resultSummary = reason
                    )
                } else s
            }
            val updatedRecovery = canonical.activeRecoveryPlan?.let { recovery ->
                if (recovery.status == com.jarves.mh.model.brain.RecoveryStatus.IN_PROGRESS ||
                    recovery.status == com.jarves.mh.model.brain.RecoveryStatus.PENDING) {
                    recovery.copy(
                        status = com.jarves.mh.model.brain.RecoveryStatus.FAILED,
                        recoveryResult = "Interrupted by process death / system restart",
                        nextAction = "RETRY_STEP"
                    )
                } else recovery
            }
            canonicalTaskRepository.saveTask(
                canonical.copy(
                    plan = canonical.plan.copy(steps = updatedSteps),
                    activeRecoveryPlan = updatedRecovery
                )
            )
        }
    }

    var workspaceDirectoryResolver: ((projectId: String) -> File?)? = null

    fun resolveWorkspaceDir(projectId: String): File? {
        return workspaceDirectoryResolver?.invoke(projectId)
            ?: appContext?.let { getCheckpoints().ensureWorkspace(projectId) }
            ?: File("workspaces/$projectId").takeIf { it.exists() }
            ?: File(projectId).takeIf { it.exists() }
    }

    fun resolveWorkspaceSha(projectId: String): String? {
        val dir = resolveWorkspaceDir(projectId)
        return resolveGitSha(dir)
    }

    internal fun resolveGitSha(workspaceDir: File?): String? {
        if (workspaceDir == null || !workspaceDir.exists()) return null
        val gitEntry = File(workspaceDir, ".git")
        val gitDir = when {
            gitEntry.isDirectory -> gitEntry
            gitEntry.isFile -> {
                val content = runCatching { gitEntry.readText().trim() }.getOrNull() ?: return null
                if (content.startsWith("gitdir:")) {
                    val rel = content.removePrefix("gitdir:").trim()
                    val target = if (rel.startsWith("/")) File(rel) else File(workspaceDir, rel)
                    if (target.isDirectory) target else null
                } else null
            }
            else -> null
        } ?: return null

        val headFile = File(gitDir, "HEAD")
        if (!headFile.isFile) return null
        val head = runCatching { headFile.readText().trim() }.getOrNull() ?: return null

        if (head.startsWith("ref:")) {
            val refPath = head.removePrefix("ref:").trim()
            val refFile = File(gitDir, refPath)
            if (refFile.isFile) {
                val sha = runCatching { refFile.readText().trim() }.getOrNull()
                if (!sha.isNullOrBlank() && sha.matches(Regex("[0-9a-fA-F]{7,40}"))) {
                    return sha
                }
            }
            // Check packed-refs
            val packedRefs = File(gitDir, "packed-refs")
            if (packedRefs.isFile) {
                val lines = runCatching { packedRefs.readLines() }.getOrNull() ?: emptyList()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("#") || trimmed.startsWith("^")) continue
                    val parts = trimmed.split("\\s+".toRegex())
                    if (parts.size >= 2 && parts[1] == refPath) {
                        val candidate = parts[0]
                        if (candidate.matches(Regex("[0-9a-fA-F]{7,40}"))) {
                            return candidate
                        }
                    }
                }
            }
        } else if (head.matches(Regex("[0-9a-fA-F]{7,40}"))) {
            return head
        }
        return null
    }

    /**
     * Authoritative deterministic task decomposition method.
     * Decomposes a CanonicalTask's objective into an ordered, validated ExecutionPlan.
     * Idempotent: If task already exists and has a plan with steps, preserves existing plan without re-decomposing.
     */
    fun decomposeTask(
        taskId: String,
        objective: String? = null,
        context: TaskDecompositionContext = TaskDecompositionContext()
    ): CanonicalTask {
        val existing = canonicalTaskRepository.getTask(taskId)
        if (existing != null && existing.plan.steps.isNotEmpty()) {
            return existing
        }
        val effObjective = objective ?: existing?.objective ?: "Task $taskId"
        val effCriteria = if (context.acceptanceCriteria.isNotEmpty()) {
            context.acceptanceCriteria
        } else {
            existing?.acceptanceCriteria.orEmpty()
        }
        val decompositionContext = context.copy(
            projectId = if (context.projectId.isNotBlank()) context.projectId else existing?.projectId.orEmpty(),
            projectSlug = if (context.projectSlug.isNotBlank()) context.projectSlug else existing?.projectSlug.orEmpty(),
            acceptanceCriteria = effCriteria
        )
        val decomposedPlan = try {
            taskDecomposer.decompose(taskId, effObjective, decompositionContext)
        } catch (e: InvalidDecompositionException) {
            runCatching { Log.w(TAG, "decomposeTask failed for task $taskId: ${e.message}. Falling back to default single-step plan.") }
            null
        }
        return initializePlan(
            taskId = taskId,
            plan = decomposedPlan,
            objective = effObjective,
            acceptanceCriteria = effCriteria,
            projectId = decompositionContext.projectId,
            projectSlug = decompositionContext.projectSlug
        )
    }

    /**
     * Authoritative execution plan initialization lifecycle for executable CanonicalTasks.
     * Ensures every executable task has exactly one valid, persisted ExecutionPlan
     * with deterministic ordered steps.
     * Idempotent: If already initialized, returns existing CanonicalTask without modifying or reordering.
     */
    fun initializePlan(
        taskId: String,
        plan: ExecutionPlan? = null,
        objective: String? = null,
        acceptanceCriteria: List<String> = emptyList(),
        projectId: String = "default-project",
        projectSlug: String = "default"
    ): CanonicalTask {
        val existing = canonicalTaskRepository.getTask(taskId)
        if (existing != null && existing.plan.steps.isNotEmpty()) {
            return existing
        }

        val effObjective = objective ?: existing?.objective ?: "Task $taskId"
        val effCriteria = if (acceptanceCriteria.isNotEmpty()) {
            acceptanceCriteria
        } else if (existing?.acceptanceCriteria?.isNotEmpty() == true) {
            existing.acceptanceCriteria
        } else {
            listOf("Fulfill objective: $effObjective")
        }
        val planId = plan?.planId?.ifBlank { "plan-$taskId" } ?: "plan-$taskId"

        val executionPlan = if (plan != null) {
            val validation = validateExecutionPlan(plan)
            require(validation.isValid) { "Invalid execution plan for task $taskId: ${validation.errorMessage}" }
            val deterministicSteps = plan.steps.mapIndexed { index, s ->
                s.copy(
                    stepId = s.stepId.ifBlank { "$taskId-step-$index" },
                    stepOrder = index,
                    objective = s.objective.ifBlank { s.description.ifBlank { s.title } },
                    acceptanceCriteria = if (s.acceptanceCriteria.isEmpty()) effCriteria else s.acceptanceCriteria,
                    status = s.status,
                    checkpointTag = if (s.checkpointTag.isNullOrBlank()) "step-${index + 1}" else s.checkpointTag,
                    planId = planId
                )
            }
            plan.copy(
                planId = planId,
                taskId = taskId,
                steps = deterministicSteps,
                currentStepIndex = plan.currentStepIndex.coerceIn(0, deterministicSteps.size),
                status = PlanStatus.PENDING
            )
        } else {
            try {
                taskDecomposer.decompose(
                    taskId = taskId,
                    objective = effObjective,
                    context = TaskDecompositionContext(
                        projectId = projectId,
                        projectSlug = projectSlug,
                        acceptanceCriteria = effCriteria
                    )
                )
            } catch (e: InvalidDecompositionException) {
                runCatching { Log.w(TAG, "Task decomposition failed in initializePlan for task $taskId: ${e.message}. Falling back to default single-step execution plan.") }
                val safeObjective = effObjective.trim().ifBlank { "Execute Task" }
                val fallbackObjective = if (PlanBuilder.isAmbiguousObjective(safeObjective)) {
                    "Execute task request: $safeObjective"
                } else {
                    safeObjective
                }
                val fallbackCriteria = if (effCriteria.isNotEmpty()) {
                    effCriteria
                } else {
                    listOf("Fulfill objective: $fallbackObjective")
                }
                PlanBuilder(
                    planId = planId,
                    taskId = taskId,
                    title = "Execution Plan for $taskId"
                ).addStep(
                    title = "Execute Task",
                    objective = fallbackObjective,
                    acceptanceCriteria = fallbackCriteria,
                    stepOrder = 0
                ).build()
            }
        }

        val canonicalTask = existing?.copy(plan = executionPlan) ?: CanonicalTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = projectSlug,
            objective = effObjective,
            acceptanceCriteria = effCriteria,
            plan = executionPlan
        )
        canonicalTaskRepository.saveTask(canonicalTask)
        return canonicalTask
    }

    /**
     * Prepares and registers a task for execution. Returns the initialized DurableTaskRecord.
     * Persists the authoritative CanonicalTask intent and plan to SQLite before registering
     * the runtime lifecycle record.
     * Idempotent: Reopening/restarting loads the existing plan without recreating or reordering.
     */
    fun createTask(
        taskId: String = UUID.randomUUID().toString(),
        projectId: String,
        projectSlug: String,
        chatId: String,
        agentKind: String,
        providerJson: String,
        prompt: String,
        maxRetries: Int = 2,
        workspaceSha: String? = null,
        constraints: List<String> = emptyList(),
        acceptanceCriteria: List<String> = emptyList(),
        plan: ExecutionPlan? = null,
        objective: String? = null
    ): DurableTaskRecord {
        val existingCanonical = canonicalTaskRepository.getTask(taskId)
        val record = stateStore.get(taskId) ?: DurableTaskRecord(
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

        if (existingCanonical != null && existingCanonical.plan.steps.isNotEmpty()) {
            // Task and plan already exist: never recreate or reorder existing plan
            stateStore.save(record)
            refreshActiveTasks()
            return record
        }

        val resolvedSha = workspaceSha ?: resolveWorkspaceSha(projectId)
        val effectiveObjective = objective?.takeIf { it.isNotBlank() } ?: prompt
        val planId = plan?.planId?.ifBlank { "plan-$taskId" } ?: "plan-$taskId"
        val effCriteria = if (acceptanceCriteria.isNotEmpty()) {
            acceptanceCriteria
        } else if (existingCanonical?.acceptanceCriteria?.isNotEmpty() == true) {
            existingCanonical.acceptanceCriteria
        } else {
            listOf("Fulfill objective: $effectiveObjective")
        }

        val executionPlan = if (plan != null) {
            val validation = validateExecutionPlan(plan)
            require(validation.isValid) { "Invalid execution plan for task $taskId: ${validation.errorMessage}" }
            val deterministicSteps = plan.steps.mapIndexed { index, s ->
                s.copy(
                    stepId = s.stepId.ifBlank { "$taskId-step-$index" },
                    stepOrder = index,
                    objective = s.objective.ifBlank { s.description.ifBlank { s.title } },
                    acceptanceCriteria = if (s.acceptanceCriteria.isEmpty()) effCriteria else s.acceptanceCriteria,
                    status = s.status,
                    checkpointTag = if (s.checkpointTag.isNullOrBlank()) "step-${index + 1}" else s.checkpointTag,
                    planId = planId
                )
            }
            plan.copy(
                planId = planId,
                taskId = taskId,
                steps = deterministicSteps,
                currentStepIndex = plan.currentStepIndex.coerceIn(0, deterministicSteps.size),
                status = PlanStatus.PENDING
            )
        } else {
            try {
                taskDecomposer.decompose(
                    taskId = taskId,
                    objective = effectiveObjective,
                    context = TaskDecompositionContext(
                        projectId = projectId,
                        projectSlug = projectSlug,
                        constraints = constraints,
                        acceptanceCriteria = effCriteria
                    )
                )
            } catch (e: InvalidDecompositionException) {
                runCatching { Log.w(TAG, "Task decomposition failed in createTask for task $taskId: ${e.message}. Falling back to default single-step execution plan.") }
                val safeObjective = effectiveObjective.trim().ifBlank { "Execute Task" }
                val fallbackObjective = if (PlanBuilder.isAmbiguousObjective(safeObjective)) {
                    "Execute task request: $safeObjective"
                } else {
                    safeObjective
                }
                val fallbackCriteria = if (effCriteria.isNotEmpty()) {
                    effCriteria
                } else {
                    listOf("Fulfill objective: $fallbackObjective")
                }
                PlanBuilder(
                    planId = planId,
                    taskId = taskId,
                    title = "Execution Plan for $taskId"
                ).addStep(
                    title = "Execute Task",
                    objective = fallbackObjective,
                    acceptanceCriteria = fallbackCriteria,
                    stepOrder = 0
                ).build()
            }
        }

        val canonicalTask = CanonicalTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = projectSlug,
            objective = effectiveObjective,
            constraints = constraints,
            acceptanceCriteria = acceptanceCriteria,
            plan = executionPlan,
            initialWorkspaceSha = resolvedSha,
            currentWorkspaceSha = resolvedSha,
            failureHistory = emptyList(),
            activeRecoveryPlan = null,
            outcome = null
        )

        // Persist CanonicalTask and ExecutionPlan before registering runtime lifecycle state
        canonicalTaskRepository.saveTask(canonicalTask)

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
        REPLAY_UNSAFE,
        TRANSIENT_API_ERROR,
        PERMANENT_AUTH_OR_CONFIG,
        WORKSPACE_MUTATED_FAILURE,
        PROCESS_FAILURE,
        STEP_VERIFICATION_FAILURE,
        TRANSIENT_SYSTEM_FAULT,
        ENVIRONMENT_DRIFT
    }

    fun isCancellationActive(taskId: String): Boolean {
        val record = stateStore.get(taskId)
        return processSupervisor.isCancellationRequested(taskId) ||
            record?.cancellationRequested == true ||
            record?.status == TaskExecutionStatus.CANCELLED
    }

    fun classifyError(errorMsg: String, workspaceMutated: Boolean, isCancelled: Boolean): TaskErrorClassification {
        if (isCancelled) {
            return TaskErrorClassification.USER_CANCELLED
        }
        val lower = errorMsg.lowercase()
        if (lower.contains("replay_unsafe")) return TaskErrorClassification.REPLAY_UNSAFE
        if (lower.contains("api key") || lower.contains("user not found") ||
            lower.contains("authentication failed") || lower.contains("not signed in") ||
            lower.contains("http 401") || lower.contains("http 403") ||
            lower.contains("code: 401") || lower.contains("code: 403")) {
            return TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG
        }
        if (lower.contains("environment_drift") || lower.contains("environment drift") ||
            lower.contains("environment state drift") || lower.contains("workspace environment drift") ||
            lower.contains("corrupted workspace state") || lower.contains("corrupted workspace environment") ||
            lower.contains("inconsistent workspace state") || lower.contains("workspace state drift") ||
            lower.contains("external workspace drift") || lower.contains("workspace drift detected") ||
            lower.contains("environment drift detected") || lower.contains("dirty environment drift")) {
            return TaskErrorClassification.ENVIRONMENT_DRIFT
        }
        if (workspaceMutated) {
            return TaskErrorClassification.WORKSPACE_MUTATED_FAILURE
        }
        if (lower.contains("verification failed") || lower.contains("step verification")) {
            return TaskErrorClassification.STEP_VERIFICATION_FAILURE
        }
        if (lower.contains("transient_system_fault") || lower.contains("transient system fault") ||
            lower.contains("transient system failure") || lower.contains("transient system error") ||
            lower.contains("resource temporarily unavailable") || lower.contains("device or resource busy") ||
            lower.contains("ebusy") || lower.contains("interrupted system call") ||
            lower.contains("sqlite_busy") || lower.contains("database is locked") ||
            lower.contains("database locked") || lower.contains("lock acquisition timeout") ||
            lower.contains("lock contention") || lower.contains("temporary system error") ||
            lower.contains("transient fault") || lower.contains("system fault")) {
            return TaskErrorClassification.TRANSIENT_SYSTEM_FAULT
        }
        val hasTransientApiMarker = lower.contains("service is currently unavailable") ||
            lower.contains("http 500") || lower.contains("http 502") || lower.contains("http 503") || lower.contains("http 504") ||
            lower.contains("code 500") || lower.contains("code: 500") || lower.contains("code 502") || lower.contains("code: 502") ||
            lower.contains("code 503") || lower.contains("code: 503") || lower.contains("code 504") || lower.contains("code: 504") ||
            lower.contains("status 500") || lower.contains("status: 500") || lower.contains("status 502") || lower.contains("status: 502") ||
            lower.contains("status 503") || lower.contains("status: 503") || lower.contains("status 504") || lower.contains("status: 504") ||
            lower.contains("status code 500") || lower.contains("status code 502") || lower.contains("status code 503") || lower.contains("status code 504") ||
            lower.contains("503 service unavailable") || lower.contains("503 unavailable") ||
            lower.contains("500 internal server error") || lower.contains("502 bad gateway") || lower.contains("504 gateway timeout") ||
            lower.contains("socket timeout") || lower.contains("socket closed") || lower.contains("network error") ||
            lower.contains("network connection interrupted") || lower.contains("connection interrupted") ||
            lower.contains("software caused connection abort") || lower.contains("connection abort") || lower.contains("connection aborted") ||
            lower.contains("econnaborted") || lower.contains("broken pipe") || lower.contains("epipe") ||
            lower.contains("network unreachable") || lower.contains("network is unreachable") || lower.contains("no route to host") ||
            lower.contains("connection reset") || lower.contains("econnreset") || lower.contains("econnrefused") || lower.contains("connection refused") ||
            lower.contains("unknownhost") || lower.contains("unknown host") || lower.contains("unreachable") ||
            lower.contains("etimedout") || lower.contains("connection timed out") || lower.contains("timed out") ||
            lower.contains("handshake timeout") || lower.contains("ssl handshake") || lower.contains("tls handshake") ||
            lower.contains("read tcp") || lower.contains("write tcp") || lower.contains("dial tcp") ||
            lower.contains("streamgeneratecontent") || lower.contains("agent executor error") ||
            lower.contains("stream error") || lower.contains("stream closed") || lower.contains("stream terminated") ||
            lower.contains("unexpected eof") || lower.contains("transport: error") ||
            lower.contains("rate limit") || lower.contains("http 429") || lower.contains("code 429") || lower.contains("resource_exhausted")
        if (hasTransientApiMarker) {
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
            runCatching { Log.d(TAG, "Task $targetTaskId is already in terminal state ${task.status}; ignoring finalizeTask($status)") }
            return task
        }

        val finalizedRecord = when (status) {
            TaskExecutionStatus.COMPLETED, TaskExecutionStatus.UNVERIFIED -> {
                if (task.status != TaskExecutionStatus.COMPLETING) {
                    runCatching { stateStore.transition(targetTaskId, TaskExecutionStatus.COMPLETING) }
                }
                val completed = stateStore.transition(targetTaskId, status)
                healthMonitor.onTaskCompleted(targetTaskId, pid ?: completed.pid)
                if (appContext != null) {
                    RuntimeExecutionService.finish(
                        context = appContext,
                        title = if (status == TaskExecutionStatus.UNVERIFIED) "Task finished — unverified" else "Task completed",
                        detail = if (status == TaskExecutionStatus.UNVERIFIED) "No deterministic check was available for ${task.projectSlug}." else "Mobile Harness finished working in ${task.projectSlug}.",
                        failed = false
                    )
                }
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
                if (appContext != null) {
                    RuntimeExecutionService.finish(
                        context = appContext,
                        title = if (recoveryRequired) "Task needs attention" else "Task failed",
                        detail = failureDetail,
                        failed = true
                    )
                }
                failed
            }
            TaskExecutionStatus.CANCELLED -> {
                val cancelDetail = error ?: "Cancelled by user"
                val cancelled = stateStore.transition(targetTaskId, TaskExecutionStatus.CANCELLED) {
                    it.copy(lastError = cancelDetail)
                }
                healthMonitor.onTaskCompleted(targetTaskId, pid ?: cancelled.pid)
                if (appContext != null) {
                    RuntimeExecutionService.cancel(appContext)
                }
                val cancelFeedback = ExecutionFeedback(
                    taskId = targetTaskId,
                    projectId = task.projectId,
                    attemptId = "$targetTaskId:attempt-${task.retryCount}",
                    outcome = ExecutionOutcome.CANCELLED,
                    summary = cancelDetail,
                    source = MemorySource.PROJECT_OBSERVED
                )
                runCatching {
                    learnExecutionFeedback(cancelFeedback)
                }.onFailure {
                    runCatching { Log.e(TAG, "Brain learning persistence failed for cancelled task $targetTaskId", it) }
                }
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
                runCatching { Log.w(TAG, "finalizeTask called with non-terminal status $status for task $targetTaskId") }
                stateStore.transition(targetTaskId, status) { it.copy(lastError = error) }
            }
        }

        runCatching {
            when (status) {
                TaskExecutionStatus.COMPLETED -> canonicalTaskRepository.updatePlanStatus(targetTaskId, PlanStatus.COMPLETED)
                TaskExecutionStatus.UNVERIFIED -> canonicalTaskRepository.updatePlanStatus(targetTaskId, PlanStatus.UNVERIFIED)
                TaskExecutionStatus.FAILED, TaskExecutionStatus.ABANDONED -> canonicalTaskRepository.updatePlanStatus(targetTaskId, PlanStatus.FAILED)
                TaskExecutionStatus.CANCELLED -> canonicalTaskRepository.updatePlanStatus(targetTaskId, PlanStatus.CANCELLED)
                else -> {}
            }
        }

        wakeLockManager?.release(targetTaskId)
        healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)
        processSupervisor.unregister(targetTaskId)
        outputBuffers.remove(targetTaskId)
        fallbackDeciders.remove(targetTaskId)
        activeJobs.remove(targetTaskId)
        refreshActiveTasks()

        return finalizedRecord
    }

    /**
     * Authoritative execution feedback learning gateway.
     * Extracts and persists typed knowledge into Brain database through BrainLearningService.
     * Secondary persistence: errors are caught and logged without breaking caller workflows.
     */
    fun learnExecutionFeedback(feedback: ExecutionFeedback): LearningResult {
        return try {
            brainLearningService.learn(feedback)
        } catch (t: Throwable) {
            runCatching { Log.e(TAG, "Brain learning failed for task ${feedback.taskId} attempt ${feedback.attemptId}", t) }
            LearningResult(
                feedbackId = "execution:${feedback.taskId}:${feedback.attemptId}",
                errors = listOf("Unexpected error during learning: ${t.message}")
            )
        }
    }

    /**
     * Executes a task under strict process, lifecycle, and concurrency supervision.
     */
    /**
     * Executes a task under strict process, lifecycle, and concurrency supervision.
     */
    fun executeTask(
        taskId: String,
        executionBlock: suspend (task: DurableTaskRecord) -> Unit
    ): Job = executeTaskInternal(taskId, stepExecutionBlock = null, executionBlock = executionBlock)

    fun executeTask(
        taskId: String,
        stepExecutionBlock: suspend (task: DurableTaskRecord, step: ExecutionStep) -> Unit
    ): Job = executeTaskInternal(taskId, stepExecutionBlock = stepExecutionBlock, executionBlock = null)

    internal fun executeTaskInternal(
        taskId: String,
        stepExecutionBlock: (suspend (task: DurableTaskRecord, step: ExecutionStep) -> Unit)?,
        executionBlock: (suspend (task: DurableTaskRecord) -> Unit)?
    ): Job {
        val task = stateStore.get(taskId) ?: error("Task $taskId not found in store")
        canonicalTaskRepository.getTask(taskId)
            ?: error("CanonicalTask $taskId not found in repository; cannot execute task without canonical record")

        val currentInitialStatus = task.status
        if (currentInitialStatus.isTerminal) {
            runCatching { Log.w(TAG, "Task $taskId is already terminal ($currentInitialStatus); skipping executeTask") }
            return supervisorScope.launch { /* no-op job */ }
        }
        if (isCancellationActive(taskId)) {
            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before launch")
            return supervisorScope.launch { /* no-op job */ }
        }

        val job = supervisorScope.launch {
            startupReconciliation?.join()
            var executionOwnedElsewhere = false
            try {
                executionLock.withExecutionLock(task.taskId, task.projectId) {
                    val statusInsideLock = stateStore.get(taskId)?.status
                    if (statusInsideLock?.isTerminal == true) {
                        runCatching { Log.w(TAG, "Task $taskId became terminal ($statusInsideLock) before execution lock; skipping") }
                        return@withExecutionLock
                    }
                    if (isCancellationActive(taskId)) {
                        finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before launch")
                        return@withExecutionLock
                    }
                    var current = stateStore.transition(taskId, TaskExecutionStatus.STARTING)
                    refreshActiveTasks()
                    wakeLockManager?.acquire(taskId)
                    healthMonitor.onTaskStarted(taskId, current.pid)
                    healthMonitor.onWakeLockChanged(wakeLockManager?.isHeld == true, wakeLockManager?.activeTaskCount ?: 0)

                    // Start foreground service for notifications
                    if (appContext != null) {
                        RuntimeExecutionService.start(
                            context = appContext,
                            taskId = taskId,
                            projectName = task.projectSlug,
                            detail = "Starting ${task.agentKind} in ${task.projectSlug}…"
                        )
                    }

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

                            // Pre-execution cancellation check
                            if (isCancellationActive(taskId)) {
                                if (stateStore.get(taskId)?.status != TaskExecutionStatus.CANCELLED) {
                                    finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before launch")
                                }
                                break
                            }

                            // Pre-execution terminal check: Never execute attempt for already-terminal task
                            val statusBeforeRun = stateStore.get(taskId)?.status
                            if (statusBeforeRun != null && statusBeforeRun.isTerminal) {
                                break
                            }

                            // Capture Brain context snapshot ONCE for this execution attempt
                            val attemptId = "$taskId:attempt-$attempt"
                            getOrCreateBrainSnapshot(
                                taskId = taskId,
                                attempt = attempt,
                                attemptId = attemptId,
                                projectId = current.projectId,
                                query = current.prompt
                            )

                            // Post-snapshot pre-execution cancellation check
                            if (isCancellationActive(taskId)) {
                                if (stateStore.get(taskId)?.status != TaskExecutionStatus.CANCELLED) {
                                    finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before launch")
                                }
                                break
                            }

                            val loopStatus = stateStore.get(taskId)?.status
                            if (loopStatus in setOf(TaskExecutionStatus.WAITING_FOR_APPROVAL, TaskExecutionStatus.WAITING_FOR_INPUT)) {
                                break
                            }

                            // Validate execution plan before execution begins
                            var canonicalTask = canonicalTaskRepository.getTask(taskId)
                                ?: error("CanonicalTask $taskId not found in repository")
                            val planValidation = validateExecutionPlan(canonicalTask.plan)
                            if (!planValidation.isValid) {
                                finalizeTask(taskId, TaskExecutionStatus.FAILED, error = "Invalid execution plan: ${planValidation.errorMessage}")
                                break
                            }

                            // Transition plan status to IN_PROGRESS if PENDING
                            if (canonicalTask.plan.status == PlanStatus.PENDING) {
                                canonicalTask = canonicalTask.copy(plan = canonicalTask.plan.copy(status = PlanStatus.IN_PROGRESS))
                                canonicalTaskRepository.updatePlanStatus(taskId, PlanStatus.IN_PROGRESS)
                            }

                            // Transition to RUNNING
                            current = stateStore.transition(taskId, TaskExecutionStatus.RUNNING)
                            refreshActiveTasks()

                            // Establish canonical task baseline checkpoint before step execution begins
                            val initialWsDir = resolveWorkspaceDir(current.projectId)
                            val initialCheckpoints = getCheckpoints()
                            if (initialWsDir != null) {
                                if (!initialWsDir.exists()) {
                                    initialWsDir.mkdirs()
                                }
                                val existingBaselineMeta = initialCheckpoints.readMetadata(current.projectId, WorkspaceCheckpoints.TASK_BASELINE_TAG)
                                if (existingBaselineMeta == null || existingBaselineMeta.taskId != taskId) {
                                    initialCheckpoints.createCheckpoint(
                                        projectId = current.projectId,
                                        workspace = initialWsDir,
                                        checkpointTag = WorkspaceCheckpoints.TASK_BASELINE_TAG,
                                        taskId = taskId,
                                        stepId = null,
                                        attempt = attempt
                                    )
                                }
                            }

                            // Step-aware execution loop: executes strictly one step at a time
                            while (!succeeded && !isCancellationActive(taskId)) {
                                val preLoopStatus = stateStore.get(taskId)?.status
                                if (preLoopStatus in setOf(TaskExecutionStatus.WAITING_FOR_APPROVAL, TaskExecutionStatus.WAITING_FOR_INPUT)) {
                                    break
                                }

                                canonicalTask = canonicalTaskRepository.getTask(taskId)
                                    ?: error("CanonicalTask $taskId not found in repository")
                                val currentPlanSteps = canonicalTask.plan.steps
                                val currentStepIndex = canonicalTask.plan.currentStepIndex

                                // If all steps in the plan are already completed or skipped
                                val allStepsDone = currentPlanSteps.isNotEmpty() && currentPlanSteps.all {
                                    it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED || it.status == StepStatus.UNVERIFIED
                                }
                                if (allStepsDone || currentStepIndex >= currentPlanSteps.size) {
                                    break
                                }

                                var stepRecord = currentPlanSteps[currentStepIndex]
                                if (stepRecord.status == StepStatus.COMPLETED || stepRecord.status == StepStatus.SKIPPED || stepRecord.status == StepStatus.UNVERIFIED) {
                                    val nextIndex = (currentStepIndex + 1).coerceAtMost(currentPlanSteps.size)
                                    canonicalTask = canonicalTask.copy(
                                        plan = canonicalTask.plan.copy(currentStepIndex = nextIndex)
                                    )
                                    canonicalTaskRepository.saveTask(canonicalTask)
                                    canonicalTaskRepository.updateCurrentStepIndex(taskId, nextIndex)
                                    continue
                                }

                                val stepIndex = currentStepIndex

                                // Pre-step retry exhaustion check on restart
                                if (stepRecord.status == StepStatus.FAILED && stepRecord.attempts >= stepRecord.maxAttempts) {
                                    finalizeTask(
                                        taskId,
                                        TaskExecutionStatus.FAILED,
                                        error = "Step '${stepRecord.title}' failed and exhausted retries (${stepRecord.attempts}/${stepRecord.maxAttempts})",
                                        recoveryRequired = false
                                    )
                                    break
                                }

                                // Pre-step cancellation check
                                if (isCancellationActive(taskId)) {
                                    if (stateStore.get(taskId)?.status != TaskExecutionStatus.CANCELLED) {
                                        finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before step ${stepRecord.title}")
                                    }
                                    break
                                }

                                val stepTag = stepRecord.checkpointTag?.takeIf { it.isNotBlank() } ?: "step-${stepRecord.stepOrder + 1}"
                                val wsDir = resolveWorkspaceDir(current.projectId)
                                val checkpoints = getCheckpoints()

                                // Requirement 9: Restart during VERIFYING state:
                                // Never mark COMPLETED automatically; rerun trusted verification or transition safely to failure/recovery.
                                if (stepRecord.status == StepStatus.VERIFYING) {
                                    val verificationResult = try {
                                        runInterruptible(Dispatchers.IO) { stepVerifier.verify(canonicalTask, stepRecord, wsDir) }
                                    } catch (t: Throwable) {
                                        if (t is kotlinx.coroutines.CancellationException) throw t
                                        StepVerificationResult(
                                            passed = false,
                                            summary = "",
                                            failureReason = "Exception during step verification on restart: ${t.message}"
                                        )
                                    }

                                    if ((verificationResult.passed || verificationResult.unverified) && !isCancellationActive(taskId)) {
                                        val completedStep = stepRecord.copy(
                                            status = if (verificationResult.unverified) StepStatus.UNVERIFIED else StepStatus.COMPLETED,
                                            completedAt = Instant.now(),
                                            resultSummary = verificationResult.summary
                                        )
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.withUpdatedStep(completedStep)
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        stateStore.update(taskId) { it.copy(lastKnownStep = "step-${completedStep.stepOrder}:${completedStep.status}") }
                                        refreshActiveTasks()

                                        val nextStepIndex = (stepIndex + 1).coerceAtMost(canonicalTask.plan.steps.size)
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.copy(currentStepIndex = nextStepIndex)
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        canonicalTaskRepository.updateCurrentStepIndex(taskId, nextStepIndex)
                                        refreshActiveTasks()
                                        continue
                                    } else {
                                        val failReason = verificationResult.failureReason ?: "Verification rerun failed for step ${stepRecord.title}"
                                        val mutatedFiles = detectStepMutatedFiles(current.projectId, stepTag, wsDir)
                                        val classification = TaskErrorClassification.STEP_VERIFICATION_FAILURE
                                        val failureRecordId = "fail-$taskId-${stepRecord.stepId}-attempt-${stepRecord.attempts}"
                                        val failureRecord = TaskFailureRecord(
                                            failureId = failureRecordId,
                                            taskId = taskId,
                                            stepId = stepRecord.stepId,
                                            classification = "STEP_VERIFICATION_FAILURE",
                                            errorMessage = failReason,
                                            mutatedFiles = mutatedFiles
                                        )
                                        var activeStep = stepRecord.copy(resultSummary = failReason)
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                            failureHistory = canonicalTask.failureHistory + failureRecord
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        stateStore.update(taskId) { it.copy(lastError = failReason) }
                                        refreshActiveTasks()

                                        if (isCancellationActive(taskId)) {
                                            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during step verification rerun")
                                            break
                                        }

                                        val recoveryPlan = recoveryEngine.planRecovery(
                                            canonicalTask, activeStep, classification, failReason, mutatedFiles, attemptCount = activeStep.attempts
                                        )?.let { plan ->
                                            plan.copy(
                                                failureRecordId = failureRecord.failureId,
                                                stepId = activeStep.stepId,
                                                checkpointTag = if (plan.strategy == com.jarves.mh.model.brain.RecoveryStrategy.RETRY_STEP_DIRECT) null else (plan.checkpointTag ?: stepTag),
                                                attemptNumber = activeStep.attempts + 1,
                                                status = com.jarves.mh.model.brain.RecoveryStatus.PENDING,
                                                nextAction = "EXECUTE_RECOVERY"
                                            )
                                        }

                                        if (recoveryPlan != null) {
                                            activeStep = activeStep.copy(status = StepStatus.RECOVERING)
                                            canonicalTask = canonicalTask.copy(
                                                activeRecoveryPlan = recoveryPlan,
                                                plan = canonicalTask.plan.withUpdatedStep(activeStep)
                                            )
                                            canonicalTaskRepository.saveTask(canonicalTask)
                                            stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:RECOVERING:${recoveryPlan.strategy}") }
                                            refreshActiveTasks()

                                            if (isCancellationActive(taskId)) {
                                                val cancelledPlan = recoveryPlan.copy(
                                                    status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                    nextAction = "CANCELLED"
                                                )
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before recovery execution")
                                                break
                                            }

                                            val inProgressPlan = recoveryPlan.copy(status = com.jarves.mh.model.brain.RecoveryStatus.IN_PROGRESS)
                                            canonicalTask = canonicalTask.copy(activeRecoveryPlan = inProgressPlan)
                                            canonicalTaskRepository.saveTask(canonicalTask)

                                            val recoveryResult = recoveryEngine.executeRecovery(
                                                canonicalTask, activeStep, inProgressPlan, wsDir, checkpoints
                                            )

                                            if (isCancellationActive(taskId)) {
                                                val cancelledPlan = inProgressPlan.copy(
                                                    status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                    recoveryResult = recoveryResult.message,
                                                    nextAction = "CANCELLED"
                                                )
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during recovery")
                                                break
                                            }

                                            val finalRecoveryStatus = if (recoveryResult.success) com.jarves.mh.model.brain.RecoveryStatus.COMPLETED else com.jarves.mh.model.brain.RecoveryStatus.FAILED
                                             val willRetry = recoveryResult.shouldRetryStep && activeStep.attempts < activeStep.maxAttempts && !isCancellationActive(taskId)
                                            val nextAction = if (willRetry) "RETRY_STEP" else "TERMINATE"
                                            val updatedPlan = inProgressPlan.copy(
                                                status = finalRecoveryStatus,
                                                recoveryResult = recoveryResult.message,
                                                nextAction = nextAction
                                            )
                                            canonicalTask = canonicalTask.copy(activeRecoveryPlan = updatedPlan)
                                            canonicalTaskRepository.saveTask(canonicalTask)

                                            if (willRetry) {
                                                stateStore.update(taskId) { it.copy(retryCount = it.retryCount + 1, lastKnownStep = "step-${activeStep.stepOrder}:RECOVERY:${recoveryPlan.strategy}") }
                                                refreshActiveTasks()
                                                val backoff = recoveryEngine.calculateBackoffMillis(activeStep.attempts)
                                                if (backoff > 0) delay(backoff)
                                                stepRecord = activeStep
                                                continue
                                            } else {
                                                val terminalStatus = if (!recoveryResult.success) com.jarves.mh.model.brain.RecoveryStatus.FAILED else com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED
                                                activeStep = activeStep.copy(
                                                    status = StepStatus.FAILED,
                                                    completedAt = Instant.now(),
                                                    resultSummary = failReason
                                                )
                                                canonicalTask = canonicalTask.copy(
                                                    plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                                    activeRecoveryPlan = updatedPlan.copy(status = terminalStatus, nextAction = "TERMINATE")
                                                )
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:FAILED") }
                                                refreshActiveTasks()
                                                throw StepVerificationException(activeStep, failReason)
                                            }
                                        } else {
                                            activeStep = activeStep.copy(
                                                status = StepStatus.FAILED,
                                                completedAt = Instant.now(),
                                                resultSummary = failReason
                                            )
                                            canonicalTask = canonicalTask.copy(
                                                plan = canonicalTask.plan.withUpdatedStep(activeStep)
                                            )
                                            canonicalTaskRepository.saveTask(canonicalTask)
                                            stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:FAILED") }
                                            refreshActiveTasks()
                                            throw StepVerificationException(activeStep, failReason)
                                        }
                                    }
                                }

                                // 1. Resuming interrupted recovery if present from previous restart
                                val persistedRecovery = canonicalTask.activeRecoveryPlan
                                if (persistedRecovery != null && persistedRecovery.targetStepIndex == stepIndex &&
                                    persistedRecovery.status != com.jarves.mh.model.brain.RecoveryStatus.COMPLETED &&
                                    persistedRecovery.status != com.jarves.mh.model.brain.RecoveryStatus.CANCELLED &&
                                    persistedRecovery.status != com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED &&
                                    persistedRecovery.nextAction != "TERMINATE") {
                                    if (isCancellationActive(taskId)) {
                                        val cancelledPlan = persistedRecovery.copy(
                                            status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                            nextAction = "CANCELLED"
                                        )
                                        canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before recovery resume")
                                        break
                                    }

                                    val inProgressRecovery = persistedRecovery.copy(status = com.jarves.mh.model.brain.RecoveryStatus.IN_PROGRESS)
                                    canonicalTask = canonicalTask.copy(activeRecoveryPlan = inProgressRecovery)
                                    canonicalTaskRepository.saveTask(canonicalTask)

                                    val resumeResult = recoveryEngine.executeRecovery(
                                        canonicalTask, stepRecord, inProgressRecovery, wsDir, checkpoints
                                    )

                                    if (isCancellationActive(taskId)) {
                                        val cancelledPlan = inProgressRecovery.copy(
                                            status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                            recoveryResult = resumeResult.message,
                                            nextAction = "CANCELLED"
                                        )
                                        canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during recovery resume")
                                        break
                                    }

                                    val finalRecoveryStatus = if (resumeResult.success) com.jarves.mh.model.brain.RecoveryStatus.COMPLETED else com.jarves.mh.model.brain.RecoveryStatus.FAILED
                                    val willRetry = resumeResult.shouldRetryStep && stepRecord.attempts < stepRecord.maxAttempts
                                    val nextAction = if (willRetry) "RETRY_STEP" else "TERMINATE"
                                    val resumedPlan = inProgressRecovery.copy(
                                        status = finalRecoveryStatus,
                                        recoveryResult = resumeResult.message,
                                        nextAction = nextAction
                                    )
                                    canonicalTask = canonicalTask.copy(activeRecoveryPlan = resumedPlan)
                                    canonicalTaskRepository.saveTask(canonicalTask)

                                    if (!willRetry) {
                                        val terminalStatus = if (!resumeResult.success) com.jarves.mh.model.brain.RecoveryStatus.FAILED else com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED
                                        val exhaustedPlan = resumedPlan.copy(
                                            status = terminalStatus,
                                            nextAction = "TERMINATE"
                                        )
                                        val failedStep = stepRecord.copy(
                                            status = StepStatus.FAILED,
                                            completedAt = Instant.now(),
                                            resultSummary = resumeResult.message
                                        )
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.withUpdatedStep(failedStep),
                                            activeRecoveryPlan = exhaustedPlan
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        stateStore.update(taskId) { it.copy(lastKnownStep = "step-${stepRecord.stepOrder}:FAILED") }
                                        refreshActiveTasks()
                                        throw StepExecutionException(failedStep, resumeResult.message)
                                    }
                                }

                                var stepCompleted = false
                                while (!stepCompleted) {
                                    // Cancellation check inside attempt loop
                                    if (isCancellationActive(taskId)) {
                                        if (stateStore.get(taskId)?.status != TaskExecutionStatus.CANCELLED) {
                                            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before step ${stepRecord.title}")
                                        }
                                        break
                                    }

                                    // Pre-step approval / input pause check
                                    val preStatus = stateStore.get(taskId)?.status
                                    if (preStatus in setOf(TaskExecutionStatus.WAITING_FOR_APPROVAL, TaskExecutionStatus.WAITING_FOR_INPUT)) {
                                        break
                                    }

                                    val currentStepAttempt = stepRecord.attempts + 1

                                    // 1. Before every step attempt, persist the step checkpoint identity/tag and attempt
                                    if (wsDir != null) {
                                        if (!wsDir.exists()) {
                                            wsDir.mkdirs()
                                        }
                                        checkpoints.createCheckpoint(
                                            projectId = current.projectId,
                                            workspace = wsDir,
                                            checkpointTag = stepTag,
                                            taskId = taskId,
                                            stepId = stepRecord.stepId,
                                            attempt = currentStepAttempt
                                        )
                                    }

                                    // Activate Step: PENDING -> RUNNING & tagged checkpoint
                                    var activeStep = stepRecord.copy(
                                        status = StepStatus.RUNNING,
                                        attempts = currentStepAttempt,
                                        checkpointTag = stepTag,
                                        startedAt = stepRecord.startedAt ?: Instant.now()
                                    )
                                    canonicalTask = canonicalTask.copy(
                                        plan = canonicalTask.plan.withUpdatedStep(activeStep).copy(currentStepIndex = stepIndex)
                                    )
                                    canonicalTaskRepository.saveTask(canonicalTask)
                                    // Inject current step objective + criteria into Brain snapshot for this step attempt
                                    val stepAttemptId = "$taskId:step-${activeStep.stepOrder}:attempt-$currentStepAttempt"
                                    val stepSnapshot = getOrCreateBrainSnapshot(
                                        taskId = taskId,
                                        attempt = attempt,
                                        attemptId = stepAttemptId,
                                        projectId = current.projectId,
                                        query = activeStep.objective.ifBlank { activeStep.description.ifBlank { current.prompt } },
                                        currentStep = activeStep
                                    )
                                    activeBrainSnapshots[taskId] = stepSnapshot
                                    stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:RUNNING") }
                                    refreshActiveTasks()

                                    // 2. Execute Step
                                    var stepExecError: Throwable? = null
                                    try {
                                        if (stepExecutionBlock != null) {
                                            stepExecutionBlock(current, activeStep)
                                        } else if (executionBlock != null) {
                                            executionBlock(current)
                                        }
                                    } catch (t: Throwable) {
                                        if (t is kotlinx.coroutines.CancellationException) {
                                            throw t
                                        }
                                        val isCancelled = isCancellationActive(taskId) ||
                                            t.message?.contains("stopped by user", ignoreCase = true) == true
                                        if (isCancelled) {
                                            throw t
                                        }
                                        stepExecError = t
                                    }

                                    if (stepExecError != null) {
                                        val errorMsg = stepExecError.localizedMessage ?: stepExecError.message ?: "Step execution error"
                                        val mutatedFiles = detectStepMutatedFiles(current.projectId, stepTag, wsDir)
                                        val classification = classifyError(errorMsg, mutatedFiles.isNotEmpty(), isCancellationActive(taskId))

                                        if (classification == TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG || classification == TaskErrorClassification.REPLAY_UNSAFE) {
                                            throw stepExecError
                                        }

                                        if (isCancellationActive(taskId)) {
                                            val cancelledPlan = canonicalTask.activeRecoveryPlan?.copy(
                                                status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                nextAction = "CANCELLED"
                                            )
                                            if (cancelledPlan != null) {
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                            }
                                            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during step execution")
                                            break
                                        }

                                        val recoveryPlan = recoveryEngine.planRecovery(
                                            canonicalTask, activeStep, classification, errorMsg, mutatedFiles, attemptCount = activeStep.attempts
                                        )?.let { plan ->
                                            plan.copy(
                                                stepId = activeStep.stepId,
                                                checkpointTag = if (plan.strategy == com.jarves.mh.model.brain.RecoveryStrategy.RETRY_STEP_DIRECT) null else (plan.checkpointTag ?: stepTag),
                                                attemptNumber = activeStep.attempts + 1,
                                                status = com.jarves.mh.model.brain.RecoveryStatus.PENDING,
                                                nextAction = "EXECUTE_RECOVERY"
                                            )
                                        }

                                        val failureRecord = TaskFailureRecord(
                                            failureId = recoveryPlan?.failureRecordId ?: "fail-$taskId-${activeStep.stepId}-attempt-${activeStep.attempts}",
                                            taskId = taskId,
                                            stepId = activeStep.stepId,
                                            classification = classification.name,
                                            errorMessage = errorMsg,
                                            mutatedFiles = mutatedFiles
                                        )

                                        if (recoveryPlan != null) {
                                            activeStep = activeStep.copy(status = StepStatus.RECOVERING)
                                            canonicalTask = canonicalTask.copy(
                                                failureHistory = canonicalTask.failureHistory + failureRecord,
                                                activeRecoveryPlan = recoveryPlan,
                                                plan = canonicalTask.plan.withUpdatedStep(activeStep)
                                            )
                                            canonicalTaskRepository.saveTask(canonicalTask)
                                            stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:RECOVERING:${recoveryPlan.strategy}") }
                                            refreshActiveTasks()

                                            if (isCancellationActive(taskId)) {
                                                val cancelledPlan = recoveryPlan.copy(
                                                    status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                    nextAction = "CANCELLED"
                                                )
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before recovery execution")
                                                break
                                            }

                                            val inProgressPlan = recoveryPlan.copy(status = com.jarves.mh.model.brain.RecoveryStatus.IN_PROGRESS)
                                            canonicalTask = canonicalTask.copy(activeRecoveryPlan = inProgressPlan)
                                            canonicalTaskRepository.saveTask(canonicalTask)

                                            val recoveryResult = recoveryEngine.executeRecovery(
                                                canonicalTask, activeStep, inProgressPlan, wsDir, checkpoints
                                            )

                                            if (isCancellationActive(taskId)) {
                                                val cancelledPlan = inProgressPlan.copy(
                                                    status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                    recoveryResult = recoveryResult.message,
                                                    nextAction = "CANCELLED"
                                                )
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during recovery")
                                                break
                                            }

                                            val finalRecoveryStatus = if (recoveryResult.success) com.jarves.mh.model.brain.RecoveryStatus.COMPLETED else com.jarves.mh.model.brain.RecoveryStatus.FAILED
                                            val willRetry = recoveryResult.shouldRetryStep && activeStep.attempts < activeStep.maxAttempts && !isCancellationActive(taskId)
                                            val nextAction = if (willRetry) "RETRY_STEP" else "TERMINATE"
                                            val updatedPlan = inProgressPlan.copy(
                                                status = finalRecoveryStatus,
                                                recoveryResult = recoveryResult.message,
                                                nextAction = nextAction
                                            )
                                            canonicalTask = canonicalTask.copy(activeRecoveryPlan = updatedPlan)
                                            canonicalTaskRepository.saveTask(canonicalTask)

                                            if (willRetry) {
                                                stateStore.update(taskId) { it.copy(retryCount = it.retryCount + 1, lastKnownStep = "step-${activeStep.stepOrder}:RECOVERY:${recoveryPlan.strategy}") }
                                                refreshActiveTasks()
                                                val backoff = recoveryEngine.calculateBackoffMillis(activeStep.attempts)
                                                if (backoff > 0) delay(backoff)
                                                stepRecord = activeStep
                                                continue
                                            } else {
                                                val terminalStatus = if (!recoveryResult.success) com.jarves.mh.model.brain.RecoveryStatus.FAILED else com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED
                                                val exhaustedPlan = updatedPlan.copy(
                                                    status = terminalStatus,
                                                    nextAction = "TERMINATE"
                                                )
                                                activeStep = activeStep.copy(
                                                    status = StepStatus.FAILED,
                                                    completedAt = Instant.now(),
                                                    resultSummary = "Execution error: $errorMsg"
                                                )
                                                canonicalTask = canonicalTask.copy(
                                                    plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                                    activeRecoveryPlan = exhaustedPlan
                                                )
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:FAILED") }
                                                refreshActiveTasks()
                                                throw StepExecutionException(activeStep, errorMsg)
                                            }
                                        } else {
                                            val exhaustedPlan = canonicalTask.activeRecoveryPlan?.copy(
                                                status = com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED,
                                                nextAction = "TERMINATE"
                                            ) ?: RecoveryPlan(
                                                taskId = taskId,
                                                failureRecordId = failureRecord.failureId,
                                                strategy = RecoveryStrategy.SAFE_ABORT_AND_CLEANUP,
                                                rationale = "Retries exhausted for step ${activeStep.title}",
                                                targetStepIndex = activeStep.stepOrder,
                                                stepId = activeStep.stepId,
                                                checkpointTag = stepTag,
                                                attemptNumber = activeStep.attempts,
                                                status = com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED,
                                                recoveryResult = errorMsg,
                                                nextAction = "TERMINATE"
                                            )
                                            activeStep = activeStep.copy(
                                                status = StepStatus.FAILED,
                                                completedAt = Instant.now(),
                                                resultSummary = "Execution error: $errorMsg"
                                            )
                                            canonicalTask = canonicalTask.copy(
                                                plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                                activeRecoveryPlan = exhaustedPlan,
                                                failureHistory = canonicalTask.failureHistory + failureRecord
                                            )
                                            canonicalTaskRepository.saveTask(canonicalTask)
                                            stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:FAILED") }
                                            refreshActiveTasks()
                                            throw StepExecutionException(activeStep, errorMsg)
                                        }
                                    }

                                    // Post-execution cancellation or approval/input pause check
                                    if (isCancellationActive(taskId)) {
                                        if (stateStore.get(taskId)?.status != TaskExecutionStatus.CANCELLED) {
                                            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user after step execution")
                                        }
                                        break
                                    }
                                    val postExecStatus = stateStore.get(taskId)?.status
                                    if (postExecStatus in setOf(TaskExecutionStatus.WAITING_FOR_APPROVAL, TaskExecutionStatus.WAITING_FOR_INPUT)) {
                                        break
                                    }

                                    // 3. Transition: RUNNING -> VERIFYING
                                    activeStep = activeStep.copy(status = StepStatus.VERIFYING)
                                    canonicalTask = canonicalTask.copy(
                                        plan = canonicalTask.plan.withUpdatedStep(activeStep)
                                    )
                                    canonicalTaskRepository.saveTask(canonicalTask)
                                    stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:VERIFYING") }
                                    refreshActiveTasks()

                                    // 4. Perform Trusted Runtime Verification
                                    val verificationResult = try {
                                        runInterruptible(Dispatchers.IO) { stepVerifier.verify(canonicalTask, activeStep, wsDir) }
                                    } catch (t: Throwable) {
                                        if (t is kotlinx.coroutines.CancellationException) throw t
                                        StepVerificationResult(
                                            passed = false,
                                            summary = "",
                                            failureReason = "Exception during step verification: ${t.message}"
                                        )
                                    }

                                    if (verificationResult.passed || verificationResult.unverified) {
                                        if (isCancellationActive(taskId)) {
                                            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during step verification")
                                            break
                                        }

                                        // Requirement 8 & 9: StepVerifier alone marks COMPLETED, persist COMPLETED before advancing currentStepIndex
                                        activeStep = activeStep.copy(
                                            status = if (verificationResult.unverified) StepStatus.UNVERIFIED else StepStatus.COMPLETED,
                                            completedAt = Instant.now(),
                                            resultSummary = verificationResult.summary
                                        )
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.withUpdatedStep(activeStep)
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:${activeStep.status}") }
                                        refreshActiveTasks()

                                        // Requirement 10: Advance currentStepIndex exactly once
                                        val nextStepIndex = (stepIndex + 1).coerceAtMost(canonicalTask.plan.steps.size)
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.copy(currentStepIndex = nextStepIndex)
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        canonicalTaskRepository.updateCurrentStepIndex(taskId, nextStepIndex)
                                        refreshActiveTasks()

                                        stepCompleted = true
                                    } else {
                                        val failReason = verificationResult.failureReason ?: "Verification failed for step ${activeStep.title}"
                                        val mutatedFiles = detectStepMutatedFiles(current.projectId, stepTag, wsDir)
                                        val classification = TaskErrorClassification.STEP_VERIFICATION_FAILURE

                                        // Persist verification result/failure before recovery is invoked (Requirement 8)
                                        val failureRecord = TaskFailureRecord(
                                            failureId = "fail-$taskId-${activeStep.stepId}-attempt-${activeStep.attempts}",
                                            taskId = taskId,
                                            stepId = activeStep.stepId,
                                            classification = "STEP_VERIFICATION_FAILURE",
                                            errorMessage = failReason,
                                            mutatedFiles = mutatedFiles
                                        )
                                        activeStep = activeStep.copy(resultSummary = failReason)
                                        canonicalTask = canonicalTask.copy(
                                            plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                            failureHistory = canonicalTask.failureHistory + failureRecord
                                        )
                                        canonicalTaskRepository.saveTask(canonicalTask)
                                        stateStore.update(taskId) { it.copy(lastError = failReason) }
                                        refreshActiveTasks()

                                        if (isCancellationActive(taskId)) {
                                            val cancelledPlan = canonicalTask.activeRecoveryPlan?.copy(
                                                status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                nextAction = "CANCELLED"
                                            )
                                            if (cancelledPlan != null) {
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                            }
                                            finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during step verification")
                                            break
                                        }

                                        val recoveryPlan = recoveryEngine.planRecovery(
                                            canonicalTask, activeStep, classification, failReason, mutatedFiles, attemptCount = activeStep.attempts
                                        )?.let { plan ->
                                            plan.copy(
                                                failureRecordId = failureRecord.failureId,
                                                stepId = activeStep.stepId,
                                                checkpointTag = if (plan.strategy == com.jarves.mh.model.brain.RecoveryStrategy.RETRY_STEP_DIRECT) null else (plan.checkpointTag ?: stepTag),
                                                attemptNumber = activeStep.attempts + 1,
                                                status = com.jarves.mh.model.brain.RecoveryStatus.PENDING,
                                                nextAction = "EXECUTE_RECOVERY"
                                            )
                                        }

                                        if (recoveryPlan != null) {
                                            activeStep = activeStep.copy(status = StepStatus.RECOVERING)
                                            canonicalTask = canonicalTask.copy(
                                                activeRecoveryPlan = recoveryPlan,
                                                plan = canonicalTask.plan.withUpdatedStep(activeStep)
                                            )
                                            canonicalTaskRepository.saveTask(canonicalTask)
                                            stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:RECOVERING:${recoveryPlan.strategy}") }
                                            refreshActiveTasks()

                                            if (isCancellationActive(taskId)) {
                                                val cancelledPlan = recoveryPlan.copy(
                                                    status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                    nextAction = "CANCELLED"
                                                )
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user before recovery execution")
                                                break
                                            }

                                            val inProgressPlan = recoveryPlan.copy(status = com.jarves.mh.model.brain.RecoveryStatus.IN_PROGRESS)
                                            canonicalTask = canonicalTask.copy(activeRecoveryPlan = inProgressPlan)
                                            canonicalTaskRepository.saveTask(canonicalTask)

                                            val recoveryResult = recoveryEngine.executeRecovery(
                                                canonicalTask, activeStep, inProgressPlan, wsDir, checkpoints
                                            )

                                            if (isCancellationActive(taskId)) {
                                                val cancelledPlan = inProgressPlan.copy(
                                                    status = com.jarves.mh.model.brain.RecoveryStatus.CANCELLED,
                                                    recoveryResult = recoveryResult.message,
                                                    nextAction = "CANCELLED"
                                                )
                                                canonicalTask = canonicalTask.copy(activeRecoveryPlan = cancelledPlan)
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user during recovery")
                                                break
                                            }

                                            val finalRecoveryStatus = if (recoveryResult.success) com.jarves.mh.model.brain.RecoveryStatus.COMPLETED else com.jarves.mh.model.brain.RecoveryStatus.FAILED
                                            val willRetry = recoveryResult.shouldRetryStep && activeStep.attempts < activeStep.maxAttempts && !isCancellationActive(taskId)
                                            val nextAction = if (willRetry) "RETRY_STEP" else "TERMINATE"
                                            val updatedPlan = inProgressPlan.copy(
                                                status = finalRecoveryStatus,
                                                recoveryResult = recoveryResult.message,
                                                nextAction = nextAction
                                            )
                                            canonicalTask = canonicalTask.copy(activeRecoveryPlan = updatedPlan)
                                            canonicalTaskRepository.saveTask(canonicalTask)

                                            if (willRetry) {
                                                stateStore.update(taskId) { it.copy(retryCount = it.retryCount + 1, lastKnownStep = "step-${activeStep.stepOrder}:RECOVERY:${recoveryPlan.strategy}") }
                                                refreshActiveTasks()
                                                val backoff = recoveryEngine.calculateBackoffMillis(activeStep.attempts)
                                                if (backoff > 0) delay(backoff)
                                                stepRecord = activeStep
                                                continue
                                            } else {
                                                val terminalStatus = if (!recoveryResult.success) com.jarves.mh.model.brain.RecoveryStatus.FAILED else com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED
                                                val exhaustedPlan = updatedPlan.copy(
                                                    status = terminalStatus,
                                                    nextAction = "TERMINATE"
                                                )
                                                activeStep = activeStep.copy(
                                                    status = StepStatus.FAILED,
                                                    completedAt = Instant.now(),
                                                    resultSummary = verificationResult.failureReason
                                                )
                                                canonicalTask = canonicalTask.copy(
                                                    plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                                    activeRecoveryPlan = exhaustedPlan
                                                )
                                                canonicalTaskRepository.saveTask(canonicalTask)
                                                stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:FAILED") }
                                                refreshActiveTasks()
                                                throw StepVerificationException(activeStep, verificationResult.failureReason ?: "Verification failed for step ${activeStep.title}")
                                            }
                                        } else {
                                            val exhaustedPlan = canonicalTask.activeRecoveryPlan?.copy(
                                                status = com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED,
                                                nextAction = "TERMINATE"
                                            ) ?: RecoveryPlan(
                                                taskId = taskId,
                                                failureRecordId = failureRecord.failureId,
                                                strategy = RecoveryStrategy.SAFE_ABORT_AND_CLEANUP,
                                                rationale = "Retries exhausted for step ${activeStep.title}",
                                                targetStepIndex = activeStep.stepOrder,
                                                stepId = activeStep.stepId,
                                                checkpointTag = stepTag,
                                                attemptNumber = activeStep.attempts,
                                                status = com.jarves.mh.model.brain.RecoveryStatus.EXHAUSTED,
                                                recoveryResult = verificationResult.failureReason,
                                                nextAction = "TERMINATE"
                                            )
                                            activeStep = activeStep.copy(
                                                status = StepStatus.FAILED,
                                                completedAt = Instant.now(),
                                                resultSummary = verificationResult.failureReason
                                            )
                                            canonicalTask = canonicalTask.copy(
                                                plan = canonicalTask.plan.withUpdatedStep(activeStep),
                                                activeRecoveryPlan = exhaustedPlan
                                            )
                                            canonicalTaskRepository.saveTask(canonicalTask)
                                            stateStore.update(taskId) { it.copy(lastKnownStep = "step-${activeStep.stepOrder}:FAILED") }
                                            refreshActiveTasks()
                                            throw StepVerificationException(activeStep, verificationResult.failureReason ?: "Verification failed for step ${activeStep.title}")
                                        }
                                    }
                                }
                            }

                            // Check if all steps completed
                            val finalCanonical = canonicalTaskRepository.getTask(taskId) ?: canonicalTask
                            val allStepsCompleted = finalCanonical.plan.steps.isNotEmpty() &&
                                finalCanonical.plan.steps.all { it.status == StepStatus.COMPLETED || it.status == StepStatus.SKIPPED || it.status == StepStatus.UNVERIFIED }

                            if (allStepsCompleted && !isCancellationActive(taskId)) {
                                succeeded = true

                                val unverified = finalCanonical.plan.steps.any { it.status == StepStatus.UNVERIFIED } ||
                                    finalCanonical.plan.steps.none { it.status == StepStatus.COMPLETED }
                                val finalPlanStatus = if (unverified) PlanStatus.UNVERIFIED else PlanStatus.COMPLETED
                                val summary = if (unverified) "Execution finished; deterministic verification unavailable for one or more steps"
                                    else "Task execution verified on attempt $attempt"
                                canonicalTaskRepository.saveTask(finalCanonical.copy(
                                    plan = finalCanonical.plan.copy(status = finalPlanStatus),
                                    outcome = TaskOutcome(success = !unverified, summary = summary)
                                ))

                                // Unverified execution must not create success/progress evidence in Brain.
                                if (!unverified) {
                                    val completedSteps = finalCanonical.plan.steps.filter { it.status == StepStatus.COMPLETED }.map { it.stepId }
                                    val checkpoints = getCheckpoints()
                                    val mutatedFiles = runCatching { checkpoints.readChangedPaths(task.projectId) }.getOrDefault(emptyList())
                                    val feedback = ExecutionFeedback(
                                        taskId = taskId,
                                        projectId = current.projectId,
                                        attemptId = attemptId,
                                        outcome = ExecutionOutcome.SUCCESS,
                                        summary = summary,
                                        completedStepIds = completedSteps,
                                        workspaceState = if (mutatedFiles.isNotEmpty()) ExecutionWorkspaceState(modifiedFiles = mutatedFiles) else null,
                                        source = MemorySource.TOOL_VERIFIED,
                                        contextFingerprint = brainSnapshots[attemptId]?.fingerprint
                                    )
                                    runCatching { learnExecutionFeedback(feedback) }.onFailure {
                                        runCatching { Log.e(TAG, "Brain learning persistence failed for task $taskId attempt $attemptId", it) }
                                    }
                                }
                                finalizeTask(taskId, if (unverified) TaskExecutionStatus.UNVERIFIED else TaskExecutionStatus.COMPLETED)
                            } else {
                                val currentStatus = stateStore.get(taskId)?.status
                                if (currentStatus in setOf(TaskExecutionStatus.WAITING_FOR_APPROVAL, TaskExecutionStatus.WAITING_FOR_INPUT) ||
                                    isCancellationActive(taskId)) {
                                    break
                                }
                            }
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) {
                                throw t
                            }
                            val isStepFailure = t is StepVerificationException || t is StepExecutionException
                            val stepReason = if (t is StepVerificationException) t.reason else if (t is StepExecutionException) t.reason else null
                            val isAuthOrConfigFailure = stepReason != null && classifyError(stepReason, workspaceMutated = false, isCancelled = isCancellationActive(taskId)) == TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG

                            if (isStepFailure && !isAuthOrConfigFailure) {
                                val failedAttempt = attempt
                                val failedAttemptId = "$taskId:attempt-$failedAttempt"
                                processSupervisor.terminate(taskId, force = true)

                                val step = if (t is StepVerificationException) t.step else (t as StepExecutionException).step
                                val reason = if (t is StepVerificationException) t.reason else (t as StepExecutionException).reason
                                val classification = if (t is StepVerificationException) "STEP_VERIFICATION_FAILURE" else "STEP_EXECUTION_FAILURE"

                                val failureRecord = TaskFailureRecord(
                                    taskId = taskId,
                                    stepId = step.stepId,
                                    classification = classification,
                                    errorMessage = reason,
                                    mutatedFiles = emptyList()
                                )
                                val failureFeedback = ExecutionFeedback(
                                    taskId = taskId,
                                    projectId = current.projectId,
                                    attemptId = failedAttemptId,
                                    outcome = ExecutionOutcome.FAILED,
                                    summary = "Step '${step.title}' failed: $reason",
                                    failures = listOf(failureRecord),
                                    source = MemorySource.TOOL_VERIFIED,
                                    contextFingerprint = brainSnapshots[failedAttemptId]?.fingerprint
                                )
                                runCatching {
                                    learnExecutionFeedback(failureFeedback)
                                }.onFailure {
                                    runCatching { Log.e(TAG, "Brain learning persistence failed for task $taskId attempt $failedAttemptId", it) }
                                }

                                finalizeTask(
                                    taskId,
                                    TaskExecutionStatus.FAILED,
                                    error = "Step '${step.title}' failed: $reason",
                                    recoveryRequired = false
                                )
                                break
                            }

                            val failedAttempt = attempt
                            val failedAttemptId = "$taskId:attempt-$failedAttempt"
                            attempt++
                            val isCancelled = isCancellationActive(taskId) ||
                                t.message?.contains("stopped by user", ignoreCase = true) == true
                            val errorMsg = t.localizedMessage ?: t.message ?: "Task execution failed"

                            // Terminate any running child process from this attempt before retrying (do not mark cancellation requested for retry)
                            processSupervisor.terminate(taskId, force = true, markCancelled = false)

                            val checkpoints = getCheckpoints()
                            val mutatedFiles = runCatching { checkpoints.readChangedPaths(task.projectId) }.getOrDefault(emptyList()) ?: emptyList()
                            val workspaceIsMutated = mutatedFiles.isNotEmpty()

                            val classification = classifyError(errorMsg, workspaceIsMutated, isCancelled)

                            // Learning: construct failure feedback for this attempt (cancellation learns authoritatively in finalizeTask)
                            if (classification != TaskErrorClassification.USER_CANCELLED) {
                                val failureRecord = TaskFailureRecord(
                                    taskId = taskId,
                                    classification = classification.name,
                                    errorMessage = errorMsg,
                                    mutatedFiles = mutatedFiles
                                )
                                val failureFeedback = ExecutionFeedback(
                                    taskId = taskId,
                                    projectId = current.projectId,
                                    attemptId = failedAttemptId,
                                    outcome = ExecutionOutcome.FAILED,
                                    summary = errorMsg,
                                    failures = listOf(failureRecord),
                                    workspaceState = if (workspaceIsMutated) ExecutionWorkspaceState(modifiedFiles = mutatedFiles) else null,
                                    source = MemorySource.TOOL_VERIFIED,
                                    contextFingerprint = brainSnapshots[failedAttemptId]?.fingerprint
                                )
                                runCatching {
                                    learnExecutionFeedback(failureFeedback)
                                }.onFailure {
                                    runCatching { Log.e(TAG, "Brain learning persistence failed for task $taskId attempt $failedAttemptId", it) }
                                }
                            }
                            when (classification) {
                                TaskErrorClassification.USER_CANCELLED -> {
                                    finalizeTask(taskId, TaskExecutionStatus.CANCELLED, error = "Cancelled by user")
                                    break
                                }
                                TaskErrorClassification.REPLAY_UNSAFE -> {
                                    finalizeTask(taskId, TaskExecutionStatus.FAILED, error = errorMsg, recoveryRequired = true)
                                    break
                                }
                                TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG -> {
                                    val fallbackDecider = fallbackDeciders[taskId]
                                    val canFallback = fallbackDecider != null && attempt <= task.maxRetries && fallbackDecider.decideFallback(taskId, errorMsg)
                                    if (canFallback) {
                                        runCatching { Log.i(TAG, "Auth/config failure on attempt $attempt for task $taskId; fallback applied, retrying attempt $attempt") }
                                    } else {
                                        val canonical = canonicalTaskRepository.getTask(taskId)
                                        if (canonical != null && canonical.plan.steps.isNotEmpty()) {
                                            val stepIndex = canonical.plan.currentStepIndex.coerceIn(0, canonical.plan.steps.size - 1)
                                            val currentStep = canonical.plan.steps[stepIndex]
                                            if (currentStep.status != StepStatus.COMPLETED) {
                                                val failedStep = currentStep.copy(
                                                    status = StepStatus.FAILED,
                                                    completedAt = Instant.now(),
                                                    resultSummary = errorMsg
                                                )
                                                canonicalTaskRepository.saveTask(canonical.copy(plan = canonical.plan.withUpdatedStep(failedStep)))
                                            }
                                        }
                                        finalizeTask(taskId, TaskExecutionStatus.FAILED, error = errorMsg, recoveryRequired = false)
                                        break
                                    }
                                }
                                TaskErrorClassification.WORKSPACE_MUTATED_FAILURE -> {
                                    val failureDetail = "Task failed after modifying files (${mutatedFiles.size} changed). Auto-retry disabled ($errorMsg)."
                                    finalizeTask(taskId, TaskExecutionStatus.FAILED, error = failureDetail, recoveryRequired = true)
                                    break
                                }
                                TaskErrorClassification.STEP_VERIFICATION_FAILURE -> {
                                    finalizeTask(taskId, TaskExecutionStatus.FAILED, error = errorMsg, recoveryRequired = false)
                                    break
                                }
                                TaskErrorClassification.TRANSIENT_API_ERROR,
                                TaskErrorClassification.PROCESS_FAILURE,
                                TaskErrorClassification.TRANSIENT_SYSTEM_FAULT,
                                TaskErrorClassification.ENVIRONMENT_DRIFT -> {
                                    if (attempt <= task.maxRetries) {
                                        runCatching { Log.w(TAG, "Transient error on attempt $attempt for task $taskId: $errorMsg. Retrying in ${1000L * attempt}ms...") }
                                    } else {
                                        val failureDetail = if (classification == TaskErrorClassification.TRANSIENT_API_ERROR) {
                                            "Service unavailable after $attempt attempts ($errorMsg)"
                                        } else if (classification == TaskErrorClassification.TRANSIENT_SYSTEM_FAULT) {
                                            "Transient system fault after $attempt attempts ($errorMsg)"
                                        } else if (classification == TaskErrorClassification.ENVIRONMENT_DRIFT) {
                                            "Environment drift unrecovered after $attempt attempts ($errorMsg)"
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
            } catch (e: DuplicateExecutionException) {
                // Another execution still holds this task's or project's lock (for example a
                // previous task finishing its retry backoff or stop cleanup). Fail this launch
                // cleanly instead of letting the exception escape the job and crash the app.
                // A duplicate launch of the same task must not finalize the running execution.
                executionOwnedElsewhere = executionLock.isTaskActive(taskId)
                runCatching { Log.w(TAG, "Execution lock unavailable for task $taskId: ${e.message}") }
                if (!executionOwnedElsewhere) {
                    try {
                        finalizeTask(
                            taskId,
                            TaskExecutionStatus.FAILED,
                            error = "Another task is still running in this project. Wait for it to finish, then try again."
                        )
                    } catch (finalizeError: Exception) {
                        runCatching { Log.e(TAG, "Failed to finalize task $taskId after lock conflict", finalizeError) }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ordinary execution failures must not escape the supervisor job: with no
                // handler they crash the app. The finally block below finalizes the task.
                // JVM Errors (OutOfMemoryError, StackOverflowError, ...) are deliberately not caught.
                runCatching { Log.e(TAG, "Unhandled error while executing task $taskId", e) }
            } finally {
                if (!executionOwnedElsewhere) {
                    try {
                        val currentRecord = stateStore.get(taskId)
                        if (currentRecord != null && !currentRecord.status.isTerminal &&
                            currentRecord.status != TaskExecutionStatus.WAITING_FOR_APPROVAL &&
                            currentRecord.status != TaskExecutionStatus.WAITING_FOR_INPUT) {
                            val finalStatus = when {
                                processSupervisor.isCancellationRequested(taskId) -> TaskExecutionStatus.CANCELLED
                                // CREATED -> ABANDONED is not a legal transition; a task that never
                                // started is a failed launch.
                                currentRecord.status == TaskExecutionStatus.CREATED -> TaskExecutionStatus.FAILED
                                else -> TaskExecutionStatus.ABANDONED
                            }
                            finalizeTask(taskId, finalStatus, error = "Execution ended prematurely")
                        }
                    } catch (finalizeError: Exception) {
                        runCatching { Log.e(TAG, "Failed to finalize task $taskId after execution ended", finalizeError) }
                    }
                    activeJobs.remove(taskId)
                }
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
