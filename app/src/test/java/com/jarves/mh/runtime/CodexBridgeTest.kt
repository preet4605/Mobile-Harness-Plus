package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.SessionTokenMetrics
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fixtures under `src/test/resources/codex` are real `codex exec --json` captures (codex-cli
 * 0.161.0). Inputs marked "synthesized" are hand-written because the shape could not be captured.
 */
private object CodexFixtures {
    fun text(name: String): String =
        checkNotNull(CodexFixtures::class.java.getResourceAsStream("/codex/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    fun events(name: String): List<CodexEvent> =
        text(name).lines().filter { it.isNotBlank() }.map(CodexJsonlParser::parseLine)
}

class CodexJsonlParserTest {
    @Test
    fun textFixtureParsesInOrder() {
        val events = CodexFixtures.events("text.jsonl")
        assertTrue(events[0] is CodexEvent.ThreadStarted)
        assertEquals("01a11b84-e5d2-7c52-9f4c-763a4f0e6f38", (events[0] as CodexEvent.ThreadStarted).threadId)
        assertTrue(events[1] is CodexEvent.Notice)
        assertEquals(CodexEvent.TurnStarted, events[2])
        assertEquals(CodexEvent.Reasoning("Planning the change"), events[3])
        assertEquals(CodexEvent.AgentMessage("Hello from the mock model."), events[4])
        assertEquals(CodexEvent.TurnCompleted(CodexUsage(120, 0, 30, 5)), events[5])
    }

    @Test
    fun shellFixtureYieldsStartedAndFinishedCommand() {
        val events = CodexFixtures.events("shell.jsonl")
        val started = events.filterIsInstance<CodexEvent.CommandStarted>().single()
        assertEquals("/bin/bash -lc 'echo hi > made-by-codex.txt && cat made-by-codex.txt'", started.command)
        val finished = events.filterIsInstance<CodexEvent.CommandFinished>().single()
        assertEquals(0, finished.exitCode)
        assertEquals("hi\n", finished.output)
        assertFalse(finished.failed)
    }

    @Test
    fun authFailureFixtureYieldsErrorThenTurnFailed() {
        val events = CodexFixtures.events("auth-failure.jsonl")
        val error = events.filterIsInstance<CodexEvent.StreamError>().single()
        assertFalse(error.transient)
        assertTrue(error.message.contains("401"))
        assertTrue(events.last() is CodexEvent.TurnFailed)
        assertTrue((events.last() as CodexEvent.TurnFailed).message.contains("Incorrect API key"))
    }

    @Test
    fun streamDroppedFixtureKeepsAgentMessageBeforeFailure() {
        val events = CodexFixtures.events("stream-dropped.jsonl")
        assertTrue(events.any { it == CodexEvent.AgentMessage("Partial answer before the stream drops.") })
        assertTrue(events.last() is CodexEvent.TurnFailed)
    }

    @Test
    fun unsupportedToolFixtureStillCompletes() {
        val events = CodexFixtures.events("unsupported-tool.jsonl")
        assertTrue(events.any { it is CodexEvent.TurnCompleted })
        assertTrue(events.none { it is CodexEvent.Diagnostic })
    }

    @Test
    fun reconnectNoticesAreTransient() {
        val retry = CodexJsonlParser.parseLine(
            """{"type":"error","message":"Reconnecting... 2/5 (stream disconnected before completion)"}""",
        ) as CodexEvent.StreamError
        assertTrue(retry.transient)
        val offline = CodexJsonlParser.parseLine(
            """{"type":"error","message":"Reconnecting... waiting for network"}""",
        ) as CodexEvent.StreamError
        assertTrue(offline.transient)
        val fatal = CodexJsonlParser.parseLine("""{"type":"error","message":"unexpected status 500"}""")
        assertFalse((fatal as CodexEvent.StreamError).transient)
    }

    @Test
    fun plainTextConfigErrorIsDiagnostic() {
        val line = "Error loading config.toml: `wire_api = \"chat\"` is no longer supported."
        assertEquals(CodexEvent.Diagnostic(line), CodexJsonlParser.parseLine(line))
    }

    @Test
    fun synthesizedFileChangeListsPaths() {
        val event = CodexJsonlParser.parseLine(
            """{"type":"item.completed","item":{"id":"item_3","type":"file_change","changes":""" +
                """[{"path":"/w/a.txt","kind":"add"},{"path":"/w/b.txt","kind":"update"},{"kind":"delete"}],""" +
                """"status":"completed"}}""",
        )
        assertEquals(CodexEvent.FileChange(listOf("/w/a.txt", "/w/b.txt")), event)
    }

    @Test
    fun failedCommandIsFlagged() {
        val byExit = CodexJsonlParser.parseLine(
            """{"type":"item.completed","item":{"id":"i","type":"command_execution","command":"x",""" +
                """"aggregated_output":"boom","exit_code":2,"status":"failed"}}""",
        ) as CodexEvent.CommandFinished
        assertTrue(byExit.failed)
        assertEquals(2, byExit.exitCode)
        val nullExit = CodexJsonlParser.parseLine(
            """{"type":"item.completed","item":{"id":"i","type":"command_execution","command":"x",""" +
                """"aggregated_output":"","exit_code":null,"status":"completed"}}""",
        ) as CodexEvent.CommandFinished
        assertNull(nullExit.exitCode)
        assertFalse(nullExit.failed)
    }

    @Test
    fun toolCallsAreRecognised() {
        val mcp = CodexJsonlParser.parseLine(
            """{"type":"item.started","item":{"id":"i","type":"mcp_tool_call","server":"fs","tool":"read",""" +
                """"arguments":{"path":"a"},"status":"in_progress"}}""",
        ) as CodexEvent.ToolCall
        assertEquals("fs.read", mcp.name)
        assertFalse(mcp.finished)
        val search = CodexJsonlParser.parseLine(
            """{"type":"item.completed","item":{"id":"i","type":"web_search","query":"kotlin"}}""",
        ) as CodexEvent.ToolCall
        assertTrue(search.finished)
        assertEquals("kotlin", search.detail)
    }

    @Test
    fun malformedAndUnknownInputNeverThrows() {
        val inputs = listOf(
            "", "   ", "{not json", "[]", "null", "{}", """{"type":42}""", """{"type":"item.completed"}""",
            """{"type":"item.completed","item":"x"}""", """{"type":"item.completed","item":{"type":"agent_message","text":null}}""",
            """{"type":"item.completed","item":{"type":"nonsense"}}""", """{"type":"thread.started"}""",
            """{"type":"turn.completed","usage":"oops"}""", """{"type":"turn.failed"}""",
            """{"type":"item.completed","item":{"type":"file_change","changes":"nope"}}""",
            """{"type":"item.completed","item":{"type":"command_execution","exit_code":"x"}}""",
            "\u0000\u0001\u0002", "{" + "x".repeat(10_000),
        )
        inputs.forEach { assertNotNull(CodexJsonlParser.parseLine(it)) }
        assertEquals(CodexEvent.Ignored, CodexJsonlParser.parseLine(""))
        assertEquals(CodexEvent.Ignored, CodexJsonlParser.parseLine("""{"type":"item.completed","item":{"type":"agent_message","text":null}}"""))
        val failed = CodexJsonlParser.parseLine("""{"type":"turn.failed"}""") as CodexEvent.TurnFailed
        assertTrue(failed.message.isNotBlank())
    }

    @Test
    fun oversizedSentinelBecomesDiagnostic() {
        val event = CodexJsonlParser.parseLine(CodexLineAssembler.OVERSIZED)
        assertTrue(event is CodexEvent.Diagnostic)
        assertFalse((event as CodexEvent.Diagnostic).text.contains('\u0000'))
    }

    @Test
    fun longDiagnosticIsBounded() {
        val event = CodexJsonlParser.parseLine("x".repeat(100_000)) as CodexEvent.Diagnostic
        assertTrue(event.text.length <= 500)
    }
}

class CodexLineAssemblerTest {
    private fun feedAll(assembler: CodexLineAssembler, text: String): List<String> =
        text.toByteArray().let { assembler.feed(it, it.size) }

    @Test
    fun splitsLinesAndKeepsPartialUntilNewline() {
        val assembler = CodexLineAssembler()
        assertEquals(listOf("one"), feedAll(assembler, "one\ntw"))
        assertEquals(listOf("two", "three"), feedAll(assembler, "o\nthree\n"))
        assertNull(assembler.flush())
    }

    @Test
    fun flushReturnsUnterminatedTail() {
        val assembler = CodexLineAssembler()
        assertTrue(feedAll(assembler, "tail").isEmpty())
        assertEquals("tail", assembler.flush())
        assertNull(assembler.flush())
    }

    @Test
    fun crlfAndBlankLinesAreNormalised() {
        assertEquals(listOf("a", "b"), feedAll(CodexLineAssembler(), "a\r\n\r\n\nb\r\n"))
    }

    @Test
    fun multiByteCharacterSplitAcrossChunksSurvives() {
        val line = "héllo ✓ 日本語 😀"
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val assembler = CodexLineAssembler()
        val out = mutableListOf<String>()
        bytes.forEach { out += assembler.feed(byteArrayOf(it), 1) }
        assertEquals(listOf(line), out)
    }

    @Test
    fun oversizedLineIsDroppedOnceAndNextLineSurvives() {
        val assembler = CodexLineAssembler(maxLineBytes = 16)
        val out = mutableListOf<String>()
        out += feedAll(assembler, "a".repeat(10))
        out += feedAll(assembler, "a".repeat(10))
        out += feedAll(assembler, "a".repeat(100) + "\nok\n")
        assertEquals(listOf(CodexLineAssembler.OVERSIZED, "ok"), out)
    }

    @Test
    fun lineOfExactlyTheLimitIsKept() {
        assertEquals(listOf("a".repeat(16)), feedAll(CodexLineAssembler(maxLineBytes = 16), "a".repeat(16) + "\n"))
    }

    @Test
    fun oversizedTailWithoutNewlineIsReportedOnFlush() {
        val assembler = CodexLineAssembler(maxLineBytes = 8)
        assertTrue(feedAll(assembler, "x".repeat(50)).isEmpty())
        assertEquals(CodexLineAssembler.OVERSIZED, assembler.flush())
    }

    @Test
    fun invalidUtf8DoesNotThrow() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xC3.toByte(), '\n'.code.toByte(), 'o'.code.toByte(), 'k'.code.toByte(), '\n'.code.toByte())
        val lines = CodexLineAssembler().feed(bytes, bytes.size)
        assertEquals(2, lines.size)
        assertEquals("ok", lines[1])
    }
}

