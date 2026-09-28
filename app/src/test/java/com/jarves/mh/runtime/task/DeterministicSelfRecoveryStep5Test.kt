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
import com.jarves.mh.model.brain.PlanStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Authoritative Test Suite for Phase 6 Step 5: "Deterministic Self-Recovery".
 *
 * Verifies full deterministic self-recovery integration across the step lifecycle:
 * 1. deterministic strategy selection
 * 2. persisted recovery-before-action
 * 3. monotonic recovery attempts
 * 4. restart during recovery
 * 5. no duplicate recovery
 * 6. checkpoint ownership
 * 7. idempotent restore
 * 8. recovery success returns through verification (RECOVERING -> RUNNING -> VERIFYING -> StepVerifier)
 * 9. recovery failure
 * 10. recovery exhaustion
 * 11. cancellation during recovery
 * 12. recovery cannot advance step
 * 13. recovery cannot complete step directly
 * 14. future steps remain untouched
 * 15. Brain snapshot & policy preservation
 */
class DeterministicSelfRecoveryStep5Test {

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

    // 1. Deterministic strategy selection
    @Test
    fun test01_deterministicStrategySelection() {
        val engine = DefaultRecoveryEngine()
        val task = CanonicalTask(
            taskId = "task-det-strat",
            projectId = "proj-1",
            projectSlug = "slug-1",
            objective = "Deterministic Strategy Selection"
        )
        val step = ExecutionStep(
            stepId = "step-uuid-1",
            stepOrder = 0,
            title = "Step 0",
            description = "Step",
            maxAttempts = 3,
            checkpointTag = "step-1"
        )

        // Multiple invocations with same inputs produce identical plans
        val plan1 = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, "HTTP 503", emptyList(), attemptCount = 1
        )
        val plan2 = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, "HTTP 503", emptyList(), attemptCount = 1
        )

        assertNotNull(plan1)
        assertNotNull(plan2)
        assertEquals(plan1!!.strategy, plan2!!.strategy)
        assertEquals(RecoveryStrategy.RETRY_STEP, plan1.strategy)
        assertEquals(plan1.recoveryId, plan2.recoveryId)
        assertEquals(plan1.failureRecordId, plan2.failureRecordId)
        assertEquals(2, plan1.attemptNumber)

        // Mutation triggers RESTORE_CHECKPOINT deterministically
        val planMut = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, "HTTP 503", listOf("mutated.txt"), attemptCount = 1
        )
        assertNotNull(planMut)
        assertEquals(RecoveryStrategy.RESTORE_CHECKPOINT, planMut!!.strategy)
        assertEquals("step-1", planMut.checkpointTag)

        // TRANSIENT_SYSTEM_FAULT with clean workspace triggers RETRY_STEP_DIRECT deterministically without checkpoint restoration
        val planSysFault = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, "Resource temporarily unavailable", emptyList(), attemptCount = 1
        )
        assertNotNull(planSysFault)
        assertEquals(RecoveryStrategy.RETRY_STEP_DIRECT, planSysFault!!.strategy)
        assertNull(planSysFault.checkpointTag)
        assertTrue(planSysFault.filesToRollback.isEmpty())
        assertEquals(2, planSysFault.attemptNumber)

        // ENVIRONMENT_DRIFT triggers RECREATE_WORKSPACE_STATE with canonical baseline tag
        val planDrift = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT, "environment_drift: corrupted workspace state", emptyList(), attemptCount = 1
        )
        assertNotNull(planDrift)
        assertEquals(RecoveryStrategy.RECREATE_WORKSPACE_STATE, planDrift!!.strategy)
        assertEquals(WorkspaceCheckpoints.TASK_BASELINE_TAG, planDrift.checkpointTag)
        assertEquals(2, planDrift.attemptNumber)

        // Permanent failure never produces recovery plan
        val planPerm = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG, "HTTP 401", emptyList(), attemptCount = 1
        )
        assertNull("Permanent error must never plan recovery", planPerm)

        // User cancellation never produces recovery plan
        val planCancel = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.USER_CANCELLED, "Cancelled", emptyList(), attemptCount = 1
        )
        assertNull("Cancelled task must never plan recovery", planCancel)
    }

    // 2. Persisted recovery-before-action
    @Test
    fun test02_persistedRecoveryBeforeAction() = runBlocking {
        val taskId = "test-recovery-before-action"
        val expectedFile = "out.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Recovery Before Action Step",
            description = "Verifies recovery is persisted in database before executeRecovery runs",
            expectedFiles = listOf(expectedFile),
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
            prompt = "Persist test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var recoveryStateObservedBeforeExecution = false
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                // Read directly from persistent repository while executeRecovery is being entered
                val persisted = canonicalRepo.getTask(task.taskId)
                val activePlan = persisted?.activeRecoveryPlan
                val currentStep = persisted?.plan?.steps?.get(0)
                if (activePlan != null && currentStep != null) {
                    if (currentStep.status == StepStatus.RECOVERING &&
                        (activePlan.status == RecoveryStatus.IN_PROGRESS || activePlan.status == RecoveryStatus.PENDING)
                    ) {
                        recoveryStateObservedBeforeExecution = true
                    }
                }
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                File(workspaceDir, "dirty.txt").writeText("dirty")
                throw RuntimeException("HTTP 503 transient failure")
            } else {
                File(workspaceDir, expectedFile).writeText("success")
            }
        }
        job.join()

        assertTrue("Recovery state and step RECOVERING status must be persisted BEFORE recovery action executes", recoveryStateObservedBeforeExecution)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
    }

    // 3. Monotonic recovery attempts
    @Test
    fun test03_monotonicRecoveryAttempts() = runBlocking {
        val taskId = "test-monotonic-attempts"
        val expectedFile = "monotonic.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Monotonic Attempt Step",
            description = "Fails twice, succeeds on attempt 3",
            expectedFiles = listOf(expectedFile),
            maxAttempts = 3,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Monotonic test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val observedRecoveryAttempts = mutableListOf<Int>()
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                observedRecoveryAttempts.add(plan.attemptNumber)
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            val count = runCount.incrementAndGet()
            if (count < 3) {
                File(workspaceDir, "temp.txt").writeText("attempt $count")
                throw RuntimeException("HTTP 503 Service Unavailable")
            } else {
                File(workspaceDir, expectedFile).writeText("final success")
            }
        }
        job.join()

        assertEquals("Step must run exactly 3 attempts", 3, runCount.get())
        assertEquals("Recovery attempt numbers must be monotonically increasing (2, 3)", listOf(2, 3), observedRecoveryAttempts)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(3, canonical.plan.steps[0].attempts)
    }

    // 4. Restart during recovery
    @Test
    fun test04_restartDuringRecovery() = runBlocking {
        val taskId = "test-restart-recovery-reconcile"
        val step = ExecutionStep(
            stepId = "step-uuid-4",
            stepOrder = 0,
            title = "Restart Recovery Step",
            description = "Interrupted during recovery",
            expectedFiles = listOf("success.txt"),
            status = StepStatus.RECOVERING,
            attempts = 1,
            maxAttempts = 2,
            checkpointTag = "step-1"
        )
        val recoveryPlan = RecoveryPlan(
            recoveryId = "rec-fixed-id-4",
            taskId = taskId,
            failureRecordId = "fail-4",
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
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart test",
            plan = plan
        )
        canonicalRepo.saveTask(canonicalRepo.getTask(taskId)!!.copy(activeRecoveryPlan = recoveryPlan))

        val durable = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart test",
            status = TaskExecutionStatus.RUNNING,
            pid = 9999984
        )
        supervisor.stateStore.save(durable)

        // 1. Reconcile on startup: does NOT assume recovery succeeded
        val reconciled = supervisor.reconcileOnStartup()
        assertTrue(reconciled.any { it.taskId == taskId })

        val reconciledTask = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, reconciledTask.plan.steps[0].status)
        assertEquals(RecoveryStatus.FAILED, reconciledTask.activeRecoveryPlan!!.status)
        assertEquals("RETRY_STEP", reconciledTask.activeRecoveryPlan!!.nextAction)

        // 2. Prepare workspace and checkpoint for resume
        File(workspaceDir, "clean.txt").writeText("initial clean")
        checkpoints.createCheckpoint("proj-4", workspaceDir, "step-1", taskId = taskId, stepId = "step-uuid-4", attempt = 1)
        File(workspaceDir, "clean.txt").writeText("dirty mutated state")

        // Reset stateStore record to CREATED to simulate fresh supervisor launch of the recovered task
        supervisor.stateStore.save(
            supervisor.stateStore.get(taskId)!!.copy(
                status = TaskExecutionStatus.CREATED,
                pid = null
            )
        )

        // 3. Resume task via supervisor: executes recovery and resumes deterministically
        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            // Check that workspace was restored before attempt 2 runs!
            assertEquals("initial clean", File(workspaceDir, "clean.txt").readText())
            File(workspaceDir, "success.txt").writeText("resumed done")
        }
        job.join()

        assertEquals(1, runCount.get())
        val finalTask = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, finalTask.plan.steps[0].status)
        assertEquals(2, finalTask.plan.steps[0].attempts)
        assertEquals(RecoveryStatus.COMPLETED, finalTask.activeRecoveryPlan!!.status)
    }

    // 5. No duplicate recovery
    @Test
    fun test05_noDuplicateRecovery() = runBlocking {
        val taskId = "test-no-dup-recovery"
        val step = ExecutionStep(
            stepId = "step-uuid-5",
            stepOrder = 0,
            title = "No Dup Recovery Step",
            description = "Resumes without duplicating recovery",
            expectedFiles = listOf("final.txt"),
            status = StepStatus.FAILED,
            attempts = 1,
            maxAttempts = 2,
            checkpointTag = "step-1"
        )
        val recoveryPlan = RecoveryPlan(
            recoveryId = "rec-fixed-5",
            taskId = taskId,
            failureRecordId = "fail-5",
            strategy = RecoveryStrategy.RETRY_STEP,
            rationale = "Retry step cleanly",
            targetStepIndex = 0,
            stepId = step.stepId,
            attemptNumber = 2,
            status = RecoveryStatus.FAILED,
            nextAction = "RETRY_STEP"
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "No dup recovery test",
            plan = ExecutionPlan(steps = listOf(step))
        )
        canonicalRepo.saveTask(canonicalRepo.getTask(taskId)!!.copy(activeRecoveryPlan = recoveryPlan))

        val recoveriesExecuted = mutableListOf<String>()
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                recoveriesExecuted.add(plan.recoveryId)
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ ->
            File(workspaceDir, "final.txt").writeText("done")
        }
        job.join()

        assertEquals("Recovery should execute exactly once for the persisted recovery ID", listOf("rec-fixed-5"), recoveriesExecuted)
        val finalCanonical = canonicalRepo.getTask(taskId)!!
        assertEquals("rec-fixed-5", finalCanonical.activeRecoveryPlan!!.recoveryId)
        assertEquals(RecoveryStatus.COMPLETED, finalCanonical.activeRecoveryPlan!!.status)
    }

    // 6. Checkpoint ownership
    @Test
    fun test06_checkpointOwnership() {
        val task = CanonicalTask(
            taskId = "task-owner-6",
            projectId = "proj-6",
            projectSlug = "slug-6",
            objective = "Ownership validation"
        )
        val step = ExecutionStep(
            stepId = "step-owner-6",
            stepOrder = 0,
            title = "Ownership Step",
            description = "Validate ownership",
            checkpointTag = "step-1"
        )

        File(workspaceDir, "test.txt").writeText("content")
        checkpoints.createCheckpoint("proj-6", workspaceDir, "step-1", taskId = "task-owner-6", stepId = "step-owner-6", attempt = 1)

        // Valid
        val valid = validateCheckpointForStep(task, step, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(valid is CheckpointValidationResult.Valid)

        // Cross-task rejected
        val crossTask = validateCheckpointForStep(task.copy(taskId = "other-task"), step, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(crossTask is CheckpointValidationResult.Invalid)
        assertTrue((crossTask as CheckpointValidationResult.Invalid).reason.contains("Checkpoint task mismatch"))

        // Cross-step rejected
        val crossStep = validateCheckpointForStep(task, step.copy(stepId = "other-step"), "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(crossStep is CheckpointValidationResult.Invalid)
        assertTrue((crossStep as CheckpointValidationResult.Invalid).reason.contains("Checkpoint step mismatch"))

        // Cross-project rejected
        val crossProj = validateCheckpointForStep(task.copy(projectId = "other-proj"), step, "step-1", checkpoints, expectedAttempt = 1)
        assertTrue(crossProj is CheckpointValidationResult.Invalid)

        // Cross-attempt rejected
        val crossAttempt = validateCheckpointForStep(task, step, "step-1", checkpoints, expectedAttempt = 2)
        assertTrue(crossAttempt is CheckpointValidationResult.Invalid)
        assertTrue((crossAttempt as CheckpointValidationResult.Invalid).reason.contains("Checkpoint attempt mismatch"))
    }

    // 7. Idempotent restore
    @Test
    fun test07_idempotentRestore() = runBlocking {
        val task = CanonicalTask(
            taskId = "task-idempotent-7",
            projectId = "proj-7",
            projectSlug = "slug-7",
            objective = "Idempotency test"
        )
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Step description",
            checkpointTag = "step-1"
        )

        val tracked = File(workspaceDir, "tracked.txt").apply { writeText("original") }
        checkpoints.createCheckpoint("proj-7", workspaceDir, "step-1")
        tracked.writeText("dirty modification")

        val plan = RecoveryPlan(
            recoveryId = "rec-idemp-7",
            taskId = "task-idempotent-7",
            failureRecordId = "fail-7",
            strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
            rationale = "Restore",
            checkpointTag = "step-1",
            targetStepIndex = 0
        )

        val engine = DefaultRecoveryEngine()
        val res1 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        assertTrue(res1.success)
        assertTrue(res1.checkpointRestored)
        assertEquals("original", tracked.readText())

        // Modify again and invoke same recovery plan
        tracked.writeText("second dirty modification")
        val res2 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        assertEquals("Cached result must be returned idempotently", res1, res2)
        assertEquals("second dirty modification", tracked.readText())
    }

    // 8. Recovery success returns through verification (RECOVERING -> RUNNING -> VERIFYING -> StepVerifier)
    @Test
    fun test08_recoverySuccessReturnsThroughVerification() = runBlocking {
        val taskId = "test-recovery-state-flow"
        val expectedFile = "verified.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Flow Step",
            description = "Verifies state machine transitions",
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
            prompt = "State flow test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val stepStatuses = mutableListOf<StepStatus>()
        var stepVerifierCalled = false
        val defaultVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                stepVerifierCalled = true
                return defaultVerifier.verify(task, step, workspaceDir)
            }
        }

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            stepStatuses.add(activeStep.status)
            val count = runCount.incrementAndGet()
            if (count == 1) {
                File(workspaceDir, "fail.txt").writeText("fail")
                throw RuntimeException("Attempt 1 failure")
            } else {
                File(workspaceDir, expectedFile).writeText("valid content")
            }
        }
        job.join()

        assertTrue("StepVerifier must be authoritative completion verifier", stepVerifierCalled)
        assertEquals(listOf(StepStatus.RUNNING, StepStatus.RUNNING), stepStatuses)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(2, canonical.plan.steps[0].attempts)
    }

    // 9. Recovery failure
    @Test
    fun test09_recoveryFailure() = runBlocking {
        val taskId = "test-recovery-action-failure"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Broken Recovery Step",
            description = "Recovery action fails (missing checkpoint)",
            expectedFiles = listOf("never.txt"),
            maxAttempts = 2,
            checkpointTag = "step-1",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-9",
            projectSlug = "slug-9",
            chatId = "c9",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Recovery action failure test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                // Simulate recovery infrastructure failure
                return RecoveryExecutionResult(
                    success = false,
                    strategy = plan.strategy,
                    shouldRetryStep = false,
                    message = "Simulated checkpoint restore engine crash"
                )
            }
        }

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            File(workspaceDir, "dirty.txt").writeText("dirty")
            throw RuntimeException("Attempt 1 error")
        }
        job.join()

        assertEquals("Step must NOT retry when recovery execution fails", 1, runCount.get())
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        val recovery = canonical.activeRecoveryPlan
        assertNotNull(recovery)
        assertEquals("Recovery plan status must be FAILED", RecoveryStatus.FAILED, recovery!!.status)
        assertEquals("TERMINATE", recovery.nextAction)
    }

    // 10. Recovery exhaustion
    @Test
    fun test10_recoveryExhaustion() = runBlocking {
        val taskId = "test-recovery-exhaustion"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Exhaustion Step",
            description = "Fails all retries",
            expectedFiles = listOf("target.txt"),
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
            prompt = "Exhaustion test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            throw RuntimeException("Persistent failure")
        }
        job.join()

        assertEquals("Step must execute up to maxAttempts (2)", 2, runCount.get())
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        val recovery = canonical.activeRecoveryPlan
        assertNotNull(recovery)
        assertEquals("Recovery plan status must be EXHAUSTED", RecoveryStatus.EXHAUSTED, recovery!!.status)
        assertEquals("TERMINATE", recovery.nextAction)
    }

    // 11. Cancellation during recovery
    @Test
    fun test11_cancellationDuringRecovery() = runBlocking {
        val taskId = "test-cancellation-recovery-step5"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Cancel Step",
            description = "Cancels during recovery action",
            expectedFiles = listOf("never.txt"),
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
            prompt = "Cancel test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                supervisor.requestStop(taskId, force = true)
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            runCount.incrementAndGet()
            File(workspaceDir, "dirty.txt").writeText("dirty")
            throw RuntimeException("Attempt 1 failure")
        }
        job.join()

        assertEquals(1, runCount.get())
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get(taskId)?.status)
        val canonical = canonicalRepo.getTask(taskId)!!
        val recovery = canonical.activeRecoveryPlan
        assertNotNull(recovery)
        assertEquals("Recovery status must be CANCELLED", RecoveryStatus.CANCELLED, recovery!!.status)
    }

    // 12. Recovery cannot advance step
    @Test
    fun test12_recoveryCannotAdvanceStep() = runBlocking {
        val taskId = "test-cannot-advance-step"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Fails once, retries",
            expectedFiles = listOf("s0.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1",
            description = "Next step",
            expectedFiles = listOf("s1.txt"),
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
            prompt = "Cannot advance test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        var stepIndexDuringRecovery: Int? = null
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                val current = canonicalRepo.getTask(taskId)
                stepIndexDuringRecovery = current?.plan?.currentStepIndex
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.stepOrder == 0 && activeStep.attempts == 1) {
                throw RuntimeException("Step 0 attempt 1 failure")
            }
            File(workspaceDir, "s${activeStep.stepOrder}.txt").writeText("done")
        }
        job.join()

        assertEquals("currentStepIndex during recovery must remain on the failing step (0)", 0, stepIndexDuringRecovery)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals("currentStepIndex advances only after completion of all steps", 2, canonical.plan.currentStepIndex)
    }

    // 13. Recovery cannot complete step directly
    @Test
    fun test13_recoveryCannotCompleteStepDirectly() = runBlocking {
        val taskId = "test-cannot-complete-directly"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Fails attempt 1",
            expectedFiles = listOf("valid.txt"),
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
            prompt = "Cannot complete directly test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var stepStatusDuringRecovery: StepStatus? = null
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                val current = canonicalRepo.getTask(taskId)
                stepStatusDuringRecovery = current?.plan?.steps?.get(0)?.status
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.attempts == 1) {
                throw RuntimeException("Attempt 1 failure")
            }
            File(workspaceDir, "valid.txt").writeText("done")
        }
        job.join()

        assertNotEquals("Step must NEVER be marked COMPLETED during recovery execution", StepStatus.COMPLETED, stepStatusDuringRecovery)
        assertEquals(StepStatus.RECOVERING, stepStatusDuringRecovery)
        val canonical = canonicalRepo.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
    }

    // 14. Future steps remain untouched
    @Test
    fun test14_futureStepsRemainUntouched() = runBlocking {
        val taskId = "test-future-steps-untouched"
        val step0 = ExecutionStep(
            stepId = "s0-id",
            stepOrder = 0,
            title = "Step 0",
            description = "Fails attempt 1",
            expectedFiles = listOf("s0.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepId = "s1-id",
            stepOrder = 1,
            title = "Step 1 Untouched",
            description = "Must remain strictly untouched during Step 0 recovery",
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
            prompt = "Future steps test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        var step1ObservedDuringRecovery: ExecutionStep? = null
        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                val current = canonicalRepo.getTask(taskId)
                step1ObservedDuringRecovery = current?.plan?.steps?.get(1)
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.stepOrder == 0 && activeStep.attempts == 1) {
                throw RuntimeException("Attempt 1 failure on step 0")
            }
            File(workspaceDir, "s${activeStep.stepOrder}.txt").writeText("done")
        }
        job.join()

        assertNotNull(step1ObservedDuringRecovery)
        assertEquals("Step 1 must remain PENDING while Step 0 is recovering", StepStatus.PENDING, step1ObservedDuringRecovery!!.status)
        assertEquals("Step 1 title must not be modified", "Step 1 Untouched", step1ObservedDuringRecovery!!.title)
        assertEquals("Step 1 attempts must remain 0", 0, step1ObservedDuringRecovery!!.attempts)
    }

    // 15. Brain snapshot & policy preservation
    @Test
    fun test15_brainAndPolicyPreservation() = runBlocking {
        val taskId = "test-brain-policy-preservation"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Preservation Step",
            description = "Tests brain knowledge feedback and global policies",
            expectedFiles = listOf("final.txt"),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-15",
            projectSlug = "slug-15",
            chatId = "c15",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Preservation test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.attempts == 1) {
                throw RuntimeException("Transient failure to trigger learning")
            }
            File(workspaceDir, "final.txt").writeText("done")
        }
        job.join()

        // 1. Verify learning entries recorded in project brain
        val memories = knowledgeRepo.findByProject("proj-15")
        assertTrue("BrainLearningService must record task outcome knowledge entries", memories.isNotEmpty())

        // 2. Verify GlobalExecutionPolicies are intact
        assertTrue(GlobalExecutionPolicies.DEFAULT_POLICIES.isNotEmpty())
        assertTrue(GlobalExecutionPolicies.isGlobalPolicyKey(GlobalExecutionPolicies.REASONING_BUDGET_POLICY_NAME))
        assertTrue(GlobalExecutionPolicies.isGlobalPolicyKey(GlobalExecutionPolicies.EXECUTION_EFFICIENCY_POLICY_NAME))
    }
}
