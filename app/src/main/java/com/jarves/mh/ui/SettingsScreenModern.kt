package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.BuildConfig
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DevStack
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.LargeTitle
import com.jarves.mh.ui.kit.LargeTitleScaffold
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SheetDetent
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.ToggleRow
import com.jarves.mh.ui.kit.rememberHaptics
import com.jarves.mh.ui.kit.rememberTitleCollapse
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.ContinuousRoundedShape
import com.jarves.mh.ui.theme.DarkColorRoles
import com.jarves.mh.ui.theme.LightColorRoles
import com.jarves.mh.ui.theme.PocketColorRoles
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.hostBackdropSource

/**
 * Settings: one grouped list under a large title. Appearance (light, dark or automatic, and
 * Reduce transparency), developer toolchains, the Linux runtime, and About. Provider and
 * account setup lives on the Agent tab.
 */
@Composable
fun SettingsScreen(
    state: AppUiState,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onClearTerminal: () -> Unit,
    onInstallDevStack: (DevStack) -> Unit = {},
    onRemoveDevStack: (DevStack) -> Unit = {},
    reduceTransparency: Boolean = false,
    onSetReduceTransparency: (Boolean) -> Unit = {},
    initialDebugUpdateManifestUrl: String = "",
    onSetDebugUpdateManifestUrl: (String) -> Unit = {},
    onClearDebugUpdateManifestUrl: () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    bottomBarPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val colors = PocketColors.current
    var terminalCleared by remember { mutableStateOf(false) }
    var stackPendingRemoval by remember { mutableStateOf<DevStack?>(null) }
    var showWhatsNew by rememberSaveable { mutableStateOf(false) }
    val collapse by rememberTitleCollapse(listState)
    val open = { url: String -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    LargeTitleScaffold(title = "Settings", collapse = { collapse }) { padding ->
        // Runs edge to edge under the glass bar and dock; it is the shared backdrop source.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hostBackdropSource().imePadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomBarPadding + PocketSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xl),
        ) {
            item(key = "title") { LargeTitle("Settings") }

            item(key = "appearance") {
                ListSection(
                    header = "Appearance",
                    footer = "Reduce transparency replaces glass with solid surfaces. It also follows the system setting.",
                ) {
                    AppearancePicker(selected = state.themeMode, onSelect = onSetThemeMode)
                    ToggleRow(
                        title = "Reduce transparency",
                        checked = reduceTransparency,
                        onCheckedChange = onSetReduceTransparency,
                        icon = Icons.Outlined.Contrast,
                        iconTile = colors.gray,
                    )
                }
            }

            item(key = "tools") {
                ListSection(
                    header = "Developer tools",
                    footer = "Node.js, npm, Git and Claude Code are always included. Removing a toolchain keeps your projects.",
                ) {
                    DevStack.entries.forEach { stack ->
                        DevStackRow(
                            stack = stack,
                            state = state,
                            onAdd = { onInstallDevStack(stack) },
                            onRemove = { stackPendingRemoval = stack },
                        )
                    }
                }
            }

            item(key = "runtime") {
                ListSection(header = "Linux runtime") {
                    ListRow("Environment", value = "Ubuntu 20.04 PRoot")
                    ListRow("Architecture", value = "ARM64")
                    ListRow(
                        "Active agent",
                        value = state.agentKind.title + if (state.installedAgentVersions.containsKey(state.agentKind)) "" else " · Not installed",
                    )
                    if (state.installedAgentVersions.isEmpty()) {
                        ListRow("Installed agents", value = "None verified")
                    } else {
                        AgentKind.entries.forEach { agent ->
                            state.installedAgentVersions[agent]?.let { version -> ListRow(agent.title, value = "v$version") }
                        }
                    }
                }
            }

            item(key = "maintenance") {
                ListSection(footer = "If large builds stop unexpectedly, Android Developer options may offer a setting that limits child processes.") {
                    ListRow(
                        title = if (terminalCleared) "Terminal history cleared" else "Clear terminal history",
                        icon = Icons.Outlined.DeleteSweep,
                        iconTile = colors.red,
                        enabled = !terminalCleared,
                        onClick = {
                            onClearTerminal()
                            terminalCleared = true
                        },
                    )
                    ListRow(
                        title = "Developer options",
                        icon = Icons.Outlined.DeveloperMode,
                        iconTile = colors.gray,
                        trailing = { OpenIcon() },
                        onClick = {
                            runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                                .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        },
                    )
                }
            }

            if (BuildConfig.DEBUG) {
                item(key = "updateChannel") {
                    DebugUpdateChannelSection(
                        initialUrl = initialDebugUpdateManifestUrl,
                        onSave = onSetDebugUpdateManifestUrl,
                        onClear = onClearDebugUpdateManifestUrl,
                    )
                }
            }

            item(key = "about") {
                ListSection(header = "About", footer = "Mobile Harness+ is a local AI coding workspace.") {
                    ListRow("Version", value = BuildConfig.VERSION_NAME)
                    ListRow(
                        title = "What’s new",
                        icon = Icons.Outlined.NewReleases,
                        iconTile = colors.pink,
                        accessory = ListRowAccessory.Chevron,
                        onClick = { showWhatsNew = true },
                    )
                    ListRow(
                        title = "Original Mobile Harness",
                        subtitle = "Forked from Tech Jarves",
                        icon = Icons.Outlined.Code,
                        iconTile = colors.blue,
                        trailing = { OpenIcon() },
                        onClick = { open("https://github.com/techjarves/Mobile-Harness") },
                    )
                    ListRow(
                        title = "Privacy policy",
                        icon = Icons.Outlined.PrivacyTip,
                        iconTile = colors.indigo,
                        trailing = { OpenIcon() },
                        onClick = { open(BuildConfig.PRIVACY_POLICY_URL) },
                    )
                }
            }
        }
    }

    stackPendingRemoval?.let { stack ->
        PocketAlert(
            onDismiss = { stackPendingRemoval = null },
            title = "Remove ${stack.label}?",
            message = "This removes the toolchain and its caches to free storage. Your projects and source files stay.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { stackPendingRemoval = null },
                AlertAction("Remove", AlertRole.Destructive) {
                    stackPendingRemoval = null
                    onRemoveDevStack(stack)
                },
            ),
        )
    }

    GlassSheet(
        onDismiss = { showWhatsNew = false },
        visible = showWhatsNew,
        title = "What’s New",
        detents = listOf(SheetDetent.Fit),
        trailing = { SheetTextButton("Done", { showWhatsNew = false }, emphasized = true) },
    ) {
        WhatsNew.forEach { (icon, title, detail) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = PocketSpacing.xl, vertical = PocketSpacing.md)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(PocketSpacing.lg))
                Column {
                    Text(title, style = PocketType.subheadline.emphasized, color = MaterialTheme.colorScheme.onSurface)
                    Text(detail, style = PocketType.subheadline, color = colors.secondaryLabel)
                }
            }
        }
        Spacer(Modifier.height(PocketSpacing.lg))
    }
}

