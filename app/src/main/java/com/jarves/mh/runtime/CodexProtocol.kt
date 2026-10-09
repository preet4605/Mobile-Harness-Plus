package com.jarves.mh.runtime

import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.BackgroundTaskStatus
import org.json.JSONObject

/** Token counters reported by `codex exec --json` in `turn.completed`. */
internal data class CodexUsage(
    val inputTokens: Int = 0,
    val cachedInputTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningOutputTokens: Int = 0,
)

/**
 * One classified line of Codex output. `codex exec --json` writes JSONL events to stdout and
 * human-readable diagnostics to stderr; the native launcher merges both streams into one
 * capture file, so non-JSON lines are expected and must never be treated as protocol errors.
 */
internal sealed interface CodexEvent {
    data class ThreadStarted(val threadId: String) : CodexEvent
    data object TurnStarted : CodexEvent
    data class AgentMessage(val text: String, val itemId: String = "", val completed: Boolean = true) : CodexEvent
    data class Reasoning(val text: String, val itemId: String = "", val completed: Boolean = true) : CodexEvent
    data class CommandStarted(val itemId: String, val command: String) : CodexEvent
    data class CommandFinished(
        val itemId: String,
        val command: String,
        val output: String,
        val exitCode: Int?,
        val failed: Boolean,
    ) : CodexEvent
    data class FileChange(val paths: List<String>) : CodexEvent
    data class Collaboration(
        val itemId: String,
        val senderThreadId: String,
        val tool: String,
        val prompt: String,
        val finished: Boolean,
        val failed: Boolean,
        val agents: List<ChildState>,
    ) : CodexEvent
    data class ChildState(val threadId: String, val status: String, val message: String)
    data class ToolCall(
        val itemId: String,
        val name: String,
        val detail: String,
        val finished: Boolean,
        val failed: Boolean,
    ) : CodexEvent

    /** A non-fatal warning item (`item.completed` with item type `error`). */
    data class Notice(val message: String) : CodexEvent

    /**
     * Top-level `error` event. [transient] is true for the "Reconnecting..." progress notices that
     * Codex prints while it retries; the turn is still alive. A terminal failure follows as
     * [TurnFailed].
     */
    data class StreamError(val message: String, val transient: Boolean) : CodexEvent
    data class TurnCompleted(val usage: CodexUsage) : CodexEvent
    data class TurnFailed(val message: String) : CodexEvent

    /** A line that is not a JSON event (stderr text, config errors, warnings). */
    data class Diagnostic(val text: String) : CodexEvent
    data object Ignored : CodexEvent
}

/**
 * Stateless parser for the `codex exec --json` event stream (verified against codex-cli 0.161.0).
 * It never throws: malformed, unknown or oversized input degrades to [CodexEvent.Diagnostic] or
 * [CodexEvent.Ignored].
 */
internal object CodexJsonlParser {
    private const val MAX_DIAGNOSTIC_CHARS = 500
    private const val MAX_OUTPUT_CHARS = 4_000

    fun parseLine(rawLine: String): CodexEvent {
        val line = rawLine.trim()
        if (line.isEmpty()) return CodexEvent.Ignored
        if (line == CodexLineAssembler.OVERSIZED) {
            return CodexEvent.Diagnostic("A very long output line from Codex was skipped.")
        }
        if (!line.startsWith("{")) return CodexEvent.Diagnostic(line.take(MAX_DIAGNOSTIC_CHARS))
        val json = runCatching { JSONObject(line) }.getOrNull()
            ?: return CodexEvent.Diagnostic(line.take(MAX_DIAGNOSTIC_CHARS))
        return runCatching { classify(json) }.getOrDefault(CodexEvent.Ignored)
    }

    private fun classify(json: JSONObject): CodexEvent = when (json.optString("type")) {
        "thread.started" -> CodexEvent.ThreadStarted(json.text("thread_id"))
        "turn.started" -> CodexEvent.TurnStarted
        "turn.completed" -> CodexEvent.TurnCompleted(parseUsage(json.optJSONObject("usage")))
        "turn.failed" -> CodexEvent.TurnFailed(
            json.optJSONObject("error")?.text("message").orEmpty()
                .ifBlank { json.text("message") }
                .ifBlank { "Codex could not finish the turn." }
                .take(MAX_DIAGNOSTIC_CHARS),
        )
        "error" -> {
            val message = json.text("message").ifBlank { "Codex reported an error." }.take(MAX_DIAGNOSTIC_CHARS)
            CodexEvent.StreamError(message, transient = isTransientNotice(message))
        }
        "item.started", "item.completed", "item.updated" -> classifyItem(
            phase = json.optString("type").substringAfter('.'),
            item = json.optJSONObject("item"),
        )
        else -> CodexEvent.Ignored
    }

