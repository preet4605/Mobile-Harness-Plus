package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DEEPSEEK_HARNESS_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.providersForAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class DshHeadlessParserTest {
    @Test
    fun emptyReasoningHeadingStartsSection() {
        assertEquals(DshLine.ReasoningHeading, DshHeadlessParser.parseLine("dsh: reasoning:"))
    }

    @Test
    fun inlineReasoningDeltaIsReasoning() {
        val parsed = DshHeadlessParser.parseLine("dsh: reasoning: checking the workspace")
        assertEquals(DshLine.Reasoning("checking the workspace"), parsed)
    }

    @Test
    fun errorDiagnosticIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: MISSING_CREDENTIAL: llm-deepseek: no API key")
        assertTrue(parsed is DshLine.Diagnostic)
        assertEquals("MISSING_CREDENTIAL: llm-deepseek: no API key", (parsed as DshLine.Diagnostic).text)
    }

    @Test
    fun authFailureIsDiagnostic() {
        val parsed = DshHeadlessParser.parseLine("dsh: AUTH: Authentication Fails, key is invalid")
        assertTrue(parsed is DshLine.Diagnostic)
    }

    @Test
    fun plainTextIsAnswer() {
        val parsed = DshHeadlessParser.parseLine("Here is the summary of your project.")
        assertEquals(DshLine.Answer("Here is the summary of your project."), parsed)
    }

    @Test
    fun whitespaceIsTrimmed() {
        val parsed = DshHeadlessParser.parseLine("   done   ")
        assertEquals(DshLine.Answer("done"), parsed)
    }
}

class DshSdkProtocolParserTest {
    private val parser = DshSdkProtocolParser("session-1")

    @Test
    fun parsesHandshakeAndLifecycle() {
        assertEquals(DshSdkProtocolEvent.Initialized, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"))
        assertEquals(DshSdkProtocolEvent.ShutdownAcknowledged, parser.parseLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}"))
        assertEquals(
            DshSdkProtocolEvent.Status(true),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "running"))),
        )
        assertEquals(
            DshSdkProtocolEvent.Status(false),
            parser.parseLine(notification("session.status", JSONObject().put("sessionId", "session-1").put("status", "idle"))),
        )
    }

    @Test
    fun accumulatesReasoningAndClosesItsBlock() {
        val first = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "Checking "),
                ),
            ),
        )
        val second = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", "files"),
                ),
            ),
        )
        val end = parser.parseLine(
            sessionEvent(
                "assistant/chunk",
                JSONObject().put("turn", 1).put("step", 2).put(
                    "chunk",
                    JSONObject().put("type", "block-end").put("index", 0).put(
                        "block",
                        JSONObject().put("type", "reasoning").put("text", "Checking files"),
                    ),
                ),
            ),
        )

        assertTrue(first is DshSdkProtocolEvent.Reasoning && first.startsNewBlock && first.text == "Checking ")
        assertTrue(second is DshSdkProtocolEvent.Reasoning && !second.startsNewBlock && second.text == "Checking files")
        assertTrue(end is DshSdkProtocolEvent.Reasoning && end.isFinal && end.text == "Checking files")
    }

    @Test
    fun parsesToolCallResultAndAssistantText() {
        val call = parser.parseLine(sessionEvent("tool/call", JSONObject()
            .put("callId", "call-1").put("name", "bash")
            .put("arguments", "{\"command\":\"pwd && ls\"}")))
        val resultBlock = JSONObject().put("type", "tool-result").put("toolCallId", "call-1")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "done")))
        val result = parser.parseLine(sessionEvent("tool/result", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(resultBlock)))))
        val answer = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Finished"),
            )))))

        assertTrue(call is DshSdkProtocolEvent.ToolStarted && call.name == "Bash" && call.detail == "pwd && ls")
        assertTrue(result is DshSdkProtocolEvent.ToolCompleted && result.name == "Bash" && result.summary == "done")
        assertEquals(DshSdkProtocolEvent.AssistantText("Finished"), answer)
    }

    @Test
    fun streamsTextDeltasAndDoesNotRepeatCompletedMessage() {
        val first = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "Hel"))))
        val second = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "text-delta").put("index", 0).put("text", "lo"))))
        val end = parser.parseLine(sessionEvent("assistant/chunk", JSONObject()
            .put("turn", 1).put("step", 1).put("chunk", JSONObject()
                .put("type", "block-end").put("index", 0).put("block", JSONObject()
                    .put("type", "text").put("text", "Hello")))))
        val completed = parser.parseLine(sessionEvent("assistant/message", JSONObject()
            .put("message", JSONObject().put("content", JSONArray().put(
                JSONObject().put("type", "text").put("text", "Hello"),
            )))))

        assertEquals(DshSdkProtocolEvent.AssistantText("Hel"), first)
        assertEquals(DshSdkProtocolEvent.AssistantText("lo"), second)
        assertEquals(DshSdkProtocolEvent.Ignored, end)
        assertEquals(DshSdkProtocolEvent.Ignored, completed)
    }

    @Test
    fun normalTurnEndCountsAsCompletedActivity() {
        val completed = parser.parseLine(
            sessionEvent(
                "turn/end",
                JSONObject().put("reason", JSONObject().put("kind", "completed")),
            ),
        )

        assertEquals(DshSdkProtocolEvent.TurnCompleted, completed)
    }

    private fun sessionEvent(type: String, data: JSONObject): String = notification(
        "session.event",
        JSONObject().put("sessionId", "session-1").put(
            "event",
            JSONObject().put("type", type).put("seq", 1).put("time", 1).put("data", data),
        ),
    )

    private fun notification(method: String, params: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", method)
        .put("params", params)
        .toString()
}