class CodexEventMapperTest {
    private val sid = "s1"

    @Test
    fun secondAgentMessageIsSeparatedFromTheFirst() {
        val mapper = CodexEventMapper(sid)
        assertEquals(listOf<RuntimeEvent>(RuntimeEvent.AssistantDelta(sid, "First")), mapper.map(CodexEvent.AgentMessage("First")))
        assertEquals(listOf<RuntimeEvent>(RuntimeEvent.AssistantDelta(sid, "\n\nSecond")), mapper.map(CodexEvent.AgentMessage("Second")))
    }

    @Test
    fun reasoningBecomesFinalSummaryBlocksWithIncreasingIds() {
        val mapper = CodexEventMapper(sid)
        val first = mapper.map(CodexEvent.Reasoning("Plan\n  the   change")).single() as RuntimeEvent.ReasoningSummary
        val second = mapper.map(CodexEvent.Reasoning("Next")).single() as RuntimeEvent.ReasoningSummary
        assertEquals("Plan the change", first.summary)
        assertTrue(first.startsNewBlock && first.isFinal)
        assertEquals(first.blockId + 1, second.blockId)
        assertTrue(mapper.map(CodexEvent.Reasoning("   ")).isEmpty())
    }

    @Test
    fun displayCommandUnwrapsShell() {
        assertEquals(
            "echo hi > x.txt && cat x.txt",
            CodexEventMapper.displayCommand("/bin/bash -lc 'echo hi > x.txt && cat x.txt'"),
        )
        assertEquals("ls -la", CodexEventMapper.displayCommand("bash -lc \"ls -la\""))
        assertEquals("echo 'hi'", CodexEventMapper.displayCommand("/bin/bash -lc 'echo '\\''hi'\\'''"))
        assertEquals("python3 run.py", CodexEventMapper.displayCommand("python3 run.py"))
        assertEquals("Working in the project", CodexEventMapper.displayCommand("   "))
        assertTrue(CodexEventMapper.displayCommand("x ".repeat(1000)).length <= 240)
    }

