package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentLifecycleTest {

    // -------------------------------------------------------------------------
    // 1. SubagentState & SubagentInfo Invariants
    // -------------------------------------------------------------------------

    @Test
    fun subagentState_isTerminalIdentifiesTerminalStatesCorrectly() {
        assertFalse(SubagentState.RUNNING.isTerminal)
        assertFalse(SubagentState.IDLE.isTerminal)
        assertFalse(SubagentState.WAITING_FOR_INPUT.isTerminal)
        assertFalse(SubagentState.WAITING_FOR_DEPENDENTS.isTerminal)

        assertTrue(SubagentState.DONE.isTerminal)
        assertTrue(SubagentState.ERRORED.isTerminal)
        assertTrue(SubagentState.TERMINATED.isTerminal)
    }

    @Test
    fun subagentInfo_matchesSupportsExactIdWildcardAndCaseInsensitiveRole() {
        val subagent = SubagentInfo(
            conversationId = "subagent-uuid-1234",
            role = "Codebase Researcher",
            typeName = "research",
            state = SubagentState.RUNNING,
        )

        // Exact conversationId match
        assertTrue(subagent.matches("subagent-uuid-1234"))
        // Wildcard match
        assertTrue(subagent.matches("*"))
        // Role match (case insensitive)
        assertTrue(subagent.matches("unrelated-id", "codebase researcher"))
        assertTrue(subagent.matches("unrelated-id", "CODEBASE RESEARCHER"))
        // Non-match
        assertFalse(subagent.matches("other-uuid", "Database Debugger"))
        assertFalse(subagent.matches("other-uuid"))
    }

    // -------------------------------------------------------------------------
    // 2. SubagentRegistry Reconciliation & Fuzzy Matching
    // -------------------------------------------------------------------------

    @Test
    fun subagentRegistry_updateAddsNewSubagent() {
        val initial = emptyList<SubagentInfo>()
        val incoming = SubagentInfo(
            conversationId = "sub-1",
            role = "Planner",
            typeName = "general",
            state = SubagentState.RUNNING,
            currentActivity = "Planning tasks",
        )

        val updated = SubagentRegistry.update(initial, incoming)
        assertEquals(1, updated.size)
        assertEquals("sub-1", updated[0].conversationId)
        assertEquals(SubagentState.RUNNING, updated[0].state)
    }

    @Test
    fun subagentRegistry_fuzzyMatchesRoleAndReconcilesUuid() {
        val initial = listOf(
            SubagentInfo(
                conversationId = "subagent-temp-1",
                role = "Codebase Researcher",
                typeName = "research",
                state = SubagentState.RUNNING,
                currentActivity = "Starting search...",
            ),
        )

        // Incoming event carries official UUID but same role
        val incoming = SubagentInfo(
            conversationId = "official-uuid-8899",
            role = "Codebase Researcher",
            typeName = "research",
            state = SubagentState.RUNNING,
            currentActivity = "Searching file system...",
        )

        val updated = SubagentRegistry.update(initial, incoming)
        // Should update in-place rather than creating duplicate
        assertEquals(1, updated.size)
        assertEquals("official-uuid-8899", updated[0].conversationId)
        assertEquals("Searching file system...", updated[0].currentActivity)
    }

    @Test
    fun subagentRegistry_invariantNoZombieResurrectionWhenTerminated() {
        val initial = listOf(
            SubagentInfo(
                conversationId = "sub-1",
                role = "Builder",
                typeName = "builder",
                state = SubagentState.TERMINATED,
                finishedAtMillis = 1000L,
            ),
        )

        // Incoming late event reports RUNNING
        val lateEvent = SubagentInfo(
            conversationId = "sub-1",
            role = "Builder",
            typeName = "builder",
            state = SubagentState.RUNNING,
            currentActivity = "Late build progress",
        )

        val updated = SubagentRegistry.update(initial, lateEvent)
        assertEquals(1, updated.size)
        // Invariant: Must remain TERMINATED
        assertEquals(SubagentState.TERMINATED, updated[0].state)
        assertEquals(1000L, updated[0].finishedAtMillis)
    }

    @Test
    fun subagentRegistry_invariantStoppingSessionForcesTerminated() {
        val initial = emptyList<SubagentInfo>()
        val incoming = SubagentInfo(
            conversationId = "sub-new",
            role = "Tester",
            typeName = "test",
            state = SubagentState.RUNNING,
        )

        val updated = SubagentRegistry.update(initial, incoming, isStopping = true, now = 2000L)
        assertEquals(1, updated.size)
        assertEquals(SubagentState.TERMINATED, updated[0].state)
        assertEquals(2000L, updated[0].finishedAtMillis)
    }

    @Test
    fun subagentRegistry_wildcardEventUpdatesAllSubagents() {
        val initial = listOf(
            SubagentInfo("s1", "Role 1", "type1", SubagentState.RUNNING),
            SubagentInfo("s2", "Role 2", "type2", SubagentState.WAITING_FOR_INPUT),
        )

        val wildcardTerminated = SubagentInfo("*", "*", "*", SubagentState.TERMINATED)
        val updated = SubagentRegistry.update(initial, wildcardTerminated, now = 3000L)
        assertEquals(2, updated.size)
        assertTrue(updated.all { it.state == SubagentState.TERMINATED })
        assertTrue(updated.all { it.finishedAtMillis == 3000L })
    }

    // -------------------------------------------------------------------------
    // 3. State Invariants: SessionCompleted, SessionFailed, Terminate & Clear
    // -------------------------------------------------------------------------

    @Test
    fun subagentRegistry_completeAllTransitionsAllNonTerminalSubagentsToDone() {
        val initial = listOf(
            SubagentInfo("s1", "R1", "t1", SubagentState.RUNNING),
            SubagentInfo("s2", "R2", "t2", SubagentState.WAITING_FOR_INPUT),
            SubagentInfo("s3", "R3", "t3", SubagentState.DONE, finishedAtMillis = 100L),
            SubagentInfo("s4", "R4", "t4", SubagentState.ERRORED, error = "Boom", finishedAtMillis = 200L),
            SubagentInfo("s5", "R5", "t5", SubagentState.TERMINATED, finishedAtMillis = 300L),
        )

        val completed = SubagentRegistry.completeAll(initial, finishedAt = 5000L)
        assertEquals(SubagentState.DONE, completed[0].state)
        assertEquals(5000L, completed[0].finishedAtMillis)

        assertEquals(SubagentState.DONE, completed[1].state)
        assertEquals(5000L, completed[1].finishedAtMillis)

        // Existing terminal states remain intact
        assertEquals(SubagentState.DONE, completed[2].state)
        assertEquals(100L, completed[2].finishedAtMillis)

        assertEquals(SubagentState.ERRORED, completed[3].state)
        assertEquals(200L, completed[3].finishedAtMillis)

        assertEquals(SubagentState.TERMINATED, completed[4].state)
        assertEquals(300L, completed[4].finishedAtMillis)
    }

    @Test
    fun subagentRegistry_failAllTransitionsAllNonTerminalSubagentsToErrored() {
        val initial = listOf(
            SubagentInfo("s1", "R1", "t1", SubagentState.RUNNING),
            SubagentInfo("s2", "R2", "t2", SubagentState.WAITING_FOR_DEPENDENTS),
            SubagentInfo("s3", "R3", "t3", SubagentState.DONE, finishedAtMillis = 100L),
        )

        val failed = SubagentRegistry.failAll(initial, error = "Session timeout", finishedAt = 6000L)
        assertEquals(SubagentState.ERRORED, failed[0].state)
        assertEquals("Session timeout", failed[0].error)
        assertEquals(6000L, failed[0].finishedAtMillis)

        assertEquals(SubagentState.ERRORED, failed[1].state)
        assertEquals("Session timeout", failed[1].error)

        assertEquals(SubagentState.DONE, failed[2].state)
        assertEquals(100L, failed[2].finishedAtMillis)
    }

    @Test
    fun subagentRegistry_terminateKillsTargetOrAllSubagents() {
        val initial = listOf(
            SubagentInfo("s1", "R1", "t1", SubagentState.RUNNING),
            SubagentInfo("s2", "R2", "t2", SubagentState.RUNNING),
        )

        val terminatedOne = SubagentRegistry.terminate(initial, "s1", finishedAt = 7000L)
        assertEquals(SubagentState.TERMINATED, terminatedOne[0].state)
        assertEquals(7000L, terminatedOne[0].finishedAtMillis)
        assertEquals(SubagentState.RUNNING, terminatedOne[1].state)

        val terminatedAll = SubagentRegistry.terminate(initial, "*", finishedAt = 8000L)
        assertTrue(terminatedAll.all { it.state == SubagentState.TERMINATED })
    }

    @Test
    fun subagentRegistry_clearCompletedPurgesOnlyTerminalSubagents() {
        val initial = listOf(
            SubagentInfo("s1", "R1", "t1", SubagentState.RUNNING),
            SubagentInfo("s2", "R2", "t2", SubagentState.DONE),
            SubagentInfo("s3", "R3", "t3", SubagentState.ERRORED),
            SubagentInfo("s4", "R4", "t4", SubagentState.TERMINATED),
            SubagentInfo("s5", "R5", "t5", SubagentState.WAITING_FOR_INPUT),
        )

        val remaining = SubagentRegistry.clearCompleted(initial)
        assertEquals(2, remaining.size)
        assertEquals(listOf("s1", "s5"), remaining.map { it.conversationId })
    }

    // -------------------------------------------------------------------------
    // 4. TaskRegistry Invariants
    // -------------------------------------------------------------------------

    @Test
    fun taskRegistry_completeFailTerminateAndClear() {
        val tasks = listOf(
            BackgroundTaskInfo("t1", "./gradlew build", "/app", BackgroundTaskStatus.RUNNING),
            BackgroundTaskInfo("t2", "git status", "/app", BackgroundTaskStatus.COMPLETED),
        )

        val completed = TaskRegistry.completeRunning(tasks)
        assertEquals(BackgroundTaskStatus.COMPLETED, completed[0].status)
        assertEquals(BackgroundTaskStatus.COMPLETED, completed[1].status)

        val failed = TaskRegistry.failRunning(tasks)
        assertEquals(BackgroundTaskStatus.FAILED, failed[0].status)

        val terminated = TaskRegistry.terminate(tasks, "t1")
        assertEquals(BackgroundTaskStatus.TERMINATED, terminated[0].status)

        val cleared = TaskRegistry.clearCompleted(tasks)
        assertEquals(1, cleared.size)
        assertEquals("t1", cleared[0].taskId)
    }

    // -------------------------------------------------------------------------
    // 5. TriggerParser Tests (PRD §3.3)
    // -------------------------------------------------------------------------

    @Test
    fun triggerParser_slashCommandDetectionAtStartAndAfterWhitespace() {
        // At start of prompt
        val t1 = TriggerParser.parseTrigger("/plan")
        assertEquals(TriggerType.SLASH_COMMAND, t1.type)
        assertEquals("plan", t1.query)
        assertEquals(0, t1.triggerStartIndex)

        // After whitespace
        val t2 = TriggerParser.parseTrigger("Please /boost")
        assertEquals(TriggerType.SLASH_COMMAND, t2.type)
        assertEquals("boost", t2.query)
        assertEquals(7, t2.triggerStartIndex)

        // Empty slash at start
        val t3 = TriggerParser.parseTrigger("/")
        assertEquals(TriggerType.SLASH_COMMAND, t3.type)
        assertEquals("", t3.query)

        // Empty slash after space
        val t4 = TriggerParser.parseTrigger("Can you /")
        assertEquals(TriggerType.SLASH_COMMAND, t4.type)
        assertEquals("", t4.query)
    }

    @Test
    fun triggerParser_fileMentionDetectionAtStartAndAfterWhitespace() {
        // At start
        val t1 = TriggerParser.parseTrigger("@MainActivity")
        assertEquals(TriggerType.FILE_MENTION, t1.type)
        assertEquals("MainActivity", t1.query)
        assertEquals(0, t1.triggerStartIndex)

        // After whitespace with path characters
        val t2 = TriggerParser.parseTrigger("inspect @app/src/main/Models.kt")
        assertEquals(TriggerType.FILE_MENTION, t2.type)
        assertEquals("app/src/main/Models.kt", t2.query)
    }

    @Test
    fun triggerParser_ignoresInWordSlashAndEmailAddress() {
        // In-word slash (URL or ratio)
        val t1 = TriggerParser.parseTrigger("https://example.com/api")
        assertEquals(TriggerType.NONE, t1.type)

        val t2 = TriggerParser.parseTrigger("either/or")
        assertEquals(TriggerType.NONE, t2.type)

        // In-word @ (email address)
        val t3 = TriggerParser.parseTrigger("developer@domain.com")
        assertEquals(TriggerType.NONE, t3.type)

        // Trailing space after command (dismisses popup)
        val t4 = TriggerParser.parseTrigger("/plan ")
        assertEquals(TriggerType.NONE, t4.type)
    }
}
