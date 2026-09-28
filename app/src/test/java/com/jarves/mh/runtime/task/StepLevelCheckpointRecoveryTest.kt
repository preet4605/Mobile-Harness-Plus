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
import com.jarves.mh.model.brain.GlobalExecutionPolicies
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStatus
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verification test suite for Phase 6 Precondition 6:
 * "Step-Level Checkpoint/Recovery Integration".
 *
 * Verifies that step checkpoint and recovery states are fully persistent,
 * restart-safe, and deterministic:
 * 1. checkpoint persisted before attempt
 * 2. recovery state persisted
 * 3. restart during RUNNING
 * 4. restart during VERIFYING
 * 5. restart during RECOVERING
 * 6. recovery resumes exactly once
 * 7. correct checkpoint ownership (taskId + projectId + stepId + attempt)
 * 8. recovery -> verification -> completion (never recovery -> completed directly)
 * 9. failed verification after recovery
 * 10. exhausted recovery
 * 11. cancellation during recovery
 * 12. no duplicate step advancement
 * 13. Brain/policy injection regression
 */
class StepLevelCheckpointRecoveryTest {

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

    // 1. Checkpoint persisted before attempt
    @Test
    fun test01_checkpointPersistedBeforeAttempt() = runBlocking {
        val taskId = "test-checkpoint-before-attempt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Check checkpoint existence before execution",
            checkpointTag = "step-1",
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
            prompt = "Do work",
            plan = ExecutionPlan(steps = listOf(step))
        )

        File(workspaceDir, "initial.txt").writeText("initial clean content")

        var checkpointVerifiedBeforeExecution = false
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            // Inside execution attempt 1: verify checkpoint and step metadata are already persisted!
            val exists = checkpoints.checkpointExists("proj-1", "step-1")
            val meta = checkpoints.readMetadata("proj-1", "step-1")
            val persistedTask = canonicalRepo.getTask(taskId)
            val persistedStep = persistedTask?.plan?.steps?.get(0)

