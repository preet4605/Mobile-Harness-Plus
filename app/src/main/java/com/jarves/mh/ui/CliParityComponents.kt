package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jarves.mh.model.ArtifactInfo
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.CLAUDE_SUBSCRIPTION_MODELS
import com.jarves.mh.model.ClaudeThinkingLevel
import com.jarves.mh.model.CustomizationScopeMode
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectCustomizationConfig
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.RuleInfo
import com.jarves.mh.model.RuleSource
import com.jarves.mh.model.ScheduledTimerInfo
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SkillSource
import com.jarves.mh.model.SlashCommand
import com.jarves.mh.model.SlashCommandCategory
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.GlassSheet
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
import com.jarves.mh.ui.kit.PocketToggle
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SearchField
import com.jarves.mh.ui.kit.SectionFooter
import com.jarves.mh.ui.kit.SectionHeader
import com.jarves.mh.ui.kit.SegmentedControl
import com.jarves.mh.ui.kit.SheetDetent
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.SymbolTile
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberBanner
import com.jarves.mh.ui.kit.rememberHaptics
import com.jarves.mh.ui.kit.rememberOverlayAnchor
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassMaterial
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.medium
import java.io.File
import java.text.NumberFormat

/** Where row text starts after a symbol tile, so separators line up with it as in the kit. */
private val RowTextStart: Dp = ListInset + ListIconTile + 14.dp

/** Space above each group after the first in the sheets' lists. */
private val GroupGap: Dp = PocketSpacing.xl

// =============================================================================================
// Composer suggestions
// =============================================================================================

/**
 * Slash commands and skills, floating on glass above the chat composer. Its height adapts to the
 * screen (about a third of it, within limits) so the composer is never squeezed. Rows only take
 * focus from a keyboard, so typing stays in the composer while choosing.
 */
@Composable
fun SlashCommandMenu(
    commands: List<SlashCommand>,
    skills: List<SkillInfo> = emptyList(),
    onSelect: (SlashCommand) -> Unit,
    onSelectSkill: ((SkillInfo) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty() && skills.isEmpty()) return

    val colors = PocketColors.current
    val maxMenuHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.32f).coerceIn(140.dp, 240.dp)

    SuggestionPanel(modifier) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = maxMenuHeight),
            contentPadding = PaddingValues(bottom = PocketSpacing.xs),
        ) {
            if (commands.isNotEmpty()) {
                item(key = "header_commands") { SuggestionHeader("Commands", commands.size) }
                itemsIndexed(commands, key = { _, cmd -> "cmd_${cmd.name}" }) { index, cmd ->
                    val (icon, tint) = commandSymbol(cmd.category)
                    SuggestionRow(
                        title = "/${cmd.name}",
                        hint = cmd.parameterHint,
                        description = cmd.description,
                        tag = if (cmd.isLocalOnly) "Instant" else null,
                        separated = index > 0,
                        onClick = { onSelect(cmd) },
                    ) { SymbolTile(icon, tint) }
                }
            }
            if (skills.isNotEmpty()) {
                item(key = "header_skills") {
                    SuggestionHeader("Skills", skills.size, Modifier.padding(top = if (commands.isNotEmpty()) PocketSpacing.sm else 0.dp))
                }
                itemsIndexed(skills, key = { _, skill -> "skill_${skill.id}" }) { index, skill ->
                    SuggestionRow(
                        title = "/${skill.name}",
                        description = skill.description,
                        tag = skillSourceTitle(skill.source),
                        separated = index > 0,
                        onClick = { onSelectSkill?.invoke(skill) },
                    ) { SymbolTile(Icons.Outlined.AutoAwesome, colors.purple) }
                }
            }
        }
    }
}

/**
 * Files and folders to mention with @, on the same glass panel as [SlashCommandMenu] and with
 * the same adaptive height idea, a little shorter.
 */
