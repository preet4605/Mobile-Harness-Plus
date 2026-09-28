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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Verification test suite for Phase 6 Step 1:
 * "Canonical Task & Plan Initialization".
 *
 * Verifies that:
 * 1. Every executable CanonicalTask receives exactly one ExecutionPlan.
 * 2. The plan is persisted in SQLite before execution begins.
 * 3. Plans contain deterministic ordered ExecutionSteps with stable IDs, deterministic indices,
 *    objective, acceptance criteria, and status=PENDING.
 * 4. currentStepIndex and plan status are accurately persisted and updated.
 * 5. Reopening/restarting a task loads the exact same plan without recreation or reordering.
 * 6. Empty or invalid plans are rejected safely before execution.
 * 7. Task and plan IDs remain strictly consistent.
 * 8. Duplicate plan initialization is prevented idempotently.
 */
class ExecutionPlanLifecycleTest {

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

    // 1. Plan created once per executable CanonicalTask
    @Test
    fun test01_planCreatedOnce() {
        val taskId = "task-plan-once"
        val record = supervisor.createTask(
            taskId = taskId,
            projectId = "proj-1",
            projectSlug = "slug-1",
            chatId = "c1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Single plan test objective"
        )
        assertNotNull(record)

        val task1 = canonicalRepo.getTask(taskId)
        assertNotNull(task1)
        val initialPlan = task1!!.plan
        assertNotNull(initialPlan)
        assertEquals("Single plan test objective", initialPlan.steps[0].objective)
        assertEquals(PlanStatus.PENDING, initialPlan.status)

        // Calling initializePlan again returns identical plan
        val task2 = supervisor.initializePlan(taskId)
        assertEquals(initialPlan.planId, task2.plan.planId)
        assertEquals(initialPlan.steps.size, task2.plan.steps.size)
        assertEquals(initialPlan.steps[0].stepId, task2.plan.steps[0].stepId)
    }

    // 2. Deterministic step IDs and ordering (indices 0, 1, 2...)
    @Test
    fun test02_deterministicStepIdsAndOrder() {
        val taskId = "task-step-order"
        val stepA = ExecutionStep(
            stepOrder = 0,
            title = "Analyze",
            description = "Step 0 description",
            objective = "Analyze repository structure",
            acceptanceCriteria = listOf("Criteria A")
        )
        val stepB = ExecutionStep(
            stepOrder = 1,
            title = "Build",
            description = "Step 1 description",
            objective = "Assemble build targets",
            acceptanceCriteria = listOf("Criteria B")
        )
        val stepC = ExecutionStep(
            stepOrder = 2,
            title = "Verify",
            description = "Step 2 description",
            objective = "Run strict test suite",
            acceptanceCriteria = listOf("Criteria C")
        )

        val plan = ExecutionPlan(
            planId = "custom-plan-id",
            steps = listOf(stepA, stepB, stepC)
        )

        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-2",
            projectSlug = "slug-2",
            chatId = "c2",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Multi-step deterministic plan",
            plan = plan
        )

        val loadedTask = canonicalRepo.getTask(taskId)
        assertNotNull(loadedTask)
        val steps = loadedTask!!.plan.steps
        assertEquals(3, steps.size)

        // Verify deterministic indices and stable order
        assertEquals(0, steps[0].stepOrder)
        assertEquals(1, steps[1].stepOrder)
        assertEquals(2, steps[2].stepOrder)

        assertEquals("Analyze repository structure", steps[0].objective)
        assertEquals("Assemble build targets", steps[1].objective)
        assertEquals("Run strict test suite", steps[2].objective)

        assertEquals(listOf("Criteria A"), steps[0].acceptanceCriteria)
        assertEquals(listOf("Criteria B"), steps[1].acceptanceCriteria)
        assertEquals(listOf("Criteria C"), steps[2].acceptanceCriteria)

        assertEquals(StepStatus.PENDING, steps[0].status)
        assertEquals(StepStatus.PENDING, steps[1].status)
        assertEquals(StepStatus.PENDING, steps[2].status)

