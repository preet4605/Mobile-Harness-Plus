package com.jarves.mh.ui

import android.app.ApplicationExitInfo
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDiagnosticsTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun countsUtf8WithoutCreatingEncodedCopies() {
        assertEquals(0L, utf8SizeInBytes(""))
        assertEquals(5L, utf8SizeInBytes("hello"))
        assertEquals(2L, utf8SizeInBytes("é"))
        assertEquals(6L, utf8SizeInBytes("नम"))
        assertEquals(4L, utf8SizeInBytes("🚀"))
        assertEquals(11L, utf8SizeInBytes("Aन🚀éB"))
        // A lone surrogate counts as one three-byte character.
        assertEquals(3L, utf8SizeInBytes("\uD83D"))
        val mixed = "line one\nदूसरी पंक्ति 🚀 end"
        assertEquals(mixed.toByteArray(Charsets.UTF_8).size.toLong(), utf8SizeInBytes(mixed))
    }

    @Test
    fun formatsBytesWithStableUnits() {
        assertEquals("0 B", formatDiagnosticBytes(0))
        assertEquals("0 B", formatDiagnosticBytes(-5))
        assertEquals("1023 B", formatDiagnosticBytes(1_023))
        assertEquals("1.0 KB", formatDiagnosticBytes(1_024))
        assertEquals("1.5 MB", formatDiagnosticBytes(1_572_864))
        assertEquals("2.00 GB", formatDiagnosticBytes(2L * 1_073_741_824L))
    }

    @Test
    fun exitReasonConstantsMatchTheFramework() {
        assertEquals(ApplicationExitInfo.REASON_LOW_MEMORY, EXIT_REASON_LOW_MEMORY)
        assertEquals(ApplicationExitInfo.REASON_CRASH, EXIT_REASON_CRASH)
        assertEquals(ApplicationExitInfo.REASON_CRASH_NATIVE, EXIT_REASON_CRASH_NATIVE)
        assertEquals(ApplicationExitInfo.REASON_ANR, EXIT_REASON_ANR)
        assertEquals(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, EXIT_REASON_EXCESSIVE_RESOURCE_USAGE)
    }

    @Test
    fun formatsOnlyAbnormalExits() {
        assertEquals("Low memory · 1970-01-01 00:01", formatExitSummary(EXIT_REASON_LOW_MEMORY, 60_000L, null, utc))
        assertEquals("App not responding · 1970-01-01 00:00 · input dispatch timed out",
            formatExitSummary(EXIT_REASON_ANR, 0L, "  input dispatch timed out ", utc))
        assertEquals("Crash · 1970-01-01 00:00", formatExitSummary(EXIT_REASON_CRASH, 0L, "   ", utc))
        assertEquals(160, formatExitSummary(EXIT_REASON_CRASH_NATIVE, 0L, "x".repeat(500), utc)!!.substringAfterLast(" · ").length)
        assertNull(formatExitSummary(ApplicationExitInfo.REASON_EXIT_SELF, 0L, "normal", utc))
        assertNull(formatExitSummary(ApplicationExitInfo.REASON_USER_REQUESTED, 0L, null, utc))
        assertNull(abnormalExitLabel(0))
    }

    @Test
    fun onlyIdleUnopenedRuntimeOutputLogsAreDisposable() {
        val now = 10 * RUNTIME_OUTPUT_IDLE_MS
        val old = now - RUNTIME_OUTPUT_IDLE_MS
        assertTrue(isDisposableRuntimeOutput("runtime-output-1.log", true, old, now, openInProcess = false))
        assertFalse(isDisposableRuntimeOutput("runtime-output-1.log", true, old, now, openInProcess = true))
        assertFalse(isDisposableRuntimeOutput("runtime-output-1.log", true, now - 1_000L, now, openInProcess = false))
        assertFalse(isDisposableRuntimeOutput("runtime-output-1.log.pipe", false, old, now, openInProcess = false))
        assertFalse(isDisposableRuntimeOutput("runtime-output-1.log", false, old, now, openInProcess = false))
        assertFalse(isDisposableRuntimeOutput("codex-output-session.log", true, old, now, openInProcess = false))
        assertFalse(isRuntimeOutputName("runtime-output-1.log.pipe"))
        assertTrue(isRuntimeOutputName("runtime-output-1.log"))
    }
}
