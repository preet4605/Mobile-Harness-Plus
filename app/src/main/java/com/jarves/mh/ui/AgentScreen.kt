package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import com.jarves.mh.model.ClaudeAuthMode
import com.jarves.mh.model.DSH_PROTOCOL_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.LargeTitle
import com.jarves.mh.ui.kit.LargeTitleScaffold
import com.jarves.mh.ui.kit.LazyGroupRow
import com.jarves.mh.ui.kit.ListIconTile
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketIconButton
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SearchField
import com.jarves.mh.ui.kit.SectionHeader
import com.jarves.mh.ui.kit.SegmentedControl
import com.jarves.mh.ui.kit.SheetDetent
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.ToggleRow
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberBanner
import com.jarves.mh.ui.kit.rememberOverlayAnchor
import com.jarves.mh.ui.kit.rememberTitleCollapse
import com.jarves.mh.ui.theme.ContinuousRoundedShape
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import kotlinx.coroutines.launch

private data class KeyConnectionStatus(
    val message: String,
    val successful: Boolean? = null,
    val providerMessage: String? = null,
    val label: String = if (successful == true) "Verified" else "Failed",
)

/** Formats Antigravity model identifiers into clean, human-friendly names. */
internal fun formatAntigravityModelName(id: String): String = when (id) {
    "gemini-3.8-flash-high" -> "Gemini 3.8 Flash (High)"
    "gemini-3.8-flash-medium" -> "Gemini 3.8 Flash"
    "gemini-3.8-flash-low" -> "Gemini 3.8 Flash (Low)"
    "gemini-3.6-flash-high" -> "Gemini 3.6 Flash (High)"
    "gemini-3.6-flash-medium" -> "Gemini 3.6 Flash"
    "gemini-3.6-flash-low" -> "Gemini 3.6 Flash (Low)"
    "gemini-3.1-pro-high" -> "Gemini 3.1 Pro (High)"
    "gemini-3.1-pro-low" -> "Gemini 3.1 Pro (Low)"
    "claude-sonnet-4-6" -> "Claude 3.7 Sonnet"
    "claude-opus-4-6-thinking" -> "Claude 3.7 Opus (Thinking)"
    "gpt-oss-120b-medium" -> "GPT-OSS 120B"
    else -> id.split("-").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
    }
}

/** Returns tier classification badges for Antigravity models. */
internal fun formatAntigravityModelTier(id: String): String = when {
    id.contains("3.8") -> "Recommended"
    id.contains("3.6") -> "Stable"
    id.contains("3.1-pro") -> "Pro Reasoning"
    id.contains("claude") -> "Anthropic"
    id.contains("gpt") -> "Open Source"
    else -> ""
}

/** Short names for the engine switcher. */
private fun AgentKind.shortName(): String = when (this) {
    AgentKind.ANTIGRAVITY -> "Antigravity"
    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek"
    AgentKind.CLAUDE_CODE -> "Claude Code"
    AgentKind.CODEX -> "Codex"
}

private val Efforts = listOf("low", "medium", "high")

private fun effortTitle(effort: String): String =
    effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }

private fun effortCaption(effort: String): String = when (effort) {
    "low" -> "Fast responses with light reasoning."
    "high" -> "The deepest reasoning; slower responses."
    else -> "Balanced speed and reasoning."
}

/** Which sheet the Agent screen shows. */
private enum class AgentSheet { None, Model, Provider, AddKey }

/**
 * Agent: the engine switcher (Claude Code, DeepSeek, Antigravity) over grouped sections for the
 * selected engine: its account, provider and endpoint, model (one sheet with the thinking
 * level), credentials and connection test, then agent updates.
 */
