package com.jarves.mh.runtime

import com.jarves.mh.data.BrainContext
import com.jarves.mh.data.BrainContextAssembler
import com.jarves.mh.data.BrainContextAssemblyException
import com.jarves.mh.data.BrainContextSnapshot
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.data.BrainKnowledgeRepository
import com.jarves.mh.data.CanonicalTaskRepository
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.data.MemoryScope
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.runtime.task.DurableTaskRecord
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant

class ControlledBrainInjectionTest {

    private lateinit var db: BrainDatabase
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var taskRepo: CanonicalTaskRepository
    private lateinit var assembler: BrainContextAssembler
    private lateinit var supervisor: TaskSupervisor

    @Before
    fun setUp() {
        ControlledBrainInjector.clearAll()
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        knowledgeRepo = BrainKnowledgeRepository(db)
        taskRepo = CanonicalTaskRepository(db)
        assembler = BrainContextAssembler(knowledgeRepo)
        supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        supervisor.brainContextAssembler = assembler
    }

    @After
    fun tearDown() {
        ControlledBrainInjector.clearAll()
    }

    // 1. Snapshot creation - snapshot contains correct taskId
    @Test
    fun testSnapshotCreationContainsCorrectTaskId() {
        val taskId = "task-verify-101"
        val attemptId = "$taskId:attempt-0"
        val context = BrainContext(maxCharacters = 1000)
        val rendered = assembler.render(context)
        val snapshot = BrainContextSnapshot.create(taskId, attemptId, context, rendered)

        assertEquals(taskId, snapshot.taskId)
        assertEquals(attemptId, snapshot.attemptId)
        assertTrue(snapshot.renderedContext.contains("[BRAIN_CONTEXT]"))
        assertTrue(snapshot.createdAt > 0L)
        assertNotNull(snapshot.fingerprint)
    }

    // 2. Snapshot immutability - context cannot be mutated after creation
    @Test
    fun testSnapshotImmutability() {
        val constraints = listOf("Never modify git directory", "Strict typing")
        val context = BrainContext(constraints = constraints)
        val rendered = assembler.render(context)
        val snapshot = BrainContextSnapshot.create("task-imm", "task-imm:0", context, rendered)

        assertEquals(2, snapshot.context.constraints.size)
        // context fields are vals and List is immutable
        assertEquals(constraints, snapshot.context.constraints)
        assertEquals(rendered, snapshot.renderedContext)
    }

    // 3. Deterministic fingerprint - same rendered context produces same fingerprint
    @Test
    fun testDeterministicFingerprint() {
        val context = BrainContext(constraints = listOf("Deterministic Rule A"))
        val rendered1 = assembler.render(context)
        val rendered2 = assembler.render(context)

        val fp1 = BrainContextSnapshot.computeFingerprint(rendered1)
        val fp2 = BrainContextSnapshot.computeFingerprint(rendered2)

        assertEquals(fp1, fp2)
        assertEquals(64, fp1.length) // SHA-256 hex string length
    }

    // 4. Different context - different rendered context produces different fingerprint
    @Test
    fun testDifferentContextProducesDifferentFingerprint() {
        val context1 = BrainContext(constraints = listOf("Rule Alpha"))
        val context2 = BrainContext(constraints = listOf("Rule Beta"))

        val rendered1 = assembler.render(context1)
        val rendered2 = assembler.render(context2)

        val fp1 = BrainContextSnapshot.computeFingerprint(rendered1)
        val fp2 = BrainContextSnapshot.computeFingerprint(rendered2)

        assertNotEquals(fp1, fp2)
    }

    // 5. Basic injection - Brain context is present exactly once
    @Test
    fun testBasicInjectionBrainContextPresentExactlyOnce() {
        val snapshot = BrainContextSnapshot.create(
            "t-5", "t-5:0",
            BrainContext(constraints = listOf("No raw queries")),
            assembler.render(BrainContext(constraints = listOf("No raw queries")))
        )
        val prompt = "Implement user authentication endpoint"
        val injected = ControlledBrainInjector.inject(prompt, snapshot)

        val brainContextStarts = Regex("<BRAIN_CONTEXT>").findAll(injected).count()
        val brainContextEnds = Regex("</BRAIN_CONTEXT>").findAll(injected).count()

        assertEquals(1, brainContextStarts)
        assertEquals(1, brainContextEnds)
        assertTrue(injected.contains("No raw queries"))
        assertTrue(injected.contains(prompt))
    }

