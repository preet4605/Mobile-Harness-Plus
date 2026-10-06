package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
import com.jarves.mh.ui.FallbackDecisionHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 4-B Resilience Regression Test Suite:
 * - F-02: Stop during retry window leaves no running replacement session; repeated Stop idempotent.
 * - F-02: ProcessSupervisor.register does not clear pending cancel and immediately terminates process.
 * - F-02: Pre-execution terminal check: No session start for a task already FAILED or CANCELLED.
 * - F-01: Single retry owner: permanent auth failure with fallback rotates and attempts under TaskSupervisor.
 * - F-01: Single retry owner: permanent auth failure without fallback finalizes FAILED without second runner.
 * - N-02: classifyError decides USER_CANCELLED only when isCancelled is true; remote "cancelled" text != USER_CANCELLED.
 * - N-03: classifyError with typed network markers and no false bare "500" / "503" matches.
 * - F-05: Setup failure surfaces as failed attempt with real reason.
 * - FallbackDecisionHelper pure decision logic across lifecycle states.
 */
class Phase4BResilienceRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: BrainDatabase
    private lateinit var canonicalRepo: CanonicalTaskRepository
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var supervisor: TaskSupervisor
    private lateinit var workspaceDir: File
    private lateinit var checkpointsDir: File
    private lateinit var checkpoints: WorkspaceCheckpoints

    @Before
    fun setUp() {
        ControlledBrainInjector.clearAll()
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        canonicalRepo = CanonicalTaskRepository(db)
        knowledgeRepo = BrainKnowledgeRepository(db)
        supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        supervisor.brainContextAssembler = BrainContextAssembler(knowledgeRepo)

        workspaceDir = tempFolder.newFolder("workspace")
        checkpointsDir = tempFolder.newFolder("checkpoints")
        checkpoints = WorkspaceCheckpoints(checkpointsDir)

        supervisor.workspaceDirectoryResolver = { workspaceDir }
        supervisor.checkpointsResolver = { checkpoints }
    }

    @After
    fun tearDown() {
        ControlledBrainInjector.clearAll()
    }

    private fun createTestPlan(taskId: String): ExecutionPlan {
        val step = ExecutionStep(
            stepId = "$taskId-step-0",
            stepOrder = 0,
            title = "Test Execution Step",
            description = "Supervised execution step",
            verificationCommand = "echo ok",
            status = StepStatus.PENDING
        )
        return ExecutionPlan(
            planId = "plan-$taskId",
            taskId = taskId,
            steps = listOf(step),
            currentStepIndex = 0
        )
    }

    @Test
    fun `test stop during retry window leaves no running replacement session and is idempotent`() = runBlocking {
        val taskId = "task-stop-during-retry"
        val plan = createTestPlan(taskId)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-retry-stop",
            projectSlug = "slug-retry-stop",
            chatId = "c1",
            agentKind = "CLAUDE_CODE",
            providerJson = "{}",
            prompt = "Perform work with retry",
            maxRetries = 2,
            plan = plan
        )

        val attemptCount = AtomicInteger(0)
        val firstAttemptFailed = CompletableDeferred<Unit>()

        val job = supervisor.executeTask(taskId) { task ->
            val attempt = attemptCount.getAndIncrement()
            if (attempt == 0) {
                firstAttemptFailed.complete(Unit)
                throw IllegalStateException("HTTP 503 UNAVAILABLE: backend overloaded")
            }
        }

        // Wait for attempt 0 to fail
        firstAttemptFailed.await()

        // Stop task during retry window / backoff
        supervisor.requestStop(taskId)
        // Repeat stop to verify idempotency
        supervisor.requestStop(taskId)
        supervisor.requestStop(taskId)

        job.join()

        // Verify task state is CANCELLED and never progressed to attempt 1
        val record = supervisor.stateStore.get(taskId)
        assertNotNull(record)
        assertEquals(TaskExecutionStatus.CANCELLED, record!!.status)
        assertTrue(record.status.isTerminal)
        assertEquals("Attempt count must be exactly 1; retry must not execute after stop", 1, attemptCount.get())
        assertTrue("Cancellation flag must remain active", supervisor.isCancellationActive(taskId))
    }

    @Test
    fun `test no session start for task already FAILED or CANCELLED`() = runBlocking {
        val failedTaskId = "task-already-failed"
        val failedPlan = createTestPlan(failedTaskId)
        supervisor.createTask(
            taskId = failedTaskId,
            projectId = "proj-failed",
            projectSlug = "slug-failed",
            chatId = "c1",
            agentKind = "CLAUDE_CODE",
            providerJson = "{}",
            prompt = "Do not run",
            plan = failedPlan
        )
        supervisor.finalizeTask(failedTaskId, TaskExecutionStatus.FAILED, error = "Previously failed")

        val failedRunCount = AtomicInteger(0)
        val jobFailed = supervisor.executeTask(failedTaskId) {
            failedRunCount.incrementAndGet()
        }
        jobFailed.join()
        assertEquals("Execution block must never run for already-FAILED task", 0, failedRunCount.get())

        val cancelledTaskId = "task-already-cancelled"
        val cancelledPlan = createTestPlan(cancelledTaskId)
        supervisor.createTask(
            taskId = cancelledTaskId,
            projectId = "proj-cancelled",
            projectSlug = "slug-cancelled",
            chatId = "c1",
            agentKind = "CLAUDE_CODE",
            providerJson = "{}",
            prompt = "Do not run cancelled",
            plan = cancelledPlan
        )
        supervisor.finalizeTask(cancelledTaskId, TaskExecutionStatus.CANCELLED, error = "Previously cancelled")

        val cancelledRunCount = AtomicInteger(0)
        val jobCancelled = supervisor.executeTask(cancelledTaskId) {
            cancelledRunCount.incrementAndGet()
        }
        jobCancelled.join()
        assertEquals("Execution block must never run for already-CANCELLED task", 0, cancelledRunCount.get())
    }

    @Test
    fun `test single retry owner - permanent auth failure with fallback rotates and attempts under TaskSupervisor`() = runBlocking {
        val taskId = "task-single-owner-fallback"
        val plan = createTestPlan(taskId)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-fallback",
            projectSlug = "slug-fallback",
            chatId = "c1",
            agentKind = "CLAUDE_CODE",
            providerJson = "{}",
            prompt = "Perform work requiring auth fallback",
            maxRetries = 1,
            plan = plan
        )

        var fallbackDeciderCalled = false
        supervisor.registerFallbackDecider(taskId, object : TaskSupervisor.TaskFallbackDecider {
            override fun decideFallback(failedTaskId: String, errorMessage: String): Boolean {
                fallbackDeciderCalled = true
                return true // Fallback applied, rotate key
            }
        })

        val attemptCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { task ->
            val attempt = attemptCount.getAndIncrement()
            if (attempt == 0) {
                throw IllegalStateException("No API key is saved for ANTHROPIC.")
            }
            // Attempt 1 succeeds
        }
        job.join()

        assertTrue("Fallback decider must have been consulted by TaskSupervisor", fallbackDeciderCalled)
        assertEquals("Must execute attempt 0 and attempt 1 under TaskSupervisor", 2, attemptCount.get())
        val record = supervisor.stateStore.get(taskId)
        assertNotNull(record)
        assertEquals(TaskExecutionStatus.COMPLETED, record!!.status)
    }

    @Test
    fun `test single retry owner - permanent auth failure without fallback finalizes FAILED without second runner`() = runBlocking {
        val taskId = "task-single-owner-no-fallback"
        val plan = createTestPlan(taskId)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-no-fallback",
            projectSlug = "slug-no-fallback",
            chatId = "c1",
            agentKind = "CLAUDE_CODE",
            providerJson = "{}",
            prompt = "Perform work failing auth permanently",
            maxRetries = 1,
            plan = plan
        )

        var fallbackDeciderCalled = false
        supervisor.registerFallbackDecider(taskId, object : TaskSupervisor.TaskFallbackDecider {
            override fun decideFallback(failedTaskId: String, errorMessage: String): Boolean {
                fallbackDeciderCalled = true
                return false // No fallback available
            }
        })

        val attemptCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { task ->
            attemptCount.incrementAndGet()
            throw IllegalStateException("HTTP 401 Unauthorized: Invalid API key")
        }
        job.join()

        assertTrue("Fallback decider must have been consulted", fallbackDeciderCalled)
        assertEquals("Must halt after attempt 0 without retrying", 1, attemptCount.get())
        val record = supervisor.stateStore.get(taskId)
        assertNotNull(record)
        assertEquals(TaskExecutionStatus.FAILED, record!!.status)
        assertTrue("Error message must be preserved", record.lastError?.contains("HTTP 401") == true)
    }

    @Test
    fun `test classifyError decides USER_CANCELLED only when isCancelled is true`() {
        // String contains "cancelled", but isCancelled = false: must NOT be USER_CANCELLED
        val remoteCancelMsg = "Upstream server cancelled stream connection"
        val classificationRemote = supervisor.classifyError(
            errorMsg = remoteCancelMsg,
            workspaceMutated = false,
            isCancelled = false
        )
        assertNotEquals(
            "Remote cancelled message must not classify as USER_CANCELLED when isCancelled = false",
            TaskSupervisor.TaskErrorClassification.USER_CANCELLED,
            classificationRemote
        )

        val peerCancelMsg = "Context deadline exceeded: call was cancelled by remote peer"
        val classificationPeer = supervisor.classifyError(
            errorMsg = peerCancelMsg,
            workspaceMutated = false,
            isCancelled = false
        )
        assertNotEquals(
            "Peer cancelled message must not classify as USER_CANCELLED when isCancelled = false",
            TaskSupervisor.TaskErrorClassification.USER_CANCELLED,
            classificationPeer
        )

        // When isCancelled = true: ALWAYS USER_CANCELLED
        val userCancelledResult = supervisor.classifyError(
            errorMsg = "Any message or network reset",
            workspaceMutated = false,
            isCancelled = true
        )
        assertEquals(
            TaskSupervisor.TaskErrorClassification.USER_CANCELLED,
            userCancelledResult
        )

        // When isCancelled = false, classifyError does not classify as USER_CANCELLED even for string text
        // (N-02 strictly mandates: classifyError decides USER_CANCELLED based on cancellation flag only)
        val stoppedByUserWithoutFlag = supervisor.classifyError(
            errorMsg = "Stopped by user",
            workspaceMutated = false,
            isCancelled = false
        )
        assertEquals(
            TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE,
            stoppedByUserWithoutFlag
        )
    }

    @Test
    fun `test classifyError with typed network markers and no false bare 500 or 503 matches`() {
        // Bare numbers in error text must NOT trigger TRANSIENT_API_ERROR
        val bare500 = supervisor.classifyError(errorMsg = "Processed 500 lines of code without EOF", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE, bare500)

        val bare503 = supervisor.classifyError(errorMsg = "Found 503 test cases in test suite", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE, bare503)

        // Real contextual HTTP codes
        val http500 = supervisor.classifyError(errorMsg = "HTTP 500 Internal Server Error", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, http500)

        val status503 = supervisor.classifyError(errorMsg = "API returned status: 503 Service Unavailable", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, status503)

        // Typed socket and network reset markers
        val econnreset = supervisor.classifyError(errorMsg = "read ECONNRESET", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, econnreset)

        val connReset = supervisor.classifyError(errorMsg = "connection reset by peer", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, connReset)

        val unknownHost = supervisor.classifyError(errorMsg = "java.net.UnknownHostException: api.anthropic.com", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, unknownHost)

        val unreachable = supervisor.classifyError(errorMsg = "Network error: remote host unreachable", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, unreachable)
    }

    @Test
    fun `test setup failure surfaces as failed attempt with real reason`() = runBlocking {
        val taskId = "task-setup-failure"
        val plan = createTestPlan(taskId)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-setup-fail",
            projectSlug = "slug-setup-fail",
            chatId = "c1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run antigravity agent",
            maxRetries = 0,
            plan = plan
        )

        val setupError = "Google account not signed in. Sign in under Antigravity settings to use Antigravity models."
        val job = supervisor.executeTask(taskId) {
            throw IllegalStateException(setupError)
        }
        job.join()

        val record = supervisor.stateStore.get(taskId)
        assertNotNull(record)
        assertEquals(TaskExecutionStatus.FAILED, record!!.status)
        assertTrue("Real setup error reason must be surfaced in lastError", record.lastError?.contains(setupError) == true)
    }

    @Test
    fun `test FallbackDecisionHelper branches`() {
        // ANTIGRAVITY never falls back via VM profile fallback
        assertFalse(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.ANTIGRAVITY,
                isRunning = true,
                isStopping = false,
                isCancelled = false,
                isTerminal = false,
                hasEligibleFallback = true
            )
        )

        // Not running
        assertFalse(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.CLAUDE_CODE,
                isRunning = false,
                isStopping = false,
                isCancelled = false,
                isTerminal = false,
                hasEligibleFallback = true
            )
        )

        // Stopping
        assertFalse(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.CLAUDE_CODE,
                isRunning = true,
                isStopping = true,
                isCancelled = false,
                isTerminal = false,
                hasEligibleFallback = true
            )
        )

        // Cancelled
        assertFalse(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.CLAUDE_CODE,
                isRunning = true,
                isStopping = false,
                isCancelled = true,
                isTerminal = false,
                hasEligibleFallback = true
            )
        )

        // Terminal
        assertFalse(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.CLAUDE_CODE,
                isRunning = true,
                isStopping = false,
                isCancelled = false,
                isTerminal = true,
                hasEligibleFallback = true
            )
        )

        // No eligible fallback
        assertFalse(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.CLAUDE_CODE,
                isRunning = true,
                isStopping = false,
                isCancelled = false,
                isTerminal = false,
                hasEligibleFallback = false
            )
        )

        // Eligible fallback with CLAUDE_CODE
        assertTrue(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.CLAUDE_CODE,
                isRunning = true,
                isStopping = false,
                isCancelled = false,
                isTerminal = false,
                hasEligibleFallback = true
            )
        )

        // Eligible fallback with DEEPSEEK_HARNESS
        assertTrue(
            FallbackDecisionHelper.shouldAttemptFallback(
                agentKind = AgentKind.DEEPSEEK_HARNESS,
                isRunning = true,
                isStopping = false,
                isCancelled = false,
                isTerminal = false,
                hasEligibleFallback = true
            )
        )
    }
}
