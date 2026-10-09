package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.BrainLearningService
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionOutcome
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verification test suite for Phase 6 Precondition 5:
 * "Deterministic Self-Recovery Engine".
 *
 * Verifies that supervised step failures trigger deterministic, bounded recovery:
 * 1. Transient failure -> bounded retry
 * 2. Verification failure -> deterministic handling
 * 3. Checkpoint restore
 * 4. Wrong checkpoint rejected
 * 5. Cancellation stops recovery
 * 6. Permanent failure does not retry
 * 7. Recovery attempt persistence
 * 8. Maximum retry bound
 * 9. Retry followed by verification
 * 10. Failed recovery becomes terminal
 * 11. Duplicate recovery invocation (idempotency)
 * 12. Agent cannot select recovery strategy
 * 13. Brain learning regression
 * 14. Step state regression
 */
class DeterministicSelfRecoveryEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: BrainDatabase
    private lateinit var canonicalRepo: CanonicalTaskRepository
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var learningService: BrainLearningService
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
        learningService = BrainLearningService(knowledgeRepo)
        supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        supervisor.brainContextAssembler = BrainContextAssembler(knowledgeRepo)
        supervisor.brainLearningService = learningService

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

    // 1. Transient failure -> bounded retry
    @Test
    fun test1_transientFailure_boundedRetry() = runBlocking {
        val taskId = "test-transient-retry"
        val expectedFile = "service_result.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Transient Step",
            description = "Fails transiently on attempt 1, succeeds on attempt 2",
            expectedFiles = listOf(expectedFile),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-1",
            projectSlug = "slug-1",
            chatId = "c1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Do transient work",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("HTTP 503 Service Unavailable")
            } else {
                File(workspaceDir, expectedFile).writeText("recovered on attempt 2")
            }
        }
        job.join()

        assertEquals("Transient error must trigger step retry", 2, runCount.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(2, canonical.plan.steps[0].attempts)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 2. Verification failure -> deterministic handling (restores dirty state and retries if recoverable)
    @Test
    fun test2_verificationFailure_deterministicHandling() = runBlocking {
        val taskId = "test-verify-handling"
        val targetFile = "output.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Verification Handling Step",
            description = "Attempt 1 writes bad data and fails verification; attempt 2 writes valid data",
            expectedContent = mapOf(targetFile to "VALID_PAYLOAD"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-2",
            projectSlug = "slug-2",
            chatId = "c2",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Verify handling test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                // Mutate file with invalid content -> fails verification
                File(workspaceDir, targetFile).writeText("CORRUPTED_PAYLOAD")
            } else {
                File(workspaceDir, targetFile).writeText("VALID_PAYLOAD")
            }
        }
        job.join()

        assertEquals(2, runCount.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(2, canonical.plan.steps[0].attempts)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 3. Checkpoint restore (reverts mutated workspace back to pre-step snapshot)
    @Test
    fun test3_checkpointRestore() = runBlocking {
        val taskId = "test-checkpoint-restore"
        val originalFile = File(workspaceDir, "baseline.txt").apply { writeText("original baseline") }
        val modifiedFile = File(workspaceDir, "mutated.txt")

        val step = ExecutionStep(
            stepOrder = 0,
            title = "Checkpoint Restore Step",
            description = "Fails after dirtying workspace",
            expectedFiles = listOf("final_target.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restore test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var restoredStateObserved = false
        val runCount = AtomicInteger(0)

        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                originalFile.writeText("corrupted baseline content")
                modifiedFile.writeText("unwanted mutated file")
                throw RuntimeException("Process failed after dirtying workspace")
            } else {
                // Verify checkpoint was restored before attempt 2 runs!
                if (originalFile.readText() == "original baseline" && !modifiedFile.exists()) {
                    restoredStateObserved = true
                }
                File(workspaceDir, "final_target.txt").writeText("success")
            }
        }
        job.join()

        assertTrue("Pre-step checkpoint must revert workspace mutations before retry", restoredStateObserved)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
    }

    // 4. Wrong checkpoint rejected (ownership and project mismatch validation)
    @Test
    fun test4_wrongCheckpointRejected() = runBlocking {
        val task = CanonicalTask(
            taskId = "task-safe-chk",
            projectId = "proj-4",
            projectSlug = "slug-4",
            objective = "Checkpoint validation"
        )
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Valid step",
            checkpointTag = "step-1"
        )

        // Case A: Tag belongs to another step
        val validationWrongStep = validateCheckpointForStep(task, step, "step-2", checkpoints)
        assertTrue(validationWrongStep is CheckpointValidationResult.Invalid)
        assertTrue((validationWrongStep as CheckpointValidationResult.Invalid).reason.contains("does not belong to step"))

        // Case B: Non-existent checkpoint
        val validationNonExistent = validateCheckpointForStep(task, step, "step-1", checkpoints)
        assertTrue(validationNonExistent is CheckpointValidationResult.Invalid)
        assertTrue((validationNonExistent as CheckpointValidationResult.Invalid).reason.contains("does not exist"))

        // Case C: Unsafe tag (traversal)
        val validationUnsafe = validateCheckpointForStep(task, step, "../escape", checkpoints)
        assertTrue(validationUnsafe is CheckpointValidationResult.Invalid)

        // Case D: Project mismatch
        checkpoints.createCheckpoint("other-project", workspaceDir, "step-1")
        val otherProjectValidation = validateCheckpointForStep(task, step, "step-1", checkpoints)
        assertTrue(otherProjectValidation is CheckpointValidationResult.Invalid)
    }

    // 5. Cancellation stops recovery (never recover after user cancellation)
    @Test
    fun test5_cancellationStopsRecovery() = runBlocking {
        val taskId = "test-cancel-recovery"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Cancel Recovery Step",
            description = "Must not recover after stop",
            expectedFiles = listOf("target.txt"),
            maxAttempts = 3,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cancel test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val stepEntered = CompletableDeferred<Unit>()

        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            stepEntered.complete(Unit)
            kotlinx.coroutines.delay(500)
            throw RuntimeException("Error after delay")
        }

        stepEntered.await()
        supervisor.requestStop(taskId)
        job.join()

        assertEquals("Cancelled task must not retry", 1, runCount.get())
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get(taskId)?.status)
    }

    // 6. Permanent failure does not retry
    @Test
    fun test6_permanentFailureDoesNotRetry() = runBlocking {
        val taskId = "test-perm-failure"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Auth Step",
            description = "Fails with HTTP 401 permanent error",
            expectedFiles = listOf("auth_target.txt"),
            maxAttempts = 3,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-6",
            projectSlug = "slug-6",
            chatId = "c6",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Perm test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            throw RuntimeException("HTTP 401 Unauthorized: Invalid API key")
        }
        job.join()

        assertEquals("Permanent auth error must NOT retry", 1, runCount.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 7. Recovery attempt persistence
    @Test
    fun test7_recoveryAttemptPersistence() = runBlocking {
        val taskId = "test-recovery-persist"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Persist Step",
            description = "Fails transiently once",
            expectedFiles = listOf("persist_out.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Persist test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.attempts == 1) {
                throw RuntimeException("HTTP 503 transient error")
            } else {
                File(workspaceDir, "persist_out.txt").writeText("done")
            }
        }
        job.join()

        val reloaded = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(reloaded)
        assertTrue("Failure history must be persisted in database", reloaded!!.failureHistory.isNotEmpty())
        assertEquals("TRANSIENT_API_ERROR", reloaded.failureHistory[0].classification)
        assertNotNull("Active recovery plan must be persisted", reloaded.activeRecoveryPlan)
        assertEquals(RecoveryStrategy.RETRY_STEP, reloaded.activeRecoveryPlan!!.strategy)
    }

    // 8. Maximum retry bound (no infinite loops)
    @Test
    fun test8_maximumRetryBound() = runBlocking {
        val taskId = "test-max-retries"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Failing Step",
            description = "Always throws transient error",
            expectedFiles = listOf("never.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-8",
            projectSlug = "slug-8",
            chatId = "c8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Max retry test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            throw RuntimeException("HTTP 503 Service Unavailable")
        }
        job.join()

        assertEquals("Must stop when maxAttempts reached", 2, runCount.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 9. Retry followed by verification (RUNNING -> VERIFYING -> StepVerifier)
    @Test
    fun test9_retryFollowedByVerification() = runBlocking {
        val taskId = "test-retry-verification"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Verify Sequence Step",
            description = "Verifies full lifecycle after retry",
            expectedFiles = listOf("seq_file.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-9",
            projectSlug = "slug-9",
            chatId = "c9",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Seq test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val lifecycleStates = mutableListOf<String>()

        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                lifecycleStates.add("VERIFY:attempt-${step.attempts}")
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            lifecycleStates.add("RUN:attempt-${activeStep.attempts}")
            if (activeStep.attempts == 1) {
                throw RuntimeException("Transient failure on attempt 1")
            } else {
                File(workspaceDir, "seq_file.txt").writeText("seq content")
            }
        }
        job.join()

        assertEquals(
            listOf("RUN:attempt-1", "RUN:attempt-2", "VERIFY:attempt-2"),
            lifecycleStates
        )
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
    }

    // 10. Failed recovery becomes terminal
    @Test
    fun test10_failedRecoveryBecomesTerminal() = runBlocking {
        val taskId = "test-terminal-fail"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Unrecoverable Step",
            description = "Recovery cannot succeed",
            expectedFiles = listOf("none.txt"),
            maxAttempts = 1,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-10",
            projectSlug = "slug-10",
            chatId = "c10",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Terminal test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ ->
            throw RuntimeException("Immediate process crash")
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 11. Duplicate recovery invocation (idempotency)
    @Test
    fun test11_duplicateRecoveryInvocation_idempotent() = runBlocking {
        val task = CanonicalTask(
            taskId = "task-idempotent",
            projectId = "proj-11",
            projectSlug = "slug-11",
            objective = "Idempotency test"
        )
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Step description",
            checkpointTag = "step-1"
        )

        File(workspaceDir, "tracked.txt").writeText("initial")
        checkpoints.createCheckpoint("proj-11", workspaceDir, "step-1")
        File(workspaceDir, "tracked.txt").writeText("dirty mutation")

        val plan = RecoveryPlan(
            recoveryId = "rec-fixed-id-123",
            taskId = "task-idempotent",
            failureRecordId = "fail-123",
            strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
            rationale = "Restore checkpoint idempotently",
            checkpointTag = "step-1",
            targetStepIndex = 0
        )

        val engine = DefaultRecoveryEngine()

        // First execution
        val result1 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        assertTrue(result1.success)
        assertTrue(result1.checkpointRestored)
        assertEquals("initial", File(workspaceDir, "tracked.txt").readText())

        // Mutate again to test whether duplicate invocation erroneously re-restores
        File(workspaceDir, "tracked.txt").writeText("second mutation")

        // Duplicate execution with same recovery plan ID
        val result2 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        assertEquals(result1, result2)
        // File remains as is because cached result was returned without repeating restore
        assertEquals("second mutation", File(workspaceDir, "tracked.txt").readText())
    }

    // 12. Agent cannot select recovery strategy
    @Test
    fun test12_agentCannotSelectRecoveryStrategy() = runBlocking {
        val engine = DefaultRecoveryEngine()
        val task = CanonicalTask(
            taskId = "task-agent-untrusted",
            projectId = "proj-12",
            projectSlug = "slug-12",
            objective = "Untrusted input"
        )
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Untrusted Step",
            description = "Step",
            checkpointTag = "step-1"
        )

        // Attempt recovery plan with arbitrary strategy outside allowlist
        val illegalPlan = RecoveryPlan(
            recoveryId = "rec-illegal",
            taskId = "task-agent-untrusted",
            failureRecordId = "fail-illegal",
            strategy = RecoveryStrategy.MANUAL_USER_INTERVENTION,
            rationale = "Agent tried to force UI intervention",
            targetStepIndex = 0
        )

        val result = engine.executeRecovery(task, step, illegalPlan, workspaceDir, checkpoints)
        assertFalse("Illegal strategy outside allowlist must be rejected", result.success)
        assertFalse(result.shouldRetryStep)
        assertTrue(result.message.contains("rejected: not in trusted allowlist"))
    }

    // 13. Brain learning regression (feedback learned for recovery failures and successes)
    @Test
    fun test13_brainLearningRegression() = runBlocking {
        val taskId = "test-learning-regression"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Learning Step",
            description = "Learns failure feedback on attempt 1, success on attempt 2",
            expectedFiles = listOf("learned.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-13",
            projectSlug = "slug-13",
            chatId = "c13",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Learning test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.attempts == 1) {
                throw RuntimeException("HTTP 503 Service Unavailable")
            } else {
                File(workspaceDir, "learned.txt").writeText("learned content")
            }
        }
        job.join()

        val memories = knowledgeRepo.findByProject("proj-13")
        assertTrue("Must learn execution feedback into project brain", memories.isNotEmpty())
        assertTrue(
            "Must persist task outcome progress",
            memories.any { it.key.contains("outcome") || it.key.contains("progress") || it.key.contains("verified") }
        )
    }

    // 14. Step state regression (strict lifecycle and no index double advancement)
    @Test
    fun test14_stepStateRegression() = runBlocking {
        val taskId = "test-state-regression"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Fails once, then succeeds",
            expectedFiles = listOf("s0.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1",
            description = "Succeeds immediately",
            expectedFiles = listOf("s1.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-14",
            projectSlug = "slug-14",
            chatId = "c14",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Multi-step regression test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        val executedSteps = mutableListOf<String>()

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            executedSteps.add("step-${activeStep.stepOrder}:attempt-${activeStep.attempts}")
            if (activeStep.stepOrder == 0 && activeStep.attempts == 1) {
                throw RuntimeException("HTTP 503 transient error")
            }
            File(workspaceDir, "s${activeStep.stepOrder}.txt").writeText("done")
        }
        job.join()

        assertEquals(
            listOf("step-0:attempt-1", "step-0:attempt-2", "step-1:attempt-1"),
            executedSteps
        )

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[1].status)
        assertEquals(2, canonical.plan.steps[0].attempts)
        assertEquals(1, canonical.plan.steps[1].attempts)
        assertEquals("currentStepIndex must advance exactly to total step count", 2, canonical.plan.currentStepIndex)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }
    @Test
    fun ambiguousToolExecutionIsNotRetriedEvenWithoutWorkspaceChanges() = runBlocking {
        val task = CanonicalTask(taskId = "replay", projectId = "project", projectSlug = "slug", objective = "review", plan = ExecutionPlan(steps = listOf(ExecutionStep(stepOrder = 0, title = "step", description = "review"))))
        val classification = supervisor.classifyError("REPLAY_UNSAFE: network interrupted after tool", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.REPLAY_UNSAFE, classification)
        assertNull(DefaultRecoveryEngine().planRecovery(task, task.plan.steps.first(), classification, "REPLAY_UNSAFE", emptyList(), 0))
    }

}