    @Test
    fun commandsMapToBashToolEvents() {
        val mapper = CodexEventMapper(sid)
        assertEquals(
            listOf<RuntimeEvent>(RuntimeEvent.ToolStarted(sid, "Bash", "ls")),
            mapper.map(CodexEvent.CommandStarted("i", "/bin/bash -lc 'ls'")),
        )
        val ok = mapper.map(CodexEvent.CommandFinished("i", "ls", "\n  a.txt\nb.txt", 0, false)).single()
        assertEquals(RuntimeEvent.ToolCompleted(sid, "Bash", "a.txt"), ok)
        val empty = mapper.map(CodexEvent.CommandFinished("i", "true", "", 0, false)).single()
        assertEquals(RuntimeEvent.ToolCompleted(sid, "Bash", "Command completed"), empty)
        val bad = mapper.map(CodexEvent.CommandFinished("i", "x", "boom\nmore", 2, true)).single()
        assertEquals(RuntimeEvent.ToolCompleted(sid, "Bash", "Exit code 2: boom"), bad)
    }

    @Test
    fun fileChangeBecomesEditPair() {
        val mapper = CodexEventMapper(sid)
        assertTrue(mapper.map(CodexEvent.FileChange(emptyList())).isEmpty())
        val events = mapper.map(CodexEvent.FileChange(listOf("a.txt", "b.txt")))
        assertEquals(RuntimeEvent.ToolStarted(sid, "Edit", "a.txt, b.txt"), events[0])
        assertEquals(RuntimeEvent.ToolCompleted(sid, "Edit", "Edited a.txt, b.txt"), events[1])
    }

    @Test
    fun usageMapsToTokenMetrics() {
        val mapper = CodexEventMapper(sid)
        val event = mapper.map(CodexEvent.TurnCompleted(CodexUsage(120, 20, 30, 5))).single() as RuntimeEvent.TokenUsageUpdated
        assertEquals(SessionTokenMetrics(promptTokens = 120, completionTokens = 30, cachedTokens = 20), event.metrics)
        assertTrue(mapper.map(CodexEvent.TurnCompleted(CodexUsage())).isEmpty())
    }

