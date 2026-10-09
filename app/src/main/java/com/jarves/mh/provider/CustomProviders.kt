package com.jarves.mh.provider

import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * Independent custom-provider profile. [id] is generated once and never derived from
 * name, URL, key or model, so editing any of those keeps the identity stable.
 * API keys live in the encrypted vault under [secretId], never in this object.
 */
data class CustomProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
    val model: String,
    val dshApi: String = "",
    val enabled: Boolean = true,
    /** Lower value is tried first. Ties break on [id] so ordering is deterministic. */
    val priority: Int = 100,
) {
    val secretId: String get() = secretIdFor(id)

    fun effectiveDshApi(): String =
        dshApi.ifBlank { com.jarves.mh.model.inferredDshApiForUrl(baseUrl) }

    fun toProviderProfile(hasSecret: Boolean = false) = ProviderProfile(
        kind = ProviderKind.CUSTOM,
        baseUrl = baseUrl,
        model = model,
        hasSecret = hasSecret,
        dshApi = effectiveDshApi(),
        profileId = id,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("baseUrl", baseUrl).put("model", model)
        .put("dshApi", dshApi).put("enabled", enabled).put("priority", priority)

    companion object {
        fun secretIdFor(profileId: String) = "custom:$profileId"

        fun fromJson(obj: JSONObject): CustomProviderProfile? {
            val id = obj.optString("id").ifBlank { return null }
            val baseUrl = obj.optString("baseUrl")
            val name = obj.optString("name", "Custom provider")
            val model = obj.optString("model")
            if (isRevokedProvider(name) || isRevokedProvider(baseUrl) || isRevokedProvider(model)) {
                return null
            }
            val storedApi = obj.optString("dshApi")
            return CustomProviderProfile(
                id = id,
                name = name,
                baseUrl = baseUrl,
                model = model,
                dshApi = storedApi,
                enabled = obj.optBoolean("enabled", true),
                priority = obj.optInt("priority", 100),
            )
        }

        fun listToJson(profiles: List<CustomProviderProfile>): String =
            JSONArray().also { arr -> profiles.forEach { arr.put(it.toJson()) } }.toString()

        fun listFromJson(raw: String?): List<CustomProviderProfile> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }
}

/** Returns true if [text] refers to a known revoked/phishing provider exchange. */
fun isRevokedProvider(text: String): Boolean {
    val clean = text.trim().lowercase(Locale.ROOT)
    return "aiqana" in clean
}

/** Result of cleaning a user-entered endpoint for a dsh route. */
data class NormalizedEndpoint(val baseUrl: String, val api: String)

object ProviderEndpointNormalizer {
    private val SUFFIXES = listOf(
        "/chat/completions" to "openai-completions",
        "/responses" to "openai-responses",
        "/messages" to "anthropic-messages",
    )

    /**
     * dsh appends the operation path itself, so a base URL that already ends in
     * /chat/completions, /responses or /messages would be requested twice. Strip the
     * operation suffix and let it decide the protocol. A missing scheme becomes https.
     * `/v1` is kept: it is part of the base for OpenAI-compatible gateways.
     */
    fun normalize(rawUrl: String, configuredApi: String): NormalizedEndpoint {
        if (isRevokedProvider(rawUrl)) {
            throw IllegalArgumentException("Revoked phishing provider: $rawUrl")
        }
        var url = rawUrl.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.contains("://")) url = "https://$url"
        val lower = url.lowercase(Locale.ROOT)
        var suffixApi: String? = null
        for ((suffix, suffixApiMapped) in SUFFIXES) {
            if (lower.endsWith(suffix)) {
                url = url.dropLast(suffix.length).trimEnd('/')
                suffixApi = suffixApiMapped
                break
            }
        }
        val api = when {
            suffixApi != null -> suffixApi
            configuredApi.isBlank() -> com.jarves.mh.model.inferredDshApiForUrl(url)
            else -> configuredApi
        }
        return NormalizedEndpoint(url, api)
    }
}

