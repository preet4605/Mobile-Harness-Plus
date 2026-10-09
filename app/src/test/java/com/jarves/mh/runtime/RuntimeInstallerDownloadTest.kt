package com.jarves.mh.runtime

import com.jarves.mh.network.FakeHttpConnection
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeInstallerDownloadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun installer() = RuntimeInstaller(RuntimeEnvironment.getApplication())

    @Test
    fun fetchTextReleasesConnectionOnHttpError() {
        val connection = FakeHttpConnection(503, "unavailable".toByteArray())
        val error = runCatching {
            installer().fetchText("https://example.test/manifest.json", openConnection = { connection })
        }.exceptionOrNull()
        assertTrue("expected the HTTP failure, got $error", error is IllegalStateException)
        assertTrue("connection left open after an HTTP error", connection.disconnected)
    }

    @Test
    fun fetchTextReleasesConnectionWhenBodyReadFails() {
        val connection = FakeHttpConnection(200, readFailure = IOException("connection reset"))
        val error = runCatching {
            installer().fetchText("https://example.test/manifest.json", openConnection = { connection })
        }.exceptionOrNull()
        assertTrue("expected the read failure, got $error", error is IOException)
        assertTrue("connection left open after a read failure", connection.disconnected)
    }

    @Test
    fun downloadReleasesConnectionOnHttpError() = runBlocking {
        val connection = FakeHttpConnection(404)
        val error = runCatching {
            installer().downloadVerified(
                url = "https://example.test/runtime.bin",
                destination = File(folder.root, "runtime.bin"),
                expectedChecksum = "00",
                openConnection = { connection },
            ) { _, _ -> }
        }.exceptionOrNull()
        assertTrue("expected the HTTP failure, got $error", error is IllegalStateException)
        assertTrue("connection left open after an HTTP error", connection.disconnected)
    }

    @Test
    fun downloadReleasesConnectionWhenBodyReadFails() = runBlocking {
        val connection = FakeHttpConnection(200, readFailure = IOException("connection reset"))
        val error = runCatching {
            installer().downloadVerified(
                url = "https://example.test/runtime.bin",
                destination = File(folder.root, "runtime.bin"),
                expectedChecksum = "00",
                openConnection = { connection },
            ) { _, _ -> }
        }.exceptionOrNull()
        assertTrue("expected the read failure, got $error", error is IOException)
        assertTrue("connection left open after a read failure", connection.disconnected)
    }
}