@Composable
fun AgentScreen(
    state: AppUiState,
    bottomBarPadding: Dp = 0.dp,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onPing: () -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onSelectAgent: (AgentKind) -> Unit = {},
    onInstallAgent: (AgentKind) -> Unit = {},
    onCheckAgentUpdates: () -> Unit = {},
    onUpdateAgent: (AgentKind) -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onLogoutAntigravity: () -> Unit = {},
    onRemoveAntigravityAccount: (String) -> Unit = {},
    onSetAntigravityPrimaryAccount: (String) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onToggleAntigravityAccountEnabled: (String, Boolean) -> Unit = { _, _ -> },
    onSetAntigravityLoadBalancingStrategy: (AntigravityLoadBalancingStrategy) -> Unit = {},
    onSetAntigravityFailoverEnabled: (Boolean) -> Unit = {},
    onRefreshAntigravityModels: () -> Unit = {},
    onSetAntigravityModel: (String) -> Unit = {},
    onSetAntigravityEffort: (String) -> Unit = {},
    onStartClaudeLogin: () -> Unit = {},
    onCancelClaudeLogin: () -> Unit = {},
    onSubmitClaudeCode: (String) -> Unit = {},
    onLogoutClaude: () -> Unit = {},
    onRefreshClaudeAuth: () -> Unit = {},
    onStartCodexLogin: () -> Unit = {},
    onCancelCodexLogin: () -> Unit = {},
    onLogoutCodex: () -> Unit = {},
    onRefreshCodexAuth: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
) {
    val scope = rememberCoroutineScope()
    val colors = PocketColors.current
    var selectedKind by rememberSaveable(state.provider.kind) { mutableStateOf(state.provider.kind) }
    var baseUrl by rememberSaveable(state.provider.baseUrl) { mutableStateOf(state.provider.baseUrl) }
    var model by rememberSaveable(state.provider.model) { mutableStateOf(state.provider.model) }
    var dshApi by rememberSaveable(state.provider.dshApi) { mutableStateOf(state.provider.dshApi) }
    var apiKey by rememberSaveable(selectedKind) { mutableStateOf(getSavedApiKey(selectedKind)) }
    var savedKeys by remember(selectedKind, state.activeApiKeyName) {
        mutableStateOf(getSavedApiKeys(selectedKind))
    }
    var newKeyName by rememberSaveable(selectedKind) { mutableStateOf("") }
    var newApiKey by rememberSaveable(selectedKind) { mutableStateOf("") }
    var models by remember(selectedKind, baseUrl) {
        mutableStateOf(if (selectedKind == ProviderKind.ANTIGRAVITY_SERVER) defaultModelsForProvider(selectedKind) else emptyList())
    }
    var modelSearch by rememberSaveable(selectedKind) { mutableStateOf("") }
    var sheet by rememberSaveable { mutableStateOf(AgentSheet.None) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var statusProviderMessage by remember { mutableStateOf<String?>(null) }
    var keyConnectionStatuses by remember(selectedKind) {
        mutableStateOf<Map<String, KeyConnectionStatus>>(emptyMap())
    }
    var keyPendingRemoval by remember { mutableStateOf<ApiKeyInfo?>(null) }
    var antigravitySearch by rememberSaveable { mutableStateOf("") }
    var antigravityCode by rememberSaveable { mutableStateOf("") }
    var viewedAgent by rememberSaveable { mutableStateOf(state.agentKind) }

    val orderedAgents = remember(state.primaryAgentKind) {
        listOf(state.primaryAgentKind) + AgentKind.entries.filterNot { it == state.primaryAgentKind }
    }
    // Codex is checked even while selected: an install from before its helper shipped needs a repair.
    val viewedAgentInstalled = (viewedAgent == state.agentKind && viewedAgent != AgentKind.CODEX) ||
        state.installedAgentVersions.containsKey(viewedAgent)
    val isAntigravity = state.agentKind == AgentKind.ANTIGRAVITY

    val filteredModels = remember(models, modelSearch) {
        val q = modelSearch.trim()
        if (q.isBlank()) models else models.filter {
            it.id.contains(q, true) || it.displayName.contains(q, true)
        }
    }

    val antigravityModelList = remember(state.antigravityModels) {
        state.antigravityModels.ifEmpty {
            listOf(
                "gemini-3.8-flash-high",
                "gemini-3.8-flash-medium",
                "gemini-3.6-flash-high",
                "gemini-3.6-flash-medium",
                "gemini-3.6-flash-low",
                "gemini-3.1-pro-high",
                "gemini-3.1-pro-low",
                "claude-sonnet-4-6",
                "claude-opus-4-6-thinking",
                "gpt-oss-120b-medium",
            )
        }
    }

    val filteredAntigravityModels = remember(antigravityModelList, antigravitySearch) {
        val q = antigravitySearch.trim()
        if (q.isBlank()) antigravityModelList
        else antigravityModelList.filter {
            it.contains(q, true) || formatAntigravityModelName(it).contains(q, true)
        }
    }

    fun discoverModels() {
        if (selectedKind == ProviderKind.CHATGPT) {
            status = "Model lists aren't available with ChatGPT sign-in. Enter a model ID, or leave it blank for Codex's default."
            statusOk = false
            statusProviderMessage = null
            return
        }
        val isAntigravityServer = selectedKind == ProviderKind.ANTIGRAVITY_SERVER
        val effectiveKey = apiKey.trim().ifBlank { newApiKey.trim() }
        val supportsPublicDiscovery = selectedKind == ProviderKind.LLM_ROUTER ||
            selectedKind == ProviderKind.OPENCODE_ZEN ||
            isAntigravityServer
        if (effectiveKey.isBlank() && !supportsPublicDiscovery) {
            status = "Add an API key first to discover models."
            statusOk = false
            statusProviderMessage = null
            sheet = AgentSheet.AddKey
            return
        }
        scope.launch {
            isDiscovering = true
            status = "Discovering models from ${selectedKind.title}…"
            statusOk = true
            statusProviderMessage = null
            val kind = selectedKind
            val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim()
            val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi)
            when (val result = onDiscoverModels(profile, effectiveKey)) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    if (apiKey.isBlank() && newApiKey.isNotBlank() && !isAntigravityServer) {
                        val keyName = newKeyName.trim().ifBlank { "${kind.title} Key" }
                        savedKeys = onAddApiKey(kind, keyName, newApiKey.trim())
                        apiKey = getSavedApiKey(kind)
                        newKeyName = ""
                        newApiKey = ""
                    }
                    status = "Discovered ${result.models.size} models from ${selectedKind.title}."
                    statusOk = true
                    statusProviderMessage = null
                    sheet = AgentSheet.Model
                }
                is ModelDiscoveryResult.Failure -> {
                    status = result.message
                    statusOk = false
                    statusProviderMessage = result.providerMessage
                }
            }
            isDiscovering = false
        }
    }

    LaunchedEffect(selectedKind) {
        if (selectedKind == ProviderKind.ANTIGRAVITY_SERVER) {
            val url = selectedKind.defaultBaseUrl
            val profile = ProviderProfile(selectedKind, url, model.trim(), dshApi = dshApi)
            when (val result = onDiscoverModels(profile, "")) {
                is ModelDiscoveryResult.Success -> models = result.models
                else -> {}
            }
        }
    }

    val onProvider: (ProviderKind) -> Unit = { kind ->
        selectedKind = kind
        baseUrl = kind.defaultBaseUrl
        model = kind.defaultModel
        dshApi = defaultDshApiForProvider(kind)
        models = defaultModelsForProvider(kind)
        modelSearch = ""
        newKeyName = ""
        newApiKey = ""
        status = null
        statusProviderMessage = null
        // The ChatGPT account has nothing to test or save by hand; selecting it is the whole choice.
        if (kind == ProviderKind.CHATGPT) onSaveProvider(ProviderProfile(kind, "", ""), "")
        if (kind == ProviderKind.ANTIGRAVITY_SERVER) {
            scope.launch {
                val profile = ProviderProfile(kind, kind.defaultBaseUrl, kind.defaultModel, dshApi = defaultDshApiForProvider(kind))
                when (val result = onDiscoverModels(profile, "")) {
                    is ModelDiscoveryResult.Success -> models = result.models
                    else -> {}
                }
            }
        }
    }
    val onBaseUrl: (String) -> Unit = {
        baseUrl = it
        if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind == ProviderKind.CUSTOM) {
            dshApi = inferredDshApiForUrl(it)
        }
        models = emptyList()
        status = null
        statusProviderMessage = null
        keyConnectionStatuses = emptyMap()
    }
    val onDshApi: (String) -> Unit = {
        dshApi = it
        status = null
        statusProviderMessage = null
        keyConnectionStatuses = emptyMap()
    }
    val onAddKey = {
        savedKeys = onAddApiKey(selectedKind, newKeyName, newApiKey.trim())
        newKeyName = ""
        newApiKey = ""
        apiKey = getSavedApiKey(selectedKind)
        status = "API key added for ${selectedKind.title}."
        statusOk = true
    }
    val onActivateKey: (String) -> Unit = { keyId ->
        savedKeys = onActivateApiKey(selectedKind, keyId)
        apiKey = getSavedApiKey(selectedKind)
        status = "Active API key changed for ${selectedKind.title}."
        statusOk = true
    }
    val onRemoveKey: (String) -> Unit = { keyId ->
        savedKeys = onRemoveApiKey(selectedKind, keyId)
        apiKey = getSavedApiKey(selectedKind)
        keyConnectionStatuses = keyConnectionStatuses - keyId
        status = "API key removed from ${selectedKind.title}."
        statusOk = true
    }
    val onValidate: () -> Unit = {
        scope.launch {
            isValidating = true
            val activeKeyId = savedKeys.firstOrNull { it.isActive }?.id
            if (activeKeyId != null) {
                keyConnectionStatuses = keyConnectionStatuses +
                    (activeKeyId to KeyConnectionStatus("Checking connection…"))
                status = null
            } else {
                status = "Checking connection…"
                statusOk = true
            }
            val kind = selectedKind
            val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl.trim()
            // A pasted setup token means token mode; otherwise keep whatever mode is active.
            val authMode = if (kind == ProviderKind.CLAUDE && apiKey.isNotBlank()) {
                ClaudeAuthMode.SETUP_TOKEN_LEGACY
            } else {
                state.provider.claudeAuthMode
            }
            val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi, claudeAuthMode = authMode)
            if (kind == ProviderKind.CLAUDE) {
                onSaveProvider(profile, apiKey.trim())
                status = "Claude subscription token saved securely. Send a message to verify your subscription."
                statusOk = true
                activeKeyId?.let {
                    keyConnectionStatuses = keyConnectionStatuses +
                        (it to KeyConnectionStatus("Subscription token saved", true, label = "Saved"))
                }
            } else when (val result = onValidateProvider(profile, apiKey.trim(), models)) {
                is ConnectionValidation.Success -> {
                    onSaveProvider(profile, apiKey.trim())
                    if (activeKeyId != null) {
                        keyConnectionStatuses = keyConnectionStatuses +
                            (activeKeyId to KeyConnectionStatus(result.message, true, label = "Verified"))
                    } else {
                        status = result.message
                        statusOk = true
                    }
                }
                is ConnectionValidation.Failure -> {
                    if (activeKeyId != null) {
                        keyConnectionStatuses = keyConnectionStatuses +
                            (activeKeyId to KeyConnectionStatus(result.message, false, result.providerMessage, result.label))
                    } else {
                        status = result.message
                        statusOk = false
                        statusProviderMessage = result.providerMessage
                    }
                }
            }
            isValidating = false
        }
    }

    // Live connection state for the selected engine, shown under the switcher.
    val (statusColor, statusLabel) = if (isAntigravity) {
        when {
            state.apiPingStatus == ApiPingStatus.PINGING -> MaterialTheme.colorScheme.primary to "Testing…"
            state.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN || state.apiPingStatus == ApiPingStatus.FAILED ->
                colors.red to "Needs attention"
            else -> colors.green to "Online"
        }
    } else {
        when (state.apiPingStatus) {
            ApiPingStatus.OK -> colors.green to "Online"
            ApiPingStatus.FAILED -> colors.red to "Needs attention"
            ApiPingStatus.PINGING -> MaterialTheme.colorScheme.primary to "Testing…"
            ApiPingStatus.IDLE -> colors.tertiaryLabel to "Not tested"
        }
    }
    val subtitle = if (isAntigravity) {
        "Antigravity · ${formatAntigravityModelName(state.antigravityModel)}"
    } else {
        "${state.agentKind.title} · ${model.ifBlank { selectedKind.title }}"
    }
    val collapse by rememberTitleCollapse(listState)

    LargeTitleScaffold(title = "Agent", collapse = { collapse }) { padding ->
        // Runs edge to edge under the glass bar and dock; it is the shared backdrop source.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hostBackdropSource().imePadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomBarPadding + PocketSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xl),
        ) {
            item(key = "title") { LargeTitle("Agent", subtitle = subtitle) }

            item(key = "switcher") {
                Column(Modifier.padding(horizontal = ListInset)) {
                    SegmentedControl(
                        items = orderedAgents,
                        selected = viewedAgent,
                        onSelect = { agent ->
                            viewedAgent = agent
                            val installed = agent == state.agentKind || state.installedAgentVersions.containsKey(agent)
                            if (installed) onSelectAgent(agent)
                        },
                        label = { it.shortName() },
                        enabled = state.agentInstalling == null,
                    )
                    if (viewedAgent == state.agentKind) {
                        StatusCaption(statusColor, statusLabel)
                    }
                }
            }

            when {
                state.agentInstalling == viewedAgent -> item(key = "installing") { InstallingSection(state, viewedAgent) }
                !viewedAgentInstalled -> item(key = "install") {
                    EmptyState(
                        icon = Icons.Outlined.SmartToy,
                        title = "${viewedAgent.title} isn’t installed",
                        message = if (state.agentInstalling == null) {
                            "Install its ${viewedAgent.downloadNote} agent package to use it with your projects."
                        } else {
                            "Another agent is installing. Try again when it finishes."
                        },
                        actionLabel = if (state.agentInstalling == null) "Install" else null,
                        onAction = { onInstallAgent(viewedAgent) },
                    )
                }
                // Selection is still switching over; nothing to configure yet.
                viewedAgent != state.agentKind -> Unit
                isAntigravity -> {
                    item(key = "accounts") {
                        AntigravityAccountsSection(
                            state = state,
                            code = antigravityCode,
                            onCode = { antigravityCode = it },
                            onStartLogin = onStartAntigravityLogin,
                            onSubmitCode = {
                                onSubmitAntigravityCode(antigravityCode)
                                antigravityCode = ""
                            },
                            onSetPrimary = onSetAntigravityPrimaryAccount,
                            onRemove = onRemoveAntigravityAccount,
                        )
                    }
                    if (state.antigravityAccounts.size > 1) {
                        item(key = "balancing") {
                            ListSection(
                                header = "Load balancing",
                                footer = state.antigravityLoadBalancingStrategy.subtitle + ".",
                            ) {
                                AntigravityLoadBalancingStrategy.entries.forEach { strategy ->
                                    ListRow(
                                        strategy.title,
                                        accessory = if (strategy == state.antigravityLoadBalancingStrategy) ListRowAccessory.Check else ListRowAccessory.None,
                                        onClick = { onSetAntigravityLoadBalancingStrategy(strategy) },
                                    )
                                }
                                ToggleRow(
                                    title = "Switch accounts at quota limit",
                                    checked = state.antigravityFailoverEnabled,
                                    onCheckedChange = onSetAntigravityFailoverEnabled,
                                )
                            }
                        }
                    }
                    if (state.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
                        item(key = "model") {
                            ListSection(header = "Model") {
                                ListRow(
                                    "Model",
                                    icon = Icons.Outlined.AutoAwesome,
                                    iconTile = colors.purple,
                                    value = formatAntigravityModelName(state.antigravityModel.ifBlank { "Choose" }),
                                    accessory = ListRowAccessory.Chevron,
                                    onClick = { sheet = AgentSheet.Model },
                                )
                                ListRow(
                                    "Thinking",
                                    icon = Icons.Outlined.Psychology,
                                    iconTile = colors.indigo,
                                    value = effortTitle(state.antigravityEffort),
                                    accessory = ListRowAccessory.Chevron,
                                    onClick = { sheet = AgentSheet.Model },
                                )
                            }
                        }
                        item(key = "connection") {
                            val pinging = state.apiPingStatus == ApiPingStatus.PINGING
                            val message = state.apiPingMessage?.takeIf { state.apiPingStatus != ApiPingStatus.IDLE }
                            ListSection(footer = "Tools are approved automatically and run inside the private Linux workspace.") {
                                ListRow(
                                    if (pinging) "Testing connection…" else "Test connection",
                                    subtitle = message,
                                    subtitleColor = if (state.apiPingStatus == ApiPingStatus.FAILED) colors.red else null,
                                    subtitleMaxLines = 4,
                                    titleColor = MaterialTheme.colorScheme.primary,
                                    leading = if (pinging) ({ ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) }) else null,
                                    enabled = !pinging,
                                    onClick = onPing,
                                )
                            }
                        }
                    }
                }
                else -> {
                    item(key = "provider") {
                        ListSection(header = "Provider") {
                            ListRow(
                                selectedKind.title,
                                subtitle = selectedKind.subtitle,
                                icon = Icons.Outlined.Cloud,
                                iconTile = colors.blue,
                                accessory = ListRowAccessory.Chevron,
                                onClick = { sheet = AgentSheet.Provider },
                            )
                        }
                    }
                    if (selectedKind == ProviderKind.CHATGPT) {
                        item(key = "codexAccount") {
                            CodexAccountCard(
                                auth = state.codexAuth,
                                codexInstalled = state.installedAgentVersions.containsKey(AgentKind.CODEX),
                                busy = state.isRunning || state.agentInstalling != null,
                                onSignIn = onStartCodexLogin,
                                onCancel = onCancelCodexLogin,
                                onSignOut = onLogoutCodex,
                                onRefresh = onRefreshCodexAuth,
                            )
                        }
                        item(key = "model") {
                            ListSection(
                                header = "Model",
                                footer = "Leave this on Codex default unless you need a specific model.",
                            ) {
                                ListRow(
                                    "Model",
                                    icon = Icons.Outlined.AutoAwesome,
                                    iconTile = colors.purple,
                                    value = model.ifBlank { "Codex default" },
                                    accessory = ListRowAccessory.Chevron,
                                    onClick = { sheet = AgentSheet.Model },
                                )
                            }
                        }
                    } else if (selectedKind != ProviderKind.CLAUDE) {
                        item(key = "endpoint") {
                            EndpointSection(
                                agentKind = state.agentKind,
                                kind = selectedKind,
                                baseUrl = baseUrl,
                                onBaseUrl = onBaseUrl,
                            )
                        }
                        if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind in DSH_PROTOCOL_PROVIDERS && !selectedKind.fixedProtocol) {
                            item(key = "protocol") {
                                ListSection(header = "Gateway protocol") {
                                    listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                                        ListRow(
                                            option,
                                            accessory = if (dshApi == option) ListRowAccessory.Check else ListRowAccessory.None,
                                            onClick = { onDshApi(option) },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "model") {
                            ListSection(header = "Model") {
                                ListRow(
                                    "Model",
                                    icon = Icons.Outlined.AutoAwesome,
                                    iconTile = colors.purple,
                                    value = model.ifBlank { "Choose" },
                                    accessory = ListRowAccessory.Chevron,
                                    onClick = { sheet = AgentSheet.Model },
                                )
                                ListRow(
                                    if (isDiscovering) "Discovering models…" else "Discover models",
                                    titleColor = MaterialTheme.colorScheme.primary,
                                    leading = if (isDiscovering) ({ ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) }) else null,
                                    value = if (models.isNotEmpty() && !isDiscovering) "${models.size} found" else null,
                                    enabled = !isDiscovering,
                                    onClick = ::discoverModels,
                                )
                            }
                        }
                    } else {
                        item(key = "claude") {
                            ClaudeAccountCard(
                                auth = state.claudeAuth,
                                claudeInstalled = state.installedAgentVersions.containsKey(AgentKind.CLAUDE_CODE),
                                busy = state.isRunning || state.agentInstalling != null,
                                onSignIn = onStartClaudeLogin,
                                onCancel = onCancelClaudeLogin,
                                onSubmitCode = onSubmitClaudeCode,
                                onSignOut = onLogoutClaude,
                                onRefresh = onRefreshClaudeAuth,
                            )
                        }
                    }
                    if (selectedKind == ProviderKind.ANTIGRAVITY_SERVER) {
                        item(key = "googleAccount") { AntigravityServerAccountSection(state) }
                    } else if (selectedKind != ProviderKind.CHATGPT) {
                        item(key = "keys") {
                            val claude = selectedKind == ProviderKind.CLAUDE
                            ListSection(
                                header = if (claude) "Setup token (advanced)" else "API keys",
                                footer = if (claude) "Prefer a token? Run claude setup-token on a computer and add it here."
                                else "Keys are encrypted in Android secure storage. Tap a key to use it.",
                            ) {
                                savedKeys.forEach { key ->
                                    KeyRow(
                                        key = key,
                                        status = keyConnectionStatuses[key.id],
                                        onActivate = { onActivateKey(key.id) },
                                        onRemove = { keyPendingRemoval = key },
                                    )
                                }
                                ListRow(
                                    if (claude) "Add setup token" else "Add API key",
                                    icon = Icons.Outlined.Add,
                                    iconTile = MaterialTheme.colorScheme.primary,
                                    titleColor = MaterialTheme.colorScheme.primary,
                                    onClick = { sheet = AgentSheet.AddKey },
                                )
                            }
                        }
                    }
                    val isAntigravityServer = selectedKind == ProviderKind.ANTIGRAVITY_SERVER
                    // For Claude the account sign-in is the main path; saving a setup token only
                    // appears once one has been added.
                    if (selectedKind != ProviderKind.CHATGPT && (selectedKind != ProviderKind.CLAUDE || savedKeys.isNotEmpty())) {
                        item(key = "validate") {
                            val hasAntigravityAuth = state.antigravityAccounts.isNotEmpty() ||
                                state.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN
                            val canValidate = !isDiscovering && !isValidating && (
                                (isAntigravityServer && hasAntigravityAuth && model.isNotBlank()) ||
                                    (!isAntigravityServer && apiKey.isNotBlank() && (selectedKind == ProviderKind.CLAUDE || (baseUrl.isNotBlank() && model.isNotBlank())))
                                )
                            val footer = status?.let { s -> statusProviderMessage?.let { "$s\nProvider: $it" } ?: s }
                            ListSection(footer = footer) {
                                ListRow(
                                    when {
                                        isValidating && selectedKind == ProviderKind.CLAUDE -> "Saving token…"
                                        isValidating && isAntigravityServer -> "Verifying Antigravity Server…"
                                        isValidating -> "Testing connection…"
                                        selectedKind == ProviderKind.CLAUDE -> "Save subscription token"
                                        isAntigravityServer -> "Connect and save"
                                        else -> "Test connection"
                                    },
                                    titleColor = if (status != null && !statusOk) colors.red else MaterialTheme.colorScheme.primary,
                                    leading = if (isValidating) ({ ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) }) else null,
                                    enabled = canValidate,
                                    onClick = onValidate,
                                )
                            }
                        }
                    }
                }
            }

            item(key = "updates") {
                AgentUpdatesSection(state = state, onCheck = onCheckAgentUpdates, onUpdate = onUpdateAgent)
            }
        }
    }

    // ── Sheets and alerts ──
    GlassSheet(
        onDismiss = { sheet = AgentSheet.None },
        visible = sheet == AgentSheet.Model,
        title = "Model",
        trailing = {
            val loading = if (isAntigravity) state.antigravityModelsLoading else isDiscovering
            if (loading) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { ProgressRing(progress = null, size = 20.dp, strokeWidth = 2.dp) }
            } else if (selectedKind != ProviderKind.CHATGPT || isAntigravity) {
                PocketIconButton(
                    Icons.Outlined.Refresh,
                    if (isAntigravity) "Refresh models" else "Discover models",
                    onClick = if (isAntigravity) onRefreshAntigravityModels else ::discoverModels,
                )
            }
        },
    ) {
        if (isAntigravity) {
            AntigravityModelList(
                models = filteredAntigravityModels,
                selected = state.antigravityModel,
                effort = state.antigravityEffort,
                search = antigravitySearch,
                onSearch = { antigravitySearch = it },
                onEffort = onSetAntigravityEffort,
                onSelect = {
                    onSetAntigravityModel(it)
                    sheet = AgentSheet.None
                },
            )
        } else {
            ProviderModelList(
                kind = selectedKind,
                models = models,
                filtered = filteredModels,
                selected = model,
                search = modelSearch,
                onSearch = { modelSearch = it },
                isDiscovering = isDiscovering,
                status = status?.takeIf { !statusOk || isDiscovering },
                onDiscover = ::discoverModels,
                onSelect = {
                    model = it
                    modelSearch = ""
                    sheet = AgentSheet.None
                    // No Test connection step exists for the ChatGPT account, so the choice is saved here.
                    if (selectedKind == ProviderKind.CHATGPT) onSaveProvider(ProviderProfile(selectedKind, "", it.trim()), "")
                },
            )
        }
    }

    GlassSheet(
        onDismiss = { sheet = AgentSheet.None },
        visible = sheet == AgentSheet.Provider,
        title = "Provider",
        detents = listOf(SheetDetent.Medium, SheetDetent.Large),
        trailing = { SheetTextButton("Done", { sheet = AgentSheet.None }, emphasized = true) },
    ) {
        val kinds = remember(state.agentKind) { providersForAgent(state.agentKind) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
            itemsIndexed(kinds, key = { _, kind -> kind.name }) { index, kind ->
                LazyGroupRow(isFirst = index == 0, isLast = index == kinds.lastIndex) {
                    ListRow(
                        kind.title,
                        subtitle = kind.subtitle,
                        subtitleMaxLines = 1,
                        accessory = if (kind == selectedKind) ListRowAccessory.Check else ListRowAccessory.None,
                        onClick = {
                            if (kind != selectedKind) onProvider(kind)
                            sheet = AgentSheet.None
                        },
                    )
                }
            }
        }
    }

    AddKeySheet(
        visible = sheet == AgentSheet.AddKey,
        kind = selectedKind,
        name = newKeyName,
        key = newApiKey,
        hint = status?.takeIf { !statusOk && sheet == AgentSheet.AddKey },
        onName = { input ->
            // A key pasted into the name field moves to the key field.
            if ((input.startsWith("sk-") || input.startsWith("ant-") || input.length > 30) && !input.contains(" ") && newApiKey.isBlank()) {
                newApiKey = input.trim()
                newKeyName = "${selectedKind.title} Key"
            } else {
                newKeyName = input
            }
        },
        onKey = { newApiKey = it },
        onDismiss = { sheet = AgentSheet.None },
        onSave = {
            onAddKey()
            sheet = AgentSheet.None
        },
    )

    keyPendingRemoval?.let { key ->
        PocketAlert(
            onDismiss = { keyPendingRemoval = null },
            title = "Remove “${key.name}”?",
            message = "The key is deleted from this device. Add it again to use it later.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { keyPendingRemoval = null },
                AlertAction("Remove", AlertRole.Destructive) {
                    keyPendingRemoval = null
                    onRemoveKey(key.id)
                },
            ),
        )
    }
}

