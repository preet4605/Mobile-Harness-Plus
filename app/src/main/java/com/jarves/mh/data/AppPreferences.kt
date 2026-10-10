package com.jarves.mh.data

import android.content.Context
import android.util.Log
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import com.jarves.mh.model.ModelQuota
import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.LinkedSkillReference
import com.jarves.mh.model.ProjectCustomizationConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Part of a chat: [messages] are the chat's messages from index [startIndex] on, in order. */
class ChatMessageWindow(val startIndex: Int, val messages: List<ChatMessage>)

class AppPreferences(
    private val context: Context? = null,
    baseChatsDir: File? = null,
) {
    private val preferences by lazy {
        context?.getSharedPreferences("pocket_preferences", Context.MODE_PRIVATE)
            ?: error("Context is required for shared preferences access")
    }

    var onboardingComplete: Boolean
        get() = preferences.getBoolean("onboarding_complete", false)
        set(value) { preferences.edit().putBoolean("onboarding_complete", value).apply() }

    var runtimeSetupComplete: Boolean
        get() = preferences.getBoolean("runtime_setup_complete", false)
        set(value) { preferences.edit().putBoolean("runtime_setup_complete", value).apply() }

    var backgroundSetupComplete: Boolean
        get() = preferences.getBoolean("background_setup_complete", false)
        set(value) { preferences.edit().putBoolean("background_setup_complete", value).apply() }

    /** Coding agent engine the user picked during setup. Absent = pre-agent-choice install → Claude. */
    var agentKind: String
        get() = preferences.getString("agent_kind", AgentKind.CLAUDE_CODE.stableId) ?: AgentKind.CLAUDE_CODE.stableId
        set(value) { preferences.edit().putString("agent_kind", value).apply() }

    /** Agent chosen for the initial runtime installation; used as the leading UI tab. */
    var primaryAgentKind: String
        get() = preferences.getString("primary_agent_kind", "") ?: ""
        set(value) { preferences.edit().putString("primary_agent_kind", value).apply() }

    var claudeModel: String
        get() = preferences.getString("claude_model", "default") ?: "default"
        set(value) { preferences.edit().putString("claude_model", value).apply() }

    var claudeThinkingLevel: String
        get() = preferences.getString("claude_thinking_level", "default") ?: "default"
        set(value) { preferences.edit().putString("claude_thinking_level", value).apply() }

    var dshReasoningEffort: String
        get() = preferences.getString("dsh_reasoning_effort", "default")
            ?.takeIf { it in listOf("default", "off", "low", "high", "max") } ?: "default"
        set(value) { preferences.edit().putString("dsh_reasoning_effort", value).apply() }

    var antigravityModel: String
        get() = preferences.getString("agent_antigravity_model", "") ?: ""
        set(value) { preferences.edit().putString("agent_antigravity_model", value).apply() }

    var antigravityEffort: String
        get() = preferences.getString("agent_antigravity_effort", "high") ?: "high"
        set(value) { preferences.edit().putString("agent_antigravity_effort", value).apply() }

    var antigravityLoadBalancingStrategy: AntigravityLoadBalancingStrategy
        get() = runCatching {
            AntigravityLoadBalancingStrategy.valueOf(
                preferences.getString("agent_antigravity_lb_strategy", AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED.name)
                    ?: AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED.name
            )
        }.getOrDefault(AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED)
        set(value) { preferences.edit().putString("agent_antigravity_lb_strategy", value.name).apply() }

    var antigravityFailoverEnabled: Boolean
        get() = preferences.getBoolean("agent_antigravity_failover_enabled", true)
        set(value) { preferences.edit().putBoolean("agent_antigravity_failover_enabled", value).apply() }

    fun loadAntigravityAccounts(): List<AntigravityAccount> {
        val raw = preferences.getString("antigravity_accounts_json", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id").ifBlank { return@mapNotNull null }
                val email = obj.optString("email").ifBlank { return@mapNotNull null }
                val modelQuotas = mutableMapOf<String, ModelQuota>()
                val quotasObj = obj.optJSONObject("modelQuotas")
                if (quotasObj != null) {
                    val keys = quotasObj.keys()
                    while (keys.hasNext()) {
                        val mKey = keys.next()
                        val qObj = quotasObj.optJSONObject(mKey) ?: continue
                        modelQuotas[mKey] = ModelQuota(
                            remainingFraction = qObj.optDouble("remainingFraction", 1.0).toFloat(),
                            resetTimeMillis = if (qObj.has("resetTimeMillis")) qObj.getLong("resetTimeMillis") else null,
                            lastFetchedMillis = qObj.optLong("lastFetchedMillis", System.currentTimeMillis()),
                        )
                    }
                }
                AntigravityAccount(
                    id = id,
                    email = email,
                    label = obj.optString("label"),
                    addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                    lastUsedAt = obj.optLong("lastUsedAt", 0L),
                    status = runCatching { AntigravityAccountStatus.valueOf(obj.optString("status", AntigravityAccountStatus.HEALTHY.name)) }.getOrDefault(AntigravityAccountStatus.HEALTHY),
                    quotaExhaustedUntil = if (obj.has("quotaExhaustedUntil")) obj.getLong("quotaExhaustedUntil") else null,
                    failureMessage = obj.optString("failureMessage").takeIf(String::isNotBlank),
                    isPrimary = obj.optBoolean("isPrimary", false),
                    modelQuotas = modelQuotas,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveAntigravityAccounts(accounts: List<AntigravityAccount>) {
        val arr = JSONArray()
        accounts.forEach { acc ->
            val obj = JSONObject()
                .put("id", acc.id)
                .put("email", acc.email)
                .put("label", acc.label)
                .put("addedAt", acc.addedAt)
                .put("lastUsedAt", acc.lastUsedAt)
                .put("status", acc.status.name)
                .put("isPrimary", acc.isPrimary)
            acc.quotaExhaustedUntil?.let { obj.put("quotaExhaustedUntil", it) }
            acc.failureMessage?.let { obj.put("failureMessage", it) }
            if (acc.modelQuotas.isNotEmpty()) {
                val quotasObj = JSONObject()
                acc.modelQuotas.forEach { (mKey, mQuota) ->
                    val qObj = JSONObject()
                        .put("remainingFraction", mQuota.remainingFraction.toDouble())
                        .put("lastFetchedMillis", mQuota.lastFetchedMillis)
                    mQuota.resetTimeMillis?.let { qObj.put("resetTimeMillis", it) }
                    quotasObj.put(mKey, qObj)
                }
                obj.put("modelQuotas", quotasObj)
            }
            arr.put(obj)
        }
        preferences.edit().putString("antigravity_accounts_json", arr.toString()).apply()
    }

    fun saveAgentConversationAccount(projectId: String, chatId: String, accountId: String) {
        preferences.edit().putString("agent_conversation_account_${projectId}_$chatId", accountId).apply()
    }

    fun loadAgentConversationAccount(projectId: String, chatId: String): String? =
        preferences.getString("agent_conversation_account_${projectId}_$chatId", null)

    var antigravitySignedIn: Boolean
        get() {
            val accounts = loadAntigravityAccounts()
            if (accounts.isNotEmpty()) return accounts.any { it.status != AntigravityAccountStatus.DISABLED && it.status != AntigravityAccountStatus.AUTH_ERROR }
            return preferences.getBoolean("agent_antigravity_signed_in", false)
        }
        set(value) { preferences.edit().putBoolean("agent_antigravity_signed_in", value).apply() }

    /** Mirror of whether Codex holds ChatGPT credentials; the credentials themselves stay in the runtime. */
    var codexSignedIn: Boolean
        get() = preferences.getBoolean("agent_codex_signed_in", false)
        set(value) { preferences.edit().putBoolean("agent_codex_signed_in", value).apply() }

    /** Codex's `model_reasoning_effort`; blank leaves Codex on its own default. */
    var codexReasoningEffort: String
        get() = preferences.getString("codex_reasoning_effort", "") ?: ""
        set(value) { preferences.edit().putString("codex_reasoning_effort", value).apply() }

    /** Last model list discovered for this agent, provider and endpoint. Settings and chat both read it. */
    fun saveModelList(agent: AgentKind, kind: ProviderKind, baseUrl: String, models: List<com.jarves.mh.network.DiscoveredModel>) {
        preferences.edit().putString(ModelListCache.key(agent, kind, baseUrl), ModelListCache.encode(models)).apply()
    }

    fun loadModelList(agent: AgentKind, kind: ProviderKind, baseUrl: String): List<com.jarves.mh.network.DiscoveredModel> =
        ModelListCache.decode(preferences.getString(ModelListCache.key(agent, kind, baseUrl), null))

    /** The last `rate_limit_event` line Claude printed, kept for the Usage section. */
    var claudeRateLimitEvent: String?
        get() = preferences.getString("claude_rate_limit_event", null)
        set(value) { preferences.edit().putString("claude_rate_limit_event", value).apply() }

    var claudeMaxTurns: Int
        get() = preferences.getInt("claude_max_turns", 25).coerceIn(1, 200)
        set(value) { preferences.edit().putInt("claude_max_turns", value.coerceIn(1, 200)).apply() }

    var claudeInteractiveApprovals: Boolean
        get() = preferences.getBoolean("claude_interactive_approvals", false)
        set(value) { preferences.edit().putBoolean("claude_interactive_approvals", value).apply() }

    var claudeRateLimitReportedAtMillis: Long
        get() = preferences.getLong("claude_rate_limit_reported_at", 0L)
        set(value) { preferences.edit().putLong("claude_rate_limit_reported_at", value).apply() }

    var antigravityAccountEmail: String
        get() {
            val accounts = loadAntigravityAccounts()
            if (accounts.isNotEmpty()) {
                val primary = accounts.firstOrNull { it.isPrimary } ?: accounts.first()
                return primary.email
            }
            return preferences.getString("agent_antigravity_account_email", "") ?: ""
        }
        set(value) { preferences.edit().putString("agent_antigravity_account_email", value).apply() }

    var githubLogin: String
        get() = preferences.getString("github_login", "") ?: ""
        set(value) { preferences.edit().putString("github_login", value).apply() }

    fun saveAgentConversation(agent: AgentKind, projectId: String, chatId: String, conversationId: String?) {
        val key = agentConversationKey(agent, projectId, chatId)
        preferences.edit().apply {
            if (conversationId.isNullOrBlank()) remove(key) else putString(key, conversationId)
        }.apply()
    }

    fun loadAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? =
        preferences.getString(agentConversationKey(agent, projectId, chatId), null)

    /** Clears this chat's native session, including route-scoped resume references. */
    // Commit invalidation before spawning a native turn: a crash cannot reuse a partial turn.
    fun consumeAgentConversation(agent: AgentKind, projectId: String, chatId: String): String? {
        val key = agentConversationKey(agent, projectId, chatId)
        val value = preferences.getString(key, null)
        return if (preferences.edit().remove(key).commit()) value else null
    }

    fun clearAgentConversation(agent: AgentKind, projectId: String, chatId: String) {
        val key = agentConversationKey(agent, projectId, chatId)
        val keys = preferences.all.keys.filter { it == key || it.startsWith("$key:") }
        preferences.edit().apply {
            keys.forEach(::remove)
            remove("agent_conversation_account_${projectId}_$chatId")
        }.apply()
    }

    fun clearAgentConversations(agent: AgentKind) {
        val stable = agent.stableId
        val keys = preferences.all.keys.filter { key ->
            key.startsWith("agent_conversation_${stable}_") ||
                key.startsWith("agent_conversation_v2_${stable}_")
        }
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun agentConversationKey(agent: AgentKind, projectId: String, chatId: String): String {
        // Antigravity v2 sessions are created with an explicit CLI project so
        // old default-project conversations cannot redirect writes to scratch.
        val version = if (agent == AgentKind.ANTIGRAVITY) "v2_" else ""
        return "agent_conversation_${version}${agent.stableId}_${projectId}_$chatId"
    }

    /** Pinned dsh version recorded when DeepSeek Harness was installed. */
    var dshVersion: String
        get() = preferences.getString("dsh_version", "") ?: ""
        set(value) { preferences.edit().putString("dsh_version", value).apply() }

    var themeMode: String
        get() = preferences.getString("theme_mode", "dark") ?: "dark"
        set(value) { preferences.edit().putString("theme_mode", value).apply() }

    var reduceTransparency: Boolean
        get() = preferences.getBoolean("reduce_transparency", false)
        set(value) { preferences.edit().putBoolean("reduce_transparency", value).apply() }

    var legacySeededCredentialRemoved: Boolean
        get() = preferences.getBoolean("legacy_seeded_credential_removed", false)
        set(value) { preferences.edit().putBoolean("legacy_seeded_credential_removed", value).apply() }

    var testProviderDefaultsVersion: Int
        get() = preferences.getInt("test_provider_defaults_version", 0)
        set(value) { preferences.edit().putInt("test_provider_defaults_version", value).apply() }

    var lastAppUpdateCheckMillis: Long
        get() = preferences.getLong("last_app_update_check_millis", 0L)
        set(value) { preferences.edit().putLong("last_app_update_check_millis", value).apply() }

    /**
     * Debug-only manifest URL override. Empty in release builds; populated via
     * Settings → Update channel in debug builds so a local server (exposed via
     * Cloudflare Tunnel or ngrok) can be tested without publishing a release.
     */
    var debugUpdateManifestUrl: String
        get() = preferences.getString("debug_update_manifest_url", "") ?: ""
        set(value) { preferences.edit().putString("debug_update_manifest_url", value).apply() }

    /** Development stacks the user picked during onboarding (names of DevStack). */
    var selectedDevStacks: Set<String>
        get() {
            val raw = preferences.getString("selected_dev_stacks", null) ?: return emptySet()
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
            }.getOrDefault(emptySet())
        }
        set(value) {
            val arr = JSONArray()
            value.sorted().forEach(arr::put)
            preferences.edit().putString("selected_dev_stacks", arr.toString()).apply()
        }


    var claudeAuthMode: com.jarves.mh.model.ClaudeAuthMode
        get() = runCatching {
            com.jarves.mh.model.ClaudeAuthMode.valueOf(
                preferences.getString("claude_auth_mode", com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION.name)
                    ?: com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION.name
            )
        }.getOrDefault(com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION)
        set(value) { preferences.edit().putString("claude_auth_mode", value.name).apply() }

    /**
     * Records how Claude Code authenticates (account sign-in vs setup token) for one agent
     * without rewriting any other provider setting, so switching modes cannot disturb the
     * provider profiles of other agents.
     */
    fun saveClaudeAuthMode(mode: com.jarves.mh.model.ClaudeAuthMode, agent: AgentKind = AgentKind.CLAUDE_CODE) {
        preferences.edit()
            .putString("claude_auth_mode", mode.name)
            .putString("${providerPrefix(agent)}claude_auth_mode", mode.name)
            .apply()
    }

    /** Saves [profile] only under [agent]'s own keys, leaving the globally active provider untouched. */
    fun saveProviderForAgentOnly(profile: ProviderProfile, agent: AgentKind) {
        val prefix = providerPrefix(agent)
        val editor = preferences.edit()
            .putString("${prefix}kind", profile.kind.name)
            .putString("${prefix}base_url", profile.baseUrl)
            .putString("${prefix}model", profile.model)
            .putString("${prefix}dsh_api", profile.dshApi)
            .putBoolean("${prefix}dsh_api_explicit", true)
            .putString("${prefix}profile_id", profile.profileId)
            .putString("${prefix}claude_auth_mode", profile.claudeAuthMode.name)
        if (profile.kind == ProviderKind.CLAUDE) {
            editor
                .putString("claude_model", profile.model)
                .putString("claude_thinking_level", profile.claudeThinkingLevel)
        }
        editor.apply()
    }

    fun saveProvider(profile: ProviderProfile, agent: AgentKind? = null) {
        val editor = preferences.edit()
            .putString("provider_kind", profile.kind.name)
            .putString("provider_base_url", profile.baseUrl)
            .putString("provider_model", profile.model)
            .putString("provider_dsh_api", profile.dshApi)
            .putString("provider_profile_id", profile.profileId)
            .putString("claude_auth_mode", profile.claudeAuthMode.name)
        if (profile.kind == ProviderKind.CLAUDE) {
            editor
                .putString("claude_model", profile.model)
                .putString("claude_thinking_level", profile.claudeThinkingLevel)
        }
        if (agent != null) {
            val prefix = providerPrefix(agent)
            editor
                .putString("${prefix}kind", profile.kind.name)
                .putString("${prefix}base_url", profile.baseUrl)
                .putString("${prefix}model", profile.model)
                .putString("${prefix}dsh_api", profile.dshApi)
                .putBoolean("${prefix}dsh_api_explicit", true)
                .putString("${prefix}profile_id", profile.profileId)
                .putString("${prefix}claude_auth_mode", profile.claudeAuthMode.name)
        }
        editor.apply()
    }

    fun loadProvider(vault: ApiKeyVault, agent: AgentKind? = null): ProviderProfile {
        val prefix = agent?.let(::providerPrefix)
        val hasAgentProfile = prefix != null && preferences.contains("${prefix}kind")
        val sourcePrefix = if (hasAgentProfile) prefix.orEmpty() else "provider_"
        val storedKind = runCatching {
            ProviderKind.valueOf(preferences.getString("${sourcePrefix}kind", null).orEmpty())
        }.getOrNull()
        val storedBaseUrl = storedKind?.let {
            preferences.getString("${sourcePrefix}base_url", it.defaultBaseUrl) ?: it.defaultBaseUrl
        }.orEmpty()
        val storedModel = storedKind?.let {
            preferences.getString("${sourcePrefix}model", it.defaultModel) ?: it.defaultModel
        }.orEmpty()
        val storedAuthMode = runCatching {
            com.jarves.mh.model.ClaudeAuthMode.valueOf(
                preferences.getString("${sourcePrefix}claude_auth_mode", null)
                    ?: preferences.getString("claude_auth_mode", com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION.name)
                    ?: com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION.name
            )
        }.getOrDefault(com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION)
        // Older builds copied the global Claude/Anthropic default into a new
        // DeepSeek Harness profile. Treat that untouched, keyless placeholder
        // as unconfigured so DeepSeek opens on its own official provider.
        val legacyClaudeDefaultInDeepSeek = agent == AgentKind.DEEPSEEK_HARNESS &&
            storedKind == ProviderKind.ANTHROPIC &&
            !vault.contains(ProviderKind.ANTHROPIC.name) &&
            storedBaseUrl == ProviderKind.ANTHROPIC.defaultBaseUrl &&
            storedModel == ProviderKind.ANTHROPIC.defaultModel
        val kind = when {
            agent == null -> storedKind ?: ProviderKind.ANTHROPIC
            agent == AgentKind.DEEPSEEK_HARNESS && (!hasAgentProfile || legacyClaudeDefaultInDeepSeek) -> ProviderKind.DEEPSEEK
            storedKind != null && storedKind in providersForAgent(agent) -> storedKind
            agent == AgentKind.DEEPSEEK_HARNESS -> ProviderKind.DEEPSEEK
            agent == AgentKind.CODEX -> ProviderKind.CHATGPT
            else -> ProviderKind.ANTHROPIC
        }
        val useStoredValues = storedKind == kind
        val savedModel = if (useStoredValues) {
            preferences.getString("${sourcePrefix}model", kind.defaultModel) ?: kind.defaultModel
        } else {
            kind.defaultModel
        }
        // DeepSeek retired its legacy alias. Migrate only DeepSeek profiles so
        // custom and gateway providers keep their independently selected model.
        val model = if (
            kind == ProviderKind.DEEPSEEK &&
            savedModel in setOf("deepseek-chat", "deepseek-reasoner")
        ) {
            kind.defaultModel.also {
                preferences.edit().putString("${sourcePrefix}model", it).apply()
            }
        } else {
            savedModel
        }
        val isNativeClaude = kind == ProviderKind.CLAUDE && storedAuthMode == com.jarves.mh.model.ClaudeAuthMode.NATIVE_SUBSCRIPTION
        val hasTokenHarborDrift = storedBaseUrl.contains("tokenharbor", ignoreCase = true) ||
            savedModel.contains("tokenharbor", ignoreCase = true) ||
            preferences.getString("claude_model", "")?.contains("tokenharbor", ignoreCase = true) == true ||
            hasTokenHarborDrift()
        val tokenHarborKeyDeleted = hasTokenHarborDrift &&
            (!vault.contains(kind.name) || vault.list(kind.name).isEmpty())
        if (tokenHarborKeyDeleted) {
            purgeTokenHarbor()
        }
        val isStoredRevoked = com.jarves.mh.provider.isRevokedProvider(storedBaseUrl) ||
            com.jarves.mh.provider.isRevokedProvider(savedModel)
        val hasRevokedDrift = isStoredRevoked ||
            com.jarves.mh.provider.isRevokedProvider(preferences.getString("claude_model", "").orEmpty()) ||
            hasRevokedProviderDrift()
        val revokedKeyDeleted = hasRevokedDrift &&
            (!vault.contains(kind.name) || vault.list(kind.name).isEmpty() || isStoredRevoked)
        if (revokedKeyDeleted || isStoredRevoked) {
            purgeRevokedProviders()
        }
        val effectiveModel = if (tokenHarborKeyDeleted || revokedKeyDeleted || isStoredRevoked || com.jarves.mh.provider.isRevokedProvider(model)) {
            kind.defaultModel
        } else if (kind == ProviderKind.CLAUDE) {
            val savedClaude = preferences.getString("claude_model", "default") ?: "default"
            if (savedClaude.contains("tokenharbor", ignoreCase = true) || com.jarves.mh.provider.isRevokedProvider(savedClaude)) {
                preferences.edit().putString("claude_model", "default").apply()
                "default"
            } else if (com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS.any { it.id.equals(savedClaude, ignoreCase = true) }) savedClaude else "default"
        } else {
            model
        }
        val claudeThinking = if (kind == ProviderKind.CLAUDE) {
            preferences.getString("claude_thinking_level", "default") ?: "default"
        } else {
            "default"
        }
        val effectiveBaseUrl = if (tokenHarborKeyDeleted || revokedKeyDeleted || isStoredRevoked) {
            kind.defaultBaseUrl
        } else if (useStoredValues) {
            preferences.getString("${sourcePrefix}base_url", kind.defaultBaseUrl) ?: kind.defaultBaseUrl
        } else {
            kind.defaultBaseUrl
        }
        val effectiveDshApi = if (useStoredValues) {
            val stored = preferences.getString("${sourcePrefix}dsh_api", null)
            val defaultApi = defaultDshApiForProvider(kind)
            val candidate = if (stored.isNullOrBlank()) defaultApi else stored
            // Claude Code never exposed a protocol choice before, so a stored CUSTOM value there is
            // only a silent default. Honor it solely when the user saved it explicitly or it came
            // from a multi-profile custom provider; otherwise keep the legacy Anthropic route.
            val legacyClaudeCustom = agent == AgentKind.CLAUDE_CODE && kind == ProviderKind.CUSTOM &&
                preferences.getString("${sourcePrefix}profile_id", "").isNullOrBlank() &&
                !(hasAgentProfile && preferences.getBoolean("${prefix}dsh_api_explicit", false))
            if (legacyClaudeCustom) {
                "anthropic-messages"
            } else {
                candidate
            }
        } else {
            defaultDshApiForProvider(kind)
        }
        return ProviderProfile(
            kind = kind,
            baseUrl = effectiveBaseUrl,
            model = effectiveModel,
            hasSecret = (!tokenHarborKeyDeleted && !revokedKeyDeleted && !isStoredRevoked) && (vault.contains(kind.name) || (kind == ProviderKind.ANTIGRAVITY_SERVER) || isNativeClaude || (kind == ProviderKind.CHATGPT && codexSignedIn)),
            dshApi = effectiveDshApi,
            claudeAuthMode = storedAuthMode,
            claudeThinkingLevel = claudeThinking,
            profileId = if (useStoredValues && kind == ProviderKind.CUSTOM) {
                preferences.getString("${sourcePrefix}profile_id", "").orEmpty()
            } else {
                ""
            },
        )
    }

    fun hasRevokedProviderDrift(): Boolean {
        for ((key, value) in preferences.all) {
            val keyLower = key.lowercase(java.util.Locale.ROOT)
            val valStr = (value as? String).orEmpty().lowercase(java.util.Locale.ROOT)
            if (com.jarves.mh.provider.isRevokedProvider(keyLower) || com.jarves.mh.provider.isRevokedProvider(valStr)) return true
        }
        return false
    }

    fun hasAiqanaDrift(): Boolean = hasRevokedProviderDrift()

    fun purgeRevokedProviders() {
        val editor = preferences.edit()
        for ((key, value) in preferences.all) {
            val keyLower = key.lowercase(java.util.Locale.ROOT)
            val valStr = (value as? String).orEmpty().lowercase(java.util.Locale.ROOT)
            if (com.jarves.mh.provider.isRevokedProvider(keyLower)) {
                editor.remove(key)
            } else if (com.jarves.mh.provider.isRevokedProvider(valStr)) {
                if (key == "custom_providers_json") {
                    val raw = value as? String
                    val profiles = com.jarves.mh.provider.CustomProviderProfile.listFromJson(raw)
                    val cleaned = profiles.filterNot {
                        com.jarves.mh.provider.isRevokedProvider(it.name) ||
                        com.jarves.mh.provider.isRevokedProvider(it.baseUrl) ||
                        com.jarves.mh.provider.isRevokedProvider(it.model)
                    }
                    if (cleaned.isEmpty()) {
                        editor.remove(key)
                    } else {
                        editor.putString(key, com.jarves.mh.provider.CustomProviderProfile.listToJson(cleaned))
                    }
                } else if (key == "claude_model") {
                    editor.putString(key, "default")
                } else {
                    editor.remove(key)
                }
            }
        }
        editor.apply()
    }

    fun hasTokenHarborDrift(): Boolean {
        for ((key, value) in preferences.all) {
            val keyLower = key.lowercase(java.util.Locale.ROOT)
            val valStr = (value as? String).orEmpty().lowercase(java.util.Locale.ROOT)
            if ("tokenharbor" in keyLower || "tokenharbor" in valStr) return true
        }
        return false
    }

    fun purgeTokenHarbor() {
        val editor = preferences.edit()
        for ((key, value) in preferences.all) {
            val keyLower = key.lowercase(java.util.Locale.ROOT)
            val valStr = (value as? String).orEmpty().lowercase(java.util.Locale.ROOT)
            if ("tokenharbor" in keyLower) {
                editor.remove(key)
            } else if ("tokenharbor" in valStr) {
                if (key == "custom_providers_json") {
                    val raw = value as? String
                    val profiles = com.jarves.mh.provider.CustomProviderProfile.listFromJson(raw)
                    val cleaned = profiles.filterNot {
                        it.name.contains("tokenharbor", ignoreCase = true) ||
                        it.baseUrl.contains("tokenharbor", ignoreCase = true) ||
                        it.model.contains("tokenharbor", ignoreCase = true)
                    }
                    if (cleaned.isEmpty()) {
                        editor.remove(key)
                    } else {
                        editor.putString(key, com.jarves.mh.provider.CustomProviderProfile.listToJson(cleaned))
                    }
                } else if (key == "claude_model") {
                    editor.putString(key, "default")
                } else {
                    editor.remove(key)
                }
            }
        }
        editor.apply()
    }

    fun purgeAiqana() = purgeRevokedProviders()

    fun loadCustomProviders(): List<com.jarves.mh.provider.CustomProviderProfile> {
        val raw = preferences.getString("custom_providers_json", null)
        val profiles = com.jarves.mh.provider.CustomProviderProfile.listFromJson(raw)
        if (profiles.isEmpty()) return emptyList()
        val cleaned = profiles.filterNot { profile ->
            val isRevoked = com.jarves.mh.provider.isRevokedProvider(profile.name) ||
                com.jarves.mh.provider.isRevokedProvider(profile.baseUrl) ||
                com.jarves.mh.provider.isRevokedProvider(profile.model)
            if (isRevoked) return@filterNot true
            val hasTokenHarbor = profile.name.contains("tokenharbor", ignoreCase = true) ||
                profile.baseUrl.contains("tokenharbor", ignoreCase = true) ||
                profile.model.contains("tokenharbor", ignoreCase = true)
            hasTokenHarbor && (context == null || (!ApiKeyVault(context).contains(profile.secretId) && !ApiKeyVault(context).contains(ProviderKind.CUSTOM.name)))
        }
        if (cleaned.size != profiles.size) {
            saveCustomProviders(cleaned)
        }
        return cleaned
    }

    fun saveCustomProviders(profiles: List<com.jarves.mh.provider.CustomProviderProfile>) {
        preferences.edit().putString("custom_providers_json", com.jarves.mh.provider.CustomProviderProfile.listToJson(profiles)).apply()
    }

    private fun providerPrefix(agent: AgentKind): String = "provider_${agent.stableId.replace('-', '_')}_"

    fun saveProjects(projects: List<Project>) {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("description", p.description)
                put("language", p.language)
                put("slug", p.slug)
                put("rootPath", p.rootPath)
                put("updatedAtMillis", p.updatedAtMillis)
                put("kind", p.kind.name)
            })
        }
        preferences.edit().putString("projects_json", arr.toString()).apply()
    }

    fun loadProjects(): List<Project> {
        val raw = preferences.getString("projects_json", null) ?: return emptyList()
        var needsSave = false
        val list = runCatching {
            val arr = JSONArray(raw)
            val usedSlugs = mutableSetOf<String>()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
                if (!obj.has("kind") || storedKind == "QUICK_CHAT") needsSave = true
                val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
                var slug = requestedSlug
                if (!usedSlugs.add(slug)) {
                    slug = "$requestedSlug-${id.take(6)}"
                    var suffix = 2
                    while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
                }
                if (slug != obj.optString("slug")) needsSave = true
                var millis = obj.optLong("updatedAtMillis", 0L)
                if (millis <= 0L) {
                    val workspaceDir = context?.filesDir?.let { File(it, "workspaces/$id") }
                    millis = if (workspaceDir?.exists() == true && workspaceDir.lastModified() > 0L) {
                        workspaceDir.lastModified()
                    } else {
                        System.currentTimeMillis() - 3600_000L
                    }
                    needsSave = true
                }
                Project(
                    id = id,
                    name = name,
                    description = obj.optString("description", ""),
                    language = obj.optString("language", ""),
                    slug = slug,
                    rootPath = obj.optString("rootPath", "").takeIf { root ->
                        root.isBlank() || (!root.startsWith('/') && !root.contains(".."))
                    } ?: "",
                    updatedAtMillis = millis,
                    kind = when (storedKind) {
                        "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
                        else -> runCatching { ProjectKind.valueOf(storedKind) }
                            .getOrDefault(ProjectKind.PROJECT)
                    },
                )
            }
        }.getOrDefault(emptyList())

        if (needsSave && list.isNotEmpty()) {
            saveProjects(list)
        }
        return list
    }

    private val chatsDir = baseChatsDir ?: File(
        context?.filesDir ?: error("Context or baseChatsDir required"),
        "chats",
    ).also { it.mkdirs() }

    @Synchronized
    fun saveProjectChats(projectId: String, chats: List<ProjectChat>) {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        // Never replace an index that cannot be read: move it aside first so its chat list survives.
        if (index.isFile && readProjectChatIndex(index) == null) moveAsideUnreadableIndex(index)
        val arr = JSONArray()
        chats.forEach { chat ->
            arr.put(JSONObject().apply {
                put("id", chat.id)
                put("title", chat.title)
                put("createdAtMillis", chat.createdAtMillis)
                put("updatedAtMillis", chat.updatedAtMillis)
            })
        }
        writeReplacing(index, arr.toString())
    }

    @Synchronized
    fun loadProjectChats(projectId: String): List<ProjectChat> {
        val projectDir = File(chatsDir, projectId).also { it.mkdirs() }
        val index = File(projectDir, "index.json")
        if (index.exists()) {
            readProjectChatIndex(index)?.let { return it }
            // Keep the unreadable index for recovery; the next save writes a fresh one.
            moveAsideUnreadableIndex(index)
            return emptyList()
        }
        // A moved-aside index means its chat list was lost. Do not rebuild a "main" chat here, because that would
        // attach the old main.json messages to a new index.
        if (projectDir.listFiles().orEmpty().any { it.name.startsWith(MOVED_ASIDE_INDEX_PREFIX) }) return emptyList()

        // Migrate the original one-file-per-project conversation without losing it.
        val legacy = File(chatsDir, "$projectId.json")
        val legacyMessages = loadLegacyMessages(legacy)
        val now = System.currentTimeMillis()
        val chat = ProjectChat(
            id = "main",
            title = legacyMessages.firstOrNull { it.fromUser }?.text?.toChatTitle() ?: "Main chat",
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        saveProjectChats(projectId, listOf(chat))
        if (legacyMessages.isNotEmpty()) saveMessages(projectId, chat.id, legacyMessages)
        return listOf(chat)
    }

    /** The chats listed in [index], or null when the file cannot be read as a chat index. */
    private fun readProjectChatIndex(index: File): List<ProjectChat>? = runCatching {
        val arr = JSONArray(index.readText())
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            ProjectChat(
                id = obj.getString("id"),
                title = obj.optString("title", "Chat"),
                createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis()),
            )
        }.sortedByDescending { it.updatedAtMillis }
    }.getOrNull()

    /** Renames an unreadable index to `index.json.corrupt-<ms>` and logs it. Chat files are never touched. */
    private fun moveAsideUnreadableIndex(index: File) {
        val target = corruptBackupTarget(index)
        if (!index.renameTo(target)) throw IOException("Could not move unreadable ${index.name} aside")
        logChatFileProblem(index, "unreadable chat index moved aside as ${target.name}")
    }

    /** A `<name>.corrupt-<ms>` path beside [file] that no existing file uses. */
    private fun corruptBackupTarget(file: File): File {
        val stamp = System.currentTimeMillis()
        var target = File(file.parentFile, "${file.name}.corrupt-$stamp")
        var attempt = 1
        while (target.exists()) target = File(file.parentFile, "${file.name}.corrupt-$stamp-${attempt++}")
        return target
    }

    /** Writes [content] to a temp file beside [destination], then renames it over [destination]. */
    private fun writeReplacing(destination: File, content: String) {
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        temporary.writeText(content)
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }

    /**
     * Saves a chat's messages from [startIndex] on. Messages before [startIndex] stay as they are on disk, so a caller
     * holding only the newest messages never drops the older ones. [startIndex] must be a page boundary within the
     * saved chat; 0 replaces the whole chat. Only pages whose messages changed are rewritten.
     */
    @Synchronized
    fun saveMessages(projectId: String, chatId: String, messages: List<ChatMessage>, startIndex: Int = 0) {
        val (repairedMessages, _) = repairDuplicateMessageIds(messages)
        val dir = chatPagesDir(projectId, chatId)
        val legacy = legacyChatFile(projectId, chatId)
        val committed = readChatPagesMeta(dir) ?: if (legacy.exists()) null else recoverChatPagesMeta(dir)
        val pageSize = committed?.pageSize ?: CHAT_PAGE_SIZE
        if (startIndex < 0 || startIndex % pageSize != 0 || startIndex > (committed?.count ?: 0)) {
            logChatFileProblem(dir, "refused a save from message $startIndex; it would leave a gap")
            throw IllegalArgumentException("Chat save must start on a saved page boundary, not at $startIndex")
        }
        // Before the first paged save, the single-file chat is still the source: keep a copy if it is damaged.
        val supersedesLegacy = committed == null && legacy.exists()
        if (supersedesLegacy) backupUnverifiedChatFile(legacy)
        writeChatPages(dir, repairedMessages, startIndex, pageSize)
        if (supersedesLegacy) {
            verifiedChatFiles.remove(legacy.path)
            if (!legacy.delete()) logChatFileProblem(legacy, "replaced by pages; the old file could not be removed")
        }
    }

    /**
     * The chat's newest messages: whole pages from the end until at least [minMessages] are loaded, or the chat's
     * start is reached. A chat in the old single-file format is moved to pages first.
     */
    @Synchronized
    fun loadMessageWindow(projectId: String, chatId: String, minMessages: Int): ChatMessageWindow =
        when (val source = openChatSource(projectId, chatId)) {
            is ChatSource.Whole -> ChatMessageWindow(0, source.messages)
            is ChatSource.Pages -> readPagesBackward(chatPagesDir(projectId, chatId), source.meta, source.meta.count, minMessages).window
        }

    /** Whole pages before [beforeIndex], newest first, until at least [minMessages] are loaded or the chat's start is reached. */
    @Synchronized
    fun loadOlderMessages(projectId: String, chatId: String, beforeIndex: Int, minMessages: Int): ChatMessageWindow {
        val meta = (openChatSource(projectId, chatId) as? ChatSource.Pages)?.meta
            ?: return ChatMessageWindow(beforeIndex, emptyList())
        if (beforeIndex <= 0 || beforeIndex % meta.pageSize != 0 || beforeIndex > meta.count) {
            return ChatMessageWindow(beforeIndex, emptyList())
        }
        return readPagesBackward(chatPagesDir(projectId, chatId), meta, beforeIndex, minMessages).window
    }

    /**
     * Messages before [endIndex] for a prompt's conversation history, without reading the whole chat: the newest pages
     * until their text covers [budgetChars], plus the first page, which holds the conversation's opening goal. Pages in
     * between would not fit the history budget.
     */
    @Synchronized
    fun loadPromptHistory(
        projectId: String,
        chatId: String,
        endIndex: Int,
        budgetChars: Int = PromptContextSupport.HISTORY_MAX_CHARS,
    ): List<ChatMessage> {
        val meta = when (val source = openChatSource(projectId, chatId)) {
            is ChatSource.Whole -> return source.messages.take(endIndex)
            is ChatSource.Pages -> source.meta
        }
        val dir = chatPagesDir(projectId, chatId)
        val end = endIndex.coerceAtMost(meta.count)
        val pages = ArrayDeque<List<ChatMessage>>()
        var chars = 0
        var page = (end - 1) / meta.pageSize
        while (end > 0 && page >= 0 && chars < budgetChars) {
            val messages = readChatPage(dir, page, meta).messages.take(end - page * meta.pageSize)
            pages.addFirst(messages)
            chars += messages.sumOf { message ->
                if (PromptContextSupport.isHistoryNoise(message)) 0 else minOf(message.text.length, PromptContextSupport.HISTORY_MAX_MESSAGE_CHARS)
            }
            page--
        }
        if (page >= 0) pages.addFirst(readChatPage(dir, 0, meta).messages)
        return pages.flatten()
    }

    /** Where a chat's messages are: committed pages, or a whole list read from the old single-file format. */
    private sealed interface ChatSource {
        class Pages(val meta: ChatPagesMeta) : ChatSource
        class Whole(val messages: List<ChatMessage>) : ChatSource
    }

    /** A chat's page size and message count, from its `meta.json`. */
    private class ChatPagesMeta(val pageSize: Int, val count: Int)

    private class PagesRead(val window: ChatMessageWindow, val clean: Boolean)

    private class PageRead(val messages: List<ChatMessage>, val clean: Boolean)

    private fun chatPagesDir(projectId: String, chatId: String): File = File(File(chatsDir, projectId), "$chatId$CHAT_PAGES_SUFFIX")

    /** The single-file chat format used before pages. Still read, and moved to pages on first load. */
    private fun legacyChatFile(projectId: String, chatId: String): File = File(File(chatsDir, projectId), "$chatId.json")

    private fun chatPageFile(dir: File, page: Int): File = File(dir, String.format(java.util.Locale.ROOT, "%08d.json", page))

    private fun readChatPagesMeta(dir: File): ChatPagesMeta? = runCatching {
        val obj = JSONObject(File(dir, CHAT_PAGES_META).readText())
        val pageSize = obj.getInt("pageSize")
        val count = obj.getInt("count")
        require(pageSize > 0 && count >= 0)
        ChatPagesMeta(pageSize, count)
    }.getOrNull()

    /** The chat as pages, moving an old single-file chat to pages first. */
    private fun openChatSource(projectId: String, chatId: String): ChatSource {
        val dir = chatPagesDir(projectId, chatId)
        readChatPagesMeta(dir)?.let { return ChatSource.Pages(it) }
        val legacy = legacyChatFile(projectId, chatId)
        if (legacy.exists()) return migrateLegacyChat(legacy, dir)
        return ChatSource.Pages(recoverChatPagesMeta(dir))
    }

    /**
     * Moves a single-file chat to pages. A file that is not a chat list is left exactly as it is; the first save backs
     * it up and replaces it. A file with unreadable entries is copied aside first. If the pages cannot be written, the
     * messages are served from the old file, which stays the source.
     */
    private fun migrateLegacyChat(legacy: File, dir: File): ChatSource {
        val decoded = decodeChatFile(legacy)
        if (decoded.unreadable) {
            verifiedChatFiles.remove(legacy.path)
            return ChatSource.Whole(emptyList())
        }
        val messages = repairDuplicateMessageIds(decoded.messages).first
        val migrated = runCatching {
            if (!decoded.isClean) legacy.copyTo(corruptBackupTarget(legacy), overwrite = true)
            writeChatPages(dir, messages, startIndex = 0, pageSize = CHAT_PAGE_SIZE)
        }
        if (migrated.isFailure) {
            logChatFileProblem(legacy, "could not move to pages: ${migrated.exceptionOrNull()?.javaClass?.simpleName}")
            return ChatSource.Whole(messages)
        }
        // The pages and their meta are committed. If the delete fails, the meta still wins on the next load.
        verifiedChatFiles.remove(legacy.path)
        if (!legacy.delete()) logChatFileProblem(legacy, "moved to pages; the old file could not be removed")
        return ChatSource.Pages(ChatPagesMeta(CHAT_PAGE_SIZE, messages.size))
    }

    /** A pages folder without a readable meta: a first save stopped before its meta. The pages from 0 that exist are the chat. */
    private fun recoverChatPagesMeta(dir: File): ChatPagesMeta {
        var pages = 0
        while (chatPageFile(dir, pages).isFile) pages++
        if (pages == 0) return ChatPagesMeta(CHAT_PAGE_SIZE, 0)
        val last = decodeChatFile(chatPageFile(dir, pages - 1)).messages.size
        logChatFileProblem(dir, "no readable meta; using $pages page(s) found on disk")
        return ChatPagesMeta(CHAT_PAGE_SIZE, (pages - 1) * CHAT_PAGE_SIZE + last)
    }

    /** Reads whole pages backwards from the page holding message [endIndex] - 1 until [minMessages] are loaded. */
    private fun readPagesBackward(dir: File, meta: ChatPagesMeta, endIndex: Int, minMessages: Int): PagesRead {
        val pages = ArrayDeque<List<ChatMessage>>()
        var loaded = 0
        var clean = true
        var page = (endIndex - 1) / meta.pageSize
        while (endIndex > 0 && page >= 0 && loaded < minMessages) {
            val read = readChatPage(dir, page, meta)
            pages.addFirst(read.messages)
            loaded += read.messages.size
            clean = clean && read.clean
            page--
        }
        val start = if (endIndex > 0) (page + 1) * meta.pageSize else 0
        return PagesRead(ChatMessageWindow(start, pages.flatten()), clean)
    }

    /** One page, cut to the chat's count. A damaged page gives the entries it can, like a chat file. */
    private fun readChatPage(dir: File, page: Int, meta: ChatPagesMeta): PageRead {
        val file = chatPageFile(dir, page)
        val stamp = chatFileStamp(file)
        val decoded = decodeChatFile(file)
        val messages = decoded.messages.take((meta.count - page * meta.pageSize).coerceIn(0, meta.pageSize))
        if (decoded.isClean) {
            verifiedChatFiles[file.path] = stamp
            rememberPage(dir, page, messages)
        } else {
            // Damaged: stop trusting it, so a later save backs it up before it is overwritten.
            verifiedChatFiles.remove(file.path)
        }
        return PageRead(messages, decoded.isClean)
    }

    /**
     * Writes [messages] as the pages from [startIndex] on, then the meta, then removes pages past the new end. Each file
     * is replaced by rename, and the meta goes last, so a crash leaves the previous count and readers ignore the rest.
     */
    private fun writeChatPages(dir: File, messages: List<ChatMessage>, startIndex: Int, pageSize: Int) {
        dir.mkdirs()
        val firstPage = startIndex / pageSize
        messages.chunked(pageSize).forEachIndexed { offset, chunk -> writeChatPage(dir, firstPage + offset, chunk) }
        val count = startIndex + messages.size
        writeReplacing(
            File(dir, CHAT_PAGES_META),
            JSONObject().put("format", CHAT_PAGES_FORMAT).put("pageSize", pageSize).put("count", count).toString(),
        )
        // Pages past the new end hold a longer earlier version, or a save that stopped before its meta.
        val pageCount = (count + pageSize - 1) / pageSize
        dir.listFiles().orEmpty().forEach { file ->
            val page = CHAT_PAGE_FILE.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
            if (page >= pageCount) {
                verifiedChatFiles.remove(file.path)
                if (snapshotDir == dir.path) pageSnapshots.remove(page)
                file.delete()
            }
        }
    }

    /** Rewrites one page unless it still holds exactly [messages] as this instance last read or wrote it. */
    private fun writeChatPage(dir: File, page: Int, messages: List<ChatMessage>) {
        val file = chatPageFile(dir, page)
        val unchanged = snapshotDir == dir.path && pageSnapshots[page] == messages &&
            verifiedChatFiles[file.path] == chatFileStamp(file)
        if (unchanged) return
        backupUnverifiedChatFile(file)
        verifiedChatFiles.remove(file.path)
        writeReplacing(file, encodeChatMessages(messages))
        verifiedChatFiles[file.path] = chatFileStamp(file)
        rememberPage(dir, page, messages)
    }

    /** Messages this instance last read from or wrote to each page of one chat, so a save rewrites only changed pages. */
    private var snapshotDir: String? = null
    private val pageSnapshots = HashMap<Int, List<ChatMessage>>()

    private fun rememberPage(dir: File, page: Int, messages: List<ChatMessage>) {
        if (snapshotDir != dir.path) {
            snapshotDir = dir.path
            pageSnapshots.clear()
        }
        pageSnapshots[page] = messages
    }

    private fun encodeChatMessages(messages: List<ChatMessage>): String {
        val arr = JSONArray()
        messages.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("attachments", JSONArray().apply {
                    m.attachments.forEach { attachment ->
                        put(JSONObject().apply {
                            put("id", attachment.id)
                            put("displayName", attachment.displayName)
                            put("relativePath", attachment.relativePath)
                            put("mimeType", attachment.mimeType)
                            put("sizeBytes", attachment.sizeBytes)
                        })
                    }
                })
                put("workedMillis", m.workedMillis)
                if (m.activeSkill != null) {
                    put("activeSkill", m.activeSkill)
                }
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
            })
        }
        return arr.toString()
    }

    /** Copies a chat file that is not verified as it is now, and fails verification, to `<name>.corrupt-<ts>` before it is overwritten. */
    private fun backupUnverifiedChatFile(file: File) {
        if (!file.isFile) return
        val stamp = chatFileStamp(file)
        if (verifiedChatFiles[file.path] == stamp) return
        if (decodeChatFile(file).isClean) {
            verifiedChatFiles[file.path] = stamp
            return
        }
        file.copyTo(corruptBackupTarget(file), overwrite = true)
    }

    /** Size and modification time of [file], compared with the stamp recorded when the file was last verified. */
    private fun chatFileStamp(file: File): Pair<Long, Long> = file.length() to file.lastModified()

    /** The whole chat. Opening a chat uses [loadMessageWindow]; this is for views that show every message at once. */
    @Synchronized
    fun loadMessages(projectId: String, chatId: String): List<ChatMessage> {
        val read = when (val source = openChatSource(projectId, chatId)) {
            is ChatSource.Whole -> return source.messages
            is ChatSource.Pages -> readPagesBackward(chatPagesDir(projectId, chatId), source.meta, source.meta.count, Int.MAX_VALUE)
        }
        val (repaired, wasRepaired) = repairDuplicateMessageIds(read.window.messages)
        // A chat with unreadable parts is not rewritten here; the next save keeps a backup of each damaged page first.
        if (wasRepaired && read.clean) {
            runCatching {
                saveMessages(projectId, chatId, repaired)
            }
        }
        return repaired
    }

    @Synchronized
    fun deleteProjectChats(projectId: String) {
        val projectDir = File(chatsDir, projectId)
        verifiedChatFiles.keys.removeAll { it.startsWith(projectDir.path + File.separator) }
        if (snapshotDir?.startsWith(projectDir.path + File.separator) == true) {
            snapshotDir = null
            pageSnapshots.clear()
        }
        projectDir.deleteRecursively()
        File(chatsDir, "$projectId.json").delete()
    }

    /**
     * True only when every chat in the project's chat folder, and any legacy chat file, is empty.
     * Reads the files on disk, not the index, so a crash between a message write and an index update cannot make a chat look empty.
     * Returns false when a folder cannot be listed, so the project is kept.
     */
    fun hasNoChatMessages(projectId: String): Boolean {
        val projectDir = File(chatsDir, projectId)
        val listed = projectDir.listFiles()
        if (projectDir.exists() && listed == null) return false
        val legacy = File(chatsDir, "$projectId.json")
        val candidates = listed.orEmpty().filter { it.name != "index.json" } + listOf(legacy).filter { it.exists() }
        return candidates.all { if (it.isDirectory) isEmptyChatPages(it) else isEmptyChatFile(it) }
    }

    /** An empty chat list is written as exactly "[]", so any other size or content counts as messages or as unknown. */
    private fun isEmptyChatFile(file: File): Boolean =
        file.isFile && file.length() == 2L && runCatching { file.readText() == "[]" }.getOrDefault(false)

    /** A pages folder is empty only when it holds nothing but a meta that counts no messages. */
    private fun isEmptyChatPages(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.all { it.name == CHAT_PAGES_META } && readChatPagesMeta(dir)?.count == 0
    }

    private fun loadLegacyMessages(file: File): List<ChatMessage> {
        val raw = readRawLegacyMessages(file)
        return repairDuplicateMessageIds(raw).first
    }

    /** What a chat file yielded: the readable messages, how many entries were skipped, and whether the file was not a JSON array. */
    internal class ChatFileDecode(val messages: List<ChatMessage>, val skippedEntries: Int, val unreadable: Boolean) {
        val isClean: Boolean get() = !unreadable && skippedEntries == 0
    }

    /** Decodes entry by entry, so one malformed message skips only itself. */
    internal fun decodeChatFile(file: File): ChatFileDecode {
        if (!file.exists()) return ChatFileDecode(emptyList(), skippedEntries = 0, unreadable = false)
        val arr = runCatching { JSONArray(file.readText()) }.getOrNull() ?: run {
            logChatFileProblem(file, "not a readable JSON array")
            return ChatFileDecode(emptyList(), skippedEntries = 0, unreadable = true)
        }
        var skipped = 0
        val messages = (0 until arr.length()).mapNotNull { i ->
            runCatching { decodeChatMessage(arr.getJSONObject(i)) }.getOrElse {
                skipped++
                null
            }
        }
        if (skipped > 0) logChatFileProblem(file, "skipped $skipped unreadable message(s)")
        return ChatFileDecode(messages, skippedEntries = skipped, unreadable = false)
    }

    internal fun readRawLegacyMessages(file: File): List<ChatMessage> = decodeChatFile(file).messages

    private fun decodeChatMessage(obj: JSONObject): ChatMessage = ChatMessage(
        id = obj.getString("id"),
        fromUser = obj.getBoolean("fromUser"),
        text = obj.getString("text"),
        createdAt = runCatching { Instant.parse(obj.getString("createdAt")) }
            .getOrDefault(Instant.now()),
        attachments = obj.optJSONArray("attachments")?.let { attachments ->
            (0 until attachments.length()).mapNotNull { index ->
                runCatching {
                    attachments.getJSONObject(index).let { attachment ->
                        ChatAttachment(
                            id = attachment.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            displayName = attachment.getString("displayName"),
                            relativePath = attachment.getString("relativePath"),
                            mimeType = attachment.optString("mimeType", "application/octet-stream"),
                            sizeBytes = attachment.optLong("sizeBytes", 0L),
                        )
                    }
                }.getOrNull()
            }
        }.orEmpty(),
        workedMillis = obj.optLong("workedMillis", 0L),
        workItems = obj.optJSONArray("workItems")?.let { workItems ->
            (0 until workItems.length()).mapNotNull { index ->
                runCatching {
                    workItems.getJSONObject(index).let { item ->
                        com.jarves.mh.model.ActivityItem(
                            title = item.optString("title"),
                            detail = item.optString("detail"),
                            isComplete = item.optBoolean("isComplete", true),
                            isCommand = item.optBoolean("isCommand", false),
                        )
                    }
                }.getOrNull()
            }
        }.orEmpty(),
        activeSkill = obj.optString("activeSkill").takeIf { it.isNotBlank() },
    )

    private fun logChatFileProblem(file: File, problem: String) {
        // android.util.Log is stubbed in JVM unit tests; recovery does not depend on the log line.
        runCatching { Log.w(CHAT_STORE_TAG, "${file.name}: $problem") }
    }

    fun saveProjectCustomizationConfig(config: ProjectCustomizationConfig) {
        val key = "customization_config_${config.projectId}"
        val json = JSONObject().apply {
            put("projectId", config.projectId)
            put("scopeMode", config.scopeMode.name)
            put("enabledRuleIds", JSONArray(config.enabledRuleIds))
            put("disabledRuleIds", JSONArray(config.disabledRuleIds))
            put("enabledSkillIds", JSONArray(config.enabledSkillIds))
            put("disabledSkillIds", JSONArray(config.disabledSkillIds))
            val linksArr = JSONArray()
            config.linkedSkills.forEach { ref ->
                linksArr.put(JSONObject().apply {
                    put("id", ref.id)
                    put("sourceProjectId", ref.sourceProjectId)
                    put("sourceProjectName", ref.sourceProjectName)
                    put("skillName", ref.skillName)
                    put("relativeSkillPath", ref.relativeSkillPath)
                    put("enabledAtMillis", ref.enabledAtMillis)
                })
            }
            put("linkedSkills", linksArr)
        }
        preferences.edit().putString(key, json.toString()).apply()
    }

    fun loadProjectCustomizationConfig(projectId: String): ProjectCustomizationConfig {
        val key = "customization_config_$projectId"
        val raw = preferences.getString(key, null)
            ?: preferences.getString("skill_config_$projectId", null)
            ?: return ProjectCustomizationConfig(projectId)
        return runCatching {
            val obj = JSONObject(raw)
            val scopeMode = runCatching {
                CustomizationScopeMode.valueOf(obj.optString("scopeMode", CustomizationScopeMode.INHERIT_AND_MERGE.name))
            }.getOrDefault(CustomizationScopeMode.INHERIT_AND_MERGE)

            val enabledRules = mutableSetOf<String>()
            obj.optJSONArray("enabledRuleIds")?.let { arr ->
                for (i in 0 until arr.length()) enabledRules.add(arr.getString(i))
            }

            val disabledRules = mutableSetOf<String>()
            obj.optJSONArray("disabledRuleIds")?.let { arr ->
                for (i in 0 until arr.length()) disabledRules.add(arr.getString(i))
            }

            val enabledSkills = mutableSetOf<String>()
            obj.optJSONArray("enabledSkillIds")?.let { arr ->
                for (i in 0 until arr.length()) enabledSkills.add(arr.getString(i))
            }

            val disabledSkills = mutableSetOf<String>()
            obj.optJSONArray("disabledSkillIds")?.let { arr ->
                for (i in 0 until arr.length()) disabledSkills.add(arr.getString(i))
            }

            val links = mutableListOf<LinkedSkillReference>()
            obj.optJSONArray("linkedSkills")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    links.add(
                        LinkedSkillReference(
                            id = item.optString("id", java.util.UUID.randomUUID().toString()),
                            sourceProjectId = item.getString("sourceProjectId"),
                            sourceProjectName = item.optString("sourceProjectName", "Other Project"),
                            skillName = item.getString("skillName"),
                            relativeSkillPath = item.getString("relativeSkillPath"),
                            enabledAtMillis = item.optLong("enabledAtMillis", System.currentTimeMillis()),
                        )
                    )
                }
            }
            ProjectCustomizationConfig(
                projectId = projectId,
                scopeMode = scopeMode,
                enabledRuleIds = enabledRules,
                disabledRuleIds = disabledRules,
                enabledSkillIds = enabledSkills,
                disabledSkillIds = disabledSkills,
                linkedSkills = links,
            )
        }.getOrDefault(ProjectCustomizationConfig(projectId))
    }

    private fun String.toChatTitle(): String {
        val clean = replace(Regex("\\s+"), " ").trim()
        return if (clean.length <= 42) clean else clean.take(39).trimEnd() + "…"
    }

    companion object {
        private const val CHAT_STORE_TAG = "ChatStore"
        private const val MOVED_ASIDE_INDEX_PREFIX = "index.json.corrupt-"

        /** Messages per page file. Opening a chat reads whole pages from the end. */
        internal const val CHAT_PAGE_SIZE = 15
        private const val CHAT_PAGES_SUFFIX = ".pages"
        private const val CHAT_PAGES_META = "meta.json"
        private const val CHAT_PAGES_FORMAT = 2
        private val CHAT_PAGE_FILE = Regex("(\\d{8})\\.json")

        /**
         * Chat files this process read or wrote cleanly, with the size and modification time they had then. A save trusts
         * an entry only while the file still has that stamp; a changed, damaged or deleted file is verified again.
         */
        private val verifiedChatFiles: MutableMap<String, Pair<Long, Long>> = ConcurrentHashMap()

        fun repairDuplicateMessageIds(messages: List<ChatMessage>): Pair<List<ChatMessage>, Boolean> {
            if (messages.isEmpty()) return messages to false
            val seen = mutableSetOf<String>()
            var modified = false
            val result = ArrayList<ChatMessage>(messages.size)
            for ((index, msg) in messages.withIndex()) {
                val baseId = msg.id.ifBlank { "msg-$index" }
                val finalId = if (seen.add(baseId)) {
                    if (baseId != msg.id) {
                        modified = true
                        baseId
                    } else {
                        msg.id
                    }
                } else {
                    modified = true
                    var counter = 1
                    var candidate = "$baseId-$counter"
                    while (!seen.add(candidate)) {
                        counter++
                        candidate = "$baseId-$counter"
                    }
                    candidate
                }
                result.add(if (finalId == msg.id) msg else msg.copy(id = finalId))
            }
            return result to modified
        }
    }
}
