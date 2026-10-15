package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.jarves.mh.model.Project
import com.jarves.mh.mods.InstalledMod
import com.jarves.mh.mods.ModAgentSteps
import com.jarves.mh.mods.ModRole
import com.jarves.mh.mods.ModRoles
import com.jarves.mh.ui.kit.*
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing

private val HARNESSES = listOf("claude" to "Claude", "codex" to "Codex", "deepseek" to "DeepSeek", "antigravity" to "Antigravity")
private val EFFORTS = listOf("low", "medium", "high")

private fun harnessTitle(id: String?): String = HARNESSES.firstOrNull { it.first == id }?.second ?: id ?: "Agent"

/**
 * The agent console of a mod with `contributes.roles` (mod dashboards phase 3): a card per role
 * where the user picks its harness, model and effort, sends it a message, stops it, hands its last
 * reply to another role and opens its chat; then the chain of steps, with the queue of sends that
 * wait their turn (a project runs one task at a time).
 */
internal fun LazyListScope.modConsole(
    mod: InstalledMod,
    roles: List<ModRole>,
    choices: Map<String, ModRoles.Choice>,
    steps: List<ModAgentSteps.Step>,
    paused: Boolean,
    projects: List<Project>,
    projectId: String?,
    onSelectProject: (String) -> Unit,
    viewModel: ModsViewModel,
    onOpenChat: (projectId: String, chatId: String) -> Unit,
) {
    val project = projects.firstOrNull { it.id == projectId }
    item(key = "console-project") {
        var open by remember { mutableStateOf(false) }
        val anchor = rememberOverlayAnchor()
        ListSection(
            header = "Agents",
            footer = "Each role works in its own chat in this project. One task runs at a time; the others wait in the queue under Chain.",
        ) {
            if (projects.isEmpty()) {
                ListRow(title = "Create a project first.")
            } else {
                ListRow(
                    title = "Project",
                    value = project?.name ?: "Choose",
                    accessory = ListRowAccessory.Chevron,
                    modifier = Modifier.overlayAnchor(anchor),
                    onClick = { open = true },
                )
                GlassMenu(expanded = open, onDismiss = { open = false }, anchor = anchor) {
                    projects.take(20).forEach { p -> GlassMenuItem(p.name, { onSelectProject(p.id) }, checked = p.id == projectId) }
                }
            }
        }
    }
    val effective = roles.map { ModRoles.apply(it, choices[it.id]) }
    effective.forEach { role ->
        item(key = "console-role-${role.id}") {
            RoleCard(mod, role, choices[role.id], effective, steps.filter { it.role == role.id }, project, viewModel, onOpenChat)
        }
    }
    item(key = "console-chain") {
        val colors = PocketColors.current
        val now = System.currentTimeMillis()
        val titles = roles.associate { it.id to it.title }
        val shown = steps.takeLast(15)
        ListSection(
            header = "Chain",
            footer = "Every step you or ${mod.manifest.name} started, newest last. Tap a step for its actions.",
        ) {
            val busy = steps.any { !it.state.done }
            if (busy || paused) {
                ListRow(title = "Stop chain", destructive = true, onClick = { viewModel.stopChain(mod.id) })
            }
            if (paused) {
                ListRow(title = "Paused", subtitle = "Queued sends and the mod's next ask wait.", value = "Resume", onClick = { viewModel.resumeChain(mod.id) })
            } else if (busy) {
                ListRow(title = "Pause after this step", onClick = { viewModel.pauseChain(mod.id) })
            }
            shown.forEach { step -> ChainRow(step, titles, now, project, viewModel, onOpenChat) }
            if (shown.isEmpty()) ListRow(title = "No steps yet.", titleColor = colors.secondaryLabel)
        }
    }
}

