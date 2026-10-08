package com.jarves.mh.runtime

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages Codex's ChatGPT sign-in through `codex login --device-auth`.
 *
 * Codex itself owns the OAuth device flow, token storage and refresh. This class only runs the
 * CLI, shows its link and one-time code, and checks whether `$CODEX_HOME/auth.json` exists. It
 * never reads or logs the contents of that file.
 */
class CodexAuthController(
    private val context: Context,
    private val onSignedInChanged: (Boolean) -> Unit = {},
    private val onLoginCompleted: () -> Unit = {},
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(CodexAuthState())
    val state: StateFlow<CodexAuthState> = mutableState.asStateFlow()

    private val loginInFlight = AtomicBoolean(false)
    @Volatile private var loginEngine: CodexLoginEngine? = null

    val isLoginInFlight: Boolean get() = loginInFlight.get()

    fun hasCredentials(): Boolean = CodexRuntimeBridge.hasChatGptCredentials(installer.rootfs)

    /** File check only; starts no process. A sign-in in progress owns the state. */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        if (loginInFlight.get()) return@withContext
        val signedIn = hasCredentials()
        mutableState.value = if (signedIn) {
            CodexAuthState(status = CodexAuthStatus.SIGNED_IN)
        } else {
            CodexAuthState(status = CodexAuthStatus.SIGNED_OUT)
        }
        onSignedInChanged(signedIn)
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (!loginInFlight.compareAndSet(false, true)) return@withContext
        val capture = File(context.cacheDir, "codex-auth-login.log")
        try {
            if (!installer.isCodexInstalled()) {
                mutableState.value = CodexAuthState(
                    status = CodexAuthStatus.ERROR,
                    message = "Install Codex first, then sign in.",
                )
                return@withContext
            }
            capture.delete()
            val engine = CodexLoginEngine(
                launch = { CapturedCodexLoginProcess(launchCli(LOGIN_ARGS, capture), capture) },
                hasCredentials = ::hasCredentials,
                onState = { mutableState.value = it },
            )
            loginEngine = engine
            startForegroundWhileSigningIn()
            val outcome = engine.run()
            if (outcome is CodexLoginEngine.Outcome.SignedIn) {
                onSignedInChanged(true)
                onLoginCompleted()
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = CodexAuthState(status = CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled.")
            throw cancelled
        } catch (t: Throwable) {
            mutableState.value = CodexAuthState(
                status = CodexAuthStatus.ERROR,
                message = CodexLoginOutput.failureMessage("", 127),
            )
        } finally {
            loginEngine = null
            runCatching { capture.delete() }
            stopForegroundAfterSigningIn()
            loginInFlight.set(false)
        }
        Unit
    }

    fun cancelLogin() {
        val engine = loginEngine
        if (engine != null) {
            engine.cancel()
        } else if (!loginInFlight.get()) {
            mutableState.value = CodexAuthState(status = CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled.")
        }
    }

    /** Called when a running session reports that the saved ChatGPT session was rejected. */
    fun markExpired() {
        if (loginInFlight.get()) return
        mutableState.value = CodexAuthState(
            status = CodexAuthStatus.ERROR,
            message = "Your ChatGPT session expired. Sign in again.",
        )
        onSignedInChanged(false)
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        if (loginInFlight.get()) return@withContext
        if (installer.isCodexInstalled()) runCliBlocking(listOf("logout"))
        // `codex logout` removes the file; make sure it is gone even if the CLI could not run.
        runCatching { credentialFile().delete() }
        refreshStatus()
    }

    private fun credentialFile(): File =
        File(installer.rootfs, CodexLaunchBuilder.CODEX_HOME_GUEST_PATH.removePrefix("/") + "/auth.json")

    /** Runs a short non-interactive CLI command; the process is always killed on timeout and the log removed. */
    private fun runCliBlocking(args: List<String>, timeoutMs: Long = CLI_TIMEOUT_MS) {
        val output = File(context.cacheDir, "codex-auth-${args.joinToString("-").filter(Char::isLetterOrDigit)}.log")
        output.delete()
        val process = runCatching { launchCli(args, output) }.getOrNull() ?: return
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (process.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(50)
        } finally {
            runCatching { if (process.isAlive) process.destroyForcibly() }
            runCatching { output.delete() }
        }
    }

    private fun launchCli(args: List<String>, outputFile: File): NativeSpawnProcess {
        val runtime = installer.installedRuntime()
        val workspace = File(context.filesDir, "workspaces/$AUTH_WORKSPACE").apply { mkdirs() }
        val process = installer.process(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
            environment = CodexLaunchBuilder.environment(CodexRoute.ChatGptLogin(""), null),
            guestCommand = listOf(CodexLaunchBuilder.CODEX_GUEST_PATH) + args,
            guestWorkspacePath = "/workspace/$AUTH_WORKSPACE",
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        return process as? NativeSpawnProcess ?: error("Unsupported Codex authentication process")
    }

    /** Keeps the app alive while the user is away in the browser; reuses the existing runtime service. */
    private fun startForegroundWhileSigningIn() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RuntimeExecutionService::class.java)
                    .setAction(RuntimeExecutionService.ACTION_START)
                    .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, "Codex sign-in")
                    .putExtra(RuntimeExecutionService.EXTRA_TITLE, "Signing in to ChatGPT")
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

    /** Adapts the native process and its capture file to the engine's process abstraction. */
    private class CapturedCodexLoginProcess(
        private val native: NativeSpawnProcess,
        private val outputFile: File,
    ) : CodexLoginProcess {
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

        override fun exitCode(): Int? = runCatching { native.exitValue() }.getOrNull()

        override fun destroy() {
            runCatching { native.destroyForcibly() }
        }
    }

    private companion object {
        const val AUTH_WORKSPACE = "codex-auth"
        const val CLI_TIMEOUT_MS = 15_000L

        /** The file store keeps credentials where [hasCredentials] looks; matches the launch overrides. */
        val LOGIN_ARGS = listOf(
            "login",
            "-c",
            "cli_auth_credentials_store=${CodexLaunchBuilder.tomlString("file")}",
            "--device-auth",
        )
    }
}