/** A coloured dot and a word for the engine's connection state. */
@Composable
private fun StatusCaption(color: Color, label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = PocketSpacing.xs)
            .semantics(mergeDescendants = true) { contentDescription = "Connection: $label" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(PocketShape.capsule).background(color))
        Spacer(Modifier.width(PocketSpacing.xs + 2.dp))
        Text(label, style = PocketType.footnote, color = PocketColors.current.secondaryLabel)
    }
}

@Composable
private fun InstallingSection(state: AppUiState, agent: AgentKind) {
    val downloaded = state.agentDownloadedBytes
    val total = state.agentTotalBytes
    val detail = buildString {
        if (downloaded != null || total != null) {
            append(formatAgentBytes(downloaded ?: 0L))
            total?.let { append(" of ${formatAgentBytes(it)}") }
        } else {
            append("Processing files")
        }
        state.agentBytesPerSecond?.takeIf { it > 0L }?.let { append(" · ${formatAgentBytes(it)}/s") }
    }
    ListSection(footer = detail) {
        ListRow(
            "Installing ${agent.title}",
            subtitle = state.agentMessage ?: "Installing the agent package…",
            leading = { ProgressRing(progress = state.agentProgress.coerceIn(0f, 1f), size = 26.dp) },
            value = "${(state.agentProgress * 100).toInt()}%",
        )
    }
}

