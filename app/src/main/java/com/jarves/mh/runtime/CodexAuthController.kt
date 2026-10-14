package com.jarves.mh.runtime

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Manages Codex's ChatGPT sign-in through `codex login --device-auth`.
 *
 * Codex itself owns the OAuth device flow, token storage and refresh. This class only runs the
 * CLI, shows its link and one-time code, and validates the selected home with local login status. It
 * never reads or logs the contents of that file.
 */
class CodexAuthController internal constructor(
    private val context: Context,
    private val onSignedInChanged: (Boolean) -> Unit = {},
    private val onLoginCompleted: () -> Unit = {},
    private val onAccountsChanged: (List<CodexAccount>, String?) -> Unit = { _, _ -> },
    private val canModifyAccounts: () -> Boolean = { true },
    private val readAccount: (String) -> CodexAccountDetails? = { CodexModelCatalog(context).readAccount(it) },
    private val readStatus: ((String) -> CodexCredentialStatus)? = null,
    private val launchOverride: ((List<String>, File, String) -> Process)? = null,
) {
    private val installer = RuntimeInstaller(context)
    private var foregroundOwner: Any? = null
    internal val accounts = CodexAccounts(installer.rootfs)
    private val generation = AtomicLong()
    private val cancelRequested = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(CodexAuthState())
    val state: StateFlow<CodexAuthState> = mutableState.asStateFlow()

    private val loginInFlight = AtomicBoolean(false)
    @Volatile private var loginEngine: CodexLoginEngine? = null

    val isLoginInFlight: Boolean get() = loginInFlight.get()

    fun hasCredentials(): Boolean = mutableState.value.status == CodexAuthStatus.SIGNED_IN &&
        runCatching { accounts.selected() }.isSuccess

    private fun publishAccounts() = onAccountsChanged(accounts.list(), accounts.selectedId())

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        if (loginInFlight.get()) return@withContext
        val token = synchronized(this@CodexAuthController) { generation.incrementAndGet() }
        if (!accounts.legacyRegistered() && credentialStatus(CodexAccounts.LEGACY) == CodexCredentialStatus.CHATGPT) {
            val legacy = runCatching { readAccount(CodexAccounts.LEGACY) }.getOrNull()
            currentCoroutineContext().ensureActive()
            synchronized(this@CodexAuthController) {
                if (token != generation.get() || loginInFlight.get()) return@withContext
                accounts.migrateLegacy(legacy?.label ?: "ChatGPT account")
            }
        }
        val id = accounts.selectedId()
        val usable = id?.let { runCatching { accounts.requireUsable(it) }.getOrNull() }
        val status = usable?.let { credentialStatus(it.id) } ?: CodexCredentialStatus.SIGNED_OUT
        currentCoroutineContext().ensureActive()
        synchronized(this@CodexAuthController) {
            if (token != generation.get() || id != accounts.selectedId() || loginInFlight.get()) return@withContext
            if (status == CodexCredentialStatus.SIGNED_OUT && usable != null) accounts.markExpired(usable.id)
            val signedIn = status == CodexCredentialStatus.CHATGPT ||
                (status == CodexCredentialStatus.UNAVAILABLE && mutableState.value.status == CodexAuthStatus.SIGNED_IN &&
                    usable != null && accounts.credentialFile(usable.id).isFile)
            mutableState.value = CodexAuthState(status = if (signedIn) CodexAuthStatus.SIGNED_IN else CodexAuthStatus.SIGNED_OUT)
            publishAccounts()
            onSignedInChanged(signedIn)
        }
    }

    @Synchronized
    fun selectAccount(id: String) {
        check(!loginInFlight.get()) { "Wait for sign-in to finish." }
        accounts.select(id)
        generation.incrementAndGet()
        mutableState.value = CodexAuthState(status = CodexAuthStatus.SIGNED_OUT)
        onSignedInChanged(false)
        publishAccounts()
    }

    @Synchronized
    fun renameAccount(id: String, label: String) {
        check(!loginInFlight.get()) { "Wait for sign-in to finish." }
        accounts.rename(id, label)
        publishAccounts()
    }

    @Synchronized
    fun setAccountEnabled(id: String, enabled: Boolean) {
        check(!loginInFlight.get() && canModifyAccounts()) { "Wait for running and queued tasks to finish." }
        generation.incrementAndGet()
        accounts.setEnabled(id, enabled)
        publishAccounts()
        if (id == accounts.selectedId() && !enabled) {
            mutableState.value = CodexAuthState(status = CodexAuthStatus.SIGNED_OUT)
            onSignedInChanged(false)
        }
    }

    suspend fun beginLogin(reconnectId: String? = null) = withContext(Dispatchers.IO) {
        synchronized(this@CodexAuthController) {
            if (!loginInFlight.compareAndSet(false, true)) return@withContext
            generation.incrementAndGet()
            cancelRequested.set(false)
        }
        var accountId: String? = null
        var loginProcess: Process? = null
        var keepDefault = false
        val capture = File(context.cacheDir, "codex-auth-login.log")
        try {
            check(canModifyAccounts()) { "Wait for running and queued tasks to finish." }
            if (launchOverride == null && !installer.isCodexInstalled()) {
                mutableState.value = CodexAuthState(
                    status = CodexAuthStatus.ERROR,
                    message = "Install Codex first, then sign in.",
                )
                return@withContext
            }
            if (reconnectId != null) {
                check(accounts.list().any { it.id == reconnectId }) { "Account no longer exists." }
            }
            val selected = accounts.selectedId()?.let { runCatching { accounts.requireUsable(it) }.getOrNull() }
            if (selected != null) {
                val status = credentialStatus(selected.id)
                keepDefault = status == CodexCredentialStatus.CHATGPT ||
                    (status == CodexCredentialStatus.UNAVAILABLE && mutableState.value.status == CodexAuthStatus.SIGNED_IN &&
                        accounts.credentialFile(selected.id).isFile)
                if (status == CodexCredentialStatus.SIGNED_OUT) accounts.markExpired(selected.id)
            }
            currentCoroutineContext().ensureActive()
            check(!cancelRequested.get()) { "Sign in cancelled." }
            // Never reuse a home from an interrupted login: its old child may still exist after app death.
            val account = accounts.create()
            accountId = account.id
            publishAccounts()
            capture.delete()
            val engine = CodexLoginEngine(
                launch = {
                    check(!cancelRequested.get()) { "Sign in cancelled." }
                    val native = launchCli(LOGIN_ARGS, capture, account.id)
                    loginProcess = native
                    CapturedCodexLoginProcess(native, capture)
                },
                hasCredentials = { !cancelRequested.get() && credentialStatus(account.id) == CodexCredentialStatus.CHATGPT },
                onState = { if (!cancelRequested.get()) mutableState.value = it },
            )
            loginEngine = engine
            if (cancelRequested.get()) engine.cancel()
            startForegroundWhileSigningIn()
            val outcome = engine.run()
            currentCoroutineContext().ensureActive()
            val validated = if (outcome is CodexLoginEngine.Outcome.SignedIn && !cancelRequested.get())
                runCatching { readAccount(account.id) }.getOrNull() else null
            currentCoroutineContext().ensureActive()
            synchronized(this@CodexAuthController) {
                if (outcome is CodexLoginEngine.Outcome.SignedIn && !cancelRequested.get()) {
                    val wasSelected = reconnectId != null && reconnectId == accounts.selectedId()
                    accounts.complete(account.id, validated?.label ?: account.label)
                    if (wasSelected || accounts.selectedId() == null || !keepDefault) {
                        accounts.select(account.id)
                    }
                    reconnectId?.let { accounts.remove(it) } // Retain the retired private home; do not race an orphan's writes.
                    publishAccounts()
                    onSignedInChanged(true)
                    onLoginCompleted()
                }
            }
        } catch (cancelled: CancellationException) {
            val hasSelected = runCatching { accounts.selected() }.isSuccess
            mutableState.value = if (hasSelected) {
                CodexAuthState(status = CodexAuthStatus.SIGNED_IN)
            } else {
                CodexAuthState(status = CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled.")
            }
            throw cancelled
        } catch (t: Throwable) {
            mutableState.value = CodexAuthState(
                status = CodexAuthStatus.ERROR,
                message = t.message?.takeIf { it.isNotBlank() } ?: CodexLoginOutput.failureMessage("", 127),
            )
        } finally {
            loginEngine = null
            val closed = loginProcess?.let(::closeAuthProcess) ?: true
            if (closed) {
                runCatching { capture.delete() }
                // Failed new attempts stay unpublished. Remove their credentials only after child exit.
                accountId?.takeIf { accounts.list().none { a -> a.id == it } }?.let {
                    runCatching { accounts.credentialFile(it).delete() }
                    runCatching { accounts.remove(it) }
                }
                loginInFlight.set(false)
            }
            if (cancelRequested.get()) {
                val hasSelected = runCatching { accounts.selected() }.isSuccess
                mutableState.value = if (hasSelected) {
                    CodexAuthState(status = CodexAuthStatus.SIGNED_IN)
                } else {
                    CodexAuthState(status = CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled.")
                }
            }
            publishAccounts()
            if (closed) stopForegroundAfterSigningIn()
        }
        Unit
    }

    @Synchronized
    fun cancelLogin() {
        cancelRequested.set(true)
        val engine = loginEngine
        if (engine != null) {
            engine.cancel()
        } else if (!loginInFlight.get()) {
            mutableState.value = CodexAuthState(status = CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled.")
        }
    }

    /** Called when a running session reports that the saved ChatGPT session was rejected. */
    @Synchronized
    fun markExpired(accountId: String? = accounts.selectedId()) {
        if (accountId == null) return
        generation.incrementAndGet()
        runCatching { accounts.markExpired(accountId) }
        publishAccounts()
        if (loginInFlight.get() || accountId != accounts.selectedId()) return
        mutableState.value = CodexAuthState(
            status = CodexAuthStatus.ERROR,
            message = "Your ChatGPT session expired. Sign in again.",
        )
        onSignedInChanged(false)
    }

    suspend fun logout(accountId: String? = accounts.selectedId()) = withContext(Dispatchers.IO) {
        if (accountId == null) return@withContext
        synchronized(this@CodexAuthController) {
            if (!loginInFlight.compareAndSet(false, true)) return@withContext
            generation.incrementAndGet()
        }
        var childClosed = true
        try {
            check(canModifyAccounts()) { "Wait for running and queued tasks to finish." }
            check(accounts.list().any { it.id == accountId }) { "Account no longer exists." }
            accounts.markExpired(accountId)
            publishAccounts()
            val output = File(context.cacheDir, "codex-auth-logout.log")
            if (installer.isCodexInstalled()) {
                val process = launchCli(listOf("logout"), output, accountId)
                childClosed = false
                val deadline = System.nanoTime() + CLI_TIMEOUT_MS * 1_000_000
                try {
                    while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
                } finally {
                    childClosed = closeAuthProcess(process)
                    check(childClosed) { "Codex sign-out is still closing. Try again later." }
                    output.delete()
                }
            }
            CredentialMounts.removeLegacyCopy(context.cacheDir, accountId)
            val credential = accounts.credentialFile(accountId)
            check(!credential.exists() || credential.delete()) { "Could not remove account credentials." }
            accounts.remove(accountId)
            publishAccounts()
        } finally {
            if (childClosed) loginInFlight.set(false)
        }
        refreshStatus()
    }

    private fun credentialStatus(accountId: String): CodexCredentialStatus {
        readStatus?.let { return it(accountId) }
        // A missing canonical file must never be resurrected from an old mask.
        if (!accounts.credentialFile(accountId).isFile) return CodexCredentialStatus.SIGNED_OUT
        if (!installer.isCodexInstalled()) return CodexCredentialStatus.UNAVAILABLE
        val output = File(context.cacheDir, "codex-auth-status-${java.util.UUID.randomUUID()}.log")
        val process = runCatching { launchCli(listOf("login", "status", "-c", "cli_auth_credentials_store=\"file\""), output, accountId) }
            .getOrElse { return CodexCredentialStatus.UNAVAILABLE }
        return try {
            val deadline = System.nanoTime() + CLI_TIMEOUT_MS * 1_000_000
            while (process.isAlive && System.nanoTime() < deadline) Thread.sleep(50)
            if (process.isAlive) CodexCredentialStatus.UNAVAILABLE else {
                val exit = process.exitValue()
                codexCredentialStatus(output.takeIf { it.length() <= 64 * 1024 }?.readText().orEmpty(), exit)
            }
        } finally {
            if (closeAuthProcess(process)) output.delete()
        }
    }

    private fun launchCli(args: List<String>, outputFile: File, accountId: String): Process {
        launchOverride?.let { return it(args, outputFile, accountId) }
        val runtime = installer.installedRuntime()
        val home = accounts.home(accountId)
        accounts.hostHome(accountId).mkdirs()
        accounts.credentialFile(accountId) // Reject dangling credential symlinks before the CLI touches them.
        val workspace = File(context.filesDir, "workspaces/$AUTH_WORKSPACE").apply { mkdirs() }
        val process = installer.process(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
            environment = CodexLaunchBuilder.environment(CodexRoute.ChatGptLogin("", home), null),
            guestCommand = listOf(CodexLaunchBuilder.CODEX_GUEST_PATH) + args,
            guestWorkspacePath = "/workspace/$AUTH_WORKSPACE",
            credentialHome = home,
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        return process as? NativeSpawnProcess ?: error("Unsupported Codex authentication process")
    }

    /** Keeps the app alive while the user is away in the browser; reuses the existing runtime service. */
    private fun startForegroundWhileSigningIn() {
        foregroundOwner = runCatching { AuthForegroundService.acquire(context, "Signing in to ChatGPT") }.getOrNull()
    }

    private fun stopForegroundAfterSigningIn() {
        val owner = foregroundOwner ?: return
        foregroundOwner = null
        runCatching { AuthForegroundService.release(context, owner) }
    }

    /** Adapts the native process and its capture file to the engine's process abstraction. */
    private class CapturedCodexLoginProcess(
        private val native: Process,
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
