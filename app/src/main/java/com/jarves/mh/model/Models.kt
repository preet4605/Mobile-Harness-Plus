package com.jarves.mh.model

import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.random.Random

enum class ProviderProtocol { CLAUDE_LOGIN, ANTHROPIC, ANTHROPIC_GATEWAY, OPENROUTER, OPENAI_RESPONSES, OPENAI_CHAT }

enum class ProviderKind(
    val title: String,
    val subtitle: String,
    val protocol: ProviderProtocol,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val experimental: Boolean = false,
    val fixedBaseUrl: Boolean = false,
    val fixedProtocol: Boolean = false,
) {
    CLAUDE("Claude subscription", "Pro, Max, Team or Enterprise", ProviderProtocol.CLAUDE_LOGIN, "", "default"),
    ANTHROPIC("Anthropic API", "Usage billed through Console", ProviderProtocol.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-6"),
    LLM_ROUTER("OpenRouter", "Use your OpenRouter API key", ProviderProtocol.OPENROUTER, "https://openrouter.ai/api", "~anthropic/claude-sonnet-latest"),
    DEEPSEEK("DeepSeek", "Use your DeepSeek API key", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.deepseek.com/anthropic", "deepseek-v4-flash"),
    KIMI("Kimi", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "https://api.moonshot.ai/anthropic", "kimi-k2.6", true),
    OPENCODE_ZEN(
        "OpenCode Zen",
        "Models through the OpenCode Zen gateway",
        ProviderProtocol.OPENAI_RESPONSES,
        "https://opencode.ai/zen/v1",
        "deepseek-v4-flash",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    NVIDIA_NIM(
        "NVIDIA NIM",
        "OpenAI-compatible models hosted by NVIDIA",
        ProviderProtocol.OPENAI_CHAT,
        "https://integrate.api.nvidia.com/v1",
        "qwen/qwen2.5-coder-32b-instruct",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    ANTIGRAVITY_SERVER(
        "Antigravity Server",
        "Google OAuth · Gemini & Claude models",
        ProviderProtocol.ANTHROPIC_GATEWAY,
        "http://127.0.0.1:0",
        "gemini-3.8-pro",
        fixedBaseUrl = true,
        fixedProtocol = true,
    ),
    CUSTOM("Custom API", "Anthropic-compatible endpoint", ProviderProtocol.ANTHROPIC_GATEWAY, "", "", true),
}

/**
 * Coding agent engine installed in the private Linux runtime.
 * Each coding agent is installed independently on demand over the shared Core runtime.
 */
enum class AgentKind(
    val stableId: String,
    val title: String,
    val subtitle: String,
    val downloadNote: String,
) {
    CLAUDE_CODE(
        "claude-code",
        "Claude Code",
        "Anthropic's coding agent · broad provider support",
        "71.8 MB",
    ),
    DEEPSEEK_HARNESS(
        "deepseek-harness",
        "DeepSeek Harness",
        "Official DeepSeek coding agent · API-key providers",
        "26.5 MB",
    ),
    ANTIGRAVITY(
        "antigravity",
        "Antigravity CLI",
        "Google's official coding agent · Google account",
        "39.9 MB",
    ),
    ;

    companion object {
        fun fromStored(value: String?): AgentKind = entries.firstOrNull {
            it.stableId == value || it.name == value
        } ?: CLAUDE_CODE
    }
}

/** Provider kinds usable with [AgentKind.DEEPSEEK_HARNESS]. Claude OAuth login has no dsh equivalent. */
val DEEPSEEK_HARNESS_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.DEEPSEEK,
    ProviderKind.ANTIGRAVITY_SERVER,
    ProviderKind.ANTHROPIC,
    ProviderKind.LLM_ROUTER,
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

val DSH_PROTOCOL_PROVIDERS: Set<ProviderKind> = setOf(
    ProviderKind.KIMI,
    ProviderKind.OPENCODE_ZEN,
    ProviderKind.NVIDIA_NIM,
    ProviderKind.CUSTOM,
)

fun defaultDshApiForProvider(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENCODE_ZEN -> "openai-responses"
    ProviderKind.NVIDIA_NIM -> "openai-completions"
    else -> "anthropic-messages"
}

/**
 * Best-effort protocol choice for a user-entered custom gateway URL.
 * The picker remains editable because a URL alone cannot prove a gateway's wire format.
 */
fun inferredDshApiForUrl(baseUrl: String): String {
    val normalized = baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
    return when {
        normalized.endsWith("/responses") -> "openai-responses"
        "/anthropic" in normalized || "api.anthropic.com" in normalized -> "anthropic-messages"
        normalized.endsWith("/v1") -> "openai-completions"
        else -> "anthropic-messages"
    }
}

/** Resolves the protocol DeepSeek Harness will actually use for this saved profile. */
fun providerProtocolForAgent(profile: ProviderProfile, agent: AgentKind): ProviderProtocol {
    if (agent != AgentKind.DEEPSEEK_HARNESS || profile.kind !in DSH_PROTOCOL_PROVIDERS) {
        return profile.kind.protocol
    }
    val api = if (profile.kind.fixedProtocol) defaultDshApiForProvider(profile.kind) else profile.dshApi
    return when (api) {
        "openai-completions" -> ProviderProtocol.OPENAI_CHAT
        "openai-responses" -> ProviderProtocol.OPENAI_RESPONSES
        else -> ProviderProtocol.ANTHROPIC_GATEWAY
    }
}

/** Provider choices shown for the selected coding agent. */
fun providersForAgent(agent: AgentKind): List<ProviderKind> = when (agent) {
    AgentKind.DEEPSEEK_HARNESS -> ProviderKind.entries.filter { it in DEEPSEEK_HARNESS_PROVIDERS }
    AgentKind.CLAUDE_CODE -> ProviderKind.entries.filterNot { it == ProviderKind.OPENCODE_ZEN }
    AgentKind.ANTIGRAVITY -> emptyList()
}

data class ProviderProfile(
    val kind: ProviderKind,
    val baseUrl: String = kind.defaultBaseUrl,
    val model: String = kind.defaultModel,
    val hasSecret: Boolean = false,
    /** dsh custom-route wire protocol for CUSTOM: anthropic-messages | openai-completions | openai-responses. */
    val dshApi: String = defaultDshApiForProvider(kind),
) {
    /** Effective base URL: fixed kinds always resolve to their constant, ignoring stored drift. */
    val resolvedBaseUrl: String get() = if (kind.fixedBaseUrl) kind.defaultBaseUrl else baseUrl
}

enum class ProjectKind { PROJECT, QUICK_PROJECT }

private val DIACRITICS_REGEX = Regex("\\p{M}+")
private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9]+")
private val DATE_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String,
    val language: String,
    val slug: String = projectSlug(name),
    val rootPath: String = "",
    val updatedAtMillis: Long = System.currentTimeMillis(),
    val kind: ProjectKind = ProjectKind.PROJECT,
) {
    val formattedUpdatedAt: String
        get() {
            val diff = System.currentTimeMillis() - updatedAtMillis
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                diff < 0 || seconds < 60 -> "Just now"
                minutes < 60 -> "${minutes}m ago"
                hours < 24 -> "${hours}h ago"
                days == 1L -> "Yesterday"
                days < 7 -> "${days}d ago"
                else -> {
                    Instant.ofEpochMilli(updatedAtMillis)
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(DATE_FORMATTER)
                }
            }
        }
}

fun projectSlug(name: String): String {
    val ascii = Normalizer.normalize(name, Normalizer.Form.NFKD)
        .replace(DIACRITICS_REGEX, "")
        .lowercase(Locale.US)
        .replace(NON_ALPHANUMERIC_REGEX, "-")
        .trim('-')
        .take(48)
        .trimEnd('-')
    return ascii.ifBlank { "project" }
}

data class QuickChatIdentity(val displayName: String, val slug: String)

fun generateQuickChatIdentity(usedSlugs: Set<String>, random: Random = Random.Default): QuickChatIdentity {
    val adjectives = listOf("bright", "calm", "clever", "curious", "gentle", "nimble", "quiet", "swift", "wise", "bold")
    val pioneers = listOf("turing", "lovelace", "hopper", "tesla", "curie", "ramanujan", "bose", "kalam", "faraday", "darwin")
    repeat(20) {
        val base = "${adjectives.random(random)}-${pioneers.random(random)}"
        if (base !in usedSlugs) return QuickChatIdentity(base.toDisplayName(), base)
    }
    val base = "${adjectives.random(random)}-${pioneers.random(random)}"
    val slug = generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in usedSlugs }
    return QuickChatIdentity(slug.toDisplayName(), slug)
}

