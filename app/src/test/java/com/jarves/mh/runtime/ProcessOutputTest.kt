package com.jarves.mh.runtime

import com.jarves.mh.ui.TERMINAL_IDLE_POLL_MIN_MS
import com.jarves.mh.ui.awaitTerminalOutput
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessOutputTest {
    private fun millisOf(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test fun awaitEndsWhenOutputIsWritten() = runBlocking {
        val signal = OutputSignal()
        val waiter = async { signal.await(5_000) }
        delay(20)
        signal.written()
        assertTrue(waiter.await())
    }

    @Test fun awaitTimesOutWithoutOutput() = runBlocking {
        assertFalse(OutputSignal().await(10))
    }

    @Test fun outputWrittenBeforeTheWaitIsNotMissed() = runBlocking {
        val signal = OutputSignal()
        signal.written()
        signal.written()
        assertTrue("a pending write wakes the next wait at once", signal.await(5_000))
        assertFalse("several writes wake one wait, not several", signal.await(10))
    }

    @Test fun blockingWaitEndsWhenOutputIsWritten() {
        val signal = OutputSignal()
        thread { Thread.sleep(20); signal.written() }
        val ms = millisOf { assertTrue(signal.awaitBlocking(5_000)) }
        assertTrue("woke after $ms ms", ms < 2_000)
        assertFalse(signal.awaitBlocking(10))
    }

    @Test fun endWakesWaitersAndIsRemembered() = runBlocking {
        val signal = OutputSignal()
        signal.end()
        assertTrue(signal.ended)
        assertTrue(signal.await(5_000))
    }

    @Test fun pumpFlushSignalsAfterTheBytesAreWritten() = runBlocking {
        val signal = OutputSignal()
        val destination = ByteArrayOutputStream()
        SignalingOutputStream(destination, signal).use { stream ->
            stream.write(byteArrayOf(1, 2, 3), 0, 3)
            assertFalse("no signal before flush", signal.await(10))
            stream.flush()
        }
        assertTrue(signal.await(5_000))
        assertArrayEquals(byteArrayOf(1, 2, 3), destination.toByteArray())
    }

    @Test fun captureMatchesTheSignalingStreamUnderItsBudget() = runBlocking {
        val signal = OutputSignal()
        val destination = ByteArrayOutputStream()
        var overflow = false
        BoundedProcessCapture.copy(ByteArray(100) { it.toByte() }.inputStream(), SignalingOutputStream(destination, signal), 64) { overflow = true }
        assertTrue(overflow)
        assertEquals(64, destination.size())
        assertTrue(signal.await(5_000))
    }

    @Test fun readerFollowsAGrowingFileThroughOneHandle() {
        val dir = Files.createTempDirectory("mh-capture").toFile()
        try {
            val file = File(dir, "out.log").apply { writeText("hello ") }
            CaptureFileReader(file).use { reader ->
                val buffer = ByteArray(16)
                var offset = 0L
                var count = reader.read(offset, buffer, 6)
                assertEquals("hello ", String(buffer, 0, count))
                offset += count
                file.appendText("world")
                count = reader.read(offset, buffer, (file.length() - offset).toInt())
                assertEquals("world", String(buffer, 0, count))
                offset += count
                assertEquals(-1, reader.read(offset, buffer, 4))
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun idlePollWakesOnOutputAndPollsAfterTheStreamEnds() = runBlocking {
        val signal = OutputSignal()
        val poll = IdlePoll(minMs = 2_000, maxMs = 2_000, signal = signal, signalMaxMs = 4_000)
        launchWrite(signal)
        val woken = millisOf { runBlocking { poll.await() } }
        assertTrue("woke after $woken ms", woken < 1_500)

        val ended = OutputSignal().apply { end() }
        val afterEnd = IdlePoll(minMs = 1, maxMs = 4, signal = ended, signalMaxMs = 1_000)
        afterEnd.await() // takes the end token
        val ms = millisOf { runBlocking { repeat(4) { afterEnd.await() } } }
        assertTrue("an ended stream polls with the short cap, took $ms ms", ms < 500)
    }

    @Test fun terminalWaitResetsWhenOutputArrives() {
        val signal = OutputSignal()
        thread { Thread.sleep(20); signal.written() }
        assertEquals(TERMINAL_IDLE_POLL_MIN_MS, awaitTerminalOutput(signal, 800))
        assertEquals("backs off past the old cap while waiting", 1_000L, awaitTerminalOutput(signal, 600))
        assertEquals("no signal keeps the old cap", 100L, awaitTerminalOutput(null, 50))
    }

    @Test fun everyReadLoopWakesOnOutput() {
        val runtime = "src/main/java/com/jarves/mh/runtime"
        for (name in listOf("ClaudeRuntimeBridge.kt", "DshRuntimeBridge.kt", "AntigravityRuntimeBridge.kt", "CodexAppServerRunner.kt", "CodexTurnRunner.kt")) {
            val code = File("$runtime/$name").readText()
            assertTrue("$name uses the output signal", code.contains("outputSignal"))
            assertFalse("$name no longer reopens the capture file per chunk", code.contains("RandomAccessFile("))
        }
        val terminals = listOf("MainViewModel.kt", "QuickTerminalController.kt")
            .joinToString("\n") { File("src/main/java/com/jarves/mh/ui/$it").readText() }
        assertEquals("both terminals wait on the signal", 2, Regex("awaitTerminalOutput\\(").findAll(terminals).count())
    }

    private fun launchWrite(signal: OutputSignal) {
        thread { Thread.sleep(20); signal.written() }
    }
}
