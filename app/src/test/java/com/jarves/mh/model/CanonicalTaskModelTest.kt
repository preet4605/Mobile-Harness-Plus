package com.jarves.mh.model

import com.jarves.mh.data.MemoryEntry
import com.jarves.mh.data.MemoryScope
import com.jarves.mh.data.MemorySource
import com.jarves.mh.data.MemoryStatus
import com.jarves.mh.data.MemoryType
import com.jarves.mh.model.brain.BrainKnowledgeEntry
import com.jarves.mh.model.brain.BrainKnowledgeType
import com.jarves.mh.model.brain.CanonicalTask
import com.jarves.mh.model.brain.ExecutionPlan
import com.jarves.mh.model.brain.ExecutionStep
import com.jarves.mh.model.brain.RecoveryPlan
import com.jarves.mh.model.brain.RecoveryStrategy
import com.jarves.mh.model.brain.StepStatus
import com.jarves.mh.model.brain.TaskFailureRecord
import com.jarves.mh.model.brain.TaskOutcome
import com.jarves.mh.model.brain.toBrainKnowledgeEntry
import com.jarves.mh.model.brain.toMemoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CanonicalTaskModelTest {

    @Test
    fun testExecutionPlanStepProgressionAndProgressFraction() {
        val step1 = ExecutionStep(
            stepOrder = 0,
            title = "Analyze requirements",
            description = "Inspect workspace and determine constraints",
            status = StepStatus.COMPLETED
        )
        val step2 = ExecutionStep(
            stepOrder = 1,
            title = "Implement domain models",
            description = "Define CanonicalTask and BrainKnowledgeType",
            status = StepStatus.RUNNING
        )
        val step3 = ExecutionStep(
            stepOrder = 2,
            title = "Run tests",
            description = "Execute unit test suite",
            status = StepStatus.PENDING
        )

        val plan = ExecutionPlan(
            title = "Phase 2 Step 1 Plan",
            steps = listOf(step1, step2, step3),
            currentStepIndex = 1
        )

        assertEquals("Phase 2 Step 1 Plan", plan.title)
        assertEquals(3, plan.steps.size)
        assertEquals(step2, plan.currentStep)
        assertFalse(plan.isFinished)
        assertEquals(1f / 3f, plan.progressFraction, 0.001f)

        // Advance to step 3 completed
        val finishedPlan = plan.copy(
            steps = listOf(
                step1.copy(status = StepStatus.COMPLETED),
                step2.copy(status = StepStatus.COMPLETED),
                step3.copy(status = StepStatus.SKIPPED)
            ),
            currentStepIndex = 2
        )

        assertTrue(finishedPlan.isFinished)
        assertEquals(2f / 3f, finishedPlan.progressFraction, 0.001f)

        val allCompletedPlan = plan.copy(
            steps = listOf(
                step1.copy(status = StepStatus.COMPLETED),
                step2.copy(status = StepStatus.COMPLETED),
                step3.copy(status = StepStatus.COMPLETED)
            )
        )
        assertTrue(allCompletedPlan.isFinished)
        assertEquals(1.0f, allCompletedPlan.progressFraction, 0.001f)
    }

    @Test
    fun testExecutionPlanEmptyStepsBoundary() {
        val emptyPlan = ExecutionPlan(title = "Empty Plan")
        assertNull(emptyPlan.currentStep)
        assertFalse(emptyPlan.isFinished)
        assertEquals(0f, emptyPlan.progressFraction, 0.001f)
    }

    @Test
    fun testBrainKnowledgeTaxonomyEnums() {
        val expectedTypes = listOf(
            BrainKnowledgeType.FACT,
            BrainKnowledgeType.DECISION,
            BrainKnowledgeType.PREFERENCE,
            BrainKnowledgeType.CONSTRAINT,
            BrainKnowledgeType.TASK,
            BrainKnowledgeType.PROGRESS,
            BrainKnowledgeType.DISCOVERY,
            BrainKnowledgeType.FAILURE,
            BrainKnowledgeType.SOLUTION,
            BrainKnowledgeType.WORKSPACE_STATE
        )

        assertEquals(10, BrainKnowledgeType.values().size)
        expectedTypes.forEach { type ->
            assertNotNull(BrainKnowledgeType.valueOf(type.name))
        }
    }

    @Test
    fun testBrainKnowledgeEntryAndMemoryEntryBidirectionalConversion() {
        val knowledgeEntry = BrainKnowledgeEntry(
            projectId = "proj-123",
            sessionId = "sess-abc",
            taskId = "task-789",
            scope = MemoryScope.PROJECT,
            knowledgeType = BrainKnowledgeType.DECISION,
            key = "architecture.db",
            content = "Use SQLite over Room to avoid KSP overhead",
            summary = "Direct SQLite driver chosen",
            importance = 0.9f,
            confidence = 1.0f,
            source = MemorySource.USER,
            tags = listOf("architecture", "database")
        )

        val memoryEntry = knowledgeEntry.toMemoryEntry()
        assertEquals(knowledgeEntry.id, memoryEntry.id)
        assertEquals(knowledgeEntry.projectId, memoryEntry.projectId)
        assertEquals(knowledgeEntry.sessionId, memoryEntry.sessionId)
        assertEquals(knowledgeEntry.taskId, memoryEntry.taskId)
        assertEquals(MemoryType.DECISION, memoryEntry.type)
        assertEquals(BrainKnowledgeType.DECISION, memoryEntry.knowledgeType)
        assertEquals(knowledgeEntry.key, memoryEntry.key)
        assertEquals(knowledgeEntry.content, memoryEntry.value)
        assertEquals(knowledgeEntry.summary, memoryEntry.summary)
        assertEquals(knowledgeEntry.importance, memoryEntry.importance, 0.001f)
        assertEquals(knowledgeEntry.confidence, memoryEntry.confidence, 0.001f)
        assertEquals(knowledgeEntry.tags, memoryEntry.tags)

        val convertedBack = memoryEntry.toBrainKnowledgeEntry()
        assertEquals(knowledgeEntry.id, convertedBack.id)
        assertEquals(knowledgeEntry.projectId, convertedBack.projectId)
        assertEquals(knowledgeEntry.taskId, convertedBack.taskId)
        assertEquals(knowledgeEntry.knowledgeType, convertedBack.knowledgeType)
        assertEquals(knowledgeEntry.key, convertedBack.key)
        assertEquals(knowledgeEntry.content, convertedBack.content)
    }

    @Test
    fun testRecoveryPlanAndStrategies() {
        val expectedStrategies = listOf(
            RecoveryStrategy.RETRY_STEP_DIRECT,
            RecoveryStrategy.REVERT_AND_RETRY_STEP,
            RecoveryStrategy.FORWARD_FIX_WITH_CONTEXT,
            RecoveryStrategy.SAFE_ABORT_AND_CLEANUP,
            RecoveryStrategy.MANUAL_USER_INTERVENTION
        )
        assertEquals(5, RecoveryStrategy.values().size)
        expectedStrategies.forEach { strategy ->
            assertNotNull(RecoveryStrategy.valueOf(strategy.name))
        }

        val failure = TaskFailureRecord(
            taskId = "task-1",
            stepId = "step-2",
            classification = "COMPILATION_ERROR",
            errorMessage = "Unresolved reference: BrainKnowledgeType",
            errorSnippet = "Line 42: val x = BrainKnowledgeType.FACT",
            mutatedFiles = listOf("app/src/main/java/com/jarves/mh/Foo.kt")
        )

        val recoveryPlan = RecoveryPlan(
            taskId = "task-1",
            failureRecordId = failure.failureId,
            strategy = RecoveryStrategy.FORWARD_FIX_WITH_CONTEXT,
            rationale = "Compilation error can be fixed forward by adding missing import",
            forwardFixInstructions = "Add import com.jarves.mh.model.brain.BrainKnowledgeType",
            targetStepIndex = 1
        )

        assertEquals("task-1", recoveryPlan.taskId)
        assertEquals(failure.failureId, recoveryPlan.failureRecordId)
        assertEquals(RecoveryStrategy.FORWARD_FIX_WITH_CONTEXT, recoveryPlan.strategy)
        assertEquals(1, recoveryPlan.targetStepIndex)
        assertFalse(recoveryPlan.approvedByUser)
    }

    @Test
    fun testCanonicalTaskCreation() {
        val task = CanonicalTask(
            projectId = "clever-kalam",
            projectSlug = "mobile-harness-plus",
            objective = "Implement Phase 2 Step 1 Canonical Task Model",
            constraints = listOf("Strict containment in /workspace/clever-kalam", "No scratch dir"),
            acceptanceCriteria = listOf("Pass all unit tests", "Preserve Phase 1 guarantees"),
            outcome = TaskOutcome(
                success = true,
                summary = "Step 1 complete",
                filesModified = listOf("BrainDomainModels.kt", "BrainDatabase.kt"),
                testsExecuted = true,
                testsPassed = true,
                durationSeconds = 42L
            )
        )

        assertEquals("clever-kalam", task.projectId)
        assertEquals("mobile-harness-plus", task.projectSlug)
        assertEquals(2, task.constraints.size)
        assertEquals(2, task.acceptanceCriteria.size)
        assertNotNull(task.outcome)
        assertTrue(task.outcome!!.success)
        assertEquals(2, task.outcome!!.filesModified.size)
    }
}