    @Test
    fun protocolOnlyEventsEmitNothing() {
        val mapper = CodexEventMapper(sid)
        listOf(
            CodexEvent.ThreadStarted("t"), CodexEvent.TurnStarted, CodexEvent.Notice("n"),
            CodexEvent.StreamError("e", false), CodexEvent.TurnFailed("f"), CodexEvent.Diagnostic("d"), CodexEvent.Ignored,
        ).forEach { assertTrue(mapper.map(it).isEmpty()) }
    }

    @Test
    fun toolCallsMapToStartAndCompletion() {
        val mapper = CodexEventMapper(sid)
        val started = mapper.map(CodexEvent.ToolCall("i", "fs.read", "", false, false)).single()
        assertEquals(RuntimeEvent.ToolStarted(sid, "fs.read", "Working in the project"), started)
        val failed = mapper.map(CodexEvent.ToolCall("i", "fs.read", "", true, true)).single()
        assertEquals(RuntimeEvent.ToolCompleted(sid, "fs.read", "fs.read failed"), failed)
    }
}

class CodexLaunchBuilderTest {
    private val login = CodexRoute.ChatGptLogin("gpt-5")
    private val apiKey = CodexRoute.ApiKey("mh-zen", "https://gw.example.com/v1", "MH_CODEX_API_KEY", "m1")

    @Test
    fun chatGptLoginArgvIsExact() {
        assertEquals(
            listOf(
                "/usr/local/bin/codex", "exec", "--json", "--color", "never", "--skip-git-repo-check", "--ephemeral",
                "-s", "danger-full-access", "-C", "/workspace/proj", "-m", "gpt-5", "-o", "/tmp/last.txt",
                "-c", "approval_policy=\"never\"",
                "-c", "check_for_update_on_startup=false",
                "-c", "analytics.enabled=false",
                "-c", "cli_auth_credentials_store=\"file\"",
                "-",
            ),
            CodexLaunchBuilder.command(login, "/workspace/proj", "/tmp/last.txt"),
        )
    }

    @Test
    fun blankModelOmitsModelFlag() {
        val argv = CodexLaunchBuilder.command(CodexRoute.ChatGptLogin(""), "/w", "/o")
        assertFalse(argv.contains("-m"))
    }

    @Test
    fun apiKeyRouteDefinesCustomProviderAndNeverPutsTheKeyInArgv() {
        val argv = CodexLaunchBuilder.command(apiKey, "/w", "/o")
        assertTrue(argv.contains("model_provider=\"mh-zen\""))
        assertTrue(
            argv.contains(
                "model_providers.mh-zen={name=\"mh-zen\",base_url=\"https://gw.example.com/v1\"," +
                    "env_key=\"MH_CODEX_API_KEY\",wire_api=\"responses\"}",
            ),
        )
        val env = CodexLaunchBuilder.environment(apiKey, "sk-very-secret")
        assertEquals("sk-very-secret", env["MH_CODEX_API_KEY"])
        assertTrue(argv.none { it.contains("sk-very-secret") })
        assertEquals("/root/.codex", env["CODEX_HOME"])
    }

    @Test
    fun environmentRequiresASecretForApiKeyOnly() {
        assertThrows(IllegalArgumentException::class.java) { CodexLaunchBuilder.environment(apiKey, " ") }
        assertThrows(IllegalArgumentException::class.java) { CodexLaunchBuilder.environment(apiKey, null) }
        val env = CodexLaunchBuilder.environment(login, "ignored")
        assertFalse(env.containsKey("MH_CODEX_API_KEY"))
        assertFalse(env.values.contains("ignored"))
    }

    @Test
    fun tomlStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", CodexLaunchBuilder.tomlString("a\"b\\c\nd"))
        assertEquals("\"\\u0001\"", CodexLaunchBuilder.tomlString("\u0001"))
        assertEquals("\"日本語\"", CodexLaunchBuilder.tomlString("日本語"))
    }

    @Test
    fun hostileBaseUrlCannotBreakOutOfTheTomlValue() {
        val route = CodexRoute.ApiKey("mh-x", "https://a\"}, evil=\"1", "MH_CODEX_API_KEY", "m")
        val table = CodexLaunchBuilder.configOverrides(route).last()
        assertTrue(table.contains("base_url=\"https://a\\\"}, evil=\\\"1\""))
    }
}

class CodexRouteMapperTest {
    @Test
    fun chatGptAccountUsesCodexOwnLoginWithTheChosenModel() {
        assertEquals(
            CodexRoute.ChatGptLogin(""),
            CodexRouteMapper.forProfile(ProviderProfile(ProviderKind.CHATGPT)),
        )
        assertEquals(
            CodexRoute.ChatGptLogin("gpt-x"),
            CodexRouteMapper.forProfile(ProviderProfile(ProviderKind.CHATGPT, "", " gpt-x ")),
        )
    }