private val WhatsNew: List<Triple<ImageVector, String, String>> = listOf(
    Triple(Icons.Outlined.Memory, "Three engines", "Claude Code, DeepSeek Harness and Google Antigravity CLI in one workspace."),
    Triple(Icons.Outlined.DataObject, "Subagents and background tasks", "Watch them live and stop or resume them yourself."),
    Triple(Icons.Outlined.Storage, "Shared memory", "Project context carries across engines and chats."),
    Triple(Icons.Outlined.Code, "Skills and rules", "Global and project skills, with five engineering discipline rules."),
)

@Composable
private fun OpenIcon() {
    Icon(
        Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = "Opens in browser",
        tint = PocketColors.current.tertiaryLabel,
        modifier = Modifier.size(18.dp),
    )
}

private fun DevStack.symbol(): ImageVector = when (this) {
    DevStack.WEB -> Icons.Outlined.Language
    DevStack.PYTHON -> Icons.Outlined.DataObject
    DevStack.ANDROID -> Icons.Outlined.Android
    DevStack.CPP -> Icons.Outlined.Memory
    DevStack.PHP -> Icons.Outlined.Storage
}

private fun DevStack.tile(colors: PocketColorRoles): Color = when (this) {
    DevStack.WEB -> colors.yellow
    DevStack.PYTHON -> colors.blue
    DevStack.ANDROID -> colors.green
    DevStack.CPP -> colors.indigo
    DevStack.PHP -> colors.purple
}

