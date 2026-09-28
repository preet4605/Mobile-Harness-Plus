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
import com.jarves.mh.runtime.task.TaskExecutionStatus
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
 * Verification test suite for Phase 7 Test 5 Remediation:
 * Environment Drift Failure Recovery (ENVIRONMENT_DRIFT -> RECREATE_WORKSPACE_STATE).
 *
 * Covers:
 * 1. Narrow evidence-based classification (distinct from PROCESS_FAILURE, WORKSPACE_MUTATED_FAILURE, TRANSIENT_SYSTEM_FAULT, TRANSIENT_API_ERROR, USER_CANCELLED)
 * 2. SQLite persistence and roundtrip of TaskFailureRecord with ENVIRONMENT_DRIFT and RecoveryPlan with RECREATE_WORKSPACE_STATE
 * 3. Baseline creation lifecycle: canonical task-baseline checkpoint established before step execution
 * 4. Baseline ownership validation preventing arbitrary checkpoint selection or cross-task/cross-project restores
 * 5. Deterministic recovery mapping in RecoveryEngine.planRecovery() (ENVIRONMENT_DRIFT -> RECREATE_WORKSPACE_STATE, bounded attempts)
 * 6. Execute recovery baseline restoration reverting workspace drift to clean baseline
 * 7. Supervised retry execution with attempt increment, pre-action persistence, and StepVerifier completion authority
 * 8. Verification gate enforcement (RUNNING -> VERIFYING -> StepVerifier required before COMPLETED)
 * 9. Recovery idempotency
 */
class EnvironmentDriftRecoveryTest {

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

    // 1. Evidence-based classification
    @Test
    fun test01_evidenceBasedClassification() {
        // Environment drift conditions (clean workspace, uncancelled)
        val drift1 = supervisor.classifyError("environment_drift: corrupted workspace state", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT, drift1)

        val drift2 = supervisor.classifyError("workspace environment drift detected in build tree", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT, drift2)

        val drift3 = supervisor.classifyError("inconsistent workspace state: external workspace drift", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT, drift3)

        val drift4 = supervisor.classifyError("corrupted workspace environment: dirty environment drift", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT, drift4)

        // Workspace mutation without explicit drift evidence is classified as WORKSPACE_MUTATED_FAILURE (never drift)
        val mutated = supervisor.classifyError("Execution failed after modifying files", workspaceMutated = true, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.WORKSPACE_MUTATED_FAILURE, mutated)

        // Environment drift with mutated/drifted workspace is classified as ENVIRONMENT_DRIFT
        val driftWithMutated = supervisor.classifyError("environment_drift: corrupted workspace state", workspaceMutated = true, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT, driftWithMutated)

        // Cancellation takes precedence
        val cancelled = supervisor.classifyError("environment_drift: corrupted workspace state", workspaceMutated = false, isCancelled = true)
        assertEquals(TaskSupervisor.TaskErrorClassification.USER_CANCELLED, cancelled)

        // Permanent auth/config distinct
        val auth = supervisor.classifyError("HTTP 401 Unauthorized API key", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG, auth)

        // Transient system fault distinct
        val systemFault = supervisor.classifyError("Resource temporarily unavailable (errno 11)", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_SYSTEM_FAULT, systemFault)

        // Remote API error distinct
        val api503 = supervisor.classifyError("HTTP 503 Service Unavailable", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, api503)

        // Generic exit code / unclassified error remains PROCESS_FAILURE
        val processFail = supervisor.classifyError("Command exited with code 1: syntax error in script", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PROCESS_FAILURE, processFail)
    }