    @Test
    fun opencodeZenIsNotOfferedBecauseResponsesSupportIsUnverified() {
        val error = assertThrows(CodexUnsupportedProviderException::class.java) {
            CodexRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN))
        }
        assertTrue(error.message!!.contains("Codex"))
    }

    @Test
    fun customResponsesEndpointIsNormalised() {
        val route = CodexRouteMapper.forProfile(
            ProviderProfile(
                ProviderKind.CUSTOM, baseUrl = "gw.example.com/v1/responses", model = "m",
                dshApi = "openai-responses", profileId = "Abc-123_xyz",
            ),
        ) as CodexRoute.ApiKey
        assertEquals("https://gw.example.com/v1", route.baseUrl)
        assertEquals("mh-abc123xyz", route.providerId)
        assertEquals("MH_CODEX_API_KEY", route.keyEnv)
        assertEquals("m", route.model)
    }

    @Test
    fun storedProtocolIsIgnoredAndDefaultsToResponses() {
        listOf("openai-completions", "anthropic-messages", "").forEach { api ->
            val route = CodexRouteMapper.forProfile(
                ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1", dshApi = api),
            )
            assertTrue(route is CodexRoute.ApiKey)
        }
    }

    @Test
    fun urlSuffixesThatSelectAnotherProtocolAreRejected() {
        val chat = assertThrows(CodexUnsupportedProviderException::class.java) {
            CodexRouteMapper.forProfile(
                ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1/chat/completions"),
            )
        }
        assertTrue(chat.message!!.contains("Responses"))
        assertThrows(CodexUnsupportedProviderException::class.java) {
            CodexRouteMapper.forProfile(
                ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example.com/v1/messages"),
            )
        }
    }

    @Test
    fun chatOnlyAndUnrelatedProvidersAreRejected() {
        val nim = assertThrows(CodexUnsupportedProviderException::class.java) {
            CodexRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM))
        }
        assertTrue(nim.message!!.contains("Chat Completions"))
        listOf(ProviderKind.CLAUDE, ProviderKind.ANTHROPIC, ProviderKind.DEEPSEEK).forEach {
            val error = assertThrows(CodexUnsupportedProviderException::class.java) {
                CodexRouteMapper.forProfile(ProviderProfile(it))
            }
            assertTrue(error.message!!.contains("Codex"))
        }
    }

    @Test
    fun invalidEndpointsAreRejected() {
        listOf("", "ftp://example.com/v1", "   ").forEach { url ->
            assertThrows(CodexUnsupportedProviderException::class.java) {
                CodexRouteMapper.forProfile(
                    ProviderProfile(ProviderKind.CUSTOM, baseUrl = url, dshApi = "openai-responses"),
                )
            }
        }
    }
}

class CodexFailureMessagesTest {
    @Test
    fun mapsKnownFailures() {
        val f = CodexFailureMessages::friendly
        assertTrue(f("unexpected status 401 Unauthorized: Incorrect API key provided, url: http://x", 1).contains("API key"))
        assertTrue(f("Missing environment variable: `MH_CODEX_API_KEY`.", 1).contains("Re-save"))
        assertTrue(f("Not logged in", 1).contains("not signed in"))
        assertTrue(f("429 Too Many Requests", 1).contains("rate limit"))
        assertTrue(f("Error loading config.toml: `wire_api = \"chat\"` is no longer supported.", 1).contains("configuration"))
        assertTrue(f("stream disconnected before completion", 1).contains("Network"))
    }

    @Test
    fun mapsExitCodesWhenThereIsNoText() {
        assertTrue(CodexFailureMessages.friendly("", 127).contains("could not start"))
        assertTrue(CodexFailureMessages.friendly("", 126).contains("could not start"))
        assertTrue(CodexFailureMessages.friendly("", 137).contains("killed"))
        assertTrue(CodexFailureMessages.friendly("", 139).contains("crashed"))
        assertTrue(CodexFailureMessages.friendly("", 3).contains("exit code 3"))
        assertEquals("Codex could not start.", CodexFailureMessages.friendly("", null))
    }

    @Test
    fun unknownTextIsBounded() {
        assertTrue(CodexFailureMessages.friendly("z".repeat(5000), 1).length <= 500)
    }
}

/** In-memory stand-in for a Codex process. [waitFor] records any call made while the process is alive. */
private class FakeCodexIo : CodexProcessIo {
    private val lock = Any()
    private val buffer = ByteArrayOutputStream()

