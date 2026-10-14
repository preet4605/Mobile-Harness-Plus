package com.jarves.mh.runtime

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexAccountBalancingTest {
    private fun fixture(test: (CodexAccounts, List<CodexAccount>) -> Unit) {
        val root = Files.createTempDirectory("codex-balancing").toFile()
        try {
            val store = CodexAccounts(root)
            val items = (1..3).map { store.create().also { store.complete(it.id, "Account $it"); store.credentialFile(it.id).writeText("fixture") } }
            test(store, items)
        } finally { root.deleteRecursively() }
    }
    @Test fun manualSelectionRemainsDefaultAndBalancedCursorSurvivesReopen() = fixture { store, a ->
        assertEquals(a[0].id, store.selectForTask().id)
        store.balancingEnabled = true
        assertEquals(a[0].id, store.selectForTask().id)
        assertEquals(a[1].id, CodexAccounts(store.hostHome(a[0].id).parentFile!!.parentFile!!.parentFile!!).selectForTask().id)
        assertEquals(a[2].id, store.selectForTask().id)
        assertEquals(a[0].id, store.selectForTask().id)
        assertEquals(a[0].id, store.selectedId())
    }
    @Test fun disabledExpiredAndMissingCredentialAccountsAreSkipped() = fixture { store, a ->
        store.balancingEnabled = true
        store.setEnabled(a[0].id, false)
        store.markExpired(a[1].id)
        assertEquals(a[2].id, store.selectForTask().id)
        store.credentialFile(a[2].id).delete()
        assertThrows(IllegalStateException::class.java) { store.selectForTask() }
    }
    @Test fun knownExhaustionRespectsResetAndSparseUpdatesDoNotClearIt() = fixture { store, a ->
        store.balancingEnabled = true
        store.recordRateLimits(a[0].id, JSONObject("""{"primary":{"usedPercent":100,"resetsAt":2000}}"""), nowMillis = 1000000)
        store.recordRateLimits(a[0].id, JSONObject("""{"primary":null,"planType":null}"""), nowMillis = 1001000)
        assertEquals(a[1].id, store.selectForTask(nowMillis = 1500000).id)
        assertEquals(a[2].id, store.selectForTask(nowMillis = 1500000).id)
        assertEquals(a[0].id, store.selectForTask(nowMillis = 2000001).id)
    }
    @Test fun nativeOwnerNeverSubstitutesAnotherAccountOrConsumesRotation() = fixture { store, a ->
        store.balancingEnabled = true
        assertEquals(a[2].id, store.selectForTask(pinnedId = a[2].id).id)
        assertEquals(a[0].id, store.selectForTask().id)
        store.setEnabled(a[2].id, false)
        assertThrows(IllegalArgumentException::class.java) { store.selectForTask(pinnedId = a[2].id) }
    }
    @Test fun invalidUsageCannotPoisonTheOtherAccounts() = fixture { store, a ->
        store.balancingEnabled = true
        store.recordRateLimits(a[0].id, JSONObject("""{"primary":{"usedPercent":101,"resetsAt":9999999999999}}"""))
        assertEquals(a[0].id, store.selectForTask().id)
    }
    @Test fun codexDefaultLimitWithNormalModelSlugStillBlocksDefaultSelection() = fixture { store, a ->
        store.balancingEnabled = true
        store.recordRateLimits(a[0].id, JSONObject("""{"limitId":"codex","normalModelSlug":"gpt-fixture","primary":{"usedPercent":100,"resetsAt":2000}}"""), nowMillis = 1000000)
        assertEquals(a[1].id, store.selectForTask(nowMillis = 1500000).id)
    }
    @Test fun unrelatedModelLimitDoesNotBlockAnotherModelAndUnknownResetExpires() = fixture { store, a ->
        store.balancingEnabled = true
        store.recordRateLimits(a[0].id, JSONObject("""{"limitId":"special-model","normalModelSlug":"special","primary":{"usedPercent":100}}"""), nowMillis = 1000000)
        assertEquals(a[0].id, store.selectForTask(nowMillis = 1500000, model = "ordinary").id)
        store.recordRateLimits(a[1].id, JSONObject("""{"primary":{"usedPercent":100}}"""), nowMillis = 1000000)
        assertEquals(a[2].id, store.selectForTask(nowMillis = 1500000).id)
        assertEquals(a[0].id, store.selectForTask(nowMillis = 2000001).id)
        assertEquals(a[1].id, store.selectForTask(nowMillis = 2000001).id)
    }

}
