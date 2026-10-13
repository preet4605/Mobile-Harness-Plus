package com.jarves.mh.ui

import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import com.jarves.mh.runtime.AndroidBuildIssue
import com.jarves.mh.runtime.androidBuildEstimateText
import com.jarves.mh.runtime.formatBuildDuration
import com.jarves.mh.runtime.typicalAndroidBuildMillis
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.CODEX_DEFAULT_EFFORT_ARG
import com.jarves.mh.model.ModelSlot
import com.jarves.mh.model.ProjectChat
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SlashCommand
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.ToolRequest
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.model.codexEffortChoices
import com.jarves.mh.model.effortLabel
import com.jarves.mh.model.effortLevelsFor
import com.jarves.mh.model.modelSlotFor
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.BarHeight
import com.jarves.mh.model.WorkStatus
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.jarves.mh.ui.theme.glass.LocalLiquidGlassConfig
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.FloatingTabBar
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuDivider
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.GlassToolbarButton
import com.jarves.mh.ui.kit.GlassToolbarGroup
import com.jarves.mh.ui.kit.GlassToolbarItem
import com.jarves.mh.ui.kit.LazyGroupRow
import com.jarves.mh.ui.kit.ListIconTile
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.OverlayAnchor
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketIconButton
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SectionHeader
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.SymbolTile
import com.jarves.mh.ui.kit.TabBarHeight
import com.jarves.mh.ui.kit.TabItem
import com.jarves.mh.ui.kit.largeTitleBarPadding
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberBanner
import com.jarves.mh.ui.kit.rememberHaptics
import com.jarves.mh.ui.kit.rememberOverlayAnchor
import com.jarves.mh.ui.theme.ContinuousRoundedShape
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketMotion.Token
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketTransitions
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.BackdropSourceScope
import com.jarves.mh.ui.theme.glass.GlassRoles
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassMaterial
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens
import com.jarves.mh.ui.theme.glass.LocalLiquidGlassBackdrop
import com.jarves.mh.ui.theme.glass.ScrollEdgeEffect
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import com.jarves.mh.ui.theme.heldWhileExiting
import com.jarves.mh.ui.theme.isTransitionTarget
import com.jarves.mh.ui.theme.medium
import com.jarves.mh.ui.theme.sharedTitle
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The project's four views, switched from the tab bar at the bottom or the menu on the title. */
internal enum class WorkspaceTab(val label: String, val icon: ImageVector) {
    CHAT("Chat", Icons.Outlined.ChatBubbleOutline),
    FILES("Files", Icons.Outlined.Folder),
    TERMINAL("Terminal", Icons.Outlined.Terminal),
    PREVIEW("Preview", Icons.Outlined.Preview),
    /** Shown only while a mod has a panel switched on for projects. */
    MODS("Mods", Icons.Outlined.Extension),
}

/** The composer never gets shorter than this, so it stays an easy target while typing. */
private val ComposerMinHeight = 52.dp

/**
 * The workspace's floating bar, one row: a glass back button, the title between the actions
 * with a second line under it, and glass actions on the right. Tapping the title ([onTitleClick])
 * opens the view menu. Content scrolls beneath the bar and softens into the canvas once anything
 * is underneath; the title's reading region has an opaque canvas backing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    scrolled: () -> Float,
    modifier: Modifier = Modifier,
    backLabel: String = "Projects",
    subtitleLead: String? = null,
    leadModifier: Modifier = Modifier,
    working: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    onTitleLongPress: (() -> Unit)? = null,
    titleAnchor: OverlayAnchor? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    run {
        val colors = PocketColors.current
        Box(modifier.fillMaxWidth()) {
            ScrollEdgeEffect(Modifier.matchParentSize(), fromTop = true, protectContent = true, strength = scrolled)
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = PocketSpacing.xs, bottom = PocketSpacing.sm),
            ) {
                BarRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = BarHeight)
                        .padding(horizontal = ListInset),
                    leading = { GlassToolbarButton(Icons.Outlined.ArrowBackIosNew, backLabel, onBack) },
                    center = {
                        // The title owns the space remaining between the actions.
                        Column(
                            Modifier
                                .heightIn(min = LiquidGlassTokens.MinTouchTarget)
                                .then(if (titleAnchor != null) Modifier.overlayAnchor(titleAnchor) else Modifier)
                                .then(
                                    if (onTitleClick != null || onTitleLongPress != null) {
                                        Modifier.combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClickLabel = "Change view",
                                            onLongClickLabel = "Show conversation details",
                                            onLongClick = onTitleLongPress,
                                            onClick = { onTitleClick?.invoke() },
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                title,
                                style = PocketType.headline,
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                            ) {
                                if (working) {
                                    ProgressRing(progress = null, size = 11.dp, strokeWidth = 1.5.dp, color = colors.secondaryLabel)
                                    Spacer(Modifier.width(PocketSpacing.xs + 2.dp))
                                }
                                if (subtitleLead != null) {
                                    Text(
                                        subtitleLead,
                                        style = PocketType.caption1,
                                        color = colors.secondaryLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false).then(leadModifier),
                                    )
                                } else {
                                    Text(
                                        subtitle,
                                        style = PocketType.caption1,
                                        color = colors.secondaryLabel,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.md),
                            content = actions,
                        )
                    },
                )
            }
        }
    }
}

/**
 * The bar's single row. Leading and trailing slots sit at the edges. The centre slot is always
 * centred between the action slots; it uses the actual available width and grows vertically
 * with system text size.
 */
