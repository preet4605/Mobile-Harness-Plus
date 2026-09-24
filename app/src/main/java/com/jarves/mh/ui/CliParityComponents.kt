package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.model.ArtifactInfo
import com.jarves.mh.model.BackgroundTaskInfo
import com.jarves.mh.model.BackgroundTaskStatus
import com.jarves.mh.model.ProjectRule
import com.jarves.mh.model.ScheduledTimerInfo
import com.jarves.mh.model.SessionTokenMetrics
import com.jarves.mh.model.SkillInfo
import com.jarves.mh.model.SlashCommand
import com.jarves.mh.model.SubagentInfo
import com.jarves.mh.model.SubagentState
import com.jarves.mh.model.WorkspaceEntry
import com.jarves.mh.ui.theme.PocketBlue
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.launch

/**
 * Floating Slash Command Menu popup displayed above chat input.
 * Uses adaptive max height (28% of viewport) to prevent compressing the input bar.
 */
@Composable
fun SlashCommandMenu(
    commands: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (commands.isEmpty()) return

    val configuration = LocalConfiguration.current
    val maxMenuHeight = (configuration.screenHeightDp.dp * 0.28f).coerceIn(120.dp, 190.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Code,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = PocketOrange,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Slash Commands",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxMenuHeight),
            ) {
                items(commands, key = { it.name }) { cmd ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "/",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "/${cmd.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (cmd.parameterHint != null) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        cmd.parameterHint,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    )
                                }
                                if (cmd.isLocalOnly) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                    ) {
                                        Text(
                                            "Instant",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Text(
                                cmd.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Context Mention Menu popup for @file and @folder references.
 * Uses adaptive max height (28% of viewport) matching the slash command menu.
 */
@Composable
fun MentionMenu(
    files: List<WorkspaceEntry>,
    onSelect: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (files.isEmpty()) return

    val configuration = LocalConfiguration.current
    val maxMenuHeight = (configuration.screenHeightDp.dp * 0.28f).coerceIn(120.dp, 190.dp)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Context Reference (@)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxMenuHeight),
            ) {
                items(files, key = { it.path }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (entry.isDirectory) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.name,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                entry.path,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact Token Telemetry and Context capacity bar.
 */
@Composable
fun TokenTelemetryBar(
    metrics: SessionTokenMetrics,
    modifier: Modifier = Modifier,
) {
    if (metrics.promptTokens == 0 && metrics.completionTokens == 0) return

    val capacityPct = if (metrics.contextWindowLimit > 0) {
        ((metrics.promptTokens.toDouble() / metrics.contextWindowLimit) * 100).coerceIn(0.0, 100.0)
    } else 0.0

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${metrics.promptTokens} in · ${metrics.completionTokens} out",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                if (metrics.cachedTokens > 0) {
                    Text(
                        " (${metrics.cachedTokens} cached)",
                        fontSize = 11.sp,
                        color = PocketGreen,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Text(
                "Context: ${String.format("%.1f%%", capacityPct)}",
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = if (capacityPct > 80.0) Color(0xFFEA4335) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Auxiliary Inspector BottomSheet (Subagents, Background Tasks, Artifacts, Timers).
 */
@OptIn(ExperimentalMaterial3Api::class)
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
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("Subagents (${subagents.size})", "Tasks (${tasks.size})", "Artifacts (${artifacts.size})", "Timers (${timers.size})")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(bottom = 16.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Layers, null, tint = PocketOrange, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Agent & Tasks Inspector", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenMemoryViewer != null) {
                        IconButton(onClick = onOpenMemoryViewer) {
                            Icon(Icons.Default.Psychology, contentDescription = "Persistent Memory", tint = PocketOrange)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 12.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }

            Box(Modifier.weight(1f).padding(16.dp)) {
                when (selectedTab) {
                    0 -> SubagentsView(
                        subagents = subagents,
                        onTerminate = onTerminateSubagent,
                        onClearCompleted = onClearCompletedSubagents,
                        onSelect = onSelectSubagentForLogs,
                    )
                    1 -> BackgroundTasksView(
                        tasks = tasks,
                        onTerminate = onTerminateTask,
                        onClearCompleted = onClearCompletedTasks,
                        onSelect = onSelectTaskForLogs,
                    )
                    2 -> ArtifactsView(artifacts)
                    3 -> TimersView(timers)
                }
            }
        }
    }
}

@Composable
private fun SubagentsView(
    subagents: List<SubagentInfo>,
    onTerminate: (String) -> Unit = {},
    onClearCompleted: () -> Unit = {},
    onSelect: (SubagentInfo) -> Unit = {},
) {
    if (subagents.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Psychology, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text("No active subagents", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Subagents spawned by the parent agent will appear here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        return
    }

    val hasCompleted = subagents.any { it.state.isTerminal }

    Column(Modifier.fillMaxSize()) {
        if (hasCompleted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onClearCompleted,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear finished", fontSize = 12.sp)
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(subagents, key = { it.conversationId }) { subagent ->
                val isActive = !subagent.state.isTerminal

                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    onClick = { onSelect(subagent) },
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f, fill = false)) {
                                Icon(Icons.Default.SmartToy, null, tint = PocketBlue, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    subagent.role,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SubagentStateBadge(subagent.state)
                                if (isActive) {
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { onTerminate(subagent.conversationId) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = "Stop subagent",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                // Navigation affordance: tap card to view transcript
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "View transcript",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Type: ${subagent.typeName} · ID: ${subagent.conversationId.take(8)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (subagent.currentActivity.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(subagent.currentActivity, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                        if (subagent.error != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(subagent.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubagentStateBadge(state: SubagentState) {
    val (label, bg, fg) = when (state) {
        SubagentState.RUNNING -> Triple("Running", PocketGreen.copy(alpha = 0.15f), PocketGreen)
        SubagentState.WAITING_FOR_INPUT -> Triple("Waiting Input", PocketOrange.copy(alpha = 0.15f), PocketOrange)
        SubagentState.WAITING_FOR_DEPENDENTS -> Triple("Waiting Peer", PocketBlue.copy(alpha = 0.15f), PocketBlue)
        SubagentState.DONE -> Triple("Completed", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        SubagentState.ERRORED -> Triple("Error", Color(0xFFEA4335).copy(alpha = 0.15f), Color(0xFFEA4335))
        SubagentState.IDLE -> Triple("Idle", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        SubagentState.TERMINATED -> Triple("Stopped", PocketOrange.copy(alpha = 0.15f), PocketOrange)
    }

    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

@Composable
private fun BackgroundTasksView(
    tasks: List<BackgroundTaskInfo>,
    onTerminate: (String) -> Unit = {},
    onClearCompleted: () -> Unit = {},
    onSelect: (BackgroundTaskInfo) -> Unit = {},
) {
    if (tasks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Terminal, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text("No background tasks running", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Asynchronous commands started by the agent will be listed here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        return
    }

    val hasCompleted = tasks.any { it.status != BackgroundTaskStatus.RUNNING }

    Column(Modifier.fillMaxSize()) {
        if (hasCompleted) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onClearCompleted,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Clear finished", fontSize = 12.sp)
                }
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f, fill = false)) {
            items(tasks, key = { it.taskId }) { task ->
                val isRunning = task.status == BackgroundTaskStatus.RUNNING
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    onClick = { onSelect(task) },
                ) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(task.taskId, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    task.status.name,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = when (task.status) {
                                        BackgroundTaskStatus.RUNNING -> PocketGreen
                                        BackgroundTaskStatus.TERMINATED -> PocketOrange
                                        BackgroundTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                                        BackgroundTaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                if (isRunning) {
                                    Spacer(Modifier.width(6.dp))
                                    IconButton(
                                        onClick = { onTerminate(task.taskId) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = "Stop task",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                // Navigation affordance
                                Spacer(Modifier.width(2.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "View logs",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(task.commandLine, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                        if (task.liveOutputTail.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF0D1117),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    task.liveOutputTail,
                                    modifier = Modifier.padding(8.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 3,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtifactsView(artifacts: List<ArtifactInfo>) {
    val clipboard = LocalClipboardManager.current
    if (artifacts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text("No artifacts produced yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Structured reports, plans, and diagrams will appear here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(artifacts, key = { it.id }) { artifact ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(artifact.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(artifact.summary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(artifact.filePath.substringAfterLast('/'), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(artifact.filePath)) }) {
                            Text("Copy Path", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimersView(timers: List<ScheduledTimerInfo>) {
    if (timers.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Timer, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(10.dp))
                Text("No active timers or schedules", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(timers, key = { it.taskId }) { timer ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(if (timer.isCron) "Recurring Schedule" else "One-shot Timer", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text("${timer.remainingSeconds}s remaining", fontSize = 12.sp, color = PocketOrange, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(timer.prompt, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * Skills & Rules Manager Dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsManagerDialog(
    skills: List<SkillInfo>,
    rules: List<ProjectRule>,
    onToggleSkill: (String) -> Unit,
    onSaveRule: (String, String) -> Unit,
    onCreateSkill: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    var showCreateSkillDialog by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, tint = PocketOrange, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Skills & Customizations")
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                PrimaryTabRow(selectedTabIndex = tabIndex) {
                    Tab(
                        selected = tabIndex == 0,
                        onClick = { tabIndex = 0 },
                        text = { Text("Skills (${skills.size})", fontSize = 12.sp) },
                    )
                    Tab(
                        selected = tabIndex == 1,
                        onClick = { tabIndex = 1 },
                        text = { Text("Project Rules (${rules.size})", fontSize = 12.sp) },
                    )
                }
                Spacer(Modifier.height(10.dp))

                if (tabIndex == 0) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Active Skills", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        TextButton(onClick = { showCreateSkillDialog = true }) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("New Skill", fontSize = 12.sp)
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(skills, key = { it.id }) { skill ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(skill.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text("Source: ${skill.source.title}", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                                    }
                                    Switch(
                                        checked = skill.isEnabled,
                                        onCheckedChange = { onToggleSkill(skill.id) },
                                        modifier = Modifier.size(36.dp),
                                    )
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rules, key = { it.fileName }) { rule ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(rule.fileName, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(if (rule.exists) "Active" else "Not created", fontSize = 11.sp, color = if (rule.exists) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        if (rule.exists && rule.content.isNotBlank()) rule.content.take(120) + "…" else "No rules defined yet.",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )

    if (showCreateSkillDialog) {
        CreateSkillDialog(
            onCreate = { name, desc, body ->
                onCreateSkill(name, desc, body)
                showCreateSkillDialog = false
            },
            onDismiss = { showCreateSkillDialog = false },
        )
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New Skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill name (e.g. test-runner)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Short description for agent") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("Markdown instructions / runbook") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, description, instructions) },
                enabled = name.isNotBlank() && description.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Model Picker Dialog for quick switching on the fly.
 */
@Composable
fun ModelPickerDialog(
    currentModel: String,
    availableModels: List<String>,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch AI Model") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val models = if (availableModels.isNotEmpty()) availableModels else listOf(
                    "gemini-3.8-flash-high",
                    "gemini-3.8-pro",
                    "gemini-3.6-flash-high",
                    "claude-sonnet-4-6",
                    "claude-opus-4-6-thinking",
                )
                items(models) { modelId ->
                    val isSelected = modelId.equals(currentModel, ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectModel(modelId)
                                onDismiss()
                            },
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(modelId, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 13.sp)
                            if (isSelected) {
                                Icon(Icons.Default.Check, null, tint = PocketGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

// =============================================================================
// Inspector Log Viewer Dialogs (PRD §3.4.4)
// =============================================================================

/**
 * Full-screen modal dialog for inspecting a background task's terminal output.
 * Shows task metadata, live stdout/stderr tail, copy-logs, stdin input, and terminate button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskLogViewerDialog(
    task: BackgroundTaskInfo,
    onDismiss: () -> Unit,
    onTerminate: (String) -> Unit = {},
    onSendInput: (String, String) -> Unit = { _, _ -> },
) {
    val clipboard = LocalClipboardManager.current
    var stdinDraft by remember { mutableStateOf("") }
    val isRunning = task.status == BackgroundTaskStatus.RUNNING

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.90f).padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, null, tint = PocketGreen, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(task.taskId, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = when (task.status) {
                                BackgroundTaskStatus.RUNNING -> PocketGreen.copy(alpha = 0.15f)
                                BackgroundTaskStatus.COMPLETED -> MaterialTheme.colorScheme.surfaceVariant
                                BackgroundTaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                                BackgroundTaskStatus.TERMINATED -> PocketOrange.copy(alpha = 0.15f)
                            },
                        ) {
                            Text(
                                task.status.name,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = when (task.status) {
                                    BackgroundTaskStatus.RUNNING -> PocketGreen
                                    BackgroundTaskStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    BackgroundTaskStatus.FAILED -> MaterialTheme.colorScheme.error
                                    BackgroundTaskStatus.TERMINATED -> PocketOrange
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        task.commandLine,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            HorizontalDivider()

            // Dark console output area
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF1E1E1E),
            ) {
                Text(
                    text = task.liveOutputTail.ifBlank { "(no output yet)" },
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFFE0E0E0),
                    lineHeight = 16.sp,
                )
            }

            // Action bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(task.liveOutputTail)) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("Copy Logs", fontSize = 12.sp)
                }
                if (isRunning) {
                    Button(
                        onClick = { onTerminate(task.taskId) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontSize = 12.sp)
                    }
                }
            }

            // Stdin input bar for running tasks
            if (isRunning) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = stdinDraft,
                        onValueChange = { stdinDraft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Send stdin input…", fontSize = 13.sp) },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            if (stdinDraft.isNotBlank()) {
                                onSendInput(task.taskId, stdinDraft)
                                stdinDraft = ""
                            }
                        },
                        enabled = stdinDraft.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow, "Send stdin",
                            tint = if (stdinDraft.isNotBlank()) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen modal dialog for inspecting a subagent's transcript.
 * Two tabs: Activity summary and raw JSONL log viewer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubagentTranscriptViewerDialog(
    subagent: SubagentInfo,
    onDismiss: () -> Unit,
    onSendMessage: (String, String) -> Unit = { _, _ -> },
    onTerminate: (String) -> Unit = {},
) {
    val clipboard = LocalClipboardManager.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var messageDraft by remember { mutableStateOf("") }

    val transcriptLines = remember(subagent.transcriptPath) {
        runCatching {
            subagent.transcriptPath?.let { path ->
                java.io.File(path).takeIf { it.exists() }?.readLines()
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.90f).padding(bottom = 16.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SmartToy, null, tint = PocketBlue, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(subagent.role, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        SubagentStateBadge(subagent.state)
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Type: ${subagent.typeName} · ID: ${subagent.conversationId.take(12)}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
            }

            HorizontalDivider()

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Activity", fontSize = 12.sp) })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Raw JSONL", fontSize = 12.sp) })
            }

            Box(Modifier.weight(1f)) {
                when (selectedTab) {
                    0 -> {
                        Column(
                            Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (subagent.currentActivity.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Current Activity", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(4.dp))
                                        Text(subagent.currentActivity, fontSize = 13.sp)
                                    }
                                }
                            }
                            if (subagent.error != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text("Error", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.height(4.dp))
                                        Text(subagent.error, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
                                    }
                                }
                            }
                            if (transcriptLines.isNotEmpty()) {
                                Text(
                                    "Transcript (last ${minOf(transcriptLines.size, 50)} of ${transcriptLines.size})",
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                androidx.compose.foundation.lazy.LazyColumn(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f, fill = false),
                                ) {
                                    val lines = transcriptLines.takeLast(50)
                                    items(lines.size) { idx ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(
                                                lines[idx].take(200), modifier = Modifier.padding(8.dp),
                                                fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            } else if (subagent.currentActivity.isBlank() && subagent.error == null) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No activity recorded yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    1 -> {
                        Surface(
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1E1E1E),
                        ) {
                            if (transcriptLines.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        if (subagent.transcriptPath != null) "No transcript at:\n${subagent.transcriptPath}"
                                        else "No transcript path available",
                                        color = Color(0xFFE0E0E0), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                    )
                                }
                            } else {
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    items(transcriptLines.size) { idx ->
                                        Text(
                                            "${idx + 1}: ${transcriptLines[idx]}",
                                            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                                            color = Color(0xFFE0E0E0), lineHeight = 14.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(transcriptLines.joinToString("\n"))) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Copy Transcript", fontSize = 12.sp) }
                if (!subagent.state.isTerminal) {
                    Button(
                        onClick = { onTerminate(subagent.conversationId) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontSize = 12.sp)
                    }
                }
            }

            if (!subagent.state.isTerminal) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = messageDraft, onValueChange = { messageDraft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message subagent…", fontSize = 13.sp) },
                        singleLine = true,
                    )
                    IconButton(
                        onClick = {
                            if (messageDraft.isNotBlank()) {
                                onSendMessage(subagent.conversationId, messageDraft)
                                messageDraft = ""
                            }
                        },
                        enabled = messageDraft.isNotBlank(),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow, "Send message",
                            tint = if (messageDraft.isNotBlank()) PocketBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
