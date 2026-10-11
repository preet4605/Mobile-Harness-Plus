package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OpenRouterRoutingGatewayTest {
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