private fun String.toDisplayName(): String = split('-').joinToString(" ") { word ->
    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
}

data class WorkspaceEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
    val sizeBytes: Long = 0,
)

enum class RiskLevel { SAFE, REVIEW, HIGH }

/**
 * Optional development toolchains the user can pick during onboarding.
 * Node.js, npm, Git, and Claude Code itself are always installed because the
 * agent runtime depends on them; these stacks add heavier extras on demand.
 */
enum class DevStack(
    val label: String,
    val description: String,
    val installsSummary: String,
) {
    WEB(
        "Web (JavaScript / TypeScript)",
        "Websites and web apps with HTML, CSS, and JS frameworks.",
        "Node.js and npm (already included)",
    ),
    PYTHON(
        "Python",
        "Scripts, automation, data work, and Python backends.",
        "python3, pip, venv, and build tools",
    ),
    ANDROID(
        "Android (Java / Kotlin)",
        "Build Android app projects and install them directly on this phone.",
        "JDK 17, ARM64 Android SDK 36, Build Tools 35, Gradle 8.14.3, and an offline Maven cache",
    ),
    CPP(
        "C / C++",
        "Fast compiled programs, algorithms, and systems code.",
        "gcc, g++, make, cmake, gdb",
    ),
    PHP(
        "PHP",
        "Websites and apps with PHP — classic sites and Laravel projects.",
        "php-cli, common extensions, and Composer",
    ),
}