/** One toolchain: Add, a progress ring while it installs or is removed, then Remove. */
@Composable
private fun DevStackRow(stack: DevStack, state: AppUiState, onAdd: () -> Unit, onRemove: () -> Unit) {
    val colors = PocketColors.current
    val installed = stack in state.installedDevStacks
    val busy = state.devStackInstalling == stack
    val removing = busy && state.devStackRemoving
    val idle = state.devStackInstalling == null
    val progressLine = if (busy) {
        state.devStackBytes?.let { (downloaded, total) ->
            buildString {
                append("${formatTransferMb(downloaded)} of ${formatTransferMb(total)}")
                state.devStackBytesPerSecond?.takeIf { it > 0L }?.let { speed ->
                    append(" · ${formatTransferSpeed(speed)} · ${formatTransferEta(downloaded, total, speed)} left")
                }
            }
        } ?: (state.devStackMessage ?: if (removing) "Removing…" else "Preparing…")
    } else {
        null
    }
    ListRow(
        title = stack.label,
        subtitle = progressLine ?: stack.installsSummary,
        icon = stack.symbol(),
        iconTile = stack.tile(colors),
        subtitleMaxLines = if (busy) 2 else 1,
        trailing = {
            when {
                removing -> ProgressRing(progress = null, size = 24.dp, strokeWidth = 2.5.dp, color = colors.red)
                busy -> ProgressRing(progress = state.devStackProgress.coerceIn(0f, 1f), size = 24.dp, strokeWidth = 2.5.dp)
                installed && stack == DevStack.WEB -> Text("Included", style = PocketType.body, color = colors.secondaryLabel)
                installed -> SheetTextButton("Remove", onRemove, enabled = idle, destructive = true)
                else -> PocketButton("Add", onAdd, style = PocketButtonStyle.Gray, size = PocketButtonSize.Small, enabled = idle)
            }
        },
    )
}

/**
 * Light, Dark and Automatic as small screen previews with a selection circle under each, so the
 * choice reads at a glance.
 */
@Composable
private fun AppearancePicker(selected: AppThemeMode, onSelect: (AppThemeMode) -> Unit) {
    val haptics = rememberHaptics()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ListInset, vertical = PocketSpacing.lg)
            .selectableGroup(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        listOf(AppThemeMode.LIGHT to "Light", AppThemeMode.DARK to "Dark", AppThemeMode.SYSTEM to "Automatic").forEach { (mode, label) ->
            val isSelected = selected == mode
            Column(
                Modifier
                    .selectable(
                        selected = isSelected,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.RadioButton,
                    ) {
                        if (!isSelected) haptics.selection()
                        onSelect(mode)
                    }
                    .padding(PocketSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppearancePreview(mode)
                Spacer(Modifier.height(PocketSpacing.sm))
                Text(label, style = PocketType.subheadline, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(PocketSpacing.sm))
                SelectionCircle(isSelected)
            }
        }
    }
}

private val PreviewShape = ContinuousRoundedShape(12.dp)