    private fun classifyItem(phase: String, item: JSONObject?): CodexEvent {
        if (item == null) return CodexEvent.Ignored
        val itemId = item.text("id")
        val completed = phase == "completed"
        return when (item.optString("type")) {
            "agent_message" -> item.text("text").takeIf { it.isNotBlank() }
                ?.let { CodexEvent.AgentMessage(it, itemId, completed) } ?: CodexEvent.Ignored
            "reasoning" -> item.text("text").takeIf { it.isNotBlank() }
                ?.let { CodexEvent.Reasoning(it, itemId, completed) } ?: CodexEvent.Ignored
            "command_execution" -> {
                val command = item.text("command")
                if (!completed) {
                    if (phase == "started") CodexEvent.CommandStarted(itemId, command) else CodexEvent.Ignored
                } else {
                    val exitCode = if (item.isNull("exit_code")) null else item.optInt("exit_code", Int.MIN_VALUE)
                        .takeIf { it != Int.MIN_VALUE }
                    val status = item.optString("status")
                    CodexEvent.CommandFinished(
                        itemId = itemId,
                        command = command,
                        output = item.text("aggregated_output").take(MAX_OUTPUT_CHARS),
                        exitCode = exitCode,
                        failed = status == "failed" || status == "declined" || (exitCode != null && exitCode != 0),
                    )
                }
            }
            "file_change" ->
                if (completed) {
                    val changes = item.optJSONArray("changes")
                    val paths = buildList {
                        if (changes != null) {
                            for (index in 0 until changes.length()) {
                                changes.optJSONObject(index)?.text("path")?.takeIf { it.isNotBlank() }?.let(::add)
                            }
                        }
                    }
                    CodexEvent.FileChange(paths)
                } else CodexEvent.Ignored
            "mcp_tool_call" -> {
                val server = item.text("server")
                val tool = item.text("tool")
                val name = listOf(server, tool).filter { it.isNotBlank() }.joinToString(".").ifBlank { "Tool" }
                CodexEvent.ToolCall(
                    itemId = itemId,
                    name = name,
                    detail = item.optJSONObject("arguments")?.toString().orEmpty().take(MAX_DIAGNOSTIC_CHARS),
                    finished = completed,
                    failed = item.optString("status") == "failed",
                )
            }
            "collab_tool_call" -> {
                val states = item.optJSONObject("agents_states")
                val agents = states?.keys()?.asSequence()?.take(256)?.mapNotNull { id ->
                    val state = states.optJSONObject(id) ?: return@mapNotNull null
                    if (id.isBlank() || id.length > 128) return@mapNotNull null
                    CodexEvent.ChildState(id, state.text("status"), state.text("message").take(MAX_DIAGNOSTIC_CHARS))
                }?.toList().orEmpty()
                CodexEvent.Collaboration(
                    itemId, item.text("sender_thread_id"), item.text("tool"),
                    item.text("prompt").take(MAX_DIAGNOSTIC_CHARS), completed,
                    item.text("status") == "failed", agents,
                )
            }
            "web_search" -> CodexEvent.ToolCall(
                itemId = itemId,
                name = "Web search",
                detail = item.text("query"),
                finished = completed,
                failed = false,
            )
            "error" ->
                if (completed) item.text("message").takeIf { it.isNotBlank() }
                    ?.let { CodexEvent.Notice(it.take(MAX_DIAGNOSTIC_CHARS)) } ?: CodexEvent.Ignored
                else CodexEvent.Ignored
            else -> CodexEvent.Ignored
        }
    }

    private fun parseUsage(usage: JSONObject?): CodexUsage = if (usage == null) CodexUsage() else CodexUsage(
        inputTokens = usage.optInt("input_tokens", 0),
        cachedInputTokens = usage.optInt("cached_input_tokens", 0),
        outputTokens = usage.optInt("output_tokens", 0),
        reasoningOutputTokens = usage.optInt("reasoning_output_tokens", 0),
    )

    /** `Reconnecting... 2/5 (...)` and `waiting for network` notices are retry progress, not failures. */
    internal fun isTransientNotice(message: String): Boolean =
        message.startsWith("Reconnecting", ignoreCase = true) || message.contains("waiting for network", ignoreCase = true)

    /** org.json returns the string "null" for JSON null; treat it as absent. */
    private fun JSONObject.text(key: String): String =
        if (isNull(key)) "" else optString(key, "")
}

/**
 * Splits captured output into lines on the newline byte, so a multi-byte UTF-8 character that
 * straddles two reads is never decoded half-way. A line longer than [maxLineBytes] is dropped
 * and reported once as [OVERSIZED], keeping memory bounded on huge tool output.
 */
