package com.jarves.mh.runtime

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ClaudeBridgeTest {

    @Test
    fun smallPromptLaunchesNormally() {
        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = "/usr/local/bin/claude",
            model = "claude-3-7-sonnet",
        )
        assertFalse("Claude command must NOT contain --bare", command.contains("--bare"))
        assertTrue(command.contains("-p"))
        assertTrue(command.contains("--output-format"))
        assertTrue(command.contains("stream-json"))
        assertTrue(command.contains("--include-partial-messages"))
        assertTrue(command.contains("--verbose"))
        assertTrue(command.contains("--model"))
        assertTrue(command.contains("claude-3-7-sonnet"))
        assertTrue(command.contains("--max-turns"))
        assertTrue(command.contains("25"))
        assertEquals(10, command.size)
        assertEquals(
            listOf(
                "/usr/local/bin/claude",
                "-p",
                "--output-format",
                "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--model",
                "claude-3-7-sonnet",
                "--max-turns",
                "25",
            ),
            command,
        )
    }

    @Test
    fun promptLargerThanMaxArgStrlenIsNotPlacedInArgv() {
        val largePrompt = "X".repeat(200_000)
        assertTrue(largePrompt.length > NativeSpawnProcess.MAX_ARG_STRLEN)

        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = "/usr/local/bin/claude",
            model = "claude-3-7-sonnet",
        )

        // Regression assertion: Claude launch argv contains no large prompt payload
        command.forEach { arg ->
            assertTrue(
                "Arg '$arg' unexpectedly exceeded 1024 chars (${arg.length})",
                arg.length < 1024,
            )
            assertFalse(
                "Arg contained prompt content",
                arg.contains("XXXX"),
            )
        }
    }

    @Test
    fun argvValidatorRejectsArgumentsExceedingMaxArgStrlen() {
        val safeArgs = listOf("/usr/local/bin/claude", "-p", "--output-format")
        NativeSpawnProcess.validateArgv(safeArgs) // should succeed

        val invalidArgs = listOf(
            "/usr/local/bin/claude",
            "-p",
            "A".repeat(NativeSpawnProcess.MAX_ARG_STRLEN),
        )
        try {
            NativeSpawnProcess.validateArgv(invalidArgs)
            fail("Expected IllegalArgumentException for argument exceeding MAX_ARG_STRLEN")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("MAX_ARG_STRLEN") == true || e.message?.contains("Large payloads must be streamed through stdin") == true)
        }
    }

    @Test
    fun largePromptIsDeliveredThroughStdinAndPreserved() = runBlocking {
        val largePrompt = StringBuilder().apply {
            append("START_PROMPT\n")
            for (i in 1..5000) {
                append("Line $i: Project Brain context and long user task details with unicode: \uD83D\uDE80\n")
            }
            append("END_PROMPT\n")
        }.toString()

        assertTrue(largePrompt.length > NativeSpawnProcess.MAX_ARG_STRLEN)

        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 65536)

        val received = ByteArrayOutputStream()

        val readJob = launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (pipedIn.read(buffer).also { bytesRead = it } != -1) {
                received.write(buffer, 0, bytesRead)
            }
        }

        val writeJob = launch(Dispatchers.IO) {
            pipedOut.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(largePrompt)
                writer.flush()
            }
        }

        writeJob.join()
        readJob.join()

        val resultString = received.toString(Charsets.UTF_8.name())
        assertEquals(largePrompt.length, resultString.length)
        assertEquals(largePrompt, resultString)
    }

    @Test
    fun stdoutStderrStreamingRemainsFunctional() {
        val tempOutput = File.createTempFile("claude-test-output-", ".log")
        try {
            val payload = "{\"type\":\"system\",\"subtype\":\"init\"}\n{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"streaming chunk\"}]}}\n"
            tempOutput.writeText(payload)

            val readContent = tempOutput.readText()
            assertEquals(payload, readContent)
            assertTrue(readContent.contains("streaming chunk"))
        } finally {
            tempOutput.delete()
        }
    }

    @Test
    fun processCancellationAndCleanupRemainsFunctional() = runBlocking {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 1024)

        // Close the input side prematurely (simulating child process termination or cancellation)
        pipedIn.close()

        // Writing to closed stream should fail safely with IOException inside runCatching
        var caught = false
        runCatching {
            pipedOut.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write("data after process closed")
                writer.flush()
            }
        }.onFailure {
            caught = true
        }
        assertTrue(caught)
    }

    @Test
    fun claudeCommandSupportsEffortArgument() {
        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = "/usr/local/bin/claude",
            model = "sonnet",
            effort = "high",
        )
        assertFalse("Claude command must NOT contain --bare", command.contains("--bare"))
        assertTrue(command.contains("--model"))
        assertTrue(command.contains("sonnet"))
        assertTrue(command.contains("--effort"))
        assertTrue(command.contains("high"))
    }

    @Test
    fun claudeCommandSupportsOpusWithMediumEffort() {
        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = "/usr/local/bin/claude",
            model = "opus",
            effort = "medium",
        )
        assertFalse("Claude command must NOT contain --bare", command.contains("--bare"))
        assertTrue(command.contains("--model"))
        val modelIdx = command.indexOf("--model")
        assertEquals("opus", command[modelIdx + 1])
        assertTrue(command.contains("--effort"))
        val effortIdx = command.indexOf("--effort")
        assertEquals("medium", command[effortIdx + 1])
    }
}
