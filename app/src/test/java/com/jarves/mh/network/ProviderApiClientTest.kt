package com.jarves.mh.network

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.model.providerProtocolForAgent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderApiClientTest {
    @Test
    fun openAiResponsesProbeUsesStructuredInputWithoutOutputCap() {
        val body = JSONObject(
            ProviderApiClient().validationBody(
                model = "muse-spark-1.3-contributor-free",
                protocol = ProviderProtocol.OPENAI_RESPONSES,
            ),
        )

        assertEquals("muse-spark-1.3-contributor-free", body.getString("model"))
        val message = body.getJSONArray("input").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        val content = message.getJSONArray("content").getJSONObject(0)
        assertEquals("input_text", content.getString("type"))
        assertEquals("Hello, reply with 1 word.", content.getString("text"))
        assertEquals(false, body.has("max_output_tokens"))
    }

    @Test
    fun modelEndpointsNeverDuplicatesV1WhenBaseEndsInV1() {
        val client = ProviderApiClient()

        val openAiChatCandidates = client.modelEndpoints("https://api.example.com/v1", ProviderProtocol.OPENAI_CHAT)
        assertEquals("https://api.example.com/v1/models", openAiChatCandidates.first())
        org.junit.Assert.assertFalse(openAiChatCandidates.any { it.contains("/v1/v1") })

        val anthropicCandidates = client.modelEndpoints("https://api.example.com/v1", ProviderProtocol.ANTHROPIC_GATEWAY)
        assertEquals("https://api.example.com/v1/models", anthropicCandidates.first())
        org.junit.Assert.assertFalse(anthropicCandidates.any { it.contains("/v1/v1") })
    }

    @Test
    fun messagesEndpointDoesNotDuplicateOperationSuffixes() {
        val client = ProviderApiClient()

        assertEquals(
            "https://api.example.com/v1/chat/completions",
            client.messagesEndpoint("https://api.example.com/v1", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            client.messagesEndpoint("https://api.example.com/v1/chat/completions", ProviderProtocol.OPENAI_CHAT),
        )
        assertEquals(
            "https://opencode.ai/zen/v1/responses",
            client.messagesEndpoint("https://opencode.ai/zen/v1/responses", ProviderProtocol.OPENAI_RESPONSES),
        )
        assertEquals(
            "https://api.example.com/v1/messages",
            client.messagesEndpoint("https://api.example.com/v1/messages", ProviderProtocol.ANTHROPIC_GATEWAY),
        )
    }

    @Test
    fun customOpenAiProfileForClaudeUsesModelsAndChatCompletionsEndpoints() {
        val profile = ProviderProfile(ProviderKind.CUSTOM, "https://api.example.com/v1", "m", dshApi = "openai-completions")
        val protocol = providerProtocolForAgent(profile, AgentKind.CLAUDE_CODE)
        val client = ProviderApiClient()

        assertEquals(ProviderProtocol.OPENAI_CHAT, protocol)
        assertEquals("https://api.example.com/v1/models", client.modelEndpoints(profile.baseUrl, protocol).first())
        assertEquals("https://api.example.com/v1/chat/completions", client.messagesEndpoint(profile.baseUrl, protocol))
    }

    @Test
    fun aiqanaEndpointIsRevokedInProviderApiClient() = kotlinx.coroutines.runBlocking {
        val client = ProviderApiClient()
        val discovery = client.discoverModels("https://aiqana.com/v1", "dummy-key", ProviderProtocol.OPENAI_CHAT)
        org.junit.Assert.assertTrue(discovery is ModelDiscoveryResult.Failure)
        org.junit.Assert.assertTrue((discovery as ModelDiscoveryResult.Failure).message.contains("revoked"))

        val validation = client.validate("https://aiqana.com/v1", "model", "key", ProviderProtocol.OPENAI_CHAT, emptyList())
        org.junit.Assert.assertTrue(validation is ConnectionValidation.Failure)
        org.junit.Assert.assertEquals("Revoked", (validation as ConnectionValidation.Failure).label)
    }
}
