package com.jarves.mh.ui

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.CompositionLocalProvider
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketTransitions
import com.jarves.mh.ui.theme.LocalSharedTransitionScope
import com.jarves.mh.ui.theme.LocalNavAnimatedVisibilityScope
import com.jarves.mh.ui.theme.heldWhileExiting
import com.jarves.mh.ui.theme.isTransitionTarget
import com.jarves.mh.ui.theme.glass.BackdropSourceScope
import com.jarves.mh.ui.kit.*
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarves.mh.model.AgentKind
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.ui.theme.glass.LiquidGlassConfig
import com.jarves.mh.ui.theme.glass.LiquidGlassHost

import androidx.compose.material.icons.outlined.SmartToy

private enum class RootScreen(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    PROJECTS("Projects", Icons.Outlined.Folder, Icons.Rounded.Folder),
    AGENT("Agent", Icons.Outlined.SmartToy, Icons.Rounded.SmartToy),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PocketDevApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val preferences = remember(context) { AppPreferences(context) }
    var reduceTransparency by remember(preferences) { mutableStateOf(preferences.reduceTransparency) }
    val glassConfig = remember(reduceTransparency) {
        LiquidGlassConfig.resolve(context, userPreference = reduceTransparency)
    }
    val setReduceTransparency = { on: Boolean ->
        preferences.reduceTransparency = on
        reduceTransparency = on
    }
    SideEffect { PocketMotion.reduced = glassConfig.reduceMotion }
    val projectsListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val banner = remember { BannerState() }
    LaunchedEffect(state.toastMessage) {
        state.toastMessage?.let { message ->
            banner.show(message, bannerKindFor(message))
            viewModel.consumeToast()
        }
    }
    LiquidGlassHost(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        config = glassConfig,
    ) {
      // Sheets, menus and banners render in this window above every screen, so they are glass
      // over the screen behind them.
      BannerHost(banner, Modifier.fillMaxSize()) {
       OverlayHost(Modifier.fillMaxSize()) {
        val destination = state.appDestination()
        SharedTransitionLayout {
            AnimatedContent(
                targetState = destination,
                transitionSpec = {
                    when {
                        initialState.depth == targetState.depth -> PocketTransitions.crossFade()
                        initialState.isStartup || targetState.isStartup -> PocketTransitions.settle()
                        else -> PocketTransitions.push(forward = targetState.depth > initialState.depth)
                    }
                },
                label = "app navigation",
            ) { shown ->
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalNavAnimatedVisibilityScope provides this,
                ) {
                    BackdropSourceScope(active = isTransitionTarget) {
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            AppScreen(shown, heldWhileExiting(state), viewModel, projectsListState, reduceTransparency, setReduceTransparency)
                        }
                    }
                }
            }
        }
       }
      }
    }
}

/** Banner style for a view-model status message: failures read as errors, completions as success. */
internal fun bannerKindFor(message: String): BannerKind {
    val text = message.lowercase()
    return when {
        listOf("fail", "error", "couldn't", "could not", "cannot", "can't", "unable", "denied", "invalid", "not found")
            .any { it in text } -> BannerKind.Error
        // Guidance ("not installed", "wait for…", "stop the…") is information, not success.
        listOf("not ", "no longer", "wait ", "stop ", "only ", "before ").any { it in text } -> BannerKind.Info
        listOf("saved", "copied", "deleted", "renamed", "installed", "updated", "created", "cloned", "imported", "exported", "connected", "signed in", "removed", " ready", "complete")
            .any { it in text } -> BannerKind.Success
        else -> BannerKind.Info
    }
}

/** Top-level screens, in navigation depth order: startup and setup, root tabs, then a project. */
internal enum class AppDestination(val depth: Int) {
    Loading(1),
    BackgroundSetup(1),
    RuntimeSetup(1),
    Installing(1),
    StartupError(1),
    AntigravityOnboarding(1),
    ProviderSetup(1),
    Root(2),
    ReadOnlyProject(3),
    Workspace(3),
    ;

    val isStartup: Boolean get() = depth == 1
}

/** Shared-element key so a project's name morphs from its row into the Workspace title. */
internal fun projectTitleKey(projectId: String?): String = "project-title-$projectId"

