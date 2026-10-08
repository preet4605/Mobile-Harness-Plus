package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.ProviderProtocol
import com.jarves.mh.provider.ProviderEndpointNormalizer

/** Raised when the selected provider cannot drive Codex. The message is shown to the user as-is. */
internal class CodexUnsupportedProviderException(message: String) : IllegalArgumentException(message)

/** How a Codex run authenticates and where its model requests go. */
internal sealed interface CodexRoute {
    val model: String

    /** Codex's own ChatGPT sign-in. Credentials live in `$CODEX_HOME/auth.json` inside the guest. */
    data class ChatGptLogin(override val model: String) : CodexRoute

    /**
     * A Responses-API endpoint reached with an API key that travels only in the process
     * environment under [keyEnv].
     */
    data class ApiKey(
        val providerId: String,
        val baseUrl: String,
        val keyEnv: String,
        override val model: String,
    ) : CodexRoute
}

internal object CodexRouteMapper {
    /** Environment variable that carries the API key into the guest. */
    const val API_KEY_ENV = "MH_CODEX_API_KEY"
    private const val RESPONSES_API = "openai-responses"
    private const val CHAT_API = "openai-completions"

    /**
     * Codex 0.161.0 rejects `wire_api = "chat"`, so only providers that speak the OpenAI
     * Responses API are usable. Everything else fails fast with an explanatory message.
     */
    fun forProfile(profile: ProviderProfile): CodexRoute {
        val model = profile.model.trim()
        return when (profile.kind) {
            ProviderKind.OPENCODE_ZEN -> apiKey(profile, profile.resolvedBaseUrl, model)
            ProviderKind.CUSTOM -> {
                val endpoint = try {
                    ProviderEndpointNormalizer.normalize(profile.resolvedBaseUrl, profile.dshApi)
                } catch (e: IllegalArgumentException) {
                    throw CodexUnsupportedProviderException("This endpoint can't be used with Codex.")
                }
                when (endpoint.api) {
                    RESPONSES_API -> apiKey(profile, endpoint.baseUrl, model)
                    CHAT_API -> throw chatOnly(profile.kind.title)
                    else -> throw unsupported(profile.kind.title)
                }
            }
            else -> if (profile.kind.protocol == ProviderProtocol.OPENAI_CHAT) {
                throw chatOnly(profile.kind.title)
            } else {
                throw unsupported(profile.kind.title)
            }
        }
    }

    private fun apiKey(profile: ProviderProfile, baseUrl: String, model: String): CodexRoute.ApiKey {
        val url = baseUrl.trim().trimEnd('/')
        if (!(url.startsWith("https://") || url.startsWith("http://"))) {
            throw CodexUnsupportedProviderException("Enter a valid http(s) endpoint for ${profile.kind.title}.")
        }
        return CodexRoute.ApiKey(
            providerId = providerIdFor(profile),
            baseUrl = url,
            keyEnv = API_KEY_ENV,
            model = model,
        )
    }

    private fun chatOnly(title: String) = CodexUnsupportedProviderException(
        "Codex only works with providers that support the OpenAI Responses API. " +
            "$title is Chat Completions only.",
    )

    private fun unsupported(title: String) = CodexUnsupportedProviderException(
        "$title can't be used with Codex. Pick an OpenAI or OpenAI-compatible Responses provider.",
    )

    /** `mh-` prefix keeps the id clear of Codex's built-in provider ids (openai, ollama, lmstudio). */
    internal fun providerIdFor(profile: ProviderProfile): String {
        val suffix = (profile.profileId.ifBlank { profile.kind.name })
            .lowercase()
            .filter { it.isLetterOrDigit() }
            .take(16)
            .ifBlank { "custom" }
        return "mh-$suffix"
    }
}

/** Builds the `codex exec` argument vector and environment. Pure; no Android dependencies. */
internal object CodexLaunchBuilder {
    const val CODEX_GUEST_PATH = "/usr/local/bin/codex"
    const val CODEX_HOME_GUEST_PATH = "/root/.codex"

