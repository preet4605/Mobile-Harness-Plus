package com.jarves.mh.ui

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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import com.jarves.mh.ui.kit.CappedTextScale
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
}

/** The composer never gets shorter than this, so it stays an easy target while typing. */
private val ComposerMinHeight = 52.dp

/**
 * The workspace's floating bar, one row: a glass back button, the title centred between the edges
 * with a second line under it, and glass actions on the right. Tapping the title ([onTitleClick])
 * opens the view menu. Content scrolls beneath the bar and softens into the canvas once anything
 * is underneath; the bar has no fill or hairline.
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
    CappedTextScale {
        val colors = PocketColors.current
        Box(modifier.fillMaxWidth()) {
            ScrollEdgeEffect(Modifier.matchParentSize(), fromTop = true, scrim = 1.3f, strength = scrolled)
            Column(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = PocketSpacing.xs, bottom = PocketSpacing.sm),
            ) {
                BarRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(BarHeight)
                        .padding(horizontal = ListInset),
                    leading = { GlassToolbarButton(Icons.Outlined.ArrowBackIosNew, backLabel, onBack) },
                    center = {
                        // Wraps the title, so BarRow can centre it on the row.
                        Column(
                            Modifier
                                .then(if (titleAnchor != null) Modifier.overlayAnchor(titleAnchor) else Modifier)
                                .then(
                                    if (onTitleClick != null || onTitleLongPress != null) {
                                        Modifier.combinedClickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            role = Role.Button,
                                            onClickLabel = "Change view",
                                            onLongClick = onTitleLongPress,
                                            onClick = { onTitleClick?.invoke() },
                                        )
                                    } else {
                                        Modifier
                                    },
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
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
                                    Text(" · $subtitle", style = PocketType.caption1, color = colors.secondaryLabel, maxLines = 1)
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
 * centred on the row: both sides reserve the width of the wider edge slot, so the title never
 * drifts toward the narrower one, and it ellipsizes once it fills that balanced gap.
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
        val loose = constraints.copy(minWidth = 0)
        val leadingPlaceable = measurables[0].measure(loose)
        val trailingPlaceable = measurables[2].measure(loose)
        val gap = PocketSpacing.sm.roundToPx()
        val width = constraints.maxWidth
        val reserve = maxOf(leadingPlaceable.width, trailingPlaceable.width) + gap
        val start = reserve
        val end = width - reserve
        val centerPlaceable = measurables[1].measure(loose.copy(maxWidth = (end - start).coerceAtLeast(0)))
        val height = maxOf(leadingPlaceable.height, centerPlaceable.height, trailingPlaceable.height).coerceIn(constraints.minHeight, constraints.maxHeight)
        val x = (width - centerPlaceable.width) / 2
        layout(width, height) {
            leadingPlaceable.placeRelative(0, (height - leadingPlaceable.height) / 2)
            centerPlaceable.placeRelative(x, (height - centerPlaceable.height) / 2)
            trailingPlaceable.placeRelative(width - trailingPlaceable.width, (height - trailingPlaceable.height) / 2)
        }
    }
}

/** A small accent capsule with a count, over the top end of a toolbar symbol. */
@Composable
private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics { }
            .defaultMinSize(minWidth = 14.dp, minHeight = 14.dp)
            .clip(PocketShape.capsule)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else "$count", style = PocketType.caption2.emphasized, color = Color.White)
    }
}

/**
 * The workspace's views as the same floating glass tab bar the main screens use, in the same place
 * at the bottom. It steps aside while the keyboard is up. [onFootprint] reports the distance from
 * the bar's top to the bottom of the screen, so the composer and the views clear it.
 */
