package com.jarves.mh.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.mods.InstalledMod
import com.jarves.mh.mods.ModAuditEntry
import com.jarves.mh.mods.ModCapability
import com.jarves.mh.mods.ModEvent
import com.jarves.mh.mods.ModHookAction
import com.jarves.mh.mods.ModManifest
import com.jarves.mh.mods.ModSetting
import com.jarves.mh.mods.ModSettingType
import com.jarves.mh.mods.ModStatus
import com.jarves.mh.mods.ModTrust
import com.jarves.mh.mods.ModsSnapshot
import com.jarves.mh.ui.kit.*
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketTransitions
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Mods root tab (spec v2 §6.6): installed mods, each with its own page, plus import, export,
 * the review sheet and the kill switch. The app ships no mods, so it starts empty.
 */
@Composable
fun ModsScreen(
    bottomBarPadding: Dp,
    viewModel: ModsViewModel = viewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val banner = LocalBanner.current
    LaunchedEffect(viewModel, banner) {
        viewModel.messages.collect { banner?.show(it, bannerKindFor(it)) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importArchive(uri)
    }
    val import = { importLauncher.launch(arrayOf("*/*")) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val openMod = snapshot.mods.firstOrNull { it.id == openId }
    BackHandler(enabled = openMod != null) { openId = null }

    AnimatedContent(targetState = openMod?.id, transitionSpec = { PocketTransitions.crossFade() }, label = "mods page") { shownId ->
        val shown = snapshot.mods.firstOrNull { it.id == shownId }
        if (shown == null) {
            ModsList(snapshot, busy, bottomBarPadding, import, onOpen = { openId = it }, viewModel)
        } else {
            ModPage(shown, snapshot, bottomBarPadding, onBack = { openId = null }, viewModel)
        }
    }

    review?.let { current ->
        ModReviewSheet(current, busy, onInstall = viewModel::confirmInstall, onDismiss = viewModel::dismissReview)
    }
}

@Composable
private fun ModsList(
    snapshot: ModsSnapshot,
    busy: Boolean,
    bottomBarPadding: Dp,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    viewModel: ModsViewModel,
) {
    val colors = PocketColors.current
    val listState = rememberLazyListState()
    val collapse by rememberTitleCollapse(listState)
    var menuOpen by remember { mutableStateOf(false) }
    val moreAnchor = rememberOverlayAnchor()

    LargeTitleScaffold(
        title = "Mods",
        collapse = { collapse },
        trailing = {
            GlassToolbarGroup {
                GlassToolbarItem(Icons.Outlined.FileDownload, "Import a mod", onImport, enabled = !busy)
                GlassToolbarItem(Icons.Outlined.MoreHoriz, "More", { menuOpen = true }, Modifier.overlayAnchor(moreAnchor))
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hostBackdropSource(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomBarPadding + PocketSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xl),
        ) {
            item(key = "title") { LargeTitle("Mods", subtitle = modsSubtitle(snapshot)) }
            if (snapshot.safeMode) {
                item(key = "safe") {
                    ListSection(footer = "The app restarted several times in a row without settling, so mods are paused for this run.") {
                        ListRow(
                            title = "Mods are paused",
                            icon = Icons.Outlined.Warning,
                            iconTile = colors.orange,
                            value = "Turn back on",
                            onClick = viewModel::leaveSafeMode,
                        )
                    }
                }
            }
            if (!snapshot.modsEnabled) {
                item(key = "off") {
                    ListSection(footer = "Turned-off mods add nothing to your chats. Their files and settings stay.") {
                        ToggleRow(
                            title = "Mods",
                            subtitle = "All mods are off",
                            checked = false,
                            onCheckedChange = viewModel::setModsEnabled,
                            icon = Icons.Outlined.PowerSettingsNew,
                            iconTile = colors.gray,
                        )
                    }
                }
            }
            if (snapshot.mods.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Extension,
                        title = "No mods yet",
                        message = "Mods add commands, rules, skills and automatic actions to your chats. Import a .mhmod file to add one.",
                        actionLabel = "Import a mod",
                        onAction = onImport,
                        modifier = Modifier.fillMaxWidth().padding(vertical = PocketSpacing.xl),
                    )
                }
            } else {
                item(key = "installed") {
                    ListSection(header = "Installed") {
                        snapshot.mods.forEach { mod ->
                            val status = snapshot.status(mod)
                            ListRow(
                                title = mod.manifest.name,
                                subtitle = "${mod.manifest.version} · ${status.label} · ${contributionSummary(mod.manifest)}",
                                subtitleMaxLines = 1,
                                subtitleColor = if (status == ModStatus.NEEDS_REVIEW) colors.orange else null,
                                leading = { MonogramTile(monogram(mod.manifest.name), modColor(mod.id, colors), tinted = true) },
                                onClick = { onOpen(mod.id) },
                                trailing = {
                                    PocketToggle(
                                        checked = mod.enabled,
                                        onCheckedChange = { viewModel.setEnabled(mod.id, it) },
                                        contentDescription = "${mod.manifest.name} on",
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    GlassMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, anchor = moreAnchor) {
        GlassMenuItem("Import a mod", onImport, icon = Icons.Outlined.FileDownload, enabled = !busy)
        GlassMenuDivider()
        GlassMenuItem(
            if (snapshot.modsEnabled) "Turn all mods off" else "Turn mods on",
            { viewModel.setModsEnabled(!snapshot.modsEnabled) },
            icon = Icons.Outlined.PowerSettingsNew,
            destructive = snapshot.modsEnabled,
        )
    }
}

private enum class ModPageTab(val label: String) { DASHBOARD("Dashboard"), CONTROLS("Controls"), PERMISSIONS("Permissions"), ACTIVITY("Activity"), ABOUT("About") }

@Composable
private fun ModPage(
    mod: InstalledMod,
    snapshot: ModsSnapshot,
    bottomBarPadding: Dp,
    onBack: () -> Unit,
    viewModel: ModsViewModel,
) {
    val listState = rememberLazyListState()
    val collapse by rememberTitleCollapse(listState)
    val tabs = ModPageTab.entries.filter { it != ModPageTab.DASHBOARD || mod.manifest.dashboard != null }
    var tab by rememberSaveable(mod.id) { mutableStateOf(ModPageTab.CONTROLS) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember { mutableStateOf<ModSetting?>(null) }
    var confirmHigh by remember { mutableStateOf<ModCapability?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var audit by remember(mod.id) { mutableStateOf<List<ModAuditEntry>>(emptyList()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(mod.id, uri)
    }
    LaunchedEffect(mod, tab) { if (tab == ModPageTab.ACTIVITY) audit = viewModel.audit(mod.id) }
    val showText = { title: String, path: String -> preview = title to path }

    LargeTitleScaffold(
        title = mod.manifest.name,
        collapse = { collapse },
        leading = { GlassToolbarButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hostBackdropSource(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomBarPadding + PocketSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xl),
        ) {
            item(key = "title") {
                LargeTitle(mod.manifest.name, subtitle = "${mod.manifest.version} · ${snapshot.status(mod).label}")
            }
            item(key = "tabs") {
                Column(Modifier.padding(horizontal = ListInset)) {
                    SegmentedControl(tabs, tab, { tab = it }, label = { it.label })
                }
            }
            mod.problem?.let { problem ->
                item(key = "problem") {
                    ListSection {
                        ListRow(problem, icon = Icons.Outlined.Warning, iconTile = PocketColors.current.orange, subtitleMaxLines = 4)
                    }
                }
            }
            when (tab) {
                ModPageTab.DASHBOARD -> dashboardTab(mod)
                ModPageTab.CONTROLS -> controlsTab(mod, viewModel, onPreview = showText, onEdit = { editing = it })
                ModPageTab.PERMISSIONS -> permissionsTab(mod, viewModel, onConfirmHigh = { confirmHigh = it })
                ModPageTab.ACTIVITY -> activityTab(mod, audit, viewModel)
                ModPageTab.ABOUT -> aboutTab(
                    mod,
                    onExport = { exportLauncher.launch("${mod.manifest.name.replace(Regex("[^A-Za-z0-9._-]+"), "-")}-${mod.manifest.version}.mhmod") },
                    onRemove = { confirmRemove = true },
                )
            }
        }
    }

    preview?.let { (title, path) ->
        var text by remember(path) { mutableStateOf("") }
        LaunchedEffect(path) { text = viewModel.readText(mod, path) }
        GlassSheet(
            onDismiss = { preview = null },
            title = title,
            detents = listOf(SheetDetent.Medium, SheetDetent.Large),
            trailing = { SheetTextButton("Done", { preview = null }, emphasized = true) },
        ) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
                item {
                    Text(
                        text,
                        style = PocketType.codeSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = ListInset),
                    )
                }
            }
        }
    }

    editing?.let { setting ->
        var value by remember(setting.key) { mutableStateOf(mod.settingValue(setting.key)?.let(::formatSetting).orEmpty()) }
        val number = setting.type == ModSettingType.NUMBER
        val parsed: Any? = if (number) value.trim().toDoubleOrNull()?.takeIf { it.isFinite() } else value
        val valid = parsed != null && (number || value.length <= (setting.maxLength ?: 1024))
        GlassSheet(
            onDismiss = { editing = null },
            title = setting.title,
            detents = listOf(SheetDetent.Fit),
            leading = { SheetTextButton("Cancel", { editing = null }) },
            trailing = {
                SheetTextButton("Save", {
                    parsed?.let { viewModel.setSetting(mod.id, setting.key, it) }
                    editing = null
                }, emphasized = true, enabled = valid)
            },
        ) {
            Column(Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
                PocketTextField(
                    value = value,
                    onValueChange = { value = it },
                    helper = setting.description,
                    error = if (!valid && value.isNotEmpty()) (if (number) "Enter a number." else "Too long.") else null,
                    keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                )
            }
        }
    }

    confirmHigh?.let { capability ->
        val permission = mod.manifest.permission(capability)
        PocketAlert(
            onDismiss = { confirmHigh = null },
            title = "Allow “${capability.summary}”?",
            message = listOfNotNull(
                permission?.reason?.let { "${mod.manifest.name} says: $it" },
                permission?.paths?.takeIf { it.isNotEmpty() }?.let { "Only: ${it.joinToString()}" },
                "This is a high-risk permission.",
            ).joinToString("\n\n"),
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmHigh = null },
                AlertAction("Allow", AlertRole.Destructive) {
                    viewModel.setGranted(mod.id, capability, true)
                    confirmHigh = null
                },
            ),
        )
    }

    if (confirmRemove) {
        PocketAlert(
            onDismiss = { confirmRemove = false },
            title = "Remove ${mod.manifest.name}?",
            message = "Its commands, rules and hooks go away. You can import it again later.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmRemove = false },
                AlertAction("Remove", AlertRole.Destructive) {
                    confirmRemove = false
                    onBack()
                    viewModel.remove(mod.id, deleteData = false)
                },
                AlertAction("Remove and delete its data", AlertRole.Destructive) {
                    confirmRemove = false
                    onBack()
                    viewModel.remove(mod.id, deleteData = true)
                },
            ),
        )
    }
}

private fun LazyListScope.dashboardTab(mod: InstalledMod) {
    item(key = "dashboard") {
        ListSection(footer = "Dashboards are drawn by mod code. This version of the app cannot run mod code yet.") {
            ListRow("Dashboard “${mod.manifest.dashboard?.pane}”", subtitle = "Not available in this version")
        }
    }
}

private fun LazyListScope.controlsTab(
    mod: InstalledMod,
    viewModel: ModsViewModel,
    onPreview: (String, String) -> Unit,
    onEdit: (ModSetting) -> Unit,
) {
    val manifest = mod.manifest
    item(key = "switch") {
        ListSection {
            ToggleRow(title = "On", checked = mod.enabled, onCheckedChange = { viewModel.setEnabled(mod.id, it) })
        }
    }
    if (manifest.settings.isNotEmpty()) {
        item(key = "settings") {
            ListSection(header = "Settings") {
                manifest.settings.forEach { setting -> SettingRow(mod, setting, viewModel, onEdit) }
            }
        }
    }
    if (manifest.slashCommands.isNotEmpty() || manifest.promptTemplates.isNotEmpty()) {
        item(key = "commands") {
            ListSection(header = "In chats", footer = "Type / in a chat to use these. Templates fill the message box so you can edit before sending.") {
                manifest.slashCommands.forEach { command ->
                    ListRow(
                        title = "/${command.name}",
                        subtitle = command.description,
                        value = command.agents?.let { agents -> agents.joinToString { it.shortTitle() } },
                    )
                }
                manifest.promptTemplates.forEach { template ->
                    ListRow(title = template.title, subtitle = "Template · ${template.template.lineSequence().first().take(80)}", subtitleMaxLines = 1)
                }
            }
        }
    }
    if (manifest.rules.isNotEmpty()) {
        item(key = "rules") {
            ListSection(header = "Rules", footer = "A rule that is on is added to every message you send to an agent. Tap to read it first.") {
                manifest.rules.forEach { rule ->
                    ListRow(
                        title = rule.id,
                        subtitle = rule.file,
                        onClick = { onPreview(rule.id, rule.file) },
                        trailing = {
                            PocketToggle(rule.id in mod.enabledRules, { viewModel.setRuleEnabled(mod.id, rule.id, it) }, contentDescription = "Rule ${rule.id}")
                        },
                    )
                }
            }
        }
    }
    if (manifest.skills.isNotEmpty()) {
        item(key = "skills") {
            ListSection(header = "Skills", footer = "Use a skill by typing / and its name in a chat. Tap to read it first.") {
                manifest.skills.forEach { skill ->
                    ListRow(
                        title = skill.id,
                        subtitle = "${skill.dir}/SKILL.md",
                        onClick = { onPreview(skill.id, "${skill.dir}/SKILL.md") },
                        trailing = {
                            PocketToggle(skill.id in mod.enabledSkills, { viewModel.setSkillEnabled(mod.id, skill.id, it) }, contentDescription = "Skill ${skill.id}")
                        },
                    )
                }
            }
        }
    }
    if (manifest.hooks.isNotEmpty()) {
        item(key = "hooks") {
            ListSection(header = "Automatic actions", footer = "An action that fails five times in a row turns itself off.") {
                manifest.hooks.forEach { hook ->
                    val missing = hook.action.capability !in mod.granted
                    ListRow(
                        title = eventLabel(hook.on) + (hook.status?.let { " ($it)" } ?: ""),
                        subtitle = actionLabel(hook.action) + if (missing) " · needs “${hook.action.capability.summary}”" else "",
                        trailing = {
                            PocketToggle(hook.id !in mod.disabledHooks, { viewModel.setHookEnabled(mod.id, hook.id, it) }, contentDescription = "Action ${hook.id}")
                        },
                    )
                }
            }
        }
    }
    if (manifest.hooksModule != null) {
        item(key = "code") {
            ListSection(footer = "This mod has a code part (${manifest.hooksModule}). This version of the app cannot run mod code yet.") {
                ListRow("Mod code", value = "Not running")
            }
        }
    }
}

@Composable
private fun SettingRow(mod: InstalledMod, setting: ModSetting, viewModel: ModsViewModel, onEdit: (ModSetting) -> Unit) {
    val value = mod.settingValue(setting.key)
    when (setting.type) {
        ModSettingType.BOOL -> ToggleRow(
            title = setting.title,
            subtitle = setting.description,
            checked = value == true,
            onCheckedChange = { viewModel.setSetting(mod.id, setting.key, it) },
        )
        ModSettingType.ENUM -> {
            var open by remember { mutableStateOf(false) }
            val anchor = rememberOverlayAnchor()
            ListRow(
                title = setting.title,
                subtitle = setting.description,
                value = value?.toString(),
                accessory = ListRowAccessory.Chevron,
                modifier = Modifier.overlayAnchor(anchor),
                onClick = { open = true },
            )
            GlassMenu(expanded = open, onDismiss = { open = false }, anchor = anchor) {
                setting.options.forEach { option ->
                    GlassMenuItem(option, { viewModel.setSetting(mod.id, setting.key, option) }, checked = option == value)
                }
            }
        }
        ModSettingType.STRING, ModSettingType.NUMBER -> ListRow(
            title = setting.title,
            subtitle = setting.description,
            value = value?.let(::formatSetting)?.take(30),
            accessory = ListRowAccessory.Chevron,
            onClick = { onEdit(setting) },
        )
        ModSettingType.SECRET -> ListRow(
            title = setting.title,
            subtitle = "Only mod code can use secrets, and this version cannot run mod code yet.",
            enabled = false,
        )
    }
}

private fun LazyListScope.permissionsTab(mod: InstalledMod, viewModel: ModsViewModel, onConfirmHigh: (ModCapability) -> Unit) {
    if (mod.trust == ModTrust.BUILT) {
        item(key = "trust") {
            ListSection(footer = "Mods built in Mod Studio cannot use high-risk permissions until you trust them. You still allow each one.") {
                ToggleRow(title = "Trust this mod", checked = mod.trusted, onCheckedChange = { viewModel.setTrusted(mod.id, it) })
            }
        }
    }
    item(key = "permissions") {
        ListSection(
            header = "Permissions",
            footer = if (mod.manifest.permissions.isEmpty()) "This mod asks for no permissions." else
                "Mods never approve agent tool requests. Turning a permission off stops the actions that need it.",
        ) {
            mod.manifest.permissions.forEach { permission ->
                val capability = permission.capability
                val details = listOfNotNull(
                    "${capability.id} · ${riskLabel(capability.risk)}",
                    permission.reason,
                    permission.paths.takeIf { it.isNotEmpty() }?.let { "Only ${it.joinToString()}" },
                ).joinToString("\n")
                val granted = capability in mod.granted
                ListRow(
                    title = capability.summary,
                    subtitle = details,
                    subtitleMaxLines = 4,
                    value = if (!capability.usable) "Not in this version" else null,
                    trailing = if (!capability.usable) null else {
                        {
                            PocketToggle(
                                checked = granted,
                                enabled = granted || mod.canHold(capability),
                                onCheckedChange = { on ->
                                    if (on && capability.risk == RiskLevel.HIGH) onConfirmHigh(capability)
                                    else viewModel.setGranted(mod.id, capability, on)
                                },
                                contentDescription = capability.summary,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun LazyListScope.activityTab(mod: InstalledMod, audit: List<ModAuditEntry>, viewModel: ModsViewModel) {
    if (mod.disabledHooks.isNotEmpty()) {
        item(key = "stopped") {
            ListSection(header = "Turned off") {
                mod.manifest.hooks.filter { it.id in mod.disabledHooks }.forEach { hook ->
                    ListRow(
                        title = eventLabel(hook.on),
                        subtitle = actionLabel(hook.action),
                        value = "Turn on",
                        onClick = { viewModel.setHookEnabled(mod.id, hook.id, true) },
                    )
                }
            }
        }
    }
    item(key = "log") {
        ListSection(header = "Recent activity", footer = if (audit.isEmpty()) "Nothing yet." else "The newest 100 entries.") {
            audit.forEach { entry ->
                ListRow(
                    title = "${entry.op} · ${entry.decision}",
                    subtitle = listOfNotNull(entry.capability, entry.detail).joinToString(" · ").ifBlank { null },
                    subtitleMaxLines = 3,
                    value = formatTime(entry.time),
                )
            }
        }
    }
}

private fun LazyListScope.aboutTab(mod: InstalledMod, onExport: () -> Unit, onRemove: () -> Unit) {
    val manifest = mod.manifest
    item(key = "about") {
        ListSection(footer = manifest.description.ifBlank { null }) {
            ListRow("Version", value = manifest.version)
            ListRow("ID", value = manifest.id)
            if (manifest.author.isNotBlank()) ListRow("Author", subtitle = "Not verified", value = manifest.author)
            ListRow("Source", subtitle = mod.sourceLabel.ifBlank { null }, value = mod.trust.label)
            ListRow("Installed", value = formatTime(mod.installedAt))
        }
    }
    item(key = "files") {
        ListSection(header = "Files", footer = "Each file is checked against its SHA-256 hash when the mod is turned on.") {
            ListRow("mod.json")
            manifest.integrity.forEach { (path, hash) -> ListRow(path, value = hash.take(12)) }
        }
    }
    item(key = "actions") {
        ListSection {
            ListRow("Export as .mhmod", onClick = onExport)
            ListRow("Remove", destructive = true, onClick = onRemove)
        }
    }
}

@Composable
private fun ModReviewSheet(review: ModReview, busy: Boolean, onInstall: (Set<ModCapability>) -> Unit, onDismiss: () -> Unit) {
    val colors = PocketColors.current
    val staged = review.staged
    val manifest = staged?.manifest
    var granted by remember(staged) {
        mutableStateOf(manifest?.permissions.orEmpty().map { it.capability }
            .filter { it.usable && it.risk != RiskLevel.HIGH }.toSet())
    }
    var confirmHigh by remember { mutableStateOf<ModCapability?>(null) }
    GlassSheet(
        onDismiss = { if (!busy) onDismiss() },
        title = if (manifest == null) "Can’t install" else if (review.existing != null) "Update mod" else "Install mod",
        detents = listOf(SheetDetent.Large),
        leading = { SheetTextButton(if (manifest == null) "Close" else "Cancel", onDismiss, enabled = !busy) },
        trailing = if (manifest == null) null else {
            { SheetTextButton(if (busy) "Installing…" else "Install", { onInstall(granted) }, emphasized = true, enabled = !busy) }
        },
    ) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = PocketSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.xl),
        ) {
            if (manifest == null || staged == null) {
                item {
                    ListSection(header = review.sourceLabel, footer = "Fix these in the mod and import it again.") {
                        ListRow(review.error ?: "This mod cannot be installed.", icon = Icons.Outlined.Warning, iconTile = colors.red, subtitleMaxLines = 4)
                        review.problems.take(30).forEach { ListRow(it, subtitleMaxLines = 4) }
                    }
                }
                return@LazyColumn
            }
            item {
                ListSection(footer = manifest.description.ifBlank { null }) {
                    ListRow(
                        title = manifest.name,
                        subtitle = listOfNotNull(
                            manifest.id,
                            manifest.author.takeIf { it.isNotBlank() }?.let { "by $it (not verified)" },
                            review.existing?.let { "Updates ${it.manifest.version} to ${manifest.version}" } ?: "Version ${manifest.version}",
                            review.trust.label + " · " + review.sourceLabel,
                        ).joinToString("\n"),
                        subtitleMaxLines = 5,
                        leading = { MonogramTile(monogram(manifest.name), modColor(manifest.id, colors), tinted = true) },
                    )
                }
            }
            if (staged.warnings.isNotEmpty()) {
                item {
                    ListSection {
                        staged.warnings.forEach { ListRow(it, icon = Icons.Outlined.Warning, iconTile = colors.orange, subtitleMaxLines = 5) }
                    }
                }
            }
            item {
                ListSection(
                    header = "Permissions",
                    footer = if (manifest.permissions.isEmpty()) "It asks for no permissions." else
                        "Allow only what you expect this mod to do. You can change these later on its page.",
                ) {
                    manifest.permissions.forEach { permission ->
                        val capability = permission.capability
                        val blocked = capability.risk == RiskLevel.HIGH && review.trust == ModTrust.BUILT
                        ListRow(
                            title = capability.summary,
                            subtitle = listOfNotNull(
                                "${capability.id} · ${riskLabel(capability.risk)}",
                                permission.reason,
                                permission.paths.takeIf { it.isNotEmpty() }?.let { "Only ${it.joinToString()}" },
                                if (blocked) "Turn on “Trust this mod” on its page first." else null,
                            ).joinToString("\n"),
                            subtitleMaxLines = 5,
                            value = if (!capability.usable) "Not in this version" else null,
                            trailing = if (!capability.usable || blocked) null else {
                                {
                                    PocketToggle(
                                        checked = capability in granted,
                                        onCheckedChange = { on ->
                                            if (on && capability.risk == RiskLevel.HIGH) confirmHigh = capability
                                            else granted = if (on) granted + capability else granted - capability
                                        },
                                        contentDescription = capability.summary,
                                    )
                                }
                            },
                        )
                    }
                }
            }
            item {
                ListSection(header = "What it adds", footer = "Rules and skills start off. Turn them on from the mod's page after reading them.") {
                    val manifestAdds = addsSummary(manifest)
                    if (manifestAdds.isEmpty()) ListRow("Nothing that runs in this version")
                    manifestAdds.forEach { (title, detail) -> ListRow(title, subtitle = detail, subtitleMaxLines = 6) }
                }
            }
            item {
                ListSection(header = "Files", footer = "${staged.files.size + 1} files, ${formatMegabytes(staged.totalBytes)}. Hashes are checked again each time it is turned on.") {
                    ListRow("mod.json")
                    staged.files.entries.take(50).forEach { (path, hash) -> ListRow(path, value = hash.take(12)) }
                    if (staged.files.size > 50) ListRow("and ${staged.files.size - 50} more")
                }
            }
        }
    }
    confirmHigh?.let { capability ->
        val permission = manifest?.permission(capability)
        PocketAlert(
            onDismiss = { confirmHigh = null },
            title = "Allow “${capability.summary}”?",
            message = listOfNotNull(
                permission?.reason?.let { "${manifest.name} says: $it" },
                permission?.paths?.takeIf { it.isNotEmpty() }?.let { "Only: ${it.joinToString()}" },
                "This is a high-risk permission.",
            ).joinToString("\n\n"),
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmHigh = null },
                AlertAction("Allow", AlertRole.Destructive) {
                    granted = granted + capability
                    confirmHigh = null
                },
            ),
        )
    }
}

private fun addsSummary(manifest: ModManifest): List<Pair<String, String>> = buildList {
    if (manifest.slashCommands.isNotEmpty()) add("Commands" to manifest.slashCommands.joinToString("\n") { "/${it.name} · ${it.description}" })
    if (manifest.promptTemplates.isNotEmpty()) add("Templates" to manifest.promptTemplates.joinToString("\n") { it.title })
    if (manifest.rules.isNotEmpty()) add("Rules" to manifest.rules.joinToString("\n") { it.id })
    if (manifest.skills.isNotEmpty()) add("Skills" to manifest.skills.joinToString("\n") { it.id })
    if (manifest.hooks.isNotEmpty()) add("Automatic actions" to manifest.hooks.joinToString("\n") { "${eventLabel(it.on)}: ${actionLabel(it.action)}" })
    if (manifest.settings.isNotEmpty()) add("Settings" to manifest.settings.joinToString("\n") { it.title })
}

private fun modsSubtitle(snapshot: ModsSnapshot): String? = when {
    snapshot.mods.isEmpty() -> null
    snapshot.safeMode -> "Paused after restarts"
    !snapshot.modsEnabled -> "All off"
    else -> "${snapshot.active.size} of ${snapshot.mods.size} on"
}

private fun contributionSummary(manifest: ModManifest): String {
    val parts = listOfNotNull(
        manifest.slashCommands.size.takeIf { it > 0 }?.let { if (it == 1) "1 command" else "$it commands" },
        manifest.rules.size.takeIf { it > 0 }?.let { if (it == 1) "1 rule" else "$it rules" },
        manifest.skills.size.takeIf { it > 0 }?.let { if (it == 1) "1 skill" else "$it skills" },
        manifest.hooks.size.takeIf { it > 0 }?.let { if (it == 1) "1 action" else "$it actions" },
    )
    return parts.joinToString(", ").ifEmpty { "${manifest.permissions.size} permissions" }
}

internal fun eventLabel(event: ModEvent): String = when (event) {
    ModEvent.TASK_STARTED -> "When a task starts"
    ModEvent.TASK_FINISHED -> "When a task finishes"
    ModEvent.SESSION_COMPLETED -> "When an agent run completes"
    ModEvent.SESSION_FAILED -> "When an agent run fails"
    ModEvent.FILES_CHANGED -> "When the agent changes files"
    ModEvent.TOOL_COMPLETED -> "When an agent tool finishes"
    ModEvent.TOOL_REQUESTED -> "When the agent asks for approval"
    ModEvent.TERMINAL_COMMAND_FINISHED -> "When a terminal command finishes"
    ModEvent.ASSISTANT_MESSAGE_COMPLETED -> "When the agent finishes a reply"
    ModEvent.PROJECT_OPENED -> "When a project opens"
    ModEvent.PROJECT_CLOSED -> "When a project closes"
    ModEvent.CHAT_SWITCHED -> "When you switch chats"
    ModEvent.APP_RESUMED -> "When you return to the app"
    ModEvent.RUN_STARTED -> "When an agent run starts"
    ModEvent.RUN_FINISHED -> "When an agent run ends"
    ModEvent.BUILD_FINISHED -> "When an Android build finishes"
}

internal fun actionLabel(action: ModHookAction): String = when (action) {
    is ModHookAction.Notify -> "Notify: ${action.title}"
    is ModHookAction.Toast -> "Show: ${action.text}"
    is ModHookAction.ChatPost -> "Post in chat: ${action.text.take(60)}"
    is ModHookAction.PromptDraft -> "Fill the message box: ${action.text.take(60)}"
    is ModHookAction.FilesAppend -> "Add a line to ${action.path}"
}

private fun riskLabel(risk: RiskLevel): String = when (risk) {
    RiskLevel.SAFE -> "low risk"
    RiskLevel.REVIEW -> "review"
    RiskLevel.HIGH -> "high risk"
}

private fun AgentKind.shortTitle(): String = when (this) {
    AgentKind.CLAUDE_CODE -> "Claude"
    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek"
    AgentKind.ANTIGRAVITY -> "Antigravity"
    AgentKind.CODEX -> "Codex"
}

private fun formatSetting(value: Any): String = when (value) {
    is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()
    else -> value.toString()
}

private val TimeFormat = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

private fun formatTime(millis: Long): String =
    runCatching { TimeFormat.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())) }.getOrDefault("")

private fun monogram(name: String): String =
    name.split(Regex("\\s+")).filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase(Locale.ROOT) }.ifEmpty { "M" }

private fun modColor(id: String, colors: com.jarves.mh.ui.theme.PocketColorRoles) =
    listOf(colors.blue, colors.indigo, colors.purple, colors.pink, colors.orange, colors.teal, colors.green)[(id.hashCode() and 0x7fffffff) % 7]
