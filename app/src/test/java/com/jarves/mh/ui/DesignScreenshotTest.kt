package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketTheme
import com.jarves.mh.ui.theme.glass.LiquidGlassConfig
import com.jarves.mh.ui.theme.glass.LiquidGlassFloatingNavBar
import com.jarves.mh.ui.theme.glass.LiquidGlassFloatingNavBarItem
import com.jarves.mh.ui.theme.glass.LiquidGlassHost
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opt-in design screenshots (Roborazzi, test-only): ./gradlew :app:testOnlineDebugUnitTest -Pscreenshots
 * Renders the main screens in light and dark into build/outputs/roborazzi for visual review.
 * The Projects dock is a stand-in for RootScreenHost (which needs a live MainViewModel).
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
    private fun RootDockStandIn(content: @Composable (bottom: androidx.compose.ui.unit.Dp) -> Unit) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    LiquidGlassFloatingNavBar(modifier = Modifier.fillMaxWidth(), layerSource = LiquidGlassLayers.Background) {
                        listOf(Icons.Default.Folder to "Projects", Icons.Default.SmartToy to "Agent", Icons.Default.Settings to "Settings")
                            .forEachIndexed { i, (icon, label) ->
                                LiquidGlassFloatingNavBarItem(
                                    selected = i == 0,
                                    onClick = {},
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label, fontSize = 11.sp) },
                                    selectedTint = MaterialTheme.colorScheme.primary,
                                )
                            }
                    }
                }
            },
        ) { _ -> Box(Modifier.fillMaxSize()) { content(120.dp) } }
    }

    private fun projects(dark: Boolean) = shot("projects", dark) {
        RootDockStandIn { bottom ->
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

    private fun agent(dark: Boolean) = shot("agent", dark) {
        RootDockStandIn { bottom ->
            AgentScreen(
                state = baseState,
                bottomBarPadding = bottom,
                onSaveProvider = { _, _ -> },
                onDiscoverModels = { _, _ -> ModelDiscoveryResult.Failure("offline") },
                onValidateProvider = { _, _, _ -> ConnectionValidation.Success("ok") },
                onPing = {},
                getSavedApiKey = { "" },
                getSavedApiKeys = { emptyList() },
                onAddApiKey = { _, _, _ -> emptyList() },
                onActivateApiKey = { _, _ -> emptyList() },
                onRemoveApiKey = { _, _ -> emptyList() },
            )
        }
    }

    private fun settings(dark: Boolean) = shot("settings", dark) {
        RootDockStandIn { _ ->
            SettingsScreen(
                state = baseState,
                onSaveProvider = { _, _ -> },
                onDiscoverModels = { _, _ -> ModelDiscoveryResult.Failure("offline") },
                onValidateProvider = { _, _, _ -> ConnectionValidation.Success("ok") },
                onSetThemeMode = {},
                onPing = {},
                onClearTerminal = {},
                getSavedApiKey = { "" },
                getSavedApiKeys = { emptyList() },
                onAddApiKey = { _, _, _ -> emptyList() },
                onActivateApiKey = { _, _ -> emptyList() },
                onRemoveApiKey = { _, _ -> emptyList() },
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
    @Test fun agentLight() = agent(false)
    @Test fun agentDark() = agent(true)
    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)
    @Test fun chatLight() = workspaceChat(false)
    @Test fun chatDark() = workspaceChat(true)
}