data class ToolRequest(
    val approvalId: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel,
)

sealed interface RuntimeEvent {
    val sessionId: String

    data class SessionStarted(override val sessionId: String) : RuntimeEvent
    data class AssistantDelta(override val sessionId: String, val text: String) : RuntimeEvent
    data class ReasoningProgress(override val sessionId: String, val estimatedTokens: Int) : RuntimeEvent
    data class ReasoningSummary(
        override val sessionId: String,
        val summary: String,
        val blockId: Long,
        val startsNewBlock: Boolean = false,
        val isFinal: Boolean = false,
    ) : RuntimeEvent
    data class ToolStarted(
        override val sessionId: String,
        val toolName: String,
        val detail: String,
    ) : RuntimeEvent
    data class RuntimeLog(
        override val sessionId: String,
        val title: String,
        val detail: String,
    ) : RuntimeEvent
    data class ToolRequested(override val sessionId: String, val request: ToolRequest) : RuntimeEvent
    data class ToolApproved(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolRejected(override val sessionId: String, val approvalId: String) : RuntimeEvent
    data class ToolCompleted(override val sessionId: String, val toolName: String, val summary: String) : RuntimeEvent
    data class FilesChanged(override val sessionId: String, val changes: List<ChangeItem>) : RuntimeEvent {
        val paths: List<String> get() = changes.map { it.path }
    }
    data class PreviewStarted(override val sessionId: String, val url: String) : RuntimeEvent
    data class SessionCompleted(override val sessionId: String) : RuntimeEvent
    data class SessionFailed(override val sessionId: String, val reason: String) : RuntimeEvent
    data class SubagentUpdated(override val sessionId: String, val subagent: SubagentInfo) : RuntimeEvent
    data class TaskUpdated(override val sessionId: String, val task: BackgroundTaskInfo) : RuntimeEvent
    data class ArtifactDiscovered(override val sessionId: String, val artifact: ArtifactInfo) : RuntimeEvent
    data class TimerUpdated(override val sessionId: String, val timer: ScheduledTimerInfo) : RuntimeEvent
    data class TokenUsageUpdated(override val sessionId: String, val metrics: SessionTokenMetrics) : RuntimeEvent
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
    val createdAt: Instant = Instant.now(),
    val attachments: List<ChatAttachment> = emptyList(),
    val workItems: List<ActivityItem> = emptyList(),
    val workedMillis: Long = 0L,
)

data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val relativePath: String,
    val mimeType: String,
    val sizeBytes: Long,
)

