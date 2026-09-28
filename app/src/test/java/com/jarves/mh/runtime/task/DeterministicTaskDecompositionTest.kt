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
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.WorkspaceCheckpoints
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Verification test suite for Phase 6 Step 2:
 * "Deterministic Task Decomposition".
 *
 * Verifies that:
 * 1. Identical input + context produces identical decomposition.
 * 2. Step IDs and orders are strictly deterministic and sequential.
 * 3. Explicit acceptance criteria are strictly required for every step.
 * 4. Empty and invalid decompositions are safely rejected.
 * 5. Maximum step counts are bounded and enforced.
 * 6. Existing persisted plans are preserved; duplicate decomposition never overwrites them.
 * 7. Existing plans survive process restart with perfect fidelity.
 * 8. Task, plan, and step IDs remain strictly consistent.
 * 9. Seamless integration with TaskSupervisor.
 */
class DeterministicTaskDecompositionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: BrainDatabase
    private lateinit var canonicalRepo: CanonicalTaskRepository
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var supervisor: TaskSupervisor
    private lateinit var workspaceDir: File
    private lateinit var checkpointsDir: File
    private lateinit var checkpoints: WorkspaceCheckpoints
    private val decomposer: TaskDecomposer = DefaultTaskDecomposer()

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

    // 1. Identical input produces identical plan
    @Test
    fun test01_identicalInputProducesIdenticalPlan() {
        val taskId = "task-identical-01"
        val objective = """
            1. Setup local environment
            2. Implement database migrations
            3. Run verification test suite
        """.trimIndent()
        val context = TaskDecompositionContext(
            projectId = "project-alpha",
            projectSlug = "alpha",
            acceptanceCriteria = listOf("Must pass all tests", "Zero compilation warnings"),
            baseTimestamp = Instant.EPOCH
        )

        val plan1 = decomposer.decompose(taskId, objective, context)
        val plan2 = decomposer.decompose(taskId, objective, context)

        assertEquals("Same input + same context must produce identical plan", plan1, plan2)
        assertEquals(3, plan1.steps.size)
        for (i in 0 until 3) {
            assertEquals(plan1.steps[i].stepId, plan2.steps[i].stepId)
            assertEquals(plan1.steps[i].stepOrder, plan2.steps[i].stepOrder)
            assertEquals(plan1.steps[i].objective, plan2.steps[i].objective)
            assertEquals(plan1.steps[i].acceptanceCriteria, plan2.steps[i].acceptanceCriteria)
            assertEquals(plan1.steps[i].status, plan2.steps[i].status)
            assertEquals(plan1.steps[i].planId, plan2.steps[i].planId)
        }
    }

    // 2. Stable step IDs and sequential order (no timestamps/randomness)
    @Test
    fun test02_stableStepIdsAndOrder() {
        val taskId = "task-stable-ids-02"
        val objective = """
            Step 1: Parse requirements
            Step 2: Generate code
            Step 3: Execute integration tests
        """.trimIndent()
        val context = TaskDecompositionContext(
            acceptanceCriteria = listOf("Code coverage >= 80%")
        )

        val plan = decomposer.decompose(taskId, objective, context)
        assertEquals(3, plan.steps.size)

        assertEquals("$taskId-step-0", plan.steps[0].stepId)
        assertEquals(0, plan.steps[0].stepOrder)
        assertEquals("Parse requirements", plan.steps[0].objective)
        assertEquals(StepStatus.PENDING, plan.steps[0].status)

        assertEquals("$taskId-step-1", plan.steps[1].stepId)
        assertEquals(1, plan.steps[1].stepOrder)
        assertEquals("Generate code", plan.steps[1].objective)
        assertEquals(StepStatus.PENDING, plan.steps[1].status)

        assertEquals("$taskId-step-2", plan.steps[2].stepId)
        assertEquals(2, plan.steps[2].stepOrder)
        assertEquals("Execute integration tests", plan.steps[2].objective)
        assertEquals(StepStatus.PENDING, plan.steps[2].status)

        // Multiple invocations return identical step IDs
        val planAgain = decomposer.decompose(taskId, objective, context)
        for (i in 0..2) {
            assertEquals(plan.steps[i].stepId, planAgain.steps[i].stepId)
            assertEquals(plan.steps[i].stepOrder, planAgain.steps[i].stepOrder)
        }
    }

    // 3. Acceptance criteria required for every step
    @Test
    fun test03_acceptanceCriteriaRequired() {
        val taskId = "task-criteria-req-03"

        // Decomposer must reject missing criteria
        assertThrows(InvalidDecompositionException::class.java) {
            decomposer.decompose(
                taskId = taskId,
                objective = "Single objective with no criteria",
                context = TaskDecompositionContext(acceptanceCriteria = emptyList())
            )
        }

        // PlanBuilder must reject steps without acceptance criteria
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-fail", taskId)
                .addStep(
                    title = "Title without criteria",
                    objective = "Objective without criteria",
                    acceptanceCriteria = emptyList()
                )
                .build()
        }

        // PlanBuilder must reject steps with all-blank acceptance criteria
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-fail-blank", taskId)
                .addStep(
                    title = "Title with blank criteria",
                    objective = "Objective with blank criteria",
                    acceptanceCriteria = listOf("  ", "")
                )
                .build()
        }

        // Valid criteria must succeed
        val validPlan = PlanBuilder("plan-success", taskId)
            .addStep(
                title = "Title with valid criteria",
                objective = "Objective with valid criteria",
                acceptanceCriteria = listOf("Valid test criteria")
            )
            .build()
        assertEquals(1, validPlan.steps.size)
        assertEquals(listOf("Valid test criteria"), validPlan.steps[0].acceptanceCriteria)
    }

    // 4. Empty and invalid decompositions rejected
    @Test
    fun test04_emptyInvalidDecompositionRejected() {
        // A. Empty plan
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-empty", "task-empty").build()
        }

        // B. Blank taskId
        assertThrows(InvalidDecompositionException::class.java) {
            decomposer.decompose(
                taskId = "   ",
                objective = "Valid objective",
                context = TaskDecompositionContext(acceptanceCriteria = listOf("Criteria"))
            )
        }

        // C. Blank objective
        assertThrows(InvalidDecompositionException::class.java) {
            decomposer.decompose(
                taskId = "task-blank-obj",
                objective = "   ",
                context = TaskDecompositionContext(acceptanceCriteria = listOf("Criteria"))
            )
        }

        // D. Ambiguous objectives ("TODO", "TBD", "?", "fix", "work")
        for (ambiguous in listOf("TODO", "tbd", "?", "...", "work", "do", "fix", "unspecified")) {
            assertThrows(InvalidDecompositionException::class.java) {
                decomposer.decompose(
                    taskId = "task-ambiguous",
                    objective = ambiguous,
                    context = TaskDecompositionContext(acceptanceCriteria = listOf("Criteria"))
                )
            }
        }

        // E. Duplicate step IDs in PlanBuilder
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-dup-id", "task-dup")
                .addStep(
                    stepId = "same-id",
                    stepOrder = 0,
                    title = "Step 1",
                    objective = "Objective 1",
                    acceptanceCriteria = listOf("Criteria 1")
                )
                .addStep(
                    stepId = "same-id",
                    stepOrder = 1,
                    title = "Step 2",
                    objective = "Objective 2",
                    acceptanceCriteria = listOf("Criteria 2")
                )
                .build()
        }

        // F. Duplicate step objectives
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-dup-obj", "task-dup")
                .addStep(
                    stepOrder = 0,
                    title = "Step 1",
                    objective = "Identical objective",
                    acceptanceCriteria = listOf("Criteria 1")
                )
                .addStep(
                    stepOrder = 1,
                    title = "Step 2",
                    objective = "Identical objective",
                    acceptanceCriteria = listOf("Criteria 2")
                )
                .build()
        }

        // G. Invalid non-sequential ordering
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-invalid-order", "task-order")
                .addStep(
                    stepOrder = 1, // Expected 0 first
                    title = "Step 1",
                    objective = "Objective 1",
                    acceptanceCriteria = listOf("Criteria 1")
                )
                .build()
        }

        // H. Non-PENDING status rejected for freshly built plan
        assertThrows(InvalidDecompositionException::class.java) {
            PlanBuilder("plan-invalid-status", "task-status")
                .addStep(
                    ExecutionStep(
                        stepId = "step-0",
                        stepOrder = 0,
                        title = "Completed Step",
                        objective = "Objective",
                        acceptanceCriteria = listOf("Criteria"),
                        status = StepStatus.COMPLETED
                    )
                )
                .build()
        }
    }

    // 5. Maximum step bounds enforced
    @Test
    fun test05_maximumStepBoundEnforced() {
        val taskId = "task-max-bound-05"

        // Default bound is TaskDecomposer.MAX_STEPS (20)
        assertEquals(20, TaskDecomposer.MAX_STEPS)

        val builder = PlanBuilder("plan-exceed", taskId, maxSteps = 3)
        for (i in 0..3) {
            builder.addStep(
                title = "Step $i",
                objective = "Unique objective $i",
                acceptanceCriteria = listOf("Criterion $i"),
                stepOrder = i
            )
        }
        assertThrows(InvalidDecompositionException::class.java) {
            builder.build()
        }

        // Exact maximum succeeds
        val exactBuilder = PlanBuilder("plan-exact", taskId, maxSteps = 3)
        for (i in 0..2) {
            exactBuilder.addStep(
                title = "Step $i",
                objective = "Unique objective $i",
                acceptanceCriteria = listOf("Criterion $i"),
                stepOrder = i
            )
        }
        val plan = exactBuilder.build()
        assertEquals(3, plan.steps.size)
    }

    // 6. Duplicate decomposition does not overwrite persisted plan
    @Test
    fun test06_duplicateDecompositionDoesNotOverwritePersistedPlan() {
        val taskId = "task-dup-preserve-06"
        val initialContext = TaskDecompositionContext(
            acceptanceCriteria = listOf("Initial criteria")
        )
        val initialCanonical = supervisor.decomposeTask(
            taskId = taskId,
            objective = "Original single milestone",
            context = initialContext
        )
        assertEquals(1, initialCanonical.plan.steps.size)
        assertEquals("Original single milestone", initialCanonical.plan.steps[0].objective)

        // Attempt second decomposition with entirely different multi-step objective
        val intruderObjective = """
            1. Intruder step 1
            2. Intruder step 2
        """.trimIndent()
        val secondCanonical = supervisor.decomposeTask(
            taskId = taskId,
            objective = intruderObjective,
            context = TaskDecompositionContext(acceptanceCriteria = listOf("Intruder criteria"))
        )

        // Must preserve initial plan
        assertEquals("Existing plan must not be overwritten", initialCanonical.plan.planId, secondCanonical.plan.planId)
        assertEquals(1, secondCanonical.plan.steps.size)
        assertEquals("Original single milestone", secondCanonical.plan.steps[0].objective)

        // Check in repository directly
        val loadedTask = canonicalRepo.getTask(taskId)
        assertNotNull(loadedTask)
        assertEquals(1, loadedTask!!.plan.steps.size)
        assertEquals("Original single milestone", loadedTask.plan.steps[0].objective)
    }

    // 7. Existing plan survives restart with perfect fidelity
    @Test
    fun test07_existingPlanSurvivesRestart() {
        val taskId = "task-restart-survive-07"
        val objective = """
            1. First restart milestone
            2. Second restart milestone
            3. Third restart milestone
        """.trimIndent()
        val context = TaskDecompositionContext(
            projectId = "proj-restart",
            projectSlug = "restart-slug",
            acceptanceCriteria = listOf("Must survive SQLite reload")
        )

        val canonicalTask = supervisor.decomposeTask(taskId, objective, context)
        assertEquals(3, canonicalTask.plan.steps.size)

        // Simulate complete restart: new repository and supervisor instances on same database
        val restartRepo = CanonicalTaskRepository(db)
        val restartSupervisor = TaskSupervisor.createForTesting(context = null, database = db)

        val reloadedTask = restartRepo.getTask(taskId)
        assertNotNull(reloadedTask)
        assertEquals(taskId, reloadedTask!!.taskId)
        assertEquals(canonicalTask.plan.planId, reloadedTask.plan.planId)
        assertEquals(3, reloadedTask.plan.steps.size)

        for (i in 0..2) {
            val originalStep = canonicalTask.plan.steps[i]
            val reloadedStep = reloadedTask.plan.steps[i]
            assertEquals(originalStep.stepId, reloadedStep.stepId)
            assertEquals(originalStep.stepOrder, reloadedStep.stepOrder)
            assertEquals(originalStep.objective, reloadedStep.objective)
            assertEquals(originalStep.acceptanceCriteria, reloadedStep.acceptanceCriteria)
            assertEquals(originalStep.status, reloadedStep.status)
            assertEquals(originalStep.planId, reloadedStep.planId)
        }

        // Supervisor decomposeTask on restarted task also preserves existing plan
        val reInit = restartSupervisor.decomposeTask(taskId, "Different prompt after restart")
        assertEquals(3, reInit.plan.steps.size)
        assertEquals("First restart milestone", reInit.plan.steps[0].objective)
    }

    // 8. Task / plan / step ID consistency
    @Test
    fun test08_taskPlanStepIdConsistency() {
        val taskId = "task-id-consistency-08"
        val objective = """
            1. Alpha step
            2. Beta step
        """.trimIndent()
        val context = TaskDecompositionContext(
            acceptanceCriteria = listOf("Integrity verified")
        )

        val task = supervisor.decomposeTask(taskId, objective, context)
        val plan = task.plan

        assertEquals(taskId, task.taskId)
        assertEquals(taskId, plan.taskId)
        assertEquals("plan-$taskId", plan.planId)

        assertEquals(2, plan.steps.size)
        assertEquals("$taskId-step-0", plan.steps[0].stepId)
        assertEquals(plan.planId, plan.steps[0].planId)

        assertEquals("$taskId-step-1", plan.steps[1].stepId)
        assertEquals(plan.planId, plan.steps[1].planId)
    }

    // 9. Integration with TaskSupervisor and execution lifecycle
    @Test
    fun test09_integrationWithTaskSupervisor() = runBlocking {
        val taskId = "task-supervisor-lifecycle-09"
        val objective = """
            1. First supervisor step
            2. Second supervisor step
        """.trimIndent()
        val context = TaskDecompositionContext(
            projectId = "proj-lifecycle",
            projectSlug = "lifecycle",
            acceptanceCriteria = listOf("Lifecycle verified")
        )

        val canonicalTask = supervisor.decomposeTask(taskId, objective, context)
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-lifecycle",
            projectSlug = "lifecycle",
            chatId = "chat-09",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = objective,
            plan = canonicalTask.plan
        )

        val record = supervisor.stateStore.get(taskId)
        assertNotNull(record)

        var stepsExecuted = 0
        val job = supervisor.executeTask(taskId) { currentStep, _ ->
            stepsExecuted++
        }
        job.join()

        assertTrue("Execution loop executed steps", stepsExecuted > 0)
        val finalTask = canonicalRepo.getTask(taskId)
        assertNotNull(finalTask)
        assertEquals(PlanStatus.COMPLETED, finalTask!!.plan.status)
        assertTrue(finalTask.plan.isFinished)
    }

    // 10. Extract acceptance criteria from prompt text
    @Test
    fun test10_acceptanceCriteriaTextExtraction() {
        val taskId = "task-extract-criteria-10"
        val prompt = """
            Build and verify authentication module
            Acceptance Criteria:
            - JWT tokens are validated
            - Expired tokens return 401
        """.trimIndent()

        val plan = decomposer.decompose(taskId, prompt, TaskDecompositionContext())
        assertEquals(1, plan.steps.size)
        assertEquals(listOf("JWT tokens are validated", "Expired tokens return 401"), plan.steps[0].acceptanceCriteria)
        assertEquals("Build and verify authentication module", plan.steps[0].objective)
    }
}
