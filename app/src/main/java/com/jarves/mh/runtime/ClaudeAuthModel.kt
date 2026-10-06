package com.jarves.mh.runtime

import java.net.URI
import org.json.JSONObject

enum class ClaudeAuthStatusState {
    SIGNED_OUT,
    STARTING,
    AWAITING_AUTH,
    VERIFYING,
    SIGNED_IN,
    EXPIRED,
    ERROR,
}

/** Machine-readable failure cause so the UI never has to parse message strings. */
enum class ClaudeAuthErrorKind {
    NOT_INSTALLED,
    SPAWN_FAILED,
    NO_URL,
    TIMEOUT,
    CODE_REJECTED,
    ORG_RESTRICTED,
    NETWORK,
    EXPIRED,
    UNKNOWN,
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

        /** Tolerates warning lines before/after the JSON object in CLI output. */
        fun fromOutput(output: String): ClaudeStatusMetadata? {
            val start = output.indexOf('{')
            val end = output.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return fromJson(output.substring(start, end + 1))
        }
    }
}

data class ClaudeAuthState(
    val status: ClaudeAuthStatusState = ClaudeAuthStatusState.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: String? = null,
    val subscriptionType: String? = null,
    val authMethod: String? = null,
    val apiProvider: String? = null,
    val errorKind: ClaudeAuthErrorKind? = null,
) {
    val displayStatus: String get() = when (status) {
        ClaudeAuthStatusState.SIGNED_IN -> {
            val tier = subscriptionType?.replaceFirstChar { it.uppercase() } ?: "Pro/Max"
            "Signed in — Claude $tier"
        }
        ClaudeAuthStatusState.STARTING -> "Starting sign in…"
        ClaudeAuthStatusState.AWAITING_AUTH -> "Waiting for authorization…"
        ClaudeAuthStatusState.VERIFYING -> "Checking your sign in…"
        ClaudeAuthStatusState.EXPIRED -> "Session expired — sign in again"
        ClaudeAuthStatusState.ERROR -> message ?: "Sign-in error"
        ClaudeAuthStatusState.SIGNED_OUT -> "Not signed in"
    }

    companion object {
        fun signedIn(metadata: ClaudeStatusMetadata): ClaudeAuthState {
            val tier = metadata.subscriptionType?.replaceFirstChar { it.uppercase() } ?: "Pro/Max"
            return ClaudeAuthState(
                status = ClaudeAuthStatusState.SIGNED_IN,
                subscriptionType = metadata.subscriptionType,
                authMethod = metadata.authMethod,
                apiProvider = metadata.apiProvider,
                message = "Signed in — Claude $tier",
            )
        }
    }
}

/**
 * Detects and validates the authorization link printed by `claude auth login`.
 * The link is only ever passed to the browser byte-for-byte; it is never rewritten.
 */
object ClaudeAuthUrl {
    /** Hosts that may be opened. Anything else is treated as "no link". */
    val ALLOWED_HOSTS: Set<String> = setOf(
        "claude.ai",
        "www.claude.ai",
        "claude.com",
        "www.claude.com",
        "platform.claude.com",
    )
    private const val MAX_LENGTH = 4096
    private val TERMINAL_CODES = Regex(
        "\u001B\\[[0-?]*[ -/]*[@-~]" +
            "|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)" +
            "|\u001B[@-Z\\\\-_]",
    )
    private val CANDIDATE = Regex("https://[^\\s\"'<>\\u0000-\\u001F\\u007F]+")
    private const val TRAILING_PUNCTUATION = ".,;:!?)]}"

    fun stripTerminalCodes(text: String): String = TERMINAL_CODES.replace(text, "")

    fun isAllowed(url: String): Boolean {
        if (url.length > MAX_LENGTH || !url.startsWith("https://")) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.rawUserInfo != null) return false
        if (uri.port != -1 && uri.port != 443) return false
        val host = uri.host?.lowercase() ?: return false
        return host in ALLOWED_HOSTS
    }

    /**
     * Returns the first allowed link in [text]. While output may still be streaming
     * ([complete] = false) a link that touches the end of the buffer is ignored,
     * because it may be cut off mid-way.
     */
    fun extract(text: String, complete: Boolean): String? {
        val clean = stripTerminalCodes(text)
        for (match in CANDIDATE.findAll(clean)) {
            if (!complete && match.range.last + 1 >= clean.length) continue
            val url = match.value.trimEnd { it in TRAILING_PUNCTUATION }
            if (isAllowed(url)) return url
        }
        return null
    }
}

/** Conservative, text-based hints. They only ever trigger a status check, never success on their own. */
object ClaudeLoginOutput {
    private val SUCCESS_HINT = Regex(
        "(?<!not )(?<!never )logged in|login successful|authentication successful",
        RegexOption.IGNORE_CASE,
    )

    fun looksSuccessful(output: String): Boolean = SUCCESS_HINT.containsMatchIn(ClaudeAuthUrl.stripTerminalCodes(output))

    fun classifyFailure(output: String): ClaudeAuthErrorKind {
        val text = ClaudeAuthUrl.stripTerminalCodes(output).lowercase()
        return when {
            listOf("enotfound", "eai_again", "econnrefused", "econnreset", "network error", "fetch failed", "getaddrinfo")
                .any(text::contains) -> ClaudeAuthErrorKind.NETWORK
            listOf("invalid code", "code is invalid", "incorrect code", "invalid_grant", "invalid authorization", "code expired", "expired code")
                .any(text::contains) -> ClaudeAuthErrorKind.CODE_REJECTED
            listOf("organization", "not permitted", "not allowed", "disabled by", "sso")
                .any(text::contains) -> ClaudeAuthErrorKind.ORG_RESTRICTED
            else -> ClaudeAuthErrorKind.UNKNOWN
        }
    }

    fun messageFor(kind: ClaudeAuthErrorKind): String = when (kind) {
        ClaudeAuthErrorKind.NOT_INSTALLED -> "Install Claude Code before signing in."
        ClaudeAuthErrorKind.SPAWN_FAILED -> "Couldn't start sign-in on this device."
        ClaudeAuthErrorKind.NO_URL -> "Claude didn't provide a sign-in link."
        ClaudeAuthErrorKind.TIMEOUT -> "Sign-in timed out. Try again."
        ClaudeAuthErrorKind.CODE_REJECTED -> "That code didn't work. Check it and try again."
        ClaudeAuthErrorKind.ORG_RESTRICTED -> "Your organization may not allow this sign-in."
        ClaudeAuthErrorKind.NETWORK -> "Check your internet connection and try again."
        ClaudeAuthErrorKind.EXPIRED -> "Your Claude session expired. Sign in again."
        ClaudeAuthErrorKind.UNKNOWN -> "Sign-in did not complete. Try again."
    }
}
