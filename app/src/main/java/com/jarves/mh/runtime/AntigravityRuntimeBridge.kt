package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.data.renderMemoryBlock
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.ArtifactInfo
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.ScheduledTimerInfo
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val accountEmail: String? = null,
)

internal sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String) : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
    data class Subagent(val subagent: SubagentInfo) : AntigravityParsedEvent
    data class Task(val task: BackgroundTaskInfo) : AntigravityParsedEvent
    data class Artifact(val artifact: ArtifactInfo) : AntigravityParsedEvent
    data class Timer(val timer: ScheduledTimerInfo) : AntigravityParsedEvent
    data class TokenUsage(val metrics: SessionTokenMetrics) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? = parseEvents(line).firstOrNull()

    fun parseEvents(line: String): List<AntigravityParsedEvent> {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<AntigravityParsedEvent>()
        when (root.optString("event")) {
            "init" -> {
                root.optString("conversation_id")
                    .takeIf(String::isNotBlank)
                    ?.let { results.add(AntigravityParsedEvent.Initialized(it)) }
            }
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return emptyList()
                val delta = step.optString("text_delta")
                if (delta.isNotEmpty()) {
                    results.add(AntigravityParsedEvent.Text(delta))
                    return results
                }
                val type = step.optString("step_type")
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.optString("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.optString("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    val isDone = step.optString("state") == "DONE"
                    if (isDone) {
                        results.add(AntigravityParsedEvent.ToolCompleted(name, detail))
                    } else {
                        results.add(AntigravityParsedEvent.ToolStarted(name, detail))
                    }

                    val info = step.optJSONObject("tool_info")
                    val params = info?.optJSONObject("parameters")
                    val rawLower = rawName.lowercase()

                    when {
                        rawLower == "manage_subagents" -> {
                            val action = params?.optString("Action", "") ?: ""
                            val ids = params?.optJSONArray("ConversationIds")
                            val singleId = params?.optString("ConversationId")?.takeIf { it.isNotBlank() }
                            val idList = if (ids != null) (0 until ids.length()).map { ids.getString(it) } else listOfNotNull(singleId)
                            if (action.equals("kill", ignoreCase = true) || action.equals("kill_all", ignoreCase = true)) {
                                if (idList.isNotEmpty()) {
                                    idList.forEach { id ->
                                        results.add(
                                            AntigravityParsedEvent.Subagent(
                                                SubagentInfo(
                                                    conversationId = id,
                                                    role = "Subagent",
                                                    typeName = "subagent",
                                                    state = SubagentState.TERMINATED,
                                                ),
                                            ),
                                        )
                                    }
                                } else {
                                    results.add(
                                        AntigravityParsedEvent.Subagent(
                                            SubagentInfo(
                                                conversationId = "*",
                                                role = "*",
                                                typeName = "*",
                                                state = SubagentState.TERMINATED,
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                        rawLower == "invoke_subagent" || (rawLower.contains("subagent") && !rawLower.contains("manage")) -> {
                            val subagentsArray = params?.optJSONArray("Subagents")
                            if (subagentsArray != null && subagentsArray.length() > 0) {
                                for (i in 0 until subagentsArray.length()) {
                                    val sub = subagentsArray.optJSONObject(i) ?: continue
                                    val role = sub.optString("Role", "Subagent")
                                    val typeName = sub.optString("TypeName", "general")
                                    val prompt = sub.optString("Prompt", "")
                                    val convId = sub.optString("conversationId").ifBlank {
                                        "subagent-${role.lowercase().replace(' ', '-')}-${i + 1}"
                                    }
                                    results.add(
                                        AntigravityParsedEvent.Subagent(
                                            SubagentInfo(
                                                conversationId = convId,
                                                role = role,
                                                typeName = typeName,
                                                state = if (isDone) SubagentState.DONE else SubagentState.RUNNING,
                                                currentActivity = prompt.take(120),
                                            ),
                                        ),
                                    )
                                }
                            } else {
                                val role = params?.optString("Role", "Subagent") ?: "Subagent"
                                val typeName = params?.optString("TypeName", "general") ?: "general"
                                val prompt = params?.optString("Prompt", "") ?: ""
                                val convId = params?.optString("conversationId").orEmpty().ifBlank {
                                    params?.optString("ConversationId").orEmpty().ifBlank {
                                        "subagent-${role.lowercase().replace(' ', '-')}-1"
                                    }
                                }
                                results.add(
                                    AntigravityParsedEvent.Subagent(
                                        SubagentInfo(
                                            conversationId = convId,
                                            role = role,
                                            typeName = typeName,
                                            state = if (isDone) SubagentState.DONE else SubagentState.RUNNING,
                                            currentActivity = prompt.take(120),
                                        ),
                                    ),
                                )
                            }
                        }
                        rawLower == "manage_task" -> {
                            val action = params?.optString("Action", "status") ?: "status"
                            val taskId = params?.optString("TaskId", "task-1") ?: "task-1"
                            val status = when (action.lowercase()) {
                                "kill" -> BackgroundTaskStatus.TERMINATED
                                else -> if (isDone) BackgroundTaskStatus.COMPLETED else BackgroundTaskStatus.RUNNING
                            }
                            results.add(
                                AntigravityParsedEvent.Task(
                                    BackgroundTaskInfo(
                                        taskId = taskId,
                                        commandLine = "manage_task $action",
                                        cwd = "",
                                        status = status,
                                    ),
                                ),
                            )
                        }
                        rawLower == "schedule" && isDone -> {
                            val prompt = params?.optString("Prompt", "Scheduled reminder") ?: "Scheduled reminder"
                            val duration = params?.optInt("DurationSeconds", 0) ?: 0
                            val cron = params?.optString("CronExpression").takeIf { !it.isNullOrBlank() }
                            val cond = params?.optString("TimerCondition", "never") ?: "never"
                            results.add(
                                AntigravityParsedEvent.Timer(
                                    ScheduledTimerInfo(
                                        taskId = "timer-${UUID.randomUUID().toString().take(6)}",
                                        prompt = prompt,
                                        totalSeconds = duration,
                                        remainingSeconds = duration,
                                        condition = cond,
                                        isCron = cron != null,
                                        cronExpression = cron,
                                    ),
                                ),
                            )
                        }
                        isDone && (rawLower in listOf("write_to_file", "replace_file_content", "multi_replace_file_content")) -> {
                            val targetFile = params?.optString("TargetFile")
                                ?.ifBlank { params.optString("AbsolutePath") }.orEmpty()
                            val meta = params?.optJSONObject("ArtifactMetadata")
                            if (targetFile.endsWith(".md", ignoreCase = true) || meta != null) {
                                val title = if (targetFile.isNotBlank()) {
                                    File(targetFile).nameWithoutExtension.replace('_', ' ').replace('-', ' ')
                                        .replaceFirstChar { it.uppercase() }
                                } else "Artifact"
                                val summary = meta?.optString("Summary")
                                    ?.ifBlank { "Markdown document: ${File(targetFile).name}" }
                                    ?: "Markdown document: ${File(targetFile).name}"
                                val isUserFacing = meta?.optBoolean("UserFacing", true) ?: true
                                results.add(
                                    AntigravityParsedEvent.Artifact(
                                        ArtifactInfo(
                                            title = title,
                                            filePath = targetFile,
                                            summary = summary,
                                            isUserFacing = isUserFacing,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                results.add(
                    AntigravityParsedEvent.Result(
                        result.optString("conversation_id").takeIf(String::isNotBlank),
                        result.optString("status", "ERROR"),
                        result.optString("response").takeIf(String::isNotBlank),
                        result.optString("error").takeIf(String::isNotBlank),
                    ),
                )
                val usage = result.optJSONObject("usage") ?: root.optJSONObject("usage")
                if (usage != null) {
                    val promptTokens = usage.optInt("input_tokens", usage.optInt("prompt_tokens", 0))
                    val completionTokens = usage.optInt("output_tokens", usage.optInt("completion_tokens", 0))
                    val cachedTokens = usage.optInt("cache_read_input_tokens", usage.optInt("cached_tokens", 0))
                    val cost = result.optDouble("cost_usd", root.optDouble("cost_usd", 0.0))
                    if (promptTokens > 0 || completionTokens > 0) {
                        results.add(
                            AntigravityParsedEvent.TokenUsage(
                                SessionTokenMetrics(
                                    promptTokens = promptTokens,
                                    completionTokens = completionTokens,
                                    cachedTokens = cachedTokens,
                                    contextWindowLimit = 200_000,
                                    estimatedCostUsd = cost,
                                ),
                            ),
                        )
                    }
                }
            }
            else -> null
        }
        return results
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.optString("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    preferredKeys.forEach { key ->
        parameters?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            val prefix = when {
                rawName.equals("schedule", true) && key == "DurationSeconds" -> "Wait "
                rawName.equals("manage_task", true) -> "$key: "
                else -> ""
            }
            val suffix = if (rawName.equals("schedule", true) && key == "DurationSeconds") "s" else ""
            return redactToolDetail(prefix + it + suffix)
        }
    }
    return parameters?.toString()?.takeUnless { it == "{}" }?.let(::redactToolDetail)
        ?: step.optJSONObject("tool")?.toString()?.let(::redactToolDetail)
        .orEmpty()
}

private fun redactToolDetail(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

/** Official Antigravity CLI bridge. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 90_000L
class AntigravityRuntimeBridge(
    private val context: Context,
    val accountManager: AntigravityAccountManager? = null,
    private val model: () -> String,
    private val effort: () -> String,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String) -> Unit,
    private val conversationAccount: ((String) -> String?)? = null,
    private val saveConversationAccount: ((String, String) -> Unit)? = null,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 64)
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    override val isRunning: Boolean get() = activeProcess?.isAlive == true
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    @Volatile private var foregroundResultPosted = false

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    /**
     * Lightweight connectivity probe: sends a tiny hello to agy and returns its
     * reply text. No foreground service, no checkpoints, no saved conversation.
     * The timeout is intentionally internal — callers only see success/failure.
     */
    suspend fun hello(timeoutMillis: Long = HELLO_TIMEOUT_MILLIS): String = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            throw IllegalStateException("Antigravity CLI is not installed.")
        }
        try {
            withTimeout(timeoutMillis) {
                val installed = installer.installedRuntime()
                val probeDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val command = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json"))
                    addAll(listOf("--output-format", "stream-json"))
                    addAll(listOf("--print-timeout", "2m"))
                    add("--dangerously-skip-permissions")
                    addAntigravitySelection(model(), effort())
                    add("--new-project")
                }
                val account = accountManager?.selectAccountForTurn()
                if (account != null) {
                    accountManager.getAccountAccessToken(account.id)
                }
                val env = if (account != null) {
                    mapOf("HOME" to accountManager.getAccountHomeGuestPath(account.id))
                } else emptyMap()
                val outputFile = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                val process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    probeDir,
                    env,
                    command,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = outputFile,
                )
                try {
                    val request = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    process.outputStream.write(request.toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                    var offset = 0L
                    val pending = StringBuilder()
                    var reply: String? = null
                    fun handleLine(line: String): Boolean {
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> if (reply == null && event.value.isNotBlank()) reply = event.value
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (reply == null && !event.response.isNullOrBlank()) reply = event.response
                                    return true
                                }
                                throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                            }
                            else -> Unit
                        }
                        return false
                    }
                    var done = false
                    while (!done && (process.isAlive || outputFile.length() > offset)) {
                        val available = outputFile.length() - offset
                        if (available <= 0) {
                            delay(100)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(outputFile, "r").use { file ->
                            file.seek(offset)
                            file.read(bytes)
                        }
                        if (count <= 0) continue
                        offset += count
                        pending.append(bytes.decodeToString(0, count))
                        var newline = pending.indexOf("\n")
                        while (newline >= 0) {
                            val line = pending.substring(0, newline).trimEnd('\r')
                            pending.delete(0, newline + 1)
                            if (handleLine(line)) {
                                done = true
                                break
                            }
                            newline = pending.indexOf("\n")
                        }
                    }
                    pending.toString().trim().takeIf(String::isNotEmpty)?.let { if (!done) done = handleLine(it) }
                    // Drain process exit without hanging past the timeout.
                    withContext(NonCancellable) {
                        runCatching { process.waitFor() }
                    }
                    check(done) { friendlyError(pending.toString().takeLast(500).ifBlank { "Antigravity exited without answering" }) }
                    reply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { process.destroy() }
                    runCatching {
                        if (process.isAlive) process.destroyForcibly()
                    }
                    runCatching { outputFile.delete() }
                    runCatching { probeDir.deleteRecursively() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AntigravitySessionException("Antigravity did not answer. Try again.")
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
        memory: ContextMemory,
        taskId: String?,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        if (taskId != null) {
            runCatching {
                com.jarves.mh.runtime.task.TaskSupervisor.getInstance(context).bindSession(taskId, sessionId)
            }
        }
        activeSessionId = sessionId
        userStopRequested = false
        foregroundResultPosted = false
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!installer.isAgentInstalled(com.jarves.mh.model.AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            return@withContext sessionId
        }

        val attemptedAccountIds = mutableSetOf<String>()
        val existingConvId = conversationId(projectId)
        val initialStickyAccountId = if (!existingConvId.isNullOrBlank()) conversationAccount?.invoke(projectId) else null
        var currentStickyAccountId = initialStickyAccountId
        var sessionCompleted = false

        while (!sessionCompleted && !userStopRequested) {
            val account = accountManager?.selectAccountForTurn(
                excludeAccountIds = attemptedAccountIds,
                stickyAccountId = currentStickyAccountId,
            )
            val useAccount = account != null
            if (useAccount) {
                attemptedAccountIds.add(account!!.id)
            } else if (attemptedAccountIds.isNotEmpty()) {
                val msg = "All available Antigravity accounts are out of quota or unavailable."
                emitFailure(sessionId, msg)
                finishForegroundRuntime(false, projectSlug, msg)
                return@withContext sessionId
            }

            val guestHome = if (useAccount) {
                accountManager!!.getAccountAccessToken(account!!.id)
                accountManager.getAccountHomeGuestPath(account.id)
            } else "/root"
            val env = if (useAccount) mapOf("HOME" to guestHome) else emptyMap()

            val isStickyTurn = (useAccount && account!!.id == currentStickyAccountId && !existingConvId.isNullOrBlank())
            val targetConvId = if (isStickyTurn) existingConvId else null
            val effectivePrompt = if (!isStickyTurn && conversationHistory.isNotEmpty()) {
                buildFailoverPrompt(projectSlug, prompt, conversationHistory, memory)
            } else {
                antigravityWorkspacePrompt(projectSlug, prompt, memory)
            }

            val turnResult = runCatching {
                RuntimeTaskController.stopAction = {
                    userStopRequested = true
                    activeProcess?.destroy()
                }
                startForegroundRuntime(projectSlug, taskId)
                val installed = installer.installedRuntime()
                val workspace = checkpoints.ensureWorkspace(projectId)
                checkpoints.createCheckpoint(projectId, workspace)
                val before = checkpoints.snapshot(workspace)
                val command = antigravityCommand(model(), effort(), targetConvId)
                val process = installer.process(
                    installed.proot,
                    installed.rootfs,
                    workspace,
                    env,
                    command,
                    guestWorkspacePath = "/workspace/$projectSlug",
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
                    Log.w("AntigravityBridge", "Process binding failed for task $taskId / session $sessionId (PID: $nativePid)")
                }
                if (userStopRequested) process.destroy()
                val request = JSONObject()
                    .put("event", "user")
                    .put("message", JSONObject().put("content", effectivePrompt))
                    .toString() + "\n"
                process.outputStream.write(request.toByteArray())
                process.outputStream.flush()
                process.outputStream.close()

                val native = process as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
                var offset = 0L
                val pending = StringBuilder()
                var resultSeen = false
                var assistantTextSeen = false
                suspend fun handleLine(line: String) {
                    AntigravityEventParser.parseEvents(line).forEach { event ->
                        when (event) {
                            is AntigravityParsedEvent.Initialized -> {
                                saveConversationId(projectId, event.conversationId)
                                if (useAccount) saveConversationAccount?.invoke(projectId, account!!.id)
                            }
                            is AntigravityParsedEvent.Text -> {
                                assistantTextSeen = true
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                            }
                            is AntigravityParsedEvent.ToolStarted -> eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                            is AntigravityParsedEvent.ToolCompleted -> eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                            is AntigravityParsedEvent.Subagent -> eventBus.emit(RuntimeEvent.SubagentUpdated(sessionId, event.subagent))
                            is AntigravityParsedEvent.Task -> eventBus.emit(RuntimeEvent.TaskUpdated(sessionId, event.task))
                            is AntigravityParsedEvent.Artifact -> eventBus.emit(RuntimeEvent.ArtifactDiscovered(sessionId, event.artifact))
                            is AntigravityParsedEvent.Timer -> eventBus.emit(RuntimeEvent.TimerUpdated(sessionId, event.timer))
                            is AntigravityParsedEvent.TokenUsage -> eventBus.emit(RuntimeEvent.TokenUsageUpdated(sessionId, event.metrics))
                            is AntigravityParsedEvent.Result -> {
                                event.conversationId?.let {
                                    saveConversationId(projectId, it)
                                    if (useAccount) saveConversationAccount?.invoke(projectId, account!!.id)
                                }
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (!assistantTextSeen && !event.response.isNullOrBlank()) {
                                        assistantTextSeen = true
                                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.response))
                                    }
                                    resultSeen = true
                                } else throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                            }
                        }
                    }
                }
                var lastOutputTime = System.currentTimeMillis()
                while (process.isAlive || native.outputFile.length() > offset) {
                    val available = native.outputFile.length() - offset
                    if (available <= 0) {
                        // Watchdog: If result was observed and output drained, don't hang indefinitely on Node.js event loop
                        if (resultSeen && (System.currentTimeMillis() - lastOutputTime >= 1500L)) {
                            Log.i("AntigravityBridge", "Result observed and output drained; closing process gracefully")
                            if (process.isAlive) process.destroy()
                            break
                        }
                        delay(50)
                        continue
                    }
                    lastOutputTime = System.currentTimeMillis()
                    val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                    val count = RandomAccessFile(native.outputFile, "r").use { file ->
                        file.seek(offset)
                        file.read(bytes)
                    }
                    if (count <= 0) continue
                    offset += count
                    pending.append(bytes.decodeToString(0, count))
                    var newline = pending.indexOf("\n")
                    while (newline >= 0) {
                        val line = pending.substring(0, newline).trimEnd('\r')
                        pending.delete(0, newline + 1)
                        handleLine(line)
                        newline = pending.indexOf("\n")
                    }
                }
                pending.toString().trim().takeIf(String::isNotEmpty)?.let { handleLine(it) }
                val exit = process.waitFor()
                val success = (exit == 0) && (resultSeen || assistantTextSeen)
                if (success) {
                    val paths = checkpoints.changedFiles(workspace, before)
                    checkpoints.saveChangedPaths(projectId, paths)
                    if (paths.isNotEmpty()) {
                        eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, paths)))
                    }
                    if (useAccount) {
                        accountManager!!.recordUsage(account!!.id)
                        accountManager.resetStatus(account.id)
                        saveConversationAccount?.invoke(projectId, account.id)
                        runCatching { accountManager.refreshAccountQuota(account.id) }
                    }
                    emitCompleted(sessionId)
                    finishForegroundRuntime(true, projectSlug, "Antigravity finished the task in $projectSlug.")
                    sessionCompleted = true
                } else {
                    val errDetail = pending.toString().takeLast(1_000).ifBlank { "Antigravity exited with code $exit" }
                    throw AntigravitySessionException(friendlyError(errDetail))
                }
            }

            turnResult.onFailure { error ->
                val errorMsg = error.message.orEmpty()
                val isQuota = isQuotaError(errorMsg)
                val isAuth = isAuthError(errorMsg)

                if (useAccount) {
                    if (isQuota) accountManager!!.markQuotaExhausted(account!!.id, modelId = model())
                    if (isAuth) accountManager!!.markAuthError(account!!.id, friendlyError(errorMsg))
                }

                val hasMoreAccounts = useAccount &&
                    accountManager?.selectAccountForTurn(excludeAccountIds = attemptedAccountIds) != null

                if (!userStopRequested && (isQuota || isAuth) && hasMoreAccounts) {
                    currentStickyAccountId = null
                    eventBus.emit(
                        RuntimeEvent.AssistantDelta(
                            sessionId,
                            "\n\n*[Notice: Account ${account?.displayTitle ?: ""} reached limit. Failing over to next available account…]*\n\n",
                        ),
                    )
                } else {
                    // Ensure active process is killed so no orphaned process runs concurrently
                    activeProcess?.let { if (it.isAlive) it.destroyForcibly() }
                    val message = if (userStopRequested) "Stopped by user" else friendlyError(errorMsg)
                    emitFailure(sessionId, message)
                    if (userStopRequested) cancelForegroundRuntime()
                    else finishForegroundRuntime(false, projectSlug, message)
                    sessionCompleted = true
                    throw error
                }
            }
        }
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.stopAction = null
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // agy headless streaming rejects control_response messages. This driver is
        // intentionally launched with --dangerously-skip-permissions by explicit
        // product choice, so no Antigravity approval can be pending here.
    }

    override suspend fun stopSession(sessionId: String, force: Boolean) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            val proc = activeProcess
            if (proc != null) {
                if (force) {
                    // Stage 2: Force kill immediately — skip grace period
                    (proc as? NativeSpawnProcess)?.destroyForcibly() ?: proc.destroyForcibly()
                } else {
                    // Stage 1: Graceful interrupt (SIGINT / Ctrl+C)
                    (proc as? NativeSpawnProcess)?.interrupt() ?: proc.destroy()
                    // Allow up to 1500ms for the process to handle the interrupt and exit.
                    var waited = 0L
                    while (proc.isAlive && waited < 1500L) {
                        kotlinx.coroutines.delay(100)
                        waited += 100
                    }
                    // Auto-escalate to force kill if still alive after grace period.
                    if (proc.isAlive) proc.destroyForcibly()
                }
            }
            emitFailure(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession(force: Boolean) = activeSessionId?.let { stopSession(it, force) } ?: Unit

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val checkpoint = checkpoints.checkpointDir(projectId)
        val backup = File(checkpoint, "project")
        val paths = checkpoints.readChangedPaths(projectId)
        if (!backup.isDirectory || paths.isEmpty()) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        paths.forEach { restore(workspace, backup, it) }
        checkpoint.deleteRecursively()
        true
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.checkpointDir(projectId).deleteRecursively()
        Unit
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        restore(checkpoints.ensureWorkspace(projectId), File(checkpoints.checkpointDir(projectId), "project"), path)
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        if (path !in checkpoints.readChangedPaths(projectId)) return@withContext false
        val workspace = checkpoints.ensureWorkspace(projectId)
        val backup = File(checkpoints.checkpointDir(projectId), "project")
        val current = checkpoints.safeWorkspaceFile(workspace, path)
        val baseline = checkpoints.safeWorkspaceFile(backup, path)
        if (current.isFile) {
            baseline.parentFile?.mkdirs()
            current.copyTo(baseline, overwrite = true)
        } else baseline.delete()
        checkpoints.removeChangedPath(projectId, path)
        true
    }

    private fun restore(workspace: File, backup: File, path: String) {
        val target = checkpoints.safeWorkspaceFile(workspace, path)
        val original = checkpoints.safeWorkspaceFile(backup, path)
        if (original.isFile) {
            target.parentFile?.mkdirs()
            original.copyTo(target, overwrite = true)
        } else target.delete()
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
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

    private fun friendlyError(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("authentication required", true) ||
                value.contains("authentication failed", true) ||
                value.contains("not signed in", true) ->
                "Antigravity needs Google sign-in. Open Settings → Coding agent."
            value.contains("out of credits", true) || value.contains("quota", true) ->
                "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
            value.contains("timed out", true) || value.contains("timeout", true) ->
                "Antigravity reached the 60-minute task limit. Your files were kept."
            value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
                "The selected Antigravity model is unavailable. Refresh models in Settings."
            value.isBlank() -> "Antigravity could not complete the task."
            else -> value.take(500)
        }
    }
}

private class AntigravitySessionException(message: String) : IllegalStateException(message)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    // This is intentionally explicit and covered by tests. Antigravity tool calls
    // do not pass through PocketDev approval dialogs while this mode is enabled.
    add("--dangerously-skip-permissions")
    addAntigravitySelection(model, effort)
    conversationId?.takeIf(String::isNotBlank)?.let {
        addAll(listOf("--conversation", it))
    } ?: add("--new-project")
}

/**
 * `agy models` returns complete configuration IDs such as
 * `gemini-3.8-flash-medium`. Supplying a second, different --effort makes the
 * official CLI reject an otherwise valid model as conflicting. An explicit
 * model ID therefore owns its effort; --effort is used only with agy's default
 * model selection.
 */
private fun MutableList<String>.addAntigravitySelection(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    } else if (effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(
    projectSlug: String,
    prompt: String,
    memory: ContextMemory = ContextMemory(""),
): String {
    val memoryBlock = renderMemoryBlock(memory)
    return buildString {
        appendLine("<pocketdev_workspace>")
        appendLine("The active project workspace is /workspace/$projectSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.")
        appendLine("</pocketdev_workspace>")
        if (memoryBlock.isNotBlank()) {
            appendLine()
            appendLine(memoryBlock)
        }
        appendLine()
        append(prompt)
    }.trimIndent()
}

internal fun buildFailoverPrompt(
    projectSlug: String,
    originalPrompt: String,
    history: List<ChatMessage>,
    memory: ContextMemory = ContextMemory(""),
): String {
    if (history.isEmpty()) return antigravityWorkspacePrompt(projectSlug, originalPrompt, memory)
    val recent = history.takeLast(4).joinToString("\n\n") { msg ->
        val role = if (msg.fromUser) "User" else "Assistant"
        "$role: ${msg.text.take(500)}"
    }
    val combined = "Previous conversation context:\n$recent\n\nCurrent task:\n$originalPrompt"
    return antigravityWorkspacePrompt(projectSlug, combined, memory)
}

internal fun isQuotaError(raw: String): Boolean {
    val value = raw.lowercase()
    return value.contains("out of credits") ||
        value.contains("quota") ||
        value.contains("rate limit") ||
        value.contains("resource_exhausted") ||
        value.contains("429")
}

internal fun isAuthError(raw: String): Boolean {
    val value = raw.lowercase()
    return value.contains("authentication required") ||
        value.contains("authentication failed") ||
        value.contains("not signed in") ||
        value.contains("oauth")
}

