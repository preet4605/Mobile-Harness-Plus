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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

/**
 * Verification test suite for Phase 6 Precondition 4:
 * "Strict Step Verification Gate".
 *
 * Verifies that step completion depends exclusively on trusted, deterministic runtime verification:
 * 1. Agent cannot self-verify or force completion through text/tool output.
 * 2. Process exit code 0 alone never completes a step without verifier criteria.
 * 3. File existence, absence, exact substring, regex content, and command exit code checks.
 * 4. Path traversal, absolute path, and workspace escape rejection.
 * 5. VERIFYING state persistence, crash recovery, and non-corruption during retries.
 * 6. Verification failure blocks later steps and does not trigger automatic rollback.
 * 7. Duplicate verification cannot double-complete steps.
 */
class StrictStepVerificationGateTest {

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

    // 1. Agent cannot self-verify (claims in agent output/messages are ignored)
    @Test
    fun test1_agentCannotSelfVerify() = runBlocking {
        val taskId = "test-agent-cannot-self-verify"
        val expectedFile = "artifact_from_tool.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Self Verify Attempt",
            description = "Agent attempts to self-report completion",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-1",
            projectSlug = "slug-1",
            chatId = "c1",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Do work",
            plan = ExecutionPlan(steps = listOf(step))
        )

