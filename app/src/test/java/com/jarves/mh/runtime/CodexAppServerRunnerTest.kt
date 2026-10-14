package com.jarves.mh.runtime

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexAppServerRunnerTest {
    private val thread = "12345678-1234-1234-1234-123456789abc"
    private val config = CodexAppServerRunnerConfig(pollMs = 1, requestTimeoutMs = 100, closeGraceMs = 10)

    @Test fun goalActivationSeedsContextAndImagesAndKeepsNativeContinuationOwned() = runBlocking {
        val events = mutableListOf<CodexEvent>()
        val seenThreads = mutableListOf<String>()
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/goal/set") {
                fake.reply(req, JSONObject().put("goal", goal("active")))
                fake.notify("turn/started", JSONObject().put("turn", turn("first", "inProgress")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("first", "completed")))
                fake.notify("turn/started", JSONObject().put("turn", turn("second", "inProgress")))
                fake.notify("thread/goal/updated", JSONObject().put("goal", goal("complete")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("second", "completed")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "context", "model", null, false,
            CodexNativeControl.parse("goal", "--budget 200 objective"), listOf("/workspace/p/.harness/attachments/a.png"),
            imageData = { "data:image/png;base64,aGVsbG8=" }, emit = { events += it }, onThread = { seenThreads += it }, config = config).run()
        assertTrue(result.failure, result.completed)
        assertFalse(io.isAlive)
        assertEquals(listOf(thread), seenThreads)
        assertEquals("complete", result.goalStatus)
        val methods = io.requests.map { it.optString("method") }
        assertTrue(methods.indexOf("thread/inject_items") < methods.indexOf("thread/goal/set"))
        assertFalse(methods.contains("turn/start"))
        val injected = io.requests.single { it.optString("method") == "thread/inject_items" }.getJSONObject("params").getJSONArray("items").getJSONObject(0).getJSONArray("content")
        assertEquals("context", injected.getJSONObject(0).getString("text"))
        assertEquals("input_image", injected.getJSONObject(1).getString("type"))
        assertEquals(2, events.filterIsInstance<CodexEvent.TurnCompleted>().size)
    }

    @Test fun savedGoalStatusReadsWithoutResumeOrModelTurn() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/goal/get") { fake.reply(req, JSONObject().put("goal", goal("budgetLimited"))); true } else false
        }
        val events = mutableListOf<CodexEvent>()
        val result = CodexAppServerRunner(io, "/workspace/p", "", "model", thread, false,
            CodexNativeControl.parse("goal", "status"), emit = { events += it }, config = config).run()
        assertTrue(result.completed)
        assertFalse(io.requests.any { it.optString("method") in listOf("thread/resume", "thread/start", "turn/start") })
        assertTrue(events.filterIsInstance<CodexEvent.AgentMessage>().single().text.contains("budgetLimited"))
    }

    @Test fun deltasAndCompletedSnapshotsDoNotDuplicateAndOtherThreadsCannotFinishOwner() = runBlocking {
        val mapper = CodexEventMapper("session")
        val output = StringBuilder()
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                fake.notify("item/agentMessage/delta", JSONObject().put("turnId", "t").put("itemId", "a").put("delta", "Hello"))
                fake.notify("item/completed", JSONObject().put("turnId", "t").put("item", JSONObject().put("id", "a").put("type", "agentMessage").put("text", "Hello")))
                fake.notify("turn/completed", JSONObject().put("threadId", "other").put("turn", turn("bad", "completed")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("t", "completed")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "prompt", "model", null, true, null,
            emit = { event -> mapper.map(event).filterIsInstance<com.jarves.mh.model.RuntimeEvent.AssistantDelta>().forEach { output.append(it.text) } }, config = config).run()
        assertTrue(result.completed)
        assertEquals("Hello", output.toString())
        val mode = io.requests.single { it.optString("method") == "turn/start" }.getJSONObject("params").getJSONObject("collaborationMode")
        assertEquals("plan", mode.getString("mode"))
        assertTrue(mode.getJSONObject("settings").isNull("developer_instructions"))
    }

    @Test fun unsupportedServerRequestReturnsRpcErrorAndFailsWithoutHanging() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                fake.append(JSONObject().put("id", "question").put("method", "item/tool/requestUserInput").put("params", JSONObject().put("threadId", thread).put("questions", org.json.JSONArray().put(JSONObject().put("question", "Choose a database")))))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "prompt", "model", null, false, null, emit = {}, config = config).run()
        assertFalse(result.completed)
        assertTrue(result.failure.contains("Choose a database"))
        assertTrue(io.requests.any { it.optString("id") == "question" && it.has("error") })
        assertFalse(io.isAlive)
    }

    @Test fun modGateAnswersApprovalRequestsOnlyWhenItsSwitchIsOn() = runBlocking {
        fun io() = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                fake.notify("item/started", JSONObject().put("turnId", "t").put("item", JSONObject().put("id", "f1").put("type", "fileChange")
                    .put("changes", org.json.JSONArray().put(JSONObject().put("path", "src/a.kt")))))
                fake.append(JSONObject().put("id", "ap1").put("method", "item/commandExecution/requestApproval")
                    .put("params", JSONObject().put("threadId", thread).put("turnId", "t").put("itemId", "c1").put("command", "rm -rf /").put("cwd", "/workspace/p")))
                fake.append(JSONObject().put("id", "ap2").put("method", "item/fileChange/requestApproval")
                    .put("params", JSONObject().put("threadId", thread).put("turnId", "t").put("itemId", "f1")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("t", "completed")))
                true
            } else false
        }
        val asked = mutableListOf<Pair<String, String>>()
        val gated = io()
        val result = CodexAppServerRunner(gated, "/workspace/p", "prompt", "model", null, false, null, emit = {}, config = config,
            toolGate = { tool, input -> asked += tool to input.toString(); !input.optString("command").startsWith("rm ") }).run()
        assertTrue(result.failure, result.completed)
        assertEquals("untrusted", gated.requests.single { it.optString("method") == "thread/start" }.getJSONObject("params").getString("approvalPolicy"))
        assertEquals("decline", gated.requests.single { it.optString("id") == "ap1" }.getJSONObject("result").getString("decision"))
        assertEquals("accept", gated.requests.single { it.optString("id") == "ap2" }.getJSONObject("result").getString("decision"))
        assertEquals(listOf("Bash", "apply_patch"), asked.map { it.first })
        assertTrue(asked[0].second, "\"cwd\":\"/workspace/p\"" in asked[0].second)
        assertTrue(asked[1].second, "src/a.kt" in asked[1].second)

        // Switch off: Codex never asks, as before, and a stray request still fails cleanly.
        val plain = io()
        val off = CodexAppServerRunner(plain, "/workspace/p", "prompt", "model", null, false, null, emit = {}, config = config).run()
        assertEquals("never", plain.requests.single { it.optString("method") == "thread/start" }.getJSONObject("params").getString("approvalPolicy"))
        assertFalse(off.completed)
        assertTrue(plain.requests.any { it.optString("id") == "ap1" && it.has("error") })
    }

    @Test fun cancellationBeforeHandshakeNeverAdmitsNativeWorkAndReapsChild() = runBlocking {
        val io = RpcIo(thread)
        val result = CodexAppServerRunner(io, "/workspace/p", "prompt", "model", null, false, null, emit = {}, isStopRequested = { true }, config = config).run()
        assertFalse(result.completed)
        assertTrue(io.requests.none { it.optString("method") == "thread/start" })
        assertFalse(io.isAlive)
    }

    @Test fun failedNativeOperationIsNotTreatedAsSuccessAndProcessCloses() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/compact/start") { fake.append(JSONObject().put("id", req.get("id")).put("error", JSONObject().put("message", "Compaction unavailable"))); true } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "", "model", thread, false, CodexNativeControl.parse("compact", ""), emit = {}, config = config).run()
        assertFalse(result.completed)
        assertTrue(result.failure.contains("Compaction unavailable"))
        assertFalse(io.isAlive)
    }

    @Test fun resumedGoalDoesNotInjectTheControlAsUserContent() = runBlocking {
        val io = RpcIo(thread) { req, fake -> when (req.optString("method")) {
            "thread/goal/get" -> { fake.reply(req, JSONObject().put("goal", goal("paused"))); true }
            "thread/goal/set" -> {
                fake.reply(req, JSONObject().put("goal", goal("active")))
                fake.notify("turn/started", JSONObject().put("turn", turn("t", "inProgress")))
                fake.notify("thread/goal/updated", JSONObject().put("goal", goal("complete")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("t", "completed")))
                true
            }
            else -> false
        } }
        val result = CodexAppServerRunner(io, "/workspace/p", "/goal resume", "model", thread, false,
            CodexNativeControl.parse("goal", "resume"), emit = {}, config = config).run()
        assertTrue(result.failure, result.completed)
        assertFalse(io.requests.any { it.optString("method") in listOf("thread/inject_items", "turn/start") })
    }

    @Test fun compactionItemAloneDoesNotHideDeathBeforeItsTurnCloses() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/compact/start") {
                fake.reply(req, JSONObject())
                fake.notify("item/completed", JSONObject().put("item", JSONObject().put("id", "compact").put("type", "contextCompaction")))
                fake.dieAfterDrain = true
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "", "model", thread, false,
            CodexNativeControl.parse("compact", ""), emit = {}, config = config).run()
        assertFalse(result.completed)
    }

    @Test fun activeGoalPauseAndClearPersistBeforeInterruptingTheirTurn() = runBlocking {
        for (operation in listOf("pause", "clear")) {
            var requested = false
            val io = RpcIo(thread) { req, fake -> when (req.optString("method")) {
                "thread/goal/get" -> { fake.reply(req, JSONObject().put("goal", goal("active"))); true }
                "thread/goal/set" -> {
                    val status = req.getJSONObject("params").getString("status")
                    fake.reply(req, JSONObject().put("goal", goal(status)))
                    if (status == "active") fake.notify("turn/started", JSONObject().put("turn", turn("t", "inProgress")))
                    true
                }
                "turn/interrupt" -> {
                    fake.reply(req, JSONObject())
                    fake.notify("turn/completed", JSONObject().put("turn", turn("t", "interrupted")))
                    true
                }
                else -> false
            } }
            val result = CodexAppServerRunner(io, "/workspace/p", "context", "model", null, false,
                CodexNativeControl.parse("goal", "objective"), emit = {}, takeGoalControl = {
                    if (!requested) CodexNativeControl.parse("goal", operation).also { requested = true } else null
                }, config = config).run()
            assertTrue("$operation: ${result.failure}", result.completed)
            val methods = io.requests.map { it.optString("method") }
            val index = if (operation == "pause") methods.lastIndexOf("thread/goal/set") else methods.indexOf("thread/goal/clear")
            assertTrue(index < methods.indexOf("turn/interrupt"))
            assertFalse(io.isAlive)
        }
    }

    @Test fun processDeathNeverCompletesAnUnfinishedNativeTurn() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                fake.dieAfterDrain = true
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "task", "model", null, false, null, emit = {}, config = config).run()
        assertFalse(result.completed)
        assertTrue(result.failure.contains("exited"))
    }

    @Test fun nativeReviewUsesTypedTargetAndItsOwnedThread() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "review/start") {
                fake.reply(req, JSONObject().put("reviewThreadId", thread).put("turn", turn("review", "inProgress")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("review", "completed")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "", "model", null, false,
            CodexNativeControl.parse("review", "--base main"), emit = {}, config = config).run()
        assertTrue(result.failure, result.completed)
        val target = io.requests.single { it.optString("method") == "review/start" }.getJSONObject("params").getJSONObject("target")
        assertEquals("baseBranch", target.getString("type"))
        assertEquals("main", target.getString("branch"))
        assertFalse(io.requests.any { it.optString("method") == "turn/start" })
    }

    @Test fun idleGoalReleasesActivityBetweenNativeTurns() = runBlocking {
        val activity = mutableListOf<Boolean>()
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/goal/set") {
                fake.reply(req, JSONObject().put("goal", goal("active")))
                fake.notify("turn/started", JSONObject().put("turn", turn("first", "inProgress")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("first", "completed")))
                fake.notify("turn/started", JSONObject().put("turn", turn("second", "inProgress")))
                fake.notify("thread/goal/updated", JSONObject().put("goal", goal("complete")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("second", "completed")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "context", "model", null, false,
            CodexNativeControl.parse("goal", "objective"), emit = {}, onActivity = { activity += it }, config = config).run()
        assertTrue(result.failure, result.completed)
        assertEquals(listOf(true, false, true, false, false), activity.dropWhile { !it })
    }

    @Test fun coroutineCancellationInterruptsAndReapsEvenWhenStdinCloseDoesNotExit() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                true
            } else false
        }.apply { keepAliveAfterInputClose = true }
        val task = async { CodexAppServerRunner(io, "/workspace/p", "task", "model", null, false, null, emit = {}, config = config).run() }
        withTimeout(1_000) { while (io.requests.none { it.optString("method") == "turn/start" }) delay(1) }
        task.cancel()
        withTimeout(1_000) { task.join() }
        assertTrue(io.requests.any { it.optString("method") == "turn/interrupt" })
        assertFalse(io.isAlive)
        assertTrue(io.destroyed)
    }

    @Test fun lateRpcResponseCannotRestoreAnAlreadyCompletedGoal() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/goal/set") {
                fake.notify("turn/started", JSONObject().put("turn", turn("t", "inProgress")))
                fake.notify("thread/goal/updated", JSONObject().put("goal", goal("complete")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("t", "completed")))
                fake.reply(req, JSONObject().put("goal", goal("active")))
                fake.dieAfterDrain = true
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "context", "model", null, false,
            CodexNativeControl.parse("goal", "objective"), emit = {}, config = config).run()
        assertTrue(result.failure, result.completed)
    }

    @Test fun goalControlCannotInterruptOrFailAnOrdinaryRunningTurn() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("t", "completed")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "task", "model", null, false, null,
            emit = {}, takeGoalControl = { CodexNativeControl.parse("goal", "pause") }, config = config).run()
        assertTrue(result.failure, result.completed)
        assertFalse(io.requests.any { it.optString("method") in setOf("thread/goal/set", "turn/interrupt") })
    }

    @Test fun savedPauseAndClearDoNotCreateAGoalWhenTheThreadHasNone() = runBlocking {
        for (operation in listOf("pause", "clear")) {
            val io = RpcIo(thread)
            val result = CodexAppServerRunner(io, "/workspace/p", "", "model", thread, false,
                CodexNativeControl.parse("goal", operation), emit = {}, config = config).run()
            assertTrue(result.failure, result.completed)
            assertFalse(io.requests.any { it.optString("method") in setOf("thread/goal/set", "thread/goal/clear", "thread/resume", "turn/start") })
        }
    }

    @Test fun budgetLimitedGoalWithoutANewTurnClosesItsOwnedServer() = runBlocking {
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "thread/goal/set") {
                fake.reply(req, JSONObject().put("goal", goal("budgetLimited")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "context", "model", null, false,
            CodexNativeControl.parse("goal", "--budget 1 objective"), emit = {}, config = config).run()
        assertTrue(result.failure, result.completed)
        assertFalse(io.isAlive)
    }

    @Test fun pauseAndClearAcceptAnInterruptedTurnBeforeTheirMutationReply() = runBlocking {
        for (operation in listOf("pause", "clear")) {
            var requested = false
            val io = RpcIo(thread) { req, fake -> when (req.optString("method")) {
                "thread/goal/get" -> { fake.reply(req, JSONObject().put("goal", goal("active"))); true }
                "thread/goal/set" -> {
                    val status = req.getJSONObject("params").getString("status")
                    if (status == "paused") fake.notify("turn/completed", JSONObject().put("turn", turn("t", "interrupted")))
                    fake.reply(req, JSONObject().put("goal", goal(status)))
                    if (status == "active") fake.notify("turn/started", JSONObject().put("turn", turn("t", "inProgress")))
                    true
                }
                "thread/goal/clear" -> {
                    fake.notify("turn/completed", JSONObject().put("turn", turn("t", "interrupted")))
                    fake.reply(req, JSONObject())
                    true
                }
                else -> false
            } }
            val result = CodexAppServerRunner(io, "/workspace/p", "context", "model", null, false,
                CodexNativeControl.parse("goal", "objective"), emit = {}, takeGoalControl = {
                    if (!requested) CodexNativeControl.parse("goal", operation).also { requested = true } else null
                }, config = config).run()
            assertTrue(result.failure, result.completed)
            assertEquals("$operation must not record an unsolicited interruption", "", result.failure)
            assertEquals(if (operation == "clear") "cleared" else "paused", result.goalStatus)
            assertFalse(io.isAlive)
        }
    }

    @Test fun globalQuotaUpdatesAreRecordedWithoutThreadIdentityAndForeignTurnsRemainFiltered() = runBlocking {
        val limits = mutableListOf<JSONObject>()
        val io = RpcIo(thread) { req, fake ->
            if (req.optString("method") == "turn/start") {
                fake.append(JSONObject().put("method", "account/rateLimits/updated").put("params", JSONObject().put("rateLimits", JSONObject("""{"primary":{"usedPercent":100,"resetsAt":2000}}"""))))
                fake.reply(req, JSONObject().put("turn", turn("t", "inProgress")))
                fake.notify("turn/completed", JSONObject().put("turn", turn("t", "completed")))
                true
            } else false
        }
        val result = CodexAppServerRunner(io, "/workspace/p", "task", "model", null, false, null,
            emit = {}, onRateLimits = { limits += it }, config = config).run()
        assertTrue(result.failure, result.completed); assertEquals(1, limits.size)
        assertEquals(100, limits.single().getJSONObject("primary").getInt("usedPercent"))
    }

    private fun goal(status: String) = JSONObject().put("threadId", thread).put("objective", "objective").put("status", status).put("tokenBudget", 200).put("tokensUsed", 123).put("timeUsedSeconds", 9)
    private fun turn(id: String, status: String) = JSONObject().put("id", id).put("status", status).put("items", org.json.JSONArray())
}

