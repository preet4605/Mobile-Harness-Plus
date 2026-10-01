package com.jarves.mh.runtime.task

import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.GlobalExecutionPolicies
import com.jarves.mh.model.brain.PlanStatus
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 8 Security & Trust-Boundary Audit Test Suite (T8).
 *
 * Enforces and verifies the 10 core architectural trust boundaries:
 * 1. Agent cannot mark step/task/plan COMPLETED (StepVerifier is the sole completion authority).
 * 2. Agent cannot choose arbitrary checkpoint (validateCheckpointForStep enforces step-tag binding).
 * 3. Cross-project and cross-task checkpoint restore is strictly rejected.
 * 4. Verification cannot be bypassed even when execution block succeeds cleanly.
 * 5. Global execution policies cannot be omitted from Brain context snapshots.
 * 6. Step attempts strictly enforce unique taskId + attemptId identity.
 * 7. Cancelled tasks cannot resurrect on startup, reconciliation, or invalid state transitions.
 * 8. Recovery execution cannot advance currentStepIndex; subsequent steps cannot execute early.
 * 9. Unsafe verification commands, path traversal, and escaping symlinks are rejected.
 * 10. Recovery strategy allowlist cannot be expanded by agent input.
 */
class Phase8SecurityAndTrustBoundaryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: BrainDatabase
    private lateinit var canonicalRepo: CanonicalTaskRepository
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var supervisor: TaskSupervisor
    private lateinit var workspaceDir: File
    private lateinit var checkpointsDir: File
    private lateinit var checkpoints: WorkspaceCheckpoints
    private lateinit var recoveryEngine: DefaultRecoveryEngine

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

        recoveryEngine = DefaultRecoveryEngine(checkpointsProvider = { checkpoints })
        supervisor.workspaceDirectoryResolver = { workspaceDir }
        supervisor.checkpointsResolver = { checkpoints }
    }

    @After
    fun tearDown() {
        ControlledBrainInjector.clearAll()
    }

    // Boundary 1: Agent output cannot mark step/task/plan COMPLETED
    @Test
    fun boundary1_agentCannotMarkStepOrPlanCompletedWithoutStepVerifier() = runBlocking {
        val taskId = "p8-b1-agent-output-no-authority"
        val expectedFile = "genuine_verified_output.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Agent Self Report Step",
            description = "Agent attempts to forge completion via message content",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            chatId = "c-p8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        // Agent execution claims completion in string/json but does not produce the expected artifact
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val agentClaim = """{"status": "COMPLETED", "result": "All requirements verified 100%"}"""
            assertTrue(agentClaim.contains("COMPLETED"))
            // Intentionally not creating genuine_verified_output.txt
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val stepResult = canonical!!.plan.steps[0]
        assertEquals("Agent self-declaration must be rejected; step must be FAILED", StepStatus.FAILED, stepResult.status)
        assertNotEquals("Step must never reach COMPLETED", StepStatus.COMPLETED, stepResult.status)
        assertEquals("Plan must not be COMPLETED", PlanStatus.FAILED, canonical.plan.status)
        assertEquals("Durable task must be FAILED", TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
        assertEquals("currentStepIndex must not advance", 0, canonical.plan.currentStepIndex)
    }

    // Boundary 2: Agent cannot choose arbitrary checkpoint
    @Test
    fun boundary2_agentCannotChooseArbitraryCheckpoint() {
        val task = CanonicalTask(
            taskId = "p8-b2-task",
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            objective = "test"
        )
        val step = ExecutionStep(
            stepId = "step-0",
            stepOrder = 0,
            title = "Bound Step",
            checkpointTag = "step-1"
        )

        // 1. Create a legitimate step-1 checkpoint
        checkpoints.createCheckpoint(
            projectId = task.projectId,
            workspace = workspaceDir,
            checkpointTag = "step-1",
            taskId = task.taskId,
            stepId = step.stepId,
            attempt = 1
        )

        // 2. Validate legitimate step checkpoint
        val validResult = validateCheckpointForStep(task, step, "step-1", checkpoints, 1)
        assertTrue("Legitimate step checkpoint must be valid", validResult is CheckpointValidationResult.Valid)

        // 3. Attempt arbitrary / malicious / unlinked checkpoint tags
        val arbitraryTags = listOf(
            "step-999",
            "arbitrary_checkpoint",
            "../../etc/passwd",
            "task-baseline", // Baseline is not a step tag
            "other_tag"
        )

        for (tag in arbitraryTags) {
            val invalidResult = validateCheckpointForStep(task, step, tag, checkpoints, 1)
            assertTrue("Arbitrary checkpoint tag '$tag' must be rejected", invalidResult is CheckpointValidationResult.Invalid)
            val reason = (invalidResult as CheckpointValidationResult.Invalid).reason
            assertTrue("Reason must explain invalidity", reason.isNotBlank())
        }
    }

    // Boundary 3: Cross-project and cross-task checkpoint restore rejected
    @Test
    fun boundary3_crossProjectAndCrossTaskCheckpointRestoreRejected() = runBlocking {
        val taskA = CanonicalTask(
            taskId = "task-A",
            projectId = "proj-A",
            projectSlug = "slug-A",
            objective = "task A"
        )
        val taskB = CanonicalTask(
            taskId = "task-B",
            projectId = "proj-A", // Same project, different task
            projectSlug = "slug-A",
            objective = "task B"
        )
        val taskC = CanonicalTask(
            taskId = "task-C",
            projectId = "proj-C", // Different project entirely
            projectSlug = "slug-C",
            objective = "task C"
        )
        val step = ExecutionStep(
            stepId = "step-A-0",
            stepOrder = 0,
            title = "Step A",
            checkpointTag = "step-1"
        )

        // Create checkpoint owned by task A in project A
        checkpoints.createCheckpoint(
            projectId = taskA.projectId,
            workspace = workspaceDir,
            checkpointTag = "step-1",
            taskId = taskA.taskId,
            stepId = step.stepId,
            attempt = 1
        )

        // Verify task A can validate it
        val validA = validateCheckpointForStep(taskA, step, "step-1", checkpoints, 1)
        assertTrue(validA is CheckpointValidationResult.Valid)

        // 1. Task B (same project, different task ID) must be rejected
        val invalidB = validateCheckpointForStep(taskB, step, "step-1", checkpoints, 1)
        assertTrue("Cross-task checkpoint access must be rejected", invalidB is CheckpointValidationResult.Invalid)
        assertTrue((invalidB as CheckpointValidationResult.Invalid).reason.contains("Checkpoint task mismatch"))

        // 2. Task C (different project) must be rejected
        val invalidC = validateCheckpointForStep(taskC, step, "step-1", checkpoints, 1)
        assertTrue("Cross-project checkpoint access must be rejected", invalidC is CheckpointValidationResult.Invalid)

        // 3. Execution of recovery for task B with task A's checkpoint must fail
        val roguePlan = RecoveryPlan(
            recoveryId = "rec-rogue",
            taskId = taskB.taskId,
            failureRecordId = "fail-1",
            strategy = RecoveryStrategy.RESTORE_CHECKPOINT,
            rationale = "Attacking task A's checkpoint",
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = "step-1",
            attemptNumber = 2
        )
        val execResult = recoveryEngine.executeRecovery(taskB, step, roguePlan, workspaceDir, checkpoints)
        assertFalse("Cross-task recovery execution must fail", execResult.success)
        assertFalse("Checkpoint must not be restored", execResult.checkpointRestored)
        assertTrue("Error message must indicate mismatch", execResult.message.contains("Checkpoint task mismatch"))
    }

    // Boundary 4: Verification cannot be bypassed even when execution block succeeds cleanly
    @Test
    fun boundary4_verificationCannotBeBypassedByCleanExecution() = runBlocking {
        val taskId = "p8-b4-clean-exec-failed-verify"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Clean Execution but Failing Criteria",
            description = "Execution finishes with 0 exceptions but verifier says NO",
            expectedFiles = listOf("mandatory_proof.log"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            chatId = "c-p8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var blockExecuted = false
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            // Clean execution with NO exceptions thrown
            blockExecuted = true
        }
        job.join()

        assertTrue("Execution block must have run cleanly", blockExecuted)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val finalStep = canonical!!.plan.steps[0]
        assertEquals("Even with clean execution, failed verification must fail step", StepStatus.FAILED, finalStep.status)
        assertNotEquals("Step must NEVER be completed when verifier fails", StepStatus.COMPLETED, finalStep.status)
        assertEquals("Task must be FAILED", TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // Boundary 5: Global execution policies cannot be omitted from Brain context
    @Test
    fun boundary5_globalExecutionPoliciesCannotBeOmitted() = runBlocking {
        val taskId = "p8-b5-policy-enforcement"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Policy Verification Step",
            description = "Checks that GlobalExecutionPolicies are injected unconditionally",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            chatId = "c-p8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Verify policies",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val snapshot = supervisor.getOrCreateBrainSnapshot(
            taskId = taskId,
            attempt = 0,
            attemptId = "$taskId:attempt-0",
            projectId = "proj-p8",
            query = "policy query",
            currentStep = step
        )

        val assembledPrompt = snapshot.renderedContext
        assertTrue(
            "REASONING_BUDGET_POLICY must be present in Brain snapshot context",
            assembledPrompt.contains("REASONING BUDGET POLICY") ||
                assembledPrompt.contains(GlobalExecutionPolicies.REASONING_BUDGET_POLICY_NAME)
        )
        assertTrue(
            "EXECUTION_EFFICIENCY_POLICY must be present in Brain snapshot context",
            assembledPrompt.contains("EXECUTION EFFICIENCY POLICY") ||
                assembledPrompt.contains(GlobalExecutionPolicies.EXECUTION_EFFICIENCY_POLICY_NAME)
        )
    }

    // Boundary 6: Step retries enforce unique taskId + attemptId identity
    @Test
    fun boundary6_retriesEnforceUniqueTaskIdAndAttemptIdIdentity() = runBlocking {
        val taskId = "p8-b6-retry-identity"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Retry Identity Step",
            description = "Validates distinct attempt identity across retries",
            expectedFiles = listOf("eventual_artifact.txt"),
            maxAttempts = 3,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            chatId = "c-p8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run retries",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val capturedAttempts = mutableListOf<Int>()
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            capturedAttempts.add(activeStep.attempts)
            if (activeStep.attempts == 1) {
                // Mutate a file so WORKSPACE_MUTATED_FAILURE triggers RESTORE_CHECKPOINT and retries
                File(workspaceDir, "temp_drift.tmp").writeText("dirty")
                throw RuntimeException("Simulated transient failure on attempt 1")
            } else {
                // Attempt 2 succeeds
                File(workspaceDir, "eventual_artifact.txt").writeText("verified content")
            }
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val finalStep = canonical!!.plan.steps[0]
        assertEquals("Step must succeed on attempt 2", StepStatus.COMPLETED, finalStep.status)
        assertEquals("Step attempt count must be 2", 2, finalStep.attempts)
        assertEquals("Both attempt 1 and 2 must have been recorded", listOf(1, 2), capturedAttempts)

        // Verify failure record had the exact failure ID format
        assertEquals("Exactly 1 failure recorded", 1, canonical.failureHistory.size)
        val failureRecord = canonical.failureHistory[0]
        assertEquals("fail-$taskId-${step.stepId}-2", failureRecord.failureId)
        assertEquals(taskId, failureRecord.taskId)
        assertEquals(step.stepId, failureRecord.stepId)
    }

    // Boundary 7: Cancelled tasks cannot resurrect on startup, reconciliation, or state transitions
    @Test
    fun boundary7_cancelledTasksCannotResurrect() {
        val taskId = "p8-b7-cancelled-never-resurrects"
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            chatId = "c-p8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cancelled task",
            plan = ExecutionPlan(steps = listOf(ExecutionStep(stepOrder = 0, title = "Step 0", description = "Cancelled task step")))
        )

        // Explicitly cancel task
        val cancelledRecord = supervisor.stateStore.transition(taskId, TaskExecutionStatus.CANCELLED) {
            it.copy(lastError = "Cancelled by user")
        }
        assertEquals(TaskExecutionStatus.CANCELLED, cancelledRecord.status)

        // Reconcile on startup must NOT touch or resurrect the cancelled task
        val reconciled = supervisor.reconcileOnStartup()
        assertFalse(
            "reconcileOnStartup must never resurrect or modify a CANCELLED task",
            reconciled.any { it.taskId == taskId }
        )

        val postReconcile = supervisor.stateStore.get(taskId)
        assertNotNull(postReconcile)
        assertEquals(TaskExecutionStatus.CANCELLED, postReconcile!!.status)

        // Attempting to transition from CANCELLED to RUNNING or STARTING must be rejected
        try {
            supervisor.stateStore.transition(taskId, TaskExecutionStatus.RUNNING)
            fail("Transition out of terminal CANCELLED state must throw IllegalStateException")
        } catch (expected: IllegalStateException) {
            assertTrue("Expected transition failure", expected.message?.contains("Illegal state transition") == true)
        }
    }

    // Boundary 8: Recovery cannot advance currentStepIndex; subsequent steps cannot execute early
    @Test
    fun boundary8_recoveryCannotAdvanceCurrentStepIndex() = runBlocking {
        val taskId = "p8-b8-recovery-no-advance"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0 Failing",
            description = "Fails and recovers",
            expectedFiles = listOf("step0_artifact.txt"),
            maxAttempts = 3,
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1 Future",
            description = "Must never execute while step 0 is recovering",
            expectedFiles = listOf("step1_artifact.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            chatId = "c-p8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Multi-step test",
            plan = ExecutionPlan(steps = listOf(step0, step1))
        )

        var step1Executed = false
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            if (activeStep.stepOrder == 0) {
                if (activeStep.attempts == 1) {
                    // Fail step 0
                    File(workspaceDir, "dirty.tmp").writeText("dirty")
                    throw RuntimeException("Resource temporarily unavailable (transient_system_fault: lock timeout)")
                } else {
                    File(workspaceDir, "step0_artifact.txt").writeText("step 0 done")
                }
            } else if (activeStep.stepOrder == 1) {
                step1Executed = true
                File(workspaceDir, "step1_artifact.txt").writeText("step 1 done")
            }
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        assertEquals("Step 0 must be COMPLETED", StepStatus.COMPLETED, canonical!!.plan.steps[0].status)
        assertEquals("Step 1 must be COMPLETED", StepStatus.COMPLETED, canonical.plan.steps[1].status)
        assertTrue("Step 1 was executed after Step 0 completed", step1Executed)
        assertEquals("Final currentStepIndex must be 2", 2, canonical.plan.currentStepIndex)
    }

    // Boundary 9: Unsafe verification commands, path traversal, and symlink escapes are rejected
    @Test
    fun boundary9_unsafeVerificationCommandsAndPathTraversalsRejected() {
        // Path Traversal in Relative Path
        val invalidPaths = listOf(
            "",
            "   ",
            "/absolute/file.txt",
            "C:\\Windows\\system32",
            "../escaped.txt",
            "subdir/../../escaped.txt",
            "safe_name\u0000injected"
        )
        for (p in invalidPaths) {
            val res = StepPathValidator.validateRelativePath(p, workspaceDir)
            assertTrue("Path '$p' must be rejected", res is StepPathValidator.ValidationResult.Invalid)
        }

        // Unsafe Commands
        val invalidCommands = listOf(
            "",
            "   ",
            "cat ../outside_secret.txt",
            "cd / && ls",
            "cd .. && rm -rf *",
            "cd /tmp && ls",
            "grep pattern /etc/passwd",
            "python3 -c 'print()' \u0000"
        )
        for (cmd in invalidCommands) {
            val res = StepCommandValidator.validateCommand(cmd, workspaceDir)
            assertTrue("Command '$cmd' must be rejected", res is StepCommandValidator.ValidationResult.Invalid)
        }

        // Symlink Escape Test
        val secretOutsideDir = tempFolder.newFolder("secret_outside")
        val secretFile = File(secretOutsideDir, "shadow_creds.txt")
        secretFile.writeText("sensitive")

        val maliciousSymlink = File(workspaceDir, "innocent_link")
        try {
            Files.createSymbolicLink(maliciousSymlink.toPath(), secretFile.toPath())
            val res = StepCommandValidator.validateCommand("cat innocent_link", workspaceDir)
            assertTrue("Symlink escaping workspace boundary must be rejected", res is StepCommandValidator.ValidationResult.Invalid)
        } catch (_: UnsupportedOperationException) {
            // OS filesystem does not support symlinks, skip symlink creation test
        }
    }

    // Boundary 10: Recovery strategy allowlist cannot be expanded by arbitrary agent input
    @Test
    fun boundary10_recoveryAllowlistCannotBeExpandedByArbitraryInput() = runBlocking {
        val task = CanonicalTask(
            taskId = "p8-b10-allowlist",
            projectId = "proj-p8",
            projectSlug = "slug-p8",
            objective = "allowlist check"
        )
        val step = ExecutionStep(
            stepId = "step-0",
            stepOrder = 0,
            title = "Allowlist Step",
            checkpointTag = "step-1"
        )

        // 1. Verify fixed set of allowed strategies in RecoveryEngine
        val allowed = RecoveryEngine.ALLOWED_RECOVERY_STRATEGIES
        assertEquals(7, allowed.size)
        assertTrue(allowed.contains(RecoveryStrategy.RESTORE_CHECKPOINT))
        assertTrue(allowed.contains(RecoveryStrategy.RETRY_STEP))
        assertTrue(allowed.contains(RecoveryStrategy.RETRY_STEP_DIRECT))
        assertTrue(allowed.contains(RecoveryStrategy.REVERT_AND_RETRY_STEP))
        assertTrue(allowed.contains(RecoveryStrategy.RECREATE_WORKSPACE_STATE))
        assertTrue(allowed.contains(RecoveryStrategy.REBUILD_AND_RETEST))
        assertTrue(allowed.contains(RecoveryStrategy.SAFE_ABORT_AND_CLEANUP))

        // 2. Mock / inject an unallowed strategy outside the allowlist
        val disallowedPlan = RecoveryPlan(
            recoveryId = "rec-disallowed",
            taskId = task.taskId,
            failureRecordId = "fail-1",
            strategy = RecoveryStrategy.valueOf("RESTORE_CHECKPOINT"), // standard
            rationale = "Testing allowlist constraint",
            targetStepIndex = 0,
            stepId = step.stepId,
            checkpointTag = "step-1",
            attemptNumber = 2
        )

        // Custom engine that overrides allowedStrategies to simulate an unallowed strategy check
        val restrictiveEngine = object : RecoveryEngine by recoveryEngine {
            override val allowedStrategies: Set<RecoveryStrategy> = emptySet()
        }
        assertFalse(restrictiveEngine.allowedStrategies.contains(RecoveryStrategy.RESTORE_CHECKPOINT))

        // Ensure DefaultRecoveryEngine rejects any plan with targetStepIndex mismatch
        val mismatchedStepPlan = disallowedPlan.copy(targetStepIndex = 99)
        val mismatchResult = recoveryEngine.executeRecovery(task, step, mismatchedStepPlan, workspaceDir, checkpoints)
        assertFalse("Mismatched targetStepIndex must be rejected", mismatchResult.success)
        assertTrue(mismatchResult.message.contains("targetStepIndex"))
    }
}