@Composable
private fun BarRow(
    modifier: Modifier,
    leading: @Composable () -> Unit,
    center: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Layout(
        content = {
            Box { leading() }
            Box { center() }
            Box { trailing() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val leadingPlaceable = measurables[0].measure(loose)
        val trailingPlaceable = measurables[2].measure(loose)
        val gap = PocketSpacing.sm.roundToPx()
        val width = constraints.maxWidth
        val start = leadingPlaceable.width + gap
        val end = width - trailingPlaceable.width - gap
        val centerPlaceable = measurables[1].measure(loose.copy(maxWidth = (end - start).coerceAtLeast(0)))
        val height = maxOf(leadingPlaceable.height, centerPlaceable.height, trailingPlaceable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        val x = start + ((end - start - centerPlaceable.width) / 2).coerceAtLeast(0)
        layout(width, height) {
            leadingPlaceable.placeRelative(0, (height - leadingPlaceable.height) / 2)
            centerPlaceable.placeRelative(x, (height - centerPlaceable.height) / 2)
            trailingPlaceable.placeRelative(width - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
        }
    }
}

/**
 * The workspace's views as the same floating glass tab bar the main screens use, in the same place
 * at the bottom. It steps aside while the keyboard is up. [onFootprint] reports the distance from
 * the bar's top to the bottom of the screen, so the composer and the views clear it.
 */
@Composable
private fun WorkspaceTabBar(
    tabs: List<WorkspaceTab>,
    selected: WorkspaceTab,
    onSelect: (WorkspaceTab) -> Unit,
    onFootprint: (Dp) -> Unit,
    changeCount: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val ime = WindowInsets.ime
    // Only the visibility flip recomposes, not every frame of the keyboard animation.
    val keyboardVisible by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    AnimatedVisibility(
        visible = !keyboardVisible,
        modifier = modifier,
        enter = fadeIn(PocketMotion.spec(Token.Quick)),
        exit = fadeOut(PocketMotion.spec(Token.Quick)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.xs),
        ) {
            FloatingTabBar(
                tabs = tabs.map {
                    TabItem(it.label, it.icon, badge = if (it == WorkspaceTab.FILES) changeCount else 0,
                        badgeDescription = if (it == WorkspaceTab.FILES && changeCount > 0) "$changeCount files awaiting review" else null)
                },
                selectedIndex = tabs.indexOf(selected).coerceAtLeast(0),
                onSelect = { onSelect(tabs[it]) },
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val screen = coordinates.findRootCoordinates().size.height
                    onFootprint(with(density) { (screen - coordinates.positionInRoot().y).toDp() })
                },
            )
        }
    }
}

/** Measured height of a floating bar, so content can start below it and scroll under it. */
@Composable
private fun rememberBarHeight(): Pair<Dp?, (IntSize) -> Unit> {
    val density = LocalDensity.current
    var height by remember { mutableStateOf<Dp?>(null) }
    return height to { size: IntSize ->
        val measured = with(density) { size.height.toDp() }
        if (measured != height) height = measured
    }
}

@Composable
internal fun ReadOnlyProjectScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onContinueHere: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val project = state.readOnlyProject ?: return
    val activeChat = state.readOnlyProjectChats.firstOrNull { it.id == state.readOnlyChatId }
    val listState = rememberChatListState(state.readOnlyChatId, state.readOnlyMessages.size, project.id)
    var showChats by rememberSaveable { mutableStateOf(false) }
    val chatsAnchor = rememberOverlayAnchor()
    val (barHeight, onBarSize) = rememberBarHeight()

    ChatsSheet(
        visible = showChats,
        chats = state.readOnlyProjectChats,
        activeChatId = state.readOnlyChatId,
        switchingEnabled = true,
        allowCreate = false,
        anchor = chatsAnchor,
        onDismiss = { showChats = false },
        onCreate = {},
        onSwitch = { chatId ->
            onSwitchChat(chatId)
            showChats = false
        },
    )

    Box(Modifier.fillMaxSize()) {
        ChatTab(
            messages = state.readOnlyMessages,
            loading = state.chatLoading,
            approval = null,
            liveProcess = emptyList(),
            isRunning = false,
            onSend = {},
            onStop = {},
            onApproval = {},
            listState = listState,
            taskStartedAtMillis = null,
            taskFinishedAtMillis = null,
            thinkingActive = false,
            agentKind = state.agentKind,
            pendingAttachments = emptyList(),
            onAttach = {},
            onRemoveAttachment = {},
            onOpenAttachment = {},
            onRunInTerminal = {},
            readOnly = true,
            readOnlyBlocked = state.isRunning || state.projectTerminalRunning,
            onContinueHere = onContinueHere,
            topClearance = barHeight ?: (largeTitleBarPadding() + PocketSpacing.sm),
        )
        WorkspaceBar(
            title = activeChat?.title ?: "Chat",
            subtitle = "History",
            subtitleLead = project.name,
            onBack = onBack,
            scrolled = { if (listState.canScrollBackward) 1f else 0f },
            modifier = Modifier.onSizeChanged(onBarSize),
            actions = {
                GlassToolbarButton(
                    Icons.Outlined.Forum,
                    "Project chats",
                    { showChats = true },
                    Modifier.overlayAnchor(chatsAnchor),
                )
            },
        )
    }
}

@Composable
internal fun WorkspaceScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onSteerFollowUp: (String) -> Unit = {},
    onRemoveFollowUp: (String) -> Unit = {},
    onComposerDraftConsumed: () -> Unit = {},
    onApproval: (Boolean) -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onInstallApk: (WorkspaceEntry) -> Unit = {},
    onAndroidBuild: (AndroidBuildRequest) -> Unit = {},
    onCancelAndroidBuild: () -> Unit = {},
    onOpenAndroidBuildIssue: (AndroidBuildIssue) -> Unit = {},
    onCloseFile: () -> Unit,
    onBeginFileEdit: () -> Unit = {},
    onUpdateFileDraft: (String) -> Unit = {},
    onCancelFileEdit: () -> Unit = {},
    onSaveFile: () -> Unit = {},
    onReloadFile: () -> Unit = {},
    onDismissFileConflict: () -> Unit = {},
    onCreateWorkspaceEntry: (String, String, Boolean) -> Unit = { _, _, _ -> },
    onRenameWorkspaceEntry: (String, String) -> Unit = { _, _ -> },
    onDeleteWorkspaceEntry: (String) -> Unit = {},
    onUploadToWorkspace: (String, List<Uri>) -> Unit = { _, _ -> },
    onShareWorkspaceFile: (String) -> Unit = {},
    onSaveWorkspaceFileCopy: (String, Uri) -> Unit = { _, _ -> },
    onUndoChanges: () -> Unit,
    onKeepChanges: () -> Unit,
    onUndoFileChange: (String) -> Unit,
    onKeepFileChange: (String) -> Unit,
    onCreateChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
    onLoadOlderMessages: () -> Unit = {},
    onTerminalRun: (String) -> Unit,
    onTerminalInput: (String) -> Unit,
    onTerminalInterrupt: () -> Unit,
    onTerminalPrepare: (String) -> Unit,
    onTerminalDraftConsumed: () -> Unit,
    onTerminalOpened: () -> Unit,
    onTerminalStop: () -> Unit,
    onTerminalClear: () -> Unit,
    onTerminalConfirm: () -> Unit,
    onTerminalCancel: () -> Unit,
    onModConfirm: (Boolean) -> Unit = {},
    onUseSuggestedProjectRoot: () -> Unit,
    onExportProject: (Uri, ExportSelection) -> Unit,
    onAddAttachments: (List<Uri>) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onPromptChanged: (String) -> Unit = {},
    onOpenInspector: () -> Unit = {},
    onCloseInspector: () -> Unit = {},
    onTerminateSubagent: (String) -> Unit = {},
    onClearCompletedSubagents: () -> Unit = {},
    onTerminateTask: (String) -> Unit = {},
    onClearCompletedTasks: () -> Unit = {},
    onSelectSubagentForLogs: (com.jarves.mh.model.SubagentInfo?) -> Unit = {},
    onSelectTaskForLogs: (com.jarves.mh.model.BackgroundTaskInfo?) -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onCloseSkills: () -> Unit = {},
    onSetCustomizationScopeMode: (com.jarves.mh.model.CustomizationScopeMode) -> Unit = {},
    onToggleSkill: (String) -> Unit = {},
    onToggleRule: (String) -> Unit = {},
    onLinkSkill: (String, String, String) -> Unit = { _, _, _ -> },
    onUnlinkSkill: (String) -> Unit = {},
    onImportSkill: (java.io.File, String) -> Unit = { _, _ -> },
    onPromoteSkillToGlobal: (java.io.File, String) -> Unit = { _, _ -> },
    onPromoteRuleToGlobal: (java.io.File, String) -> Unit = { _, _ -> },
    onSaveProjectRule: (String, String) -> Unit = { _, _ -> },
    onCreateSkill: (String, String, String) -> Unit = { _, _, _ -> },
    onOpenModelPicker: () -> Unit = {},
    onCloseModelPicker: () -> Unit = {},
    onSelectModel: (String) -> Unit = {},
    onCloseEffortPicker: () -> Unit = {},
    onSelectEffort: (String) -> Unit = {},
    onOpenMemoryViewer: () -> Unit = {},
    onCloseMemoryViewer: () -> Unit = {},
    onAddMemoryEntry: (String, String) -> Unit = { _, _ -> },
    onDeleteMemoryEntry: (String) -> Unit = {},
    onClearAutoMemory: () -> Unit = {},
    onClearAllMemory: () -> Unit = {},
    initialTab: WorkspaceTab = WorkspaceTab.CHAT,
) {
    BackHandler(onBack = onBack)
    val banner = rememberBanner()
    // Selection mode and the chosen files survive process death while the save picker is open,
    // so the export that follows writes what was picked.
    val projectKey = state.activeProject?.id
    var exportSelectionMode by rememberSaveable(projectKey) { mutableStateOf(false) }
    var exportSelection by rememberSaveable(projectKey, stateSaver = ExportSelectionSaver) { mutableStateOf(ExportSelection()) }
    // A finished export ends selection mode; one that finished before this screen appeared does not.
    var seenExport by rememberSaveable { mutableStateOf(state.projectExportSucceededAtMillis) }
    LaunchedEffect(state.projectExportSucceededAtMillis) {
        if (state.projectExportSucceededAtMillis != seenExport) {
            seenExport = state.projectExportSucceededAtMillis
            exportSelectionMode = false
            exportSelection = ExportSelection()
        }
    }
    val exportProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> if (uri != null) onExportProject(uri, exportSelection) },
    )
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = onAddAttachments,
    )
    // The folder an upload goes into and the file a copy is made of, saved across process death
    // while the system picker is open.
    var pendingUploadFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris ->
            pendingUploadFolder?.let { folder -> if (uris.isNotEmpty()) onUploadToWorkspace(folder, uris) }
            pendingUploadFolder = null
        },
    )
    var pendingCopyPath by rememberSaveable { mutableStateOf<String?>(null) }
    val saveCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
        onResult = { uri ->
            val path = pendingCopyPath
            if (uri != null && path != null) onSaveWorkspaceFileCopy(path, uri)
            pendingCopyPath = null
        },
    )
    val context = androidx.compose.ui.platform.LocalContext.current
    // Saved so the capture survives Android killing the app while the camera app is open.
    var pendingPhotoUri by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { saved ->
            val uri = pendingPhotoUri?.let(Uri::parse)
            pendingPhotoUri = null
            if (saved && uri != null) onAddAttachments(listOf(uri))
        },
    )
    val chatItemCount = state.messages.size +
        (if (state.liveProcess.isNotEmpty() || state.liveThinking) 1 else 0) +
        (if (state.pendingApproval != null) 1 else 0)
    // Keyed by project and chat, so a chat opens on its newest message rather than on the first one.
    val chatListState = rememberChatListState(state.activeChatId, chatItemCount, state.activeProject?.id)
    val filesListState = rememberLazyListState()
    var userScrolledUp by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.activeChatId) {
        userScrolledUp = false
        if (chatItemCount > 0) chatListState.scrollToItem(chatItemCount - 1)
    }

    // When the user actively scrolls/touches the screen, detect if they scrolled up to read thinking/messages.
    LaunchedEffect(chatListState, chatListState.isScrollInProgress) {
        if (chatListState.isScrollInProgress) {
            if (chatListState.canScrollForward) {
                userScrolledUp = true
            }
        } else {
            // If user scrolled back down to the very bottom, re-enable follow mode
            if (!chatListState.canScrollForward) {
                userScrolledUp = false
            }
        }
    }

    // Follow new tokens/updates only when user is at the bottom and has not scrolled up to read.
    LaunchedEffect(
        chatListState,
        state.messages.size,
        state.messages.lastOrNull()?.text?.length,
        state.liveProcess.size,
        state.liveProcess.lastOrNull()?.detail,
        state.pendingApproval,
    ) {
        if (!state.isRunning || chatItemCount <= 0 || userScrolledUp || chatListState.isScrollInProgress) return@LaunchedEffect
        if (!chatListState.canScrollForward) {
            chatListState.scrollToItem(chatItemCount - 1)
        }
    }

    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    val modPanes = rememberModPanesAvailable()
    val tabs = WorkspaceTab.entries.filter { it != WorkspaceTab.MODS || modPanes }
    if (selectedTab !in tabs) selectedTab = WorkspaceTab.CHAT
    var showChats by rememberSaveable { mutableStateOf(false) }
    var showViews by rememberSaveable { mutableStateOf(false) }
    val viewsAnchor = rememberOverlayAnchor()
    var showChanges by rememberSaveable { mutableStateOf(false) }
    // Changes wait for review in Files; a running task keeps them out of reach until it finishes.
    val reviewable = if (state.isRunning) emptyList() else state.changes
    val chatsAnchor = rememberOverlayAnchor()
    val (barHeight, onBarSize) = rememberBarHeight()
    // Distance from the top of the bottom tab bar to the screen's bottom edge, once measured.
    var tabBarFootprint by remember { mutableStateOf<Dp?>(null) }
    val selectView = { tab: WorkspaceTab ->
        selectedTab = tab
        if (tab == WorkspaceTab.FILES) onRefreshFiles()
        if (tab == WorkspaceTab.TERMINAL) onTerminalOpened()
    }
    ChangesReviewSheet(
        visible = showChanges && state.changes.isNotEmpty(),
        changes = state.changes,
        anchor = null,
        onDismiss = { showChanges = false },
        onUndoAll = onUndoChanges,
        onKeepAll = onKeepChanges,
        onUndoFile = onUndoFileChange,
        onKeepFile = onKeepFileChange,
    )
    ChatsSheet(
        visible = showChats,
        chats = state.projectChats,
        activeChatId = state.activeChatId,
        switchingEnabled = !state.isRunning,
        anchor = chatsAnchor,
        onDismiss = { showChats = false },
        onCreate = {
            onCreateChat()
            showChats = false
            selectedTab = WorkspaceTab.CHAT
        },
        onSwitch = { chatId ->
            onSwitchChat(chatId)
            showChats = false
            selectedTab = WorkspaceTab.CHAT
        },
    )
    val activeChat = state.projectChats.firstOrNull { it.id == state.activeChatId }

    state.pendingTerminalCommand?.let { command ->
        PocketAlert(
            onDismiss = onTerminalCancel,
            title = "Run this command?",
            message = state.pendingTerminalReason ?: "It can delete files, rewrite Git history, or change the project significantly.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel, onClick = onTerminalCancel),
                AlertAction("Run anyway", AlertRole.Destructive, onClick = onTerminalConfirm),
            ),
        ) {
            Text(
                command,
                style = PocketType.codeSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PocketShape.sm)
                    .background(PocketColors.current.codeSurface)
                    .padding(PocketSpacing.md),
            )
        }
    }
    state.pendingModConfirm?.let { request ->
        PocketAlert(
            onDismiss = { onModConfirm(false) },
            title = request.title,
            message = "${request.modName}: ${request.reason}",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel, onClick = { onModConfirm(false) }),
                AlertAction("Continue", AlertRole.Default, onClick = { onModConfirm(true) }),
            ),
        )
    }
    if (state.auxiliaryInspectorVisible) {
        AuxiliaryInspectorSheet(
            subagents = state.subagents,
            tasks = state.backgroundTasks,
            artifacts = state.artifacts,
            timers = state.scheduledTimers,
            onDismiss = onCloseInspector,
            onTerminateSubagent = onTerminateSubagent,
            onClearCompletedSubagents = onClearCompletedSubagents,
            onTerminateTask = onTerminateTask,
            onClearCompletedTasks = onClearCompletedTasks,
            onSelectSubagentForLogs = onSelectSubagentForLogs,
            onSelectTaskForLogs = onSelectTaskForLogs,
            onOpenMemoryViewer = onOpenMemoryViewer,
            tokenMetrics = state.tokenMetrics,
        )
    }
    state.selectedSubagentForLogs?.let { selected ->
        val subagent = state.subagents.firstOrNull { it.conversationId == selected.conversationId } ?: selected
        SubagentTranscriptViewerDialog(
            subagent = subagent,
            onDismiss = { onSelectSubagentForLogs(null) },
            onTerminate = onTerminateSubagent,
            project = state.activeProject,
            projectSlug = state.activeProject?.slug,
            engineHome = when (state.agentKind) {
                com.jarves.mh.model.AgentKind.CLAUDE_CODE -> "/root/.claude"
                com.jarves.mh.model.AgentKind.DEEPSEEK_HARNESS -> "/root/.dsh"
                com.jarves.mh.model.AgentKind.CODEX -> "/root/.codex"
                com.jarves.mh.model.AgentKind.ANTIGRAVITY -> "/root/.gemini"
            },
        )
    }
    state.selectedTaskForLogs?.let { selected ->
        val task = state.backgroundTasks.firstOrNull { it.taskId == selected.taskId } ?: selected
        TaskLogViewerDialog(
            task = task,
            onDismiss = { onSelectTaskForLogs(null) },
            onTerminate = onTerminateTask,
        )
    }
    if (state.skillsManagerVisible) {
        SkillsManagerDialog(
            config = state.activeCustomizationConfig,
            activeSkills = state.activeSkills,
            otherProjectsSkills = state.otherProjectsSkills,
            globalSkills = state.globalSkills,
            activeRules = state.activeRules,
            projectRules = state.projectRulesList,
            globalRules = state.globalRulesList,
            onSetScopeMode = onSetCustomizationScopeMode,
            onToggleSkill = onToggleSkill,
            onToggleRule = onToggleRule,
            onLinkSkill = onLinkSkill,
            onUnlinkSkill = onUnlinkSkill,
            onImportSkill = onImportSkill,
            onPromoteSkill = onPromoteSkillToGlobal,
            onPromoteRule = onPromoteRuleToGlobal,
            onSaveRule = onSaveProjectRule,
            onCreateSkill = onCreateSkill,
            onDismiss = onCloseSkills,
        )
    }
    if (state.modelPickerVisible) {
        val slot = modelSlotFor(state.agentKind, state.provider.kind)
        ModelPickerDialog(
            currentModel = when (slot) {
                ModelSlot.ANTIGRAVITY -> state.antigravityModel.ifBlank { state.provider.model }
                ModelSlot.CLAUDE_SUBSCRIPTION -> state.claudeModel
                ModelSlot.PROVIDER -> state.provider.model
            },
            availableModels = when (slot) {
                ModelSlot.ANTIGRAVITY -> state.antigravityModels.ifEmpty { ANTIGRAVITY_FALLBACK_MODELS }
                ModelSlot.CLAUDE_SUBSCRIPTION -> CLAUDE_SUBSCRIPTION_MODELS.map { it.id }
                ModelSlot.PROVIDER -> state.providerModels
                    .ifEmpty { defaultModelsForProvider(state.provider.kind) }
                    .map { it.id }
            },
            provider = if (slot == ModelSlot.CLAUDE_SUBSCRIPTION) ProviderKind.CLAUDE else null,
            onSelectModel = onSelectModel,
            onDismiss = onCloseModelPicker,
            defaultTitle = if (slot == ModelSlot.PROVIDER && state.provider.kind == ProviderKind.CHATGPT) "Codex default" else null,
            defaultSubtitle = "Let Codex choose the model for your plan. Discover models in Settings to list more.",
        )
    }
    if (state.effortPickerVisible) {
        val codexLevels = codexEffortChoices(state.providerModels, state.provider.model)
        EffortPickerDialog(
            options = effortLevelsFor(state.agentKind, codexLevels).orEmpty().map { EffortOption(it, effortLabel(state.agentKind, it)) },
            current = when (state.agentKind) {
                AgentKind.CLAUDE_CODE -> state.claudeThinkingLevel
                AgentKind.ANTIGRAVITY -> state.antigravityEffort
                AgentKind.CODEX -> state.codexReasoningEffort.ifBlank { CODEX_DEFAULT_EFFORT_ARG }
                AgentKind.DEEPSEEK_HARNESS -> state.dshReasoningEffort
            },
            notice = if (state.agentKind == AgentKind.DEEPSEEK_HARNESS) {
                "The provider validates this effort when the next run starts. Default keeps its own setting."
            } else if (state.agentKind == AgentKind.CODEX && codexLevels.isEmpty()) {
                "Discover models in Settings to see the levels this model supports."
            } else {
                null
            },
            onSelect = onSelectEffort,
            onDismiss = onCloseEffortPicker,
        )
    }
    if (state.memoryViewerVisible) {
        MemoryViewerSheet(
            memory = state.contextMemory,
            onDismiss = onCloseMemoryViewer,
            onAddEntry = onAddMemoryEntry,
            onDeleteEntry = onDeleteMemoryEntry,
            onClearAuto = onClearAutoMemory,
            onClearAll = onClearAllMemory,
        )
    }

    val engine = if (state.agentKind == AgentKind.ANTIGRAVITY) state.agentKind.title else state.provider.kind.title

    // An open file is pushed over the workspace and popped back to Files.
    AnimatedContent(
        targetState = state.openedFilePath,
        transitionSpec = { PocketTransitions.push(forward = targetState != null) },
        contentKey = { it != null },
        label = "file viewer",
    ) { openedPath ->
        BackdropSourceScope(active = isTransitionTarget) {
            if (openedPath != null) {
                val held = heldWhileExiting(state)
                val closeFile = {
                    onCloseFile()
                    selectedTab = WorkspaceTab.FILES
                }
                BackHandler(enabled = isTransitionTarget, onBack = closeFile)
                FileViewerScreen(
                    filePath = openedPath,
                    content = held.openedFileContent,
                    loading = held.fileContentLoading,
                    onClose = closeFile,
                    readOnlyReason = held.openedFileReadOnlyReason,
                    edit = held.openedFileEdit,
                    editingAllowed = !workspaceFilesBusy(state),
                    onBeginEdit = onBeginFileEdit,
                    onDraftChange = onUpdateFileDraft,
                    onCancelEdit = onCancelFileEdit,
                    onSave = onSaveFile,
                    onReload = onReloadFile,
                    onDismissConflict = onDismissFileConflict,
                )
            } else {
                Box(Modifier.fillMaxSize()) {
                    val top = barHeight ?: (largeTitleBarPadding() + PocketSpacing.sm)
                    val bottomBar = tabBarFootprint
                        ?: (WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + TabBarHeight + PocketSpacing.sm)
                    // Views end above the tab bar, or above the keyboard while it is up (the bar hides).
                    val bottomInsets = WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets(bottom = bottomBar))
                    val underBar = if (selectedTab == WorkspaceTab.CHAT) chatListState else if (selectedTab == WorkspaceTab.FILES) filesListState else null
                    // Chat and Files scroll under the floating bar; Terminal and Preview start below it.
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = { PocketTransitions.crossFade() },
                        label = "workspace tab",
                    ) { tab ->
                        BackdropSourceScope(active = isTransitionTarget) {
                            when (tab) {
                                WorkspaceTab.CHAT -> ChatTab(
                                    state.messages,
                                    state.pendingApproval,
                                    state.liveProcess,
                                    state.isRunning,
                                    onSend,
                                    onStop,
                                    onApproval,
                                    listState = chatListState,
                                    taskStartedAtMillis = state.workSegmentStartedAtMillis ?: state.taskStartedAtMillis,
                                    taskFinishedAtMillis = state.taskFinishedAtMillis,
                                    thinkingActive = state.liveThinking,
                                    agentKind = state.agentKind,
                                    pendingAttachments = state.pendingAttachments,
                                    onAttach = {
                                        attachmentLauncher.launch(arrayOf("*/*"))
                                    },
                                    onTakePhoto = {
                                        runCatching {
                                            val uri = createCameraPhotoUri(context)
                                            pendingPhotoUri = uri.toString()
                                            takePhotoLauncher.launch(uri)
                                        }.onFailure {
                                            pendingPhotoUri = null
                                            banner("No camera app is available", BannerKind.Error)
                                        }
                                    },
                                    onRemoveAttachment = onRemoveAttachment,
                                    onOpenAttachment = onOpenAttachment,
                                    onRunInTerminal = { command ->
                                        selectedTab = WorkspaceTab.TERMINAL
                                        onTerminalOpened()
                                        onTerminalPrepare(command)
                                    },
                                    slashCommands = state.filteredSlashCommands,
                                    slashCommandsVisible = state.slashCommandsVisible,
                                    skills = state.filteredSkills,
                                    onPromptChanged = onPromptChanged,
                                    onOpenInspector = onOpenInspector,
                                    onOpenMemoryViewer = onOpenMemoryViewer,
                                    liveWorkStatus = if (state.isStopping) WorkStatus.STOPPING else state.liveWorkStatus,
                                    onOpenSkills = onOpenSkills,
                                    subagentsCount = state.subagents.count { it.state == SubagentState.RUNNING },
                                    tasksCount = state.backgroundTasks.count { it.status == BackgroundTaskStatus.RUNNING },
                                    mentionMenuVisible = state.mentionMenuVisible,
                                    mentionFiles = state.filteredMentionEntries,
                                    isStopping = state.isStopping,
                                    activeChatId = state.activeChatId,
                                    topClearance = top,
                                    bottomBarClearance = bottomBar,
                                    loading = state.chatLoading,
                                    hasOlderMessages = state.olderMessageCount > 0,
                                    onLoadOlderMessages = onLoadOlderMessages,
                                    queuedFollowUps = state.queuedFollowUps,
                                    onSteerFollowUp = onSteerFollowUp,
                                    onRemoveFollowUp = onRemoveFollowUp,
                                    composerDraft = state.composerDraft,
                                    onComposerDraftConsumed = onComposerDraftConsumed,
                                )
                                WorkspaceTab.FILES -> FilesTab(
                                    files = state.workspaceFiles,
                                    loading = state.filesLoading,
                                    suggestedProjectRoot = state.suggestedProjectRoot,
                                    onRefresh = onRefreshFiles,
                                    onOpenFile = onOpenFile,
                                    onInstallApk = onInstallApk,
                                    androidBuild = if (state.androidProjectDetected || state.androidBuildRunning) {
                                        AndroidBuildUi(
                                            running = state.androidBuildRunning,
                                            cancelling = state.androidBuildCancelling,
                                            message = state.androidBuildMessage,
                                            failed = state.androidBuildFailed,
                                            startedAtMillis = state.androidBuildStartedAtMillis,
                                            typicalMillis = typicalAndroidBuildMillis(state.androidBuildRecentDurationsMillis),
                                            issues = state.androidBuildIssues,
                                        )
                                    } else null,
                                    onAndroidBuild = onAndroidBuild,
                                    onCancelAndroidBuild = onCancelAndroidBuild,
                                    onOpenAndroidBuildIssue = onOpenAndroidBuildIssue,
                                    onUseSuggestedProjectRoot = onUseSuggestedProjectRoot,
                                    selectionMode = exportSelectionMode,
                                    exportSelection = exportSelection,
                                    exportRunning = state.projectExportRunning,
                                    onEnterSelection = {
                                        exportSelection = ExportSelection()
                                        exportSelectionMode = true
                                    },
                                    onCancelSelection = {
                                        exportSelectionMode = false
                                        exportSelection = ExportSelection()
                                    },
                                    onSelectionChange = { exportSelection = it },
                                    onExport = {
                                        runCatching {
                                            exportProjectLauncher.launch("${state.activeProject?.slug ?: "project"}.zip")
                                        }.onFailure { banner("No app can save the ZIP", BannerKind.Error) }
                                    },
                                    listState = filesListState,
                                    topClearance = top,
                                    bottomClearance = bottomBar,
                                    changes = reviewable,
                                    onReviewChanges = { showChanges = true },
                                    onUndoChanges = onUndoChanges,
                                    onKeepChanges = onKeepChanges,
                                    fileOpsEnabled = !workspaceFilesBusy(state),
                                    onCreateEntry = onCreateWorkspaceEntry,
                                    onRenameEntry = onRenameWorkspaceEntry,
                                    onDeleteEntry = onDeleteWorkspaceEntry,
                                    onUploadInto = { folder ->
                                        pendingUploadFolder = folder
                                        runCatching { uploadLauncher.launch(arrayOf("*/*")) }.onFailure {
                                            pendingUploadFolder = null
                                            banner("No app can pick files to upload", BannerKind.Error)
                                        }
                                    },
                                    onShareFile = onShareWorkspaceFile,
                                    onSaveFileCopy = { path ->
                                        pendingCopyPath = path
                                        runCatching { saveCopyLauncher.launch(path.substringAfterLast('/')) }.onFailure {
                                            pendingCopyPath = null
                                            banner("No app can save a copy", BannerKind.Error)
                                        }
                                    },
                                )
                                WorkspaceTab.TERMINAL -> Box(
                                    Modifier
                                        .fillMaxSize()
                                        .hostBackdropSource()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(top = top)
                                        .windowInsetsPadding(bottomInsets)
                                        .clipToBounds(),
                                ) {
                                    TerminalScreen(
                                        lines = state.projectTerminalLines,
                                        isRunning = state.projectTerminalRunning,
                                        onRun = onTerminalRun,
                                        onInput = onTerminalInput,
                                        onInterrupt = onTerminalInterrupt,
                                        onClear = onTerminalClear,
                                        onToggleTheme = {},
                                        themeMode = state.themeMode,
                                        title = "Project Terminal",
                                        subtitle = "${state.projectTerminalCwd} · Ubuntu PRoot",
                                        liveOutput = state.projectTerminalLiveOutput,
                                        currentCommand = state.projectTerminalCommand,
                                        commandDraft = state.projectTerminalDraft,
                                        onCommandDraftConsumed = onTerminalDraftConsumed,
                                        promptPath = state.projectTerminalCwd,
                                        onStop = onTerminalStop,
                                        showThemeAction = false,
                                        showQuickCommands = false,
                                        compactHeader = true,
                                    )
                                }
                                WorkspaceTab.PREVIEW -> Box(
                                    Modifier
                                        .fillMaxSize()
                                        .hostBackdropSource()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(top = top)
                                        .windowInsetsPadding(bottomInsets)
                                        .clipToBounds(),
                                ) {
                                    key(state.activeProject?.id, state.activeProject?.rootPath) {
                                        PreviewTab(state.previewReady, state.previewUrl, state.activeProject)
                                    }
                                }
                                WorkspaceTab.MODS -> Box(
                                    Modifier
                                        .fillMaxSize()
                                        .hostBackdropSource()
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(top = top)
                                        .windowInsetsPadding(bottomInsets)
                                        .clipToBounds(),
                                ) {
                                    ModWorkspacePanes()
                                }
                            }
                        }
                    }
                    WorkspaceBar(
                        title = activeChat?.title ?: "Chat",
                        subtitle = engine,
                        subtitleLead = state.activeProject?.name.orEmpty(),
                        leadModifier = Modifier.sharedTitle(projectTitleKey(state.activeProject?.id)),
                        onBack = onBack,
                        scrolled = { if (underBar?.canScrollBackward == true) 1f else 0f },
                        modifier = Modifier.onSizeChanged(onBarSize),
                        working = state.isRunning,
                        titleAnchor = viewsAnchor,
                        onTitleClick = { showViews = true },
                        onTitleLongPress = { banner("${activeChat?.title ?: "Chat"}\n${state.activeProject?.name.orEmpty()} · $engine", BannerKind.Info) },
                        actions = {
                            GlassToolbarGroup {
                                GlassToolbarItem(
                                    Icons.Outlined.Forum,
                                    "Project chats",
                                    { showChats = true },
                                    Modifier.overlayAnchor(chatsAnchor),
                                )
                            }
                        },
                    )
                    WorkspaceTabBar(
                        tabs = tabs,
                        selected = selectedTab,
                        onSelect = selectView,
                        onFootprint = { if (it != tabBarFootprint) tabBarFootprint = it },
                        changeCount = reviewable.size,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    GlassMenu(expanded = showViews, onDismiss = { showViews = false }, anchor = viewsAnchor) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(activeChat?.title ?: "Chat", Modifier.padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.sm), style = PocketType.headline)
                            Text("${state.activeProject?.name.orEmpty()} · $engine", Modifier.padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.sm), style = PocketType.footnote, color = PocketColors.current.secondaryLabel)
                            GlassMenuDivider()
                            tabs.forEach { tab ->
                                GlassMenuItem(tab.label, { selectView(tab) }, checked = tab == selectedTab)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The project's chats in a sheet: start a new one, or switch to a saved one. */
@Composable
private fun ChatsSheet(
    visible: Boolean,
    chats: List<ProjectChat>,
    activeChatId: String?,
    switchingEnabled: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onSwitch: (String) -> Unit,
    allowCreate: Boolean = true,
    anchor: OverlayAnchor? = null,
) {
    val colors = PocketColors.current
    val accent = MaterialTheme.colorScheme.primary
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Chats",
        anchor = anchor,
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
            if (allowCreate) {
                item(key = "new-chat") {
                    ListSection(footer = if (!switchingEnabled) "Finish the running task before switching chats." else null) {
                        ListRow(
                            "New chat",
                            icon = Icons.Outlined.Add,
                            iconTile = accent,
                            titleColor = accent,
                            enabled = switchingEnabled,
                            onClick = onCreate,
                        )
                    }
                }
            }
            item(key = "saved-header") {
                SectionHeader("Saved chats", Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.md))
            }
            itemsIndexed(chats, key = { _, chat -> chat.id }) { index, chat ->
                val current = chat.id == activeChatId
                LazyGroupRow(
                    isFirst = index == 0,
                    isLast = index == chats.lastIndex,
                    separatorStart = ListInset + ListIconTile + 14.dp,
                ) {
                    ListRow(
                        chat.title,
                        subtitle = if (current) "Current chat" else null,
                        icon = Icons.Outlined.ChatBubbleOutline,
                        iconTile = if (current) accent else colors.gray,
                        accessory = if (current) ListRowAccessory.Check else ListRowAccessory.None,
                        enabled = switchingEnabled,
                        onClick = { onSwitch(chat.id) },
                    )
                }
            }
        }
    }
}