@Composable
fun MentionMenu(
    files: List<WorkspaceEntry>,
    onSelect: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return

    val colors = PocketColors.current
    val maxMenuHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.28f).coerceIn(120.dp, 190.dp)

    SuggestionPanel(modifier) {
        Column(Modifier.fillMaxWidth()) {
            SuggestionHeader("Files and folders", files.size)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = maxMenuHeight),
                contentPadding = PaddingValues(bottom = PocketSpacing.xs),
            ) {
                itemsIndexed(files, key = { _, entry -> entry.path }) { index, entry ->
                    SuggestionRow(
                        title = entry.name,
                        // The name is the title, so the row names where it lives.
                        description = entry.path.substringBeforeLast('/', "").ifEmpty { "Project root" },
                        separated = index > 0,
                        onClick = { onSelect(entry) },
                    ) {
                        SymbolTile(
                            if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.Description,
                            if (entry.isDirectory) colors.blue else colors.gray,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionPanel(modifier: Modifier, content: @Composable () -> Unit) {
    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        material = LiquidGlassMaterial.Regular,
        shape = PocketShape.lg,
        layerSource = LiquidGlassLayers.Background,
        content = content,
    )
}

@Composable
private fun SuggestionHeader(title: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = PocketSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        SectionHeader(title, Modifier.weight(1f))
        Text(
            "$count",
            style = PocketType.footnote,
            color = PocketColors.current.tertiaryLabel,
            modifier = Modifier.padding(end = ListInset),
        )
    }
}

/** A compact suggestion: symbol tile, name with an optional argument hint, one line of description. */
@Composable
private fun SuggestionRow(
    title: String,
    description: String,
    separated: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
    tag: String? = null,
    leading: @Composable () -> Unit,
) {
    val colors = PocketColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateColorAsState(
        if (pressed) colors.tertiaryFill else Color.Transparent,
        PocketMotion.spec(PocketMotion.Token.Quick),
        label = "suggestionHighlight",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .hairlineAbove(if (separated) RowTextStart else null, colors.separator)
            .background(highlight)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(start = ListInset, end = ListInset, top = PocketSpacing.sm - 1.dp, bottom = PocketSpacing.sm - 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(RowTextStart - ListInset - ListIconTile))
        Column(Modifier.weight(1f)) {
            Row {
                Text(
                    title,
                    style = PocketType.subheadline.emphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alignByBaseline(),
                )
                if (hint != null) {
                    Spacer(Modifier.width(PocketSpacing.xs + 2.dp))
                    Text(
                        hint,
                        style = PocketType.codeSmall,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                    )
                }
            }
            if (description.isNotBlank()) {
                Text(
                    description,
                    style = PocketType.footnote,
                    color = colors.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (tag != null) {
            Spacer(Modifier.width(PocketSpacing.sm))
            Text(tag, style = PocketType.caption1, color = colors.secondaryLabel, maxLines = 1)
        }
    }
}

@Composable
@ReadOnlyComposable
private fun commandSymbol(category: SlashCommandCategory): Pair<ImageVector, Color> {
    val colors = PocketColors.current
    return when (category) {
        SlashCommandCategory.GENERAL -> Icons.Outlined.Code to colors.blue
        SlashCommandCategory.AGENT_WORKFLOW -> Icons.Outlined.SmartToy to colors.indigo
        SlashCommandCategory.CONFIG -> Icons.Outlined.Tune to colors.gray
        SlashCommandCategory.DIAGNOSTICS -> Icons.Outlined.Speed to colors.teal
        SlashCommandCategory.VCS -> Icons.Outlined.History to colors.orange
    }
}

private fun skillSourceTitle(source: SkillSource): String = when (source) {
    SkillSource.PROJECT -> "Project"
    SkillSource.LINKED -> "Linked"
    SkillSource.GLOBAL -> "Library"
    SkillSource.BUNDLED -> "Built-in"
    SkillSource.OTHER_PROJECT -> "Other project"
}

// =============================================================================================
// Token telemetry
// =============================================================================================

/** One line of token use and context fill, for a small glass chip. */
@Composable
fun TokenTelemetryBar(
    metrics: SessionTokenMetrics,
    modifier: Modifier = Modifier,
) {
    if (metrics.promptTokens == 0 && metrics.completionTokens == 0) return

    val colors = PocketColors.current
    val capacityPct = contextPercent(metrics)
    val numbers = PocketType.caption1.copy(fontFeatureSettings = "tnum")

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Speed, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(PocketSpacing.xs + 2.dp))
        Text(
            buildAnnotatedString {
                append("${formatCount(metrics.promptTokens)} in · ${formatCount(metrics.completionTokens)} out")
                if (metrics.cachedTokens > 0) {
                    append(" · ")
                    withStyle(SpanStyle(color = colors.green)) { append("${formatCount(metrics.cachedTokens)} cached") }
                }
            },
            style = numbers,
            color = colors.secondaryLabel,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(PocketSpacing.sm))
        Text(
            "Context ${formatPercent(capacityPct)}",
            style = numbers.medium,
            color = if (capacityPct > 80.0) colors.red else colors.secondaryLabel,
            maxLines = 1,
        )
    }
}

private fun contextPercent(metrics: SessionTokenMetrics): Double =
    if (metrics.contextWindowLimit > 0) {
        ((metrics.promptTokens.toDouble() / metrics.contextWindowLimit) * 100).coerceIn(0.0, 100.0)
    } else 0.0

private fun formatPercent(value: Double): String = String.format("%.1f%%", value)

private fun formatCount(value: Int): String = NumberFormat.getIntegerInstance().format(value)

private fun formatRemaining(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val time = when {
        s < 60 -> "${s}s"
        s < 3_600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3_600}h ${(s % 3_600) / 60}m"
    }
    return "$time left"
}

// =============================================================================================
// Activity sheet
// =============================================================================================

/**
 * Activity: this session's token use, then the subagents, background tasks, artifacts and timers
 * the agent started. Stopping a subagent or a task asks first.
 */
@Composable
fun AuxiliaryInspectorSheet(
    subagents: List<SubagentInfo>,
    tasks: List<BackgroundTaskInfo>,
    artifacts: List<ArtifactInfo>,
    timers: List<ScheduledTimerInfo>,
    onDismiss: () -> Unit,
    onTerminateSubagent: (String) -> Unit = {},
    onClearCompletedSubagents: () -> Unit = {},
    onTerminateTask: (String) -> Unit = {},
    onClearCompletedTasks: () -> Unit = {},
    onSelectSubagentForLogs: (SubagentInfo) -> Unit = {},
    onSelectTaskForLogs: (BackgroundTaskInfo) -> Unit = {},
    onOpenMemoryViewer: (() -> Unit)? = null,
    visible: Boolean = true,
    tokenMetrics: SessionTokenMetrics = SessionTokenMetrics(),
) {
    var stopSubagent by remember { mutableStateOf<SubagentInfo?>(null) }
    var stopTask by remember { mutableStateOf<BackgroundTaskInfo?>(null) }
    LaunchedEffect(visible) {
        // A confirmation belongs to one visit; don't bring it back the next time the sheet opens.
        if (!visible) {
            stopSubagent = null
            stopTask = null
        }
    }

    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Activity",
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        ActivityList(
            subagents = subagents,
            tasks = tasks,
            artifacts = artifacts,
            timers = timers,
            tokenMetrics = tokenMetrics,
            onOpenMemoryViewer = onOpenMemoryViewer,
            onClearCompletedSubagents = onClearCompletedSubagents,
            onClearCompletedTasks = onClearCompletedTasks,
            onSelectSubagent = onSelectSubagentForLogs,
            onSelectTask = onSelectTaskForLogs,
            onStopSubagent = { stopSubagent = it },
            onStopTask = { stopTask = it },
        )
    }

    if (visible) {
        stopSubagent?.let { subagent ->
            StopSubagentAlert(subagent, onDismiss = { stopSubagent = null }) {
                stopSubagent = null
                onTerminateSubagent(subagent.conversationId)
            }
        }
        stopTask?.let { task ->
            StopTaskAlert(task, onDismiss = { stopTask = null }) {
                stopTask = null
                onTerminateTask(task.taskId)
            }
        }
    }
}

@Composable
private fun ActivityList(
    subagents: List<SubagentInfo>,
    tasks: List<BackgroundTaskInfo>,
    artifacts: List<ArtifactInfo>,
    timers: List<ScheduledTimerInfo>,
    tokenMetrics: SessionTokenMetrics,
    onOpenMemoryViewer: (() -> Unit)?,
    onClearCompletedSubagents: () -> Unit,
    onClearCompletedTasks: () -> Unit,
    onSelectSubagent: (SubagentInfo) -> Unit,
    onSelectTask: (BackgroundTaskInfo) -> Unit,
    onStopSubagent: (SubagentInfo) -> Unit,
    onStopTask: (BackgroundTaskInfo) -> Unit,
) {
    val colors = PocketColors.current
    val accent = MaterialTheme.colorScheme.primary
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    val hasSession = tokenMetrics.promptTokens != 0 || tokenMetrics.completionTokens != 0
    val nothingRunning = subagents.isEmpty() && tasks.isEmpty() && artifacts.isEmpty() && timers.isEmpty()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
        var groups = 0
        fun nextGap(): Dp = if (groups++ == 0) PocketSpacing.xs else GroupGap

        if (hasSession) {
            val gap = nextGap()
            item(key = "session") { SessionSection(tokenMetrics, Modifier.padding(top = gap)) }
        }
        if (onOpenMemoryViewer != null) {
            val gap = nextGap()
            item(key = "memory") {
                ListSection(Modifier.padding(top = gap)) {
                    ListRow(
                        "Memory",
                        subtitle = "Facts kept across chats",
                        icon = Icons.Outlined.Psychology,
                        iconTile = colors.pink,
                        accessory = ListRowAccessory.Chevron,
                        onClick = onOpenMemoryViewer,
                    )
                }
            }
        }
        if (subagents.isNotEmpty()) {
            val gap = nextGap()
            val clearable = subagents.any { it.state.isTerminal }
            item(key = "subagents_header") { GroupHeader("Subagents", gap) }
            itemsIndexed(subagents, key = { _, subagent -> "subagent_${subagent.conversationId}" }) { index, subagent ->
                LazyGroupRow(isFirst = index == 0, isLast = index == subagents.lastIndex && !clearable, separatorStart = RowTextStart) {
                    SubagentRow(subagent, onOpen = { onSelectSubagent(subagent) }, onStop = { onStopSubagent(subagent) })
                }
            }
            if (clearable) {
                item(key = "subagents_clear") {
                    LazyGroupRow(isFirst = false, isLast = true) {
                        ListRow("Clear finished", titleColor = accent, onClick = onClearCompletedSubagents)
                    }
                }
            }
        }
        if (tasks.isNotEmpty()) {
            val gap = nextGap()
            val clearable = tasks.any { it.status != BackgroundTaskStatus.RUNNING }
            item(key = "tasks_header") { GroupHeader("Background tasks", gap) }
            itemsIndexed(tasks, key = { _, task -> "task_${task.taskId}" }) { index, task ->
                LazyGroupRow(isFirst = index == 0, isLast = index == tasks.lastIndex && !clearable, separatorStart = RowTextStart) {
                    TaskRow(task, onOpen = { onSelectTask(task) }, onStop = { onStopTask(task) })
                }
            }
            if (clearable) {
                item(key = "tasks_clear") {
                    LazyGroupRow(isFirst = false, isLast = true) {
                        ListRow("Clear finished", titleColor = accent, onClick = onClearCompletedTasks)
                    }
                }
            }
        }
        if (artifacts.isNotEmpty()) {
            val gap = nextGap()
            item(key = "artifacts_header") { GroupHeader("Artifacts", gap) }
            itemsIndexed(artifacts, key = { _, artifact -> "artifact_${artifact.id}" }) { index, artifact ->
                LazyGroupRow(isFirst = index == 0, isLast = index == artifacts.lastIndex, separatorStart = RowTextStart) {
                    ListRow(
                        title = artifact.title,
                        subtitle = listOf(artifact.summary, artifact.filePath.substringAfterLast('/'))
                            .filter { it.isNotBlank() }
                            .joinToString("\n")
                            .ifBlank { null },
                        subtitleMaxLines = 3,
                        icon = Icons.Outlined.Description,
                        iconTile = colors.indigo,
                        trailing = {
                            PocketIconButton(
                                Icons.Outlined.ContentCopy,
                                "Copy path of ${artifact.title}",
                                onClick = {
                                    clipboard.setText(AnnotatedString(artifact.filePath))
                                    banner("Path copied", BannerKind.Success)
                                },
                            )
                        },
                    )
                }
            }
        }
        if (timers.isNotEmpty()) {
            val gap = nextGap()
            item(key = "timers_header") { GroupHeader("Timers", gap) }
            itemsIndexed(timers, key = { _, timer -> "timer_${timer.taskId}" }) { index, timer ->
                LazyGroupRow(isFirst = index == 0, isLast = index == timers.lastIndex, separatorStart = RowTextStart) {
                    ListRow(
                        title = timer.prompt,
                        subtitle = if (timer.isCron) listOfNotNull("Repeats", timer.cronExpression).joinToString(" · ") else "One time",
                        icon = Icons.Outlined.Timer,
                        iconTile = colors.orange,
                        value = formatRemaining(timer.remainingSeconds),
                    )
                }
            }
        }
        if (nothingRunning) {
            val message = "Subagents, background commands, artifacts and timers the agent starts show up here."
            // Below the session rows a full empty state would sit off screen, so it shrinks to a short note.
            if (groups == 0) {
                item(key = "empty") { EmptyState(icon = Icons.Outlined.Layers, title = "Nothing running", message = message) }
            } else {
                val gap = nextGap()
                item(key = "empty") { QuietNote("Nothing running", message, Modifier.padding(top = gap)) }
            }
        }
    }
}

