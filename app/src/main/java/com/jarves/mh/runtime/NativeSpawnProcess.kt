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
    private val captureFailed: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(),
    private val capturePipe: File? = null,
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

    override fun waitFor(): Int {
        // Do not hold the exitValue monitor during a blocking JNI wait: Stop needs it too.
        while (true) {
            try { return exitValue() }
            catch (_: IllegalThreadStateException) { Thread.sleep(25) }
        }
    }

    internal fun checkCapture() {
        check(!captureFailed.get()) { "Runtime output exceeded its safe capture budget or could not be captured" }
    }

    @Synchronized
    override fun exitValue(): Int {
        result?.let { return it }
        val status = NativeSpawn.waitFor(pid, true)
        if (status == NativeSpawn.STILL_RUNNING) throw IllegalThreadStateException("Process is still running")
        outputPump?.join(1_000)
        if (outputPump?.isAlive == true) {
            captureFailed.set(true)
            NativeSpawn.kill(pid, 9)
            closeStreams()
        }
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
        // Unblock a FIFO reader if the child died before opening its output descriptor.
        capturePipe?.let { pipe -> runCatching {
            val fd = android.system.Os.open(pipe.absolutePath,
                android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_NONBLOCK, 0)
            android.system.Os.close(fd)
        } }
    }

    override fun isAlive(): Boolean = runCatching { exitValue(); false }.getOrDefault(true)

    companion object {
        const val MAX_OUTPUT_BYTES: Long = 5L * 1024 * 1024 // 5 MB
        const val MAX_ARG_STRLEN: Int = 131072 // Linux MAX_ARG_STRLEN (32 pages * 4096)

        fun validateArgv(argv: List<String>) {
            argv.forEachIndexed { index, arg ->
                require(arg.length < MAX_ARG_STRLEN) {
                    "Argument at index $index exceeds maximum allowed length (${arg.length} >= $MAX_ARG_STRLEN). Large payloads must be streamed through stdin, not passed in argv."
                }
            }
        }

        fun start(
            argv: List<String>,
            environment: Map<String, String>,
            cwd: String,
            outputFile: File,
            pseudoTerminal: Boolean = false,
            ptyRows: Int = 40,
            ptyColumns: Int = 120,
        ): NativeSpawnProcess {
            validateArgv(argv)
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(ByteArray(0))
            val pipe = if (pseudoTerminal) null else File(outputFile.parentFile, "${outputFile.name}.pipe").also {
                android.system.Os.mkfifo(it.absolutePath, 0b110000000)
            }
            val spawned = try {
                NativeSpawn.spawn(
                    argv.toTypedArray(), environment.map { "${it.key}=${it.value}" }.toTypedArray(), cwd,
                    pipe?.absolutePath ?: outputFile.absolutePath, pseudoTerminal, ptyRows, ptyColumns,
                ).also { check(it.size == 3 && it[0] > 0) { "Native runtime launch failed" } }
            } catch (error: Throwable) {
                pipe?.delete()
                outputFile.delete()
                throw error
            }
            val failed = java.util.concurrent.atomic.AtomicBoolean()
            val input = ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.adoptFd(spawned[1]))
            val pump = Thread({
                try {
                    val source = if (pipe != null) FileInputStream(pipe) else
                        ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(spawned[2]))
                    source.use { stream -> FileOutputStream(outputFile, false).use { destination ->
                        BoundedProcessCapture.copy(stream, destination, MAX_OUTPUT_BYTES) {
                            failed.set(true)
                            NativeSpawn.kill(spawned[0], 9)
                        }
                    } }
                } catch (_: Throwable) {
                    failed.set(true)
                    runCatching { NativeSpawn.kill(spawned[0], 9) }
                } finally { pipe?.delete() }
            }, "pocket-runtime-output").apply { isDaemon = true; start() }
            return NativeSpawnProcess(spawned[0], outputFile, input, pump, failed, pipe)
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