        // Step IDs must be stable and non-blank
        assertTrue(steps[0].stepId.isNotBlank())
        assertTrue(steps[1].stepId.isNotBlank())
        assertTrue(steps[2].stepId.isNotBlank())
        assertNotEquals(steps[0].stepId, steps[1].stepId)
        assertNotEquals(steps[1].stepId, steps[2].stepId)
    }

    // 3. Plan and steps persistence in SQLite BEFORE execution begins
    @Test
    fun test03_persistenceBeforeExecution() {
        val taskId = "task-persist-before-exec"
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Persistence verification prompt"
        )

        // Verify canonical_tasks row
        val canonicalRow = db.driver.query(
            "SELECT plan_id, plan_status, current_step_index FROM canonical_tasks WHERE task_id = ?",
            listOf(taskId)
        ) { row ->
            Triple(row.getString("plan_id"), row.getString("plan_status"), row.getInt("current_step_index"))
        }.firstOrNull()
        assertNotNull("canonical_tasks record must be persisted before execution", canonicalRow)
        assertEquals("plan-$taskId", canonicalRow!!.first)
        assertEquals("PENDING", canonicalRow.second)
        assertEquals(0, canonicalRow.third)

        // Verify execution_plans row
        val planRow = db.driver.query(
            "SELECT plan_id, status, current_step_index FROM execution_plans WHERE task_id = ?",
            listOf(taskId)
        ) { row ->
            Triple(row.getString("plan_id"), row.getString("status"), row.getInt("current_step_index"))
        }.firstOrNull()
        assertNotNull("execution_plans record must be persisted before execution", planRow)
        assertEquals("plan-$taskId", planRow!!.first)
        assertEquals("PENDING", planRow.second)
        assertEquals(0, planRow.third)

        // Verify execution_steps row
        val stepRows = db.driver.query(
            "SELECT step_id, step_order, status, objective FROM execution_steps WHERE task_id = ?",
            listOf(taskId)
        ) { row ->
            Pair(row.getString("status"), row.getString("objective"))
        }
        assertEquals(1, stepRows.size)
        assertEquals("PENDING", stepRows[0].first)
        assertEquals("Persistence verification prompt", stepRows[0].second)
    }

    // 4. Reopening/restarting a task loads identical plan (no recreation or reordering)
    @Test
    fun test04_restartLoadsIdenticalPlan() {
        val taskId = "task-restart-identical"
        val step1 = ExecutionStep(
            stepId = "stable-step-1",
            stepOrder = 0,
            title = "First Step",
            description = "Desc 1",
            objective = "Objective 1",
            acceptanceCriteria = listOf("Criterion 1")
        )
        val step2 = ExecutionStep(
            stepId = "stable-step-2",
            stepOrder = 1,
            title = "Second Step",
            description = "Desc 2",
            objective = "Objective 2",
            acceptanceCriteria = listOf("Criterion 2")
        )
        val initialPlan = ExecutionPlan(
            planId = "stable-plan-id",
            steps = listOf(step1, step2),
            currentStepIndex = 1
        )

        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart integrity prompt",
            plan = initialPlan
        )

        // Re-open from repository simulating a restart
        val reloadedRepo = CanonicalTaskRepository(db)
        val reloadedTask = reloadedRepo.getTask(taskId)
        assertNotNull(reloadedTask)
        val reloadedPlan = reloadedTask!!.plan

        assertEquals("stable-plan-id", reloadedPlan.planId)
        assertEquals(taskId, reloadedPlan.taskId)
        assertEquals(1, reloadedPlan.currentStepIndex)
        assertEquals(2, reloadedPlan.steps.size)

        // Steps must match exactly in order and IDs
        assertEquals("stable-step-1", reloadedPlan.steps[0].stepId)
        assertEquals(0, reloadedPlan.steps[0].stepOrder)
        assertEquals("Objective 1", reloadedPlan.steps[0].objective)
        assertEquals(listOf("Criterion 1"), reloadedPlan.steps[0].acceptanceCriteria)

        assertEquals("stable-step-2", reloadedPlan.steps[1].stepId)
        assertEquals(1, reloadedPlan.steps[1].stepOrder)
        assertEquals("Objective 2", reloadedPlan.steps[1].objective)
        assertEquals(listOf("Criterion 2"), reloadedPlan.steps[1].acceptanceCriteria)
    }

    // 5. currentStepIndex persistence across step lifecycle and updates
    @Test
    fun test05_currentStepIndexPersistence() = runBlocking {
        val taskId = "task-step-index-persist"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Desc 0",
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1",
            description = "Desc 1",
            status = StepStatus.PENDING
        )
        val plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)

        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Step index persistence",
            plan = plan
        )

        assertEquals(0, canonicalRepo.getTask(taskId)?.plan?.currentStepIndex)

        // Direct index update
        canonicalRepo.updateCurrentStepIndex(taskId, 1)
        assertEquals(1, canonicalRepo.getTask(taskId)?.plan?.currentStepIndex)

        // Verify SQLite execution_plans table was also updated
        val dbIndex = db.driver.query(
            "SELECT current_step_index FROM execution_plans WHERE task_id = ?",
            listOf(taskId)
        ) { it.getInt("current_step_index") ?: -1 }.firstOrNull()
        assertEquals(1, dbIndex)
    }

    // 6. Invalid and empty plans are rejected safely
    @Test
    fun test06_invalidEmptyPlanRejection() = runBlocking {
        // A. Empty plan rejection at createTask
        assertThrows(IllegalArgumentException::class.java) {
            supervisor.createTask(
                taskId = "task-empty-fail",
                projectId = "proj-6",
                projectSlug = "slug-6",
                chatId = "c6",
                agentKind = "ANTIGRAVITY",
                providerJson = "{}",
                prompt = "Empty plan prompt",
                plan = ExecutionPlan(steps = emptyList())
            )
        }

        // B. Blank title rejection
        assertThrows(IllegalArgumentException::class.java) {
            val invalidStep = ExecutionStep(stepOrder = 0, title = "", description = "")
            supervisor.createTask(
                taskId = "task-blank-step-fail",
                projectId = "proj-6",
                projectSlug = "slug-6",
                chatId = "c6",
                agentKind = "ANTIGRAVITY",
                providerJson = "{}",
                prompt = "Blank step prompt",
                plan = ExecutionPlan(steps = listOf(invalidStep))
            )
        }

        // C. Duplicate step ID rejection
        assertThrows(IllegalArgumentException::class.java) {
            val s1 = ExecutionStep(stepId = "dup-id", stepOrder = 0, title = "Step 1", description = "Desc 1")
            val s2 = ExecutionStep(stepId = "dup-id", stepOrder = 1, title = "Step 2", description = "Desc 2")
            supervisor.createTask(
                taskId = "task-dup-id-fail",
                projectId = "proj-6",
                projectSlug = "slug-6",
                chatId = "c6",
                agentKind = "ANTIGRAVITY",
                providerJson = "{}",
                prompt = "Duplicate step ID prompt",
                plan = ExecutionPlan(steps = listOf(s1, s2))
            )
        }

        // D. Safe execution failure before running if canonical task plan is empty in database
        val rawTask = CanonicalTask(
            taskId = "task-manual-empty",
            projectId = "proj-6",
            projectSlug = "slug-6",
            objective = "Manual empty task",
            plan = ExecutionPlan(steps = emptyList())
        )
        canonicalRepo.saveTask(rawTask)
        supervisor.stateStore.save(
            DurableTaskRecord(
                taskId = "task-manual-empty",
                projectId = "proj-6",
                projectSlug = "slug-6",
                chatId = "c6",
                agentKind = "ANTIGRAVITY",
                providerJson = "{}",
                prompt = "Manual empty task",
                status = TaskExecutionStatus.CREATED
            )
        )

        var executionAttempted = false
        val job = supervisor.executeTask("task-manual-empty") { _, _ ->
            executionAttempted = true
        }
        job.join()

        assertFalse("Execution block must never run for an empty plan", executionAttempted)
        val finalRecord = supervisor.stateStore.get("task-manual-empty")
        assertEquals(TaskExecutionStatus.FAILED, finalRecord?.status)
        assertTrue(finalRecord?.lastError?.contains("Invalid execution plan") == true)
    }

    // 7. Plan and task IDs consistency with CanonicalTask
    @Test
    fun test07_taskPlanIdConsistency() {
        val taskId = "task-id-consistency"
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Consistency verification"
        )

        val canonical = canonicalRepo.getTask(taskId)
        assertNotNull(canonical)
        assertEquals(taskId, canonical!!.taskId)
        assertEquals(taskId, canonical.plan.taskId)
        assertEquals("plan-$taskId", canonical.plan.planId)

        // All steps must reference the task's planId
        canonical.plan.steps.forEach { step ->
            assertEquals(canonical.plan.planId, step.planId)
        }

        // Dedicated getPlan API returns consistent plan
        val plan = canonicalRepo.getPlan(taskId)
        assertNotNull(plan)
        assertEquals(canonical.plan.planId, plan!!.planId)
        assertEquals(taskId, plan.taskId)
    }

    // 8. Duplicate initialization prevention (idempotent lifecycle)
    @Test
    fun test08_duplicateInitializationPrevention() {
        val taskId = "task-dup-init-prevent"
        val customStep = ExecutionStep(
            stepId = "custom-step-0",
            stepOrder = 0,
            title = "Custom Original Step",
            description = "Original Description",
            objective = "Original Objective"
        )
        val originalPlan = ExecutionPlan(
            planId = "orig-plan-id",
            steps = listOf(customStep)
        )

        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-8",
            projectSlug = "slug-8",
            chatId = "c8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Original Prompt",
            plan = originalPlan
        )

        val taskBefore = canonicalRepo.getTask(taskId)
        assertEquals("orig-plan-id", taskBefore!!.plan.planId)
        assertEquals("Custom Original Step", taskBefore.plan.steps[0].title)

        // Attempting to createTask again with different plan must NOT overwrite existing plan
        val intruderStep = ExecutionStep(
            stepId = "intruder-step",
            stepOrder = 0,
            title = "Intruder Step",
            description = "Intruder Desc"
        )
        val intruderPlan = ExecutionPlan(
            planId = "intruder-plan-id",
            steps = listOf(intruderStep)
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-8",
            projectSlug = "slug-8",
            chatId = "c8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Intruder Prompt",
            plan = intruderPlan
        )

        val taskAfterCreate = canonicalRepo.getTask(taskId)
        assertEquals("orig-plan-id", taskAfterCreate!!.plan.planId)
        assertEquals("Custom Original Step", taskAfterCreate.plan.steps[0].title)

        // Attempting to initializePlan again must also be a no-op
        supervisor.initializePlan(taskId, plan = intruderPlan)
        val taskAfterInit = canonicalRepo.getTask(taskId)
        assertEquals("orig-plan-id", taskAfterInit!!.plan.planId)
        assertEquals("Custom Original Step", taskAfterInit.plan.steps[0].title)
    }

    // 9. Plan status transitions from PENDING -> IN_PROGRESS -> COMPLETED
    @Test
    fun test09_planStatusTransitionsThroughExecution() = runBlocking {
        val taskId = "task-plan-status-transition"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Single Step",
            description = "Desc",
            verificationCommand = "true",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-9",
            projectSlug = "slug-9",
            chatId = "c9",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Status transition prompt",
            plan = ExecutionPlan(steps = listOf(step))
        )

        assertEquals(PlanStatus.PENDING, canonicalRepo.getTask(taskId)?.plan?.status)

        var statusDuringExecution: PlanStatus? = null
        val job = supervisor.executeTask(taskId) { _, _ ->
            statusDuringExecution = canonicalRepo.getTask(taskId)?.plan?.status
        }
        job.join()

        assertEquals(PlanStatus.IN_PROGRESS, statusDuringExecution)
        val completedTask = canonicalRepo.getTask(taskId)
        assertEquals(PlanStatus.COMPLETED, completedTask?.plan?.status)
    }
}