@Composable
private fun SessionSection(metrics: SessionTokenMetrics, modifier: Modifier = Modifier) {
    val colors = PocketColors.current
    val capacityPct = contextPercent(metrics)
    val full = capacityPct > 80.0
    ListSection(modifier, header = "Session") {
        ListRow("Input tokens", value = formatCount(metrics.promptTokens))
        ListRow("Output tokens", value = formatCount(metrics.completionTokens))
        if (metrics.cachedTokens > 0) ListRow("Cached tokens", value = formatCount(metrics.cachedTokens))
        ListRow(
            "Context used",
            trailing = {
                ProgressRing(
                    progress = (capacityPct / 100.0).toFloat(),
                    size = 18.dp,
                    strokeWidth = 2.5.dp,
                    color = if (full) colors.red else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(PocketSpacing.sm))
                Text(formatPercent(capacityPct), style = PocketType.body, color = if (full) colors.red else colors.secondaryLabel)
            },
        )
        if (metrics.estimatedCostUsd > 0.0) ListRow("Estimated cost", value = String.format("$%.2f", metrics.estimatedCostUsd))
    }
}

/** A small centered title and message for an empty part of a list that has other content. */
@Composable
private fun QuietNote(title: String, message: String, modifier: Modifier = Modifier) {
    val colors = PocketColors.current
    Column(
        modifier.fillMaxWidth().padding(horizontal = PocketSpacing.jumbo, vertical = PocketSpacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = PocketType.headline, color = colors.secondaryLabel, textAlign = TextAlign.Center)
        Spacer(Modifier.height(PocketSpacing.xxs))
        Text(message, style = PocketType.footnote, color = colors.tertiaryLabel, textAlign = TextAlign.Center)
    }
}

/** A section title inside a lazy list of grouped rows. */
@Composable
private fun GroupHeader(text: String, top: Dp) {
    SectionHeader(text, Modifier.padding(horizontal = ListInset).padding(top = top))
}

private data class StatusStyle(val label: String, val color: Color)

@Composable
@ReadOnlyComposable
private fun subagentStatus(state: SubagentState): StatusStyle {
    val colors = PocketColors.current
    return when (state) {
        SubagentState.RUNNING -> StatusStyle("Running", colors.green)
        SubagentState.WAITING_FOR_INPUT -> StatusStyle("Needs input", colors.orange)
        SubagentState.WAITING_FOR_DEPENDENTS -> StatusStyle("Waiting on others", colors.indigo)
        SubagentState.DONE -> StatusStyle("Done", colors.gray)
        SubagentState.ERRORED -> StatusStyle("Failed", colors.red)
        SubagentState.IDLE -> StatusStyle("Idle", colors.gray)
        SubagentState.TERMINATED -> StatusStyle("Stopped", colors.gray)
    }
}

@Composable
@ReadOnlyComposable
private fun taskStatus(status: BackgroundTaskStatus): StatusStyle {
    val colors = PocketColors.current
    return when (status) {
        BackgroundTaskStatus.RUNNING -> StatusStyle("Running", colors.green)
        BackgroundTaskStatus.COMPLETED -> StatusStyle("Done", colors.gray)
        BackgroundTaskStatus.FAILED -> StatusStyle("Failed", colors.red)
        BackgroundTaskStatus.TERMINATED -> StatusStyle("Stopped", colors.gray)
    }
}

private fun taskSummary(task: BackgroundTaskInfo, status: String): String = listOfNotNull(
    status,
    task.taskId.takeIf { task.commandLine.isNotBlank() },
    task.exitCode?.let { "exit $it" },
).joinToString(" · ")

@Composable
private fun SubagentRow(subagent: SubagentInfo, onOpen: () -> Unit, onStop: () -> Unit) {
    val colors = PocketColors.current
    val status = subagentStatus(subagent.state)
    val details = listOfNotNull(
        listOf(status.label, subagent.typeName).filter { it.isNotBlank() }.joinToString(" · "),
        subagent.currentActivity.takeIf { it.isNotBlank() },
        subagent.error,
    ).joinToString("\n")
    ListRow(
        title = subagent.role,
        subtitle = details,
        subtitleColor = if (subagent.error != null) colors.red else null,
        subtitleMaxLines = 4,
        icon = Icons.Outlined.SmartToy,
        iconTile = status.color,
        accessory = ListRowAccessory.Chevron,
        onClick = onOpen,
        trailing = if (!subagent.state.isTerminal) {
            { StopButton("Stop ${subagent.role}", onStop) }
        } else null,
    )
}