/**
 * A file pushed over the workspace: Markdown rendered, anything else as numbered code lines. Text
 * files that can be edited switch to a monospaced editor; leaving it with unsaved changes asks first.
 */
@Composable
private fun FileViewerScreen(
    filePath: String,
    content: String?,
    loading: Boolean,
    onClose: () -> Unit,
    readOnlyReason: FileReadOnlyReason? = null,
    edit: FileEditState? = null,
    editingAllowed: Boolean = true,
    onBeginEdit: () -> Unit = {},
    onDraftChange: (String) -> Unit = {},
    onCancelEdit: () -> Unit = {},
    onSave: () -> Unit = {},
    onReload: () -> Unit = {},
    onDismissConflict: () -> Unit = {},
) {
    val fileName = filePath.substringAfterLast('/')
    val isMarkdown = fileName.substringAfterLast('.', "") == "md"
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    val colors = PocketColors.current
    val listState = rememberLazyListState()
    val (barHeight, onBarSize) = rememberBarHeight()
    val top = (barHeight ?: (largeTitleBarPadding() + PocketSpacing.sm)) + PocketSpacing.sm
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + PocketSpacing.xxl
    val editing = edit != null
    val showsCode = !loading && content != null && (!isMarkdown || editing)
    val editorScroll = rememberScrollState()

    // The editor keeps its own cursor and history; the draft text goes to the ViewModel.
    var editorValue by remember(filePath, editing) { mutableStateOf(TextFieldValue(edit?.draft.orEmpty())) }
    var undoHistory by remember(filePath, editing) { mutableStateOf(emptyList<TextFieldValue>()) }
    var redoHistory by remember(filePath, editing) { mutableStateOf(emptyList<TextFieldValue>()) }
    var confirmLeave by remember { mutableStateOf(false) }
    fun applyEditorValue(next: TextFieldValue) {
        editorValue = next
        onDraftChange(next.text)
    }
    fun leaveEditor() {
        if (edit?.dirty == true) confirmLeave = true else onCancelEdit()
    }
    BackHandler(enabled = editing) { leaveEditor() }

    if (confirmLeave && edit != null) {
        PocketAlert(
            onDismiss = { confirmLeave = false },
            title = "Unsaved changes",
            message = "Save your changes to $fileName before leaving the editor?",
            actions = listOf(
                AlertAction("Save", enabled = editingAllowed && !edit.saving) {
                    confirmLeave = false
                    onSave()
                },
                AlertAction("Discard changes", AlertRole.Destructive) {
                    confirmLeave = false
                    onCancelEdit()
                },
                AlertAction("Keep editing", AlertRole.Cancel) { confirmLeave = false },
            ),
        )
    }
    if (edit?.conflict == true) {
        PocketAlert(
            onDismiss = onDismissConflict,
            title = "File changed",
            message = "$fileName changed outside the editor since you opened it. Reload it to see the newer version; your edits will be lost.",
            actions = listOf(
                AlertAction("Keep editing", AlertRole.Cancel, onClick = onDismissConflict),
                AlertAction("Reload", AlertRole.Destructive, onClick = onReload),
            ),
        )
    }

    Box(Modifier.fillMaxSize().background(if (showsCode) colors.codeSurface else MaterialTheme.colorScheme.background)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ProgressRing(progress = null)
            }
            content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.Description,
                    "No content",
                    message = when (readOnlyReason) {
                        FileReadOnlyReason.BINARY -> "Binary files can't be shown or edited."
                        FileReadOnlyReason.UNSAFE -> "This file is protected or outside the project."
                        else -> "The file could not be read."
                    },
                )
            }
            editing -> {
                val lineCount = remember(editorValue.text) { editorValue.text.count { it == '\n' } + 1 }
                val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
                val horizontal = rememberScrollState()
                Row(
                    Modifier
                        .fillMaxSize()
                        .hostBackdropSource()
                        .imePadding()
                        .verticalScroll(editorScroll)
                        .padding(top = top, bottom = bottom),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        lineNumbers,
                        modifier = Modifier.width(42.dp).padding(start = PocketSpacing.sm, end = 6.dp),
                        style = PocketType.code,
                        color = colors.tertiaryLabel,
                        textAlign = TextAlign.End,
                    )
                    Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                        BasicTextField(
                            value = editorValue,
                            onValueChange = { next ->
                                if (next.text != editorValue.text) {
                                    undoHistory = boundedEditHistory(undoHistory + editorValue)
                                    redoHistory = emptyList()
                                }
                                applyEditorValue(next)
                            },
                            enabled = !edit.saving,
                            textStyle = PocketType.code.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                            modifier = Modifier
                                .widthIn(min = 600.dp)
                                .padding(end = PocketSpacing.md)
                                .semantics { contentDescription = "Contents of $fileName" },
                        )
                    }
                }
            }
            isMarkdown -> LazyColumn(
                modifier = Modifier.fillMaxSize().hostBackdropSource(),
                state = listState,
                contentPadding = PaddingValues(start = PocketSpacing.lg, top = top, end = PocketSpacing.lg, bottom = bottom),
            ) {
                readOnlyNote(readOnlyReason)
                item { MarkdownText(markdown = content, color = MaterialTheme.colorScheme.onSurface) }
            }
            else -> {
                val lines = remember(content) { content.lines() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().hostBackdropSource(),
                    state = listState,
                    contentPadding = PaddingValues(top = top, bottom = bottom),
                ) {
                    readOnlyNote(readOnlyReason, Modifier.padding(horizontal = PocketSpacing.lg))
                    items(lines.size) { idx ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${idx + 1}",
                                modifier = Modifier.width(42.dp).padding(start = PocketSpacing.sm, end = 6.dp),
                                style = PocketType.codeSmall,
                                color = colors.tertiaryLabel,
                                textAlign = TextAlign.End,
                            )
                            Text(
                                text = lines[idx],
                                modifier = Modifier.weight(1f).padding(end = PocketSpacing.md),
                                style = PocketType.code,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        WorkspaceBar(
            title = fileName,
            subtitle = filePath,
            onBack = { if (editing) leaveEditor() else onClose() },
            backLabel = if (editing) "Stop editing" else "Close file",
            scrolled = {
                val scrolled = if (editing) editorScroll.value > 0 else listState.canScrollBackward
                if (scrolled) 1f else 0f
            },
            modifier = Modifier.onSizeChanged(onBarSize),
            actions = {
                if (edit != null) {
                    GlassToolbarGroup {
                        GlassToolbarItem(
                            Icons.AutoMirrored.Outlined.Undo,
                            "Undo",
                            {
                                undoHistory.lastOrNull()?.let { previous ->
                                    undoHistory = undoHistory.dropLast(1)
                                    redoHistory = boundedEditHistory(redoHistory + editorValue)
                                    applyEditorValue(previous)
                                }
                            },
                            enabled = undoHistory.isNotEmpty() && !edit.saving,
                        )
                        GlassToolbarItem(
                            Icons.AutoMirrored.Outlined.Redo,
                            "Redo",
                            {
                                redoHistory.lastOrNull()?.let { next ->
                                    redoHistory = redoHistory.dropLast(1)
                                    undoHistory = boundedEditHistory(undoHistory + editorValue)
                                    applyEditorValue(next)
                                }
                            },
                            enabled = redoHistory.isNotEmpty() && !edit.saving,
                        )
                    }
                    if (edit.saving) {
                        Box(Modifier.size(LiquidGlassTokens.MinTouchTarget), contentAlignment = Alignment.Center) {
                            ProgressRing(progress = null, size = 20.dp, strokeWidth = 2.dp)
                        }
                    } else {
                        GlassToolbarButton(
                            Icons.Outlined.Check,
                            "Save file",
                            onSave,
                            enabled = edit.dirty && editingAllowed,
                            prominent = edit.dirty && editingAllowed,
                        )
                    }
                } else {
                    if (!content.isNullOrEmpty()) {
                        GlassToolbarButton(
                            Icons.Outlined.ContentCopy,
                            "Copy file contents",
                            {
                                clipboard.setText(AnnotatedString(content))
                                banner("Copied $fileName", BannerKind.Success)
                            },
                        )
                    }
                    if (!loading && content != null && readOnlyReason == null) {
                        GlassToolbarButton(
                            Icons.Outlined.Edit,
                            "Edit file",
                            onBeginEdit,
                            enabled = editingAllowed,
                        )
                    }
                }
            },
        )
    }
}

/** A footnote above a file that is shown but can't be edited, saying why. */
private fun LazyListScope.readOnlyNote(reason: FileReadOnlyReason?, modifier: Modifier = Modifier) {
    val text = when (reason) {
        FileReadOnlyReason.TOO_LARGE -> "Files larger than 512 KB are shown in part and can't be edited."
        FileReadOnlyReason.INVALID_UTF8 -> "This file isn't valid UTF-8 text, so it can't be edited."
        else -> return
    }
    item(key = "read-only-note") {
        Text(
            text,
            modifier = modifier.padding(bottom = PocketSpacing.sm),
            style = PocketType.footnote,
            color = PocketColors.current.secondaryLabel,
        )
    }
}

/** Undo steps kept by the editor: the newest ones, up to about two million characters in all. */
private fun boundedEditHistory(history: List<TextFieldValue>): List<TextFieldValue> {
    var total = 0L
    val kept = history.asReversed().takeWhile { value ->
        total += value.text.length
        total <= 2_000_000L
    }
    return kept.asReversed().takeLast(100)
}

/** The project tree as a grouped list, with built APKs and a detected project folder above it. */
@Composable
private fun FilesTab(
    files: List<WorkspaceEntry>,
    loading: Boolean,
    suggestedProjectRoot: String?,
    onRefresh: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onInstallApk: (WorkspaceEntry) -> Unit = {},
    androidBuild: AndroidBuildUi? = null,
    onAndroidBuild: (AndroidBuildRequest) -> Unit = {},
    onCancelAndroidBuild: () -> Unit = {},
    onOpenAndroidBuildIssue: (AndroidBuildIssue) -> Unit = {},
    onUseSuggestedProjectRoot: () -> Unit,
    onExport: () -> Unit,
    listState: LazyListState,
    topClearance: Dp,
    bottomClearance: Dp,
    changes: List<ChangeItem>,
    onReviewChanges: () -> Unit,
    onUndoChanges: () -> Unit,
    onKeepChanges: () -> Unit,
    fileOpsEnabled: Boolean = true,
    onCreateEntry: (parentPath: String, name: String, isDirectory: Boolean) -> Unit = { _, _, _ -> },
    onRenameEntry: (path: String, newName: String) -> Unit = { _, _ -> },
    onDeleteEntry: (path: String) -> Unit = {},
    onUploadInto: (parentPath: String) -> Unit = {},
    onShareFile: (path: String) -> Unit = {},
    onSaveFileCopy: (path: String) -> Unit = {},
    selectionMode: Boolean = false,
    exportSelection: ExportSelection = ExportSelection(),
    exportRunning: Boolean = false,
    onEnterSelection: () -> Unit = {},
    onCancelSelection: () -> Unit = {},
    onSelectionChange: (ExportSelection) -> Unit = {},
) {
    val colors = PocketColors.current
    val accent = MaterialTheme.colorScheme.primary
    val selectionMarks = remember(files, exportSelection, selectionMode) {
        if (selectionMode) exportSelectionMarks(files, exportSelection) else emptyMap()
    }
    val anySelected = selectionMarks.values.any { it != SelectionMark.NONE }
    val allSelected = files.isNotEmpty() && selectionMarks.values.all { it == SelectionMark.ALL }
    var nameDialog by remember { mutableStateOf<EntryNameDialog?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var rootMenuOpen by remember { mutableStateOf(false) }
    val rootMenuAnchor = rememberOverlayAnchor()
    val entryActions = EntryActions(
        enabled = fileOpsEnabled,
        onNewFile = { parent -> nameDialog = EntryNameDialog.Create(parent, isDirectory = false) },
        onNewFolder = { parent -> nameDialog = EntryNameDialog.Create(parent, isDirectory = true) },
        onUpload = onUploadInto,
        onRename = { entry -> nameDialog = EntryNameDialog.Rename(entry.path, entry.name) },
        onDelete = { entry -> deleteTarget = entry },
        onShare = { entry -> onShareFile(entry.path) },
        onSaveCopy = { entry -> onSaveFileCopy(entry.path) },
    )
    val apkFiles = remember(files) {
        files.filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
            .sortedByDescending { it.sizeBytes }
    }
    var expandedDirectories by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(files.map { it.path }) {
        val directories = files.asSequence().filter { it.isDirectory }.map { it.path }.toSet()
        expandedDirectories = expandedDirectories.filter { it in directories }
    }
    val expandedSet = expandedDirectories.toSet()
    val visibleFiles = files.filter { entry ->
        val segments = entry.path.split('/')
        segments.size == 1 || (1 until segments.size).all { depth ->
            segments.take(depth).joinToString("/") in expandedSet
        }
    }
    val directChildCounts = remember(files) {
        files.filter { candidate ->
            candidate.path.contains('/')
        }.groupingBy { candidate -> candidate.path.substringBeforeLast('/') }.eachCount()
    }
    val bottom = bottomClearance + PocketSpacing.lg

    LazyColumn(
        modifier = Modifier.fillMaxSize().hostBackdropSource(),
        state = listState,
        contentPadding = PaddingValues(top = topClearance + PocketSpacing.sm, bottom = bottom),
    ) {
        if (changes.isNotEmpty()) {
            item(key = "changes") {
                ChangesFilesSection(
                    changes = changes,
                    onReview = onReviewChanges,
                    onUndoAll = onUndoChanges,
                    onKeepAll = onKeepChanges,
                )
            }
        }
        if (suggestedProjectRoot != null) {
            item(key = "suggested-project-root") {
                ListSection(
                    modifier = Modifier.padding(bottom = PocketSpacing.lg),
                    header = "Project folder detected",
                    footer = "Chat, Terminal, Changes and Preview will all run from the same folder.",
                ) {
                    ListRow(
                        "Use $suggestedProjectRoot as project root",
                        icon = Icons.Outlined.Folder,
                        iconTile = colors.blue,
                        titleColor = accent,
                        onClick = onUseSuggestedProjectRoot,
                    )
                }
            }
        }
        if (androidBuild != null) {
            item(key = "android-build") {
                AndroidBuildSection(androidBuild, onAndroidBuild, onCancelAndroidBuild, onOpenAndroidBuildIssue)
            }
        }
        if (apkFiles.isNotEmpty()) {
            item(key = "built-apks-card") {
                ListSection(modifier = Modifier.padding(bottom = PocketSpacing.lg), header = "Built APKs") {
                    apkFiles.take(3).forEach { apk ->
                        ListRow(
                            apk.name,
                            subtitle = "${formatFileSize(apk.sizeBytes)} · ${apk.path}",
                            subtitleMaxLines = 1,
                            icon = Icons.Outlined.Android,
                            iconTile = colors.green,
                            trailing = {
                                PocketButton("Install", { onInstallApk(apk) }, style = PocketButtonStyle.Tinted, size = PocketButtonSize.Small)
                            },
                        )
                    }
                }
            }
        }
        item(key = "files-header") {
            Row(
                Modifier.fillMaxWidth().padding(start = ListInset, end = ListInset - PocketSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    SectionHeader("Select files to export", Modifier.weight(1f))
                    SheetTextButton(
                        if (allSelected) "Deselect all" else "Select all",
                        { onSelectionChange(if (allSelected) ExportSelection.None else ExportSelection.All) },
                        enabled = !exportRunning,
                    )
                    SheetTextButton("Cancel", onCancelSelection, enabled = !exportRunning)
                    return@Row
                }
                SectionHeader("Files", Modifier.weight(1f))
                if (expandedDirectories.isNotEmpty()) {
                    SheetTextButton("Collapse all", { expandedDirectories = emptyList() })
                }
                PocketIconButton(
                    Icons.Outlined.Add,
                    "New file or folder",
                    { rootMenuOpen = true },
                    modifier = Modifier.overlayAnchor(rootMenuAnchor),
                    enabled = fileOpsEnabled,
                )
                GlassMenu(expanded = rootMenuOpen, onDismiss = { rootMenuOpen = false }, anchor = rootMenuAnchor) {
                    FolderMenuItems(parentPath = "", actions = entryActions)
                }
                if (!loading && files.any { !it.isDirectory }) {
                    PocketIconButton(Icons.Outlined.Download, "Export project as ZIP", onEnterSelection, enabled = fileOpsEnabled)
                }
                if (loading) {
                    Box(Modifier.size(LiquidGlassTokens.MinTouchTarget), contentAlignment = Alignment.Center) {
                        ProgressRing(progress = null, size = 18.dp, strokeWidth = 2.dp)
                    }
                } else {
                    PocketIconButton(Icons.Outlined.Refresh, "Refresh files", onRefresh)
                }
            }
        }
        if (!loading && files.isEmpty()) {
            item(key = "files-empty") {
                EmptyState(Icons.Outlined.Folder, "No files yet", message = "Ask your coding agent to create something in this project.")
            }
        }
        itemsIndexed(visibleFiles, key = { _, entry -> entry.path }) { index, entry ->
            LazyGroupRow(
                isFirst = index == 0,
                isLast = index == visibleFiles.lastIndex,
                modifier = Modifier.animateItem(
                    fadeInSpec = PocketMotion.spec(Token.Quick),
                    placementSpec = PocketMotion.spec(Token.Snappy, androidx.compose.ui.unit.IntOffset.VisibilityThreshold),
                    fadeOutSpec = PocketMotion.spec(Token.Quick),
                ),
                separatorStart = ListInset + (entry.depth * 16).dp + 52.dp,
            ) {
                val toggleExpanded = {
                    expandedDirectories = if (entry.path in expandedSet) {
                        expandedDirectories.filterNot { it == entry.path || it.startsWith("${entry.path}/") }
                    } else {
                        expandedDirectories + entry.path
                    }
                }
                FileRow(
                    entry = entry,
                    expanded = entry.path in expandedSet,
                    childCount = directChildCounts[entry.path] ?: 0,
                    onClick = {
                        val isApk = !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)
                        if (selectionMode) {
                            if (!exportRunning) onSelectionChange(exportSelection.toggled(entry.path, entry.isDirectory))
                        } else if (entry.isDirectory) {
                            toggleExpanded()
                        } else if (isApk) {
                            onInstallApk(entry)
                        } else {
                            onOpenFile(entry)
                        }
                    },
                    actions = if (selectionMode) null else entryActions,
                    selection = if (selectionMode) selectionMarks[entry.path] ?: SelectionMark.NONE else null,
                    onToggleExpanded = toggleExpanded,
                )
            }
        }
        if (selectionMode) {
            item(key = "download-selection") {
                PocketButton(
                    if (exportRunning) "Creating ZIP…" else "Download ZIP",
                    onExport,
                    modifier = Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.lg),
                    enabled = anySelected && !exportRunning && fileOpsEnabled,
                    fullWidth = true,
                )
            }
        }
    }
    BackHandler(enabled = selectionMode && !exportRunning, onBack = onCancelSelection)

    nameDialog?.let { dialog ->
        EntryNameAlert(
            dialog = dialog,
            onDismiss = { nameDialog = null },
            onConfirm = { name ->
                nameDialog = null
                when (dialog) {
                    is EntryNameDialog.Create -> onCreateEntry(dialog.parentPath, name, dialog.isDirectory)
                    is EntryNameDialog.Rename -> onRenameEntry(dialog.path, name)
                }
            },
        )
    }
    deleteTarget?.let { entry ->
        PocketAlert(
            onDismiss = { deleteTarget = null },
            title = "Delete ${entry.name}?",
            message = if (entry.isDirectory) {
                "This folder and everything in it will be permanently deleted."
            } else {
                "This file will be permanently deleted."
            },
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { deleteTarget = null },
                AlertAction("Delete", AlertRole.Destructive) {
                    deleteTarget = null
                    onDeleteEntry(entry.path)
                },
            ),
        )
    }
}

