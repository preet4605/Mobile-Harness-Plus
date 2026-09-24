package com.jarves.mh.runtime

import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AntigravityAccountManagerTest {

    private fun createTestManager(
        initialAccounts: List<AntigravityAccount> = emptyList(),
        strategy: () -> AntigravityLoadBalancingStrategy = { AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED },
        quotaFetcher: ((String) -> Map<String, com.jarves.mh.model.ModelQuota>?)? = null,
    ): Pair<AntigravityAccountManager, MutableList<AntigravityAccount>> {
        val memoryStore = initialAccounts.toMutableList()
        val manager = AntigravityAccountManager(
            customAccountsDir = File("/fake/accounts"),
            loadAccountsOverride = { memoryStore.toList() },
            saveAccountsOverride = { updated ->
                memoryStore.clear()
                memoryStore.addAll(updated)
            },
            strategyOverride = strategy,
            quotaFetcherOverride = quotaFetcher,
        )
        return Pair(manager, memoryStore)
    }

    @Test
    fun `first added account is automatically promoted to primary`() {
        val (manager, store) = createTestManager()
        val acc = manager.addAccount(id = "acc-1", email = "test1@gmail.com", label = "Work")
        
        assertTrue(acc.isPrimary)
        assertEquals(1, store.size)
        assertEquals("acc-1", manager.getPrimaryAccount()?.id)
    }

    @Test
    fun `adding second account without primary flag preserves first as primary`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "test1@gmail.com", label = "Work")
        manager.addAccount(id = "acc-2", email = "test2@gmail.com", label = "Personal", isPrimary = false)

        val primary = manager.getPrimaryAccount()
        assertEquals("acc-1", primary?.id)
        assertFalse(manager.getAccount("acc-2")!!.isPrimary)
    }

    @Test
    fun `adding second account with primary flag demotes first account`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "test1@gmail.com", label = "Work")
        manager.addAccount(id = "acc-2", email = "test2@gmail.com", label = "Personal", isPrimary = true)

        assertEquals("acc-2", manager.getPrimaryAccount()?.id)
        assertFalse(manager.getAccount("acc-1")!!.isPrimary)
    }

    @Test
    fun `removing primary account promotes the next remaining account`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "test1@gmail.com")
        manager.addAccount(id = "acc-2", email = "test2@gmail.com")

        assertTrue(manager.getAccount("acc-1")!!.isPrimary)
        val removed = manager.removeAccount("acc-1")

        assertTrue(removed)
        assertEquals(1, manager.accountsList().size)
        assertEquals("acc-2", manager.getPrimaryAccount()?.id)
        assertTrue(manager.getAccount("acc-2")!!.isPrimary)
    }

    @Test
    fun `primary only routing strategy always selects primary account`() {
        val (manager, _) = createTestManager(
            strategy = { AntigravityLoadBalancingStrategy.PRIMARY_ONLY },
        )
        manager.addAccount(id = "acc-1", email = "test1@gmail.com", isPrimary = false)
        manager.addAccount(id = "acc-2", email = "test2@gmail.com", isPrimary = true)

        val selected = manager.selectAccountForTurn()
        assertEquals("acc-2", selected?.id)
    }

    @Test
    fun `round robin strategy cycles through accounts sequentially`() {
        var currentStrategy = AntigravityLoadBalancingStrategy.ROUND_ROBIN
        val (manager, _) = createTestManager(
            strategy = { currentStrategy },
        )
        manager.addAccount(id = "acc-1", email = "test1@gmail.com")
        manager.addAccount(id = "acc-2", email = "test2@gmail.com")
        manager.addAccount(id = "acc-3", email = "test3@gmail.com")

        val first = manager.selectAccountForTurn()?.id
        val second = manager.selectAccountForTurn()?.id
        val third = manager.selectAccountForTurn()?.id
        val fourth = manager.selectAccountForTurn()?.id

        val sequence = listOf(first, second, third)
        assertTrue(sequence.containsAll(listOf("acc-1", "acc-2", "acc-3")))
        assertEquals(first, fourth) // Cycles back
    }

    @Test
    fun `least recently used strategy selects account with oldest lastUsedAt`() {
        val (manager, store) = createTestManager(
            strategy = { AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED },
        )
        val now = System.currentTimeMillis()
        val acc1 = AntigravityAccount(id = "acc-1", email = "1@test.com", lastUsedAt = now - 5000, status = AntigravityAccountStatus.HEALTHY)
        val acc2 = AntigravityAccount(id = "acc-2", email = "2@test.com", lastUsedAt = now - 1000, status = AntigravityAccountStatus.HEALTHY)
        val acc3 = AntigravityAccount(id = "acc-3", email = "3@test.com", lastUsedAt = now - 20000, status = AntigravityAccountStatus.HEALTHY)
        store.addAll(listOf(acc1, acc2, acc3))
        manager.reload()

        val selected = manager.selectAccountForTurn()
        assertEquals("acc-3", selected?.id)
    }

    @Test
    fun `selectAccountForTurn skips accounts passed in excludeAccountIds`() {
        val (manager, _) = createTestManager(
            strategy = { AntigravityLoadBalancingStrategy.PRIMARY_ONLY },
        )
        manager.addAccount(id = "acc-1", email = "1@test.com", isPrimary = true)
        manager.addAccount(id = "acc-2", email = "2@test.com", isPrimary = false)

        val selected = manager.selectAccountForTurn(excludeAccountIds = setOf("acc-1"))
        assertEquals("acc-2", selected?.id)
    }

    @Test
    fun `quota exhausted accounts are skipped until cooldown expires`() {
        val (manager, store) = createTestManager()
        val now = System.currentTimeMillis()
        val exhaustedAccount = AntigravityAccount(
            id = "acc-1",
            email = "1@test.com",
            status = AntigravityAccountStatus.QUOTA_EXHAUSTED,
            quotaExhaustedUntil = now + 60000,
            isPrimary = true,
        )
        val healthyAccount = AntigravityAccount(
            id = "acc-2",
            email = "2@test.com",
            status = AntigravityAccountStatus.HEALTHY,
            isPrimary = false,
        )
        store.addAll(listOf(exhaustedAccount, healthyAccount))
        manager.reload()

        val selected = manager.selectAccountForTurn()
        assertEquals("acc-2", selected?.id)
    }

    @Test
    fun `sticky account takes priority if healthy and not excluded`() {
        val (manager, _) = createTestManager(
            strategy = { AntigravityLoadBalancingStrategy.PRIMARY_ONLY },
        )
        manager.addAccount(id = "acc-1", email = "1@test.com", isPrimary = true)
        manager.addAccount(id = "acc-2", email = "2@test.com", isPrimary = false)

        val selected = manager.selectAccountForTurn(stickyAccountId = "acc-2")
        assertEquals("acc-2", selected?.id)

        // If sticky account is excluded, falls back to available accounts
        val fallback = manager.selectAccountForTurn(excludeAccountIds = setOf("acc-2"), stickyAccountId = "acc-2")
        assertEquals("acc-1", fallback?.id)
    }

    @Test
    fun `recordUsage updates lastUsedAt and restores healthy status from quota exhaustion`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "1@test.com")
        manager.markQuotaExhausted("acc-1", cooldownMillis = 10000)

        val exhausted = manager.getAccount("acc-1")
        assertEquals(AntigravityAccountStatus.QUOTA_EXHAUSTED, exhausted?.status)
        assertNotNull(exhausted?.quotaExhaustedUntil)

        manager.recordUsage("acc-1")
        val restored = manager.getAccount("acc-1")
        assertEquals(AntigravityAccountStatus.HEALTHY, restored?.status)
    }

    @Test
    fun `markAuthError marks account with AUTH_ERROR and sets failure message`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "1@test.com")
        manager.markAuthError("acc-1", "Invalid grant token")

        val account = manager.getAccount("acc-1")
        assertEquals(AntigravityAccountStatus.AUTH_ERROR, account?.status)
        assertEquals("Invalid grant token", account?.failureMessage)
    }

    @Test
    fun `remainingPercentageFor supports exact and prefix matching`() {
        val (manager, _) = createTestManager()
        val acc = manager.addAccount(id = "acc-1", email = "1@test.com")

        // Default healthy account with no quotas returns null (falls back to 100% in UI)
        assertNull(acc.remainingPercentageFor("gemini-3.8-flash"))

        manager.updateAccountModelQuota("acc-1", "gemini-3.8-flash-high", 0.85f)
        val updated = manager.getAccount("acc-1")!!

        // Exact match
        assertEquals(85, updated.remainingPercentageFor("gemini-3.8-flash-high"))
        // Prefix match (active model alias gemini-3.8-flash matches gemini-3.8-flash-high)
        assertEquals(85, updated.remainingPercentageFor("gemini-3.8-flash"))
    }

    @Test
    fun `markQuotaExhausted with modelId sets model quota to 0 percent`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "1@test.com")
        manager.updateAccountModelQuota("acc-1", "gemini-3.8-flash-high", 0.85f)

        manager.markQuotaExhausted("acc-1", modelId = "gemini-3.8-flash-high", cooldownMillis = 60000)
        val exhausted = manager.getAccount("acc-1")!!

        assertEquals(AntigravityAccountStatus.QUOTA_EXHAUSTED, exhausted.status)
        assertEquals(0, exhausted.remainingPercentageFor("gemini-3.8-flash-high"))
        assertEquals(0, exhausted.remainingPercentageFor("gemini-3.8-flash"))
    }

    @Test
    fun `remainingPercentageFor matches backend tiered suffix with frontend effort aliases`() {
        val (manager, _) = createTestManager()
        manager.addAccount(id = "acc-1", email = "1@test.com")
        manager.updateAccountModelQuota("acc-1", "gemini-3.8-flash-tiered", 0.61f)
        val acc = manager.getAccount("acc-1")!!

        assertEquals(61, acc.remainingPercentageFor("gemini-3.8-flash-high"))
        assertEquals(61, acc.remainingPercentageFor("gemini-3.8-flash-medium"))
        assertEquals(61, acc.remainingPercentageFor("gemini-3.8-flash-low"))
        assertEquals(61, acc.remainingPercentageFor("gemini-3.8-flash"))
    }

    @Test
    fun `refreshAccountQuota updates model quotas and restores healthy status`() = kotlinx.coroutines.runBlocking {
        val mockQuotas = mapOf(
            "gemini-3.8-flash-tiered" to com.jarves.mh.model.ModelQuota(remainingFraction = 0.65f)
        )
        val (manager, _) = createTestManager(quotaFetcher = { mockQuotas })
        manager.addAccount(id = "acc-1", email = "1@test.com")
        manager.markQuotaExhausted("acc-1")
        assertEquals(AntigravityAccountStatus.QUOTA_EXHAUSTED, manager.getAccount("acc-1")?.status)

        val success = manager.refreshAccountQuota("acc-1")
        assertTrue(success)
        val refreshed = manager.getAccount("acc-1")!!
        assertEquals(AntigravityAccountStatus.HEALTHY, refreshed.status)
        assertEquals(65, refreshed.remainingPercentageFor("gemini-3.8-flash-high"))
    }

    @Test
    fun `isTokenExpiredOrExpiringSoon identifies past and soon-to-expire timestamps`() {
        val (manager, _) = createTestManager()
        val past = java.time.Instant.now().minusSeconds(3600).toString()
        val soon = java.time.Instant.now().plusSeconds(60).toString()
        val future = java.time.Instant.now().plusSeconds(3600).toString()

        assertTrue(manager.isTokenExpiredOrExpiringSoon(past))
        assertTrue(manager.isTokenExpiredOrExpiringSoon(soon))
        assertFalse(manager.isTokenExpiredOrExpiringSoon(future))
        assertFalse(manager.isTokenExpiredOrExpiringSoon(null))
    }

    @Test
    fun `getAccountAccessToken refreshes expired token using tokenRefresherOverride`() {
        val tempFolder = org.junit.rules.TemporaryFolder().apply { create() }
        try {
            val root = tempFolder.newFolder("accounts")
            val accDir = File(root, "acc-1/.gemini/antigravity-cli").apply { mkdirs() }
            val expiredTokenJson = org.json.JSONObject()
                .put("token", org.json.JSONObject()
                    .put("access_token", "old-access-token")
                    .put("refresh_token", "valid-refresh-token")
                    .put("expiry", java.time.Instant.now().minusSeconds(100).toString()))
                .toString()
            File(accDir, "antigravity-oauth-token").writeText(expiredTokenJson)

            var refreshCalled = false
            val manager = AntigravityAccountManager(
                customAccountsDir = root,
                loadAccountsOverride = { listOf(AntigravityAccount(id = "acc-1", email = "1@test.com", status = AntigravityAccountStatus.AUTH_ERROR)) },
                saveAccountsOverride = {},
                tokenRefresherOverride = { id, rt ->
                    refreshCalled = true
                    assertEquals("acc-1", id)
                    assertEquals("valid-refresh-token", rt)
                    "new-fresh-access-token"
                },
            )

            val token = manager.getAccountAccessToken("acc-1")
            assertTrue(refreshCalled)
            assertEquals("new-fresh-access-token", token)
            assertEquals(AntigravityAccountStatus.HEALTHY, manager.getAccount("acc-1")?.status)
        } finally {
            tempFolder.delete()
        }
    }

    @Test
    fun `selectAccountForTurn rotates accounts in round-robin mode regardless of sticky account`() {
        val (manager, _) = createTestManager(
            strategy = { AntigravityLoadBalancingStrategy.ROUND_ROBIN },
        )
        manager.addAccount(id = "acc-1", email = "1@test.com", isPrimary = true)
        manager.addAccount(id = "acc-2", email = "2@test.com", isPrimary = false)

        val first = manager.selectAccountForTurn(stickyAccountId = "acc-1")
        val second = manager.selectAccountForTurn(stickyAccountId = "acc-1")
        val third = manager.selectAccountForTurn(stickyAccountId = "acc-1")

        assertEquals("acc-1", first?.id)
        assertEquals("acc-2", second?.id)
        assertEquals("acc-1", third?.id)
    }
}