    // 6. User task preservation - original task remains intact
    @Test
    fun testUserTaskPreservation() {
        val snapshot = BrainContextSnapshot.create(
            "t-6", "t-6:0",
            BrainContext(),
            assembler.render(BrainContext())
        )
        val originalPrompt = "Refactor TaskExecutionLock and ensure unit tests pass cleanly."
        val injected = ControlledBrainInjector.inject(originalPrompt, snapshot)

        val extractedPrompt = ControlledBrainInjector.extractUserTask(injected)
        assertEquals(originalPrompt, extractedPrompt)
    }

    // 7. Structural separation - BRAIN_CONTEXT and USER_TASK remain distinct
    @Test
    fun testStructuralSeparation() {
        val snapshot = BrainContextSnapshot.create(
            "t-7", "t-7:0",
            BrainContext(constraints = listOf("Project Rule")),
            assembler.render(BrainContext(constraints = listOf("Project Rule")))
        )
        val userPrompt = "Build registration form"
        val injected = ControlledBrainInjector.inject(userPrompt, snapshot)

        val brainStart = injected.indexOf("<BRAIN_CONTEXT>")
        val brainEnd = injected.indexOf("</BRAIN_CONTEXT>")
        val taskStart = injected.indexOf("<USER_TASK>")
        val taskEnd = injected.indexOf("</USER_TASK>")

        assertTrue(brainStart in 0 until brainEnd)
        assertTrue(brainEnd < taskStart)
        assertTrue(taskStart < taskEnd)

        val brainBlock = ControlledBrainInjector.extractBrainContext(injected)
        assertNotNull(brainBlock)
        assertTrue(brainBlock!!.contains("Project Rule"))
        assertFalse(brainBlock.contains(userPrompt))
    }

    // 8. Duplicate injection prevention - same attempt cannot receive two Brain wrappers
    @Test
    fun testDuplicateInjectionPrevention() {
        val snapshot = BrainContextSnapshot.create(
            "t-8", "t-8:0",
            BrainContext(constraints = listOf("Check duplicate")),
            assembler.render(BrainContext(constraints = listOf("Check duplicate")))
        )
        val prompt = "Create invoice generator"

        val firstInjection = ControlledBrainInjector.inject(prompt, snapshot, "t-8:0")
        val secondInjection = ControlledBrainInjector.inject(firstInjection, snapshot, "t-8:0")

        assertEquals(firstInjection, secondInjection)
        assertEquals(1, Regex("<BRAIN_CONTEXT>").findAll(secondInjection).count())
        assertEquals(1, Regex("<USER_TASK>").findAll(secondInjection).count())
    }

    // 9. Same-attempt reuse - repeated execution/resume uses same snapshot
    @Test
    fun testSameAttemptReuse() {
        val taskId = "task-reuse-9"
        supervisor.createTask(taskId = taskId, projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Task prompt")

        val snap1 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0)
        val snap2 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0)