@Composable
private fun EndpointSection(
    agentKind: AgentKind,
    kind: ProviderKind,
    baseUrl: String,
    onBaseUrl: (String) -> Unit,
) {
    val showsProtocol = agentKind == AgentKind.DEEPSEEK_HARNESS && kind in DSH_PROTOCOL_PROVIDERS
    ListSection(
        header = if (kind == ProviderKind.CUSTOM) "Custom API" else "Endpoint",
        footer = when {
            kind == ProviderKind.CUSTOM -> "Enter the provider’s base URL, then add its API key below."
            kind.fixedBaseUrl -> "Set by ${kind.title}."
            else -> null
        },
    ) {
        if (kind.fixedBaseUrl) {
            ListRow("Base URL", subtitle = baseUrl, icon = Icons.Outlined.Link, iconTile = PocketColors.current.gray)
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.md)) {
                PocketTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrl,
                    placeholder = "https://api.example.com/v1",
                    label = "Base URL",
                    monospace = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
            }
        }
        // A choosable protocol has its own section below.
        if (showsProtocol && kind.fixedProtocol) {
            ListRow("Protocol", value = defaultDshApiForProvider(kind))
        }
    }
}

/** One saved API key: tap to use it; its last check shows underneath; ⋯ to remove. */
@Composable
private fun KeyRow(key: ApiKeyInfo, status: KeyConnectionStatus?, onActivate: () -> Unit, onRemove: () -> Unit) {
    val colors = PocketColors.current
    val anchor = rememberOverlayAnchor()
    var menu by remember { mutableStateOf(false) }
    val subtitle = when {
        status != null -> status.message + (status.providerMessage?.let { "\nProvider: $it" } ?: "")
        key.isActive -> "In use"
        else -> null
    }
    ListRow(
        key.name,
        subtitle = subtitle,
        subtitleColor = when (status?.successful) {
            true -> colors.green
            false -> colors.red
            null -> null
        },
        subtitleMaxLines = 4,
        icon = Icons.Outlined.Key,
        iconTile = if (key.isActive) colors.green else colors.gray,
        accessory = if (key.isActive) ListRowAccessory.Check else ListRowAccessory.None,
        onClick = onActivate,
        trailing = {
            if (status != null && status.successful == null) ProgressRing(progress = null, size = 18.dp, strokeWidth = 2.dp)
            PocketIconButton(
                Icons.Outlined.MoreHoriz,
                "Options for ${key.name}",
                onClick = { menu = true },
                modifier = Modifier.overlayAnchor(anchor),
                tint = colors.secondaryLabel,
            )
        },
    )
    GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
        if (!key.isActive) GlassMenuItem("Use this key", onActivate, icon = Icons.Outlined.Key)
        GlassMenuItem("Remove key", onRemove, destructive = true)
    }
}

