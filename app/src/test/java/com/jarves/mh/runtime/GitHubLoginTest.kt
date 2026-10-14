package com.jarves.mh.runtime

import java.nio.file.Files
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GitHubLoginTest {
    @Test fun `device code is published once and successful process is closed`() = runBlocking {
        val output = Files.createTempFile("github-fixture", ".log").toFile()
        output.writeText("First copy your one-time code: ABCD-1234\n")
        val codes = mutableListOf<String>()
        val process = AuthFixtureProcess(false)
        completeGitHubLogin(process, output, { codes += it })
        assertEquals(listOf("ABCD-1234"), codes)
        assertFalse(output.exists())
    }

    @Test fun `cancelling login reaps old child before replacement starts`() = runBlocking {
        val output = Files.createTempFile("github-fixture", ".log").toFile()
        val process = AuthFixtureProcess()
        val job = launch { completeGitHubLogin(process, output, {}) }
        yield()
        job.cancelAndJoin()
        assertTrue(process.destroyed)
        assertFalse(output.exists())
        val nextOutput = Files.createTempFile("github-fixture", ".log").toFile()
        completeGitHubLogin(AuthFixtureProcess(false), nextOutput, {})
        assertFalse(nextOutput.exists())
    }

    @Test fun `silent CLI times out and is killed`() = runBlocking {
        val output = Files.createTempFile("github-fixture", ".log").toFile()
        val process = AuthFixtureProcess()
        try {
            completeGitHubLogin(process, output, {}, timeoutMs = 0)
            fail("Expected timeout")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("timed out"))
        }
        assertTrue(process.destroyed)
        assertFalse(output.exists())
    }
}
