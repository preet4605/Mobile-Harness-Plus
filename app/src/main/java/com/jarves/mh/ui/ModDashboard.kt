package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jarves.mh.mods.InstalledMod
import com.jarves.mh.mods.ModAgentSteps
import com.jarves.mh.mods.ModAuditEntry
import com.jarves.mh.mods.ModCapability
import com.jarves.mh.mods.ModChatLine
import com.jarves.mh.mods.ModCodeCommand
import com.jarves.mh.mods.ModLogEntry
import com.jarves.mh.mods.ModRunState
import com.jarves.mh.mods.ModRuntimeInfo
import com.jarves.mh.mods.ModServer
import com.jarves.mh.mods.ModSetting
import com.jarves.mh.mods.ModStatus
import com.jarves.mh.mods.ModSurfaces
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.SegmentedControl
import com.jarves.mh.ui.kit.ToggleRow
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import org.json.JSONObject

/** What a mod's dashboard reads besides the mod itself; loaded by the mod page while the dashboard shows. */
internal data class ModDashboardData(
    val audit: List<ModAuditEntry> = emptyList(),
    val log: List<ModLogEntry> = emptyList(),
    val stored: JSONObject? = null,
    val modelTokens: Long? = null,
    /** The mod's dashboard chat (phase 2). */
    val chat: List<ModChatLine> = emptyList(),
)

/**
 * Every mod's home (mod dashboards plan, phase 1): drawn by the app from what the mod declares, so
 * a mod needs no code for it. Its state and switch, what it is doing now (agent tasks, servers,
 * schedules), its own panes when it draws any, its settings and rules, its commands, recent
 * activity and saved data. Mods add to it with panes; they can't hide these sections.
 */
