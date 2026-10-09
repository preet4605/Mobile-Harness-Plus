package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
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
import java.util.concurrent.atomic.AtomicReference

/**
 * Verification test suite for Phase 6 Precondition 3:
 * "Step-Aware Execution Loop".
 *
 * Verifies that runtime execution transitions through a supervised step lifecycle:
 * PENDING -> RUNNING -> VERIFYING -> COMPLETED (or VERIFYING -> FAILED),
 * ensuring deterministic trusted verification, step checkpoint creation,
 * persistence, isolation from agent self-reporting, and blocking on step failure.
 */
class StepAwareExecutionLoopTest {

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

    // 1. PENDING -> RUNNING transition and step checkpoint tagged creation
    @Test
    fun test1_pendingToRunning_and_taggedCheckpointCreation() = runBlocking {
        val taskId = "task-step-1"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step One",
            description = "Initial step",
            status = StepStatus.PENDING
        )
        val plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-1",
            projectSlug = "slug-1",
            chatId = "c1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Do work",
            plan = plan
        )

        // Workspace file to snapshot
        File(workspaceDir, "init.txt").writeText("initial content")

        var observedStatusDuringExecution: StepStatus? = null
        var observedAttempts: Int? = null

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val persistedStep = supervisor.canonicalTaskRepository.getTask(taskId)?.plan?.currentStep
            observedStatusDuringExecution = persistedStep?.status
            observedAttempts = persistedStep?.attempts
        }
        job.join()

        assertEquals(StepStatus.RUNNING, observedStatusDuringExecution)
        assertEquals(1, observedAttempts)
        assertTrue("Step checkpoint step-1 must exist", checkpoints.checkpointExists("proj-1", "step-1"))
    }

    // 2. RUNNING -> VERIFYING transition
    @Test
    fun test2_runningToVerifyingTransition() = runBlocking {
        val taskId = "task-step-2"
        val expectedFile = "generated.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Produce File",
            description = "Generate output",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        val plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-2",
            projectSlug = "slug-2",
            chatId = "c2",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Produce file",
            plan = plan
        )

        var statusObservedDuringVerification: StepStatus? = null

        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                val dbStep = supervisor.canonicalTaskRepository.getTask(taskId)?.plan?.steps?.get(0)
                statusObservedDuringVerification = dbStep?.status
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            File(workspaceDir, expectedFile).writeText("completed content")
        }
        job.join()

        assertEquals("Step must enter VERIFYING before terminal resolution", StepStatus.VERIFYING, statusObservedDuringVerification)
    }

    // 3. Successful verification -> COMPLETED
    @Test
    fun test3_successfulVerificationToCompleted() = runBlocking {
        val taskId = "task-step-3"
        val expectedFile = "build/artifact.bin"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Build Artifact",
            description = "Compile binary",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Build artifact",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val out = File(workspaceDir, expectedFile)
            out.parentFile?.mkdirs()
            out.writeText("binary data")
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val completedStep = canonical!!.plan.steps[0]
        assertEquals(StepStatus.COMPLETED, completedStep.status)
        assertNotNull(completedStep.completedAt)
        assertTrue(completedStep.resultSummary?.contains("passed") == true)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 4. Failed verification -> FAILED
    @Test
    fun test4_failedVerificationToFailed() = runBlocking {
        val taskId = "task-step-4"
        val expectedFile = "nonexistent.file"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Missing File Step",
            description = "Fails to create expected file",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Fail file check",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            // Does not create expectedFile
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val failedStep = canonical!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, failedStep.status)
        assertNotNull(failedStep.completedAt)
        assertTrue(failedStep.resultSummary?.contains("Expected file does not exist") == true)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 5. Failed step blocks later steps
    @Test
    fun test5_failedStepBlocksLaterSteps() = runBlocking {
        val taskId = "task-step-5"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0 - Fails",
            description = "Fails verification",
            expectedFiles = listOf("file_never_created.txt"),
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1 - Later Step",
            description = "Should not be executed",
            expectedFiles = listOf("later.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Multi-step test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        var step1Executed = false

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            if (activeStep.stepOrder == 1) {
                step1Executed = true
            }
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        assertFalse("Later step must never be executed after earlier step failure", step1Executed)
        assertEquals(StepStatus.FAILED, canonical!!.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[1].status)
        assertEquals(0, canonical.plan.steps[1].attempts)
        assertEquals(0, canonical.plan.currentStepIndex)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 6. Exit code alone cannot complete step without trusted verification
    @Test
    fun test6_exitCodeAloneCannotCompleteWithoutVerification() = runBlocking {
        val taskId = "task-step-6"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Process Exit Code Step",
            description = "Agent process finishes with exit 0 but missing file",
            expectedFiles = listOf("required_artifact.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-6",
            projectSlug = "slug-6",
            chatId = "c6",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Test exit code",
            plan = ExecutionPlan(steps = listOf(step))
        )

        // Agent process simulated: exits cleanly with code 0, but did not create artifact
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val simulatedExitCode = 0
            assertEquals(0, simulatedExitCode)
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertNotEquals("Step must NOT be COMPLETED from exit code alone", StepStatus.COMPLETED, resultStep.status)
        assertEquals(StepStatus.FAILED, resultStep.status)
    }

    // 7. Expected-file, forbidden-file, and content pattern verification
    @Test
    fun test7_fileAndContentVerificationRules() = runBlocking {
        fun makeStep(): ExecutionStep = ExecutionStep(
            stepOrder = 0,
            title = "Content and Rules Step",
            description = "Test multi-axis deterministic verification",
            expectedFiles = listOf("src/Main.kt"),
            forbiddenFiles = listOf("temp/forbidden.tmp"),
            expectedContent = mapOf("src/Main.kt" to "class AppMain"),
            status = StepStatus.PENDING
        )

        // Case A: Missing expected file -> FAIL
        val taskA = "task-step-7-a"
        supervisor.createTask(
            taskId = taskA,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Rules test A",
            plan = ExecutionPlan(steps = listOf(makeStep()))
        )
        val jobA = supervisor.executeTask(taskA) { _, _ -> }
        jobA.join()
        val canonicalA = supervisor.canonicalTaskRepository.getTask(taskA)
        assertEquals(StepStatus.FAILED, canonicalA!!.plan.steps[0].status)

        // Case B: Create expected file but wrong content -> FAIL
        val mainFile = File(workspaceDir, "src/Main.kt").apply { parentFile?.mkdirs(); writeText("class WrongClass") }
        val taskB = "task-step-7-b"
        supervisor.createTask(
            taskId = taskB,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Rules test B",
            plan = ExecutionPlan(steps = listOf(makeStep()))
        )
        val jobB = supervisor.executeTask(taskB) { _, _ -> }
        jobB.join()
        val canonicalB = supervisor.canonicalTaskRepository.getTask(taskB)
        assertEquals(StepStatus.FAILED, canonicalB!!.plan.steps[0].status)
        assertTrue(canonicalB.plan.steps[0].resultSummary?.contains("does not contain expected pattern") == true)

        // Case C: Right content, but forbidden file present -> FAIL
        mainFile.writeText("class AppMain {\n  fun run() = 1\n}")
        val forbiddenFile = File(workspaceDir, "temp/forbidden.tmp").apply { parentFile?.mkdirs(); writeText("bad") }
        val taskC = "task-step-7-c"
        supervisor.createTask(
            taskId = taskC,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Rules test C",
            plan = ExecutionPlan(steps = listOf(makeStep()))
        )
        val jobC = supervisor.executeTask(taskC) { _, _ -> }
        jobC.join()
        val canonicalC = supervisor.canonicalTaskRepository.getTask(taskC)
        assertEquals(StepStatus.FAILED, canonicalC!!.plan.steps[0].status)
        assertTrue(canonicalC.plan.steps[0].resultSummary?.contains("Forbidden file exists") == true)

        // Case D: Forbidden file removed, content matches -> PASS
        forbiddenFile.delete()
        val taskD = "task-step-7-d"
        supervisor.createTask(
            taskId = taskD,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Rules test D",
            plan = ExecutionPlan(steps = listOf(makeStep()))
        )
        val jobD = supervisor.executeTask(taskD) { _, _ -> }
        jobD.join()
        val canonicalD = supervisor.canonicalTaskRepository.getTask(taskD)
        assertEquals(StepStatus.COMPLETED, canonicalD!!.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskD)?.status)
    }

    // 8. Verification command success and failure
    @Test
    fun test8_verificationCommandSuccessAndFailure() = runBlocking {
        val taskId = "task-step-8"
        val stepSuccess = ExecutionStep(
            stepOrder = 0,
            title = "Command Success Step",
            description = "Runs valid command",
            verificationCommand = "echo 'verification OK'",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-8",
            projectSlug = "slug-8",
            chatId = "c8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Command test",
            plan = ExecutionPlan(steps = listOf(stepSuccess))
        )

        var job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()
        var canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertEquals(StepStatus.COMPLETED, canonical!!.plan.steps[0].status)

        // Test failure command
        val taskIdFail = "task-step-8-fail"
        val stepFail = ExecutionStep(
            stepOrder = 0,
            title = "Command Fail Step",
            description = "Runs failing command",
            verificationCommand = "false", // Exits with code 1
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdFail,
            projectId = "proj-8",
            projectSlug = "slug-8",
            chatId = "c8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Command test fail",
            plan = ExecutionPlan(steps = listOf(stepFail))
        )
        job = supervisor.executeTask(taskIdFail) { _, _ -> }
        job.join()
        canonical = supervisor.canonicalTaskRepository.getTask(taskIdFail)
        assertEquals(StepStatus.FAILED, canonical!!.plan.steps[0].status)
        assertTrue(canonical.plan.steps[0].resultSummary?.contains("failed with exit code") == true)
    }

    // 9. Persisted step state (reloaded faithfully from SQLite)
    @Test
    fun test9_persistedStepStateReloadedFromSQLite() = runBlocking {
        val taskId = "task-step-9"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Persistence Step",
            description = "Verify SQLite persistence",
            expectedFiles = listOf("saved.txt"),
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
            prompt = "Persist step",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            File(workspaceDir, "saved.txt").writeText("data")
        }
        job.join()

        // Query completely independently from repository
        val loaded = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(loaded)
        val loadedStep = loaded!!.plan.steps[0]
        assertEquals(StepStatus.COMPLETED, loadedStep.status)
        assertEquals(1, loadedStep.attempts)
        assertEquals("step-1", loadedStep.checkpointTag)
        assertNotNull(loadedStep.resultSummary)
        assertNotNull(loadedStep.startedAt)
        assertNotNull(loadedStep.completedAt)
        assertEquals(1, loaded.plan.currentStepIndex)
    }

    // 10. Step attempt count incrementation
    @Test
    fun test10_stepAttemptCountIncrementation() = runBlocking {
        val taskId = "task-step-10"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Attempts Step",
            description = "Track step attempt counts",
            expectedFiles = listOf("retry_target.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-10",
            projectSlug = "slug-10",
            chatId = "c10",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Attempt tracking",
            maxRetries = 1,
            plan = ExecutionPlan(steps = listOf(step))
        )

        // Initial attempt count: 0
        assertEquals(0, supervisor.canonicalTaskRepository.getTask(taskId)!!.plan.steps[0].attempts)

        var runCount = 0
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            runCount++
            if (runCount == 1) {
                // Attempt 1 fails with transient error to trigger retry
                throw RuntimeException("HTTP 503 transient error")
            } else {
                // Attempt 2 succeeds and creates expected file
                File(workspaceDir, "retry_target.txt").writeText("created on attempt 2")
            }
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals("Step attempts must be incremented across attempts", 2, canonical.plan.steps[0].attempts)
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 11. Cancellation does not complete step
    @Test
    fun test11_cancellationDoesNotCompleteStep() = runBlocking {
        val taskId = "task-step-11"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Cancellable Step",
            description = "Interrupted by user stop",
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

        val enteredBlock = CompletableDeferred<Unit>()

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            enteredBlock.complete(Unit)
            // Request cancellation while step is executing
            supervisor.requestStop(taskId)
        }
        enteredBlock.await()
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        assertNotEquals("Cancelled step must never be marked COMPLETED", StepStatus.COMPLETED, canonical!!.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get(taskId)?.status)
    }

    // 12. Waiting for approval or input does not advance step
    @Test
    fun test12_waitingForApprovalOrInputDoesNotAdvanceStep() = runBlocking {
        val taskId = "task-step-12"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Approval Step 0",
            description = "Pauses for tool approval",
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1",
            description = "Should not be entered while paused",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-12",
            projectSlug = "slug-12",
            chatId = "c12",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Approval pause test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        val job = supervisor.executeTask(taskId) { task, activeStep ->
            // Pause for tool approval during step
            supervisor.pauseForApproval(taskId)
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        assertNotEquals("Step must not be COMPLETED while waiting for approval", StepStatus.COMPLETED, canonical!!.plan.steps[0].status)
        assertEquals(0, canonical.plan.currentStepIndex)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[1].status)
        assertEquals(TaskExecutionStatus.WAITING_FOR_APPROVAL, supervisor.stateStore.get(taskId)?.status)
    }

    // 13. Brain snapshot & injection regression
    @Test
    fun test13_brainSnapshotAndInjectionRegression() = runBlocking {
        val taskId = "task-step-13"
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-13",
            projectSlug = "slug-13",
            chatId = "c13",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Snapshot test"
        )

        val snapshotRef = AtomicReference<String>()
        val job = supervisor.executeTask(taskId) { task ->
            val snapshot = supervisor.getBrainSnapshot(taskId)
            assertNotNull(snapshot)
            snapshotRef.set(snapshot?.attemptId)
        }
        job.join()

        assertNotNull("Brain snapshot must be captured during attempt", snapshotRef.get())
        assertEquals(TaskExecutionStatus.UNVERIFIED, supervisor.stateStore.get(taskId)?.status)
    }

    // 14. Retry regression (transient API errors retry with backoff)
    @Test
    fun test14_retryRegressionOnTransientError() = runBlocking {
        val taskId = "task-step-14"
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-14",
            projectSlug = "slug-14",
            chatId = "c14",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Retry test",
            maxRetries = 2
        )

        val executions = java.util.concurrent.atomic.AtomicInteger(0)
        val job = supervisor.executeTask(taskId) { task ->
            val count = executions.incrementAndGet()
            if (count == 1) {
                throw RuntimeException("HTTP 503 service unavailable")
            }
        }
        job.join()

        assertEquals("Task must have retried after transient 503 error", 2, executions.get())
        assertEquals(TaskExecutionStatus.UNVERIFIED, supervisor.stateStore.get(taskId)?.status)
        assertEquals(1, supervisor.stateStore.get(taskId)?.retryCount)
    }
}
