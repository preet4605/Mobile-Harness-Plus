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
        repeat(ModAgentSteps.MAX_STEPS + 5) { steps.ended(steps.add("a.mod", null, "claude", "p$it", fromUser = false), ModAgentSteps.State.COMPLETED) }
        assertEquals(ModAgentSteps.MAX_STEPS, steps.steps.value.size)
        assertEquals("p5", steps.steps.value.first().prompt)
        assertEquals(ModAgentSteps.State.TIMEOUT, ModAgentSteps.stateOf("timeout"))
        assertEquals(ModAgentSteps.State.FAILED, ModAgentSteps.stateOf("failed"))
    }

    @Test
    fun `queued sends run in queue order, can be moved, cancelled, paused and retried`() {
        val steps = ModAgentSteps()
        var wakes = 0
        steps.wake = { wakes++ }
        val a = steps.enqueue("a.mod", "coder", "codex", "first", "p1", model = "m")
        val b = steps.enqueue("a.mod", "reviewer", "claude", "second", "p1")
        val c = steps.enqueue("a.mod", "coder", "codex", "third", "p1")
        assertEquals(3, wakes)
        assertTrue(steps.move(c, earlier = true))
        assertFalse(steps.move(a, earlier = true))
        assertTrue(steps.cancel(b))
        assertFalse(steps.cancel(b))
        val first = steps.takeNext()!!
        assertEquals(a, first.id)
        assertEquals("m", first.model)
        assertEquals(ModAgentSteps.State.WAITING, steps.of("a.mod").first { it.id == a }.state)
        steps.pause("a.mod")
        assertEquals(null, steps.takeNext())
        steps.resume("a.mod")
        assertEquals(4, wakes)
        assertEquals(c, steps.takeNext()!!.id)
        assertEquals(null, steps.takeNext())
        steps.ended(a, ModAgentSteps.State.COMPLETED, reply = "ok")
        val again = steps.retry(a, "p2")!!
        steps.of("a.mod").first { it.id == again }.let {
            assertEquals(ModAgentSteps.State.QUEUED, it.state)
            assertEquals("first", it.prompt)
            assertEquals("p2", it.projectId)
            assertTrue(it.fromUser)
        }
        assertEquals(null, steps.retry(c, "p1"))
    }

    @Test
    fun `stop chain pauses the mod, drops its queue and stops its running step`() {
        val steps = ModAgentSteps()
        val stopped = mutableListOf<Long>()
        steps.stopper = { stopped += it.id; true }
        val running = steps.add("a.mod", "coder", "codex", "work", fromUser = false)
        steps.running(running, "codex", "t1", "c1")
        val queued = steps.enqueue("a.mod", "coder", "codex", "more", "p1")
        val other = steps.enqueue("b.mod", null, "claude", "theirs", "p1")
        steps.stopChain("a.mod")
        assertEquals(listOf(running), stopped)
        assertEquals(ModAgentSteps.State.CANCELLED, steps.of("a.mod").first { it.id == queued }.state)
        assertTrue("a.mod" in steps.paused.value)
        assertEquals(other, steps.takeNext()!!.id)
    }

    @Test
    fun `trimming keeps unfinished steps`() {
        val steps = ModAgentSteps()
        val queued = steps.enqueue("a.mod", null, "claude", "keep me", "p1")
        repeat(ModAgentSteps.MAX_STEPS + 5) { steps.ended(steps.add("a.mod", null, "claude", "p$it", fromUser = false), ModAgentSteps.State.COMPLETED) }
        assertEquals(ModAgentSteps.MAX_STEPS, steps.steps.value.size)
        assertEquals(queued, steps.steps.value.first().id)
    }
}