/** Keeps an [ExportSelection] in saved instance state. */
private val ExportSelectionSaver = listSaver<ExportSelection, Any>(
    save = { selection ->
        listOf(
            selection.defaultIncluded,
            selection.skipBuildOutput,
            ArrayList(selection.overrides.keys),
            selection.overrides.values.toBooleanArray(),
        )
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val keys = saved[2] as List<String>
        val values = saved[3] as BooleanArray
        ExportSelection(
            overrides = keys.zip(values.toList()).toMap(),
            defaultIncluded = saved[0] as Boolean,
            skipBuildOutput = saved[1] as Boolean,
        )
    },
)

/** What the Files tab's menus can do. Changes are disabled (not hidden) while [enabled] is false. */
private class EntryActions(
    val enabled: Boolean,
    val onNewFile: (parentPath: String) -> Unit,
    val onNewFolder: (parentPath: String) -> Unit,
    val onUpload: (parentPath: String) -> Unit,
    val onRename: (WorkspaceEntry) -> Unit,
    val onDelete: (WorkspaceEntry) -> Unit,
    val onShare: (WorkspaceEntry) -> Unit,
    val onSaveCopy: (WorkspaceEntry) -> Unit,
)

private sealed interface EntryNameDialog {
    data class Create(val parentPath: String, val isDirectory: Boolean) : EntryNameDialog
    data class Rename(val path: String, val currentName: String) : EntryNameDialog
}

