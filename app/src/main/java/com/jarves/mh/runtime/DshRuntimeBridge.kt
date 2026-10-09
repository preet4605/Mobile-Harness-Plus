package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.data.renderMemoryBlock
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RuntimeBridge] driving the official DeepSeek Harness (`dsh`) in headless
 * one-shot mode: `dsh --profile headless "<task>"`.
 *
 * Verified contract (dsh 0.1.2-rc.1): the final answer goes to stdout, provider
 * reasoning deltas stream under a `dsh: reasoning:` heading, failures print
 * `dsh: <CODE>: <message>`, exit 0 means the turn completed. Because the
 * native launcher merges stdout+stderr into one capture file, this bridge
 * separates the streams by the `dsh:` diagnostic prefix.
 *
 * Auth and model selection are fully non-interactive: keys travel in the
 * process environment (`DEEPSEEK_API_KEY` for the native route, one shared
 * `MH_DSH_API_KEY` for hand-declared routes) and `$DSH_HOME/settings.yaml`
 * carries the default model plus any custom provider route.
 */
class DshRuntimeBridge(
    private val context: Context,
    val accountManager: AntigravityAccountManager? = null,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val preferences = com.jarves.mh.data.AppPreferences(context)
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
    @Volatile private var lastThinkingUpdateAt: Long = 0L

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
            throw DshSessionException("Stopped by user")
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
        lastThinkingUpdateAt = 0L
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting DeepSeek Harness…")
        val effectiveAccountMgr = accountManager ?: AntigravityAccountManager(context, com.jarves.mh.data.AppPreferences(context))
        if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER && effectiveAccountMgr.selectAccountForTurn() == null) {
            val message = "Google account not signed in. Sign in under Antigravity settings to use Antigravity models."
            activeSessionId = null
            emitFailureOnce(sessionId, message)
            throw DshSessionException(message)
        }
        val secret = if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER) {
            secretFor(provider)?.ifBlank { "antigravity-local-token" } ?: "antigravity-local-token"
        } else {
            secretFor(provider).orEmpty()
        }
        if (secret.isBlank()) {
            val message = "No API key is saved for ${provider.kind.title}."
            activeSessionId = null
            emitFailureOnce(sessionId, message)
            throw DshSessionException(message)
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            val message = "Claude subscription login is not supported by DeepSeek Harness. Pick a key-based provider in Settings."
            activeSessionId = null
            emitFailureOnce(sessionId, message)
            throw DshSessionException(message)
        }

        var antigravityGateway: AntigravityGatewayServer? = null
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
            startForegroundRuntime(projectSlug, taskId)
            val installed = installer.installedRuntime()
            check(installer.isAgentInstalled(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek Harness is not installed. Open Settings → Coding agent to install it."
            }
            installer.cleanDshNativeCache()
            installer.ensureDshAndroidCompatibility()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER) {
                antigravityGateway = AntigravityGatewayServer(effectiveAccountMgr, targetModel = { provider.model }).start()
            }
            val route = DshRouteMapper.forProfile(provider, localGatewayUrl = antigravityGateway?.url)
            writeDshSettings(installed.rootfs, route, provider)
            val dshSecret = if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER && antigravityGateway != null) {
                antigravityGateway.gatewaySecret
            } else {
                secret
            }
            val environment = buildDshEnvironment(route, dshSecret)

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(injectedPrompt, conversationHistory, guestWorkspacePath, projectKind, memory)
            val command = listOf("/usr/local/bin/dsh", "--profile", "sdk")
            Log.d("DshBridge", "Route: ${route.name}, Model: ${provider.model}")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
                credentialHome = DSH_HOME_GUEST_PATH,
                // dsh's editor saves through an atomic temp-file rename. PRoot's
                // hard-link emulation turns that rename into a dangling `.l2s`
                // symlink after the temp file is removed, losing the real file.
                emulateHardLinks = false,
            )
            activeProcess = process
            val nativePid = (process as? NativeSpawnProcess)?.processPid
            val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
            val bound = supervisor.bindProcess(
                taskId = taskId ?: sessionId,
                sessionId = sessionId,
                process = process,
                pid = nativePid
            )
            if (!bound) {
                Log.w("DshBridge", "Process binding failed for task $taskId / session $sessionId (PID: $nativePid)")
            }
            if (userStopRequested) process.destroy()
            val sdkResult = runSdkSession(
                process = process,
                sessionId = sessionId,
                route = route,
                model = provider.model.ifBlank { route.defaultModel },
                guestWorkspacePath = guestWorkspacePath,
                prompt = contextPrompt,
            )
            val exit = process.waitFor()
            (process as? NativeSpawnProcess)?.checkCapture()
            Log.d("DshBridge", "SDK process exited with code $exit")
            val changed = checkpoints.changedFiles(workspace, before)
            if (changed.isNotEmpty()) {
                Log.d("DshBridge", "Changed files: $changed")
                checkpoints.saveChangedPaths(projectId, changed)
                val details = checkpoints.buildChangeDetails(projectId, workspace, checkpoints.readChangedPaths(projectId))
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
            } else if (!File(checkpoints.checkpointDir(projectId), "changes.json").isFile) {
                acceptLastChanges(projectId)
            }
            if (exit == 0 && sdkResult.completed && !userStopRequested) {
                emitCompletedOnce(sessionId)
                finishForegroundRuntime(
                    completed = true,
                    projectName = projectSlug,
                    detail = "DeepSeek Harness finished the task in $projectSlug.",
                )
            } else {
                if (userStopRequested) throw DshSessionException("Stopped by user")
                error(sdkResult.failure.ifBlank { "DeepSeek Harness stopped with exit code $exit" })
            }
        }.onFailure { error ->
            Log.e("DshBridge", "Session failed", error)
            activeProcess?.let { if (it.isAlive) it.destroyForcibly() }
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
            if (!userStopRequested) {
                if (activeSessionId == sessionId) {
                    (activeProcess as? NativeSpawnProcess)?.outputFile?.delete()
                    antigravityGateway?.close()
                    activeProcess = null
                    activeSessionId = null
                    activeTaskId = null
                    RuntimeTaskController.stopAction = null
                }
                throw error
            }
        }
        if (activeSessionId == sessionId) {
            (activeProcess as? NativeSpawnProcess)?.outputFile?.delete()
            antigravityGateway?.close()
            activeProcess = null
            activeSessionId = null
            activeTaskId = null
            RuntimeTaskController.stopAction = null
        }
        sessionId
    }

    private suspend fun runSdkSession(
        process: Process,
        sessionId: String,
        route: DshRoute,
        model: String,
        guestWorkspacePath: String,
        prompt: String,
    ): DshSdkRunResult {
        val nativeProcess = process as? NativeSpawnProcess
            ?: error("Unsupported Android runtime process")
        val writer = process.outputStream.bufferedWriter()
        val parser = DshSdkProtocolParser(sessionId)
        var outputOffset = 0L
        val pendingOutput = CodexLineAssembler()
        var promptSent = false
        var sawRunning = false
        var completed = false
        var sawActivity = false
        var shutdownSent = false
        var shutdownSentAt = 0L
        var inputClosed = false
        var failure = ""

        fun send(method: String, id: Int, params: JSONObject? = null) {
            val frame = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("method", method)
            if (params != null) frame.put("params", params)
            writer.write(frame.toString())
            writer.newLine()
            writer.flush()
        }

        fun closeInput() {
            if (inputClosed) return
            inputClosed = true
            runCatching { writer.close() }
        }

        send(
            method = "initialize",
            id = SDK_INITIALIZE_ID,
            params = buildInitializeParams(guestWorkspacePath, route.name, model, preferences.dshReasoningEffort),
        )

        suspend fun handle(protocolEvent: DshSdkProtocolEvent) {
            when (protocolEvent) {
                DshSdkProtocolEvent.Initialized -> if (!promptSent) {
                    send(
                        method = "session/prompt",
                        id = SDK_PROMPT_ID,
                        params = JSONObject()
                            .put("sessionId", sessionId)
                            .put(
                                "contentBlocks",
                                JSONArray().put(JSONObject().put("type", "text").put("text", prompt)),
                            ),
                    )
                    promptSent = true
                }
                DshSdkProtocolEvent.PromptAccepted -> Unit
                is DshSdkProtocolEvent.Status -> {
                    if (protocolEvent.running) {
                        sawRunning = true
                        pushForegroundProgress("DeepSeek Harness is working…")
                    } else if (sawRunning && !shutdownSent) {
                        completed = sawActivity && failure.isBlank()
                        if (!completed && failure.isBlank()) {
                            failure = "DeepSeek Harness stopped before processing the prompt"
                        }
                        shutdownSent = true
                        shutdownSentAt = android.os.SystemClock.elapsedRealtime()
                        send("shutdown", SDK_SHUTDOWN_ID)
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    sawActivity = true
                    emitReasoningSummary(
                        sessionId = sessionId,
                        text = protocolEvent.text,
                        blockId = protocolEvent.blockId,
                        startsNewBlock = protocolEvent.startsNewBlock,
                        isFinal = protocolEvent.isFinal,
                        force = protocolEvent.startsNewBlock || protocolEvent.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolStarted(sessionId, protocolEvent.name, protocolEvent.detail),
                    )
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    sawActivity = true
                    eventBus.emit(
                        RuntimeEvent.ToolCompleted(sessionId, protocolEvent.name, protocolEvent.summary, protocolEvent.previewUrl),
                    )
                }
                is DshSdkProtocolEvent.AssistantText -> if (protocolEvent.text.isNotEmpty()) {
                    sawActivity = true
                    eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                }
                is DshSdkProtocolEvent.UsageUpdated -> {
                    if (protocolEvent.text.isNotEmpty()) {
                        sawActivity = true
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, protocolEvent.text))
                    }
                    eventBus.emit(RuntimeEvent.TokenUsageUpdated(sessionId, protocolEvent.metrics))
                }
                is DshSdkProtocolEvent.ChildUpdated -> eventBus.emit(RuntimeEvent.SubagentUpdated(sessionId, protocolEvent.child))
                is DshSdkProtocolEvent.Failed -> failure = protocolEvent.message
                DshSdkProtocolEvent.TurnCompleted -> sawActivity = true
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeInput()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
            if (
                process.isAlive &&
                shutdownSentAt > 0L &&
                android.os.SystemClock.elapsedRealtime() - shutdownSentAt >= SDK_SHUTDOWN_TIMEOUT_MS
            ) {
                closeInput()
                process.destroyForcibly()
            }
            val available = nativeProcess.outputFile.length() - outputOffset
            if (available <= 0) {
                delay(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                file.seek(outputOffset)
                file.read(bytes)
            }
            if (count <= 0) continue
            outputOffset += count
            for (line in pendingOutput.feed(bytes, count)) {
                check(line != CodexLineAssembler.OVERSIZED) { "Runtime output line exceeded its safe limit" }
                handle(parser.parseLine(line))
            }
        }
        pendingOutput.flush()?.let {
            check(it != CodexLineAssembler.OVERSIZED) { "Runtime output line exceeded its safe limit" }
            handle(parser.parseLine(it))
        }
        nativeProcess.checkCapture()

        closeInput()
        return DshSdkRunResult(completed = completed, failure = failure)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // Headless one-shot runs expose no approval channel; nothing is ever requested.
    }

    override suspend fun stopSession(sessionId: String, force: Boolean) = withContext(Dispatchers.IO) {
        userStopRequested = true
        val resolvedTaskId: String? = runCatching {
            com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).getTaskIdForSession(sessionId)
        }.getOrNull() ?: activeTaskId
        if (resolvedTaskId != null) {
            stopState.requestStop(resolvedTaskId)
        }
        val proc = activeProcess
        if (proc != null) {
            if (force) {
                (proc as? NativeSpawnProcess)?.destroyForcibly() ?: proc.destroyForcibly()
            } else {
                (proc as? NativeSpawnProcess)?.interrupt() ?: proc.destroy()
                delay(500)
                if (proc.isAlive) proc.destroyForcibly()
            }
        }
        emitFailureOnce(sessionId, "Stopped by user")
    }

    override suspend fun stopActiveSession(force: Boolean) {
        userStopRequested = true
        val currentSessionId = activeSessionId
        if (currentSessionId != null) {
            stopSession(currentSessionId, force)
        } else {
            val proc = activeProcess
            if (proc != null) {
                if (force) {
                    (proc as? NativeSpawnProcess)?.destroyForcibly() ?: proc.destroyForcibly()
                } else {
                    (proc as? NativeSpawnProcess)?.interrupt() ?: proc.destroy()
                    delay(500)
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
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

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        blockId: Long,
        startsNewBlock: Boolean,
        isFinal: Boolean,
        force: Boolean = false,
    ) {
        val summary = text.replace(Regex("\\s+"), " ").trim().take(2_000)
        if (summary.isBlank()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (force || now - lastThinkingUpdateAt >= 400) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = blockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "DeepSeek Harness finished the task.",
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

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is DshSessionException -> message
            message.contains("authentication", true) ||
                message.contains("invalid api key", true) ||
                message.contains("autherror", true) ||
                message.contains("expired", true) ||
                message.contains("quota", true) ||
                message.contains("rate limit", true) ||
                listOf("401", "403", "429").any { code ->
                    message.contains(code) && (message.contains("auth", true) || message.contains("HTTP", true))
                } ->
                "The provider rejected the saved API key."
            message.contains("missing_credential", true) ->
                "No API key reached DeepSeek Harness. Re-save the provider key in Settings."
            message.contains("software caused connection abort", true) ||
                message.contains("connection abort", true) ||
                message.contains("network connection interrupted", true) ||
                message.contains("broken pipe", true) ||
                message.contains("network unreachable", true) ||
                message.contains("connection reset", true) ->
                "Network connection interrupted. Please check your internet connection."
            message.contains("not installed", true) -> message.take(300)
            message.isBlank() -> "DeepSeek Harness could not start."
            else -> message.take(500)
        }
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        memory: ContextMemory = ContextMemory(""),
    ): String = com.jarves.mh.data.PromptContextSupport.buildPrompt(
        currentPrompt = currentPrompt,
        history = history.dropLast(1), // the current prompt was just appended to history
        guestWorkspacePath = guestWorkspacePath,
        projectKind = projectKind,
        memory = memory,
        androidStackInstalled = installer.isStackInstalled(com.jarves.mh.model.DevStack.ANDROID),
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
        // Supervised tasks retain the service until verification/recovery reaches a final outcome.
        if (activeTaskId != null) return
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
        if (activeTaskId != null) return
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        runCatching {
            RuntimeExecutionService.cancel(context)
        }
    }

    private class DshSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val DSH_HOME_GUEST_PATH = "/root/.dsh"
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        const val NARB_DISABLE_NATIVE_CACHE_ENV = "NARB_DISABLE_NATIVE_CACHE"
        const val NARB_DISABLE_NATIVE_CACHE_VALUE = "1"
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L
        private const val SDK_INITIALIZE_ID = 1
        private const val SDK_PROMPT_ID = 2
        private const val SDK_SHUTDOWN_ID = 3
        private const val SDK_SHUTDOWN_TIMEOUT_MS = 3_000L

        internal fun buildInitializeParams(workspace: String, provider: String, model: String, effort: String): JSONObject {
            val validated = com.jarves.mh.model.normalizeEffortChoice(com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS, effort, emptyList())
            require(validated != null) { "Unsupported DeepSeek Harness effort" }
            return JSONObject().put("cwd", workspace).put("provider", provider).put("model", model).apply {
                if (validated != "default") put("reasoningEffort", validated)
            }
        }

        internal fun buildDshEnvironment(route: DshRoute, secret: String): Map<String, String> {
            val env = linkedMapOf(
                "DSH_HOME" to DSH_HOME_GUEST_PATH,
                // PocketDev already confines the whole Linux guest with PRoot. Let dsh
                // use every tool inside that boundary without an unavailable approval UI.
                "DSH_PERMISSION_MODE" to "danger-full-access",
                NARB_DISABLE_NATIVE_CACHE_ENV to NARB_DISABLE_NATIVE_CACHE_VALUE,
                route.keyEnv to secret,
            )
            if (route.keyEnv != FALLBACK_KEY_ENV) env.remove(FALLBACK_KEY_ENV)
            return env
        }

        internal fun yamlQuote(value: String): String = "'${value.replace("'", "''")}'"

        internal fun buildDshCordisPatch(route: DshRoute, provider: ProviderProfile): String {
            val targetModel = provider.model.ifBlank { route.defaultModel }
            return buildString {
                appendLine("- id: agent-default-model")
                appendLine("  config:")
                appendLine("    provider: ${route.name}")
                appendLine("    model: ${yamlQuote(targetModel)}")
                if (route.custom != null) {
                    appendLine()
                    appendLine("- id: llm-pi-ai")
                    appendLine("  config:")
                    appendLine("    providers:")
                    appendLine("      ${route.name}:")
                    appendLine("        apiKeyEnv: ${route.keyEnv}")
                    appendLine("        api: ${route.custom.api}")
                    appendLine("        baseURL: ${yamlQuote(route.custom.baseUrl)}")
                    appendLine("        models:")
                    if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER) {
                        val allAntigravity = (listOf(targetModel) + AntigravityProtocolAdapter.SUPPORTED_MODELS).distinct()
                        for (m in allAntigravity) {
                            appendLine("          - id: ${yamlQuote(m)}")
                        }
                    } else {
                        appendLine("          - id: ${yamlQuote(targetModel)}")
                    }
                }
            }
        }

        internal fun buildDshLegacySettings(route: DshRoute, provider: ProviderProfile): String {
            val targetModel = provider.model.ifBlank { route.defaultModel }
            return buildString {
                appendLine("agent-default-model:")
                appendLine("  provider: ${route.name}")
                appendLine("  model: ${yamlQuote(targetModel)}")
                if (route.custom != null) {
                    appendLine("llm-pi-ai:")
                    appendLine("  providers:")
                    appendLine("    ${route.name}:")
                    appendLine("      apiKeyEnv: ${route.keyEnv}")
                    appendLine("      api: ${route.custom.api}")
                    appendLine("      baseURL: ${yamlQuote(route.custom.baseUrl)}")
                    appendLine("      models:")
                    if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER) {
                        val allAntigravity = (listOf(targetModel) + AntigravityProtocolAdapter.SUPPORTED_MODELS).distinct()
                        for (m in allAntigravity) {
                            appendLine("        - id: ${yamlQuote(m)}")
                        }
                    } else {
                        appendLine("        - id: ${yamlQuote(targetModel)}")
                    }
                }
            }
        }

        internal fun writeDshSettings(rootfs: File, route: DshRoute, provider: ProviderProfile) {
            val home = File(rootfs, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
            val cordisPatch = buildDshCordisPatch(route, provider)
            File(home, "cordis.patch.yml").writeText(cordisPatch)
            val sdkProfileDir = File(home, "profiles/sdk")
            if (sdkProfileDir.isDirectory) {
                File(sdkProfileDir, "cordis.patch.yml").writeText(cordisPatch)
            }
            File(home, "settings.yaml").writeText(buildDshLegacySettings(route, provider))
        }
    }
}

