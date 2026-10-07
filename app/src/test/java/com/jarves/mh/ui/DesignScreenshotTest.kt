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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.ChatMessage
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
        ),
        ChatMessage(fromUser = true, text = "Great. Can you also add a test for the preference?"),
        ChatMessage(
            fromUser = false,
            text = "Done. `PreferencesTest` covers the default value, a write, and a read after restart. All 14 tests pass.",
        ),
    )
    private val baseState = AppUiState(projects = projects)

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            PocketTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                LiquidGlassHost(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    config = LiquidGlassConfig(),
                ) { content() }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        captureScreenRoboImage("${System.getProperty("roborazzi.output.dir")}/$name-${if (dark) "dark" else "light"}.png")
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

    private fun workspaceChat(dark: Boolean) = shot("chat", dark) {
        WorkspaceScreen(
            state = baseState.copy(
                activeProject = projects.first(),
                workspaceVisible = true,
                projectChats = listOf(chat),
                activeChatId = chat.id,
                messages = messages,
            ),
            onBack = {}, onSend = {}, onStop = {}, onApproval = {}, onRefreshFiles = {}, onOpenFile = {},
            onCloseFile = {}, onUndoChanges = {}, onKeepChanges = {}, onUndoFileChange = {}, onKeepFileChange = {},
            onCreateChat = {}, onSwitchChat = {}, onTerminalRun = {}, onTerminalInput = {}, onTerminalInterrupt = {},
            onTerminalPrepare = {}, onTerminalDraftConsumed = {}, onTerminalOpened = {}, onTerminalStop = {},
            onTerminalClear = {}, onTerminalConfirm = {}, onTerminalCancel = {}, onUseSuggestedProjectRoot = {},
            onExportProject = {}, onAddAttachments = {}, onRemoveAttachment = {}, onOpenAttachment = {},
            onBuildAndRunAndroid = {},
        )
    }

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
}