@Composable
private fun WorkspaceTabBar(
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
                .padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.sm),
        ) {
            FloatingTabBar(
                tabs = WorkspaceTab.entries.map { TabItem(it.label, it.icon, badge = if (it == WorkspaceTab.FILES) changeCount else 0) },
                selectedIndex = selected.ordinal,
                onSelect = { onSelect(WorkspaceTab.entries[it]) },
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
    val listState = rememberLazyListState()
    var showChats by rememberSaveable { mutableStateOf(false) }
    val chatsAnchor = rememberOverlayAnchor()
    val (barHeight, onBarSize) = rememberBarHeight()

    LaunchedEffect(state.readOnlyChatId) {
        if (state.readOnlyMessages.isNotEmpty()) listState.scrollToItem(state.readOnlyMessages.lastIndex)
    }

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
    onApproval: (Boolean) -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (WorkspaceEntry) -> Unit,
    onInstallApk: (WorkspaceEntry) -> Unit = {},
    onCloseFile: () -> Unit,
    onUndoChanges: () -> Unit,
    onKeepChanges: () -> Unit,
    onUndoFileChange: (String) -> Unit,
    onKeepFileChange: (String) -> Unit,
    onCreateChat: () -> Unit,
    onSwitchChat: (String) -> Unit,
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
    onUseSuggestedProjectRoot: () -> Unit,
    onExportProject: (Uri) -> Unit,
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
    val exportProjectLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
        onResult = { uri -> if (uri != null) onExportProject(uri) },
    )
    val attachmentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = onAddAttachments,
    )
    val chatItemCount = state.messages.size +
        (if (state.liveProcess.isNotEmpty() || state.liveThinking) 1 else 0) +
        (if (state.pendingApproval != null) 1 else 0)
    // Keyed by project and chat, so a chat opens on its newest message rather than on the first one.
    val chatListState = key(state.activeProject?.id, state.activeChatId) {
        rememberLazyListState(initialFirstVisibleItemIndex = (chatItemCount - 1).coerceAtLeast(0))
    }
    val filesListState = rememberLazyListState()
    var userScrolledUp by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.activeChatId) {
        userScrolledUp = false
        if (chatItemCount > 0) chatListState.scrollToItem(chatItemCount - 1)
    }

    // When the user actively scrolls/touches the screen, detect if they scrolled up to read thinking/messages.
    LaunchedEffect(chatListState.isScrollInProgress) {
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
            message = "It can delete files, rewrite Git history, or change the project significantly.",
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
    val memoryCount = state.contextMemory.entries.size

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
                                )
                                WorkspaceTab.FILES -> FilesTab(
                                    files = state.workspaceFiles,
                                    loading = state.filesLoading,
                                    suggestedProjectRoot = state.suggestedProjectRoot,
                                    onRefresh = onRefreshFiles,
                                    onOpenFile = onOpenFile,
                                    onInstallApk = onInstallApk,
                                    onUseSuggestedProjectRoot = onUseSuggestedProjectRoot,
                                    onExport = {
                                        exportProjectLauncher.launch("${state.activeProject?.slug ?: "project"}.zip")
                                    },
                                    listState = filesListState,
                                    topClearance = top,
                                    bottomClearance = bottomBar,
                                    changes = reviewable,
                                    onReviewChanges = { showChanges = true },
                                    onUndoChanges = onUndoChanges,
                                    onKeepChanges = onKeepChanges,
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
                                    PreviewTab(state.previewReady, state.previewUrl)
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
                        onTitleLongPress = { banner(state.activeProject?.name.orEmpty(), BannerKind.Info) },
                        actions = {
                            GlassToolbarGroup {
                                Box {
                                    GlassToolbarItem(
                                        Icons.Outlined.Psychology,
                                        if (memoryCount > 0) "Persistent memory, $memoryCount entries" else "Persistent memory",
                                        onOpenMemoryViewer,
                                        tint = if (memoryCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                    if (memoryCount > 0) {
                                        CountBadge(memoryCount, Modifier.align(Alignment.TopEnd))
                                    }
                                }
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
                        selected = selectedTab,
                        onSelect = selectView,
                        onFootprint = { if (it != tabBarFootprint) tabBarFootprint = it },
                        changeCount = reviewable.size,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    GlassMenu(expanded = showViews, onDismiss = { showViews = false }, anchor = viewsAnchor) {
                        WorkspaceTab.entries.forEach { tab ->
                            GlassMenuItem(tab.label, { selectView(tab) }, checked = tab == selectedTab)
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

/** A file pushed over the workspace: Markdown rendered, anything else as numbered code lines. */
@Composable
private fun FileViewerScreen(
    filePath: String,
    content: String?,
    loading: Boolean,
    onClose: () -> Unit,
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
    val showsCode = !loading && content != null && !isMarkdown

    Box(Modifier.fillMaxSize().background(if (showsCode) colors.codeSurface else MaterialTheme.colorScheme.background)) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ProgressRing(progress = null)
            }
            content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.Description, "No content", message = "The file could not be read.")
            }
            isMarkdown -> LazyColumn(
                modifier = Modifier.fillMaxSize().hostBackdropSource(),
                state = listState,
                contentPadding = PaddingValues(start = PocketSpacing.lg, top = top, end = PocketSpacing.lg, bottom = bottom),
            ) {
                item { MarkdownText(markdown = content, color = MaterialTheme.colorScheme.onSurface) }
            }
            else -> {
                val lines = remember(content) { content.lines() }
                LazyColumn(
                    modifier = Modifier.fillMaxSize().hostBackdropSource(),
                    state = listState,
                    contentPadding = PaddingValues(top = top, bottom = bottom),
                ) {
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
            onBack = onClose,
            backLabel = "Close file",
            scrolled = { if (listState.canScrollBackward) 1f else 0f },
            modifier = Modifier.onSizeChanged(onBarSize),
            actions = {
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
            },
        )
    }
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
    onUseSuggestedProjectRoot: () -> Unit,
    onExport: () -> Unit,
    listState: LazyListState,
    topClearance: Dp,
    bottomClearance: Dp,
    changes: List<ChangeItem>,
    onReviewChanges: () -> Unit,
    onUndoChanges: () -> Unit,
    onKeepChanges: () -> Unit,
) {
    val colors = PocketColors.current
    val accent = MaterialTheme.colorScheme.primary
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
    val directChildCounts = files.filter { candidate ->
        candidate.path.contains('/')
    }.groupingBy { candidate -> candidate.path.substringBeforeLast('/') }.eachCount()
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
                SectionHeader("Files", Modifier.weight(1f))
                if (expandedDirectories.isNotEmpty()) {
                    SheetTextButton("Collapse all", { expandedDirectories = emptyList() })
                }
                if (!loading && files.any { !it.isDirectory }) {
                    PocketIconButton(Icons.Outlined.Download, "Export project as ZIP", onExport)
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
                FileRow(
                    entry = entry,
                    expanded = entry.path in expandedSet,
                    childCount = directChildCounts[entry.path] ?: 0,
                    onClick = {
                        val isApk = !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)
                        if (entry.isDirectory) {
                            expandedDirectories = if (entry.path in expandedSet) {
                                expandedDirectories.filterNot { it == entry.path || it.startsWith("${entry.path}/") }
                            } else {
                                expandedDirectories + entry.path
                            }
                        } else if (isApk) {
                            onInstallApk(entry)
                        } else {
                            onOpenFile(entry)
                        }
                    },
                )
            }
        }
    }
}

/** One entry of the tree: a disclosure chevron for folders, indented by depth. */
@Composable
private fun FileRow(entry: WorkspaceEntry, expanded: Boolean, childCount: Int, onClick: () -> Unit) {
    val colors = PocketColors.current
    val isApk = !entry.isDirectory && entry.name.endsWith(".apk", ignoreCase = true)
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
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = when {
                    entry.isDirectory -> if (expanded) "Collapse folder" else "Expand folder"
                    isApk -> "Install"
                    else -> "Open"
                },
                onClick = onClick,
            )
            .heightIn(min = LiquidGlassTokens.MinTouchTarget)
            .padding(start = ListInset + (entry.depth * 16).dp, end = ListInset - 2.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.tertiaryLabel,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevron },
            )
        } else {
            Spacer(Modifier.width(18.dp))
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
            } else {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.tertiaryLabel,
                    modifier = Modifier.size(18.dp),
                )
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
    readOnly: Boolean = false,
    readOnlyBlocked: Boolean = false,
    loading: Boolean = false,
    onContinueHere: () -> Unit = {},
    slashCommands: List<SlashCommand> = emptyList(),
    slashCommandsVisible: Boolean = false,
    skills: List<SkillInfo> = emptyList(),
    onPromptChanged: (String) -> Unit = {},
    onOpenInspector: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
    subagentsCount: Int = 0,
    tasksCount: Int = 0,
    mentionMenuVisible: Boolean = false,
    mentionFiles: List<WorkspaceEntry> = emptyList(),
    isStopping: Boolean = false,
    activeChatId: String? = null,
    topClearance: Dp = 0.dp,
    bottomBarClearance: Dp = 0.dp,
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
    val chatScope = rememberCoroutineScope()
    // True while the newest item (message, live panel, or approval card) is on screen.
    val readerAtBottom by remember {
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
            if (liveProcess.isNotEmpty() || thinkingActive) {
                item(key = "live-claude-process") {
                    LiveClaudeProcess(
                        processItems = liveProcess,
                        isRunning = isRunning,
                        startedAtMillis = taskStartedAtMillis,
                        finishedAtMillis = taskFinishedAtMillis,
                        thinkingActive = thinkingActive,
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
                    .padding(start = PocketSpacing.lg, top = PocketSpacing.sm, end = PocketSpacing.lg, bottom = PocketSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (activeCount > 0) ActivityCapsule(activeCount, onOpenInspector)
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

                if (slashCommandsVisible) {
                    SlashCommandMenu(
                        commands = slashCommands,
                        skills = skills,
                        onSelect = { cmd ->
                            if (cmd.isLocalOnly && cmd.parameterHint == null) {
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
                    attachEnabled = !isRunning && pendingAttachments.size < 5,
                    hasAttachments = pendingAttachments.isNotEmpty(),
                    activeCount = activeCount,
                    onSend = {
                        onSend(inputState.text)
                        setInput(TextFieldValue(""))
                    },
                    onStop = onStop,
                    onAttach = onAttach,
                    onToggleCommands = {
                        val newText = if (inputState.text.startsWith("/")) "" else "/"
                        setInput(TextFieldValue(newText, TextRange(newText.length)))
                    },
                    onOpenSkills = onOpenSkills,
                    onOpenInspector = onOpenInspector,
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
    onToggleCommands: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenInspector: () -> Unit,
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
                        .padding(vertical = 15.dp)
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
                Box(Modifier.padding(vertical = PocketSpacing.xs)) {
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
                    } else {
                        ComposerActionButton(
                            contentDescription = "Send",
                            container = if (canSend) accent else colors.secondaryFill,
                            enabled = canSend,
                            onClick = onSend,
                        ) {
                            Icon(
                                Icons.Outlined.ArrowUpward,
                                contentDescription = null,
                                tint = if (canSend) Color.White else colors.secondaryLabel,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    GlassMenu(expanded = toolsOpen, onDismiss = { toolsOpen = false }, anchor = toolsAnchor) {
        GlassMenuItem("Attach files", onAttach, icon = Icons.Outlined.AttachFile, enabled = attachEnabled)
        GlassMenuItem("Commands", onToggleCommands, icon = Icons.Outlined.Code)
        GlassMenuItem("Skills", onOpenSkills, icon = Icons.Outlined.AutoAwesome)
        GlassMenuDivider()
        GlassMenuItem(if (activeCount > 0) "Activity · $activeCount running" else "Activity", onOpenInspector, icon = Icons.Outlined.Layers)
    }
}

/** A 34 dp filled circle inside a 44 dp touch area that dips slightly while pressed. */
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
    @Suppress("UNUSED_PARAMETER") finishedAtMillis: Long?,
    thinkingActive: Boolean,
) {
    val elapsedSeconds = startedAtMillis?.let { rememberLiveElapsedSeconds(it).toLong() } ?: 0L
    ClaudeActivityDisclosure(
        items = processItems,
        headline = activityHeadline(processItems, elapsedSeconds, thinkingActive),
        isRunning = isRunning,
    )
}

@Composable
private fun WorkBlockCard(message: ChatMessage) {
    val seconds = (message.workedMillis / 1_000L).coerceAtLeast(1L)
    Column {
        ClaudeActivityDisclosure(
            items = message.workItems,
            headline = activityHeadline(message.workItems, seconds, message.workItems.isEmpty()),
        )
        if (message.workItems.lastOrNull()?.title?.startsWith("Task stopped") == true) {
            Text(
                text = "Worked for ${formatDuration(seconds)}",
                modifier = Modifier.padding(start = 29.dp, bottom = 6.dp),
                style = PocketType.footnote,
                color = PocketColors.current.secondaryLabel,
            )
        }
    }
}

@Composable
private fun ClaudeActivityDisclosure(
    items: List<ActivityItem>,
    headline: String,
    isRunning: Boolean = false,
) {
    var expandedItems by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = PocketSpacing.xs, vertical = 6.dp)) {
        if (items.isEmpty()) {
            ActivitySummaryRow(
                item = null,
                text = headline,
                expanded = 0 in expandedItems,
                showProgress = isRunning,
                onToggle = {
                    expandedItems = if (0 in expandedItems) expandedItems - 0 else expandedItems + 0
                },
            )
            ActivityDetailReveal(visible = 0 in expandedItems) {
                ActivityExpandedDetail(null, "Reviewing the request and planning the next action.")
            }
        } else {
            items.forEachIndexed { index, item ->
                ActivitySummaryRow(
                    item = item,
                    text = compactActivityText(item),
                    expanded = index in expandedItems,
                    showProgress = isRunning && !item.isComplete,
                    onToggle = {
                        expandedItems = if (index in expandedItems) expandedItems - index else expandedItems + index
                    },
                )
                ActivityDetailReveal(visible = index in expandedItems) {
                    ActivityExpandedDetail(item, activityDetail(item))
                }
            }
        }
    }
}

/** Expanded activity detail grows open and folds shut on a spring instead of popping in. */
@Composable
private fun ActivityDetailReveal(visible: Boolean, content: @Composable () -> Unit) {
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = if (expanded) "Collapse activity" else "Expand activity",
                onClick = onToggle,
            )
            .heightIn(min = 32.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(activityIcon(item), contentDescription = null, modifier = Modifier.size(16.dp), tint = muted)
        Spacer(Modifier.width(9.dp))
        Text(text, Modifier.weight(1f), style = PocketType.footnote, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (showProgress) {
            AnimatedThinkingDots(dotColor = muted)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = chevron },
            tint = colors.tertiaryLabel,
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

private fun compactActivityText(item: ActivityItem): String = "${activityName(item)} · ${activityDetail(item).replace(Regex("\\s+"), " ").take(105)}"

private fun activityDetail(item: ActivityItem): String {
    if (item.title == "Think" && item.detail.contains("reasoning tokens processed", true)) {
        return "Reviewed the request and planned the next action"
    }
    return item.detail.ifBlank { item.title }
}

private fun activityHeadline(items: List<ActivityItem>, seconds: Long, thinking: Boolean): String {
    val latest = items.lastOrNull()
    if (latest == null) return "Think · Analyzing the request · ${formatDuration(seconds)}"
    if (thinking && latest.title == "Think") return "Think · ${latest.detail} · ${formatDuration(seconds)}"
    val detail = latest.detail.replace(Regex("\\s+"), " ").trim().ifBlank { latest.title }
    return "${activityName(latest)} · ${detail.take(100)} · ${formatDuration(seconds)}"
}

private fun activityName(item: ActivityItem): String = item.title
    .removePrefix("Running ")
    .removeSuffix(" completed")
    .replaceFirstChar { it.uppercase() }

@Composable
private fun rememberLiveElapsedSeconds(startedAtMillis: Long): Int {
    var seconds by remember(startedAtMillis) {
        mutableIntStateOf(((System.currentTimeMillis() - startedAtMillis) / 1000L).toInt().coerceAtLeast(0))
    }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            delay(1_000)
            seconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).toInt().coerceAtLeast(0)
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
                    tint = if (isCopied) colors.green else colors.tertiaryLabel,
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
private fun PreviewTab(ready: Boolean, url: String?) {
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
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                loading = newProgress < 100
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