private data class DshSdkRunResult(val completed: Boolean, val failure: String)

/** dsh provider route resolved from our saved provider profile. */
internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(val api: String, val baseUrl: String)

internal object DshRouteMapper {
    fun forProfile(profile: ProviderProfile, localGatewayUrl: String? = null): DshRoute {
        val model = profile.model.ifBlank { profile.kind.defaultModel }
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute(
                name = "deepseek-official",
                keyEnv = "DEEPSEEK_API_KEY",
                defaultModel = model,
            )
            ProviderKind.ANTIGRAVITY_SERVER -> DshRoute(
                name = "antigravity-server",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", localGatewayUrl ?: profile.resolvedBaseUrl),
            )
            ProviderKind.ANTHROPIC -> DshRoute(
                name = "mh-anthropic",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                name = "mh-openrouter",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("anthropic-messages", profile.resolvedBaseUrl),
            )
            ProviderKind.KIMI -> DshRoute(
                name = "mh-kimi",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute(profile.dshApi.ifBlank { "anthropic-messages" }, profile.resolvedBaseUrl),
            )
            ProviderKind.OPENCODE_ZEN -> DshRoute(
                name = "opencode-zen",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-responses", profile.resolvedBaseUrl),
            )
            ProviderKind.NVIDIA_NIM -> DshRoute(
                name = "nvidia-nim",
                keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                defaultModel = model,
                custom = DshCustomRoute("openai-completions", profile.resolvedBaseUrl),
            )
            ProviderKind.CUSTOM -> {
                val endpoint = com.jarves.mh.provider.ProviderEndpointNormalizer.normalize(profile.resolvedBaseUrl, profile.dshApi)
                DshRoute(
                    name = if (profile.profileId.isBlank()) "mh-custom" else "mh-custom-" + profile.profileId.filter { it.isLetterOrDigit() }.take(12),
                    keyEnv = DshRuntimeBridge.FALLBACK_KEY_ENV,
                    defaultModel = model,
                    custom = DshCustomRoute(endpoint.api, endpoint.baseUrl),
                )
            }
            ProviderKind.CLAUDE -> throw IllegalArgumentException("Claude subscription login is not supported by DeepSeek Harness")
            ProviderKind.CHATGPT -> throw IllegalArgumentException("ChatGPT sign-in is not supported by DeepSeek Harness")
        }
    }
}

