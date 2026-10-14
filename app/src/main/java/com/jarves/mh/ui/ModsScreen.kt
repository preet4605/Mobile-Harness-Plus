package com.jarves.mh.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.Project
import com.jarves.mh.model.RiskLevel
import com.jarves.mh.mods.InstalledMod
import com.jarves.mh.mods.ModAuditEntry
import com.jarves.mh.mods.ModCapability
import com.jarves.mh.mods.ModEngineState
import com.jarves.mh.mods.ModEvent
import com.jarves.mh.mods.ModHostSnapshot
import com.jarves.mh.mods.ModLogEntry
import com.jarves.mh.mods.ModPlace
import com.jarves.mh.mods.ModSurfaces
import com.jarves.mh.mods.ModRunState
import com.jarves.mh.mods.ModRuntimeInfo
import com.jarves.mh.mods.ModHookAction
import com.jarves.mh.mods.ModManifest
import com.jarves.mh.mods.ModSetting
import com.jarves.mh.mods.ModSettingType
import com.jarves.mh.mods.ModStatus
import com.jarves.mh.mods.ModTrust
import com.jarves.mh.mods.ModWebPanel
import com.jarves.mh.mods.ModsSnapshot
import com.jarves.mh.ui.kit.*
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketTransitions
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Mods root tab (spec v2 §6.6): installed mods, each with its own page, plus import, export,
 * the review sheet and the kill switch; and Mod Studio, where an agent the user has set up builds
 * a mod as a draft project the user installs from here. The app ships no mods, so it starts empty.
 */
@Composable
fun ModsScreen(
    bottomBarPadding: Dp,
    projects: List<Project> = emptyList(),
    agents: List<AgentKind> = emptyList(),
    currentAgent: AgentKind = AgentKind.CLAUDE_CODE,
    onBuildMod: (name: String, idea: String, agent: AgentKind) -> Unit = { _, _, _ -> },
    onOpenProject: (Project) -> Unit = {},
    viewModel: ModsViewModel = viewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val review by viewModel.review.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val drafts by viewModel.drafts.collectAsStateWithLifecycle()
    val host by viewModel.hostSnapshot.collectAsStateWithLifecycle()
    val surfaces by viewModel.surfaces.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.setVisible(true)
        onDispose { viewModel.setVisible(false) }
    }
    LaunchedEffect(projects) { viewModel.refreshDrafts(projects) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshDrafts(projects) }
    var building by rememberSaveable { mutableStateOf(false) }
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
    BackHandler(enabled = openMod != null || openId == ENGINE_TEST_PAGE) { openId = null }
    val page = if (openId == ENGINE_TEST_PAGE) ENGINE_TEST_PAGE else openMod?.id

    AnimatedContent(targetState = page, transitionSpec = { PocketTransitions.crossFade() }, label = "mods page") { shownId ->
        val shown = snapshot.mods.firstOrNull { it.id == shownId }
        when {
            shownId == ENGINE_TEST_PAGE -> ModEngineTestPage(bottomBarPadding, onBack = { openId = null }, viewModel)
            shown == null -> ModsList(
                snapshot, host, drafts, busy, bottomBarPadding, import,
                onOpen = { openId = it },
                onBuild = { building = true },
                onOpenDraft = { onOpenProject(it.project) },
                onEngineTest = { openId = ENGINE_TEST_PAGE },
                projects = projects,
                viewModel = viewModel,
            )
            else -> ModPage(shown, snapshot, host.mods[shown.id], surfaces[shown.id], bottomBarPadding, onBack = { openId = null }, viewModel)
        }
    }

    review?.let { current ->
        ModReviewSheet(current, busy, onInstall = viewModel::confirmInstall, onDismiss = viewModel::dismissReview)
    }
    if (building) {
        NewModSheet(
            agents = agents,
            currentAgent = currentAgent,
            onCreate = { name, idea, agent ->
                building = false
                onBuildMod(name, idea, agent)
            },
            onDismiss = { building = false },
        )
    }
}

private enum class ModsSection(val label: String) { INSTALLED("Installed"), STUDIO("Studio") }

/** Page key for the engine test; mod ids are lowercase and dotted, so it never matches one. */
private const val ENGINE_TEST_PAGE = "#engine-test"

