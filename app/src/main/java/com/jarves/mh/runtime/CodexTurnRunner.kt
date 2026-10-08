package com.jarves.mh.runtime

import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the runner needs from a running Codex process. The production implementation wraps
 * [NativeSpawnProcess]; tests supply an in-memory fake so every failure mode can be exercised
 * on the JVM without a device.
 */
internal interface CodexProcessIo {
    val isAlive: Boolean

    /** Bytes captured so far. stdout and stderr share one capture file. */
    fun outputLength(): Long
    fun readOutput(offset: Long, buffer: ByteArray, length: Int): Int
    fun writeInput(text: String)
    fun closeInput()
    fun interrupt()
    fun destroy()
    fun destroyForcibly()
    fun waitFor(): Int
}

internal class NativeCodexProcessIo(private val process: NativeSpawnProcess) : CodexProcessIo {
    override val isAlive: Boolean get() = process.isAlive
    override fun outputLength(): Long = process.outputFile.length()
    override fun readOutput(offset: Long, buffer: ByteArray, length: Int): Int =
        RandomAccessFile(process.outputFile, "r").use { file ->
            file.seek(offset)
            file.read(buffer, 0, length)
        }

    override fun writeInput(text: String) {
        val stream = process.outputStream
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    override fun closeInput() {
        runCatching { process.outputStream.close() }
    }

    override fun interrupt() = process.interrupt()
    override fun destroy() = process.destroy()
    override fun destroyForcibly() {
        process.destroyForcibly()
    }

    override fun waitFor(): Int = process.waitFor()
}

internal data class CodexRunnerConfig(
    val pollMs: Long = 50L,
    /** Retry notices ("Reconnecting...") with no progress for this long end the run as a network failure. */
    val transientStallMs: Long = 120_000L,
    /** How long a finished turn may linger before the process is terminated. */
    val exitGraceMs: Long = 3_000L,
    /** How long after SIGINT before a stopped process is force-killed. */
    val stopGraceMs: Long = 2_000L,
    val maxLineBytes: Int = 1 shl 20,
)

internal data class CodexRunResult(
    val completed: Boolean,
    val failure: String,
    val exitCode: Int,
    val threadId: String?,
)

/**
 * Drives one `codex exec --json` process: writes the prompt to stdin, drains the capture file
 * line by line, forwards parsed [CodexEvent]s, and decides exactly one outcome.
 *
 * Stability rules this class enforces:
 * - It never throws (except coroutine cancellation). Parser, consumer and I/O failures degrade
 *   into a failed [CodexRunResult].
 * - Completion is decided by turn events plus process exit and the last-message file, never by a
 *   single stream event, because the 5 MB capture cap can truncate the stream.
 * - Retry notices cannot keep a run alive forever ([CodexRunnerConfig.transientStallMs]).
 * - A lingering process after a finished turn, a stop request or a stall is terminated.
 */
internal class CodexTurnRunner(
    private val io: CodexProcessIo,
    private val prompt: String,
    private val emit: suspend (CodexEvent) -> Unit,
    private val isStopRequested: () -> Boolean = { false },
    private val readLastMessage: () -> String? = { null },
    private val now: () -> Long = { System.nanoTime() / 1_000_000L },
    private val config: CodexRunnerConfig = CodexRunnerConfig(),
) {
    @Volatile private var promptFailure: String? = null

    suspend fun run(): CodexRunResult {
        // Deliberately not a child of this coroutine: a write blocked on a full pipe cannot be
        // cancelled and must not hold up the result. It ends when the process exits or stdin closes.
        val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { sendPrompt() }
        try {
            return drain()
        } finally {
            writer.cancel()
            runCatching { io.closeInput() }
        }
    }

    private fun sendPrompt() {
        try {
            io.writeInput(prompt)
        } catch (e: Exception) {
            promptFailure = "Could not send the prompt to Codex."
        } finally {
            runCatching { io.closeInput() }
        }
    }

    private suspend fun drain(): CodexRunResult {
        val assembler = CodexLineAssembler(config.maxLineBytes)
        val buffer = ByteArray(READ_CHUNK_BYTES)
        val diagnostics = ArrayDeque<String>()
        var offset = 0L
        var threadId: String? = null
        var sawTurnCompleted = false
        var sawAgentMessage = false
        var turnFailure: String? = null
        var lastErrorMessage: String? = null
        var stallFailure: String? = null
        var transientSince = NONE
        var terminalAt = NONE
        var terminateAt = NONE
        var stopSignalAt = NONE
        var forceKilledAt = NONE
        var stuckReads = 0

        fun terminate(t: Long) {
            if (terminateAt == NONE) {
                terminateAt = t
                runCatching { io.destroy() }
            }
        }

        suspend fun handle(event: CodexEvent) {
            when (event) {
                is CodexEvent.ThreadStarted -> {
                    threadId = event.threadId
                    transientSince = NONE
                }
                is CodexEvent.AgentMessage -> {
                    sawAgentMessage = true
                    transientSince = NONE
                }
                is CodexEvent.TurnCompleted -> {
                    sawTurnCompleted = true
                    terminalAt = now()
                    transientSince = NONE
                }
                is CodexEvent.TurnFailed -> {
                    turnFailure = event.message
                    terminalAt = now()
                }
                is CodexEvent.StreamError ->
                    if (event.transient) {
                        if (transientSince == NONE) transientSince = now()
                    } else {
                        lastErrorMessage = event.message
                    }
                is CodexEvent.Diagnostic -> {
                    if (!isNoise(event.text)) {
                        diagnostics.addLast(event.text)
                        while (diagnostics.size > MAX_DIAGNOSTIC_LINES) diagnostics.removeFirst()
                    }
                }
                is CodexEvent.Notice, CodexEvent.Ignored -> Unit
                else -> transientSince = NONE
            }
            safeEmit(event)
        }

        try {
            while (true) {
                val alive = io.isAlive
                val length = io.outputLength()
                if (!alive && length <= offset) break
                val t = now()

                if (alive && isStopRequested()) {
                    if (stopSignalAt == NONE) {
                        stopSignalAt = t
                        runCatching { io.interrupt() }
                    } else if (forceKilledAt == NONE && t - stopSignalAt >= config.stopGraceMs) {
                        forceKilledAt = t
                        runCatching { io.destroyForcibly() }
                    }
                }
                if (alive && transientSince != NONE && t - transientSince >= config.transientStallMs) {
                    if (stallFailure == null) {
                        stallFailure = "Network connection interrupted. Codex could not reach the provider."
                    }
                    terminate(t)
                }
                if (alive && terminalAt != NONE && t - terminalAt >= config.exitGraceMs) terminate(t)
                if (alive && terminateAt != NONE && forceKilledAt == NONE && t - terminateAt >= config.exitGraceMs) {
                    forceKilledAt = t
                    runCatching { io.destroyForcibly() }
                }
                // A process that survives SIGKILL is abandoned rather than waited on forever.
                if (alive && forceKilledAt != NONE && t - forceKilledAt >= config.exitGraceMs) break

                val available = length - offset
                if (available <= 0L) {
                    delay(config.pollMs)
                    continue
                }
                val count = io.readOutput(offset, buffer, minOf(available, buffer.size.toLong()).toInt())
                if (count <= 0) {
                    // The capture file shrank or cannot be read; do not spin on it forever.
                    if (++stuckReads > MAX_STUCK_READS) break
                    delay(config.pollMs)
                    continue
                }
                stuckReads = 0
                offset += count
                for (line in assembler.feed(buffer, count)) handle(CodexJsonlParser.parseLine(line))
            }
            assembler.flush()?.let { handle(CodexJsonlParser.parseLine(it)) }
        } catch (e: CancellationException) {
            runCatching { io.destroyForcibly() }
            throw e
        } catch (e: Exception) {
            if (lastErrorMessage == null) lastErrorMessage = "Could not read Codex output."
        }

        val exit = awaitExit()
        val lastMessage = runCatching { readLastMessage() }.getOrNull()?.trim().orEmpty()
        if (!sawAgentMessage && lastMessage.isNotEmpty() && exit == 0 && turnFailure == null) {
            sawAgentMessage = true
            safeEmit(CodexEvent.AgentMessage(lastMessage))
        }

        return when {
            isStopRequested() -> CodexRunResult(false, "Stopped by user", exit, threadId)
            stallFailure != null -> CodexRunResult(false, stallFailure!!, exit, threadId)
            turnFailure != null ->
                CodexRunResult(false, CodexFailureMessages.friendly(turnFailure!!, exit), exit, threadId)
            sawTurnCompleted || (exit == 0 && sawAgentMessage) -> CodexRunResult(true, "", exit, threadId)
            else -> {
                val raw = lastErrorMessage
                    ?: diagnostics.joinToString(" ").takeIf { it.isNotBlank() }
                    ?: promptFailure.orEmpty()
                CodexRunResult(false, CodexFailureMessages.friendly(raw, exit), exit, threadId)
            }
        }
    }

    /**
     * Returns the exit code without ever blocking on a live process: [CodexProcessIo.waitFor] is
     * only called once the process is gone, because it holds a lock that also guards liveness checks.
     */
    private suspend fun awaitExit(): Int {
        if (isAliveSafe()) {
            runCatching { io.destroy() }
            if (!awaitDead()) {
                runCatching { io.destroyForcibly() }
                if (!awaitDead()) return -1
            }
        }
        return runCatching { io.waitFor() }.getOrDefault(-1)
    }

    private suspend fun awaitDead(): Boolean {
        val deadline = now() + config.exitGraceMs
        while (isAliveSafe()) {
            if (now() >= deadline) return false
            delay(config.pollMs)
        }
        return true
    }

    private fun isAliveSafe(): Boolean = runCatching { io.isAlive }.getOrDefault(false)

    private suspend fun safeEmit(event: CodexEvent) {
        try {
            emit(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A faulty consumer must not abort the run or the process cleanup that follows it.
        }
    }

    private fun isNoise(text: String): Boolean =
        text.contains("PATH aliases") || text.startsWith("Reading additional input")

    private companion object {
        const val READ_CHUNK_BYTES = 16 * 1024
        const val MAX_DIAGNOSTIC_LINES = 5
        const val MAX_STUCK_READS = 100
        const val NONE = Long.MIN_VALUE
    }
}
