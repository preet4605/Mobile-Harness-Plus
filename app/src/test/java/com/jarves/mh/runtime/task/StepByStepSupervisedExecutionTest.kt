package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.PlanStatus
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Authoritative verification test suite for Phase 6 Step 3:
 * "Step-by-Step Supervised Execution".
 *
 * Verifies that TaskSupervisor executes the persisted ExecutionPlan
 * strictly one step at a time through the supervised lifecycle:
 * PENDING -> RUNNING -> VERIFYING -> COMPLETED (or RECOVERING -> RUNNING -> VERIFYING).
 */
class StepByStepSupervisedExecutionTest {

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

    // 1. Executes ONLY current step
    @Test
    fun test01_executesOnlyCurrentStep() = runBlocking {
        val taskId = "step3-test-1"
        val step0 = ExecutionStep(stepOrder = 0, title = "Step 0", description = "Run step 0", verificationCommand = "true", status = StepStatus.PENDING)
        val step1 = ExecutionStep(stepOrder = 1, title = "Step 1", description = "Run step 1", verificationCommand = "true", status = StepStatus.PENDING)
        val step2 = ExecutionStep(stepOrder = 2, title = "Step 2", description = "Run step 2", verificationCommand = "true", status = StepStatus.PENDING)
        val plan = ExecutionPlan(steps = listOf(step0, step1, step2), currentStepIndex = 0)

        supervisor.createTask(
            taskId = taskId,
            projectId = "p-1",
            projectSlug = "slug-1",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = plan
        )

        var step0Executed = false
        var step1WhileStep0Running = false
        var step2WhileStep0Running = false

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            if (activeStep.stepOrder == 0) {
                step0Executed = true
                val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
                step1WhileStep0Running = canonical?.plan?.steps?.get(1)?.status != StepStatus.PENDING
                step2WhileStep0Running = canonical?.plan?.steps?.get(2)?.status != StepStatus.PENDING
            }
        }
        job.join()