internal class CodexLineAssembler(private val maxLineBytes: Int = 1 shl 20) {
    private val pending = java.io.ByteArrayOutputStream()
    private var skipping = false

    fun feed(bytes: ByteArray, count: Int): List<String> {
        val lines = ArrayList<String>()
        var start = 0
        for (index in 0 until count) {
            if (bytes[index] == NEWLINE) {
                append(bytes, start, index - start)
                finishLine()?.let(lines::add)
                start = index + 1
            }
        }
        if (start < count) append(bytes, start, count - start)
        return lines
    }

    /** Returns the trailing line that never received a newline, if any. */
    fun flush(): String? = if (pending.size() > 0 || skipping) finishLine() else null

    private fun append(bytes: ByteArray, offset: Int, length: Int) {
        if (skipping || length <= 0) return
        if (pending.size() + length > maxLineBytes) {
            skipping = true
            pending.reset()
            return
        }
        pending.write(bytes, offset, length)
    }

    private fun finishLine(): String? {
        if (skipping) {
            skipping = false
            pending.reset()
            return OVERSIZED
        }
        val line = String(pending.toByteArray(), Charsets.UTF_8).trimEnd('\r')
        pending.reset()
        return line.takeIf { it.isNotBlank() }
    }

    companion object {
        const val OVERSIZED = "\u0000codex-oversized-line"
        private const val NEWLINE = '\n'.code.toByte()
    }
}

/**
 * Turns [CodexEvent]s into app [RuntimeEvent]s. Session start, completion and failure are not
 * mapped here; the bridge emits those exactly once.
 */
internal class CodexEventMapper(private val sessionId: String, private val workspace: String = "") {
    private var reasoningBlock = 0L
    private var messageCount = 0
    private val itemTexts = mutableMapOf<String, String>()
    private val reasoningBlocks = mutableMapOf<String, Long>()
    private var parentThreadId: String? = null
    private val children = mutableMapOf<String, SubagentInfo>()
    private val commands = mutableMapOf<String, BackgroundTaskInfo>()

