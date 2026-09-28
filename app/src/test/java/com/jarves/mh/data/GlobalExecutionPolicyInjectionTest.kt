package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.GlobalExecutionPolicies
import com.jarves.mh.runtime.ControlledBrainInjector
import com.jarves.mh.runtime.RuntimeBridge
import com.jarves.mh.runtime.task.TaskExecutionStatus
import com.jarves.mh.runtime.task.TaskSupervisor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GlobalExecutionPolicyInjectionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: BrainDatabase
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var assembler: BrainContextAssembler
    private lateinit var supervisor: TaskSupervisor

    @Before
    fun setUp() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        knowledgeRepo = BrainKnowledgeRepository(db)
        assembler = BrainContextAssembler(knowledgeRepo)
        supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        supervisor.brainContextAssembler = assembler
        ControlledBrainInjector.clearAll()
    }

    @After
    fun tearDown() {
        ControlledBrainInjector.clearAll()
    }

    // 1. Unrelated prompt still receives policies
    @Test
    fun test1_unrelatedPromptStillReceivesPolicies() {
        val taskId = "task-unrelated-1"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-unrelated",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "write a poem about green frogs in spring",
            objective = "write a poem about green frogs in spring"
        )

        // Seed some unrelated knowledge
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-sql",
                projectId = "p-unrelated",
                key = "SQL migration",
                content = "Room database schema version 3 requires migration",
                knowledgeType = BrainKnowledgeType.DECISION,
                scope = MemoryScope.PROJECT
            )
        )

        val snapshot = supervisor.getOrCreateBrainSnapshot(
            taskId = taskId,
            attempt = 0,
            projectId = "p-unrelated",
            query = "write a poem about green frogs in spring"
        )

        assertNotNull(snapshot)
        assertTrue("Rendered context must have [GLOBAL_EXECUTION_POLICIES]", snapshot.renderedContext.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue("Must include REASONING BUDGET POLICY", snapshot.renderedContext.contains("REASONING BUDGET POLICY"))
        assertTrue("Must include EXECUTION EFFICIENCY POLICY", snapshot.renderedContext.contains("EXECUTION EFFICIENCY POLICY"))

        // Unrelated SQL knowledge must not be retrieved
        assertFalse("Unrelated knowledge must not match query", snapshot.renderedContext.contains("Room database schema version 3"))

        // Injected prompt must contain policies
        val injected = ControlledBrainInjector.inject("write a poem about green frogs in spring", snapshot, taskId, snapshot.attemptId)
        assertTrue(injected.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue(injected.contains("REASONING BUDGET POLICY"))
        assertTrue(injected.contains("EXECUTION EFFICIENCY POLICY"))
        assertTrue(injected.contains("<USER_TASK>"))
        assertTrue(injected.contains("write a poem about green frogs in spring"))
    }

    // 2. Policies survive context budget pressure
    @Test
    fun test2_policiesSurviveContextBudgetPressure() {
        val task = CanonicalTask(
            taskId = "t-budget",
            projectId = "p-budget",
            projectSlug = "slug",
            objective = "Heavy task with massive requirements",
            constraints = (1..10).map { "Constraint $it: Must adhere to strict protocol specification item number $it in complete detail" },
            acceptanceCriteria = (1..10).map { "Criteria $it: Verification gate $it must pass completely with zero defects" }
        )

        // Assemble under severe budget pressure (1,500 chars)
        val context = assembler.assemble(task = task, maxCharacters = 1_500)
        val rendered = assembler.render(context, maxCharacters = 1_500)

        assertTrue("Rendered length must respect budget", rendered.length <= 1_500)
        assertTrue("Global policies section must survive budget pressure", rendered.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue("REASONING BUDGET POLICY must survive budget pressure", rendered.contains("REASONING BUDGET POLICY"))
        assertTrue("EXECUTION EFFICIENCY POLICY must survive budget pressure", rendered.contains("EXECUTION EFFICIENCY POLICY"))
    }

    // 3. Policies survive retry
    @Test
    fun test3_policiesSurviveRetry() {
        val taskId = "task-retry-3"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-retry",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Compile build"
        )

        val snap0 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0, projectId = "p-retry")
        val injected0 = ControlledBrainInjector.inject("Compile build", snap0, taskId, snap0.attemptId)
        assertTrue(injected0.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue(injected0.contains("REASONING BUDGET POLICY"))
        assertTrue(injected0.contains("EXECUTION EFFICIENCY POLICY"))

        // Retry attempt 1
        val snap1 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 1, projectId = "p-retry")
        val injected1 = ControlledBrainInjector.inject("Compile build", snap1, taskId, snap1.attemptId)
        assertTrue(injected1.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue(injected1.contains("REASONING BUDGET POLICY"))
        assertTrue(injected1.contains("EXECUTION EFFICIENCY POLICY"))
    }

    // 4. Retry gets new snapshot
    @Test
    fun test4_retryGetsNewSnapshot() {
        val taskId = "task-retry-4"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-retry4",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Fix database lock"
        )

        val snap0 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0, projectId = "p-retry4", query = "Fix database lock")
        assertEquals("$taskId:attempt-0", snap0.attemptId)

        // Add new matching knowledge before attempt 1
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-retry-sol",
                projectId = "p-retry4",
                key = "Fix database lock solution",
                content = "Always close database cursor in finally block",
                knowledgeType = BrainKnowledgeType.SOLUTION,
                scope = MemoryScope.PROJECT
            )
        )

        val snap1 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 1, projectId = "p-retry4", query = "Fix database lock")
        assertEquals("$taskId:attempt-1", snap1.attemptId)
        assertNotEquals(snap0.attemptId, snap1.attemptId)
        assertNotEquals(snap0.fingerprint, snap1.fingerprint)
        assertTrue(snap1.renderedContext.contains("Always close database cursor in finally block"))
        assertTrue(snap1.renderedContext.contains("[GLOBAL_EXECUTION_POLICIES]"))
    }

    // 5. Duplicate injection is blocked
    @Test
    fun test5_duplicateInjectionIsBlocked() {
        val snapshot = BrainContextSnapshot.create(
            "t-dup-5", "t-dup-5:attempt-0",
            assembler.assemble(task = null),
            assembler.render(assembler.assemble(task = null))
        )
        val prompt = "Implement feature X"

        val firstInjection = ControlledBrainInjector.inject(prompt, snapshot, "t-dup-5", "t-dup-5:attempt-0")
        val secondInjection = ControlledBrainInjector.inject(firstInjection, snapshot, "t-dup-5", "t-dup-5:attempt-0")

        assertEquals(firstInjection, secondInjection)
        assertEquals(1, Regex("<BRAIN_CONTEXT>").findAll(secondInjection).count())
        assertEquals(1, Regex("</BRAIN_CONTEXT>").findAll(secondInjection).count())
        assertEquals(1, Regex("<USER_TASK>").findAll(secondInjection).count())
        assertEquals(1, Regex("</USER_TASK>").findAll(secondInjection).count())
    }

    // 6. API-key failover preserves Brain context
    @Test
    fun test6_apiKeyFailoverPreservesBrainContext() = runBlocking {
        val taskId = "task-failover-6"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-6",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Migrate database schema"
        )

        val snapshot0 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0, projectId = "p-6")
        val originalFingerprint = snapshot0.fingerprint
        val originalAttemptId = snapshot0.attemptId

        // Capture received prompts across failover
        val receivedPrompts = mutableListOf<String>()
        val mockBridge = object : RuntimeBridge {
            override val events: Flow<RuntimeEvent> = emptyFlow()
            override suspend fun startSession(
                projectId: String,
                projectSlug: String,
                projectKind: ProjectKind,
                prompt: String,
                conversationHistory: List<ChatMessage>,
                provider: ProviderProfile,
                memory: ContextMemory,
                taskId: String?,
                brainSnapshot: BrainContextSnapshot?,
                attemptId: String?,
            ): String {
                val injected = ControlledBrainInjector.inject(prompt, brainSnapshot, taskId, attemptId)
                receivedPrompts.add(injected)
                return "session-${receivedPrompts.size}"
            }
            override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {}
            override suspend fun stopSession(sessionId: String, force: Boolean) {}
            override suspend fun stopActiveSession(force: Boolean) {}
            override suspend fun undoLastChanges(projectId: String): Boolean = true
            override suspend fun acceptLastChanges(projectId: String) {}
            override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
            override suspend fun undoFileChange(projectId: String, path: String): Boolean = true
            override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true
        }

        val provider1 = ProviderProfile(kind = ProviderKind.ANTIGRAVITY_SERVER, model = "gemini-3.8-flash")
        // First startSession on key 1
        mockBridge.startSession(
            projectId = "p-6",
            projectSlug = "slug",
            projectKind = ProjectKind.PROJECT,
            prompt = "Migrate database schema",
            conversationHistory = emptyList<ChatMessage>(),
            provider = provider1,
            memory = ContextMemory("p-6"),
            taskId = taskId,
            brainSnapshot = snapshot0,
            attemptId = originalAttemptId,
        )

        // Simulate failover: Key 1 fails with 401, key 2 is activated, preserving taskId, attemptId, snapshot
        val provider2 = ProviderProfile(kind = ProviderKind.ANTIGRAVITY_SERVER, model = "gemini-3.8-flash")
        mockBridge.startSession(
            projectId = "p-6",
            projectSlug = "slug",
            projectKind = ProjectKind.PROJECT,
            prompt = "Migrate database schema",
            conversationHistory = emptyList<ChatMessage>(),
            provider = provider2,
            memory = ContextMemory("p-6"),
            taskId = taskId,
            brainSnapshot = snapshot0,
            attemptId = originalAttemptId,
        )

        assertEquals(2, receivedPrompts.size)
        // Both sessions must have received the global execution policies
        assertTrue("Session 1 must contain policies", receivedPrompts[0].contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue("Session 2 must contain policies", receivedPrompts[1].contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertEquals("Failover must preserve exact injected prompt", receivedPrompts[0], receivedPrompts[1])
        assertEquals(originalFingerprint, snapshot0.fingerprint)
        assertEquals(originalAttemptId, snapshot0.attemptId)
    }

    // 7. All RuntimeBridges receive policies
    @Test
    fun test7_allRuntimeBridgesReceivePolicies() = runBlocking {
        val taskId = "task-bridges-7"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-bridges",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Verify parity across bridges"
        )
        val snapshot = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0, projectId = "p-bridges")

        val bridgesReceivedPrompts = mutableMapOf<String, String>()

        fun createRecordingBridge(name: String): RuntimeBridge = object : RuntimeBridge {
            override val events: Flow<RuntimeEvent> = emptyFlow()
            override suspend fun startSession(
                projectId: String,
                projectSlug: String,
                projectKind: ProjectKind,
                prompt: String,
                conversationHistory: List<ChatMessage>,
                provider: ProviderProfile,
                memory: ContextMemory,
                taskId: String?,
                brainSnapshot: BrainContextSnapshot?,
                attemptId: String?,
            ): String {
                val injected = ControlledBrainInjector.inject(prompt, brainSnapshot, taskId, attemptId)
                bridgesReceivedPrompts[name] = injected
                return "session-$name"
            }
            override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {}
            override suspend fun stopSession(sessionId: String, force: Boolean) {}
            override suspend fun stopActiveSession(force: Boolean) {}
            override suspend fun undoLastChanges(projectId: String): Boolean = true
            override suspend fun acceptLastChanges(projectId: String) {}
            override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = emptyList()
            override suspend fun undoFileChange(projectId: String, path: String): Boolean = true
            override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true
        }

        val bridgeNames = listOf("ANTIGRAVITY", "CLAUDE", "DEEPSEEK")
        for (name in bridgeNames) {
            val bridge = createRecordingBridge(name)
            ControlledBrainInjector.clearAttempt(taskId, snapshot.attemptId)
            bridge.startSession(
                projectId = "p-bridges",
                projectSlug = "slug",
                projectKind = ProjectKind.PROJECT,
                prompt = "Verify parity across bridges",
                conversationHistory = emptyList<ChatMessage>(),
                provider = ProviderProfile(kind = ProviderKind.ANTIGRAVITY_SERVER, model = "gemini-3.8-pro"),
                memory = ContextMemory("p-bridges"),
                taskId = taskId,
                brainSnapshot = snapshot,
                attemptId = snapshot.attemptId,
            )
        }

        assertEquals(3, bridgesReceivedPrompts.size)
        for (name in bridgeNames) {
            val p = bridgesReceivedPrompts[name]
            assertNotNull("Bridge $name must receive prompt", p)
            assertTrue("Bridge $name must contain [GLOBAL_EXECUTION_POLICIES]", p!!.contains("[GLOBAL_EXECUTION_POLICIES]"))
            assertTrue("Bridge $name must contain REASONING BUDGET POLICY", p.contains("REASONING BUDGET POLICY"))
            assertTrue("Bridge $name must contain EXECUTION EFFICIENCY POLICY", p.contains("EXECUTION EFFICIENCY POLICY"))
        }
    }

    // 8. Approval/input resume does not duplicate injection
    @Test
    fun test8_approvalInputResumeDoesNotDuplicateInjection() {
        val taskId = "task-approval-8"
        supervisor.createTask(
            taskId = taskId,
            projectId = "p-approval",
            projectSlug = "slug",
            chatId = "c-1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run approval workflow"
        )
        supervisor.stateStore.transition(taskId, TaskExecutionStatus.STARTING)
        supervisor.stateStore.transition(taskId, TaskExecutionStatus.RUNNING)

        val snapshotBefore = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0, projectId = "p-approval")
        val injectedPrompt = ControlledBrainInjector.inject("Run approval workflow", snapshotBefore, taskId, snapshotBefore.attemptId)

        supervisor.pauseForApproval(taskId)
        assertEquals(TaskExecutionStatus.WAITING_FOR_APPROVAL, supervisor.stateStore.get(taskId)?.status)

        supervisor.resumeFromApproval(taskId)
        assertEquals(TaskExecutionStatus.RUNNING, supervisor.stateStore.get(taskId)?.status)

        // Resumed prompt check
        val reinjected = ControlledBrainInjector.inject(injectedPrompt, snapshotBefore, taskId, snapshotBefore.attemptId)
        assertEquals(injectedPrompt, reinjected)
        assertEquals(1, Regex("<BRAIN_CONTEXT>").findAll(reinjected).count())
        assertEquals(1, Regex("</BRAIN_CONTEXT>").findAll(reinjected).count())
    }

    // 9. Normal Brain retrieval still works and separates project knowledge from global policies
    @Test
    fun test9_normalBrainRetrievalStillWorks() {
        val projectId = "p-retrieval-9"
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-rule-dao",
                projectId = projectId,
                key = "Database Rule",
                content = "All database queries must use Room DAO interfaces",
                knowledgeType = BrainKnowledgeType.CONSTRAINT,
                scope = MemoryScope.PROJECT
            )
        )
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-rule-compose",
                projectId = projectId,
                key = "UI Architecture",
                content = "Adopt Jetpack Compose for all user interface layouts",
                knowledgeType = BrainKnowledgeType.DECISION,
                scope = MemoryScope.PROJECT
            )
        )
        // Also seed a legacy memory that used the policy name as a FACT
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-legacy-policy",
                projectId = projectId,
                key = "REASONING BUDGET POLICY",
                content = "Legacy reasoning budget text",
                knowledgeType = BrainKnowledgeType.FACT,
                scope = MemoryScope.PROJECT
            )
        )

        val task = CanonicalTask(
            taskId = "t-retrieval",
            projectId = projectId,
            projectSlug = "slug",
            objective = "Implement user interface and database queries"
        )

        val context = assembler.assemble(task = task, projectId = projectId, query = "database queries and UI")
        val rendered = assembler.render(context)

        // Normal project knowledge retrieved
        assertTrue("Must retrieve project constraint", rendered.contains("All database queries must use Room DAO interfaces"))
        assertTrue("Must retrieve project decision", rendered.contains("Adopt Jetpack Compose for all user interface layouts"))

        // Global execution policies rendered in their dedicated section
        assertTrue("Must contain [GLOBAL_EXECUTION_POLICIES]", rendered.contains("[GLOBAL_EXECUTION_POLICIES]"))
        assertTrue("Must contain mandatory REASONING BUDGET POLICY", rendered.contains("REASONING BUDGET POLICY"))
        assertTrue("Must contain mandatory EXECUTION EFFICIENCY POLICY", rendered.contains("EXECUTION EFFICIENCY POLICY"))

        // Legacy fact should NOT duplicate into [RELEVANT_KNOWLEDGE]
        val knowledgeSection = rendered.substringAfter("[RELEVANT_KNOWLEDGE]").substringBefore("[FAILURES]")
        assertFalse("Legacy policy FACT must not duplicate in [RELEVANT_KNOWLEDGE]", knowledgeSection.contains("Legacy reasoning budget text"))
    }
}