@Composable
private fun ModsList(
    snapshot: ModsSnapshot,
    host: ModHostSnapshot,
    drafts: List<ModDraft>,
    busy: Boolean,
    bottomBarPadding: Dp,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onBuild: () -> Unit,
    onOpenDraft: (ModDraft) -> Unit,
    onEngineTest: () -> Unit,
    projects: List<Project>,
    viewModel: ModsViewModel,
) {
    val colors = PocketColors.current
    var section by rememberSaveable { mutableStateOf(ModsSection.INSTALLED) }
    val listState = rememberLazyListState()
    val collapse by rememberTitleCollapse(listState)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDeveloper by remember { mutableStateOf(false) }
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
            item(key = "sections") {
                Column(Modifier.padding(horizontal = ListInset)) {
                    SegmentedControl(ModsSection.entries, section, { section = it }, label = { it.label })
                }
            }
            if (section == ModsSection.STUDIO) {
                studioSection(drafts, busy, onBuild, onOpenDraft, onImport, viewModel)
                return@LazyColumn
            }
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
            if (host.engine == ModEngineState.PAUSED || host.engine == ModEngineState.UNAVAILABLE) {
                item(key = "engine") {
                    val paused = host.engine == ModEngineState.PAUSED
                    ListSection(footer = host.engineMessage) {
                        ListRow(
                            title = if (paused) "Mod code is paused" else "Mod code can't run on this phone",
                            icon = Icons.Outlined.Warning,
                            iconTile = colors.orange,
                            value = if (paused) "Resume" else null,
                            onClick = if (paused) viewModel::resumeModCode else null,
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
                        message = "Mods add commands, rules, skills and automatic actions to your chats. Build one with an agent in Studio, or import a .mhmod file.",
                        actionLabel = "Build a mod",
                        onAction = { section = ModsSection.STUDIO },
                        modifier = Modifier.fillMaxWidth().padding(vertical = PocketSpacing.xl),
                    )
                }
            } else {
                item(key = "installed") {
                    ListSection(header = "Installed") {
                        snapshot.mods.forEach { mod ->
                            val status = snapshot.status(mod)
                            val runtime = host.mods[mod.id]?.takeIf { status == ModStatus.ON }
                            ListRow(
                                title = mod.manifest.name,
                                subtitle = "${mod.manifest.version} · ${runtime?.state?.label ?: status.label} · " +
                                    (runtime?.summary ?: contributionSummary(mod.manifest)),
                                subtitleMaxLines = 1,
                                subtitleColor = if (status == ModStatus.NEEDS_REVIEW || runtime?.state == ModRunState.CRASHED) colors.orange else null,
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
            item(key = "developer") {
                ListSection(
                    footer = "Developer mode lets mods you allow run commands, change settings, approve tool calls and more. " +
                        "Each mod still needs each developer permission. Turning it off stops them all at once.",
                ) {
                    ToggleRow(
                        title = "Developer mode",
                        subtitle = if (snapshot.developerMode) "Developer permissions work" else "Off",
                        checked = snapshot.developerMode,
                        onCheckedChange = { on -> if (on) confirmDeveloper = true else viewModel.setDeveloperMode(false) },
                        icon = Icons.Outlined.Code,
                        iconTile = if (snapshot.developerMode) colors.orange else colors.gray,
                    )
                }
            }
            val gateProjects = projects.filter { project -> drafts.none { it.project.id == project.id } }.take(30)
            if (snapshot.developerMode && gateProjects.isNotEmpty()) {
                item(key = "codex-gate") {
                    ListSection(
                        header = "Mods gate Codex",
                        footer = "In these projects Codex asks before it runs a command or changes a file, and mods that gate " +
                            "tool calls answer for you. Codex works as before in the others. Leave it off until you need it.",
                    ) {
                        gateProjects.forEach { project ->
                            ToggleRow(
                                title = project.name,
                                checked = project.id in snapshot.codexGateProjects,
                                onCheckedChange = { viewModel.setCodexGate(project.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDeveloper) {
        PocketAlert(
            onDismiss = { confirmDeveloper = false },
            title = "Turn on Developer mode?",
            message = "Mods you give developer permissions can then run commands in your projects, change app settings, " +
                "start agent chains and approve tool calls. Only allow them for mods you built or fully trust.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmDeveloper = false },
                AlertAction("Turn on", AlertRole.Destructive) {
                    confirmDeveloper = false
                    viewModel.setDeveloperMode(true)
                },
            ),
        )
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
        GlassMenuDivider()
        GlassMenuItem("Engine test", onEngineTest, icon = Icons.Outlined.Speed)
    }
}

private fun LazyListScope.studioSection(
    drafts: List<ModDraft>,
    busy: Boolean,
    onBuild: () -> Unit,
    onOpenDraft: (ModDraft) -> Unit,
    onImport: () -> Unit,
    viewModel: ModsViewModel,
) {
    item(key = "build") {
        val colors = PocketColors.current
        ListSection(footer = "Describe the mod and pick an agent you have set up. It writes the mod in a draft project; you install it from here when it is ready.") {
            ListRow("Build a new mod", icon = Icons.Outlined.AutoAwesome, iconTile = colors.purple, onClick = onBuild)
        }
    }
    item(key = "drafts") {
        ListSection(
            header = "Drafts",
            footer = if (drafts.isEmpty()) "Drafts you build appear here." else "Tap a draft to keep working on it with its agent. Install checks it and shows what it asks for first. Once installed, each finished run in the draft reloads the mod, so its page shows the latest version; new permissions wait for Install.",
        ) {
            if (drafts.isEmpty()) {
                ListRow("No drafts yet", subtitle = "Build a new mod to start one.")
            }
            drafts.forEach { draft ->
                ListRow(
                    title = draft.modName,
                    subtitle = listOf(draft.version.ifBlank { "No version" }, draft.project.name).joinToString(" · "),
                    subtitleMaxLines = 1,
                    leading = { MonogramTile(monogram(draft.modName), modColor(draft.modId.ifBlank { draft.project.id }, PocketColors.current), tinted = true) },
                    onClick = { onOpenDraft(draft) },
                    trailing = {
                        PocketButton(
                            "Install",
                            { viewModel.reviewDraft(draft) },
                            style = PocketButtonStyle.Tinted,
                            size = PocketButtonSize.Small,
                            enabled = !busy,
                        )
                    },
                )
            }
        }
    }
    item(key = "share") {
        ListSection(header = "Share", footer = "Export an installed mod from its page, under About.") {
            ListRow("Import a .mhmod file", icon = Icons.Outlined.FileDownload, iconTile = PocketColors.current.blue, onClick = onImport)
        }
    }
}

@Composable
private fun NewModSheet(
    agents: List<AgentKind>,
    currentAgent: AgentKind,
    onCreate: (name: String, idea: String, agent: AgentKind) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var idea by rememberSaveable { mutableStateOf("") }
    var agent by rememberSaveable { mutableStateOf(if (currentAgent in agents) currentAgent else agents.firstOrNull()) }
    val chosen = agent
    val ready = name.isNotBlank() && idea.isNotBlank() && chosen != null
    GlassSheet(
        onDismiss = onDismiss,
        title = "New mod",
        detents = listOf(SheetDetent.Large),
        leading = { SheetTextButton("Cancel", onDismiss) },
        trailing = { SheetTextButton("Create", { if (chosen != null) onCreate(name.trim(), idea.trim(), chosen) }, emphasized = true, enabled = ready) },
    ) {
        Column(
            Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.lg),
        ) {
            PocketTextField(value = name, onValueChange = { name = it.take(60) }, label = "Name", placeholder = "Build notes")
            PocketTextField(
                value = idea,
                onValueChange = { idea = it.take(2000) },
                label = "What should it do?",
                placeholder = "Show a toast when a task finishes and add a /recap command.",
                singleLine = false,
                minLines = 3,
            )
            if (chosen == null) {
                Text("Install an agent in the Agent tab first.", style = PocketType.footnote, color = PocketColors.current.red)
            } else {
                Text("Agent", style = PocketType.footnote.emphasized, color = PocketColors.current.secondaryLabel)
                if (agents.size > 1) {
                    SegmentedControl(agents, chosen, { agent = it }, label = { it.shortTitle() })
                } else {
                    Text(chosen.title, style = PocketType.body, color = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    "A draft project opens with this agent and your idea in the message box. Send it to start; " +
                        "commands, rules, skills and automatic actions are what it can build.",
                    style = PocketType.footnote,
                    color = PocketColors.current.secondaryLabel,
                )
            }
        }
    }
}

private enum class ModPageTab(val label: String) { DASHBOARD("Dashboard"), CONTROLS("Controls"), PERMISSIONS("Permissions"), ACTIVITY("Activity"), ABOUT("About") }

@Composable
private fun ModPage(
    mod: InstalledMod,
    snapshot: ModsSnapshot,
    runtime: ModRuntimeInfo?,
    surfaces: ModSurfaces?,
    bottomBarPadding: Dp,
    onBack: () -> Unit,
    viewModel: ModsViewModel,
) {
    val listState = rememberLazyListState()
    val collapse by rememberTitleCollapse(listState)
    val hasDashboard = mod.manifest.hooksModule != null && (mod.manifest.dashboard != null || !surfaces?.panes.isNullOrEmpty())
    val tabs = ModPageTab.entries.filter { it != ModPageTab.DASHBOARD || hasDashboard }
    var tab by rememberSaveable(mod.id) { mutableStateOf(if (mod.manifest.dashboard != null) ModPageTab.DASHBOARD else ModPageTab.CONTROLS) }
    if (tab !in tabs) tab = ModPageTab.CONTROLS
    val panes = surfaces?.panes.orEmpty()
    var paneId by rememberSaveable(mod.id) { mutableStateOf<String?>(null) }
    val pane = panes.firstOrNull { it.id == paneId } ?: panes.firstOrNull()
    val shownSurface = pane?.surface?.takeIf { tab == ModPageTab.DASHBOARD && snapshot.status(mod) == ModStatus.ON }
    DisposableEffect(mod.id, shownSurface) {
        shownSurface?.let { viewModel.showSurface(mod.id, it, true) }
        onDispose { shownSurface?.let { viewModel.showSurface(mod.id, it, false) } }
    }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember { mutableStateOf<ModSetting?>(null) }
    // One live web panel at a time (mod plan row 12): opening another replaces this one.
    var panelId by rememberSaveable(mod.id) { mutableStateOf<String?>(null) }
    var confirmHigh by remember { mutableStateOf<ModCapability?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var audit by remember(mod.id) { mutableStateOf<List<ModAuditEntry>>(emptyList()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) viewModel.export(mod.id, uri)
    }
    LaunchedEffect(mod, tab) { if (tab == ModPageTab.ACTIVITY) audit = viewModel.audit(mod.id) }
    val logVersion by viewModel.logVersion.collectAsStateWithLifecycle()
    var log by remember(mod.id) { mutableStateOf<List<ModLogEntry>>(emptyList()) }
    LaunchedEffect(mod.id, tab, logVersion) { if (tab == ModPageTab.ACTIVITY && mod.manifest.hooksModule != null) log = viewModel.modLog(mod.id) }
    val shownRuntime = runtime?.takeIf { snapshot.status(mod) == ModStatus.ON }
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
                LargeTitle(mod.manifest.name, subtitle = "${mod.manifest.version} · ${shownRuntime?.state?.label ?: snapshot.status(mod).label}")
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
                ModPageTab.DASHBOARD -> dashboardTab(mod, shownRuntime, surfaces, pane?.id, onSelectPane = { paneId = it }, viewModel)
                ModPageTab.CONTROLS -> controlsTab(mod, shownRuntime, viewModel, onPreview = showText, onEdit = { editing = it }, onOpenPanel = { panelId = it.id })
                ModPageTab.PERMISSIONS -> permissionsTab(mod, viewModel, onConfirmHigh = { confirmHigh = it })
                ModPageTab.ACTIVITY -> activityTab(mod, audit, log, viewModel)
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

    mod.manifest.webPanels.firstOrNull { it.id == panelId }
        ?.takeIf { mod.enabled && ModCapability.UI_WEBPANE in mod.granted }
        ?.let { panel ->
            val fromMod = remember(mod.id, panel.id) { viewModel.panelMessages(mod.id, panel.id) }
            GlassSheet(
                onDismiss = { panelId = null },
                title = panel.title,
                detents = listOf(SheetDetent.Large),
                trailing = { SheetTextButton("Done", { panelId = null }, emphasized = true) },
            ) {
                ModWebPanelView(
                    mod = mod,
                    panel = panel,
                    fromMod = fromMod,
                    toMod = { viewModel.panelMessage(mod.id, panel.id, it) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }

    editing?.let { setting ->
        val secret = setting.type == ModSettingType.SECRET
        var value by remember(setting.key) { mutableStateOf(if (secret) "" else mod.settingValue(setting.key)?.let(::formatSetting).orEmpty()) }
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
                    // A secret is kept encrypted for the mod's code ($.secrets.get), never in its settings.
                    if (secret) viewModel.setSecret(mod.id, setting.key, value.trim().ifEmpty { null })
                    else parsed?.let { viewModel.setSetting(mod.id, setting.key, it) }
                    editing = null
                }, emphasized = true, enabled = valid)
            },
        ) {
            Column(Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
                PocketTextField(
                    value = value,
                    onValueChange = { value = it },
                    helper = if (secret) "Saved encrypted on this device. Leave empty and save to remove it." else setting.description,
                    secure = secret,
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
                permission?.hosts?.takeIf { it.isNotEmpty() }?.let { "Only these sites: ${it.joinToString()}" },
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

private fun LazyListScope.dashboardTab(
    mod: InstalledMod,
    runtime: ModRuntimeInfo?,
    surfaces: ModSurfaces?,
    paneId: String?,
    onSelectPane: (String) -> Unit,
    viewModel: ModsViewModel,
) {
    val panes = surfaces?.panes.orEmpty()
    val pane = panes.firstOrNull { it.id == paneId }
    if (panes.size > 1) {
        item(key = "panes") {
            Column(Modifier.padding(horizontal = ListInset)) {
                if (panes.size <= 4) {
                    SegmentedControl(panes.map { it.id }, pane?.id ?: panes.first().id, onSelectPane, label = { id -> panes.first { it.id == id }.title })
                } else {
                    androidx.compose.foundation.layout.Row(
                        Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                    ) {
                        panes.forEach { p ->
                            PocketButton(p.title, { onSelectPane(p.id) }, style = if (p.id == pane?.id) PocketButtonStyle.Filled else PocketButtonStyle.Gray, size = PocketButtonSize.Small)
                        }
                    }
                }
            }
        }
    }
    if (runtime?.state != ModRunState.RUNNING || pane == null || surfaces == null) {
        item(key = "dashboard-off") {
            ListSection(footer = "The dashboard shows while the mod's code runs. Controls says why it isn't running.") {
                ListRow(
                    title = pane?.title ?: "Dashboard",
                    subtitle = runtime?.message ?: if (mod.enabled) "Starting…" else "This mod is off.",
                    value = runtime?.state?.label ?: "Off",
                    subtitleMaxLines = 4,
                )
            }
        }
    } else {
        modPaneItems(
            keyPrefix = "pane-${pane.id}",
            drawing = surfaces.drawings[pane.surface],
            dir = surfaces.dir,
            onEvent = { handler, value -> viewModel.press(mod.id, pane.surface, handler, value) },
            itemModifier = Modifier.padding(horizontal = ListInset),
        )
    }
    val actions = mod.manifest.dashboard?.actions.orEmpty()
    if (actions.isNotEmpty() && runtime?.state == ModRunState.RUNNING) {
        item(key = "actions") {
            ListSection(header = "Actions") {
                actions.forEach { name ->
                    val command = runtime.commands.firstOrNull { it.name == name }
                    ListRow(
                        title = "/$name",
                        subtitle = command?.description?.ifBlank { null },
                        enabled = command != null,
                        onClick = { viewModel.runAction(mod.id, name) },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.controlsTab(
    mod: InstalledMod,
    runtime: ModRuntimeInfo?,
    viewModel: ModsViewModel,
    onPreview: (String, String) -> Unit,
    onEdit: (ModSetting) -> Unit,
    onOpenPanel: (ModWebPanel) -> Unit,
) {
    val manifest = mod.manifest
    item(key = "switch") {
        ListSection {
            ToggleRow(title = "On", checked = mod.enabled, onCheckedChange = { viewModel.setEnabled(mod.id, it) })
        }
    }
    if (manifest.webPanels.isNotEmpty()) {
        item(key = "panels") {
            val allowed = mod.enabled && ModCapability.UI_WEBPANE in mod.granted
            ListSection(
                header = "Panels",
                footer = if (allowed) "Web pages from the mod's own files. They can't reach the internet or your files." else
                    "Turn the mod on and allow “${ModCapability.UI_WEBPANE.summary}” to open its panels.",
            ) {
                manifest.webPanels.forEach { panel ->
                    ListRow(title = panel.title, subtitle = panel.entry, onClick = if (allowed) ({ onOpenPanel(panel) }) else null)
                }
            }
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
            val colors = PocketColors.current
            val declared = manifest.slashCommands.map { it.name }.toSet()
            ListSection(
                header = "Mod code",
                footer = "Runs in a locked-down sandbox with no access to your files or the internet, and only the permissions " +
                    "you allow. It runs while a project is open or Mods is showing. Five problems in 10 minutes stop it.",
            ) {
                ListRow(
                    title = manifest.hooksModule,
                    value = runtime?.state?.label ?: "Off",
                    titleColor = if (runtime?.state == ModRunState.CRASHED) colors.orange else null,
                )
                runtime?.message?.let { message ->
                    ListRow(
                        title = if (runtime.state == ModRunState.CRASHED) "Why it stopped" else "Note",
                        subtitle = message,
                        subtitleMaxLines = 6,
                        subtitleColor = if (runtime.state == ModRunState.CRASHED) colors.orange else null,
                    )
                }
                if (runtime?.state == ModRunState.CRASHED) {
                    ListRow("Restart", onClick = { viewModel.restartMod(mod.id) })
                }
                runtime?.status?.let { ListRow("Status", subtitle = it) }
                runtime?.takeIf { it.strikes > 0 && it.state != ModRunState.CRASHED }?.let {
                    ListRow("Problems in the last 10 minutes", value = it.strikes.toString())
                }
                runtime?.commands?.filter { it.name !in declared }?.forEach { command ->
                    ListRow(title = "/${command.name}", subtitle = command.description.ifBlank { null }, value = command.argumentHint)
                }
            }
        }
        item(key = "places") {
            ListSection(header = "In projects", footer = "Where this mod also shows while a project is open. Each is off until you turn it on.") {
                ModPlace.entries.forEach { place ->
                    val requested = place.capability in manifest.requested
                    val granted = place.capability in mod.granted
                    ToggleRow(
                        title = place.label,
                        subtitle = when {
                            !requested -> "This mod doesn't ask to show here."
                            !granted -> "Turn on “${place.capability.summary}” in Permissions first."
                            else -> null
                        },
                        checked = place in mod.shownIn && requested,
                        enabled = requested,
                        onCheckedChange = { viewModel.setShownIn(mod.id, place, it) },
                    )
                }
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
            subtitle = setting.description,
            value = if (viewModel.hasSecret(mod.id, setting.key)) "Saved" else "Not set",
            accessory = ListRowAccessory.Chevron,
            onClick = { onEdit(setting) },
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
                "Only mods you allow to approve tools, with Developer mode on, can approve agent tool requests. " +
                    "Turning a permission off stops the actions that need it.",
        ) {
            mod.manifest.permissions.forEach { permission ->
                val capability = permission.capability
                val details = listOfNotNull(
                    "${capability.id} · ${riskLabel(capability.risk)}" + if (capability.developer) " · developer" else "",
                    permission.reason,
                    permission.paths.takeIf { it.isNotEmpty() }?.let { "Only ${it.joinToString()}" }, permission.hosts.takeIf { it.isNotEmpty() }?.let { "Sites: ${it.joinToString()}" },
                ).joinToString("\n")
                val granted = capability in mod.granted
                ListRow(
                    title = capability.summary,
                    subtitle = details,
                    subtitleMaxLines = 4,
                    value = when {
                        !capability.usable -> "Not in this version"
                        capability.developer && !mod.developerMode -> "Needs Developer mode"
                        else -> null
                    },
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

private fun LazyListScope.activityTab(mod: InstalledMod, audit: List<ModAuditEntry>, log: List<ModLogEntry>, viewModel: ModsViewModel) {
    if (mod.manifest.hooksModule != null) {
        item(key = "modlog") {
            val colors = PocketColors.current
            ListSection(header = "Mod log", footer = if (log.isEmpty()) "Nothing yet. The log is kept until the app closes." else "The newest 50 lines, newest first.") {
                log.takeLast(50).asReversed().forEach { entry ->
                    ListRow(
                        title = entry.text.lineSequence().first().take(200),
                        subtitle = entry.text.lines().drop(1).joinToString("\n").take(600).ifBlank { null },
                        subtitleMaxLines = 4,
                        titleColor = when (entry.level) { "error" -> colors.red; "warn" -> colors.orange; else -> null },
                        value = formatTime(entry.time),
                    )
                }
            }
        }
    }
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
                        val needsDeveloper = capability.developer && !review.developerMode
                        val blocked = capability.risk == RiskLevel.HIGH && review.trust == ModTrust.BUILT || needsDeveloper
                        ListRow(
                            title = capability.summary,
                            subtitle = listOfNotNull(
                                "${capability.id} · ${riskLabel(capability.risk)}",
                                permission.reason,
                                permission.paths.takeIf { it.isNotEmpty() }?.let { "Only ${it.joinToString()}" }, permission.hosts.takeIf { it.isNotEmpty() }?.let { "Sites: ${it.joinToString()}" },
                                if (needsDeveloper) "Turn on Developer mode in Mods first." else if (blocked) "Turn on “Trust this mod” on its page first." else null,
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

internal fun eventLabel(event: ModEvent): String = event.label

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
