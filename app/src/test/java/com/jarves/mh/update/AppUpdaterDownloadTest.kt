package com.jarves.mh.update

import com.jarves.mh.BuildConfig
import com.jarves.mh.network.FakeHttpConnection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterDownloadTest {
    private val context = RuntimeEnvironment.getApplication()
    private val updates = File(context.filesDir, "updates")
    private val partial = File(updates, "mobile-harness-${BuildConfig.APP_VARIANT}.apk.part")
    private val target = File(updates, "mobile-harness-${BuildConfig.APP_VARIANT}.apk")

    @After
    fun removeDownloads() {
        updates.deleteRecursively()
    }

    private fun download(connection: FakeHttpConnection, sha256: String = ""): Throwable? = runCatching {
        AppUpdater(context, openConnection = { connection }).download(
            AppUpdateInfo(
                versionCode = 9_999L,
                versionName = "test",
                apkUrl = "https://example.test/app.apk",
                sha256 = sha256,
                sizeBytes = 0L,
                notes = "",
            ),
        ) { _, _ -> }
    }.exceptionOrNull()

    @Test
    fun declaredLengthAboveCeilingIsRejectedBeforeAnyBytesAreWritten() {
        val connection = FakeHttpConnection(200, ByteArray(16), contentLength = 512L * 1024 * 1024 + 1)
        val error = download(connection)
        assertTrue("expected a size-limit failure, got $error", error is IOException)
        assertFalse("partial file written for an oversized download", partial.exists())
        assertFalse(target.exists())
        assertTrue(connection.disconnected)
    }

    @Test
    fun checksumMismatchRemovesThePartialFile() {
        val connection = FakeHttpConnection(200, ByteArray(32) { 1 })
        val error = download(connection, sha256 = "0".repeat(64))
        assertTrue("expected a checksum failure, got $error", error?.message?.contains("SHA-256") == true)
        assertFalse("partial file left behind after a failed download", partial.exists())
    }

    @Test
    fun copyStopsOnceTheCeilingIsExceeded() {
        val output = ByteArrayOutputStream()
        val error = runCatching {
            copyUpdateStream(ByteArrayInputStream(ByteArray(64)), output, maxBytes = 16L, total = 64L) { _, _ -> }
        }.exceptionOrNull()
        assertTrue("expected a size-limit failure, got $error", error is IOException)
        assertTrue("wrote past the ceiling: ${output.size()} bytes", output.size() <= 16)
    }

    @Test
    fun copyAcceptsExactlyTheCeiling() {
        val output = ByteArrayOutputStream()
        copyUpdateStream(ByteArrayInputStream(ByteArray(16)), output, maxBytes = 16L, total = 16L) { _, _ -> }
        assertEquals(16, output.size())
    }

    @Test
    fun httpErrorReleasesConnectionAndWritesNothing() {
        val connection = FakeHttpConnection(500)
        val error = download(connection)
        assertTrue("expected the HTTP failure, got $error", error?.message?.contains("HTTP 500") == true)
        assertTrue(connection.disconnected)
        assertFalse(partial.exists())
    }
}
