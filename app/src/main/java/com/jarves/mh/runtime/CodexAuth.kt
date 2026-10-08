package com.jarves.mh.runtime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

enum class CodexAuthStatus {
    SIGNED_OUT,
    STARTING,
    AWAITING_AUTH,
    SIGNED_IN,
    ERROR,
}

/** Non-secret sign-in state shown in Settings. Never holds tokens. */
data class CodexAuthState(
    val status: CodexAuthStatus = CodexAuthStatus.SIGNED_OUT,
    /** Where the user opens the device-code page. Only set while [CodexAuthStatus.AWAITING_AUTH]. */
    val verificationUrl: String? = null,
    /** One-time code the user types on that page. Only set while [CodexAuthStatus.AWAITING_AUTH]. */
    val userCode: String? = null,
    val message: String? = null,
) {
    val displayStatus: String
        get() = when (status) {
            CodexAuthStatus.SIGNED_IN -> "Signed in with ChatGPT"
            CodexAuthStatus.STARTING -> "Starting sign in…"
            CodexAuthStatus.AWAITING_AUTH -> "Waiting for you to enter the code…"
            CodexAuthStatus.ERROR -> message ?: "Sign-in error"
            CodexAuthStatus.SIGNED_OUT -> message ?: "Not signed in"
        }
}

internal data class CodexDeviceCode(val url: String, val code: String)

/**
 * Reads the output of `codex login --device-auth`. The prompt layout comes from the Codex 0.161.0
 * source (`device_code_auth.rs`); it has not been captured from a live sign-in, so the parser stays
 * tolerant: colours are stripped, and the code is whatever token follows the "one-time code" line.
 */
internal object CodexLoginOutput {
    private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    private val URL = Regex("https://[^\\s\"'<>]+")
    private val CODE = Regex("^[A-Za-z0-9][A-Za-z0-9-]{3,39}$")
    private const val CODE_MARKER = "one-time code"

    fun clean(text: String): String = ANSI.replace(text, "").replace("\r", "")

    /**
     * Returns the link and code once both are on complete lines of [output]; a half-written code
     * line is never returned, so the UI cannot show a truncated code.
     */
    fun parseDeviceCode(output: String): CodexDeviceCode? {
        val text = clean(output)
        val complete = text.substring(0, text.lastIndexOf('\n') + 1)
        if (complete.isEmpty()) return null
        val lines = complete.lines()
        val markerIndex = lines.indexOfFirst { it.contains(CODE_MARKER, ignoreCase = true) }
        if (markerIndex < 0) return null
        val url = lines.subList(0, markerIndex)
            .firstNotNullOfOrNull { line -> URL.find(line)?.value?.trimEnd('.', ',', ')', ';') }
            ?: return null
        val code = lines.drop(markerIndex + 1)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.takeIf { CODE.matches(it) }
            ?: return null
        return CodexDeviceCode(url, code)
    }

    fun failureMessage(output: String, exitCode: Int?): String {
        val text = clean(output)
        return when {
            text.contains("not enabled", ignoreCase = true) ->
                "ChatGPT device-code sign-in isn't enabled for this account. Business and Enterprise " +
                    "workspaces may need an admin to allow it, or sign in with an API key instead."
            text.contains("timed out", ignoreCase = true) -> "The code expired before it was entered. Try again."
            text.contains("error sending request", ignoreCase = true) ||
                text.contains("dns", ignoreCase = true) ||
                text.contains("connection", ignoreCase = true) ||
                text.contains("network", ignoreCase = true) ->
                "Couldn't reach OpenAI. Check your internet connection and try again."
            text.contains("PermissionDenied", ignoreCase = true) || text.contains("workspace", ignoreCase = true) ->
                "This ChatGPT workspace isn't allowed to sign in to Codex."
            exitCode == 126 || exitCode == 127 -> "Codex could not start. Reinstall it from Settings → Coding agent."
            exitCode == 137 -> "Codex was killed, likely because the device ran low on memory."
            else -> "Sign in didn't finish. Try again."
        }
    }
}