@Composable
private fun TaskRow(task: BackgroundTaskInfo, onOpen: () -> Unit, onStop: () -> Unit) {
    val colors = PocketColors.current
    val status = taskStatus(task.status)
    val tail = remember(task.liveOutputTail) { task.liveOutputTail.trimEnd().lines().takeLast(3).filter { it.isNotBlank() } }
    Column {
        ListRow(
            title = task.commandLine.ifBlank { task.taskId },
            subtitle = taskSummary(task, status.label),
            subtitleMaxLines = 1,
            icon = Icons.Outlined.Terminal,
            iconTile = status.color,
            accessory = ListRowAccessory.Chevron,
            onClick = onOpen,
            trailing = if (task.status == BackgroundTaskStatus.RUNNING) {
                { StopButton("Stop task", onStop) }
            } else null,
        )
        if (tail.isNotEmpty()) {
            Column(
                Modifier
                    .padding(start = RowTextStart, end = ListInset, bottom = PocketSpacing.md)
                    .fillMaxWidth()
                    .clip(PocketShape.sm)
                    .background(colors.codeSurface)
                    .clickable(onClickLabel = "Open log", onClick = onOpen)
                    .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.sm),
            ) {
                // One line per output line, cut at the edge, so a long line can't push the others out.
                tail.forEach { line ->
                    Text(
                        line,
                        style = PocketType.codeSmall,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StopButton(description: String, onClick: () -> Unit) {
    PocketIconButton(Icons.Outlined.StopCircle, description, onClick, tint = PocketColors.current.red)
}

@Composable
private fun StopSubagentAlert(subagent: SubagentInfo, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PocketAlert(
        onDismiss = onDismiss,
        title = "Stop “${subagent.role}”?",
        message = "The subagent stops right away.",
        actions = listOf(
            AlertAction("Cancel", AlertRole.Cancel, onClick = onDismiss),
            AlertAction("Stop", AlertRole.Destructive, onClick = onConfirm),
        ),
    )
}

@Composable
private fun StopTaskAlert(task: BackgroundTaskInfo, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PocketAlert(
        onDismiss = onDismiss,
        title = "Stop this task?",
        message = "${task.commandLine.ifBlank { task.taskId }} stops right away.",
        actions = listOf(
            AlertAction("Cancel", AlertRole.Cancel, onClick = onDismiss),
            AlertAction("Stop", AlertRole.Destructive, onClick = onConfirm),
        ),
    )
}

// =============================================================================================
// Skills and rules
// =============================================================================================

private enum class HubTab(val title: String) { Rules("Rules"), Personas("Personas"), Skills("Skills"), Prompt("Prompt") }

/**
 * Skills and rules for the open project: the scope that decides which apply, project rules,
 * global personas, skills (active, library, other projects) and a preview of what is added to
 * the prompt. Editors open as sheets on top; unlinking a skill asks first.
 */
@Composable
fun SkillsManagerDialog(
    config: ProjectCustomizationConfig = ProjectCustomizationConfig(""),
    activeSkills: List<SkillInfo> = emptyList(),
    otherProjectsSkills: Map<Project, List<SkillInfo>> = emptyMap(),
    globalSkills: List<SkillInfo> = emptyList(),
    activeRules: List<RuleInfo> = emptyList(),
    projectRules: List<RuleInfo> = emptyList(),
    globalRules: List<RuleInfo> = emptyList(),
    onSetScopeMode: (CustomizationScopeMode) -> Unit = {},
    onToggleSkill: (String) -> Unit = {},
    onToggleRule: (String) -> Unit = {},
    onLinkSkill: (String, String, String) -> Unit = { _, _, _ -> },
    onUnlinkSkill: (String) -> Unit = {},
    onImportSkill: (File, String) -> Unit = { _, _ -> },
    onPromoteSkill: (File, String) -> Unit = { _, _ -> },
    onPromoteRule: (File, String) -> Unit = { _, _ -> },
    onSaveRule: (String, String) -> Unit = { _, _ -> },
    onCreateSkill: (String, String, String) -> Unit = { _, _, _ -> },
    onDismiss: () -> Unit,
    visible: Boolean = true,
) {
    var tab by remember { mutableStateOf(HubTab.Rules) }
    var skillSearchQuery by remember { mutableStateOf("") }
    var showCreateSkillDialog by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<RuleInfo?>(null) }
    var showNewRuleDialog by remember { mutableStateOf(false) }
    var expandedRuleId by remember { mutableStateOf<String?>(null) }
    var expandedSkillId by remember { mutableStateOf<String?>(null) }
    var pendingUnlink by remember { mutableStateOf<SkillInfo?>(null) }
    LaunchedEffect(visible) {
        // Editors and confirmations belong to one visit; don't bring them back next time.
        if (!visible) {
            showCreateSkillDialog = false
            editingRule = null
            showNewRuleDialog = false
            pendingUnlink = null
        }
    }
    val toggleRule = { id: String -> expandedRuleId = if (expandedRuleId == id) null else id }
    val toggleSkill = { id: String -> expandedSkillId = if (expandedSkillId == id) null else id }
    val skillDir = { skill: SkillInfo -> File(skill.filePath).parentFile ?: File(skill.filePath) }

    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Skills and rules",
        initialDetent = SheetDetent.Large,
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        ScopeSection(config.scopeMode, onSetScopeMode)
        SegmentedControl(
            items = HubTab.entries,
            selected = tab,
            onSelect = { tab = it },
            label = { it.title },
            modifier = Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.md),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                HubTab.Rules -> ProjectRulesList(
                    rules = projectRules,
                    expandedId = expandedRuleId,
                    onToggleExpanded = toggleRule,
                    onEdit = { editingRule = it },
                    onPromote = { onPromoteRule(File(it.filePath), it.name) },
                    onNew = { showNewRuleDialog = true },
                )
                HubTab.Personas -> PersonasList(
                    rules = globalRules,
                    activeRules = activeRules,
                    expandedId = expandedRuleId,
                    onToggleExpanded = toggleRule,
                    onToggleRule = onToggleRule,
                )
                HubTab.Skills -> SkillsList(
                    query = skillSearchQuery,
                    onQuery = { skillSearchQuery = it },
                    activeSkills = activeSkills,
                    globalSkills = globalSkills,
                    otherProjectsSkills = otherProjectsSkills,
                    expandedId = expandedSkillId,
                    onToggleExpanded = toggleSkill,
                    onToggleSkill = onToggleSkill,
                    onPromote = { onPromoteSkill(skillDir(it), it.name) },
                    onImport = { onImportSkill(skillDir(it), it.name) },
                    onLink = { project, skill -> onLinkSkill(project.id, skill.name, ".agents/skills/${skill.name}") },
                    onUnlink = { pendingUnlink = it },
                    onNewSkill = { showCreateSkillDialog = true },
                )
                HubTab.Prompt -> PromptPreview(activeRules, activeSkills)
            }
        }
    }

    if (visible && showCreateSkillDialog) {
        CreateSkillDialog(
            onCreate = { name, desc, body ->
                onCreateSkill(name, desc, body)
                showCreateSkillDialog = false
            },
            onDismiss = { showCreateSkillDialog = false },
        )
    }

    if (visible) {
        editingRule?.let { rule ->
            EditRuleDialog(
                rule = rule,
                onSave = { name, content ->
                    onSaveRule(name, content)
                    editingRule = null
                },
                onDismiss = { editingRule = null },
            )
        }
    }

    if (visible && showNewRuleDialog) {
        EditRuleDialog(
            rule = RuleInfo(
                id = "",
                name = "NEW_RULE.md",
                title = "New Rule",
                description = "",
                filePath = "",
                source = RuleSource.PROJECT,
                content = "# Custom Project Rule\n\nSpecify guidelines here.\n",
            ),
            onSave = { name, content ->
                onSaveRule(name, content)
                showNewRuleDialog = false
            },
            onDismiss = { showNewRuleDialog = false },
        )
    }

    if (visible) {
        pendingUnlink?.let { skill ->
            PocketAlert(
                onDismiss = { pendingUnlink = null },
                title = "Unlink “${skill.name}”?",
                message = "It stays in ${skill.sourceProjectName ?: "its project"}. You can link it again later.",
                actions = listOf(
                    AlertAction("Cancel", AlertRole.Cancel) { pendingUnlink = null },
                    AlertAction("Unlink", AlertRole.Destructive) {
                        pendingUnlink = null
                        onUnlinkSkill(skill.id)
                    },
                ),
            )
        }
    }
}

private fun scopeTitle(mode: CustomizationScopeMode): String = when (mode) {
    CustomizationScopeMode.INHERIT_AND_MERGE -> "Inherit and merge"
    CustomizationScopeMode.PROJECT_ONLY -> "Project only"
    CustomizationScopeMode.GLOBAL_ONLY -> "Global only"
    CustomizationScopeMode.CUSTOM -> "Custom"
}

@Composable
private fun ScopeSection(mode: CustomizationScopeMode, onSetScopeMode: (CustomizationScopeMode) -> Unit) {
    val anchor = rememberOverlayAnchor()
    var menu by remember { mutableStateOf(false) }
    ListSection(Modifier.padding(top = PocketSpacing.xs), footer = mode.description) {
        ListRow(
            "Scope",
            modifier = Modifier.overlayAnchor(anchor),
            value = scopeTitle(mode),
            onClick = { menu = true },
            trailing = {
                Icon(
                    Icons.Outlined.UnfoldMore,
                    contentDescription = null,
                    tint = PocketColors.current.tertiaryLabel,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
    GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
        CustomizationScopeMode.entries.forEach { option ->
            GlassMenuItem(scopeTitle(option), { onSetScopeMode(option) }, checked = option == mode)
        }
    }
}

@Composable
private fun ProjectRulesList(
    rules: List<RuleInfo>,
    expandedId: String?,
    onToggleExpanded: (String) -> Unit,
    onEdit: (RuleInfo) -> Unit,
    onPromote: (RuleInfo) -> Unit,
    onNew: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
        item(key = "header") { GroupHeader("Project rules", PocketSpacing.sm) }
        itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
            LazyGroupRow(isFirst = index == 0, isLast = false, separatorStart = RowTextStart) {
                ProjectRuleRow(
                    rule = rule,
                    expanded = expandedId == rule.id,
                    onToggleExpanded = { onToggleExpanded(rule.id) },
                    onEdit = { onEdit(rule) },
                    onPromote = { onPromote(rule) },
                )
            }
        }
        item(key = "new") {
            LazyGroupRow(isFirst = rules.isEmpty(), isLast = true, separatorStart = RowTextStart) {
                ListRow("New rule", icon = Icons.Outlined.Add, iconTile = accent, titleColor = accent, onClick = onNew)
            }
        }
        if (rules.isEmpty()) {
            item(key = "empty") { SectionFooter("No rules in this project yet.", Modifier.padding(horizontal = ListInset)) }
        }
    }
}

@Composable
private fun ProjectRuleRow(
    rule: RuleInfo,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEdit: () -> Unit,
    onPromote: () -> Unit,
) {
    val colors = PocketColors.current
    val haptics = rememberHaptics()
    val anchor = rememberOverlayAnchor()
    var menu by remember { mutableStateOf(false) }
    Column {
        ListRow(
            title = rule.name,
            subtitle = rule.title.takeIf { it.isNotBlank() },
            subtitleMaxLines = 1,
            icon = Icons.Outlined.Description,
            iconTile = colors.blue,
            modifier = Modifier.expandedState(expanded),
            onClick = onToggleExpanded,
            onLongClick = {
                haptics.longPress()
                menu = true
            },
            trailing = {
                PocketIconButton(
                    Icons.Outlined.MoreHoriz,
                    "Options for ${rule.name}",
                    onClick = { menu = true },
                    modifier = Modifier.overlayAnchor(anchor),
                    tint = colors.secondaryLabel,
                )
                ExpandChevron(expanded)
            },
        )
        ContentPreview(visible = expanded && rule.content.isNotBlank(), text = rule.content, limit = 600)
    }
    GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
        GlassMenuItem("Edit", onEdit, icon = Icons.Outlined.Edit)
        GlassMenuItem("Make global", onPromote, icon = Icons.Outlined.Public)
    }
}

@Composable
private fun PersonasList(
    rules: List<RuleInfo>,
    activeRules: List<RuleInfo>,
    expandedId: String?,
    onToggleExpanded: (String) -> Unit,
    onToggleRule: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
        item(key = "header") { GroupHeader("Personas and global rules", PocketSpacing.sm) }
        if (rules.isEmpty()) {
            item(key = "empty") { SectionFooter("No global rules yet.", Modifier.padding(horizontal = ListInset)) }
        }
        itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
            val isEnabled = activeRules.any { it.name.equals(rule.name, ignoreCase = true) }
            val expanded = expandedId == rule.id
            val (icon, tint) = personaSymbol(rule.name)
            LazyGroupRow(isFirst = index == 0, isLast = index == rules.lastIndex, separatorStart = RowTextStart) {
                Column {
                    ListRow(
                        title = rule.title,
                        subtitle = rule.description.takeIf { it.isNotBlank() },
                        subtitleMaxLines = if (expanded) 10 else 2,
                        icon = icon,
                        iconTile = tint,
                        modifier = Modifier.expandedState(expanded),
                        onClick = { onToggleExpanded(rule.id) },
                        trailing = {
                            PocketToggle(checked = isEnabled, onCheckedChange = { onToggleRule(rule.id) }, contentDescription = rule.title)
                        },
                    )
                    ContentPreview(
                        visible = expanded && rule.content.isNotBlank(),
                        text = rule.content,
                        limit = 800,
                        caption = rule.name,
                    )
                }
            }
        }
    }
}

