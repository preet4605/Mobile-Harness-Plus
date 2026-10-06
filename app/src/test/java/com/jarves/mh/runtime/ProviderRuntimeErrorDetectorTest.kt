package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRuntimeErrorDetectorTest {
    @Test
    fun userNotFoundIsFatal() {
        assertEquals(
            "User not found. Check the API key and provider account.",
            ProviderRuntimeErrorDetector.detect("Failed to authenticate. API Error: 401 User not found."),
        )
    }

    @Test
    fun authenticationRetryIsFatalImmediately() {
        val event = """{"type":"system","subtype":"api_retry","attempt":1,"error_status":401,"error":"authentication_failed"}"""
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun ordinaryRuntimeOutputIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("Claude Code connected"))
    }

    @Test
    fun toolResultContainingAuthAndQuotaKeywordsIsNotFatal() {
        val toolOutputEvent = """
            {"type":"user","message":{"role":"user","content":[{"tool_use_id":"toolu_1","type":"tool_result","content":"if ('rate limit' in text || 'quota' in text || 'expired' in text || 'http 401' in text || 'authentication failed' in text) return false;"}]}}
        """.trimIndent()
        assertNull("Tool results must never trigger provider error detection", ProviderRuntimeErrorDetector.detect(toolOutputEvent))
    }

    @Test
    fun thinkingDeltaContainingAuthKeywordsIsNotFatal() {
        val thinkingEvent = """
            {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let us check the quota and ensure no rate limit or http 401 error occurs."}}
        """.trimIndent()
        assertNull("Thinking deltas must never trigger provider error detection", ProviderRuntimeErrorDetector.detect(thinkingEvent))
    }

    @Test
    fun textDeltaContainingAuthKeywordsIsNotFatal() {
        val textEvent = """
            {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"The system encountered a rate limit and quota issue in the legacy code."}}
        """.trimIndent()
        assertNull("Assistant text deltas must never trigger provider error detection", ProviderRuntimeErrorDetector.detect(textEvent))
    }

    @Test
    fun rateLimitTelemetryEventIsNotFatal() {
        val rateLimitEvent = """
            {"type":"rate_limit_event","rate_limit_info":{"status":"allowed","resetsAt":1791114600,"rateLimitType":"five_hour","overageStatus":"rejected","isUsingOverage":false}}
        """.trimIndent()
        assertNull("Rate limit telemetry events are informational and must not be treated as fatal", ProviderRuntimeErrorDetector.detect(rateLimitEvent))
    }

    @Test
    fun rateLimit429RetryIsNotFatal() {
        val retry429 = """
            {"type":"system","subtype":"api_retry","attempt":1,"max_retries":10,"retry_delay_ms":1000,"error_status":429,"error":"rate_limit"}
        """.trimIndent()
        assertNull("429 rate limit retries must be retried by Claude Code, not aborted immediately", ProviderRuntimeErrorDetector.detect(retry429))
    }

    @Test
    fun authenticationRetry403IsFatalImmediately() {
        val retry403 = """
            {"type":"system","subtype":"api_retry","attempt":1,"max_retries":10,"retry_delay_ms":1000,"error_status":403,"error":"forbidden"}
        """.trimIndent()
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect(retry403),
        )
    }

    @Test
    fun notLoggedInAssistantErrorIsFatal() {
        val notLoggedInAssistant = """
            {"type":"assistant","is_api_error_message":true,"error":"authentication_failed","message":{"content":[{"type":"text","text":"Not logged in · Please run /login"}]}}
        """.trimIndent()
        assertEquals(
            "Claude subscription is not signed in. Use Sign in with Claude.",
            ProviderRuntimeErrorDetector.detect(notLoggedInAssistant),
        )
    }

    @Test
    fun notLoggedInResultErrorIsFatal() {
        val notLoggedInResult = """
            {"type":"result","is_error":true,"terminal_reason":"api_error","result":"Not logged in · Please run /login"}
        """.trimIndent()
        assertEquals(
            "Claude subscription is not signed in. Use Sign in with Claude.",
            ProviderRuntimeErrorDetector.detect(notLoggedInResult),
        )
    }

    @Test
    fun successfulResultIsNotFatal() {
        val successResult = """
            {"type":"result","is_error":false,"subtype":"success","result":"Done"}
        """.trimIndent()
        assertNull("Successful result must not be detected as error", ProviderRuntimeErrorDetector.detect(successResult))
    }
}