/** What the Files tab shows about the project's one-tap Android build. */
internal data class AndroidBuildUi(
    val running: Boolean,
    val cancelling: Boolean,
    val message: String?,
    val failed: Boolean,
    val startedAtMillis: Long? = null,
    val typicalMillis: Long? = null,
    val issues: List<AndroidBuildIssue> = emptyList(),
)

internal enum class AndroidBuildRequest { BUILD_AND_RUN, BUILD_ONLY, FORCE_REBUILD }

private const val MAX_VISIBLE_BUILD_ISSUES = 8

/** Build & run, Build only and Force rebuild for an Android Gradle project, with live progress and Cancel. */
@Composable
private fun AndroidBuildSection(
    build: AndroidBuildUi,
    onBuild: (AndroidBuildRequest) -> Unit,
    onCancel: () -> Unit,
    onOpenIssue: (AndroidBuildIssue) -> Unit,
) {
    val colors = PocketColors.current
    // A one-second clock only while a Gradle build is running and this section is on screen.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(build.running, build.startedAtMillis) {
        while (build.running && build.startedAtMillis != null) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val elapsed = build.startedAtMillis?.let { (now - it).coerceAtLeast(0L) }
    val progressText = when {
        build.running && elapsed != null -> listOfNotNull(
            "${formatBuildDuration(elapsed)} elapsed",
            androidBuildEstimateText(running = true, elapsedMillis = elapsed, typicalMillis = build.typicalMillis),
        ).joinToString(" · ")
        else -> null
    }
    val usualText = androidBuildEstimateText(running = false, elapsedMillis = 0L, typicalMillis = build.typicalMillis)
    ListSection(
        modifier = Modifier.padding(bottom = PocketSpacing.lg),
        header = "Android app",
        footer = if (build.running) null else listOfNotNull(
            usualText?.let { "$it per build." },
            "Unchanged projects reuse the last APK. Force rebuild always runs Gradle.",
        ).joinToString(" "),
    ) {
        if (build.running) {
            ListRow(
                build.message ?: "Building debug APK…",
                subtitle = progressText,
                leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
                trailing = {
                    PocketButton(
                        if (build.cancelling) "Stopping…" else "Cancel",
                        onCancel,
                        style = PocketButtonStyle.Tinted,
                        size = PocketButtonSize.Small,
                        destructive = true,
                        enabled = !build.cancelling,
                    )
                },
            )
        } else {
            build.message?.let { message ->
                ListRow(
                    if (build.failed) "Build failed" else "Last build",
                    subtitle = message,
                    subtitleMaxLines = 6,
                    subtitleColor = if (build.failed) colors.red else null,
                    icon = if (build.failed) Icons.Outlined.Warning else Icons.Outlined.Check,
                    iconTile = if (build.failed) colors.red else colors.green,
                )
            }
            build.issues.take(MAX_VISIBLE_BUILD_ISSUES).forEach { issue ->
                val location = issue.filePath?.let { path ->
                    listOfNotNull(path, issue.line?.toString(), issue.column?.toString()).joinToString(":")
                }
                ListRow(
                    location ?: issue.title,
                    subtitle = if (location != null) "${issue.title}: ${issue.detail}" else issue.detail,
                    subtitleMaxLines = 4,
                    icon = Icons.Outlined.Description,
                    iconTile = colors.orange,
                    accessory = if (issue.filePath != null) ListRowAccessory.Chevron else ListRowAccessory.None,
                    onClick = issue.filePath?.let { { onOpenIssue(issue) } },
                )
            }
            if (build.issues.size > MAX_VISIBLE_BUILD_ISSUES) {
                ListRow("${build.issues.size - MAX_VISIBLE_BUILD_ISSUES} more issues not shown", enabled = false)
            }
            ListRow(
                "Build & run",
                subtitle = "Build the debug APK, then install and open it",
                icon = Icons.Outlined.PlayArrow,
                iconTile = colors.green,
                onClick = { onBuild(AndroidBuildRequest.BUILD_AND_RUN) },
            )
            ListRow(
                "Build only",
                subtitle = "Build the debug APK without installing it",
                icon = Icons.Outlined.Build,
                iconTile = colors.blue,
                onClick = { onBuild(AndroidBuildRequest.BUILD_ONLY) },
            )
            ListRow(
                "Force rebuild",
                subtitle = "Run Gradle even if nothing changed",
                icon = Icons.Outlined.Refresh,
                iconTile = colors.orange,
                onClick = { onBuild(AndroidBuildRequest.FORCE_REBUILD) },
            )
        }
    }
}

@Composable
private fun FolderMenuItems(parentPath: String, actions: EntryActions) {
    GlassMenuItem("New file", { actions.onNewFile(parentPath) }, icon = Icons.AutoMirrored.Outlined.NoteAdd, enabled = actions.enabled)
    GlassMenuItem("New folder", { actions.onNewFolder(parentPath) }, icon = Icons.Outlined.CreateNewFolder, enabled = actions.enabled)
    GlassMenuItem("Upload here", { actions.onUpload(parentPath) }, icon = Icons.Outlined.Upload, enabled = actions.enabled)
}

/** The actions for one entry of the tree, from its "…" button or a long press. */
@Composable
private fun EntryMenuItems(entry: WorkspaceEntry, actions: EntryActions) {
    if (entry.isDirectory) {
        FolderMenuItems(entry.path, actions)
    } else {
        GlassMenuItem("Share", { actions.onShare(entry) }, icon = Icons.Outlined.Share)
        GlassMenuItem("Save a copy", { actions.onSaveCopy(entry) }, icon = Icons.Outlined.Download)
    }
    GlassMenuDivider()
    GlassMenuItem("Rename", { actions.onRename(entry) }, icon = Icons.Outlined.DriveFileRenameOutline, enabled = actions.enabled)
    GlassMenuItem("Delete", { actions.onDelete(entry) }, icon = Icons.Outlined.Delete, destructive = true, enabled = actions.enabled)
}

/** Asks for the name of a new file or folder, or a new name for an entry. Problems show under the field. */
@Composable
private fun EntryNameAlert(dialog: EntryNameDialog, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val initial = (dialog as? EntryNameDialog.Rename)?.currentName.orEmpty()
    var name by remember(dialog) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(dialog) { runCatching { focusRequester.requestFocus() } }
    val problem = if (name.isEmpty()) null else entryNameProblem(name)
    val canConfirm = name.isNotEmpty() && problem == null && name != initial
    val folder = when (dialog) {
        is EntryNameDialog.Create -> dialog.parentPath
        is EntryNameDialog.Rename -> dialog.path.substringBeforeLast('/', "")
    }
    PocketAlert(
        onDismiss = onDismiss,
        title = when (dialog) {
            is EntryNameDialog.Create -> if (dialog.isDirectory) "New folder" else "New file"
            is EntryNameDialog.Rename -> "Rename"
        },
        message = "In /$folder",
        actions = listOf(
            AlertAction("Cancel", AlertRole.Cancel, onClick = onDismiss),
            AlertAction(if (dialog is EntryNameDialog.Rename) "Rename" else "Create", enabled = canConfirm) { onConfirm(name) },
        ),
    ) {
        PocketTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = "Name",
            error = problem,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(name) }),
            focusRequester = focusRequester,
        )
    }
}

/** One entry of the tree: a disclosure chevron for folders, indented by depth, and a menu of actions. */
@Composable
private fun FileRow(
    entry: WorkspaceEntry,
    expanded: Boolean,
    childCount: Int,
    onClick: () -> Unit,
    actions: EntryActions? = null,
    selection: SelectionMark? = null,
    onToggleExpanded: () -> Unit = {},
) {
    val colors = PocketColors.current
    val isApk = !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)
    var menuOpen by remember { mutableStateOf(false) }
    val menuAnchor = rememberOverlayAnchor()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateColorAsState(
        if (pressed) colors.tertiaryFill else Color.Transparent,
        PocketMotion.spec(Token.Quick),
        label = "fileRowHighlight",
    )
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, PocketMotion.spec(Token.Snappy), label = "folderChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .background(highlight)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = when {
                    selection != null -> if (selection == SelectionMark.ALL) "Leave out of the export" else "Include in the export"
                    entry.isDirectory -> if (expanded) "Collapse folder" else "Expand folder"
                    isApk -> "Install"
                    else -> "Open"
                },
                onLongClickLabel = if (actions != null) "More actions" else null,
                onLongClick = if (actions != null) ({ menuOpen = true }) else null,
                onClick = onClick,
            )
            .heightIn(min = LiquidGlassTokens.MinTouchTarget)
            .padding(
                start = ListInset + (entry.depth * 16).dp,
                end = if (actions != null) PocketSpacing.xs else ListInset - 2.dp,
                top = if (actions != null) 6.dp else 10.dp,
                bottom = if (actions != null) 6.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) {
            Icon(
                when (selection) {
                    SelectionMark.ALL -> Icons.Rounded.CheckCircle
                    SelectionMark.SOME -> Icons.Rounded.RemoveCircle
                    SelectionMark.NONE -> Icons.Outlined.Circle
                },
                contentDescription = when (selection) {
                    SelectionMark.ALL -> "Selected"
                    SelectionMark.SOME -> "Partly selected"
                    SelectionMark.NONE -> "Not selected"
                },
                tint = if (selection == SelectionMark.NONE) colors.tertiaryLabel else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(PocketSpacing.sm))
        }
        if (entry.isDirectory) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = if (selection != null) (if (expanded) "Collapse folder" else "Expand folder") else null,
                tint = colors.tertiaryLabel,
                modifier = Modifier
                    .then(
                        // While selecting, a tap on the row selects; the chevron still opens the folder.
                        if (selection != null) {
                            Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onToggleExpanded).padding(PocketSpacing.xs)
                        } else {
                            Modifier
                        },
                    )
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        } else {
            Spacer(Modifier.width(if (selection != null) 18.dp + PocketSpacing.xs * 2 else 18.dp))
        }
        Spacer(Modifier.width(PocketSpacing.xs))
        Icon(
            when {
                entry.isDirectory -> Icons.Outlined.Folder
                isApk -> Icons.Outlined.Android
                else -> Icons.Outlined.Description
            },
            contentDescription = null,
            tint = when {
                entry.isDirectory -> colors.blue
                isApk -> colors.green
                else -> colors.secondaryLabel
            },
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(PocketSpacing.sm + 2.dp))
        Text(
            entry.name,
            Modifier.weight(1f),
            style = PocketType.body,
            color = if (isApk) colors.green else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(PocketSpacing.sm))
        if (entry.isDirectory) {
            Text("$childCount", style = PocketType.subheadline, color = colors.secondaryLabel)
        } else {
            Text(formatFileSize(entry.sizeBytes), style = PocketType.footnote, color = colors.secondaryLabel)
            if (isApk) {
                Spacer(Modifier.width(PocketSpacing.sm))
                Text("Install", style = PocketType.footnote.emphasized, color = colors.green)
            } else if (actions == null && selection == null) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.tertiaryLabel,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (actions != null) {
            Box(
                Modifier
                    .overlayAnchor(menuAnchor)
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClickLabel = "More actions for ${entry.name}") { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MoreHoriz,
                    contentDescription = "More actions for ${entry.name}",
                    tint = colors.secondaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
            GlassMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, anchor = menuAnchor) {
                EntryMenuItems(entry, actions)
            }
        }
    }
}