@Composable
private fun RoleCard(
    mod: InstalledMod,
    role: ModRole,
    choice: ModRoles.Choice?,
    roles: List<ModRole>,
    steps: List<ModAgentSteps.Step>,
    project: Project?,
    viewModel: ModsViewModel,
    onOpenChat: (String, String) -> Unit,
) {
    val colors = PocketColors.current
    val now = System.currentTimeMillis()
    val running = steps.lastOrNull { it.state == ModAgentSteps.State.RUNNING }
    val waiting = steps.count { it.state == ModAgentSteps.State.QUEUED || it.state == ModAgentSteps.State.WAITING }
    val last = steps.lastOrNull { it.state.done }
    val chatId = project?.let { viewModel.roleChat(mod.id, it.id, role.id) }
    var draft by remember(mod.id, role.id) { mutableStateOf("") }
    var editModel by remember { mutableStateOf(false) }
    var modelDraft by remember(role.model) { mutableStateOf(role.model.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }
    val base = choice ?: ModRoles.Choice()
    ListSection(header = role.title) {
        MenuRow("Harness", harnessTitle(role.harness)) {
            HARNESSES.forEach { (id, title) ->
                GlassMenuItem(title, { viewModel.setRoleChoice(mod.id, role.id, ModRoles.Choice(harness = id)) }, checked = id == role.harness)
            }
            if (choice != null) GlassMenuItem("The mod's own picks", { viewModel.setRoleChoice(mod.id, role.id, ModRoles.Choice()) })
        }
        ListRow(title = "Model", value = role.model ?: "Harness default", accessory = ListRowAccessory.Chevron, onClick = { editModel = !editModel })
        if (editModel) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
            ) {
                PocketTextField(value = modelDraft, onValueChange = { modelDraft = it.take(120) }, placeholder = "Model id, empty for the default", modifier = Modifier.weight(1f))
                PocketButton("Save", {
                    viewModel.setRoleChoice(mod.id, role.id, base.copy(harness = role.harness, model = modelDraft.trim().ifEmpty { null }))
                    editModel = false
                }, style = PocketButtonStyle.Filled, size = PocketButtonSize.Small)
            }
        }
        MenuRow("Effort", role.effort ?: "Default") {
            GlassMenuItem("Default", { viewModel.setRoleChoice(mod.id, role.id, base.copy(harness = role.harness, effort = null)) }, checked = role.effort == null)
            EFFORTS.forEach { effort ->
                GlassMenuItem(effort, { viewModel.setRoleChoice(mod.id, role.id, base.copy(harness = role.harness, effort = effort)) }, checked = effort == role.effort)
            }
        }
        ListRow(
            title = when {
                running != null -> "Running · ${consoleDuration(now - running.startedAt)}"
                waiting > 0 -> "Waiting · $waiting queued"
                else -> "Idle"
            },
            value = if (running != null) "Stop" else null,
            onClick = running?.let { step -> { viewModel.stopStep(step.id) } },
        )
        if (last != null) {
            val text = last.error ?: last.reply?.ifBlank { null } ?: "No reply."
            ListRow(
                title = if (last.state == ModAgentSteps.State.COMPLETED) "Last reply" else "Last step: ${last.state.label}",
                titleColor = if (last.state == ModAgentSteps.State.FAILED) colors.red else null,
                subtitle = if (expanded) text else text.take(400),
                subtitleMaxLines = if (expanded) 200 else 3,
                value = if (expanded) "Less" else "More",
                onClick = { expanded = !expanded },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ListInset, vertical = PocketSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
        ) {
            PocketTextField(value = draft, onValueChange = { draft = it }, placeholder = "Message the ${role.title}", modifier = Modifier.weight(1f))
            PocketButton("Send", {
                project?.let { viewModel.sendToRole(mod, role, draft, it.id) }
                draft = ""
            }, style = PocketButtonStyle.Filled, size = PocketButtonSize.Small, enabled = draft.isNotBlank() && project != null)
        }
        val others = roles.filter { it.id != role.id }
        if (project != null && others.isNotEmpty() && last?.state == ModAgentSteps.State.COMPLETED && !last.reply.isNullOrBlank()) {
            MenuRow("Hand off the last reply", "To…") {
                others.forEach { other -> GlassMenuItem(other.title, { viewModel.handoff(mod, role, other, project.id) }) }
            }
        }
        if (project != null && chatId != null) {
            ListRow(title = "Open the ${role.title}'s chat", accessory = ListRowAccessory.Chevron, onClick = { onOpenChat(project.id, chatId) })
        }
    }
}

@Composable
private fun ChainRow(
    step: ModAgentSteps.Step,
    titles: Map<String, String>,
    now: Long,
    project: Project?,
    viewModel: ModsViewModel,
    onOpenChat: (String, String) -> Unit,
) {
    val colors = PocketColors.current
    val who = listOfNotNull(step.role?.let { titles[it] ?: it }, harnessTitle(step.harness).takeIf { step.harness != null })
        .joinToString(" · ").ifEmpty { "Agent task" } + if (step.fromUser) " (you)" else " (mod)"
    val took = when {
        step.endedAt != null -> " · " + consoleDuration(step.endedAt - step.startedAt)
        step.state == ModAgentSteps.State.RUNNING -> " · " + consoleDuration(now - step.startedAt)
        else -> ""
    }
    val actions = buildList<Pair<String, () -> Unit>> {
        when {
            step.state == ModAgentSteps.State.RUNNING -> add("Stop" to { viewModel.stopStep(step.id) })
            step.state == ModAgentSteps.State.QUEUED -> {
                add("Move earlier" to { viewModel.moveStep(step.id, earlier = true) })
                add("Move later" to { viewModel.moveStep(step.id, earlier = false) })
                add("Cancel" to { viewModel.cancelStep(step.id) })
            }
            step.state.done && project != null -> add("Retry step" to { viewModel.retryStep(step.id, project.id) })
        }
        val chat = step.chatId
        if (chat != null && project != null && step.projectId == project.id) add("Open its chat" to { onOpenChat(project.id, chat) })
    }
    var open by remember { mutableStateOf(false) }
    val anchor = rememberOverlayAnchor()
    ListRow(
        title = who,
        subtitle = (step.error ?: step.prompt).lineSequence().firstOrNull()?.take(120),
        titleColor = if (step.state == ModAgentSteps.State.FAILED) colors.red else null,
        value = step.state.label + took,
        modifier = Modifier.overlayAnchor(anchor),
        onClick = if (actions.isEmpty()) null else ({ open = true }),
    )
    GlassMenu(expanded = open, onDismiss = { open = false }, anchor = anchor) {
        actions.forEach { (label, action) -> GlassMenuItem(label, action, destructive = label == "Cancel" || label == "Stop") }
    }
}

/** A row whose value opens a menu of [items]. */
@Composable
private fun MenuRow(title: String, value: String, items: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    val anchor = rememberOverlayAnchor()
    ListRow(title = title, value = value, accessory = ListRowAccessory.Chevron, modifier = Modifier.overlayAnchor(anchor), onClick = { open = true })
    GlassMenu(expanded = open, onDismiss = { open = false }, anchor = anchor, content = items)
}

internal fun consoleDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return if (seconds < 60) "${seconds}s" else if (seconds < 3600) "${seconds / 60}m ${seconds % 60}s" else "${seconds / 3600}h ${seconds % 3600 / 60}m"
}