enum class ProviderFailureClass {
    /** Key rejected or throttled: another key of the same profile may work. */
    KEY,
    /** Network, timeout, 5xx: the profile is unhealthy, move to the next profile. */
    TRANSIENT,
    /** Bad endpoint, model or request: no key or profile change will fix it. */
    PERMANENT,
}

object ProviderFailureClassifier {
    private val KEY_MARKERS = listOf(
        "api key", "authentication", "user not found", "http 401", "http 403", "http 429",
        "expired", "quota", "rate limit",
    )
    private val TRANSIENT_MARKERS = listOf(
        "timeout", "timed out", "connection reset", "econnreset", "econnrefused", "enotfound",
        "unknownhost", "unable to resolve", "dns", "network", "socket", "eof",
        "http 500", "http 502", "http 503", "http 504", "http 529", "overloaded",
        "temporarily unavailable", "service unavailable", "bad gateway", "gateway timeout",
    )

    fun classify(reason: String): ProviderFailureClass {
        val value = reason.lowercase(Locale.ROOT)
        return when {
            KEY_MARKERS.any { it in value } -> ProviderFailureClass.KEY
            TRANSIENT_MARKERS.any { it in value } -> ProviderFailureClass.TRANSIENT
            else -> ProviderFailureClass.PERMANENT
        }
    }

    private val SECRET_PATTERNS = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+"),
        Regex("(?i)(api[_-]?key|x-api-key|authorization)([\"'\\s:=]+)[A-Za-z0-9._~+/=-]{6,}"),
        Regex("\\bsk[_-][A-Za-z0-9_-]{6,}"),
    )

    /** Removes anything that looks like a credential, plus any exact known secrets. */
    fun redact(text: String, knownSecrets: Collection<String> = emptyList()): String {
        var out = text
        knownSecrets.filter { it.length >= 4 }.forEach { out = out.replace(it, "••••") }
        out = SECRET_PATTERNS[0].replace(out, "Bearer ••••")
        out = SECRET_PATTERNS[1].replace(out) { "${it.groupValues[1]}${it.groupValues[2]}••••" }
        out = SECRET_PATTERNS[2].replace(out, "sk-••••")
        return out
    }
}

/** One attempt in a fallback chain. [keyId] null means the legacy/active key. */
data class FallbackAttempt(val profile: ProviderProfile, val keyId: String?)

/**
 * Deterministic provider ordering. The selected profile goes first, then enabled
 * profiles by (priority, id). Disabled profiles are never used, even if selected.
 */
object ProviderFallbackPlanner {
    fun orderedProfiles(selectedId: String?, all: List<CustomProviderProfile>): List<CustomProviderProfile> {
        val enabled = all.filter { it.enabled }
        val selected = enabled.firstOrNull { it.id == selectedId }
        val rest = enabled.filter { it.id != selectedId }.sortedWith(compareBy({ it.priority }, { it.id }))
        return listOfNotNull(selected) + rest
    }

    /**
     * Decides what to run after a failure. Returns null when the chain ends.
     * - KEY: another untried key of the same profile, else next profile.
     * - TRANSIENT: next profile (same key would hit the same unhealthy endpoint).
     * - PERMANENT: stop; retrying cannot help and would storm the provider.
     * Each profile and each key is attempted at most once per task.
     */
    fun next(
        failure: ProviderFailureClass,
        current: ProviderProfile,
        untriedKeyIds: List<String>,
        remainingProfiles: List<CustomProviderProfile>,
    ): Step? = when (failure) {
        ProviderFailureClass.PERMANENT -> null
        ProviderFailureClass.KEY ->
            untriedKeyIds.firstOrNull()?.let { Step.NextKey(it) } ?: remainingProfiles.firstOrNull()?.let { Step.NextProfile(it) }
        ProviderFailureClass.TRANSIENT -> remainingProfiles.firstOrNull()?.let { Step.NextProfile(it) }
    }

    sealed interface Step {
        data class NextKey(val keyId: String) : Step
        data class NextProfile(val profile: CustomProviderProfile) : Step
    }
}
