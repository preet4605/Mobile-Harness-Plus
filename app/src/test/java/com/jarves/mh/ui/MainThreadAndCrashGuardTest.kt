package com.jarves.mh.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainThreadAndCrashGuardTest {
    private fun source(path: String) = File("src/main/java/com/jarves/mh/$path").readText()

    @Test fun runtimeCollectorsAreGuarded() {
        val vm = source("ui/MainViewModel.kt")
        for (bridge in listOf("claudeRuntime", "dshRuntime", "antigravityRuntime", "codexRuntime")) {
            assertTrue(vm.contains("$bridge.events.coalesceAssistantDeltas().collect { onRuntimeEventSafely(it,"))
        }
        val guard = vm.substringAfter("private fun onRuntimeEventSafely(").substringBefore("private fun onRuntimeEvent(")
        assertTrue("cancellation must still propagate", guard.contains("catch (cancelled: kotlinx.coroutines.CancellationException) {\n            throw cancelled"))
    }

    @Test fun fallbackSetsAreThreadSafe() {
        val vm = source("ui/MainViewModel.kt")
        assertTrue(vm.contains("private val failedApiKeyIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()"))
        assertTrue(vm.contains("private val attemptedProfileIds: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()"))
    }

    @Test fun workspaceEmptinessChecksStopAtFirstFile() {
        val vm = source("ui/MainViewModel.kt")
        assertFalse(vm.contains("}.count()\n            } else 0"))
        assertFalse(vm.contains("}.count()\n                } else 0"))
        assertEquals(2, Regex("workspaceDir.walkTopDown\\(\\).any \\{").findAll(vm).count())
    }

    @Test fun endOfTaskMemoryWorkRunsOffMain() {
        val finish = source("ui/MainViewModel.kt").substringAfter("private fun finishSupervisedTask(").substringBefore("/** Spec v2 §6.4")
        val io = finish.indexOf("viewModelScope.launch(Dispatchers.IO) {")
        assertTrue(io >= 0 && io < finish.indexOf("extractMemoryFromSession(record.projectId, finishedMessages)"))
    }

    @Test fun registryStartSurvivesAFailedStateWrite() {
        val start = source("mods/ModRegistry.kt").substringAfter("fun onProcessStart()").substringBefore("if (starts >= SAFE_MODE_STARTS)")
        assertTrue(start.contains("runCatching { writeStateLocked() }"))
    }

    @Test fun gatewayAcceptLoopsCannotSpin() {
        for (name in listOf("AntigravityGatewayServer.kt", "LocalFormatGateway.kt", "OpenRouterRoutingGateway.kt")) {
            val code = source("runtime/$name")
            assertTrue(name, code.contains("if (server.isClosed) return\n                Thread.sleep(GATEWAY_ACCEPT_RETRY_MS)"))
        }
    }
}
