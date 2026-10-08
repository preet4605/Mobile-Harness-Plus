package com.jarves.mh.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jarves.mh.BuildConfig
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ClaudeAuthMode
import com.jarves.mh.model.DSH_PROTOCOL_PROVIDERS
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.ClaudeAuthState
import com.jarves.mh.runtime.ClaudeAuthStatusState
import com.jarves.mh.runtime.RuntimeExecutionService
import com.jarves.mh.runtime.RuntimeSetupService
import com.jarves.mh.runtime.supportsArm64Runtime
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.GlassToolbarButton
import com.jarves.mh.ui.kit.LargeTitleBar
import com.jarves.mh.ui.kit.LazyGroupRow
import com.jarves.mh.ui.kit.ListIconTile
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.LocalBanner
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketIconButton
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SearchField
import com.jarves.mh.ui.kit.SheetDetent
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.SymbolTile
import com.jarves.mh.ui.kit.largeTitleBarPadding
import com.jarves.mh.ui.kit.listRowInset
import com.jarves.mh.ui.kit.rememberBanner
import com.jarves.mh.ui.kit.rememberHaptics
import com.jarves.mh.ui.kit.rememberTitleCollapse
import com.jarves.mh.ui.theme.AppThemeMode
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
import com.jarves.mh.ui.theme.glass.ScrollEdgeEffect
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import com.jarves.mh.ui.theme.isTransitionTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Startup, setup and sign-in screens. Every page shares one frame: a glass bar (back, theme)
 * over a scrolling page that is the shared backdrop source, a centred hero symbol and title,
 * grouped sections, and the page's actions pinned at the bottom over a soft scroll edge.
 * Steps move with the push transition; every permission, launcher and validation path is
 * unchanged from the previous screens.
 */

private val HeroSymbol = 72.dp
private val RowIconGap = 14.dp
private val ActionsFade = 24.dp

// region Frame

/**
 * A setup page. [content] scrolls under the bar (its inline [title] fades in once the hero has
 * scrolled away) and clears the pinned [actions], which are measured, not guessed, and follow
 * the keyboard.
 */
@Composable
private fun OnboardingScaffold(
    title: String,
    onToggleTheme: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    onBack: (() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PocketColors.current
    val canvas = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val collapse by rememberTitleCollapse(scrollState)
    var actionsHeight by remember { mutableStateOf(0.dp) }
    val bottomClearance = if (actions != null) actionsHeight else WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val leading: (@Composable RowScope.() -> Unit)? = onBack?.let { back ->
        { GlassToolbarButton(Icons.Outlined.ArrowBackIosNew, "Back", back) }
    }
    val trailing: (@Composable RowScope.() -> Unit)? = onToggleTheme?.let { toggle ->
        {
            GlassToolbarButton(
                if (colors.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                if (colors.isDark) "Use light appearance" else "Use dark appearance",
                toggle,
            )
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .hostBackdropSource()
                .verticalScroll(scrollState)
                .padding(top = largeTitleBarPadding() + PocketSpacing.md, bottom = bottomClearance + PocketSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xxl),
            content = content,
        )
        LargeTitleBar(title = title, collapse = { collapse }, leading = leading, trailing = trailing)
        if (actions != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding(),
            ) {
                // Content softens into the edge, then the canvas settles under the buttons so a
                // disabled (translucent) button never shows the page through it.
                ScrollEdgeEffect(Modifier.matchParentSize(), fromTop = false) { if (scrollState.canScrollForward) 1f else 0f }
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            val fade = ActionsFade.toPx()
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, canvas), startY = 0f, endY = fade))
                        },
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { actionsHeight = with(density) { it.height.toDp() } }
                        .navigationBarsPadding()
                        .padding(start = PocketSpacing.xl, end = PocketSpacing.xl, top = ActionsFade, bottom = PocketSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs),
                    content = actions,
                )
            }
        }
    }
}

/** Centred symbol, optional step label, title and explanation at the top of a page. */
@Composable
private fun OnboardingHero(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    stableMessage: Boolean = false,
    symbol: @Composable () -> Unit,
) {
    val colors = PocketColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PocketSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        symbol()
        Spacer(Modifier.height(PocketSpacing.xl))
        if (eyebrow != null) {
            Text(eyebrow, style = PocketType.footnote.emphasized, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(PocketSpacing.xs))
        }
        Text(
            title,
            style = PocketType.title1,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (message != null) {
            Spacer(Modifier.height(PocketSpacing.sm))
            Text(
                message,
                style = PocketType.body,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
                // Live status text keeps two lines so the page doesn't jump as it changes.
                minLines = if (stableMessage) 2 else 1,
                maxLines = if (stableMessage) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A centred footnote between sections. */
@Composable
private fun PageNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = PocketType.footnote,
        color = PocketColors.current.secondaryLabel,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PocketSpacing.xxl),
    )
}

/** Mobile Harness's symbol: the terminal glyph on an accent tile with a soft top light. */
@Composable
private fun BrandMark(modifier: Modifier = Modifier, size: Dp = HeroSymbol) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier
            .size(size)
            .clip(ContinuousRoundedShape(size * 0.24f))
            .background(Brush.verticalGradient(listOf(lerp(primary, Color.White, 0.22f), primary)))
            .semantics { contentDescription = "Mobile Harness" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Outlined.Terminal, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.56f))
    }
}

/** Letters on a coloured tile: the mark of an agent or provider. */
@Composable
private fun MonogramTile(text: String, color: Color, size: Dp = ListIconTile) {
    val large = size > ListIconTile
    val style = when {
        large && text.length > 1 -> PocketType.title2
        large -> PocketType.title1
        text.length > 1 -> PocketType.caption2.emphasized
        else -> PocketType.subheadline.emphasized
    }
    Box(
        Modifier
            .size(size)
            .clip(ContinuousRoundedShape(size * 0.24f))
            .background(color)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = style, color = Color.White, maxLines = 1, softWrap = false)
    }
}

/** A small tinted capsule label ("Recommended", "Beta"). */
@Composable
private fun Tag(text: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Text(
        text,
        style = PocketType.caption1.emphasized,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(PocketShape.capsule)
            .background(color.copy(alpha = if (PocketColors.current.isDark) 0.22f else 0.12f))
            .padding(horizontal = PocketSpacing.sm, vertical = PocketSpacing.xxs),
    )
}