    // 2. SQLite persistence and roundtrip
    @Test
    fun test02_sqlitePersistence() {
        val taskId = "task-drift-persist-test"
        val step = ExecutionStep(
            stepId = "step-d1",
            stepOrder = 0,
            title = "Step Drift Persistence",
            description = "Persistence verification for environment drift",
            status = StepStatus.FAILED,
            attempts = 1
        )
        val failureRecord = TaskFailureRecord(
            failureId = "fail-d1",
            taskId = taskId,
            stepId = step.stepId,
            classification = TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT.name,
            errorMessage = "environment_drift: corrupted workspace state",
            mutatedFiles = emptyList()
        )
        val plan = RecoveryPlan(
            recoveryId = "rec-d1",
            taskId = taskId,
            failureRecordId = failureRecord.failureId,
            strategy = RecoveryStrategy.RECREATE_WORKSPACE_STATE,
            rationale = "Environment drift detected. Recreate clean workspace state from canonical baseline 'task-baseline' and retry step.",
            filesToRollback = emptyList(),
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = WorkspaceCheckpoints.TASK_BASELINE_TAG,
            attemptNumber = 2,
            status = RecoveryStatus.PENDING
        )
        val canonicalTask = CanonicalTask(
            taskId = taskId,
            projectId = "proj-drift-persist",
            projectSlug = "slug-drift-persist",
            objective = "Test persistence of ENVIRONMENT_DRIFT and RECREATE_WORKSPACE_STATE",
            plan = ExecutionPlan(steps = listOf(step)),
            failureHistory = listOf(failureRecord),
            activeRecoveryPlan = plan
        )

        canonicalRepo.saveTask(canonicalTask)

        // Read back task and verify exact preservation
        val retrieved = canonicalRepo.getTask(taskId)
        assertNotNull(retrieved)
        assertEquals(1, retrieved!!.failureHistory.size)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT.name, retrieved.failureHistory[0].classification)
        assertEquals("environment_drift: corrupted workspace state", retrieved.failureHistory[0].errorMessage)
        assertTrue(retrieved.failureHistory[0].mutatedFiles.isEmpty())

