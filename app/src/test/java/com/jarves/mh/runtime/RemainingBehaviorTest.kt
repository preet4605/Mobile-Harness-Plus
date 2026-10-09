package com.jarves.mh.runtime

import com.jarves.mh.model.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.jarves.mh.data.BrainDatabase
import com.jarves.mh.data.BrainDatabaseDriverFactory
import com.jarves.mh.runtime.task.TaskSupervisor
import com.jarves.mh.runtime.task.TaskExecutionStatus
import org.junit.rules.TemporaryFolder
import java.io.*
import java.net.*

class RemainingBehaviorTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun supervisor(): TaskSupervisor = TaskSupervisor.createForTesting(
        database = BrainDatabase(BrainDatabaseDriverFactory.createInMemoryDriver())
    ).apply {
        val workspace = temporary.newFolder()
        workspaceDirectoryResolver = { workspace }
        val checkpoints = WorkspaceCheckpoints(temporary.newFolder())
        checkpointsResolver = { checkpoints }
    }

    @Test fun unsafeReplayFailsOnceAndPreservesRecoveryRequirementAfterRecreation() = runBlocking {
        val supervisor = supervisor()
        val record = supervisor.createTask(taskId = "unsafe", projectId = "p", projectSlug = "p", chatId = "c",
            agentKind = "ANTIGRAVITY", providerJson = "{}", prompt = "Run tools", maxRetries = 2)
        var attempts = 0
        val job = supervisor.executeTask(record.taskId) {
            attempts++
            throw IllegalStateException("REPLAY_UNSAFE: interrupted tool execution")
        }
        withTimeout(5000) { job.join() }
        assertEquals(1, attempts)
        val restored = TaskSupervisor.createForTesting(database = supervisor.database).stateStore.get(record.taskId)!!
        assertEquals(TaskExecutionStatus.FAILED, restored.status)
        assertTrue(restored.recoveryRequired)
    }

    @Test fun startupSnapshotDoesNotAbandonTasksCreatedAfterSnapshot() {
        val supervisor = supervisor()
        supervisor.createTask(taskId = "old", projectId = "p", projectSlug = "p", chatId = "c",
            agentKind = "CODEX", providerJson = "{}", prompt = "Old task")
        supervisor.stateStore.transition("old", TaskExecutionStatus.STARTING)
        val snapshot = supervisor.stateStore.getActiveTasks()
        supervisor.createTask(taskId = "new", projectId = "q", projectSlug = "q", chatId = "d",
            agentKind = "CODEX", providerJson = "{}", prompt = "New task")
        supervisor.stateStore.transition("new", TaskExecutionStatus.STARTING)
        supervisor.reconcileOnStartup(snapshot)
        assertTrue(supervisor.stateStore.get("old")!!.status.isTerminal)
        assertEquals(TaskExecutionStatus.STARTING, supervisor.stateStore.get("new")!!.status)
    }

    @Test fun captureBudgetStillDrainsWholeStreamAndReportsOverflowOnce() {
        val source = ByteArrayInputStream(ByteArray(1_000_000) { 65 })
        val destination = ByteArrayOutputStream()
        var reports = 0
        BoundedProcessCapture.copy(source, destination, 256) { reports++ }
        assertEquals(256, destination.size())
        assertEquals(0, source.available())
        assertEquals(1, reports)
    }

    @Test fun exactCaptureBudgetDoesNotReportOverflow() {
        var overflow = false
        BoundedProcessCapture.copy(ByteArrayInputStream(ByteArray(32)), ByteArrayOutputStream(), 32) { overflow = true }
        assertFalse(overflow)
    }

    @Test fun responsesToolsAndResultsRoundTripAndErrorsAreRejected() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, "https://provider.example/v1", "model", dshApi = "openai-responses")
        LocalFormatGateway(profile, "dummy").use { gateway ->
            val source = JSONObject("""{"messages":[{"role":"user","content":"hi"},{"role":"assistant","content":[{"type":"tool_use","id":"call1","name":"read","input":{"path":"a"}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"call1","content":"file"}]}],"tools":[{"name":"read","input_schema":{"type":"object"}}]}""")
            val request = gateway.toResponses(source)
            assertFalse(request.has("messages"))
            val items = request.getJSONArray("input")
            assertEquals("function_call", items.getJSONObject(1).getString("type"))
            assertEquals("call1", items.getJSONObject(2).getString("call_id"))
            assertEquals("function_call_output", items.getJSONObject(2).getString("type"))
            assertEquals("read", request.getJSONArray("tools").getJSONObject(0).getString("name"))
            val response = JSONObject("""{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]},{"type":"function_call","call_id":"call2","name":"read","arguments":"{\"path\":\"b\"}"}],"usage":{"input_tokens":5,"output_tokens":8}}""")
            val converted = gateway.fromResponses(response, "model")
            assertEquals("done", converted.getJSONArray("content").getJSONObject(0).getString("text"))
            assertEquals("call2", converted.getJSONArray("content").getJSONObject(1).getString("id"))
            assertEquals("tool_use", converted.getString("stop_reason"))
            assertEquals(5, converted.getJSONObject("usage").getInt("input_tokens"))
            listOf("incomplete", "failed", "in_progress").forEach { status ->
                assertThrows(IllegalStateException::class.java) { gateway.fromResponses(JSONObject().put("status", status), "model") }
            }
        }
    }

    @Test fun responsesRouteReachesOnlyResponsesEndpoint() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            var seenPath = ""
            var seenBody = ""
            val upstream = Thread {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    seenPath = input.readLine().split(' ')[1]
                    var length = 0
                    while (true) {
                        val line = input.readLine()
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < length) { val n = input.read(chars, offset, length - offset); if (n < 0) break; offset += n }
                    seenBody = String(chars)
                    val body = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}""".toByteArray()
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                }
            }.apply { isDaemon = true; start() }
            val profile = ProviderProfile(ProviderKind.CUSTOM, "http://127.0.0.1:${server.localPort}/v1/responses", "model", dshApi = "openai-responses")
            LocalFormatGateway(profile, "dummy").start().use { gateway ->
                val connection = URL(gateway.url + "/v1/messages").openConnection() as HttpURLConnection
                try {
                    connection.readTimeout = 3000
                    connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer ${gateway.gatewaySecret}")
                    connection.outputStream.use { it.write("""{"messages":[{"role":"user","content":"hi"}]}""".toByteArray()) }
                    assertEquals(200, connection.responseCode)
                    assertTrue(connection.inputStream.bufferedReader().use { it.readText() }.contains("OK"))
                } finally { connection.disconnect() }
            }
            upstream.join(1000)
            assertEquals("/v1/responses", seenPath)
            assertTrue(JSONObject(seenBody).has("input"))
        }
    }

    @Test fun shutdownClosesStalledClientsAndConcurrencyIsBounded() {
        val gateway = LocalFormatGateway(ProviderProfile(ProviderKind.NVIDIA_NIM), "dummy").start()
        val clients = (0 until 20).map { Socket("127.0.0.1", URI(gateway.url).port).apply { soTimeout = 1000 } }
        try {
            gateway.close()
            clients.forEach { client ->
                val result = runCatching { client.getInputStream().read() }
                assertTrue(result.getOrNull() == -1 || result.exceptionOrNull() is SocketException)
            }
        } finally { clients.forEach { it.close() }; gateway.close() }
    }

    @Test fun interruptedAndPrematureEofAntigravityStreamsEmitErrorWithoutDone() {
        val root = temporary.newFolder()
        val token = File(root, "account/.gemini/antigravity-cli/antigravity-oauth-token").apply { requireNotNull(parentFile).mkdirs() }
        token.writeText("""{"token":{"access_token":"dummy"}}""")
        var accounts = listOf(AntigravityAccount(id = "account", email = "fixture@example.com", isPrimary = true))
        val manager = AntigravityAccountManager(customAccountsDir = root, loadAccountsOverride = { accounts }, saveAccountsOverride = { accounts = it }, strategyOverride = { AntigravityLoadBalancingStrategy.ROUND_ROBIN })
        for (throws in listOf(false, true)) {
            val chunk = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}\n\n".toByteArray()
            val stream = object : InputStream() {
                var offset = 0
                override fun read(): Int = if (offset < chunk.size) chunk[offset++].toInt() and 255 else if (throws) throw IOException("injected interruption") else -1
            }
            AntigravityGatewayServer(manager, transportOverride = { _, _, _, _ -> AntigravityGatewayServer.GatewayHttpResult(200, "", stream) }).start().use { gateway ->
                val connection = URL(gateway.url + "/v1/messages").openConnection() as HttpURLConnection
                try {
                    connection.readTimeout = 3000; connection.requestMethod = "POST"; connection.doOutput = true
                    connection.setRequestProperty("Authorization", "Bearer ${gateway.gatewaySecret}")
                    connection.outputStream.use { it.write("""{"model":"gemini-3.8-pro","stream":true,"messages":[{"role":"user","content":"hi"}]}""".toByteArray()) }
                    val output = connection.inputStream.bufferedReader().use { it.readText() }
                    assertTrue(output.contains("event: error"))
                    assertFalse(output.contains("message_stop"))
                    assertFalse(output.contains("end_turn"))
                } finally { connection.disconnect() }
            }
        }
    }

    @Test fun credentialMaskKeepsOnlySelectedHomeAndRejectsTraversal() {
        assertFalse(CredentialMounts.hiddenHomes("/root/.codex").contains("/root/.codex"))
        assertTrue(CredentialMounts.hiddenHomes("/root/.codex").contains("/root/.claude"))
        assertTrue(CredentialMounts.hiddenHomes("/root/.antigravity-accounts/account").contains("/root/.antigravity-accounts"))
        assertThrows(IllegalArgumentException::class.java) { CredentialMounts.hiddenHomes("/root/.codex/../.claude") }
    }

    @Test fun nativeResumeScopeChangesWithOwnerRouteRootAndCredentialIdentity() {
        val a = temporary.newFolder(); val b = temporary.newFolder()
        val profile = ProviderProfile(ProviderKind.CUSTOM, "https://provider.example/v1", "model")
        val original = NativeConversationScope.key("chat", a, profile, "key1")
        assertEquals(original, NativeConversationScope.key("chat", a, profile, "key1"))
        assertNotEquals(original, NativeConversationScope.key("other", a, profile, "key1"))
        assertNotEquals(original, NativeConversationScope.key("chat", b, profile, "key1"))
        assertNotEquals(original, NativeConversationScope.key("chat", a, profile.copy(model = "other"), "key1"))
        assertNotEquals(original, NativeConversationScope.key("chat", a, profile, "key2"))
        assertNull(NativeConversationScope.validId("--last"))
    }

    @Test fun resumeArgumentsUseExplicitIdAndKeepStdinImagesAndEffort() {
        val id = "00112233-4455-6677-8899-aabbccddeeff"
        val command = CodexLaunchBuilder.command(CodexRoute.ChatGptLogin("model"), "/workspace/project", "/tmp/last", "high", listOf("/workspace/project/a.png"), id, true)
        assertFalse(command.contains("--ephemeral"))
        assertTrue(command.contains("resume")); assertEquals(id, command[command.lastIndex - 1]); assertEquals("-", command.last())
        assertTrue(command.indexOf("-C") < command.indexOf("resume"))
        assertTrue(command.contains("model_reasoning_effort=\"high\""))
        val claude = ClaudeRuntimeBridge.buildClaudeCommand("claude", "model", resumeSessionId = id)
        assertEquals(id, claude[claude.indexOf("--resume") + 1])
    }
}