/** Which top-level screen [this] state shows; the order of checks is the screen priority. */
internal fun AppUiState.appDestination(): AppDestination = when {
    startupStage == StartupStage.CHECKING -> AppDestination.Loading
    !backgroundSetupComplete && startupStage == StartupStage.SETUP_REQUIRED -> AppDestination.BackgroundSetup
    startupStage == StartupStage.SETUP_REQUIRED -> AppDestination.RuntimeSetup
    startupStage == StartupStage.INSTALLING && showDetailedSetupProgress -> AppDestination.Installing
    startupStage == StartupStage.INSTALLING || startupStage == StartupStage.INITIALIZING -> AppDestination.Loading
    startupStage == StartupStage.ERROR -> AppDestination.StartupError
    startupStage == StartupStage.MODEL_SETUP && agentKind == AgentKind.ANTIGRAVITY -> AppDestination.AntigravityOnboarding
    startupStage == StartupStage.MODEL_SETUP -> AppDestination.ProviderSetup
    startupStage == StartupStage.READY && !backgroundSetupComplete -> AppDestination.BackgroundSetup
    readOnlyProject != null -> AppDestination.ReadOnlyProject
    activeProject != null && workspaceVisible -> AppDestination.Workspace
    else -> AppDestination.Root
}

@Composable
private fun AppScreen(
    destination: AppDestination,
    state: AppUiState,
    viewModel: MainViewModel,
    projectsListState: LazyListState,
    reduceTransparency: Boolean,
    setReduceTransparency: (Boolean) -> Unit,
) {
    when (destination) {
        AppDestination.Loading -> StartupLoadingScreen(
            state = state,
            themeMode = state.themeMode,
            onToggleTheme = viewModel::toggleTheme,
        )
        AppDestination.BackgroundSetup ->
            BackgroundTaskSetupScreen(
                themeMode = state.themeMode,
                onToggleTheme = viewModel::toggleTheme,
                onContinue = viewModel::finishBackgroundSetup,
            )
        AppDestination.RuntimeSetup -> RuntimeSetupPromptScreen(
            selectedStacks = state.selectedDevStacks,
            selectedAgent = state.agentKind,
            themeMode = state.themeMode,
            onToggleTheme = viewModel::toggleTheme,
            onToggleStack = viewModel::toggleDevStack,
            onSelectAgent = viewModel::selectAgent,
            onDownload = viewModel::startRuntimeSetup,
        )
        AppDestination.Installing ->
            RuntimeInstallationScreen(
                state = state,
                themeMode = state.themeMode,
                onToggleTheme = viewModel::toggleTheme,
            )
        AppDestination.StartupError -> StartupErrorScreen(
            message = state.startupError,
            isOffline = state.startupErrorIsOffline,
            logs = state.startupLogs,
            themeMode = state.themeMode,
            onToggleTheme = viewModel::toggleTheme,
            onRetry = viewModel::retryStartup,
        )
        AppDestination.AntigravityOnboarding ->
            AntigravityOnboardingScreen(
                state = state,
                onStartLogin = viewModel::startAntigravityLogin,
                onSubmitCode = viewModel::submitAntigravityCode,
                onContinue = viewModel::finishAntigravityOnboarding,
                onSelectAgent = viewModel::chooseOnboardingAgent,
                onToggleTheme = viewModel::toggleTheme,
            )
        AppDestination.ProviderSetup -> ProviderSetupScreen(
            initial = state.provider,
            onboarding = true,
            agentKind = state.agentKind,
            initialStep = 1,
            onSave = viewModel::finishOnboarding,
            onDiscover = viewModel::discoverModels,
            onValidate = viewModel::validateProvider,
            onSelectAgent = viewModel::chooseOnboardingAgent,
            onToggleTheme = viewModel::toggleTheme,
            themeMode = state.themeMode,
            claudeSignIn = ClaudeSignInActions(
                auth = state.claudeAuth,
                claudeInstalled = state.installedAgentVersions.containsKey(AgentKind.CLAUDE_CODE),
                busy = state.agentInstalling != null,
                onSignIn = viewModel::startClaudeLogin,
                onCancel = viewModel::cancelClaudeLogin,
                onSubmitCode = viewModel::submitClaudeCode,
                onSignOut = viewModel::logoutClaude,
                onRefresh = viewModel::refreshClaudeAuthStatus,
                onFinish = viewModel::finishClaudeOnboarding,
            ),
            codexSignIn = CodexSignInActions(
                auth = state.codexAuth,
                codexInstalled = state.installedAgentVersions.containsKey(AgentKind.CODEX),
                busy = state.agentInstalling != null,
                onSignIn = viewModel::startCodexLogin,
                onCancel = viewModel::cancelCodexLogin,
                onSignOut = viewModel::logoutCodex,
                onRefresh = viewModel::refreshCodexAuthStatus,
                onFinish = viewModel::finishCodexOnboarding,
            ),
        )
        AppDestination.ReadOnlyProject -> ReadOnlyProjectScreen(
            state = state,
            onBack = viewModel::closeReadOnlyProject,
            onSwitchChat = viewModel::switchReadOnlyChat,
            onContinueHere = viewModel::activateReadOnlyProject,
        )
        AppDestination.Workspace -> WorkspaceScreen(
            state = state,
            onBack = viewModel::closeProject,
            onSend = viewModel::sendPrompt,
            onStop = viewModel::stopTask,
            onApproval = viewModel::answerApproval,
            onRefreshFiles = viewModel::refreshProjectFiles,
            onOpenFile = viewModel::openFile,
            onInstallApk = viewModel::installApk,
            onCloseFile = viewModel::closeFile,
            onUndoChanges = viewModel::undoLastChanges,
            onKeepChanges = viewModel::keepLastChanges,
            onUndoFileChange = viewModel::undoFileChange,
            onKeepFileChange = viewModel::keepFileChange,
            onCreateChat = viewModel::createChat,
            onSwitchChat = viewModel::switchChat,
            onTerminalRun = viewModel::requestProjectTerminalCommand,
            onTerminalInput = viewModel::sendProjectTerminalInput,
            onTerminalInterrupt = viewModel::interruptProjectTerminalCommand,
            onTerminalPrepare = viewModel::prepareProjectTerminalCommand,
            onTerminalDraftConsumed = viewModel::consumeProjectTerminalDraft,
            onTerminalOpened = viewModel::openProjectTerminal,
            onTerminalStop = viewModel::stopProjectTerminalCommand,
            onTerminalClear = viewModel::clearProjectTerminal,
            onTerminalConfirm = viewModel::confirmProjectTerminalCommand,
            onTerminalCancel = viewModel::cancelProjectTerminalCommand,
            onUseSuggestedProjectRoot = viewModel::useSuggestedProjectRoot,
            onExportProject = viewModel::exportActiveProject,
            onAddAttachments = viewModel::addChatAttachments,
            onRemoveAttachment = viewModel::removePendingAttachment,
            onOpenAttachment = viewModel::openChatAttachment,
            onPromptChanged = viewModel::onPromptChanged,
            onOpenInspector = { viewModel.toggleAuxiliaryInspector(true) },
            onCloseInspector = { viewModel.toggleAuxiliaryInspector(false) },
            onTerminateSubagent = viewModel::terminateSubagent,
            onClearCompletedSubagents = viewModel::clearCompletedSubagents,
            onTerminateTask = viewModel::terminateBackgroundTask,
            onClearCompletedTasks = viewModel::clearCompletedTasks,
            onSelectSubagentForLogs = viewModel::selectSubagentForLogs,
            onSelectTaskForLogs = viewModel::selectTaskForLogs,
            onOpenSkills = { viewModel.toggleSkillsManager(true) },
            onCloseSkills = { viewModel.toggleSkillsManager(false) },
            onSetCustomizationScopeMode = viewModel::setCustomizationScopeMode,
            onToggleSkill = viewModel::toggleSkill,
            onToggleRule = viewModel::toggleRule,
            onLinkSkill = viewModel::linkSkill,
            onUnlinkSkill = viewModel::unlinkSkill,
            onImportSkill = viewModel::importSkill,
            onPromoteSkillToGlobal = viewModel::promoteSkillToGlobal,
            onPromoteRuleToGlobal = viewModel::promoteRuleToGlobal,
            onSaveProjectRule = viewModel::saveProjectRule,
            onCreateSkill = viewModel::createGlobalSkill,
            onOpenModelPicker = { viewModel.toggleModelPicker(true) },
            onCloseModelPicker = { viewModel.toggleModelPicker(false) },
            onSelectModel = { viewModel.applyModelChoice(it) },
            onCloseEffortPicker = { viewModel.toggleEffortPicker(false) },
            onSelectEffort = viewModel::chooseEffortFromPicker,
            onOpenMemoryViewer = { viewModel.setMemoryViewerVisible(true) },
            onCloseMemoryViewer = { viewModel.setMemoryViewerVisible(false) },
            onAddMemoryEntry = { k, v -> viewModel.upsertMemory(k, v) },
            onDeleteMemoryEntry = viewModel::deleteMemory,
            onClearAutoMemory = viewModel::clearAutoMemory,
            onClearAllMemory = viewModel::clearMemory,
        )
        AppDestination.Root -> RootScreenHost(state, viewModel, projectsListState, reduceTransparency, setReduceTransparency)
    }
}