            if (exists && meta != null && persistedStep != null) {
                if (meta.taskId == taskId &&
                    meta.stepId == step.stepId &&
                    meta.attempt == 1 &&
                    meta.projectId == "proj-1" &&
                    persistedStep.attempts == 1 &&
                    persistedStep.status == StepStatus.RUNNING &&
                    persistedStep.checkpointTag == "step-1"
                ) {
                    checkpointVerifiedBeforeExecution = true
                }
            }
        }
        job.join()

        assertTrue("Checkpoint with metadata must be persisted before step execution begins", checkpointVerifiedBeforeExecution)
    }

    // 2. Recovery state persisted
    @Test
    fun test02_recoveryStatePersisted() = runBlocking {
        val taskId = "test-recovery-state-persisted"
        val targetFile = "output.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Recovery Persistence Step",
            description = "Fails attempt 1, succeeds attempt 2",
            expectedFiles = listOf(targetFile),
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
            prompt = "Recovery persistence test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                File(workspaceDir, "dirty.txt").writeText("dirty workspace")
                throw RuntimeException("HTTP 503 Service Unavailable")
            } else {
                File(workspaceDir, targetFile).writeText("clean execution")
            }
        }
        job.join()

        val persisted = canonicalRepo.getTask(taskId)
        assertNotNull(persisted)
        val recovery = persisted!!.activeRecoveryPlan
        assertNotNull("Active recovery plan must be persisted in repository", recovery)
        assertEquals("Strategy must match executed strategy", RecoveryStrategy.RESTORE_CHECKPOINT, recovery!!.strategy)
        assertEquals("Recovery attempt must be 2", 2, recovery.attemptNumber)
        assertEquals("Recovery status must be COMPLETED", RecoveryStatus.COMPLETED, recovery.status)
        assertEquals("step-1", recovery.checkpointTag)
        assertEquals(step.stepId, recovery.stepId)
        assertNotNull("Recovery result must be recorded", recovery.recoveryResult)
        assertEquals("RETRY_STEP", recovery.nextAction)
    }

    // 3. Restart during RUNNING
    @Test
    fun test03_restartDuringRunning() = runBlocking {
        val taskId = "test-restart-running"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Running Step",
            description = "Interrupted while RUNNING",
            status = StepStatus.RUNNING,
            attempts = 1,
            checkpointTag = "step-1"
        )
        val plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart running test",
            plan = plan
        )

        val durable = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart running test",
            status = TaskExecutionStatus.RUNNING,
            pid = 9999991
        )
        supervisor.stateStore.save(durable)

        val reconciled = supervisor.reconcileOnStartup()
        assertTrue(reconciled.any { it.taskId == taskId })

        val canonical = canonicalRepo.getTask(taskId)
        assertNotNull(canonical)
        val reconciledStep = canonical!!.plan.steps[0]
        assertNotEquals("RUNNING step must NEVER be marked COMPLETED on restart", StepStatus.COMPLETED, reconciledStep.status)
        assertEquals("RUNNING step must be reconciled to FAILED on restart", StepStatus.FAILED, reconciledStep.status)
        assertNotNull(reconciledStep.completedAt)
        assertTrue(reconciledStep.resultSummary?.contains("Process terminated") == true ||
            reconciledStep.resultSummary?.contains("Application restarted") == true)
    }

    // 4. Restart during VERIFYING
    @Test
    fun test04_restartDuringVerifying() = runBlocking {
        val taskId = "test-restart-verifying"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Verifying Step",
            description = "Interrupted while VERIFYING",
            status = StepStatus.VERIFYING,
            attempts = 1,
            checkpointTag = "step-1"
        )
        val plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart verifying test",
            plan = plan
        )

        val durable = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart verifying test",
            status = TaskExecutionStatus.RUNNING,
            pid = 9999992
        )
        supervisor.stateStore.save(durable)

        val reconciled = supervisor.reconcileOnStartup()
        assertTrue(reconciled.any { it.taskId == taskId })

        val canonical = canonicalRepo.getTask(taskId)
        assertNotNull(canonical)
        val reconciledStep = canonical!!.plan.steps[0]
        assertNotEquals("VERIFYING step must NEVER be marked COMPLETED on restart", StepStatus.COMPLETED, reconciledStep.status)
        assertEquals("VERIFYING step must be reconciled to FAILED on restart", StepStatus.FAILED, reconciledStep.status)
        assertNotNull(reconciledStep.completedAt)
    }

    // 5. Restart during RECOVERING
    @Test
    fun test05_restartDuringRecovering() = runBlocking {
        val taskId = "test-restart-recovering"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Recovering Step",
            description = "Interrupted while RECOVERING",
            status = StepStatus.RECOVERING,
            attempts = 1,
            checkpointTag = "step-1"
        )
        val recoveryPlan = RecoveryPlan(
            taskId = taskId,
            failureRecordId = "fail-1",
            strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
            rationale = "Interrupted recovery",
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = "step-1",
            attemptNumber = 2,
            status = RecoveryStatus.IN_PROGRESS
        )
        val plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart recovering test",
            plan = plan
        )
        val currentCanonical = canonicalRepo.getTask(taskId)!!
        canonicalRepo.saveTask(currentCanonical.copy(activeRecoveryPlan = recoveryPlan))

        val durable = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart recovering test",
            status = TaskExecutionStatus.RUNNING,
            pid = 9999993
        )
        supervisor.stateStore.save(durable)

        val reconciled = supervisor.reconcileOnStartup()
        assertTrue(reconciled.any { it.taskId == taskId })

        val canonical = canonicalRepo.getTask(taskId)
        assertNotNull(canonical)
        val reconciledStep = canonical!!.plan.steps[0]
        assertEquals("RECOVERING step must be reconciled to FAILED on restart", StepStatus.FAILED, reconciledStep.status)
        val reconciledRecovery = canonical.activeRecoveryPlan
        assertNotNull(reconciledRecovery)
        assertEquals("Interrupted recovery plan must not remain IN_PROGRESS", RecoveryStatus.FAILED, reconciledRecovery!!.status)
        assertEquals("RETRY_STEP", reconciledRecovery.nextAction)
    }

    // 6. Recovery resumes exactly once
    @Test
    fun test06_recoveryResumesExactlyOnce() = runBlocking {
        val taskId = "test-recovery-resumes-once"
        val baselineFile = File(workspaceDir, "base.txt").apply { writeText("original") }
        checkpoints.createCheckpoint("proj-6", workspaceDir, "step-1", taskId = taskId, stepId = "step-uuid-6", attempt = 1)

        val step = ExecutionStep(
            stepId = "step-uuid-6",
            stepOrder = 0,
            title = "Resuming Step",
            description = "Resumes from persisted recovery state",
            expectedFiles = listOf("success.txt"),
            status = StepStatus.FAILED,
            attempts = 1,
            maxAttempts = 2,
            checkpointTag = "step-1"
        )
        val recoveryPlan = RecoveryPlan(
            recoveryId = "rec-fixed-id-6",
            taskId = taskId,
            failureRecordId = "fail-6",
            strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
            rationale = "Resume persisted recovery",
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = "step-1",
            attemptNumber = 2,
            status = RecoveryStatus.FAILED,
            nextAction = "RETRY_STEP"
        )
        val plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-6",
            projectSlug = "slug-6",
            chatId = "c6",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Resume test",
            plan = plan
        )
        val currentCanonical = canonicalRepo.getTask(taskId)!!
        canonicalRepo.saveTask(currentCanonical.copy(activeRecoveryPlan = recoveryPlan))

        // Simulate dirty workspace before resume
        baselineFile.writeText("corrupted dirty state")

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            File(workspaceDir, "success.txt").writeText("done")
        }
        job.join()

        assertEquals("Step should run exactly once on resume", 1, runCount.get())
        val finalCanonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, finalCanonical.plan.steps[0].status)
        assertEquals(2, finalCanonical.plan.steps[0].attempts)
        val finalRecovery = finalCanonical.activeRecoveryPlan
        assertNotNull(finalRecovery)
        assertEquals("rec-fixed-id-6", finalRecovery!!.recoveryId)
        assertEquals(2, finalRecovery.attemptNumber)
        assertEquals(RecoveryStatus.COMPLETED, finalRecovery.status)
    }

    // 7. Correct checkpoint ownership
    @Test
    fun test07_correctCheckpointOwnership() {
        val task = CanonicalTask(
            taskId = "task-owner-1",
            projectId = "proj-7",
            projectSlug = "slug-7",
            objective = "Ownership test"
        )
        val step = ExecutionStep(
            stepId = "step-owner-1",
            stepOrder = 0,
            title = "Ownership Step",
            description = "Validate ownership",
            checkpointTag = "step-1"
        )

        File(workspaceDir, "file.txt").writeText("content")

        // Create checkpoint with full matching identity
        checkpoints.createCheckpoint("proj-7", workspaceDir, "step-1", taskId = "task-owner-1", stepId = "step-owner-1", attempt = 1)

        // Case A: Matching identity -> Valid
        val validResult = validateCheckpointForStep(task, step, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue("Matching checkpoint identity must be valid", validResult is CheckpointValidationResult.Valid)

        // Case B: Wrong task ID
        val wrongTask = task.copy(taskId = "other-task-99")
        val wrongTaskResult = validateCheckpointForStep(wrongTask, step, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(wrongTaskResult is CheckpointValidationResult.Invalid)
        assertTrue((wrongTaskResult as CheckpointValidationResult.Invalid).reason.contains("Checkpoint task mismatch"))

        // Case C: Wrong step ID
        val wrongStep = step.copy(stepId = "other-step-99")
        val wrongStepResult = validateCheckpointForStep(task, wrongStep, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(wrongStepResult is CheckpointValidationResult.Invalid)
        assertTrue((wrongStepResult as CheckpointValidationResult.Invalid).reason.contains("Checkpoint step mismatch"))

        // Case D: Wrong project ID
        val wrongProjTask = task.copy(projectId = "other-proj-99")
        val wrongProjResult = validateCheckpointForStep(wrongProjTask, step, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(wrongProjResult is CheckpointValidationResult.Invalid)
        assertTrue((wrongProjResult as CheckpointValidationResult.Invalid).reason.contains("does not exist"))

        // Case E: Wrong attempt
        val wrongAttemptResult = validateCheckpointForStep(task, step, "step-1", checkpoints, expectedAttempt = 2)
        assertTrue(wrongAttemptResult is CheckpointValidationResult.Invalid)
        assertTrue((wrongAttemptResult as CheckpointValidationResult.Invalid).reason.contains("Checkpoint attempt mismatch"))
    }

    // 8. Recovery -> Verification -> Completion (Never Recovery -> Completed directly)
    @Test
    fun test08_recoveryToVerificationToCompletion() = runBlocking {
        val taskId = "test-recovery-verify-complete"
        val expectedFile = "verified.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Strict State Transition Step",
            description = "Must transition RUNNING -> VERIFYING -> COMPLETED",
            expectedFiles = listOf(expectedFile),
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
            prompt = "Strict transitions",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val observedStatuses = mutableListOf<StepStatus>()
        val runCount = AtomicInteger(0)

        // Track StepVerifier calls to prove StepVerifier is the sole completion authority
        var verifierInvoked = false
        val defaultVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                verifierInvoked = true
                return defaultVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            observedStatuses.add(activeStep.status)
            val count = runCount.incrementAndGet()
            if (count == 1) {
                File(workspaceDir, "corrupted.txt").writeText("dirty")
                throw RuntimeException("Attempt 1 failure")
            } else {
                File(workspaceDir, expectedFile).writeText("valid content")
            }
        }
        job.join()

        assertTrue("StepVerifier must be invoked after recovery", verifierInvoked)
        assertEquals(listOf(StepStatus.RUNNING, StepStatus.RUNNING), observedStatuses)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(2, canonical.plan.steps[0].attempts)
    }

    // 9. Failed verification after recovery
    @Test
    fun test09_failedVerificationAfterRecovery() = runBlocking {
        val taskId = "test-failed-verification-after-recovery"
        val expectedFile = "never_created.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Always Fail Verification",
            description = "Attempt 1 fails exec, attempt 2 fails verification",
            expectedFiles = listOf(expectedFile),
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
            prompt = "Fail verify test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                File(workspaceDir, "dirty.txt").writeText("dirty")
                throw RuntimeException("Execution error on attempt 1")
            } else {
                // Attempt 2 runs cleanly but does NOT create expectedFile -> fails verification!
                File(workspaceDir, "other.txt").writeText("irrelevant")
            }
        }
        job.join()

        assertEquals(2, runCount.get())
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals("Step must end in FAILED when verification fails after recovery", StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 10. Exhausted recovery
    @Test
    fun test10_exhaustedRecovery() = runBlocking {
        val taskId = "test-exhausted-recovery"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Exhausted Step",
            description = "Fails all attempts",
            expectedFiles = listOf("final.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-10",
            projectSlug = "slug-10",
            chatId = "c10",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Exhausted test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            throw RuntimeException("Persistent network breakdown")
        }
        job.join()

        assertEquals(2, runCount.get())
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        val recovery = canonical.activeRecoveryPlan
        assertNotNull(recovery)
        assertEquals("Recovery status must be EXHAUSTED when retries are depleted", RecoveryStatus.EXHAUSTED, recovery!!.status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 11. Cancellation during recovery
    @Test
    fun test11_cancellationDuringRecovery() = runBlocking {
        val taskId = "test-cancellation-during-recovery"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Cancel Recovery Step",
            description = "Cancels during recovery",
            expectedFiles = listOf("result.txt"),
            maxAttempts = 3,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-11",
            projectSlug = "slug-11",
            chatId = "c11",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cancel during recovery",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                // Cancel while recovery is being executed!
                supervisor.requestStop(taskId, force = true)
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            File(workspaceDir, "dirty.txt").writeText("dirty")
            throw RuntimeException("Trigger recovery")
        }
        job.join()

        assertEquals("Step must execute only attempt 1 before cancellation halts execution", 1, runCount.get())
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get(taskId)?.status)
        val canonical = canonicalRepo.getTask(taskId)!!
        val recovery = canonical.activeRecoveryPlan
        assertNotNull(recovery)
        assertEquals("Recovery plan status must be CANCELLED", RecoveryStatus.CANCELLED, recovery!!.status)
    }

    // 12. No duplicate step advancement
    @Test
    fun test12_noDuplicateStepAdvancement() = runBlocking {
        val taskId = "test-no-duplicate-advancement"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0 - Retried",
            description = "Fails attempt 1, passes attempt 2",
            expectedFiles = listOf("step0_done.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1 - Sequential",
            description = "Executes strictly after step 0 completes",
            expectedFiles = listOf("step1_done.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-12",
            projectSlug = "slug-12",
            chatId = "c12",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Step advancement test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        val step0Attempts = AtomicInteger(0)
        val step1Attempts = AtomicInteger(0)

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.stepOrder == 0) {
                val att = step0Attempts.incrementAndGet()
                if (att == 1) {
                    throw RuntimeException("Step 0 attempt 1 failure")
                } else {
                    File(workspaceDir, "step0_done.txt").writeText("step 0 complete")
                }
            } else if (activeStep.stepOrder == 1) {
                step1Attempts.incrementAndGet()
                File(workspaceDir, "step1_done.txt").writeText("step 1 complete")
            }
        }
        job.join()

        assertEquals(2, step0Attempts.get())
        assertEquals(1, step1Attempts.get())
        val finalCanonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, finalCanonical.plan.steps[0].status)
        assertEquals(StepStatus.COMPLETED, finalCanonical.plan.steps[1].status)
        assertEquals("currentStepIndex must advance exactly to 2 (end of plan)", 2, finalCanonical.plan.currentStepIndex)
    }

    // 13. Brain / policy injection regression
    @Test
    fun test13_brainPolicyInjectionRegression() = runBlocking {
        val taskId = "test-brain-policy-regression"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Brain Policy Step",
            description = "Verifies policies and learning intact",
            expectedFiles = listOf("done.txt"),
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
            prompt = "Brain policy test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { currentRecord, activeStep ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("Attempt 1 failure to trigger failure feedback")
            } else {
                File(workspaceDir, "done.txt").writeText("success")
            }
        }
        job.join()

        // Verify learning service recorded knowledge entries across attempts
        val knowledgeEntries = knowledgeRepo.findByProject("proj-13")
        assertTrue("Brain knowledge entries must be recorded across attempts", knowledgeEntries.isNotEmpty())

        // Verify GlobalExecutionPolicies remains active and valid
        assertTrue(GlobalExecutionPolicies.DEFAULT_POLICIES.isNotEmpty())
        assertTrue(GlobalExecutionPolicies.isGlobalPolicyKey(GlobalExecutionPolicies.REASONING_BUDGET_POLICY_NAME))
    }
}