class DshRouteMapperTest {
    @Test
    fun deepseekUsesNativeRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals("deepseek-official", route.name)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertNull(route.custom)
    }

    @Test
    fun zenUsesFixedUrlAndResponsesProtocol() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.OPENCODE_ZEN))
        assertEquals("opencode-zen", route.name)
        assertEquals("openai-responses", route.custom?.api)
        assertEquals("https://opencode.ai/zen/v1", route.custom?.baseUrl)
    }

    @Test
    fun customHonorsDshApiChoice() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, baseUrl = "https://gw.example/v1", model = "m", dshApi = "openai-completions")
        val route = DshRouteMapper.forProfile(profile)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://gw.example/v1", route.custom?.baseUrl)
    }

    @Test
    fun nvidiaNimUsesFixedOpenAiCompletionsRoute() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.NVIDIA_NIM))
        assertEquals("nvidia-nim", route.name)
        assertEquals("openai-completions", route.custom?.api)
        assertEquals("https://integrate.api.nvidia.com/v1", route.custom?.baseUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun claudeSubscriptionIsRejected() {
        DshRouteMapper.forProfile(ProviderProfile(ProviderKind.CLAUDE))
    }
}

class AgentProviderPresetTest {
    @Test
    fun customGatewayUrlSuggestsProtocolWithoutRemovingManualChoice() {
        assertEquals("openai-completions", inferredDshApiForUrl("https://api.example.com/v1/"))
        assertEquals("anthropic-messages", inferredDshApiForUrl("https://api.example.com/anthropic"))
        assertEquals("openai-responses", inferredDshApiForUrl("https://api.example.com/v1/responses"))
    }

    @Test
    fun deepSeekHarnessValidationUsesSelectedCustomProtocol() {
        val profile = ProviderProfile(
            ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "model",
            dshApi = "openai-completions",
        )
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        // Claude Code now resolves the same configured CUSTOM API instead of always using the
        // kind's Anthropic default, so validation and runtime routing agree with the selection.
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
    }

    @Test
    fun customProtocolResolvesFromConfiguredApiForEveryAgent() {
        val expected = mapOf(
            "openai-completions" to ProviderProtocol.OPENAI_CHAT,
            "openai-responses" to ProviderProtocol.OPENAI_RESPONSES,
            "anthropic-messages" to ProviderProtocol.ANTHROPIC_GATEWAY,
        )
        expected.forEach { (api, protocol) ->
            val profile = ProviderProfile(ProviderKind.CUSTOM, "https://api.example.com/v1", "m", dshApi = api)
            assertEquals(api, protocol, providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE))
            assertEquals(api, protocol, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        }
    }

    @Test
    fun claudeRuntimeStartsFormatGatewayOnlyForOpenAiProtocols() {
        fun needsGateway(profile: ProviderProfile) = providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE) in
            setOf(ProviderProtocol.OPENAI_CHAT, ProviderProtocol.OPENAI_RESPONSES)