    @Volatile var alive = true
    @Volatile var exitCode = 0
    @Volatile var interruptCalls = 0
    @Volatile var destroyCalls = 0
    @Volatile var forceKillCalls = 0
    @Volatile var inputClosed = false
    @Volatile var waitedWhileAlive = false
    @Volatile var exitOnInterrupt = false
    @Volatile var exitOnDestroy = true
    @Volatile var exitOnForceKill = true
    @Volatile var exitOnClose: Int? = null
    @Volatile var writeFailure: Exception? = null
    @Volatile var readFailure: Exception? = null
    @Volatile var readReturnsZero = false
    val written = StringBuffer()

    fun output(text: String) = outputBytes(text.toByteArray(Charsets.UTF_8))
    fun outputBytes(bytes: ByteArray) = synchronized(lock) { buffer.write(bytes) }
    fun exit(code: Int) {
        exitCode = code
        alive = false
    }

    override val isAlive: Boolean get() = alive
    override fun outputLength(): Long = synchronized(lock) { buffer.size().toLong() }
    override fun readOutput(offset: Long, buffer: ByteArray, length: Int): Int {
        readFailure?.let { throw it }
        if (readReturnsZero) return 0
        val all = synchronized(lock) { this.buffer.toByteArray() }
        val count = minOf(length, all.size - offset.toInt())
        System.arraycopy(all, offset.toInt(), buffer, 0, count)
        return count
    }

    override fun writeInput(text: String) {
        writeFailure?.let { throw it }
        written.append(text)
    }

    override fun closeInput() {
        inputClosed = true
        exitOnClose?.let { exit(it) }
    }

    override fun interrupt() {
        interruptCalls++
        if (exitOnInterrupt) exit(130)
    }

    override fun destroy() {
        destroyCalls++
        if (exitOnDestroy) exit(143)
    }

    override fun destroyForcibly() {
        forceKillCalls++
        if (exitOnForceKill) exit(137)
    }

    override fun waitFor(): Int {
        if (alive) waitedWhileAlive = true
        return exitCode
    }
}

class CodexTurnRunnerTest {
    private val fast = CodexRunnerConfig(pollMs = 1, transientStallMs = 500, exitGraceMs = 200, stopGraceMs = 100, maxLineBytes = 4096)

    private class Run(val io: FakeCodexIo = FakeCodexIo()) {
        var prompt = "Hello"
        @Volatile var lastMessage: String? = null
        @Volatile var stop = false
        var onEvent: (CodexEvent) -> Unit = {}
        val events = mutableListOf<CodexEvent>()
        private var clock = 0L

        fun execute(config: CodexRunnerConfig): CodexRunResult = runBlocking {
            withTimeout(20_000) {
                CodexTurnRunner(
                    io = io,
                    prompt = prompt,
                    emit = { events += it; onEvent(it) },
                    isStopRequested = { stop },
                    readLastMessage = { lastMessage },
                    now = { clock += 10; clock },
                    config = config,
                ).run()
            }.also { assertFalse("waitFor() was called on a live process", io.waitedWhileAlive) }
        }
    }

    private fun run(setup: Run.() -> Unit): Pair<Run, CodexRunResult> {
        val run = Run()
        run.setup()
        return run to run.execute(fast)
    }

    @Test
    fun successfulTurnCompletesAndSendsPromptOnStdin() {
        val (run, result) = run {
            io.output(CodexFixtures.text("text.jsonl"))
            io.exitOnClose = 0
            prompt = "Fix the bug"
        }
        assertTrue(result.failure, result.completed)
        assertEquals(0, result.exitCode)
        assertNotNull(result.threadId)
        assertEquals("Fix the bug", run.io.written.toString())
        assertTrue(run.io.inputClosed)
        assertEquals(0, run.io.destroyCalls + run.io.forceKillCalls)
        assertTrue(run.events.contains(CodexEvent.AgentMessage("Hello from the mock model.")))
    }

    @Test
    fun immediateExit127ReportsStartFailure() {
        val (_, result) = run { io.exit(127) }
        assertFalse(result.completed)
        assertTrue(result.failure, result.failure.contains("could not start"))
        assertEquals(127, result.exitCode)
    }

    @Test
    fun killedMidTaskReportsMemoryKill() {
        val (_, result) = run {
            io.output("""{"type":"thread.started","thread_id":"t"}""" + "\n" + """{"type":"turn.started"}""" + "\n")
            io.exit(137)
        }
        assertFalse(result.completed)
        assertTrue(result.failure.contains("killed"))
    }

    @Test
    fun nativeCrashReportsCrash() {
        val (_, result) = run { io.output("""{"type":"turn.started"}""" + "\n"); io.exit(139) }
        assertFalse(result.completed)
        assertTrue(result.failure.contains("crashed"))
    }

    @Test
    fun cleanExitWithoutAnyOutputIsAFailureNotASuccess() {
        val (_, result) = run { io.exit(0) }
        assertFalse(result.completed)
        assertTrue(result.failure.contains("exit code 0"))
    }

