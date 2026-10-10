package com.jarves.mh.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.os.SystemClock
import android.os.Build
import android.system.Os
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.jarves.mh.BuildConfig
import com.jarves.mh.data.ApiKeyVault
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.data.ContextMemoryStore
import com.jarves.mh.data.MemoryEntry
import com.jarves.mh.data.MemoryExtractor
import com.jarves.mh.data.MemorySource
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ATTACHMENTS_DIRECTORY
import com.jarves.mh.model.AttachmentPrompt
import com.jarves.mh.model.MAX_ATTACHMENT_BYTES
import com.jarves.mh.model.attachmentExtension
import com.jarves.mh.model.attachmentTooLargeMessage
import com.jarves.mh.model.cleanAttachmentName
import com.jarves.mh.model.isAttachmentStoragePath
import com.jarves.mh.model.resolveAttachmentMimeType
import com.jarves.mh.model.splitAttachmentName
import com.jarves.mh.model.storedAttachmentName
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ModelSlot
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.modelSlotFor
import com.jarves.mh.model.ModelChoiceCheck
import com.jarves.mh.model.checkModelChoice
import com.jarves.mh.model.effortLabel
import com.jarves.mh.model.effortLevelsFor
import com.jarves.mh.model.normalizeEffortChoice
import com.jarves.mh.provider.ProviderFailureClass
import com.jarves.mh.provider.ProviderFailureClassifier
import com.jarves.mh.provider.ProviderFallbackPlanner
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.RuntimeEvent
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.SubagentRegistry
import com.jarves.mh.model.TaskRegistry
import com.jarves.mh.model.TriggerParser
import com.jarves.mh.model.TriggerType
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.projectSlug
import com.jarves.mh.model.generateQuickChatIdentity
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.model.ClaudeAuthMode
import com.jarves.mh.model.ClaudeThinkingLevel
import com.jarves.mh.runtime.AndroidBrowserBridge
import com.jarves.mh.runtime.ClaudeAuthController
import com.jarves.mh.runtime.ClaudeAuthState
import com.jarves.mh.runtime.ClaudeAuthStatusState
import com.jarves.mh.runtime.ClaudeRuntimeBridge
import com.jarves.mh.runtime.CodexAuthController
import com.jarves.mh.runtime.CodexAuthState
import com.jarves.mh.runtime.CodexModelCatalog
import com.jarves.mh.runtime.CodexAuthStatus
import com.jarves.mh.runtime.CodexRuntimeBridge
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import com.jarves.mh.runtime.AntigravityAccountManager
import com.jarves.mh.runtime.DshRuntimeBridge
import com.jarves.mh.runtime.AgentRegistry
import com.jarves.mh.runtime.AgentUpdateInfo
import com.jarves.mh.runtime.AntigravityAuthController
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.AntigravityRuntimeBridge
import com.jarves.mh.runtime.discoverAntigravityModels
import com.jarves.mh.runtime.reconcileAntigravityModelSelection
import com.jarves.mh.runtime.antigravityEffortFromModel
import com.jarves.mh.runtime.antigravityModelWithEffort
import com.jarves.mh.runtime.NativeSpawnProcess
import com.jarves.mh.runtime.RuntimeInstallProgress
import com.jarves.mh.runtime.RuntimeInstaller
import com.jarves.mh.runtime.RuntimeSetupController
import com.jarves.mh.runtime.RuntimeSetupService
import com.jarves.mh.runtime.RuntimeSetupSnapshot
import com.jarves.mh.runtime.RuntimeSetupStatus
import com.jarves.mh.runtime.supportsArm64Runtime
import com.jarves.mh.runtime.AndroidAppInstaller
import com.jarves.mh.update.AppUpdateInfo
import com.jarves.mh.update.AppUpdater
import com.jarves.mh.model.ArtifactInfo
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.ProjectRule
import com.jarves.mh.model.ScheduledTimerInfo
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.LinkedSkillReference
import com.jarves.mh.model.ProjectCustomizationConfig
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.SlashCommand
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.runtime.DiagnosticsHelper
import com.jarves.mh.runtime.SkillManager
import com.jarves.mh.runtime.SlashCommandEngine
import com.jarves.mh.runtime.WorkspaceCheckpoints
import java.io.File
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.net.URI
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class StartupStage { CHECKING, SETUP_REQUIRED, INSTALLING, MODEL_SETUP, INITIALIZING, READY, ERROR }

enum class ApiPingStatus { IDLE, PINGING, OK, FAILED }
enum class AppUpdateStatus { AVAILABLE, PERMISSION_REQUIRED, DOWNLOADING, INSTALLING, ERROR }
enum class GitHubAuthStatus { DISCONNECTED, STARTING, AWAITING_USER, CONNECTED, ERROR }

data class TerminalOutputLine(
    val id: String = java.util.UUID.randomUUID().toString(),
    val command: String,
    val output: String,
    val exitCode: Int = 0,
)

private val ANSI_TERMINAL_SEQUENCE = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")

internal fun sanitizeTerminalOutput(text: String): String = text
    .replace(ANSI_TERMINAL_SEQUENCE, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }


private data class ProjectTerminalSnapshot(
    val lines: List<TerminalOutputLine> = emptyList(),
    val cwd: String = "/workspace",
)

private data class ProjectTerminalResult(
    val output: String,
    val exitCode: Int,
    val cwd: String,
)

private data class RuntimeRetryRequest(
    val runtime: com.jarves.mh.runtime.RuntimeBridge,
    val project: Project,
    val prompt: String,
    val history: List<ChatMessage>,
    val provider: ProviderProfile,
    val memory: ContextMemory = ContextMemory(project.id),
    val taskId: String? = null,
    val attemptId: String? = null,
    val brainSnapshot: com.jarves.mh.data.BrainContextSnapshot? = null,
    /** Custom profiles snapshotted at task start; later edits cannot change this task's fallback order. */
    val fallbackProfiles: List<com.jarves.mh.provider.CustomProviderProfile> = emptyList(),
    /** Redacted record of every failure that caused a key or provider switch. */
    val failureChain: List<String> = emptyList(),
)

private data class TranscriptWrite(
    val projectId: String,
    val chatId: String,
    val messages: List<ChatMessage>,
)

/** Chat history read off the main thread when a project opens. */
private data class LoadedChat(
    val chats: List<ProjectChat>,
    val chatId: String,
    val messages: List<ChatMessage>,
)

private data class ProjectDetails(
    val terminal: ProjectTerminalSnapshot,
    val suggestedRoot: String?,
    val memory: ContextMemory,
)

private data class ImportedZipProject(
    val project: Project,
    val sourceAttachment: ChatAttachment,
)

data class AppUiState(
    val startupStage: StartupStage = StartupStage.CHECKING,
    val startupProgress: Float = 0f,
    val startupMessage: String = "Checking this device…",
    val startupBytes: Pair<Long, Long>? = null,
    val startupLogs: List<String> = emptyList(),
    val startupIndeterminate: Boolean = false,
    val startupError: String? = null,
    val startupErrorIsOffline: Boolean = false,
    val showDetailedSetupProgress: Boolean = false,
    val onboardingComplete: Boolean = false,
    val backgroundSetupComplete: Boolean = false,
    val provider: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val activeApiKeyName: String? = null,
    val themeMode: com.jarves.mh.ui.theme.AppThemeMode = com.jarves.mh.ui.theme.AppThemeMode.DARK,
    val apiPingStatus: ApiPingStatus = ApiPingStatus.IDLE,
    val apiPingMessage: String? = null,
    val projects: List<Project> = emptyList(),
    val projectImporting: Boolean = false,
    val projectImportMessage: String? = null,
    val gitCloneRunning: Boolean = false,
    val gitCloneMessage: String? = null,
    val githubAuthStatus: GitHubAuthStatus = GitHubAuthStatus.DISCONNECTED,
    val githubLogin: String? = null,
    val githubUserCode: String? = null,
    val githubVerificationUri: String? = null,
    val githubMessage: String? = null,
    val githubRepositories: List<GitHubRepository> = emptyList(),
    val githubRepositoriesLoading: Boolean = false,
    val activeProject: Project? = null,
    val workspaceVisible: Boolean = false,
    val readOnlyProject: Project? = null,
    val readOnlyProjectChats: List<ProjectChat> = emptyList(),
    val readOnlyChatId: String? = null,
    val readOnlyMessages: List<ChatMessage> = emptyList(),
    val projectChats: List<ProjectChat> = emptyList(),
    val activeChatId: String? = null,
    /** True while the open project's chat history is loading off the main thread. */
    val chatLoading: Boolean = false,
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val androidProjectDetected: Boolean = false,
    val filesLoading: Boolean = false,
    val openedFilePath: String? = null,
    val openedFileContent: String? = null,
    val fileContentLoading: Boolean = false,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."),
    ),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val pendingApproval: ToolRequest? = null,
    val changes: List<ChangeItem> = emptyList(),
    val activity: List<ActivityItem> = emptyList(),
    val liveProcess: List<ActivityItem> = emptyList(),
    val liveThinking: Boolean = false,
    val activeThinkingBlockId: Long? = null,
    val taskStartedAtMillis: Long? = null,
    val taskFinishedAtMillis: Long? = null,
    val workSegmentStartedAtMillis: Long? = null,
    val currentTaskRequest: String? = null,
    val previewReady: Boolean = false,
    val previewUrl: String? = null,
    val isRunning: Boolean = false,
    val activeSessionId: String? = null,
    /** Sessions of earlier attempts of the current task; their events never reach the chat again. */
    val retiredSessionIds: Set<String> = emptySet(),
    val toastMessage: String? = null,
    val projectTerminalLines: List<TerminalOutputLine> = emptyList(),
    val projectTerminalLiveOutput: String = "",
    val projectTerminalRunning: Boolean = false,
    val projectTerminalCwd: String = "/workspace",
    val projectTerminalCommand: String? = null,
    val projectTerminalDraft: String? = null,
    val pendingTerminalCommand: String? = null,
    val suggestedProjectRoot: String? = null,
    val selectedDevStacks: Set<DevStack> = emptySet(),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val devStackInstalling: DevStack? = null,
    val devStackRemoving: Boolean = false,
    val devStackMessage: String? = null,
    val devStackProgress: Float = 0f,
    val devStackBytes: Pair<Long, Long>? = null,
    val devStackBytesPerSecond: Long? = null,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val primaryAgentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val installedAgentVersions: Map<AgentKind, String> = emptyMap(),
    val agentInstalling: AgentKind? = null,
    val agentMessage: String? = null,
    val agentProgress: Float = 0f,
    val agentDownloadedBytes: Long? = null,
    val agentTotalBytes: Long? = null,
    val agentBytesPerSecond: Long? = null,
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    val agentUpdatesChecking: Boolean = false,
    val agentUpdating: AgentKind? = null,
    val agentUpdateMessage: String? = null,
    val agentUpdateProgress: Float = 0f,
    val agentUpdateDownloadedBytes: Long? = null,
    val agentUpdateTotalBytes: Long? = null,
    val agentUpdateBytesPerSecond: Long? = null,
    val antigravityAuth: AntigravityAuthState = AntigravityAuthState(),
    val antigravityAccounts: List<AntigravityAccount> = emptyList(),
    val claudeAuth: ClaudeAuthState = ClaudeAuthState(),
    val codexAuth: CodexAuthState = CodexAuthState(),
    val claudeAuthMode: ClaudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
    val antigravityLoadBalancingStrategy: AntigravityLoadBalancingStrategy = AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED,
    val antigravityFailoverEnabled: Boolean = true,
    val antigravityModel: String = "",
    val antigravityEffort: String = "high",
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val claudeModel: String = "default",
    val claudeThinkingLevel: String = "default",
    val codexReasoningEffort: String = "",
    val dshReasoningEffort: String = "default",
    val claudeThinkingPickerVisible: Boolean = false,
    val androidBuildRunning: Boolean = false,
    val androidBuildMessage: String? = null,
    val appUpdate: AppUpdateInfo? = null,
    val appUpdateStatus: AppUpdateStatus? = null,
    val appUpdateDownloadedBytes: Long = 0L,
    val appUpdateTotalBytes: Long = -1L,
    val appUpdateError: String? = null,
    val slashCommandsVisible: Boolean = false,
    val slashCommandQuery: String = "",
    val filteredSlashCommands: List<SlashCommand> = emptyList(),
    val filteredSkills: List<SkillInfo> = emptyList(),
    val activeCustomizationConfig: ProjectCustomizationConfig = ProjectCustomizationConfig(""),
    val activeSkills: List<SkillInfo> = emptyList(),
    val otherProjectsSkills: Map<Project, List<SkillInfo>> = emptyMap(),
    val globalSkills: List<SkillInfo> = emptyList(),
    val activeRules: List<RuleInfo> = emptyList(),
    val projectRulesList: List<RuleInfo> = emptyList(),
    val globalRulesList: List<RuleInfo> = emptyList(),
    val projectRules: List<ProjectRule> = emptyList(),
    val subagents: List<SubagentInfo> = emptyList(),
    val backgroundTasks: List<BackgroundTaskInfo> = emptyList(),
    val artifacts: List<ArtifactInfo> = emptyList(),
    val scheduledTimers: List<ScheduledTimerInfo> = emptyList(),
    val tokenMetrics: SessionTokenMetrics = SessionTokenMetrics(),
    val auxiliaryInspectorVisible: Boolean = false,
    val skillsManagerVisible: Boolean = false,
    val modelPickerVisible: Boolean = false,
    /** Saved model list for the active provider; refreshed when the picker opens. */
    val providerModels: List<com.jarves.mh.network.DiscoveredModel> = emptyList(),
    val effortPickerVisible: Boolean = false,
    /** Plan usage the active agent last reported; refreshed only on request. */
    val usage: com.jarves.mh.model.AgentUsage = com.jarves.mh.model.AgentUsage(),
    val usageRefreshing: Boolean = false,
    val mentionMenuVisible: Boolean = false,
    val filteredMentionEntries: List<WorkspaceEntry> = emptyList(),
    /** True while the two-stage graceful→force interrupt sequence is in progress. */
    val isStopping: Boolean = false,
    /** The subagent whose transcript is currently open in the Inspector log viewer. */
    val selectedSubagentForLogs: SubagentInfo? = null,
    /** The background task whose terminal output is currently open in the Inspector log viewer. */
    val selectedTaskForLogs: BackgroundTaskInfo? = null,
    val contextMemory: ContextMemory = ContextMemory(""),
    val memoryViewerVisible: Boolean = false,
)


