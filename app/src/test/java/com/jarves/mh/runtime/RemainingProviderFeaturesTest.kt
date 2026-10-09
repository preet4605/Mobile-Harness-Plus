package com.jarves.mh.runtime

import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.SubagentState
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemainingProviderFeaturesTest {
    @Test fun dshInitializePreservesDefaultAndForwardsExplicitEffort() {
        val default = DshRuntimeBridge.buildInitializeParams("/workspace/p", "deepseek-official", "deepseek-v4-flash", "default")
        assertFalse(default.has("reasoningEffort"))
        val high = DshRuntimeBridge.buildInitializeParams("/workspace/p", "deepseek-official", "deepseek-v4-flash", "high")
        assertEquals("high", high.getString("reasoningEffort"))
        assertEquals("/workspace/p", high.getString("cwd"))
    }

    @Test fun dshInitializeRefusesUnknownEffort() {
        try {
            DshRuntimeBridge.buildInitializeParams("/workspace/p", "deepseek-official", "model", "medium")
            fail("Unsupported effort was forwarded")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun distinctCodexChildrenRemainDistinctInTheSharedRegistry() {
        val raw = """{"type":"item.completed","item":{"id":"collab","type":"collab_tool_call","tool":"spawn_agent","agents_states":{"one":{"status":"running"},"two":{"status":"running"}},"status":"completed"}}"""
        val children = CodexEventMapper("parent").map(CodexJsonlParser.parseLine(raw)).filterIsInstance<RuntimeEvent.SubagentUpdated>()
        val registry = children.fold(emptyList<com.jarves.mh.model.SubagentInfo>()) { list, event -> com.jarves.mh.model.SubagentRegistry.update(list, event.subagent) }
        assertEquals(setOf("one", "two"), registry.map { it.conversationId }.toSet())
    }

    @Test fun malformedDshUsageIsUnavailable() {
        val raw = frame("assistant/message", """{"turn":1,"step":1,"usage":{"inputTokens":null,"outputTokens":-1}}""")
        assertEquals(DshSdkProtocolEvent.Ignored, DshSdkProtocolParser("s").parseLine(raw))
    }

    @Test fun codexCommandsPublishOwnedInspectorState() {
        val mapper = CodexEventMapper("session")
        val started = mapper.map(CodexEvent.CommandStarted("command", "npm run dev")).filterIsInstance<RuntimeEvent.TaskUpdated>().single()
        val finished = mapper.map(CodexEvent.CommandFinished("command", "npm run dev", "stopped", 2, true)).filterIsInstance<RuntimeEvent.TaskUpdated>().single()
        assertEquals("session", started.sessionId)
        assertEquals("command", started.task.taskId)
        assertEquals(com.jarves.mh.model.BackgroundTaskStatus.RUNNING, started.task.status)
        assertEquals(com.jarves.mh.model.BackgroundTaskStatus.FAILED, finished.task.status)
        assertEquals(started.task.startedAtMillis, finished.task.startedAtMillis)
        assertEquals(2, finished.task.exitCode)
    }
    @Test fun codexChildCompletionDoesNotCompleteTheParent() {
        val parser = CodexJsonlParser
        val mapper = CodexEventMapper("parent")
        val raw = """{"type":"item.completed","item":{"id":"collab-1","type":"collab_tool_call","tool":"wait","sender_thread_id":"main","receiver_thread_ids":["child"],"agents_states":{"child":{"status":"completed","message":"Done"}},"status":"completed"}}"""
        val events = mapper.map(parser.parseLine(raw))
        val child = events.filterIsInstance<RuntimeEvent.SubagentUpdated>().single()
        assertEquals("parent", child.sessionId)
        assertEquals("child", child.subagent.conversationId)
        assertEquals(SubagentState.DONE, child.subagent.state)
        assertFalse(events.any { it is RuntimeEvent.SessionCompleted })
    }

    @Test fun codexFailedWaitDoesNotInventAFailedChild() {
        val raw = """{"type":"item.completed","item":{"id":"collab-2","type":"collab_tool_call","tool":"wait","receiver_thread_ids":["child"],"agents_states":{},"status":"failed"}}"""
        val events = CodexEventMapper("parent").map(CodexJsonlParser.parseLine(raw))
        assertTrue(events.any { it is RuntimeEvent.ToolCompleted })
        assertTrue(events.none { it is RuntimeEvent.SubagentUpdated })
    }

    @Test fun dshUsageIsReportedWithoutDuplicatingStreamedText() {
        val parser = DshSdkProtocolParser("s")
        parser.parseLine(frame("assistant/chunk", """{"turn":1,"step":1,"chunk":{"type":"text-delta","index":0,"text":"answer"}}"""))
        val event = parser.parseLine(frame("assistant/message", """{"turn":1,"step":1,"message":{"content":[{"type":"text","text":"answer"}]},"usage":{"inputTokens":10,"outputTokens":5,"cacheReadTokens":3,"cacheWriteTokens":2}}"""))
        assertNotEquals(DshSdkProtocolEvent.Ignored, event)
        val usage = event as DshSdkProtocolEvent.UsageUpdated
        assertEquals("", usage.text)
        assertEquals(15, usage.metrics.promptTokens)
        assertEquals(5, usage.metrics.completionTokens)
        assertEquals(3, usage.metrics.cachedTokens)
        assertTrue(usage.metrics.reported)
    }

    @Test fun dshRepeatedUsageReplacesTheStepBucket() {
        val parser = DshSdkProtocolParser("s")
        val raw = frame("assistant/chunk", """{"turn":1,"step":1,"chunk":{"type":"usage","usage":{"inputTokens":10,"outputTokens":5}}}""")
        parser.parseLine(raw)
        val repeated = parser.parseLine(raw) as DshSdkProtocolEvent.UsageUpdated
        assertEquals(10, repeated.metrics.promptTokens)
        val next = parser.parseLine(raw.replace("\"step\":1", "\"step\":2")) as DshSdkProtocolEvent.UsageUpdated
        assertEquals(20, next.metrics.promptTokens)
        assertEquals(10, next.metrics.completionTokens)
    }

    @Test fun dshChildrenRequireParentOwnershipAndCannotCloseTheParent() {
        val parser = DshSdkProtocolParser("s")
        val raw = """{"method":"subagent.started","params":{"parentSessionId":"s","childSessionId":"child"}}"""
        val child = parser.parseLine(raw) as DshSdkProtocolEvent.ChildUpdated
        assertEquals(SubagentState.RUNNING, child.child.state)
        assertEquals(DshSdkProtocolEvent.Ignored, parser.parseLine(raw.replace("\"s\"", "\"old\"")))
        val finish = parser.parseLine("""{"method":"subagent.finished","params":{"parentSessionId":"s","childSessionId":"child","status":"ok","stopReason":"completed"}}""") as DshSdkProtocolEvent.ChildUpdated
        assertEquals(SubagentState.DONE, finish.child.state)
        assertEquals(child.child.startedAtMillis, finish.child.startedAtMillis)
        assertEquals(DshSdkProtocolEvent.Ignored, parser.parseLine("""{"method":"session.status","params":{"sessionId":"child","status":"idle"}}"""))
    }

    @Test fun staleDshUsageCannotAffectTheCurrentSession() {
        val raw = frame("assistant/message", """{"turn":1,"step":1,"usage":{"inputTokens":10,"outputTokens":5}}""").replace("\"s\"", "\"old\"")
        assertEquals(DshSdkProtocolEvent.Ignored, DshSdkProtocolParser("s").parseLine(raw))
    }

    private fun frame(type: String, data: String): String = JSONObject()
        .put("method", "session.event")
        .put("params", JSONObject().put("sessionId", "s").put("event", JSONObject().put("type", type).put("data", JSONObject(data))))
        .toString()
}
