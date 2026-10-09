package com.jarves.mh.runtime

import com.jarves.mh.model.RuntimeEvent
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RemainingResourceUiRegressionTest {
    @Test fun codexProgressiveSnapshotsRenderExactlyOnce() {
        val mapper = CodexEventMapper("session")
        val frames = listOf("updated" to "hel", "updated" to "hello", "completed" to "hello")
        val text = frames.flatMap { (phase, value) ->
            mapper.map(CodexJsonlParser.parseLine("""{"type":"item.$phase","item":{"id":"a","type":"agent_message","text":"$value"}}"""))
        }.filterIsInstance<RuntimeEvent.AssistantDelta>().map { it.text }
        assertEquals(listOf("hel", "lo"), text)
    }
    @Test fun nonPtyOutputIsPumpedAndInstallerAlwaysCleansUp() {
        assertTrue(source("runtime/NativeSpawnProcess.kt").contains("Os.mkfifo"))
        val verify = source("runtime/RuntimeInstaller.kt").substringAfter("private suspend fun verifyGuest").substringBefore("private fun ensureRootfsCompatibilityLinks")
        assertTrue(verify.contains("finally"))
        assertTrue(verify.contains("destroyForcibly"))
        assertTrue(verify.contains("delete()"))
    }
    private fun source(path: String): String {
        val root = if (File("src/main/java").isDirectory) File("src/main/java/com/jarves/mh") else File("app/src/main/java/com/jarves/mh")
        return File(root, path).readText()
    }
}