private class RpcIo(private val thread: String, private val handle: (JSONObject, RpcIo) -> Boolean = { _, _ -> false }) : CodexProcessIo {
    val requests = mutableListOf<JSONObject>()
    private val output = ByteArrayOutputStream()
    private var alive = true
    var dieAfterDrain = false
    var keepAliveAfterInputClose = false
    var destroyed = false
    override val isAlive: Boolean get() = alive
    override fun outputLength() = output.size().toLong()
    override fun readOutput(offset: Long, buffer: ByteArray, length: Int): Int { val bytes = output.toByteArray(); val n = minOf(length, bytes.size - offset.toInt()); bytes.copyInto(buffer, 0, offset.toInt(), offset.toInt() + n); if (dieAfterDrain && offset + n == bytes.size.toLong()) alive = false; return n }
    override fun writeInput(text: String) {
        val request = JSONObject(text)
        requests += request
        if (!request.has("method") || !request.has("id") || handle(request, this)) return
        val result = when (request.getString("method")) {
            "thread/start", "thread/resume" -> JSONObject().put("thread", JSONObject().put("id", thread)).put("model", "model")
            "thread/goal/get" -> JSONObject().put("goal", JSONObject.NULL)
            else -> JSONObject()
        }
        reply(request, result)
    }
    fun append(obj: JSONObject) { output.write((obj.toString() + "\n").toByteArray()) }
    fun reply(request: JSONObject, result: JSONObject) = append(JSONObject().put("id", request.get("id")).put("result", result))
    fun notify(method: String, params: JSONObject) { if (!params.has("threadId")) params.put("threadId", thread); append(JSONObject().put("method", method).put("params", params)) }
    override fun closeInput() { if (!keepAliveAfterInputClose) alive = false }
    override fun interrupt() { alive = false }
    override fun destroy() { destroyed = true; alive = false }
    override fun destroyForcibly() { alive = false }
    override fun waitFor() = 0
}