/**
 * A choice in a grouped list: mark, title with an optional [tag] beside it, subtitle, and a
 * check while [selected]. [multiple] reads as a checkbox, otherwise as one of a set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    tag: String? = null,
    multiple: Boolean = false,
    leading: @Composable () -> Unit,
) {
    val colors = PocketColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateColorAsState(
        if (pressed) colors.tertiaryFill else Color.Transparent,
        PocketMotion.spec(Token.Quick),
        label = "choiceHighlight",
    )
    val select = when {
        onClick == null -> Modifier
        multiple -> Modifier.toggleable(selected, interaction, indication = null, role = Role.Checkbox) { onClick() }
        else -> Modifier.selectable(selected, interaction, indication = null, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        Modifier
            .listRowInset(ListInset + ListIconTile + RowIconGap)
            .fillMaxWidth()
            .background(highlight)
            .then(select)
            .heightIn(min = 60.dp)
            .padding(start = ListInset, end = ListInset - 2.dp, top = PocketSpacing.sm, bottom = PocketSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(ListIconTile), contentAlignment = Alignment.Center) { leading() }
        Spacer(Modifier.width(RowIconGap))
        Column(Modifier.weight(1f)) {
            // The tag sits beside the title, or under it when both don't fit on one line.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.xxs),
            ) {
                Text(
                    title,
                    style = PocketType.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (tag != null) Tag(tag, modifier = Modifier.align(Alignment.CenterVertically))
            }
            Text(subtitle, style = PocketType.subheadline, color = colors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(PocketSpacing.sm))
        // A fixed slot, so the text never shifts when the check appears.
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

private fun agentMonogram(agent: AgentKind): String = when (agent) {
    AgentKind.CLAUDE_CODE -> "CC"
    AgentKind.DEEPSEEK_HARNESS -> "DS"
    AgentKind.ANTIGRAVITY -> "AG"
}

@Composable
private fun agentTint(agent: AgentKind): Color {
    val colors = PocketColors.current
    return when (agent) {
        AgentKind.CLAUDE_CODE -> colors.orange
        AgentKind.DEEPSEEK_HARNESS -> colors.indigo
        AgentKind.ANTIGRAVITY -> colors.blue
    }
}

private fun providerMonogram(provider: ProviderKind): String = when (provider) {
    ProviderKind.CLAUDE -> "C"
    ProviderKind.ANTHROPIC -> "A"
    ProviderKind.LLM_ROUTER -> "OR"
    ProviderKind.DEEPSEEK -> "DS"
    ProviderKind.KIMI -> "K"
    ProviderKind.OPENCODE_ZEN -> "Z"
    ProviderKind.NVIDIA_NIM -> "NV"
    ProviderKind.ANTIGRAVITY_SERVER -> "AG"
    ProviderKind.CUSTOM -> "<>"
}

@Composable
private fun providerTint(provider: ProviderKind): Color {
    val colors = PocketColors.current
    return when (provider) {
        ProviderKind.CLAUDE -> colors.orange
        ProviderKind.ANTHROPIC -> colors.brown
        ProviderKind.LLM_ROUTER -> colors.blue
        ProviderKind.DEEPSEEK -> colors.indigo
        ProviderKind.KIMI -> colors.purple
        ProviderKind.OPENCODE_ZEN -> colors.teal
        ProviderKind.NVIDIA_NIM -> colors.green
        ProviderKind.ANTIGRAVITY_SERVER -> colors.cyan
        ProviderKind.CUSTOM -> colors.gray
    }
}

/** The three coding agents as one choice list. */
@Composable
private fun AgentChoiceSection(
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    header: String? = null,
    footer: String? = null,
) {
    val haptics = rememberHaptics()
    ListSection(header = header, footer = footer) {
        AgentKind.entries.forEach { agent ->
            ChoiceRow(
                title = agent.title,
                subtitle = "${agent.subtitle} · ${agent.downloadNote}",
                selected = agent == selected,
                tag = if (agent == AgentKind.DEEPSEEK_HARNESS) "Recommended" else null,
                onClick = {
                    haptics.selection()
                    onSelect(agent)
                },
            ) { MonogramTile(agentMonogram(agent), agentTint(agent)) }
        }
    }
}

/** Switch the coding agent from a sign-in step, in case its service is unavailable. */
@Composable
private fun AgentSwitchSheet(
    visible: Boolean,
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Coding agent",
        detents = listOf(SheetDetent.Fit),
        trailing = { SheetTextButton("Cancel", onDismiss) },
    ) {
        Text(
            "Switch if the current service is unavailable. Your existing login and credentials stay saved.",
            style = PocketType.subheadline,
            color = PocketColors.current.secondaryLabel,
            modifier = Modifier.padding(start = ListInset * 2, end = ListInset * 2, top = PocketSpacing.xs, bottom = PocketSpacing.lg),
        )
        AgentChoiceSection(selected = selected, onSelect = onSelect)
        Spacer(Modifier.height(PocketSpacing.xxl))
    }
}

// endregion

// region Startup

@Composable
internal fun StartupLoadingScreen(
    state: AppUiState,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
) {
    val view = LocalView.current
    // Runtime download + install can take 10+ minutes; keep the screen on while this
    // screen is visible. Released automatically when setup finishes or leaves.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val messages = remember {
        listOf(
            "Setting up your workspace",
            "Preparing your coding tools",
            "Almost ready",
        )
    }
    var messageIndex by remember(state.startupStage) { mutableIntStateOf(0) }
    LaunchedEffect(messages) {
        while (true) {
            delay(3_000)
            messageIndex = (messageIndex + 1) % messages.size
        }
    }
    val logoTransition = rememberInfiniteTransition(label = "startup logo")
    val logoPulse by logoTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "startup logo pulse",
    )
    val colors = PocketColors.current

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(
                Modifier.graphicsLayer {
                    val pulse = if (PocketMotion.reduced) 1f else logoPulse
                    scaleX = pulse
                    scaleY = pulse
                    alpha = 0.82f + ((pulse - 0.96f) / 0.08f) * 0.18f
                },
            )
            Spacer(Modifier.height(PocketSpacing.xxl))
            AnimatedContent(
                targetState = messages[messageIndex],
                transitionSpec = { PocketTransitions.crossFade() },
                label = "startup message",
            ) { message ->
                Text(message, style = PocketType.callout, color = colors.secondaryLabel, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(PocketSpacing.lg))
            ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp, color = colors.secondaryLabel)
        }
    }
}