    @Test
    fun cleanExitWithAgentMessageButNoTurnEventCountsAsCompleted() {
        val (_, result) = run {
            io.output("""{"type":"item.completed","item":{"id":"i","type":"agent_message","text":"Done"}}""" + "\n")
            io.exit(0)
        }
        assertTrue(result.completed)
    }

    @Test
    fun nonZeroExitAfterTurnCompletedStillCompletes() {
        val (_, result) = run { io.output(CodexFixtures.text("text.jsonl")); io.exit(1) }
        assertTrue(result.completed)
        assertEquals(1, result.exitCode)
    }

    @Test
    fun truncatedCaptureFallsBackToLastMessageFile() {
        val (run, result) = run {
            io.output("""{"type":"thread.started","thread_id":"t"}""" + "\n" + """{"type":"turn.started"}""" + "\n")
            io.exit(0)
            lastMessage = "  Final answer  "
        }
        assertTrue(result.completed)
        assertTrue(run.events.contains(CodexEvent.AgentMessage("Final answer")))
    }

    @Test
    fun lastMessageFileIsIgnoredWhenTheProcessFailed() {
        val (run, result) = run {
            io.output("""{"type":"turn.started"}""" + "\n")
            io.exit(2)
            lastMessage = "stale text"
        }
        assertFalse(result.completed)
        assertTrue(run.events.none { it is CodexEvent.AgentMessage })
    }

    @Test
    fun turnFailedIsMappedToAFriendlyMessage() {
        val (_, result) = run { io.output(CodexFixtures.text("auth-failure.jsonl")); io.exit(1) }
        assertFalse(result.completed)
        assertEquals("The provider rejected the saved API key.", result.failure)
    }

    @Test
    fun streamDropAfterPartialAnswerFailsButKeepsTheAnswer() {
        val (run, result) = run { io.output(CodexFixtures.text("stream-dropped.jsonl")); io.exit(1) }
        assertFalse(result.completed)
        assertTrue(result.failure.contains("Network"))
        assertTrue(run.events.contains(CodexEvent.AgentMessage("Partial answer before the stream drops.")))
    }

    @Test
    fun plainTextConfigErrorSurfacesAsFriendlyFailure() {
        val (_, result) = run {
            io.output("Error loading config.toml: `wire_api = \"chat\"` is no longer supported.\n")
            io.exit(1)
        }
        assertFalse(result.completed)
        assertTrue(result.failure.contains("configuration"))
    }

    @Test
    fun pathAliasWarningNoiseIsNotReportedAsTheFailure() {
        val (_, result) = run {
            io.output("WARNING: proceeding, even though we could not create PATH aliases: nope\n")
            io.exit(127)
        }
        assertTrue(result.failure, result.failure.contains("could not start"))
    }

    @Test
    fun reconnectNoticeFollowedByCompletionIsNotAStall() {
        val (_, result) = run {
            io.output("""{"type":"error","message":"Reconnecting... 1/5 (stream disconnected)"}""" + "\n")
            io.output(CodexFixtures.text("text.jsonl"))
            io.exit(0)
        }
        assertTrue(result.completed)
    }

    @Test
    fun endlessReconnectingIsCutOffByTheWatchdog() {
        val (run, result) = run {
            io.output("""{"type":"error","message":"Reconnecting... 1/5 (stream disconnected)"}""" + "\n")
        }
        assertFalse(result.completed)
        assertTrue(result.failure, result.failure.contains("Network connection interrupted"))
        assertEquals(1, run.io.destroyCalls)
        assertFalse(run.io.alive)
    }

    @Test
    fun lingeringProcessAfterTurnCompletedIsTerminated() {
        val (run, result) = run { io.output(CodexFixtures.text("text.jsonl")) }
        assertTrue(result.completed)
        assertEquals(1, run.io.destroyCalls)
        assertEquals(0, run.io.forceKillCalls)
    }

    @Test
    fun processIgnoringSigtermIsForceKilled() {
        val (run, result) = run {
            io.output(CodexFixtures.text("text.jsonl"))
            io.exitOnDestroy = false
        }
        assertTrue(result.completed)
        assertEquals(1, run.io.forceKillCalls)
    }

    @Test
    fun unkillableProcessIsAbandonedInsteadOfHangingTheRunner() {
        val (run, result) = run {
            io.output(CodexFixtures.text("text.jsonl"))
            io.exitOnDestroy = false
            io.exitOnForceKill = false
        }
        assertTrue(result.completed)
        assertTrue(run.io.forceKillCalls >= 1)
        assertTrue(run.io.alive)
    }

    @Test
    fun stopRequestInterruptsTheProcess() {
        val (run, result) = run {
            io.output("""{"type":"turn.started"}""" + "\n")
            io.exitOnInterrupt = true
            stop = true
        }
        assertFalse(result.completed)
        assertEquals("Stopped by user", result.failure)
        assertEquals(1, run.io.interruptCalls)
        assertEquals(0, run.io.forceKillCalls)
    }