@Composable
@ReadOnlyComposable
private fun personaSymbol(name: String): Pair<ImageVector, Color> {
    val colors = PocketColors.current
    return when {
        name.contains("coding") -> Icons.Outlined.Code to colors.blue
        name.contains("architect") -> Icons.Outlined.AccountTree to colors.blue
        name.contains("design") -> Icons.Outlined.Palette to colors.pink
        name.contains("debug") -> Icons.Outlined.BugReport to colors.green
        name.contains("perf") -> Icons.Outlined.Speed to colors.purple
        name.contains("sec") -> Icons.Outlined.Shield to colors.red
        else -> Icons.Outlined.Person to colors.indigo
    }
}

@Composable
@ReadOnlyComposable
private fun skillTileColor(source: SkillSource): Color {
    val colors = PocketColors.current
    return when (source) {
        SkillSource.PROJECT -> colors.blue
        SkillSource.LINKED -> colors.green
        SkillSource.GLOBAL -> colors.purple
        SkillSource.BUNDLED -> colors.gray
        SkillSource.OTHER_PROJECT -> colors.teal
    }
}

@Composable
private fun SkillsList(
    query: String,
    onQuery: (String) -> Unit,
    activeSkills: List<SkillInfo>,
    globalSkills: List<SkillInfo>,
    otherProjectsSkills: Map<Project, List<SkillInfo>>,
    expandedId: String?,
    onToggleExpanded: (String) -> Unit,
    onToggleSkill: (String) -> Unit,
    onPromote: (SkillInfo) -> Unit,
    onImport: (SkillInfo) -> Unit,
    onLink: (Project, SkillInfo) -> Unit,
    onUnlink: (SkillInfo) -> Unit,
    onNewSkill: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val matches = { skill: SkillInfo ->
        query.isBlank() || skill.name.contains(query, ignoreCase = true) || skill.description.contains(query, ignoreCase = true)
    }
    val active = activeSkills.filter(matches)
    val library = globalSkills.filter(matches)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
        item(key = "search") {
            SearchField(
                query,
                onQuery,
                placeholder = "Search skills",
                modifier = Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm),
            )
        }

        item(key = "active_header") { GroupHeader("Active in this project", PocketSpacing.xs) }
        if (active.isEmpty()) {
            item(key = "active_empty") { EmptyNote("No skills are active in this project.") }
        }
        itemsIndexed(active, key = { _, skill -> "active_${skill.id}" }) { index, skill ->
            LazyGroupRow(isFirst = index == 0, isLast = index == active.lastIndex, separatorStart = RowTextStart) {
                ActiveSkillRow(
                    skill = skill,
                    expanded = expandedId == skill.id,
                    onToggleExpanded = { onToggleExpanded(skill.id) },
                    onToggle = { onToggleSkill(skill.id) },
                    onPromote = { onPromote(skill) },
                    onImport = { onImport(skill) },
                    onUnlink = { onUnlink(skill) },
                )
            }
        }

        item(key = "library_header") { GroupHeader("Library", GroupGap) }
        itemsIndexed(library, key = { _, skill -> "library_${skill.id}" }) { index, skill ->
            val isEnabled = activeSkills.any { it.name.equals(skill.name, ignoreCase = true) && it.isEnabled }
            LazyGroupRow(isFirst = index == 0, isLast = false, separatorStart = RowTextStart) {
                ListRow(
                    title = skill.name,
                    subtitle = skillSubtitle(skillSourceTitle(skill.source), skill.description),
                    icon = Icons.Outlined.AutoAwesome,
                    iconTile = skillTileColor(skill.source),
                    trailing = {
                        PocketToggle(checked = isEnabled, onCheckedChange = { onToggleSkill(skill.id) }, contentDescription = skill.name)
                    },
                )
            }
        }
        item(key = "library_new") {
            LazyGroupRow(isFirst = library.isEmpty(), isLast = true, separatorStart = RowTextStart) {
                ListRow("New skill", icon = Icons.Outlined.Add, iconTile = accent, titleColor = accent, onClick = onNewSkill)
            }
        }

        if (otherProjectsSkills.isEmpty()) {
            item(key = "other_header") { GroupHeader("Other projects", GroupGap) }
            item(key = "other_empty") { EmptyNote("No other projects have skills.") }
        } else {
            otherProjectsSkills.forEach { (project, skills) ->
                val matching = skills.filter(matches)
                if (matching.isNotEmpty()) {
                    item(key = "other_header_${project.id}") { GroupHeader("From ${project.name}", GroupGap) }
                    itemsIndexed(matching, key = { index, _ -> "other_${project.id}_$index" }) { index, skill ->
                        LazyGroupRow(isFirst = index == 0, isLast = index == matching.lastIndex, separatorStart = RowTextStart) {
                            OtherProjectSkillRow(
                                skill = skill,
                                onLink = { onLink(project, skill) },
                                onImport = { onImport(skill) },
                                onPromote = { onPromote(skill) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun skillSubtitle(source: String, description: String): String =
    if (description.isBlank()) source else "$source · $description"

@Composable
private fun ActiveSkillRow(
    skill: SkillInfo,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggle: () -> Unit,
    onPromote: () -> Unit,
    onImport: () -> Unit,
    onUnlink: () -> Unit,
) {
    val colors = PocketColors.current
    val anchor = rememberOverlayAnchor()
    var menu by remember { mutableStateOf(false) }
    val source = when (skill.source) {
        SkillSource.PROJECT -> "This project"
        SkillSource.LINKED -> "Linked from ${skill.sourceProjectName ?: "another project"}"
        SkillSource.GLOBAL -> "Library"
        SkillSource.BUNDLED -> "Built-in"
        SkillSource.OTHER_PROJECT -> "Other project"
    }
    val hasMenu = skill.source == SkillSource.PROJECT || skill.source == SkillSource.LINKED
    Column {
        ListRow(
            title = skill.name,
            subtitle = skillSubtitle(source, skill.description),
            subtitleMaxLines = if (expanded) 10 else 2,
            icon = Icons.Outlined.AutoAwesome,
            iconTile = skillTileColor(skill.source),
            modifier = Modifier.expandedState(expanded),
            onClick = onToggleExpanded,
            trailing = {
                if (hasMenu) {
                    PocketIconButton(
                        Icons.Outlined.MoreHoriz,
                        "Options for ${skill.name}",
                        onClick = { menu = true },
                        modifier = Modifier.overlayAnchor(anchor),
                        tint = colors.secondaryLabel,
                    )
                }
                PocketToggle(checked = skill.isEnabled, onCheckedChange = { onToggle() }, contentDescription = skill.name)
            },
        )
        ContentPreview(visible = expanded && skill.markdownContent != null, text = skill.markdownContent.orEmpty(), limit = 600)
    }
    if (hasMenu) {
        GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
            if (skill.source == SkillSource.PROJECT) {
                GlassMenuItem("Add to library", onPromote, icon = Icons.Outlined.Public)
            } else {
                GlassMenuItem("Copy to this project", onImport, icon = Icons.Outlined.Download)
                GlassMenuItem("Unlink", onUnlink, icon = Icons.Outlined.LinkOff, destructive = true)
            }
        }
    }
}

@Composable
private fun OtherProjectSkillRow(
    skill: SkillInfo,
    onLink: () -> Unit,
    onImport: () -> Unit,
    onPromote: () -> Unit,
) {
    val colors = PocketColors.current
    val anchor = rememberOverlayAnchor()
    var menu by remember { mutableStateOf(false) }
    ListRow(
        title = skill.name,
        subtitle = skill.description.takeIf { it.isNotBlank() },
        icon = Icons.Outlined.AutoAwesome,
        iconTile = skillTileColor(skill.source),
        onClick = { menu = true },
        trailing = {
            PocketIconButton(
                Icons.Outlined.MoreHoriz,
                "Options for ${skill.name}",
                onClick = { menu = true },
                modifier = Modifier.overlayAnchor(anchor),
                tint = colors.secondaryLabel,
            )
        },
    )
    GlassMenu(expanded = menu, onDismiss = { menu = false }, anchor = anchor) {
        GlassMenuItem("Link to this project", onLink, icon = Icons.Outlined.Link)
        GlassMenuItem("Copy to this project", onImport, icon = Icons.Outlined.Download)
        GlassMenuItem("Add to library", onPromote, icon = Icons.Outlined.Public)
    }
}

@Composable
private fun PromptPreview(activeRules: List<RuleInfo>, activeSkills: List<SkillInfo>) {
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    val previewText = remember(activeRules, activeSkills) {
        buildString {
            val rulesEnabled = activeRules.filter { it.isEnabled }
            if (rulesEnabled.isNotEmpty()) {
                appendLine("<user_rules>")
                rulesEnabled.forEach { r ->
                    appendLine("<RULE[${r.name}]>")
                    appendLine(r.content.trim())
                    appendLine("</RULE[${r.name}]>")
                }
                appendLine("</user_rules>")
                appendLine()
            }
            val skillsEnabled = activeSkills.filter { it.isEnabled }
            if (skillsEnabled.isNotEmpty()) {
                appendLine("<skills>")
                appendLine("Available skills:")
                skillsEnabled.forEach { s ->
                    appendLine("- ${s.name} (${s.filePath}): ${s.description}")
                }
                appendLine("</skills>")
            }
            if (rulesEnabled.isEmpty() && skillsEnabled.isEmpty()) {
                appendLine("(No rules or skills are active in the current scope)")
            }
        }
    }
    val lines = remember(previewText) { previewText.trimEnd().lines() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ListInset).padding(top = PocketSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionHeader("Added to the prompt", Modifier.weight(1f))
            PocketButton(
                "Copy",
                onClick = {
                    clipboard.setText(AnnotatedString(previewText))
                    banner("Copied", BannerKind.Success)
                },
                style = PocketButtonStyle.Plain,
                size = PocketButtonSize.Small,
                icon = Icons.Outlined.ContentCopy,
            )
        }
        LogView(
            lines = lines,
            emptyText = "",
            followTail = false,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = ListInset).padding(bottom = PocketSpacing.lg),
        )
    }
}

@Composable
private fun EditRuleDialog(
    rule: RuleInfo,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var fileName by remember { mutableStateOf(rule.name.let { if (it.endsWith(".md")) it else "$it.md" }) }
    var content by remember { mutableStateOf(rule.content) }

    GlassSheet(
        onDismiss = onDismiss,
        title = if (rule.id.isBlank()) "New rule" else "Edit rule",
        detents = listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Cancel", onDismiss) },
        trailing = {
            SheetTextButton(
                "Save",
                { onSave(fileName, content) },
                emphasized = true,
                enabled = fileName.isNotBlank() && content.isNotBlank(),
            )
        },
    ) {
        FormColumn {
            PocketTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = "File name",
                placeholder = "coding.md",
                helper = "For example GEMINI.md or coding.md.",
                monospace = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            PocketTextField(
                value = content,
                onValueChange = { content = it },
                label = "Rule",
                placeholder = "Markdown guidelines for the agent",
                singleLine = false,
                minLines = 8,
                maxLines = 14,
                monospace = true,
            )
        }
    }
}

@Composable
private fun CreateSkillDialog(
    onCreate: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    GlassSheet(
        onDismiss = onDismiss,
        title = "New skill",
        detents = listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Cancel", onDismiss) },
        trailing = {
            SheetTextButton(
                "Create",
                { onCreate(name, description, instructions) },
                emphasized = true,
                enabled = name.isNotBlank() && description.isNotBlank(),
            )
        },
    ) {
        FormColumn {
            PocketTextField(
                value = name,
                onValueChange = { name = it },
                label = "Name",
                placeholder = "test-runner",
                monospace = true,
                focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            PocketTextField(
                value = description,
                onValueChange = { description = it },
                label = "Description",
                placeholder = "When the agent should use it",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            PocketTextField(
                value = instructions,
                onValueChange = { instructions = it },
                label = "Instructions",
                placeholder = "Markdown steps the agent follows",
                singleLine = false,
                minLines = 5,
                maxLines = 12,
                monospace = true,
            )
        }
        LaunchedEffect(Unit) {
            // Let the sheet finish rising before the keyboard pushes it.
            kotlinx.coroutines.delay(SheetFocusDelayMillis)
            runCatching { focus.requestFocus() }
        }
    }
}

/** Fields of a short form in a sheet; scrolls when the keyboard leaves too little room. */
@Composable
private fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ListInset)
            .padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl),
        verticalArrangement = Arrangement.spacedBy(PocketSpacing.lg),
        content = content,
    )
}

// =============================================================================================
// Model picker
// =============================================================================================

/** Antigravity models offered until the account's live model list has loaded. */
internal val ANTIGRAVITY_FALLBACK_MODELS = listOf(
    "gemini-3.8-flash-high",
    "gemini-3.8-pro",
    "gemini-3.6-flash-high",
    "claude-sonnet-4-6",
    "claude-opus-4-6-thinking",
)

/**
 * Quick model switch. Choosing a model applies it and closes the sheet. The caller supplies the
 * list for the active agent; an empty list means the provider has no model list to offer.
 */
@Composable
fun ModelPickerDialog(
    currentModel: String,
    availableModels: List<String>,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
    provider: ProviderKind? = null,
    visible: Boolean = true,
) {
    val isClaude = provider == ProviderKind.CLAUDE
    val models = availableModels
    // Short lists fit their content; long ones scroll in a resizable sheet.
    val fits = models.size <= 8

    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = if (isClaude) "Claude model" else "Model",
        detents = if (fits) listOf(SheetDetent.Fit) else listOf(SheetDetent.Medium, SheetDetent.Large),
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        LazyColumn(
            if (fits) Modifier.fillMaxWidth() else Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = PocketSpacing.xs, bottom = PocketSpacing.xxl),
        ) {
            if (models.isEmpty()) {
                item {
                    LazyGroupRow(isFirst = true, isLast = true) {
                        ListRow(
                            title = "No model list for this provider",
                            subtitle = "Type /model followed by a model ID to choose one.",
                            subtitleMaxLines = 2,
                        )
                    }
                }
            }
            itemsIndexed(models) { index, modelId ->
                val isSelected = modelId.equals(currentModel, ignoreCase = true)
                val descriptor = if (isClaude) {
                    CLAUDE_SUBSCRIPTION_MODELS.firstOrNull { it.id.equals(modelId, ignoreCase = true) }
                } else null
                val description: String? = if (isClaude) {
                    when (modelId.lowercase()) {
                        "default" -> "Recommended for most work"
                        "sonnet" -> "Fast and highly capable"
                        "opus" -> "Deepest reasoning and analysis"
                        "haiku" -> "Fastest and lightest"
                        "fable" -> "Experimental"
                        else -> null
                    }
                } else null
                LazyGroupRow(isFirst = index == 0, isLast = index == models.lastIndex) {
                    ListRow(
                        title = descriptor?.displayName ?: modelId,
                        subtitle = description,
                        subtitleMaxLines = 1,
                        accessory = if (isSelected) ListRowAccessory.Check else ListRowAccessory.None,
                        onClick = {
                            onSelectModel(modelId)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

/** Quick switch for Claude's reasoning effort. Choosing a level applies it and closes the sheet. */
@Composable
fun ClaudeThinkingPickerDialog(
    currentLevel: String,
    onSelectLevel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = ClaudeThinkingLevel.fromStored(currentLevel)
    val levels = ClaudeThinkingLevel.entries
    GlassSheet(
        onDismiss = onDismiss,
        title = "Thinking effort",
        detents = listOf(SheetDetent.Fit),
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        LazyColumn(
            Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(top = PocketSpacing.xs, bottom = PocketSpacing.xxl),
        ) {
            itemsIndexed(levels, key = { _, level -> level.id }) { index, level ->
                LazyGroupRow(isFirst = index == 0, isLast = index == levels.lastIndex) {
                    ListRow(
                        title = level.displayName,
                        subtitle = level.description,
                        subtitleMaxLines = 2,
                        accessory = if (level == current) ListRowAccessory.Check else ListRowAccessory.None,
                        onClick = {
                            onSelectLevel(level.storageValue)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

// =============================================================================================
// Log viewers
// =============================================================================================

/**
 * A background task's output, following the newest lines like a terminal. Running tasks take
 * input and can be stopped (after a confirmation).
 */
@Composable
fun TaskLogViewerDialog(
    task: BackgroundTaskInfo,
    onDismiss: () -> Unit,
    onTerminate: (String) -> Unit = {},
    onSendInput: (String, String) -> Unit = { _, _ -> },
) {
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    var stdinDraft by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val isRunning = task.status == BackgroundTaskStatus.RUNNING
    val sendInput = {
        if (stdinDraft.isNotBlank()) {
            onSendInput(task.taskId, stdinDraft)
            stdinDraft = ""
        }
    }

    GlassSheet(
        onDismiss = onDismiss,
        title = "Task log",
        initialDetent = SheetDetent.Large,
        leading = {
            PocketIconButton(
                Icons.Outlined.ContentCopy,
                "Copy log",
                onClick = {
                    clipboard.setText(AnnotatedString(task.liveOutputTail))
                    banner("Log copied", BannerKind.Success)
                },
            )
        },
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        val status = taskStatus(task.status)
        ListSection(Modifier.padding(top = PocketSpacing.xs)) {
            ListRow(
                title = task.commandLine.ifBlank { task.taskId },
                subtitle = taskSummary(task, status.label),
                subtitleMaxLines = 1,
                icon = Icons.Outlined.Terminal,
                iconTile = status.color,
            )
        }
        val lines = remember(task.liveOutputTail) { logLines(task.liveOutputTail) }
        LogView(
            lines = lines,
            emptyText = "No output yet",
            followTail = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ListInset)
                .padding(top = PocketSpacing.md, bottom = if (isRunning) 0.dp else PocketSpacing.lg),
        )
        if (isRunning) {
            InputBar(
                value = stdinDraft,
                onValueChange = { stdinDraft = it },
                placeholder = "Send input",
                sendDescription = "Send input",
                monospace = true,
                onSend = sendInput,
                onStop = { confirmStop = true },
            )
        }
    }

    if (confirmStop) {
        StopTaskAlert(task, onDismiss = { confirmStop = false }) {
            confirmStop = false
            onTerminate(task.taskId)
        }
    }
}

/**
 * A subagent's activity and its raw transcript. Running subagents take messages and can be
 * stopped (after a confirmation).
 */
@Composable
fun SubagentTranscriptViewerDialog(
    subagent: SubagentInfo,
    onDismiss: () -> Unit,
    onSendMessage: (String, String) -> Unit = { _, _ -> },
    onTerminate: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    val banner = rememberBanner()
    var selectedTab by remember { mutableIntStateOf(0) }
    var messageDraft by remember { mutableStateOf("") }
    var confirmStop by remember { mutableStateOf(false) }
    val active = !subagent.state.isTerminal

    val transcriptLines = remember(subagent.transcriptPath) {
        runCatching {
            subagent.transcriptPath?.let { path ->
                java.io.File(path).takeIf { it.exists() }?.readLines()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }
    val sendMessage = {
        if (messageDraft.isNotBlank()) {
            onSendMessage(subagent.conversationId, messageDraft)
            messageDraft = ""
        }
    }

    GlassSheet(
        onDismiss = onDismiss,
        title = subagent.role,
        initialDetent = SheetDetent.Large,
        leading = {
            PocketIconButton(
                Icons.Outlined.ContentCopy,
                "Copy transcript",
                onClick = {
                    clipboard.setText(AnnotatedString(transcriptLines.joinToString("\n")))
                    banner("Transcript copied", BannerKind.Success)
                },
            )
        },
        trailing = { SheetTextButton("Done", onDismiss, emphasized = true) },
    ) {
        SegmentedControl(
            items = listOf(0, 1),
            selected = selectedTab,
            onSelect = { selectedTab = it },
            label = { if (it == 0) "Activity" else "Transcript" },
            modifier = Modifier.padding(horizontal = ListInset),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (selectedTab == 0) {
                SubagentActivity(subagent, transcriptLines)
            } else {
                LogView(
                    lines = transcriptLines,
                    emptyText = subagent.transcriptPath?.let { "No transcript at\n$it" } ?: "No transcript yet",
                    followTail = false,
                    numbered = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = ListInset)
                        .padding(top = PocketSpacing.sm, bottom = if (active) 0.dp else PocketSpacing.lg),
                )
            }
        }
        if (active) {
            InputBar(
                value = messageDraft,
                onValueChange = { messageDraft = it },
                placeholder = "Message subagent",
                sendDescription = "Send message",
                onSend = sendMessage,
                onStop = { confirmStop = true },
            )
        }
    }

    if (confirmStop) {
        StopSubagentAlert(subagent, onDismiss = { confirmStop = false }) {
            confirmStop = false
            onTerminate(subagent.conversationId)
        }
    }
}

@Composable
private fun SubagentActivity(subagent: SubagentInfo, transcriptLines: List<String>) {
    val colors = PocketColors.current
    val status = subagentStatus(subagent.state)
    val recent = remember(transcriptLines) { transcriptLines.takeLast(50) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
        item(key = "summary") {
            ListSection {
                ListRow(
                    "Status",
                    trailing = { Text(status.label, style = PocketType.body, color = status.color, maxLines = 1) },
                )
                ListRow("Type", value = subagent.typeName)
                ListRow("ID", value = subagent.conversationId.take(12))
            }
        }
        if (subagent.currentActivity.isNotBlank()) {
            item(key = "now") { TextSection("Now", subagent.currentActivity, Modifier.padding(top = GroupGap)) }
        }
        subagent.error?.let { error ->
            item(key = "error") { TextSection("Error", error, Modifier.padding(top = GroupGap), color = colors.red) }
        }
        if (recent.isNotEmpty()) {
            item(key = "transcript_header") { GroupHeader("Recent transcript", GroupGap) }
            itemsIndexed(recent) { index, line ->
                LazyGroupRow(isFirst = index == 0, isLast = index == recent.lastIndex) {
                    Text(
                        line.take(200),
                        style = PocketType.codeSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.sm + 2.dp),
                    )
                }
            }
            item(key = "transcript_footer") {
                SectionFooter("Last ${recent.size} of ${transcriptLines.size} lines.", Modifier.padding(horizontal = ListInset))
            }
        } else if (subagent.currentActivity.isBlank() && subagent.error == null) {
            item(key = "empty") { EmptyState(Icons.Outlined.SmartToy, "No activity yet") }
        }
    }
}

@Composable
private fun TextSection(header: String, text: String, modifier: Modifier = Modifier, color: Color? = null) {
    ListSection(modifier, header = header) {
        SelectionContainer {
            Text(
                text,
                style = PocketType.body,
                color = color ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.md),
            )
        }
    }
}

private fun logLines(output: String): List<String> =
    if (output.isBlank()) emptyList() else output.trimEnd().lines()

/**
 * Monospaced lines on the code surface, lazily laid out and selectable. With [followTail] it
 * keeps the newest line in view until the reader scrolls up, and follows again once they return
 * to the bottom.
 */
@Composable
private fun LogView(
    lines: List<String>,
    emptyText: String,
    followTail: Boolean,
    modifier: Modifier = Modifier,
    numbered: Boolean = false,
) {
    val colors = PocketColors.current
    val listState = rememberLazyListState()
    var following by remember { mutableStateOf(followTail) }
    if (followTail) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (!scrolling) following = !listState.canScrollForward
            }
        }
        LaunchedEffect(lines.size) {
            if (following && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
        }
    }
    val digits = lines.size.toString().length
    Box(
        modifier
            .clip(PocketShape.md)
            .background(colors.codeSurface)
            // In light mode the code surface nearly matches the sheet, so a hairline marks its edge.
            .then(if (colors.isDark) Modifier else Modifier.border(0.5.dp, colors.separator, PocketShape.md)),
    ) {
        if (lines.isEmpty()) {
            Text(
                emptyText,
                style = PocketType.subheadline,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(PocketSpacing.xl),
            )
        } else {
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(PocketSpacing.md),
                ) {
                    itemsIndexed(lines) { index, line ->
                        Row {
                            if (numbered) {
                                DisableSelection {
                                    Text(
                                        "${(index + 1).toString().padStart(digits)}  ",
                                        style = PocketType.codeSmall,
                                        color = colors.tertiaryLabel,
                                    )
                                }
                            }
                            Text(line, style = PocketType.codeSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

/** The message field and Stop button under a running task or subagent. */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    sendDescription: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    monospace: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
    ) {
        PocketTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = placeholder,
            monospace = monospace,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            trailing = {
                PocketIconButton(Icons.Outlined.ArrowUpward, sendDescription, onSend, enabled = value.isNotBlank())
            },
        )
        PocketButton("Stop", onStop, style = PocketButtonStyle.Gray, destructive = true, icon = Icons.Outlined.Stop)
    }
}

// =============================================================================================
// Shared pieces
// =============================================================================================

@Composable
private fun EmptyNote(text: String) {
    Text(
        text,
        style = PocketType.subheadline,
        color = PocketColors.current.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(horizontal = ListInset * 2, vertical = PocketSpacing.xs),
    )
}

/** The start of a file's text under its row, on the code surface; it unfolds when [visible]. */
@Composable
private fun ContentPreview(visible: Boolean, text: String, limit: Int, caption: String? = null) {
    val colors = PocketColors.current
    AnimatedVisibility(visible = visible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Column(Modifier.fillMaxWidth().padding(start = RowTextStart, end = ListInset, bottom = PocketSpacing.md)) {
            if (caption != null) {
                Text(caption, style = PocketType.caption1, color = colors.secondaryLabel, modifier = Modifier.padding(bottom = PocketSpacing.xs))
            }
            SelectionContainer {
                Text(
                    text.take(limit) + if (text.length > limit) "…" else "",
                    style = PocketType.codeSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(PocketShape.sm)
                        .background(colors.codeSurface)
                        .padding(PocketSpacing.md),
                )
            }
        }
    }
}

@Composable
private fun ExpandChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        if (expanded) 90f else 0f,
        PocketMotion.spec(PocketMotion.Token.Snappy),
        label = "expandChevron",
    )
    Icon(
        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
        contentDescription = null,
        tint = PocketColors.current.tertiaryLabel,
        modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
    )
}

private fun Modifier.expandedState(expanded: Boolean): Modifier =
    semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }

/** A hairline at the top edge from [start] to the end, as between rows of a grouped card. */
private fun Modifier.hairlineAbove(start: Dp?, color: Color): Modifier = if (start == null) this else drawWithContent {
    drawContent()
    val stroke = 0.5.dp.toPx().coerceAtLeast(1f)
    val inset = start.toPx()
    val (from, to) = if (layoutDirection == LayoutDirection.Ltr) inset to size.width else 0f to size.width - inset
    drawLine(color, Offset(from, stroke / 2f), Offset(to, stroke / 2f), stroke)
}