@Composable
private fun AntigravityAccountsSection(
    state: AppUiState,
    code: String,
    onCode: (String) -> Unit,
    onStartLogin: () -> Unit,
    onSubmitCode: () -> Unit,
    onSetPrimary: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = PocketColors.current
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    val auth = state.antigravityAuth
    val accounts = state.antigravityAccounts
    var pendingRemoval by remember { mutableStateOf<AntigravityAccount?>(null) }
    ListSection(
        header = "Google accounts",
        footer = if (accounts.isNotEmpty()) {
            "${accounts.size} connected · ${state.antigravityLoadBalancingStrategy.title}. Tap an account to make it primary."
        } else {
            "Antigravity runs on your Google account. Sign in to start."
        },
    ) {
        accounts.forEach { account ->
            AntigravityAccountRow(
                account = account,
                model = state.antigravityModel,
                onSetPrimary = { onSetPrimary(account.id) },
                onRemove = { pendingRemoval = account },
            )
        }
        when (auth.status) {
            AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> ListRow(
                "Connecting to Google…",
                leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
            )
            AntigravityAuthStatus.AWAITING_CODE -> {
                auth.authorizationUrl?.let { url ->
                    ListRow(
                        "Copy Google sign-in link",
                        icon = Icons.Outlined.ContentCopy,
                        iconTile = colors.blue,
                        onClick = {
                            clipboard.setText(AnnotatedString(url))
                            banner("Sign-in link copied. Open it in your browser.", BannerKind.Success)
                        },
                    )
                }
                Column(
                    Modifier.fillMaxWidth().padding(ListInset),
                    verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
                ) {
                    PocketTextField(
                        value = code,
                        onValueChange = onCode,
                        label = "One-time authorization code",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                    PocketButton("Complete sign-in", onSubmitCode, enabled = code.isNotBlank(), fullWidth = true)
                }
            }
            else -> ListRow(
                when {
                    accounts.isNotEmpty() || auth.status == AntigravityAuthStatus.SIGNED_IN -> "Add account"
                    auth.status == AntigravityAuthStatus.ERROR -> "Reconnect with Google"
                    else -> "Sign in with Google"
                },
                icon = if (accounts.isEmpty()) Icons.Outlined.Person else Icons.Outlined.Add,
                iconTile = MaterialTheme.colorScheme.primary,
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = onStartLogin,
            )
        }
    }
    pendingRemoval?.let { account ->
        PocketAlert(
            onDismiss = { pendingRemoval = null },
            title = "Remove ${account.email}?",
            message = "Antigravity stops using this account. You can sign in with it again later.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { pendingRemoval = null },
                AlertAction("Remove", AlertRole.Destructive) {
                    pendingRemoval = null
                    onRemove(account.id)
                },
            ),
        )
    }
}