/** Quiet stand-in for the transcript while a project's chat history loads. */
@Composable
private fun ChatLoadingPlaceholder() {
    val colors = PocketColors.current
    Column(verticalArrangement = Arrangement.spacedBy(PocketSpacing.md)) {
        listOf(0.9f, 0.6f, 0.75f).forEach { fraction ->
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(PocketShape.sm)
                    .background(colors.codeSurface),
            )
        }
    }
}

/** Older messages start loading when the first visible row is this close to the top of what is loaded. */
private const val OLDER_MESSAGES_PREFETCH_ROWS = 5

internal fun sanitizeChatTabMessages(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    val seen = HashSet<String>(messages.size)
    var hasDups = false
    for (m in messages) {
        if (!seen.add(m.id)) {
            hasDups = true
            break
        }
    }
    return if (hasDups) {
        AppPreferences.repairDuplicateMessageIds(messages).first
    } else {
        messages
    }
}

@Composable
private fun ChatTab(
    messages: List<ChatMessage>,
    approval: ToolRequest?,
    liveProcess: List<ActivityItem>,
    isRunning: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onApproval: (Boolean) -> Unit,
    listState: LazyListState,
    taskStartedAtMillis: Long?,
    taskFinishedAtMillis: Long?,
    thinkingActive: Boolean,
    agentKind: AgentKind,
    pendingAttachments: List<ChatAttachment>,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onOpenAttachment: (ChatAttachment) -> Unit,
    onRunInTerminal: (String) -> Unit,
    onTakePhoto: () -> Unit = {},
    readOnly: Boolean = false,
    readOnlyBlocked: Boolean = false,
    loading: Boolean = false,
    onContinueHere: () -> Unit = {},
    slashCommands: List<SlashCommand> = emptyList(),
    slashCommandsVisible: Boolean = false,
    skills: List<SkillInfo> = emptyList(),
    onPromptChanged: (String) -> Unit = {},
    onOpenInspector: () -> Unit = {},
    onOpenMemoryViewer: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    liveWorkStatus: WorkStatus = WorkStatus.WORKING,
    subagentsCount: Int = 0,
    tasksCount: Int = 0,
    mentionMenuVisible: Boolean = false,
    mentionFiles: List<WorkspaceEntry> = emptyList(),
    isStopping: Boolean = false,
    activeChatId: String? = null,
    topClearance: Dp = 0.dp,
    bottomBarClearance: Dp = 0.dp,
    hasOlderMessages: Boolean = false,
    onLoadOlderMessages: () -> Unit = {},
    queuedFollowUps: List<QueuedFollowUp> = emptyList(),
    onSteerFollowUp: (String) -> Unit = {},
    onRemoveFollowUp: (String) -> Unit = {},
    composerDraft: String? = null,
    onComposerDraftConsumed: () -> Unit = {},
) {
    val view = LocalView.current
    // Keep the screen on while the selected agent is working in this chat. Released automatically
    // when the task finishes or the user leaves the chat tab.
    DisposableEffect(isRunning) {
        view.keepScreenOn = isRunning
        onDispose { view.keepScreenOn = false }
    }
    var inputState by rememberSaveable(activeChatId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val setInput = { value: TextFieldValue ->
        inputState = value
        onPromptChanged(value.text)
    }
    // A mod's prompt.draft: it fills only an empty composer and is never sent by itself.
    LaunchedEffect(composerDraft) {
        val draft = composerDraft ?: return@LaunchedEffect
        if (inputState.text.isBlank()) setInput(TextFieldValue(draft, selection = TextRange(draft.length)))
        onComposerDraftConsumed()
    }
    val chatScope = rememberCoroutineScope()
    // True while the newest item (message, live panel, or approval card) is on screen.
    val readerAtBottom by remember(listState) {
        derivedStateOf {
            !listState.canScrollForward
        }
    }
    val safeMessages = remember(messages) { sanitizeChatTabMessages(messages) }
    val density = LocalDensity.current
    val backdrop = LocalLiquidGlassBackdrop.current
    val colors = PocketColors.current
    // Programmatic scrolls (Latest, auto-follow) dispatch no nested scroll, so recapture the shared
    // backdrop whenever the scroll position moves; the source throttles the actual captures.
    LaunchedEffect(listState, backdrop) {
        if (backdrop == null) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { backdrop.requestCapture() }
    }
    // Older messages stay on disk until the reader nears the top. Rows are keyed, so the list keeps the row on screen
    // in place as older ones arrive above it. Restarting on each new row asks again if the reader is still near the top.
    val loadOlder by rememberUpdatedState(onLoadOlderMessages)
    LaunchedEffect(listState, hasOlderMessages, safeMessages.size) {
        if (!hasOlderMessages) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex < OLDER_MESSAGES_PREFETCH_ROWS }
            .collect { nearTop -> if (nearTop) loadOlder() }
    }
    // Height of everything floating at the bottom: the composer stack plus the keyboard, tab bar or
    // navigation bar below it. Measured, so the last message always scrolls clear of the chrome.
    var bottomChromeClearance by remember { mutableStateOf(0.dp) }
    // The larger of the keyboard, the navigation bar and the workspace tab bar ([bottomBarClearance]),
    // so the composer rides the keyboard up from above the tab bar without a jump.
    val bottomInsets = WindowInsets.ime.union(WindowInsets.navigationBars).union(WindowInsets(bottom = bottomBarClearance))
    val bottomChromeModifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(bottomInsets)
        .onGloballyPositioned { coordinates ->
            val parentHeight = coordinates.parentLayoutCoordinates?.size?.height ?: return@onGloballyPositioned
            val clearance = with(density) { (parentHeight - coordinates.positionInParent().y).toDp() }
            if (clearance != bottomChromeClearance) bottomChromeClearance = clearance
        }
    val activeCount = subagentsCount + tasksCount
    val isEmpty = safeMessages.isEmpty() && liveProcess.isEmpty() && !thinkingActive && approval == null

    // The message list is the shared backdrop source and runs edge to edge; all chat chrome floats
    // over it as siblings (never descendants) and samples it through LiquidGlassLayers.Background.
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .hostBackdropSource(),
            state = listState,
            contentPadding = PaddingValues(
                start = PocketSpacing.lg,
                top = topClearance + PocketSpacing.md,
                end = PocketSpacing.lg,
                bottom = bottomChromeClearance + PocketSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
        ) {
            if (isEmpty) {
                item(key = "empty-chat") {
                    Box(Modifier.fillParentMaxHeight(0.7f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // While history loads, a quiet placeholder stands in so the chat never flashes "Start a conversation".
                        if (loading) {
                            ChatLoadingPlaceholder()
                        } else {
                            EmptyState(
                                Icons.Outlined.AutoAwesome,
                                if (readOnly) "No messages" else "Start a conversation",
                                message = if (readOnly) null else "Ask ${agentKind.title} to build, fix or explain something in this project.",
                            )
                        }
                    }
                }
            }
            items(
                safeMessages,
                key = { it.id },
                contentType = { if (it.workItems.isNotEmpty()) "work" else if (it.fromUser) "user" else "assistant" },
            ) { message ->
                // New turns fade in; no placement animation, so streaming growth never lags.
                Box(
                    Modifier.animateItem(
                        fadeInSpec = PocketMotion.spec(Token.Smooth),
                        placementSpec = null,
                        fadeOutSpec = PocketMotion.spec(Token.Quick),
                    ),
                ) {
                    if (message.workItems.isNotEmpty()) {
                        WorkBlockCard(message)
                    } else {
                        MessageBubble(message, onRunInTerminal, onOpenAttachment)
                    }
                }
            }
            if (isRunning || liveProcess.isNotEmpty() || thinkingActive) {
                item(key = "live-claude-process") {
                    LiveClaudeProcess(
                        processItems = liveProcess,
                        isRunning = isRunning,
                        startedAtMillis = taskStartedAtMillis,
                        finishedAtMillis = taskFinishedAtMillis,
                        status = liveWorkStatus,
                    )
                }
            }
            approval?.let { request -> item(key = "approval") { ApprovalCard(request, onApproval) } }
        }
        // Messages soften into the canvas under the composer, as they do under the top bar.
        ScrollEdgeEffect(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomChromeClearance + PocketSpacing.xxl),
            fromTop = false,
        ) { if (listState.canScrollForward) 1f else 0f }
        AnimatedVisibility(
            visible = !readerAtBottom,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomChromeClearance + PocketSpacing.md),
            enter = fadeIn(PocketMotion.spec(Token.Quick)) + scaleIn(PocketMotion.spec(Token.Snappy), initialScale = 0.8f),
            exit = fadeOut(PocketMotion.spec(Token.Quick)) + scaleOut(PocketMotion.spec(Token.Quick), targetScale = 0.8f),
        ) {
            LiquidGlassSurface(
                material = LiquidGlassMaterial.Regular,
                shape = PocketShape.capsule,
                layerSource = LiquidGlassLayers.Background,
                role = GlassRoles.Latest,
                onClick = {
                    chatScope.launch {
                        listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                    }
                },
                semanticRole = Role.Button,
                contentDescription = "Scroll to latest",
            ) {
                Row(
                    Modifier
                        .heightIn(min = LiquidGlassTokens.MinTouchTarget)
                        .padding(start = PocketSpacing.md, end = PocketSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(PocketSpacing.xs))
                    Text("Latest", style = PocketType.footnote.emphasized)
                }
            }
        }
        if (readOnly) {
            Box(Modifier.align(Alignment.BottomCenter).then(bottomChromeModifier)) {
                ReadOnlyBanner(blocked = readOnlyBlocked, onContinueHere = onContinueHere)
            }
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .then(bottomChromeModifier)
                    .padding(start = PocketSpacing.lg, top = PocketSpacing.xs, end = PocketSpacing.lg, bottom = PocketSpacing.xs),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (activeCount > 0) ActivityCapsule(activeCount, onOpenInspector)
                if (queuedFollowUps.isNotEmpty()) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs),
                    ) {
                        queuedFollowUps.forEach { followUp ->
                            key(followUp.id) {
                                QueuedFollowUpCard(
                                    followUp = followUp,
                                    onSteer = { onSteerFollowUp(followUp.id) },
                                    onRemove = { onRemoveFollowUp(followUp.id) },
                                )
                            }
                        }
                    }
                }
                if (pendingAttachments.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                    ) {
                        pendingAttachments.forEach { attachment ->
                            AttachmentChip(
                                attachment = attachment,
                                onOpen = null,
                                onRemove = { onRemoveAttachment(attachment.id) },
                            )
                        }
                    }
                }

                ModProjectStrip()

                if (slashCommandsVisible) {
                    SlashCommandMenu(
                        commands = slashCommands,
                        skills = skills,
                        onSelect = { cmd ->
                            val template = cmd.insertText
                            if (template != null) {
                                // A mod's prompt template: its text replaces the "/query" segment.
                                val currentText = inputState.text
                                val cursorPos = inputState.selection.end
                                val slashIdx = currentText.lastIndexOf('/', cursorPos)
                                val before = if (slashIdx >= 0) currentText.substring(0, slashIdx) else currentText
                                val after = if (slashIdx >= 0) currentText.substring(cursorPos) else ""
                                setInput(TextFieldValue(text = before + template + after, selection = TextRange(before.length + template.length)))
                            } else if (cmd.isLocalOnly && cmd.parameterHint == null) {
                                // Local instant commands: dispatch immediately and clear input
                                onSend(cmd.syntax)
                                setInput(TextFieldValue(""))
                            } else {
                                // Commands with arguments: replace trigger segment with cursor at end
                                val currentText = inputState.text
                                val cursorPos = inputState.selection.end
                                val slashIdx = currentText.lastIndexOf('/', cursorPos)
                                val newText = if (slashIdx >= 0) {
                                    // Mid-sentence insertion: replace from '/' to cursor
                                    val before = currentText.substring(0, slashIdx)
                                    val after = currentText.substring(cursorPos)
                                    "${before}${cmd.syntax} ${after}"
                                } else {
                                    "${cmd.syntax} "
                                }
                                val newCursorPos = if (slashIdx >= 0) {
                                    slashIdx + cmd.syntax.length + 1
                                } else {
                                    newText.length
                                }
                                setInput(TextFieldValue(text = newText, selection = TextRange(newCursorPos)))
                            }
                        },
                        onSelectSkill = { skill ->
                            val currentText = inputState.text
                            val cursorPos = inputState.selection.end
                            val slashIdx = currentText.lastIndexOf('/', cursorPos)
                            val skillSyntax = "/${skill.name}"
                            val newText = if (slashIdx >= 0) {
                                val before = currentText.substring(0, slashIdx)
                                val after = currentText.substring(cursorPos)
                                "${before}${skillSyntax} ${after}"
                            } else {
                                "${skillSyntax} "
                            }
                            val newCursorPos = if (slashIdx >= 0) {
                                slashIdx + skillSyntax.length + 1
                            } else {
                                newText.length
                            }
                            setInput(TextFieldValue(text = newText, selection = TextRange(newCursorPos)))
                        },
                    )
                }

                if (mentionMenuVisible) {
                    MentionMenu(
                        files = mentionFiles,
                        onSelect = { entry ->
                            val currentText = inputState.text
                            val cursorPos = inputState.selection.end
                            val atIdx = currentText.lastIndexOf('@', cursorPos)
                            val newText = if (atIdx >= 0) {
                                val before = currentText.substring(0, atIdx)
                                val after = currentText.substring(cursorPos)
                                "${before}@${entry.path} ${after}"
                            } else {
                                "@${entry.path} "
                            }
                            val newCursorPos = if (atIdx >= 0) {
                                atIdx + entry.path.length + 2
                            } else {
                                newText.length
                            }
                            setInput(TextFieldValue(text = newText, selection = TextRange(newCursorPos)))
                        },
                    )
                }

                Composer(
                    input = inputState,
                    onInputChange = setInput,
                    agentKind = agentKind,
                    isRunning = isRunning,
                    isStopping = isStopping,
                    canSend = inputState.text.isNotBlank() || pendingAttachments.isNotEmpty(),
                    attachEnabled = pendingAttachments.size < 5,
                    hasAttachments = pendingAttachments.isNotEmpty(),
                    activeCount = activeCount,
                    onSend = {
                        onSend(inputState.text)
                        setInput(TextFieldValue(""))
                    },
                    onStop = onStop,
                    onAttach = onAttach,
                    onTakePhoto = onTakePhoto,
                    onToggleCommands = {
                        val newText = if (inputState.text.startsWith("/")) "" else "/"
                        setInput(TextFieldValue(newText, TextRange(newText.length)))
                    },
                    onOpenSkills = onOpenSkills,
                    onOpenInspector = onOpenInspector,
                    onOpenMemoryViewer = onOpenMemoryViewer,
                )
            }
        }
    }
}