data class ProjectChat(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

enum class DiffLineType { CONTEXT, ADDITION, DELETION, INFO }

data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLine: Int? = null,
    val newLine: Int? = null,
)

data class ChangeItem(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val diffLines: List<DiffLine> = emptyList(),
    val binary: Boolean = false,
    val accepted: Boolean? = null,
)

data class ActivityItem(
    val title: String,
    val detail: String,
    val isComplete: Boolean = true,
    val isCommand: Boolean = false,
)

enum class AntigravityAccountStatus {
    HEALTHY,
    QUOTA_EXHAUSTED,
    AUTH_ERROR,
    DISABLED,
}

enum class AntigravityLoadBalancingStrategy(val title: String, val subtitle: String) {
    LEAST_RECENTLY_USED("Least recently used", "Distributes turns across accounts to let quotas recover"),
    ROUND_ROBIN("Round-robin", "Cycles evenly between all available accounts"),
    PRIMARY_ONLY("Primary only", "Always uses primary account; falls back on quota limit"),
}

data class ModelQuota(
    val remainingFraction: Float, // 0.0f to 1.0f
    val resetTimeMillis: Long? = null,
    val lastFetchedMillis: Long = System.currentTimeMillis(),
) {
    val percentage: Int
        get() = (remainingFraction.coerceIn(0f, 1f) * 100f).roundToInt()

    val isExhausted: Boolean
        get() = remainingFraction <= 0.001f
}

data class AntigravityAccount(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val label: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0L,
    val status: AntigravityAccountStatus = AntigravityAccountStatus.HEALTHY,
    val quotaExhaustedUntil: Long? = null,
    val failureMessage: String? = null,
    val isPrimary: Boolean = false,
    val modelQuotas: Map<String, ModelQuota> = emptyMap(),
) {
    val isAvailableForRouting: Boolean
        get() {
            if (status == AntigravityAccountStatus.DISABLED || status == AntigravityAccountStatus.AUTH_ERROR) {
                return false
            }
            if (status == AntigravityAccountStatus.QUOTA_EXHAUSTED) {
                val now = System.currentTimeMillis()
                return quotaExhaustedUntil != null && now >= quotaExhaustedUntil
            }
            return true
        }

    val displayTitle: String
        get() = label.ifBlank { email }

    /**
     * Resolves remaining percentage for the requested model or alias.
     * Matches exact alias or prefix/suffix (e.g. 'gemini-3.8-flash' matches 'gemini-3.8-flash-high').
     */
    fun remainingPercentageFor(modelId: String): Int? {
        if (status == AntigravityAccountStatus.QUOTA_EXHAUSTED) {
            val now = System.currentTimeMillis()
            if (quotaExhaustedUntil == null || now < quotaExhaustedUntil) {
                return 0
            }
        }
        val direct = modelQuotas[modelId]?.percentage
        if (direct != null) return direct

        val clean = modelId.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return null

        val base = clean
            .removeSuffix("-high")
            .removeSuffix("-medium")
            .removeSuffix("-low")
            .removeSuffix("-thinking")

        val match = modelQuotas.entries.firstOrNull { (key, _) ->
            val k = key.trim().lowercase(Locale.ROOT)
            val kBase = k
                .removeSuffix("-tiered")
                .removeSuffix("-high")
                .removeSuffix("-medium")
                .removeSuffix("-low")
                .removeSuffix("-thinking")
            k == clean || k == base || kBase == clean || kBase == base ||
                clean.startsWith(kBase) || k.startsWith(base) || base.startsWith(kBase)
        }?.value
        return match?.percentage
    }
}

