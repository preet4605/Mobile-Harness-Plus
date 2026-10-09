package com.jarves.mh.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring guards for side effects that cannot run in this ARM64 host's Android test runner. */
class Phase2RuntimeEventWiringTest {
    private fun source(): String = File("src/main/java/com/jarves/mh/ui/MainViewModel.kt").readText()

    @Test fun ownershipGatePrecedesAuthenticationAndFallbackEffects() {
        val handler = source().substringAfter("private fun onRuntimeEvent(").substringBefore("fun canFallbackForTask")
        val gate = handler.indexOf("RuntimeSessionRouting.acceptsOwned")
        assertTrue("Ownership must be checked before auth invalidation", gate >= 0 && gate < handler.indexOf("invalidateSession"))
        assertTrue("Ownership must be checked before key fallback", gate < handler.indexOf("retryWithNextApiKey"))
    }

    @Test fun bridgeCompletionCannotFinalizeTaskOrRecordSuccess() {
        val handler = source().substringAfter("private fun onRuntimeEvent(").substringBefore("fun canFallbackForTask")
        val completion = handler.substringAfter("is RuntimeEvent.SessionCompleted ->").substringBefore("is RuntimeEvent.SessionFailed ->")
        assertFalse(completion.contains("isRunning = false"))
        assertFalse(completion.contains("completeAll("))
        assertFalse(handler.contains("recordSuccess("))
        assertTrue(source().contains("finishSupervisedTask("))
    }
    @Test fun supervisedBridgesDeferForegroundFinalization() {
        for (name in listOf("Claude", "Dsh", "Antigravity", "Codex")) {
            val bridge = File("src/main/java/com/jarves/mh/runtime/${name}RuntimeBridge.kt").readText()
            val finish = bridge.substringAfter("private fun finishForegroundRuntime(").substringBefore("private fun cancelForegroundRuntime(")
            val cancel = bridge.substringAfter("private fun cancelForegroundRuntime(").substringBefore("\n    }")
            assertTrue("$name must keep the service through supervisor verification", finish.contains("if (activeTaskId != null) return"))
            assertTrue("$name must defer supervised cancellation notification", cancel.contains("if (activeTaskId != null) return"))
        }
    }

}
