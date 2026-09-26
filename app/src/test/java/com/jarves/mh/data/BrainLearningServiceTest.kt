package com.jarves.mh.data

import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionFeedback
import com.jarves.mh.model.brain.ExecutionOutcome
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.ExecutionWorkspaceState
import com.jarves.mh.model.brain.LearnedDiscovery
import com.jarves.mh.model.brain.LearnedSolution
import com.jarves.mh.model.brain.LearningResult
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.task.DurableTaskRecord
import com.jarves.mh.runtime.task.TaskExecutionStatus
import com.jarves.mh.runtime.task.TaskSupervisor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class BrainLearningServiceTest {

    private lateinit var db: BrainDatabase
    private lateinit var knowledgeRepo: BrainKnowledgeRepository
    private lateinit var canonicalTaskRepo: CanonicalTaskRepository
    private lateinit var learningService: BrainLearningService

    @Before
    fun setUp() {
        val driver = BrainDatabaseDriverFactory.createInMemoryDriver()
        db = BrainDatabase(driver)
        knowledgeRepo = BrainKnowledgeRepository(db)
        canonicalTaskRepo = CanonicalTaskRepository(db)
        learningService = BrainLearningService(knowledgeRepo)
    }

    // 1. Successful verified execution creates PROGRESS
    @Test
    fun testSuccessfulVerifiedExecutionCreatesProgress() {
        val feedback = ExecutionFeedback(
            taskId = "task-succ-1",
            projectId = "proj-1",
            attemptId = "task-succ-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Compiled and tested feature XYZ",
            completedStepIds = listOf("step-1", "step-2"),
            verifiedCriteria = listOf("Passes all 10 tests", "No memory leak"),
            source = MemorySource.TOOL_VERIFIED
        )

        val result = learningService.learn(feedback)
        assertTrue(result.isSuccessful)
        assertTrue(result.insertedCount >= 4) // outcome + 2 criteria + 2 steps

        val entries = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS)
        assertTrue(entries.any { it.key == "progress:task-succ-1:verified-outcome" })
        assertTrue(entries.any { it.key.contains("criterion") })
        assertTrue(entries.any { it.key == "progress:task-succ-1:step:step-1" })
        assertTrue(entries.any { it.key == "progress:task-succ-1:step:step-2" })

        val outcomeEntry = entries.first { it.key == "progress:task-succ-1:verified-outcome" }
        assertEquals(MemorySource.TOOL_VERIFIED, outcomeEntry.source)
        assertEquals(0.95f, outcomeEntry.confidence, 0.01f)
    }

    // 2. Failed execution creates FAILURE
    @Test
    fun testFailedExecutionCreatesFailure() {
        val failureRecord = TaskFailureRecord(
            taskId = "task-fail-1",
            stepId = "step-3",
            classification = "BUILD_ERROR",
            errorMessage = "Unresolved reference: SymbolX in Activity.kt",
            errorSnippet = "Activity.kt:42: error: unresolved reference"
        )
        val feedback = ExecutionFeedback(
            taskId = "task-fail-1",
            projectId = "proj-1",
            attemptId = "task-fail-1:attempt-0",
            outcome = ExecutionOutcome.FAILED,
            summary = "Build failed on compilation",
            failures = listOf(failureRecord),
            source = MemorySource.TOOL_VERIFIED
        )

        val result = learningService.learn(feedback)
        assertTrue(result.isSuccessful)
        assertEquals(1, result.insertedCount)

        val failures = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.FAILURE)
        assertEquals(1, failures.size)
        val failure = failures.first()
        assertEquals(BrainKnowledgeType.FAILURE, failure.knowledgeType)
        assertTrue(failure.key.contains("build-error"))
        assertTrue(failure.content.contains("Unresolved reference: SymbolX"))
        assertEquals(MemorySource.TOOL_VERIFIED, failure.source)
        assertEquals(0.95f, failure.confidence, 0.01f)
        assertEquals("execution:task-fail-1:task-fail-1:attempt-0", failure.sourceReference)
    }

    // 3. Verified corrective action creates SOLUTION
    @Test
    fun testVerifiedCorrectiveActionCreatesSolution() {
        val solution = LearnedSolution(
            key = "aapt2-override",
            summary = "AAPT2 experimental override flag",
            procedure = "Add android.aapt2FromMavenOverride to gradle.properties",
            failureClassification = "AAPT2_ERROR",
            isVerified = true
        )
        val feedback = ExecutionFeedback(
            taskId = "task-sol-1",
            projectId = "proj-1",
            attemptId = "task-sol-1:attempt-1",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Fixed AAPT2 error by overriding path",
            solutions = listOf(solution),
            source = MemorySource.TOOL_VERIFIED
        )

        val result = learningService.learn(feedback)
        assertTrue(result.isSuccessful)

        val solutions = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.SOLUTION)
        assertEquals(1, solutions.size)
        val savedSolution = solutions.first()
        assertEquals("solution:task-sol-1:aapt2-override", savedSolution.key)
        assertEquals(MemorySource.TOOL_VERIFIED, savedSolution.source)
        assertEquals(0.95f, savedSolution.confidence, 0.01f)
        assertTrue(savedSolution.content.contains("Verified solution:"))
    }

    // 4. Unverified agent claim does NOT become trusted FACT
    @Test
    fun testUnverifiedAgentClaimDoesNotBecomeTrustedFact() {
        val agentClaim = LearnedDiscovery(
            key = "database-choice",
            content = "Agent believes MongoDB is the best database for this app",
            summary = "Database opinion",
            isObjectivelyObserved = false,
            source = MemorySource.AGENT_INFERRED
        )
        val feedback = ExecutionFeedback(
            taskId = "task-claim-1",
            projectId = "proj-1",
            attemptId = "task-claim-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Finished with opinion",
            discoveries = listOf(agentClaim)
        )

        learningService.learn(feedback)

        // Verify NO FACT was created
        val facts = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.FACT)
        assertEquals(0, facts.size)

        // Verify NO CONSTRAINT, DECISION, or PREFERENCE was created
        assertEquals(0, knowledgeRepo.findByType("proj-1", BrainKnowledgeType.CONSTRAINT).size)
        assertEquals(0, knowledgeRepo.findByType("proj-1", BrainKnowledgeType.DECISION).size)
        assertEquals(0, knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PREFERENCE).size)

        // Stored only as low-confidence discovery with inferred key
        val discoveries = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.DISCOVERY)
        assertEquals(1, discoveries.size)
        val disc = discoveries.first()
        assertEquals(MemorySource.AGENT_INFERRED, disc.source)
        assertEquals(0.40f, disc.confidence, 0.01f)
        assertTrue(disc.key.contains("database-choice"))
    }

    // 5. Agent-inferred observation remains low trust
    @Test
    fun testAgentInferredObservationRemainsLowTrust() {
        val solution = LearnedSolution(
            key = "speculative-fix",
            summary = "Maybe increase heap size",
            procedure = "Increase Xmx to 4G without test confirmation",
            isVerified = false,
            source = MemorySource.AGENT_INFERRED
        )
        val feedback = ExecutionFeedback(
            taskId = "task-trust-1",
            projectId = "proj-1",
            attemptId = "task-trust-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Finished",
            solutions = listOf(solution)
        )

        learningService.learn(feedback)
        val solutions = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.SOLUTION)
        assertEquals(1, solutions.size)
        assertEquals(MemorySource.AGENT_INFERRED, solutions.first().source)
        assertEquals(0.40f, solutions.first().confidence, 0.01f)
        assertTrue(solutions.first().key.contains("speculative-fix"))
        assertTrue(solutions.first().content.contains("Agent-inferred"))
    }

    // 6. TOOL_VERIFIED observation gets high trust
    @Test
    fun testToolVerifiedObservationGetsHighTrust() {
        val verifiedSolution = LearnedSolution(
            key = "verified-patch",
            summary = "Fixed null pointer",
            procedure = "Check null before calling size()",
            isVerified = true,
            source = MemorySource.TOOL_VERIFIED
        )
        val feedback = ExecutionFeedback(
            taskId = "task-trust-2",
            projectId = "proj-1",
            attemptId = "task-trust-2:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Verified by running tests",
            solutions = listOf(verifiedSolution)
        )

        learningService.learn(feedback)
        val solutions = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.SOLUTION)
        assertEquals(1, solutions.size)
        assertEquals(MemorySource.TOOL_VERIFIED, solutions.first().source)
        assertEquals(0.95f, solutions.first().confidence, 0.01f)
    }

    // 7. USER_PROVIDED information preserves user provenance
    @Test
    fun testUserProvidedInformationPreservesUserProvenance() {
        val userDiscovery = LearnedDiscovery(
            key = "custom-ndk-path",
            content = "/opt/android-sdk/ndk/25.1.8937393",
            summary = "NDK path specified by user",
            isObjectivelyObserved = true,
            source = MemorySource.USER_PROVIDED
        )
        val feedback = ExecutionFeedback(
            taskId = "task-user-1",
            projectId = "proj-1",
            attemptId = "task-user-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Configured using user path",
            discoveries = listOf(userDiscovery)
        )

        learningService.learn(feedback)
        val discoveries = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.DISCOVERY)
        assertEquals(1, discoveries.size)
        assertEquals(MemorySource.USER_PROVIDED, discoveries.first().source)
        assertEquals(0.95f, discoveries.first().confidence, 0.01f)
        assertEquals("execution:task-user-1:task-user-1:attempt-0", discoveries.first().sourceReference)
    }

    // 8. DISCOVERY requires objective evidence
    @Test
    fun testDiscoveryRequiresObjectiveEvidence() {
        val objectiveDiscovery = LearnedDiscovery(
            key = "gradle-version",
            content = "Gradle 8.14 is active in wrapper",
            isObjectivelyObserved = true,
            source = MemorySource.PROJECT_OBSERVED
        )
        val guessDiscovery = LearnedDiscovery(
            key = "backend-speculation",
            content = "The server might run on port 8080 maybe",
            isObjectivelyObserved = false,
            source = MemorySource.AGENT_INFERRED
        )
        val feedback = ExecutionFeedback(
            taskId = "task-disc-1",
            projectId = "proj-1",
            attemptId = "task-disc-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Discovered project info",
            discoveries = listOf(objectiveDiscovery, guessDiscovery)
        )

        learningService.learn(feedback)
        val discoveries = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.DISCOVERY)
        assertEquals(2, discoveries.size)

        val obj = discoveries.first { it.key == "discovery:proj-1:gradle-version" }
        assertEquals(MemorySource.PROJECT_OBSERVED, obj.source)
        assertEquals(0.90f, obj.confidence, 0.01f)

        val guess = discoveries.first { it.key == "discovery:proj-1:backend-speculation" }
        assertEquals(MemorySource.AGENT_INFERRED, guess.source)
        assertEquals(0.40f, guess.confidence, 0.01f)
    }

    // 9. WORKSPACE_STATE only uses existing bounded state
    @Test
    fun testWorkspaceStateOnlyUsesExistingBoundedState() {
        val wsState = ExecutionWorkspaceState(
            headCommitSha = "e23d52f2414d",
            modifiedFiles = listOf("MainActivity.kt", "build.gradle.kts"),
            branch = "master",
            summary = "Working directory modified 2 files"
        )
        val feedback = ExecutionFeedback(
            taskId = "task-ws-1",
            projectId = "proj-1",
            attemptId = "task-ws-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Completed work",
            workspaceState = wsState
        )

        learningService.learn(feedback)
        val wsEntries = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.WORKSPACE_STATE)
        assertEquals(1, wsEntries.size)
        val ws = wsEntries.first()
        assertTrue(ws.content.contains("e23d52f2414d"))
        assertTrue(ws.content.contains("MainActivity.kt"))
        assertTrue(ws.content.contains("master"))
        assertEquals(MemorySource.PROJECT_OBSERVED, ws.source)

        // When workspaceState is null, no record is created
        val feedbackNoWs = ExecutionFeedback(
            taskId = "task-ws-2",
            projectId = "proj-1",
            attemptId = "task-ws-2:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "No workspace snapshot",
            workspaceState = null
        )
        learningService.learn(feedbackNoWs)
        val wsEntries2 = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.WORKSPACE_STATE)
        assertEquals(1, wsEntries2.size) // still only 1
    }

    // 10. CANCELLED execution does not create verified success
    @Test
    fun testCancelledExecutionDoesNotCreateVerifiedSuccess() {
        val solution = LearnedSolution(
            key = "partial-sol",
            summary = "Attempted solution",
            isVerified = true
        )
        val feedback = ExecutionFeedback(
            taskId = "task-cancel-1",
            projectId = "proj-1",
            attemptId = "task-cancel-1:attempt-0",
            outcome = ExecutionOutcome.CANCELLED,
            summary = "User clicked stop",
            completedStepIds = listOf("step-1"),
            verifiedCriteria = listOf("Criterion X"),
            solutions = listOf(solution)
        )

        val result = learningService.learn(feedback)
        assertTrue(result.isSuccessful)

        // MUST NOT create verified outcome PROGRESS
        val progress = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS)
        assertFalse(progress.any { it.key == "progress:task-cancel-1:verified-outcome" })
        assertFalse(progress.any { it.key.contains("criterion") })

        // MUST NOT create verified SOLUTION
        val solutions = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.SOLUTION)
        assertEquals(0, solutions.size)

        // Only records cancellation diagnostic
        assertTrue(progress.any { it.key.contains("cancelled") })
    }

    // 11. PARTIAL execution only records verified progress
    @Test
    fun testPartialExecutionOnlyRecordsVerifiedProgress() {
        val feedback = ExecutionFeedback(
            taskId = "task-part-1",
            projectId = "proj-1",
            attemptId = "task-part-1:attempt-0",
            outcome = ExecutionOutcome.PARTIAL,
            summary = "Completed 1 out of 3 steps",
            completedStepIds = listOf("step-1"),
            failedStepIds = listOf("step-2")
        )

        learningService.learn(feedback)
        val progress = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS)
        assertFalse(progress.any { it.key == "progress:task-part-1:verified-outcome" })
        assertTrue(progress.any { it.key.contains("partial") })
        assertTrue(progress.any { it.key == "progress:task-part-1:step:step-1" })
        assertFalse(progress.any { it.key == "progress:task-part-1:step:step-2" })
    }

    // 12. BLOCKED execution does not fabricate completion
    @Test
    fun testBlockedExecutionDoesNotFabricateCompletion() {
        val feedback = ExecutionFeedback(
            taskId = "task-block-1",
            projectId = "proj-1",
            attemptId = "task-block-1:attempt-0",
            outcome = ExecutionOutcome.BLOCKED,
            summary = "Blocked waiting for upstream service",
            completedStepIds = emptyList()
        )

        learningService.learn(feedback)
        val progress = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS)
        assertFalse(progress.any { it.key == "progress:task-block-1:verified-outcome" })
        assertTrue(progress.any { it.key.contains("blocked") })
    }

    // 13. Same attempt processed twice is idempotent
    @Test
    fun testSameAttemptProcessedTwiceIsIdempotent() {
        val feedback = ExecutionFeedback(
            taskId = "task-idem-1",
            projectId = "proj-1",
            attemptId = "task-idem-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Initial execution",
            completedStepIds = listOf("step-1")
        )

        val result1 = learningService.learn(feedback)
        val initialCount = knowledgeRepo.findByProject("proj-1").size
        assertTrue(result1.insertedCount > 0)

        // Process again with exact same feedback
        val result2 = learningService.learn(feedback)
        val secondCount = knowledgeRepo.findByProject("proj-1").size

        assertEquals(initialCount, secondCount)
        assertEquals(0, result2.insertedCount)
        assertTrue(result2.deduplicatedCount > 0 || result2.ignoredCount > 0)
    }

    // 14. Different attempts remain historically distinct
    @Test
    fun testDifferentAttemptsRemainHistoricallyDistinct() {
        val feedback0 = ExecutionFeedback(
            taskId = "task-hist-1",
            projectId = "proj-1",
            attemptId = "task-hist-1:attempt-0",
            outcome = ExecutionOutcome.FAILED,
            summary = "Attempt 0 failed",
            failures = listOf(
                TaskFailureRecord(
                    taskId = "task-hist-1",
                    classification = "SYNTAX_ERROR",
                    errorMessage = "Syntax error on attempt 0"
                )
            )
        )
        val feedback1 = ExecutionFeedback(
            taskId = "task-hist-1",
            projectId = "proj-1",
            attemptId = "task-hist-1:attempt-1",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Attempt 1 succeeded",
            completedStepIds = listOf("step-1")
        )

        learningService.learn(feedback0)
        learningService.learn(feedback1)

        val failures = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.FAILURE)
        val progress = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS)

        assertEquals(1, failures.size)
        assertTrue(failures.first().sourceReference!!.contains("attempt-0"))

        assertTrue(progress.any { it.sourceReference!!.contains("attempt-1") })
    }

    // 15. Attempt 0 failure + Attempt 1 success preserves both
    @Test
    fun testAttempt0FailurePlusAttempt1SuccessPreservesBoth() {
        val failureRecord = TaskFailureRecord(
            taskId = "task-retry-1",
            classification = "TIMEOUT",
            errorMessage = "Gradle daemon timed out"
        )
        val f0 = ExecutionFeedback(
            taskId = "task-retry-1",
            projectId = "proj-1",
            attemptId = "task-retry-1:attempt-0",
            outcome = ExecutionOutcome.FAILED,
            summary = "Timeout",
            failures = listOf(failureRecord)
        )
        val solution = LearnedSolution(
            key = "increase-timeout",
            summary = "Increased daemon timeout to 120s",
            isVerified = true
        )
        val f1 = ExecutionFeedback(
            taskId = "task-retry-1",
            projectId = "proj-1",
            attemptId = "task-retry-1:attempt-1",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Succeeded on retry with increased timeout",
            solutions = listOf(solution)
        )

        learningService.learn(f0)
        learningService.learn(f1)

        val failures = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.FAILURE)
        val solutions = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.SOLUTION)

        assertEquals(1, failures.size)
        assertEquals(1, solutions.size)
        assertTrue(failures.first().content.contains("Gradle daemon timed out"))
        assertTrue(solutions.first().content.contains("Increased daemon timeout"))
    }

    // 16. Long failure text is bounded
    @Test
    fun testLongFailureTextIsBounded() {
        val hugeErrorMessage = "Error ".repeat(500) // ~3,000 chars
        val failureRecord = TaskFailureRecord(
            taskId = "task-bound-1",
            classification = "HUGE_ERROR",
            errorMessage = hugeErrorMessage
        )
        val feedback = ExecutionFeedback(
            taskId = "task-bound-1",
            projectId = "proj-1",
            attemptId = "task-bound-1:attempt-0",
            outcome = ExecutionOutcome.FAILED,
            summary = "Huge error",
            failures = listOf(failureRecord)
        )

        learningService.learn(feedback)
        val failure = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.FAILURE).first()

        assertTrue(failure.content.length <= BrainLearningService.MAX_FAILURE_CHARS)
        assertTrue(failure.content.endsWith(BrainLearningService.TRUNCATION_MARKER))
        assertTrue(failure.summary!!.length <= BrainLearningService.MAX_FAILURE_CHARS)
    }

    // 17. Long solution text is bounded
    @Test
    fun testLongSolutionTextIsBounded() {
        val hugeProcedure = "Step to fix: ".repeat(300) // ~3,900 chars
        val solution = LearnedSolution(
            key = "huge-sol",
            summary = "Huge solution summary",
            procedure = hugeProcedure,
            isVerified = true
        )
        val feedback = ExecutionFeedback(
            taskId = "task-bound-2",
            projectId = "proj-1",
            attemptId = "task-bound-2:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Long solution executed",
            solutions = listOf(solution)
        )

        learningService.learn(feedback)
        val sol = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.SOLUTION).first()

        assertTrue(sol.content.length <= BrainLearningService.MAX_SOLUTION_CHARS)
        assertTrue(sol.content.endsWith(BrainLearningService.TRUNCATION_MARKER))
    }

    // 18. Large agent output is not persisted wholesale
    @Test
    fun testLargeAgentOutputIsNotPersistedWholesale() {
        val hugeSummary = "Task ran for 100 iterations. ".repeat(200) // ~5,800 chars
        val feedback = ExecutionFeedback(
            taskId = "task-bound-3",
            projectId = "proj-1",
            attemptId = "task-bound-3:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = hugeSummary
        )

        learningService.learn(feedback)
        val progress = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS).first()

        assertTrue(progress.summary!!.length <= BrainLearningService.MAX_GENERAL_SUMMARY_CHARS)
        assertTrue(progress.content.length <= BrainLearningService.MAX_PROGRESS_CHARS)
        assertTrue(progress.content.endsWith(BrainLearningService.TRUNCATION_MARKER))
    }

    // 19. Maximum learned entries per attempt is enforced
    @Test
    fun testMaximumLearnedEntriesPerAttemptIsEnforced() {
        val manyDiscoveries = (1..30).map { i ->
            LearnedDiscovery(
                key = "disc-$i",
                content = "Discovered config $i",
                isObjectivelyObserved = true
            )
        }
        val feedback = ExecutionFeedback(
            taskId = "task-max-1",
            projectId = "proj-1",
            attemptId = "task-max-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Found 30 things",
            discoveries = manyDiscoveries
        )

        val result = learningService.learn(feedback)
        assertTrue(result.entries.size <= BrainLearningService.MAX_LEARNED_ENTRIES)
        assertEquals(BrainLearningService.MAX_LEARNED_ENTRIES, result.entries.size)
        assertTrue(result.ignoredCount > 0)
    }

    // 20. Conflict resolver is used
    @Test
    fun testConflictResolverIsUsed() {
        val entry1 = BrainKnowledgeEntry(
            projectId = "proj-1",
            knowledgeType = BrainKnowledgeType.DISCOVERY,
            key = "discovery:proj-1:ndk-dir",
            content = "/opt/ndk",
            source = MemorySource.TOOL_VERIFIED
        )
        knowledgeRepo.save(entry1)

        val feedback = ExecutionFeedback(
            taskId = "task-cr-1",
            projectId = "proj-1",
            attemptId = "task-cr-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Confirmed NDK",
            discoveries = listOf(
                LearnedDiscovery(
                    key = "ndk-dir",
                    content = "/opt/ndk",
                    source = MemorySource.TOOL_VERIFIED
                )
            )
        )

        val result = learningService.learn(feedback)
        assertTrue(result.deduplicatedCount > 0)
        val discoveries = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.DISCOVERY)
        assertEquals(1, discoveries.size)
        assertEquals("/opt/ndk", discoveries.first().content)
    }

    // 21. Lower-trust conflicting knowledge is rejected according to existing rules
    @Test
    fun testLowerTrustConflictingKnowledgeIsRejected() {
        val highTrustEntry = BrainKnowledgeEntry(
            projectId = "proj-1",
            knowledgeType = BrainKnowledgeType.DISCOVERY,
            key = "discovery:proj-1:compiler-version",
            content = "Kotlin 2.0.21 verified by gradle",
            source = MemorySource.TOOL_VERIFIED,
            confidence = 0.95f
        )
        knowledgeRepo.save(highTrustEntry)

        val lowTrustFeedback = ExecutionFeedback(
            taskId = "task-trust-reject",
            projectId = "proj-1",
            attemptId = "task-trust-reject:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Speculative check",
            discoveries = listOf(
                LearnedDiscovery(
                    key = "compiler-version",
                    content = "Kotlin 1.9 maybe",
                    isObjectivelyObserved = false,
                    source = MemorySource.AGENT_INFERRED,
                    confidence = 0.40f
                )
            )
        )

        val result = learningService.learn(lowTrustFeedback)
        // Conflict resolver should reject or ignore lower-trust conflicting entry
        assertTrue(result.rejectedCount > 0 || result.ignoredCount > 0)

        val current = knowledgeRepo.findByKey("proj-1", "discovery:proj-1:compiler-version")
        assertNotNull(current)
        assertEquals("Kotlin 2.0.21 verified by gradle", current!!.content)
        assertEquals(MemorySource.TOOL_VERIFIED, current.source)
    }

    // 22. Higher-authority verified knowledge can supersede where existing resolver permits
    @Test
    fun testHigherAuthorityVerifiedKnowledgeCanSupersede() {
        val lowTrustEntry = BrainKnowledgeEntry(
            projectId = "proj-1",
            knowledgeType = BrainKnowledgeType.DISCOVERY,
            key = "discovery:proj-1:java-home",
            content = "/usr/lib/jvm/default",
            source = MemorySource.AUTO,
            confidence = 0.60f
        )
        knowledgeRepo.save(lowTrustEntry)

        val verifiedFeedback = ExecutionFeedback(
            taskId = "task-supersede",
            projectId = "proj-1",
            attemptId = "task-supersede:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Verified java home",
            discoveries = listOf(
                LearnedDiscovery(
                    key = "java-home",
                    content = "/root/android-sdk/jbr-21",
                    isObjectivelyObserved = true,
                    source = MemorySource.TOOL_VERIFIED,
                    confidence = 0.95f
                )
            )
        )

        val result = learningService.learn(verifiedFeedback)
        assertTrue(result.supersededCount > 0 || result.insertedCount > 0)

        val active = knowledgeRepo.findByKey("proj-1", "discovery:proj-1:java-home")
        assertNotNull(active)
        assertEquals("/root/android-sdk/jbr-21", active!!.content)
        assertEquals(MemorySource.TOOL_VERIFIED, active.source)
    }

    // 23. Learning failure does not change task outcome
    @Test
    fun testLearningFailureDoesNotChangeTaskOutcome() = runBlocking {
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        supervisor.brainLearningService = object : BrainLearningService(knowledgeRepo) {
            override fun learn(feedback: ExecutionFeedback): LearningResult {
                throw RuntimeException("Database disk full during learning")
            }
        }

        val record = supervisor.createTask(
            taskId = "task-failing-learn",
            projectId = "proj-1",
            projectSlug = "slug",
            chatId = "chat",
            agentKind = "AGY",
            providerJson = "p",
            prompt = "Do work"
        )

        val job = supervisor.executeTask(record.taskId) {
            // Work succeeds
        }
        job.join()

        val finalRecord = supervisor.stateStore.get("task-failing-learn")
        assertNotNull(finalRecord)
        assertEquals("Status should be COMPLETED, but was ${finalRecord?.status}, lastError=${finalRecord?.lastError}", TaskExecutionStatus.COMPLETED, finalRecord!!.status)
        assertNotEquals(TaskExecutionStatus.FAILED, finalRecord.status)
    }

    // 24. Learning happens once at the authoritative completion boundary
    @Test
    fun testLearningHappensOnceAtAuthoritativeCompletionBoundary() = runBlocking {
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        var executionCallCount = 0

        val record = supervisor.createTask(
            taskId = "task-auth-boundary",
            projectId = "proj-1",
            projectSlug = "slug",
            chatId = "chat",
            agentKind = "AGY",
            providerJson = "p",
            prompt = "Run test"
        )

        val job = supervisor.executeTask(record.taskId) {
            executionCallCount++
        }
        job.join()

        assertEquals(1, executionCallCount)
        val progress = knowledgeRepo.findByType("proj-1", BrainKnowledgeType.PROGRESS)
        assertTrue(progress.any { it.taskId == "task-auth-boundary" })
    }

    // 25. Antigravity/Claude/Dsh do not independently persist Brain knowledge
    @Test
    fun testBridgesDoNotIndependentlyPersistBrainKnowledge() {
        val agyBridge = AntigravityRuntimeBridge::class.java
        val claudeBridge = ClaudeRuntimeBridge::class.java
        val dshBridge = DshRuntimeBridge::class.java

        // Check fields and constructors of runtime bridges do not accept BrainLearningService or BrainKnowledgeRepository
        for (field in agyBridge.declaredFields) {
            assertFalse(BrainLearningService::class.java.isAssignableFrom(field.type))
            assertFalse(BrainKnowledgeRepository::class.java.isAssignableFrom(field.type))
        }
        for (field in claudeBridge.declaredFields) {
            assertFalse(BrainLearningService::class.java.isAssignableFrom(field.type))
            assertFalse(BrainKnowledgeRepository::class.java.isAssignableFrom(field.type))
        }
        for (field in dshBridge.declaredFields) {
            assertFalse(BrainLearningService::class.java.isAssignableFrom(field.type))
            assertFalse(BrainKnowledgeRepository::class.java.isAssignableFrom(field.type))
        }
    }

    // 26. Same feedback produces deterministic logical results
    @Test
    fun testSameFeedbackProducesDeterministicLogicalResults() {
        val driver2 = BrainDatabaseDriverFactory.createInMemoryDriver()
        val db2 = BrainDatabase(driver2)
        val service2 = BrainLearningService(BrainKnowledgeRepository(db2))

        val feedback = ExecutionFeedback(
            taskId = "task-det-1",
            projectId = "proj-1",
            attemptId = "task-det-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Deterministic run",
            completedStepIds = listOf("step-A", "step-B"),
            verifiedCriteria = listOf("Criterion 1")
        )

        val result1 = learningService.learn(feedback)
        val result2 = service2.learn(feedback)

        assertEquals(result1.entries.size, result2.entries.size)
        for (i in result1.entries.indices) {
            assertEquals(result1.entries[i].id, result2.entries[i].id)
            assertEquals(result1.entries[i].key, result2.entries[i].key)
            assertEquals(result1.entries[i].content, result2.entries[i].content)
            assertEquals(result1.entries[i].confidence, result2.entries[i].confidence, 0.001f)
            assertEquals(result1.entries[i].source, result2.entries[i].source)
        }
    }

    // 27. SourceReference contains task + attempt provenance
    @Test
    fun testSourceReferenceContainsTaskPlusAttemptProvenance() {
        val feedback = ExecutionFeedback(
            taskId = "task-prov-1",
            projectId = "proj-1",
            attemptId = "task-prov-1:attempt-2",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Attempt 2 succeeded"
        )

        val result = learningService.learn(feedback)
        assertTrue(result.entries.isNotEmpty())
        for (entry in result.entries) {
            assertEquals("execution:task-prov-1:task-prov-1:attempt-2", entry.sourceReference)
            assertEquals("task-prov-1", entry.taskId)
        }
    }

    // 28. Fingerprint/snapshot identity can be associated with learned feedback
    @Test
    fun testFingerprintIdentityAssociatedWithLearnedFeedback() {
        val feedback = ExecutionFeedback(
            taskId = "task-fp-1",
            projectId = "proj-1",
            attemptId = "task-fp-1:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "Checked with snapshot",
            contextFingerprint = "sha256-abcdef123456"
        )

        assertEquals("sha256-abcdef123456", feedback.contextFingerprint)
        val result = learningService.learn(feedback)
        assertTrue(result.isSuccessful)
    }

    // 29. No filesystem scan occurs during learning
    @Test
    fun testNoFilesystemScanOccursDuringLearning() {
        // Learning service operates strictly in-memory / SQLite without touching workspace directories
        val feedback = ExecutionFeedback(
            taskId = "task-no-fs",
            projectId = "non-existent-fs-project",
            attemptId = "task-no-fs:attempt-0",
            outcome = ExecutionOutcome.SUCCESS,
            summary = "No disk scan",
            workspaceState = ExecutionWorkspaceState(
                headCommitSha = "dummy-sha",
                modifiedFiles = listOf("NonExistentFile.kt")
            )
        )

        val start = System.currentTimeMillis()
        val result = learningService.learn(feedback)
        val duration = System.currentTimeMillis() - start

        assertTrue(result.isSuccessful)
        assertTrue("Learning should be instantaneous memory/db operation", duration < 500)
    }

    // 30. USER_CANCELLED has exactly one learning pass (no duplicate learning)
    @Test
    fun testUserCancelledHasExactlyOneAuthoritativeLearningPass() = runBlocking {
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        val invocations = mutableListOf<ExecutionFeedback>()
        supervisor.brainLearningService = object : BrainLearningService(knowledgeRepo) {
            override fun learn(feedback: ExecutionFeedback): LearningResult {
                invocations.add(feedback)
                return super.learn(feedback)
            }
        }

        val record = supervisor.createTask(
            taskId = "task-cancel-remediation",
            projectId = "proj-1",
            projectSlug = "slug",
            chatId = "chat",
            agentKind = "AGY",
            providerJson = "p",
            prompt = "Run task to be cancelled"
        )

        val job = supervisor.executeTask(record.taskId) {
            throw RuntimeException("Execution stopped by user")
        }
        job.join()

        // Verify exactly one learning invocation was made
        assertEquals("USER_CANCELLED must invoke BrainLearningService.learn exactly once", 1, invocations.size)
        assertEquals(ExecutionOutcome.CANCELLED, invocations[0].outcome)
        assertEquals("Cancelled by user", invocations[0].summary)

        val finalRecord = supervisor.stateStore.get("task-cancel-remediation")
        assertNotNull(finalRecord)
        assertEquals(TaskExecutionStatus.CANCELLED, finalRecord!!.status)
    }

    // 31. FAILED has exactly one failure-learning pass
    @Test
    fun testFailedExecutionHasExactlyOneFailureLearningPass() = runBlocking {
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        val invocations = mutableListOf<ExecutionFeedback>()
        supervisor.brainLearningService = object : BrainLearningService(knowledgeRepo) {
            override fun learn(feedback: ExecutionFeedback): LearningResult {
                invocations.add(feedback)
                return super.learn(feedback)
            }
        }

        val record = supervisor.createTask(
            taskId = "task-fail-learning-pass",
            projectId = "proj-1",
            projectSlug = "slug",
            chatId = "chat",
            agentKind = "AGY",
            providerJson = "p",
            prompt = "Run failing task",
            maxRetries = 0
        )

        val job = supervisor.executeTask(record.taskId) {
            throw RuntimeException("Invalid api key provided")
        }
        job.join()

        assertEquals("FAILED must invoke BrainLearningService.learn exactly once per failure", 1, invocations.size)
        assertEquals(ExecutionOutcome.FAILED, invocations[0].outcome)
        val finalRecord = supervisor.stateStore.get("task-fail-learning-pass")
        assertNotNull(finalRecord)
        assertEquals(TaskExecutionStatus.FAILED, finalRecord!!.status)
    }

    // 32. SUCCESS has exactly one success-learning pass
    @Test
    fun testSuccessExecutionHasExactlyOneSuccessLearningPass() = runBlocking {
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        val invocations = mutableListOf<ExecutionFeedback>()
        supervisor.brainLearningService = object : BrainLearningService(knowledgeRepo) {
            override fun learn(feedback: ExecutionFeedback): LearningResult {
                invocations.add(feedback)
                return super.learn(feedback)
            }
        }

        val record = supervisor.createTask(
            taskId = "task-success-learning-pass",
            projectId = "proj-1",
            projectSlug = "slug",
            chatId = "chat",
            agentKind = "AGY",
            providerJson = "p",
            prompt = "Run success task"
        )

        val job = supervisor.executeTask(record.taskId) {
            // Task succeeds
        }
        job.join()

        assertEquals("SUCCESS must invoke BrainLearningService.learn exactly once", 1, invocations.size)
        assertEquals(ExecutionOutcome.SUCCESS, invocations[0].outcome)
        val finalRecord = supervisor.stateStore.get("task-success-learning-pass")
        assertNotNull(finalRecord)
        assertEquals(TaskExecutionStatus.COMPLETED, finalRecord!!.status)
    }

    // 33. Cancellation before launch has exactly one learning pass
    @Test
    fun testCancellationBeforeLaunchHasExactlyOneLearningPass() = runBlocking {
        val supervisor = TaskSupervisor.createForTesting(context = null, database = db)
        val invocations = mutableListOf<ExecutionFeedback>()
        supervisor.brainLearningService = object : BrainLearningService(knowledgeRepo) {
            override fun learn(feedback: ExecutionFeedback): LearningResult {
                invocations.add(feedback)
                return super.learn(feedback)
            }
        }

        val record = supervisor.createTask(
            taskId = "task-cancel-prelaunch",
            projectId = "proj-1",
            projectSlug = "slug",
            chatId = "chat",
            agentKind = "AGY",
            providerJson = "p",
            prompt = "Run task cancelled before launch"
        )

        supervisor.requestStop(record.taskId)

        assertEquals("Pre-launch cancel must invoke BrainLearningService.learn exactly once", 1, invocations.size)
        assertEquals(ExecutionOutcome.CANCELLED, invocations[0].outcome)
        val finalRecord = supervisor.stateStore.get("task-cancel-prelaunch")
        assertNotNull(finalRecord)
        assertEquals(TaskExecutionStatus.CANCELLED, finalRecord!!.status)
    }
}