enum class SlashCommandCategory(val title: String) {
    GENERAL("General"),
    AGENT_WORKFLOW("Agent Workflow"),
    CONFIG("Configuration"),
    DIAGNOSTICS("Diagnostics"),
    VCS("Version Control"),
}

data class SlashCommand(
    val name: String,
    val description: String,
    val syntax: String = "/$name",
    val category: SlashCommandCategory,
    val isLocalOnly: Boolean = false,
    val parameterHint: String? = null,
    val supportedAgents: Set<AgentKind> = AgentKind.entries.toSet(),
)

enum class SkillSource(val title: String) {
    PROJECT("Project"),
    GLOBAL("Global"),
    BUNDLED("Built-in"),
}

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val filePath: String,
    val source: SkillSource,
    val isEnabled: Boolean = true,
    val markdownContent: String? = null,
)

data class ProjectRule(
    val fileName: String,
    val filePath: String,
    val exists: Boolean,
    val content: String = "",
)

enum class SubagentState {
    RUNNING,
    IDLE,
    WAITING_FOR_INPUT,
    WAITING_FOR_DEPENDENTS,
    DONE,
    ERRORED,
    TERMINATED;

    /** True when this is a terminal / end state that requires no further transitions. */
    val isTerminal: Boolean
        get() = this == DONE || this == ERRORED || this == TERMINATED
}

data class SubagentInfo(
    val conversationId: String,
    val role: String,
    val typeName: String,
    val state: SubagentState,
    val currentActivity: String = "",
    val transcriptPath: String? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val finishedAtMillis: Long? = null,
    val error: String? = null,
) {
    /**
     * Fuzzy match used by the subagent registry to reconcile events that may carry
     * either the official UUID [conversationId] or a human-readable [role] string.
     * Wildcard "*" matches any record.
     */
    fun matches(targetId: String, targetRole: String? = null): Boolean {
        if (targetId == "*" || conversationId == targetId) return true
        if (targetRole != null && role.equals(targetRole, ignoreCase = true)) return true
        return false
    }
}

// ---------------------------------------------------------------------------
// Input trigger models (PRD §4.1) – used by ChatTab / TextFieldValue migration
// ---------------------------------------------------------------------------

enum class TriggerType {
    NONE,
    SLASH_COMMAND,
    FILE_MENTION,
}

data class InputTriggerState(
    val type: TriggerType = TriggerType.NONE,
    val query: String = "",
    /** Absolute character index of the trigger character ('/' or '@') in the field text. */
    val triggerStartIndex: Int = -1,
)

enum class BackgroundTaskStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    TERMINATED,
}

data class BackgroundTaskInfo(
    val taskId: String,
    val commandLine: String,
    val cwd: String,
    val status: BackgroundTaskStatus,
    val exitCode: Int? = null,
    val startedAtMillis: Long = System.currentTimeMillis(),
    val logFilePath: String? = null,
    val liveOutputTail: String = "",
)

data class ArtifactInfo(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val filePath: String,
    val summary: String,
    val isUserFacing: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
)

data class ScheduledTimerInfo(
    val taskId: String,
    val prompt: String,
    val totalSeconds: Int,
    val remainingSeconds: Int,
    val condition: String = "never",
    val isCron: Boolean = false,
    val cronExpression: String? = null,
)

data class SessionTokenMetrics(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val contextWindowLimit: Int = 200_000,
    val estimatedCostUsd: Double = 0.0,
)