    fun map(event: CodexEvent): List<RuntimeEvent> = when (event) {
        is CodexEvent.AgentMessage -> {
            if (event.itemId.isBlank()) {
                val text = if (messageCount++ == 0) event.text else "\n\n${event.text}"
                listOf(RuntimeEvent.AssistantDelta(sessionId, text))
            } else {
                val previous = itemTexts[event.itemId].orEmpty()
                // JSONL updates are cumulative snapshots. Ignore rewrites rather than duplicate text.
                if (!event.text.startsWith(previous) || event.text == previous) emptyList()
                else {
                    check(itemTexts.size < 4096 || event.itemId in itemTexts) { "Too many Codex output items" }
                    itemTexts[event.itemId] = event.text
                    val prefix = if (previous.isEmpty() && messageCount++ > 0) "\n\n" else ""
                    listOf(RuntimeEvent.AssistantDelta(sessionId, prefix + event.text.removePrefix(previous)))
                }
            }
        }
        is CodexEvent.Reasoning -> {
            val key = event.itemId.ifBlank { "legacy-${reasoningBlock + 1}" }
            val previous = itemTexts["reasoning:$key"]
            val summary = event.text.replace(Regex("\\s+"), " ").trim().take(MAX_REASONING_CHARS)
            if (summary.isBlank() || previous == summary && !event.completed) emptyList()
            else {
                check(itemTexts.size < 4096 || "reasoning:$key" in itemTexts) { "Too many Codex output items" }
                val newBlock = key !in reasoningBlocks
                val block = reasoningBlocks.getOrPut(key) { ++reasoningBlock }
                itemTexts["reasoning:$key"] = summary
                listOf(RuntimeEvent.ReasoningSummary(sessionId, summary, block, newBlock, event.completed))
            }
        }
        is CodexEvent.CommandStarted -> buildList {
            add(RuntimeEvent.ToolStarted(sessionId, "Bash", displayCommand(event.command)))
            commandTask(event.itemId, event.command, BackgroundTaskStatus.RUNNING)?.let(::add)
        }
        is CodexEvent.CommandFinished -> buildList {
            add(RuntimeEvent.ToolCompleted(sessionId, "Bash", commandSummary(event), LocalPreviewDiscovery.candidate(event.output)))
            commandTask(event.itemId, event.command, if (event.failed) BackgroundTaskStatus.FAILED else BackgroundTaskStatus.COMPLETED,
                event.output, event.exitCode)?.let(::add)
        }
        is CodexEvent.FileChange -> if (event.paths.isEmpty()) emptyList() else {
            val detail = event.paths.joinToString(", ").take(MAX_DETAIL_CHARS)
            listOf(
                RuntimeEvent.ToolStarted(sessionId, "Edit", detail),
                RuntimeEvent.ToolCompleted(sessionId, "Edit", "Edited $detail"),
            )
        }
        is CodexEvent.ToolCall ->
            if (event.finished) {
                listOf(
                    RuntimeEvent.ToolCompleted(
                        sessionId,
                        event.name,
                        if (event.failed) "${event.name} failed" else "${event.name} completed",
                    ),
                )
            } else {
                listOf(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail.ifBlank { "Working in the project" }))
            }
        is CodexEvent.Collaboration -> {
            if (parentThreadId != null && event.senderThreadId != parentThreadId) emptyList()
            else buildList {
                val name = "Agent ${event.tool.replace('_', ' ')}"
                if (event.finished) add(RuntimeEvent.ToolCompleted(sessionId, name, if (event.failed) "$name failed" else "$name completed"))
                else add(RuntimeEvent.ToolStarted(sessionId, name, event.prompt))
                for (agent in event.agents) {
                    if (agent.threadId == parentThreadId) continue
                    val state = when (agent.status) {
                        "pending_init", "running" -> SubagentState.RUNNING
                        "completed" -> SubagentState.DONE
                        "errored" -> SubagentState.ERRORED
                        "interrupted", "shutdown", "not_found" -> SubagentState.TERMINATED
                        else -> continue
                    }
                    val old = children[agent.threadId]
                    if (old == null && children.size >= 256) continue
                    val child = (old ?: SubagentInfo(agent.threadId, "Subagent", "Codex", state)).copy(
                        state = state,
                        currentActivity = agent.message,
                        error = agent.message.takeIf { state == SubagentState.ERRORED },
                        finishedAtMillis = if (state.isTerminal) old?.finishedAtMillis ?: System.currentTimeMillis() else null,
                    )
                    children[agent.threadId] = child
                    add(RuntimeEvent.SubagentUpdated(sessionId, child))
                }
            }
        }
        is CodexEvent.TurnCompleted -> {
            val usage = event.usage
            if (usage.inputTokens > 0 || usage.outputTokens > 0) {
                listOf(
                    RuntimeEvent.TokenUsageUpdated(
                        sessionId,
                        SessionTokenMetrics(
                            promptTokens = usage.inputTokens,
                            completionTokens = usage.outputTokens,
                            cachedTokens = usage.cachedInputTokens,
                        ),
                    ),
                )
            } else emptyList()
        }
        is CodexEvent.ThreadStarted -> { parentThreadId = event.threadId; emptyList() }
        CodexEvent.TurnStarted,
        is CodexEvent.Notice,
        is CodexEvent.StreamError,
        is CodexEvent.TurnFailed,
        is CodexEvent.Diagnostic,
        CodexEvent.Ignored,
        -> emptyList()
    }

    private fun commandTask(id: String, command: String, status: BackgroundTaskStatus, output: String = "", exitCode: Int? = null): RuntimeEvent.TaskUpdated? {
        if (id.isBlank() || id !in commands && commands.size >= 256) return null
        val task = (commands[id] ?: BackgroundTaskInfo(id, displayCommand(command), workspace, status)).copy(
            status = status, exitCode = exitCode, liveOutputTail = output.takeLast(4_000),
        )
        commands[id] = task
        return RuntimeEvent.TaskUpdated(sessionId, task)
    }

    private fun commandSummary(event: CodexEvent.CommandFinished): String {
        val firstLine = event.output.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }
        return when {
            event.failed -> "Exit code ${event.exitCode ?: "unknown"}" + (firstLine?.let { ": $it" } ?: "")
            firstLine != null -> firstLine
            else -> "Command completed"
        }.take(MAX_SUMMARY_CHARS)
    }

    companion object {
        private const val MAX_REASONING_CHARS = 2_000
        private const val MAX_DETAIL_CHARS = 240
        private const val MAX_SUMMARY_CHARS = 180
        private val SHELL_WRAPPER = Regex("""^(?:/\S+/)?(?:bash|sh|zsh)\s+-l?c\s+(['"])([\s\S]*)\1$""")

        /** Shows `echo hi` instead of `/bin/bash -lc 'echo hi'`. */
        internal fun displayCommand(command: String): String {
            val inner = SHELL_WRAPPER.matchEntire(command.trim())?.groupValues?.get(2)?.replace("'\\''", "'")
            return (inner ?: command).replace(Regex("\\s+"), " ").trim().take(MAX_DETAIL_CHARS)
                .ifBlank { "Working in the project" }
        }
    }
}
