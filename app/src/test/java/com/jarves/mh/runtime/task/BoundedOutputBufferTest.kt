package com.jarves.mh.runtime.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutputBufferTest {

    @Test
    fun `test buffer line count limit drops oldest lines`() {
        val buffer = BoundedOutputBuffer(maxLines = 5, maxBytes = 1024 * 1024)

        for (i in 1..10) {
            buffer.appendLine("Line $i")
        }

        assertEquals(5, buffer.lineCount)
        val lines = buffer.getLines()
        assertEquals(listOf("Line 6", "Line 7", "Line 8", "Line 9", "Line 10"), lines)
    }

    @Test
    fun `test buffer byte limit drops oldest lines when size exceeds limit`() {
        // max 200 bytes
        val buffer = BoundedOutputBuffer(maxLines = 100, maxBytes = 200)

        // 10 lines of 50 chars each -> ~100 bytes each
        for (i in 1..5) {
            buffer.appendLine("01234567890123456789012345678901234567890123456789")
        }

        assertTrue(buffer.byteCount <= 200)
        assertTrue(buffer.lineCount < 5)
    }

    @Test
    fun `test latest error is detected and retained`() {
        val buffer = BoundedOutputBuffer(maxLines = 10)

        buffer.appendLine("Task started")
        buffer.appendLine("Running gradle build")
        buffer.appendLine("error: unresolved reference 'foo'")
        buffer.appendLine("Build finished with status 1")

        assertNotNull(buffer.getLastError())
        assertTrue(buffer.getLastError()?.contains("unresolved reference") == true)
    }

    @Test
    fun `test appendText splits multiple newlines correctly`() {
        val buffer = BoundedOutputBuffer(maxLines = 10)
        buffer.appendText("Line 1\nLine 2\r\nLine 3\n")

        val lines = buffer.getLines()
        assertEquals(3, lines.size)
        assertEquals("Line 1", lines[0])
        assertEquals("Line 2", lines[1])
        assertEquals("Line 3", lines[2])
    }

    @Test
    fun `test clear resets buffer state`() {
        val buffer = BoundedOutputBuffer(maxLines = 10)
        buffer.appendLine("Error: failed")
        assertEquals(1, buffer.lineCount)
        assertNotNull(buffer.getLastError())

        buffer.clear()
        assertEquals(0, buffer.lineCount)
        assertEquals(0, buffer.byteCount)
        assertNull(buffer.getLastError())
    }
}