internal fun LazyListScope.modDashboard(
    mod: InstalledMod,
    status: ModStatus,
    runtime: ModRuntimeInfo?,
    surfaces: ModSurfaces?,
    paneId: String?,
    onSelectPane: (String) -> Unit,
    steps: List<ModAgentSteps.Step>,
    servers: List<ModServer>,
    data: ModDashboardData,
    viewModel: ModsViewModel,
    onEdit: (ModSetting) -> Unit,
    onRunWithArgs: (ModCodeCommand) -> Unit,
    onShowActivity: () -> Unit,
    onClearData: () -> Unit,
) {
    val manifest = mod.manifest
    val hasCode = manifest.hooksModule != null
    item(key = "dash-status") {
        val colors = PocketColors.current
        val developer = mod.granted.filter { it.developer && mod.canHold(it) }
        ListSection {
            ToggleRow(title = "On", checked = mod.enabled, onCheckedChange = { viewModel.setEnabled(mod.id, it) })
            ListRow(
                title = runtime?.state?.label ?: status.label,
                subtitle = runtime?.message ?: if (!mod.enabled) "Turn it on to use it." else null,
                subtitleMaxLines = 4,
                titleColor = when (runtime?.state) { ModRunState.CRASHED, ModRunState.BLOCKED -> colors.red; else -> null },
                value = if (hasCode && mod.enabled) "Restart" else null,
                onClick = if (hasCode && mod.enabled) ({ viewModel.restartMod(mod.id) }) else null,
            )
            if (developer.isNotEmpty()) {
                ListRow(
                    title = "Uses Developer mode",
                    subtitle = developer.joinToString { it.id },
                    icon = Icons.Outlined.Warning,
                    iconTile = colors.orange,
                )
            }
        }
    }

    val active = steps.filter { !it.state.done }
    val recent = steps.filter { it.state.done }.takeLast(3).asReversed()
    item(key = "dash-now") {
        val now = System.currentTimeMillis()
        val lastRuns = data.audit.filter { it.op == "schedule.run" }.groupBy { it.detail }.mapValues { (_, entries) -> entries.maxOf { it.time } }
        ListSection(header = "Now") {
            runtime?.status?.let { ListRow(title = it, subtitle = runtime.summary, subtitleMaxLines = 3) }
            active.forEach { step ->
                ListRow(
                    title = stepTitle(step),
                    subtitle = step.prompt.lineSequence().firstOrNull()?.take(160),
                    value = if (step.state == ModAgentSteps.State.RUNNING) "Stop · ${duration(now - step.startedAt)}" else step.state.label,
                    onClick = if (step.state == ModAgentSteps.State.RUNNING) ({ viewModel.stopStep(step.id) }) else null,
                )
            }
            servers.forEach { server ->
                ListRow(
                    title = "Server ${server.name}",
                    subtitle = "Port ${server.port} · " + if (server.running) "running" else (server.note ?: "ended (exit ${server.exitCode ?: "?"})"),
                    value = if (server.running) "Stop" else null,
                    onClick = if (server.running) ({ viewModel.stopServer(mod.id, server.name) }) else null,
                )
            }
            manifest.schedules.forEach { schedule ->
                ListRow(
                    title = "Schedule ${schedule.id}",
                    subtitle = "Every ${schedule.everyMinutes} min" + (lastRuns[schedule.id]?.let { " · last ran ${clock(it)}" } ?: " · not run yet"),
                )
            }
            recent.forEach { step ->
                ListRow(
                    title = stepTitle(step),
                    subtitle = (step.error ?: step.reply ?: step.prompt).lineSequence().firstOrNull()?.take(160),
                    value = step.state.label,
                    titleColor = if (step.state == ModAgentSteps.State.FAILED) PocketColors.current.red else null,
                )
            }
            if (runtime?.status == null && active.isEmpty() && servers.isEmpty() && manifest.schedules.isEmpty() && recent.isEmpty()) {
                ListRow(title = "Nothing running right now.")
            }
        }
    }

    val panes = surfaces?.panes.orEmpty()
    if (panes.isNotEmpty()) {
        val pane = panes.firstOrNull { it.id == paneId } ?: panes.first()
        if (panes.size > 1) {
            item(key = "dash-panes") {
                Column(Modifier.padding(horizontal = ListInset)) {
                    SegmentedControl(panes.map { it.id }, pane.id, onSelectPane, label = { id -> panes.first { it.id == id }.title })
                }
            }
        }
        if (runtime?.state == ModRunState.RUNNING && surfaces != null) {
            modPaneItems(
                keyPrefix = "pane-${pane.id}",
                drawing = surfaces.drawings[pane.surface],
                dir = surfaces.dir,
                onEvent = { handler, value -> viewModel.press(mod.id, pane.surface, handler, value) },
                itemModifier = Modifier.padding(horizontal = ListInset),
            )
        }
    }

    if (manifest.settings.isNotEmpty() || manifest.rules.isNotEmpty()) {
        item(key = "dash-controls") {
            ListSection(header = "Controls") {
                manifest.settings.forEach { setting -> ModSettingRow(mod, setting, viewModel, onEdit) }
                manifest.rules.forEach { rule ->
                    ToggleRow(
                        title = "Rule ${rule.id}",
                        checked = rule.id in mod.enabledRules,
                        onCheckedChange = { viewModel.setRuleEnabled(mod.id, rule.id, it) },
                    )
                }
            }
        }
    }

    val commands = runtime?.commands.orEmpty()
    if (commands.isNotEmpty()) {
        item(key = "dash-actions") {
            val pinned = manifest.dashboard?.actions.orEmpty()
            ListSection(header = "Actions", footer = "Runs the mod's command here; its answer shows as a message.") {
                commands.sortedBy { pinned.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }.forEach { command ->
                    ListRow(
                        title = "/${command.name}",
                        subtitle = command.description.ifBlank { null },
                        value = if (command.argumentHint != null) command.argumentHint else "Run",
                        onClick = { if (command.argumentHint != null) onRunWithArgs(command) else viewModel.runAction(mod.id, command.name) },
                    )
                }
            }
        }
    }

    item(key = "dash-activity") {
        val colors = PocketColors.current
        val lines = (data.log.map { Triple(it.time, it.text.lineSequence().firstOrNull().orEmpty(), it.level) } +
            data.audit.map { Triple(it.time, "${it.op} · ${it.decision}" + (it.detail?.let { d -> " · $d" } ?: ""), if (it.decision == "denied") "warn" else "info") })
            .sortedByDescending { it.first }.take(20)
        ListSection(header = "Activity") {
            lines.forEach { (time, text, level) ->
                ListRow(
                    title = text.take(200),
                    titleColor = when (level) { "error" -> colors.red; "warn" -> colors.orange; else -> null },
                    value = clock(time),
                )
            }
            if (lines.isEmpty()) ListRow(title = "Nothing yet.")
            ListRow(title = "Show all activity", onClick = onShowActivity)
        }
    }

    // Phase 2: a chat with the mod itself, when its code answers `dashboard.message` (or it posts here).
    val answers = runtime?.events?.contains("dashboard.message") == true
    if (answers || data.chat.isNotEmpty()) {
        item(key = "dash-chat") {
            val colors = PocketColors.current
            var draft by remember(mod.id) { mutableStateOf("") }
            ListSection(
                header = "Chat with ${manifest.name}",
                footer = if (answers) "Messages go to the mod, not to an agent." else "The mod isn't answering right now.",
            ) {
                data.chat.takeLast(30).forEach { line ->
                    ListRow(
                        title = if (line.fromUser) "You" else manifest.name,
                        subtitle = line.text.take(2_000),
                        subtitleMaxLines = 12,
                        subtitleColor = if (line.fromUser) colors.secondaryLabel else null,
                        value = clock(line.time),
                    )
                }
                if (answers) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                    ) {
                        PocketTextField(value = draft, onValueChange = { draft = it }, placeholder = "Message ${manifest.name}", modifier = Modifier.weight(1f))
                        PocketButton("Send", {
                            viewModel.sendChat(mod.id, draft)
                            draft = ""
                        }, style = PocketButtonStyle.Filled, size = PocketButtonSize.Small, enabled = draft.isNotBlank())
                    }
                }
                if (data.chat.isNotEmpty()) ListRow(title = "Clear chat", destructive = true, onClick = { viewModel.clearChat(mod.id) })
            }
        }
    }

    val stored = data.stored
    val usesModel = ModCapability.MODEL_COMPLETE in manifest.requested
    if ((stored != null && stored.length() > 0) || usesModel) {
        item(key = "dash-data") {
            ListSection(header = "Data") {
                if (usesModel) ListRow(title = "Model tokens today", value = "%,d of 50,000".format(data.modelTokens ?: 0L))
                stored?.keys()?.asSequence()?.take(20)?.forEach { key ->
                    ListRow(title = key, subtitle = stored.opt(key)?.toString()?.take(200), subtitleMaxLines = 2)
                }
                if (stored != null && stored.length() > 0) ListRow(title = "Clear saved data", destructive = true, onClick = onClearData)
            }
        }
    }
}

private fun stepTitle(step: ModAgentSteps.Step): String =
    listOfNotNull(step.role, step.harness?.replaceFirstChar { it.uppercase() }).joinToString(" · ").ifEmpty { "Agent task" } +
        if (step.fromUser) " (you)" else ""

private fun duration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else if (seconds < 3600) "${seconds / 60}m ${seconds % 60}s" else "${seconds / 3600}h ${seconds % 3600 / 60}m"
}

private fun clock(millis: Long): String =
    runCatching { java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())) }.getOrDefault("")