    /**
     * `codex exec` reading the prompt from stdin (`-`), so a long conversation context never
     * travels in argv. The sandbox is off because the whole guest is already confined by PRoot;
     * approvals are `never` because exec mode has no interactive channel.
     */
    fun command(route: CodexRoute, guestWorkspacePath: String, lastMessageGuestPath: String): List<String> =
        buildList {
            add(CODEX_GUEST_PATH)
            add("exec")
            add("--json")
            add("--color")
            add("never")
            add("--skip-git-repo-check")
            add("--ephemeral")
            add("-s")
            add("danger-full-access")
            add("-C")
            add(guestWorkspacePath)
            if (route.model.isNotBlank()) {
                add("-m")
                add(route.model)
            }
            add("-o")
            add(lastMessageGuestPath)
            configOverrides(route).forEach {
                add("-c")
                add(it)
            }
            add("-")
        }

    internal fun configOverrides(route: CodexRoute): List<String> = buildList {
        add("approval_policy=${tomlString("never")}")
        add("check_for_update_on_startup=false")
        add("analytics.enabled=false")
        add("cli_auth_credentials_store=${tomlString("file")}")
        if (route is CodexRoute.ApiKey) {
            add("model_provider=${tomlString(route.providerId)}")
            add(
                "model_providers.${route.providerId}={" +
                    "name=${tomlString(route.providerId)}," +
                    "base_url=${tomlString(route.baseUrl)}," +
                    "env_key=${tomlString(route.keyEnv)}," +
                    "wire_api=${tomlString("responses")}}",
            )
        }
    }

    /** [secret] is required for [CodexRoute.ApiKey] and ignored for ChatGPT sign-in. */
    fun environment(route: CodexRoute, secret: String?): Map<String, String> {
        val env = linkedMapOf(
            "CODEX_HOME" to CODEX_HOME_GUEST_PATH,
            "NO_COLOR" to "1",
        )
        if (route is CodexRoute.ApiKey) {
            require(!secret.isNullOrBlank()) { "No API key is saved for this provider." }
            env[route.keyEnv] = secret
        }
        return env
    }

    /** TOML basic string. `-c key=value` parses the value as TOML. */
    internal fun tomlString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' || ch == '\u007f' -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }
}

/** Maps raw Codex failure text to short, actionable messages. Never echoes secrets. */
internal object CodexFailureMessages {
    fun friendly(raw: String, exitCode: Int? = null): String {
        val text = raw.trim()
        return when {
            text.contains("Incorrect API key", true) ||
                text.contains("invalid_api_key", true) ||
                text.contains("401", true) && text.contains("Unauthorized", true) ->
                "The provider rejected the saved API key."
            text.contains("Missing environment variable", true) ->
                "No API key reached Codex. Re-save the key in Settings."
            text.contains("not logged in", true) ||
                text.contains("log in again", true) ||
                text.contains("refresh token", true) ->
                "Codex is not signed in. Sign in from Settings → Coding agent."
            text.contains("429", true) || text.contains("rate limit", true) ||
                text.contains("usage limit", true) || text.contains("quota", true) ->
                "The provider reported a rate limit or quota problem. Try again later."
            text.contains("wire_api", true) || text.contains("Error loading config", true) ->
                "Codex rejected its generated configuration. Reinstall Codex or pick another provider."
            text.contains("stream disconnected", true) ||
                text.contains("Connection failed", true) ||
                text.contains("error sending request", true) ||
                text.contains("waiting for network", true) ||
                text.contains("connection reset", true) ||
                text.contains("broken pipe", true) ->
                "Network connection interrupted. Please check your internet connection."
            text.isNotBlank() -> text.take(500)
            exitCode == 126 || exitCode == 127 ->
                "Codex could not start (exit code $exitCode). Reinstall it from Settings → Coding agent."
            exitCode == 137 -> "Codex was killed, likely by Android because the device ran low on memory."
            exitCode == 134 || exitCode == 139 -> "Codex crashed (exit code $exitCode)."
            exitCode != null -> "Codex stopped before reporting completion (exit code $exitCode)."
            else -> "Codex could not start."
        }
    }
}
