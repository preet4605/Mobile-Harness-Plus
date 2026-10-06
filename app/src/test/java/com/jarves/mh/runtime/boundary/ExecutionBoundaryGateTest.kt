package com.jarves.mh.runtime.boundary

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.runtime.DshRouteMapper
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.antigravityCommand
import com.jarves.mh.runtime.task.TaskExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExecutionBoundaryGateTest {

    private lateinit var gate: ExecutionBoundaryGate

    @Before
    fun setUp() {
        gate = ExecutionBoundaryGate.instance
        gate.clearAllForTesting()
    }

    // =========================================================================
    // Group A: Conversational Safety (Non-execution)
    // =========================================================================

    @Test
    fun conversationPhrasesAlwaysClassifiedAsConversation() {
        val conversationalPrompts = listOf(
            "now what?",
            "now what",
            "okay",
            "ok",
            "why?",
            "why did that happen?",
            "what happened?",
            "what should I do next?",
            "what next?",
            "what changed?",
            "hello",
            "hello there",
            "thanks!",
            "thank you",
            "sounds good",
            "can you explain how the supervisor works?",
            "can you explain the error?",
            "how do retries work?",
            "tell me about the architecture",
            "is it fixed?",
        )

        for (prompt in conversationalPrompts) {
            val context = BoundaryEvaluationContext()
            val decision = gate.evaluate(prompt, context)
            assertEquals(
                "Prompt '$prompt' MUST be classified as CONVERSATION",
                ExecutionMode.CONVERSATION,
                decision.mode,
            )
        }
    }

    @Test
    fun explicitNegationsAlwaysClassifiedAsConversation() {
        val negatedPrompts = listOf(
            "don't run anything, just explain",
            "do not run anything, explain what failed",
            "don't execute any commands, just review",
            "do not execute any tools",
            "please no execution, just tell me what happened",
            "without running, what would happen?",
            "do not build the apk, just check the syntax",
        )

        for (prompt in negatedPrompts) {
            val context = BoundaryEvaluationContext()
            val decision = gate.evaluate(prompt, context)
            assertEquals(
                "Negated prompt '$prompt' MUST be classified as CONVERSATION",
                ExecutionMode.CONVERSATION,
                decision.mode,
            )
        }
    }

    @Test
    fun emptyOrAmbiguousPromptsDefaultToConversation() {
        val ambiguous = listOf(
            "",
            "   ",
            "hmm",
            "well",
            "maybe",
            "???",
        )

        for (prompt in ambiguous) {
            val decision = gate.evaluate(prompt, BoundaryEvaluationContext())
            assertEquals(
                "Ambiguous prompt '$prompt' MUST default safely to CONVERSATION",
                ExecutionMode.CONVERSATION,
                decision.mode,
            )
        }
    }

    // =========================================================================
    // Group B: Explicit Execution
    // =========================================================================

    @Test
    fun explicitExecutionCommandsClassifiedAsExecution() {
        val executionPrompts = listOf(
            "run the unit tests",
            "run tests",
            "build the APK",
            "assembleOnlineDebug",
            "audit the code",
            "fix H9 and verify it",
            "git status and git diff",
            "compile the code",
            "remedy the failure and restart",
            "implement the requested feature",
            "test the boundary gate",
        )

        for (prompt in executionPrompts) {
            val context = BoundaryEvaluationContext()
            val decision = gate.evaluate(prompt, context)
            assertEquals(
                "Execution prompt '$prompt' MUST be classified as EXECUTION",
                ExecutionMode.EXECUTION,
                decision.mode,
            )
        }
    }

    @Test
    fun slashCommandAlwaysClassifiedAsExecution() {
        val context = BoundaryEvaluationContext(isSlashCommand = true)
        val decision = gate.evaluate("help", context)
        assertEquals(ExecutionMode.EXECUTION, decision.mode)
    }

    @Test
    fun questionsWithAttachmentsEvaluateSensibly() {
        // Informational question about an attachment -> CONVERSATION
        val infoDecision = gate.evaluate(
            "what does this file do?",
            BoundaryEvaluationContext(hasAttachments = true),
        )
        assertEquals(ExecutionMode.CONVERSATION, infoDecision.mode)

        // Imperative instruction on attachment -> EXECUTION
        val execDecision = gate.evaluate(
            "refactor this file and run the unit tests",
            BoundaryEvaluationContext(hasAttachments = true),
        )
        assertEquals(ExecutionMode.EXECUTION, execDecision.mode)
    }

    // =========================================================================
    // Group C: Terminal Task Authority Revocation & Follow-up Context
    // =========================================================================

    @Test
    fun conversationalFollowupAfterTerminalTaskDefaultsToConversation() {
        val terminalStatuses = listOf(
            TaskExecutionStatus.COMPLETED,
            TaskExecutionStatus.FAILED,
            TaskExecutionStatus.CANCELLED,
        )

        for (status in terminalStatuses) {
            val context = BoundaryEvaluationContext(
                lastTaskId = "prev-task-123",
                lastTaskStatus = status,
            )

            val decisionWhy = gate.evaluate("why did that happen?", context)
            assertEquals(
                "Followup 'why' after $status MUST be CONVERSATION",
                ExecutionMode.CONVERSATION,
                decisionWhy.mode,
            )

            val decisionNowWhat = gate.evaluate("now what?", context)
            assertEquals(
                "Followup 'now what?' after $status MUST be CONVERSATION",
                ExecutionMode.CONVERSATION,
                decisionNowWhat.mode,
            )

            val decisionWhatNext = gate.evaluate("what should I do next?", context)
            assertEquals(
                "Followup 'what next?' after $status MUST be CONVERSATION",
                ExecutionMode.CONVERSATION,
                decisionWhatNext.mode,
            )
        }
    }

    @Test
    fun explicitFollowupAfterTerminalTaskAcquiresFreshExecution() {
        val context = BoundaryEvaluationContext(
            lastTaskId = "prev-task-123",
            lastTaskStatus = TaskExecutionStatus.FAILED,
        )

        val decisionRerun = gate.evaluate("rerun the tests and fix the failure", context)
        assertEquals(
            "Explicit command after terminal task MUST be EXECUTION",
            ExecutionMode.EXECUTION,
            decisionRerun.mode,
        )
    }

    @Test
    fun terminalTaskAuthorityRevocationIsImmediateAndIrreversible() {
        val taskId = "task-revocation-test"
        val sessionId = "session-revocation-test"

        // Authorize execution
        gate.authorizeExecution(taskId)
        assertTrue(gate.isExecutionAuthorized(taskId))

        // Register session
        gate.registerSession(taskId, sessionId)
        assertTrue(gate.isToolExecutionAllowed(taskId, sessionId))
        assertTrue(gate.isEventPermitted(taskId, sessionId))

        // Revoke authority (simulating task finalization on terminal transition)
        gate.revokeExecutionAuthority(taskId)

        // Verify immediate revocation
        assertFalse("Task execution authority MUST be revoked", gate.isExecutionAuthorized(taskId))
        assertFalse("Tool execution MUST be disallowed after revocation", gate.isToolExecutionAllowed(taskId, sessionId))
        assertFalse("Events MUST be rejected after revocation", gate.isEventPermitted(taskId, sessionId))
    }

    // =========================================================================
    // Group D: Tool Security & Session Boundary Enforcement
    // =========================================================================

    @Test
    fun toolExecutionDisallowedWithoutAuthorization() {
        val unauthorizedTaskId = "unauthorized-task-456"
        val sessionId = "session-456"

        assertFalse(gate.isExecutionAuthorized(unauthorizedTaskId))
        assertFalse(gate.isToolExecutionAllowed(unauthorizedTaskId, sessionId))
    }

    @Test
    fun toolExecutionDisallowedForNullOrBlankIds() {
        assertFalse(gate.isToolExecutionAllowed(null, "session-1"))
        assertFalse(gate.isToolExecutionAllowed("", "session-1"))
        assertFalse(gate.isToolExecutionAllowed("task-1", null))
        assertFalse(gate.isToolExecutionAllowed("task-1", ""))
        assertFalse(gate.isToolExecutionAllowed(null, null))
    }

    @Test
    fun toolExecutionDisallowedForMismatchedSession() {
        val taskId = "task-match-test"
        val legitimateSessionId = "session-legit"
        val forgedSessionId = "session-forged"

        gate.authorizeExecution(taskId)
        gate.registerSession(taskId, legitimateSessionId)

        assertTrue(gate.isToolExecutionAllowed(taskId, legitimateSessionId))
        assertFalse("Mismatched session MUST NOT be allowed to execute tools", gate.isToolExecutionAllowed(taskId, forgedSessionId))

        gate.revokeExecutionAuthority(taskId)
    }

    @Test
    fun eventPermittedDisallowedForEmptyOrStaleSessions() {
        val taskId = "task-event-test"
        val sessionId = "session-event-test"

        assertFalse(gate.isEventPermitted(null, sessionId))
        assertFalse(gate.isEventPermitted("", sessionId))
        assertFalse(gate.isEventPermitted(taskId, null))
        assertFalse(gate.isEventPermitted(taskId, ""))

        gate.authorizeExecution(taskId)
        gate.registerSession(taskId, sessionId)
        assertTrue(gate.isEventPermitted(taskId, sessionId))

        // Mismatched session
        assertFalse(gate.isEventPermitted(taskId, "different-session"))

        gate.revokeExecutionAuthority(taskId)
        assertFalse(gate.isEventPermitted(taskId, sessionId))
    }

    // =========================================================================
    // Group E: Harness Matrix Verification (Claude, DSH, Antigravity)
    // =========================================================================

    @Test
    fun dshHarnessPermissionModeIsReadOnlyByDefaultAndDangerOnlyWhenAuthorized() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))

        // Unauthorized context -> MUST be read-only
        val envUnauthorized = DshRuntimeBridge.buildDshEnvironment(
            route = route,
            secret = "test-secret",
            isExecutionAuthorized = false,
        )
        assertEquals("read-only", envUnauthorized["DSH_PERMISSION_MODE"])

        // Authorized context -> danger-full-access
        val envAuthorized = DshRuntimeBridge.buildDshEnvironment(
            route = route,
            secret = "test-secret",
            isExecutionAuthorized = true,
        )
        assertEquals("danger-full-access", envAuthorized["DSH_PERMISSION_MODE"])
    }

    @Test
    fun antigravityHarnessDangerouslySkipPermissionsOnlyIncludedWhenAuthorized() {
        // Unauthorized context -> MUST NOT contain --dangerously-skip-permissions
        val cmdUnauthorized = antigravityCommand(
            model = "gemini-model",
            effort = "high",
            conversationId = "conv-1",
            isExecutionAuthorized = false,
        )
        assertFalse(
            "Unauthorized AGY command MUST NOT contain --dangerously-skip-permissions",
            cmdUnauthorized.contains("--dangerously-skip-permissions"),
        )

        // Authorized context -> MUST contain --dangerously-skip-permissions
        val cmdAuthorized = antigravityCommand(
            model = "gemini-model",
            effort = "high",
            conversationId = "conv-1",
            isExecutionAuthorized = true,
        )
        assertTrue(
            "Authorized AGY command MUST contain --dangerously-skip-permissions",
            cmdAuthorized.contains("--dangerously-skip-permissions"),
        )
    }

    @Test
    fun conversationModeNeverReceivesExecutionAuthorityOrToolAllowance() {
        val prompt = "now what?"
        val context = BoundaryEvaluationContext()
        val decision = gate.evaluate(prompt, context)

        assertEquals(ExecutionMode.CONVERSATION, decision.mode)

        // In conversation mode, no task ID is authorized
        val hypotheticalTaskId = "conversational-turn-task"
        assertFalse(gate.isExecutionAuthorized(hypotheticalTaskId))
        assertFalse(gate.isToolExecutionAllowed(hypotheticalTaskId, "session-conv"))
    }
}
