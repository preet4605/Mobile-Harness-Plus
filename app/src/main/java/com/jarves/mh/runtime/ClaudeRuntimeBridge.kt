package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.data.renderMemoryBlock
import com.jarves.mh.model.ArtifactInfo
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.ToolRequest
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        if (json != null) {
            val type = json.optString("type")
            return when (type) {
                "system" -> {
                    if (json.optString("subtype") == "api_retry") {
                        val status = json.optInt("error_status", 0)
                        if (status in listOf(401, 403)) {
                            "The provider rejected the saved API key."
                        } else {
                            // Transient retries (such as 429 rate limits or server 5xx) are retried automatically by Claude Code
                            null
                        }
                    } else {
                        null
                    }
                }
                "result" -> {
                    if (json.optBoolean("is_error")) {
                        val result = json.optString("result")
                        val terminalReason = json.optString("terminal_reason")
                        classifyErrorMessage("$result $terminalReason")
                            ?: result.takeIf(String::isNotBlank)
                            ?: "Claude Code reported an error"
                    } else {
                        null
                    }
                }
                "assistant" -> {
                    if (json.optBoolean("is_api_error_message") || json.has("error")) {
                        val error = json.optString("error")
                        val text = extractAssistantText(json)
                        classifyErrorMessage("$error $text")
                            ?: text.takeIf(String::isNotBlank)
                            ?: "The provider rejected the saved API key."
                    } else {
                        null
                    }
                }
                // Tool results ("user"), thinking/text deltas, stream events, rate limit telemetry are normal execution
                "user", "content_block_start", "content_block_delta", "content_block_stop", "stream_event", "rate_limit_event", "message_delta" -> null
                else -> null
            }
        }

        // Plain text output (e.g. CLI stderr before JSON streaming starts)
        return classifyErrorMessage(trimmed)
    }

    private fun extractAssistantText(json: JSONObject): String {
        val message = json.optJSONObject("message") ?: return ""
        val content = message.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") {
                sb.append(block.optString("text")).append(' ')
            }
        }
        return sb.toString().trim()
    }

    private fun classifyErrorMessage(raw: String): String? {
        val lower = raw.lowercase()
        return when {
            "not logged in" in lower || "run /login" in lower || "run login" in lower ->
                "Claude subscription is not signed in. Use Sign in with Claude."
            "user not found" in lower ->
                "User not found. Check the API key and provider account."
            "authentication_failed" in lower ||
                "authentication failed" in lower ||
                "invalid api key" in lower ||
                "invalid_api_key" in lower ||
                ("http 401" in lower && ("error" in lower || "unauthorized" in lower || "api" in lower || "failed" in lower)) ||
                ("http 403" in lower && ("error" in lower || "forbidden" in lower || "api" in lower || "failed" in lower)) ->
                "The provider rejected the saved API key."
            "not available on your tier" in lower ||
                "isn't available" in lower ||
                "model not found" in lower ||
                "does not have access" in lower ->
                raw.take(300)
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    val accountManager: AntigravityAccountManager? = null,
    private val secretFor: (ProviderProfile) -> String?,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
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
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L

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
            throw ProviderSessionException("Stopped by user")
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
        toolNames.clear()
        seenToolCalls.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
        streamedThinking.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Claude Code…")
        val effectiveAccountMgr = accountManager ?: AntigravityAccountManager(context, com.jarves.mh.data.AppPreferences(context))
        if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER && effectiveAccountMgr.selectAccountForTurn() == null) {
            val message = "Google account not signed in. Sign in under Antigravity settings to use Antigravity models."
            activeSessionId = null
            emitFailureOnce(sessionId, message)
            throw ProviderSessionException(message)
        }
        val isNativeSubscription = provider.kind == ProviderKind.CLAUDE &&
            provider.claudeAuthMode == com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION
        val secret = if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER) {
            secretFor(provider)?.ifBlank { "antigravity-local-token" } ?: "antigravity-local-token"
        } else if (isNativeSubscription) {
            null
        } else {
            secretFor(provider).orEmpty()
        }
        if (!isNativeSubscription && secret.isNullOrBlank()) {
            val message = if (provider.kind == ProviderKind.CLAUDE) {
                "No Claude setup token is saved. Add one from Agent → AI provider."
            } else {
                "No API key is saved for ${provider.kind.title}."
            }
            activeSessionId = null
            emitFailureOnce(sessionId, message)
            throw ProviderSessionException(message)
        }

        var formatGateway: LocalFormatGateway? = null
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
            // Setup and release checks happen once in the app-start loading flow.
            // Sending a prompt must never perform network update checks or put setup
            // messages into the conversation.
            val installed = installer.installedRuntime()
            installer.ensureSettingsAndHooks()
            val workspace = ensureWorkspace(projectId)
            createCheckpoint(projectId, workspace)
            val before = snapshot(workspace)
            var gatewayAuthToken: String? = null
            if (provider.kind == ProviderKind.ANTIGRAVITY_SERVER) {
                antigravityGateway = AntigravityGatewayServer(effectiveAccountMgr, targetModel = { provider.model }).start()
                gatewayAuthToken = antigravityGateway.gatewaySecret
            } else if (com.jarves.mh.model.providerProtocolForAgent(provider, com.jarves.mh.model.AgentKind.CLAUDE_CODE) in setOf(
                    com.jarves.mh.model.ProviderProtocol.OPENAI_CHAT,
                    com.jarves.mh.model.ProviderProtocol.OPENAI_RESPONSES,
                )) {
                formatGateway = LocalFormatGateway(provider, secret.orEmpty()).start()
                gatewayAuthToken = formatGateway.gatewaySecret
            }
            val localGatewayUrl = antigravityGateway?.url ?: formatGateway?.url
            val launch = RuntimeLaunchConfigBuilder.build(provider, authToken = gatewayAuthToken ?: secret, localGatewayUrl = localGatewayUrl)
            Log.d("ClaudeBridge", "Provider: ${provider.kind}, Model: ${provider.model}, BaseUrl: ${provider.baseUrl}")
            Log.d("ClaudeBridge", "Launch environment keys: ${launch.environment.keys}")

            // Build a context-aware prompt that includes conversation history
            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(injectedPrompt, conversationHistory, guestWorkspacePath, projectKind, memory)

            val effort = if (provider.kind == ProviderKind.CLAUDE) {
                com.jarves.mh.model.ClaudeThinkingLevel.fromStored(provider.claudeThinkingLevel).effortArg
            } else null
            val command = buildClaudeCommand(
                executable = launch.executable,
                model = launch.environment["ANTHROPIC_MODEL"] ?: provider.model,
                effort = effort,
            )
            Log.d("ClaudeBridge", "Launching command: $command")
            val process = installer.process(
                installed.proot,
                installed.rootfs,
                workspace,
                launch.environment,
                command,
                guestWorkspacePath = guestWorkspacePath,
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
                Log.w("ClaudeBridge", "Process binding failed for task $taskId / session $sessionId (PID: $nativePid)")
            }
            if (userStopRequested) process.destroy()
            coroutineScope {
                val permissionWatcher = launch { watchPermissionRequests(sessionId) }
                val promptWriter = launch(Dispatchers.IO) {
                    deliverPromptToStdin(process, contextPrompt)
                }
                try {
                    var lastDiagnostic = ""
                    val pendingOutput = StringBuilder()
                    val nativeProcess = process as? NativeSpawnProcess
                        ?: error("Unsupported Android runtime process")
                    var outputOffset = 0L
                    var lastOutputTime = System.currentTimeMillis()
                    while (process.isAlive || nativeProcess.outputFile.length() > outputOffset) {
                        val available = nativeProcess.outputFile.length() - outputOffset
                        if (available <= 0) {
                            if (finishedSessions.contains(sessionId) && (System.currentTimeMillis() - lastOutputTime >= 1500L)) {
                                Log.i("ClaudeBridge", "Session completed and output drained; stopping lingering process")
                                if (process.isAlive) process.destroy()
                                break
                            }
                            delay(50)
                            continue
                        }
                        lastOutputTime = System.currentTimeMillis()
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(nativeProcess.outputFile, "r").use { file ->
                            file.seek(outputOffset)
                            file.read(bytes)
                        }
                        if (count > 0) {
                            outputOffset += count
                            pendingOutput.append(bytes.decodeToString(0, count))
                            var newline = pendingOutput.indexOf("\n")
                            while (newline >= 0) {
                                val line = pendingOutput.substring(0, newline).trimEnd('\r')
                                pendingOutput.delete(0, newline + 1)
                                if (line.isNotBlank()) {
                                    Log.d("ClaudeBridge", "OUTPUT: $line")
                                    ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                                        process.destroyForcibly()
                                        throw ProviderSessionException(reason)
                                    }
                                    if (line.contains("\"rate_limit_event\"")) {
                                        runCatching { com.jarves.mh.data.AppPreferences(context).claudeRateLimitEvent = line.take(4096) }
                                    }
                                    if (!consumeClaudeEvent(sessionId, line)) {
                                        lastDiagnostic = line.takeLast(500)
                                        runCatching {
                                            val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context)
                                            supervisor.getOutputBuffer(sessionId).appendLine(line)
                                            supervisor.healthMonitor.onOutputReceived()
                                        }
                                        terminalStatus(line)?.let { (title, detail) ->
                                            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, title, detail))
                                        }
                                    }
                                }
                                newline = pendingOutput.indexOf("\n")
                            }
                        }
                    }
                    pendingOutput.toString().trim().takeIf(String::isNotBlank)?.let { line ->
                        Log.d("ClaudeBridge", "TRAILING OUTPUT: $line")
                        if (!consumeClaudeEvent(sessionId, line)) lastDiagnostic = line.takeLast(500)
                    }
                    val exit = process.waitFor()
                    Log.d("ClaudeBridge", "Process exited with code $exit")
                    val changed = changedFiles(workspace, before)
                    if (changed.isNotEmpty()) {
                        Log.d("ClaudeBridge", "Changed files: $changed")
                        saveChangedPaths(projectId, changed)
                        val details = loadPendingChanges(projectId)
                        eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                    } else if (!File(checkpointDir(projectId), "changes.json").isFile) {
                        acceptLastChanges(projectId)
                    }
                    if (exit == 0 || finishedSessions.contains(sessionId)) {
                        emitCompletedOnce(sessionId)
                        finishForegroundRuntime(
                            completed = true,
                            projectName = projectSlug,
                            detail = "Claude Code finished the task in $projectSlug.",
                        )
                    } else {
                        if (userStopRequested) throw ProviderSessionException("Stopped by user")
                        error(lastDiagnostic.ifBlank { "Claude Code stopped before reporting completion (exit code $exit)" })
                    }
                } finally {
                    promptWriter.cancel()
                    permissionWatcher.cancelAndJoin()
                    pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                        runCatching { permission.response.writeText("deny") }
                        pending.remove(permission.request.approvalId)
                    }
                }
            }
        }.onFailure { error ->
            Log.e("ClaudeBridge", "Session failed", error)
            activeProcess?.let { if (it.isAlive) it.destroyForcibly() }
            val message = friendlyError(error, isNativeSubscription = isNativeSubscription)
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
                    formatGateway?.close()
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
            formatGateway?.close()
            antigravityGateway?.close()
            activeProcess = null
            activeSessionId = null
            activeTaskId = null
            RuntimeTaskController.stopAction = null
        }
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val permission = pending.remove(request.approvalId) ?: return@withContext
        permission.response.writeText(if (approved) "allow" else "deny")
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
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

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.undoLastChanges(projectId)
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpointDir(projectId).deleteRecursively()
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = ensureWorkspace(projectId)
        val paths = readChangedPaths(projectId).filterNot(::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        checkpoints.undoFileChange(projectId, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (isInternalRuntimePath(path) || path !in readChangedPaths(projectId)) return@withContext false
        val workspace = ensureWorkspace(projectId)
        val backup = File(checkpointDir(projectId), "project")
        val current = safeWorkspaceFile(workspace, path)
        val baseline = safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else if (baseline.isFile) {
            baseline.delete()
        }
        removeChangedPath(projectId, path)
        true
    }

    private suspend fun watchPermissionRequests(sessionId: String) {
        val bridge = File(context.filesDir, "runtime-bridge")
        while (kotlin.coroutines.coroutineContext.isActive) {
            bridge.listFiles { file -> file.name.endsWith(".request") }.orEmpty().forEach { file ->
                val approvalId = file.name.removeSuffix(".request")
                runCatching {
                    val json = JSONObject(file.readText())
                    val toolName = json.optString("tool_name", "Tool")
                    val input = json.optJSONObject("tool_input") ?: JSONObject()
                    val command = input.optString("command").ifBlank { null }
                    val paths = listOf("file_path", "path", "notebook_path")
                        .mapNotNull { key -> input.optString(key).takeIf(String::isNotBlank) }
                    val explanation = input.optString("description")
                        .ifBlank { command.orEmpty() }
                        .ifBlank { "$toolName running in project" }

                    Log.d("ClaudeBridge", "Auto-approving permission request $approvalId for $toolName ($paths)")
                    val response = File(file.parentFile, "$approvalId.response")
                    response.writeText("allow")

                    eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, explanation))
                }.onFailure {
                    File(file.parentFile, "$approvalId.response").writeText("allow")
                }
            }
            delay(250)
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeClaudeJsonEvent(sessionId, json)
        return true
    }

    private suspend fun consumeClaudeJsonEvent(sessionId: String, json: JSONObject) {
        when (json.optString("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeClaudeJsonEvent(sessionId, it) }
            "system" -> when (json.optString("subtype")) {
                "init" -> Unit
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.optString("decision_reason").ifBlank { json.optString("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.optString("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "thinking_delta" -> delta.optString("thinking").takeIf(String::isNotEmpty)?.let {
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.optString("text").takeIf(String::isNotEmpty)?.let {
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
            }
            "message_delta" -> {
                val usage = json.optJSONObject("usage")
                if (usage != null) {
                    val inputTokens = usage.optInt("input_tokens", 0)
                    val outputTokens = usage.optInt("output_tokens", 0)
                    val cachedTokens = usage.optInt("cache_read_input_tokens", 0)
                    if (inputTokens > 0 || outputTokens > 0) {
                        eventBus.emit(
                            RuntimeEvent.TokenUsageUpdated(
                                sessionId,
                                SessionTokenMetrics(
                                    promptTokens = inputTokens,
                                    completionTokens = outputTokens,
                                    cachedTokens = cachedTokens,
                                ),
                            ),
                        )
                    }
                }
            }
            "content_block_stop" -> {
                if (streamedThinking.isNotBlank()) {
                    emitReasoningSummary(sessionId, streamedThinking.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val usage = message.optJSONObject("usage")
                if (usage != null) {
                    val inputTokens = usage.optInt("input_tokens", 0)
                    val outputTokens = usage.optInt("output_tokens", 0)
                    val cachedTokens = usage.optInt("cache_read_input_tokens", 0)
                    if (inputTokens > 0 || outputTokens > 0) {
                        eventBus.emit(
                            RuntimeEvent.TokenUsageUpdated(
                                sessionId,
                                SessionTokenMetrics(
                                    promptTokens = inputTokens,
                                    completionTokens = outputTokens,
                                    cachedTokens = cachedTokens,
                                ),
                            ),
                        )
                    }
                }
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.optString("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.optString("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.optString("thinking").takeIf(String::isNotBlank)?.let {
                            if (currentThinkingBlockId == 0L || streamedThinking.toString() != it) {
                                currentThinkingBlockId += 1
                                streamedThinking.clear()
                                streamedThinking.append(it)
                                emitReasoningSummary(
                                    sessionId,
                                    it,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> emitToolStarted(sessionId, block)
                    }
                }
                streamedText.clear()
                // Some Anthropic-compatible providers omit Claude Code's final
                // `result` envelope. An assistant end_turn is still authoritative;
                // tool_use means the agent must remain active for another turn.
                if (message.optString("stop_reason") == "end_turn") {
                    emitCompletedOnce(sessionId)
                    terminateActiveProcessGracefully()
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.optString("type") == "tool_result") {
                        val toolId = block.optString("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val result = block.optString("content")
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result)))
                        if (toolName == "Bash") {
                            eventBus.emit(
                                RuntimeEvent.TaskUpdated(
                                    sessionId,
                                    BackgroundTaskInfo(
                                        taskId = toolId.ifBlank { "task" },
                                        commandLine = "",
                                        cwd = "",
                                        status = if (block.optBoolean("is_error")) BackgroundTaskStatus.FAILED else BackgroundTaskStatus.COMPLETED,
                                        liveOutputTail = result.takeLast(200),
                                    ),
                                ),
                            )
                        } else if (toolName in listOf("Agent", "Task", "Subagent")) {
                            eventBus.emit(
                                RuntimeEvent.SubagentUpdated(
                                    sessionId,
                                    SubagentInfo(
                                        conversationId = toolId.ifBlank { "subagent" },
                                        role = toolName,
                                        typeName = "subagent",
                                        state = if (block.optBoolean("is_error")) SubagentState.ERRORED else SubagentState.DONE,
                                        currentActivity = result.take(120),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            "result" -> {
                if (json.optBoolean("is_error")) {
                    val message = json.optString("result").ifBlank { "Claude Code reported an error" }
                    throw IllegalStateException(message)
                }
                val cost = json.optDouble("total_cost_usd", json.optDouble("cost_usd", 0.0))
                val usage = json.optJSONObject("usage")
                if (usage != null || cost > 0.0) {
                    val inputTokens = usage?.optInt("input_tokens", 0) ?: 0
                    val outputTokens = usage?.optInt("output_tokens", 0) ?: 0
                    val cachedTokens = usage?.optInt("cache_read_input_tokens", 0) ?: 0
                    eventBus.emit(
                        RuntimeEvent.TokenUsageUpdated(
                            sessionId,
                            SessionTokenMetrics(
                                promptTokens = inputTokens,
                                completionTokens = outputTokens,
                                cachedTokens = cachedTokens,
                                contextWindowLimit = 200_000,
                                estimatedCostUsd = cost,
                            ),
                        ),
                    )
                }
                // The structured result is Claude Code's authoritative terminal event.
                // Update the UI immediately instead of waiting for a PRoot/Node wrapper
                // that may remain alive after the answer has already completed.
                emitCompletedOnce(sessionId)
                terminateActiveProcessGracefully()
            }
        }
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val summary = sanitizeForDisplay(text).trim().take(2_000)
        if (summary.isBlank() && !startsNewBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || now - lastThinkingUpdateAt >= 150) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = currentThinkingBlockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitReasoningProgress(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (tokens - lastReasoningTokens >= 25 || now - lastReasoningUpdateAt >= 500) {
            lastReasoningTokens = tokens
            lastReasoningUpdateAt = now
            eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitToolStarted(sessionId: String, block: JSONObject) {
        val id = block.optString("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return
        val name = block.optString("name", "Tool")
        if (id.isNotBlank()) toolNames[id] = name
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "Bash" -> input.optString("command").ifBlank { input.optString("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> input.optString("file_path").ifBlank { input.optString("notebook_path") }
            "Glob" -> input.optString("pattern")
            "Grep" -> input.optString("pattern").let { pattern ->
                input.optString("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" } ?: pattern
            }
            else -> input.optString("description").ifBlank { "Running $name" }
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, sanitizeForDisplay(detail.ifBlank { "Running $name" })))
        pushForegroundProgress("Running $name · ${detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { name }}")

        when (name) {
            "Bash" -> {
                val command = input.optString("command").ifBlank { input.optString("description") }
                if (command.isNotBlank()) {
                    eventBus.emit(
                        RuntimeEvent.TaskUpdated(
                            sessionId,
                            BackgroundTaskInfo(
                                taskId = id.ifBlank { "task-${UUID.randomUUID().toString().take(6)}" },
                                commandLine = command,
                                cwd = input.optString("cwd"),
                                status = BackgroundTaskStatus.RUNNING,
                            ),
                        ),
                    )
                }
            }
            "Write", "Edit" -> {
                val filePath = input.optString("file_path").ifBlank { input.optString("notebook_path") }
                if (filePath.endsWith(".md", ignoreCase = true)) {
                    val title = File(filePath).nameWithoutExtension.replace('_', ' ').replace('-', ' ')
                        .replaceFirstChar { it.uppercase() }
                    eventBus.emit(
                        RuntimeEvent.ArtifactDiscovered(
                            sessionId,
                            ArtifactInfo(
                                title = title,
                                filePath = filePath,
                                summary = "Markdown document: ${File(filePath).name}",
                                isUserFacing = true,
                            ),
                        ),
                    )
                }
            }
            "Agent", "Task", "Subagent" -> {
                val subPrompt = input.optString("prompt").ifBlank { input.optString("description") }
                val role = input.optString("role").ifBlank { input.optString("name", "Claude Subagent") }
                eventBus.emit(
                    RuntimeEvent.SubagentUpdated(
                        sessionId,
                        SubagentInfo(
                            conversationId = id.ifBlank { "subagent-${UUID.randomUUID().toString().take(6)}" },
                            role = role,
                            typeName = "subagent",
                            state = SubagentState.RUNNING,
                            currentActivity = subPrompt.take(120),
                        ),
                    ),
                )
            }
        }
    }

    private fun terminalStatus(line: String): Pair<String, String>? = null

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            // Post the completion notification immediately. Waiting for process
            // teardown is unsafe: the PRoot/Node wrapper can hang after the answer
            // is already done, which would freeze the notification on its last step.
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "your project",
                detail = "Claude Code finished the task.",
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

    private fun ensureWorkspace(projectId: String): File = checkpoints.ensureWorkspace(projectId)

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    private fun checkpointDir(projectId: String): File = checkpoints.checkpointDir(projectId)

    private fun createCheckpoint(projectId: String, workspace: File) {
        checkpoints.createCheckpoint(projectId, workspace)
    }

    private fun saveChangedPaths(projectId: String, paths: List<String>) {
        checkpoints.saveChangedPaths(projectId, paths)
    }

    private fun readChangedPaths(projectId: String): List<String> = checkpoints.readChangedPaths(projectId)

    private fun removeChangedPath(projectId: String, path: String) {
        checkpoints.removeChangedPath(projectId, path)
    }

    private fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> =
        checkpoints.buildChangeDetails(projectId, workspace, paths)

    private fun safeWorkspaceFile(root: File, relative: String): File =
        checkpoints.safeWorkspaceFile(root, relative)

    private fun snapshot(root: File): Map<String, String> = checkpoints.snapshot(root)

    private fun changedFiles(root: File, before: Map<String, String>): List<String> =
        checkpoints.changedFiles(root, before)

    private fun isInternalRuntimePath(path: String): Boolean = checkpoints.isInternalRuntimePath(path)

    private fun classifyRisk(tool: String, command: String?): RiskLevel {
        val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
        return when {
            listOf("rm -rf", "git push", "git reset", "sudo", "curl ").any(preview::contains) -> RiskLevel.HIGH
            tool in listOf("Write", "Edit", "NotebookEdit", "Bash") -> RiskLevel.REVIEW
            else -> RiskLevel.SAFE
        }
    }

    private fun friendlyError(error: Throwable, isNativeSubscription: Boolean = false): String {
        val message = error.message.orEmpty()
        return when {
            error is ProviderSessionException -> message
            message.contains("not logged in", true) || message.contains("run /login", true) || message.contains("run login", true) ->
                "Claude subscription is not signed in. Use Sign in with Claude."
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> {
                if (isNativeSubscription) "Claude subscription is not signed in. Use Sign in with Claude."
                else "The provider rejected the saved API key."
            }
            message.contains("software caused connection abort", true) ||
                message.contains("connection abort", true) ||
                message.contains("network connection interrupted", true) ||
                message.contains("broken pipe", true) ||
                message.contains("network unreachable", true) ||
                message.contains("connection reset", true) ->
                "Network connection interrupted. Please check your internet connection."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    /**
     * Mirrors what Claude Code is doing right now into the foreground-service
     * notification, so the notification panel shows the real task progress.
     * Throttled because each update is a service round-trip.
     */
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

    /**
     * Destroys the CLI process, escalating to a force kill if the PRoot/Node
     * wrapper ignores the graceful signal. Without this, a hung wrapper would
     * block session cleanup forever after the answer was already delivered.
     */
    private fun terminateActiveProcessGracefully() {
        val running = activeProcess ?: return
        Thread {
            runCatching {
                running.destroy()
                Thread.sleep(3_000)
                if (running.isAlive) running.destroyForcibly()
            }
        }.apply { isDaemon = true }.start()
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
        // The first completion/failure post wins; later cleanup must not duplicate it.
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

    private fun deliverPromptToStdin(process: Process, prompt: String) {
        runCatching {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(prompt)
                writer.flush()
            }
            Log.d("ClaudeBridge", "Context prompt (${prompt.length} chars) streamed to stdin and closed")
        }.onFailure { error ->
            Log.w("ClaudeBridge", "Failed writing prompt to stdin: ${error.message}")
        }
    }

    private data class PendingPermission(val request: ToolRequest, val response: File)
    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val DIFF_CONTEXT_LINES = 3
        private const val FOREGROUND_PROGRESS_MIN_INTERVAL_MS = 750L

        val SUPPORTED_EFFORT_LEVELS: Set<String> = setOf("low", "medium", "high", "xhigh", "max")

        fun buildClaudeCommand(
            executable: String,
            model: String,
            effort: String? = null,
        ): List<String> {
            val command = mutableListOf(
                executable,
                "-p",
                "--output-format",
                "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--model",
                model,
            )
            val validatedEffort = effort?.takeIf { it in SUPPORTED_EFFORT_LEVELS }
            if (validatedEffort != null) {
                command.add("--effort")
                command.add(validatedEffort)
            }
            command.add("--max-turns")
            command.add("25")
            NativeSpawnProcess.validateArgv(command)
            return command
        }

        fun sanitizeForDisplay(value: String): String {
            return value
                .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
                .replace(Regex("(?i)(authorization|api[_-]?key|bearer|token|oauth_token)(\\s*[:=]\\s*|\\s+)[^\\s,}]+"), "${'$'}1: ••••")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(600)
        }
    }
}
