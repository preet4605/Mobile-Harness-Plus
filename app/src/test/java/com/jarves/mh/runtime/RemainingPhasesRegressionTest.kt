package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.provider.CustomProviderProfile
import com.jarves.mh.provider.ProviderEndpointNormalizer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Socket
import java.net.URI

class RemainingPhasesRegressionTest {
    @Test fun explicitAnthropicBaseAndPersistedProfileSurviveV1() {
        assertEquals("anthropic-messages", ProviderEndpointNormalizer.normalize("https://proxy.example/v1", "anthropic-messages").api)
        val saved = CustomProviderProfile(name = "Proxy", baseUrl = "https://proxy.example/v1", model = "m", dshApi = "anthropic-messages")
        assertEquals(saved, CustomProviderProfile.fromJson(saved.toJson()))
    }

    @Test fun unauthorizedStalledBodyIsRejectedWithoutReadingIt() {
        LocalFormatGateway(ProviderProfile(ProviderKind.NVIDIA_NIM), "dummy").start().use { gateway ->
            Socket("127.0.0.1", URI(gateway.url).port).use { client ->
                client.soTimeout = 1000
                client.getOutputStream().write("POST /v1/messages HTTP/1.1\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                assertTrue(client.getInputStream().bufferedReader().readLine().contains("401"))
            }
        }
    }

    @Test fun intermediateClaudeTurnDoesNotCompleteOrKillProcess() {
        val source = source("runtime/ClaudeRuntimeBridge.kt")
        val assistant = source.substringAfter("private suspend fun consumeClaudeJsonEvent").substringAfter("\"assistant\" ->").substringBefore("\"user\" ->")
        assertFalse(assistant.contains("emitCompletedOnce"))
        assertFalse(assistant.contains("terminateActiveProcessGracefully"))
    }

    @Test fun failedAntigravityAttemptDoesNotLoseDiffOrBlindlyReplay() {
        val source = source("runtime/AntigravityRuntimeBridge.kt")
        assertTrue(source.contains("retryBlockedByChanges"))
        assertTrue(source.contains("attemptedAccountIds.remove(it.id)"))
        val failure = source.substringAfter("turnResult.onFailure")
        assertTrue(failure.contains("retryBlockedByChanges"))
    }

    @Test fun antigravitySessionAlwaysClosesOwnedStateOnFailure() {
        val session = source("runtime/AntigravityRuntimeBridge.kt").substringAfter("override suspend fun startSession(")
            .substringBefore("override suspend fun respondToApproval(")
        assertTrue(session.contains("finally {\n            if (activeSessionId == sessionId)"))
        val cleanup = session.substringAfterLast("finally {")
        assertTrue(cleanup.contains("activeTaskId = null"))
        assertTrue(cleanup.contains("RuntimeTaskController.stopAction = null"))
        assertTrue(session.contains("catch (error: Throwable)"))
    }

    @Test fun interruptedGatewayCannotEmitNormalFinish() {
        val source = source("runtime/AntigravityGatewayServer.kt")
        val catch = source.substringAfter("Upstream streaming connection interrupted").substringBefore("when (format)")
        assertTrue(catch.contains("return"))
        assertTrue(source.contains("upstreamCompleted"))
    }

    private fun source(path: String): String {
        val root = if (File("src/main/java").isDirectory) File("src/main/java/com/jarves/mh") else File("app/src/main/java/com/jarves/mh")
        return File(root, path).readText()
    }
}