class MainViewModel(
    application: Application,
    /** Runs project file, customization and file read work. Tests pass a dispatcher they drain by hand. */
    private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {
    /** The default ViewModel factory looks up a constructor that takes only the application. */
    constructor(application: Application) : this(application, Dispatchers.IO)

    private val vault = ApiKeyVault(application)
    private val preferences = AppPreferences(application)
    private val memoryStore = ContextMemoryStore(application)
    private val skillManager = SkillManager(application)
    private val antigravityAccountManager = AntigravityAccountManager(application, preferences)
    private val claudeRuntime = ClaudeRuntimeBridge(application, accountManager = antigravityAccountManager) { profile -> vault.get(profile.secretId) }
    private val dshRuntime = DshRuntimeBridge(application, accountManager = antigravityAccountManager) { profile -> vault.get(profile.secretId) }
    private val codexRuntime = CodexRuntimeBridge(application) { profile -> vault.get(profile.secretId) }
    private val codexModelCatalog = CodexModelCatalog(application)
    private val installer = RuntimeInstaller(application)
    private val antigravityRuntime = AntigravityRuntimeBridge(
        application,
        accountManager = antigravityAccountManager,
        model = { _state.value.antigravityModel },
        effort = { _state.value.antigravityEffort },
        conversationId = { projectId ->
            _state.value.activeChatId?.let { preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, it) }
        },
        saveConversationId = { projectId, id ->
            _state.value.activeChatId?.let { preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, it, id) }
        },
        conversationAccount = { projectId ->
            _state.value.activeChatId?.let { preferences.loadAgentConversationAccount(projectId, it) }
        },
        saveConversationAccount = { projectId, accountId ->
            _state.value.activeChatId?.let { preferences.saveAgentConversationAccount(projectId, it, accountId) }
        },
    )
    private val agentRegistry = AgentRegistry.builtIns(claudeRuntime, dshRuntime, antigravityRuntime, codexRuntime)
    private fun activeRuntime(): com.jarves.mh.runtime.RuntimeBridge = agentRegistry.require(_state.value.agentKind).runtime
    private val providerApi = ProviderApiClient()
    private fun appUpdater(): AppUpdater = AppUpdater(
        getApplication(),
        if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else "",
    )
    @Volatile private var projectTerminalProcess: Process? = null
    @Volatile private var terminalProcess: Process? = null
    @Volatile private var projectTerminalProjectId: String? = null
    @Volatile private var projectTerminalStopRequested: Boolean = false
    @Volatile private var setupCompletionHandled: Boolean = false
    @Volatile private var githubAuthProcess: Process? = null
    private var githubAuthJob: kotlinx.coroutines.Job? = null
    @Volatile private var lastOpenedAntigravityAuthUrl: String? = null
    private var activeRuntimeRequest: RuntimeRetryRequest? = null
    /** Task whose sessions the chat follows; set by sendPrompt, read from supervisor threads. */
    @Volatile private var activeUiTaskId: String? = null
    private val failedApiKeyIds = mutableSetOf<String>()
    private val attemptedProfileIds = mutableSetOf<String>()
    @Volatile private var pendingTranscriptWrite: TranscriptWrite? = null
    private var transcriptDebounceJob: kotlinx.coroutines.Job? = null
    private var previewDiscoverySession: String? = null
    private val previewDiscoveryJobs = mutableMapOf<String, Job>()
    /** The project open in flight. A newer open or a close cancels it; [projectOpenGeneration] drops late results. */
    private var projectOpenJob: Job? = null

    /** The file read in flight. Selecting or closing a file, or switching project, cancels it. */
    private var fileReadJob: Job? = null

    /** Bumped whenever the viewer's file changes. A read that finds a newer value publishes nothing. */
    private var fileReadGeneration = 0L

    /** Bumped by each file refresh. A refresh that is no longer the newest publishes nothing. */
    private var projectFilesGeneration = 0L
    /** Bumped by each customization reload. A reload that is no longer the newest publishes nothing. */
    private var customizationsGeneration = 0L
    private var projectOpenGeneration = 0L
    private val initialAgentKind = AgentKind.fromStored(preferences.agentKind)
    private val initialPrimaryAgentKind = preferences.primaryAgentKind
        .takeIf(String::isNotBlank)
        ?.let(AgentKind::fromStored)
        ?: initialAgentKind
    private val antigravityAuthController = AntigravityAuthController(
        application,
        antigravityAccountManager,
        preferences.antigravitySignedIn,
        preferences.antigravityAccountEmail,
    ) { signedIn, email ->
        preferences.antigravitySignedIn = signedIn
        preferences.antigravityAccountEmail = email.orEmpty()
        if (!signedIn) preferences.clearAgentConversations(AgentKind.ANTIGRAVITY)
    }
    private val browserBridge = AndroidBrowserBridge(application) { url ->
        openExternalUrlOnce(url)
    }
    val claudeAuthController = ClaudeAuthController(
        context = application,
        onSignedInChanged = { signedIn ->
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CLAUDE && current.provider.claudeAuthMode == ClaudeAuthMode.NATIVE_SUBSCRIPTION) {
                    current.copy(provider = current.provider.copy(hasSecret = signedIn))
                } else current
            }
        },
        onAuthUrlDiscovered = { url ->
            openExternalUrlOnce(url)
        },
        onLoginCompleted = { adoptClaudeAccountLogin() },
    )
    val codexAuthController = CodexAuthController(
        context = application,
        onSignedInChanged = { signedIn ->
            preferences.codexSignedIn = signedIn
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CHATGPT) {
                    current.copy(provider = current.provider.copy(hasSecret = signedIn))
                } else current
            }
        },
        onLoginCompleted = { adoptCodexAccountLogin() },
    )
    private var lastAutoOpenedUrl: String? = null
    private var lastAutoOpenedAtMillis = 0L
    @Volatile private var signInAfterClaudeInstall = false
    @Volatile private var signInAfterCodexInstall = false
    private var lastClaudeStatusCheckAtMillis = 0L
    private val _state = MutableStateFlow(
        AppUiState(
            onboardingComplete = preferences.onboardingComplete,
            backgroundSetupComplete = preferences.backgroundSetupComplete,
            agentKind = initialAgentKind,
            primaryAgentKind = initialPrimaryAgentKind,
            provider = preferences.loadProvider(vault, initialAgentKind),
            activeApiKeyName = vault.list(preferences.loadProvider(vault, initialAgentKind).kind.name)
                .firstOrNull(ApiKeyInfo::isActive)?.name,
            antigravityAuth = AntigravityAuthState(
                status = if (preferences.antigravitySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
                message = preferences.antigravityAccountEmail.takeIf(String::isNotBlank)?.let { "Connected as $it" },
                accountEmail = preferences.antigravityAccountEmail.takeIf(String::isNotBlank),
            ),
            antigravityAccounts = antigravityAccountManager.accountsList(),
            claudeAuthMode = preferences.claudeAuthMode,
            antigravityLoadBalancingStrategy = preferences.antigravityLoadBalancingStrategy,
            antigravityFailoverEnabled = preferences.antigravityFailoverEnabled,
            antigravityModel = preferences.antigravityModel,
            antigravityEffort = preferences.antigravityEffort,
            claudeModel = preferences.claudeModel,
            claudeThinkingLevel = preferences.claudeThinkingLevel,
            codexReasoningEffort = preferences.codexReasoningEffort,
            dshReasoningEffort = preferences.dshReasoningEffort,
            themeMode = runCatching { com.jarves.mh.ui.theme.AppThemeMode.valueOf(preferences.themeMode.uppercase()) }
                .getOrDefault(com.jarves.mh.ui.theme.AppThemeMode.DARK),
            projects = preferences.loadProjects(),
            githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
            githubLogin = preferences.githubLogin.takeIf(String::isNotBlank),
            selectedDevStacks = preferences.selectedDevStacks.mapNotNull { name ->
                runCatching { DevStack.valueOf(name) }.getOrNull()
            }.toSet() + DevStack.WEB,
        ),
    )

    init {
        // GitHub's official CLI owns its OAuth credential. Remove credentials from
        // the retired custom OAuth implementation and discover the real CLI status.
        vault.remove(LEGACY_GITHUB_TOKEN_KEY)
        viewModelScope.launch { refreshGitHubConnection() }
        RuntimeSetupController.restore(application)
        val taskSupervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(application)
        viewModelScope.launch {
            taskSupervisor.activeTasks.collect { activeMap ->
                val current = _state.value
                val tracked = activeUiTaskId
                if (tracked != null) {
                    taskSupervisor.stateStore.get(tracked)?.takeIf { it.status.isTerminal }?.let {
                        finishSupervisedTask(tracked)
                    }
                } else {
                    // Reattach after Activity/ViewModel recreation only to this chat and engine.
                    val active = activeMap.values.firstOrNull {
                        it.projectId == current.activeProject?.id && it.chatId == (current.activeChatId ?: "default") &&
                            it.agentKind == current.agentKind.name && it.status.isActive
                    }
                    if (active != null) {
                        activeUiTaskId = active.taskId
                        _state.update { it.copy(isRunning = true, activeSessionId = active.sessionId) }
                    }
                }
            }
        }
        viewModelScope.launch { dshRuntime.events.collect { onRuntimeEvent(it, AgentKind.DEEPSEEK_HARNESS) } }
        viewModelScope.launch { antigravityRuntime.events.collect { onRuntimeEvent(it, AgentKind.ANTIGRAVITY) } }
        viewModelScope.launch { codexRuntime.events.collect { onRuntimeEvent(it, AgentKind.CODEX) } }
        viewModelScope.launch {
            antigravityAuthController.state.collect { auth ->
                _state.update { it.copy(antigravityAuth = auth) }
                auth.authorizationUrl?.takeIf { it != lastOpenedAntigravityAuthUrl }?.let { url ->
                    lastOpenedAntigravityAuthUrl = url
                    runCatching {
                        getApplication<Application>().startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the sign-in URL instead.") }
                    }
                }
            }
        }
        viewModelScope.launch {
            antigravityAccountManager.accounts.collect { accounts ->
                _state.update { current ->
                    current.copy(
                        antigravityAccounts = accounts,
                        antigravityAuth = if (accounts.isNotEmpty()) {
                            val primary = accounts.firstOrNull { acc -> acc.isPrimary } ?: accounts.first()
                            current.antigravityAuth.copy(
                                status = AntigravityAuthStatus.SIGNED_IN,
                                message = "Connected as ${primary.email}",
                                accountEmail = primary.email,
                            )
                        } else current.antigravityAuth,
                    )
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { antigravityAccountManager.refreshAllAccountQuotas() }
        }
        viewModelScope.launch(Dispatchers.IO) {
            browserBridge.watch(this)
        }
        viewModelScope.launch {
            claudeAuthController.state.collect { auth ->
                _state.update { it.copy(claudeAuth = auth) }
                syncClaudePingFromAuth()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { claudeAuthController.queryAuthStatus() }
        }
        viewModelScope.launch {
            codexAuthController.state.collect { auth ->
                _state.update { it.copy(codexAuth = auth) }
                syncCodexPingFromAuth()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { codexAuthController.refreshStatus() }
        }
        if (antigravityAuthController.hasOfficialCredential() &&
            (!preferences.antigravitySignedIn || preferences.antigravityAccountEmail.isBlank())
        ) {
            viewModelScope.launch { antigravityAuthController.beginLogin() }
        }
        if (!preferences.legacySeededCredentialRemoved) {
            vault.remove(ProviderKind.CUSTOM.name)
            preferences.legacySeededCredentialRemoved = true
            _state.update { current ->
                if (current.provider.kind == ProviderKind.CUSTOM) {
                    current.copy(provider = current.provider.copy(hasSecret = false))
                } else current
            }
        }
        if (
            BuildConfig.TEST_OPENROUTER_API_KEY.isNotBlank() &&
            preferences.testProviderDefaultsVersion < TEST_PROVIDER_DEFAULTS_VERSION
        ) {
            val testProvider = ProviderProfile(
                kind = ProviderKind.CUSTOM,
                baseUrl = TEST_OPENROUTER_BASE_URL,
                model = TEST_OPENROUTER_MODEL,
                hasSecret = true,
            )
            vault.put(ProviderKind.CUSTOM.name, BuildConfig.TEST_OPENROUTER_API_KEY)
            preferences.saveProvider(testProvider, _state.value.agentKind)
            preferences.testProviderDefaultsVersion = TEST_PROVIDER_DEFAULTS_VERSION
            _state.update { it.copy(provider = testProvider) }
        }

        vault.purgeRevokedProviders()
        val customKeys = vault.list(ProviderKind.CUSTOM.name)
        val hasCustomKey = customKeys.isNotEmpty() && vault.get(ProviderKind.CUSTOM.name)?.isNotBlank() == true
        val currentProvider = _state.value.provider
        val isProviderRevoked = com.jarves.mh.provider.isRevokedProvider(currentProvider.baseUrl) ||
            com.jarves.mh.provider.isRevokedProvider(currentProvider.model)
        val providerHasRevokedDrift = isProviderRevoked ||
            com.jarves.mh.provider.isRevokedProvider(_state.value.activeApiKeyName.orEmpty()) ||
            preferences.hasRevokedProviderDrift()
        if (providerHasRevokedDrift && (!hasCustomKey || isProviderRevoked)) {
            preferences.purgeRevokedProviders()
            val cleaned = currentProvider.copy(
                baseUrl = currentProvider.kind.defaultBaseUrl,
                model = currentProvider.kind.defaultModel,
                hasSecret = false,
                profileId = "",
            )
            AgentKind.entries.forEach { agent ->
                preferences.saveProvider(cleaned, agent)
            }
            _state.update {
                it.copy(
                    provider = cleaned,
                    activeApiKeyName = null,
                    apiPingMessage = if (com.jarves.mh.provider.isRevokedProvider(it.apiPingMessage.orEmpty())) null else it.apiPingMessage,
                    apiPingStatus = if (com.jarves.mh.provider.isRevokedProvider(it.apiPingMessage.orEmpty())) ApiPingStatus.IDLE else it.apiPingStatus,
                )
            }
        }
        if (com.jarves.mh.provider.isRevokedProvider(preferences.claudeModel)) {
            preferences.claudeModel = "default"
            _state.update { it.copy(claudeModel = "default") }
        }

        val loadedProjects = preferences.loadProjects()
        val cleanedProjects = loadedProjects.filter { project ->
            if (project.kind == ProjectKind.QUICK_PROJECT) {
                val workspaceDir = File(application.filesDir, "workspaces/${project.id}")
                val userFiles = if (workspaceDir.isDirectory) {
                    workspaceDir.walkTopDown().filter { file ->
                        file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                    }.count()
                } else 0
                // Removed only when every chat file on disk is an empty list; any unreadable or non-empty file keeps it.
                val keep = userFiles > 0 || !preferences.hasNoChatMessages(project.id)
                if (!keep) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(project.id).delete()
                    preferences.deleteProjectChats(project.id)
                }
                keep
            } else true
        }
        if (cleanedProjects.size != loadedProjects.size) {
            preferences.saveProjects(cleanedProjects)
            _state.update { it.copy(projects = cleanedProjects) }
        }
    }

    val state: StateFlow<AppUiState> = _state.asStateFlow()

    private val _terminalLines = MutableStateFlow<List<TerminalOutputLine>>(
        listOf(
            TerminalOutputLine(
                command = "uname -a",
                output = "Linux pocket-dev 6.1.0-arm64 #1 SMP aarch64 GNU/Linux (PRoot Sandbox)",
                exitCode = 0,
            ),
        ),
    )
    val terminalLines: StateFlow<List<TerminalOutputLine>> = _terminalLines.asStateFlow()

    private val _isTerminalRunning = MutableStateFlow(false)
    val isTerminalRunning: StateFlow<Boolean> = _isTerminalRunning.asStateFlow()

    private val _terminalLiveOutput = MutableStateFlow("")
    val terminalLiveOutput: StateFlow<String> = _terminalLiveOutput.asStateFlow()

    private val _terminalCurrentCommand = MutableStateFlow<String?>(null)
    val terminalCurrentCommand: StateFlow<String?> = _terminalCurrentCommand.asStateFlow()

    fun runTerminalCommand(cmd: String) {
        val command = cmd.trim()
        if (command.isBlank() || _isTerminalRunning.value) return
        if (command == "clear") {
            _terminalLines.value = emptyList()
            return
        }
        _isTerminalRunning.value = true
        _terminalCurrentCommand.value = command
        _terminalLiveOutput.value = ""
        viewModelScope.launch {
            val (output, exitCode) = withContext(Dispatchers.IO) {
                runCatching {
                    if (!installer.isInstalled()) {
                        return@runCatching "Linux environment is not ready yet." to 1
                    }
                    val runtime = installer.installedRuntime()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/terminal").apply { mkdirs() }
                    val preparedCommand = prepareInteractiveShellCommand(command)
                    val proc = installer.process(
                        proot = runtime.proot,
                        rootfs = runtime.rootfs,
                        workspace = workspace,
                        environment = emptyMap(),
                        guestCommand = listOf("/usr/bin/bash", "-c", preparedCommand),
                    )
                    terminalProcess = proc
                    val native = proc as? NativeSpawnProcess
                    var offset = 0L
                    val streamed = StringBuilder()
                    var autoConfirmed = false
                    while (proc.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                        val file = native?.outputFile
                        val available = (file?.length() ?: 0L) - offset
                        if (file == null || available <= 0) {
                            Thread.sleep(50)
                            continue
                        }
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        val count = RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            input.read(bytes)
                        }
                        if (count > 0) {
                            offset += count
                            streamed.append(bytes.decodeToString(0, count))
                            _terminalLiveOutput.value = sanitizeTerminalOutput(streamed.toString())
                                .trimEnd()
                                .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                            if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, streamed.toString())) {
                                proc.outputStream.write("y\n".toByteArray())
                                proc.outputStream.flush()
                                autoConfirmed = true
                            }
                        }
                    }
                    val exit = proc.waitFor()
                    runCatching { proc.outputStream.close() }
                    val out = sanitizeTerminalOutput(streamed.toString()).trim()
                    val finalOut = if (out.isNotEmpty() || exit == 0) out else "Process exited with code $exit"
                    finalOut to exit
                }.getOrElse { "Error: ${it.message}" to 1 }
            }
            _terminalLines.update { it + TerminalOutputLine(command = command, output = output, exitCode = exitCode) }
            _terminalLiveOutput.value = ""
            _terminalCurrentCommand.value = null
            _isTerminalRunning.value = false
            terminalProcess = null
        }
    }

    fun sendTerminalInput(text: String) {
        sendProcessInput(terminalProcess, text)
    }

    fun interruptTerminalCommand() {
        interruptProcess(terminalProcess)
    }

    fun clearTerminal() {
        _terminalLines.value = emptyList()
    }

    fun requestProjectTerminalCommand(command: String) {
        val normalized = command.trim()
        if (normalized.isBlank() || _state.value.projectTerminalRunning || projectTerminalProcess?.isAlive == true) return
        if (requiresAndroidToolchain(normalized) && !installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        if (isDestructiveTerminalCommand(normalized)) {
            _state.update { it.copy(pendingTerminalCommand = normalized) }
        } else {
            runProjectTerminalCommand(normalized)
        }
    }

    fun prepareProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (command.isBlank() || _state.value.projectTerminalRunning) return
        _state.update {
            it.copy(
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalDraft = command.trim(),
            )
        }
    }

    fun consumeProjectTerminalDraft() {
        _state.update { it.copy(projectTerminalDraft = null) }
    }

    fun openProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalCwd = projectGuestRoot(project)) }
    }

    fun confirmProjectTerminalCommand() {
        val command = _state.value.pendingTerminalCommand ?: return
        _state.update { it.copy(pendingTerminalCommand = null) }
        runProjectTerminalCommand(command)
    }

    fun cancelProjectTerminalCommand() {
        _state.update { it.copy(pendingTerminalCommand = null) }
    }

    private fun runProjectTerminalCommand(command: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        val startingCwd = _state.value.projectTerminalCwd
        val existingLines = _state.value.projectTerminalLines
        projectTerminalStopRequested = false
        val requestedPreviewUrl = detectServerUrl(command)
        _state.update {
            it.copy(
                projectTerminalRunning = true,
                projectTerminalLiveOutput = "",
                projectTerminalCommand = command,
                pendingTerminalCommand = null,
                previewReady = it.previewReady || requestedPreviewUrl != null,
                previewUrl = requestedPreviewUrl ?: it.previewUrl,
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { runProjectTerminalProcess(project.id, command, startingCwd) }
                    .getOrElse { error ->
                        ProjectTerminalResult(
                            output = "Terminal error: ${error.message ?: error::class.java.simpleName}",
                            exitCode = 1,
                            cwd = startingCwd,
                        )
                    }
            }
            val completedLine = TerminalOutputLine(
                command = command,
                output = result.output.ifBlank {
                    if (result.exitCode == 0) "" else "Process exited with code ${result.exitCode}"
                },
                exitCode = result.exitCode,
            )
            val updatedLines = (existingLines + completedLine).takeLast(MAX_PROJECT_TERMINAL_HISTORY)
            saveProjectTerminal(project.id, result.cwd, updatedLines)
            if (_state.value.activeProject?.id == project.id) {
                _state.update {
                    it.copy(
                        projectTerminalLines = updatedLines,
                        projectTerminalLiveOutput = "",
                        projectTerminalRunning = false,
                        projectTerminalCwd = result.cwd,
                        projectTerminalCommand = null,
                    )
                }
                refreshProjectFiles()
            }
            projectTerminalProcess = null
            projectTerminalProjectId = null
            projectTerminalStopRequested = false
        }
    }

    fun stopProjectTerminalCommand() {
        if (!_state.value.projectTerminalRunning) return
        projectTerminalStopRequested = true
        viewModelScope.launch(Dispatchers.IO) {
            projectTerminalProcess?.destroy()
            delay(400)
            if (projectTerminalProcess?.isAlive == true) projectTerminalProcess?.destroyForcibly()
        }
    }

    fun sendProjectTerminalInput(text: String) {
        sendProcessInput(projectTerminalProcess, text)
    }

    fun interruptProjectTerminalCommand() {
        interruptProcess(projectTerminalProcess)
    }

    fun clearProjectTerminal() {
        val project = _state.value.activeProject ?: return
        if (_state.value.projectTerminalRunning) return
        _state.update { it.copy(projectTerminalLines = emptyList(), projectTerminalLiveOutput = "") }
        saveProjectTerminal(project.id, _state.value.projectTerminalCwd, emptyList())
    }

    private fun runProjectTerminalProcess(projectId: String, command: String, cwd: String): ProjectTerminalResult {
        if (!installer.isInstalled()) return ProjectTerminalResult("Linux environment is not ready yet.", 1, cwd)
        val installed = installer.installedRuntime()
        val project = _state.value.projects.firstOrNull { it.id == projectId }
            ?: _state.value.activeProject?.takeIf { it.id == projectId }
            ?: return ProjectTerminalResult("Project is no longer available.", 1, cwd)
        val workspace = projectWorkspaceRoot(project)
        val guestWorkspacePath = projectGuestRoot(project)
        val marker = "__POCKETDEV_CWD_${UUID.randomUUID()}__"
        val preparedCommand = prepareInteractiveShellCommand(command)
        val script = """
            cd -- ${shellQuote(cwd)} || exit 1
            $preparedCommand
            pocket_status=${'$'}?
            printf '\n$marker%s\n' "${'$'}PWD"
            exit ${'$'}pocket_status
        """.trimIndent()
        val process = installer.process(
            proot = installed.proot,
            rootfs = installed.rootfs,
            workspace = workspace,
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/bash", "-lc", script),
            guestWorkspacePath = guestWorkspacePath,
        )
        projectTerminalProcess = process
        projectTerminalProjectId = projectId
        if (projectTerminalStopRequested) process.destroy()
        val native = process as? NativeSpawnProcess
            ?: return ProjectTerminalResult("Unsupported terminal process.", 1, cwd)
        var offset = 0L
        val output = StringBuilder()
        var autoConfirmed = false
        while (process.isAlive || native.outputFile.length() > offset) {
            val available = native.outputFile.length() - offset
            if (available <= 0) {
                Thread.sleep(50)
                continue
            }
            val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
            val count = RandomAccessFile(native.outputFile, "r").use { file ->
                file.seek(offset)
                file.read(bytes)
            }
            if (count > 0) {
                offset += count
                output.append(bytes.decodeToString(0, count))
                val visible = sanitizeTerminalOutput(output.toString().substringBefore(marker))
                    .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
                if (!autoConfirmed && shouldAutoConfirmPackageCommand(command, visible)) {
                    process.outputStream.write("y\n".toByteArray())
                    process.outputStream.flush()
                    autoConfirmed = true
                }
                val detectedPreviewUrl = detectPreviewUrl(visible)
                _state.update { current ->
                    if (current.activeProject?.id == projectId) {
                        current.copy(
                            projectTerminalLiveOutput = visible,
                            previewReady = current.previewReady || detectedPreviewUrl != null,
                            previewUrl = detectedPreviewUrl ?: current.previewUrl,
                        )
                    } else current
                }
            }
        }
        val exitCode = process.waitFor()
        runCatching { process.outputStream.close() }
        val raw = output.toString()
        val cwdAfter = raw.substringAfter(marker, "")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it == guestWorkspacePath || it.startsWith("$guestWorkspacePath/") }
            ?: cwd
        val cleanOutput = sanitizeTerminalOutput(raw.substringBefore(marker))
            .trim()
            .takeLast(MAX_PROJECT_TERMINAL_OUTPUT)
        return ProjectTerminalResult(cleanOutput, exitCode, cwdAfter)
    }

    private fun sendProcessInput(process: Process?, text: String) {
        if (process?.isAlive != true || text.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                process.outputStream.write((text + "\n").toByteArray())
                process.outputStream.flush()
            }.onFailure {
                _state.update { current -> current.copy(toastMessage = "This process is no longer accepting input.") }
            }
        }
    }

    private fun interruptProcess(process: Process?) {
        if (process?.isAlive != true) return
        viewModelScope.launch(Dispatchers.IO) {
            (process as? NativeSpawnProcess)?.interrupt() ?: process.destroy()
        }
    }

    /**
     * Package management must never block on Y/N, locale, timezone, service-restart,
     * or config-file dialogs in the phone UI. Other commands remain interactive and
     * can receive input through [sendProcessInput].
     */
    private fun prepareInteractiveShellCommand(command: String): String {
        val normalizedApt = command
            .replace(Regex("(?<![\\w-])sudo\\s+apt(?:-get)?\\s+"), "apt-get ")
            .replace(Regex("(?<![\\w-])apt\\s+"), "apt-get ")
            .replace(
                Regex("(?<![\\w-])apt-get\\s+(install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|fix-broken)\\b"),
                "apt-get -y -o Dpkg::Options::=--force-confold $1",
            )
        return "export DEBIAN_FRONTEND=noninteractive APT_LISTCHANGES_FRONTEND=none UCF_FORCE_CONFFOLD=1 NEEDRESTART_MODE=a TZ=Etc/UTC LC_ALL=C.UTF-8; $normalizedApt"
    }

    private fun shouldAutoConfirmPackageCommand(command: String, output: String): Boolean {
        val packageCommand = Regex("(?i)(^|[;&|]\\s*)(sudo\\s+)?(apt|apt-get|dpkg)\\b").containsMatchIn(command)
        if (!packageCommand) return false
        val tail = output.takeLast(500)
        return Regex("(?i)(do you want to continue|continue\\?)\\s*\\[[Yy]/[Nn]\\]").containsMatchIn(tail)
    }

    private fun isDestructiveTerminalCommand(command: String): Boolean {
        val normalized = command.lowercase().replace(Regex("\\s+"), " ")
        return listOf(
            "rm -rf", "rm -fr", "git reset --hard", "git clean -f", "git push --force",
            "mkfs", "dd if=", "chmod -r 777", "shutdown", "reboot", ":(){", "kill \$(pgrep", "pkill -f",
        ).any(normalized::contains) || Regex("(curl|wget).*(\\||>)\\s*(sh|bash)").containsMatchIn(normalized)
    }

    private fun detectPreviewUrl(output: String): String? {
        return com.jarves.mh.runtime.LocalPreviewDiscovery.candidate(output)
    }

    private fun detectServerUrl(command: String): String? {
        val match = Regex("""python(?:3)?\s+-m\s+http\.server(?:\s+(\d{2,5}))?""")
            .find(command)
            ?: return null
        val port = match.groupValues.getOrNull(1)?.toIntOrNull() ?: 8000
        return port.takeIf { it in 1..65535 }?.let { "http://127.0.0.1:$it/" }
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun terminalHistoryFile(projectId: String): File =
        File(getApplication<Application>().filesDir, "terminal-history/$projectId.json")

    private fun loadProjectTerminal(project: Project): ProjectTerminalSnapshot {
        val file = terminalHistoryFile(project.id)
        val guestRoot = projectGuestRoot(project)
        if (!file.isFile) return ProjectTerminalSnapshot(cwd = guestRoot)
        return runCatching {
            val root = JSONObject(file.readText())
            val array = root.optJSONArray("lines") ?: JSONArray()
            val lines = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TerminalOutputLine(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    command = item.optString("command"),
                    output = item.optString("output"),
                    exitCode = item.optInt("exitCode"),
                )
            }
            ProjectTerminalSnapshot(
                lines = lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY),
                cwd = root.optString("cwd", guestRoot).takeIf {
                    it == guestRoot || it.startsWith("$guestRoot/")
                } ?: guestRoot,
            )
        }.getOrDefault(ProjectTerminalSnapshot(cwd = guestRoot))
    }

    private fun saveProjectTerminal(projectId: String, cwd: String, lines: List<TerminalOutputLine>) {
        runCatching {
            val file = terminalHistoryFile(projectId)
            file.parentFile?.mkdirs()
            val array = JSONArray()
            lines.takeLast(MAX_PROJECT_TERMINAL_HISTORY).forEach { line ->
                array.put(
                    JSONObject()
                        .put("id", line.id)
                        .put("command", line.command)
                        .put("output", line.output.takeLast(MAX_PROJECT_TERMINAL_OUTPUT))
                        .put("exitCode", line.exitCode),
                )
            }
            file.writeText(JSONObject().put("cwd", cwd).put("lines", array).toString())
        }
    }

    private fun projectGuestRoot(project: Project): String = "/workspace/${project.slug}"

    private fun projectWorkspaceRoot(project: Project): File {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
            .apply { mkdirs() }
            .canonicalFile
        if (project.rootPath.isBlank()) return base
        val selected = File(base, project.rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun buildAndRunAndroidApp() {
        val project = _state.value.activeProject ?: return
        if (_state.value.androidBuildRunning) return
        if (!installer.isStackInstalled(DevStack.ANDROID)) {
            _state.update {
                it.copy(toastMessage = "Android build tools are not installed. Add Android in Settings → Development stacks.")
            }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Wait for Claude to finish creating the project before building.") }
            return
        }
        if (_state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Wait for the project terminal command to finish before building.") }
            return
        }
        _state.update { it.copy(androidBuildRunning = true, androidBuildMessage = "Building debug APK…", toastMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val installed = installer.installedRuntime()
                val workspace = findAndroidGradleProjectRoot(projectWorkspaceRoot(project))
                    ?: error("No Android Gradle project found yet. Ask Claude to create it, then wait for the task to finish.")
                val process = installer.process(
                    installed.proot, installed.rootfs, workspace, emptyMap(),
                    listOf(
                        "/usr/bin/bash", "-lc",
                        "gradle --init-script /root/.gradle/init.d/pocketdev-android.gradle " +
                            "-Pandroid.aapt2FromMavenOverride=/root/android-sdk/build-tools/35.0.0/aapt2 " +
                            "--no-daemon assembleDebug --console=plain",
                    ),
                    projectGuestRoot(project),
                )
                val exitCode = process.waitFor()
                val buildOutput = (process as? NativeSpawnProcess)?.outputFile?.readText().orEmpty()
                check(exitCode == 0) {
                    buildOutput.trim().takeLast(2_000).ifBlank { "Gradle build failed (exit code $exitCode)" }
                }
                val apk = workspace.walkTopDown()
                    .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && it.path.contains("/outputs/apk/debug/") }
                    .maxByOrNull(File::lastModified)
                    ?: error("Gradle finished but no debug APK was found")
                AndroidAppInstaller.install(getApplication(), apk)
            }.onSuccess {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(
                            androidBuildRunning = false,
                            androidBuildMessage = "APK sent to Android installer",
                            toastMessage = "APK built. Complete Android's install prompt.",
                        )
                    }
                    refreshProjectFiles()
                }
            }.onFailure { error ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(androidBuildRunning = false, androidBuildMessage = null, toastMessage = error.message ?: "Could not build APK") }
                    refreshProjectFiles()
                }
            }
        }
    }

    private fun findAndroidGradleProjectRoot(workspace: File): File? {
        val settingsNames = setOf("settings.gradle", "settings.gradle.kts", "settings.gradle.dcl")
        return workspace.walkTopDown()
            .maxDepth(4)
            .filter { it.isFile && it.name in settingsNames }
            .mapNotNull(File::getParentFile)
            .sortedBy { it.absolutePath.length }
            .firstOrNull { root ->
                root.walkTopDown()
                    .maxDepth(4)
                    .any { it.isFile && it.invariantSeparatorsPath.endsWith("src/main/AndroidManifest.xml") }
            }
    }

    private fun requiresAndroidToolchain(command: String): Boolean =
        Regex("(?m)(^|[;&|]\\s*)(?:\\./)?gradle(?:w)?(?:\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(command)


    fun toggleTheme() {
        val next = if (_state.value.themeMode == com.jarves.mh.ui.theme.AppThemeMode.DARK) {
            com.jarves.mh.ui.theme.AppThemeMode.LIGHT
        } else {
            com.jarves.mh.ui.theme.AppThemeMode.DARK
        }
        setThemeMode(next)
    }

    fun setThemeMode(mode: com.jarves.mh.ui.theme.AppThemeMode) {
        preferences.themeMode = mode.name.lowercase()
        _state.update { it.copy(themeMode = mode) }
    }

    fun getSavedApiKey(kind: ProviderKind): String = vault.get(kind.name).orEmpty()

    fun getSavedApiKeys(kind: ProviderKind): List<ApiKeyInfo> = vault.list(kind.name)

    fun addApiKey(kind: ProviderKind, name: String, secret: String): List<ApiKeyInfo> {
        vault.add(kind.name, name, secret)
        if (kind == ProviderKind.CLAUDE) setClaudeAuthMode(ClaudeAuthMode.SETUP_TOKEN_LEGACY)
        val keys = vault.list(kind.name)
        refreshActiveApiKey(kind)
        return keys
    }

    fun activateApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        vault.activate(kind.name, keyId)
        if (kind == ProviderKind.CLAUDE) setClaudeAuthMode(ClaudeAuthMode.SETUP_TOKEN_LEGACY)
        refreshActiveApiKey(kind)
        return vault.list(kind.name)
    }

    fun removeApiKey(kind: ProviderKind, keyId: String): List<ApiKeyInfo> {
        val keysBefore = vault.list(kind.name)
        val removedKey = keysBefore.firstOrNull { it.id == keyId }
        vault.remove(kind.name, keyId)
        val remaining = vault.list(kind.name)
        val wasRevoked = com.jarves.mh.provider.isRevokedProvider(removedKey?.name.orEmpty()) ||
            com.jarves.mh.provider.isRevokedProvider(_state.value.provider.baseUrl) ||
            com.jarves.mh.provider.isRevokedProvider(_state.value.provider.model) ||
            com.jarves.mh.provider.isRevokedProvider(_state.value.activeApiKeyName.orEmpty())
        if (wasRevoked || remaining.isEmpty()) {
            vault.purgeRevokedProviders()
            preferences.purgeRevokedProviders()
        }
        if (kind == ProviderKind.CLAUDE && remaining.isEmpty()) setClaudeAuthMode(ClaudeAuthMode.NATIVE_SUBSCRIPTION)
        refreshActiveApiKey(kind)
        return remaining
    }

    private fun refreshActiveApiKey(kind: ProviderKind) {
        val keys = vault.list(kind.name)
        val activeName = keys.firstOrNull(ApiKeyInfo::isActive)?.name
        val currentProvider = _state.value.provider
        val hasRevokedDrift = com.jarves.mh.provider.isRevokedProvider(currentProvider.baseUrl) ||
            com.jarves.mh.provider.isRevokedProvider(currentProvider.model) ||
            com.jarves.mh.provider.isRevokedProvider(_state.value.activeApiKeyName.orEmpty()) ||
            com.jarves.mh.provider.isRevokedProvider(activeName.orEmpty())
        val hasNoKeys = keys.isEmpty()
        val shouldReset = hasRevokedDrift || (hasNoKeys && currentProvider.kind == kind && kind == ProviderKind.CUSTOM)
        val updatedProvider = if (shouldReset) {
            currentProvider.copy(
                baseUrl = currentProvider.kind.defaultBaseUrl,
                model = currentProvider.kind.defaultModel,
                hasSecret = false,
                profileId = "",
            )
        } else if (currentProvider.kind == kind) {
            currentProvider.copy(hasSecret = keys.isNotEmpty())
        } else {
            val targetKeys = vault.list(currentProvider.secretId)
            currentProvider.copy(hasSecret = targetKeys.isNotEmpty())
        }
        if (shouldReset) {
            preferences.purgeRevokedProviders()
            AgentKind.entries.forEach { agent ->
                preferences.saveProvider(updatedProvider, agent)
            }
        } else {
            preferences.saveProvider(updatedProvider, _state.value.agentKind)
        }
        val cleanPingMessage = if (com.jarves.mh.provider.isRevokedProvider(_state.value.apiPingMessage.orEmpty())) null else _state.value.apiPingMessage
        val cleanPingStatus = if (cleanPingMessage == null && _state.value.apiPingMessage != null) ApiPingStatus.IDLE else _state.value.apiPingStatus
        _state.update { current ->
            current.copy(
                activeApiKeyName = if (shouldReset && (com.jarves.mh.provider.isRevokedProvider(activeName.orEmpty()) || hasNoKeys)) null else activeName,
                provider = updatedProvider,
                apiPingMessage = cleanPingMessage,
                apiPingStatus = cleanPingStatus,
            )
        }
    }

    /** Keeps both agent bridges mapped to the same workspace root; the active one is used. */
    private fun configureBridgeRoots(projectId: String, rootPath: String) {
        claudeRuntime.configureProjectRoot(projectId, rootPath)
        dshRuntime.configureProjectRoot(projectId, rootPath)
        antigravityRuntime.configureProjectRoot(projectId, rootPath)
        codexRuntime.configureProjectRoot(projectId, rootPath)
    }

    init {
        viewModelScope.launch { RuntimeSetupController.snapshot.collect(::onSetupSnapshot) }
        viewModelScope.launch { claudeRuntime.events.collect { onRuntimeEvent(it, AgentKind.CLAUDE_CODE) } }
        viewModelScope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        if (!supportsArm64Runtime(android.os.Build.SUPPORTED_ABIS, System.getProperty("os.arch"))) {
            _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "ARM64 device required",
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            return
        }
        val setupSnapshot = RuntimeSetupController.snapshot.value
        if (setupSnapshot.status == RuntimeSetupStatus.RUNNING) {
            onSetupSnapshot(setupSnapshot)
            resumeRuntimeSetupService()
            return
        }
        val installed = withContext(Dispatchers.IO) {
            // Upgrades from the old single-bundle layout keep every already-installed tool.
            installer.migrateLegacyToolMarkers()
            installer.isInstalled().also { ready ->
                if (ready) installer.cleanupLegacyWorkspaceScaffolding()
            }
        }
        _state.update { current ->
            current.copy(
                installedDevStacks = if (installed) installer.installedStacks() else current.installedDevStacks,
                installedAgentVersions = if (installed) installer.installedAgentVersions() else emptyMap(),
            )
        }
        when {
            !installed && setupSnapshot.status == RuntimeSetupStatus.ERROR -> onSetupSnapshot(setupSnapshot)
            !installed -> _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupProgress = 0f) }
            !preferences.onboardingComplete -> {
                preferences.runtimeSetupComplete = true
                _state.update { it.copy(startupStage = StartupStage.MODEL_SETUP, startupProgress = 1f) }
            }
            else -> initializeRuntime()
        }
    }

    fun startRuntimeSetup() {
        if (state.value.startupStage == StartupStage.INSTALLING) return
        setupCompletionHandled = false
        _state.update {
            it.copy(
                startupStage = StartupStage.INSTALLING,
                startupProgress = 0.01f,
                startupMessage = "Preparing your private coding workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Preparing your private coding workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
                showDetailedSetupProgress = true,
            )
        }
        resumeRuntimeSetupService()
    }

    fun retryStartup() {
        if (installer.isInstalled()) viewModelScope.launch { initializeRuntime() } else {
            _state.update { it.copy(startupStage = StartupStage.SETUP_REQUIRED, startupError = null) }
            startRuntimeSetup()
        }
    }

    private fun resumeRuntimeSetupService() {
        val stacks = _state.value.selectedDevStacks.joinToString(",") { it.name }
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, _state.value.agentKind.name),
        )
    }

    private fun onSetupSnapshot(snapshot: RuntimeSetupSnapshot) {
        when (snapshot.status) {
            RuntimeSetupStatus.RUNNING -> _state.update {
                it.copy(
                    startupStage = StartupStage.INSTALLING,
                    startupProgress = snapshot.progress,
                    startupMessage = snapshot.message,
                    startupBytes = snapshot.totalBytes?.let { total -> (snapshot.downloadedBytes ?: 0L) to total },
                    startupLogs = snapshot.logs,
                    startupIndeterminate = snapshot.indeterminate,
                    startupError = null,
                    startupErrorIsOffline = false,
                )
            }
            RuntimeSetupStatus.COMPLETE -> {
                if (setupCompletionHandled) return
                setupCompletionHandled = true
                preferences.runtimeSetupComplete = true
                if (preferences.onboardingComplete) {
                    viewModelScope.launch { initializeRuntime() }
                } else {
                    _state.update {
                        it.copy(
                            startupStage = StartupStage.MODEL_SETUP,
                            startupProgress = 1f,
                            startupBytes = null,
                            startupIndeterminate = false,
                        )
                    }
                }
            }
            RuntimeSetupStatus.ERROR -> _state.update {
                it.copy(
                    startupStage = StartupStage.ERROR,
                    startupMessage = snapshot.message,
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                    startupError = snapshot.errorMessage,
                    startupErrorIsOffline = snapshot.offline,
                )
            }
            RuntimeSetupStatus.CANCELLED -> _state.update {
                it.copy(
                    startupStage = StartupStage.SETUP_REQUIRED,
                    startupMessage = "Setup paused",
                    startupProgress = snapshot.progress,
                    startupLogs = snapshot.logs,
                    startupIndeterminate = false,
                )
            }
            RuntimeSetupStatus.IDLE -> Unit
        }
    }

    private suspend fun initializeRuntime() {
        val startedAt = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                startupStage = StartupStage.INITIALIZING,
                startupProgress = 0.05f,
                startupMessage = "Opening your private workspace",
                startupBytes = null,
                startupLogs = listOf("\$ Opening your private workspace"),
                startupIndeterminate = false,
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
        val result = runCatching {
            withContext(Dispatchers.IO) {
                installer.initializeExisting { progress ->
                    _state.update { current ->
                        current.copy(
                            startupProgress = 0.05f + progress.fraction * 0.95f,
                            startupMessage = progress.message,
                            startupBytes = null,
                            startupLogs = mergeStartupLog(current.startupLogs, progress),
                        )
                    }
                }
            }
        }
        if (result.isSuccess) {
            // The real version probe can finish in a fraction of a second on fast phones.
            // Keep the successful loading state visible long enough to be understandable.
            val remaining = MINIMUM_INITIALIZATION_SCREEN_MS - (SystemClock.elapsedRealtime() - startedAt)
            if (remaining > 0) delay(remaining)
            _state.update {
                it.copy(
                    startupStage = StartupStage.READY,
                    startupProgress = 1f,
                    installedAgentVersions = installer.installedAgentVersions(),
                )
            }
            pingApi()
            checkForAppUpdate()
        } else {
            showStartupError(result.exceptionOrNull() ?: IllegalStateException("Claude Code initialization failed"))
        }
    }

    fun checkForAppUpdate(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - preferences.lastAppUpdateCheckMillis < 24L * 60L * 60L * 1000L) return
        viewModelScope.launch(Dispatchers.IO) {
            val update = runCatching { appUpdater().check() }.getOrNull()
            preferences.lastAppUpdateCheckMillis = System.currentTimeMillis()
            if (update != null) {
                _state.update {
                    it.copy(appUpdate = update, appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null)
                }
            }
        }
    }

    /** Debug builds only: persist a manifest URL override and re-check immediately. */
    fun setDebugUpdateManifestUrl(url: String) {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = url.trim()
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: clear the manifest URL override and re-check the default channel. */
    fun clearDebugUpdateManifestUrl() {
        if (!BuildConfig.DEBUG) return
        preferences.debugUpdateManifestUrl = ""
        preferences.lastAppUpdateCheckMillis = 0L
        checkForAppUpdate(force = true)
    }

    /** Debug builds only: the currently-active manifest URL override (empty = default). */
    fun debugUpdateManifestUrl(): String = if (BuildConfig.DEBUG) preferences.debugUpdateManifestUrl else ""

    fun installAppUpdate() {
        val info = _state.value.appUpdate ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getApplication<Application>().packageManager.canRequestPackageInstalls()) {
            _state.update { it.copy(appUpdateStatus = AppUpdateStatus.PERMISSION_REQUIRED) }
            return
        }
        if (_state.value.appUpdateStatus == AppUpdateStatus.DOWNLOADING) return
        _state.update {
            it.copy(appUpdateStatus = AppUpdateStatus.DOWNLOADING, appUpdateDownloadedBytes = 0L, appUpdateTotalBytes = info.sizeBytes, appUpdateError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                appUpdater().download(info) { downloaded, total ->
                    _state.update { current -> current.copy(appUpdateDownloadedBytes = downloaded, appUpdateTotalBytes = total) }
                }
            }.onSuccess { apk ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.INSTALLING) }
                runCatching { AndroidAppInstaller.install(getApplication(), apk) }.onFailure { error ->
                    _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Could not start the Android installer") }
                }
            }.onFailure { error ->
                _state.update { it.copy(appUpdateStatus = AppUpdateStatus.ERROR, appUpdateError = error.message ?: "Update download failed") }
            }
        }
    }

    fun dismissAppUpdateError() {
        _state.update { it.copy(appUpdateStatus = AppUpdateStatus.AVAILABLE, appUpdateError = null) }
    }

    private fun mergeStartupLog(
        existing: List<String>,
        progress: RuntimeInstallProgress,
    ): List<String> {
        val prefix = "\$ ${progress.message}"
        val bytes = progress.totalBytes?.let { total ->
            val downloaded = progress.downloadedBytes ?: 0L
            " — %.1f / %.1f MB".format(downloaded / 1_048_576.0, total / 1_048_576.0)
        }.orEmpty()
        val nextLine = prefix + bytes
        val updated = if (existing.lastOrNull()?.startsWith(prefix) == true) {
            existing.dropLast(1) + nextLine
        } else {
            existing + nextLine
        }
        return updated.takeLast(80)
    }

    private fun showStartupError(error: Throwable) {
        val isOffline = generateSequence(error as Throwable?) { it.cause }
            .any { cause ->
                cause is UnknownHostException ||
                    cause.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                    cause.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
            }
        val message = if (isOffline) {
            "Connect to Wi-Fi or mobile data, then try again. Internet is required to finish the first-time setup."
        } else {
            error.message?.take(300) ?: "Something went wrong while preparing Mobile Harness. Please try again."
        }
        _state.update {
            it.copy(
                startupStage = StartupStage.ERROR,
                startupError = message,
                startupErrorIsOffline = isOffline,
            )
        }
    }

    fun finishOnboarding(profile: ProviderProfile, secret: String) {
        val isClaudeNative = profile.kind == ProviderKind.CLAUDE &&
            profile.claudeAuthMode == ClaudeAuthMode.NATIVE_SUBSCRIPTION
        if (!isClaudeNative && secret.isNotBlank()) {
            vault.put(profile.secretId, secret)
        }
        val hasSecret = secret.isNotBlank() || vault.contains(profile.secretId) ||
            (profile.kind == ProviderKind.ANTIGRAVITY_SERVER && (antigravityAccountManager.accountsList().isNotEmpty() || antigravityAuthController.hasOfficialCredential())) ||
            (isClaudeNative && (claudeAuthController.hasNativeCredentials() || _state.value.claudeAuth.status == ClaudeAuthStatusState.SIGNED_IN)) ||
            (profile.kind == ProviderKind.CHATGPT && codexAuthController.hasCredentials())
        val saved = profile.copy(
            hasSecret = hasSecret,
        )
        preferences.saveProvider(saved, _state.value.agentKind)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = saved, startupStage = StartupStage.READY) }
        refreshActiveApiKey(profile.kind)
        pingApi()
    }

    fun finishClaudeOnboarding() {
        // Persists a clean Claude subscription profile (account mode) for Claude Code.
        adoptClaudeAccountLogin()
        val profile = preferences.loadProvider(vault, AgentKind.CLAUDE_CODE)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = profile, startupStage = StartupStage.READY) }
    }

    fun finishCodexOnboarding() {
        // Persists a clean ChatGPT-account profile for Codex.
        adoptCodexAccountLogin()
        val profile = preferences.loadProvider(vault, AgentKind.CODEX)
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, provider = profile, startupStage = StartupStage.READY) }
    }

    fun finishAntigravityOnboarding() {
        check(_state.value.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN) {
            "Sign in to Antigravity first"
        }
        preferences.onboardingComplete = true
        _state.update { it.copy(onboardingComplete = true, startupStage = StartupStage.READY) }
    }

    /** Lets first-run users escape a provider/login failure without losing saved credentials. */
    fun chooseOnboardingAgent(kind: AgentKind) {
        selectAgent(kind)
        _state.update {
            it.copy(
                startupStage = if (installer.isAgentInstalled(kind)) {
                    StartupStage.MODEL_SETUP
                } else {
                    StartupStage.SETUP_REQUIRED
                },
                startupError = null,
                startupErrorIsOffline = false,
            )
        }
    }

    fun updateProvider(profile: ProviderProfile, secret: String) = finishOnboarding(profile, secret)

    fun finishBackgroundSetup() {
        preferences.backgroundSetupComplete = true
        _state.update { it.copy(backgroundSetupComplete = true) }
    }

    /** Called from the first-launch setup screen; persists the agent choice for setup and Settings. */
    fun selectAgent(kind: AgentKind) {
        if (_state.value.agentKind == kind) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        val selectingInitialAgent = !preferences.runtimeSetupComplete
        preferences.agentKind = kind.stableId
        if (selectingInitialAgent) preferences.primaryAgentKind = kind.stableId
        _state.update { current ->
            preferences.saveProvider(current.provider, current.agentKind)
            val provider = preferences.loadProvider(vault, kind)
            current.copy(
                agentKind = kind,
                primaryAgentKind = if (selectingInitialAgent) kind else current.primaryAgentKind,
                provider = provider,
                activeApiKeyName = vault.list(provider.secretId).firstOrNull(ApiKeyInfo::isActive)?.name,
                // Ping results belong to the previous agent; never leak them across.
                apiPingStatus = ApiPingStatus.IDLE,
                usage = com.jarves.mh.model.AgentUsage(),
                apiPingMessage = null,
            )
        }
    }

    /** Installs the other agent on demand (Settings) with live progress, then switches to it. */
    fun installAgent(kind: AgentKind) {
        if (_state.value.agentInstalling != null) return
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current agent before switching.") }
            return
        }
        if (installer.isAgentInstalled(kind)) {
            selectAgent(kind)
            return
        }
        _state.update {
            it.copy(
                agentInstalling = kind,
                agentMessage = "Preparing ${kind.title}…",
                agentProgress = 0f,
                agentDownloadedBytes = null,
                agentTotalBytes = null,
                agentBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    agentRegistry.require(kind).install(installer) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentBytesPerSecond
                        _state.update { current ->
                            current.copy(
                                agentMessage = progress.message,
                                agentProgress = progress.fraction.coerceIn(0f, 1f),
                                agentDownloadedBytes = bytes ?: current.agentDownloadedBytes,
                                agentTotalBytes = progress.totalBytes ?: current.agentTotalBytes,
                                agentBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            result.onSuccess {
                if (kind == AgentKind.DEEPSEEK_HARNESS) preferences.dshVersion = installer.dshVersion
                selectAgent(kind)
                if (kind == AgentKind.CLAUDE_CODE && signInAfterClaudeInstall) {
                    signInAfterClaudeInstall = false
                    viewModelScope.launch { claudeAuthController.beginLogin() }
                }
                if (kind == AgentKind.CODEX && signInAfterCodexInstall) {
                    signInAfterCodexInstall = false
                    viewModelScope.launch { codexAuthController.beginLogin() }
                }
            }
            if (result.isFailure) {
                signInAfterClaudeInstall = false
                signInAfterCodexInstall = false
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentInstalling = null,
                    agentProgress = 0f,
                    agentDownloadedBytes = null,
                    agentTotalBytes = null,
                    agentBytesPerSecond = null,
                    agentMessage = result.fold(
                        onSuccess = { "${kind.title} is ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${kind.title}" },
                    ),
                )
            }
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.agentUpdatesChecking || _state.value.agentUpdating != null || _state.value.isRunning) return
        _state.update { it.copy(agentUpdatesChecking = true, agentUpdateMessage = "Checking official agent releases…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    agentUpdates = result.getOrDefault(emptyMap()),
                    agentUpdatesChecking = false,
                    agentUpdateMessage = result.fold(
                        onSuccess = { updates -> if (updates.isEmpty()) "All installed agents are up to date" else "${updates.size} agent update${if (updates.size == 1) "" else "s"} available" },
                        onFailure = { error -> error.message?.take(200) ?: "Could not check agent updates" },
                    ),
                )
            }
        }
    }

    fun updateAgent(kind: AgentKind) {
        val update = _state.value.agentUpdates[kind] ?: return
        if (_state.value.agentUpdating != null || _state.value.agentInstalling != null || _state.value.isRunning) return
        _state.update {
            it.copy(
                agentUpdating = kind,
                agentUpdateMessage = "Preparing ${kind.title} ${update.latestVersion}…",
                agentUpdateProgress = 0f,
                agentUpdateDownloadedBytes = null,
                agentUpdateTotalBytes = null,
                agentUpdateBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleAt = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.updateAgent(kind, update.latestVersion) { progress ->
                        val now = SystemClock.elapsedRealtime()
                        val bytes = progress.downloadedBytes
                        val elapsed = now - sampleAt
                        val speed = if (bytes != null && elapsed >= 500L) {
                            ((bytes - sampleBytes).coerceAtLeast(0L) * 1_000L / elapsed.coerceAtLeast(1L)).also {
                                sampleBytes = bytes
                                sampleAt = now
                            }
                        } else _state.value.agentUpdateBytesPerSecond
                        _state.update {
                            it.copy(
                                agentUpdateMessage = progress.message,
                                agentUpdateProgress = progress.fraction.coerceIn(0f, 1f),
                                agentUpdateDownloadedBytes = bytes ?: it.agentUpdateDownloadedBytes,
                                agentUpdateTotalBytes = progress.totalBytes ?: it.agentUpdateTotalBytes,
                                agentUpdateBytesPerSecond = speed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    installedAgentVersions = installer.installedAgentVersions(),
                    agentUpdates = if (result.isSuccess) current.agentUpdates - kind else current.agentUpdates,
                    agentUpdating = null,
                    agentUpdateMessage = result.fold(
                        onSuccess = { "${kind.title} updated to ${update.latestVersion}" },
                        onFailure = { error -> error.message?.take(220) ?: "Could not update ${kind.title}" },
                    ),
                    agentUpdateProgress = if (result.isSuccess) 1f else 0f,
                    agentUpdateDownloadedBytes = null,
                    agentUpdateTotalBytes = null,
                    agentUpdateBytesPerSecond = null,
                )
            }
        }
    }

    fun startAntigravityLogin() {
        if (_state.value.agentInstalling != null || _state.value.isRunning) return
        lastOpenedAntigravityAuthUrl = null
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            installAgent(AgentKind.ANTIGRAVITY)
            return
        }
        viewModelScope.launch { antigravityAuthController.beginLogin() }
    }

    fun submitAntigravityCode(code: String) {
        runCatching { antigravityAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit the code") } }
    }

    fun logoutAntigravity(accountId: String? = null) {
        viewModelScope.launch {
            runCatching { antigravityAuthController.logout(accountId) }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not sign out") } }
        }
    }

    fun startClaudeLogin() {
        if (claudeAuthController.isLoginInFlight) return
        if (_state.value.agentInstalling != null) {
            _state.update { it.copy(toastMessage = "Wait for the current install to finish, then sign in.") }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current task before signing in.") }
            return
        }
        lastAutoOpenedUrl = null
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) {
            // Install first; installAgent continues into the sign-in once Claude Code is ready.
            signInAfterClaudeInstall = true
            installAgent(AgentKind.CLAUDE_CODE)
            return
        }
        viewModelScope.launch { claudeAuthController.beginLogin() }
    }

    /**
     * After a successful account sign-in, make the account the way Claude Code authenticates:
     * the Claude subscription provider in account mode, saved for the Claude Code agent only.
     */
    private fun adoptClaudeAccountLogin() {
        val existing = preferences.loadProvider(vault, AgentKind.CLAUDE_CODE)
        val profile = if (existing.kind == ProviderKind.CLAUDE) {
            existing.copy(claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION, hasSecret = true)
        } else {
            ProviderProfile(kind = ProviderKind.CLAUDE, claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION, hasSecret = true)
        }
        val claudeIsActive = _state.value.agentKind == AgentKind.CLAUDE_CODE
        if (claudeIsActive) {
            preferences.saveProvider(profile, AgentKind.CLAUDE_CODE)
        } else {
            preferences.saveProviderForAgentOnly(profile, AgentKind.CLAUDE_CODE)
        }
        preferences.saveClaudeAuthMode(ClaudeAuthMode.NATIVE_SUBSCRIPTION, AgentKind.CLAUDE_CODE)
        _state.update { current ->
            current.copy(
                provider = if (claudeIsActive) profile else current.provider,
                claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
            )
        }
        syncClaudePingFromAuth()
    }

    fun startCodexLogin() {
        if (codexAuthController.isLoginInFlight) return
        if (_state.value.agentInstalling != null) {
            _state.update { it.copy(toastMessage = "Wait for the current install to finish, then sign in.") }
            return
        }
        if (_state.value.isRunning) {
            _state.update { it.copy(toastMessage = "Stop the current task before signing in.") }
            return
        }
        if (!installer.isAgentInstalled(AgentKind.CODEX)) {
            // Install first; installAgent continues into the sign-in once Codex is ready.
            signInAfterCodexInstall = true
            installAgent(AgentKind.CODEX)
            return
        }
        viewModelScope.launch { codexAuthController.beginLogin() }
    }

    fun cancelCodexLogin() {
        codexAuthController.cancelLogin()
    }

    fun logoutCodex() {
        viewModelScope.launch {
            runCatching { codexAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not sign out") } }
        }
    }

    fun refreshCodexAuthStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { codexAuthController.refreshStatus() }
        }
    }

    /**
     * After a successful ChatGPT sign-in, make the account the way Codex authenticates: the ChatGPT
     * provider, saved for the Codex agent only. A model the user already picked is kept.
     */
    private fun adoptCodexAccountLogin() {
        val existing = preferences.loadProvider(vault, AgentKind.CODEX)
        val profile = if (existing.kind == ProviderKind.CHATGPT) {
            existing.copy(hasSecret = true)
        } else {
            ProviderProfile(kind = ProviderKind.CHATGPT, hasSecret = true)
        }
        val codexIsActive = _state.value.agentKind == AgentKind.CODEX
        if (codexIsActive) {
            preferences.saveProvider(profile, AgentKind.CODEX)
        } else {
            preferences.saveProviderForAgentOnly(profile, AgentKind.CODEX)
        }
        _state.update { current ->
            current.copy(provider = if (codexIsActive) profile else current.provider)
        }
        syncCodexPingFromAuth()
    }

    private fun syncCodexPingFromAuth() {
        val current = _state.value
        if (current.agentKind != AgentKind.CODEX || current.provider.kind != ProviderKind.CHATGPT) return
        val auth = current.codexAuth
        val (status, message) = when (auth.status) {
            CodexAuthStatus.SIGNED_IN -> ApiPingStatus.OK to auth.displayStatus
            CodexAuthStatus.STARTING, CodexAuthStatus.AWAITING_AUTH -> ApiPingStatus.PINGING to auth.displayStatus
            else -> ApiPingStatus.FAILED to "Not signed in to ChatGPT"
        }
        _state.update { it.copy(apiPingStatus = status, apiPingMessage = message) }
    }

    /** Re-checks the Claude session when the app returns to the foreground (e.g. back from the browser). */
    fun onAppResumed() {
        if (_state.value.provider.kind == ProviderKind.CHATGPT) refreshCodexAuthStatus()
        val provider = _state.value.provider
        if (provider.kind != ProviderKind.CLAUDE || provider.claudeAuthMode != ClaudeAuthMode.NATIVE_SUBSCRIPTION) return
        // Each check starts a short runtime process; do not repeat it on every quick app switch.
        val now = SystemClock.elapsedRealtime()
        if (now - lastClaudeStatusCheckAtMillis < CLAUDE_STATUS_RECHECK_MS) return
        lastClaudeStatusCheckAtMillis = now
        refreshClaudeAuthStatus()
    }

    private fun syncClaudePingFromAuth() {
        val current = _state.value
        if (current.agentKind != AgentKind.CLAUDE_CODE ||
            current.provider.kind != ProviderKind.CLAUDE ||
            current.provider.claudeAuthMode != ClaudeAuthMode.NATIVE_SUBSCRIPTION
        ) return
        val auth = current.claudeAuth
        val (status, message) = when (auth.status) {
            ClaudeAuthStatusState.SIGNED_IN -> ApiPingStatus.OK to auth.displayStatus
            ClaudeAuthStatusState.STARTING,
            ClaudeAuthStatusState.AWAITING_AUTH,
            ClaudeAuthStatusState.VERIFYING,
            -> ApiPingStatus.PINGING to auth.displayStatus
            else -> ApiPingStatus.FAILED to "Not signed in to Claude"
        }
        _state.update { it.copy(apiPingStatus = status, apiPingMessage = message) }
    }

    fun submitClaudeCode(code: String) {
        runCatching { claudeAuthController.submitCode(code) }
            .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not submit the code") } }
    }

    fun cancelClaudeLogin() {
        claudeAuthController.cancelLogin()
    }

    fun logoutClaude() {
        viewModelScope.launch {
            runCatching { claudeAuthController.logout() }
                .onFailure { error -> _state.update { it.copy(toastMessage = error.message ?: "Could not sign out") } }
        }
    }

    fun setClaudeAuthMode(mode: ClaudeAuthMode) {
        // Only the Claude Code profile changes; other agents' providers are never rewritten.
        preferences.saveClaudeAuthMode(mode, AgentKind.CLAUDE_CODE)
        _state.update { current ->
            val provider = if (current.agentKind == AgentKind.CLAUDE_CODE && current.provider.kind == ProviderKind.CLAUDE) {
                current.provider.copy(claudeAuthMode = mode)
            } else {
                current.provider
            }
            current.copy(provider = provider, claudeAuthMode = mode)
        }
    }

    fun refreshClaudeAuthStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            claudeAuthController.queryAuthStatus()
        }
    }

    fun removeAntigravityAccount(accountId: String) {
        logoutAntigravity(accountId)
    }

    fun setAntigravityPrimaryAccount(accountId: String) {
        antigravityAccountManager.setPrimary(accountId)
    }

    fun toggleAntigravityAccountEnabled(accountId: String, enabled: Boolean) {
        antigravityAccountManager.setAccountEnabled(accountId, enabled)
    }

    fun setAntigravityLoadBalancingStrategy(strategy: AntigravityLoadBalancingStrategy) {
        preferences.antigravityLoadBalancingStrategy = strategy
        _state.update { it.copy(antigravityLoadBalancingStrategy = strategy) }
    }

    fun setAntigravityFailoverEnabled(enabled: Boolean) {
        preferences.antigravityFailoverEnabled = enabled
        _state.update { it.copy(antigravityFailoverEnabled = enabled) }
    }

    /** Stores [model] in the slot the active agent runs. Returns false when the active agent does not accept it. */
    /** Applies a model picked or typed in chat. A blank ID is only valid for Codex, where it means Codex's default. */
    fun applyModelChoice(model: String): ModelChoiceCheck {
        val trimmed = model.trim()
        val current = _state.value
        return when (modelSlotFor(current.agentKind, current.provider.kind)) {
            ModelSlot.ANTIGRAVITY -> {
                val check = checkModelChoice(trimmed, current.antigravityModels)
                if (check != ModelChoiceCheck.REJECTED) setAntigravityModel(trimmed)
                check
            }
            ModelSlot.CLAUDE_SUBSCRIPTION -> {
                val check = checkModelChoice(trimmed, com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS.map { it.id })
                if (check == ModelChoiceCheck.APPLIED) setClaudeModel(trimmed)
                check
            }
            ModelSlot.PROVIDER -> {
                val isCodexDefault = trimmed.isEmpty() && current.provider.kind == ProviderKind.CHATGPT
                val check = if (isCodexDefault) {
                    ModelChoiceCheck.APPLIED
                } else {
                    checkModelChoice(trimmed, savedModelsFor(current.agentKind, current.provider).map { it.id })
                }
                if (check != ModelChoiceCheck.REJECTED) {
                    val updated = current.provider.copy(model = trimmed)
                    preferences.saveProvider(updated, current.agentKind)
                    _state.update { it.copy(provider = updated) }
                }
                check
            }
        }
    }

    fun setClaudeModel(model: String) {
        val validated = com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS.firstOrNull { it.id.equals(model, ignoreCase = true) }?.id ?: return
        preferences.claudeModel = validated
        _state.update { current ->
            val updatedProvider = if (current.provider.kind == ProviderKind.CLAUDE) {
                val updated = current.provider.copy(model = validated)
                preferences.saveProvider(updated, current.agentKind)
                updated
            } else {
                current.provider
            }
            current.copy(
                claudeModel = validated,
                provider = updatedProvider,
            )
        }
    }

    fun setClaudeThinkingLevel(level: String) {
        val validated = com.jarves.mh.model.ClaudeThinkingLevel.fromStored(level)
        preferences.claudeThinkingLevel = validated.id
        _state.update { current ->
            val updatedProvider = if (current.provider.kind == ProviderKind.CLAUDE) {
                val updated = current.provider.copy(claudeThinkingLevel = validated.id)
                preferences.saveProvider(updated, current.agentKind)
                updated
            } else {
                current.provider
            }
            current.copy(
                claudeThinkingLevel = validated.id,
                provider = updatedProvider,
            )
        }
    }

    /** Blank keeps Codex on its own default effort; unknown levels are ignored and stored as default. */
    fun setCodexReasoningEffort(level: String) {
        val validated = com.jarves.mh.model.codexReasoningEffortOrNull(level).orEmpty()
        preferences.codexReasoningEffort = validated
        _state.update { it.copy(codexReasoningEffort = validated) }
    }

    fun toggleClaudeThinkingPicker(visible: Boolean? = null) {
        _state.update { it.copy(claudeThinkingPickerVisible = visible ?: !it.claudeThinkingPickerVisible) }
    }

    fun setAntigravityModel(model: String) {
        preferences.antigravityModel = model
        val modelEffort = antigravityEffortFromModel(model)
        if (modelEffort != null) preferences.antigravityEffort = modelEffort
        _state.update {
            it.copy(
                antigravityModel = model,
                antigravityEffort = modelEffort ?: it.antigravityEffort,
            )
        }
    }

    /** Returns false when the effort is unknown or the selected model does not offer it. */
    fun setAntigravityEffort(effort: String): Boolean {
        if (effort !in setOf("low", "medium", "high")) return false
        val current = _state.value
        val matchingModel = antigravityModelWithEffort(current.antigravityModel, effort)
            ?.takeIf { candidate -> current.antigravityModels.isEmpty() || candidate in current.antigravityModels }
        if (current.antigravityModel.isNotBlank() &&
            antigravityEffortFromModel(current.antigravityModel) != null &&
            matchingModel == null
        ) {
            _state.update { it.copy(toastMessage = "This model does not offer ${effort.replaceFirstChar(Char::uppercase)} reasoning") }
            return false
        }
        preferences.antigravityEffort = effort
        matchingModel?.let { preferences.antigravityModel = it }
        _state.update {
            it.copy(
                antigravityEffort = effort,
                antigravityModel = matchingModel ?: it.antigravityModel,
            )
        }
        return true
    }

    private var antigravityModelRefreshJob: Job? = null

    fun refreshAntigravityModels() {
        if (antigravityModelRefreshJob?.isActive == true || _state.value.antigravityModelsLoading || !installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        _state.update { it.copy(antigravityModelsLoading = true) }
        antigravityModelRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                launch { runCatching { antigravityAccountManager.refreshAllAccountQuotas() } }
                val result = runCatching {
                    discoverAntigravityModels(
                        installer = installer,
                        accountManager = antigravityAccountManager,
                        workspaceDir = File(getApplication<Application>().filesDir, "workspaces/antigravity-models"),
                        cacheDir = getApplication<Application>().cacheDir,
                    )
                }
                withContext(Dispatchers.Main) {
                    _state.update { current ->
                        result.fold(
                            onSuccess = { models ->
                                val (selected, selectedEffort) = reconcileAntigravityModelSelection(
                                    currentModel = current.antigravityModel,
                                    currentEffort = current.antigravityEffort,
                                    availableModels = models,
                                )
                                preferences.antigravityModel = selected
                                preferences.antigravityEffort = selectedEffort
                                current.copy(
                                    antigravityModelsLoading = false,
                                    antigravityModels = models,
                                    antigravityModel = selected,
                                    antigravityEffort = selectedEffort,
                                )
                            },
                            onFailure = { error ->
                                current.copy(
                                    antigravityModelsLoading = false,
                                    toastMessage = error.message ?: "Could not load Antigravity models",
                                )
                            },
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) {
                        if (_state.value.antigravityModelsLoading) {
                            _state.update { it.copy(antigravityModelsLoading = false) }
                        }
                    }
                }
            }
        }
    }

    /** Called from the first-launch tool picker; persists the choice for setup and Settings. */
    fun toggleDevStack(stack: DevStack) {
        if (stack == DevStack.WEB) return
        val updated = _state.value.selectedDevStacks.toMutableSet().apply {
            if (!add(stack)) remove(stack)
        }
        preferences.selectedDevStacks = updated.map { it.name }.toSet()
        _state.update { it.copy(selectedDevStacks = updated) }
    }

    /** Installs one development stack on demand (Settings) with live progress. */
    fun installDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null) return
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = false,
                devStackMessage = "Preparing ${stack.label}…",
                devStackProgress = 0f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            var sampleBytes = 0L
            var sampleTime = android.os.SystemClock.elapsedRealtime()
            var latestSpeed: Long? = null
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.ensureStackInstalled(stack) { progress ->
                        val transfer = progress.totalBytes?.let { total ->
                            (progress.downloadedBytes ?: 0L) to total
                        }
                        if (transfer != null) {
                            val now = android.os.SystemClock.elapsedRealtime()
                            val elapsed = now - sampleTime
                            val delta = transfer.first - sampleBytes
                            if (delta < 0L) {
                                sampleBytes = transfer.first
                                sampleTime = now
                                latestSpeed = null
                            } else if (elapsed >= 500L) {
                                latestSpeed = (delta * 1_000L / elapsed).coerceAtLeast(0L)
                                sampleBytes = transfer.first
                                sampleTime = now
                            }
                        } else {
                            sampleBytes = 0L
                            sampleTime = android.os.SystemClock.elapsedRealtime()
                            latestSpeed = null
                        }
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                                devStackBytes = transfer,
                                devStackBytesPerSecond = latestSpeed,
                            )
                        }
                    }
                }
            }
            _state.update { current ->
                current.copy(
                    devStackInstalling = null,
                    devStackRemoving = false,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks + stack else current.installedDevStacks,
                    devStackProgress = 0f,
                    devStackBytes = null,
                    devStackBytesPerSecond = null,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} tools are ready" },
                        onFailure = { _ -> result.exceptionOrNull()?.message?.take(200) ?: "Could not install ${stack.label}" },
                    ),
                )
            }
        }
    }

    /** Removes an optional toolchain after the Settings confirmation dialog. */
    fun removeDevStack(stack: DevStack) {
        if (_state.value.devStackInstalling != null || stack == DevStack.WEB) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop running tasks and terminal commands before removing tools") }
            return
        }
        _state.update {
            it.copy(
                devStackInstalling = stack,
                devStackRemoving = true,
                devStackMessage = "Removing ${stack.label}…",
                devStackProgress = 0.1f,
                devStackBytes = null,
                devStackBytesPerSecond = null,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    installer.removeStack(stack) { progress ->
                        _state.update { current ->
                            current.copy(
                                devStackMessage = progress.message,
                                devStackProgress = progress.fraction.coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            }
            if (result.isSuccess) {
                val selected = _state.value.selectedDevStacks - stack
                preferences.selectedDevStacks = selected.map { it.name }.toSet()
            }
            _state.update { current ->
                current.copy(
                    selectedDevStacks = if (result.isSuccess) current.selectedDevStacks - stack else current.selectedDevStacks,
                    installedDevStacks = if (result.isSuccess) current.installedDevStacks - stack else current.installedDevStacks,
                    devStackInstalling = null,
                    devStackRemoving = false,
                    devStackProgress = 0f,
                    devStackMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { result.exceptionOrNull()?.message?.take(200) ?: "Could not remove ${stack.label}" },
                    ),
                    toastMessage = result.fold(
                        onSuccess = { "${stack.label} removed" },
                        onFailure = { "Could not remove ${stack.label}" },
                    ),
                )
            }
        }
    }

    /** Discover from the provider, then keep the list so Settings and chat show the same models. */
    suspend fun discoverModels(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        val result = fetchModelList(profile, secret)
        // Antigravity lists carry live quota labels, so they are never saved.
        if (result is ModelDiscoveryResult.Success && profile.kind != ProviderKind.ANTIGRAVITY_SERVER) {
            preferences.saveModelList(_state.value.agentKind, profile.kind, profile.baseUrl, result.models)
        }
        return result
    }

    private suspend fun fetchModelList(profile: ProviderProfile, secret: String): ModelDiscoveryResult {
        if (profile.kind == ProviderKind.CHATGPT) {
            return withContext(Dispatchers.IO) { codexModelCatalog.fetch() }
        }
        if (profile.kind == ProviderKind.CLAUDE) {
            return ModelDiscoveryResult.Success(com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS, "Claude Code")
        }
        if (profile.kind == ProviderKind.ANTIGRAVITY_SERVER) {
            val primaryAcc = antigravityAccountManager.getPrimaryAccount()
            val models = com.jarves.mh.runtime.AntigravityProtocolAdapter.SUPPORTED_MODELS.map { id ->
                val remainingPct = primaryAcc?.remainingPercentageFor(id)
                val quotaSuffix = if (remainingPct != null) " · $remainingPct%" else ""
                val name = when (id) {
                    "gemini-3.8-pro" -> "Gemini 3.8 Pro"
                    "gemini-3.8-flash" -> "Gemini 3.8 Flash"
                    "gemini-2.5-pro" -> "Gemini 2.5 Pro"
                    "gemini-2.5-flash" -> "Gemini 2.5 Flash"
                    "claude-3-7-sonnet" -> "Claude 3.7 Sonnet (Antigravity)"
                    "claude-3-5-sonnet" -> "Claude 3.5 Sonnet (Antigravity)"
                    else -> id
                }
                com.jarves.mh.network.DiscoveredModel(
                    id = id,
                    displayName = "$name$quotaSuffix",
                )
            }
            return ModelDiscoveryResult.Success(models, "Antigravity Server")
        }
        val key = secret.ifBlank { vault.get(profile.secretId).orEmpty() }
        return providerApi.discoverModels(profile.baseUrl, key, providerProtocolForAgent(profile, _state.value.agentKind))
    }

    suspend fun validateProvider(
        profile: ProviderProfile,
        secret: String,
        models: List<com.jarves.mh.network.DiscoveredModel>,
    ): ConnectionValidation {
        if (profile.kind == ProviderKind.CHATGPT) {
            return if (codexAuthController.hasCredentials()) {
                ConnectionValidation.Success("Signed in with ChatGPT.")
            } else {
                ConnectionValidation.Failure("Not signed in to ChatGPT. Sign in first.", label = "Sign in needed")
            }
        }
        if (profile.kind == ProviderKind.ANTIGRAVITY_SERVER) {
            val hasAccount = antigravityAccountManager.accountsList().any { it.isAvailableForRouting } ||
                antigravityAuthController.hasOfficialCredential()
            return if (hasAccount) {
                ConnectionValidation.Success("Google Antigravity account verified. Models ready.")
            } else {
                ConnectionValidation.Failure(
                    "Google account not signed in. Sign in under Antigravity settings to use Antigravity models.",
                    label = "Sign in needed",
                )
            }
        }
        val key = secret.ifBlank { vault.get(profile.secretId).orEmpty() }
        return providerApi.validate(
            profile.baseUrl,
            profile.model,
            key,
            providerProtocolForAgent(profile, _state.value.agentKind),
            models,
        )
    }

    fun pingApi() {
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY) {
            testAntigravityConnection()
            return
        }
        val profile = _state.value.provider
        if (profile.kind == ProviderKind.ANTIGRAVITY_SERVER) {
            val hasAccount = antigravityAccountManager.accountsList().any { it.isAvailableForRouting } ||
                antigravityAuthController.hasOfficialCredential()
            _state.update {
                it.copy(
                    apiPingStatus = if (hasAccount) ApiPingStatus.OK else ApiPingStatus.FAILED,
                    apiPingMessage = if (hasAccount) "Antigravity account connected" else "Google account not signed in",
                )
            }
            return
        }
        if (profile.kind == ProviderKind.CLAUDE && profile.claudeAuthMode == ClaudeAuthMode.NATIVE_SUBSCRIPTION) {
            // Account login has no API endpoint to ping; report the sign-in state instead.
            syncClaudePingFromAuth()
            refreshClaudeAuthStatus()
            return
        }
        if (profile.kind == ProviderKind.CHATGPT) {
            syncCodexPingFromAuth()
            refreshCodexAuthStatus()
            return
        }
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Sending a minimal test request…") }
        viewModelScope.launch {
            val key = vault.get(profile.secretId).orEmpty()
            val result = providerApi.validate(
                profile.baseUrl,
                profile.model,
                key,
                providerProtocolForAgent(profile, _state.value.agentKind),
                emptyList(),
            )
            when (result) {
                is ConnectionValidation.Success -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.OK, apiPingMessage = "API responded successfully")
                }
                is ConnectionValidation.Failure -> _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = result.message)
                }
            }
        }
    }

    /** Sends a tiny hello to the agy CLI to prove it actually answers. Silent timeout inside. */
    fun testAntigravityConnection() {
        if (_state.value.agentKind != AgentKind.ANTIGRAVITY) return
        if (_state.value.apiPingStatus == ApiPingStatus.PINGING) return
        if (_state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update {
                it.copy(
                    apiPingStatus = ApiPingStatus.FAILED,
                    apiPingMessage = "Antigravity needs Google sign-in",
                )
            }
            return
        }
        _state.update { it.copy(apiPingStatus = ApiPingStatus.PINGING, apiPingMessage = "Saying hello to Antigravity…") }
        viewModelScope.launch {
            val result = runCatching { antigravityRuntime.hello() }
            result.onSuccess {
                _state.update {
                    it.copy(
                        apiPingStatus = ApiPingStatus.OK,
                        apiPingMessage = "Antigravity is Working!",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(apiPingStatus = ApiPingStatus.FAILED, apiPingMessage = helloFailureMessage(error.message.orEmpty()))
                }
            }
        }
    }

    private fun helloFailureMessage(raw: String): String {
        val value = raw.replace(Regex("\\s+"), " ").trim()
        return when {
            value.contains("not installed", true) -> "Antigravity CLI is not installed. Install it from the Coding agent section."
            value.contains("sign-in", true) || value.contains("not signed in", true) ||
                value.contains("authentication", true) -> "Antigravity needs Google sign-in. Reconnect from the Google connection section."
            value.contains("did not answer", true) -> "Antigravity did not answer. Try again."
            value.isBlank() -> "Antigravity did not answer. Try again."
            else -> value.take(200)
        }
    }

    fun openProject(project: Project) {
        val openStartedAt = OpenPerf.nowMs()
        val current = _state.value
        if (current.activeProject?.id == project.id) {
            dismissReadOnlyLoad()
            _state.update {
                it.copy(
                    workspaceVisible = true,
                    readOnlyProject = null,
                    readOnlyProjectChats = emptyList(),
                    readOnlyChatId = null,
                    readOnlyMessages = emptyList(),
                )
            }
            return
        }
        val generation = ++projectOpenGeneration
        projectOpenJob?.cancel()
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update {
                it.copy(
                    readOnlyProject = project,
                    readOnlyProjectChats = emptyList(),
                    readOnlyChatId = null,
                    readOnlyMessages = emptyList(),
                    chatLoading = true,
                )
            }
            projectOpenJob = viewModelScope.launch {
                val loaded = runCatching {
                    withContext(ioDispatcher) {
                        val chats = preferences.loadProjectChats(project.id).ifEmpty {
                            listOf(ProjectChat(title = "Main chat"))
                        }
                        val chat = chats.first()
                        LoadedChat(chats, chat.id, preferences.loadMessages(project.id, chat.id))
                    }
                }.getOrNull()
                if (generation != projectOpenGeneration) return@launch
                _state.update {
                    it.copy(
                        readOnlyProjectChats = loaded?.chats.orEmpty(),
                        readOnlyChatId = loaded?.chatId,
                        readOnlyMessages = loaded?.messages.orEmpty(),
                        chatLoading = false,
                    )
                }
            }
            return
        }
        // Shell first: the project shows at once with a placeholder, and its chat history loads off the main thread.
        cancelFileRead()
        _state.update {
            it.copy(
                activeProject = project,
                workspaceVisible = true,
                openedFilePath = null,
                openedFileContent = null,
                fileContentLoading = false,
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
                projectChats = emptyList(),
                activeChatId = null,
                contextMemory = ContextMemory(""),
                messages = emptyList(),
                chatLoading = true,
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = projectGuestRoot(project),
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
        OpenPerf.log("open ${project.slug}: shell published +${OpenPerf.nowMs() - openStartedAt} ms")
        OpenPerf.onNextFrame {
            OpenPerf.log("open ${project.slug}: next frame +${OpenPerf.nowMs() - openStartedAt} ms")
        }
        refreshProjectFiles()
        projectOpenJob = viewModelScope.launch {
            // Phase 1: the chat list and its messages, so the chat body appears without waiting for the terminal or memory.
            val chat = runCatching {
                withContext(ioDispatcher) {
                    // Bridge roots are configured off Main, before anything in this project reads them.
                    configureBridgeRoots(project.id, project.rootPath)
                    val chats = preferences.loadProjectChats(project.id).ifEmpty {
                        listOf(ProjectChat(title = "Main chat")).also { preferences.saveProjectChats(project.id, it) }
                    }
                    val activeChat = chats.first()
                    LoadedChat(
                        chats,
                        activeChat.id,
                        OpenPerf.span("open.messages") { preferences.loadMessages(project.id, activeChat.id) },
                    )
                }
            }.getOrNull()
            if (generation != projectOpenGeneration) return@launch
            if (chat == null) {
                _state.update { it.copy(chatLoading = false, toastMessage = "Could not load this project's chat history.") }
                return@launch
            }
            OpenPerf.log("open ${project.slug}: chat ready +${OpenPerf.nowMs() - openStartedAt} ms, ${chat.messages.size} messages")
            // chatLoading stays true until phase 2 publishes memory, so sending and new chats wait for it.
            _state.update {
                it.copy(
                    projectChats = chat.chats,
                    activeChatId = chat.chatId,
                    messages = chat.messages.ifEmpty {
                        listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change."))
                    },
                )
            }
            // Phase 2: the terminal, nested root and memory. These can take longer than the chat.
            val details = runCatching {
                withContext(ioDispatcher) {
                    ProjectDetails(
                        terminal = OpenPerf.span("open.terminal") { loadProjectTerminal(project) },
                        suggestedRoot = if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null,
                        memory = OpenPerf.span("open.memory") { memoryStore.load(project.id) },
                    )
                }
            }.getOrNull()
            if (generation != projectOpenGeneration) return@launch
            if (details == null) {
                _state.update { it.copy(chatLoading = false, toastMessage = "Could not load this project's terminal and memory.") }
                return@launch
            }
            OpenPerf.log("open ${project.slug}: details ready +${OpenPerf.nowMs() - openStartedAt} ms")
            _state.update {
                it.copy(
                    contextMemory = details.memory,
                    chatLoading = false,
                    projectTerminalLines = details.terminal.lines,
                    projectTerminalCwd = details.terminal.cwd,
                    suggestedProjectRoot = details.suggestedRoot,
                )
            }
            // Pending changes read the bridge root, so they load only after it is configured above.
            runRequest { activeRuntime().loadPendingChanges(project.id) }
                .onSuccess { pending ->
                    if (_state.value.activeProject?.id == project.id) _state.update { it.copy(changes = pending) }
                }
                .onFailure { error ->
                    if (_state.value.activeProject?.id == project.id) {
                        _state.update { it.copy(toastMessage = "Could not load pending changes: ${error.message}") }
                    }
                }
        }
    }

    fun closeProject() {
        val active = _state.value.activeProject
        // A write that is queued or still flushing is not on disk yet, so the emptiness check must not trust the disk.
        val writeInFlight = pendingTranscriptWrite != null || transcriptDebounceJob?.isActive == true
        persistMessages()
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update {
                it.copy(
                    workspaceVisible = false,
                    toastMessage = if (it.isRunning) {
                        "Task continues in the background"
                    } else {
                        "Terminal command continues in the background"
                    },
                )
            }
            return
        }

        if (active != null) {
            val workspaceDir = File(getApplication<Application>().filesDir, "workspaces/${active.id}")
            val userFiles = if (workspaceDir.isDirectory) {
                workspaceDir.walkTopDown().filter { file ->
                    file.isFile && !file.name.startsWith(".claude") && file.name != ".pocket-dev-stacks.json"
                }.count()
            } else 0

            if (!writeInFlight && preferences.hasNoChatMessages(active.id) && userFiles == 0 &&
                !_state.value.isRunning && !_state.value.projectTerminalRunning
            ) {
                // Unused empty project; delete immediately so it does not clutter the project list.
                // Drop the write queued above so the deleted project's folder is not written again.
                pendingTranscriptWrite = null
                transcriptDebounceJob?.cancel()
                _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == active.id }) }
                preferences.saveProjects(_state.value.projects)
                viewModelScope.launch(Dispatchers.IO) {
                    workspaceDir.deleteRecursively()
                    terminalHistoryFile(active.id).delete()
                    preferences.deleteProjectChats(active.id)
                }
            }
        }

        projectOpenGeneration++
        projectOpenJob?.cancel()
        cancelFileRead()
        _state.update {
            it.copy(
                activeProject = null,
                openedFilePath = null,
                openedFileContent = null,
                fileContentLoading = false,
                workspaceVisible = false,
                projectChats = emptyList(),
                activeChatId = null,
                chatLoading = false,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = false,
                isRunning = false,
                activeSessionId = null,
                pendingApproval = null,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = "/workspace",
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun closeReadOnlyProject() {
        dismissReadOnlyLoad()
        _state.update {
            it.copy(
                readOnlyProject = null,
                readOnlyProjectChats = emptyList(),
                readOnlyChatId = null,
                readOnlyMessages = emptyList(),
            )
        }
    }

    /**
     * Ends a read-only load the user dismissed. Its late result must not publish, and the loading flag it
     * set must not stay on.
     */
    private fun dismissReadOnlyLoad() {
        if (_state.value.readOnlyProject == null) return
        projectOpenGeneration++
        projectOpenJob?.cancel()
        _state.update { it.copy(chatLoading = false) }
    }

    fun switchReadOnlyChat(chatId: String) {
        val project = _state.value.readOnlyProject ?: return
        if (_state.value.readOnlyProjectChats.none { it.id == chatId }) return
        _state.update {
            it.copy(
                readOnlyChatId = chatId,
                readOnlyMessages = preferences.loadMessages(project.id, chatId),
            )
        }
    }

    fun activateReadOnlyProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning) return
        val project = _state.value.readOnlyProject ?: return
        closeReadOnlyProject()
        openProject(project)
    }

    fun consumeToast() = _state.update { it.copy(toastMessage = null) }

    fun createProject(name: String) {
        if (name.isBlank()) return
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the background task before creating another project") }
            return
        }
        val baseSlug = projectSlug(name)
        val usedSlugs = _state.value.projects.mapTo(mutableSetOf()) { it.slug }
        val slug = generateSequence(1) { it + 1 }
            .map { number -> if (number == 1) baseSlug else "$baseSlug-$number" }
            .first { it !in usedSlugs }
        val project = Project(
            name = name.trim(),
            description = "Starter web project",
            language = "TypeScript",
            slug = slug,
        )
        configureBridgeRoots(project.id, project.rootPath)
        val guestRoot = projectGuestRoot(project)
        _state.update {
            it.copy(
                projects = listOf(project) + it.projects,
                activeProject = project,
                workspaceVisible = true,
                contextMemory = ContextMemory(project.id),
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                changes = emptyList(),
                workspaceFiles = emptyList(),
                androidProjectDetected = false,
                filesLoading = true,
                projectTerminalLines = emptyList(),
                projectTerminalLiveOutput = "",
                projectTerminalRunning = false,
                projectTerminalCwd = guestRoot,
                projectTerminalCommand = null,
                projectTerminalDraft = null,
                pendingTerminalCommand = null,
                suggestedProjectRoot = null,
                previewReady = false,
                previewUrl = null,
            )
        }
        preferences.saveProjects(_state.value.projects)
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        val firstChat = ProjectChat(title = "New chat")
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projectChats = listOf(firstChat), activeChatId = firstChat.id) }
        refreshProjectFiles()
    }

    fun createQuickProject() {
        if (_state.value.isRunning || _state.value.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the background task before creating another project") }
            return
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val project = Project(
            name = identity.displayName,
            description = "Quick project workspace",
            language = "General",
            slug = identity.slug,
            kind = ProjectKind.QUICK_PROJECT,
        )
        val firstChat = ProjectChat(title = "New chat")
        File(getApplication<Application>().filesDir, "workspaces/${project.id}").mkdirs()
        preferences.saveProjectChats(project.id, listOf(firstChat))
        _state.update { it.copy(projects = listOf(project) + it.projects) }
        preferences.saveProjects(_state.value.projects)
        openProject(project)
    }

    fun importZipProject(uri: Uri) {
        if (_state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { it.copy(projectImporting = true, projectImportMessage = "Reading project archive…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { extractImportedProject(uri) } }
            result.onSuccess { imported ->
                val project = imported.project
                val firstChat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(firstChat))
                _state.update { current ->
                    current.copy(
                        projects = listOf(project) + current.projects,
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "${project.name} imported successfully",
                    )
                }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(pendingAttachments = listOf(imported.sourceAttachment)) }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        projectImporting = false,
                        projectImportMessage = null,
                        toastMessage = "Import failed: ${error.message?.take(180) ?: "Invalid ZIP archive"}",
                    )
                }
            }
        }
    }

    private fun extractImportedProject(uri: Uri): ImportedZipProject {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        var archiveName = "Imported project.zip"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    archiveName = cursor.getString(index) ?: archiveName
                }
            }
        }
        val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
        val projectId = UUID.randomUUID().toString()
        val destination = File(app.filesDir, "workspaces/$projectId")
        destination.mkdirs()
        val destinationPath = destination.canonicalFile.toPath()
        val availableLimit = (destination.usableSpace * 8L / 10L).coerceAtMost(MAX_IMPORTED_PROJECT_BYTES)
        var extractedBytes = 0L
        var entries = 0
        try {
            val source = resolver.openInputStream(uri) ?: error("The selected ZIP could not be opened")
            source.buffered().use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        require(entries <= MAX_IMPORTED_ZIP_ENTRIES) { "The ZIP contains too many files" }
                        val entryName = entry.name.replace('\\', '/').trimStart('/')
                        require(entryName.isNotBlank() && '\u0000' !in entryName) { "The ZIP contains an invalid path" }
                        if (entryName.startsWith("__MACOSX/") || entryName.endsWith("/.DS_Store") || entryName == ".DS_Store") {
                            zip.closeEntry()
                            continue
                        }
                        val target = File(destination, entryName).canonicalFile
                        require(target.toPath().startsWith(destinationPath)) { "The ZIP contains an unsafe path" }
                        if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = zip.read(buffer)
                                    if (count < 0) break
                                    extractedBytes += count
                                    require(extractedBytes <= availableLimit) { "The extracted project is too large for available storage" }
                                    output.write(buffer, 0, count)
                                }
                            }
                            if (entry.time > 0) target.setLastModified(entry.time)
                        }
                        zip.closeEntry()
                    }
                }
            }
            require(entries > 0 && destination.walkTopDown().any { it.isFile }) { "The ZIP does not contain project files" }
            val preliminary = Project(
                id = projectId,
                name = identity.displayName,
                description = "Imported project workspace",
                language = "General",
                slug = identity.slug,
                kind = ProjectKind.QUICK_PROJECT,
            )
            val nestedRoot = detectNestedProjectRoot(preliminary)
            val projectRoot = nestedRoot?.let { File(destination, it) } ?: destination
            val metadata = detectImportedProjectMetadata(projectRoot)
            val safeArchiveName = sanitizeAttachmentName(archiveName).let { name ->
                if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
            }
            val archiveFolder = File(projectRoot, ".pocketdev/imports").apply { mkdirs() }
            val archivedSource = File(archiveFolder, safeArchiveName)
            var sourceBytes = 0L
            resolver.openInputStream(uri)?.buffered()?.use { input ->
                archivedSource.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sourceBytes += count
                        require(extractedBytes + sourceBytes <= availableLimit) { "The imported project is too large for available storage" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("The selected ZIP could not be preserved")
            val project = preliminary.copy(
                description = metadata.first,
                language = metadata.second,
                rootPath = nestedRoot.orEmpty(),
            )
            return ImportedZipProject(
                project = project,
                sourceAttachment = ChatAttachment(
                    displayName = archiveName.take(120),
                    relativePath = archivedSource.relativeTo(projectRoot).invariantSeparatorsPath,
                    mimeType = "application/zip",
                    sizeBytes = sourceBytes,
                ),
            )
        } catch (error: Throwable) {
            destination.deleteRecursively()
            throw error
        }
    }

    private fun detectImportedProjectMetadata(root: File): Pair<String, String> {
        val names = root.walkTopDown().maxDepth(3).filter(File::isFile).map { it.name.lowercase() }.toSet()
        return when {
            names.any { it == "settings.gradle.kts" || it == "build.gradle.kts" } -> "Imported Gradle project" to "Kotlin"
            names.any { it == "settings.gradle" || it == "build.gradle" } -> "Imported Gradle project" to "Java"
            "package.json" in names && names.any { it == "tsconfig.json" || it.endsWith(".ts") || it.endsWith(".tsx") } -> "Imported web project" to "TypeScript"
            "package.json" in names -> "Imported web project" to "JavaScript"
            names.any { it == "pyproject.toml" || it == "requirements.txt" || it.endsWith(".py") } -> "Imported Python project" to "Python"
            names.any { it == "cargo.toml" || it.endsWith(".rs") } -> "Imported Rust project" to "Rust"
            names.any { it == "go.mod" || it.endsWith(".go") } -> "Imported Go project" to "Go"
            else -> "Imported ZIP project" to "General"
        }
    }

    fun clonePublicGitRepository(url: String) {
        cloneGitRepository(url = url, repositoryName = null, branch = null, useGitHubCli = false)
    }

    fun cloneGitHubRepository(repository: GitHubRepository) {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) {
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubMessage = "Connect GitHub again") }
            return
        }
        cloneGitRepository(repository.cloneUrl, repository.fullName, repository.defaultBranch, useGitHubCli = true)
    }

    private fun cloneGitRepository(url: String, repositoryName: String?, branch: String?, useGitHubCli: Boolean) {
        if (_state.value.gitCloneRunning || _state.value.projectImporting || _state.value.isRunning || _state.value.projectTerminalRunning) return
        val normalized = runCatching { validateGitUrl(url) }.getOrElse { error ->
            _state.update { it.copy(toastMessage = error.message ?: "Enter a valid public HTTPS Git URL") }
            return
        }
        _state.update { it.copy(gitCloneRunning = true, gitCloneMessage = "Connecting to Git repository…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val identity = generateQuickChatIdentity(_state.value.projects.mapTo(mutableSetOf()) { it.slug })
                    val projectId = UUID.randomUUID().toString()
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/$projectId").apply { mkdirs() }
                    val output = File(getApplication<Application>().cacheDir, "git-clone-${System.nanoTime()}.log")
                    try {
                        val environment = mutableMapOf(
                            "GIT_TERMINAL_PROMPT" to "0",
                            "GIT_LFS_SKIP_SMUDGE" to "1",
                            "GH_PROMPT_DISABLED" to "1",
                            "GH_NO_UPDATE_NOTIFIER" to "1",
                        )
                        val installed = installer.installedRuntime()
                        val command = if (useGitHubCli && repositoryName != null) {
                            buildList {
                                addAll(listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH, "repo", "clone", repositoryName, ".", "--", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                            }
                        } else {
                            buildList {
                                addAll(listOf("git", "clone", "--progress", "--single-branch"))
                                branch?.takeIf(String::isNotBlank)?.let { addAll(listOf("--branch", it)) }
                                add(normalized)
                                add(".")
                            }
                        }
                        _state.update { it.copy(gitCloneMessage = "Cloning ${repositoryName ?: normalized.substringAfterLast('/').removeSuffix(".git")}…") }
                        val process = installer.process(
                            installed.proot,
                            installed.rootfs,
                            workspace,
                            environment,
                            command,
                            guestWorkspacePath = "/workspace/${identity.slug}",
                            outputFile = output,
                        )
                        val exit = process.waitFor()
                        val details = output.readText().trim()
                        check(exit == 0) { details.takeLast(600).ifBlank { "Git clone failed with exit code $exit" } }
                        val metadata = detectImportedProjectMetadata(workspace)
                        Project(
                            id = projectId,
                            name = identity.displayName,
                            description = repositoryName?.let { "GitHub · $it" } ?: "Imported Git repository",
                            language = metadata.second,
                            slug = identity.slug,
                            kind = ProjectKind.QUICK_PROJECT,
                        )
                    } catch (error: Throwable) {
                        workspace.deleteRecursively()
                        throw error
                    } finally {
                        output.delete()
                    }
                }
            }
            result.onSuccess { project ->
                val chat = ProjectChat(title = "New chat")
                preferences.saveProjectChats(project.id, listOf(chat))
                _state.update { current -> current.copy(projects = listOf(project) + current.projects) }
                preferences.saveProjects(_state.value.projects)
                openProject(project)
                _state.update { it.copy(gitCloneRunning = false, gitCloneMessage = null, toastMessage = "Repository cloned successfully") }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        gitCloneRunning = false,
                        gitCloneMessage = null,
                        toastMessage = "Clone failed: ${error.message?.lineSequence()?.lastOrNull()?.take(180) ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    private fun validateGitUrl(value: String): String {
        val clean = value.trim()
        val uri = URI(clean)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Only HTTPS Git URLs are supported" }
        require(uri.userInfo == null && uri.fragment == null && uri.host?.isNotBlank() == true) { "Enter a valid HTTPS Git URL without credentials" }
        require(uri.host != "localhost" && uri.host != "127.0.0.1" && uri.host != "::1") { "Local Git URLs are not supported" }
        require(uri.path.count { it == '/' } >= 2) { "The URL must identify a Git repository" }
        return uri.toASCIIString()
    }

    fun startGitHubLogin() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING || _state.value.githubAuthStatus == GitHubAuthStatus.AWAITING_USER) return
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Preparing official GitHub sign-in…") }
        startGitHubForegroundOperation()
        githubAuthJob = viewModelScope.launch {
            try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    installer.ensureGitHubCliInstalled { progress ->
                        _state.update { it.copy(githubMessage = progress.message) }
                    }
                    val runtime = installer.installedRuntime()
                    val outputFile = File(getApplication<Application>().cacheDir, "github-auth-${System.nanoTime()}.log")
                    val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
                    val process = installer.process(
                        runtime.proot,
                        runtime.rootfs,
                        workspace,
                        githubCliEnvironment(),
                        listOf(
                            RuntimeInstaller.GITHUB_CLI_GUEST_PATH,
                            "auth", "login",
                            "--hostname", "github.com",
                            "--git-protocol", "https",
                            "--web",
                            "--insecure-storage",
                        ),
                        guestWorkspacePath = "/workspace/github-auth",
                        outputFile = outputFile,
                    )
                    githubAuthProcess = process
                    var offset = 0L
                    val captured = StringBuilder()
                    var browserOpened = false
                    try {
                        while (process.isAlive || outputFile.length() > offset) {
                            if (outputFile.length() > offset) {
                                val count = (outputFile.length() - offset).coerceAtMost(16L * 1024).toInt()
                                val bytes = ByteArray(count)
                                RandomAccessFile(outputFile, "r").use { file -> file.seek(offset); file.readFully(bytes) }
                                offset += count
                                captured.append(bytes.toString(Charsets.UTF_8))
                                val clean = sanitizeTerminalOutput(captured.toString()).takeLast(20_000)
                                val code = GITHUB_DEVICE_CODE.find(clean)?.value
                                if (code != null && !browserOpened) {
                                    browserOpened = true
                                    _state.update {
                                        it.copy(
                                            githubAuthStatus = GitHubAuthStatus.AWAITING_USER,
                                            githubUserCode = code,
                                            githubVerificationUri = GITHUB_DEVICE_URL,
                                            githubMessage = "Enter this one-time code on GitHub",
                                        )
                                    }
                                    openExternalUrl(GITHUB_DEVICE_URL)
                                }
                            } else {
                                delay(100)
                            }
                        }
                        val exit = process.waitFor()
                        check(exit == 0) {
                            sanitizeTerminalOutput(captured.toString()).lineSequence().lastOrNull { it.isNotBlank() }
                                ?: "GitHub sign-in failed (exit $exit)"
                        }
                    } finally {
                        githubAuthProcess = null
                        outputFile.delete()
                    }
                    githubAccountLogin() ?: error("GitHub connected, but the account could not be identified")
                }
            }
            result.onSuccess { login ->
                preferences.githubLogin = login
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.CONNECTED,
                        githubLogin = login,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = "Connected as @$login",
                    )
                }
                refreshGitHubRepositories()
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.ERROR,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubMessage = error.message?.take(240) ?: "GitHub sign-in failed",
                    )
                }
            }
            } finally {
                stopGitHubForegroundOperation()
                githubAuthJob = null
            }
        }
    }

    fun generateNewGitHubCode() {
        githubAuthProcess?.destroy()
        githubAuthJob?.cancel()
        githubAuthProcess = null
        githubAuthJob = null
        stopGitHubForegroundOperation()
        _state.update {
            it.copy(
                githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                githubUserCode = null,
                githubVerificationUri = null,
                githubMessage = "Generating a new GitHub code…",
            )
        }
        startGitHubLogin()
    }

    fun refreshGitHubRepositories() {
        if (_state.value.githubAuthStatus != GitHubAuthStatus.CONNECTED) return
        if (_state.value.githubRepositoriesLoading) return
        _state.update { it.copy(githubRepositoriesLoading = true, githubMessage = "Loading repositories…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { githubRepositoriesFromCli() } }
            result.onSuccess { repositories ->
                _state.update {
                    it.copy(
                        githubRepositories = repositories,
                        githubRepositoriesLoading = false,
                        githubMessage = "${repositories.size} repositories available",
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        githubRepositoriesLoading = false,
                        githubMessage = error.message ?: "Could not load GitHub repositories",
                    )
                }
            }
        }
    }

    fun disconnectGitHub() {
        if (_state.value.githubAuthStatus == GitHubAuthStatus.STARTING) return
        val login = _state.value.githubLogin
        _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.STARTING, githubMessage = "Signing out of GitHub…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val command = buildList {
                        addAll(listOf("auth", "logout", "--hostname", "github.com"))
                        login?.takeIf(String::isNotBlank)?.let { addAll(listOf("--user", it)) }
                    }
                    val output = runGitHubCli(command)
                    check(output.first == 0) { output.second.lineSequence().lastOrNull { it.isNotBlank() } ?: "GitHub logout failed" }
                }
            }
            result.onSuccess {
                preferences.githubLogin = ""
                _state.update {
                    it.copy(
                        githubAuthStatus = GitHubAuthStatus.DISCONNECTED,
                        githubLogin = null,
                        githubUserCode = null,
                        githubVerificationUri = null,
                        githubRepositories = emptyList(),
                        githubMessage = "Signed out",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.ERROR, githubMessage = error.message ?: "Could not sign out") }
            }
        }
    }

    private suspend fun refreshGitHubConnection() = withContext(Dispatchers.IO) {
        if (!installer.isGitHubCliInstalled()) return@withContext
        val login = runCatching { githubAccountLogin() }.getOrNull()
        if (login.isNullOrBlank()) {
            preferences.githubLogin = ""
            _state.update { it.copy(githubAuthStatus = GitHubAuthStatus.DISCONNECTED, githubLogin = null) }
        } else {
            preferences.githubLogin = login
            _state.update {
                it.copy(
                    githubAuthStatus = GitHubAuthStatus.CONNECTED,
                    githubLogin = login,
                    githubMessage = "Connected as @$login",
                )
            }
        }
    }

    private fun githubCliEnvironment(): Map<String, String> = mapOf(
        "GH_PROMPT_DISABLED" to "1",
        "GH_NO_UPDATE_NOTIFIER" to "1",
        // Android PRoot has no Secret Service. This keeps the official gh-owned
        // credential in PocketDev's private Linux home instead of exporting it.
        "BROWSER" to "/bin/false",
    )

    private fun runGitHubCli(arguments: List<String>): Pair<Int, String> {
        check(installer.isGitHubCliInstalled()) { "GitHub CLI is not installed" }
        val runtime = installer.installedRuntime()
        val outputFile = File(getApplication<Application>().cacheDir, "github-cli-${System.nanoTime()}.log")
        val workspace = File(getApplication<Application>().filesDir, "workspaces/github-auth").apply { mkdirs() }
        return try {
            val process = installer.process(
                runtime.proot,
                runtime.rootfs,
                workspace,
                githubCliEnvironment(),
                listOf(RuntimeInstaller.GITHUB_CLI_GUEST_PATH) + arguments,
                guestWorkspacePath = "/workspace/github-auth",
                outputFile = outputFile,
            )
            val exit = process.waitFor()
            exit to sanitizeTerminalOutput(outputFile.takeIf(File::isFile)?.readText().orEmpty()).trim()
        } finally {
            outputFile.delete()
        }
    }

    private fun githubAccountLogin(): String? {
        val (exit, output) = runGitHubCli(listOf("api", "user", "--jq", ".login"))
        return output.lineSequence().lastOrNull { it.isNotBlank() }?.trim().takeIf { exit == 0 && !it.isNullOrBlank() }
    }

    private fun githubRepositoriesFromCli(): List<GitHubRepository> {
        val endpoint = "user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100"
        val (exit, output) = runGitHubCli(listOf("api", "--paginate", "--slurp", endpoint))
        check(exit == 0) { output.lineSequence().lastOrNull { it.isNotBlank() } ?: "Could not load GitHub repositories" }
        val pages = JSONArray(output)
        val repositories = LinkedHashMap<String, GitHubRepository>()
        for (pageIndex in 0 until pages.length()) {
            val page = pages.optJSONArray(pageIndex) ?: continue
            for (index in 0 until page.length()) {
                val item = page.optJSONObject(index) ?: continue
                val fullName = item.optString("full_name").takeIf(String::isNotBlank) ?: continue
                repositories[fullName] = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = item.optString("clone_url", "https://github.com/$fullName.git"),
                    private = item.optBoolean("private"),
                    defaultBranch = item.optString("default_branch", "main"),
                    description = item.optString("description"),
                    updatedAt = item.optString("updated_at"),
                )
            }
        }
        return repositories.values.toList()
    }

    /**
     * Opens an automatically discovered link at most once: the CLI's own browser request, the
     * controller callback, and a retry can all report the same link within moments.
     */
    private fun openExternalUrlOnce(url: String) {
        val now = SystemClock.elapsedRealtime()
        if (url == lastAutoOpenedUrl && now - lastAutoOpenedAtMillis < AUTO_OPEN_DEDUPE_MS) return
        lastAutoOpenedUrl = url
        lastAutoOpenedAtMillis = now
        openExternalUrl(url)
    }

    private fun openExternalUrl(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            _state.update { state -> state.copy(toastMessage = "Could not open the browser. Copy the URL instead.") }
        }
    }

    private fun startGitHubForegroundOperation() {
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_START)
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_PROJECT_NAME, "GitHub sign-in")
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_TITLE, "Connecting GitHub")
                .putExtra(com.jarves.mh.runtime.RuntimeExecutionService.EXTRA_CAN_STOP, false),
        )
    }

    private fun stopGitHubForegroundOperation() {
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java)
                    .setAction(com.jarves.mh.runtime.RuntimeExecutionService.ACTION_CANCELLED),
            )
        }.onFailure {
            getApplication<Application>().stopService(
                Intent(getApplication(), com.jarves.mh.runtime.RuntimeExecutionService::class.java),
            )
        }
    }

    fun renameProject(projectId: String, newName: String) {
        val clean = newName.replace(Regex("\\s+"), " ").trim().take(60)
        if (clean.isBlank()) return
        _state.update { current ->
            val projects = current.projects.map { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            val active = current.activeProject?.let { project ->
                if (project.id == projectId) project.copy(name = clean) else project
            }
            current.copy(projects = projects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    fun deleteProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        if (_state.value.activeProject?.id == projectId || _state.value.isRunning || _state.value.projectTerminalRunning) return
        _state.update { current -> current.copy(projects = current.projects.filterNot { it.id == projectId }) }
        preferences.saveProjects(_state.value.projects)
        viewModelScope.launch(Dispatchers.IO) {
            val filesDir = getApplication<Application>().filesDir
            File(filesDir, "workspaces/${project.id}").deleteRecursively()
            terminalHistoryFile(project.id).delete()
            preferences.deleteProjectChats(project.id)
        }
    }

    private fun detectNestedProjectRoot(project: Project): String? {
        val base = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
        if (!base.isDirectory) return null
        val visible = base.listFiles().orEmpty().filterNot { file ->
            file.name == ".claude" || file.name == ".claude.json"
        }
        // A symlink is never a project root, and its target may lie outside the workspace.
        val onlyDirectory = visible.singleOrNull()
            ?.takeIf { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            ?: return null
        val containsProjectFiles = onlyDirectory.walkTopDown()
            .maxDepth(2)
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .any { it.isFile && it.name !in setOf(".DS_Store", ".claude.json") }
        return onlyDirectory.name.takeIf { containsProjectFiles && !it.contains("..") }
    }

    fun useSuggestedProjectRoot() {
        val current = _state.value
        val project = current.activeProject ?: return
        val root = current.suggestedProjectRoot ?: return
        if (current.isRunning || current.projectTerminalRunning) return
        val updated = project.copy(rootPath = root)
        configureBridgeRoots(updated.id, updated.rootPath)
        val projects = current.projects.map { if (it.id == updated.id) updated else it }
        val guestRoot = projectGuestRoot(updated)
        preferences.saveProjects(projects)
        saveProjectTerminal(updated.id, guestRoot, current.projectTerminalLines)
        _state.update {
            it.copy(
                projects = projects,
                activeProject = updated,
                suggestedProjectRoot = null,
                projectTerminalCwd = guestRoot,
                changes = emptyList(),
                toastMessage = "$root is now the project root",
            )
        }
        refreshProjectFiles()
    }

    fun exportActiveProject(uri: Uri) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.projectTerminalRunning) {
            _state.update { it.copy(toastMessage = "Stop the running task before exporting") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val root = projectWorkspaceRoot(project)
                    val rootPath = root.canonicalFile.toPath()
                    val output = getApplication<Application>().contentResolver.openOutputStream(uri)
                        ?: error("The selected location could not be opened")
                    output.buffered().use { stream ->
                        ZipOutputStream(stream).use { zip ->
                            zip.putNextEntry(ZipEntry("${project.slug}/"))
                            zip.closeEntry()
                            root.walkTopDown()
                                .onEnter { directory ->
                                    if (directory == root) {
                                        true
                                    } else {
                                        val relative = directory.relativeTo(root).invariantSeparatorsPath
                                        !isExportExcludedPath(relative) &&
                                            !Files.isSymbolicLink(directory.toPath()) &&
                                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                                    }
                                }
                                .drop(1)
                                .filter { file ->
                                    !Files.isSymbolicLink(file.toPath()) &&
                                        runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false) &&
                                        !isExportExcludedPath(file.relativeTo(root).invariantSeparatorsPath)
                                }
                                .forEach { file ->
                                    val relative = file.relativeTo(root).invariantSeparatorsPath
                                    val entryName = "${project.slug}/$relative" + if (file.isDirectory) "/" else ""
                                    zip.putNextEntry(ZipEntry(entryName).apply { time = file.lastModified() })
                                    if (file.isFile) file.inputStream().buffered().use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                        }
                    }
                }
            }
            _state.update {
                it.copy(
                    toastMessage = result.fold(
                        onSuccess = { "${project.slug}.zip exported" },
                        onFailure = { error -> "Export failed: ${error.message ?: "Unknown error"}" },
                    ),
                )
            }
        }
    }

    fun createChat() {
        val project = _state.value.activeProject ?: return
        if (_state.value.isRunning) return
        // The saved chat index is only known once it has loaded. Writing it earlier would replace the saved chats.
        if (_state.value.chatLoading || _state.value.projectChats.isEmpty()) {
            _state.update { it.copy(toastMessage = "Chat history has not loaded yet.") }
            return
        }
        persistMessages()
        // An open still in flight must not publish over the chat created here.
        projectOpenGeneration++
        val chat = ProjectChat()
        val chats = listOf(chat) + _state.value.projectChats
        preferences.saveProjectChats(project.id, chats)
        _state.update {
            it.copy(
                projectChats = chats,
                activeChatId = chat.id,
                messages = listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")),
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    fun switchChat(chatId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        if (current.isRunning || current.activeChatId == chatId) return
        val chat = current.projectChats.firstOrNull { it.id == chatId } ?: return
        persistMessages()
        val saved = preferences.loadMessages(project.id, chat.id)
        _state.update {
            it.copy(
                activeChatId = chat.id,
                messages = saved.ifEmpty { listOf(ChatMessage(fromUser = false, text = "Hi! Tell me what you want to build or change.")) },
                liveProcess = emptyList(),
                liveThinking = false,
                taskStartedAtMillis = null,
                taskFinishedAtMillis = null,
                pendingApproval = null,
                pendingAttachments = emptyList(),
            )
        }
    }

    /**
     * Reads the project's rules, skills and customization config from disk and returns the state change that
     * shows them. The caller applies it, so the disk work can run off Main and the publish can be guarded.
     */
    private fun customizationUpdate(project: Project): (AppUiState) -> AppUiState {
        val rootfsDir = runCatching { installer.installedRuntime().rootfs }.getOrNull()
        val workspacesBase = File(getApplication<Application>().filesDir, "workspaces")
        val workspaceDir = projectWorkspaceRoot(project)

        val config = preferences.loadProjectCustomizationConfig(project.id)
        val allRules = skillManager.discoverRules(workspaceDir, rootfsDir)
        val projectRulesList = allRules.filter { it.source == RuleSource.PROJECT }
        val globalRulesList = allRules.filter { it.source != RuleSource.PROJECT }
        val activeRules = skillManager.resolveActiveRules(projectRulesList, globalRulesList, config)

        val otherSkills = skillManager.discoverAllProjectsSkills(_state.value.projects, project.id, workspacesBase)
        val globalSkills = skillManager.discoverGlobalAndLinuxSkills(rootfsDir) + skillManager.discoverBundledSkills()
        val activeSkills = skillManager.compileActiveProjectSkills(project, config, _state.value.projects, workspacesBase, rootfsDir)
        val legacyRules = skillManager.loadProjectRules(workspaceDir)

        return { state ->
            state.copy(
                activeCustomizationConfig = config,
                activeRules = activeRules,
                projectRulesList = projectRulesList,
                globalRulesList = globalRulesList,
                activeSkills = activeSkills,
                otherProjectsSkills = otherSkills,
                globalSkills = globalSkills,
                projectRules = legacyRules,
            )
        }
    }

    /**
     * Re-reads the project's rules, skills and customization config off Main, then publishes them. Only the
     * newest reload publishes, and only while the same project root is still active.
     */
    fun reloadCustomizations(project: Project) {
        val generation = ++customizationsGeneration
        viewModelScope.launch {
            val loaded = withContext(ioDispatcher) { runRequest { customizationUpdate(project) } }
            if (generation != customizationsGeneration || !isCurrentProjectRoot(project)) return@launch
            loaded
                .onSuccess { change -> _state.update(change) }
                .onFailure { error ->
                    _state.update { it.copy(toastMessage = "Could not load customizations: ${error.message}") }
                }
        }
    }

    /**
     * Saves [config] and shows it at once, so a change made before the scan finishes builds on it. The scan
     * itself runs off Main through [reloadCustomizations].
     */
    private fun saveCustomizationConfig(project: Project, config: ProjectCustomizationConfig) {
        preferences.saveProjectCustomizationConfig(config)
        _state.update { it.copy(activeCustomizationConfig = config) }
        reloadCustomizations(project)
    }

    /** True while [project] is still the active project at the same root, so a late result may be published. */
    private fun isCurrentProjectRoot(project: Project): Boolean {
        val active = _state.value.activeProject ?: return false
        return active.id == project.id && active.rootPath == project.rootPath
    }

    fun refreshProjectFiles() {
        val project = _state.value.activeProject ?: return
        val generation = ++projectFilesGeneration
        _state.update { it.copy(filesLoading = true) }
        viewModelScope.launch {
            try {
                val workspaceDir = withContext(ioDispatcher) { projectWorkspaceRoot(project) }
                val entries = withContext(ioDispatcher) { readWorkspace(project) }
                val suggestedRoot = withContext(ioDispatcher) { if (project.rootPath.isBlank()) detectNestedProjectRoot(project) else null }
                val androidProjectDetected = withContext(ioDispatcher) { findAndroidGradleProjectRoot(workspaceDir) != null }
                // Publish only if this is still the newest refresh and the same project root is still active.
                if (generation == projectFilesGeneration && isCurrentProjectRoot(project)) {
                    _state.update {
                        it.copy(
                            workspaceFiles = entries,
                            filesLoading = false,
                            suggestedProjectRoot = suggestedRoot,
                            androidProjectDetected = androidProjectDetected,
                        )
                    }
                    // Customizations reload under their own generation rather than riding along with these files.
                    reloadCustomizations(project)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A failed refresh is recorded as a message instead of escaping viewModelScope.
                if (generation == projectFilesGeneration) {
                    _state.update { it.copy(toastMessage = "Could not load project files: ${error.message}") }
                }
            } finally {
                // Only the newest refresh owns the spinner, so an older one must not clear it early.
                if (generation == projectFilesGeneration) {
                    _state.update { it.copy(filesLoading = false) }
                }
            }
        }
    }

    fun installApk(entry: WorkspaceEntry) {
        val project = _state.value.activeProject ?: return
        val apkFile = File(projectWorkspaceRoot(project), entry.path)
        if (!apkFile.isFile || !apkFile.name.endsWith(".apk", ignoreCase = true)) return
        runCatching {
            AndroidAppInstaller.install(getApplication(), apkFile)
            _state.update { it.copy(toastMessage = "Opening package installer for ${apkFile.name}…") }
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = "Could not install APK: ${error.message}") }
        }
    }

    fun openFile(entry: WorkspaceEntry) {
        if (entry.isDirectory) return
        if (entry.name.endsWith(".apk", ignoreCase = true)) {
            installApk(entry)
            return
        }
        val project = _state.value.activeProject ?: return
        cancelFileRead()
        val request = fileReadGeneration
        _state.update { it.copy(openedFilePath = entry.path, openedFileContent = null, fileContentLoading = true) }
        fileReadJob = viewModelScope.launch {
            val content = withContext(ioDispatcher) {
                runRequest {
                    val file = File(projectWorkspaceRoot(project), entry.path)
                    if (file.length() > 512_000L) {
                        file.inputStream().use { stream ->
                            val buf = ByteArray(512_000)
                            val read = stream.read(buf)
                            String(buf, 0, read)
                        } + "\n\n[File truncated — too large to display fully]"
                    } else {
                        file.readText()
                    }
                }.getOrElse { "Could not read file: ${it.message}" }
            }
            // A newer selection or a close happened while this file was read.
            if (request != fileReadGeneration) return@launch
            _state.update { it.copy(openedFileContent = content, fileContentLoading = false) }
        }
    }

    fun closeFile() {
        cancelFileRead()
        _state.update { it.copy(openedFilePath = null, openedFileContent = null, fileContentLoading = false) }
    }

    /** Cancels the file read in flight. The generation bump keeps a read that is already running from publishing. */
    private fun cancelFileRead() {
        fileReadGeneration++
        fileReadJob?.cancel()
        fileReadJob = null
    }

    /**
     * Runs one request. Cancellation propagates so the scope can stop; any other failure comes back as a
     * failed Result instead of escaping viewModelScope.
     */
    private inline fun <T> runRequest(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

    private val skipIntermediateDirNames = setOf(
        "intermediates", "tmp", "generated", "transforms",
        "extracted-include-protos", ".cxx", ".gradle", ".kotlin",
        ".idea", "node_modules", "__pycache__", ".venv", "classes", "dex", "kotlin-classes"
    )

    private fun readWorkspace(project: Project): List<WorkspaceEntry> {
        val root = projectWorkspaceRoot(project)
        if (!root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { directory ->
                if (directory == root) return@onEnter true
                val dirName = directory.name.lowercase(Locale.ROOT)
                val relative = directory.relativeTo(root).invariantSeparatorsPath
                if (dirName in skipIntermediateDirNames) return@onEnter false
                if (dirName == ".git" || relative.startsWith(".git/") || relative == ".git") return@onEnter false
                if (isClaudeRuntimeMetadata(relative)) return@onEnter false
                if (Files.isSymbolicLink(directory.toPath())) return@onEnter false
                runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .drop(1)
            .filter { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val name = file.name.lowercase(Locale.ROOT)
                if (name in skipIntermediateDirNames) return@filter false
                if (relative == ".git" || relative.startsWith(".git/")) return@filter false
                if (isClaudeRuntimeMetadata(relative)) return@filter false
                if (Files.isSymbolicLink(file.toPath())) return@filter false
                runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
            }
            .take(MAX_VISIBLE_WORKSPACE_ENTRIES)
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                WorkspaceEntry(
                    path = relative,
                    name = file.name,
                    isDirectory = file.isDirectory,
                    depth = relative.count { it == '/' },
                    sizeBytes = if (file.isFile) file.length() else 0,
                )
            }
            .sortedWith(
                compareBy<WorkspaceEntry> { it.path.substringBeforeLast('/', "") }
                    .thenByDescending { it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
            .toList()
    }

    private fun isClaudeRuntimeMetadata(relativePath: String): Boolean {
        return relativePath == ".claude" ||
            relativePath == ".claude.json" ||
            relativePath.startsWith(".claude/")
    }

    private fun isExportExcludedPath(relativePath: String): Boolean {
        val excludedNames = setOf(
            ".git", ".claude", ".gradle", ".idea", ".next", ".cache",
            "node_modules", ".venv", "venv", "__pycache__", "build",
        )
        return relativePath.split('/').any { it in excludedNames } ||
            isClaudeRuntimeMetadata(relativePath) ||
            isAttachmentStoragePath(relativePath)
    }

    fun addChatAttachments(uris: List<Uri>) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        if (current.isRunning || uris.isEmpty()) return
        val importMessages = current.messages
        val remaining = (MAX_ATTACHMENTS_PER_MESSAGE - current.pendingAttachments.size).coerceAtLeast(0)
        if (remaining == 0) {
            _state.update { it.copy(toastMessage = "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files per message") }
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val added = mutableListOf<ChatAttachment>()
                val errors = mutableListOf<String>()
                uris.take(remaining).forEach { uri ->
                    runCatching { copyChatAttachment(project, chatId, uri) }
                        .onSuccess(added::add)
                        .onFailure { errors += (it.message ?: "Could not attach file") }
                }
                added to errors
            }
            val (added, errors) = result
            var acceptedIds = emptySet<String>()
            _state.update { state ->
                if (state.activeProject?.id != project.id || state.activeChatId != chatId ||
                    state.isRunning || state.messages !== importMessages) return@update state
                val capacity = (MAX_ATTACHMENTS_PER_MESSAGE - state.pendingAttachments.size).coerceAtLeast(0)
                val accepted = added.take(capacity)
                acceptedIds = accepted.map { it.id }.toSet()
                state.copy(
                    pendingAttachments = state.pendingAttachments + accepted,
                    toastMessage = errors.firstOrNull() ?: if (added.size > capacity || uris.size > remaining)
                        "You can attach up to $MAX_ATTACHMENTS_PER_MESSAGE files per message" else null,
                )
            }
            withContext(Dispatchers.IO) {
                val root = projectWorkspaceRoot(project).canonicalFile
                added.filterNot { it.id in acceptedIds }.forEach { attachment ->
                    val file = File(root, attachment.relativePath).canonicalFile
                    if (file.toPath().startsWith(root.toPath())) file.delete()
                }
            }
            if (acceptedIds.isNotEmpty() && _state.value.activeProject?.id == project.id) refreshProjectFiles()
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val current = _state.value
        val project = current.activeProject ?: return
        val attachment = current.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { item -> item.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = projectWorkspaceRoot(project)
            val file = File(root, attachment.relativePath).canonicalFile
            if (file.toPath().startsWith(root.canonicalFile.toPath())) file.delete()
        }
    }

    fun openChatAttachment(attachment: ChatAttachment) {
        val project = _state.value.activeProject ?: return
        runCatching {
            val root = projectWorkspaceRoot(project).canonicalFile
            val file = File(root, attachment.relativePath).canonicalFile
            require(file.isFile && file.toPath().startsWith(root.toPath())) { "Attachment is unavailable" }
            val app = getApplication<Application>()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, attachment.mimeType.ifBlank { "application/octet-stream" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            app.startActivity(intent)
        }.onFailure { error ->
            _state.update { it.copy(toastMessage = error.message ?: "No app can open this attachment") }
        }
    }

    private fun copyChatAttachment(project: Project, chatId: String, uri: Uri): ChatAttachment {
        val resolver = getApplication<Application>().contentResolver
        var reportedName: String? = null
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { reportedName = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declaredSize = cursor.getLong(it) }
            }
        }
        val displayName = cleanAttachmentName(reportedName ?: uri.lastPathSegment)
            .ifBlank { "attachment-${UUID.randomUUID().toString().take(8)}" }
        require(declaredSize <= MAX_ATTACHMENT_BYTES) { attachmentTooLargeMessage(displayName) }
        val mimeType = resolveAttachmentMimeType(resolver.getType(uri), attachmentExtension(displayName)) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
        }
        val root = projectWorkspaceRoot(project).canonicalFile
        val folder = File(root, "$ATTACHMENTS_DIRECTORY/$chatId").apply { mkdirs() }.canonicalFile
        require(folder.toPath().startsWith(root.toPath())) { "Unsafe attachment folder" }
        val storedName = storedAttachmentName(displayName)
        val (stem, storedExtension) = splitAttachmentName(storedName)
        var destination = File(folder, storedName)
        var suffix = 2
        while (destination.exists()) destination = File(folder, "$stem-${suffix++}$storedExtension")
        var copied = 0L
        try {
            val input = resolver.openInputStream(uri) ?: error("Could not read $displayName")
            input.buffered().use { source ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_ATTACHMENT_BYTES) { attachmentTooLargeMessage(displayName) }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(copied > 0) { "$displayName is empty" }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
        return ChatAttachment(
            displayName = displayName,
            relativePath = destination.relativeTo(root).invariantSeparatorsPath,
            mimeType = mimeType,
            sizeBytes = copied,
        )
    }

    private fun sanitizeAttachmentName(name: String): String {
        val clean = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().trim('.').take(100)
        return clean.ifBlank { "attachment-${UUID.randomUUID().toString().take(8)}" }
    }

    fun onPromptChanged(newPrompt: String, cursorPosition: Int = newPrompt.length) {
        val trigger = TriggerParser.parseTrigger(newPrompt, cursorPosition)
        when (trigger.type) {
            TriggerType.SLASH_COMMAND -> {
                val query = trigger.query
                val filteredCmds = SlashCommandEngine.filterCommands(query, _state.value.agentKind)
                val filteredSkills = SlashCommandEngine.filterSkills(query, _state.value.activeSkills)
                _state.update {
                    it.copy(
                        slashCommandsVisible = filteredCmds.isNotEmpty() || filteredSkills.isNotEmpty(),
                        slashCommandQuery = query,
                        filteredSlashCommands = filteredCmds,
                        filteredSkills = filteredSkills,
                        mentionMenuVisible = false,
                    )
                }
            }
            TriggerType.FILE_MENTION -> {
                val query = trigger.query.lowercase(Locale.ROOT)
                val files = _state.value.workspaceFiles.filter { entry ->
                    query.isEmpty() || entry.name.lowercase(Locale.ROOT).contains(query) || entry.path.lowercase(Locale.ROOT).contains(query)
                }.take(8)
                _state.update {
                    it.copy(
                        mentionMenuVisible = files.isNotEmpty(),
                        filteredMentionEntries = files,
                        slashCommandsVisible = false,
                        filteredSkills = emptyList(),
                    )
                }
            }
            else -> {
                _state.update {
                    it.copy(
                        slashCommandsVisible = false,
                        filteredSkills = emptyList(),
                        mentionMenuVisible = false,
                    )
                }
            }
        }
    }

    fun dismissMentionMenu() {
        _state.update { it.copy(mentionMenuVisible = false) }
    }

    fun dismissSlashCommands() {
        _state.update { it.copy(slashCommandsVisible = false, filteredSkills = emptyList()) }
    }

    fun toggleAuxiliaryInspector(visible: Boolean? = null) {
        _state.update { it.copy(auxiliaryInspectorVisible = visible ?: !it.auxiliaryInspectorVisible) }
    }

    fun terminateSubagent(conversationId: String) {
        _state.update { it.copy(toastMessage = "This harness does not support stopping an individual subagent. Use Stop to cancel the full task.") }
    }

    fun clearCompletedSubagents() {
        _state.update { current ->
            current.copy(subagents = SubagentRegistry.clearCompleted(current.subagents))
        }
    }

    fun terminateBackgroundTask(taskId: String) {
        _state.update { it.copy(toastMessage = "This harness does not support stopping an individual background task. Use Stop to cancel the full task.") }
    }

    fun clearCompletedTasks() {
        _state.update { current ->
            current.copy(backgroundTasks = TaskRegistry.clearCompleted(current.backgroundTasks))
        }
    }

    /** Opens the subagent transcript viewer in the Inspector sheet. */
    fun selectSubagentForLogs(subagent: SubagentInfo?) {
        _state.update { it.copy(selectedSubagentForLogs = subagent) }
    }

    /** Opens the task terminal output viewer in the Inspector sheet. */
    fun selectTaskForLogs(task: BackgroundTaskInfo?) {
        _state.update { it.copy(selectedTaskForLogs = task) }
    }

    fun toggleSkillsManager(visible: Boolean? = null) {
        _state.update { it.copy(skillsManagerVisible = visible ?: !it.skillsManagerVisible) }
    }

    fun toggleModelPicker(visible: Boolean? = null) {
        _state.update { current ->
            val open = visible ?: !current.modelPickerVisible
            current.copy(
                modelPickerVisible = open,
                providerModels = if (open) savedModelsFor(current.agentKind, current.provider) else current.providerModels,
            )
        }
    }

    fun toggleEffortPicker(visible: Boolean? = null) {
        _state.update { current ->
            val open = visible ?: !current.effortPickerVisible
            current.copy(
                effortPickerVisible = open,
                providerModels = if (open) savedModelsFor(current.agentKind, current.provider) else current.providerModels,
            )
        }
    }

    /** Picker choice: applies it and closes the picker. A refusal shows as a toast. */
    fun chooseEffortFromPicker(value: String) {
        toggleEffortPicker(false)
        val change = applyEffortChoice(value)
        if (change is EffortChange.Refused) _state.update { it.copy(toastMessage = change.reason) }
    }

    /** Applies an /effort argument to the active agent. Codex levels come from the selected model's saved list. */
    fun applyEffortChoice(choice: String): EffortChange {
        val current = _state.value
        val agent = current.agentKind
        val codexLevels = codexModelLevelsNow()
        val levels = effortLevelsFor(agent, codexLevels)
            ?: return EffortChange.Refused("${agent.title} doesn't have an effort setting yet.")
        val value = normalizeEffortChoice(agent, choice, codexLevels)
            ?: return EffortChange.Refused(
                buildString {
                    append("`${choice.trim()}` isn't an effort level here. Choose one of: ${levels.joinToString(", ")}.")
                    if (agent == AgentKind.CODEX && codexLevels.isEmpty()) {
                        append(" Discover models in Settings to see the levels this model supports.")
                    }
                },
            )
        when (agent) {
            AgentKind.CLAUDE_CODE -> setClaudeThinkingLevel(value)
            AgentKind.ANTIGRAVITY -> if (!setAntigravityEffort(value)) {
                return EffortChange.Refused("This model does not offer ${effortLabel(agent, value)} reasoning.")
            }
            AgentKind.CODEX -> setCodexReasoningEffort(value)
            AgentKind.DEEPSEEK_HARNESS -> {
                preferences.dshReasoningEffort = value
                _state.update { it.copy(dshReasoningEffort = value) }
            }
        }
        return EffortChange.Applied(effortLabel(agent, value))
    }

    /** Refreshes the Usage section. Manual only: nothing polls. */
    fun refreshUsage() {
        if (_state.value.usageRefreshing) return
        _state.update { it.copy(usageRefreshing = true) }
        viewModelScope.launch {
            val usage = loadUsage()
            _state.update { it.copy(usage = usage, usageRefreshing = false) }
        }
    }

    /** Reads the active agent's plan usage. Network work runs on IO; failures come back as a note, never a throw. */
    private suspend fun loadUsage(): com.jarves.mh.model.AgentUsage {
        val current = _state.value
        val usage = when (current.agentKind) {
            AgentKind.CODEX -> withContext(Dispatchers.IO) { codexModelCatalog.fetchUsage() }
            AgentKind.CLAUDE_CODE -> com.jarves.mh.runtime.ClaudeUsageReport.parse(preferences.claudeRateLimitEvent).let {
                if (preferences.claudeRateLimitEvent.isNullOrBlank()) it else it.copy(
                    note = "Last CLI report; Refresh reads cached limits.",
                    checkedAtMillis = preferences.claudeRateLimitReportedAtMillis.takeIf { time -> time > 0 },
                )
            }
            AgentKind.ANTIGRAVITY -> {
                val usedAccountId = current.activeProject?.id?.let { projectId ->
                    current.activeChatId?.let { preferences.loadAgentConversationAccount(projectId, it) }
                }
                com.jarves.mh.runtime.AntigravityUsageReport.from(
                    current.antigravityAccounts.firstOrNull { it.id == usedAccountId }
                        ?: current.antigravityAccounts.firstOrNull { it.isPrimary } ?: current.antigravityAccounts.firstOrNull(),
                )
            }
            AgentKind.DEEPSEEK_HARNESS -> if (current.provider.kind == ProviderKind.DEEPSEEK) {
                val key = vault.get(current.provider.secretId).orEmpty()
                if (key.isBlank()) {
                    com.jarves.mh.model.AgentUsage(note = "Save a DeepSeek API key to see your balance.")
                } else {
                    withContext(Dispatchers.IO) { com.jarves.mh.runtime.DeepSeekBalanceReport.fetch(key) }
                }
            } else {
                com.jarves.mh.model.AgentUsage(note = "Balance is shown for the DeepSeek provider only.")
            }
        }
        return if (current.agentKind == AgentKind.CLAUDE_CODE) usage else usage.copy(checkedAtMillis = System.currentTimeMillis())
    }

    /** Codex levels the selected model lists in the saved catalog. Empty for other agents or when unknown. */
    private fun codexModelLevelsNow(): List<String> {
        val current = _state.value
        if (current.agentKind != AgentKind.CODEX) return emptyList()
        return com.jarves.mh.model.codexEffortChoices(savedModelsFor(current.agentKind, current.provider), current.provider.model)
    }

    /** Model lists saved by Discover in Settings, for this agent's provider and endpoint. */
    fun savedModelsFor(agent: AgentKind, provider: ProviderProfile): List<com.jarves.mh.network.DiscoveredModel> =
        preferences.loadModelList(agent, provider.kind, provider.baseUrl)

    fun savedModelList(kind: ProviderKind, baseUrl: String): List<com.jarves.mh.network.DiscoveredModel> =
        preferences.loadModelList(_state.value.agentKind, kind, baseUrl)

    fun setCustomizationScopeMode(mode: CustomizationScopeMode) {
        val project = _state.value.activeProject ?: return
        val currentConfig = _state.value.activeCustomizationConfig
        val newConfig = currentConfig.copy(scopeMode = mode)
        saveCustomizationConfig(project, newConfig)
    }

    fun toggleRule(ruleId: String) {
        val project = _state.value.activeProject ?: return
        val currentConfig = _state.value.activeCustomizationConfig
        val newConfig = if (currentConfig.scopeMode == CustomizationScopeMode.CUSTOM) {
            val enabled = currentConfig.enabledRuleIds.toMutableSet()
            if (enabled.contains(ruleId)) enabled.remove(ruleId) else enabled.add(ruleId)
            currentConfig.copy(enabledRuleIds = enabled)
        } else {
            val disabled = currentConfig.disabledRuleIds.toMutableSet()
            if (disabled.contains(ruleId)) disabled.remove(ruleId) else disabled.add(ruleId)
            currentConfig.copy(disabledRuleIds = disabled)
        }
        saveCustomizationConfig(project, newConfig)
    }

    fun toggleSkill(skillId: String) {
        val project = _state.value.activeProject ?: return
        val currentConfig = _state.value.activeCustomizationConfig
        val newConfig = if (currentConfig.scopeMode == CustomizationScopeMode.CUSTOM) {
            val enabled = currentConfig.enabledSkillIds.toMutableSet()
            if (enabled.contains(skillId)) enabled.remove(skillId) else enabled.add(skillId)
            currentConfig.copy(enabledSkillIds = enabled)
        } else {
            val disabled = currentConfig.disabledSkillIds.toMutableSet()
            if (disabled.contains(skillId)) disabled.remove(skillId) else disabled.add(skillId)
            currentConfig.copy(disabledSkillIds = disabled)
        }
        saveCustomizationConfig(project, newConfig)
    }

    fun linkSkill(sourceProjectId: String, skillName: String, relativeSkillPath: String = ".agents/skills/$skillName") {
        val project = _state.value.activeProject ?: return
        val sourceProj = _state.value.projects.firstOrNull { it.id == sourceProjectId }
        val sourceProjectName = sourceProj?.name ?: "Other Project"
        val currentConfig = _state.value.activeCustomizationConfig
        if (currentConfig.linkedSkills.any { it.sourceProjectId == sourceProjectId && it.skillName.equals(skillName, ignoreCase = true) }) {
            _state.update { it.copy(toastMessage = "Skill '$skillName' is already linked.") }
            return
        }
        val newRef = LinkedSkillReference(
            sourceProjectId = sourceProjectId,
            sourceProjectName = sourceProjectName,
            skillName = skillName,
            relativeSkillPath = relativeSkillPath,
        )
        val newConfig = currentConfig.copy(linkedSkills = currentConfig.linkedSkills + newRef)
        saveCustomizationConfig(project, newConfig)
        _state.update { it.copy(toastMessage = "Linked '$skillName' from $sourceProjectName.") }
    }

    fun unlinkSkill(linkIdOrSkillName: String) {
        val project = _state.value.activeProject ?: return
        val currentConfig = _state.value.activeCustomizationConfig
        val newLinks = currentConfig.linkedSkills.filter {
            it.id != linkIdOrSkillName && !it.skillName.equals(linkIdOrSkillName, ignoreCase = true) &&
                "linked:${it.sourceProjectId}:${it.skillName}" != linkIdOrSkillName
        }
        val newConfig = currentConfig.copy(linkedSkills = newLinks)
        saveCustomizationConfig(project, newConfig)
        _state.update { it.copy(toastMessage = "Unlinked skill.") }
    }

    fun importSkill(sourceSkillDir: File, skillName: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val workspace = projectWorkspaceRoot(project)
                skillManager.importSkillToProject(sourceSkillDir, workspace, skillName)
                withContext(Dispatchers.Main) {
                    reloadCustomizations(project)
                    _state.update { it.copy(toastMessage = "Imported '$skillName' into workspace.") }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(toastMessage = "Failed to import skill: ${err.message}") }
                }
            }
        }
    }

    fun promoteSkillToGlobal(sourceSkillDir: File, skillName: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val rootfsDir = runCatching { installer.installedRuntime().rootfs }.getOrNull()
                skillManager.promoteSkillToGlobal(sourceSkillDir, skillName, rootfsDir)
                withContext(Dispatchers.Main) {
                    reloadCustomizations(project)
                    _state.update { it.copy(toastMessage = "Promoted '$skillName' to Global Library.") }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(toastMessage = "Failed to promote skill: ${err.message}") }
                }
            }
        }
    }

    fun promoteRuleToGlobal(sourceRuleFile: File, ruleFileName: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val rootfsDir = runCatching { installer.installedRuntime().rootfs }.getOrNull()
                skillManager.promoteRuleToGlobal(sourceRuleFile, ruleFileName, rootfsDir)
                withContext(Dispatchers.Main) {
                    reloadCustomizations(project)
                    _state.update { it.copy(toastMessage = "Promoted rule '$ruleFileName' to Global Library.") }
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(toastMessage = "Failed to promote rule: ${err.message}") }
                }
            }
        }
    }

    fun saveProjectRule(fileName: String, content: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val workspace = projectWorkspaceRoot(project)
            val file = File(workspace, fileName)
            skillManager.saveProjectRule(file, content, workspace)
            withContext(Dispatchers.Main) {
                reloadCustomizations(project)
                _state.update { it.copy(toastMessage = "Saved $fileName.") }
            }
        }
    }

    fun createGlobalSkill(name: String, description: String, instructions: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            skillManager.createGlobalSkill(name, description, instructions)
            withContext(Dispatchers.Main) {
                reloadCustomizations(project)
                _state.update { it.copy(toastMessage = "Skill '$name' created.") }
            }
        }
    }

    private fun executeLocalSlashCommand(command: SlashCommand, args: String) {
        _state.update { it.copy(slashCommandsVisible = false) }
        when (command.name.lowercase(Locale.ROOT)) {
            "help" -> {
                val help = DiagnosticsHelper.buildHelpMessage(_state.value.agentKind)
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(fromUser = true, text = "/help") +
                            ChatMessage(fromUser = false, text = help),
                    )
                }
                persistMessages()
            }
            "clear" -> {
                val owner = _state.value
                owner.activeProject?.let { project ->
                    owner.activeChatId?.let { chat ->
                        AgentKind.entries.forEach { preferences.clearAgentConversation(it, project.id, chat) }
                    }
                }
                _state.update {
                    it.copy(
                        messages = listOf(ChatMessage(fromUser = false, text = "Conversation history cleared. Workspace files are preserved.")),
                        subagents = emptyList(),
                        backgroundTasks = emptyList(),
                        artifacts = emptyList(),
                        tokenMetrics = SessionTokenMetrics(),
                        toastMessage = "Conversation cleared.",
                    )
                }
                persistMessages()
            }
            "cost" -> {
                val cost = DiagnosticsHelper.buildCostMessage(_state.value.tokenMetrics, _state.value.agentKind)
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(fromUser = true, text = "/cost") +
                            ChatMessage(fromUser = false, text = cost),
                    )
                }
                persistMessages()
            }
            "doctor" -> {
                val userMsg = ChatMessage(fromUser = true, text = "/doctor")
                val pendingMsg = ChatMessage(fromUser = false, text = "Running system diagnostics…")
                _state.update { it.copy(messages = it.messages + userMsg + pendingMsg) }
                viewModelScope.launch {
                    val report = DiagnosticsHelper.runDoctor(getApplication(), _state.value.agentKind)
                    _state.update { current ->
                        current.copy(
                            messages = current.messages.dropLast(1) + ChatMessage(fromUser = false, text = report),
                        )
                    }
                    persistMessages()
                }
            }
            "model" -> {
                val trimmed = args.trim()
                if (trimmed.isNotBlank()) {
                    val reply = when (applyModelChoice(trimmed)) {
                        ModelChoiceCheck.APPLIED -> "Switched model to `$trimmed`."
                        ModelChoiceCheck.UNCHECKED ->
                            "Switched model to `$trimmed`. ${_state.value.agentKind.title} has no model list to check it against."
                        ModelChoiceCheck.REJECTED ->
                            "`$trimmed` is not in the ${_state.value.agentKind.title} model list. Choose one from /model, or run Discover in Settings to refresh it."
                    }
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(fromUser = true, text = "/model $trimmed") +
                                ChatMessage(fromUser = false, text = reply),
                        )
                    }
                    persistMessages()
                } else {
                    toggleModelPicker(true)
                }
            }
            "usage" -> {
                val pending = ChatMessage(fromUser = false, text = "Checking usage…")
                _state.update { it.copy(messages = it.messages + ChatMessage(fromUser = true, text = "/usage") + pending) }
                viewModelScope.launch {
                    val usage = loadUsage()
                    _state.update { current ->
                        val summary = com.jarves.mh.model.usageSummary(current.agentKind, current.tokenMetrics, usage)
                        current.copy(
                            usage = usage,
                            messages = current.messages.dropLast(1) + ChatMessage(fromUser = false, text = summary),
                        )
                    }
                    persistMessages()
                }
            }
            "turns" -> {
                val value = args.trim().toIntOrNull()
                val valid = value != null && value in 1..200
                if (valid) preferences.claudeMaxTurns = requireNotNull(value)
                val text = if (args.isNotBlank() && !valid) "Use /turns with a number from 1 to 200."
                    else "Claude's turn budget is ${preferences.claudeMaxTurns}. Applies to the next run."
                _state.update { it.copy(messages = it.messages + ChatMessage(fromUser = false, text = text)) }
                persistMessages()
            }
            "approvals" -> {
                val choice = args.trim().lowercase(Locale.ROOT)
                if (choice == "on" || choice == "off") preferences.claudeInteractiveApprovals = choice == "on"
                val text = if (choice.isNotEmpty() && choice !in listOf("on", "off")) "Use /approvals on or /approvals off."
                    else "Claude tool approvals are ${if (preferences.claudeInteractiveApprovals) "on" else "off"}. Applies to the next run."
                _state.update { it.copy(messages = it.messages + ChatMessage(fromUser = false, text = text)) }
                persistMessages()
            }
            "effort" -> {
                val trimmed = args.trim()
                val agent = _state.value.agentKind
                if (trimmed.isBlank()) {
                    if (effortLevelsFor(agent, codexModelLevelsNow()) == null) {
                        _state.update {
                            it.copy(
                                messages = it.messages + ChatMessage(fromUser = true, text = "/effort") +
                                    ChatMessage(fromUser = false, text = "${agent.title} doesn't have an effort setting yet."),
                            )
                        }
                        persistMessages()
                    } else {
                        toggleEffortPicker(true)
                    }
                } else {
                    val reply = when (val change = applyEffortChoice(trimmed)) {
                        is EffortChange.Applied -> "Set effort to **${change.label}** for ${agent.title}."
                        is EffortChange.Refused -> change.reason
                    }
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(fromUser = true, text = "/effort $trimmed") +
                                ChatMessage(fromUser = false, text = reply),
                        )
                    }
                    persistMessages()
                }
            }
            "thinking" -> {
                val trimmed = args.trim()
                if (trimmed.isNotBlank()) {
                    setClaudeThinkingLevel(trimmed)
                    val level = ClaudeThinkingLevel.fromStored(trimmed)
                    _state.update {
                        it.copy(
                            messages = it.messages + ChatMessage(fromUser = true, text = "/thinking $trimmed") +
                                ChatMessage(fromUser = false, text = "Set Claude thinking effort to **${level.displayName}** (`${level.storageValue}`)."),
                        )
                    }
                    persistMessages()
                } else {
                    _state.update { it.copy(claudeThinkingPickerVisible = true) }
                }
            }
            "status" -> {
                val runtime = _state.value.agentKind.title
                val activeSubagents = _state.value.subagents.count { !it.state.isTerminal }
                val activeTasks = _state.value.backgroundTasks.count { it.status == BackgroundTaskStatus.RUNNING }
                val statusText = buildString {
                    appendLine("### System & Runtime Status")
                    appendLine("- **Active Agent:** $runtime")
                    appendLine("- **Running Task:** ${if (_state.value.isRunning) "Active turn running" else "Idle"}")
                    appendLine("- **Active Subagents:** $activeSubagents")
                    appendLine("- **Background Tasks:** $activeTasks")
                    appendLine("- **Context Memory Facts:** ${_state.value.contextMemory.entries.size}")
                }
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(fromUser = true, text = "/status") +
                            ChatMessage(fromUser = false, text = statusText),
                    )
                }
                persistMessages()
            }
            "skills", "skill" -> {
                val trimmedArgs = args.trim()
                val parts = trimmedArgs.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val project = _state.value.activeProject
                val replyText = when {
                    parts.isEmpty() -> {
                        val active = _state.value.activeSkills
                        val scope = _state.value.activeCustomizationConfig.scopeMode.title
                        buildString {
                            appendLine("### Skills & Customizations Hub")
                            appendLine("- **Scope Policy:** $scope")
                            appendLine("- **Active In Project:** ${active.count { it.isEnabled }} of ${active.size}")
                            appendLine()
                            if (active.isEmpty()) {
                                appendLine("No active skills configured.")
                            } else {
                                active.forEach { s ->
                                    val status = if (s.isEnabled) "✓" else "✕"
                                    val badge = when (s.source) {
                                        SkillSource.PROJECT -> "Project Local"
                                        SkillSource.LINKED -> "Linked: ${s.sourceProjectName ?: "Other"}"
                                        SkillSource.GLOBAL -> "Global Library"
                                        SkillSource.BUNDLED -> "Built-in"
                                        SkillSource.OTHER_PROJECT -> "Available"
                                    }
                                    appendLine("- [$status] **${s.name}** (`$badge`): ${s.description}")
                                }
                            }
                            appendLine()
                            appendLine("*Available commands:*")
                            appendLine("- `/skill link <projectName> <skillName>`")
                            appendLine("- `/skill unlink <skillName>`")
                            appendLine("- `/skill import <projectName> <skillName>`")
                            appendLine("- `/skill promote <skillName>`")
                        }
                    }
                    parts[0].equals("link", ignoreCase = true) && parts.size >= 3 -> {
                        val sourceTarget = parts[1]
                        val skillName = parts[2]
                        val sourceProj = _state.value.projects.firstOrNull {
                            it.name.equals(sourceTarget, ignoreCase = true) || it.slug.equals(sourceTarget, ignoreCase = true) || it.id == sourceTarget
                        }
                        if (sourceProj == null) {
                            "Error: Project '$sourceTarget' not found in registered projects."
                        } else {
                            linkSkill(sourceProj.id, skillName)
                            "✓ Linked skill '$skillName' from project '${sourceProj.name}' into current workspace."
                        }
                    }
                    parts[0].equals("unlink", ignoreCase = true) && parts.size >= 2 -> {
                        val skillName = parts[1]
                        unlinkSkill(skillName)
                        "✓ Unlinked skill '$skillName' from workspace."
                    }
                    parts[0].equals("import", ignoreCase = true) && parts.size >= 3 -> {
                        val sourceTarget = parts[1]
                        val skillName = parts[2]
                        val sourceProj = _state.value.projects.firstOrNull {
                            it.name.equals(sourceTarget, ignoreCase = true) || it.slug.equals(sourceTarget, ignoreCase = true) || it.id == sourceTarget
                        }
                        if (sourceProj == null) {
                            "Error: Project '$sourceTarget' not found."
                        } else {
                            val sourceSkillDir = File(getApplication<Application>().filesDir, "workspaces/${sourceProj.id}/.agents/skills/$skillName")
                            if (!sourceSkillDir.isDirectory) {
                                "Error: Skill directory not found at ${sourceSkillDir.path}"
                            } else {
                                importSkill(sourceSkillDir, skillName)
                                "✓ Importing '$skillName' from '${sourceProj.name}' into `.agents/skills/$skillName`…"
                            }
                        }
                    }
                    parts[0].equals("promote", ignoreCase = true) && parts.size >= 2 -> {
                        val skillName = parts[1]
                        if (project == null) {
                            "Error: No active workspace."
                        } else {
                            val workspace = projectWorkspaceRoot(project)
                            val sourceSkillDir = File(workspace, ".agents/skills/$skillName")
                            if (!sourceSkillDir.isDirectory) {
                                "Error: Local workspace skill '$skillName' not found at ${sourceSkillDir.path}"
                            } else {
                                promoteSkillToGlobal(sourceSkillDir, skillName)
                                "✓ Promoted '$skillName' to Global Library."
                            }
                        }
                    }
                    else -> "Invalid syntax. Usage: `/skill [link <project> <skill> | unlink <skill> | import <project> <skill> | promote <skill>]`"
                }
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(fromUser = true, text = "/skills $trimmedArgs".trim()) +
                            ChatMessage(fromUser = false, text = replyText),
                    )
                }
                persistMessages()
            }
            "rules", "rule" -> {
                val trimmedArgs = args.trim()
                val parts = trimmedArgs.split("\\s+".toRegex()).filter { it.isNotBlank() }
                val replyText = when {
                    parts.isEmpty() -> {
                        val active = _state.value.activeRules
                        val scope = _state.value.activeCustomizationConfig.scopeMode.title
                        buildString {
                            appendLine("### Rules & Operational Guidelines")
                            appendLine("- **Scope Policy:** $scope")
                            appendLine("- **Active Rules:** ${active.count { it.isEnabled }} of ${active.size}")
                            appendLine()
                            if (active.isEmpty()) {
                                appendLine("No active rules loaded.")
                            } else {
                                active.forEach { r ->
                                    val status = if (r.isEnabled) "✓" else "✕"
                                    appendLine("- [$status] **${r.title}** (`${r.name}` - ${r.source.title}): ${r.description}")
                                }
                            }
                            appendLine()
                            appendLine("*Available commands:*")
                            appendLine("- `/rules scope <inherit | project | global | custom>`")
                        }
                    }
                    parts[0].equals("scope", ignoreCase = true) && parts.size >= 2 -> {
                        val requestedMode = when (parts[1].lowercase()) {
                            "inherit", "inherit_and_merge", "merge" -> CustomizationScopeMode.INHERIT_AND_MERGE
                            "project", "project_only", "isolated" -> CustomizationScopeMode.PROJECT_ONLY
                            "global", "global_only", "baseline" -> CustomizationScopeMode.GLOBAL_ONLY
                            "custom" -> CustomizationScopeMode.CUSTOM
                            else -> null
                        }
                        if (requestedMode == null) {
                            "Invalid scope mode. Options: `inherit`, `project`, `global`, `custom`."
                        } else {
                            setCustomizationScopeMode(requestedMode)
                            "✓ Customization scope updated to: **${requestedMode.title}**"
                        }
                    }
                    else -> "Invalid syntax. Usage: `/rules [scope <inherit | project | global | custom>]`"
                }
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(fromUser = true, text = "/rules $trimmedArgs".trim()) +
                            ChatMessage(fromUser = false, text = replyText),
                    )
                }
                persistMessages()
            }
            "checkpoint" -> {
                val project = _state.value.activeProject ?: return
                val tag = args.ifBlank { "manual-${System.currentTimeMillis()}" }
                viewModelScope.launch(Dispatchers.IO) {
                    val workspace = projectWorkspaceRoot(project)
                    val checkpoints = WorkspaceCheckpoints(getApplication<Application>().filesDir)
                    checkpoints.createCheckpoint(project.id, workspace)
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                messages = it.messages + ChatMessage(fromUser = true, text = "/checkpoint $args".trim()) +
                                    ChatMessage(fromUser = false, text = "✓ Checkpoint snapshot saved as `$tag`."),
                            )
                        }
                        persistMessages()
                    }
                }
            }
            "rollback" -> {
                val project = _state.value.activeProject ?: return
                undoLastChanges()
                _state.update {
                    it.copy(
                        messages = it.messages + ChatMessage(fromUser = true, text = "/rollback") +
                            ChatMessage(fromUser = false, text = "✓ Reverted workspace files to previous checkpoint."),
                    )
                }
                persistMessages()
            }
            "memory" -> {
                val project = _state.value.activeProject ?: return
                val lowerArgs = args.trim().lowercase(Locale.ROOT)
                when {
                    lowerArgs.isBlank() || lowerArgs == "view" -> {
                        _state.update { it.copy(memoryViewerVisible = true) }
                    }
                    lowerArgs == "clear" || lowerArgs.startsWith("clear ") -> {
                        val updated = memoryStore.clearAuto(project.id)
                        _state.update {
                            it.copy(
                                contextMemory = updated,
                                messages = it.messages + ChatMessage(fromUser = true, text = "/memory $args".trim()) +
                                    ChatMessage(fromUser = false, text = "✓ Cleared auto-extracted memories. ${updated.entries.size} user memory facts preserved."),
                            )
                        }
                        persistMessages()
                    }
                    lowerArgs == "add" || lowerArgs.startsWith("add ") -> {
                        val rawPayload = args.trim().removePrefix("add").trim()
                        val tokens = SlashCommandEngine.tokenizeArgs(rawPayload)
                        if (tokens.size >= 2 && tokens[0].isNotBlank() && tokens[1].isNotBlank()) {
                            val key = tokens[0].trim()
                            val value = tokens.subList(1, tokens.size).joinToString(" ").trim()
                            val updated = memoryStore.upsert(project.id, key, value, MemorySource.USER)
                            _state.update {
                                it.copy(
                                    contextMemory = updated,
                                    messages = it.messages + ChatMessage(fromUser = true, text = "/memory $args".trim()) +
                                        ChatMessage(fromUser = false, text = "✓ Remembered: `$key` = \"$value\""),
                                )
                            }
                            persistMessages()
                        } else {
                            _state.update {
                                it.copy(
                                    messages = it.messages + ChatMessage(fromUser = true, text = "/memory $args".trim()) +
                                        ChatMessage(fromUser = false, text = "Usage: `/memory add <key> <value>` or `/memory clear` or `/memory` to view."),
                                )
                            }
                            persistMessages()
                        }
                    }
                    else -> {
                        _state.update { it.copy(memoryViewerVisible = true) }
                    }
                }
            }
        }
    }

    fun sendPrompt(prompt: String) {
        val project = state.value.activeProject ?: return
        if (_state.value.agentKind == AgentKind.ANTIGRAVITY &&
            _state.value.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN) {
            _state.update { it.copy(toastMessage = "Sign in to Antigravity from Settings before starting a task.") }
            return
        }
        if (_state.value.agentKind == AgentKind.DEEPSEEK_HARNESS && _state.value.provider.kind == ProviderKind.CLAUDE) {
            _state.update { it.copy(toastMessage = "Claude subscription login is not supported by DeepSeek Harness — pick a key-based provider in Settings.") }
            return
        }
        val attachments = state.value.pendingAttachments
        val trimmed = prompt.trim()
        if ((trimmed.isBlank() && attachments.isEmpty()) || state.value.isRunning || state.value.chatLoading) return

        // Check if input is a slash command
        val parsedCmd = SlashCommandEngine.parseCommand(trimmed)
        if (parsedCmd != null && parsedCmd.first.isLocalOnly) {
            executeLocalSlashCommand(parsedCmd.first, parsedCmd.second)
            return
        }

        // Check if input is a skill invocation
        val parsedSkill = if (parsedCmd == null) {
            SlashCommandEngine.parseSkillInvocation(trimmed, _state.value.activeSkills)
        } else null

        // If user typed a slash command that does not exist and is not an available skill, reject locally without burning tokens
        if (parsedCmd == null && parsedSkill == null && trimmed.startsWith("/")) {
            val cmdToken = trimmed.substringBefore(' ')
            _state.update {
                it.copy(
                    messages = it.messages + ChatMessage(fromUser = true, text = trimmed) +
                        ChatMessage(fromUser = false, text = "Unknown command or skill `$cmdToken`. Type `/help` for available commands or `/` to view skills."),
                    slashCommandsVisible = false,
                    filteredSkills = emptyList(),
                )
            }
            persistMessages()
            return
        }

        // Format agent workflow command, skill invocation, or use plain prompt
        val effectivePrompt = when {
            parsedCmd != null -> {
                SlashCommandEngine.buildPromptForCommand(parsedCmd.first, parsedCmd.second, _state.value.agentKind)
            }
            parsedSkill != null -> {
                val skill = parsedSkill.first
                val skillContent = skill.markdownContent?.takeIf { it.isNotBlank() }
                    ?: runCatching { File(skill.filePath).readText() }.getOrNull()
                    ?: skill.description
                SlashCommandEngine.buildPromptForSkill(skill, parsedSkill.second, skillContent)
            }
            else -> {
                trimmed.ifBlank { "Please review the attached files." }
            }
        }

        val requestText = effectivePrompt
        updateActiveChatTitle(requestText)

        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        _state.update {
            val startedAt = System.currentTimeMillis()
            val skillName = parsedSkill?.first?.name
            it.copy(
                messages = it.messages + ChatMessage(
                    fromUser = true,
                    text = prompt.trim(),
                    attachments = attachments,
                    activeSkill = skillName,
                ),
                pendingAttachments = emptyList(),
                isRunning = true,
                slashCommandsVisible = false,
                filteredSkills = emptyList(),
                activity = listOf(ActivityItem(
                    if (skillName != null) "Applying skill: $skillName" else "Understanding your request",
                    if (skillName != null) "Executing skill directives" else "Preparing a safe plan",
                    false
                )) + it.activity,
                liveProcess = listOf(ActivityItem(
                    "Think",
                    if (skillName != null) "Applying skill: $skillName - ${requestPlanningSummary(requestText, it.agentKind)}"
                    else requestPlanningSummary(requestText, it.agentKind),
                    false
                )),
                liveThinking = true,
                activeThinkingBlockId = null,
                taskStartedAtMillis = startedAt,
                taskFinishedAtMillis = null,
                workSegmentStartedAtMillis = startedAt,
                currentTaskRequest = requestText,
                retiredSessionIds = emptySet(),
            )
        }
        touchProject(project.id)
        persistMessages()
        val history = state.value.messages // includes all messages up to now

        // Inject active rules and skills progressive disclosure index
        val rulesBlock = skillManager.buildRulesBlock(_state.value.activeRules)
        val skillsIndex = skillManager.buildProgressiveDisclosureIndex(_state.value.activeSkills)
        val envelopePrefix = buildString {
            if (rulesBlock.isNotBlank()) {
                appendLine(rulesBlock)
                appendLine()
            }
            if (skillsIndex.isNotBlank()) {
                appendLine(skillsIndex)
                appendLine()
            }
        }
        val withSkillsText = if (envelopePrefix.isNotBlank()) "$envelopePrefix$requestText" else requestText

        val runtimePrompt = if (attachments.isEmpty()) withSkillsText else buildString {
            appendLine(withSkillsText)
            appendLine()
            append(AttachmentPrompt.render(attachments, projectGuestRoot(project)))
        }
        failedApiKeyIds.clear()
        val taskRecord = try {
            supervisor.createTask(
                projectId = project.id,
                projectSlug = project.slug,
                chatId = state.value.activeChatId ?: "default",
                agentKind = state.value.agentKind.name,
                providerJson = state.value.provider.kind.name,
                prompt = runtimePrompt,
                objective = requestText.ifBlank { runtimePrompt }
            )
        } catch (t: Throwable) {
            runCatching { android.util.Log.e("MainViewModel", "Failed to create task in sendPrompt", t) }
            _state.update {
                it.copy(
                    isRunning = false,
                    liveThinking = false,
                    activeSessionId = null,
                    toastMessage = "Task creation error: ${t.localizedMessage ?: t.message}",
                )
            }
            return
        }
        activeRuntimeRequest = RuntimeRetryRequest(
            runtime = activeRuntime(),
            project = project,
            prompt = runtimePrompt,
            history = history,
            provider = state.value.provider,
            memory = state.value.contextMemory,
            taskId = taskRecord.taskId,
            attemptId = "${taskRecord.taskId}:attempt-0",
            fallbackProfiles = customProviderFallbackSnapshot(state.value.provider, state.value.agentKind),
        )
        attemptedProfileIds.clear()
        state.value.provider.profileId.takeIf { it.isNotBlank() }?.let { attemptedProfileIds += it }
        supervisor.registerFallbackDecider(taskRecord.taskId) { taskId, errorMsg ->
            applyFallbackForTask(taskId, errorMsg)
        }
        activeUiTaskId = taskRecord.taskId
        // Retain the latest task request across retries and provider fallback.
        var lastTaskRequest: RuntimeRetryRequest? = activeRuntimeRequest
        val job = supervisor.executeTask(taskRecord.taskId) { task ->
            lastTaskRequest?.let { previous ->
                val request = RuntimeSessionRouting.requestForAttempt(
                    activeRuntimeRequest, activeRuntimeRequest?.taskId, previous, task.taskId,
                )
                val snapshot = supervisor.getBrainSnapshot(task.taskId)
                val effectiveAttemptId = snapshot?.attemptId ?: "${task.taskId}:attempt-${task.retryCount}"
                val attemptRequest = request.copy(
                    taskId = task.taskId,
                    attemptId = effectiveAttemptId,
                    brainSnapshot = snapshot,
                )
                if (RuntimeSessionRouting.followsTask(_state.value, activeUiTaskId, task)) {
                    activeRuntimeRequest = attemptRequest
                }
                lastTaskRequest = attemptRequest
                // Each attempt runs in a new bridge session: follow it, not the failed one.
                // Bridges bind every session to the task before emitting SessionStarted, so the
                // task's bound session here is the previous attempt's, even if the chat never saw it.
                val previousSessionId = supervisor.stateStore.get(task.taskId)?.sessionId
                _state.update {
                    RuntimeSessionRouting.prepareForAttempt(
                        it, activeUiTaskId, task.taskId, task.projectId, previousSessionId, requestText,
                    )
                }
                val sessionId = request.runtime.startSession(
                    request.project.id,
                    request.project.slug,
                    request.project.kind,
                    request.prompt,
                    request.history,
                    request.provider,
                    request.memory,
                    task.taskId,
                    snapshot,
                    effectiveAttemptId,
                )
                supervisor.bindSession(task.taskId, sessionId)
            }
        }
        val launchedTaskId = taskRecord.taskId
        job.invokeOnCompletion {
            viewModelScope.launch { finishSupervisedTask(launchedTaskId) }
        }
    }

    /** Only the durable supervisor outcome can finish chat or record task success. */
    private fun finishSupervisedTask(taskId: String) {
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        val record = supervisor.stateStore.get(taskId) ?: return
        if (!RuntimeSessionRouting.canFinish(_state.value, activeUiTaskId, record)) return
        val verified = record.status == com.jarves.mh.runtime.task.TaskExecutionStatus.COMPLETED
        val unverified = record.status == com.jarves.mh.runtime.task.TaskExecutionStatus.UNVERIFIED
        val cancelled = record.status == com.jarves.mh.runtime.task.TaskExecutionStatus.CANCELLED
        val title = when {
            verified -> "Task completed"
            unverified -> "Task finished — unverified"
            cancelled -> "Task stopped"
            else -> "Task failed"
        }
        val detail = when {
            verified -> "Runtime verification passed"
            unverified -> "No deterministic verification criteria were available; review the result."
            else -> record.lastError ?: title
        }
        val finishedAt = record.completedAt ?: System.currentTimeMillis()
        _state.update { current ->
            if (!RuntimeSessionRouting.followsTask(current, activeUiTaskId, record)) current else {
                val subagents = when {
                    verified -> SubagentRegistry.completeAll(current.subagents, finishedAt)
                    unverified || cancelled -> SubagentRegistry.terminate(current.subagents, "*", finishedAt)
                    else -> SubagentRegistry.failAll(current.subagents, detail, finishedAt)
                }
                val backgroundTasks = when {
                    verified -> TaskRegistry.completeRunning(current.backgroundTasks)
                    unverified || cancelled -> TaskRegistry.terminate(current.backgroundTasks, "*")
                    else -> TaskRegistry.failRunning(current.backgroundTasks)
                }
                attachTaskDuration(finishWorkSegment(current, finishedAt), finishedAt).copy(
                    isRunning = false,
                    isStopping = false,
                    activeSessionId = null,
                    retiredSessionIds = current.retiredSessionIds + listOfNotNull(current.activeSessionId, record.sessionId),
                    pendingApproval = null,
                    subagents = subagents,
                    backgroundTasks = backgroundTasks,
                    activity = listOf(ActivityItem(title, detail)) + current.activity.map { it.copy(isComplete = true) },
                    taskFinishedAtMillis = finishedAt,
                    currentTaskRequest = null,
                    toastMessage = if (!verified && !unverified && !cancelled) detail else current.toastMessage,
                )
            }
        }
        activeUiTaskId = null
        if (activeRuntimeRequest?.taskId == taskId) activeRuntimeRequest = null
        failedApiKeyIds.clear()
        attemptedProfileIds.clear()
        if (verified) {
            runCatching { memoryStore.taskRepository.recordSuccess(record.projectId, detail) }
            extractMemoryFromSession(record.projectId, _state.value.messages)
        } else if (!unverified && !cancelled) {
            runCatching { memoryStore.taskRepository.recordError(record.projectId, detail) }
        }
        touchProject(record.projectId)
        refreshProjectFiles()
        persistMessages(includeLiveProcess = true, immediate = true)
    }

    fun answerApproval(approved: Boolean) {
        val request = state.value.pendingApproval ?: return
        com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication()).resumeFromApproval(request.sessionId)
        viewModelScope.launch { activeRuntime().respondToApproval(request, approved) }
    }

    /**
     * Stage 1: Graceful interrupt (SIGINT / Ctrl+C equivalent).
     * Immediately updates UI to [isStopping] = true and marks all non-terminal
     * subagents and tasks as [SubagentState.TERMINATED] / [BackgroundTaskStatus.TERMINATED]
     * for instant Inspector feedback. If the user taps again while stopping, or if the
     * process has not exited after 1500ms, [forceKillTask] is called immediately.
     */
    fun stopTask() {
        if (!_state.value.isRunning && !_state.value.isStopping) return
        if (_state.value.isStopping) {
            forceKillTask()
            return
        }
        val now = System.currentTimeMillis()
        // Immediate UI feedback: mark all active subagents/tasks as terminated locally.
        _state.update { current ->
            current.copy(
                isStopping = true,
                currentTaskRequest = null,
                subagents = SubagentRegistry.terminate(current.subagents, "*", now),
                backgroundTasks = TaskRegistry.terminate(current.backgroundTasks, "*"),
            )
        }
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        viewModelScope.launch {
            supervisor.requestStopActive(force = false)
            activeRuntime().stopActiveSession()
            // Auto-escalate to force kill after 1500ms if still alive.
            delay(1500)
            if (_state.value.isStopping) {
                forceKillTask()
            }
        }
    }

    /** Stage 2: Force kill (SIGKILL). Called automatically if graceful stop times out or on re-press. */
    fun forceKillTask() {
        if (!_state.value.isRunning && !_state.value.isStopping) return
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        viewModelScope.launch {
            // Pass force=true to skip any remaining grace period and immediately kill
            supervisor.requestStopActive(force = true)
            runCatching { activeRuntime().stopActiveSession(force = true) }
            // If the bridge still hasn't emitted SessionFailed, force-clear UI state.
            delay(500)
            _state.update {
                it.copy(
                    isRunning = false,
                    isStopping = false,
                    liveThinking = false,
                    pendingApproval = null,
                    activeThinkingBlockId = null,
                    activeSessionId = null,
                    currentTaskRequest = null,
                )
            }
        }
    }

    fun undoLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val restored = activeRuntime().undoLastChanges(project.id)
            _state.update { current ->
                current.copy(
                    changes = if (restored) emptyList() else current.changes,
                    activity = listOf(
                        ActivityItem(
                            if (restored) "Changes undone" else "Undo unavailable",
                            if (restored) "Restored files to their state before the task" else "No restorable checkpoint was found",
                        ),
                    ) + current.activity,
                )
            }
            if (restored) refreshProjectFiles()
        }
    }

    fun keepLastChanges() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            activeRuntime().acceptLastChanges(project.id)
            _state.update {
                it.copy(
                    changes = emptyList(),
                    activity = listOf(ActivityItem("Changes kept", "Accepted the task's file changes")) + it.activity,
                )
            }
        }
    }

    fun undoFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().undoFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
                refreshProjectFiles()
            }
        }
    }

    fun keepFileChange(path: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            if (activeRuntime().acceptFileChange(project.id, path)) {
                _state.update { current -> current.copy(changes = current.changes.filterNot { it.path == path }) }
            }
        }
    }

    private fun isNoisyRuntimeItem(item: ActivityItem): Boolean {
        val combined = "${item.title} ${item.detail}"
        return combined.contains("Starting Claude Code", true) ||
            combined.contains("Agent process started", true) ||
            combined.contains("Claude Code connected", true) ||
            combined.contains("Runtime warning", true) ||
            combined.contains("unrecognized_model", true) ||
            combined.contains("Writing response", true) ||
            combined.contains("Claude Code finished", true) ||
            combined.contains("Task completed", true)
    }

    private fun toolPlanSummary(toolName: String, detail: String): String {
        val clean = detail.replace(Regex("\\s+"), " ").trim()
        val short = clean.take(90).ifBlank { "the current project" }
        return when (toolName) {
            "Write" -> "Preparing to create ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Edit", "NotebookEdit" -> "Preparing to update ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Read" -> "Preparing to inspect ${clean.substringAfterLast('/').ifBlank { "a project file" }}"
            "Glob" -> "Preparing to find matching project files"
            "Grep" -> "Preparing to search the project for $short"
            "Bash" -> if (clean.contains("cat ", true) || clean.contains("printf ", true) || clean.contains(" >")) {
                "Preparing to create or update project files with Bash"
            } else {
                "Preparing to run: $short"
            }
            else -> "Preparing to use $toolName for the next step"
        }
    }

    private fun requestPlanningSummary(
        request: String,
        agentKind: AgentKind,
        toolName: String? = null,
        detail: String = "",
    ): String {
        val agentName = agentKind.title
        val cleanRequest = request.replace(Regex("\\s+"), " ").trim().take(110)
        val requestPart = if (cleanRequest.isBlank()) {
            "$agentName is reviewing the request"
        } else {
            "The user is asking: “$cleanRequest”"
        }
        return if (toolName == null) {
            "$requestPart. $agentName is deciding the next useful step."
        } else {
            "$requestPart. ${toolPlanSummary(toolName, detail)}."
        }
    }

    private fun finishWorkSegment(current: AppUiState, finishedAt: Long = System.currentTimeMillis()): AppUiState {
        val meaningfulItems = current.liveProcess.filterNot(::isNoisyRuntimeItem)
            .map { if (it.isComplete) it else it.copy(isComplete = true) }
        if (!current.liveThinking && meaningfulItems.isEmpty()) {
            return current.copy(liveProcess = emptyList(), workSegmentStartedAtMillis = null)
        }
        val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: finishedAt
        val block = ChatMessage(
            fromUser = false,
            text = "",
            workItems = meaningfulItems,
            workedMillis = (finishedAt - startedAt).coerceAtLeast(0L),
        )
        return current.copy(
            messages = current.messages + block,
            liveProcess = emptyList(),
            liveThinking = false,
            activeThinkingBlockId = null,
            workSegmentStartedAtMillis = null,
        )
    }

    /** Adds the complete request duration to the response produced after the latest user message. */
    private fun attachTaskDuration(current: AppUiState, finishedAt: Long): AppUiState {
        val startedAt = current.taskStartedAtMillis ?: return current
        val lastUserIndex = current.messages.indexOfLast { it.fromUser }
        val responseIndex = current.messages.indices.lastOrNull { index ->
            index > lastUserIndex && !current.messages[index].fromUser && current.messages[index].text.isNotBlank()
        } ?: return current
        val updated = current.messages.toMutableList()
        updated[responseIndex] = updated[responseIndex].copy(
            workedMillis = (finishedAt - startedAt).coerceAtLeast(1L),
        )
        return current.copy(messages = updated)
    }

    private fun appendWorkItem(current: AppUiState, item: ActivityItem): AppUiState {
        if (isNoisyRuntimeItem(item)) return current
        return current.copy(
            liveProcess = current.liveProcess.map { if (!it.isComplete) it.copy(isComplete = true) else it } + item,
            liveThinking = false,
            workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
        )
    }

    private fun onRuntimeEvent(event: RuntimeEvent, source: AgentKind) {
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        val task = activeUiTaskId?.let { supervisor.stateStore.get(it) }
        // Gate before authentication, fallback, persistence, or any other event side effect.
        if (!RuntimeSessionRouting.acceptsOwned(_state.value, event, activeUiTaskId, task, source)) return
        if (event is RuntimeEvent.SessionFailed && _state.value.agentKind == AgentKind.ANTIGRAVITY &&
            (event.reason.contains("sign-in", true) || event.reason.contains("authentication", true))) {
            antigravityAuthController.invalidateSession(event.reason)
        }
        if (event is RuntimeEvent.SessionFailed && _state.value.agentKind == AgentKind.CLAUDE_CODE &&
            _state.value.provider.claudeAuthMode == ClaudeAuthMode.NATIVE_SUBSCRIPTION &&
            event.reason.contains("not signed in", ignoreCase = true)
        ) {
            claudeAuthController.markExpired()
        }
        if (event is RuntimeEvent.SessionFailed && _state.value.agentKind == AgentKind.CODEX &&
            _state.value.provider.kind == ProviderKind.CHATGPT &&
            event.reason.contains("not signed in", ignoreCase = true)
        ) {
            codexAuthController.markExpired()
        }
        if (event is RuntimeEvent.SessionFailed && retryWithNextApiKey(event)) {
            // A predicted fallback can still fail later. Consume the close so final failure can finish chat.
            _state.update { current ->
                if (RuntimeSessionRouting.acceptsOwned(current, event, activeUiTaskId, task, source)) {
                    RuntimeSessionRouting.closeAttempt(current, event.sessionId)
                } else current
            }
            task?.taskId?.let(::finishSupervisedTask)
            return
        }
        var accepted = false
        _state.update { current ->
            accepted = RuntimeSessionRouting.acceptsOwned(current, event, activeUiTaskId, task, source)
            if (!accepted) {
                current
            } else when (event) {
                is RuntimeEvent.SessionStarted -> current.copy(
                    activeSessionId = event.sessionId,
                    activity = current.activity.mapIndexed { index, item -> if (index == 0) item.copy(isComplete = true) else item },
                )
                is RuntimeEvent.AssistantDelta -> {
                    val timeline = if (current.liveThinking || current.liveProcess.any { !isNoisyRuntimeItem(it) }) {
                        finishWorkSegment(current)
                    } else {
                        current
                    }
                    val lastMessage = timeline.messages.lastOrNull()
                    if (lastMessage != null && !lastMessage.fromUser && lastMessage.workItems.isEmpty() && lastMessage.workedMillis == 0L) {
                        timeline.copy(messages = timeline.messages.dropLast(1) + lastMessage.copy(text = lastMessage.text + event.text))
                    } else {
                        timeline.copy(messages = timeline.messages + ChatMessage(fromUser = false, text = event.text))
                    }
                }
                is RuntimeEvent.ReasoningProgress -> {
                    val existingIndex = current.liveProcess.indexOfLast { it.title == "Think" }
                    // The request-level Think summary is seeded once in sendPrompt.
                    // After that segment has been committed to the timeline, later
                    // agent turns must not repeat the same request summary.
                    if (existingIndex < 0) return@update current
                    val reasoning = ActivityItem(
                        title = "Think",
                        detail = current.liveProcess.getOrNull(existingIndex)?.detail
                            ?: requestPlanningSummary(current.currentTaskRequest.orEmpty(), current.agentKind),
                        isComplete = false,
                    )
                    val process = if (existingIndex >= 0) {
                        current.liveProcess.toMutableList().also { it[existingIndex] = reasoning }
                    } else {
                        current.liveProcess + reasoning
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = true,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ReasoningSummary -> {
                    val summary = event.summary.trim()
                    val process = current.liveProcess.toMutableList()
                    val existingIndex = process.indexOfLast { !it.isComplete && it.title == "Think" }
                    if (event.startsNewBlock) {
                        process.indices.forEach { index ->
                            if (!process[index].isComplete) process[index] = process[index].copy(isComplete = true)
                        }
                        val initial = summary.ifBlank { "Thinking…" }
                        val replaceFallback = current.activeThinkingBlockId == null &&
                            process.size == 1 && process.first().title == "Think"
                        if (replaceFallback) {
                            process[0] = ActivityItem("Think", initial, event.isFinal)
                        } else {
                            process += ActivityItem("Think", initial, event.isFinal)
                        }
                    } else if (current.activeThinkingBlockId == event.blockId && existingIndex >= 0 && summary.isNotBlank()) {
                        process[existingIndex] = process[existingIndex].copy(
                            detail = summary,
                            isComplete = event.isFinal,
                        )
                    } else {
                        return@update current
                    }
                    current.copy(
                        liveProcess = process,
                        liveThinking = !event.isFinal,
                        activeThinkingBlockId = if (event.isFinal) null else event.blockId,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.ToolStarted -> {
                    val planned = current.copy(
                        liveProcess = current.liveProcess.map { item ->
                            if (!item.isComplete) item.copy(isComplete = true) else item
                        },
                        liveThinking = false,
                        activeThinkingBlockId = null,
                        activity = listOf(
                            ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                        ) + current.activity.map { if (!it.isComplete) it.copy(isComplete = true) else it },
                    )
                    appendWorkItem(
                        planned,
                        ActivityItem("Running ${event.toolName}", event.detail, false, isCommand = event.toolName == "Bash"),
                    )
                }
                is RuntimeEvent.RuntimeLog -> appendWorkItem(
                    current.copy(activity = listOf(ActivityItem(event.title, event.detail)) + current.activity),
                    ActivityItem(event.title, event.detail),
                )
                is RuntimeEvent.ToolRequested -> {
                    if (task?.status?.isTerminal != true) supervisor.pauseForApproval(event.sessionId)
                    appendWorkItem(current.copy(
                        pendingApproval = event.request,
                        activity = listOf(ActivityItem("Waiting for approval", event.request.explanation, false)) + current.activity,
                    ), ActivityItem("Waiting for approval", event.request.explanation, false))
                }
                is RuntimeEvent.ToolApproved -> {
                    if (task?.status?.isTerminal != true) supervisor.resumeFromApproval(event.sessionId)
                    appendWorkItem(current.copy(
                        pendingApproval = null,
                        activity = listOf(ActivityItem("Applying approved changes", "Editing project files", false)) + current.activity,
                    ), ActivityItem("Action approved", "Claude is continuing the task", false))
                }
                is RuntimeEvent.ToolRejected -> {
                    if (task?.status?.isTerminal != true) supervisor.resumeFromApproval(event.sessionId)
                    appendWorkItem(current.copy(
                        pendingApproval = null,
                    ), ActivityItem("Action rejected", "Claude will continue without this action"))
                }
                is RuntimeEvent.ToolCompleted -> {
                    val runningIndex = current.liveProcess.indexOfLast {
                        !it.isComplete && it.title == "Running ${event.toolName}"
                    }
                    val process = if (runningIndex >= 0) {
                        current.liveProcess.toMutableList().also { items ->
                            val runningItem = items[runningIndex]
                            items[runningIndex] = ActivityItem(
                                "${event.toolName} completed",
                                runningItem.detail.ifBlank { event.summary },
                                isCommand = event.toolName == "Bash",
                            )
                        }
                    } else {
                        current.liveProcess + ActivityItem(
                            "${event.toolName} completed",
                            event.summary,
                            isCommand = event.toolName == "Bash",
                        )
                    }
                    current.copy(
                        activity = listOf(ActivityItem(event.summary, event.toolName)) + current.activity,
                        liveProcess = process,
                        liveThinking = false,
                        workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                    )
                }
                is RuntimeEvent.FilesChanged -> current.copy(
                    changes = event.changes,
                    liveThinking = false,
                    liveProcess = if (event.paths.isEmpty()) current.liveProcess else current.liveProcess +
                        ActivityItem(
                            "Files changed",
                            event.paths.take(4).joinToString(", ") + if (event.paths.size > 4) " +${event.paths.size - 4} more" else "",
                        ),
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.PreviewStarted -> current.copy(
                    previewReady = true,
                    previewUrl = event.url,
                    activity = listOf(ActivityItem("Preview ready", event.url)) + current.activity,
                    liveProcess = current.liveProcess + ActivityItem("Preview ready", event.url),
                    liveThinking = false,
                    workSegmentStartedAtMillis = current.workSegmentStartedAtMillis ?: System.currentTimeMillis(),
                )
                is RuntimeEvent.SessionCompleted -> appendWorkItem(
                    RuntimeSessionRouting.closeAttempt(current, event.sessionId).copy(
                        activity = listOf(ActivityItem("Checking result", "Execution finished; supervisor verification is pending", false)) + current.activity,
                    ),
                    ActivityItem("Checking result", "Waiting for supervisor verification", false),
                )
                is RuntimeEvent.SessionFailed -> appendWorkItem(
                    RuntimeSessionRouting.closeAttempt(current, event.sessionId).copy(
                        activity = listOf(ActivityItem("Attempt failed", event.reason)) + current.activity,
                    ),
                    ActivityItem("Attempt failed", "${event.reason} — supervisor will decide recovery"),
                )
                is RuntimeEvent.SubagentUpdated -> {
                    val updated = SubagentRegistry.update(current.subagents, event.subagent, isStopping = current.isStopping)
                    current.copy(subagents = updated)
                }
                is RuntimeEvent.TaskUpdated -> {
                    val existingIndex = current.backgroundTasks.indexOfFirst { it.taskId == event.task.taskId }
                    val updated = if (existingIndex >= 0) {
                        current.backgroundTasks.toMutableList().also { it[existingIndex] = event.task }
                    } else {
                        current.backgroundTasks + event.task
                    }
                    current.copy(backgroundTasks = updated)
                }
                is RuntimeEvent.ArtifactDiscovered -> {
                    val existing = current.artifacts.any { it.filePath == event.artifact.filePath }
                    current.copy(artifacts = if (existing) current.artifacts else current.artifacts + event.artifact)
                }
                is RuntimeEvent.TimerUpdated -> {
                    val existingIndex = current.scheduledTimers.indexOfFirst { it.taskId == event.timer.taskId }
                    val updated = if (existingIndex >= 0) {
                        current.scheduledTimers.toMutableList().also { it[existingIndex] = event.timer }
                    } else {
                        current.scheduledTimers + event.timer
                    }
                    current.copy(scheduledTimers = updated)
                }
                is RuntimeEvent.TokenUsageUpdated -> {
                    current.copy(tokenMetrics = event.metrics.copy(reported = true))
                }
            }
        }
        if (!accepted) return
        discoverAgentPreview(event, task)
        if (event is RuntimeEvent.FilesChanged) {
            _state.value.activeProject?.id?.let { touchProject(it) }
            refreshProjectFiles()
        }
        // Save every visible reasoning/tool transition, not only assistant text and
        // final results. If Android kills the process, the last displayed timeline
        // is restored as an interrupted work block rather than disappearing.
        val isTerminalEvent = event is RuntimeEvent.SessionCompleted || event is RuntimeEvent.SessionFailed
        persistMessages(includeLiveProcess = true, immediate = isTerminalEvent)
        // The outcome may already be durable while the final bridge event was buffered.
        if (isTerminalEvent) task?.taskId?.let(::finishSupervisedTask)
    }

    private fun discoverAgentPreview(event: RuntimeEvent, task: com.jarves.mh.runtime.task.DurableTaskRecord?) {
        if (event is RuntimeEvent.SessionStarted) {
            previewDiscoveryJobs.values.forEach { it.cancel() }
            previewDiscoveryJobs.clear()
            previewDiscoverySession = event.sessionId
        }
        if (task == null || event.sessionId != previewDiscoverySession) return
        val output = when (event) {
            is RuntimeEvent.AssistantDelta -> event.text
            is RuntimeEvent.ToolCompleted -> event.previewUrl ?: event.summary
            is RuntimeEvent.TaskUpdated -> event.task.liveOutputTail
            else -> return
        }
        val url = com.jarves.mh.runtime.LocalPreviewDiscovery.candidate(output) ?: return
        if (url in previewDiscoveryJobs || previewDiscoveryJobs.size >= 8) return
        val startedAt = _state.value.taskStartedAtMillis ?: return
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        fun owns(current: AppUiState): Boolean = previewDiscoverySession == event.sessionId &&
            supervisor.stateStore.get(task.taskId)?.let { RuntimeSessionRouting.acceptsPreview(current, activeUiTaskId, it, event.sessionId, startedAt) } == true
        previewDiscoveryJobs[url] = viewModelScope.launch {
            // Retry only advertised local URLs, briefly; no port scanning or recurring polling.
            repeat(5) { attempt ->
                if (!owns(_state.value)) return@launch
                val ready = kotlinx.coroutines.runInterruptible(Dispatchers.IO) { com.jarves.mh.runtime.LocalPreviewDiscovery.isReady(url) }
                if (ready) {
                    val preview = RuntimeEvent.PreviewStarted(event.sessionId, url)
                    _state.update { current ->
                        if (!owns(current) || current.previewUrl == preview.url && current.previewReady) current
                        else current.copy(previewReady = true, previewUrl = preview.url,
                            activity = listOf(ActivityItem("Preview ready", preview.url)) + current.activity)
                    }
                    return@launch
                }
                if (attempt < 4) delay(300L * (attempt + 1))
            }
        }
    }

    fun canFallbackForTask(taskId: String, errorMsg: String): Boolean {
        val current = _state.value
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        val taskRecord = supervisor.stateStore.get(taskId) ?: return false
        if (!RuntimeSessionRouting.followsTask(current, activeUiTaskId, taskRecord)) return false
        val isCancelled = supervisor.isCancellationActive(taskId)
        val isTerminal = taskRecord.status.isTerminal

        val request = activeRuntimeRequest?.takeIf { it.taskId == taskId } ?: return false
        val failure = ProviderFailureClassifier.classify(errorMsg)
        val secretId = request.provider.secretId
        val credentials = vault.credentials(secretId)
        val active = credentials.firstOrNull { it.isActive }
        val failed = if (failure == ProviderFailureClass.KEY && active != null) failedApiKeyIds + "$secretId/${active.id}" else failedApiKeyIds
        val untried = credentials.filter { "$secretId/${it.id}" !in failed }.map { it.id }
        val remaining = if (request.provider.kind == ProviderKind.CUSTOM && request.provider.profileId.isNotBlank()) {
            request.fallbackProfiles.filter { it.id !in attemptedProfileIds }
        } else {
            emptyList()
        }
        val hasEligibleFallback = ProviderFallbackPlanner.next(failure, request.provider, if (failure == ProviderFailureClass.KEY) untried else emptyList(), remaining) != null

        return FallbackDecisionHelper.shouldAttemptFallback(
            agentKind = current.agentKind,
            isRunning = current.isRunning,
            isStopping = current.isStopping,
            isCancelled = isCancelled,
            isTerminal = isTerminal,
            hasEligibleFallback = hasEligibleFallback
        )
    }

    private fun applyFallbackForTask(taskId: String, errorMsg: String): Boolean {
        val current = _state.value
        if (current.agentKind == AgentKind.ANTIGRAVITY) return false
        if (!current.isRunning || current.isStopping) return false
        val supervisor = com.jarves.mh.runtime.task.TaskSupervisor.getInstance(getApplication())
        if (supervisor.isCancellationActive(taskId)) return false
        val taskRecord = supervisor.stateStore.get(taskId) ?: return false
        if (!RuntimeSessionRouting.followsTask(current, activeUiTaskId, taskRecord) || taskRecord.status.isTerminal) return false

        val request = activeRuntimeRequest?.takeIf { it.taskId == taskId } ?: return false
        val failure = ProviderFailureClassifier.classify(errorMsg)
        val secretId = request.provider.secretId
        val credentials = vault.credentials(secretId)
        val active = credentials.firstOrNull { it.isActive }
        if (failure == ProviderFailureClass.KEY && active != null) failedApiKeyIds += "$secretId/${active.id}"
        val untried = credentials.filter { "$secretId/${it.id}" !in failedApiKeyIds }.map { it.id }
        // Provider fallback only applies to Dsh custom profiles; other providers keep key-only failover.
        val remaining = if (request.provider.kind == ProviderKind.CUSTOM && request.provider.profileId.isNotBlank()) {
            request.fallbackProfiles.filter { it.id !in attemptedProfileIds }
        } else {
            emptyList()
        }
        val step = ProviderFallbackPlanner.next(failure, request.provider, if (failure == ProviderFailureClass.KEY) untried else emptyList(), remaining)
            ?: return false
        val chainEntry = ProviderFailureClassifier.redact(
            "${request.provider.kind.title}${request.provider.profileId.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()}: ${errorMsg.take(300)}",
            credentials.map { it.secret },
        )
        val chain = (request.failureChain + chainEntry).takeLast(10)
        val nextRequest: RuntimeRetryRequest
        val toast: String
        when (step) {
            is ProviderFallbackPlanner.Step.NextKey -> {
                val next = credentials.firstOrNull { it.id == step.keyId } ?: return false
                if (!vault.activate(secretId, next.id)) return false
                nextRequest = request.copy(failureChain = chain)
                toast = "${active?.name ?: "Key"} failed. Switched to ${next.name}."
                _state.update { it.copy(activeApiKeyName = next.name) }
            }
            is ProviderFallbackPlanner.Step.NextProfile -> {
                attemptedProfileIds += step.profile.id
                val hasKey = vault.contains(step.profile.secretId)
                nextRequest = request.copy(
                    provider = step.profile.toProviderProfile(hasKey),
                    failureChain = chain,
                )
                toast = "Provider failed. Falling back to ${step.profile.name}."
            }
        }
        activeRuntimeRequest = nextRequest
        _state.update {
            it.copy(
                activeSessionId = null,
                toastMessage = toast,
                liveProcess = it.liveProcess + ActivityItem("Provider fallback", chain.last(), true),
            )
        }
        return true
    }

    private fun retryWithNextApiKey(event: RuntimeEvent.SessionFailed): Boolean {
        val current = _state.value
        if (current.agentKind == AgentKind.ANTIGRAVITY) return false
        if (!current.isRunning || current.isStopping || current.activeSessionId != event.sessionId) return false
        val request = activeRuntimeRequest ?: return false
        return canFallbackForTask(request.taskId ?: event.sessionId, event.reason)
    }

    private fun customProviderFallbackSnapshot(provider: ProviderProfile, agent: AgentKind): List<com.jarves.mh.provider.CustomProviderProfile> =
        if (agent == AgentKind.DEEPSEEK_HARNESS && provider.kind == ProviderKind.CUSTOM && provider.profileId.isNotBlank()) {
            ProviderFallbackPlanner.orderedProfiles(provider.profileId, preferences.loadCustomProviders())
        } else {
            emptyList()
        }

    // --- Multi custom provider profiles (Dsh). Keys are stored per profile id in the vault. ---

    fun customProviders(): List<com.jarves.mh.provider.CustomProviderProfile> = preferences.loadCustomProviders()

    /** Creates or edits a profile. The id is preserved on edit so running tasks and saved keys stay linked. */
    fun saveCustomProvider(profile: com.jarves.mh.provider.CustomProviderProfile, apiKey: String = ""): com.jarves.mh.provider.CustomProviderProfile {
        val all = preferences.loadCustomProviders().filterNot { it.id == profile.id } + profile
        preferences.saveCustomProviders(all)
        if (apiKey.isNotBlank()) vault.add(profile.secretId, "Primary", apiKey)
        return profile
    }

    fun removeCustomProvider(profileId: String) {
        preferences.saveCustomProviders(preferences.loadCustomProviders().filterNot { it.id == profileId })
        vault.remove(com.jarves.mh.provider.CustomProviderProfile.secretIdFor(profileId))
    }

    fun selectCustomProvider(profileId: String) {
        val profile = preferences.loadCustomProviders().firstOrNull { it.id == profileId && it.enabled } ?: return
        val selected = profile.toProviderProfile(vault.contains(profile.secretId))
        preferences.saveProvider(selected, _state.value.agentKind)
        _state.update { it.copy(provider = selected) }
    }

    private fun touchProject(projectId: String) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            val updatedProjects = current.projects.map { p ->
                if (p.id == projectId) p.copy(updatedAtMillis = now) else p
            }
            val active = if (current.activeProject?.id == projectId) current.activeProject?.copy(updatedAtMillis = now) else current.activeProject
            current.copy(projects = updatedProjects, activeProject = active)
        }
        preferences.saveProjects(_state.value.projects)
    }

    private fun persistMessages(includeLiveProcess: Boolean = true, immediate: Boolean = false) {
        val current = _state.value
        val project = current.activeProject ?: return
        val chatId = current.activeChatId ?: return
        val liveItems = if (includeLiveProcess) current.liveProcess.filterNot(::isNoisyRuntimeItem) else emptyList()
        val messages = if (liveItems.isEmpty() && !current.liveThinking) {
            current.messages
        } else {
            val startedAt = current.workSegmentStartedAtMillis ?: current.taskStartedAtMillis ?: System.currentTimeMillis()
            current.messages + createInterruptedMessage(startedAt, liveItems)
        }
        val write = TranscriptWrite(project.id, chatId, messages)
        pendingTranscriptWrite = write
        if (immediate) {
            transcriptDebounceJob?.cancel()
            flushPendingTranscriptWrite()
        } else {
            if (transcriptDebounceJob?.isActive != true) {
                transcriptDebounceJob = viewModelScope.launch(Dispatchers.IO) {
                    delay(500)
                    flushPendingTranscriptWrite()
                }
            }
        }
    }

    private fun flushPendingTranscriptWrite() {
        val write = pendingTranscriptWrite ?: return
        pendingTranscriptWrite = null
        runCatching {
            preferences.saveMessages(write.projectId, write.chatId, write.messages)
        }
    }

    override fun onCleared() {
        flushPendingTranscriptWrite()
        super.onCleared()
    }

    private fun updateActiveChatTitle(prompt: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId ?: return
        val now = System.currentTimeMillis()
        val title = prompt.replace(Regex("\\s+"), " ").trim().let {
            if (it.length <= 42) it else it.take(39).trimEnd() + "…"
        }
        _state.update { current ->
            val chats = current.projectChats.map { chat ->
                if (chat.id == chatId) {
                    chat.copy(
                        title = if (chat.title == "New chat") title else chat.title,
                        updatedAtMillis = now,
                    )
                } else chat
            }.sortedByDescending { it.updatedAtMillis }
            current.copy(projectChats = chats)
        }
        preferences.saveProjectChats(project.id, _state.value.projectChats)
    }

    private fun extractMemoryFromSession(projectId: String, messages: List<ChatMessage>) {
        runCatching {
            val extracted = MemoryExtractor.extractMemories(messages)
            extracted.forEach { (key, value) ->
                memoryStore.upsert(projectId, key, value, MemorySource.AUTO)
            }
            val rich = MemoryExtractor.extractRichMemories(messages, projectId)
            rich.forEach { memoryStore.repository.save(it) }
            val checkpoint = MemoryExtractor.extractTaskCheckpoint(messages, projectId)
            if (checkpoint != null) {
                memoryStore.taskRepository.saveCheckpoint(checkpoint)
            }
            val updated = memoryStore.load(projectId)
            _state.update { it.copy(contextMemory = updated) }
        }.onFailure { error ->
            android.util.Log.w("MainViewModel", "Failed to extract session memory", error)
        }
    }

    fun setMemoryViewerVisible(visible: Boolean) {
        _state.update { it.copy(memoryViewerVisible = visible) }
    }

    fun upsertMemory(key: String, value: String, source: MemorySource = MemorySource.USER) {
        val project = _state.value.activeProject ?: return
        val updated = memoryStore.upsert(project.id, key, value, source)
        _state.update { it.copy(contextMemory = updated) }
    }

    fun deleteMemory(entryId: String) {
        val project = _state.value.activeProject ?: return
        val updated = memoryStore.delete(project.id, entryId)
        _state.update { it.copy(contextMemory = updated) }
    }

    fun clearMemory() {
        val project = _state.value.activeProject ?: return
        val updated = memoryStore.clear(project.id)
        _state.update { it.copy(contextMemory = updated) }
    }

    fun clearAutoMemory() {
        val project = _state.value.activeProject ?: return
        val updated = memoryStore.clearAuto(project.id)
        _state.update { it.copy(contextMemory = updated) }
    }

    companion object {
        private const val MINIMUM_INITIALIZATION_SCREEN_MS = 3_000L
        private const val MAX_VISIBLE_WORKSPACE_ENTRIES = 2_000
        private const val MAX_PROJECT_TERMINAL_HISTORY = 100
        private const val MAX_PROJECT_TERMINAL_OUTPUT = 200_000
        private const val MAX_ATTACHMENTS_PER_MESSAGE = 5
        private const val MAX_IMPORTED_PROJECT_BYTES = 8L * 1024L * 1024L * 1024L
        private const val MAX_IMPORTED_ZIP_ENTRIES = 100_000
        private const val AUTO_OPEN_DEDUPE_MS = 15_000L
        private const val CLAUDE_STATUS_RECHECK_MS = 30_000L
        private const val LEGACY_GITHUB_TOKEN_KEY = "GITHUB_APP"
        private const val GITHUB_DEVICE_URL = "https://github.com/login/device"
        private val GITHUB_DEVICE_CODE = Regex("\\b[A-Z0-9]{4}-[A-Z0-9]{4}\\b")
        private const val TEST_PROVIDER_DEFAULTS_VERSION = 1
        private const val TEST_OPENROUTER_BASE_URL = "https://openrouter.ai/api"
        private const val TEST_OPENROUTER_MODEL = "stealth/ox-alpha"

        internal fun createInterruptedMessage(
            startedAtMillis: Long?,
            liveItems: List<ActivityItem>,
        ): ChatMessage {
            val startedAt = startedAtMillis ?: System.currentTimeMillis()
            return ChatMessage(
                id = "interrupted-${UUID.randomUUID()}",
                fromUser = false,
                text = "",
                workItems = liveItems.map { it.copy(isComplete = true) } + ActivityItem(
                    "Task interrupted",
                    "The agent process stopped before reporting completion. Continue this chat to resume its official session.",
                ),
                workedMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L),
            )
        }
    }
}

/** Outcome of an /effort choice: the label of the level now in use, or why it was refused. */
sealed interface EffortChange {
    data class Applied(val label: String) : EffortChange
    data class Refused(val reason: String) : EffortChange
}
