package com.jarves.mh.runtime

import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AntigravityModelDiscoveryTest {

    private fun createTestAccountManager(
        initialAccounts: List<AntigravityAccount> = emptyList(),
        tokenRefresher: ((String, String) -> String?)? = null,
    ): Pair<AntigravityAccountManager, MutableList<AntigravityAccount>> {
        val memoryStore = initialAccounts.toMutableList()
        val manager = AntigravityAccountManager(
            customAccountsDir = File("/fake/accounts"),
            loadAccountsOverride = { memoryStore.toList() },
            saveAccountsOverride = { updated ->
                memoryStore.clear()
                memoryStore.addAll(updated)
            },
            strategyOverride = { AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED },
            tokenRefresherOverride = tokenRefresher,
        )
        return Pair(manager, memoryStore)
    }

    // 1. Model-output parsing
    @Test
    fun `parseAntigravityModelList parses standard and bulleted CLI output`() {
        val output = """
            Available models:
              * gemini-2.5-pro (default)
              - gemini-2.5-flash
              • gemini-3.8-flash-low
                gemini-3.8-flash-high
        """.trimIndent()

        val models = parseAntigravityModelList(output)
        assertEquals(
            listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.8-flash-low", "gemini-3.8-flash-high"),
            models,
        )
    }

    @Test
    fun `parseAntigravityModelList strips ANSI escape sequences and noise lines`() {
        val output = "\u001B[1mModels:\u001B[0m\n" +
            "Connecting to daily-cloudcode-pa.googleapis.com...\n" +
            "# Comment header\n" +
            "\u001B[32mgemini-2.5-pro\u001B[0m\tRecommended\n" +
            "\u001B[34mgemini-2.5-flash\u001B[0m\n"

        val models = parseAntigravityModelList(output)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), models)
    }

    @Test
    fun `parseAntigravityModelList rejects headers and table column titles`() {
        val output = """
            MODEL                 DESCRIPTION
            gemini-2.5-pro        Gemini 2.5 Pro
            gemini-2.5-flash      Gemini 2.5 Flash
        """.trimIndent()

        val models = parseAntigravityModelList(output)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), models)
    }

    // 2. Duplicate model IDs
    @Test
    fun `parseAntigravityModelList removes duplicate model IDs preserving CLI order`() {
        val output = """
            gemini-2.5-pro
            gemini-2.5-flash
            gemini-2.5-pro
            gemini-3.8-flash-high
            gemini-2.5-flash
        """.trimIndent()

        val models = parseAntigravityModelList(output)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-3.8-flash-high"), models)
    }

    // 3. Auth-error mapping
    @Test
    fun `mapAntigravityDiscoveryError maps auth errors properly`() {
        val cases = listOf(
            "Authentication required. Please run agy login",
            "OAuth token expired or invalid",
            "Not signed in to Google account",
            "HTTP 401 Unauthorized: token expired",
            "error: invalid_grant: token has been revoked",
        )

        for (case in cases) {
            val mapped = mapAntigravityDiscoveryError(1, case)
            assertEquals(
                "Antigravity authentication failed. Reconnect your Google account and try again.",
                mapped,
            )
        }
    }

    // 4. Network-error mapping
    @Test
    fun `mapAntigravityDiscoveryError maps network errors properly`() {
        val cases = listOf(
            "agent executor error: generating and executing: request failed: software caused connection abort",
            "read tcp 192.168.1.5:4321->172.217.113.4:443: read: connection reset by peer",
            "dial tcp: lookup daily-cloudcode-pa.googleapis.com: no such host",
            "write: broken pipe",
            "Network connection interrupted. Please check your internet connection.",
        )

        for (case in cases) {
            val mapped = mapAntigravityDiscoveryError(1, case)
            assertEquals(
                "Could not refresh Antigravity models because the network connection was interrupted.",
                mapped,
            )
        }
    }

    // 5. Non-zero exit with empty output
    @Test
    fun `mapAntigravityDiscoveryError handles non-zero exit with empty or blank output`() {
        val emptyResult = mapAntigravityDiscoveryError(1, "")
        assertEquals("Could not list Antigravity models (exit 1)", emptyResult)

        val whitespaceResult = mapAntigravityDiscoveryError(127, "   \n\t  ")
        assertEquals("Could not list Antigravity models (exit 127)", whitespaceResult)
    }

    // 6. Current model retained when still available
    @Test
    fun `reconcileAntigravityModelSelection keeps current model when still present`() {
        val available = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-3.8-flash-medium")
        val (model, effort) = reconcileAntigravityModelSelection("gemini-2.5-pro", "medium", available)

        assertEquals("gemini-2.5-pro", model)
        assertEquals("medium", effort)
    }

    // 7. Effort reconciliation where applicable
    @Test
    fun `reconcileAntigravityModelSelection reconciles effort variant when available`() {
        val available = listOf("gemini-3.8-flash-low", "gemini-3.8-flash-medium", "gemini-3.8-flash-high")
        val (model, effort) = reconcileAntigravityModelSelection("gemini-3.8-flash-low", "high", available)

        assertEquals("gemini-3.8-flash-high", model)
        assertEquals("high", effort)
    }

    @Test
    fun `reconcileAntigravityModelSelection falls back deterministically when current model is missing`() {
        val available = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        val (model, effort) = reconcileAntigravityModelSelection("gemini-1.5-pro", "medium", available)

        assertEquals("gemini-2.5-flash", model)
        assertEquals("medium", effort)
    }

    // 8. Account preparation and discovery flow without real Android subprocess
    @Test
    fun `executeAntigravityModelDiscovery fails with clear error when no account exists`() = runBlocking {
        val (manager, _) = createTestAccountManager(emptyList())

        try {
            executeAntigravityModelDiscovery(manager) { _ ->
                fail("Runner should not be called when no account exists")
                Pair(0, "")
            }
            fail("Expected AntigravityModelDiscoveryException")
        } catch (e: AntigravityModelDiscoveryException) {
            assertEquals("No usable Antigravity account. Sign in or reconnect an account first.", e.message)
        }
    }

    @Test
    fun `executeAntigravityModelDiscovery passes correct HOME env and parses models`() = runBlocking {
        val account = AntigravityAccount(
            id = "acc-123",
            email = "user@gmail.com",
            status = AntigravityAccountStatus.HEALTHY,
            isPrimary = true,
        )
        val (manager, _) = createTestAccountManager(listOf(account))

        var passedHome: String? = null
        val models = executeAntigravityModelDiscovery(manager) { env ->
            passedHome = env["HOME"]
            Pair(0, "gemini-2.5-pro\ngemini-2.5-flash\n")
        }

        assertEquals("/root/.antigravity-accounts/acc-123", passedHome)
        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), models)
    }

    @Test
    fun `executeAntigravityModelDiscovery maps CLI non-zero exit with auth error`() = runBlocking {
        val account = AntigravityAccount(
            id = "acc-123",
            email = "user@gmail.com",
            status = AntigravityAccountStatus.HEALTHY,
        )
        val (manager, _) = createTestAccountManager(listOf(account))

        try {
            executeAntigravityModelDiscovery(manager) { _ ->
                Pair(1, "Authentication required. Please run agy login")
            }
            fail("Expected AntigravityModelDiscoveryException")
        } catch (e: AntigravityModelDiscoveryException) {
            assertEquals(
                "Antigravity authentication failed. Reconnect your Google account and try again.",
                e.message,
            )
        }
    }

    @Test
    fun `executeAntigravityModelDiscovery fails safely when no models returned`() = runBlocking {
        val account = AntigravityAccount(
            id = "acc-123",
            email = "user@gmail.com",
            status = AntigravityAccountStatus.HEALTHY,
        )
        val (manager, _) = createTestAccountManager(listOf(account))

        try {
            executeAntigravityModelDiscovery(manager) { _ ->
                Pair(0, "Available models:\n")
            }
            fail("Expected AntigravityModelDiscoveryException")
        } catch (e: AntigravityModelDiscoveryException) {
            assertEquals("Antigravity returned no models", e.message)
        }
    }

    // 9. Secret redaction in unknown CLI failure
    @Test
    fun `mapAntigravityDiscoveryError redacts tokens from unknown CLI failure output`() {
        val raw = "Unknown fatal error: Bearer ya29.a0AfH6SMD_secret123 failed to connect"
        val mapped = mapAntigravityDiscoveryError(2, raw)

        assertFalse("Token must not be exposed", mapped.contains("ya29.a0AfH6SMD_secret123"))
        assertTrue("Output should indicate model listing failure", mapped.startsWith("Could not list Antigravity models: "))
    }
}
