package com.jarves.mh.runtime

import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import com.jarves.mh.model.ModelQuota
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoopbackGatewaySecurityTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun createMockAccountManager(
        accounts: List<AntigravityAccount> = emptyList(),
        tokens: Map<String, String> = emptyMap(),
    ): AntigravityAccountManager {
        val root = tempDir.newFolder("accounts-${System.nanoTime()}")
        tokens.forEach { (accountId, token) ->
            val accDir = File(root, "$accountId/.gemini/antigravity-cli").apply { mkdirs() }
            File(accDir, "antigravity-oauth-token").writeText(
                JSONObject().put("token", JSONObject().put("access_token", token)).toString(),
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

    private fun httpCall(
        baseUrl: String,
        method: String,
        path: String,
        authHeader: String? = null,
        body: String? = null,
    ): Pair<Int, String> {
        val conn = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            if (authHeader != null) {
                setRequestProperty("Authorization", authHeader)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.write(body.toByteArray(Charsets.UTF_8))
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text
    }

    // 1. Missing Authorization → rejected
    @Test
    fun missingAuthorizationIsRejected() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val (code, body) = httpCall(gateway.url, "GET", "/health")
            assertEquals(401, code)
            val json = JSONObject(body)
            assertEquals("error", json.getString("type"))
            assertEquals("authentication_error", json.getJSONObject("error").getString("type"))
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        LocalFormatGateway(profile, "upstream-provider-key").use { gateway ->
            gateway.start()
            val (code, body) = httpCall(gateway.url, "GET", "/health")
            assertEquals(401, code)
            val json = JSONObject(body)
            assertEquals("error", json.getString("type"))
            assertEquals("authentication_error", json.getJSONObject("error").getString("type"))
        }
    }

    // 2. Invalid Authorization → rejected
    @Test
    fun invalidAuthorizationIsRejected() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val invalidHeaders = listOf(
                "Basic dXNlcjpwYXNz",
                "Bearer",
                "Bearer ",
                "Token ${gateway.gatewaySecret}",
                "Bearer\t",
            )
            for (header in invalidHeaders) {
                val (code, _) = httpCall(gateway.url, "GET", "/health", authHeader = header)
                assertEquals("Expected 401 for header '$header'", 401, code)
            }
        }
    }

    // 3. Wrong token → rejected
    @Test
    fun wrongTokenIsRejected() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val (code, body) = httpCall(gateway.url, "GET", "/health", authHeader = "Bearer sk-ant-oat-wrong-token")
            assertEquals(401, code)
            val json = JSONObject(body)
            assertEquals("authentication_error", json.getJSONObject("error").getString("type"))
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        LocalFormatGateway(profile, "upstream-provider-key").use { gateway ->
            gateway.start()
            val (code, body) = httpCall(gateway.url, "GET", "/health", authHeader = "Bearer wrong-token")
            assertEquals(401, code)
            val json = JSONObject(body)
            assertEquals("authentication_error", json.getJSONObject("error").getString("type"))
        }
    }

    // 4. Correct token → accepted
    @Test
    fun correctTokenIsAccepted() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val (code, body) = httpCall(gateway.url, "GET", "/health", authHeader = "Bearer ${gateway.gatewaySecret}")
            assertEquals(200, code)
            assertTrue(JSONObject(body).getString("status") == "ok")
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        LocalFormatGateway(profile, "upstream-provider-key").use { gateway ->
            gateway.start()
            val (code, body) = httpCall(gateway.url, "GET", "/health", authHeader = "Bearer ${gateway.gatewaySecret}")
            assertEquals(200, code)
            assertTrue(JSONObject(body).getString("status") == "ok")
        }
    }

    // 5. /health and / require authentication
    @Test
    fun healthEndpointsRequireAuthentication() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            assertEquals(401, httpCall(gateway.url, "GET", "/health").first)
            assertEquals(401, httpCall(gateway.url, "GET", "/").first)
            assertEquals(401, httpCall(gateway.url, "GET", "/v1").first)
            assertEquals(200, httpCall(gateway.url, "GET", "/health", authHeader = "Bearer ${gateway.gatewaySecret}").first)
            assertEquals(200, httpCall(gateway.url, "GET", "/", authHeader = "Bearer ${gateway.gatewaySecret}").first)
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        LocalFormatGateway(profile, "key").use { gateway ->
            gateway.start()
            assertEquals(401, httpCall(gateway.url, "GET", "/health").first)
            assertEquals(401, httpCall(gateway.url, "GET", "/").first)
            assertEquals(200, httpCall(gateway.url, "GET", "/health", authHeader = "Bearer ${gateway.gatewaySecret}").first)
            assertEquals(200, httpCall(gateway.url, "GET", "/", authHeader = "Bearer ${gateway.gatewaySecret}").first)
        }
    }

    // 6. /models requires authentication
    @Test
    fun modelsEndpointRequiresAuthentication() {
        val accounts = listOf(
            AntigravityAccount(id = "acc-1", email = "test@example.com", isPrimary = true),
        )
        val accountMgr = createMockAccountManager(accounts)
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            assertEquals(401, httpCall(gateway.url, "GET", "/models").first)
            assertEquals(401, httpCall(gateway.url, "GET", "/v1/models").first)
            assertEquals(200, httpCall(gateway.url, "GET", "/v1/models", authHeader = "Bearer ${gateway.gatewaySecret}").first)
            assertEquals(200, httpCall(gateway.url, "GET", "/models", authHeader = "Bearer ${gateway.gatewaySecret}").first)
        }
    }

    // 7. /count_tokens requires authentication
    @Test
    fun countTokensEndpointRequiresAuthentication() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
            assertEquals(401, httpCall(gateway.url, "POST", "/count_tokens", body = body).first)
            assertEquals(401, httpCall(gateway.url, "POST", "/v1/messages/count_tokens", body = body).first)
            assertEquals(200, httpCall(gateway.url, "POST", "/v1/messages/count_tokens", authHeader = "Bearer ${gateway.gatewaySecret}", body = body).first)
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        LocalFormatGateway(profile, "key").use { gateway ->
            gateway.start()
            val body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"
            assertEquals(401, httpCall(gateway.url, "POST", "/count_tokens", body = body).first)
            assertEquals(401, httpCall(gateway.url, "POST", "/v1/messages/count_tokens", body = body).first)
            assertEquals(200, httpCall(gateway.url, "POST", "/count_tokens", authHeader = "Bearer ${gateway.gatewaySecret}", body = body).first)
        }
    }

    // 8. /messages requires authentication
    @Test
    fun messagesEndpointRequiresAuthentication() {
        var upstreamCalled = false
        val accounts = listOf(AntigravityAccount(id = "acc-1", email = "user@example.com", isPrimary = true))
        val accountMgr = createMockAccountManager(accounts, mapOf("acc-1" to "token-1"))
        AntigravityGatewayServer(
            accountManager = accountMgr,
            transportOverride = { _, _, _, _ ->
                upstreamCalled = true
                AntigravityGatewayServer.GatewayHttpResult(200, "{}")
            },
        ).use { gateway ->
            gateway.start()
            val body = "{\"model\":\"gemini-3.8-pro\",\"messages\":[]}"
            assertEquals(401, httpCall(gateway.url, "POST", "/messages", body = body).first)
            assertEquals(401, httpCall(gateway.url, "POST", "/v1/messages", body = body).first)
            assertFalse("Upstream should never be called when unauthenticated", upstreamCalled)
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM, baseUrl = "http://127.0.0.1:9")
        LocalFormatGateway(profile, "provider-api-key").use { gateway ->
            gateway.start()
            val body = "{\"model\":\"claude-sonnet-4-6\",\"messages\":[]}"
            assertEquals(401, httpCall(gateway.url, "POST", "/messages", body = body).first)
            assertEquals(401, httpCall(gateway.url, "POST", "/v1/messages", body = body).first)
        }
    }

    // 9. /chat/completions requires authentication where supported
    @Test
    fun chatCompletionsEndpointRequiresAuthentication() {
        var upstreamCalled = false
        val accounts = listOf(AntigravityAccount(id = "acc-1", email = "user@example.com", isPrimary = true))
        val accountMgr = createMockAccountManager(accounts, mapOf("acc-1" to "token-1"))
        AntigravityGatewayServer(
            accountManager = accountMgr,
            transportOverride = { _, _, _, _ ->
                upstreamCalled = true
                AntigravityGatewayServer.GatewayHttpResult(200, "{}")
            },
        ).use { gateway ->
            gateway.start()
            val body = "{\"model\":\"gemini-3.8-pro\",\"messages\":[]}"
            val (code1, body1) = httpCall(gateway.url, "POST", "/chat/completions", body = body)
            assertEquals(401, code1)
            val json1 = JSONObject(body1)
            assertEquals("authentication_error", json1.getJSONObject("error").getString("type"))

            val (code2, body2) = httpCall(gateway.url, "POST", "/v1/chat/completions", body = body)
            assertEquals(401, code2)
            val json2 = JSONObject(body2)
            assertEquals("authentication_error", json2.getJSONObject("error").getString("type"))

            assertFalse("Upstream PA must not be called", upstreamCalled)
        }
    }

    // 10. Existing legitimate authenticated requests still work
    @Test
    fun existingLegitimateAuthenticatedRequestsStillWork() {
        val accounts = listOf(AntigravityAccount(id = "acc-1", email = "user@example.com", isPrimary = true))
        val tokens = mapOf("acc-1" to "oauth-tok")
        val accountMgr = createMockAccountManager(accounts, tokens)

        val geminiResp = JSONObject().put("response", JSONObject().put("candidates", JSONArray().put(
            JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray().put(
                JSONObject().put("text", "Authenticated response!"),
            ))).put("finishReason", "STOP"),
        )).put("usageMetadata", JSONObject().put("promptTokenCount", 5).put("candidatesTokenCount", 3)))

        AntigravityGatewayServer(
            accountManager = accountMgr,
            transportOverride = { _, _, _, _ ->
                AntigravityGatewayServer.GatewayHttpResult(200, geminiResp.toString())
            },
        ).use { gateway ->
            gateway.start()
            val (code, body) = httpCall(
                gateway.url,
                "POST",
                "/v1/messages",
                authHeader = "Bearer ${gateway.gatewaySecret}",
                body = "{\"model\":\"gemini-3.8-pro\",\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}",
            )
            assertEquals(200, code)
            val json = JSONObject(body)
            assertEquals("message", json.getString("type"))
            assertEquals("Authenticated response!", json.getJSONArray("content").getJSONObject(0).getString("text"))
        }
    }

    // 11. Two gateway instances receive different secrets
    @Test
    fun twoGatewayInstancesReceiveDifferentSecrets() {
        val accountMgr = createMockAccountManager()
        val g1 = AntigravityGatewayServer(accountMgr)
        val g2 = AntigravityGatewayServer(accountMgr)
        try {
            assertNotEquals(g1.gatewaySecret, g2.gatewaySecret)
            assertTrue("Secret should have sufficient entropy", g1.gatewaySecret.length >= 64)
            assertTrue("Secret should have sufficient entropy", g2.gatewaySecret.length >= 64)
            assertTrue(g1.gatewaySecret.startsWith("sk-ant-oat-"))
            assertTrue(g2.gatewaySecret.startsWith("sk-ant-oat-"))
        } finally {
            g1.close()
            g2.close()
        }

        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        val f1 = LocalFormatGateway(profile, "key-1")
        val f2 = LocalFormatGateway(profile, "key-2")
        try {
            assertNotEquals(f1.gatewaySecret, f2.gatewaySecret)
            assertTrue(f1.gatewaySecret.startsWith("sk-ant-oat-"))
            assertTrue(f2.gatewaySecret.startsWith("sk-ant-oat-"))
        } finally {
            f1.close()
            f2.close()
        }
    }

    // 12. Provider API key is not accepted as the gateway secret
    @Test
    fun providerApiKeyIsNotAcceptedAsGatewaySecret() {
        val upstreamApiKey = "sk-upstream-secret-key-1234567890"
        val profile = ProviderProfile(ProviderKind.NVIDIA_NIM)
        LocalFormatGateway(profile, upstreamApiKey).use { gateway ->
            gateway.start()
            assertNotEquals(upstreamApiKey, gateway.gatewaySecret)

            // Attempting to authenticate to the local gateway using the upstream API key must fail!
            val (code, body) = httpCall(
                gateway.url,
                "GET",
                "/health",
                authHeader = "Bearer $upstreamApiKey",
            )
            assertEquals(401, code)
            val json = JSONObject(body)
            assertEquals("authentication_error", json.getJSONObject("error").getString("type"))
        }
    }

    // 13. Secret is never returned in an HTTP response
    @Test
    fun secretIsNeverReturnedInHttpResponse() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val endpoints = listOf(
                Pair("GET", "/health"),
                Pair("GET", "/models"),
                Pair("POST", "/count_tokens"),
                Pair("POST", "/messages"),
                Pair("POST", "/chat/completions"),
            )
            for ((m, p) in endpoints) {
                val (_, unauthBody) = httpCall(gateway.url, m, p)
                assertFalse("Secret leaked in $p 401 response", unauthBody.contains(gateway.gatewaySecret))

                val (_, wrongBody) = httpCall(gateway.url, m, p, authHeader = "Bearer wrong-token")
                assertFalse("Secret leaked in $p wrong token response", wrongBody.contains(gateway.gatewaySecret))
            }
        }
    }

    // 14. Secret is never written to logs/errors
    @Test
    fun secretIsNeverWrittenToLogsOrErrors() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val (_, body) = httpCall(gateway.url, "POST", "/v1/messages", authHeader = "Bearer invalid-attempt")
            val json = JSONObject(body)
            val errMsg = json.getJSONObject("error").getString("message")
            assertFalse(errMsg.contains(gateway.gatewaySecret))
            assertFalse(errMsg.contains("sk-ant-oat-"))
            assertEquals("Missing or invalid authorization token", errMsg)
        }
    }

    // 15. Gateway shutdown prevents further requests
    @Test
    fun gatewayShutdownPreventsFurtherRequests() {
        val accountMgr = createMockAccountManager()
        val gateway = AntigravityGatewayServer(accountMgr).start()
        val url = gateway.url
        val secret = gateway.gatewaySecret

        // Verify it works while running
        val (code, _) = httpCall(url, "GET", "/health", authHeader = "Bearer $secret")
        assertEquals(200, code)

        // Close gateway
        gateway.close()

        // Further requests must fail
        try {
            httpCall(url, "GET", "/health", authHeader = "Bearer $secret")
            fail("Expected connection to fail after gateway shutdown")
        } catch (e: Exception) {
            assertTrue(
                "Expected ConnectException or SocketException, got: ${e.javaClass.simpleName}",
                e is ConnectException || e is SocketException || e is IOException,
            )
        }
    }

    // 16. Existing gateway behavior and response formats remain compatible after authentication
    @Test
    fun existingGatewayBehaviorAndResponseFormatsRemainCompatible() {
        val accountMgr = createMockAccountManager()
        AntigravityGatewayServer(accountMgr).use { gateway ->
            gateway.start()
            val auth = "Bearer ${gateway.gatewaySecret}"

            // 1. /health
            val (hCode, hBody) = httpCall(gateway.url, "GET", "/health", authHeader = auth)
            assertEquals(200, hCode)
            val hJson = JSONObject(hBody)
            assertEquals("ok", hJson.getString("status"))
            assertEquals("antigravity-gateway", hJson.getString("service"))

            // 2. /v1/messages/count_tokens
            val (tCode, tBody) = httpCall(
                gateway.url,
                "POST",
                "/v1/messages/count_tokens",
                authHeader = auth,
                body = "1234567890123456",
            )
            assertEquals(200, tCode)
            val tJson = JSONObject(tBody)
            assertTrue(tJson.has("input_tokens"))
            assertEquals(5, tJson.getInt("input_tokens"))
        }
    }
}
