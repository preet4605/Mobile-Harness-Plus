package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ModelQuota
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.runtime.AntigravityAuthState
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.ui.kit.FloatingTabBar
import com.jarves.mh.ui.kit.OverlayHost
import com.jarves.mh.ui.kit.TabBarAccessory
import com.jarves.mh.ui.kit.TabItem
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketTheme
import com.jarves.mh.ui.theme.glass.LiquidGlassConfig
import com.jarves.mh.ui.theme.glass.LiquidGlassHost
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opt-in design screenshots (Roborazzi, test-only): ./gradlew :app:testOnlineDebugUnitTest -Pscreenshots
 * Renders the main screens in light and dark into build/outputs/roborazzi for visual review.
 * The dock is a stand-in for RootScreenHost (which needs a live MainViewModel).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class DesignScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val projects = listOf(
        Project(name = "weather-app", description = "Kotlin · Compose", language = "Kotlin", updatedAtMillis = now - 5 * 60_000),
        Project(name = "landing-page", description = "TypeScript · Vite", language = "TypeScript", updatedAtMillis = now - 3 * 3_600_000),
        Project(name = "api-server", description = "Node.js · Express", language = "JavaScript", updatedAtMillis = now - 2 * 86_400_000),
        Project(name = "scripts", description = "Python utilities", language = "Python", updatedAtMillis = now - 9 * 86_400_000),
    )
    private val chat = ProjectChat(title = "Add dark mode")
    private val messages = listOf(
        ChatMessage(fromUser = true, text = "Add a dark mode toggle to the settings screen and persist it."),
        ChatMessage(
            fromUser = false,
            text = "I added a `ThemeMode` preference and a toggle in **Settings**. The choice is saved with DataStore and applied on launch.\n\n" +
                "```kotlin\nval mode by prefs.themeMode.collectAsState(ThemeMode.SYSTEM)\nPocketTheme(mode) { App() }\n```\n\n" +
                "Changed files:\n- `SettingsScreen.kt`\n- `Preferences.kt`\n- `MainActivity.kt`",
            workedMillis = 42_000,
        ),
        ChatMessage(fromUser = true, text = "Great. Can you also add a test for the preference?"),
        ChatMessage(
            fromUser = false,
            text = "Done. `PreferencesTest` covers the default value, a write, and a read after restart. All 14 tests pass.",
            workedMillis = 68_000,
        ),
    )
    private val baseState = AppUiState(projects = projects)

    private fun shot(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        config: LiquidGlassConfig = LiquidGlassConfig(),
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            PocketTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
                ) {
                    LiquidGlassHost(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        config = config,
                    ) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val suffix = (if (dark) "dark" else "light") + if (fontScale != 1f) "-large" else ""
        captureScreenRoboImage("${System.getProperty("roborazzi.output.dir")}/$name-$suffix.png")
    }

    @Composable
    private fun RootDockStandIn(selected: Int, content: @Composable (bottom: Dp) -> Unit) {
        OverlayHost(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                content(120.dp)
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = PocketSpacing.xl, vertical = PocketSpacing.sm),
                ) {
                    FloatingTabBar(
                        tabs = listOf(
                            TabItem("Projects", Icons.Outlined.Folder),
                            TabItem("Agent", Icons.Outlined.SmartToy),
                            TabItem("Settings", Icons.Outlined.Settings),
                        ),
                        selectedIndex = selected,
                        onSelect = {},
                        accessory = { TabBarAccessory(Icons.Outlined.Terminal, "Terminal", {}) },
                    )
                }
            }
        }
    }

    private fun projects(dark: Boolean) = shot("projects", dark) {
        RootDockStandIn(0) { bottom ->
            ProjectsScreen(
                state = baseState,
                bottomBarPadding = bottom,
                onOpen = {}, onCreate = {}, onCreateQuickProject = {}, onImportZip = {}, onCloneGit = {},
                onStartGitHubLogin = {}, onGenerateNewGitHubCode = {}, onRefreshGitHub = {}, onDisconnectGitHub = {},
                onCloneGitHub = {}, onRenameProject = { _, _ -> }, onDeleteProject = {}, onSettings = {}, onPing = {},
                onToggleTheme = {}, onInstallUpdate = {},
            )
        }
    }

    private fun agent(name: String, dark: Boolean, state: AppUiState = baseState, keys: List<ApiKeyInfo> = emptyList()) = shot(name, dark) {
        RootDockStandIn(1) { bottom ->
            AgentScreen(
                state = state,
                bottomBarPadding = bottom,
                onSaveProvider = { _, _ -> },
                onDiscoverModels = { _, _ -> ModelDiscoveryResult.Failure("offline") },
                onValidateProvider = { _, _, _ -> ConnectionValidation.Success("ok") },
                onPing = {},
                getSavedApiKey = { if (keys.isEmpty()) "" else "sk-test" },
                getSavedApiKeys = { keys },
                onAddApiKey = { _, _, _ -> keys },
                onActivateApiKey = { _, _ -> keys },
                onRemoveApiKey = { _, _ -> keys },
            )
        }
    }

    private val deepSeekState = baseState.copy(
        agentKind = AgentKind.DEEPSEEK_HARNESS,
        installedAgentVersions = mapOf(AgentKind.DEEPSEEK_HARNESS to "0.9.2", AgentKind.CLAUDE_CODE to "2.1.0"),
        provider = ProviderProfile(ProviderKind.DEEPSEEK, ProviderKind.DEEPSEEK.defaultBaseUrl, "deepseek-v4-flash"),
        apiPingStatus = ApiPingStatus.OK,
    )
    private val deepSeekKeys = listOf(ApiKeyInfo("1", "Personal", isActive = true), ApiKeyInfo("2", "Work"))

    private val antigravityState = baseState.copy(
        agentKind = AgentKind.ANTIGRAVITY,
        installedAgentVersions = mapOf(AgentKind.ANTIGRAVITY to "1.4.0"),
        antigravityAuth = AntigravityAuthState(status = AntigravityAuthStatus.SIGNED_IN, accountEmail = "dev@example.com"),
        antigravityAccounts = listOf(
            AntigravityAccount(email = "dev@example.com", isPrimary = true, modelQuotas = mapOf("gemini-3.8-flash-high" to ModelQuota(0.82f))),
            AntigravityAccount(email = "backup@example.com", modelQuotas = mapOf("gemini-3.8-flash-high" to ModelQuota(0.12f))),
        ),
        antigravityModel = "gemini-3.8-flash-high",
        antigravityEffort = "medium",
        apiPingStatus = ApiPingStatus.OK,
    )

    private fun settings(dark: Boolean) = shot("settings", dark) {
        RootDockStandIn(2) { bottom ->
            SettingsScreen(
                state = baseState.copy(installedDevStacks = setOf(DevStack.WEB, DevStack.PYTHON)),
                onSetThemeMode = {},
                onClearTerminal = {},
                bottomBarPadding = bottom,
            )
        }
    }

    private val workspaceState = baseState.copy(
        activeProject = projects.first(),
        workspaceVisible = true,
        projectChats = listOf(chat),
        activeChatId = chat.id,
        messages = messages,
    )

    private val runningState = workspaceState.copy(
        messages = messages.take(3),
        isRunning = true,
        liveProcess = listOf(
            ActivityItem("Read", "app/src/test/PreferencesTest.kt"),
            ActivityItem("Running Bash", "./gradlew testDebugUnitTest --tests PreferencesTest", isComplete = false, isCommand = true),
        ),
        taskStartedAtMillis = now - 42_000,
        subagents = listOf(SubagentInfo("a1", "reviewer", "Reviewer", SubagentState.RUNNING, "Checking the new test")),
        pendingAttachments = listOf(ChatAttachment(displayName = "screenshot.png", relativePath = "a/screenshot.png", mimeType = "image/png", sizeBytes = 284_000)),
    )

    private val approvalState = workspaceState.copy(
        messages = messages.take(3),
        isRunning = true,
        pendingApproval = ToolRequest(
            sessionId = "s",
            toolName = "Bash",
            explanation = "Delete the generated build folder before rebuilding.",
            affectedPaths = listOf("app/build/"),
            risk = RiskLevel.HIGH,
        ),
        changes = listOf(ChangeItem("app/src/main/Settings.kt", 24, 3)),
    )

    private val filesState = workspaceState.copy(
        suggestedProjectRoot = "weather-app",
        workspaceFiles = listOf(
            WorkspaceEntry("app", "app", true, 0),
            WorkspaceEntry("app/build.gradle.kts", "build.gradle.kts", false, 1, 2_340),
            WorkspaceEntry("app/src", "src", true, 1),
            WorkspaceEntry("app/weather-debug.apk", "weather-debug.apk", false, 1, 8_420_000),
            WorkspaceEntry("gradle", "gradle", true, 0),
            WorkspaceEntry("README.md", "README.md", false, 0, 1_204),
            WorkspaceEntry("settings.gradle.kts", "settings.gradle.kts", false, 0, 412),
        ),
    )

    private val fileState = workspaceState.copy(
        openedFilePath = "app/src/main/java/Settings.kt",
        openedFileContent = "package demo\n\nimport androidx.compose.runtime.Composable\n\n@Composable\nfun Settings(prefs: Preferences) {\n    val mode by prefs.themeMode.collectAsState(ThemeMode.SYSTEM)\n    ThemeToggle(mode, onChange = prefs::setThemeMode)\n}\n",
    )

    private fun workspace(
        name: String,
        dark: Boolean,
        state: AppUiState,
        tab: WorkspaceTab = WorkspaceTab.CHAT,
        fontScale: Float = 1f,
        config: LiquidGlassConfig = LiquidGlassConfig(),
    ) = shot(name, dark, fontScale, config) {
        OverlayHost(Modifier.fillMaxSize()) {
            WorkspaceScreen(
                state = state,
                onBack = {}, onSend = {}, onStop = {}, onApproval = {}, onRefreshFiles = {}, onOpenFile = {},
                onCloseFile = {}, onUndoChanges = {}, onKeepChanges = {}, onUndoFileChange = {}, onKeepFileChange = {},
                onCreateChat = {}, onSwitchChat = {}, onTerminalRun = {}, onTerminalInput = {}, onTerminalInterrupt = {},
                onTerminalPrepare = {}, onTerminalDraftConsumed = {}, onTerminalOpened = {}, onTerminalStop = {},
                onTerminalClear = {}, onTerminalConfirm = {}, onTerminalCancel = {}, onUseSuggestedProjectRoot = {},
                onExportProject = {}, onAddAttachments = {}, onRemoveAttachment = {}, onOpenAttachment = {},
                initialTab = tab,
            )
        }
    }

    private fun workspaceChat(dark: Boolean) = workspace("chat", dark, workspaceState)

    @Test fun projectsLight() = projects(false)
    @Test fun projectsDark() = projects(true)
    @Test fun agentLight() = agent("agent", false)
    @Test fun agentDark() = agent("agent", true)
    @Test fun agentKeysLight() = agent("agent-keys", false, deepSeekState, deepSeekKeys)
    @Test fun agentKeysDark() = agent("agent-keys", true, deepSeekState, deepSeekKeys)
    @Test fun agentAntigravityLight() = agent("agent-antigravity", false, antigravityState)
    @Test fun agentAntigravityDark() = agent("agent-antigravity", true, antigravityState)
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
    @Test fun chatLight() = workspaceChat(false)
    @Test fun chatDark() = workspaceChat(true)
    @Test fun chatRunningLight() = workspace("chat-running", false, runningState)
    @Test fun chatRunningDark() = workspace("chat-running", true, runningState)
    @Test fun chatApprovalLight() = workspace("chat-approval", false, approvalState)
    @Test fun chatApprovalDark() = workspace("chat-approval", true, approvalState)
    @Test fun filesLight() = workspace("files", false, filesState, WorkspaceTab.FILES)
    @Test fun filesDark() = workspace("files", true, filesState, WorkspaceTab.FILES)
    @Test fun fileViewerLight() = workspace("file-viewer", false, fileState)
    @Test fun fileViewerDark() = workspace("file-viewer", true, fileState)

    // Onboarding and setup. Robolectric reports an x86 host, so the device check is told it runs
    // on arm64 for the supported renders.
    private fun withArm64(block: () -> Unit) {
        val abis = android.os.Build.SUPPORTED_ABIS
        val arch = System.getProperty("os.arch")
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
        System.setProperty("os.arch", "aarch64")
        try {
            block()
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "SUPPORTED_ABIS", abis)
            System.setProperty("os.arch", arch)
        }
    }

    private fun setup(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        tap: String? = null,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            PocketTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
                ) {
                    LiquidGlassHost(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        config = LiquidGlassConfig(),
                    ) { OverlayHost(Modifier.fillMaxSize()) { content() } }
                }
            }
        }
        if (tap != null) {
            compose.waitForIdle()
            compose.onNodeWithText(tap).performClick()
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val suffix = (if (dark) "dark" else "light") + if (fontScale != 1f) "-large" else ""
        captureScreenRoboImage("${System.getProperty("roborazzi.output.dir")}/$name-$suffix.png")
    }

    private fun backgroundSetup(dark: Boolean, fontScale: Float = 1f) =
        setup("setup-permissions", dark, fontScale) { BackgroundTaskSetupScreen(onContinue = {}) }

    private fun deviceCheck(dark: Boolean) = withArm64 {
        setup("setup-device", dark) {
            RuntimeSetupPromptScreen(selectedStacks = setOf(DevStack.PYTHON), selectedAgent = AgentKind.DEEPSEEK_HARNESS, onToggleStack = {}, onDownload = {})
        }
    }

    private fun toolchains(dark: Boolean) = withArm64 {
        setup("setup-tools", dark, tap = "Continue to tool setup") {
            RuntimeSetupPromptScreen(selectedStacks = setOf(DevStack.PYTHON), selectedAgent = AgentKind.DEEPSEEK_HARNESS, onToggleStack = {}, onDownload = {})
        }
    }

    private val installingState = AppUiState(
        startupProgress = 0.42f,
        startupMessage = "Downloading the Python toolchain",
        startupBytes = 121_634_816L to 304_087_040L,
        selectedDevStacks = setOf(DevStack.PYTHON),
        startupLogs = listOf("$ apt-get install python3", "Unpacking python3.12 (3.12.3-1) ..."),
    )

    private fun installing(dark: Boolean) = setup("setup-installing", dark) { RuntimeInstallationScreen(state = installingState) }

    private fun loading(dark: Boolean) = setup("setup-loading", dark) { StartupLoadingScreen(state = AppUiState()) }

    private fun startupError(dark: Boolean) = setup("setup-error", dark) {
        StartupErrorScreen(
            message = "The runtime download was interrupted before it finished. Your progress is kept.",
            isOffline = false,
            logs = listOf("curl: (56) Recv failure"),
            onRetry = {},
        )
    }

    private fun antigravitySignIn(dark: Boolean) = setup("setup-antigravity", dark) {
        AntigravityOnboardingScreen(
            state = AppUiState(
                agentKind = AgentKind.ANTIGRAVITY,
                antigravityAuth = AntigravityAuthState(status = AntigravityAuthStatus.AWAITING_CODE, authorizationUrl = "https://accounts.google.com/o/oauth2/auth"),
            ),
            onStartLogin = {}, onSubmitCode = {}, onContinue = {}, onSelectAgent = {}, onToggleTheme = {},
        )
    }

    private fun provider(name: String, dark: Boolean, profile: ProviderProfile, agent: AgentKind, step: Int, fontScale: Float = 1f) =
        setup(name, dark, fontScale) {
            ProviderSetupScreen(
                initial = profile,
                onboarding = true,
                agentKind = agent,
                initialStep = step,
                onSave = { _, _ -> },
                onDiscover = { _, _ -> ModelDiscoveryResult.Success(emptyList(), "") },
                onValidate = { _, _, _ -> ConnectionValidation.Success("Connected") },
                onSelectAgent = {},
                onToggleTheme = {},
                claudeSignIn = if (profile.kind == ProviderKind.CLAUDE) {
                    ClaudeSignInActions(
                        auth = com.jarves.mh.runtime.ClaudeAuthState(),
                        claudeInstalled = true,
                        busy = false,
                        onSignIn = {}, onCancel = {}, onSubmitCode = {}, onSignOut = {}, onRefresh = {}, onFinish = {},
                    )
                } else null,
            )
        }

    private val customGateway = ProviderProfile(ProviderKind.CUSTOM, "https://gateway.example.com/v1", "deepseek-v4-pro")
    private val claudeProfile = ProviderProfile(ProviderKind.CLAUDE, "", "default")

    // Accessibility and glass tiers: double text size, reduce transparency (solid), increased contrast.
    @Test fun a11yChatLarge() = workspace("a11y-chat", false, runningState, fontScale = 2f)
    @Test fun a11yFilesLarge() = workspace("a11y-files", true, filesState, WorkspaceTab.FILES, fontScale = 2f)
    @Test fun a11yToolsLarge() = withArm64 {
        setup("a11y-tools", false, fontScale = 2f, tap = "Continue to tool setup") {
            RuntimeSetupPromptScreen(selectedStacks = setOf(DevStack.PYTHON), selectedAgent = AgentKind.DEEPSEEK_HARNESS, onToggleStack = {}, onDownload = {})
        }
    }
    @Test fun a11yChatSolidDark() = workspace("a11y-chat-solid", true, runningState, config = LiquidGlassConfig(reduceTransparency = true))
    @Test fun a11yChatContrastLight() = workspace("a11y-chat-contrast", false, runningState, config = LiquidGlassConfig(increaseContrast = true))

    @Test fun setupPermissionsLight() = backgroundSetup(false)
    @Test fun setupPermissionsDark() = backgroundSetup(true)
    @Test fun setupPermissionsLarge() = backgroundSetup(false, fontScale = 1.3f)
    @Test fun setupDeviceLight() = deviceCheck(false)
    @Test fun setupDeviceDark() = deviceCheck(true)
    @Test fun setupToolsLight() = toolchains(false)
    @Test fun setupToolsDark() = toolchains(true)
    @Test fun setupInstallingLight() = installing(false)
    @Test fun setupInstallingDark() = installing(true)
    @Test fun setupLoadingDark() = loading(true)
    @Test fun setupErrorLight() = startupError(false)
    @Test fun setupErrorLogDark() = withArm64 { setup("setup-error-log", true, tap = "Setup log") { StartupErrorScreen(message = "The runtime download was interrupted before it finished. Your progress is kept.", isOffline = true, logs = listOf("$ curl -fL runtime.tar.xz", "curl: (56) Recv failure: Connection reset by peer"), onRetry = {}) } }
    @Test fun setupAntigravityDark() = antigravitySignIn(true)
    @Test fun setupProviderLight() = provider("setup-provider", false, customGateway, AgentKind.DEEPSEEK_HARNESS, step = 1)
    @Test fun setupProviderDark() = provider("setup-provider", true, customGateway, AgentKind.DEEPSEEK_HARNESS, step = 1)
    @Test fun setupCredentialsLight() = provider("setup-credentials", false, customGateway, AgentKind.DEEPSEEK_HARNESS, step = 2)
    @Test fun setupCredentialsDark() = provider("setup-credentials", true, customGateway, AgentKind.DEEPSEEK_HARNESS, step = 2)
    @Test fun setupCredentialsLarge() = provider("setup-credentials", false, customGateway, AgentKind.DEEPSEEK_HARNESS, step = 2, fontScale = 1.3f)
    @Test fun setupClaudeLight() = provider("setup-claude", false, claudeProfile, AgentKind.CLAUDE_CODE, step = 2)
    @Test fun setupClaudeDark() = provider("setup-claude", true, claudeProfile, AgentKind.CLAUDE_CODE, step = 2)
}
