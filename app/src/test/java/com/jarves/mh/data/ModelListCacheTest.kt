package com.jarves.mh.data

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.network.DiscoveredModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelListCacheTest {

    @Test
    fun encodeAndDecodeRoundTripKeepsIdsNamesAndEffortLevels() {
        val models = listOf(
            DiscoveredModel("gpt-6-astra", "GPT-6-Astra", reasoningEfforts = listOf("low", "max", "ultra")),
            DiscoveredModel("deepseek-v4-flash", "DeepSeek-V4 Flash"),
        )
        assertEquals(models, ModelListCache.decode(ModelListCache.encode(models)))
    }

    @Test
    fun corruptOrMissingDataReadsAsEmpty() {
        assertTrue(ModelListCache.decode(null).isEmpty())
        assertTrue(ModelListCache.decode("").isEmpty())
        assertTrue(ModelListCache.decode("{not json").isEmpty())
        assertTrue(ModelListCache.decode("""[{"name":"no id"}]""").isEmpty())
    }

    @Test
    fun endpointKeyIgnoresTrailingSlashesAndFixedEndpoints() {
        val a = ModelListCache.key(AgentKind.CODEX, ProviderKind.CUSTOM, "https://gw.example.com/v1/")
        val b = ModelListCache.key(AgentKind.CODEX, ProviderKind.CUSTOM, " https://gw.example.com/v1 ")
        assertEquals(a, b)

        val chatGptA = ModelListCache.key(AgentKind.CODEX, ProviderKind.CHATGPT, "")
        val chatGptB = ModelListCache.key(AgentKind.CODEX, ProviderKind.CHATGPT, "https://ignored.example.com")
        assertEquals(chatGptA, chatGptB)

        assertTrue(a != ModelListCache.key(AgentKind.DEEPSEEK_HARNESS, ProviderKind.CUSTOM, "https://gw.example.com/v1"))
    }
}