/**
 * The composer: a glass + circle (attach files, commands, skills, activity) beside a glass
 * capsule holding the message field and the send or stop button.
 */
@Composable
private fun Composer(
    input: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    agentKind: AgentKind,
    isRunning: Boolean,
    isStopping: Boolean,
    canSend: Boolean,
    attachEnabled: Boolean,
    hasAttachments: Boolean,
    activeCount: Int,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onTakePhoto: () -> Unit,
    onToggleCommands: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenInspector: () -> Unit,
    onOpenMemoryViewer: () -> Unit,
) {
    val colors = PocketColors.current
    val accent = MaterialTheme.colorScheme.primary
    var toolsOpen by remember { mutableStateOf(false) }
    val toolsAnchor = rememberOverlayAnchor()
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
    ) {
        LiquidGlassSurface(
            modifier = Modifier.size(ComposerMinHeight).overlayAnchor(toolsAnchor),
            shape = PocketShape.capsule,
            layerSource = LiquidGlassLayers.Background,
            role = GlassRoles.Composer,
            onClick = { toolsOpen = true },
            semanticRole = Role.Button,
            contentDescription = "Attach files and tools",
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    tint = if (hasAttachments) accent else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        LiquidGlassSurface(
            modifier = Modifier.weight(1f).heightIn(min = ComposerMinHeight),
            shape = ContinuousRoundedShape(ComposerMinHeight / 2),
            layerSource = LiquidGlassLayers.Background,
            role = GlassRoles.Composer,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = PocketSpacing.lg, end = PocketSpacing.xs),
                verticalAlignment = Alignment.Bottom,
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = PocketSpacing.md)
                        .heightIn(min = 22.dp, max = 132.dp),
                    minLines = 1,
                    maxLines = 6,
                    textStyle = PocketType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (input.text.isEmpty()) {
                                Text(
                                    text = "Message ${agentKind.title}…",
                                    style = PocketType.body,
                                    color = colors.secondaryLabel,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.width(PocketSpacing.xs))
                Row(Modifier.padding(vertical = PocketSpacing.xxs)) {
                    if (isRunning) {
                        ComposerActionButton(
                            contentDescription = "Stop AI task",
                            container = if (isStopping) colors.red.copy(alpha = 0.6f) else colors.red,
                            onClick = onStop,
                        ) {
                            if (isStopping) {
                                ProgressRing(progress = null, size = 16.dp, strokeWidth = 2.dp, color = Color.White, trackColor = Color.White.copy(alpha = 0.3f))
                            } else {
                                Icon(Icons.Outlined.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    // While a task runs, Send appears beside Stop once there is something to queue.
                    if (!isRunning || canSend) {
                        ComposerActionButton(
                            contentDescription = if (isRunning) "Queue follow-up" else "Send",
                            container = if (canSend) accent else colors.secondaryFill,
                            enabled = canSend,
                            onClick = onSend,
                        ) {
                            Icon(
                                Icons.Outlined.ArrowUpward,
                                contentDescription = null,
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else colors.secondaryLabel,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    GlassMenu(expanded = toolsOpen, onDismiss = { toolsOpen = false }, anchor = toolsAnchor) {
        GlassMenuItem("Take photo", onTakePhoto, icon = Icons.Outlined.PhotoCamera, enabled = attachEnabled)
        GlassMenuItem("Attach files", onAttach, icon = Icons.Outlined.AttachFile, enabled = attachEnabled)
        GlassMenuItem("Commands", onToggleCommands, icon = Icons.Outlined.Code)
        GlassMenuItem("Skills", onOpenSkills, icon = Icons.Outlined.AutoAwesome)
        GlassMenuItem("Memory", onOpenMemoryViewer, icon = Icons.Outlined.Psychology)
        GlassMenuDivider()
        GlassMenuItem(if (activeCount > 0) "Activity · $activeCount running" else "Activity", onOpenInspector, icon = Icons.Outlined.Layers)
    }
}

/** A compact filled circle inside an Android-sized touch area. */
@Composable
private fun ComposerActionButton(
    contentDescription: String,
    container: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, PocketMotion.spec(Token.Quick), label = "composerPress")
    val fill by animateColorAsState(container, PocketMotion.spec(Token.Quick), label = "composerFill")
    Box(
        Modifier
            .size(LiquidGlassTokens.MinTouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(fill)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** A follow-up waiting for the running task: Steer runs it next (stopping the task), the cross removes it. */
@Composable
private fun QueuedFollowUpCard(
    followUp: QueuedFollowUp,
    onSteer: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = PocketColors.current
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = PocketShape.lg,
        layerSource = LiquidGlassLayers.Background,
        role = GlassRoles.Composer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = LiquidGlassTokens.MinTouchTarget)
                .padding(start = PocketSpacing.md, end = PocketSpacing.xs, top = PocketSpacing.xs, bottom = PocketSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
        ) {
            SymbolTile(Icons.Outlined.Schedule, colors.gray, size = 28.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    followUp.prompt.ifBlank { "Attached files" },
                    style = PocketType.subheadline,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (followUp.attachments.isNotEmpty()) {
                    val count = followUp.attachments.size
                    Text(
                        "$count attachment${if (count == 1) "" else "s"}",
                        style = PocketType.caption2,
                        color = colors.secondaryLabel,
                    )
                }
            }
            PocketButton("Steer", onSteer, style = PocketButtonStyle.Tinted, size = PocketButtonSize.Small)
            PocketIconButton(Icons.Rounded.Cancel, "Remove queued follow-up", onRemove, tint = colors.tertiaryLabel, iconSize = 18.dp)
        }
    }
}

/** Shown above the composer while subagents or background tasks run; opens Activity. */
@Composable
private fun ActivityCapsule(count: Int, onClick: () -> Unit) {
    val colors = PocketColors.current
    LiquidGlassSurface(
        shape = PocketShape.capsule,
        layerSource = LiquidGlassLayers.Background,
        onClick = onClick,
        semanticRole = Role.Button,
        contentDescription = "Activity, $count running",
    ) {
        Row(
            Modifier
                .heightIn(min = LiquidGlassTokens.MinTouchTarget)
                .padding(start = PocketSpacing.md, end = PocketSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(colors.green))
            Spacer(Modifier.width(PocketSpacing.sm))
            Text("$count running", style = PocketType.subheadline.medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(PocketSpacing.sm))
            Text("Activity", style = PocketType.subheadline.emphasized, color = MaterialTheme.colorScheme.primary)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Replaces the composer while reading another project's history. */
@Composable
private fun ReadOnlyBanner(blocked: Boolean, onContinueHere: () -> Unit) {
    val colors = PocketColors.current
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.sm),
        shape = PocketShape.lg,
        layerSource = LiquidGlassLayers.Background,
        role = GlassRoles.Composer,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.md),
        ) {
            SymbolTile(Icons.Outlined.History, colors.gray)
            Column(Modifier.weight(1f)) {
                Text("Read-only history", style = PocketType.subheadline.emphasized, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (blocked) {
                        "Another project has a running task. You can read this chat, but cannot send a message."
                    } else {
                        "The other task finished. Open this project to continue chatting."
                    },
                    style = PocketType.footnote,
                    color = colors.secondaryLabel,
                )
            }
            if (!blocked) {
                PocketButton("Open", onContinueHere, style = PocketButtonStyle.Tinted, size = PocketButtonSize.Small)
            }
        }
    }
}

@Composable
private fun LiveClaudeProcess(
    processItems: List<ActivityItem>,
    isRunning: Boolean,
    startedAtMillis: Long?,
    finishedAtMillis: Long?,
    status: WorkStatus,
) {
    val elapsedSeconds = rememberLiveElapsedSeconds(startedAtMillis, finishedAtMillis, isRunning)
    key(startedAtMillis) {
        ClaudeActivityDisclosure(
            items = processItems,
            headline = "${if (isRunning) status.label else "View activity"} · ${formatDuration(elapsedSeconds)}",
            isRunning = isRunning && status !in setOf(WorkStatus.WAITING_FOR_INPUT, WorkStatus.WAITING_FOR_APPROVAL),
            liveDetail = processItems.lastOrNull()?.takeIf { isRunning && status == WorkStatus.WORKING }?.let(::compactActivityText),
        )
    }
}

@Composable
private fun WorkBlockCard(message: ChatMessage) {
    ClaudeActivityDisclosure(items = message.workItems, headline = workBlockHeadline(message))
}

@Composable
internal fun ClaudeActivityDisclosure(
    items: List<ActivityItem>,
    headline: String,
    isRunning: Boolean = false,
    liveDetail: String? = null,
) {
    // One disclosure per stable chat message: appending/trimming events cannot move expansion.
    var expanded by rememberSaveable { mutableStateOf(false) }
    var visibleCount by rememberSaveable { mutableIntStateOf(32) }
    Column(Modifier.fillMaxWidth().padding(horizontal = PocketSpacing.xs, vertical = PocketSpacing.xs)) {
        ActivitySummaryRow(
            item = items.lastOrNull(), text = headline, expanded = expanded,
            showProgress = isRunning, onToggle = { expanded = !expanded },
        )
        if (!expanded && liveDetail != null) {
            Text(liveDetail, Modifier.padding(start = 24.dp, bottom = PocketSpacing.xs), style = PocketType.footnote,
                color = PocketColors.current.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        ActivityDetailReveal(visible = expanded) {
            Column {
                if (items.isEmpty()) ActivityExpandedDetail(null, "Reviewing the request and planning the next action.")
                if (items.size > visibleCount) {
                    PocketButton("Show earlier activity (${items.size - visibleCount})", { visibleCount += 32 }, style = PocketButtonStyle.Plain)
                }
                items.takeLast(visibleCount).forEach { item ->
                    Text(activityName(item), Modifier.padding(start = 24.dp, top = PocketSpacing.sm, bottom = PocketSpacing.xs),
                        style = PocketType.footnote.emphasized, color = PocketColors.current.secondaryLabel)
                    ActivityExpandedDetail(item, activityDetail(item))
                }
            }
        }
    }
}

/** Expanded activity detail grows open and folds shut on a spring instead of popping in. */
@Composable
private fun ActivityDetailReveal(visible: Boolean, content: @Composable () -> Unit) {
    if (LocalLiquidGlassConfig.current.reduceMotion) {
        if (visible) content()
        return
    }
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(PocketMotion.spec(Token.Smooth, IntSize.VisibilityThreshold)) + fadeIn(PocketMotion.spec(Token.Quick)),
        exit = shrinkVertically(PocketMotion.spec(Token.Snappy, IntSize.VisibilityThreshold)) + fadeOut(PocketMotion.spec(Token.Quick)),
    ) { content() }
}

@Composable
internal fun AnimatedThinkingDots(
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (LocalLiquidGlassConfig.current.reduceMotion || !lifecycleState.isAtLeast(Lifecycle.State.STARTED)) {
        Text("…", modifier, color = dotColor, style = PocketType.footnote)
        return
    }
    val transition = rememberInfiniteTransition(label = "thinking_dots")
    // Each dot lifts and brightens in turn, 180 ms apart, then rests until the next cycle.
    val offsets = listOf(0, 180, 360).map { delayMillis ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = -3.5f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1100
                    0f at 0
                    -3.5f at 220
                    0f at 440
                    0f at 1100
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delayMillis),
            ),
            label = "dot$delayMillis",
        )
    }
    val alphas = listOf(0, 180, 360).map { delayMillis ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1100
                    0.35f at 0
                    1f at 220
                    0.35f at 440
                    0.35f at 1100
                },
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(delayMillis),
            ),
            label = "dotAlpha$delayMillis",
        )
    }

    Row(
        modifier = modifier.padding(horizontal = PocketSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        offsets.indices.forEach { i ->
            Box(
                Modifier
                    .size(3.5.dp)
                    .graphicsLayer {
                        translationY = offsets[i].value * density
                        alpha = alphas[i].value
                    }
                    .background(dotColor, CircleShape),
            )
        }
    }
}

@Composable
private fun ActivitySummaryRow(
    item: ActivityItem?,
    text: String,
    expanded: Boolean,
    showProgress: Boolean,
    onToggle: () -> Unit,
) {
    val colors = PocketColors.current
    val muted = colors.secondaryLabel
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, PocketMotion.spec(Token.Snappy), label = "activityChevron")
    Row(
        Modifier
            .fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse activity" else "Expand activity",
                onClick = onToggle,
            )
            .heightIn(min = LiquidGlassTokens.MinTouchTarget)
            .padding(vertical = PocketSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(activityIcon(item), contentDescription = null, modifier = Modifier.size(16.dp), tint = muted)
        Spacer(Modifier.width(PocketSpacing.sm))
        Text(text, Modifier.weight(1f), style = PocketType.footnote, color = muted)
        if (showProgress) {
            AnimatedThinkingDots(dotColor = muted)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevron },
            tint = colors.secondaryLabel,
        )
    }
}

private fun activityIcon(item: ActivityItem?): ImageVector {
    if (item == null) return Icons.Outlined.AutoAwesome
    val task = item.title
        .removePrefix("Running ")
        .removeSuffix(" completed")
        .trim()
    return when {
        item.isCommand || task.equals("Bash", ignoreCase = true) -> Icons.Outlined.Terminal
        task.equals("Write", ignoreCase = true) ||
            task.equals("Edit", ignoreCase = true) ||
            task.equals("NotebookEdit", ignoreCase = true) -> Icons.Outlined.Edit
        task.equals("Read", ignoreCase = true) -> Icons.Outlined.Description
        task.equals("Glob", ignoreCase = true) ||
            task.equals("Grep", ignoreCase = true) -> Icons.Outlined.Search
        task.contains("file", ignoreCase = true) -> Icons.Outlined.Description
        else -> Icons.Outlined.AutoAwesome
    }
}

@Composable
private fun ActivityExpandedDetail(item: ActivityItem?, detail: String) {
    val colors = PocketColors.current
    if (item?.isCommand == true) {
        Text(
            detail,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 25.dp, end = PocketSpacing.sm, bottom = PocketSpacing.sm)
                .clip(PocketShape.sm)
                .background(colors.codeSurface)
                .padding(PocketSpacing.sm),
            style = PocketType.codeSmall,
            color = colors.secondaryLabel,
        )
    } else {
        MarkdownText(
            markdown = detail,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 25.dp, end = PocketSpacing.sm, bottom = PocketSpacing.sm),
            color = colors.secondaryLabel,
        )
    }
}

