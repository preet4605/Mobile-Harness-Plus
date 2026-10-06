package com.jarves.mh.runtime.boundary

import android.util.Log
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class ExecutionMode {
    CONVERSATION,
    EXECUTION,
}

enum class ExecutionUiStatus {
    IDLE,
    CONVERSATIONAL_TURN,
    ACTIVE_EXECUTION,
    COMPLETED_EXECUTION,
    FAILED_EXECUTION,
    CANCELLED_EXECUTION,
}

data class ExecutionContext(
    val taskId: String,
    val sessionId: String,
    val executionMode: ExecutionMode,
) {
    val isExecution: Boolean get() = executionMode == ExecutionMode.EXECUTION
}

data class BoundaryDecision(
    val mode: ExecutionMode,
    val reason: String,
    val requiresFreshAuthorization: Boolean = false,
) {
    val isExecution: Boolean get() = mode == ExecutionMode.EXECUTION
    val isConversation: Boolean get() = mode == ExecutionMode.CONVERSATION
}

data class BoundaryEvaluationContext(
    val history: List<ChatMessage> = emptyList(),
    val lastTaskId: String? = null,
    val lastTaskStatus: TaskExecutionStatus? = null,
    val activeTask: DurableTaskRecord? = null,
    val hasAttachments: Boolean = false,
    val isSlashCommand: Boolean = false,
    val explicitAuthorization: Boolean = false,
)

/**
 * Authoritative system execution boundary gate.
 * Strictly separates conversational turns from autonomous execution.
 * Enforces fail-safe classification, prevents execution resource allocation
 * during conversation mode, and invalidates authority on terminal task transitions.
 */
class ExecutionBoundaryGate {

    private val activeExecutionContext = AtomicReference<ExecutionContext?>(null)
    private val authorizedExecutionTasks = ConcurrentHashMap<String, ExecutionContext>()
    private val terminalTaskIds = ConcurrentHashMap.newKeySet<String>()
    private val pendingToolRequests = ConcurrentHashMap<String, ToolRequest>()

    /**
     * Evaluates a user message and conversational context to produce an authoritative
     * boundary decision (CONVERSATION vs EXECUTION).
     *
     * Enforces fail-safe classification:
     * - Pure conversational greetings, questions, acknowledgments remain CONVERSATION.
     * - Explicit non-execution directives ("don't run anything", "just explain") remain CONVERSATION.
     * - Ambiguous messages default to CONVERSATION.
     * - Completed/failed/cancelled tasks lose authority; follow-ups default to CONVERSATION
     *   unless new explicit execution intent is present.
     * - Only clear, imperative execution directives become EXECUTION.
     */
    fun evaluate(prompt: String, context: BoundaryEvaluationContext = BoundaryEvaluationContext()): BoundaryDecision {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) {
            return BoundaryDecision(ExecutionMode.CONVERSATION, "Empty prompt treated as conversation")
        }

        val lower = trimmed.lowercase()

        // 1. Explicit non-execution constraints (negations) override any action keywords
        if (isExplicitNonExecutionDirective(lower)) {
            return BoundaryDecision(
                ExecutionMode.CONVERSATION,
                "User requested explanation or analysis without workspace execution",
            )
        }

        // 2. Pure conversational acknowledgments, greetings, or conversational closures
        if (isConversationalGreetingOrAck(lower)) {
            return BoundaryDecision(
                ExecutionMode.CONVERSATION,
                "Conversational acknowledgment or greeting",
            )
        }

        // 3. Informational queries, explanations, and status inquiries
        if (isInformationalOrStatusQuery(lower)) {
            return BoundaryDecision(
                ExecutionMode.CONVERSATION,
                "Informational inquiry or status request without execution directive",
            )
        }

        // 4. Terminal task context: historical task context does NOT grant execution authority
        val isAfterTerminalTask = context.lastTaskStatus?.isTerminal == true ||
            (context.lastTaskId != null && context.activeTask == null)

        // 5. Explicit imperative execution directives
        if (isExplicitExecutionDirective(lower) || context.isSlashCommand || context.explicitAuthorization) {
            return BoundaryDecision(
                ExecutionMode.EXECUTION,
                "Explicit execution intent detected",
                requiresFreshAuthorization = isAfterTerminalTask,
            )
        }