        // Agent execution claims success in stdout/messages, but does NOT create the file
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val simulatedAgentOutput = """
                {"status": "COMPLETED", "verified": true, "message": "All steps completed successfully"}
            """.trimIndent()
            assertTrue(simulatedAgentOutput.contains("COMPLETED"))
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals("Agent self-declaration must be ignored; step must FAIL verification", StepStatus.FAILED, resultStep.status)
        assertTrue(resultStep.resultSummary?.contains("Expected file does not exist") == true)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
        assertEquals(0, canonical.plan.currentStepIndex)
    }

    // 2. Process exit code 0 alone without verifier criteria finishes unverified
    @Test
    fun test2_exit0WithoutVerifierCriteriaIsUnverified() = runBlocking {
        val taskId = "test-exit0-no-criteria"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Empty Criteria Step",
            description = "No verification criteria specified",
            expectedFiles = emptyList(),
            forbiddenFiles = emptyList(),
            expectedContent = emptyMap(),
            verificationCommand = null,
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-2",
            projectSlug = "slug-2",
            chatId = "c2",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Run with zero criteria",
            plan = ExecutionPlan(steps = listOf(step))
        )

        // Process finishes with exit 0 (no exception)
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            // Clean exit
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals("Exit 0 without verifier criteria cannot prove completion", StepStatus.UNVERIFIED, resultStep.status)
        assertTrue(
            "Failure reason must state no criteria specified: ${resultStep.resultSummary}",
            resultStep.resultSummary?.contains("no deterministic verification criteria") == true
        )
        assertEquals(TaskExecutionStatus.UNVERIFIED, supervisor.stateStore.get(taskId)?.status)
    }

    // 3. Expected file exists pass and fail (including directory rejection)
    @Test
    fun test3_expectedFile_passAndFail() = runBlocking {
        val taskIdPass = "test-expected-file-pass"
        val stepPass = ExecutionStep(
            stepOrder = 0,
            title = "Expected File Pass",
            description = "Creates valid file",
            expectedFiles = listOf("build/target.apk"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdPass,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Pass file",
            plan = ExecutionPlan(steps = listOf(stepPass))
        )
        val jobPass = supervisor.executeTask(taskIdPass) { _, _ ->
            val f = File(workspaceDir, "build/target.apk").apply { parentFile?.mkdirs(); writeText("binary") }
        }
        jobPass.join()
        assertEquals(StepStatus.COMPLETED, supervisor.canonicalTaskRepository.getTask(taskIdPass)!!.plan.steps[0].status)

        // Fail case: file missing
        val taskIdMissing = "test-expected-file-missing"
        val stepMissing = ExecutionStep(
            stepOrder = 0,
            title = "Expected File Missing",
            description = "Does not create file",
            expectedFiles = listOf("missing/file.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdMissing,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Missing file",
            plan = ExecutionPlan(steps = listOf(stepMissing))
        )
        val jobMissing = supervisor.executeTask(taskIdMissing) { _, _ -> }
        jobMissing.join()
        val missingStep = supervisor.canonicalTaskRepository.getTask(taskIdMissing)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, missingStep.status)
        assertTrue(missingStep.resultSummary?.contains("Expected file does not exist") == true)

        // Fail case: directory instead of regular file
        val taskIdDir = "test-expected-file-dir"
        val stepDir = ExecutionStep(
            stepOrder = 0,
            title = "Expected File Is Directory",
            description = "Creates directory instead of file",
            expectedFiles = listOf("build/not_a_file"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdDir,
            projectId = "proj-3",
            projectSlug = "slug-3",
            chatId = "c3",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Dir file",
            plan = ExecutionPlan(steps = listOf(stepDir))
        )
        val jobDir = supervisor.executeTask(taskIdDir) { _, _ ->
            File(workspaceDir, "build/not_a_file").mkdirs()
        }
        jobDir.join()
        val dirStep = supervisor.canonicalTaskRepository.getTask(taskIdDir)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, dirStep.status)
        assertTrue(dirStep.resultSummary?.contains("not a regular file") == true)
    }

    // 4. Forbidden file absent pass and fail
    @Test
    fun test4_forbiddenFile_passAndFail() = runBlocking {
        // Pass: forbidden file absent
        val taskIdPass = "test-forbidden-pass"
        val stepPass = ExecutionStep(
            stepOrder = 0,
            title = "Forbidden Absent",
            description = "No forbidden file",
            forbiddenFiles = listOf("temp/secret.pem"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdPass,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Forbidden absent",
            plan = ExecutionPlan(steps = listOf(stepPass))
        )
        val jobPass = supervisor.executeTask(taskIdPass) { _, _ -> }
        jobPass.join()
        assertEquals(StepStatus.COMPLETED, supervisor.canonicalTaskRepository.getTask(taskIdPass)!!.plan.steps[0].status)

        // Fail: forbidden file exists
        val taskIdFail = "test-forbidden-fail"
        val stepFail = ExecutionStep(
            stepOrder = 0,
            title = "Forbidden Present",
            description = "Forbidden file is created",
            forbiddenFiles = listOf("temp/secret.pem"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdFail,
            projectId = "proj-4",
            projectSlug = "slug-4",
            chatId = "c4",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Forbidden present",
            plan = ExecutionPlan(steps = listOf(stepFail))
        )
        val jobFail = supervisor.executeTask(taskIdFail) { _, _ ->
            File(workspaceDir, "temp/secret.pem").apply { parentFile?.mkdirs(); writeText("KEY") }
        }
        jobFail.join()
        val failStep = supervisor.canonicalTaskRepository.getTask(taskIdFail)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, failStep.status)
        assertTrue(failStep.resultSummary?.contains("Forbidden file exists") == true)
    }

    // 5. Exact and substring content verification
    @Test
    fun test5_exactSubstringContent() = runBlocking {
        val targetPath = "config/settings.json"
        val expectedSubstring = "\"database\": \"sqlite3\""

        // Pass case
        val taskIdPass = "test-substring-pass"
        val stepPass = ExecutionStep(
            stepOrder = 0,
            title = "Content Substring Pass",
            description = "Matches substring",
            expectedContent = mapOf(targetPath to expectedSubstring),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdPass,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Content pass",
            plan = ExecutionPlan(steps = listOf(stepPass))
        )
        val jobPass = supervisor.executeTask(taskIdPass) { _, _ ->
            File(workspaceDir, targetPath).apply {
                parentFile?.mkdirs()
                writeText("{\n  \"env\": \"prod\",\n  \"database\": \"sqlite3\",\n  \"port\": 8080\n}")
            }
        }
        jobPass.join()
        assertEquals(StepStatus.COMPLETED, supervisor.canonicalTaskRepository.getTask(taskIdPass)!!.plan.steps[0].status)

        // Fail case
        val taskIdFail = "test-substring-fail"
        val stepFail = ExecutionStep(
            stepOrder = 0,
            title = "Content Substring Fail",
            description = "Fails to match substring",
            expectedContent = mapOf(targetPath to expectedSubstring),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdFail,
            projectId = "proj-5",
            projectSlug = "slug-5",
            chatId = "c5",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Content fail",
            plan = ExecutionPlan(steps = listOf(stepFail))
        )
        val jobFail = supervisor.executeTask(taskIdFail) { _, _ ->
            File(workspaceDir, targetPath).writeText("{\n  \"env\": \"prod\",\n  \"database\": \"postgres\"\n}")
        }
        jobFail.join()
        val failStep = supervisor.canonicalTaskRepository.getTask(taskIdFail)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, failStep.status)
        assertTrue(failStep.resultSummary?.contains("does not contain expected pattern/content") == true)
    }

    // 6. Regex content verification
    @Test
    fun test6_regexContent() = runBlocking {
        val targetPath = "version.txt"
        val regexPattern = "^v\\d+\\.\\d+\\.\\d+(-alpha|-beta)?$"

        // Pass case
        val taskIdPass = "test-regex-pass"
        val stepPass = ExecutionStep(
            stepOrder = 0,
            title = "Regex Pass",
            description = "Matches semver regex",
            expectedContent = mapOf(targetPath to regexPattern),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdPass,
            projectId = "proj-6",
            projectSlug = "slug-6",
            chatId = "c6",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Regex pass",
            plan = ExecutionPlan(steps = listOf(stepPass))
        )
        val jobPass = supervisor.executeTask(taskIdPass) { _, _ ->
            File(workspaceDir, targetPath).writeText("v1.2.3-alpha")
        }
        jobPass.join()
        assertEquals(StepStatus.COMPLETED, supervisor.canonicalTaskRepository.getTask(taskIdPass)!!.plan.steps[0].status)

        // Fail case
        val taskIdFail = "test-regex-fail"
        val stepFail = ExecutionStep(
            stepOrder = 0,
            title = "Regex Fail",
            description = "Fails semver regex",
            expectedContent = mapOf(targetPath to regexPattern),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdFail,
            projectId = "proj-6",
            projectSlug = "slug-6",
            chatId = "c6",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Regex fail",
            plan = ExecutionPlan(steps = listOf(stepFail))
        )
        val jobFail = supervisor.executeTask(taskIdFail) { _, _ ->
            File(workspaceDir, targetPath).writeText("invalid-version-string")
        }
        jobFail.join()
        val failStep = supervisor.canonicalTaskRepository.getTask(taskIdFail)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, failStep.status)
        assertTrue(failStep.resultSummary?.contains("does not contain expected pattern/content") == true)
    }

    // 7. Verification command pass and fail (including blank command rejection)
    @Test
    fun test7_verificationCommand_passAndFail() = runBlocking {
        // Pass case: command exit 0
        val taskIdPass = "test-cmd-pass"
        val stepPass = ExecutionStep(
            stepOrder = 0,
            title = "Cmd Pass",
            description = "Workspace directory exists",
            verificationCommand = "test -d .",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdPass,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cmd pass",
            plan = ExecutionPlan(steps = listOf(stepPass))
        )
        val jobPass = supervisor.executeTask(taskIdPass) { _, _ -> }
        jobPass.join()
        assertEquals(StepStatus.COMPLETED, supervisor.canonicalTaskRepository.getTask(taskIdPass)!!.plan.steps[0].status)

        // Fail case: command non-zero
        val taskIdFail = "test-cmd-fail"
        val stepFail = ExecutionStep(
            stepOrder = 0,
            title = "Cmd Fail",
            description = "Exit code 1",
            verificationCommand = "sh -c 'exit 1'",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdFail,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cmd fail",
            plan = ExecutionPlan(steps = listOf(stepFail))
        )
        val jobFail = supervisor.executeTask(taskIdFail) { _, _ -> }
        jobFail.join()
        val failStep = supervisor.canonicalTaskRepository.getTask(taskIdFail)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, failStep.status)
        assertTrue(failStep.resultSummary?.contains("Verification command failed with exit code") == true)

        // Blank command case: rejected
        val taskIdBlank = "test-cmd-blank"
        val stepBlank = ExecutionStep(
            stepOrder = 0,
            title = "Cmd Blank",
            description = "Blank command rejected",
            verificationCommand = "    ",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdBlank,
            projectId = "proj-7",
            projectSlug = "slug-7",
            chatId = "c7",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cmd blank",
            plan = ExecutionPlan(steps = listOf(stepBlank))
        )
        val jobBlank = supervisor.executeTask(taskIdBlank) { _, _ -> }
        jobBlank.join()
        val blankStep = supervisor.canonicalTaskRepository.getTask(taskIdBlank)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, blankStep.status)
        assertTrue(blankStep.resultSummary?.contains("Verification command is blank or invalid") == true)
    }

    // 8. Path traversal rejected
    @Test
    fun test8_pathTraversalRejected() = runBlocking {
        val taskId = "test-traversal-rejected"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Traversal Step",
            description = "Path traversal check",
            expectedFiles = listOf("../secret.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-8",
            projectSlug = "slug-8",
            chatId = "c8",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Traversal test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, resultStep.status)
        assertTrue(
            "Failure reason must mention path traversal rejection: ${resultStep.resultSummary}",
            resultStep.resultSummary?.contains("Path traversal (..) is forbidden") == true
        )
    }

    // 9. Absolute path rejected
    @Test
    fun test9_absolutePathRejected() = runBlocking {
        val taskId = "test-absolute-path-rejected"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Absolute Path Step",
            description = "Absolute path check",
            expectedFiles = listOf("/etc/passwd"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-9",
            projectSlug = "slug-9",
            chatId = "c9",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Absolute path test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, resultStep.status)
        assertTrue(
            "Failure reason must mention absolute path rejection: ${resultStep.resultSummary}",
            resultStep.resultSummary?.contains("Absolute paths are forbidden") == true
        )
    }

    // 10. Workspace escape rejected (symlink escaping workspace)
    @Test
    fun test10_workspaceEscapeRejected() = runBlocking {
        val outsideDir = tempFolder.newFolder("outside")
        val outsideFile = File(outsideDir, "outside_secret.txt").apply { writeText("secret") }

        // Create symlink inside workspace pointing to outside file
        val symlinkFile = File(workspaceDir, "escaped_link.txt")
        runCatching {
            Files.createSymbolicLink(symlinkFile.toPath(), outsideFile.toPath())
        }

        if (symlinkFile.exists()) {
            val taskId = "test-escape-rejected"
            val step = ExecutionStep(
                stepOrder = 0,
                title = "Workspace Escape Step",
                description = "Symlink escaping workspace boundary",
                expectedFiles = listOf("escaped_link.txt"),
                status = StepStatus.PENDING
            )
            supervisor.createTask(
                taskId = taskId,
                projectId = "proj-10",
                projectSlug = "slug-10",
                chatId = "c10",
                agentKind = "ANTIGRAVITY",
                providerJson = "{}",
                prompt = "Escape test",
                plan = ExecutionPlan(steps = listOf(step))
            )

            val job = supervisor.executeTask(taskId) { _, _ -> }
            job.join()

            val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
            assertNotNull(canonical)
            val resultStep = canonical!!.plan.steps[0]
            assertEquals(StepStatus.FAILED, resultStep.status)
            assertTrue(
                "Failure reason must mention workspace escape: ${resultStep.resultSummary}",
                resultStep.resultSummary?.contains("escapes workspace boundary") == true
            )
        }
    }

    // 11. VERIFYING state persistence
    @Test
    fun test11_verifyingPersistence() = runBlocking {
        val taskId = "test-verifying-persistence"
        val expectedFile = "verify_persist.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Verifying Persistence Step",
            description = "Check VERIFYING state in SQLite",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-11",
            projectSlug = "slug-11",
            chatId = "c11",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Verifying persistence",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var statusCapturedDuringVerification: StepStatus? = null
        var lastKnownStepCapturedDuringVerification: String? = null

        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                val dbTask = supervisor.canonicalTaskRepository.getTask(taskId)
                val dbDurable = supervisor.stateStore.get(taskId)
                statusCapturedDuringVerification = dbTask?.plan?.steps?.get(0)?.status
                lastKnownStepCapturedDuringVerification = dbDurable?.lastKnownStep
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ ->
            File(workspaceDir, expectedFile).writeText("ok")
        }
        job.join()

        assertEquals("SQLite step status must be VERIFYING during verification", StepStatus.VERIFYING, statusCapturedDuringVerification)
        assertEquals("Durable record lastKnownStep must record VERIFYING", "step-0:VERIFYING", lastKnownStepCapturedDuringVerification)
        assertEquals(StepStatus.COMPLETED, supervisor.canonicalTaskRepository.getTask(taskId)!!.plan.steps[0].status)
    }

    // 12. Crash / reload during VERIFYING never silently becomes COMPLETED
    @Test
    fun test12_crashReloadDuringVerifying() = runBlocking {
        val taskId = "test-crash-during-verifying"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Crash Step",
            description = "Crashed during verification",
            status = StepStatus.VERIFYING
        )
        val canonical = CanonicalTask(
            taskId = taskId,
            projectId = "proj-12",
            projectSlug = "slug-12",
            objective = "Crash recovery test",
            plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        )
        canonicalRepo.saveTask(canonical)

        // Durable task record was left RUNNING with dead process
        val durable = DurableTaskRecord(
            taskId = taskId,
            projectId = "proj-12",
            projectSlug = "slug-12",
            chatId = "c12",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Crash test",
            status = TaskExecutionStatus.RUNNING,
            pid = 9999999
        )
        supervisor.stateStore.save(durable)

        // Startup reconciliation recovers dead process
        val reconciled = supervisor.reconcileOnStartup()
        assertTrue(reconciled.any { it.taskId == taskId })

        val reloadedCanonical = canonicalRepo.getTask(taskId)
        assertNotNull(reloadedCanonical)
        val reloadedStep = reloadedCanonical!!.plan.steps[0]
        assertNotEquals("Crash during VERIFYING must NEVER become COMPLETED", StepStatus.COMPLETED, reloadedStep.status)
        assertEquals("Crash during VERIFYING must be reconciled to FAILED", StepStatus.FAILED, reloadedStep.status)
        assertNotNull(reloadedStep.completedAt)
    }

    // 13. Failed verification blocks next step
    @Test
    fun test13_failedVerificationBlocksNextStep() = runBlocking {
        val taskId = "test-failed-blocks-next"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0 - Fails",
            description = "Fails file check",
            expectedFiles = listOf("uncreated.txt"),
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1 - Blocked",
            description = "Must not execute",
            verificationCommand = "true",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-13",
            projectSlug = "slug-13",
            chatId = "c13",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Blocking test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        var step1Executed = false
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.stepOrder == 1) {
                step1Executed = true
            }
        }
        job.join()

        assertFalse("Step 1 must never be executed after Step 0 failure", step1Executed)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.PENDING, canonical.plan.steps[1].status)
        assertEquals(0, canonical.plan.steps[1].attempts)
        assertEquals(0, canonical.plan.currentStepIndex)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 14. Successful verification advances exactly once
    @Test
    fun test14_successfulVerificationAdvancesExactlyOnce() = runBlocking {
        val taskId = "test-advances-once"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Creates step0.txt",
            expectedFiles = listOf("step0.txt"),
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1",
            description = "Creates step1.txt",
            expectedFiles = listOf("step1.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-14",
            projectSlug = "slug-14",
            chatId = "c14",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Advance test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        val executedSteps = mutableListOf<Int>()
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            executedSteps.add(activeStep.stepOrder)
            File(workspaceDir, "step${activeStep.stepOrder}.txt").writeText("done")
        }
        job.join()

        assertEquals(listOf(0, 1), executedSteps)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[1].status)
        assertEquals("Plan must advance currentStepIndex to steps.size", 2, canonical.plan.currentStepIndex)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 15. Duplicate verification cannot double-complete
    @Test
    fun test15_duplicateVerificationCannotDoubleComplete() = runBlocking {
        val taskId = "test-dedup-verify"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0",
            description = "Creates file",
            expectedFiles = listOf("done.txt"),
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Step 1",
            description = "Creates file 1",
            expectedFiles = listOf("done1.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-15",
            projectSlug = "slug-15",
            chatId = "c15",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Dedup verify test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        // Complete step 0 first
        File(workspaceDir, "done.txt").writeText("done")
        val job1 = supervisor.executeTask(taskId) { _, activeStep ->
            // If activeStep == 0, done.txt is ready.
            // If activeStep == 1, don't create done1.txt yet, fail it.
            if (activeStep.stepOrder == 1) {
                // do nothing -> will fail step 1
            }
        }
        job1.join()

        var canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.FAILED, canonical.plan.steps[1].status)
        assertEquals(1, canonical.plan.currentStepIndex)

        // Reset step 1 to PENDING, keep step 0 as COMPLETED, and run again
        canonical = canonical.copy(
            plan = canonical.plan.withUpdatedStep(canonical.plan.steps[1].copy(status = StepStatus.PENDING))
        )
        supervisor.canonicalTaskRepository.saveTask(canonical)
        supervisor.stateStore.save(supervisor.stateStore.get(taskId)!!.copy(status = TaskExecutionStatus.CREATED))

        val step0ExecutionCount = AtomicInteger(0)
        val step1ExecutionCount = AtomicInteger(0)
        File(workspaceDir, "done1.txt").writeText("done1")

        val job2 = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.stepOrder == 0) step0ExecutionCount.incrementAndGet()
            if (activeStep.stepOrder == 1) step1ExecutionCount.incrementAndGet()
        }
        job2.join()

        assertEquals("Completed step 0 must NOT be re-executed", 0, step0ExecutionCount.get())
        assertEquals("Pending step 1 must be executed once", 1, step1ExecutionCount.get())
        val finalCanonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, finalCanonical.plan.steps[0].status)
        assertEquals(StepStatus.COMPLETED, finalCanonical.plan.steps[1].status)
        assertEquals(2, finalCanonical.plan.currentStepIndex)
    }

    // 16. Retry does not corrupt step status (transient vs verification failure separation)
    @Test
    fun test16_retryDoesNotCorruptStepStatus() = runBlocking {
        // Case A: Transient retry succeeds on second attempt
        val taskIdTransient = "test-retry-transient"
        val stepTransient = ExecutionStep(
            stepOrder = 0,
            title = "Transient Step",
            description = "Fails transiently once",
            expectedFiles = listOf("transient_done.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdTransient,
            projectId = "proj-16",
            projectSlug = "slug-16",
            chatId = "c16",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Transient retry test",
            maxRetries = 2,
            plan = ExecutionPlan(steps = listOf(stepTransient))
        )

        var transientAttemptCount = 0
        val jobTransient = supervisor.executeTask(taskIdTransient) { _, _ ->
            transientAttemptCount++
            if (transientAttemptCount == 1) {
                throw RuntimeException("HTTP 503 transient service error")
            } else {
                File(workspaceDir, "transient_done.txt").writeText("ok on attempt 2")
            }
        }
        jobTransient.join()

        assertEquals("Transient error must retry", 2, transientAttemptCount)
        val transientCanonical = supervisor.canonicalTaskRepository.getTask(taskIdTransient)!!
        assertEquals(StepStatus.COMPLETED, transientCanonical.plan.steps[0].status)
        assertEquals(2, transientCanonical.plan.steps[0].attempts)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskIdTransient)?.status)

        // Case B: Step verification failure does NOT automatically retry
        val taskIdVerifyFail = "test-verify-fail-no-retry"
        val stepVerifyFail = ExecutionStep(
            stepOrder = 0,
            title = "Deterministic Fail Step",
            description = "Verification fails deterministically",
            expectedFiles = listOf("missing_forever.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdVerifyFail,
            projectId = "proj-16",
            projectSlug = "slug-16",
            chatId = "c16",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Verify fail no retry",
            maxRetries = 2,
            plan = ExecutionPlan(steps = listOf(stepVerifyFail))
        )

        var verifyFailRunCount = 0
        val jobVerifyFail = supervisor.executeTask(taskIdVerifyFail) { _, _ ->
            verifyFailRunCount++
        }
        jobVerifyFail.join()

        assertEquals("Deterministic verification failure must NOT trigger task retries", 1, verifyFailRunCount)
        val verifyFailCanonical = supervisor.canonicalTaskRepository.getTask(taskIdVerifyFail)!!
        assertEquals(StepStatus.FAILED, verifyFailCanonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskIdVerifyFail)?.status)
    }

    // 17. Verification command rejected on absolute path access
    @Test
    fun test17_verificationCommand_absolutePathAccessRejected() = runBlocking {
        val outsideDir = tempFolder.newFolder("outside_abs")
        val outsideFile = File(outsideDir, "secret_abs.txt").apply { writeText("top-secret") }

        val taskId = "test-cmd-abs-path"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Absolute Path Command Step",
            description = "Attempts to access outside file via absolute path",
            verificationCommand = "cat ${outsideFile.absolutePath}",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-17",
            projectSlug = "slug-17",
            chatId = "c17",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Abs path test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals("Absolute path command must FAIL verification", StepStatus.FAILED, resultStep.status)
        assertTrue(
            "Failure reason must mention absolute path outside workspace: ${resultStep.resultSummary}",
            resultStep.resultSummary?.contains("Absolute path outside workspace is forbidden") == true
        )
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 18. Verification command rejected on cd /
    @Test
    fun test18_verificationCommand_cdRootRejected() = runBlocking {
        val taskId = "test-cmd-cd-root"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "cd / Command Step",
            description = "Attempts to cd / to escape workspace",
            verificationCommand = "cd / && ls",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-18",
            projectSlug = "slug-18",
            chatId = "c18",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "cd / test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals("cd / command must FAIL verification", StepStatus.FAILED, resultStep.status)
        assertTrue(
            "Failure reason must mention cd outside workspace rejection: ${resultStep.resultSummary}",
            resultStep.resultSummary?.contains("cd outside workspace is forbidden") == true
        )
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 19. Verification command rejected on cd .. / ../../ path traversal
    @Test
    fun test19_verificationCommand_cdTraversalRejected() = runBlocking {
        // Case A: cd ..
        val taskIdCdDotDot = "test-cmd-cd-dotdot"
        val stepCdDotDot = ExecutionStep(
            stepOrder = 0,
            title = "cd .. Command Step",
            description = "Attempts to cd .. to escape workspace",
            verificationCommand = "cd .. && ls",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdCdDotDot,
            projectId = "proj-19",
            projectSlug = "slug-19",
            chatId = "c19",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "cd .. test",
            plan = ExecutionPlan(steps = listOf(stepCdDotDot))
        )
        val jobA = supervisor.executeTask(taskIdCdDotDot) { _, _ -> }
        jobA.join()
        val stepAResult = supervisor.canonicalTaskRepository.getTask(taskIdCdDotDot)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, stepAResult.status)
        assertTrue(
            "Failure reason must mention traversal or cd outside workspace: ${stepAResult.resultSummary}",
            stepAResult.resultSummary?.contains("Path traversal (..) is forbidden") == true ||
                stepAResult.resultSummary?.contains("cd outside workspace is forbidden") == true
        )

        // Case B: ../../ traversal reading file
        val taskIdTraversal = "test-cmd-traversal-multi"
        val stepTraversal = ExecutionStep(
            stepOrder = 0,
            title = "Multi traversal Command Step",
            description = "Attempts ../../ to escape workspace",
            verificationCommand = "cat ../../secret.txt",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskIdTraversal,
            projectId = "proj-19",
            projectSlug = "slug-19",
            chatId = "c19",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "traversal test",
            plan = ExecutionPlan(steps = listOf(stepTraversal))
        )
        val jobB = supervisor.executeTask(taskIdTraversal) { _, _ -> }
        jobB.join()
        val stepBResult = supervisor.canonicalTaskRepository.getTask(taskIdTraversal)!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, stepBResult.status)
        assertTrue(
            "Failure reason must mention path traversal rejection: ${stepBResult.resultSummary}",
            stepBResult.resultSummary?.contains("Path traversal (..) is forbidden") == true
        )
    }

    // 20. Verification command rejected on workspace symlink escape
    @Test
    fun test20_verificationCommand_symlinkEscapeRejected() = runBlocking {
        val outsideDir = tempFolder.newFolder("outside_symlink")
        val outsideFile = File(outsideDir, "outside_data.txt").apply { writeText("confidential") }

        // Create symlink inside workspace pointing to outside file
        val symlinkFile = File(workspaceDir, "escaped_cmd_link.txt")
        runCatching {
            Files.createSymbolicLink(symlinkFile.toPath(), outsideFile.toPath())
        }

        if (symlinkFile.exists()) {
            val taskId = "test-cmd-symlink-escape"
            val step = ExecutionStep(
                stepOrder = 0,
                title = "Symlink Escape Command Step",
                description = "Command targets symlink pointing outside workspace",
                verificationCommand = "cat escaped_cmd_link.txt",
                status = StepStatus.PENDING
            )
            supervisor.createTask(
                taskId = taskId,
                projectId = "proj-20",
                projectSlug = "slug-20",
                chatId = "c20",
                agentKind = "ANTIGRAVITY",
                providerJson = "{}",
                prompt = "Symlink escape test",
                plan = ExecutionPlan(steps = listOf(step))
            )

            val job = supervisor.executeTask(taskId) { _, _ -> }
            job.join()

            val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
            assertNotNull(canonical)
            val resultStep = canonical!!.plan.steps[0]
            assertEquals("Workspace symlink escape must FAIL verification", StepStatus.FAILED, resultStep.status)
            assertTrue(
                "Failure reason must mention workspace escape or symlink: ${resultStep.resultSummary}",
                resultStep.resultSummary?.contains("escaping workspace boundary") == true
            )
            assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
        }
    }

    // 21. Safe verification command strictly within workspace succeeds
    @Test
    fun test21_verificationCommand_safeWorkspaceCommandSucceeds() = runBlocking {
        val taskId = "test-cmd-safe-pass"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Safe Command Step",
            description = "Valid command confined to workspace",
            verificationCommand = "grep 'expected-output' output.txt",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-21",
            projectSlug = "slug-21",
            chatId = "c21",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Safe command pass",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val job = supervisor.executeTask(taskId) { _, _ ->
            File(workspaceDir, "output.txt").writeText("line1\nexpected-output\nline3")
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals(StepStatus.COMPLETED, resultStep.status)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 22. Verification command timeout enforcement
    @Test
    fun test22_timeoutEnforcement() = runBlocking {
        val taskId = "test-cmd-timeout"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Timeout Step",
            description = "Command exceeds timeout bound",
            verificationCommand = "sleep 5",
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-22",
            projectSlug = "slug-22",
            chatId = "c22",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Timeout test",
            plan = ExecutionPlan(steps = listOf(step))
        )
        supervisor.stepVerifier = DefaultStepVerifier(commandTimeoutSeconds = 1)

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)
        assertNotNull(canonical)
        val resultStep = canonical!!.plan.steps[0]
        assertEquals(StepStatus.FAILED, resultStep.status)
        assertTrue(
            "Failure reason must mention timeout: ${resultStep.resultSummary}",
            resultStep.resultSummary?.contains("timed out") == true
        )
    }

    // 23. Restart during VERIFYING state reruns trusted verification
    @Test
    fun test23_restartDuringVerifying_rerunsVerification() = runBlocking {
        val taskId = "test-restart-verifying"
        val expectedFile = "restart_verified.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Interrupted Verifying Step",
            description = "Restart in VERIFYING state",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.VERIFYING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-23",
            projectSlug = "slug-23",
            chatId = "c23",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Restart verifying test",
            plan = ExecutionPlan(steps = listOf(step), currentStepIndex = 0)
        )
        File(workspaceDir, expectedFile).writeText("ready")

        val verifierRan = AtomicBoolean(false)
        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                verifierRan.set(true)
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        assertTrue("StepVerifier MUST rerun on restart during VERIFYING", verifierRan.get())
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(1, canonical.plan.currentStepIndex)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }

    // 24. Failed verification enters RecoveryEngine
    @Test
    fun test24_failedVerificationEntersRecovery() = runBlocking {
        val taskId = "test-fail-enters-recovery"
        val expectedFile = "needed_target.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Step Entering Recovery",
            description = "Fails verification and enters recovery",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING,
            maxAttempts = 2
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-24",
            projectSlug = "slug-24",
            chatId = "c24",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Recovery entry test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var recoveryPlanned = false
        var recoveryExecuted = false
        val mockRecoveryEngine = object : RecoveryEngine {
            override val allowedStrategies = RecoveryEngine.ALLOWED_RECOVERY_STRATEGIES
            override fun calculateBackoffMillis(attempt: Int): Long = 10L
            override fun planRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                classification: TaskSupervisor.TaskErrorClassification,
                errorMessage: String,
                mutatedFiles: List<String>,
                attemptCount: Int
            ): RecoveryPlan? {
                assertEquals(TaskSupervisor.TaskErrorClassification.STEP_VERIFICATION_FAILURE, classification)
                recoveryPlanned = true
                return RecoveryPlan(
                    taskId = task.taskId,
                    failureRecordId = "rec-1",
                    strategy = RecoveryStrategy.RETRY_STEP,
                    rationale = "Retry after verification failure",
                    targetStepIndex = step.stepOrder,
                    stepId = step.stepId,
                    attemptNumber = attemptCount + 1
                )
            }
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                recoveryExecuted = true
                return RecoveryExecutionResult(
                    success = true,
                    strategy = plan.strategy,
                    shouldRetryStep = true,
                    message = "Recovered for retry"
                )
            }
        }
        supervisor.recoveryEngine = mockRecoveryEngine

        var attempts = 0
        val job = supervisor.executeTask(taskId) { _, activeStep ->
            attempts++
            if (attempts == 2) {
                File(workspaceDir, expectedFile).writeText("created on attempt 2")
            }
        }
        job.join()

        assertTrue("Recovery plan must be invoked on verification failure", recoveryPlanned)
        assertTrue("Recovery execute must be invoked", recoveryExecuted)
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(2, canonical.plan.steps[0].attempts)
        assertTrue(canonical.failureHistory.any { it.classification == "STEP_VERIFICATION_FAILURE" })
    }

    // 25. Recovery returns strictly through RUNNING -> VERIFYING -> StepVerifier
    @Test
    fun test25_recoveryReturnsThroughRunningVerifyingStepVerifier() = runBlocking {
        val taskId = "test-recovery-state-flow"
        val expectedFile = "state_flow.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "State Flow Step",
            description = "Tracks state transitions during recovery",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING,
            maxAttempts = 2
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-25",
            projectSlug = "slug-25",
            chatId = "c25",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "State flow test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val recordedStepStates = mutableListOf<StepStatus>()
        var attemptCount = 0

        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                recordedStepStates.add(StepStatus.VERIFYING)
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val mockRecoveryEngine = object : RecoveryEngine {
            override val allowedStrategies = RecoveryEngine.ALLOWED_RECOVERY_STRATEGIES
            override fun calculateBackoffMillis(attempt: Int): Long = 10L
            override fun planRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                classification: TaskSupervisor.TaskErrorClassification,
                errorMessage: String,
                mutatedFiles: List<String>,
                attemptCount: Int
            ): RecoveryPlan? {
                recordedStepStates.add(StepStatus.RECOVERING)
                return RecoveryPlan(
                    taskId = task.taskId,
                    failureRecordId = "rec-2",
                    strategy = RecoveryStrategy.RETRY_STEP,
                    rationale = "Retry step",
                    targetStepIndex = step.stepOrder,
                    stepId = step.stepId,
                    attemptNumber = attemptCount + 1
                )
            }
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                return RecoveryExecutionResult(
                    success = true,
                    strategy = plan.strategy,
                    shouldRetryStep = true,
                    message = "Recovered"
                )
            }
        }
        supervisor.recoveryEngine = mockRecoveryEngine

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            attemptCount++
            recordedStepStates.add(activeStep.status)
            if (attemptCount == 2) {
                File(workspaceDir, expectedFile).writeText("done")
            }
        }
        job.join()

        assertEquals(
            listOf(
                StepStatus.RUNNING,
                StepStatus.VERIFYING,
                StepStatus.RECOVERING,
                StepStatus.RUNNING,
                StepStatus.VERIFYING
            ),
            recordedStepStates
        )
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
    }

    // 26. No direct RECOVERING -> COMPLETED transition
    @Test
    fun test26_noDirectRecoveryCompletion() = runBlocking {
        val taskId = "test-no-direct-recovery-completion"
        val expectedFile = "nonexistent.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "No Direct Complete Step",
            description = "Recovery must never mark COMPLETED directly",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING,
            maxAttempts = 1
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-26",
            projectSlug = "slug-26",
            chatId = "c26",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "No direct complete test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        val mockRecoveryEngine = object : RecoveryEngine {
            override val allowedStrategies = RecoveryEngine.ALLOWED_RECOVERY_STRATEGIES
            override fun calculateBackoffMillis(attempt: Int): Long = 0L
            override fun planRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                classification: TaskSupervisor.TaskErrorClassification,
                errorMessage: String,
                mutatedFiles: List<String>,
                attemptCount: Int
            ): RecoveryPlan? {
                return RecoveryPlan(
                    taskId = task.taskId,
                    failureRecordId = "rec-3",
                    strategy = RecoveryStrategy.SAFE_ABORT_AND_CLEANUP,
                    rationale = "Cleanup on failure",
                    targetStepIndex = step.stepOrder,
                    stepId = step.stepId,
                    attemptNumber = attemptCount + 1
                )
            }
            override suspend fun executeRecovery(
                task: CanonicalTask,
                step: ExecutionStep,
                plan: RecoveryPlan,
                workspaceDir: File?,
                checkpoints: WorkspaceCheckpoints?
            ): RecoveryExecutionResult {
                return RecoveryExecutionResult(
                    success = true,
                    strategy = plan.strategy,
                    shouldRetryStep = false,
                    message = "Cleaned up"
                )
            }
        }
        supervisor.recoveryEngine = mockRecoveryEngine

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertNotEquals("Step must NEVER transition directly from RECOVERING to COMPLETED", StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.FAILED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 27. Final step verification required before plan completion
    @Test
    fun test27_finalStepVerificationRequired() = runBlocking {
        val taskId = "test-final-step-verification"
        val step0 = ExecutionStep(
            stepOrder = 0,
            title = "Step 0 Pass",
            description = "Step 0 passes",
            expectedFiles = listOf("step0_ok.txt"),
            status = StepStatus.PENDING
        )
        val step1 = ExecutionStep(
            stepOrder = 1,
            title = "Final Step 1 Fail",
            description = "Final step fails verification",
            expectedFiles = listOf("final_missing.txt"),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-27",
            projectSlug = "slug-27",
            chatId = "c27",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Final step test",
            plan = ExecutionPlan(steps = listOf(step0, step1), currentStepIndex = 0)
        )

        val job = supervisor.executeTask(taskId) { _, activeStep ->
            if (activeStep.stepOrder == 0) {
                File(workspaceDir, "step0_ok.txt").writeText("step 0 done")
            }
        }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(StepStatus.FAILED, canonical.plan.steps[1].status)
        assertNotEquals("Plan must not be COMPLETED when final step fails verification", PlanStatus.COMPLETED, canonical.plan.status)
        assertEquals(TaskExecutionStatus.FAILED, supervisor.stateStore.get(taskId)?.status)
    }

    // 28. Cancellation during verification immediately terminates
    @Test
    fun test28_cancellationDuringVerification() = runBlocking {
        val taskId = "test-cancellation-during-verification"
        val expectedFile = "cancel_test.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Cancellation Step",
            description = "Cancelled during verifier execution",
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-28",
            projectSlug = "slug-28",
            chatId = "c28",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Cancellation test",
            plan = ExecutionPlan(steps = listOf(step))
        )
        File(workspaceDir, expectedFile).writeText("content")

        val originalVerifier = supervisor.stepVerifier
        supervisor.stepVerifier = object : StepVerifier {
            override fun verify(task: CanonicalTask, step: ExecutionStep, workspaceDir: File?): StepVerificationResult {
                runBlocking { supervisor.requestStop(taskId) }
                return originalVerifier.verify(task, step, workspaceDir)
            }
        }

        val job = supervisor.executeTask(taskId) { _, _ -> }
        job.join()

        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertNotEquals("Step must not be COMPLETED if cancelled during verification", StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.CANCELLED, supervisor.stateStore.get(taskId)?.status)
    }

    // 29. Brain snapshot and policy injection preserved during verification
    @Test
    fun test29_brainPolicyInjectionPreserved() = runBlocking {
        val taskId = "test-brain-policy-injection"
        val expectedFile = "policy_verified.txt"
        val step = ExecutionStep(
            stepOrder = 0,
            title = "Policy Step",
            description = "Step verifying brain and policy context",
            objective = "Verify architectural integrity",
            acceptanceCriteria = listOf("Policy active", "Context valid"),
            expectedFiles = listOf(expectedFile),
            status = StepStatus.PENDING
        )
        supervisor.createTask(
            taskId = taskId,
            projectId = "proj-29",
            projectSlug = "slug-29",
            chatId = "c29",
            agentKind = "ANTIGRAVITY",
            providerJson = "{}",
            prompt = "Brain policy test",
            plan = ExecutionPlan(steps = listOf(step))
        )

        var snapshotRendered: String? = null
        val job = supervisor.executeTask(taskId) { task, activeStep ->
            val snapshot = supervisor.getBrainSnapshot(task.taskId)
            snapshotRendered = snapshot?.renderedContext
            File(workspaceDir, expectedFile).writeText("policy checked")
        }
        job.join()

        assertNotNull(snapshotRendered)
        assertTrue("Snapshot must contain Global Execution Policies", snapshotRendered!!.contains("EXECUTION POLICIES") || snapshotRendered!!.contains("REASONING BUDGET POLICY"))
        assertTrue("Snapshot must contain step objective", snapshotRendered!!.contains("Verify architectural integrity"))
        val canonical = supervisor.canonicalTaskRepository.getTask(taskId)!!
        assertEquals(StepStatus.COMPLETED, canonical.plan.steps[0].status)
        assertEquals(TaskExecutionStatus.COMPLETED, supervisor.stateStore.get(taskId)?.status)
    }
}
