package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * [RuntimeBridge] driving OpenAI's Codex CLI in non-interactive mode:
 * `codex exec --json ... -` with the prompt on stdin.
 *
 * One process per turn. The process is confined by the PRoot guest, so Codex's own sandbox is
 * off and approvals are `never`. Conversation continuity comes from the prompt context (the CLI
 * runs `--ephemeral`), exactly like the DeepSeek harness. Parsing, the watchdogs and the outcome
 * decision live in [CodexTurnRunner]; this class owns the Android side: preflight, process
 * launch, event forwarding, workspace checkpoints and cleanup.
 *
 * Stability contract: every failure becomes exactly one [RuntimeEvent.SessionFailed]; the child
 * process and its temporary files are always cleaned up; nothing here can block on a live process.
 */
class CodexRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    override val isRunning: Boolean get() = activeProcess?.isAlive == true
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeTaskId: String? = null
    private val stopState = BridgeStopState()
    private var userStopRequested by stopState::userStopRequested
    @Volatile private var activeProjectSlug: String? = null
    @Volatile private var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        memory: ContextMemory,
        taskId: String?,
        brainSnapshot: com.jarves.mh.data.BrainContextSnapshot?,
        attemptId: String?,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        if (taskId != null) {
            runCatching {
                com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).bindSession(taskId, sessionId)
            }
        }
        val snapshot = brainSnapshot ?: taskId?.let {
            runCatching { com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).getBrainSnapshot(it) }.getOrNull()
        }
        val effectiveAttemptId = attemptId ?: snapshot?.attemptId
        val injectedPrompt = ControlledBrainInjector.inject(prompt, snapshot, taskId, effectiveAttemptId)
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        activeTaskId = taskId
        val cancellationActive = taskId != null && runCatching {
            com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).isCancellationActive(taskId)
        }.getOrDefault(false)
        if (stopState.beginSession(taskId, cancellationActive)) {
            activeSessionId = null
            activeTaskId = null
            emitFailureOnce(sessionId, "Stopped by user")
            cancelForegroundRuntime()
            throw CodexSessionException("Stopped by user")
        }
        val isTaskTerminal = taskId != null && runCatching {
            val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
            supervisor.stateStore.get(taskId)?.status?.isTerminal == true
        }.getOrDefault(false)
        if (isTaskTerminal) {
            activeSessionId = null
            activeTaskId = null
            emitFailureOnce(sessionId, "Task $taskId is already terminal")
            throw IllegalStateException("Task $taskId is already terminal")
        }
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Codex…")

        var lastMessageFile: File? = null
        var outputFile: File? = null
        try {
            runCatching {
                RuntimeTaskController.stopAction = {
                    userStopRequested = true
                    val running = activeProcess
                    if (running != null) {
                        Thread {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }.start()
                    }
                }
                val route = CodexRouteMapper.forProfile(provider)
                val secret = if (route is CodexRoute.ApiKey) secretFor(provider).orEmpty() else null
                if (route is CodexRoute.ApiKey && secret.isNullOrBlank()) {
                    throw CodexSessionException("No API key is saved for ${provider.kind.title}.")
                }
                startForegroundRuntime(projectSlug, taskId)
                check(installer.isCodexInstalled()) {
                    "Codex is not installed or needs repair. Open Settings → Coding agent and tap Install."
                }
                val installed = installer.installedRuntime()
                if (route is CodexRoute.ChatGptLogin && !hasChatGptCredentials(installed.rootfs)) {
                    throw CodexSessionException("Codex is not signed in. Sign in from Settings → Coding agent.")
                }
                if (userStopRequested) throw CodexSessionException("Stopped by user")

                val workspace = checkpoints.ensureWorkspace(projectId)
                checkpoints.createCheckpoint(projectId, workspace)
                val before = checkpoints.snapshot(workspace)

                val guestWorkspacePath = "/workspace/$projectSlug"
                val contextPrompt = buildContextPrompt(injectedPrompt, conversationHistory, guestWorkspacePath, projectKind, memory)
                val lastFile = File(installed.rootfs, "tmp/$LAST_MESSAGE_PREFIX$sessionId.txt").also {
                    it.parentFile?.mkdirs()
                    lastMessageFile = it
                }
                val capture = File(context.cacheDir, "codex-output-$sessionId.log").also { outputFile = it }
                val prefs = AppPreferences(context)
                val reasoningEffort = com.jarves.mh.model.codexEffortToLaunch(
                    prefs.codexReasoningEffort,
                    route.model,
                    prefs.loadModelList(AgentKind.CODEX, provider.kind, provider.baseUrl),
                )
                val imagePaths = CodexLaunchBuilder.imagePaths(prompt, guestWorkspacePath)
                val command = CodexLaunchBuilder.command(route, guestWorkspacePath, "/tmp/${lastFile.name}", reasoningEffort, imagePaths)
                val environment = CodexLaunchBuilder.environment(route, secret)
                Log.d(TAG, "Route: ${route::class.simpleName}, Model: ${route.model.ifBlank { "default" }}")
                val process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    workspace,
                    environment,
                    command,
                    guestWorkspacePath = guestWorkspacePath,
                    // Same reason as the other one-shot harnesses: PRoot's hard-link emulation can turn
                    // an atomic temp-file rename into a dangling `.l2s` symlink.
                    emulateHardLinks = false,
                    outputFile = capture,
                )
                activeProcess = process
                val nativeProcess = process as? NativeSpawnProcess ?: error("Unsupported Android runtime process")
                val bound = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).bindProcess(
                    taskId = taskId ?: sessionId,
                    sessionId = sessionId,
                    process = process,
                    pid = nativeProcess.processPid,
                )
                if (!bound) {
                    Log.w(TAG, "Process binding failed for task $taskId / session $sessionId (PID: ${nativeProcess.processPid})")
                }

                val mapper = CodexEventMapper(sessionId)
                val runner = CodexTurnRunner(
                    io = NativeCodexProcessIo(nativeProcess),
                    prompt = contextPrompt,
                    emit = { event -> forward(mapper, event) },
                    isStopRequested = { userStopRequested },
                    readLastMessage = { lastFile.takeIf { it.isFile }?.readText() },
                )
                val result = runner.run()
                Log.d(TAG, "Codex exited with code ${result.exitCode}, completed=${result.completed}")

                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    Log.d(TAG, "Changed files: ${changed.size}")
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                    acceptLastChanges(projectId)
                }
                if (userStopRequested) throw CodexSessionException("Stopped by user")
                if (!result.completed) throw CodexSessionException(result.failure.ifBlank { "Codex stopped unexpectedly." })
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "Codex finished the task in $projectSlug.",
                )
            }.onFailure { error ->
                Log.e(TAG, "Session failed", error)
                activeProcess?.let { if (it.isAlive) it.destroyForcibly() }
                val message = friendlyError(error)
                emitFailureOnce(sessionId, message)
                if (userStopRequested) {
                    cancelForegroundRuntime()
                } else {
                    finishForegroundRuntime(completed = false, projectName = projectSlug, detail = message)
                }
                if (!userStopRequested) throw error
            }
        } finally {
            // Runs on every outcome, including a throwing failure path, so no process or log leaks.
            cleanupSession(sessionId, lastMessageFile, outputFile)
        }
        sessionId
    }

    /** Idempotent. Runs on every outcome so a failed turn never leaves a process or log behind. */
    private fun cleanupSession(sessionId: String, lastMessageFile: File?, outputFile: File?) {
        runCatching { activeProcess?.takeIf { it.isAlive }?.destroyForcibly() }
        runCatching { lastMessageFile?.delete() }
        runCatching { outputFile?.delete() }
        if (activeSessionId == sessionId) {
            activeProcess = null
            activeSessionId = null
            activeTaskId = null
            RuntimeTaskController.stopAction = null
        }
    }

    private suspend fun forward(mapper: CodexEventMapper, event: CodexEvent) {
        when (event) {
            is CodexEvent.Reasoning -> pushForegroundProgress("Thinking…")
            is CodexEvent.CommandStarted ->
                pushForegroundProgress("Running ${CodexEventMapper.displayCommand(event.command)}")
            is CodexEvent.AgentMessage -> pushForegroundProgress("Writing the answer…")
            else -> Unit
        }
        mapper.map(event).forEach { eventBus.emit(it) }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // `codex exec` runs with approval_policy=never; no approval is ever requested.
    }

    override suspend fun stopSession(sessionId: String, force: Boolean) = withContext(Dispatchers.IO) {
        userStopRequested = true
        val resolvedTaskId: String? = runCatching {
            com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).getTaskIdForSession(sessionId)
        }.getOrNull() ?: activeTaskId
        if (resolvedTaskId != null) {
            stopState.requestStop(resolvedTaskId)
        }
        stopProcess(force)
        emitFailureOnce(sessionId, "Stopped by user")
    }

    override suspend fun stopActiveSession(force: Boolean) {
        userStopRequested = true
        val currentSessionId = activeSessionId
        if (currentSessionId != null) {
            stopSession(currentSessionId, force)
        } else {
            stopProcess(force)
        }
    }

    private suspend fun stopProcess(force: Boolean) {
        val proc = activeProcess ?: return
        if (force) {
            (proc as? NativeSpawnProcess)?.destroyForcibly() ?: proc.destroyForcibly()
        } else {
            (proc as? NativeSpawnProcess)?.interrupt() ?: proc.destroy()
            delay(STOP_GRACE_MS)
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.undoLastChanges(projectId)
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.undoFileChange(projectId, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (checkpoints.isInternalRuntimePath(path) || path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "Codex finished the task.",
            )
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "your project",
                    detail = reason,
                )
            }
        }
    }

    private fun friendlyError(error: Throwable): String = when (error) {
        is CodexSessionException, is CodexUnsupportedProviderException ->
            error.message.orEmpty().ifBlank { "Codex could not start." }
        else -> CodexFailureMessages.friendly(error.message.orEmpty())
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        memory: ContextMemory = ContextMemory(""),
    ): String = CodexPromptNotes.withPhoneEnvironment(
        com.jarves.mh.data.PromptContextSupport.buildPrompt(
            currentPrompt = currentPrompt,
            history = history.dropLast(1), // the current prompt was just appended to history
            guestWorkspacePath = guestWorkspacePath,
            projectKind = projectKind,
            memory = memory,
            androidStackInstalled = installer.isStackInstalled(com.jarves.mh.model.DevStack.ANDROID),
        ),
    )

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < FOREGROUND_PROGRESS_MIN_INTERVAL_MS) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        runCatching {
            context.startService(
                android.content.Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, activeProjectSlug)
                    .putExtra(RuntimeExecutionService.EXTRA_DETAIL, text),
            )
        }
    }

    private fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    private fun startForegroundRuntime(projectName: String, taskId: String? = null) {
        ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .apply { if (taskId != null) putExtra(RuntimeExecutionService.EXTRA_TASK_ID, taskId) },
        )
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            RuntimeExecutionService.finish(
                context = context,
                title = if (completed) "Task completed" else "Task needs attention",
                detail = detail,
                failed = !completed,
                projectName = projectName,
            )
        }
    }

    private fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            RuntimeExecutionService.cancel(context)
        }
    }

    private class CodexSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val TAG = "CodexBridge"
        private const val LAST_MESSAGE_PREFIX = "mh-codex-last-"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val STOP_GRACE_MS = 1_500L

        /** `codex login` stores ChatGPT credentials in `$CODEX_HOME/auth.json` inside the guest. */
        internal fun hasChatGptCredentials(rootfs: File): Boolean {
            val auth = File(rootfs, CodexLaunchBuilder.CODEX_HOME_GUEST_PATH.removePrefix("/") + "/auth.json")
            return runCatching { auth.isFile && auth.length() > 2L }.getOrDefault(false)
        }
    }
}