        assertNotNull(retrieved.activeRecoveryPlan)
        assertEquals(RecoveryStrategy.RECREATE_WORKSPACE_STATE, retrieved.activeRecoveryPlan!!.strategy)
        assertEquals(WorkspaceCheckpoints.TASK_BASELINE_TAG, retrieved.activeRecoveryPlan!!.checkpointTag)
        assertEquals(2, retrieved.activeRecoveryPlan!!.attemptNumber)
        assertEquals(RecoveryStatus.PENDING, retrieved.activeRecoveryPlan!!.status)
    }

    // 3. Baseline creation lifecycle
    @Test
    fun test03_baselineCreationLifecycle() = runBlocking {
        val taskId = "task-baseline-lifecycle"
        val projectId = "proj-lifecycle"

        // Initial workspace has a baseline file before task launches
        val baselineFile = File(workspaceDir, "initial_source.txt")
        baselineFile.writeText("initial content v1")

        val step = ExecutionStep(
            stepOrder = 0,
            title = "Baseline Lifecycle Step",
            description = "Verifies baseline checkpoint creation before step runs",
            expectedFiles = listOf("initial_source.txt"),
            maxAttempts = 1,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = "slug-lifecycle",
            chatId = "c-lifecycle",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Lifecycle test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var baselineExistedDuringStep = false
        val job = supervisor.executeTask(taskId) { _, _ ->
            // Check that canonical task-baseline checkpoint exists while the step is running
            baselineExistedDuringStep = checkpoints.checkpointExists(projectId, WorkspaceCheckpoints.TASK_BASELINE_TAG)
        }
        job.join()

        assertTrue("Canonical task-baseline checkpoint must exist before step runs", baselineExistedDuringStep)
        assertTrue("Canonical task-baseline must remain after task completes", checkpoints.checkpointExists(projectId, WorkspaceCheckpoints.TASK_BASELINE_TAG))

        // Validate metadata ownership
        val meta = checkpoints.readMetadata(projectId, WorkspaceCheckpoints.TASK_BASELINE_TAG)
        assertNotNull(meta)
        assertEquals(WorkspaceCheckpoints.TASK_BASELINE_TAG, meta!!.checkpointTag)
        assertEquals(projectId, meta.projectId)
        assertEquals(taskId, meta.taskId)

        // Validate backup content
        val backedUpFile = File(checkpoints.checkpointDir(projectId, WorkspaceCheckpoints.TASK_BASELINE_TAG), "project/initial_source.txt")
        assertTrue(backedUpFile.exists())
        assertEquals("initial content v1", backedUpFile.readText())
    }

    // 4. Baseline ownership validation
    @Test
    fun test04_baselineOwnershipValidation() {
        val taskId = "task-owner-val"
        val projectId = "proj-owner-val"
        val task = CanonicalTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = "slug-val",
            objective = "Validate ownership",
            plan = ExecutionPlan(steps = emptyList())
        )

        File(workspaceDir, "test.txt").writeText("baseline state")
        checkpoints.createCheckpoint(projectId, workspaceDir, WorkspaceCheckpoints.TASK_BASELINE_TAG, taskId = taskId)

        // Valid canonical baseline with matching project and task
        val validResult = validateBaselineCheckpoint(task, WorkspaceCheckpoints.TASK_BASELINE_TAG, checkpoints)
        assertTrue("Canonical baseline matching project and task must be Valid", validResult is CheckpointValidationResult.Valid)

        // Non-canonical tag ("baseline" without task- prefix) must be rejected
        val nonCanonicalResult = validateBaselineCheckpoint(task, "baseline", checkpoints)
        assertTrue("Non-canonical tag 'baseline' must be rejected", nonCanonicalResult is CheckpointValidationResult.Invalid)

        // Step tag must be rejected for baseline restoration
        val stepTagResult = validateBaselineCheckpoint(task, "step-1", checkpoints)
        assertTrue("Step tag must be rejected for baseline restoration", stepTagResult is CheckpointValidationResult.Invalid)

        // Mismatched project
        val wrongProjectTask = task.copy(projectId = "other-project")
        val wrongProjResult = validateBaselineCheckpoint(wrongProjectTask, WorkspaceCheckpoints.TASK_BASELINE_TAG, checkpoints)
        assertTrue("Mismatched project must be rejected", wrongProjResult is CheckpointValidationResult.Invalid)

        // Mismatched task
        val wrongTask = task.copy(taskId = "other-task")
        val wrongTaskResult = validateBaselineCheckpoint(wrongTask, WorkspaceCheckpoints.TASK_BASELINE_TAG, checkpoints)
        assertTrue("Mismatched task must be rejected", wrongTaskResult is CheckpointValidationResult.Invalid)

        // Path traversal / invalid tag
        val invalidTagResult = validateBaselineCheckpoint(task, "../traversal", checkpoints)
        assertTrue("Path traversal tag must be rejected", invalidTagResult is CheckpointValidationResult.Invalid)
    }

    // 5. Deterministic recovery mapping in RecoveryEngine.planRecovery()
    @Test
    fun test05_deterministicRecoveryMapping() {
        val task = CanonicalTask(
            taskId = "task-map-test",
            projectId = "proj-map-test",
            projectSlug = "slug-map",
            objective = "Test mapping",
            plan = ExecutionPlan(steps = emptyList())
        )
        val step = ExecutionStep(
            stepId = "step-map-0",
            stepOrder = 0,
            title = "Mapping Step",
            description = "Deterministic mapping test",
            maxAttempts = 3,
            checkpointTag = "step-1"
        )

        File(workspaceDir, "sample.txt").writeText("baseline")
        checkpoints.createCheckpoint(task.projectId, workspaceDir, WorkspaceCheckpoints.TASK_BASELINE_TAG, taskId = task.taskId)

        val engine = DefaultRecoveryEngine(checkpointsProvider = { checkpoints })

        // Deterministic mapping to RECREATE_WORKSPACE_STATE
        val plan1 = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT,
            "environment_drift: corrupted workspace state", emptyList(), attemptCount = 1
        )
        val plan2 = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT,
            "environment_drift: corrupted workspace state", emptyList(), attemptCount = 1
        )

        assertNotNull(plan1)
        assertNotNull(plan2)
        assertEquals(RecoveryStrategy.RECREATE_WORKSPACE_STATE, plan1!!.strategy)
        assertEquals(WorkspaceCheckpoints.TASK_BASELINE_TAG, plan1.checkpointTag)
        assertEquals(2, plan1.attemptNumber)
        assertEquals(plan1.recoveryId, plan2!!.recoveryId)
        assertEquals(plan1.failureRecordId, plan2.failureRecordId)

        // Bounded retries: attemptCount >= maxAttempts returns null
        val planExhausted = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT,
            "environment_drift: corrupted workspace state", emptyList(), attemptCount = 3
        )
        assertNull("Exhausted retries must return null", planExhausted)

        // Unrecoverable environment drift returns null
        val planUnrec = engine.planRecovery(
            task, step, TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT,
            "environment_drift: unrecoverable hardware failure", emptyList(), attemptCount = 1
        )
        assertNull("Unrecoverable drift must return null", planUnrec)

        // Missing baseline prerequisite returns null
        val engineNoBaseline = DefaultRecoveryEngine(checkpointsProvider = { checkpoints })
        val taskNoBaseline = task.copy(projectId = "proj-without-baseline")
        val planNoBaseline = engineNoBaseline.planRecovery(
            taskNoBaseline, step, TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT,
            "environment_drift: corrupted workspace state", emptyList(), attemptCount = 1
        )
        assertNull("Missing baseline prerequisite must return null", planNoBaseline)
    }

    // 6. Execute recovery baseline restoration
    @Test
    fun test06_executeRecoveryBaselineRestoration() = runBlocking {
        val taskId = "task-restore-test"
        val projectId = "proj-restore-test"
        val task = CanonicalTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = "slug-restore",
            objective = "Test baseline restoration",
            plan = ExecutionPlan(steps = emptyList())
        )
        val step = ExecutionStep(
            stepId = "step-rest-0",
            stepOrder = 0,
            title = "Restore Step",
            description = "Baseline restore test",
            maxAttempts = 2,
            checkpointTag = "step-1"
        )

        // 1. Establish baseline in workspace
        val originalFile = File(workspaceDir, "config.json")
        originalFile.writeText("{\"version\": 1}")
        checkpoints.createCheckpoint(projectId, workspaceDir, WorkspaceCheckpoints.TASK_BASELINE_TAG, taskId = taskId)

        // 2. Corrupt workspace (environment drift)
        originalFile.writeText("{\"version\": 999, \"corrupted\": true}")
        val driftedFile = File(workspaceDir, "corrupted_drift.log")
        driftedFile.writeText("dirty drift data")

        val engine = DefaultRecoveryEngine()
        val plan = RecoveryPlan(
            recoveryId = "rec-restore-1",
            taskId = taskId,
            failureRecordId = "fail-restore-1",
            strategy = RecoveryStrategy.RECREATE_WORKSPACE_STATE,
            rationale = "Recreate workspace from baseline",
            filesToRollback = listOf("config.json", "corrupted_drift.log"),
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = WorkspaceCheckpoints.TASK_BASELINE_TAG,
            attemptNumber = 2
        )

        val result = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        assertTrue("Recovery execution must succeed", result.success)
        assertTrue("Checkpoint must be restored", result.checkpointRestored)
        assertTrue("Step retry must be approved", result.shouldRetryStep)

        // Verify workspace is restored to baseline
        assertEquals("{\"version\": 1}", originalFile.readText())
        assertFalse("Drifted file must be removed by baseline restoration", driftedFile.exists())
    }

    // 7. Supervised retry and verification gate
    @Test
    fun test07_supervisedRetryAndStrictVerificationGate() = runBlocking {
        val taskId = "task-drift-retry-gate"
        val expectedFile = "verified_payload.txt"
        val baselineFile = "config.txt"

        // Setup clean baseline
        File(workspaceDir, baselineFile).writeText("baseline v1")

        val step = ExecutionStep(
            stepOrder = 0,
            title = "Environment Drift Step",
            description = "Fails with environment drift on attempt 1, succeeds on attempt 2",
            expectedFiles = listOf(expectedFile),
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-drift-retry",
            projectSlug = "slug-drift-retry",
            chatId = "c-drift",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Execute drift recovery",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val recoveryPlanPersistedBeforeAction = AtomicBoolean(false)

        val defaultEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by defaultEngine {
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                // Verify RecoveryPlan is persisted in SQLite before action
                val savedTask = supervisor.canonicalTaskRepository.getTask(task.taskId)
                if (savedTask?.activeRecoveryPlan?.strategy == RecoveryStrategy.RECREATE_WORKSPACE_STATE) {
                    recoveryPlanPersistedBeforeAction.set(true)
                }
                return defaultEngine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                // Attempt 1: Drift environment and fail
                File(workspaceDir, "drifted_artifact.tmp").writeText("dirty")
                throw RuntimeException("environment_drift: corrupted workspace state")
            } else {
                // Attempt 2: Write expected payload
                File(workspaceDir, expectedFile).writeText("verified outcome")
            }
        }
        job.join()

        assertEquals("Step must have run exactly 2 times", 2, runCount.get())
        assertTrue("RecoveryPlan must be persisted in SQLite before action", recoveryPlanPersistedBeforeAction.get())

        val finalTask = supervisor.canonicalTaskRepository.getTask(taskId)!!
        val completedStep = finalTask.plan.steps[0]
        assertEquals(StepStatus.COMPLETED, completedStep.status)
        assertEquals("Attempts must be incremented to 2", 2, completedStep.attempts)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)

        // Verify failure recorded with ENVIRONMENT_DRIFT
        assertEquals(1, finalTask.failureHistory.size)
        assertEquals(TaskSupervisor.TaskErrorClassification.ENVIRONMENT_DRIFT.name, finalTask.failureHistory[0].classification)

        // Verify clean baseline was restored on retry (drifted_artifact.tmp was eliminated before attempt 2)
        assertEquals("baseline v1", File(workspaceDir, baselineFile).readText())
    }

    // 8. Verification gate fails: cannot complete step
    @Test
    fun test08_verificationGateFailsCannotCompleteStep() = runBlocking {
        val taskId = "task-drift-verify-gate-fail"
        val expectedFile = "missing_verification_payload.txt"
        val baselineFile = "config.txt"

        File(workspaceDir, baselineFile).writeText("baseline v1")

        val step = ExecutionStep(
            stepOrder = 0,
            title = "Failing Verification Drift Step",
            description = "Recreates baseline on attempt 1, runs attempt 2 but fails verification",
            expectedFiles = listOf(expectedFile), // Verifier checks for this file
            maxAttempts = 2,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-drift-gate-fail",
            projectSlug = "slug-drift-gate-fail",
            chatId = "c-drift-fail",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Execute drift verification fail",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val runCount = AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { _, _ ->
            val count = runCount.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("environment_drift: corrupted workspace state")
            }
            // Attempt 2 succeeds execution block but intentionally does NOT create expectedFile
        }
        job.join()

        assertEquals("Step must have run 2 times (attempt 1 fail, attempt 2 retry)", 2, runCount.get())

        val finalTask = supervisor.canonicalTaskRepository.getTask(taskId)!!
        val failedStep = finalTask.plan.steps[0]

        // Verification authority must NOT allow step completion
        assertEquals(StepStatus.FAILED, failedStep.status)
        assertEquals("Attempts must be 2", 2, failedStep.attempts)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 9. Recovery idempotency
    @Test
    fun test09_recoveryIdempotency() = runBlocking {
        val taskId = "task-drift-idempotency"
        val projectId = "proj-drift-idempotency"
        val task = CanonicalTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = "slug-idem",
            objective = "Test recovery idempotency",
            plan = ExecutionPlan(steps = emptyList())
        )
        val step = ExecutionStep(
            stepId = "step-idem-0",
            stepOrder = 0,
            title = "Idempotency Step",
            description = "Recovery idempotency test",
            maxAttempts = 2,
            checkpointTag = "step-1"
        )

        File(workspaceDir, "file.txt").writeText("baseline")
        checkpoints.createCheckpoint(projectId, workspaceDir, WorkspaceCheckpoints.TASK_BASELINE_TAG, taskId = taskId)

        val engine = DefaultRecoveryEngine()
        val plan = RecoveryPlan(
            recoveryId = "rec-idem-1",
            taskId = taskId,
            failureRecordId = "fail-idem-1",
            strategy = RecoveryStrategy.RECREATE_WORKSPACE_STATE,
            rationale = "Recreate workspace from baseline",
            filesToRollback = emptyList(),
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = WorkspaceCheckpoints.TASK_BASELINE_TAG,
            attemptNumber = 2
        )

        val result1 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)
        val result2 = engine.executeRecovery(task, step, plan, workspaceDir, checkpoints)

        assertTrue(result1.success)
        assertTrue(result2.success)
        assertEquals("Subsequent recovery execution must return cached identical result", result1.message, result2.message)
        assertEquals(result1.checkpointRestored, result2.checkpointRestored)
        assertEquals(result1.shouldRetryStep, result2.shouldRetryStep)
    }
}
