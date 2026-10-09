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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Verification test suite for Phase 6 Precondition 1:
 * "Runtime CanonicalTask Lifecycle Integration".
 *
 * Verifies that normal runtime task creation persists a CanonicalTask
 * before execution begins, ensuring consistency across DurableTaskRecord,
 * CanonicalTask, execution attempts, BrainContextSnapshot, and runtime execution.
 */
class CanonicalTaskLifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: BrainDatabase
    private lateinit var canonicalRepo: CanonicalTaskRepository
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var supervisor: TaskSupervisor

    @Before
    fun setUp() {
        ControlledBrainInjector.clearAll()
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        canonicalRepo = CanonicalTaskRepository(db)
        knowledgeRepo = BrainKnowledgeRepository(db)
        supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        supervisor.brainContextAssembler = BrainContextAssembler(knowledgeRepo)
    }

    @After
    fun tearDown() {
        ControlledBrainInjector.clearAll()
    }

    // TEST 1: Create a normal runtime task and verify canonicalTaskRepository.getTask(taskId) != null and taskId matches
    @Test
    fun test1_createRuntimeTask_persistsCanonicalTaskWithMatchingTaskId() {
        val taskId = "test-task-1"
        val record = supervisor.createTask(
            taskId = taskId,
            projectId = "proj-1",
            projectSlug = "test-proj",
            chatId = "chat-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Implement feature A"
        )

        assertEquals(taskId, record.taskId)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull("CanonicalTask must exist in repository after runtime task creation", canonical)
        assertEquals("CanonicalTask taskId must match runtime taskId", record.taskId, canonical!!.taskId)
    }

    // TEST 2: Verify canonicalTask.projectId == runtimeProjectId
    @Test
    fun test2_canonicalTask_projectIdMatchesRuntimeProjectId() {
        val taskId = "test-task-2"
        val projectId = "proj-alpha-123"
        val projectSlug = "slug-alpha"
        val record = supervisor.createTask(
            taskId = taskId,
            projectId = projectId,
            projectSlug = projectSlug,
            chatId = "chat-2",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Verify project id matching"
        )

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        assertEquals("CanonicalTask projectId must match runtime projectId", projectId, canonical!!.projectId)
        assertEquals("CanonicalTask projectSlug must match runtime projectSlug", projectSlug, canonical.projectSlug)
    }

    // TEST 3: Verify canonicalTask.objective == originalUserPrompt
    @Test
    fun test3_canonicalTask_objectiveMatchesOriginalUserPrompt() {
        val originalPrompt = "Refactor authentication flow to use OAuth2 PKCE"
        val runtimeWrappedPrompt = """
            <rules>
            Always write unit tests first.
            </rules>
            $originalPrompt
        """.trimIndent()

        // Test with explicit objective parameter representing original user prompt
        val record = supervisor.createTask(
            taskId = "test-task-3a",
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "chat-3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = runtimeWrappedPrompt,
            objective = originalPrompt
        )

        val canonical = supervisor.canonicalTaskRepository.getTask(record.taskId)
        assertNotNull(canonical)
        assertEquals("CanonicalTask objective must preserve original user prompt", originalPrompt, canonical!!.objective)

        // Test default behavior where objective falls back to prompt when not explicitly separated
        val record2 = supervisor.createTask(
            taskId = "test-task-3b",
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "chat-3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = originalPrompt
        )
        val canonical2 = supervisor.canonicalTaskRepository.getTask(record2.taskId)
        assertNotNull(canonical2)
        assertEquals("CanonicalTask objective must match prompt when objective omitted", originalPrompt, canonical2!!.objective)
    }

    // TEST 4: Verify plan exists and is structurally valid
    @Test
    fun test4_canonicalTask_planExistsAndIsStructurallyValid() {
        val record = supervisor.createTask(
            taskId = "test-task-4",
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "chat-4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Fix memory leak in network pool"
        )

        val canonical = supervisor.canonicalTaskRepository.getTask(record.taskId)
        assertNotNull(canonical)
        val plan = canonical!!.plan
        assertNotNull("ExecutionPlan must exist", plan)
        assertTrue("ExecutionPlan must contain at least one step", plan.steps.isNotEmpty())

        val currentStep = plan.currentStep
        assertNotNull("Current execution step must not be null", currentStep)
        assertEquals(0, currentStep!!.stepOrder)
        assertEquals(StepStatus.PENDING, currentStep.status)
        assertEquals("Execute Task", currentStep.title)
        assertEquals("Fix memory leak in network pool", currentStep.description)
        assertFalse("Initial plan is not marked finished", plan.isFinished)
    }

    // TEST 5: Verify initialWorkspaceSha / currentWorkspaceSha are populated when workspace SHA is available
    @Test
    fun test5_canonicalTask_workspaceShaPopulatedWhenAvailable() {
        val explicitSha = "e752e3bdfa47f82b5db69998bb39037f862eb5e7"
        val recordExplicit = supervisor.createTask(
            taskId = "test-task-5-explicit",
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "chat-5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Check explicit workspace sha",
            workspaceSha = explicitSha
        )

        val canonicalExplicit = supervisor.canonicalTaskRepository.getTask(recordExplicit.taskId)
        assertNotNull(canonicalExplicit)
        assertEquals("initialWorkspaceSha must match provided SHA", explicitSha, canonicalExplicit!!.initialWorkspaceSha)
        assertEquals("currentWorkspaceSha must match provided SHA", explicitSha, canonicalExplicit.currentWorkspaceSha)

        // Test resolving Git SHA from workspace directory on disk
        val wsDir = tempFolder.newFolder("mock_repo")
        val gitDir = File(wsDir, ".git").apply { mkdirs() }
        val refsHeads = File(gitDir, "refs/heads").apply { mkdirs() }
        val masterRef = File(refsHeads, "master")
        val expectedGitSha = "1234567890abcdef1234567890abcdef12345678"
        masterRef.writeText(expectedGitSha)
        File(gitDir, "HEAD").writeText("ref: refs/heads/master\n")

        supervisor.workspaceDirectoryResolver = { wsDir }

        val recordFromDisk = supervisor.createTask(
            taskId = "test-task-5-disk",
            projectId = "proj-5-disk",
            projectSlug = "slug-5-disk",
            chatId = "chat-5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Check resolved workspace sha from disk"
        )

        val canonicalDisk = supervisor.canonicalTaskRepository.getTask(recordFromDisk.taskId)
        assertNotNull(canonicalDisk)
        assertEquals("initialWorkspaceSha must match git HEAD commit", expectedGitSha, canonicalDisk!!.initialWorkspaceSha)
        assertEquals("currentWorkspaceSha must match git HEAD commit", expectedGitSha, canonicalDisk.currentWorkspaceSha)

        // Clean up resolver
        supervisor.workspaceDirectoryResolver = null
    }

    // TEST 6: Verify persistence occurs before execution starts; executeTask fails if CanonicalTask is missing
    @Test
    fun test6_persistenceOccursBeforeExecutionStarts_andFailsIfCanonicalMissing() {
        val taskId = "test-task-6-orphan"

        // Artificially create a DurableTaskRecord in stateStore WITHOUT creating a CanonicalTask
        val orphanRecord = DurableTaskRecord(
            taskId = taskId,
            projectId = "p-orphan",
            projectSlug = "orphan",
            chatId = "c-orphan",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Orphan task without canonical record",
            status = TaskExecutionStatus.CREATED
        )
        supervisor.stateStore.save(orphanRecord)

        // Attempting to execute should immediately fail with IllegalStateException
        try {
            supervisor.executeTask(taskId) { }
            fail("executeTask must throw an error when CanonicalTask is missing")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Error message must indicate missing CanonicalTask",
                e.message?.contains("CanonicalTask") == true && e.message?.contains("not found") == true
            )
        }

        // Verify task never transitioned to STARTING or RUNNING
        val currentRecord = supervisor.stateStore.get(taskId)
        assertEquals(TaskExecutionStatus.CREATED, currentRecord?.status)
    }

    // TEST 7: Verify an existing task still passes through the previously validated Brain context and runtime pipeline
    @Test
    fun test7_existingTaskPassesThroughBrainContextAndRuntimePipeline() = runBlocking {
        val taskId = "test-task-7-pipeline"
        val record = supervisor.createTask(
            taskId = taskId,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "chat-7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Execute end-to-end task with Brain injection"
        )

        var blockExecuted = false
        val job = supervisor.executeTask(record.taskId) { task ->
            assertEquals(record.taskId, task.taskId)
            val snapshot = supervisor.getOrCreateBrainSnapshot(taskId = task.taskId, attempt = 0)
            assertNotNull(snapshot)
            assertEquals(record.taskId, snapshot.taskId)

            // Verify task and current step are properly rendered in Brain context
            assertTrue("Snapshot should render [TASK]", snapshot.renderedContext.contains("[TASK]"))
            assertTrue("Snapshot should contain taskId", snapshot.renderedContext.contains("id: $taskId"))
            assertTrue("Snapshot should render [CURRENT_STEP]", snapshot.renderedContext.contains("[CURRENT_STEP]"))
            assertTrue("Snapshot should render step title", snapshot.renderedContext.contains("title: Execute Task"))

            val prompt = "Original prompt"
            val injected = ControlledBrainInjector.inject(prompt, snapshot, task.taskId)
            assertTrue(injected.contains("<BRAIN_CONTEXT>"))
            assertTrue(injected.contains("<USER_TASK>"))
            assertTrue(injected.contains(prompt))

            blockExecuted = true
        }

        job.join()
        assertTrue("Execution block should have run", blockExecuted)
        val finalizedRecord = supervisor.stateStore.get(taskId)
        assertEquals(TaskExecutionStatus.UNVERIFIED, finalizedRecord?.status)
    }

    // TEST: Detached HEAD git SHA resolution
    @Test
    fun testGitShaResolution_detachedHead() {
        val wsDir = tempFolder.newFolder("mock_detached")
        val gitDir = File(wsDir, ".git").apply { mkdirs() }
        val detachedSha = "abcdef0123456789abcdef0123456789abcdef01"
        File(gitDir, "HEAD").writeText(detachedSha)

        val resolved = supervisor.resolveGitSha(wsDir)
        assertEquals(detachedSha, resolved)
    }

    // TEST: Packed-refs git SHA resolution
    @Test
    fun testGitShaResolution_packedRefs() {
        val wsDir = tempFolder.newFolder("mock_packed")
        val gitDir = File(wsDir, ".git").apply { mkdirs() }
        File(gitDir, "HEAD").writeText("ref: refs/heads/release/v1.0\n")
        val packedSha = "9876543210fedcba9876543210fedcba98765432"
        val packedRefsContent = """
            # pack-refs with: peeled-tags fully-peeled sorted
            1111111111111111111111111111111111111111 refs/heads/master
            $packedSha refs/heads/release/v1.0
            2222222222222222222222222222222222222222 refs/tags/v1.0
        """.trimIndent()
        File(gitDir, "packed-refs").writeText(packedRefsContent)

        val resolved = supervisor.resolveGitSha(wsDir)
        assertEquals(packedSha, resolved)
    }

    // TEST: Non-git directory gracefully returns null
    @Test
    fun testGitShaResolution_nonGitDirectoryReturnsNull() {
        val nonGitDir = tempFolder.newFolder("non_git")
        val resolved = supervisor.resolveGitSha(nonGitDir)
        assertNull(resolved)
    }
}
