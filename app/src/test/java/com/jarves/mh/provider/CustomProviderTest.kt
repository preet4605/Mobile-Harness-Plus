package com.jarves.mh.provider

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.runtime.DshRouteMapper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList

class CustomProviderTest {
    private val servers = mutableListOf<ServerSocket>()
    @After fun stop() = servers.forEach { runCatching { it.close() } }

    private data class Seen(val path: String, val auth: String?, val body: String)

    /** Minimal one-shot-per-connection HTTP/1.1 fake provider. */
    private fun fake(status: Int, response: String, seen: MutableList<Seen>): String {
        val server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    val input = socket.getInputStream().bufferedReader()
                    val path = input.readLine().split(" ")[1]
                    var auth: String? = null
                    var length = 0
                    while (true) {
                        val line = input.readLine().orEmpty()
                        if (line.isEmpty()) break
                        val (k, v) = line.split(":", limit = 2).let { it[0].lowercase() to it.getOrElse(1) { "" }.trim() }
                        if (k == "authorization") auth = v
                        if (k == "content-length") length = v.toInt()
                    }
                    val buf = CharArray(length)
                    var read = 0
                    while (read < length) read += input.read(buf, read, length - read)
                    seen += Seen(path, auth, String(buf))
                    val bytes = response.toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes)
                        flush()
                    }
                }
                runCatching { socket.close() }
            }
        }
        return "http://127.0.0.1:${server.localPort}"
    }

    private fun custom(url: String, api: String = "openai-completions", model: String = "my-model", id: String = "abc-123") =
        ProviderProfile(ProviderKind.CUSTOM, url, model, dshApi = api, profileId = id)

    // 1, 16: built-in DeepSeek is untouched
    @Test fun builtInDeepSeekRouteUnchanged() {
        val route = DshRouteMapper.forProfile(ProviderProfile(ProviderKind.DEEPSEEK))
        assertEquals("deepseek-official", route.name)
        assertEquals("DEEPSEEK_API_KEY", route.keyEnv)
        assertNull(route.custom)
    }

    // 2, 3, 4
    @Test fun customRouteUsesNormalizedUrlProtocolAndModel() {
        val route = DshRouteMapper.forProfile(custom("api.example.com/v1/chat/completions/", api = "anthropic-messages", model = "m-x"))
        assertEquals("https://api.example.com/v1", route.custom!!.baseUrl)
        assertEquals("openai-completions", route.custom!!.api)
        assertEquals("m-x", route.defaultModel)
        assertTrue(route.name.startsWith("mh-custom-"))
    }

    @Test fun responsesSuffixIsNotDuplicated() {
        val n = ProviderEndpointNormalizer.normalize("https://h.example/v1/responses", "anthropic-messages")
        assertEquals(NormalizedEndpoint("https://h.example/v1", "openai-responses"), n)
    }

    @Test fun plainBaseKeepsV1AndConfiguredApi() {
        assertEquals(NormalizedEndpoint("https://h.example/v1", "openai-completions"),
            ProviderEndpointNormalizer.normalize(" https://h.example/v1/ ", "openai-completions"))
        assertEquals("http://localhost:8080", ProviderEndpointNormalizer.normalize("http://localhost:8080", "").baseUrl)
    }

    @Test fun legacyCustomRouteNameAndVaultIdUnchanged() {
        val legacy = ProviderProfile(ProviderKind.CUSTOM, "https://x/v1", "m")
        assertEquals("mh-custom", DshRouteMapper.forProfile(legacy).name)
        assertEquals("CUSTOM", legacy.secretId)
    }

    // 5, 6/7 (non-streaming validation request), model reaches wire, auth reaches endpoint
    @Test fun validationSendsAuthModelAndCorrectPath() = runBlocking {
        val seen = CopyOnWriteArrayList<Seen>()
        val url = fake(200, """{"choices":[{"message":{"content":"OK"}}]}""", seen)
        val result = ProviderApiClient().validate(url + "/v1", "my-model", "sk-secret-1234", ProviderProtocol.OPENAI_CHAT, emptyList())
        assertTrue(result is ConnectionValidation.Success)
        assertEquals("/v1/chat/completions", seen.single().path)
        assertEquals("Bearer sk-secret-1234", seen.single().auth)
        assertTrue(seen.single().body.contains("\"my-model\""))
    }

    // 10: permanent 4xx classified, no fallback
    @Test fun permanentErrorsStop() {
        listOf("HTTP 404 not found", "model not found", "invalid request body", "HTTP 400").forEach {
            assertEquals(it, ProviderFailureClass.PERMANENT, ProviderFailureClassifier.classify(it))
        }
        val step = ProviderFallbackPlanner.next(ProviderFailureClass.PERMANENT, custom("u"), listOf("k2"), listOf(profile("B")))
        assertNull(step)
    }

    // 11
    @Test fun transientErrorsFallBackToNextProfileNotNextKey() {
        listOf("HTTP 503", "ECONNRESET", "Read timed out", "Unable to resolve host", "HTTP 502 bad gateway").forEach {
            assertEquals(it, ProviderFailureClass.TRANSIENT, ProviderFailureClassifier.classify(it))
        }
        val b = profile("B")
        val step = ProviderFallbackPlanner.next(ProviderFailureClass.TRANSIENT, custom("u"), listOf("k2"), listOf(b))
        assertEquals(ProviderFallbackPlanner.Step.NextProfile(b), step)
    }

    // 8, 9
    @Test fun keyFailureTriesAlternateKeyThenNextProfile() {
        val b = profile("B")
        assertEquals(ProviderFallbackPlanner.Step.NextKey("k2"),
            ProviderFallbackPlanner.next(ProviderFailureClass.KEY, custom("u"), listOf("k2"), listOf(b)))
        assertEquals(ProviderFallbackPlanner.Step.NextProfile(b),
            ProviderFallbackPlanner.next(ProviderFailureClass.KEY, custom("u"), emptyList(), listOf(b)))
        assertNull(ProviderFallbackPlanner.next(ProviderFailureClass.KEY, custom("u"), emptyList(), emptyList()))
    }

    // 12, 13
    @Test fun disabledSkippedAndPriorityDeterministic() {
        val a = profile("A", priority = 5)
        val b = profile("B", priority = 1)
        val c = profile("C", priority = 1)
        val off = profile("D", priority = 0, enabled = false)
        val order = ProviderFallbackPlanner.orderedProfiles(a.id, listOf(c, off, b, a))
        assertEquals(listOf(a.id) + listOf(b.id, c.id).sorted(), order.map { it.id })
        assertFalse(order.any { it.id == off.id })
        assertTrue(ProviderFallbackPlanner.orderedProfiles(off.id, listOf(off, a)).none { it.id == off.id })
        assertEquals(order, ProviderFallbackPlanner.orderedProfiles(a.id, listOf(a, b, c, off)))
    }

    // 14
    @Test fun editingProfileKeepsIdAndDoesNotMutateSnapshot() {
        val original = profile("A")
        val snapshot = original.toProviderProfile()
        val edited = original.copy(name = "Renamed", baseUrl = "https://other", model = "other")
        assertEquals(original.id, edited.id)
        assertEquals(original.secretId, edited.secretId)
        assertEquals(original.baseUrl, snapshot.baseUrl)
        assertEquals(original.id, snapshot.profileId)
        assertNotEquals(edited.baseUrl, snapshot.baseUrl)
    }

    @Test fun profilesRoundTripThroughJson() {
        val list = listOf(profile("A", priority = 3), profile("B", enabled = false))
        assertEquals(list, CustomProviderProfile.listFromJson(CustomProviderProfile.listToJson(list)))
        assertTrue(CustomProviderProfile.listFromJson("not json").isEmpty())
        assertFalse(CustomProviderProfile.listToJson(list).contains("apiKey"))
    }

    @Test fun genericV1InfersOnlyWhenProtocolIsUnspecified() {
        val n1 = ProviderEndpointNormalizer.normalize("https://api.example.com/v1", "anthropic-messages")
        assertEquals(NormalizedEndpoint("https://api.example.com/v1", "anthropic-messages"), n1)

        val n2 = ProviderEndpointNormalizer.normalize("https://api.example.com/v1", "")
        assertEquals(NormalizedEndpoint("https://api.example.com/v1", "openai-completions"), n2)

        val n3 = ProviderEndpointNormalizer.normalize("https://api.example.com/v1/chat/completions", "anthropic-messages")
        assertEquals(NormalizedEndpoint("https://api.example.com/v1", "openai-completions"), n3)
    }

    @Test fun aiqanaEndpointIsRevokedAndRejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointNormalizer.normalize("https://aiqana.com/v1", "anthropic-messages")
        }
    }

    @Test fun customProfileFromJsonPreservesExplicitAnthropicProtocol() {
        val json = org.json.JSONObject()
            .put("id", "profile-1")
            .put("name", "Generic Gateway")
            .put("baseUrl", "https://api.example.com/v1")
            .put("model", "qwen2.5-coder-32b-instruct")
            .put("dshApi", "anthropic-messages")
        val profile = CustomProviderProfile.fromJson(json)
        org.junit.Assert.assertNotNull(profile)
        assertEquals("anthropic-messages", profile!!.dshApi)
        assertEquals("anthropic-messages", profile.effectiveDshApi())
    }

    @Test fun aiqanaProfileIsRevokedFromJson() {
        val json = org.json.JSONObject()
            .put("id", "profile-aiqana")
            .put("name", "AIQANA")
            .put("baseUrl", "https://aiqana.com/v1")
            .put("model", "qwen2.5-coder-32b-instruct")
        org.junit.Assert.assertNull(CustomProviderProfile.fromJson(json))
    }

    // 15
    @Test fun secretsAreRedacted() {
        val text = "HTTP 401 Authorization: Bearer sk_live_12345678abcdef key=sk-live-ZZZZZZZZ apiKey: hunter2hunter2 raw-SECRET-value sk_live_987654321"
        val out = ProviderFailureClassifier.redact(text, listOf("raw-SECRET-value"))
        listOf("sk_live_12345678abcdef", "sk-live-ZZZZZZZZ", "hunter2hunter2", "raw-SECRET-value", "sk_live_987654321").forEach {
            assertFalse(it, out.contains(it))
        }
    }

    @Test fun tokenHarborEndpointInfersOpenAiCompletionsOrAnthropicMessages() {
        val n1 = ProviderEndpointNormalizer.normalize("https://api.tokenharbor.ai/v1", "")
        assertEquals(NormalizedEndpoint("https://api.tokenharbor.ai/v1", "openai-completions"), n1)

        val n2 = ProviderEndpointNormalizer.normalize("https://api.tokenharbor.ai/v1/chat/completions", "")
        assertEquals(NormalizedEndpoint("https://api.tokenharbor.ai/v1", "openai-completions"), n2)

        val n3 = ProviderEndpointNormalizer.normalize("https://api.tokenharbor.ai/v1/messages", "")
        assertEquals(NormalizedEndpoint("https://api.tokenharbor.ai/v1", "anthropic-messages"), n3)
    }

    @Test fun tokenHarborProfileRoundTrip() {
        val th = profile("TokenHarbor").copy(
            baseUrl = "https://api.tokenharbor.ai/v1",
            model = "tokenharbor/claude-3-5-sonnet",
        )
        val list = listOf(th)
        val json = CustomProviderProfile.listToJson(list)
        val restored = CustomProviderProfile.listFromJson(json)
        assertEquals(1, restored.size)
        assertEquals("TokenHarbor", restored[0].name)
        assertEquals("https://api.tokenharbor.ai/v1", restored[0].baseUrl)
    }

    @Test fun tokenHarborProfilesPrunedWhenKeyDeleted() {
        val th = profile("TokenHarbor").copy(
            baseUrl = "https://api.tokenharbor.ai/v1",
            model = "tokenharbor/claude-3-5-sonnet",
        )
        val valid = profile("DeepSeek Direct").copy(
            baseUrl = "https://api.deepseek.com",
            model = "deepseek-chat",
        )
        val list = listOf(th, valid)
        val json = CustomProviderProfile.listToJson(list)
        val restored = CustomProviderProfile.listFromJson(json)
        val cleaned = restored.filterNot {
            it.name.contains("tokenharbor", ignoreCase = true) ||
            it.baseUrl.contains("tokenharbor", ignoreCase = true) ||
            it.model.contains("tokenharbor", ignoreCase = true)
        }
        assertEquals(1, cleaned.size)
        assertEquals("DeepSeek Direct", cleaned[0].name)
    }

    private fun profile(name: String, priority: Int = 100, enabled: Boolean = true) =
        CustomProviderProfile(name = name, baseUrl = "https://$name.example/v1", model = "m", priority = priority, enabled = enabled)
}