/** One classified line of merged headless output (stdout+stderr share a capture file). */
internal sealed interface DshLine {
    data object ReasoningHeading : DshLine
    data class Reasoning(val text: String) : DshLine
    data class Diagnostic(val text: String) : DshLine
    data class Answer(val text: String) : DshLine
}

internal object DshHeadlessParser {
    fun parseLine(rawLine: String): DshLine {
        val line = rawLine.trim()
        if (line.startsWith("dsh:")) {
            val body = line.removePrefix("dsh:").trim()
            if (body.startsWith("reasoning:")) {
                val rest = body.removePrefix("reasoning:").trim()
                return if (rest.isBlank()) DshLine.ReasoningHeading else DshLine.Reasoning(rest)
            }
            return DshLine.Diagnostic(body.ifBlank { line })
        }
        return DshLine.Answer(line)
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String, val previewUrl: String? = null) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class UsageUpdated(val metrics: com.jarves.mh.model.SessionTokenMetrics, val text: String = "") : DshSdkProtocolEvent
    data class ChildUpdated(val child: com.jarves.mh.model.SubagentInfo) : DshSdkProtocolEvent
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

/** Stateful parser for the pinned dsh SDK's newline-delimited JSON-RPC stream. */
internal class DshSdkProtocolParser(private val expectedSessionId: String) {
    private val reasoningByBlock = mutableMapOf<Long, StringBuilder>()
    private val textByBlock = mutableMapOf<Long, StringBuilder>()
    private val streamedTextSinceMessage = StringBuilder()
    private val toolNames = mutableMapOf<String, String>()
    private val usageByStep = mutableMapOf<Long, com.jarves.mh.model.SessionTokenMetrics>()
    private val children = mutableMapOf<String, com.jarves.mh.model.SubagentInfo>()

