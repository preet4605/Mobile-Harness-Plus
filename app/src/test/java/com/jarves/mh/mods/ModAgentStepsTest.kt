package com.jarves.mh.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModAgentStepsTest {
    @Test
    fun `steps go from waiting to running to done, and only running ones stop`() {
        var now = 1_000L
        val steps = ModAgentSteps { now }
        val stopped = mutableListOf<String?>()
        steps.stopper = { stopped += it.taskId; true }
        val a = steps.add("a.mod", "coder", null, "Build it", fromUser = true)
        assertFalse(steps.stop(a))
        steps.running(a, "codex", "task-1", "chat-1")
        assertEquals(ModAgentSteps.State.RUNNING, steps.of("a.mod").single().state)
        assertEquals("codex", steps.of("a.mod").single().harness)
        assertTrue(steps.stop(a))
        assertEquals(listOf<String?>("task-1"), stopped)
        now = 5_000L
        steps.ended(a, ModAgentSteps.stateOf("completed"), reply = "Done")
        steps.ended(a, ModAgentSteps.State.FAILED, error = "late")
        val done = steps.of("a.mod").single()
        assertEquals(ModAgentSteps.State.COMPLETED, done.state)
        assertEquals(5_000L, done.endedAt)
        assertEquals("Done", done.reply)
        assertFalse(steps.stop(a))
        assertTrue(steps.of("b.mod").isEmpty())
    }

    @Test
    fun `only the newest steps are kept`() {
        val steps = ModAgentSteps()
        repeat(ModAgentSteps.MAX_STEPS + 5) { steps.add("a.mod", null, "claude", "p$it", fromUser = false) }
        assertEquals(ModAgentSteps.MAX_STEPS, steps.steps.value.size)
        assertEquals("p5", steps.steps.value.first().prompt)
        assertEquals(ModAgentSteps.State.TIMEOUT, ModAgentSteps.stateOf("timeout"))
        assertEquals(ModAgentSteps.State.FAILED, ModAgentSteps.stateOf("failed"))
    }
}