        // 6. Fail safe: Ambiguous requests default to CONVERSATION without execution
        return BoundaryDecision(
            ExecutionMode.CONVERSATION,
            "Ambiguous intent resolved safely to conversation mode",
        )
    }

    /**
     * Authorizes execution for a specific taskId.
     * Only callable when an EXECUTION decision was reached.
     */
    fun authorizeExecution(taskId: String, sessionId: String = UUID.randomUUID().toString()): ExecutionContext {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val context = ExecutionContext(taskId, sessionId, ExecutionMode.EXECUTION)
        terminalTaskIds.remove(taskId)
        authorizedExecutionTasks[taskId] = context
        activeExecutionContext.set(context)
        runCatching { Log.i(TAG, "Authorized execution for task $taskId / session $sessionId") }
        return context
    }

    /**
     * Associates or updates the runtime sessionId for an already-authorized task.
     */
    fun registerSession(taskId: String, sessionId: String) {
        if (taskId.isBlank() || sessionId.isBlank()) return
        val existing = authorizedExecutionTasks[taskId]
        if (existing != null && !terminalTaskIds.contains(taskId)) {
            val updated = existing.copy(sessionId = sessionId)
            authorizedExecutionTasks[taskId] = updated
            activeExecutionContext.set(updated)
            runCatching { Log.d(TAG, "Registered session $sessionId for task $taskId") }
        }
    }

    /**
     * Revokes execution authority for a task upon reaching a terminal state
     * (COMPLETED, FAILED, CANCELLED, STOPPED).
     * Invariant: No stale capability survives a task boundary.
     */
    fun revokeExecutionAuthority(taskId: String, reason: String = "Terminal task transition") {
        if (taskId.isBlank()) return
        terminalTaskIds.add(taskId)
        authorizedExecutionTasks.remove(taskId)
        val active = activeExecutionContext.get()
        if (active?.taskId == taskId) {
            activeExecutionContext.set(null)
        }
        // Invalidate all pending tool requests belonging to this task
        val toRemove = pendingToolRequests.values.filter { it.sessionId == active?.sessionId }
        toRemove.forEach { req ->
            pendingToolRequests.remove(req.approvalId)
        }
        runCatching { Log.i(TAG, "Revoked execution authority for task $taskId ($reason). Cleared ${toRemove.size} pending tool requests.") }
    }

    /**
     * Verifies if execution is currently authorized for the given taskId and sessionId.
     * Enforces that identity matches and neither task nor session is terminal or revoked.
     */
    fun isExecutionAuthorized(taskId: String?, sessionId: String? = null): Boolean {
        if (taskId.isNullOrBlank()) return false
        if (terminalTaskIds.contains(taskId)) return false
        val context = authorizedExecutionTasks[taskId] ?: return false
        if (context.executionMode != ExecutionMode.EXECUTION) return false
        if (!sessionId.isNullOrBlank() && context.sessionId != sessionId) return false
        return true
    }

    /**
     * Verifies if tool execution is authorized for a specific tool call.
     * Strict guarantee: In conversation mode or after terminal transition, tool requests are rejected.
     */
    fun isToolExecutionAllowed(taskId: String?, sessionId: String?, toolName: String = ""): Boolean {
        if (!isExecutionAuthorized(taskId, sessionId)) {
            runCatching { Log.w(TAG, "Tool execution denied for '$toolName': task $taskId / session $sessionId is not authorized for execution") }
            return false
        }
        return true
    }

    /**
     * Validates whether a runtime event is permitted to update execution state.
     * Rejects events from empty/stale/terminal sessions without wildcard matching.
     */
    fun isEventPermitted(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        val active = activeExecutionContext.get() ?: return false
        if (active.sessionId != sessionId) return false
        if (terminalTaskIds.contains(active.taskId)) return false
        return true
    }

    fun isEventPermitted(taskId: String?, sessionId: String?): Boolean {
        if (taskId.isNullOrBlank() || sessionId.isNullOrBlank()) return false
        if (!isExecutionAuthorized(taskId, sessionId)) return false
        return isEventPermitted(sessionId)
    }

    fun getActiveExecutionContext(): ExecutionContext? = activeExecutionContext.get()

    fun recordPendingToolRequest(request: ToolRequest) {
        pendingToolRequests[request.approvalId] = request
    }

    fun removePendingToolRequest(approvalId: String): ToolRequest? = pendingToolRequests.remove(approvalId)

    fun clearAllForTesting() {
        activeExecutionContext.set(null)
        authorizedExecutionTasks.clear()
        terminalTaskIds.clear()
        pendingToolRequests.clear()
    }

    companion object {
        private const val TAG = "ExecutionBoundaryGate"
        val instance = ExecutionBoundaryGate()

        private val NON_EXECUTION_PATTERNS = listOf(
            Regex("""\b(don'?t|do not|never|stop|without)\s+(run|execute|build|modify|touch|change|start)\b"""),
            Regex("""\b(just|only)\s+(explain|tell|show|describe|answer|clarify)\b"""),
            Regex("""\btell\s+me\s+without\s+(running|executing)\b"""),
            Regex("""\b(dry\s*run|no\s+execution)\b"""),
            Regex("""^explain\s+that\??$"""),
            Regex("""^explain\s+(why|how|what|the failure|the error|this)\b"""),
            Regex("""^help\s+me\s+understand\b"""),
        )

        private val GREETING_OR_ACK_WORDS = setOf(
            "ok", "okay", "thanks", "thank you", "thx", "got it", "cool",
            "understood", "yes", "no", "sure", "hello", "hi", "hey",
            "bye", "goodbye", "great", "awesome", "nice", "sounds good",
            "alright", "all right", "yep", "nope",
        )

        private val STATUS_QUERY_EXACT = setOf(
            "now what", "now what?", "what now", "what now?",
            "what next", "what next?", "what should i do next", "what should i do next?",
            "what should we do next", "what should we do next?", "what do we do next", "what do we do next?",
            "why", "why?", "why did that happen", "why did that happen?",
            "why did it fail", "why did it fail?", "why did this happen", "why did this happen?",
            "what happened", "what happened?", "what failed", "what failed?",
            "tell me what failed", "tell me what failed.", "show me the result", "show me the result.",
            "what changed", "what changed?", "is it fixed", "is it fixed?",
            "is that fixed", "is that fixed?", "is it working", "is it working?",
            "is it done", "is it done?", "is it ready", "is it ready?",
            "how did that happen", "how did that happen?", "how does that work", "how does that work?",
        )

        private val EXECUTION_ACTION_PATTERNS = listOf(
            // Run / execute tests, verification, scripts, commands, audits
            Regex("""\b(run|rerun|execute)\s+(the\s+)?((unit\s+)?tests?|verification|test\s+suite|apk|build|script|command|requested\s+audit)\b"""),
            // Build / compile / assemble APK or project or code
            Regex("""\b(build|compile|assemble)\s+(the\s+)?(online\s+debug\s+)?(apk|binary|app|project|code)\b"""),
            Regex("""\b(assembleonlinedebug|assembledebug|assemblerelease)\b"""),
            Regex("""\b\./gradlew\b"""),
            // Fix / repair / patch / remedy issues, bugs, failures, test failures
            Regex("""\b(fix|repair|patch|resolve|remedy)\s+(the\s+)?(h\d+|issue|bug|failure|error|defect|problem|crash|tests?)\b"""),
            // Audit / inspect / investigate / forensic verification
            // Match explicit autonomous-work intent without requiring a narrow object vocabulary.
            Regex("""\b(audit|inspect|investigate|examine|trace)\s+(the\s+)?(repository|repo|codebase|code|implementation|execution\s+boundary|execution\s+paths?|implementation|workspace)\b"""),
            Regex("""\b(perform|conduct|run)\s+(a\s+)?(forensic\s+)?(audit|verification|review|investigation)\b"""),
            Regex("""\b(forensic\s+verification|forensic\s+audit)\b"""),
            Regex("""\bdetermine\s+whether\b.*\b(implementation|code|boundary|execution|tests?)\b"""),
            Regex("""\bverify\s+(that|whether)\b.*\b(implementation|code|boundary|execution|tests?)\b"""),
            Regex("""\btrace\s+(every|all|the)\s+.*\b(execution|entry\s+points?|runtime|authorization|session)\b"""),
            // Git operations
            Regex("""\bgit\s+(status|diff|log|add|commit|push|pull|branch|checkout)\b"""),
            // Imperative test / verify commands
            Regex("""\b(test|verify)\s+(the\s+)?(boundary(\s+gate)?|fix|changes|everything|feature)\b"""),
            // Implement / add / create specific components or features
            Regex("""\b(implement|create|develop)\s+(the\s+)?(requested\s+)?(feature|module|boundary|execution\s+boundary|component|changes?)\b"""),
            // Apply fix/patch and verify
            Regex("""\b(apply|commit)\s+(the\s+)?(fix|patch|changes)(\s+and\s+verify(\s+it)?)?\b"""),
            // Specific imperative instructions
            Regex("""\binspect\s+the\s+repository\s+and\s+find\s+the\s+bug\b"""),
            Regex("""\bfix\s+the\s+failure\s+and\s+rerun\s+verification\b"""),
            Regex("""\bremedy\s+the\s+failure\s+and\s+restart\b"""),
        )

        private fun isExplicitNonExecutionDirective(lower: String): Boolean {
            return NON_EXECUTION_PATTERNS.any { it.containsMatchIn(lower) }
        }

        private fun isConversationalGreetingOrAck(lower: String): Boolean {
            val clean = lower.trimEnd('!', '?', '.', ',', ' ').trim()
            return GREETING_OR_ACK_WORDS.contains(clean)
        }

        private fun isInformationalOrStatusQuery(lower: String): Boolean {
            val clean = lower.trim()
            if (STATUS_QUERY_EXACT.contains(clean) || STATUS_QUERY_EXACT.contains(clean.trimEnd('?'))) {
                return true
            }
            // General query prefixes that are informational questions rather than commands
            val questionPrefixes = listOf(
                "why did ", "why does ", "why is ", "why was ", "why are ",
                "what did ", "what is ", "what was ", "what are ",
                "tell me about ", "tell me why ", "tell me what ",
                "how does ", "how do i ", "how to ",
                "can you explain ", "could you explain ", "would you explain ",
                "show me ",
            )
            val isQuestion = questionPrefixes.any { clean.startsWith(it) }
            val hasQuestionMark = clean.endsWith("?")
            if (isQuestion) {
                // If it is asking a question and does not contain an explicit execution command, it's informational
                val hasImperativeExecution = EXECUTION_ACTION_PATTERNS.any { it.containsMatchIn(clean) }
                if (!hasImperativeExecution) return true
            }
            return false
        }

        private fun isExplicitExecutionDirective(lower: String): Boolean {
            return EXECUTION_ACTION_PATTERNS.any { it.containsMatchIn(lower) }
        }
    }
}