@Composable
private fun AppearancePreview(mode: AppThemeMode) {
    val colors = PocketColors.current
    Box(
        Modifier
            .size(width = 62.dp, height = 112.dp)
            .clip(PreviewShape)
            .border(0.5.dp, colors.separator, PreviewShape)
            .drawBehind {
                when (mode) {
                    AppThemeMode.LIGHT -> drawMiniScreen(LightColorRoles)
                    AppThemeMode.DARK -> drawMiniScreen(DarkColorRoles)
                    AppThemeMode.SYSTEM -> {
                        drawMiniScreen(LightColorRoles)
                        // Dark on the lower-right half, split on the diagonal.
                        val half = Path().apply {
                            moveTo(size.width, 0f)
                            lineTo(size.width, size.height)
                            lineTo(0f, size.height)
                            close()
                        }
                        clipPath(half) { drawMiniScreen(DarkColorRoles) }
                    }
                }
            },
    )
}

/** A tiny grouped-list screen: canvas, a title bar and two cards of rows. */
private fun DrawScope.drawMiniScreen(roles: PocketColorRoles) {
    val u = size.width / 62f
    drawRect(roles.groupedBackground)
    drawRoundRect(roles.label.copy(alpha = 0.85f), Offset(7 * u, 14 * u), Size(26 * u, 5 * u), CornerRadius(2.5f * u))
    var top = 26 * u
    listOf(3, 2).forEach { rows ->
        val height = rows * 10 * u
        drawRoundRect(roles.groupedSurface, Offset(5 * u, top), Size(size.width - 10 * u, height), CornerRadius(5 * u))
        repeat(rows) { i ->
            val y = top + i * 10 * u + 3.5f * u
            drawRoundRect(roles.accent, Offset(8 * u, y), Size(3 * u, 3 * u), CornerRadius(1 * u))
            drawRoundRect(roles.secondaryLabel.copy(alpha = 0.5f), Offset(14 * u, y + 0.5f * u), Size(26 * u, 2 * u), CornerRadius(1 * u))
        }
        top += height + 6 * u
    }
}

@Composable
private fun SelectionCircle(selected: Boolean) {
    val colors = PocketColors.current
    Box(
        Modifier
            .size(22.dp)
            .clip(PocketShape.capsule)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                else Modifier.border(1.5.dp, colors.tertiaryLabel, PocketShape.capsule),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

/** Debug builds only: point the update check at a temporary manifest. */
@Composable
private fun DebugUpdateChannelSection(
    initialUrl: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    val isOverridden = initialUrl.isNotBlank()
    ListSection(
        header = "Update channel",
        footer = if (isOverridden) "Debug builds only. Current: $initialUrl"
        else "Debug builds only. Paste an HTTPS manifest URL (for example from a tunnel) that serves mobile-harness-update.json and a newer APK.",
    ) {
        Column(Modifier.fillMaxWidth().padding(ListInset), verticalArrangement = Arrangement.spacedBy(PocketSpacing.md)) {
            PocketTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://…/mobile-harness-update.json",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
                PocketButton(
                    if (isOverridden) "Replace" else "Use and check",
                    { onSave(url) },
                    modifier = Modifier.weight(1f),
                    style = PocketButtonStyle.Tinted,
                    enabled = url.startsWith("https://"),
                    fullWidth = true,
                )
                PocketButton(
                    "Reset",
                    onClear,
                    modifier = Modifier.weight(1f),
                    style = PocketButtonStyle.Gray,
                    enabled = isOverridden,
                    fullWidth = true,
                )
            }
        }
    }
}

private fun formatTransferMb(bytes: Long): String = "%.1f MB".format(bytes.coerceAtLeast(0L) / 1_048_576.0)

private fun formatTransferSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_048_576L -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    else -> "%.0f KB/s".format(bytesPerSecond / 1_024.0)
}

private fun formatTransferEta(downloaded: Long, total: Long, bytesPerSecond: Long): String {
    val seconds = ((total - downloaded).coerceAtLeast(0L) / bytesPerSecond.coerceAtLeast(1L)).coerceAtLeast(1L)
    return if (seconds >= 60L) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}
