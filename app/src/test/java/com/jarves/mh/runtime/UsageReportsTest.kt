package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.ModelQuota
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.usageSummary
import com.jarves.mh.model.usageWindowLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageReportsTest {

    @Test
    fun windowLabelsNameCommonLengths() {
        assertEquals("Limit", usageWindowLabel(null))
        assertEquals("5-hour limit", usageWindowLabel(300L))
        assertEquals("Weekly limit", usageWindowLabel(10080L))
        assertEquals("1-day limit", usageWindowLabel(1440L))
        assertEquals("2-hour limit", usageWindowLabel(120L))
        assertEquals("90-minute limit", usageWindowLabel(90L))
    }

    @Test
    fun codexRateLimitsBecomeWindowsPlanAndCredits() {
        val result = JSONObject(
            """
            {"rateLimits": {
               "primary": {"usedPercent": 42, "windowDurationMins": 300, "resetsAt": 1790000000},
               "secondary": {"usedPercent": 10, "windowDurationMins": 10080, "resetsAt": null},
               "planType": "pro",
               "credits": {"hasCredits": true, "unlimited": false, "balance": "12.50"}},
             "ordinaryUsageAllowed": true}
            """.trimIndent(),
        )
        val usage = CodexAppServerProtocol.parseRateLimits(result)
        assertEquals(listOf("5-hour limit", "Weekly limit"), usage.limits.map { it.label })
        assertTrue(usage.limits[0].detail.startsWith("42% used"))
        assertTrue(usage.limits[0].detail.contains("resets"))
        assertEquals("10% used", usage.limits[1].detail)
        assertEquals("Credits: 12.50", usage.balance)
        assertEquals("Plan: pro", usage.note)
    }

    @Test
    fun codexRefusalAsksForSignInInsteadOfEchoingServerText() {
        val refused = CodexAppServerProtocol.parseReply(
            """{"id":2,"error":{"code":-32600,"message":"codex account authentication required to read rate limits"}}""",
        )
        assertNotNull(refused)
        val usage = CodexAppServerProtocol.usageError(refused!!.errorMessage!!)
        assertEquals("Usage needs ChatGPT sign-in. Sign in from Settings → Coding agent.", usage.note)
        assertTrue(usage.limits.isEmpty())
    }

    @Test
    fun claudeEventShowsStatusAndReset() {
        val usage = ClaudeUsageReport.parse(
            """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1790000000,"rateLimitType":"five_hour"}}""",
        )
        assertEquals("5-hour limit", usage.limits.single().label)
        assertTrue(usage.limits.single().detail.startsWith("Status: allowed"))
        assertTrue(usage.limits.single().detail.contains("resets"))
    }

    @Test
    fun claudeWithoutAnEventSaysWhatToDo() {
        assertTrue(ClaudeUsageReport.parse(null).note!!.contains("during a run"))
        assertNotNull(ClaudeUsageReport.parse("not json").note)
        assertNotNull(ClaudeUsageReport.parse("""{"type":"rate_limit_event"}""").note)
    }

    @Test
    fun deepSeekBalanceReadsEveryCurrencyAndFailsSoftly() {
        val ok = DeepSeekBalanceReport.parse(
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00"},{"currency":"USD","total_balance":""}]}""",
        )
        assertEquals("CNY 110.00", ok.balance)
        assertNull(ok.note)

        assertNotNull(DeepSeekBalanceReport.parse("""{"is_available":false,"balance_infos":[]}""").note)
        assertNotNull(DeepSeekBalanceReport.parse("""{"balance_infos":[]}""").note)
        assertNotNull(DeepSeekBalanceReport.parse("<html>").note)
    }

    @Test
    fun antigravityQuotasListThePrimaryAccountsModels() {
        val account = AntigravityAccount(
            email = "dev@example.com",
            isPrimary = true,
            modelQuotas = mapOf("gemini-3.8-pro" to ModelQuota(remainingFraction = 0.25f, resetTimeMillis = 1790000000_000L)),
        )
        val usage = AntigravityUsageReport.from(account)
        assertEquals("gemini-3.8-pro", usage.limits.single().label)
        assertTrue(usage.limits.single().detail.startsWith("25% left"))
        assertNotNull(AntigravityUsageReport.from(null).note)
    }

    @Test
    fun usageSummaryShowsSessionTotalsAndLimits() {
        val session = SessionTokenMetrics(promptTokens = 10, completionTokens = 5, cachedTokens = 2)
        val empty = usageSummary(AgentKind.CODEX, session, com.jarves.mh.model.AgentUsage())
        assertTrue(empty.contains("Session: 10 in · 5 out · 2 cached tokens"))
        assertTrue(empty.contains("No limits reported yet"))

        val filled = usageSummary(
            AgentKind.CODEX,
            session,
            com.jarves.mh.model.AgentUsage(limits = listOf(com.jarves.mh.model.UsageLimit("5-hour limit", "42% used")), balance = "Credits: 12.50"),
        )
        assertTrue(filled.contains("- 5-hour limit: 42% used"))
        assertTrue(filled.contains("- Balance: Credits: 12.50"))
    }
}
