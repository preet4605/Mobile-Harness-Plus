package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.BrainLearningService
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStatus
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Verification test suite for Phase 7 Test 4 Remediation:
 * Transient System Fault Recovery with RETRY_STEP_DIRECT without checkpoint restoration.
 *
 * Covers:
 * 1. Evidence-based classification (distinct from PROCESS_FAILURE, TRANSIENT_API_ERROR, WORKSPACE_MUTATED_FAILURE, USER_CANCELLED)
 * 2. SQLite persistence of TaskFailureRecord with TRANSIENT_SYSTEM_FAULT and RecoveryPlan with RETRY_STEP_DIRECT
 * 3. Deterministic mapping in RecoveryEngine.planRecovery() (clean workspace -> RETRY_STEP_DIRECT, bounded attempts)
 * 4. Strict absence of checkpoint restoration on RETRY_STEP_DIRECT
 * 5. Supervised retry execution with attempt increment and pre-action persistence
 * 6. Verification gate enforcement (RUNNING -> VERIFYING -> StepVerifier required before COMPLETED)
 * 7. Recovery idempotency
 */
class TransientSystemFaultRecoveryTest {

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

    // 1. Evidence-based classification distinct from PROCESS_FAILURE, TRANSIENT_API_ERROR, workspace mutation
    @Test
    fun test01_classificationDistinctiveness() {
        // Transient system fault conditions (clean workspace, uncancelled)
        val fault1 = supervisor.classifyError("Resource temporarily unavailable (errno 11)", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, fault1)

        val fault2 = supervisor.classifyError("sqlite_busy: database is locked", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, fault2)

        val fault3 = supervisor.classifyError("Error: EBUSY: resource busy or locked", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, fault3)

        val fault4 = supervisor.classifyError("interrupted system call while acquiring file lock", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, fault4)

        val fault5 = supervisor.classifyError("TRANSIENT_SYSTEM_FAULT: transient system failure", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, fault5)

        // Workspace mutation takes precedence over transient system fault
        val mutated = supervisor.classifyError("Resource temporarily unavailable", workspaceMutated = true, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.WORKSPACE_MUTATED_FAILURE, mutated)

        // Cancellation takes precedence
        val cancelled = supervisor.classifyError("Resource temporarily unavailable", workspaceMutated = false, isCancelled = true)
        assertEquals(TaskSupervisor.TaskErrorClassification.USER_CANCELLED, cancelled)

        // Remote API errors distinct from system faults
        val api503 = supervisor.classifyError("HTTP 503 Service Unavailable", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, api503)

        val api429 = supervisor.classifyError("HTTP 429 rate limit exceeded", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, api429)

        // Permanent auth/config distinct
        val auth = supervisor.classifyError("HTTP 401 Unauthorized API key", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG, auth)

        // Generic exit code / unclassified error remains PROCESS_FAILURE
        val processFail = supervisor.classifyError("Command exited with code 1: syntax error in script", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE, processFail)
    }

