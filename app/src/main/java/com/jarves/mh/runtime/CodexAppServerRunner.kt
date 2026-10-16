package com.jarves.mh.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

internal data class CodexAppServerRunnerConfig(
    val pollMs: Long = 50,
    val requestTimeoutMs: Long = 15_000,
    val closeGraceMs: Long = 3_000,
    val transientStallMs: Long = 120_000,
)

/** One owned stdio server per supervised task; native goals may execute several turns. */
internal class CodexAppServerRunner(
    private val io: CodexProcessIo,
    private val workspace: String,
    private val prompt: String,
    private var model: String,
    private val resumeId: String?,
    private val planMode: Boolean,
    private val control: CodexNativeControl?,
    private val imagePaths: List<String> = emptyList(),
    private val imageData: (String) -> String = { error("Native goal image data is unavailable.") },
    private val emit: suspend (CodexEvent) -> Unit,
    private val onThread: (String) -> Unit = {},
    private val onRateLimits: (JSONObject) -> Unit = {},
    private val takeGoalControl: () -> CodexNativeControl? = { null },
    private val isStopRequested: () -> Boolean = { false },
    private val onActivity: (Boolean) -> Unit = {},
    private val reasoningEffort: String? = null,
    /**
     * Mods' tool gate (mod plan row 6), only when the project's Codex switch is on: Codex then asks
     * before commands and edits ("untrusted"), and each request runs when this answers true. Null
     * keeps Codex never asking, as before.
     */
    private val toolGate: (suspend (tool: String, input: JSONObject) -> Boolean)? = null,
    private val config: CodexAppServerRunnerConfig = CodexAppServerRunnerConfig(),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var nextId = 1
    private var offset = 0L
    private val assembler = CodexLineAssembler(1 shl 20)
    private val buffer = ByteArray(64 * 1024)
    private val pendingLines = ArrayDeque<String>()
    private val completedItems = mutableSetOf<String>()
    private val replies = mutableMapOf<Int, JSONObject>()
    private var threadId: String? = resumeId
    private var activeTurn: String? = null
    private var finishedTurn = false
    private var goal: CodexNativeGoal? = null
    private var goalVersion = 0L
    private var compactionCompleted = false
    private var pausedByUser = false
    private var clearedByUser = false
    private var failure: String? = null
    private var transientAt: Long? = null
    private val textByItem = mutableMapOf<String, String>()
    private val reasoningByItem = mutableMapOf<String, String>()
    private val fileChangePaths = mutableMapOf<String, List<String>>()
    private var usage = CodexUsage()

    suspend fun run(): CodexRunResult {
        var success = false
        try {
            check(!isStopRequested()) { "Stopped by user" }
            rpc("initialize", JSONObject().put("clientInfo", JSONObject().put("name", "mobile_harness").put("version", "1"))
                .put("capabilities", JSONObject().put("experimentalApi", true)))
            send(JSONObject().put("method", "initialized"))
            if (control?.goalControl == true) {
                require(resumeId != null) { "This chat has no native Codex goal. Use /goal <objective> first." }
                performGoalControl(control)
                success = true
            } else {
                if (control?.kind in setOf("goalSet", "goalResume")) require(!planMode) { "Use /plan off before activating a goal." }
                val needsExisting = control?.kind in setOf("compact", "goalResume")
                require(!needsExisting || resumeId != null) { "This chat has no native Codex thread. Send a task first." }
                if (resumeId != null) {
                    readGoal("thread/goal/get", JSONObject().put("threadId", resumeId))
                    if (control?.kind !in setOf("goalSet", "goalResume")) require(goal?.status != "active") { "Pause the active goal with /goal pause or explicitly resume it with /goal resume." }
                    if (control?.kind == "goalResume") require(goal != null) { "This chat has no native Codex goal." }
                }
                val params = JSONObject().put("cwd", workspace).put("approvalPolicy", if (toolGate != null) "untrusted" else "never")
                    .put("sandbox", "danger-full-access")
                if (model.isNotBlank()) params.put("model", model)
                val result = if (resumeId == null) rpc("thread/start", params)
                    else rpc("thread/resume", params.put("threadId", resumeId).put("excludeTurns", true))
                val id = result.getJSONObject("thread").getString("id")
                require(NativeConversationScope.validId(id) != null && (resumeId == null || id == resumeId)) { "Codex returned an invalid thread identity." }
                threadId = id
                model = result.optString("model").ifBlank { model }
                onThread(id) // Synchronous persistence precedes any operation that can dispatch work.
                emit(CodexEvent.ThreadStarted(id))
                when (control?.kind) {
                    "compact" -> rpc("thread/compact/start", JSONObject().put("threadId", id))
                    "review" -> {
                        val target = JSONObject().put("type", control.target)
                        when (control.target) {
                            "baseBranch" -> target.put("branch", control.text)
                            "commit" -> target.put("sha", control.text)
                            "custom" -> target.put("instructions", control.text)
                        }
                        val review = rpc("review/start", JSONObject().put("threadId", id).put("delivery", "inline").put("target", target))
                        require(review.optString("reviewThreadId", id) == id) { "Codex started review on an unexpected thread." }
                        rememberTurn(review.optJSONObject("turn"))
                    }
                    "goalSet", "goalResume" -> {
                        // Goal activation auto-dispatches. Seed context as USER data before that RPC.
                        if (control.kind == "goalSet" && (prompt.isNotBlank() || imagePaths.isNotEmpty())) {
                            val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
                            imagePaths.forEach { content.put(JSONObject().put("type", "input_image").put("image_url", imageData(it))) }
                            rpc("thread/inject_items", JSONObject().put("threadId", id).put("items", JSONArray().put(
                                JSONObject().put("type", "message").put("role", "user").put("content", content))))
                        }
                        val goalParams = JSONObject().put("threadId", id).put("status", "active").put("origin", "user")
                        if (control.kind == "goalSet") goalParams.put("objective", control.text)
                        control.tokenBudget?.let { goalParams.put("tokenBudget", it) }
                        readGoal("thread/goal/set", goalParams)
                        emitGoal()
                        if (activeTurn == null) onActivity(false)
                    }
                    else -> {
                        require(model.isNotBlank()) { "Codex did not resolve a model for the native mode." }
                        val input = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
                        imagePaths.forEach { input.put(JSONObject().put("type", "localImage").put("path", it)) }
                        val mode = if (planMode || control?.kind == "planTurn") "plan" else "default"
                        val settings = JSONObject().put("model", model).put("developer_instructions", JSONObject.NULL)
                        reasoningEffort?.let { settings.put("reasoning_effort", it) }
                        val started = rpc("turn/start", JSONObject().put("threadId", id).put("input", input)
                            .put("collaborationMode", JSONObject().put("mode", mode).put("settings", settings)))
                        rememberTurn(started.optJSONObject("turn"))
                    }
                }
                // An active native goal owns continuation. Neither turn/completed nor idle proves it stopped.
                val idle = IdlePoll(config.pollMs, signal = io.outputSignal)
                while (true) {
                    val readFrom = offset
                    pump()
                    if (offset != readFrom) idle.reset()
                    check(failure == null) { failure.orEmpty() }
                    if (isStopRequested()) { stopNative(); error("Stopped by user") }
                    takeGoalControl()?.let { performGoalControl(it) }
                    val terminalGoal = goal?.status != "active"
                    val goalOperation = control?.kind in setOf("goalSet", "goalResume")
                    val operationDone = activeTurn == null && terminalGoal &&
                        (finishedTurn || goalOperation && goal != null) &&
                        (control?.kind != "compact" || compactionCompleted)
                    if (operationDone || (pausedByUser || clearedByUser) && activeTurn == null) {
                        if (goal != null || clearedByUser) emitGoal()
                        if (control?.kind == "compact") emit(CodexEvent.AgentMessage("Native Codex context compaction completed.", status = true))
                        success = true
                        break
                    }
                    transientAt?.let { check(now() - it < config.transientStallMs) { "Network connection interrupted. Codex could not reach the provider." } }
                    check(io.isAlive) { "Codex app-server exited before completing its native operation." }
                    idle.await()
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runCatching { stopNative() } }
            throw cancelled
        } catch (error: Exception) {
            failure = error.message?.take(1000).orEmpty().ifBlank { "Codex native operation failed." }
            runCatching { stopNative() }
        } finally {
            withContext(NonCancellable) {
                runCatching { onActivity(false) }
                // A completed turn is not a process exit. Close stdin, then bound termination and reap.
                runCatching { io.closeInput() }
                val deadline = now() + config.closeGraceMs
                while (io.isAlive && now() < deadline) delay(config.pollMs)
                if (io.isAlive) runCatching { io.destroy() }
                val forcedDeadline = now() + config.closeGraceMs
                while (io.isAlive && now() < forcedDeadline) delay(config.pollMs)
                if (io.isAlive) runCatching { io.destroyForcibly() }
                val reapDeadline = now() + config.closeGraceMs
                while (io.isAlive && now() < reapDeadline) delay(config.pollMs)
                if (io.isAlive) { success = false; failure = "Codex native process could not be reaped." }
            }
        }
        val exit = if (!io.isAlive) runCatching { io.waitFor() }.getOrDefault(-1) else -1
        return CodexRunResult(success && !isStopRequested(), failure.orEmpty(), exit, threadId, if (clearedByUser) "cleared" else goal?.status,
            compacted = compactionCompleted)
    }

    private fun rememberTurn(turn: JSONObject?) {
        if (turn == null) return
        // Notifications can complete a very short turn before its RPC response is drained.
        if (!finishedTurn && turn.optString("status") == "inProgress") activeTurn = turn.getString("id")
    }

    private suspend fun performGoalControl(action: CodexNativeControl) {
        if (control?.goalControl != true && control?.kind !in setOf("goalSet", "goalResume")) {
            emit(CodexEvent.AgentMessage("This running Codex task does not own an active native goal."))
            return
        }
        val id = threadId ?: error("This chat has no native Codex goal.")
        val params = JSONObject().put("threadId", id)
        when (action.kind) {
            "goalStatus" -> readGoal("thread/goal/get", params)
            "goalPause" -> {
                readGoal("thread/goal/get", params)
                if (goal == null) { emitGoal(); return }
                pausedByUser = true // The interrupted notification may precede the mutation reply.
                readGoal("thread/goal/set", params.put("status", "paused").put("origin", "user"))
                pausedByUser = goal?.status == "paused"
                if (pausedByUser) activeTurn?.let { rpc("turn/interrupt", JSONObject().put("threadId", id).put("turnId", it)) }
            }
            "goalClear" -> {
                readGoal("thread/goal/get", params)
                if (goal == null) { clearedByUser = true; emitGoal(); return }
                clearedByUser = true
                rpc("thread/goal/clear", params.put("origin", "user"))
                goal = null
                clearedByUser = true
                activeTurn?.let { rpc("turn/interrupt", JSONObject().put("threadId", id).put("turnId", it)) }
            }
            else -> error("Unsupported active goal control")
        }
        emitGoal()
    }

    private suspend fun stopNative() {
        val id = threadId ?: return
        if (goal?.status == "active") readGoal("thread/goal/set", JSONObject().put("threadId", id).put("status", "paused").put("origin", "user"), allowStop = true)
        activeTurn?.let { rpc("turn/interrupt", JSONObject().put("threadId", id).put("turnId", it), allowStop = true) }
    }

    /** Notifications received before an RPC reply contain newer state than its captured response. */
    private suspend fun readGoal(method: String, params: JSONObject, allowStop: Boolean = false) {
        val version = goalVersion
        val reply = rpc(method, params, allowStop)
        val value = if (method == "thread/goal/get") reply.optJSONObject("goal") else reply.getJSONObject("goal")
        if (version == goalVersion) updateGoal(value)
    }

    private fun updateGoal(value: JSONObject?) { goal = value?.let(CodexNativeGoal::parse) }
    private suspend fun emitGoal() = emit(CodexEvent.AgentMessage(goal?.describe() ?: if (clearedByUser) "Native Codex goal cleared." else "This native Codex thread has no goal.", status = true))

    private fun send(obj: JSONObject) = io.writeInput(obj.toString() + "\n")
    private suspend fun rpc(method: String, params: JSONObject, allowStop: Boolean = false): JSONObject {
        val id = nextId++
        send(JSONObject().put("id", id).put("method", method).put("params", params))
        val deadline = now() + if (allowStop) config.closeGraceMs else config.requestTimeoutMs
        while (true) {
            pump(id)
            replies.remove(id)?.let { reply ->
                reply.optJSONObject("error")?.let { error(it.optString("message").take(1000).ifBlank { "$method failed." }) }
                return reply.optJSONObject("result") ?: error("Codex returned no result for $method.")
            }
            check(failure == null) { failure.orEmpty() }
            check(allowStop || !isStopRequested()) { "Stopped by user" }
            check(io.isAlive) { "Codex exited during $method." }
            check(now() < deadline) { "Codex did not acknowledge $method within its deadline." }
            waitForOutput(config.pollMs)
        }
    }

    /** Waits up to [timeoutMs], ending early when Codex writes output. */
    private suspend fun waitForOutput(timeoutMs: Long) {
        val signal = io.outputSignal
        if (signal == null) delay(timeoutMs) else signal.await(timeoutMs)
    }

    private suspend fun pump(expectedReply: Int? = null) {
        val available = io.outputLength() - offset
        if (available > 0) {
            val count = io.readOutput(offset, buffer, minOf(available, buffer.size.toLong()).toInt())
            check(count > 0) { "Could not read Codex native output." }
            offset += count
            pendingLines.addAll(assembler.feed(buffer, count))
        }
        while (pendingLines.isNotEmpty()) {
            val line = pendingLines.removeFirst()
            check(line != CodexLineAssembler.OVERSIZED) { "Codex native output exceeded the supported event size." }
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue // stderr is merged with stdout.
            val method = obj.optString("method")
            if (method.isEmpty() && obj.has("id")) {
                check(replies.size < 32) { "Too many unexpected Codex replies." }
                replies[obj.getInt("id")] = obj
                if (obj.getInt("id") == expectedReply) return
            } else if (obj.has("id") && toolGate != null && method in APPROVAL_METHODS) {
                answerApproval(obj, method, obj.optJSONObject("params") ?: JSONObject(), toolGate)
            } else if (obj.has("id")) {
                val params = obj.optJSONObject("params") ?: JSONObject()
                val questions = params.optJSONArray("questions")
                val detail = questions?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("question") }.joinToString("; ") }.orEmpty()
                val message = if (method == "item/tool/requestUserInput") "Codex needs your input: $detail. Reply with your choices in a new message."
                    else "This noninteractive Codex driver cannot answer $method."
                send(JSONObject().put("id", obj.get("id")).put("error", JSONObject().put("code", -32601).put("message", message)))
                failure = message
            } else {
                val params = obj.optJSONObject("params") ?: continue
                if (method == "account/rateLimits/updated") {
                    params.optJSONObject("rateLimits")?.let(onRateLimits)
                    continue
                }
                if (params.optString("threadId") != threadId) continue
                notification(method, params)
              }
        }
    }

    /**
     * Answers Codex's request to run a command or apply a file change from the mods' gate.
     * A gate that fails or takes too long lets it run, as Codex did before the switch.
     */
    private suspend fun answerApproval(request: JSONObject, method: String, params: JSONObject, gate: suspend (String, JSONObject) -> Boolean) {
        val command = params.opt("command").let { if (it is JSONArray) (0 until it.length()).joinToString(" ") { i -> it.optString(i) } else it as? String }
        val input = if (method in COMMAND_APPROVAL_METHODS) {
            JSONObject().put("command", command.orEmpty().take(8_000)).put("cwd", params.optString("cwd", workspace))
        } else {
            val paths = fileChangePaths[params.optString("itemId")] ?: params.optJSONObject("fileChanges")?.keys()?.asSequence()?.toList().orEmpty()
            JSONObject().put("paths", JSONArray(paths.take(200)))
        }
        if (params.has("reason") && !params.isNull("reason")) input.put("reason", params.optString("reason").take(1_000))
        val tool = if (input.has("command")) "Bash" else "apply_patch"
        val run = runCatching { withTimeoutOrNull(GATE_WAIT_MS) { gate(tool, input) } }.getOrNull() ?: true
        val decision = if (method in LEGACY_APPROVAL_METHODS) (if (run) "approved" else "denied") else (if (run) "accept" else "decline")
        send(JSONObject().put("id", request.get("id")).put("result", JSONObject().put("decision", decision)))
    }

    private suspend fun notification(method: String, params: JSONObject) {
        val turn = params.optJSONObject("turn")
        val turnId = params.optString("turnId", turn?.optString("id").orEmpty())
        when (method) {
            "turn/started" -> { activeTurn = turn?.getString("id"); finishedTurn = false; transientAt = null; onActivity(true); emit(CodexEvent.TurnStarted) }
            "turn/completed" -> {
                val id = turn?.getString("id") ?: return
                if (activeTurn != null && activeTurn != id) return
                // Completed turn snapshots also supply the final text if a delta was absent.
                turn.optJSONArray("items")?.let { items -> for (i in 0 until items.length()) items.optJSONObject(i)?.let { item(it, true, id) } }
                activeTurn = null
                finishedTurn = true
                onActivity(false)
                val status = turn.optString("status")
                if (status == "failed") failure = turn.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Codex could not finish the native turn." }
                else if (status == "interrupted" && !pausedByUser && !clearedByUser && !isStopRequested()) failure = "Codex native turn was interrupted."
                emit(CodexEvent.TurnCompleted(usage))
            }
            "thread/goal/updated" -> { goalVersion++; updateGoal(params.getJSONObject("goal")) }
            "thread/goal/cleared" -> { goalVersion++; goal = null; clearedByUser = true }
            "thread/tokenUsage/updated" -> {
                val total = params.getJSONObject("tokenUsage").getJSONObject("total")
                usage = CodexUsage(total.optInt("inputTokens"), total.optInt("cachedInputTokens"), total.optInt("outputTokens"), total.optInt("reasoningOutputTokens"))
            }
            "item/agentMessage/delta" -> {
                val key = "$turnId:${params.getString("itemId")}"; val text = appendText(key, params.getString("delta"))
                emit(CodexEvent.AgentMessage(text, key, false)); transientAt = null
            }
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> {
                val key = "$turnId:${params.getString("itemId")}"; val old = reasoningByItem[key].orEmpty()
                check(reasoningByItem.size < 4096 || key in reasoningByItem) { "Too many Codex reasoning items." }
                val text = (old + params.optString("delta")).take(8000); reasoningByItem[key] = text
                emit(CodexEvent.Reasoning(text, key, false)); transientAt = null
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let { item(it, method == "item/completed", turnId) }
            "error" -> {
                val message = params.optJSONObject("error")?.optString("message").orEmpty().ifBlank { "Codex reported an error." }
                if (params.optBoolean("willRetry")) { if (transientAt == null) transientAt = now() }
                else failure = message
                emit(CodexEvent.StreamError(message, params.optBoolean("willRetry")))
              }
        }
    }

    private fun appendText(key: String, delta: String): String {
        check(textByItem.size < 4096 || key in textByItem) { "Too many Codex message items." }
        val text = textByItem[key].orEmpty() + delta
        check(text.length <= 1 shl 20) { "Codex message exceeded the supported size." }
        textByItem[key] = text
        return text
    }

    private suspend fun item(value: JSONObject, completed: Boolean, turn: String) {
        val id = "$turn:${value.optString("id")}" // Item IDs can repeat across native turns.
        if (completed && !completedItems.add(id)) return
        check(completedItems.size <= 4096) { "Too many completed Codex items." }
        fun text(key: String) = value.optString(key).takeUnless { it == "null" }.orEmpty()
        when (val type = value.optString("type")) {
            "agentMessage", "plan" -> if (text("text").isNotBlank()) {
                emit(CodexEvent.AgentMessage(text("text"), id, completed))
                textByItem[id] = text("text")
            }
            "reasoning" -> {
                val summary = value.optJSONArray("summary")
                val text = summary?.let { (0 until it.length()).map { i -> it.optString(i) }.joinToString("\n") }.orEmpty()
                    .ifBlank { reasoningByItem[id].orEmpty() }
                if (text.isNotBlank()) emit(CodexEvent.Reasoning(text, id, completed))
            }
            "commandExecution" -> if (completed) emit(CodexEvent.CommandFinished(id, text("command"), text("aggregatedOutput").takeLast(4000), if (value.isNull("exitCode")) null else value.getInt("exitCode"), text("status") in setOf("failed", "declined") || (!value.isNull("exitCode") && value.getInt("exitCode") != 0)))
                else emit(CodexEvent.CommandStarted(id, text("command")))
            "fileChange" -> if (!completed) {
                // Kept so the mods' gate can say which files an approval request would change.
                val changes = value.optJSONArray("changes")
                if (fileChangePaths.size < 256) fileChangePaths[value.optString("id")] =
                    changes?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("path")?.takeIf(String::isNotBlank) } }.orEmpty()
            } else {
                fileChangePaths.remove(value.optString("id"))
                val changes = value.optJSONArray("changes")
                emit(CodexEvent.FileChange(changes?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.optString("path")?.takeIf(String::isNotBlank) } }.orEmpty()))
            }
            "mcpToolCall", "dynamicToolCall", "webSearch", "imageView", "imageGeneration" -> {
                val name = when (type) { "mcpToolCall" -> "${text("server")}.${text("tool")}"; "webSearch" -> "Web search"; else -> text("tool").ifBlank { type } }
                emit(CodexEvent.ToolCall(id, name, value.opt("arguments")?.toString().orEmpty().take(500), completed, text("status") == "failed"))
            }
            "collabAgentToolCall" -> {
                val states = value.optJSONObject("agentsStates")
                val agents = states?.keys()?.asSequence()?.take(256)?.mapNotNull { child -> states.optJSONObject(child)?.let { CodexEvent.ChildState(child, it.optString("status"), it.optString("message").take(500)) } }?.toList().orEmpty()
                emit(CodexEvent.Collaboration(id, text("senderThreadId"), text("tool"), text("prompt").take(500), completed, text("status") == "failed", agents))
            }
            "enteredReviewMode" -> emit(CodexEvent.ToolCall(id, "Native review", text("review"), false, false))
            "exitedReviewMode" -> if (completed) emit(CodexEvent.AgentMessage(text("review"), id))
            "contextCompaction" -> { if (completed) compactionCompleted = true; emit(CodexEvent.ToolCall(id, "Native compaction", "Compacting thread context", completed, false)) }
        }
    }

    private companion object {
        const val GATE_WAIT_MS = 9_000L
        /** v1 names answer `approved`/`denied`; the v2 item names answer `accept`/`decline`. */
        val LEGACY_APPROVAL_METHODS = setOf("execCommandApproval", "applyPatchApproval")
        val COMMAND_APPROVAL_METHODS = setOf("item/commandExecution/requestApproval", "execCommandApproval")
        val APPROVAL_METHODS = LEGACY_APPROVAL_METHODS + COMMAND_APPROVAL_METHODS + "item/fileChange/requestApproval"
    }
}