@Composable
private fun AntigravityAccountRow(
    account: AntigravityAccount,
    model: String,
    onSetPrimary: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = PocketColors.current
    val anchor = rememberOverlayAnchor()
    var menu by remember { mutableStateOf(false) }
    val quota = account.remainingPercentageFor(model)
    val exhausted = account.status == AntigravityAccountStatus.QUOTA_EXHAUSTED || quota == 0
    val authError = account.status == AntigravityAccountStatus.AUTH_ERROR
    val quotaColor = when {
        authError || exhausted -> colors.red
        quota == null -> colors.secondaryLabel
        quota >= 50 -> colors.green
        quota >= 15 -> colors.orange
        else -> colors.red
    }
    val cooldownMs = account.quotaExhaustedUntil?.minus(System.currentTimeMillis()) ?: 0L
    val cooldownMin = if (cooldownMs > 0L) (cooldownMs + 59_999L) / 60_000L else 0L
    val subtitle = when {
        account.status == AntigravityAccountStatus.QUOTA_EXHAUSTED ->
            if (cooldownMin > 0) "Quota limit · resets in ${cooldownMin}m" else "Quota exhausted · resetting…"
        authError -> account.failureMessage ?: "Sign in again"
        account.isPrimary -> "Primary"
        else -> "Connected"
    }
    ListRow(
        account.displayTitle,
        subtitle = subtitle,
        subtitleColor = when {
            authError -> colors.red
            account.status == AntigravityAccountStatus.QUOTA_EXHAUSTED -> colors.orange
            else -> null
        },
        leading = {
            Box(
                Modifier
                    .size(ListIconTile)
                    .clip(ContinuousRoundedShape(ListIconTile * 0.24f))
                    .background(quotaColor.copy(alpha = 0.16f))
                    .semantics { contentDescription = quota?.let { "$it percent quota left" } ?: "Quota unknown" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        authError -> "!"
                        quota != null -> "$quota"
                        else -> "–"
                    },
                    style = PocketType.caption1.emphasized,
                    color = quotaColor,
                    maxLines = 1,
                )
            }
        },
        accessory = if (account.isPrimary) ListRowAccessory.Check else ListRowAccessory.None,
        onClick = onSetPrimary,
        trailing = {
            PocketIconButton(
                Icons.Outlined.MoreHoriz,
                "Options for ${account.email}",
                onClick = { menu = true },
                modifier = Modifier.overlayAnchor(anchor),
                tint = colors.secondaryLabel,
            )
        },
    )
    GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
        if (!account.isPrimary) GlassMenuItem("Make primary", onSetPrimary, icon = Icons.Outlined.Person)
        GlassMenuItem("Remove account", onRemove, destructive = true)
    }
}