@Composable
internal fun StartupErrorScreen(
    message: String?,
    isOffline: Boolean,
    logs: List<String>,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val banner = LocalBanner.current
    val clipboard = LocalClipboardManager.current
    val colors = PocketColors.current
    var showLog by rememberSaveable { mutableStateOf(false) }
    val copyLogs: () -> Unit = {
        clipboard.setText(AnnotatedString(logs.joinToString("\n")))
        banner?.show("Setup log copied", BannerKind.Success)
    }
    OnboardingScaffold(
        title = "Mobile Harness",
        onToggleTheme = onToggleTheme,
        actions = {
            if (isOffline) {
                PocketButton(
                    "Open internet settings",
                    onClick = {
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                        context.startActivity(Intent(action))
                    },
                    size = PocketButtonSize.Large,
                    fullWidth = true,
                )
                PocketButton("Try again", onRetry, style = PocketButtonStyle.Gray, size = PocketButtonSize.Large, fullWidth = true)
            } else {
                PocketButton("Try again", onRetry, size = PocketButtonSize.Large, fullWidth = true)
            }
        },
    ) {
        OnboardingHero(
            title = if (isOffline) "You're offline" else "Mobile Harness couldn't finish starting",
            message = message ?: "Please try again.",
        ) {
            SymbolTile(
                if (isOffline) Icons.Outlined.WifiOff else Icons.Outlined.Warning,
                if (isOffline) colors.orange else colors.red,
                size = HeroSymbol,
            )
        }
        if (logs.isNotEmpty()) {
            ListSection {
                ListRow(
                    "Setup log",
                    icon = Icons.Outlined.Terminal,
                    iconTile = colors.gray,
                    accessory = ListRowAccessory.Chevron,
                    onClick = { showLog = true },
                )
                ListRow(
                    "Copy setup logs",
                    icon = Icons.Outlined.ContentCopy,
                    iconTile = colors.gray,
                    onClick = copyLogs,
                )
            }
        }
    }
    GlassSheet(
        onDismiss = { showLog = false },
        visible = showLog,
        title = "Setup log",
        leading = { SheetTextButton("Copy", copyLogs) },
        trailing = { SheetTextButton("Done", { showLog = false }, emphasized = true) },
    ) {
        SelectionContainer(Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ListInset, vertical = PocketSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs),
            ) {
                logs.forEach { line ->
                    Text(line, style = PocketType.codeSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// endregion

// region Background permissions

@Composable
internal fun BackgroundTaskSetupScreen(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    fun notificationsAllowed(): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun batteryUnrestricted(): Boolean = powerManager.isIgnoringBatteryOptimizations(context.packageName)

    var currentStep by rememberSaveable { mutableIntStateOf(0) }
    var notificationGranted by remember { mutableStateOf(notificationsAllowed()) }
    var batteryGranted by remember { mutableStateOf(batteryUnrestricted()) }
    var notificationDenied by rememberSaveable { mutableStateOf(false) }
    var taskProtectionConfirmed by rememberSaveable { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = notificationsAllowed()
        notificationDenied = !granted
        if (notificationGranted) currentStep = 1
    }
    val notificationSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationGranted = notificationsAllowed()
        if (notificationGranted) currentStep = 1
    }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        batteryGranted = batteryUnrestricted()
        if (batteryGranted) currentStep = 2
    }

    LaunchedEffect(Unit) {
        notificationGranted = notificationsAllowed()
        batteryGranted = batteryUnrestricted()
    }

    val colors = PocketColors.current
    fun stepIcon(step: Int): ImageVector = when (step) {
        0 -> Icons.Outlined.Notifications
        1 -> Icons.Outlined.BatterySaver
        else -> Icons.Outlined.Shield
    }
    fun stepTint(step: Int): Color = when (step) {
        0 -> colors.red
        1 -> colors.green
        else -> colors.blue
    }
    fun stepTitle(step: Int): String = when (step) {
        0 -> "Task notifications"
        1 -> "Background reliability"
        else -> "Task protection"
    }
    fun stepDescription(step: Int): String = when (step) {
        0 -> "See live progress and receive an alert when your agent finishes or needs your attention."
        1 -> "Allow Mobile Harness to continue a task when you lock the phone or switch to another app."
        else -> "Keep the CPU awake only while a visible coding task is running, then release it automatically."
    }
    val currentPrivacyNote = when (currentStep) {
        0 -> "Only task progress, completion, and error notifications are sent."
        1 -> "You remain in control and can stop every task from its notification."
        else -> "The screen stays off. Protection is capped at 90 minutes and stops with the task."
    }
    val currentGranted = when (currentStep) {
        0 -> notificationGranted
        1 -> batteryGranted
        else -> taskProtectionConfirmed
    }
    val primaryLabel = when (currentStep) {
        0 -> if (notificationGranted) "Next" else if (notificationDenied) "Open notification settings" else "Allow notifications"
        1 -> if (batteryGranted) "Next" else "Open battery settings"
        else -> "Enable and finish"
    }
    val onPrimary: () -> Unit = {
        when (currentStep) {
            0 -> when {
                notificationGranted -> currentStep = 1
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationDenied -> {
                    // Targets below API 33 can have notification prompts tied to
                    // channel creation. Create channels only after this explicit tap.
                    RuntimeExecutionService.ensureNotificationChannels(context)
                    RuntimeSetupService.ensureNotificationChannel(context)
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> notificationSettingsLauncher.launch(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(
                        Settings.EXTRA_APP_PACKAGE,
                        context.packageName,
                    ),
                )
            }
            1 -> if (batteryGranted) {
                currentStep = 2
            } else {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                runCatching { batteryLauncher.launch(intent) }
                    .onFailure {
                        batteryLauncher.launch(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
            }
            else -> {
                taskProtectionConfirmed = true
                onContinue()
            }
        }
    }

    OnboardingScaffold(
        title = "Mobile Harness",
        onToggleTheme = onToggleTheme,
        actions = {
            PocketButton(primaryLabel, onPrimary, size = PocketButtonSize.Large, fullWidth = true)
            if (currentStep < 2 && !currentGranted) {
                PocketButton(
                    if (currentStep == 0) "Continue without notifications" else "Continue without battery exemption",
                    onClick = { currentStep += 1 },
                    style = PocketButtonStyle.Plain,
                    fullWidth = true,
                )
            }
        },
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = { PocketTransitions.push(forward = targetState > initialState) },
            label = "permission step",
            modifier = Modifier.fillMaxWidth(),
        ) { step ->
            OnboardingHero(
                title = stepTitle(step),
                message = stepDescription(step),
                eyebrow = "Step ${step + 1} of 3",
            ) { SymbolTile(stepIcon(step), stepTint(step), size = HeroSymbol) }
        }
        ListSection(footer = currentPrivacyNote) {
            PermissionRow(stepIcon(0), "Notifications", stepTint(0), notificationGranted, currentStep == 0)
            PermissionRow(stepIcon(1), "Background", stepTint(1), batteryGranted, currentStep == 1)
            PermissionRow(stepIcon(2), "Task protection", stepTint(2), taskProtectionConfirmed, currentStep == 2)
        }
        Column(verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
            PageNote("Setup time depends on the toolchains you choose next. You may leave Mobile Harness in the background while it works.")
            PageNote("You can change these settings later. Android may still stop exceptionally heavy work when the device is low on memory.")
        }
    }
}

private enum class PermissionState { Complete, Required, Next }

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    tint: Color,
    complete: Boolean,
    active: Boolean,
) {
    val colors = PocketColors.current
    val state = when {
        complete -> PermissionState.Complete
        active -> PermissionState.Required
        else -> PermissionState.Next
    }
    ListRow(
        title,
        icon = icon,
        iconTile = if (state == PermissionState.Next) colors.gray else tint,
        trailing = {
            AnimatedContent(targetState = state, transitionSpec = { PocketTransitions.crossFade() }, label = "permission state") { shown ->
                when (shown) {
                    PermissionState.Complete -> Icon(Icons.Outlined.CheckCircle, "Complete", tint = colors.green, modifier = Modifier.size(22.dp))
                    PermissionState.Required -> Text("Required", style = PocketType.subheadline.emphasized, color = MaterialTheme.colorScheme.primary)
                    PermissionState.Next -> Text("Next", style = PocketType.subheadline, color = colors.tertiaryLabel)
                }
            }
        },
    )
}

// endregion

// region Runtime setup

private data class DevStackVisuals(val icon: ImageVector, val tint: Color)

@Composable
private fun devStackVisuals(stack: DevStack): DevStackVisuals {
    val colors = PocketColors.current
    return when (stack) {
        DevStack.WEB -> DevStackVisuals(Icons.Outlined.Language, colors.cyan)
        DevStack.PYTHON -> DevStackVisuals(Icons.Outlined.Terminal, colors.yellow)
        DevStack.ANDROID -> DevStackVisuals(Icons.Outlined.Android, colors.green)
        DevStack.CPP -> DevStackVisuals(Icons.Outlined.Memory, colors.purple)
        DevStack.PHP -> DevStackVisuals(Icons.Outlined.Dns, colors.indigo)
    }
}

@Composable
internal fun RuntimeSetupPromptScreen(
    selectedStacks: Set<DevStack>,
    selectedAgent: AgentKind = AgentKind.CLAUDE_CODE,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
    onToggleStack: (DevStack) -> Unit,
    onSelectAgent: (AgentKind) -> Unit = {},
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memoryInfo = remember { ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo) }
    val totalRamGb = memoryInfo.totalMem.toDouble() / 1_073_741_824.0
    val totalRamLabel = String.format(java.util.Locale.US, "%.1f", totalRamGb)
    val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
    // Android reports usable physical memory after hardware/GPU reservations.
    // RAM is therefore informational; it must not reject nominal 4 GB phones.
    val compatible = arm64

    var currentStep by remember { mutableIntStateOf(0) }

    if (currentStep > 0) {
        BackHandler { currentStep = 0 }
    }

    // Each step is its own page, so it always opens scrolled to the top.
    AnimatedContent(
        targetState = currentStep,
        transitionSpec = { PocketTransitions.push(forward = targetState > initialState) },
        label = "setup step",
    ) { step ->
        BackdropSourceScope(active = isTransitionTarget) {
            if (step == 0) {
                DeviceCheckPage(
                    compatible = compatible,
                    arm64 = arm64,
                    totalRamLabel = totalRamLabel,
                    onToggleTheme = onToggleTheme,
                    onContinue = { currentStep = 1 },
                )
            } else {
                ToolchainPage(
                    compatible = compatible,
                    selectedStacks = selectedStacks,
                    selectedAgent = selectedAgent,
                    onToggleTheme = onToggleTheme,
                    onBack = { currentStep = 0 },
                    onToggleStack = onToggleStack,
                    onSelectAgent = onSelectAgent,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
private fun DeviceCheckPage(
    compatible: Boolean,
    arm64: Boolean,
    totalRamLabel: String,
    onToggleTheme: () -> Unit,
    onContinue: () -> Unit,
) {
    val colors = PocketColors.current
    OnboardingScaffold(
        title = "Device check",
        onToggleTheme = onToggleTheme,
        actions = {
            PocketButton(
                if (compatible) "Continue to tool setup" else "Device not supported",
                onClick = onContinue,
                enabled = compatible,
                size = PocketButtonSize.Large,
                fullWidth = true,
            )
            Text("You can change tools later", style = PocketType.footnote, color = colors.secondaryLabel, textAlign = TextAlign.Center)
        },
    ) {
        OnboardingHero(
            title = if (compatible) "Ready to build on this phone" else "This phone isn't supported",
            message = if (compatible) {
                "Your phone meets the requirements. Choose your coding tools next and Mobile Harness will handle the setup."
            } else {
                "Mobile Harness needs a 64-bit ARM processor to run its private Linux environment."
            },
            eyebrow = "Device check",
        ) { BrandMark() }
        ListSection(header = "This phone", footer = "The download depends on the tools you select.") {
            ListRow(
                "Compatibility",
                icon = Icons.Outlined.Speed,
                iconTile = if (compatible) colors.green else colors.red,
                value = if (compatible) "Ready" else "Unsupported",
            )
            ListRow("Memory", icon = Icons.Outlined.Memory, iconTile = colors.blue, value = "$totalRamLabel GB usable")
            ListRow(
                "Processor",
                icon = Icons.Outlined.Code,
                iconTile = if (arm64) colors.indigo else colors.red,
                value = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            )
            ListRow("Download", icon = Icons.Outlined.Storage, iconTile = colors.gray, value = "149–774 MB")
        }
    }
}

@Composable
private fun ToolchainPage(
    compatible: Boolean,
    selectedStacks: Set<DevStack>,
    selectedAgent: AgentKind,
    onToggleTheme: () -> Unit,
    onBack: () -> Unit,
    onToggleStack: (DevStack) -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onDownload: () -> Unit,
) {
    val colors = PocketColors.current
    val haptics = rememberHaptics()
    OnboardingScaffold(
        title = "Choose your tools",
        onToggleTheme = onToggleTheme,
        onBack = onBack,
        actions = {
            PocketButton(
                if (compatible) "Install Mobile Harness" else "Device not supported",
                onClick = onDownload,
                enabled = compatible,
                icon = Icons.Outlined.Download,
                size = PocketButtonSize.Large,
                fullWidth = true,
            )
            Text(
                toolchainDownloadSummary(selectedStacks, selectedAgent),
                style = PocketType.footnote,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        },
    ) {
        OnboardingHero(
            title = "Choose your tools",
            message = "Start lightweight. You can install more toolchains later from Settings.",
            eyebrow = "Toolchain setup",
        ) { SymbolTile(Icons.Outlined.Build, colors.orange, size = HeroSymbol) }
        ListSection(header = "Included") {
            ListRow(
                "Core runtime",
                subtitle = "Ubuntu · Node.js · npm · Git",
                icon = Icons.Outlined.Terminal,
                iconTile = colors.gray,
                value = "68.8 MB",
            )
        }
        AgentChoiceSection(
            selected = selectedAgent,
            onSelect = onSelectAgent,
            header = "Coding agent",
            footer = "Only the selected optional agent is downloaded. You can install or switch agents later from Settings.",
        )
        ListSection(header = "Optional toolchains") {
            DevStack.entries.forEach { stack ->
                val visuals = devStackVisuals(stack)
                val locked = stack == DevStack.WEB
                ChoiceRow(
                    title = stack.label,
                    subtitle = devStackDescription(stack),
                    selected = locked || stack in selectedStacks,
                    multiple = true,
                    onClick = if (locked) {
                        null
                    } else {
                        {
                            haptics.selection()
                            onToggleStack(stack)
                        }
                    },
                ) { SymbolTile(visuals.icon, visuals.tint) }
            }
        }
    }
}

private fun devStackDescription(stack: DevStack): String {
    val concise = when (stack) {
        DevStack.WEB -> "Included with the Core runtime"
        DevStack.PYTHON -> "Scripts, automation and backends"
        DevStack.ANDROID -> "Java and Kotlin build tools"
        DevStack.CPP -> "Native apps and command-line tools"
        DevStack.PHP -> "PHP sites and Laravel projects"
    }
    return when {
        stack == DevStack.WEB -> concise
        BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack in setOf(DevStack.PYTHON, DevStack.ANDROID) -> "$concise · included"
        !BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack == DevStack.PYTHON -> "$concise · 55 MB"
        !BuildConfig.OFFLINE_RUNTIME_BUNDLES && stack == DevStack.ANDROID -> "$concise · 570 MB"
        else -> concise
    }
}

private const val CORE_RUNTIME_DOWNLOAD_MB = 69
private const val CLAUDE_RUNTIME_DOWNLOAD_MB = 72
private const val DSH_RUNTIME_DOWNLOAD_MB = 27
private const val AGY_RUNTIME_DOWNLOAD_MB = 40
private const val PYTHON_RUNTIME_DOWNLOAD_MB = 55
private const val ANDROID_RUNTIME_DOWNLOAD_MB = 570

private fun setupTimeEstimate(selected: Set<DevStack>): String {
    var minimumMinutes = 3
    var maximumMinutes = 5
    if (DevStack.PYTHON in selected) {
        minimumMinutes += 1
        maximumMinutes += 2
    }
    if (DevStack.ANDROID in selected) {
        minimumMinutes += 7
        maximumMinutes += 10
    }
    if (DevStack.CPP in selected) {
        minimumMinutes += 3
        maximumMinutes += 5
    }
    if (DevStack.PHP in selected) {
        minimumMinutes += 2
        maximumMinutes += 4
    }
    return "$minimumMinutes–$maximumMinutes minutes"
}

private fun toolchainDownloadSummary(selected: Set<DevStack>, agent: AgentKind): String {
    if (BuildConfig.OFFLINE_RUNTIME_BUNDLES) return "All selected bundles are included in this offline app"
    val total = CORE_RUNTIME_DOWNLOAD_MB +
        when (agent) {
            AgentKind.CLAUDE_CODE -> CLAUDE_RUNTIME_DOWNLOAD_MB
            AgentKind.DEEPSEEK_HARNESS -> DSH_RUNTIME_DOWNLOAD_MB
            AgentKind.ANTIGRAVITY -> AGY_RUNTIME_DOWNLOAD_MB
        } +
        (if (DevStack.PYTHON in selected) PYTHON_RUNTIME_DOWNLOAD_MB else 0) +
        (if (DevStack.ANDROID in selected) ANDROID_RUNTIME_DOWNLOAD_MB else 0)
    val laterPackages = selected.intersect(setOf(DevStack.CPP, DevStack.PHP))
    return buildString {
        append("Download: ")
        append(total)
        append(" MB")
        if (laterPackages.isNotEmpty()) append(" · C/PHP packages download later")
        if (total >= 500) append(" · Wi-Fi recommended")
    }
}

@Composable
internal fun RuntimeInstallationScreen(
    state: AppUiState,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    onToggleTheme: () -> Unit = {},
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val colors = PocketColors.current

    OnboardingScaffold(title = "Setting up", onToggleTheme = onToggleTheme) {
        OnboardingHero(
            title = "Build your workspace",
            message = state.startupMessage,
            stableMessage = true,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ProgressRing(progress = state.startupProgress.coerceIn(0f, 1f), size = 96.dp, strokeWidth = 8.dp)
                Text(
                    "${(state.startupProgress * 100).toInt()}%",
                    style = PocketType.title3.emphasized.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
        ListSection(footer = "You can leave Mobile Harness in the background and follow setup from the notification.") {
            ListRow(
                "Estimated time",
                icon = Icons.Outlined.Schedule,
                iconTile = colors.blue,
                value = setupTimeEstimate(state.selectedDevStacks),
            )
            state.startupBytes?.let { (downloaded, total) ->
                ListRow(
                    "Downloaded",
                    subtitle = "${formatMegabytes(downloaded)} of ${formatMegabytes(total)}",
                    icon = Icons.Outlined.Download,
                    iconTile = colors.green,
                )
            }
        }
        SetupLogPanel(logs = state.startupLogs.ifEmpty { listOf("$ ${state.startupMessage}") })
    }
}

/** Setup output, hidden until asked for: a disclosure row over the live log. */
@Composable
private fun SetupLogPanel(logs: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val colors = PocketColors.current

    LaunchedEffect(logs.size, logs.lastOrNull()) {
        if (expanded && followLatest) {
            delay(20)
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    LaunchedEffect(scrollState.isScrollInProgress) {
        if (!scrollState.isScrollInProgress && expanded) {
            followLatest = scrollState.maxValue - scrollState.value < 32
        }
    }
    val chevron by animateFloatAsState(if (expanded) 90f else 0f, PocketMotion.spec(Token.Snappy), label = "setupLogChevron")

    Column(verticalArrangement = Arrangement.spacedBy(PocketSpacing.md)) {
        ListSection {
            ListRow(
                if (expanded) "Hide setup details" else "Show setup details",
                icon = Icons.Outlined.Terminal,
                iconTile = colors.gray,
                onClick = { expanded = !expanded },
                trailing = {
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.tertiaryLabel,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = chevron },
                    )
                },
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(PocketMotion.spec(Token.Smooth, IntSize.VisibilityThreshold)) + fadeIn(PocketMotion.spec(Token.Smooth)),
            exit = shrinkVertically(PocketMotion.spec(Token.Snappy, IntSize.VisibilityThreshold)) + fadeOut(PocketMotion.spec(Token.Quick)),
        ) {
            Column(Modifier.padding(horizontal = ListInset)) {
                SelectionContainer {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(PocketShape.lg)
                            .background(colors.codeSurface)
                            .heightIn(max = 220.dp)
                            .verticalScroll(scrollState)
                            .padding(PocketSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs),
                    ) {
                        logs.forEach { line ->
                            Text(line, style = PocketType.codeSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                if (!followLatest) {
                    PocketButton(
                        "Jump to latest",
                        onClick = {
                            followLatest = true
                            scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                        },
                        style = PocketButtonStyle.Plain,
                        size = PocketButtonSize.Small,
                        modifier = Modifier.align(Alignment.End),
                    )
                }
            }
        }
    }
}

// endregion

// region Agent sign-in

@Composable
internal fun AntigravityOnboardingScreen(
    state: AppUiState,
    onStartLogin: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onContinue: () -> Unit,
    onSelectAgent: (AgentKind) -> Unit,
    onToggleTheme: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    val colors = PocketColors.current
    var code by rememberSaveable { mutableStateOf("") }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }
    val auth = state.antigravityAuth
    AgentSwitchSheet(
        visible = showAgentPicker,
        selected = AgentKind.ANTIGRAVITY,
        onSelect = { agent ->
            showAgentPicker = false
            onSelectAgent(agent)
        },
        onDismiss = { showAgentPicker = false },
    )
    OnboardingScaffold(
        title = "Set up Antigravity",
        onToggleTheme = onToggleTheme,
        actions = {
            when (auth.status) {
                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR ->
                    PocketButton("Sign in with Google", onStartLogin, size = PocketButtonSize.Large, fullWidth = true)
                AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING ->
                    PocketButton("Signing in", {}, enabled = false, loading = true, size = PocketButtonSize.Large, fullWidth = true)
                AntigravityAuthStatus.AWAITING_CODE ->
                    PocketButton(
                        "Complete sign-in",
                        onClick = { onSubmitCode(code); code = "" },
                        enabled = code.isNotBlank(),
                        size = PocketButtonSize.Large,
                        fullWidth = true,
                    )
                AntigravityAuthStatus.SIGNED_IN ->
                    PocketButton("Continue", onContinue, size = PocketButtonSize.Large, fullWidth = true)
            }
            PocketButton("Use another coding agent", { showAgentPicker = true }, style = PocketButtonStyle.Plain, fullWidth = true)
        },
    ) {
        OnboardingHero(
            title = "Connect your Google account",
            message = "Mobile Harness runs Google's official agy CLI inside its private Linux environment. Google handles authentication and agy owns the saved session.",
        ) { MonogramTile(agentMonogram(AgentKind.ANTIGRAVITY), agentTint(AgentKind.ANTIGRAVITY), size = HeroSymbol) }
        when (auth.status) {
            AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> auth.message?.let { message ->
                ListSection {
                    ListRow(message, icon = Icons.Outlined.Warning, iconTile = colors.red, titleColor = colors.red)
                }
            }
            AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> ListSection {
                ListRow(
                    if (auth.status == AntigravityAuthStatus.STARTING) "Starting the official Antigravity login…" else "Completing Google sign-in…",
                    leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
                )
            }
            AntigravityAuthStatus.AWAITING_CODE -> ListSection(
                header = "Google sign-in",
                footer = "Google sign-in opened in your browser. Copy the one-time code shown after approval.",
            ) {
                auth.authorizationUrl?.let { url ->
                    ListRow(
                        "Copy sign-in URL",
                        icon = Icons.Outlined.ContentCopy,
                        iconTile = colors.blue,
                        onClick = {
                            clipboard.setText(AnnotatedString(url))
                            banner("Sign-in link copied", BannerKind.Success)
                        },
                    )
                }
                Column(Modifier.fillMaxWidth().padding(ListInset)) {
                    PocketTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = "Authorization code",
                        placeholder = "Paste the one-time code",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    )
                }
            }
            AntigravityAuthStatus.SIGNED_IN -> ListSection {
                ListRow(
                    auth.accountEmail?.let { "Connected as $it" } ?: "Google account connected",
                    icon = Icons.Outlined.Check,
                    iconTile = colors.green,
                )
            }
        }
        ListSection {
            ListRow(
                "Automatic tool approval",
                subtitle = "Antigravity can edit project files and run commands without confirmation. Changes remain reviewable in Mobile Harness.",
                subtitleMaxLines = 6,
                icon = Icons.Outlined.Warning,
                iconTile = colors.orange,
            )
        }
    }
}

/** Everything the onboarding screen needs to offer "Sign in with Claude". */
internal data class ClaudeSignInActions(
    val auth: ClaudeAuthState,
    val claudeInstalled: Boolean,
    val busy: Boolean,
    val onSignIn: () -> Unit,
    val onCancel: () -> Unit,
    val onSubmitCode: (String) -> Unit,
    val onSignOut: () -> Unit,
    val onRefresh: () -> Unit,
    val onFinish: () -> Unit,
)

@Composable
internal fun ProviderSetupScreen(
    initial: ProviderProfile,
    onboarding: Boolean,
    agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    initialStep: Int = 1,
    onBack: (() -> Unit)? = null,
    onSave: (ProviderProfile, String) -> Unit,
    onDiscover: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidate: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onSelectAgent: (AgentKind) -> Unit,
    onToggleTheme: (() -> Unit)? = null,
    themeMode: AppThemeMode = AppThemeMode.DARK,
    claudeSignIn: ClaudeSignInActions? = null,
) {
    var step by rememberSaveable { mutableIntStateOf(initialStep) }
    var selected by rememberSaveable { mutableStateOf(initial.kind) }
    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl.ifBlank { initial.kind.defaultBaseUrl }) }
    var model by rememberSaveable { mutableStateOf(initial.model.ifBlank { initial.kind.defaultModel }) }
    var dshApi by rememberSaveable { mutableStateOf(initial.dshApi.ifBlank { "anthropic-messages" }) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var showAgentPicker by rememberSaveable { mutableStateOf(false) }

    AgentSwitchSheet(
        visible = showAgentPicker,
        selected = agentKind,
        onSelect = { agent ->
            showAgentPicker = false
            onSelectAgent(agent)
        },
        onDismiss = { showAgentPicker = false },
    )

    val handleBack: (() -> Unit)? = when {
        step > 1 -> { { step = 1 } }
        !onboarding && onBack != null -> onBack
        else -> null
    }

    if (handleBack != null) {
        BackHandler(onBack = handleBack)
    }

    val title = if (onboarding) "Set up Mobile Harness" else "AI Provider & Settings"
    AnimatedContent(
        targetState = step > 1,
        transitionSpec = { PocketTransitions.push(forward = targetState) },
        label = "provider step",
    ) { credentials ->
        BackdropSourceScope(active = isTransitionTarget) {
            if (!credentials) {
                ProviderChoiceStep(
                    title = title,
                    eyebrow = if (onboarding) "Step 2 of 3" else null,
                    selected = selected,
                    agentKind = agentKind,
                    onBack = handleBack,
                    onToggleTheme = onToggleTheme,
                    onSelected = {
                        if (selected != it) {
                            selected = it
                            baseUrl = it.defaultBaseUrl
                            model = it.defaultModel
                            apiKey = ""
                        }
                    },
                    onContinue = { step = 2 },
                    onChangeAgent = { showAgentPicker = true },
                )
            } else {
                ProviderCredentialsStep(
                    title = title,
                    eyebrow = if (onboarding) "Step 3 of 3" else null,
                    onBack = handleBack,
                    onToggleTheme = onToggleTheme,
                    provider = selected,
                    agentKind = agentKind,
                    baseUrl = baseUrl,
                    model = model,
                    dshApi = dshApi,
                    apiKey = apiKey,
                    onBaseUrl = {
                        baseUrl = it
                        if (agentKind == AgentKind.DEEPSEEK_HARNESS && selected == ProviderKind.CUSTOM) {
                            dshApi = inferredDshApiForUrl(it)
                        }
                    },
                    onModel = { model = it },
                    onDshApi = { dshApi = it },
                    onApiKey = { apiKey = it },
                    hasStoredSecret = initial.kind == selected && initial.hasSecret,
                    onDiscover = {
                        val url = if (selected.fixedBaseUrl) selected.defaultBaseUrl else baseUrl.trim()
                        onDiscover(ProviderProfile(selected, url, model.trim(), dshApi = dshApi), apiKey)
                    },
                    onValidate = { models ->
                        val url = if (selected.fixedBaseUrl) selected.defaultBaseUrl else baseUrl.trim()
                        onValidate(ProviderProfile(selected, url, model.trim(), dshApi = dshApi), apiKey, models)
                    },
                    onSave = {
                        val url = if (selected.fixedBaseUrl) selected.defaultBaseUrl else baseUrl.trim()
                        // A pasted Claude setup token means token mode; sign-in completes via claudeSignIn.onFinish.
                        val authMode = if (selected == ProviderKind.CLAUDE && apiKey.isNotBlank()) {
                            ClaudeAuthMode.SETUP_TOKEN_LEGACY
                        } else {
                            ClaudeAuthMode.NATIVE_SUBSCRIPTION
                        }
                        onSave(ProviderProfile(selected, url, model.trim(), dshApi = dshApi, claudeAuthMode = authMode), apiKey)
                    },
                    onChangeAgent = { showAgentPicker = true },
                    claudeSignIn = claudeSignIn,
                )
            }
        }
    }
}

@Composable
private fun ProviderChoiceStep(
    title: String,
    eyebrow: String?,
    selected: ProviderKind,
    agentKind: AgentKind,
    onBack: (() -> Unit)?,
    onToggleTheme: (() -> Unit)?,
    onSelected: (ProviderKind) -> Unit,
    onContinue: () -> Unit,
    onChangeAgent: () -> Unit,
) {
    val visibleProviders = remember(agentKind) { providersForAgent(agentKind) }
    val haptics = rememberHaptics()
    OnboardingScaffold(
        title = title,
        onToggleTheme = onToggleTheme,
        onBack = onBack,
        actions = {
            PocketButton("Continue", onContinue, size = PocketButtonSize.Large, fullWidth = true)
            PocketButton("Use another coding agent", onChangeAgent, style = PocketButtonStyle.Plain, fullWidth = true)
        },
    ) {
        OnboardingHero(
            title = "Connect your AI",
            message = "Choose how Mobile Harness should access your coding model.",
            eyebrow = eyebrow,
        ) { SymbolTile(Icons.Outlined.AutoAwesome, PocketColors.current.indigo, size = HeroSymbol) }
        ListSection(header = "Provider", footer = "API keys are encrypted in Android secure storage.") {
            visibleProviders.forEach { provider ->
                ChoiceRow(
                    title = provider.title,
                    subtitle = provider.subtitle,
                    selected = selected == provider,
                    tag = if (provider.experimental) "Beta" else null,
                    onClick = {
                        haptics.selection()
                        onSelected(provider)
                    },
                ) { MonogramTile(providerMonogram(provider), providerTint(provider)) }
            }
        }
    }
}

@Composable
private fun ProviderCredentialsStep(
    title: String,
    eyebrow: String?,
    onBack: (() -> Unit)?,
    onToggleTheme: (() -> Unit)?,
    provider: ProviderKind,
    agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    baseUrl: String,
    model: String,
    dshApi: String = "anthropic-messages",
    apiKey: String,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onDshApi: (String) -> Unit = {},
    onApiKey: (String) -> Unit,
    hasStoredSecret: Boolean,
    onDiscover: suspend () -> ModelDiscoveryResult,
    onValidate: suspend (List<DiscoveredModel>) -> ConnectionValidation,
    onSave: () -> Unit,
    onChangeAgent: () -> Unit,
    claudeSignIn: ClaudeSignInActions? = null,
) {
    val scope = rememberCoroutineScope()
    val colors = PocketColors.current
    var models by remember(baseUrl) { mutableStateOf(emptyList<DiscoveredModel>()) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusDetails by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var modelSearch by rememberSaveable { mutableStateOf("") }
    val hasKey = apiKey.isNotBlank() || hasStoredSecret || provider == ProviderKind.ANTIGRAVITY_SERVER
    val filteredModels = remember(models, modelSearch) {
        val query = modelSearch.trim()
        if (query.isEmpty()) models else models.filter {
            it.id.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true)
        }
    }

    if (provider == ProviderKind.CLAUDE) {
        ClaudeSubscriptionCredentialsStep(
            title = title,
            eyebrow = eyebrow,
            onBack = onBack,
            onToggleTheme = onToggleTheme,
            token = apiKey,
            hasStoredToken = hasStoredSecret,
            onToken = onApiKey,
            onSave = onSave,
            onChangeAgent = onChangeAgent,
            claudeSignIn = claudeSignIn,
        )
        return
    }

    fun discoverModels(openWhenReady: Boolean = true) {
        scope.launch {
            isDiscovering = true
            status = null
            statusDetails = null
            when (val result = onDiscover()) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    statusOk = true
                    status = "Found ${result.models.size} available model${if (result.models.size == 1) "" else "s"}."
                    if (model.isBlank() && result.models.isNotEmpty()) onModel(result.models.first().id)
                    if (openWhenReady && result.models.isNotEmpty()) showModels = true
                }
                is ModelDiscoveryResult.Failure -> {
                    statusOk = false
                    status = result.message
                    statusDetails = result.providerMessage
                }
            }
            isDiscovering = false
        }
    }

    GlassSheet(
        onDismiss = { showModels = false },
        visible = showModels,
        title = "Models",
        detents = listOf(SheetDetent.Medium, SheetDetent.Large),
        initialDetent = SheetDetent.Large,
        leading = {
            if (isDiscovering) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    ProgressRing(progress = null, size = 20.dp, strokeWidth = 2.dp)
                }
            } else {
                PocketIconButton(Icons.Outlined.Refresh, "Refresh models", onClick = { discoverModels(openWhenReady = false) })
            }
        },
        trailing = { SheetTextButton("Done", { showModels = false }, emphasized = true) },
    ) {
        SearchField(
            value = modelSearch,
            onValueChange = { modelSearch = it },
            placeholder = "Search model name or ID",
            modifier = Modifier.padding(horizontal = ListInset),
        )
        Text(
            "${filteredModels.size} of ${models.size} models",
            style = PocketType.footnote,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(start = ListInset * 2, end = ListInset * 2, top = PocketSpacing.xs),
        )
        if (filteredModels.isEmpty()) {
            EmptyState(Icons.Outlined.Search, "No matching models", Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = PocketSpacing.sm, bottom = PocketSpacing.xxl),
            ) {
                itemsIndexed(filteredModels, key = { _, option -> option.id }) { index, option ->
                    LazyGroupRow(isFirst = index == 0, isLast = index == filteredModels.lastIndex) {
                        val free: (@Composable RowScope.() -> Unit)? = if (option.isFree) {
                            { Tag("Free", colors.green) }
                        } else null
                        ListRow(
                            option.displayName,
                            subtitle = option.id.takeIf { it != option.displayName },
                            subtitleMaxLines = 1,
                            trailing = free,
                            accessory = if (model == option.id) ListRowAccessory.Check else ListRowAccessory.None,
                            onClick = {
                                onModel(option.id)
                                status = null
                                modelSearch = ""
                                showModels = false
                            },
                        )
                    }
                }
            }
        }
    }

    OnboardingScaffold(
        title = title,
        onToggleTheme = onToggleTheme,
        onBack = onBack,
        actions = {
            PocketButton(
                if (isValidating) "Checking" else "Continue",
                onClick = {
                    scope.launch {
                        isValidating = true
                        status = "Checking API key, model and settings…"
                        statusDetails = null
                        statusOk = true
                        when (val result = onValidate(models)) {
                            is ConnectionValidation.Success -> {
                                status = result.message
                                statusOk = true
                                onSave()
                            }
                            is ConnectionValidation.Failure -> {
                                status = result.message
                                statusDetails = result.providerMessage
                                statusOk = false
                            }
                        }
                        isValidating = false
                    }
                },
                enabled = baseUrl.isNotBlank() && model.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                loading = isValidating,
                size = PocketButtonSize.Large,
                fullWidth = true,
            )
            PocketButton("Use another coding agent", onChangeAgent, style = PocketButtonStyle.Plain, fullWidth = true)
        },
    ) {
        OnboardingHero(
            title = provider.title,
            message = when {
                agentKind == AgentKind.DEEPSEEK_HARNESS -> "DeepSeek Harness will connect through this API endpoint."
                provider.protocol.name.startsWith("OPENAI") -> "Mobile Harness will translate Claude Code requests for this provider."
                else -> "Claude Code will connect through this API endpoint."
            },
            eyebrow = eyebrow,
        ) { MonogramTile(providerMonogram(provider), providerTint(provider), size = HeroSymbol) }

        ListSection(header = "Connection", footer = "Keys are encrypted locally in Android secure storage.") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(ListInset),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
            ) {
                PocketTextField(
                    value = baseUrl,
                    onValueChange = { onBaseUrl(it); status = null; statusDetails = null; models = emptyList() },
                    label = "Base URL",
                    helper = if (provider.fixedBaseUrl) "Fixed by ${provider.title}" else null,
                    enabled = !provider.fixedBaseUrl,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                if (provider != ProviderKind.ANTIGRAVITY_SERVER) {
                    PocketTextField(
                        value = apiKey,
                        onValueChange = { onApiKey(it); status = null; statusDetails = null },
                        label = "API key",
                        placeholder = if (hasStoredSecret) "Saved securely — leave blank to keep it" else "Enter your API key",
                        helper = if (hasStoredSecret && apiKey.isBlank()) "A saved key is ready to use" else null,
                        secure = true,
                    )
                }
                PocketTextField(
                    value = model,
                    onValueChange = { onModel(it); status = null; statusDetails = null },
                    label = "Model",
                    helper = "Select an available model or enter an exact model ID.",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                PocketButton(
                    if (models.isEmpty()) "Find available models" else "Choose from ${models.size} models",
                    onClick = { if (models.isEmpty()) discoverModels() else showModels = true },
                    enabled = baseUrl.isNotBlank() && hasKey && !isDiscovering && !isValidating,
                    loading = isDiscovering,
                    icon = Icons.Outlined.Search,
                    style = PocketButtonStyle.Tinted,
                    fullWidth = true,
                )
            }
        }

        if (agentKind == AgentKind.DEEPSEEK_HARNESS && provider in DSH_PROTOCOL_PROVIDERS && !provider.fixedProtocol) {
            ListSection(header = "Gateway protocol", footer = "Pick the protocol your gateway speaks; DeepSeek Harness routes it directly.") {
                listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                    ListRow(
                        option,
                        accessory = if (dshApi == option) ListRowAccessory.Check else ListRowAccessory.None,
                        onClick = { onDshApi(option); status = null },
                    )
                }
            }
        }

        if (provider == ProviderKind.ANTIGRAVITY_SERVER) {
            ListSection(header = "Google account") {
                ListRow(
                    if (hasStoredSecret) "Google Antigravity connected" else "Google account not signed in",
                    subtitle = if (hasStoredSecret) {
                        "Using the active Google sign-in and multi-account rotation."
                    } else {
                        "Sign in under Antigravity settings to use Antigravity models."
                    },
                    icon = if (hasStoredSecret) Icons.Outlined.Check else Icons.Outlined.Warning,
                    iconTile = if (hasStoredSecret) colors.green else colors.red,
                )
            }
        }

        status?.let { text ->
            ListSection {
                val checking: (@Composable () -> Unit)? = if (isValidating || isDiscovering) {
                    { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) }
                } else null
                ListRow(
                    text,
                    subtitle = statusDetails?.takeIf(String::isNotBlank),
                    subtitleMaxLines = 8,
                    leading = checking,
                    icon = if (statusOk) Icons.Outlined.Check else Icons.Outlined.Warning,
                    iconTile = if (statusOk) colors.green else colors.red,
                    titleColor = if (statusOk) null else colors.red,
                )
            }
        }
    }
}

@Composable
private fun ClaudeSubscriptionCredentialsStep(
    title: String,
    eyebrow: String?,
    onBack: (() -> Unit)?,
    onToggleTheme: (() -> Unit)?,
    token: String,
    hasStoredToken: Boolean,
    onToken: (String) -> Unit,
    onSave: () -> Unit,
    onChangeAgent: () -> Unit,
    claudeSignIn: ClaudeSignInActions? = null,
) {
    val colors = PocketColors.current
    val hasToken = token.isNotBlank() || hasStoredToken

    OnboardingScaffold(
        title = title,
        onToggleTheme = onToggleTheme,
        onBack = onBack,
        actions = {
            if (claudeSignIn != null) {
                PocketButton(
                    "Continue",
                    claudeSignIn.onFinish,
                    enabled = claudeSignIn.auth.status == ClaudeAuthStatusState.SIGNED_IN,
                    size = PocketButtonSize.Large,
                    fullWidth = true,
                )
            } else {
                PocketButton("Save and continue", onSave, enabled = hasToken, size = PocketButtonSize.Large, fullWidth = true)
            }
            PocketButton("Use another coding agent", onChangeAgent, style = PocketButtonStyle.Plain, fullWidth = true)
        },
    ) {
        OnboardingHero(
            title = "Claude subscription",
            message = "Connect a Claude Pro, Max, Team, or Enterprise subscription to Claude Code.",
            eyebrow = eyebrow,
        ) { MonogramTile(providerMonogram(ProviderKind.CLAUDE), providerTint(ProviderKind.CLAUDE), size = HeroSymbol) }
        if (claudeSignIn != null) {
            ClaudeAccountCard(
                auth = claudeSignIn.auth,
                claudeInstalled = claudeSignIn.claudeInstalled,
                busy = claudeSignIn.busy,
                onSignIn = claudeSignIn.onSignIn,
                onCancel = claudeSignIn.onCancel,
                onSubmitCode = claudeSignIn.onSubmitCode,
                onSignOut = claudeSignIn.onSignOut,
                onRefresh = claudeSignIn.onRefresh,
            )
        }
        ListSection(header = if (claudeSignIn != null) "Or use a setup token" else "Setup token") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(ListInset),
                verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
            ) {
                Text("1. On a computer where Claude Code is installed, run:", style = PocketType.subheadline, color = MaterialTheme.colorScheme.onSurface)
                SelectionContainer {
                    Text(
                        "claude setup-token",
                        style = PocketType.code,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(PocketShape.sm)
                            .background(colors.codeSurface)
                            .padding(PocketSpacing.md),
                    )
                }
                Text("2. Sign in to Claude and paste the generated token here.", style = PocketType.subheadline, color = MaterialTheme.colorScheme.onSurface)
                PocketTextField(
                    value = token,
                    onValueChange = onToken,
                    label = "Claude setup token",
                    placeholder = if (hasStoredToken) "Saved securely — leave blank to keep it" else "Paste token",
                    helper = if (hasStoredToken && token.isBlank()) "A saved subscription token is ready to use" else null,
                    secure = true,
                )
                if (claudeSignIn != null) {
                    PocketButton("Save token and continue", onSave, enabled = hasToken, style = PocketButtonStyle.Tinted, fullWidth = true)
                }
            }
        }
    }
}

// endregion
