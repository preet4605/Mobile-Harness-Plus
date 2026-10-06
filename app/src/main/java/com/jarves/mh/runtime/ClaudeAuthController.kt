package com.jarves.mh.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.jarves.mh.model.AgentKind
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * Manages Claude Code's native subscription authentication via `claude auth login --claudeai`.
 *
 * Claude Code itself owns the OAuth PKCE flow, credential storage, and refresh.
 * Mobile Harness does NOT implement custom OAuth, does NOT extract or persist OAuth tokens,
 * and does NOT read the contents of `/root/.claude/.credentials.json`.
 *
 * The sign-in sequencing lives in [ClaudeLoginEngine]; this class adapts it to the PRoot
 * runtime, the foreground service that keeps the app alive while the user is in the
 * browser, and the app's state flow.
 */
class ClaudeAuthController(
    private val context: Context,
    private val onSignedInChanged: (Boolean) -> Unit = {},
    private val onAuthUrlDiscovered: ((String) -> Unit)? = null,
    /** Fired only when the user just finished an account sign-in (not on routine status checks). */
    private val onLoginCompleted: () -> Unit = {},
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(ClaudeAuthState())
    val state: StateFlow<ClaudeAuthState> = mutableState.asStateFlow()

    private val loginInFlight = AtomicBoolean(false)
    @Volatile private var loginEngine: ClaudeLoginEngine? = null

    val isLoginInFlight: Boolean get() = loginInFlight.get()

    fun hasNativeCredentials(): Boolean =
        File(installer.rootfs, "root/.claude/.credentials.json").isFile

    fun extractOAuthUrl(text: String): String? = ClaudeAuthUrl.extract(text, complete = true)

    suspend fun queryAuthStatus(): ClaudeStatusMetadata? = withContext(Dispatchers.IO) {
        // A sign-in in progress owns the state; a status refresh must not overwrite it.
        if (loginInFlight.get()) return@withContext null
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) {
            mutableState.value = ClaudeAuthState(
                status = ClaudeAuthStatusState.SIGNED_OUT,
                message = "Not signed in",
            )
            return@withContext null
        }
        val metadata = runStatusBlocking()
        if (metadata != null && !loginInFlight.get()) {
            if (metadata.loggedIn) {
                mutableState.value = ClaudeAuthState.signedIn(metadata)
                onSignedInChanged(true)
            } else {
                mutableState.value = ClaudeAuthState(
                    status = ClaudeAuthStatusState.SIGNED_OUT,
                    authMethod = metadata.authMethod,
                    apiProvider = metadata.apiProvider,
                    message = "Not signed in",
                )
                onSignedInChanged(false)
            }
        }
        metadata
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (!loginInFlight.compareAndSet(false, true)) return@withContext
        val outputFile = File(context.cacheDir, "claude-auth-login.log")
        try {
            if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) {
                mutableState.value = ClaudeAuthState(
                    status = ClaudeAuthStatusState.ERROR,
                    message = ClaudeLoginOutput.messageFor(ClaudeAuthErrorKind.NOT_INSTALLED),
                    errorKind = ClaudeAuthErrorKind.NOT_INSTALLED,
                )
                return@withContext
            }
            outputFile.delete()
            val engine = ClaudeLoginEngine(
                launcher = { args -> PtyClaudeCliProcess(launchCli(args, pty = true, outputFile = outputFile), outputFile) },
                queryStatus = ::runStatusBlocking,
                onState = { mutableState.value = it },
                onUrl = { url -> onAuthUrlDiscovered?.invoke(url) },
            )
            loginEngine = engine
            startForegroundWhileSigningIn()
            AndroidBrowserBridge(context).ensureBridgeInstalled(installer.rootfs)
            val outcome = runInterruptible { engine.run() }
            if (outcome is ClaudeLoginEngine.Outcome.SignedIn) {
                onSignedInChanged(true)
                onLoginCompleted()
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = ClaudeAuthState(
                status = ClaudeAuthStatusState.SIGNED_OUT,
                message = "Sign in cancelled.",
            )
            throw cancelled
        } catch (t: Throwable) {
            mutableState.value = ClaudeAuthState(
                status = ClaudeAuthStatusState.ERROR,
                message = ClaudeLoginOutput.messageFor(ClaudeAuthErrorKind.SPAWN_FAILED),
                errorKind = ClaudeAuthErrorKind.SPAWN_FAILED,
            )
        } finally {
            loginEngine = null
            runCatching { outputFile.delete() }
            stopForegroundAfterSigningIn()
            loginInFlight.set(false)
        }
        Unit
    }

    /** Forwards a code the user copied from claude.ai when the browser redirect could not finish the login. */
    fun submitCode(code: String) {
        loginEngine?.submitCode(code)
    }

    fun cancelLogin() {
        val engine = loginEngine
        if (engine != null) {
            engine.cancel()
        } else {
            mutableState.value = ClaudeAuthState(
                status = ClaudeAuthStatusState.SIGNED_OUT,
                message = "Sign in cancelled.",
            )
        }
    }

    /** Called when a running session reports that the saved Claude session was rejected. */
    fun markExpired() {
        if (loginInFlight.get()) return
        mutableState.value = ClaudeAuthState(
            status = ClaudeAuthStatusState.EXPIRED,
            message = ClaudeLoginOutput.messageFor(ClaudeAuthErrorKind.EXPIRED),
            errorKind = ClaudeAuthErrorKind.EXPIRED,
        )
        onSignedInChanged(false)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) return@withContext
        runCliBlocking(listOf("auth", "logout"))
        queryAuthStatus()
    }

    private fun runStatusBlocking(): ClaudeStatusMetadata? {
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) return null
        return ClaudeStatusMetadata.fromOutput(runCliBlocking(listOf("auth", "status", "--json")).orEmpty())
    }

    /** Runs a short non-interactive CLI command; the process is always killed on timeout and the log removed. */
    private fun runCliBlocking(args: List<String>, timeoutMs: Long = CLI_TIMEOUT_MS): String? {
        val output = File(context.cacheDir, "claude-auth-${args.joinToString("-").filter(Char::isLetterOrDigit)}.log")
        output.delete()
        val process = runCatching { launchCli(args, pty = false, outputFile = output) }.getOrNull() ?: return null
        return try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
            if (process.isAlive) null else runCatching { output.readText() }.getOrNull()
        } finally {
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { output.delete() }
        }
    }

    private fun launchCli(args: List<String>, pty: Boolean, outputFile: File): NativeSpawnProcess {
        val runtime = installer.installedRuntime()
        val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
        val environment = mutableMapOf(
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "NO_COLOR" to "1",
        )
        if (pty) environment["BROWSER"] = AndroidBrowserBridge.BROWSER_ENV_PATH
        val process = installer.process(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
            environment = environment,
            guestCommand = listOf(RuntimeInstaller.CLAUDE_GUEST_PATH) + args,
            guestWorkspacePath = "/workspace/claude-auth",
            emulateHardLinks = false,
            outputFile = outputFile,
            pseudoTerminal = pty,
            ptyRows = 40,
            // Wide enough that the terminal UI never wraps the long authorization link.
            ptyColumns = PTY_COLUMNS,
        )
        return process as? NativeSpawnProcess ?: error("Unsupported Claude authentication process")
    }

    /** Keeps the app alive while the user is away in the browser; reuses the existing runtime service. */
    private fun startForegroundWhileSigningIn() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_START)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, "Claude sign-in")
                    .putExtra(RuntimeExecutionService.EXTRA_TITLE, "Signing in to Claude")
                    .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, false),
            )
        }
    }

    private fun stopForegroundAfterSigningIn() {
        // A coding session that started meanwhile owns the service; do not stop it from here.
        if (RuntimeTaskController.stopAction != null) return
        runCatching {
            context.startService(
                Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            runCatching { context.stopService(Intent(context, RuntimeExecutionService::class.java)) }
        }
    }

    /** Adapts a PTY-backed [NativeSpawnProcess] to the engine's process abstraction. */
    private class PtyClaudeCliProcess(
        private val native: NativeSpawnProcess,
        private val outputFile: File,
    ) : ClaudeCliProcess {
        private var offset = 0L

        override val isAlive: Boolean get() = native.isAlive

        override fun readNewOutput(): String {
            val available = outputFile.length() - offset
            if (available <= 0) return ""
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = runCatching {
                RandomAccessFile(outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
            }.getOrDefault(-1)
            if (count <= 0) return ""
            offset += count
            return bytes.decodeToString(0, count)
        }

        override fun write(text: String) {
            native.outputStream.write(text.toByteArray(Charsets.UTF_8))
            native.outputStream.flush()
        }

        override fun destroy() {
            runCatching { if (native.isAlive) native.destroyForcibly() }
        }
    }

    private companion object {
        const val CLI_TIMEOUT_MS = 10_000L
        const val PTY_COLUMNS = 512
    }
}