    // 2. SQLite persistence of TaskFailureRecord and RecoveryPlan
    @Test
    fun test02_sqlitePersistence() {
        val taskId = "task-persist-test"
        val step = ExecutionStep(
            stepId = "step-p1",
            stepOrder = 0,
            title = "Step P1",
            description = "Persistence verification",
            status = StepStatus.FAILED,
            attempts = 1
        )
        val failureRecord = TaskFailureRecord(
            failureId = "fail-p1",
            taskId = taskId,
            stepId = step.stepId,
            classification = TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT.name,
            errorMessage = "Resource temporarily unavailable",
            mutatedFiles = emptyList()
        )
        val plan = RecoveryPlan(
            recoveryId = "rec-p1",
            taskId = taskId,
            failureRecordId = failureRecord.failureId,
            strategy = RecoveryStrategy.RETRY_STEP_DIRECT,
            rationale = "Transient system fault with clean workspace. Retry step directly without checkpoint restoration.",
            filesToRollback = emptyList(),
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = null,
            attemptNumber = 2,
            status = RecoveryStatus.PENDING
        )
        val canonicalTask = CanonicalTask(
            taskId = taskId,
            projectId = "proj-persist",
            projectSlug = "slug-persist",
            objective = "Test persistence of TRANSIENT_SYSTEM_FAULT and RETRY_STEP_DIRECT",
            plan = ExecutionPlan(steps = listOf(step)),
            failureHistory = listOf(failureRecord),
            activeRecoveryPlan = plan
        )

        canonicalRepo.saveTask(canonicalTask)

        // Read back task and verify exact preservation
        val retrieved = canonicalRepo.getTask(taskId)
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.failureHistory.size)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT.name, retrieved.failureHistory[0].classification)
        assertEquals("Resource temporarily unavailable", retrieved.failureHistory[0].errorMessage)
        assertTrue(retrieved.failureHistory[0].mutatedFiles.isEmpty())

        assertNotNull(retrieved.activeRecoveryPlan)
        assertEquals(RecoveryStrategy.RETRY_STEP_DIRECT, retrieved.activeRecoveryPlan!!.strategy)
        assertEquals("rec-p1", retrieved.activeRecoveryPlan!!.recoveryId)
        assertEquals(RecoveryStatus.PENDING, retrieved.activeRecoveryPlan!!.status)
        assertNull(retrieved.activeRecoveryPlan!!.checkpointTag)
    }

    // 3. Deterministic mapping in RecoveryEngine.planRecovery()
    @Test
    fun test03_deterministicMappingAndBounds() {
        val engine = DefaultRecoveryEngine()
        val task = CanonicalTask(
            taskId = "task-map-test",
            projectId = "proj-map",
            projectSlug = "slug-map",
            objective = "Map TRANSIENT_SYSTEM_FAULT"
        )
        val step = ExecutionStep(
            stepId = "step-map-1",
            stepOrder = 0,
            title = "Step 0",
            description = "Step",
            maxAttempts = 2,
            checkpointTag = "step-1"
        )

        // Clean workspace -> RETRY_STEP_DIRECT without checkpoint tag
        val plan1 = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT,
            "Resource temporarily unavailable", emptyList(), attemptCount = 0
        )
        assertNotNull(plan1)
        assertEquals(RecoveryStrategy.RETRY_STEP_DIRECT, plan1!!.strategy)
        assertNull(plan1.checkpointTag)
        assertTrue(plan1.filesToRollback.isEmpty())
        assertEquals(1, plan1.attemptNumber)

        // Calling it again produces identical deterministic plan
        val plan2 = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT,
            "Resource temporarily unavailable", emptyList(), attemptCount = 0
        )
        assertNotNull(plan2)
        assertEquals(plan1.recoveryId, plan2!!.recoveryId)
        assertEquals(plan1.strategy, plan2.strategy)

        // Exhausted attempts (attemptCount >= maxAttempts) -> null
        val planExhausted = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT,
            "Resource temporarily unavailable", emptyList(), attemptCount = 2
        )
        assertNull("Exhausted attempts must not produce recovery plan", planExhausted)
    }

    // 4. RETRY_STEP_DIRECT never restores a checkpoint
    @Test
    fun test04_noCheckpointRestorationOnRetryStepDirect() = runBlocking {
        val engine = DefaultRecoveryEngine()
        val task = CanonicalTask(
            taskId = "task-no-restore",
            projectId = "proj-no-restore",
            projectSlug = "slug-no-restore",
            objective = "Ensure no checkpoint restore"
        )
        val step = ExecutionStep(
            stepId = "step-nr-1",
            stepOrder = 0,
            title = "Step NR",
            description = "No restore test",
            maxAttempts = 2
        )
        val plan = RecoveryPlan(
            recoveryId = "rec-nr-1",
            taskId = task.taskId,
            failureRecordId = "fail-nr-1",
            strategy = RecoveryStrategy.RETRY_STEP_DIRECT,
            rationale = "Direct step retry",
            targetStepIndex = 0,
            attemptNumber = 2
        )

        val testFile = File(workspaceDir, "test.txt")
        testFile.writeText("initial checkpoint content")
        checkpoints.createCheckpoint(task.projectId, workspaceDir, "step-1", task.taskId, step.stepId, 1)

        // Mutate workspace file
        testFile.writeText("mutated workspace content")

        val result = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        assertTrue("Recovery execution must succeed", result.success)
        assertEquals(RecoveryStrategy.RETRY_STEP_DIRECT, result.strategy)
        assertFalse("checkpointRestored must be false for RETRY_STEP_DIRECT", result.checkpointRestored)
        assertTrue("shouldRetryStep must be true", result.shouldRetryStep)
        // File must NOT be reverted to checkpoint content
        assertEquals("mutated workspace content", testFile.readText())

        // Even with null checkpoints and null workspaceDir, RETRY_STEP_DIRECT succeeds without needing checkpoints
        val resultNullCheckpoints = engine.executeRecovery(task, step, plan, null, null)
        assertTrue(resultNullCheckpoints.success)
        assertFalse(resultNullCheckpoints.checkpointRestored)
        assertTrue(resultNullCheckpoints.shouldRetryStep)
    }

    // 5 & 6. Supervised retry execution, attempt count increment, and strict verification gate
    @Test
    fun test05_supervisedRetryAndStrictVerificationGate() = runBlocking {
        val taskId = "task-transient-sys-retry"
        val expectedFile = "completed_data.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Transient System Step",
            description = "Fails with transient system fault on attempt 1, writes file on attempt 2",
            expectedFiles = listOf(expectedFile),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-sys-retry",
            projectSlug = "slug-sys-retry",
            chatId = "c-sys",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Execute system work",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val recoveryPlanPersistedBeforeAttempt2 = AtomicBoolean(false)

        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                // Verify RecoveryPlan is persisted in SQLite before execution proceeds
                val savedTask = supervisor.canonicalTaskRepository.getTask(task.taskId)
                if (savedTask?.activeRecoveryPlan?.strategy == RecoveryStrategy.RETRY_STEP_DIRECT) {
                    recoveryPlanPersistedBeforeAttempt2.set(true)
                }
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                // Attempt 1: Transient system fault with clean workspace
                throw RuntimeException("Resource temporarily unavailable (transient_system_fault)")
            } else {
                // Attempt 2: Write expected file
                File(workspaceDir, expectedFile).writeText("deterministic result")
            }
        }
        job.join()

        assertEquals("Step must have run exactly 2 times (attempt 1 fail, attempt 2 retry)", 2, runCount.get())
        assertTrue("RecoveryPlan must be persisted in SQLite before retry action", recoveryPlanPersistedBeforeAttempt2.get())

        val finalTask = supervisor.canonicalTaskRepository.getTask(taskId)!!
        val completedStep = finalTask.plan.steps[0]
        assertEquals(StepStatus.COMPLETED, completedStep.status)
        assertEquals("Attempts must be incremented to 2", 2, completedStep.attempts)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)

        // Verify failure was recorded with TRANSIENT_SYSTEM_FAULT
        assertEquals(1, finalTask.failureHistory.size)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT.name, finalTask.failureHistory[0].classification)
    }

    // 6b. Verification gate enforcement: If verification fails after retrying, step cannot complete
    @Test
    fun test06_verificationGateFailsCannotCompleteStep() = runBlocking {
        val taskId = "task-verify-failure-gate"
        val expectedFile = "verified_payload.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Failing Verification Step",
            description = "Passes execution on attempt 2 but fails step verification",
            expectedFiles = listOf(expectedFile), // Verifier checks for this file
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-verify-gate",
            projectSlug = "slug-verify-gate",
            chatId = "c-vg",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run with verification gate",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("Resource temporarily unavailable: transient system fault")
            }
            // On attempt 2, deliberately do NOT create expectedFile, so StepVerifier fails
        }
        job.join()

        assertEquals(2, runCount.get())
        val finalTask = supervisor.canonicalTaskRepository.getTask(taskId)!!
        val stepResult = finalTask.plan.steps[0]
        // Step must NOT be COMPLETED
        assertFalse("Step must not complete when verification fails", stepResult.status == StepStatus.COMPLETED)
        assertEquals(StepStatus.FAILED, stepResult.status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 7. Idempotency of RecoveryEngine.executeRecovery()
    @Test
    fun test07_idempotencyOfExecuteRecovery() = runBlocking {
        val engine = DefaultRecoveryEngine()
        val task = CanonicalTask(
            taskId = "task-idemp",
            projectId = "proj-idemp",
            projectSlug = "slug-idemp",
            objective = "Idempotency test"
        )
        val step = ExecutionStep(
            stepId = "step-idemp-1",
            stepOrder = 0,
            title = "Step Idempotency",
            description = "Step",
            maxAttempts = 2
        )
        val plan = RecoveryPlan(
            recoveryId = "rec-idemp-unique",
            taskId = task.taskId,
            failureRecordId = "fail-idemp-1",
            strategy = RecoveryStrategy.RETRY_STEP_DIRECT,
            rationale = "Direct retry",
            targetStepIndex = 0,
            attemptNumber = 2
        )

        val result1 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        val result2 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)

        // Both calls must return identical result instance
        assertEquals(result1.success, result2.success)
        assertEquals(result1.strategy, result2.strategy)
        assertEquals(result1.checkpointRestored, result2.checkpointRestored)
        assertEquals(result1.shouldRetryStep, result2.shouldRetryStep)
        assertEquals(result1.message, result2.message)
    }
}