internal fun formatMegabytes(bytes: Long): String = "%.1f MB".format(bytes / 1_048_576.0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScreenHost(
    state: AppUiState,
    viewModel: MainViewModel,
    projectsListState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    reduceTransparency: Boolean = false,
    onSetReduceTransparency: (Boolean) -> Unit = {},
) {
    var screen by rememberSaveable { mutableStateOf(RootScreen.PROJECTS) }
    var showQuickTerminal by rememberSaveable { mutableStateOf(false) }
    val terminalAnchor = rememberOverlayAnchor()
    var floatingNavMeasuredHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val keyboardVisible = WindowInsets.ime.getBottom(density) > 0
    val terminalLines by viewModel.terminalLines.collectAsStateWithLifecycle()
    val isTerminalRunning by viewModel.isTerminalRunning.collectAsStateWithLifecycle()
    val terminalLiveOutput by viewModel.terminalLiveOutput.collectAsStateWithLifecycle()
    val terminalCurrentCommand by viewModel.terminalCurrentCommand.collectAsStateWithLifecycle()

    val minimize = rememberTabBarMinimizeState()
    LaunchedEffect(screen) { minimize.expand() }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!keyboardVisible) {
                // The dock: a floating glass tab bar that shrinks to a circle while a list
                // scrolls down, beside the Terminal circle (one piece of glass on Android 13+).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = PocketSpacing.xl, vertical = PocketSpacing.sm)
                        .onGloballyPositioned { coordinates ->
                            val heightDp = with(density) { coordinates.size.height.toDp() }
                            if (heightDp > 0.dp && heightDp != floatingNavMeasuredHeightDp) {
                                floatingNavMeasuredHeightDp = heightDp
                            }
                        },
                ) {
                    FloatingTabBar(
                        tabs = RootScreen.entries.map { TabItem(it.label, it.icon, it.selectedIcon) },
                        selectedIndex = screen.ordinal,
                        onSelect = { screen = RootScreen.entries[it] },
                        minimized = minimize.minimized,
                        onExpand = minimize::expand,
                        accessory = {
                            TabBarAccessory(
                                icon = Icons.Outlined.Terminal,
                                contentDescription = "Terminal",
                                onClick = { showQuickTerminal = true },
                                modifier = Modifier.overlayAnchor(terminalAnchor),
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        val navBarsBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val scaffoldBottom = padding.calculateBottomPadding()
        val actualNavFootprint = maxOf(floatingNavMeasuredHeightDp, scaffoldBottom).takeIf { it > 0.dp }
            ?: (navBarsBottomInset + 88.dp)
        val floatingNavClearance = actualNavFootprint + 28.dp

        // Each screen's scrolling list registers the shared backdrop source (one on screen at a
        // time), so its glass top bar and this dock both sample real content as siblings.
        Box(
            modifier = Modifier.fillMaxSize().nestedScroll(minimize.connection),
        ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { PocketTransitions.crossFade() },
                label = "root tab",
            ) { tab ->
            BackdropSourceScope(active = isTransitionTarget) {
            when (tab) {
                RootScreen.PROJECTS -> ProjectsScreen(
                    state = state,
                    listState = projectsListState,
                    bottomBarPadding = floatingNavClearance,
                    onOpen = viewModel::openProject,
                    onCreate = viewModel::createProject,
                    onCreateQuickProject = viewModel::createQuickProject,
                    onImportZip = viewModel::importZipProject,
                    onCloneGit = viewModel::clonePublicGitRepository,
                    onStartGitHubLogin = viewModel::startGitHubLogin,
                    onGenerateNewGitHubCode = viewModel::generateNewGitHubCode,
                    onRefreshGitHub = viewModel::refreshGitHubRepositories,
                    onDisconnectGitHub = viewModel::disconnectGitHub,
                    onCloneGitHub = viewModel::cloneGitHubRepository,
                    onRenameProject = viewModel::renameProject,
                    onDeleteProject = viewModel::deleteProject,
                    onSettings = { screen = RootScreen.SETTINGS },
                    onPing = viewModel::pingApi,
                    onToggleTheme = viewModel::toggleTheme,
                    onInstallUpdate = viewModel::installAppUpdate,
                )
                RootScreen.AGENT -> AgentScreen(
                    state = state,
                    bottomBarPadding = floatingNavClearance,
                    onSaveProvider = { profile, key ->
                        viewModel.updateProvider(profile, key)
                    },
                    onDiscoverModels = viewModel::discoverModels,
                    loadSavedModels = viewModel::savedModelList,
                    onValidateProvider = viewModel::validateProvider,
                    onPing = viewModel::pingApi,
                    getSavedApiKey = viewModel::getSavedApiKey,
                    getSavedApiKeys = viewModel::getSavedApiKeys,
                    onAddApiKey = viewModel::addApiKey,
                    onActivateApiKey = viewModel::activateApiKey,
                    onRemoveApiKey = viewModel::removeApiKey,
                    onSelectAgent = viewModel::selectAgent,
                    onInstallAgent = viewModel::installAgent,
                    onCheckAgentUpdates = viewModel::checkAgentUpdates,
                    onUpdateAgent = viewModel::updateAgent,
                    onStartAntigravityLogin = viewModel::startAntigravityLogin,
                    onSubmitAntigravityCode = viewModel::submitAntigravityCode,
                    onLogoutAntigravity = viewModel::logoutAntigravity,
                    onRemoveAntigravityAccount = viewModel::removeAntigravityAccount,
                    onSetAntigravityPrimaryAccount = viewModel::setAntigravityPrimaryAccount,
                    onToggleAntigravityAccountEnabled = viewModel::toggleAntigravityAccountEnabled,
                    onSetAntigravityLoadBalancingStrategy = viewModel::setAntigravityLoadBalancingStrategy,
                    onSetAntigravityFailoverEnabled = viewModel::setAntigravityFailoverEnabled,
                    onRefreshAntigravityModels = viewModel::refreshAntigravityModels,
                    onSetAntigravityModel = viewModel::setAntigravityModel,
                    onSetAntigravityEffort = viewModel::setAntigravityEffort,
                    onSetCodexReasoningEffort = viewModel::setCodexReasoningEffort,
                    onStartClaudeLogin = viewModel::startClaudeLogin,
                    onCancelClaudeLogin = viewModel::cancelClaudeLogin,
                    onSubmitClaudeCode = viewModel::submitClaudeCode,
                    onLogoutClaude = viewModel::logoutClaude,
                    onRefreshClaudeAuth = viewModel::refreshClaudeAuthStatus,
                    onStartCodexLogin = viewModel::startCodexLogin,
                    onCancelCodexLogin = viewModel::cancelCodexLogin,
                    onLogoutCodex = viewModel::logoutCodex,
                    onRefreshCodexAuth = viewModel::refreshCodexAuthStatus,
                )
                RootScreen.SETTINGS -> Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    SettingsScreen(
                        state = state,
                        bottomBarPadding = floatingNavClearance,
                        onSetThemeMode = viewModel::setThemeMode,
                        onClearTerminal = viewModel::clearTerminal,
                        onInstallDevStack = viewModel::installDevStack,
                        onRemoveDevStack = viewModel::removeDevStack,
                        reduceTransparency = reduceTransparency,
                        onSetReduceTransparency = onSetReduceTransparency,
                        initialDebugUpdateManifestUrl = viewModel.debugUpdateManifestUrl(),
                        onSetDebugUpdateManifestUrl = viewModel::setDebugUpdateManifestUrl,
                        onClearDebugUpdateManifestUrl = viewModel::clearDebugUpdateManifestUrl,
                        onOpenAgent = { screen = RootScreen.AGENT },
                    )
                }
            }
            }
            }
        }
    }
    GlassSheet(
        onDismiss = { showQuickTerminal = false },
        visible = showQuickTerminal,
        title = "Terminal",
        anchor = terminalAnchor,
    ) {
        Box(Modifier.fillMaxSize()) {
            TerminalScreen(
                lines = terminalLines,
                isRunning = isTerminalRunning,
                onRun = viewModel::runTerminalCommand,
                onInput = viewModel::sendTerminalInput,
                onInterrupt = viewModel::interruptTerminalCommand,
                onClear = viewModel::clearTerminal,
                onToggleTheme = viewModel::toggleTheme,
                themeMode = state.themeMode,
                liveOutput = terminalLiveOutput,
                currentCommand = terminalCurrentCommand,
                showThemeAction = false,
                showQuickCommands = true,
                compactHeader = true,
            )
        }
    }
}
