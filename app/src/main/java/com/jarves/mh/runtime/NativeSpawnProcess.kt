package com.jarves.mh.runtime

import android.os.ParcelFileDescriptor
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

internal class NativeSpawnProcess private constructor(
    private val pid: Int,
    internal val outputFile: File,
    private val stdin: OutputStream,
    private val outputPump: Thread? = null,
) : Process() {
    val processPid: Int get() = pid
    @Volatile private var result: Int? = null
    @Volatile private var cachedInputStream: InputStream? = null

    override fun getOutputStream(): OutputStream = stdin

    @Synchronized
    override fun getInputStream(): InputStream {
        return cachedInputStream ?: FileInputStream(outputFile).also { cachedInputStream = it }
    }

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    @Synchronized
    override fun waitFor(): Int {
        result?.let {
            outputPump?.join(1_000)
            return it
        }
        return NativeSpawn.waitFor(pid, false).also {
            result = it
            outputPump?.join(1_000)
        }
    }

    @Synchronized
    override fun exitValue(): Int {
        result?.let { return it }
        val status = NativeSpawn.waitFor(pid, true)
        if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
        outputPump?.join(1_000)
        return status.also { result = it }
    }

    override fun destroy() {
        NativeSpawn.kill(pid, 15)
        closeStreams()
    }

    /** Send the same interrupt signal produced by Ctrl+C in a real terminal. */
    internal fun interrupt() {
        NativeSpawn.kill(pid, 2)
    }

    override fun destroyForcibly(): Process {
        NativeSpawn.kill(pid, 9)
        closeStreams()
        return this
    }

    private fun closeStreams() {
        runCatching { stdin.close() }
        runCatching { cachedInputStream?.close() }
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        const val MAX_OUTPUT_BYTES: Long = 5L * 1024 * 1024 // 5 MB

        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            outputFile.parentFile?.mkdirs()
            if (pseudoTerminal) outputFile.delete()
            val spawned = NativeSpawn.spawn(
                argv.toTypedArray(),
                environment.map { "${it.key}=${it.value}" }.toTypedArray(),
                cwd,
                outputFile.absolutePath,
                pseudoTerminal,
                ptyRows,
                ptyColumns,
            )
            check(spawned.size == 3 && spawned[0] > 0) { "Native runtime launch failed" }
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val pump = spawned[2].takeIf { it >= 0 }?.let { outputFd ->
                Thread({
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(outputFd)).use { source ->
                            FileOutputStream(outputFile, false).use { destination ->
                                val buffer = ByteArray(8192)
                                var totalWritten = 0L
                                var bytesRead: Int
                                while (source.read(buffer).also { bytesRead = it } != -1) {
                                    if (totalWritten < MAX_OUTPUT_BYTES) {
                                        val toWrite = if (totalWritten + bytesRead > MAX_OUTPUT_BYTES) {
                                            (MAX_OUTPUT_BYTES - totalWritten).toInt()
                                        } else {
                                            bytesRead
                                        }
                                        destination.write(buffer, 0, toWrite)
                                        destination.flush()
                                        totalWritten += toWrite
                                        if (totalWritten >= MAX_OUTPUT_BYTES) {
                                            destination.write("\n\n[Mobile-Harness: Process output truncated at 5MB limit]\n".toByteArray())
                                            destination.flush()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }, "pocket-pty-output").apply {
                    isDaemon = true
                    start()
                }
            }
            return NativeSpawnProcess(spawned[0], outputFile, input, pump)
        }

        fun isPidAlive(targetPid: Int): Boolean {
            if (targetPid <= 1) return false
            return runCatching {
                NativeSpawn.kill(targetPid, 0) == 0
            }.getOrDefault(false)
        }
    }
}

internal object NativeSpawn {
    const val STILL_RUNNING = -2

    init {
        runCatching {
            System.loadLibrary("pocketspawn")
        }
    }

    external fun spawn(
        argv: Array<String>,
        environment: Array<String>,
        cwd: String,
        outputFile: String,
        pseudoTerminal: Boolean,
        ptyRows: Int,
        ptyColumns: Int,
    ): IntArray
    external fun waitFor(pid: Int, noHang: Boolean): Int
    external fun kill(pid: Int, signal: Int): Int
}