/** Antigravity Server (a provider for the other engines) signs in through Google accounts. */
@Composable
private fun AntigravityServerAccountSection(state: AppUiState) {
    val colors = PocketColors.current
    val accounts = state.antigravityAccounts
    val connected = accounts.isNotEmpty() || state.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN
    ListSection(
        header = "Google account",
        footer = if (connected) {
            "Antigravity Server uses your Google accounts with rotating sign-in tokens. No API key needed."
        } else {
            "Switch to Antigravity above and sign in with Google to use these models."
        },
    ) {
        if (accounts.isEmpty()) {
            ListRow(
                if (connected) state.antigravityAuth.accountEmail ?: "Connected" else "No Google account",
                icon = Icons.Outlined.Person,
                iconTile = if (connected) colors.green else colors.gray,
            )
        } else {
            accounts.forEach { account ->
                ListRow(
                    account.email,
                    subtitle = if (account.isPrimary) "Primary" else "Failover",
                    icon = Icons.Outlined.Person,
                    iconTile = if (account.isAvailableForRouting) colors.green else colors.red,
                )
            }
        }
    }
}

@Composable
private fun AntigravityModelList(
    models: List<String>,
    selected: String,
    effort: String,
    search: String,
    onSearch: (String) -> Unit,
    onEffort: (String) -> Unit,
    onSelect: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
        item(key = "thinking") {
            ListSection(header = "Thinking", footer = effortCaption(effort)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.sm)) {
                    SegmentedControl(Efforts, effort, onEffort, label = ::effortTitle)
                }
            }
        }
        item(key = "search") {
            SearchField(
                search,
                onSearch,
                placeholder = "Search models",
                modifier = Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.lg),
            )
        }
        item(key = "count") {
            SectionHeader("${models.size} available", Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm))
        }
        if (models.isEmpty()) {
            item(key = "empty") {
                Text(
                    "No models match “${search.trim()}”.",
                    style = PocketType.subheadline,
                    color = PocketColors.current.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(PocketSpacing.xl),
                )
            }
        }
        itemsIndexed(models, key = { _, id -> id }) { index, id ->
            LazyGroupRow(isFirst = index == 0, isLast = index == models.lastIndex) {
                val tier = formatAntigravityModelTier(id)
                ListRow(
                    formatAntigravityModelName(id),
                    subtitle = if (tier.isEmpty()) id else "$tier · $id",
                    subtitleMaxLines = 1,
                    accessory = if (id == selected) ListRowAccessory.Check else ListRowAccessory.None,
                    onClick = { onSelect(id) },
                )
            }
        }
    }
}