        val customProfile = ProviderProfile(ProviderKind.CUSTOM, "https://api.example.com/v1", "m", dshApi = "openai-completions")
        assertTrue(needsGateway(customProfile))
        assertTrue(needsGateway(customProfile.copy(dshApi = "openai-responses")))
        assertFalse(needsGateway(customProfile.copy(dshApi = "anthropic-messages")))
        assertTrue(needsGateway(ProviderProfile(ProviderKind.NVIDIA_NIM)))
    }

    @Test
    fun nonCustomClaudeProvidersKeepTheirFixedProtocol() {
        // dshApi must be ignored for Claude Code outside CUSTOM, even when it carries a stale value.
        assertEquals(
            ProviderProtocol.ANTHROPIC,
            providerProtocolForAgent(ProviderProfile(ProviderKind.ANTHROPIC, dshApi = "openai-completions"), AgentKind.CLAUDE_CODE),
        )
        assertEquals(
            ProviderKind.KIMI.protocol,
            providerProtocolForAgent(ProviderProfile(ProviderKind.KIMI, dshApi = "openai-responses"), AgentKind.CLAUDE_CODE),
        )
        assertEquals(
            ProviderProtocol.OPENAI_CHAT,
            providerProtocolForAgent(ProviderProfile(ProviderKind.NVIDIA_NIM), AgentKind.CLAUDE_CODE),
        )
    }

    @Test
    fun openCodeZenPresetIsLocked() {
        val zen = ProviderKind.OPENCODE_ZEN
        assertEquals("https://opencode.ai/zen/v1", zen.defaultBaseUrl)
        assertTrue(zen.fixedBaseUrl)
        assertTrue(zen.fixedProtocol)
        assertEquals("https://opencode.ai/zen/v1", ProviderProfile(zen).resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedUrl() {
        val profile = ProviderProfile(ProviderKind.OPENCODE_ZEN, baseUrl = "https://evil.example/", model = "x")
        assertEquals("https://opencode.ai/zen/v1", profile.resolvedBaseUrl)
    }

    @Test
    fun storedDriftCannotOverrideFixedProtocol() {
        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM, dshApi = "anthropic-messages")
        assertEquals(ProviderProtocol.OPENAI_CHAT, providerProtocolForAgent(profile, AgentKind.DEEPSEEK_HARNESS))
        assertEquals("openai-completions", DshRouteMapper.forProfile(profile).custom?.api)
    }

    @Test
    fun dshHarnessExcludesClaudeSubscription() {
        assertFalse(ProviderKind.CLAUDE in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.OPENCODE_ZEN in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.DEEPSEEK in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.NVIDIA_NIM in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.ANTIGRAVITY_SERVER in DEEPSEEK_HARNESS_PROVIDERS)
        assertTrue(ProviderKind.ANTIGRAVITY_SERVER in providersForAgent(AgentKind.CLAUDE_CODE))
        assertEquals(8, DEEPSEEK_HARNESS_PROVIDERS.size)
    }

    @Test
    fun openCodeZenIsOnlyShownForDeepSeekHarness() {
        assertTrue(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
        assertFalse(ProviderKind.OPENCODE_ZEN in providersForAgent(AgentKind.CLAUDE_CODE))
    }

    @Test
    fun agentKindsAreStable() {
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.valueOf("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.valueOf("DEEPSEEK_HARNESS"))
        assertEquals(AgentKind.ANTIGRAVITY, AgentKind.fromStored("antigravity"))
        assertEquals(AgentKind.CLAUDE_CODE, AgentKind.fromStored("CLAUDE_CODE"))
        assertEquals(AgentKind.DEEPSEEK_HARNESS, AgentKind.fromStored("DEEPSEEK_HARNESS"))
    }

    @Test
    fun nativeCacheDisabledForDshEnvironment() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))
        val envUnauthorized = DshRuntimeBridge.buildDshEnvironment(route, "secret-key-123", isExecutionAuthorized = false)
        assertEquals("1", envUnauthorized[DshRuntimeBridge.NARB_DISABLE_NATIVE_CACHE_ENV])
        assertEquals(DshRuntimeBridge.DSH_HOME_GUEST_PATH, envUnauthorized["DSH_HOME"])
        assertEquals("read-only", envUnauthorized["DSH_PERMISSION_MODE"])
        assertEquals("secret-key-123", envUnauthorized["DEEPSEEK_API_KEY"])

        val envAuthorized = DshRuntimeBridge.buildDshEnvironment(route, "secret-key-123", isExecutionAuthorized = true)
        assertEquals("danger-full-access", envAuthorized["DSH_PERMISSION_MODE"])
    }

    @Test
    fun staleLoaderCacheCleanupIsTargetedAndIdempotent() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "test-cache-cleanup-${System.nanoTime()}")
        tempDir.mkdirs()
        try {
            val stale1 = java.io.File(tempDir, "node-addon-native-custom-loader-1").apply { mkdirs() }
            val stale2 = java.io.File(tempDir, "node-addon-native-custom-loader-2").apply { mkdirs() }
            java.io.File(stale1, "dummy.node").writeText("corrupted")
            val unrelatedFile = java.io.File(tempDir, "important-data.txt").apply { writeText("keep me") }
            val unrelatedDir = java.io.File(tempDir, "unrelated-dir").apply { mkdirs() }

            val targets = listOf(tempDir)
            fun cleanTargets(): Int {
                var removed = 0
                targets.forEach { dir ->
                    dir.listFiles { file ->
                        file.name.startsWith("node-addon-native-custom-loader-")
                    }?.forEach { loaderDir ->
                        if (loaderDir.deleteRecursively()) removed++
                    }
                }
                return removed
            }

            val firstRun = cleanTargets()
            assertEquals(2, firstRun)
            assertFalse(stale1.exists())
            assertFalse(stale2.exists())
            assertTrue(unrelatedFile.exists())
            assertEquals("keep me", unrelatedFile.readText())
            assertTrue(unrelatedDir.exists())

            // Idempotent second run
            val secondRun = cleanTargets()
            assertEquals(0, secondRun)
            assertTrue(unrelatedFile.exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun dshHostPreparationSucceedsInCurrentRuntime() {
        val dshBinary = java.io.File("/usr/local/bin/dsh")
        if (!dshBinary.exists()) return // skip if dsh is not on host in this runner

        val pb = ProcessBuilder("/usr/local/bin/dsh", "--profile", "headless", "--help")
        pb.environment()[DshRuntimeBridge.NARB_DISABLE_NATIVE_CACHE_ENV] = "1"
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        assertEquals("dsh failed with output: $output", 0, exitCode)
        assertTrue(output.contains("Usage: dsh --profile headless") || output.contains("dsh"))
    }

    @Test
    fun cordisPatchAndLegacySettingsGeneratedForCustomRoute() {
        val profile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com/v1",
            model = "qwen2.5-coder-32b-instruct",
            dshApi = "openai-completions",
            profileId = "example-custom-123",
        )
        val route = DshRouteMapper.forProfile(profile)
        val patch = DshRuntimeBridge.buildDshCordisPatch(route, profile)
        assertTrue(patch.contains("- id: agent-default-model"))
        assertTrue(patch.contains("provider: ${route.name}"))
        assertTrue(patch.contains("model: 'qwen2.5-coder-32b-instruct'"))
        assertTrue(patch.contains("- id: llm-pi-ai"))
        assertTrue(patch.contains("api: openai-completions"))
        assertTrue(patch.contains("baseURL: 'https://api.example.com/v1'"))
        assertTrue(patch.contains("- id: 'qwen2.5-coder-32b-instruct'"))

        val legacy = DshRuntimeBridge.buildDshLegacySettings(route, profile)
        assertTrue(legacy.contains("agent-default-model:"))
        assertTrue(legacy.contains("llm-pi-ai:"))
        assertTrue(legacy.contains("api: openai-completions"))
        assertTrue(legacy.contains("baseURL: 'https://api.example.com/v1'"))
    }

    @Test
    fun writeDshSettingsCreatesCordisPatchAndLegacySettings() {
        val tempRootfs = java.io.File(System.getProperty("java.io.tmpdir", "/tmp"), "test-dsh-settings-${System.nanoTime()}")
        tempRootfs.mkdirs()
        try {
            val sdkDir = java.io.File(tempRootfs, "root/.dsh/profiles/sdk").apply { mkdirs() }
            val profile = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = "https://api.example.com/v1",
                model = "qwen2.5-coder-32b-instruct",
                dshApi = "openai-completions",
                profileId = "example-custom-123",
            )
            val route = DshRouteMapper.forProfile(profile)
            DshRuntimeBridge.writeDshSettings(tempRootfs, route, profile)

            val cordisPatchFile = java.io.File(tempRootfs, "root/.dsh/cordis.patch.yml")
            val sdkPatchFile = java.io.File(sdkDir, "cordis.patch.yml")
            val legacyFile = java.io.File(tempRootfs, "root/.dsh/settings.yaml")

            assertTrue(cordisPatchFile.isFile)
            assertTrue(sdkPatchFile.isFile)
            assertTrue(legacyFile.isFile)

            val patchContent = cordisPatchFile.readText()
            assertTrue(patchContent.contains("- id: agent-default-model"))
            assertTrue(patchContent.contains("baseURL: 'https://api.example.com/v1'"))
            assertEquals(patchContent, sdkPatchFile.readText())
        } finally {
            tempRootfs.deleteRecursively()
        }
    }
}