        assertTrue(step0Executed)
        assertFalse("Future step 1 must remain PENDING while step 0 runs", step1WhileStep0Running)
        assertFalse("Future step 2 must remain PENDING while step 0 runs", step2WhileStep0Running)
    }

    // 2. Correct step objective and acceptance criteria injected into execution context
    @Test
    fun test02_correctStepObjectiveAndCriteriaInjected() = runBlocking {
        val taskId = "step3-test-2"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Migrate Schema",
            description = "Run schema migration script",
            objective = "Apply v2 migration",
            acceptanceCriteria = listOf("Schema tables exist", "Zero column errors"),
            expectedFiles = listOf("migrations/v2.sql"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-2",
            projectSlug = "slug-2",
            chatId = "c-2",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Database Migration",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var injectedContext: String? = null
        var observedObjective: String? = null
        var observedCriteria: List<String>? = null

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            observedObjective = activeStep.objective
            observedCriteria = activeStep.acceptanceCriteria
            val snapshot = supervisor.getBrainSnapshot(task.taskId)
            injectedContext = snapshot?.renderedContext

            val file = File(workspaceDir, "migrations/v2.sql")
            file.parentFile?.mkdirs()
            file.writeText("-- migration applied")
        }
        job.join()

        assertEquals("Apply v2 migration", observedObjective)
        assertEquals(listOf("Schema tables exist", "Zero column errors"), observedCriteria)
        assertNotNull(injectedContext)
        assertTrue(injectedContext!!.contains("objective: Apply v2 migration"))
        assertTrue(injectedContext!!.contains("acceptance_criteria: Schema tables exist; Zero column errors"))
        assertTrue(injectedContext!!.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 3. Step checkpoint created BEFORE execution begins
    @Test
    fun test03_checkpointBeforeExecution() = runBlocking {
        val taskId = "step3-test-3"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Checkpoint Step",
            description = "Checkpoint step description",
            verificationCommand = "true",
            checkpointTag = "chk-step-1",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-3",
            projectSlug = "slug-3",
            chatId = "c-3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        File(workspaceDir, "baseline.txt").writeText("initial data")

        var checkpointExistedDuringExecution = false
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            checkpointExistedDuringExecution = checkpoints.checkpointExists("p-3", "chk-step-1")
        }
        job.join()

        assertTrue("Step checkpoint must be created before execution begins", checkpointExistedDuringExecution)
    }

    // 4. RUNNING status persisted before launching agent / process
    @Test
    fun test04_runningPersistedBeforeLaunch() = runBlocking {
        val taskId = "step3-test-4"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Launch Step",
            description = "Launch step description",
            verificationCommand = "true",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-4",
            projectSlug = "slug-4",
            chatId = "c-4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var persistedStatusAtLaunch: StepStatus? = null
        var lastKnownStepAtLaunch: String? = null

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val persisted = supervisor.canonicalTaskRepository.getTask(taskId)?.plan?.steps?.get(0)
            persistedStatusAtLaunch = persisted?.status
            lastKnownStepAtLaunch = supervisor.stateStore.get(taskId)?.lastKnownStep
        }
        job.join()

        assertEquals("Step status must be persisted as RUNNING before execution block runs", StepStatus.RUNNING, persistedStatusAtLaunch)
        assertEquals("step-0:RUNNING", lastKnownStepAtLaunch)
    }

    // 5. Agent / process exit does NOT complete step without verification
    @Test
    fun test05_processExitDoesNotCompleteStep() = runBlocking {
        val taskId = "step3-test-5"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Missing Output Step",
            description = "Missing output step description",
            expectedFiles = listOf("mandatory_output.bin"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-5",
            projectSlug = "slug-5",
            chatId = "c-5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        // Process block exits normally (simulating exit code 0), but required file not created
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            // no file created
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        assertNotEquals("Process exit alone must NOT mark step COMPLETED", StepStatus.COMPLETED, canonical!!.plan.steps[0].status)
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
    }

    // 6. VERIFYING gate enforced before terminal completion
    @Test
    fun test06_verifyingGateEnforced() = runBlocking {
        val taskId = "step3-test-6"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Verifying Gate Step",
            description = "Verifying gate step description",
            verificationCommand = "echo 'ok'",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-6",
            projectSlug = "slug-6",
            chatId = "c-6",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var statusBeforeVerifierDecision: StepStatus? = null
        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                val dbStep = supervisor.canonicalTaskRepository.getTask(taskId)?.plan?.steps?.get(0)
                statusBeforeVerifierDecision = dbStep?.status
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        assertEquals("Step must enter VERIFYING state before StepVerifier evaluates", StepStatus.VERIFYING, statusBeforeVerifierDecision)
    }

    // 7. Successful step advances currentStepIndex exactly once and persists COMPLETED first
    @Test
    fun test07_successfulStepAdvancesExactlyOnce() = runBlocking {
        val taskId = "step3-test-7"
        val step0 = ExecutionStep(stepOrder = 0, title = "Step 0", description = "Desc 0", verificationCommand = "true", status = StepStatus.PENDING)
        val step1 = ExecutionStep(stepOrder = 1, title = "Step 1", description = "Desc 1", verificationCommand = "true", status = StepStatus.PENDING)
        val plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)

        supervisor.createTask(
            taskId = taskId,
            projectId = "p-7",
            projectSlug = "slug-7",
            chatId = "c-7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = plan
        )

        val observedIndexes = mutableListOf<Int>()
        var step0Ran = false
        var step1Ran = false

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            observedIndexes.add(supervisor.canonicalTaskRepository.getTask(taskId)!!.plan.currentStepIndex)
            if (activeStep.stepOrder == 0) step0Ran = true
            if (activeStep.stepOrder == 1) step1Ran = true
        }
        job.join()

        assertTrue(step0Ran)
        assertTrue(step1Ran)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[1].status)
        assertEquals("currentStepIndex must be 2 after both steps complete", 2, canonical.plan.currentStepIndex)
    }

    // 8. Future steps remain PENDING while earlier step executes or fails
    @Test
    fun test08_futureStepsRemainPending() = runBlocking {
        val taskId = "step3-test-8"
        val step0 = ExecutionStep(stepOrder = 0, title = "Failing Step 0", description = "Desc 0", expectedFiles = listOf("absent.txt"), status = StepStatus.PENDING)
        val step1 = ExecutionStep(stepOrder = 1, title = "Future Step 1", description = "Desc 1", verificationCommand = "true", status = StepStatus.PENDING)
        val step2 = ExecutionStep(stepOrder = 2, title = "Future Step 2", description = "Desc 2", verificationCommand = "true", status = StepStatus.PENDING)
        val plan = ExecutionPlan(steps = listOf(step0, step1, step2), currentStepIndex = 0)

        supervisor.createTask(
            taskId = taskId,
            projectId = "p-8",
            projectSlug = "slug-8",
            chatId = "c-8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = plan
        )

        var step1Attempted = false
        var step2Attempted = false

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            if (activeStep.stepOrder == 1) step1Attempted = true
            if (activeStep.stepOrder == 2) step2Attempted = true
        }
        job.join()

        assertFalse(step1Attempted)
        assertFalse(step2Attempted)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[1].status)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[2].status)
        assertEquals(0, canonical.plan.steps[1].attempts)
        assertEquals(0, canonical.plan.steps[2].attempts)
        assertEquals(0, canonical.plan.currentStepIndex)
    }

    // 9. Multi-step execution order (strictly step 0 -> step 1 -> step 2)
    @Test
    fun test09_multiStepExecutionOrder() = runBlocking {
        val taskId = "step3-test-9"
        val steps = (0..2).map { index ->
            ExecutionStep(stepOrder = index, title = "Step $index", description = "Desc $index", verificationCommand = "true", status = StepStatus.PENDING)
        }
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-9",
            projectSlug = "slug-9",
            chatId = "c-9",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = steps, currentStepIndex = 0)
        )

        val executionOrder = mutableListOf<Int>()
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            executionOrder.add(activeStep.stepOrder)
        }
        job.join()

        assertEquals(listOf(0, 1, 2), executionOrder)
    }

    // 10. Restart resumes from persisted step state (never assumes RUNNING/VERIFYING succeeded)
    @Test
    fun test10_restartResumesCorrectStep() = runBlocking {
        val taskId = "step3-test-10"
        val step0 = ExecutionStep(stepOrder = 0, title = "Step 0", description = "Desc 0", verificationCommand = "true", status = StepStatus.COMPLETED, resultSummary = "Passed")
        val step1 = ExecutionStep(stepOrder = 1, title = "Step 1", description = "Desc 1", verificationCommand = "true", status = StepStatus.RUNNING, attempts = 1)
        val step2 = ExecutionStep(stepOrder = 2, title = "Step 2", description = "Desc 2", verificationCommand = "true", status = StepStatus.PENDING)
        val plan = ExecutionPlan(steps = listOf(step0, step1, step2), currentStepIndex = 1)

        supervisor.createTask(
            taskId = taskId,
            projectId = "p-10",
            projectSlug = "slug-10",
            chatId = "c-10",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart Task",
            plan = plan
        )

        val executedSteps = mutableListOf<Int>()
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            executedSteps.add(activeStep.stepOrder)
        }
        job.join()

        assertFalse("Step 0 was already completed; must not be re-executed", executedSteps.contains(0))
        assertTrue("Step 1 was left in RUNNING; must be resumed and executed", executedSteps.contains(1))
        assertTrue("Step 2 must be executed after step 1 completes", executedSteps.contains(2))
        assertEquals(listOf(1, 2), executedSteps)
    }

    // 11. Failure enters RecoveryEngine
    @Test
    fun test11_failureEntersRecoveryEngine() = runBlocking {
        val taskId = "step3-test-11"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Engine Recovery Step",
            description = "Engine recovery description",
            expectedFiles = listOf("does_not_exist.txt"),
            status = StepStatus.PENDING,
            maxAttempts = 2
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-11",
            projectSlug = "slug-11",
            chatId = "c-11",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var recoveryEngineInvoked = false
        val originalEngine = supervisor.recoveryEngine
        supervisor.recoveryEngine = object : RecoveryEngine by originalEngine {
            override fun planRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                classification: TaskSupervisor.TaskErrorClassification,
                errorMessage: String,
                mutatedFiles: List<String>,
                attemptCount: Int
            ): RecoveryPlan? {
                recoveryEngineInvoked = true
                return originalEngine.planRecovery(task, step, classification, errorMessage, mutatedFiles, attemptCount)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        assertTrue("Failure must enter RecoveryEngine", recoveryEngineInvoked)
    }

    // 12. Recovery returns through verification before completion
    @Test
    fun test12_recoveryReturnsThroughVerification() = runBlocking {
        val taskId = "step3-test-12"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Recover Then Verify",
            description = "Recover description",
            expectedFiles = listOf("recovered.txt"),
            status = StepStatus.PENDING,
            maxAttempts = 3
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-12",
            projectSlug = "slug-12",
            chatId = "c-12",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var executionAttempts = 0
        var verificationAttempts = 0

        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                verificationAttempts++
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            executionAttempts++
            if (executionAttempts == 1) {
                // Mutate a temporary file so RecoveryEngine triggers RESTORE_CHECKPOINT
                File(workspaceDir, "temp_attempt1.txt").writeText("dirty workspace")
            } else {
                File(workspaceDir, "recovered.txt").writeText("created on attempt 2")
            }
        }
        job.join()

        assertEquals(2, executionAttempts)
        assertEquals(2, verificationAttempts)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
    }

    // 13. Final step completes plan and finalizes task exactly once
    @Test
    fun test13_finalStepCompletesPlan() = runBlocking {
        val taskId = "step3-test-13"
        val step = ExecutionStep(stepOrder = 0, title = "Final Step", description = "Final step description", verificationCommand = "true", status = StepStatus.PENDING)
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-13",
            projectSlug = "slug-13",
            chatId = "c-13",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(PlanStatus.COMPLETED, canonical.plan.status)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 14. Cancellation stops progression immediately
    @Test
    fun test14_cancellationStopsProgression() = runBlocking {
        val taskId = "step3-test-14"
        val step0 = ExecutionStep(stepOrder = 0, title = "Cancel During Step 0", description = "Cancel desc", verificationCommand = "true", status = StepStatus.PENDING)
        val step1 = ExecutionStep(stepOrder = 1, title = "Never Executed", description = "Never desc", verificationCommand = "true", status = StepStatus.PENDING)
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-14",
            projectSlug = "slug-14",
            chatId = "c-14",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step0, step1))
        )

        val step1Run = AtomicBoolean(false)
        val inStep0 = CompletableDeferred<Unit>()

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            if (activeStep.stepOrder == 0) {
                inStep0.complete(Unit)
                supervisor.requestStop(taskId)
            } else {
                step1Run.set(true)
            }
        }
        inStep0.await()
        job.join()

        assertFalse("Step 1 must not run after cancellation", step1Run.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertNotEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[1].status)
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get(taskId)?.status)
    }

    // 15. Approval / input pause does NOT advance currentStepIndex
    @Test
    fun test15_approvalInputPauseDoesNotAdvance() = runBlocking {
        val taskId = "step3-test-15"
        val step0 = ExecutionStep(stepOrder = 0, title = "Approval Pause Step", description = "Pause desc", verificationCommand = "true", status = StepStatus.PENDING)
        val step1 = ExecutionStep(stepOrder = 1, title = "Waiting Step", description = "Wait desc", verificationCommand = "true", status = StepStatus.PENDING)
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-15",
            projectSlug = "slug-15",
            chatId = "c-15",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            supervisor.pauseForApproval(taskId)
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(0, canonical.plan.currentStepIndex)
        assertNotEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[1].status)
        assertEquals(TaskExecutionStatus.WAITING_FOR_APPROVAL, supervisor.stateStore.get(taskId)?.status)
    }

    // 16. Brain snapshot and policy injection preserved
    @Test
    fun test16_brainPolicyInjectionPreserved() = runBlocking {
        val taskId = "step3-test-16"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-16",
            projectSlug = "slug-16",
            chatId = "c-16",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Preserve policies"
        )

        var snapshotAttemptId: String? = null
        var containsPolicies = false

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val snapshot = supervisor.getBrainSnapshot(task.taskId)
            snapshotAttemptId = snapshot?.attemptId
            containsPolicies = snapshot?.renderedContext?.contains("[GLOBAL_EXECUTION_POLICIES]") == true
        }
        job.join()

        assertNotNull(snapshotAttemptId)
        assertTrue("Global execution policies must be preserved in snapshot", containsPolicies)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 17. No duplicate execution or advancement of steps
    @Test
    fun test17_noDuplicateExecutionOrAdvancement() = runBlocking {
        val taskId = "step3-test-17"
        val step0 = ExecutionStep(stepOrder = 0, title = "Step 0", description = "Desc 0", verificationCommand = "true", status = StepStatus.PENDING)
        val step1 = ExecutionStep(stepOrder = 1, title = "Step 1", description = "Desc 1", verificationCommand = "true", status = StepStatus.PENDING)
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-17",
            projectSlug = "slug-17",
            chatId = "c-17",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Prompt",
            plan = ExecutionPlan(steps = listOf(step0, step1))
        )

        val step0Count = AtomicInteger(0)
        val step1Count = AtomicInteger(0)

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            if (activeStep.stepOrder == 0) step0Count.incrementAndGet()
            if (activeStep.stepOrder == 1) step1Count.incrementAndGet()
        }
        job.join()

        assertEquals(1, step0Count.get())
        assertEquals(1, step1Count.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(2, canonical.plan.currentStepIndex)
        assertEquals(PlanStatus.COMPLETED, canonical.plan.status)
    }
}