    @Test
    fun stopIgnoredByTheProcessEscalatesToForceKill() {
        val (run, result) = run {
            io.output("""{"type":"turn.started"}""" + "\n")
            stop = true
        }
        assertEquals("Stopped by user", result.failure)
        assertEquals(1, run.io.interruptCalls)
        assertEquals(1, run.io.forceKillCalls)
    }

    @Test
    fun stopRequestedWhileACommandIsRunning() {
        val (run, result) = run {
            io.output(CodexFixtures.text("shell.jsonl").lines().take(4).joinToString("\n") + "\n")
            io.exitOnInterrupt = true
            onEvent = { if (it is CodexEvent.CommandStarted) stop = true }
        }
        assertEquals("Stopped by user", result.failure)
        assertTrue(run.events.any { it is CodexEvent.CommandStarted })
    }

    @Test
    fun promptWriteFailureIsReported() {
        val (_, result) = run {
            io.writeFailure = IOException("broken pipe")
            io.exitOnClose = 1
        }
        assertFalse(result.completed)
        assertTrue(result.failure, result.failure.contains("prompt"))
    }

    @Test
    fun throwingConsumerDoesNotAbortTheRun() {
        val io = FakeCodexIo().apply { output(CodexFixtures.text("text.jsonl")); exit(0) }
        val events = mutableListOf<CodexEvent>()
        val result = runBlocking {
            withTimeout(20_000) {
                CodexTurnRunner(
                    io = io,
                    prompt = "p",
                    emit = { events += it; if (it is CodexEvent.AgentMessage) throw IllegalStateException("ui exploded") },
                    config = fast,
                ).run()
            }
        }
        assertTrue(result.completed)
        assertTrue(events.any { it is CodexEvent.TurnCompleted })
    }

    @Test
    fun readFailureStopsTheProcessAndReportsIt() {
        val (run, result) = run {
            io.output("""{"type":"turn.started"}""" + "\n")
            io.readFailure = IOException("disk gone")
        }
        assertFalse(result.completed)
        assertEquals("Could not read Codex output.", result.failure)
        assertTrue(run.io.destroyCalls >= 1)
    }

    @Test
    fun unreadableCaptureFileDoesNotSpinForever() {
        val (run, result) = run {
            io.output("""{"type":"turn.started"}""" + "\n")
            io.readReturnsZero = true
        }
        assertFalse(result.completed)
        assertTrue(run.io.destroyCalls >= 1)
    }

    @Test
    fun oversizedLineIsSkippedAndTheTurnStillCompletes() {
        val (run, result) = run {
            io.output("x".repeat(20_000) + "\n")
            io.output(CodexFixtures.text("text.jsonl"))
            io.exit(0)
        }
        assertTrue(result.completed)
        assertTrue(run.events.any { it is CodexEvent.Diagnostic })
    }

    @Test
    fun randomBinaryGarbageNeverCrashesTheRunner() {
        val random = Random(42)
        val garbage = ByteArray(200_000).also(random::nextBytes)
        val (_, result) = run {
            io.outputBytes(garbage)
            io.outputBytes("\n".toByteArray())
            io.output(CodexFixtures.text("text.jsonl"))
            io.exit(0)
        }
        assertTrue(result.failure, result.completed)
    }

    @Test
    fun cancellationKillsTheProcess() {
        val io = FakeCodexIo()
        runBlocking {
            val job = launch {
                CodexTurnRunner(io = io, prompt = "p", emit = {}, config = fast).run()
            }
            delay(50)
            job.cancel()
            job.join()
        }
        assertTrue(io.forceKillCalls >= 1)
    }
}

class CodexBridgeHelpersTest {
    @get:Rule val folder = TemporaryFolder()

    private fun authFile(rootfs: java.io.File) = java.io.File(rootfs, "root/.codex/auth.json")

    @Test
    fun missingCredentialsAreNotSignedIn() {
        assertFalse(CodexRuntimeBridge.hasChatGptCredentials(folder.root))
    }

    @Test
    fun emptyOrPlaceholderCredentialFileIsNotSignedIn() {
        authFile(folder.root).apply { parentFile!!.mkdirs(); writeText("") }
        assertFalse(CodexRuntimeBridge.hasChatGptCredentials(folder.root))
        authFile(folder.root).writeText("{}")
        assertFalse(CodexRuntimeBridge.hasChatGptCredentials(folder.root))
    }

    @Test
    fun credentialFileCountsAsSignedIn() {
        authFile(folder.root).apply { parentFile!!.mkdirs(); writeText("""{"auth_mode":"chatgpt"}""") }
        assertTrue(CodexRuntimeBridge.hasChatGptCredentials(folder.root))
    }

    @Test
    fun directoryNamedLikeTheCredentialFileIsNotSignedIn() {
        authFile(folder.root).mkdirs()
        assertFalse(CodexRuntimeBridge.hasChatGptCredentials(folder.root))
    }
}
