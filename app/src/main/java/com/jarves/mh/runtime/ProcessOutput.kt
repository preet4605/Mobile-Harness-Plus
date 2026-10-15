package com.jarves.mh.runtime

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Raised by a process's capture pump after each write to its output file and once when the stream
 * ends, so a reader waiting for output wakes as soon as bytes land instead of on its next poll.
 * A wait always has a timeout: a missed signal costs one poll interval, never a hang.
 */
internal class OutputSignal {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val blockingWake = Semaphore(0)

    /** True once the pump has stopped writing; readers then only wait for the process to exit. */
    @Volatile var ended = false
        private set

    fun written() {
        wake.trySend(Unit)
        if (blockingWake.availablePermits() == 0) blockingWake.release()
    }

    fun end() {
        ended = true
        written()
    }

    /** Suspends until output is written or [timeoutMs] passes. True when woken by output. */
    suspend fun await(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { wake.receive() } != null

    /** [await] for reader threads that are not coroutines. */
    fun awaitBlocking(timeoutMs: Long): Boolean {
        val woken = blockingWake.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)
        blockingWake.drainPermits()
        return woken
    }
}

/** The pump's file stream: every flush (one per chunk read from the process) wakes the readers. */
internal class SignalingOutputStream(
    private val destination: OutputStream,
    private val signal: OutputSignal,
) : OutputStream() {
    override fun write(b: Int) = destination.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = destination.write(b, off, len)
    override fun flush() {
        destination.flush()
        signal.written()
    }
    override fun close() = destination.close()
}

/** Reads a growing capture file through one open handle, instead of reopening it for every chunk. */
internal class CaptureFileReader(private val file: File) : Closeable {
    private var handle: RandomAccessFile? = null

    fun read(offset: Long, buffer: ByteArray, length: Int = buffer.size): Int {
        val input = handle ?: RandomAccessFile(file, "r").also { handle = it }
        input.seek(offset)
        return input.read(buffer, 0, length)
    }

    override fun close() {
        runCatching { handle?.close() }
        handle = null
    }
}
