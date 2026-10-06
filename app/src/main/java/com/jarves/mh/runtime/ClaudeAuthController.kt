package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.model.AgentKind
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

enum class ClaudeAuthStatusState {
    SIGNED_OUT,
    STARTING,
    AWAITING_AUTH,
    SIGNED_IN,
    ERROR,
}

/**
 * Non-secret status metadata parsed from `claude auth status --json`.
 * Never contains access tokens, refresh tokens, or credential file contents.
 */
data class ClaudeStatusMetadata(
    val loggedIn: Boolean,
    val authMethod: String = "none",
    val apiProvider: String = "firstParty",
    val subscriptionType: String? = null,
) {
    companion object {
        fun fromJson(jsonStr: String): ClaudeStatusMetadata? = runCatching {
            val json = JSONObject(jsonStr)
            ClaudeStatusMetadata(
                loggedIn = json.optBoolean("loggedIn", false),
                authMethod = json.optString("authMethod", "none"),
                apiProvider = json.optString("apiProvider", "firstParty"),
                subscriptionType = json.optString("subscriptionType").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }
}

data class ClaudeAuthState(
    val status: ClaudeAuthStatusState = ClaudeAuthStatusState.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val subscriptionType: String? = null,
    val authMethod: String? = null,
    val apiProvider: String? = null,
) {
    val displayStatus: String get() = when (status) {
        ClaudeAuthStatusState.SIGNED_IN -> {
            val tier = subscriptionType?.replaceFirstChar { it.uppercase() } ?: "Pro/Max"
            "Signed in — Claude $tier"
        }
        ClaudeAuthStatusState.STARTING -> "Starting sign in…"
        ClaudeAuthStatusState.AWAITING_AUTH -> "Waiting for authorization…"
        ClaudeAuthStatusState.ERROR -> message ?: "Sign-in error"
        ClaudeAuthStatusState.SIGNED_OUT -> "Not signed in"
    }
}

/**
 * Manages Claude Code's native subscription authentication via `claude auth login --claudeai`.
 *
 * Claude Code itself owns the OAuth PKCE flow, credential storage, and refresh.
 * Mobile Harness does NOT implement custom OAuth, does NOT extract or persist OAuth tokens,
 * and does NOT read the contents of `/root/.claude/.credentials.json`.
 */
class ClaudeAuthController(
    private val context: Context,
    private val onSignedInChanged: (Boolean) -> Unit = {},
    private val onAuthUrlDiscovered: ((String) -> Unit)? = null,
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(ClaudeAuthState())
    val state: StateFlow<ClaudeAuthState> = mutableState.asStateFlow()

    @Volatile private var process: Process? = null

    fun hasNativeCredentials(): Boolean =
        File(installer.rootfs, "root/.claude/.credentials.json").isFile

    fun extractOAuthUrl(text: String): String? {
        val regex = Regex("https://(?:claude\\.com|platform\\.claude\\.com)/[a-zA-Z0-9_\\-\\.~:/?#\\[\\]@!${'$'}&'()*+,;=%]+")
        return regex.find(text)?.value
    }

    suspend fun queryAuthStatus(): ClaudeStatusMetadata? = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) {
            mutableState.value = ClaudeAuthState(
                status = ClaudeAuthStatusState.SIGNED_OUT,
                message = "Not signed in",
            )
            return@withContext null
        }
        val runtime = runCatching { installer.installedRuntime() }.getOrNull() ?: return@withContext null
        val statusOutput = File(context.cacheDir, "claude-auth-status.log").apply { delete() }
        val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
        val statusProcess = runCatching {
            installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                environment = mapOf(
                    "HOME" to "/root",
                    "TERM" to "xterm-256color",
                    "NO_COLOR" to "1",
                ),
                guestCommand = listOf(RuntimeInstaller.CLAUDE_GUEST_PATH, "auth", "status", "--json"),
                guestWorkspacePath = "/workspace/claude-auth",
                emulateHardLinks = false,
                outputFile = statusOutput,
                pseudoTerminal = false,
            )
        }.getOrNull() ?: return@withContext null

        withTimeoutOrNull(10_000L) {
            while (statusProcess.isAlive) delay(50)
        }
        val output = runCatching { statusOutput.readText() }.getOrDefault("").trim()
        val metadata = ClaudeStatusMetadata.fromJson(output)
        if (metadata != null) {
            if (metadata.loggedIn) {
                val tier = metadata.subscriptionType?.replaceFirstChar { it.uppercase() } ?: "Pro/Max"
                mutableState.value = ClaudeAuthState(
                    status = ClaudeAuthStatusState.SIGNED_IN,
                    subscriptionType = metadata.subscriptionType,
                    authMethod = metadata.authMethod,
                    apiProvider = metadata.apiProvider,
                    message = "Signed in — Claude $tier",
                )
                onSignedInChanged(true)
            } else {
                mutableState.value = ClaudeAuthState(
                    status = ClaudeAuthStatusState.SIGNED_OUT,
                    subscriptionType = null,
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
        if (process?.isAlive == true) return@withContext
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) {
            mutableState.value = ClaudeAuthState(
                status = ClaudeAuthStatusState.ERROR,
                message = "Install Claude Code before signing in.",
            )
            return@withContext
        }
        mutableState.value = ClaudeAuthState(
            status = ClaudeAuthStatusState.STARTING,
            message = "Starting Claude sign-in…",
        )
        val browserBridge = AndroidBrowserBridge(context)
        browserBridge.ensureBridgeInstalled(installer.rootfs)
        val authOutput = File(context.cacheDir, "claude-auth-login.log").apply { delete() }
        val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
        val runtime = installer.installedRuntime()
        val running = installer.process(
            proot = runtime.proot,
            rootfs = runtime.rootfs,
            workspace = workspace,
            environment = mapOf(
                "HOME" to "/root",
                "TERM" to "xterm-256color",
                "NO_COLOR" to "1",
                "BROWSER" to AndroidBrowserBridge.BROWSER_ENV_PATH,
            ),
            guestCommand = listOf(RuntimeInstaller.CLAUDE_GUEST_PATH, "auth", "login", "--claudeai"),
            guestWorkspacePath = "/workspace/claude-auth",
            emulateHardLinks = false,
            outputFile = authOutput,
            pseudoTerminal = true,
            ptyRows = 40,
            ptyColumns = 120,
        )
        process = running
        val native = running as? NativeSpawnProcess ?: error("Unsupported Claude authentication process")
        var offset = 0L
        val output = StringBuilder()
        try {
            while (running.isAlive || native.outputFile.length() > offset) {
                val available = native.outputFile.length() - offset
                if (available <= 0) {
                    delay(100)
                    continue
                }
                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                val count = RandomAccessFile(native.outputFile, "r").use { file ->
                    file.seek(offset)
                    file.read(bytes)
                }
                if (count <= 0) continue
                offset += count
                output.append(bytes.decodeToString(0, count))
                val clean = output.toString()
                val url = extractOAuthUrl(clean)
                if (url != null && mutableState.value.authorizationUrl == null) {
                    mutableState.value = ClaudeAuthState(
                        status = ClaudeAuthStatusState.AWAITING_AUTH,
                        authorizationUrl = url,
                        message = "Finish signing in on Claude.ai, or paste the code below.",
                    )
                    onAuthUrlDiscovered?.invoke(url)
                }
                if (clean.contains("Logged in", true) ||
                    clean.contains("Authentication successful", true) ||
                    clean.contains("Login successful", true)
                ) {
                    break
                }
            }
            repeat(20) {
                if (!running.isAlive) return@repeat
                delay(100)
            }
        } finally {
            if (running.isAlive) running.destroy()
            process = null
        }
        queryAuthStatus()
    }

    fun submitCode(code: String) {
        val running = process ?: return
        val clean = code.trim()
        if (clean.isBlank()) return
        runCatching {
            running.outputStream.write("$clean\r\n".toByteArray(Charsets.UTF_8))
            running.outputStream.flush()
        }
    }

    fun cancelLogin() {
        process?.let {
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
        mutableState.value = ClaudeAuthState(
            status = ClaudeAuthStatusState.SIGNED_OUT,
            message = "Sign in cancelled.",
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) return@withContext
        val runtime = runCatching { installer.installedRuntime() }.getOrNull() ?: return@withContext
        val logoutOutput = File(context.cacheDir, "claude-auth-logout.log").apply { delete() }
        val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
        val proc = runCatching {
            installer.process(
                proot = runtime.proot,
                rootfs = runtime.rootfs,
                workspace = workspace,
                environment = mapOf(
                    "HOME" to "/root",
                    "TERM" to "xterm-256color",
                    "NO_COLOR" to "1",
                ),
                guestCommand = listOf(RuntimeInstaller.CLAUDE_GUEST_PATH, "auth", "logout"),
                guestWorkspacePath = "/workspace/claude-auth",
                emulateHardLinks = false,
                outputFile = logoutOutput,
                pseudoTerminal = false,
            )
        }.getOrNull() ?: return@withContext
        withTimeoutOrNull(10_000L) {
            while (proc.isAlive) delay(50)
        }
        queryAuthStatus()
    }
}
