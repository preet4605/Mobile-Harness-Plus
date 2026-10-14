package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Long-running streams must not be killed by the 5 MB budget meant for short helper captures. */
class StreamingOutputBudgetTest {
    private fun source(path: String) = File("src/main/java/com/jarves/mh/$path").readText()

    @Test fun streamingBudgetIsLargerThanHelperBudget() {
        assertTrue(NativeSpawnProcess.STREAMING_OUTPUT_BYTES >= 64L * NativeSpawnProcess.MAX_OUTPUT_BYTES / 5)
    }

    @Test fun agentSessionsTerminalsAndBuildsUseStreamingBudget() {
        val expectations = mapOf(
            "runtime/ClaudeRuntimeBridge.kt" to 1,
            "runtime/DshRuntimeBridge.kt" to 1,
            "runtime/AntigravityRuntimeBridge.kt" to 1,
            "runtime/CodexRuntimeBridge.kt" to 1,
            "runtime/task/GuestStepCommandRunner.kt" to 1,
            "ui/MainViewModel.kt" to 3,
        )
        expectations.forEach { (path, count) ->
            val uses = Regex("maxOutputBytes = (com\\.jarves\\.mh\\.runtime\\.)?NativeSpawnProcess\\.STREAMING_OUTPUT_BYTES")
                .findAll(source(path)).count()
            assertTrue("$path should pass the streaming budget $count time(s), found $uses", uses == count)
        }
    }

    @Test fun budgetReachesTheCapture() {
        val spawn = source("runtime/NativeSpawnProcess.kt")
        assertTrue(spawn.contains("BoundedProcessCapture.copy(stream, destination, maxOutputBytes)"))
        assertTrue(source("runtime/RuntimeInstaller.kt").contains("maxOutputBytes = maxOutputBytes,"))
    }
}