private fun compactActivityText(item: ActivityItem): String =
    "${activityName(item)} · ${activityDetail(item).take(180).replace(Regex("\\s+"), " ").take(105)}"

private fun activityDetail(item: ActivityItem): String {
    if (item.title == "Think" && item.detail.contains("reasoning tokens processed", true)) {
        return "Reviewed the request and planned the next action"
    }
    return item.detail.ifBlank { item.title }
}

private fun activityName(item: ActivityItem): String = item.title
    .removePrefix("Running ")
    .removeSuffix(" completed")
    .replaceFirstChar { it.uppercase() }

@Composable
private fun rememberLiveElapsedSeconds(startedAtMillis: Long?, finishedAtMillis: Long?, isRunning: Boolean): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seconds by remember(startedAtMillis, finishedAtMillis, isRunning) {
        mutableLongStateOf(startedAtMillis?.let { elapsedWorkSeconds(it, finishedAtMillis, System.currentTimeMillis()) } ?: 0L)
    }
    LaunchedEffect(startedAtMillis, finishedAtMillis, isRunning, lifecycle) {
        if (startedAtMillis == null || finishedAtMillis != null || !isRunning) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                seconds = elapsedWorkSeconds(startedAtMillis, null, System.currentTimeMillis())
                delay(1_000)
            }
        }
    }
    return seconds
}

internal fun formatDuration(totalSeconds: Long): String = when {
    totalSeconds >= 3_600 -> "${totalSeconds / 3_600}h ${(totalSeconds % 3_600) / 60}m"
    totalSeconds >= 60 -> "${totalSeconds / 60}m ${totalSeconds % 60}s"
    else -> "${totalSeconds}s"
}

@Composable
private fun MessageBubble(message: ChatMessage, onRunInTerminal: (String) -> Unit, onOpenAttachment: (ChatAttachment) -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val colors = PocketColors.current
    var isCopied by remember(message.id) { mutableStateOf(false) }
    var resetJob by remember { mutableStateOf<Job?>(null) }

    val copyAction = {
        if (message.text.isNotBlank()) {
            clipboardManager.setText(AnnotatedString(message.text))
            try {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            } catch (_: Exception) {}
            isCopied = true
            resetJob?.cancel()
            resetJob = scope.launch {
                delay(2000L)
                isCopied = false
            }
        }
    }

    // User turns are compact bubbles with their actions on a long press; assistant replies sit
    // directly on the canvas as selectable content, with the duration and copy in a quiet footer
    // once the turn has finished.
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
    ) {
        if (message.fromUser) {
            UserBubble(message, onCopy = copyAction)
        } else {
            message.origin?.takeIf { it.startsWith("mod:") }?.let { origin ->
                Row(
                    modifier = Modifier.padding(start = PocketSpacing.xs, bottom = PocketSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(12.dp), tint = colors.secondaryLabel)
                    Spacer(Modifier.width(PocketSpacing.xs))
                    Text("From mod ${origin.removePrefix("mod:")}", style = PocketType.caption1, color = colors.secondaryLabel)
                }
            }
            SelectionContainer {
                MarkdownText(
                    markdown = message.text,
                    modifier = Modifier.padding(horizontal = PocketSpacing.xs),
                    color = MaterialTheme.colorScheme.onSurface,
                    onRunCode = onRunInTerminal,
                )
            }
        }
        if (message.attachments.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth(if (message.fromUser) 0.85f else 1f)
                    .padding(top = PocketSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = if (message.fromUser) Alignment.End else Alignment.Start,
            ) {
                message.attachments.forEach { attachment ->
                    AttachmentChip(attachment = attachment, onOpen = { onOpenAttachment(attachment) }, onRemove = null)
                }
            }
        }
        if (message.text.isNotBlank() && !message.fromUser && message.workedMillis > 0L) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Worked for ${formatDuration((message.workedMillis / 1_000L).coerceAtLeast(1L))}",
                    style = PocketType.footnote,
                    color = colors.secondaryLabel,
                    modifier = Modifier.padding(start = PocketSpacing.xs),
                )
                PocketIconButton(
                    icon = if (isCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                    contentDescription = if (isCopied) "Message copied to clipboard" else "Copy entire message",
                    onClick = copyAction,
                    tint = if (isCopied) colors.green else colors.secondaryLabel,
                    iconSize = 15.dp,
                )
            }
        }
    }
}

/** The person's own turn: a bubble whose long press (or accessibility action) offers Copy. */
@Composable
private fun UserBubble(message: ChatMessage, onCopy: () -> Unit) {
    val colors = PocketColors.current
    val banner = rememberBanner()
    val haptics = rememberHaptics()
    val anchor = rememberOverlayAnchor()
    var menuOpen by remember { mutableStateOf(false) }
    val canCopy = message.text.isNotBlank()
    val copy = {
        onCopy()
        banner("Copied", BannerKind.Success)
    }
    Box(Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .overlayAnchor(anchor)
                .clip(PocketShape.lg)
                .background(colors.groupedSurface)
                .then(
                    if (canCopy) {
                        Modifier
                            .pointerInput(message.id) {
                                detectTapGestures(onLongPress = { haptics.longPress(); menuOpen = true })
                            }
                            .semantics { customActions = listOf(CustomAccessibilityAction("Copy") { copy(); true }) }
                    } else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (message.activeSkill != null) {
                Row(
                    modifier = Modifier.padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(PocketSpacing.xs))
                    Text(
                        "Skill: ${message.activeSkill}",
                        style = PocketType.caption1.emphasized,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = message.text,
                style = PocketType.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
    GlassMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, anchor = anchor) {
        GlassMenuItem("Copy", copy, icon = Icons.Outlined.ContentCopy)
    }
}

/**
 * A file attached to a message. Pending attachments float over the chat as glass with a remove
 * button; sent ones sit in the conversation on a fill and open on tap.
 */
@Composable
private fun AttachmentChip(
    attachment: ChatAttachment,
    onOpen: (() -> Unit)?,
    onRemove: (() -> Unit)?,
) {
    val colors = PocketColors.current
    val isImage = attachment.mimeType.startsWith("image/")
    val body: @Composable () -> Unit = {
        Row(
            Modifier
                .heightIn(min = LiquidGlassTokens.MinTouchTarget)
                .padding(start = PocketSpacing.sm, end = if (onRemove == null) PocketSpacing.md else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolTile(if (isImage) Icons.Outlined.Image else Icons.Outlined.Description, if (isImage) colors.blue else colors.gray, size = 28.dp)
            Spacer(Modifier.width(PocketSpacing.sm))
            Column(Modifier.widthIn(max = 180.dp)) {
                Text(
                    attachment.displayName,
                    style = PocketType.footnote.emphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(formatFileSize(attachment.sizeBytes), style = PocketType.caption2, color = colors.secondaryLabel)
            }
            if (onRemove != null) {
                PocketIconButton(Icons.Rounded.Cancel, "Remove attachment", onRemove, tint = colors.tertiaryLabel, iconSize = 18.dp)
            }
        }
    }
    if (onRemove != null) {
        LiquidGlassSurface(shape = PocketShape.md, layerSource = LiquidGlassLayers.Background, role = GlassRoles.Chip) { body() }
    } else {
        Box(
            Modifier
                .clip(PocketShape.md)
                .background(colors.tertiaryFill)
                .then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open", onClick = onOpen) else Modifier),
        ) { body() }
    }
}

/** An inline card asking to allow or reject the agent's next action. */
@Composable
private fun ApprovalCard(request: ToolRequest, onApproval: (Boolean) -> Unit) {
    val colors = PocketColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(PocketShape.lg)
            .background(colors.groupedSurface)
            .padding(PocketSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymbolTile(Icons.Outlined.Warning, colors.orange)
            Spacer(Modifier.width(PocketSpacing.md))
            Text("Review this action", style = PocketType.headline, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(request.explanation, style = PocketType.subheadline, color = MaterialTheme.colorScheme.onSurface)
        if (request.affectedPaths.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(PocketShape.sm)
                    .background(colors.codeSurface)
                    .padding(PocketSpacing.md),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs),
            ) {
                request.affectedPaths.forEach { Text(it, style = PocketType.codeSmall, color = colors.secondaryLabel) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
            PocketButton("Reject", { onApproval(false) }, Modifier.weight(1f), style = PocketButtonStyle.Gray, fullWidth = true)
            PocketButton("Allow once", { onApproval(true) }, Modifier.weight(1f), style = PocketButtonStyle.Filled, fullWidth = true)
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> "%.1f KB".format(bytes / 1_024.0)
    else -> "%.1f MB".format(bytes / 1_048_576.0)
}

/** A local web preview: an address field for loopback URLs over a sandboxed WebView. */
@Composable
private fun PreviewTab(ready: Boolean, url: String?, project: com.jarves.mh.model.Project?) {
    var address by rememberSaveable(url) { mutableStateOf(if (ready) url.orEmpty() else "") }
    var activeUrl by rememberSaveable(url) { mutableStateOf(if (ready) url else null) }
    var addressError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var navigationRequest by remember { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val colors = PocketColors.current

    val navigate = {
        val normalized = normalizePreviewUrl(address)
        if (normalized == null) {
            addressError = "Use a local URL such as localhost:3000"
        } else {
            addressError = null
            address = normalized
            activeUrl = normalized
            navigationRequest++
        }
        Unit
    }

    LaunchedEffect(ready, url) {
        if (ready && !url.isNullOrBlank() && activeUrl == null) {
            normalizePreviewUrl(url)?.let {
                address = it
                activeUrl = it
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PocketTextField(
            value = address,
            onValueChange = {
                address = it
                addressError = null
            },
            modifier = Modifier.padding(horizontal = ListInset, vertical = PocketSpacing.sm),
            placeholder = "localhost:3000",
            error = addressError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { navigate() }),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (loading) {
                        Box(Modifier.size(LiquidGlassTokens.MinTouchTarget), contentAlignment = Alignment.Center) {
                            ProgressRing(progress = null, size = 16.dp, strokeWidth = 2.dp)
                        }
                    } else {
                        PocketIconButton(
                            Icons.Outlined.Refresh,
                            "Refresh preview",
                            { webView?.reload() ?: navigate() },
                            tint = colors.secondaryLabel,
                            enabled = address.isNotBlank(),
                        )
                    }
                    PocketIconButton(Icons.AutoMirrored.Outlined.ArrowForward, "Open URL", navigate)
                }
            },
        )
        val targetUrl = activeUrl
        if (targetUrl == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    Icons.Outlined.Language,
                    "Preview not running",
                    message = "Enter a localhost URL above, or start a local web server in the project Terminal.",
                )
            }
        } else {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        project?.let { owner ->
                            val base = java.io.File(context.filesDir, "workspaces/${owner.id}").canonicalFile
                            val root = if (owner.rootPath.isBlank()) base else java.io.File(base, owner.rootPath).canonicalFile
                            if (root.toPath().startsWith(base.toPath()))
                                com.jarves.mh.runtime.PreviewInspectionHost.attach(this, root, "/workspace/${owner.slug}")
                        }
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loading = newProgress < 100
                            }
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                                if (message != null) com.jarves.mh.runtime.PreviewInspectionHost.console(this@apply, message)
                                return true
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val target = request?.url ?: return true
                                if (!target.isLoopbackPreviewUrl()) {
                                    addressError = "External navigation is blocked in project preview"
                                    return true
                                }
                                address = target.toString()
                                return false
                            }

                            override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                                if (view != null) com.jarves.mh.runtime.PreviewInspectionHost.navigation(view)
                                pageUrl?.takeIf { normalizePreviewUrl(it) != null }?.let { address = it }
                            }

                            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                val target = request?.url ?: return blockedPreviewResponse()
                                return if (target.isLoopbackPreviewUrl()) null else blockedPreviewResponse()
                            }
                        }
                        tag = navigationRequest to targetUrl
                        loadUrl(targetUrl)
                    }
                },
                update = { current ->
                    webView = current
                    val request = navigationRequest to targetUrl
                    if (current.tag != request) {
                        current.tag = request
                        current.loadUrl(targetUrl)
                    }
                },
                onRelease = { current ->
                    com.jarves.mh.runtime.PreviewInspectionHost.detach(current)
                    current.stopLoading()
                    current.webChromeClient = null
                    current.destroy()
                    if (webView === current) webView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun normalizePreviewUrl(input: String): String? {
    val raw = input.trim()
    if (raw.isBlank()) return null
    val withScheme = if ("://" in raw) raw else "http://$raw"
    val parsed = runCatching { Uri.parse(withScheme) }.getOrNull() ?: return null
    if (!parsed.isLoopbackPreviewUrl() || parsed.host.isNullOrBlank()) return null
    return if (parsed.host == "0.0.0.0") {
        parsed.buildUpon().encodedAuthority(
            buildString {
                append("127.0.0.1")
                if (parsed.port >= 0) append(":${parsed.port}")
            },
        ).build().toString()
    } else {
        parsed.toString()
    }
}

private fun Uri.isLoopbackPreviewUrl(): Boolean =
    scheme in setOf("data", "blob", "about") ||
        (scheme in setOf("http", "https", "ws", "wss") && host in setOf("127.0.0.1", "localhost", "0.0.0.0"))

private fun blockedPreviewResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))

/** Creates an empty cache file for the camera app to write into, shared through our FileProvider. */
private fun createCameraPhotoUri(context: Context): Uri {
    val folder = java.io.File(context.cacheDir, "camera").apply { mkdirs() }
    // Photos are copied into the project as attachments, so older captures are no longer needed.
    val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000L
    folder.listFiles()?.filter { it.lastModified() < staleBefore }?.forEach { it.delete() }
    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val file = java.io.File(folder, "photo-$stamp.jpg")
    return androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}
