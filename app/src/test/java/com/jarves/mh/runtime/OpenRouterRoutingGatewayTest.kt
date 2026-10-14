package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.net.SocketTimeoutException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenRouterRoutingGatewayTest {
    private fun connect(gateway: OpenRouterRoutingGateway): Socket = Socket("127.0.0.1", URL(gateway.url).port).apply {
        soTimeout = 2_000
    }

    private fun sendIncompleteRequest(socket: Socket) {
        socket.getOutputStream().write(("POST /v1/messages/count_tokens HTTP/1.1\r\n" +
            "x-api-key: launch-key\r\nContent-Length: 2\r\n\r\n{").toByteArray())
        socket.getOutputStream().flush()
    }

    @Test
    fun closingGatewayClosesAcceptedClientsWaitingForBody() {
        val gateway = OpenRouterRoutingGateway(ProviderProfile(ProviderKind.LLM_ROUTER), "launch-key").start()
        try {
            connect(gateway).use { client ->
                sendIncompleteRequest(client)
                client.soTimeout = 100
                try { client.getInputStream().read(); fail("Incomplete body should await input") }
                catch (_: SocketTimeoutException) { }
                gateway.close()
                client.soTimeout = 2_000
                assertEquals(-1, client.getInputStream().read())
            }
        } finally { gateway.close() }
    }

    @Test
    fun excessiveConcurrentClientsAreRejected() {
        OpenRouterRoutingGateway(ProviderProfile(ProviderKind.LLM_ROUTER), "launch-key").start().use { gateway ->
            val clients = mutableListOf<Socket>()
            try {
                repeat(8) {
                    val client = connect(gateway)
                    clients += client
                    sendIncompleteRequest(client)
                    client.soTimeout = 100
                    try { client.getInputStream().read(); fail("Incomplete body should await input") }
                    catch (_: SocketTimeoutException) { }
                }
                connect(gateway).use { rejected ->
                    assertEquals(-1, rejected.getInputStream().read())
                }
            } finally { clients.forEach { it.close() } }
        }
    }

    @Test
    fun excessiveHeadersAreRejectedBeforeDispatch() {
        OpenRouterRoutingGateway(ProviderProfile(ProviderKind.LLM_ROUTER), "launch-key").start().use { gateway ->
            connect(gateway).use { client ->
                val request = buildString {
                    append("POST /v1/messages/count_tokens HTTP/1.1\r\nx-api-key: launch-key\r\nContent-Length: 2\r\n")
                    repeat(101) { append("X-Header-$it: value\r\n") }
                    append("\r\n{}")
                }
                client.getOutputStream().write(request.toByteArray())
                client.getOutputStream().flush()
                val result = runCatching { client.getInputStream().read() }
                assertTrue("Oversized headers must close the client", result.getOrNull() == -1 ||
                    result.exceptionOrNull() is java.net.SocketException)
            }
        }
    }

    @Test
    fun routingPolicyIsAddedWithoutChangingTheRequest() {
        val source = JSONObject()
            .put("model", "anthropic/claude-sonnet-4.6")
            .put("stream", true)
        val profile = ProviderProfile(
            kind = ProviderKind.LLM_ROUTER,
            openRouterProviderOrder = "anthropic, amazon-bedrock, anthropic",
            openRouterAllowFallbacks = false,
        )

        val routed = applyOpenRouterRouting(source, profile)

        assertEquals("anthropic/claude-sonnet-4.6", routed.getString("model"))
        assertEquals(true, routed.getBoolean("stream"))
        val policy = routed.getJSONObject("provider")
        assertEquals(listOf("anthropic", "amazon-bedrock"), policy.getJSONArray("order").let { array ->
            (0 until array.length()).map(array::getString)
        })
        assertFalse(policy.getBoolean("allow_fallbacks"))
    }

    @Test
    fun blankProviderOrderLeavesAutomaticRoutingUntouched() {
        val source = JSONObject().put("model", "auto")

        applyOpenRouterRouting(source, ProviderProfile(ProviderKind.LLM_ROUTER))

        assertFalse(source.has("provider"))
    }

    @Test
    fun gatewayRejectsCallersWithoutTheLaunchKey() {
        val profile = ProviderProfile(
            kind = ProviderKind.LLM_ROUTER,
            baseUrl = "http://127.0.0.1:9",
            openRouterProviderOrder = "anthropic",
        )
        OpenRouterRoutingGateway(profile, "launch-key").start().use { gateway ->
            fun post(key: String?): Int {
                val connection = URL("${gateway.url}/v1/messages/count_tokens").openConnection() as HttpURLConnection
                return try {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    key?.let { connection.setRequestProperty("x-api-key", it) }
                    connection.outputStream.use { it.write("{}".toByteArray()) }
                    connection.responseCode
                } finally {
                    connection.disconnect()
                }
            }
            assertEquals(401, post(null))
            assertEquals(401, post("other-app"))
            assertEquals(200, post("launch-key"))
        }
    }
}
