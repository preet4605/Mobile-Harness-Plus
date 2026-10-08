package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexModelCatalogTest {

    private val listResult = JSONObject(
        """
        {
          "data": [
            {"id": "gpt-6.1-sol", "model": "gpt-6.1-sol", "displayName": "GPT-6.1-Sol", "hidden": false,
             "isDefault": true, "defaultReasoningEffort": "low",
             "supportedReasoningEfforts": [
               {"reasoningEffort": "low", "description": "Fast"},
               {"reasoningEffort": "max", "description": "Maximum"}]},
            {"id": "internal-only", "model": "internal-only", "displayName": "Internal", "hidden": true,
             "supportedReasoningEfforts": []},
            {"id": "gpt-5.5", "model": "gpt-5.5", "hidden": false, "supportedReasoningEfforts": []}
          ],
          "nextCursor": null
        }
        """.trimIndent(),
    )

    @Test
    fun requestsAreNewlineTerminatedJsonRpcLines() {
        val initialize = JSONObject(CodexAppServerProtocol.initializeRequest(1).trim())
        assertEquals("initialize", initialize.getString("method"))
        assertEquals(1, initialize.getInt("id"))
        assertTrue(CodexAppServerProtocol.initializeRequest(1).endsWith("\n"))

        val notification = JSONObject(CodexAppServerProtocol.initializedNotification().trim())
        assertEquals("initialized", notification.getString("method"))
        assertTrue(!notification.has("id"))
    }

    @Test
    fun modelListRequestCarriesCursorOnlyWhenPaging() {
        val first = JSONObject(CodexAppServerProtocol.modelListRequest(2, cursor = null).trim())
        assertEquals("model/list", first.getString("method"))
        assertTrue(!first.getJSONObject("params").has("cursor"))

        val next = JSONObject(CodexAppServerProtocol.modelListRequest(3, cursor = "page-2").trim())
        assertEquals("page-2", next.getJSONObject("params").getString("cursor"))
    }

    @Test
    fun parseReplyIgnoresBannersNotificationsAndNonJson() {
        assertNull(CodexAppServerProtocol.parseReply("WARNING: proceeding, even though we could not create PATH aliases"))
        assertNull(CodexAppServerProtocol.parseReply("2026-10-08T15:50:21Z ERROR codex_app_server: bubblewrap missing"))
        assertNull(CodexAppServerProtocol.parseReply("""{"jsonrpc":"2.0","method":"configWarning","params":{}}"""))
        assertNull(CodexAppServerProtocol.parseReply(""))
    }

    @Test
    fun parseReplyReadsResultsAndErrors() {
        val ok = CodexAppServerProtocol.parseReply("""{"id":2,"result":{"data":[]}}""")
        assertEquals(2, ok?.id)
        assertNull(ok?.errorMessage)
        assertTrue(ok?.result?.has("data") == true)

        val refused = CodexAppServerProtocol.parseReply(
            """{"id":3,"error":{"code":-32600,"message":"codex account authentication required"}}""",
        )
        assertEquals("codex account authentication required", refused?.errorMessage)
    }

    @Test
    fun parseModelPageSkipsHiddenModelsAndKeepsEffortLevels() {
        val page = CodexAppServerProtocol.parseModelPage(listResult)

        assertEquals(listOf("gpt-6.1-sol", "gpt-5.5"), page.models.map { it.id })
        assertEquals("GPT-6.1-Sol", page.models.first().displayName)
        assertEquals(listOf("low", "max"), page.models.first().reasoningEfforts)
        assertEquals("gpt-5.5", page.models.last().displayName)
        assertTrue(page.models.last().reasoningEfforts.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun parseModelPageReturnsTheNextCursorWhenMoreModelsExist() {
        val page = CodexAppServerProtocol.parseModelPage(JSONObject("""{"data":[],"nextCursor":"abc"}"""))
        assertEquals("abc", page.nextCursor)
        assertTrue(page.models.isEmpty())
    }
}
