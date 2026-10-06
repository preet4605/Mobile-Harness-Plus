package com.jarves.mh.runtime

import java.util.concurrent.ConcurrentLinkedQueue

/** A running `claude` CLI invocation, abstracted so the login logic is testable without Android. */
internal interface ClaudeCliProcess {
    val isAlive: Boolean

    /** Output produced since the previous call (empty when none). */
    fun readNewOutput(): String
    fun write(text: String)

    /** Kills the process and everything it spawned. Safe to call repeatedly. */
    fun destroy()
}

internal fun interface ClaudeCliLauncher {
    fun launch(args: List<String>): ClaudeCliProcess
}

/**
 * Drives one `claude auth login --claudeai` attempt. Claude Code owns the OAuth flow;
 * this class only watches its output for the link, forwards a pasted code, and then
 * decides success from `claude auth status --json`, never from output text alone.
 *
 * [run] blocks; call it from a background thread. [cancel] and [submitCode] are thread-safe.
 */
internal class ClaudeLoginEngine(
    private val launcher: ClaudeCliLauncher,
    private val queryStatus: () -> ClaudeStatusMetadata?,
    private val onState: (ClaudeAuthState) -> Unit,
    private val onUrl: (String) -> Unit,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val deadlineMs: Long = DEFAULT_DEADLINE_MS,
) {
    sealed interface Outcome {
        data class SignedIn(val status: ClaudeStatusMetadata) : Outcome
        data object Cancelled : Outcome
        data class Failed(val kind: ClaudeAuthErrorKind, val message: String) : Outcome
    }

    @Volatile private var cancelRequested = false
    private val pendingCodes = ConcurrentLinkedQueue<String>()

    fun cancel() {
        cancelRequested = true
    }

    fun submitCode(code: String) {
        val clean = code.trim()
        if (clean.isNotEmpty()) pendingCodes.add(clean)
    }

    fun run(): Outcome {
        val startedAt = nowMs()
        onState(ClaudeAuthState(ClaudeAuthStatusState.STARTING, message = "Starting Claude sign-in…"))
        val process = try {
            launcher.launch(listOf("auth", "login", "--claudeai"))
        } catch (t: Throwable) {
            return fail(ClaudeAuthErrorKind.SPAWN_FAILED, ClaudeLoginOutput.messageFor(ClaudeAuthErrorKind.SPAWN_FAILED))
        }
        val output = StringBuilder()
        var url: String? = null
        var lastGrowthAt = startedAt
        var hintCheckedAtLength = -1
        var nextCodeCheckAt = Long.MAX_VALUE
        var codeChecksLeft = 0
        try {
            while (true) {
                if (cancelRequested) return cancelled()
                val now = nowMs()
                if (now - startedAt > deadlineMs) {
                    return fail(ClaudeAuthErrorKind.TIMEOUT, ClaudeLoginOutput.messageFor(ClaudeAuthErrorKind.TIMEOUT))
                }

                val chunk = process.readNewOutput()
                if (chunk.isNotEmpty()) {
                    output.append(chunk)
                    if (output.length > BUFFER_LIMIT) output.delete(0, output.length - BUFFER_LIMIT)
                    lastGrowthAt = now
                }
                val alive = process.isAlive

                if (url == null) {
                    val settled = !alive || now - lastGrowthAt >= URL_SETTLE_MS
                    ClaudeAuthUrl.extract(output.toString(), complete = settled)?.let { found ->
                        url = found
                        onState(
                            ClaudeAuthState(
                                ClaudeAuthStatusState.AWAITING_AUTH,
                                authorizationUrl = found,
                                message = "Finish signing in on Claude.ai, or paste the code below.",
                            ),
                        )
                        onUrl(found)
                    }
                }

                while (true) {
                    val code = pendingCodes.poll() ?: break
                    runCatching { process.write("$code\r\n") }
                    nextCodeCheckAt = now + CODE_FIRST_CHECK_MS
                    codeChecksLeft = CODE_CHECKS
                    onState(
                        ClaudeAuthState(
                            ClaudeAuthStatusState.VERIFYING,
                            authorizationUrl = url,
                            message = "Checking your code…",
                        ),
                    )
                }

                if (!alive) {
                    val tail = process.readNewOutput()
                    if (tail.isNotEmpty()) output.append(tail)
                    return finishAfterExit(output.toString(), url)
                }

                if (output.length != hintCheckedAtLength && ClaudeLoginOutput.looksSuccessful(output.toString())) {
                    hintCheckedAtLength = output.length
                    verifySignedIn(attempts = HINT_CHECKS, url = url)?.let { return it }
                    if (cancelRequested) return cancelled()
                }

                if (now >= nextCodeCheckAt) {
                    val status = queryStatus()
                    if (status?.loggedIn == true) return signedIn(status)
                    codeChecksLeft -= 1
                    if (codeChecksLeft <= 0) {
                        return fail(ClaudeAuthErrorKind.CODE_REJECTED, ClaudeLoginOutput.messageFor(ClaudeAuthErrorKind.CODE_REJECTED))
                    }
                    nextCodeCheckAt = nowMs() + CODE_CHECK_INTERVAL_MS
                }

                sleep(POLL_MS)
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

    private fun finishAfterExit(output: String, url: String?): Outcome {
        verifySignedIn(attempts = EXIT_CHECKS, url = url)?.let { return it }
        if (cancelRequested) return cancelled()
        val kind = when {
            url == null && ClaudeLoginOutput.classifyFailure(output) == ClaudeAuthErrorKind.UNKNOWN -> ClaudeAuthErrorKind.NO_URL
            else -> ClaudeLoginOutput.classifyFailure(output)
        }
        return fail(kind, ClaudeLoginOutput.messageFor(kind))
    }

    /** Polls `auth status` up to [attempts] times; null means "not signed in (yet)". */
    private fun verifySignedIn(attempts: Int, url: String?): Outcome? {
        onState(ClaudeAuthState(ClaudeAuthStatusState.VERIFYING, authorizationUrl = url, message = "Checking your sign in…"))
        repeat(attempts) { index ->
            if (cancelRequested) return null
            val status = queryStatus()
            if (status?.loggedIn == true) return signedIn(status)
            if (index < attempts - 1) sleep(VERIFY_INTERVAL_MS)
        }
        if (url != null) {
            onState(
                ClaudeAuthState(
                    ClaudeAuthStatusState.AWAITING_AUTH,
                    authorizationUrl = url,
                    message = "Finish signing in on Claude.ai, or paste the code below.",
                ),
            )
        }
        return null
    }

    private fun signedIn(status: ClaudeStatusMetadata): Outcome {
        onState(ClaudeAuthState.signedIn(status))
        return Outcome.SignedIn(status)
    }

    private fun cancelled(): Outcome {
        onState(ClaudeAuthState(ClaudeAuthStatusState.SIGNED_OUT, message = "Sign in cancelled."))
        return Outcome.Cancelled
    }

    private fun fail(kind: ClaudeAuthErrorKind, message: String): Outcome {
        onState(ClaudeAuthState(ClaudeAuthStatusState.ERROR, message = message, errorKind = kind))
        return Outcome.Failed(kind, message)
    }

    companion object {
        const val DEFAULT_DEADLINE_MS = 10 * 60 * 1000L
        const val BUFFER_LIMIT = 16 * 1024
        const val POLL_MS = 100L
        const val URL_SETTLE_MS = 1_500L
        const val VERIFY_INTERVAL_MS = 2_000L
        const val EXIT_CHECKS = 5
        const val HINT_CHECKS = 3
        const val CODE_FIRST_CHECK_MS = 3_000L
        const val CODE_CHECK_INTERVAL_MS = 4_000L
        const val CODE_CHECKS = 5
    }
}