@Composable
private fun ProviderModelList(
    kind: ProviderKind,
    models: List<DiscoveredModel>,
    filtered: List<DiscoveredModel>,
    selected: String,
    search: String,
    onSearch: (String) -> Unit,
    isDiscovering: Boolean,
    status: String?,
    onDiscover: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = PocketColors.current
    val custom = search.trim()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
        item(key = "search") {
            SearchField(
                search,
                onSearch,
                placeholder = "Search or enter a model ID",
                modifier = Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm),
            )
        }
        if (status != null) {
            item(key = "status") {
                Text(
                    status,
                    style = PocketType.footnote,
                    color = if (isDiscovering) colors.secondaryLabel else colors.red,
                    modifier = Modifier.padding(horizontal = ListInset * 2, vertical = PocketSpacing.sm),
                )
            }
        }
        if (custom.isNotEmpty() && filtered.none { it.id.equals(custom, ignoreCase = true) }) {
            item(key = "custom") {
                ListSection(Modifier.padding(top = PocketSpacing.md)) {
                    ListRow(
                        "Use “$custom”",
                        subtitle = "Custom model ID",
                        icon = Icons.Outlined.Edit,
                        iconTile = MaterialTheme.colorScheme.primary,
                        onClick = { onSelect(custom) },
                    )
                }
            }
        }
        if (kind == ProviderKind.CHATGPT && custom.isEmpty()) {
            item(key = "codexDefault") {
                ListSection(Modifier.padding(top = PocketSpacing.md)) {
                    ListRow(
                        "Codex default",
                        subtitle = "Let Codex choose the model for your plan",
                        accessory = if (selected.isBlank()) ListRowAccessory.Check else ListRowAccessory.None,
                        onClick = { onSelect("") },
                    )
                }
            }
        }
        if (filtered.isEmpty() && !isDiscovering) {
            val recommended = defaultModelsForProvider(kind)
            if (recommended.isNotEmpty() && custom.isEmpty()) {
                item(key = "recommended") {
                    ListSection(Modifier.padding(top = PocketSpacing.md), header = "Recommended") {
                        recommended.forEach { option ->
                            ListRow(
                                option.displayName,
                                subtitle = option.id,
                                subtitleMaxLines = 1,
                                accessory = if (option.id == selected) ListRowAccessory.Check else ListRowAccessory.None,
                                onClick = { onSelect(option.id) },
                            )
                        }
                    }
                }
            }
            if (kind != ProviderKind.CHATGPT) item(key = "discover") {
                ListSection(Modifier.padding(top = PocketSpacing.md)) {
                    ListRow(
                        "Discover models from ${kind.title}",
                        icon = Icons.Outlined.AutoAwesome,
                        iconTile = colors.purple,
                        titleColor = MaterialTheme.colorScheme.primary,
                        onClick = onDiscover,
                    )
                }
            }
        } else if (filtered.isNotEmpty()) {
            item(key = "count") {
                SectionHeader(
                    if (filtered.size == models.size) "${models.size} models" else "${filtered.size} of ${models.size}",
                    Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.md),
                )
            }
            itemsIndexed(filtered, key = { _, option -> option.id }) { index, option ->
                LazyGroupRow(isFirst = index == 0, isLast = index == filtered.lastIndex) {
                    ListRow(
                        option.displayName,
                        subtitle = option.id.takeIf { it != option.displayName },
                        subtitleMaxLines = 1,
                        value = if (option.isFree) "Free" else null,
                        accessory = if (option.id == selected) ListRowAccessory.Check else ListRowAccessory.None,
                        onClick = { onSelect(option.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AddKeySheet(
    visible: Boolean,
    kind: ProviderKind,
    name: String,
    key: String,
    hint: String?,
    onName: (String) -> Unit,
    onKey: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    val claude = kind == ProviderKind.CLAUDE
    val canSave = name.isNotBlank() && key.isNotBlank()
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = if (claude) "Add Setup Token" else "Add API Key",
        detents = listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Cancel", onDismiss) },
        trailing = { SheetTextButton("Save", onSave, emphasized = true, enabled = canSave) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = PocketSpacing.xl).padding(top = PocketSpacing.sm, bottom = PocketSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.lg),
        ) {
            PocketTextField(
                value = name,
                onValueChange = onName,
                label = "Name",
                placeholder = "${kind.title} Key",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            PocketTextField(
                value = key,
                onValueChange = onKey,
                label = if (claude) "Claude setup token" else "API key",
                placeholder = if (claude) "sk-ant-oat01-…" else "Paste the key",
                secure = true,
                monospace = true,
                helper = hint ?: "Stored encrypted on this device.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            )
        }
    }
}

@Composable
private fun AgentUpdatesSection(
    state: AppUiState,
    onCheck: () -> Unit,
    onUpdate: (AgentKind) -> Unit,
) {
    val canCheck = !state.agentUpdatesChecking && state.agentUpdating == null && state.agentInstalling == null
    ListSection(header = "Updates", footer = state.agentUpdateMessage ?: "Installed agents stay current.") {
        ListRow(
            if (state.agentUpdatesChecking) "Checking for updates…" else "Check for updates",
            titleColor = MaterialTheme.colorScheme.primary,
            leading = if (state.agentUpdatesChecking) ({ ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) }) else null,
            enabled = canCheck,
            onClick = onCheck,
        )
        state.agentUpdates.forEach { (agent, update) ->
            val updating = state.agentUpdating == agent
            val downloaded = state.agentUpdateDownloadedBytes
            val total = state.agentUpdateTotalBytes
            val fraction = if (updating && downloaded != null && total != null && total > 0L) {
                (downloaded.toFloat() / total).coerceIn(0f, 1f)
            } else {
                state.agentUpdateProgress.coerceIn(0f, 1f)
            }
            ListRow(
                agent.title,
                subtitle = if (updating) state.agentUpdateMessage ?: "Updating…" else "${update.installedVersion} → ${update.latestVersion}",
                trailing = {
                    if (updating) {
                        ProgressRing(progress = fraction, size = 24.dp, strokeWidth = 2.5.dp)
                    } else {
                        PocketButton(
                            "Update",
                            { onUpdate(agent) },
                            style = PocketButtonStyle.Tinted,
                            size = PocketButtonSize.Small,
                            enabled = state.agentUpdating == null && state.agentInstalling == null,
                        )
                    }
                },
            )
        }
    }
}

private fun formatAgentBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/** Provides popular default models for providers when discovery hasn't been run or is unavailable. */
private fun defaultModelsForProvider(kind: ProviderKind): List<DiscoveredModel> = when (kind) {
    ProviderKind.DEEPSEEK -> listOf(
        DiscoveredModel("deepseek-v4-flash", "DeepSeek-V4 Flash"),
    )
    ProviderKind.OPENCODE_ZEN -> listOf(
        DiscoveredModel(ProviderKind.OPENCODE_ZEN.defaultModel, "OpenCode Zen default"),
    )
    ProviderKind.NVIDIA_NIM -> listOf(
        DiscoveredModel(ProviderKind.NVIDIA_NIM.defaultModel, "Qwen 2.5 Coder 32B"),
    )
    ProviderKind.ANTHROPIC -> listOf(
        DiscoveredModel("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet (Hybrid)"),
        DiscoveredModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet v2"),
        DiscoveredModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku"),
    )
    ProviderKind.LLM_ROUTER -> listOf(
        DiscoveredModel("deepseek/deepseek-r1", "DeepSeek R1 (via OpenRouter)"),
        DiscoveredModel("anthropic/claude-3.7-sonnet", "Claude 3.7 Sonnet"),
        DiscoveredModel("openai/gpt-4o", "GPT-4o"),
        DiscoveredModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B"),
    )
    ProviderKind.KIMI -> listOf(
        DiscoveredModel("kimi-k2.6", "Kimi K2.6"),
        DiscoveredModel("moonshot-v1-8k", "Moonshot v1 8K"),
        DiscoveredModel("moonshot-v1-32k", "Moonshot v1 32K"),
    )
    ProviderKind.ANTIGRAVITY_SERVER -> listOf(
        DiscoveredModel("gemini-3.8-pro", "Gemini 3.8 Pro"),
        DiscoveredModel("gemini-3.8-flash", "Gemini 3.8 Flash"),
        DiscoveredModel("claude-3-7-sonnet", "Claude 3.7 Sonnet (Antigravity)"),
        DiscoveredModel("claude-3-5-sonnet", "Claude 3.5 Sonnet (Antigravity)"),
        DiscoveredModel("gemini-2.5-pro", "Gemini 2.5 Pro"),
        DiscoveredModel("gemini-2.5-flash", "Gemini 2.5 Flash"),
    )
    else -> if (kind.defaultModel.isNotBlank()) listOf(
        DiscoveredModel(kind.defaultModel, "${kind.title} Default (${kind.defaultModel})")
    ) else emptyList()
}
