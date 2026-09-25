package com.jarves.mh.runtime

import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import com.jarves.mh.model.ModelQuota
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AntigravityGatewayServerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createMockAccountManager(
        accounts: List<AntigravityAccount>,
        tokens: Map<String, String> = emptyMap(),
    ): AntigravityAccountManager {
        val root = tempDir.newFolder("accounts-${System.nanoTime()}")
        tokens.forEach { (accountId, token) ->
            val accDir = File(root, "$accountId/.gemini/antigravity-cli").apply { mkdirs() }
            File(accDir, "antigravity-oauth-token").writeText(
                JSONObject().put("token", JSONObject().put("access_token", token)).toString()
            )
        }
        var currentAccounts = accounts
        return AntigravityAccountManager(
            customAccountsDir = root,
            loadAccountsOverride = { currentAccounts },
            saveAccountsOverride = { currentAccounts = it },
            strategyOverride = { AntigravityLoadBalancingStrategy.ROUND_ROBIN },
        )
    }

    @Test
    fun protocolAdapterTranslatesAnthropicMessagesToGemini() {
        val anthropic = JSONObject()
            .put("model", "claude-sonnet-4-6")
            .put("system", "You are an expert developer.")
            .put("max_tokens", 4096)
            .put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 2048))
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "user").put("content", "Write a python script"))
                put(JSONObject().put("role", "assistant").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "thinking").put("thinking", "Planning script..."))
                    put(JSONObject().put("type", "text").put("text", "Sure, here it is"))
                    put(JSONObject().put("type", "tool_use").put("id", "call_123").put("name", "WriteFile").put("input", JSONObject().put("path", "main.py")))
                }))
                put(JSONObject().put("role", "user").put("content", JSONArray().apply {
                    put(JSONObject().put("type", "tool_result").put("tool_use_id", "call_123").put("content", "File written successfully"))
                }))
            })
            .put("tools", JSONArray().apply {
                put(JSONObject()
                    .put("name", "WriteFile")
                    .put("description", "Writes file")
                    .put("input_schema", JSONObject().put("type", "object").put("properties", JSONObject().put("path", JSONObject().put("type", "string")))))
            })

        val gemini = AntigravityProtocolAdapter.toGeminiRequest(anthropic, WireFormat.ANTHROPIC)

        // Model aliasing: claude-sonnet-4-6 maps to claude-3-7-sonnet
        assertEquals("claude-3-7-sonnet", gemini.getString("model"))

        // System instructions
        val systemInstruction = gemini.getJSONObject("systemInstruction")
        assertEquals("You are an expert developer.", systemInstruction.getJSONArray("parts").getJSONObject(0).getString("text"))

        // Generation config
        val genConfig = gemini.getJSONObject("generationConfig")
        assertEquals(4096, genConfig.getInt("maxOutputTokens"))
        assertEquals(2048, genConfig.getJSONObject("thinkingConfig").getInt("thinkingBudget"))

        // Contents
        val contents = gemini.getJSONArray("contents")
        assertTrue(contents.length() >= 2)
        assertEquals("user", contents.getJSONObject(0).getString("role"))
        assertEquals("model", contents.getJSONObject(1).getString("role"))

        // Tools
        val tools = gemini.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("WriteFile", tools.getJSONObject(0).getString("name"))
    }

    @Test
    fun protocolAdapterTranslatesOpenAiChatToGemini() {
        val openAi = JSONObject()
            .put("model", "deepseek-v4-flash")
            .put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", "System prompt"))
                put(JSONObject().put("role", "user").put("content", "Hello world"))
            })
            .put("max_tokens", 1024)

        val gemini = AntigravityProtocolAdapter.toGeminiRequest(openAi, WireFormat.OPENAI)

        // deepseek-v4-flash maps to gemini-3.8-flash
        assertEquals("gemini-3.8-flash", gemini.getString("model"))
        assertEquals("System prompt", gemini.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(1024, gemini.getJSONObject("generationConfig").getInt("maxOutputTokens"))
    }

    @Test
    fun gatewayBindsToLoopbackOnly() {
        val accountMgr = createMockAccountManager(emptyList())
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val url = URL(gateway.url)
            assertEquals("127.0.0.1", url.host)
            assertTrue(url.port > 0)
        }
    }

    @Test
    fun countTokensEndpointReturnsApproximateTokens() {
        val accountMgr = createMockAccountManager(emptyList())
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val conn = (URL("${gateway.url}/v1/messages/count_tokens").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
            }
            conn.outputStream.write("1234567890123456".toByteArray(Charsets.UTF_8))
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            assertEquals(5, json.getInt("input_tokens")) // 16 / 4 + 1 = 5
        }
    }

    @Test
    fun modelsCatalogReturnsSupportedModelsWithQuota() {
        val accounts = listOf(
            AntigravityAccount(
                id = "acc-1",
                email = "user@example.com",
                modelQuotas = mapOf("gemini-3.8-pro" to ModelQuota(remainingFraction = 0.85f)),
                isPrimary = true,
            ),
        )
        val accountMgr = createMockAccountManager(accounts)
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val conn = (URL("${gateway.url}/v1/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
            }
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val data = json.getJSONArray("data")
            assertTrue(data.length() >= 4)
            val geminiPro = (0 until data.length()).map { data.getJSONObject(it) }.first { it.getString("id") == "gemini-3.8-pro" }
            assertEquals("85%", geminiPro.optString("quota_percentage"))
        }
    }

    @Test
    fun unauthenticatedStateReturnsActionableError() {
        val accountMgr = createMockAccountManager(emptyList()) // No accounts signed in
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val conn = (URL("${gateway.url}/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.write("{\"model\":\"gemini-3.8-pro\",\"messages\":[]}".toByteArray(Charsets.UTF_8))
            assertEquals(401, conn.responseCode)
            val errBody = conn.errorStream.bufferedReader().use { it.readText() }
            val json = JSONObject(errBody)
            assertEquals("error", json.getString("type"))
            assertTrue(json.getJSONObject("error").getString("message").contains("Google account not signed in"))
        }
    }

    @Test
    fun protocolAdapterBuildsCloudCodePaUpstreamPayload() {
        val geminiReq = JSONObject()
            .put("model", "claude-3-7-sonnet")
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Hello")))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Act as assistant"))))

        val upstream = AntigravityProtocolAdapter.toUpstreamPayload(geminiReq)

        assertEquals("", upstream.getString("project"))
        assertEquals("claude-sonnet-4-6", upstream.getString("model"))
        assertTrue(upstream.has("request"))

        val innerReq = upstream.getJSONObject("request")
        assertFalse(innerReq.has("model"))
        assertTrue(innerReq.has("contents"))
        assertTrue(innerReq.has("systemInstruction"))
        assertEquals("Act as assistant", innerReq.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))

        // Test model aliases
        assertEquals("claude-sonnet-4-6", AntigravityProtocolAdapter.toUpstreamModel("claude-3-5-sonnet"))
        assertEquals("claude-sonnet-4-6", AntigravityProtocolAdapter.toUpstreamModel("claude-sonnet-4-6"))
        assertEquals("claude-opus-4-6-thinking", AntigravityProtocolAdapter.toUpstreamModel("claude-opus-4-6"))
        assertEquals("gemini-pro-agent", AntigravityProtocolAdapter.toUpstreamModel("gemini-3.8-pro"))
        assertEquals("gemini-3.8-flash-tiered", AntigravityProtocolAdapter.toUpstreamModel("gemini-3.8-flash"))
        assertEquals("gemini-2.5-flash", AntigravityProtocolAdapter.toUpstreamModel("gemini-2.5-flash"))
    }

    @Test
    fun protocolAdapterUnwrapsUpstreamCloudCodePaResponse() {
        val rawResponse = JSONObject()
            .put("candidates", JSONArray().apply {
                put(JSONObject()
                    .put("content", JSONObject().put("role", "model").put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "Response from Cloud Code PA"))
                    }))
                    .put("finishReason", "STOP"))
            })
            .put("usageMetadata", JSONObject().put("promptTokenCount", 15).put("candidatesTokenCount", 7))

        // Cloud Code PA wraps inside "response": { ... }
        val upstreamWrapped = JSONObject().put("response", rawResponse)

        val anthropic = AntigravityProtocolAdapter.toAnthropicResponse(upstreamWrapped, "claude-3-7-sonnet")
        assertEquals("message", anthropic.getString("type"))
        assertEquals("Response from Cloud Code PA", anthropic.getJSONArray("content").getJSONObject(0).getString("text"))
        assertEquals(15, anthropic.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(7, anthropic.getJSONObject("usage").getInt("output_tokens"))

        val parsedChunks = AntigravityProtocolAdapter.parseGeminiChunk(upstreamWrapped)
        assertEquals(1, parsedChunks.size)
        assertEquals("Response from Cloud Code PA", parsedChunks[0].text)

        val openAi = AntigravityProtocolAdapter.toOpenAiResponse(upstreamWrapped, "deepseek-v4-flash")
        assertEquals("chat.completion", openAi.getString("object"))
        assertEquals("Response from Cloud Code PA", openAi.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"))
    }

    @Test
    fun parseGeminiChunkHandlesThoughtBooleanProperly() {
        val nonThinkingPayload = JSONObject()
            .put("candidates", JSONArray().apply {
                put(JSONObject().put("content", JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("thought", false).put("text", "Actual answer without thinking"))
                })))
            })
        val nonThinkingParts = AntigravityProtocolAdapter.parseGeminiChunk(nonThinkingPayload)
        assertEquals(1, nonThinkingParts.size)
        assertEquals("Actual answer without thinking", nonThinkingParts[0].text)
        assertNull(nonThinkingParts[0].thinking)

        val thinkingPayload = JSONObject()
            .put("candidates", JSONArray().apply {
                put(JSONObject().put("content", JSONObject().put("parts", JSONArray().apply {
                    put(JSONObject().put("thought", true).put("text", "Internal chain of thought"))
                })))
            })
        val thinkingParts = AntigravityProtocolAdapter.parseGeminiChunk(thinkingPayload)
        assertEquals(1, thinkingParts.size)
        assertEquals("Internal chain of thought", thinkingParts[0].thinking)
        assertNull(thinkingParts[0].text)

        val anthropicResp = AntigravityProtocolAdapter.toAnthropicResponse(nonThinkingPayload, "claude-3-7-sonnet")
        val contentArr = anthropicResp.getJSONArray("content")
        assertEquals(1, contentArr.length())
        assertEquals("text", contentArr.getJSONObject(0).getString("type"))
        assertEquals("Actual answer without thinking", contentArr.getJSONObject(0).getString("text"))
    }

    @Test
    fun nonStreamingInferenceReturnsValidAnthropicResponse() {
        val accounts = listOf(
            AntigravityAccount(id = "acc-1", email = "test@example.com", isPrimary = true),
        )
        val tokens = mapOf("acc-1" to "token-xyz")
        val accountMgr = createMockAccountManager(accounts, tokens)

        val geminiResponse = JSONObject()
            .put("candidates", JSONArray().apply {
                put(JSONObject()
                    .put("content", JSONObject().put("role", "model").put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "Hello from Antigravity!"))
                    }))
                    .put("finishReason", "STOP"))
            })
            .put("usageMetadata", JSONObject().put("promptTokenCount", 10).put("candidatesTokenCount", 6))

        // Wrap as Cloud Code PA returns
        val upstreamWrapped = JSONObject().put("response", geminiResponse)

        AntigravityGatewayServer(
            accountManager = accountMgr,
            transportOverride = { url, method, headers, body ->
                assertEquals("Bearer token-xyz", headers["Authorization"])
                val sentJson = JSONObject(body)
                assertEquals("", sentJson.getString("project"))
                assertEquals("gemini-pro-agent", sentJson.getString("model"))
                assertTrue(sentJson.has("request"))
                assertTrue(sentJson.getJSONObject("request").has("contents"))
                AntigravityGatewayServer.GatewayHttpResult(200, upstreamWrapped.toString())
            },
        ).use { gateway ->
            gateway.start()
            val conn = (URL("${gateway.url}/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.write("{\"model\":\"gemini-3.8-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}".toByteArray(Charsets.UTF_8))
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            assertEquals("message", json.getString("type"))
            assertEquals("assistant", json.getString("role"))
            assertEquals("end_turn", json.getString("stop_reason"))
            val content = json.getJSONArray("content").getJSONObject(0)
            assertEquals("text", content.getString("type"))
            assertEquals("Hello from Antigravity!", content.getString("text"))
        }
    }

    @Test
    fun multiAccountMidTurnFailoverOn429RetriesNextAccount() {
        val accounts = listOf(
            AntigravityAccount(id = "acc-1", email = "primary@example.com", isPrimary = true),
            AntigravityAccount(id = "acc-2", email = "backup@example.com", isPrimary = false),
        )
        val tokens = mapOf("acc-1" to "token-1", "acc-2" to "token-2")
        val accountMgr = createMockAccountManager(accounts, tokens)

        val geminiSuccess = JSONObject()
            .put("candidates", JSONArray().apply {
                put(JSONObject()
                    .put("content", JSONObject().put("role", "model").put("parts", JSONArray().apply {
                        put(JSONObject().put("text", "Failover success!"))
                    }))
                    .put("finishReason", "STOP"))
            })

        val receivedTokens = mutableListOf<String>()
        AntigravityGatewayServer(
            accountManager = accountMgr,
            transportOverride = { url, method, headers, body ->
                val auth = headers["Authorization"].orEmpty().removePrefix("Bearer ")
                receivedTokens.add(auth)
                if (auth == "token-1") {
                    AntigravityGatewayServer.GatewayHttpResult(429, "Quota exhausted")
                } else {
                    AntigravityGatewayServer.GatewayHttpResult(200, geminiSuccess.toString())
                }
            },
        ).use { gateway ->
            gateway.start()
            val conn = (URL("${gateway.url}/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.write("{\"model\":\"gemini-3.8-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}".toByteArray(Charsets.UTF_8))

            // The turn should succeed without failing the caller!
            assertEquals(200, conn.responseCode)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            assertEquals("Failover success!", json.getJSONArray("content").getJSONObject(0).getString("text"))

            // Verify both accounts were used in order
            assertEquals(listOf("token-1", "token-2"), receivedTokens)

            // Verify acc-1 was marked quota exhausted
            val acc1 = accountMgr.getAccount("acc-1")
            assertEquals(AntigravityAccountStatus.QUOTA_EXHAUSTED, acc1?.status)
        }
    }
}
