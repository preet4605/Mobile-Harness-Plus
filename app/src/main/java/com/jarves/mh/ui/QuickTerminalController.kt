package com.jarves.mh.ui

import android.os.SystemClock
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstaller
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Terminal tab: one shell command at a time in the shared `workspaces/terminal` folder, with
 * its live output and history. Moved out of [MainViewModel] unchanged; the ViewModel still exposes
 * the same flows and functions to the screens.
 */
internal class QuickTerminalController(
    private val scope: CoroutineScope,
    private val filesDir: File,
    private val installer: RuntimeInstaller,
    private val maxOutputChars: Int,
    private val maxHistory: Int,
    private val prepareCommand: (String) -> String,
    private val shouldAutoConfirm: (command: String, output: String) -> Boolean,
    private val sendInput: (Process?, String) -> Unit,
    private val interrupt: (Process?) -> Unit,
) {
    /** The running command's process, while one runs. */
    @Volatile var process: Process? = null
        private set

    // Starts empty: a hard-coded `uname -a` line here was fake output, not from the runtime.
    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(emptyList())
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        scope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(filesDir, "workspaces/terminal").apply { mkdirs() }
                    val preparedCommand = prepareCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                        maxOutputBytes = com.jarves.mh.runtime.NativeSpawnProcess.STREAMING_OUTPUT_BYTES,
                    )
                    process = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = TerminalOutputTail(maxOutputChars)
                    val decoder = com.jarves.mh.runtime.Utf8ChunkDecoder()
                    var autoConfirmed = false
                    var idleSleep = TERMINAL_IDLE_POLL_MIN_MS
                    var lastPublishAt = 0L
                    val reader = native?.let { com.jarves.mh.runtime.CaptureFileReader(it.outputFile) }
                    val bytes = ByteArray(16 * 1024)
                    try {
                        while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                            val file = native?.outputFile
                            val available = (file?.length() ?: 0L) - offset
                            if (reader == null || available <= 0) {
                                idleSleep = awaitTerminalOutput(native?.outputSignal, idleSleep)
                                continue
                            }
                            idleSleep = TERMINAL_IDLE_POLL_MIN_MS
                            val count = reader.read(offset, bytes, minOf(available, bytes.size.toLong()).toInt())
                            if (count > 0) {
                                offset += count
                                streamed.append(decoder.decode(bytes, count))
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastPublishAt >= TERMINAL_PUBLISH_INTERVAL_MS || available <= count) {
                                    lastPublishAt = now
                                    com.jarves.mh.runtime.PerfStats.count("terminal.publish")
                                    _terminalLiveOutput.value = streamed.text().trimEnd()
                                }
                                if (!autoConfirmed && shouldAutoConfirm(command, streamed.recent(500))) {
                                    proc.outputStream.write("y\n".toByteArray())
                                    proc.outputStream.flush()
                                    autoConfirmed = true
                                }
                            }
                        }
                    } finally {
                        reader?.close()
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    streamed.append(decoder.finish())
                    streamed.finish()
                    val out = streamed.text().trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else "Process exited with code $exit"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update { (it + TerminalOutputLine(command = command, output = output, exitCode = exitCode)).takeLast(maxHistory) }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            process = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendInput(process, text)
    }

    fun interruptTerminalCommand() {
        interrupt(process)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }
}
