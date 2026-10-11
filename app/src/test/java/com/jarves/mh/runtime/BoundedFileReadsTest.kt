package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedFileReadsTest {
    @Test
    fun largeFileReturnsOnlyItsTail() {
        val file = File.createTempFile("tail", ".log").apply { deleteOnExit() }
        file.writeText("a".repeat(1_000) + "END")

        assertEquals("aaEND", file.readTailText(5))
    }

    @Test
    fun smallFileIsReadWhole() {
        val file = File.createTempFile("tail", ".log").apply { deleteOnExit() }
        file.writeText("short")

        assertEquals("short", file.readTailText(1_024))
    }

    @Test
    fun missingFileOrNoBudgetReadsNothing() {
        val missing = File(System.getProperty("java.io.tmpdir"), "mh-missing-${System.nanoTime()}.log")
        val file = File.createTempFile("tail", ".log").apply { deleteOnExit(); writeText("data") }

        assertEquals("", missing.readTailText(10))
        assertEquals("", file.readTailText(0))
    }
}