    fun parseLine(line: String): DshSdkProtocolEvent {
        val frame = runCatching { JSONObject(line) }.getOrNull()
            ?: return if (line.startsWith("dsh:", ignoreCase = true)) {
                DshSdkProtocolEvent.Failed(line.removePrefix("dsh:").trim())
            } else {
                DshSdkProtocolEvent.Ignored
            }

        if (frame.has("id")) {
            val id = frame.optInt("id", -1)
            frame.optJSONObject("error")?.let { error ->
                return DshSdkProtocolEvent.Failed(
                    error.optString("message").ifBlank { "DeepSeek Harness SDK request $id failed" },
                )
            }
            return when (id) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val params = frame.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (frame.optString("method")) {
            "session.status" -> {
                if (params.optString("sessionId") != expectedSessionId) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Status(params.optString("status") == "running")
            }
            "session.event" -> parseSessionEvent(params)
            "subagent.started", "subagent.finished" -> parseChild(frame.optString("method"), params)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseSessionEvent(params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("sessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val event = params.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val data = event.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored
        return when (event.optString("type")) {
            "assistant/chunk" -> parseAssistantChunk(data)
            "assistant/message" -> {
                val content = data.optJSONObject("message")?.optJSONArray("content")
                val text = if (streamedTextSinceMessage.isNotEmpty()) {
                    // `assistant/message` repeats the completed content after the SDK has
                    // already delivered its text deltas. The UI has appended those deltas.
                    streamedTextSinceMessage.clear()
                    ""
                } else contentText(content)
                val metrics = data.optJSONObject("usage")?.let { parseUsage(data, it) }
                when {
                    metrics != null -> DshSdkProtocolEvent.UsageUpdated(metrics, text)
                    text.isNotBlank() -> DshSdkProtocolEvent.AssistantText(text)
                    else -> DshSdkProtocolEvent.Ignored
                }
            }
            "tool/call" -> {
                val callId = data.optString("callId")
                val rawName = data.optString("name").ifBlank { "Tool" }
                val arguments = data.optString("arguments")
                val displayName = displayToolName(rawName, arguments)
                toolNames[callId] = displayName
                DshSdkProtocolEvent.ToolStarted(callId, displayName, toolDetail(arguments))
            }
            "tool/result" -> {
                val message = data.optJSONObject("message")
                val resultBlock = message?.optJSONArray("content")?.optJSONObject(0)
                val callId = resultBlock?.optString("toolCallId").orEmpty()
                val name = toolNames.remove(callId) ?: "Tool"
                val error = data.optJSONObject("error")
                val text = contentText(resultBlock?.optJSONArray("content"))
                val summary = error?.optString("message").orEmpty()
                    .ifBlank { text }
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(180)
                    .ifBlank { "$name completed" }
                DshSdkProtocolEvent.ToolCompleted(callId, name, summary, LocalPreviewDiscovery.candidate(text))
            }
            "turn/end" -> {
                val reason = data.optJSONObject("reason")
                when (reason?.optString("kind")) {
                    "error" -> DshSdkProtocolEvent.Failed(
                        reason.optJSONObject("error")?.optString("message").orEmpty()
                            .ifBlank { "DeepSeek Harness turn failed" },
                    )
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek Harness was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseAssistantChunk(data: JSONObject): DshSdkProtocolEvent {
        val chunk = data.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val index = chunk.optInt("index", 0)
        val blockId = data.optInt("turn", 0) * 1_000_000L + data.optInt("step", 0) * 1_000L + index
        return when (chunk.optString("type")) {
            "usage" -> chunk.optJSONObject("usage")?.let { parseUsage(data, it) }
                ?.let { DshSdkProtocolEvent.UsageUpdated(it) } ?: DshSdkProtocolEvent.Ignored
            "text-delta" -> {
                val delta = chunk.optString("text")
                if (delta.isEmpty()) return DshSdkProtocolEvent.Ignored
                textByBlock.getOrPut(blockId) { StringBuilder() }.append(delta)
                streamedTextSinceMessage.append(delta)
                DshSdkProtocolEvent.AssistantText(delta)
            }
            "reasoning-delta" -> {
                val buffer = reasoningByBlock.getOrPut(blockId) { StringBuilder() }
                val starts = buffer.isEmpty()
                buffer.append(chunk.optString("text"))
                DshSdkProtocolEvent.Reasoning(blockId, buffer.toString(), starts, isFinal = false)
            }
            "block-end" -> {
                val block = chunk.optJSONObject("block")
                if (block?.optString("type") == "text") {
                    val streamed = textByBlock.remove(blockId)?.toString().orEmpty()
                    val complete = block.optString("text")
                    val missingSuffix = complete.takeIf { it.startsWith(streamed) }?.removePrefix(streamed).orEmpty()
                    if (missingSuffix.isBlank()) return DshSdkProtocolEvent.Ignored
                    streamedTextSinceMessage.append(missingSuffix)
                    return DshSdkProtocolEvent.AssistantText(missingSuffix)
                }
                if (block?.optString("type") != "reasoning") return DshSdkProtocolEvent.Ignored
                val text = block.optString("text").ifBlank { reasoningByBlock[blockId]?.toString().orEmpty() }
                val starts = blockId !in reasoningByBlock
                reasoningByBlock.remove(blockId)
                if (text.isBlank()) DshSdkProtocolEvent.Ignored
                else DshSdkProtocolEvent.Reasoning(blockId, text, starts, isFinal = true)
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun parseUsage(data: JSONObject, usage: JSONObject): com.jarves.mh.model.SessionTokenMetrics? {
        fun valid(key: String): Boolean {
            val number = usage.opt(key) as? Number ?: return false
            val value = number.toDouble()
            return value.isFinite() && value >= 0 && value == kotlin.math.floor(value)
        }
        if (!valid("inputTokens") || !valid("outputTokens") ||
            listOf("cacheReadTokens", "cacheWriteTokens").any { usage.has(it) && !valid(it) }) return null
        fun count(key: String) = usage.optLong(key, 0).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        val key = (data.optInt("turn").toLong() shl 32) or (data.optInt("step").toLong() and 0xffffffffL)
        if (key !in usageByStep && usageByStep.size >= 4096) return null
        val cached = count("cacheReadTokens")
        val prompt = (count("inputTokens").toLong() + cached + count("cacheWriteTokens")).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        // Usage chunks and the assembled message repeat one step's accounting. Replace its bucket.
        usageByStep[key] = com.jarves.mh.model.SessionTokenMetrics(prompt, count("outputTokens"), cached, reported = true)
        fun total(get: (com.jarves.mh.model.SessionTokenMetrics) -> Int) = usageByStep.values.sumOf { get(it).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return com.jarves.mh.model.SessionTokenMetrics(total { it.promptTokens }, total { it.completionTokens }, total { it.cachedTokens }, reported = true)
    }

    private fun parseChild(method: String, params: JSONObject): DshSdkProtocolEvent {
        if (params.optString("parentSessionId") != expectedSessionId) return DshSdkProtocolEvent.Ignored
        val id = params.optString("childSessionId")
        if (id.isBlank() || id == "null" || id == expectedSessionId || id.length > 128) return DshSdkProtocolEvent.Ignored
        val old = children[id]
        if (old == null && children.size >= 256) return DshSdkProtocolEvent.Ignored
        val state = when {
            method == "subagent.started" -> com.jarves.mh.model.SubagentState.RUNNING
            params.optString("status") == "ok" -> com.jarves.mh.model.SubagentState.DONE
            params.optString("status") == "error" -> com.jarves.mh.model.SubagentState.ERRORED
            else -> return DshSdkProtocolEvent.Ignored
        }
        val reason = params.optString("stopReason").take(180)
        val child = (old ?: com.jarves.mh.model.SubagentInfo(id, "Subagent", "DeepSeek Harness", state)).copy(
            state = state,
            currentActivity = reason,
            error = reason.takeIf { state == com.jarves.mh.model.SubagentState.ERRORED },
            finishedAtMillis = if (state.isTerminal) old?.finishedAtMillis ?: System.currentTimeMillis() else null,
        )
        children[id] = child
        return DshSdkProtocolEvent.ChildUpdated(child)
    }

    private fun displayToolName(rawName: String, arguments: String): String {
        val operation = runCatching { JSONObject(arguments).optString("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (operation.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun toolDetail(arguments: String): String {
        val parsed = runCatching { JSONObject(arguments) }.getOrNull()
        val detail = parsed?.let { json ->
            listOf("path", "file_path", "command", "pattern", "query")
                .firstNotNullOfOrNull { key -> json.optString(key).takeIf(String::isNotBlank) }
        }.orEmpty()
        return detail.ifBlank { arguments }.replace(Regex("\\s+"), " ").trim().take(240)
            .ifBlank { "Working in the project" }
    }

    private fun contentText(content: JSONArray?): String {
        if (content == null) return ""
        return buildList {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    "tool-result" -> contentText(block.optJSONArray("content")).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.joinToString("\n")
    }
}