/** What the sign-in engine needs from a running `codex login` process. */
internal interface CodexLoginProcess {
    val isAlive: Boolean
    fun readNewOutput(): String
    fun exitCode(): Int?
    fun destroy()
}

/**
 * Drives one `codex login --device-auth` run: publishes the link and code as soon as they appear,
 * then waits for the process to exit. Success needs both exit code 0 and saved credentials.
 */
internal class CodexLoginEngine(
    private val launch: () -> CodexLoginProcess,
    private val hasCredentials: () -> Boolean,
    private val onState: (CodexAuthState) -> Unit,
    private val pollMs: Long = 250L,
    private val timeoutMs: Long = LOGIN_TIMEOUT_MS,
    private val codeWaitMs: Long = CODE_WAIT_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Outcome {
        data object SignedIn : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val cancelled = AtomicBoolean(false)
    @Volatile private var process: CodexLoginProcess? = null

    /** Safe from any thread. The running [run] notices within one poll and returns [Outcome.Cancelled]. */
    fun cancel() {
        cancelled.set(true)
        process?.let { runCatching { it.destroy() } }
    }

    suspend fun run(): Outcome {
        onState(CodexAuthState(status = CodexAuthStatus.STARTING))
        val proc = try {
            launch()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return failed(CodexLoginOutput.failureMessage("", 127))
        }
        process = proc
        try {
            val output = StringBuilder()
            var published: CodexDeviceCode? = null
            val startedAt = now()
            val deadline = startedAt + timeoutMs
            while (true) {
                if (cancelled.get()) return cancelledOutcome()
                append(output, proc.readNewOutput())
                val alive = proc.isAlive
                if (published == null) {
                    published = CodexLoginOutput.parseDeviceCode(output.toString())
                    published?.let {
                        onState(
                            CodexAuthState(
                                status = CodexAuthStatus.AWAITING_AUTH,
                                verificationUrl = it.url,
                                userCode = it.code,
                            ),
                        )
                    }
                }
                if (!alive) break
                val current = now()
                if (published == null && current - startedAt > codeWaitMs) {
                    return failed("Codex didn't give a sign-in code. Check your connection and try again.")
                }
                if (current > deadline) {
                    return failed("The code expired before it was entered. Try again.")
                }
                delay(pollMs)
            }
            append(output, proc.readNewOutput())
            if (cancelled.get()) return cancelledOutcome()
            val exit = proc.exitCode()
            return if (exit == 0 && hasCredentials()) {
                onState(CodexAuthState(status = CodexAuthStatus.SIGNED_IN))
                Outcome.SignedIn
            } else {
                failed(CodexLoginOutput.failureMessage(output.toString(), exit))
            }
        } finally {
            process = null
            if (proc.isAlive) runCatching { proc.destroy() }
        }
    }

    private fun append(output: StringBuilder, chunk: String) {
        if (chunk.isEmpty() || output.length >= MAX_OUTPUT_CHARS) return
        output.append(chunk, 0, minOf(chunk.length, MAX_OUTPUT_CHARS - output.length))
    }

    private fun cancelledOutcome(): Outcome {
        onState(CodexAuthState(status = CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled."))
        return Outcome.Cancelled
    }

    private fun failed(message: String): Outcome {
        onState(CodexAuthState(status = CodexAuthStatus.ERROR, message = message))
        return Outcome.Failed(message)
    }

    companion object {
        /** Codex's own device code lives 15 minutes; one extra minute covers the final poll. */
        const val LOGIN_TIMEOUT_MS = 16L * 60_000L

        /** Codex prints the code right after one request; if nothing usable shows up, stop waiting. */
        const val CODE_WAIT_MS = 60_000L
        private const val MAX_OUTPUT_CHARS = 64 * 1024
    }
}