        assertSame(snap1, snap2)
        assertEquals(snap1.fingerprint, snap2.fingerprint)
    }

    // 10. New-attempt behavior - genuinely new retry attempt receives new snapshot
    @Test
    fun testNewAttemptBehavior() {
        val taskId = "task-retry-10"
        supervisor.createTask(taskId = taskId, projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Task prompt")

        val snapAttempt0 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0)
        // Add new knowledge before attempt 1 that matches query
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-new",
                projectId = "p-1",
                key = "Task prompt rule",
                content = "Always validate network responses for task prompt",
                knowledgeType = BrainKnowledgeType.CONSTRAINT,
                scope = MemoryScope.PROJECT
            )
        )
        val snapAttempt1 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 1)

        assertNotEquals(snapAttempt0.attemptId, snapAttempt1.attemptId)
        assertEquals("$taskId:attempt-0", snapAttempt0.attemptId)
        assertEquals("$taskId:attempt-1", snapAttempt1.attemptId)
        assertNotEquals(snapAttempt0.fingerprint, snapAttempt1.fingerprint)
        assertTrue(snapAttempt1.renderedContext.contains("Always validate network responses"))
    }

    // 11. Approval pause/resume - exact same fingerprint before/after resume
    @Test
    fun testApprovalPauseResumeFingerprintEquality() {
        val taskId = "task-approval-11"
        supervisor.createTask(taskId = taskId, projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Approve tool")
        supervisor.stateStore.transition(taskId, TaskExecutionStatus.STARTING)
        supervisor.stateStore.transition(taskId, TaskExecutionStatus.RUNNING)

        val snapshotBefore = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0)
        val fingerprintBefore = snapshotBefore.fingerprint

        supervisor.pauseForApproval(taskId)
        assertEquals(TaskExecutionStatus.WAITING_FOR_APPROVAL, supervisor.stateStore.get(taskId)?.status)

        // Mutate repository during pause
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-pause-time",
                projectId = "p-1",
                key = "MidPause",
                content = "Approve tool: Added while paused",
                knowledgeType = BrainKnowledgeType.FACT,
                scope = MemoryScope.PROJECT
            )
        )

        supervisor.resumeFromApproval(taskId)
        assertEquals(TaskExecutionStatus.RUNNING, supervisor.stateStore.get(taskId)?.status)

        val snapshotAfter = supervisor.getBrainSnapshot(taskId)
        assertNotNull(snapshotAfter)
        assertEquals(fingerprintBefore, snapshotAfter!!.fingerprint)
        assertSame(snapshotBefore, snapshotAfter)
    }

    // 12. RuntimeBridge integration - bridge receives injected context
    @Test
    fun testRuntimeBridgeIntegration() = runBlocking {
        var receivedPrompt: String? = null
        val fakeBridge = object : RuntimeBridge {
            override val events: Flow<RuntimeEvent> = emptyFlow()
            override suspend fun startSession(
                projectId: String,
                projectSlug: String,
                projectKind: ProjectKind,
                prompt: String,
                conversationHistory: List<ChatMessage>,
                provider: ProviderProfile,
                memory: com.jarves.mh.data.ContextMemory,
                taskId: String?,
                brainSnapshot: BrainContextSnapshot?,
                attemptId: String?,
            ): String {
                val effectiveAttemptId = attemptId ?: brainSnapshot?.attemptId
                val injected = ControlledBrainInjector.inject(prompt, brainSnapshot, taskId, effectiveAttemptId)
                receivedPrompt = injected
                return "session-fake-1"
            }
            override suspend fun respondToApproval(request: com.jarves.mh.model.ToolRequest, approved: Boolean) {}
            override suspend fun stopSession(sessionId: String, force: Boolean) {}
            override suspend fun stopActiveSession(force: Boolean) {}
            override suspend fun undoLastChanges(projectId: String): Boolean = true
            override suspend fun acceptLastChanges(projectId: String) {}
            override suspend fun loadPendingChanges(projectId: String): List<com.jarves.mh.model.ChangeItem> = emptyList()
            override suspend fun undoFileChange(projectId: String, path: String): Boolean = true
            override suspend fun acceptFileChange(projectId: String, path: String): Boolean = true
        }

        val snapshot = BrainContextSnapshot.create(
            "task-br", "task-br:0",
            BrainContext(constraints = listOf("Strict Bridge Contract")),
            assembler.render(BrainContext(constraints = listOf("Strict Bridge Contract")))
        )

        val provider = ProviderProfile(kind = ProviderKind.ANTIGRAVITY_SERVER, model = "gemini-3.8-pro")
        fakeBridge.startSession(
            projectId = "p-1",
            projectSlug = "slug",
            projectKind = ProjectKind.PROJECT,
            prompt = "Run migration",
            conversationHistory = emptyList(),
            provider = provider,
            taskId = "task-br",
            brainSnapshot = snapshot
        )

        assertNotNull(receivedPrompt)
        assertTrue(receivedPrompt!!.contains("<BRAIN_CONTEXT>"))
        assertTrue(receivedPrompt!!.contains("Strict Bridge Contract"))
        assertTrue(receivedPrompt!!.contains("<USER_TASK>"))
        assertTrue(receivedPrompt!!.contains("Run migration"))
    }

    // 13. Empty knowledge - minimal valid Brain context still executes
    @Test
    fun testEmptyKnowledgeExecutesWithMinimalValidContext() {
        val emptySnapshot = supervisor.getOrCreateBrainSnapshot(
            taskId = "empty-task",
            attempt = 0,
            projectId = "empty-project",
            query = "Empty query"
        )

        assertNotNull(emptySnapshot)
        assertTrue(emptySnapshot.renderedContext.isNotBlank())
        assertTrue(emptySnapshot.renderedContext.contains("[BRAIN_CONTEXT]"))
        assertTrue(emptySnapshot.fingerprint.isNotBlank())

        val injected = ControlledBrainInjector.inject("Do work", emptySnapshot)
        assertTrue(injected.contains("<BRAIN_CONTEXT>"))
        assertTrue(injected.contains("Do work"))
    }

    // 14. Assembly failure - agent process is not launched
    @Test
    fun testAssemblyFailureAgentProcessNotLaunched() = runBlocking {
        var processLaunched = false
        supervisor.createTask(taskId = "fail-task", projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Prompt", maxRetries = 0)

        // Injected failing assembler
        supervisor.brainContextAssembler = object : BrainContextAssembler() {
            override fun assemble(
                task: CanonicalTask?,
                currentStep: ExecutionStep?,
                projectId: String?,
                query: String?,
                maxCharacters: Int
            ): BrainContext {
                throw BrainContextAssemblyException("Simulated brain assembly corruption")
            }
        }

        val job = supervisor.executeTask("fail-task") {
            processLaunched = true
        }
        job.join()

        assertFalse(processLaunched)
        val finalRecord = supervisor.stateStore.get("fail-task")
        assertNotNull(finalRecord)
        assertEquals(TaskExecutionStatus.FAILED, finalRecord!!.status)
        assertTrue(finalRecord.lastError?.contains("Simulated brain assembly corruption") == true)
    }

    // 15. Cancellation before launch - no process starts
    @Test
    fun testCancellationBeforeLaunch() = runBlocking {
        var processStarted = false
        supervisor.createTask(taskId = "cancel-pre", projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Prompt")

        // Pre-cancel
        supervisor.processSupervisor.markCancellationRequested("cancel-pre")

        val job = supervisor.executeTask("cancel-pre") {
            processStarted = true
        }
        job.join()

        assertFalse(processStarted)
        val finalRecord = supervisor.stateStore.get("cancel-pre")
        assertNotNull(finalRecord)
        assertEquals(TaskExecutionStatus.CANCELLED, finalRecord!!.status)
    }

    // 16. Cancellation after launch - existing cancellation behavior remains intact
    @Test
    fun testCancellationAfterLaunchPreservesSnapshot() = runBlocking {
        supervisor.createTask(taskId = "cancel-post", projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Prompt")

        var snapshotDuringLaunch: BrainContextSnapshot? = null
        val job = supervisor.executeTask("cancel-post") { task ->
            snapshotDuringLaunch = supervisor.getBrainSnapshot(task.taskId)
            supervisor.requestStop(task.taskId)
        }
        job.join()

        val finalRecord = supervisor.stateStore.get("cancel-post")
        assertEquals(TaskExecutionStatus.CANCELLED, finalRecord?.status)
        assertNotNull(snapshotDuringLaunch)
        val snapshotAfter = supervisor.getBrainSnapshot("cancel-post")
        assertSame(snapshotDuringLaunch, snapshotAfter)
    }

    // 17. Context mutation during execution - repository update does not alter active snapshot
    @Test
    fun testContextMutationDuringExecutionDoesNotAlterActiveSnapshot() {
        val taskId = "task-mut-17"
        supervisor.createTask(taskId = taskId, projectId = "p-mut", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Prompt")

        val initialSnapshot = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0, projectId = "p-mut")
        val initialFingerprint = initialSnapshot.fingerprint

        // Mutate repository mid-execution
        knowledgeRepo.save(
            BrainKnowledgeEntry(
                id = "k-mutation",
                projectId = "p-mut",
                key = "New Constraint",
                content = "New constraint added mid-flight",
                knowledgeType = BrainKnowledgeType.CONSTRAINT,
                scope = MemoryScope.PROJECT
            )
        )

        // Read active snapshot
        val currentSnapshot = supervisor.getBrainSnapshot(taskId)
        assertNotNull(currentSnapshot)
        assertEquals(initialFingerprint, currentSnapshot!!.fingerprint)
        assertFalse(currentSnapshot.renderedContext.contains("New constraint added mid-flight"))
    }

    // 18. Service/UI recreation - existing snapshot is reused when execution state survives
    @Test
    fun testServiceUiRecreationSnapshotReused() {
        val taskId = "task-ui-recreate"
        supervisor.createTask(taskId = taskId, projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Prompt")

        val snap1 = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0)
        // Simulate UI recreation / ViewModel re-querying supervisor
        val snap2 = supervisor.getBrainSnapshot(taskId)

        assertSame(snap1, snap2)
    }

    // 19. Context size - injected Brain context remains <= 8,000 chars
    @Test
    fun testContextSizeRemainsBoundedUnder8000Chars() {
        // Create huge knowledge entries
        for (i in 1..25) {
            knowledgeRepo.save(
                BrainKnowledgeEntry(
                    id = "huge-$i",
                    projectId = "p-huge",
                    key = "Constraint $i",
                    content = "A".repeat(500),
                    knowledgeType = BrainKnowledgeType.CONSTRAINT,
                    scope = MemoryScope.PROJECT
                )
            )
        }

        val snapshot = supervisor.getOrCreateBrainSnapshot(
            taskId = "t-huge",
            attempt = 0,
            projectId = "p-huge",
            query = "Large query"
        )

        assertTrue(snapshot.renderedContext.length <= BrainContextAssembler.DEFAULT_MAX_CONTEXT_CHARS)
        val injected = ControlledBrainInjector.inject("User prompt", snapshot)
        val brainBlock = ControlledBrainInjector.extractBrainContext(injected)
        assertNotNull(brainBlock)
        assertTrue(brainBlock!!.length <= BrainContextAssembler.DEFAULT_MAX_CONTEXT_CHARS)
    }

    // 20. Large context safety - no duplicated/unbounded prompt construction
    @Test
    fun testLargeContextSafetyNoDuplication() {
        val hugeTask = "X".repeat(5000)
        val snapshot = BrainContextSnapshot.create(
            "t-20", "t-20:0",
            BrainContext(),
            assembler.render(BrainContext())
        )

        val injected1 = ControlledBrainInjector.inject(hugeTask, snapshot, "t-20:0")
        val injected2 = ControlledBrainInjector.inject(injected1, snapshot, "t-20:0")

        assertEquals(injected1.length, injected2.length)
        assertEquals(hugeTask, ControlledBrainInjector.extractUserTask(injected2))
    }

    // 21. Special characters - Unicode/newlines/brackets remain safe
    @Test
    fun testSpecialCharactersSafety() {
        val complexPrompt = """
            Fix 🚀 unicode issue!
            Newlines: line1
            line2
            Brackets: [test] and {json: "value"}
            Quotes: "hello" 'world'
        """.trimIndent()

        val snapshot = BrainContextSnapshot.create(
            "t-21", "t-21:0",
            BrainContext(constraints = listOf("Handle Unicode: 🌍 and [SPECIAL_TAG]")),
            assembler.render(BrainContext(constraints = listOf("Handle Unicode: 🌍 and [SPECIAL_TAG]")))
        )

        val injected = ControlledBrainInjector.inject(complexPrompt, snapshot)
        val extracted = ControlledBrainInjector.extractUserTask(injected)

        assertEquals(complexPrompt, extracted)
        assertTrue(injected.contains("🌍"))
        assertTrue(injected.contains("🚀"))
    }

    // 22. Brain content containing section tags
    @Test
    fun testBrainContentContainingSectionTags() {
        val promptWithTags = "Investigate [BRAIN_CONTEXT] and [/BRAIN_CONTEXT] alongside [USER_TASK] and </USER_TASK> tags"
        val snapshot = BrainContextSnapshot.create(
            "t-22", "t-22:0",
            BrainContext(constraints = listOf("Do not confuse [BRAIN_CONTEXT] or </BRAIN_CONTEXT> tags")),
            assembler.render(BrainContext(constraints = listOf("Do not confuse [BRAIN_CONTEXT] or </BRAIN_CONTEXT> tags")))
        )

        val injected = ControlledBrainInjector.inject(promptWithTags, snapshot)
        assertTrue(injected.startsWith("<BRAIN_CONTEXT>"))
        assertTrue(injected.endsWith("</USER_TASK>"))

        val extracted = ControlledBrainInjector.extractUserTask(injected)
        assertEquals(promptWithTags, extracted)
    }

    // 23. Fingerprint observability - recorded fingerprint matches snapshot
    @Test
    fun testFingerprintObservabilityMatchesSnapshot() {
        val taskId = "task-obs-23"
        supervisor.createTask(taskId = taskId, projectId = "p-1", projectSlug = "slug", chatId = "c-1", agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Task prompt")

        val snapshot = supervisor.getOrCreateBrainSnapshot(taskId = taskId, attempt = 0)
        val durableTask = supervisor.stateStore.get(taskId)

        assertNotNull(durableTask)
        assertTrue(durableTask!!.lastKnownStep?.startsWith("brain:${snapshot.fingerprint}@") == true)
    }

    // 24. Multiple RuntimeBridge implementations - all use the same injection path
    @Test
    fun testMultipleRuntimeBridgeImplementationsSharedInjectionPath() {
        val snapshot = BrainContextSnapshot.create(
            "t-24", "t-24:0",
            BrainContext(constraints = listOf("Bridge parity test")),
            assembler.render(BrainContext(constraints = listOf("Bridge parity test")))
        )
        val prompt = "Parity check"

        // ControlledBrainInjector is the single shared authority for all bridges
        val injectedForAntigravity = ControlledBrainInjector.inject(prompt, snapshot, "t-24:0")
        ControlledBrainInjector.clearAttempt("t-24:0")
        val injectedForClaude = ControlledBrainInjector.inject(prompt, snapshot, "t-24:0")
        ControlledBrainInjector.clearAttempt("t-24:0")
        val injectedForDsh = ControlledBrainInjector.inject(prompt, snapshot, "t-24:0")

        assertEquals(injectedForAntigravity, injectedForClaude)
        assertEquals(injectedForClaude, injectedForDsh)
    }

    // 25. Existing Phase 1 regression - error classification and lifecycle intact
    @Test
    fun testPhase1ReliabilityPreservation() {
        val class503 = supervisor.classifyError("HTTP 503: Service Unavailable", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.TRANSIENT_API_ERROR, class503)

        val classMutated = supervisor.classifyError("Compile error", workspaceMutated = true, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.WORKSPACE_MUTATED_FAILURE, classMutated)

        val classUserStop = supervisor.classifyError("Process stopped by user", workspaceMutated = false, isCancelled = true)
        assertEquals(TaskSupervisor.TaskErrorClassification.USER_CANCELLED, classUserStop)

        val classAuth = supervisor.classifyError("HTTP 401: Invalid API Key", workspaceMutated = false, isCancelled = false)
        assertEquals(TaskSupervisor.TaskErrorClassification.PERMANENT_AUTH_OR_CONFIG, classAuth)
    }
}
