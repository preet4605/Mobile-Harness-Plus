package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.projectSlug
import com.jarves.mh.network.GitHubRepository
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuDivider
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.GlassToolbarGroup
import com.jarves.mh.ui.kit.GlassToolbarItem
import com.jarves.mh.ui.kit.LargeTitle
import com.jarves.mh.ui.kit.LargeTitleScaffold
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.MonogramTile
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SearchField
import com.jarves.mh.ui.kit.SheetDetent
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.SymbolTile
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberHaptics
import com.jarves.mh.ui.kit.rememberOverlayAnchor
import com.jarves.mh.ui.kit.rememberTitleCollapse
import com.jarves.mh.ui.theme.PocketColorRoles
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import com.jarves.mh.ui.theme.sharedTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip

/** How long a form sheet waits before raising the keyboard. */
internal const val SheetFocusDelayMillis = 320L

/** Which sheet the Projects screen shows. */
private enum class ProjectsSheet { None, Create, Clone, GitHub, Update }

/** "Claude Code · sonnet-4" for the screen subtitle, plus running subagents. */
internal fun engineSummary(state: AppUiState): String {
    val model = if (state.agentKind == AgentKind.ANTIGRAVITY) state.antigravityModel else state.provider.model
    val live = state.subagents.count { !it.state.isTerminal }
    return listOfNotNull(
        state.agentKind.title,
        model.trim().takeIf { it.isNotEmpty() },
        if (live > 0) "$live subagent${if (live == 1) "" else "s"} running" else null,
    ).joinToString(" · ")
}

/** A stable tile colour per project, so a project keeps its colour everywhere. */
/** A project's language as a short monogram and colour ("Kt" purple), or null when there is none. */
internal fun projectBadge(language: String, colors: PocketColorRoles): Pair<String, Color>? {
    val name = language.trim()
    return when (name.lowercase()) {
        "", "general", "workspace" -> null
        "kotlin" -> "Kt" to colors.purple
        "java" -> "J" to colors.red
        "typescript" -> "TS" to colors.blue
        "javascript" -> "JS" to colors.yellow
        "python" -> "Py" to colors.green
        "rust" -> "Rs" to colors.brown
        "go" -> "Go" to colors.cyan
        else -> name.take(2).replaceFirstChar { it.uppercase() } to colors.gray
    }
}

/**
 * Projects: a large title over the project list, a glass toolbar (+ new project, ⋯ more), search,
 * Recent projects, and ways to start something. Creating, cloning, GitHub and updates are sheets;
 * rename and delete are on a long press (and as accessibility actions).
 */
@Composable
internal fun ProjectsScreen(
    state: AppUiState,
    listState: LazyListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() },
    bottomBarPadding: Dp = 0.dp,
    onOpen: (Project) -> Unit,
    onCreate: (String) -> Unit,
    onCreateQuickProject: () -> Unit,
    onImportZip: (Uri) -> Unit,
    onCloneGit: (String) -> Unit,
    onStartGitHubLogin: () -> Unit,
    onGenerateNewGitHubCode: () -> Unit,
    onRefreshGitHub: () -> Unit,
    onDisconnectGitHub: () -> Unit,
    onCloneGitHub: (GitHubRepository) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onSettings: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPing: () -> Unit,
    onToggleTheme: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val colors = PocketColors.current
    var sheet by rememberSaveable { mutableStateOf(ProjectsSheet.None) }
    var menuOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val createAnchor = rememberOverlayAnchor()
    val moreAnchor = rememberOverlayAnchor()
    val importZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) onImportZip(uri)
    }
    val importBusy = state.projectImporting || state.gitCloneRunning
    val openGitHub = {
        sheet = ProjectsSheet.GitHub
        if (state.githubAuthStatus == GitHubAuthStatus.CONNECTED && state.githubRepositories.isEmpty()) onRefreshGitHub()
    }
    // A new update opens its sheet once, as the update dialog did.
    LaunchedEffect(state.appUpdate?.versionCode) {
        if (state.appUpdate != null) sheet = ProjectsSheet.Update
    }
    val collapse by rememberTitleCollapse(listState)
    val shown = remember(state.projects, query) {
        if (query.isBlank()) state.projects else state.projects.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }

    LargeTitleScaffold(
        title = "Projects",
        collapse = { collapse },
        trailing = {
            GlassToolbarGroup {
                GlassToolbarItem(Icons.Outlined.Add, "New project", { sheet = ProjectsSheet.Create }, Modifier.overlayAnchor(createAnchor))
                GlassToolbarItem(Icons.Outlined.MoreHoriz, "More", { menuOpen = true }, Modifier.overlayAnchor(moreAnchor))
            }
        },
    ) { padding ->
        // Runs edge to edge under the glass bar and dock; it is the shared backdrop source.
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().hostBackdropSource(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottomBarPadding + PocketSpacing.lg),
        ) {
            item(key = "title") { LargeTitle("Projects", subtitle = engineSummary(state)) }
            if (state.projects.isNotEmpty()) {
                item(key = "search") {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search projects",
                        modifier = Modifier.padding(horizontal = ListInset).padding(bottom = PocketSpacing.sm),
                    )
                }
            }
            state.appUpdate?.let { update ->
                item(key = "update") {
                    ListSection(Modifier.padding(top = PocketSpacing.sm)) {
                        ListRow(
                            title = "Mobile Harness ${update.versionName}",
                            subtitle = "Update available",
                            icon = Icons.Outlined.Download,
                            iconTile = MaterialTheme.colorScheme.primary,
                            accessory = ListRowAccessory.Chevron,
                            onClick = { sheet = ProjectsSheet.Update },
                        )
                    }
                }
            }
            if (importBusy) {
                item(key = "importing") {
                    ListSection(Modifier.padding(top = PocketSpacing.sm)) {
                        ListRow(
                            title = if (state.gitCloneRunning) "Cloning repository" else "Importing project",
                            subtitle = state.gitCloneMessage ?: state.projectImportMessage,
                            leading = { ProgressRing(progress = null, size = 22.dp, strokeWidth = 2.5.dp) },
                        )
                    }
                }
            }
            if (state.projects.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Outlined.Folder,
                        title = "No projects yet",
                        message = "Create a project, or start a quick chat to work with an agent right away.",
                        actionLabel = "New project",
                        onAction = { sheet = ProjectsSheet.Create },
                        modifier = Modifier.fillMaxWidth().padding(vertical = PocketSpacing.xl),
                    )
                }
            } else {
                item(key = "recent") {
                    ListSection(
                        modifier = Modifier.padding(top = PocketSpacing.md),
                        header = if (query.isBlank()) "Recent" else "Results",
                        footer = if (shown.isEmpty()) "No projects match “${query.trim()}”." else null,
                    ) {
                        shown.forEach { project ->
                            ProjectRow(
                                project = project,
                                taskRunning = state.isRunning && state.activeProject?.id == project.id,
                                terminalRunning = state.projectTerminalRunning && state.activeProject?.id == project.id,
                                onOpen = { onOpen(project) },
                                onRename = { onRenameProject(project.id, it) },
                                onDelete = { onDeleteProject(project.id) },
                            )
                        }
                    }
                }
            }
            item(key = "start") {
                ListSection(
                    modifier = Modifier.padding(top = PocketSpacing.xxl),
                    header = "Start something",
                ) {
                    ListRow(
                        title = "Quick chat",
                        subtitle = "A scratch workspace for questions and experiments",
                        icon = Icons.Outlined.AutoAwesome,
                        iconTile = colors.orange,
                        onClick = onCreateQuickProject,
                    )
                    ListRow(
                        title = "Import a ZIP file",
                        icon = Icons.Outlined.FolderZip,
                        iconTile = colors.blue,
                        enabled = !importBusy,
                        onClick = { importZipLauncher.launch("*/*") },
                    )
                    ListRow(
                        title = "Clone a Git repository",
                        icon = Icons.Outlined.Link,
                        iconTile = colors.gray,
                        enabled = !importBusy,
                        accessory = ListRowAccessory.Chevron,
                        onClick = { sheet = ProjectsSheet.Clone },
                    )
                    ListRow(
                        title = "GitHub",
                        icon = Icons.Outlined.Hub,
                        iconTile = colors.indigo,
                        value = state.githubLogin?.let { "@$it" } ?: "Connect",
                        enabled = !state.gitCloneRunning,
                        accessory = ListRowAccessory.Chevron,
                        onClick = openGitHub,
                    )
                }
            }
        }
    }

    GlassMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, anchor = moreAnchor) {
        GlassMenuItem("Quick chat", onCreateQuickProject, icon = Icons.Outlined.AutoAwesome)
        GlassMenuItem("Import ZIP file", { importZipLauncher.launch("*/*") }, icon = Icons.Outlined.FolderZip, enabled = !importBusy)
        GlassMenuItem("Clone repository", { sheet = ProjectsSheet.Clone }, icon = Icons.Outlined.Link, enabled = !importBusy)
        GlassMenuItem("GitHub", openGitHub, icon = Icons.Outlined.Hub, enabled = !state.gitCloneRunning)
        GlassMenuDivider()
        GlassMenuItem(
            if (colors.isDark) "Light appearance" else "Dark appearance",
            onToggleTheme,
            icon = if (colors.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
        )
        GlassMenuItem("Settings", onSettings, icon = Icons.Outlined.Settings)
    }

    CreateProjectSheet(
        visible = sheet == ProjectsSheet.Create,
        anchor = createAnchor,
        onDismiss = { sheet = ProjectsSheet.None },
        onCreate = { name ->
            onCreate(name)
            sheet = ProjectsSheet.None
        },
    )
    CloneRepositorySheet(
        visible = sheet == ProjectsSheet.Clone,
        busy = state.gitCloneRunning,
        message = state.gitCloneMessage,
        onDismiss = { sheet = ProjectsSheet.None },
        onClone = { url ->
            onCloneGit(url)
            sheet = ProjectsSheet.None
        },
    )
    GitHubSheet(
        visible = sheet == ProjectsSheet.GitHub,
        state = state,
        onDismiss = { if (!state.gitCloneRunning) sheet = ProjectsSheet.None },
        onStartLogin = onStartGitHubLogin,
        onGenerateNewCode = onGenerateNewGitHubCode,
        onRefresh = onRefreshGitHub,
        onDisconnect = {
            onDisconnectGitHub()
            sheet = ProjectsSheet.None
        },
        onClone = { repository ->
            sheet = ProjectsSheet.None
            onCloneGitHub(repository)
        },
    )
    UpdateSheet(
        visible = sheet == ProjectsSheet.Update && state.appUpdate != null,
        state = state,
        onDismiss = { if (state.appUpdateStatus != AppUpdateStatus.INSTALLING) sheet = ProjectsSheet.None },
        onInstallUpdate = onInstallUpdate,
    )
}

/** One project in the Recent list. Long press (or the accessibility actions) for rename and delete. */
@Composable
private fun ProjectRow(
    project: Project,
    taskRunning: Boolean,
    terminalRunning: Boolean,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = PocketColors.current
    val haptics = rememberHaptics()
    val anchor = rememberOverlayAnchor()
    var menuOpen by remember { mutableStateOf(false) }
    var showRename by rememberSaveable(project.id) { mutableStateOf(false) }
    var showDelete by rememberSaveable(project.id) { mutableStateOf(false) }
    var renameText by rememberSaveable(project.id) { mutableStateOf(project.name) }
    val running = taskRunning || terminalRunning
    ListRow(
        title = project.name,
        titleModifier = Modifier.sharedTitle(projectTitleKey(project.id)),
        subtitle = when {
            taskRunning -> "Agent working…"
            terminalRunning -> "Terminal command running…"
            else -> listOf(project.language.ifBlank { "Workspace" }, project.formattedUpdatedAt).filter { it.isNotBlank() }.joinToString(" · ")
        },
        subtitleMaxLines = 1,
        leading = {
            val badge = projectBadge(project.language, colors)
            when {
                project.kind == ProjectKind.QUICK_PROJECT -> SymbolTile(Icons.Outlined.AutoAwesome, colors.orange)
                badge != null -> MonogramTile(badge.first, badge.second, tinted = true)
                else -> SymbolTile(Icons.Outlined.Folder, colors.gray)
            }
        },
        accessory = ListRowAccessory.Chevron,
        modifier = Modifier
            .overlayAnchor(anchor)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Rename") { renameText = project.name; showRename = true; true },
                    CustomAccessibilityAction("Delete") { showDelete = true; true },
                )
            },
        onLongClick = {
            haptics.longPress()
            menuOpen = true
        },
        onClick = onOpen,
        trailing = if (running) {
            { ProgressRing(progress = null, size = 18.dp, strokeWidth = 2.dp) }
        } else null,
    )
    GlassMenu(expanded = menuOpen, onDismiss = { menuOpen = false }, anchor = anchor) {
        GlassMenuItem("Open", onOpen, icon = Icons.Outlined.Folder)
        GlassMenuItem("Rename", { renameText = project.name; showRename = true }, icon = Icons.Outlined.Edit)
        GlassMenuDivider()
        GlassMenuItem("Delete", { showDelete = true }, icon = Icons.Outlined.Delete, destructive = true)
    }
    if (showRename) {
        PocketAlert(
            onDismiss = { showRename = false },
            title = "Rename project",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { showRename = false },
                AlertAction("Save", enabled = renameText.isNotBlank()) {
                    onRename(renameText)
                    showRename = false
                },
            ),
        ) {
            val focus = remember { FocusRequester() }
            PocketTextField(
                value = renameText,
                onValueChange = { renameText = it },
                placeholder = "Project name",
                focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (renameText.isNotBlank()) {
                        onRename(renameText)
                        showRename = false
                    }
                }),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        }
    }
    if (showDelete) {
        PocketAlert(
            onDismiss = { showDelete = false },
            title = "Delete “${project.name}”?",
            message = "Its chats, files, attachments, changes and terminal history are permanently removed.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { showDelete = false },
                AlertAction("Delete", AlertRole.Destructive) {
                    onDelete()
                    showDelete = false
                },
            ),
        )
    }
}

@Composable
private fun CreateProjectSheet(
    visible: Boolean,
    anchor: com.jarves.mh.ui.kit.OverlayAnchor,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val create = {
        if (name.isNotBlank()) {
            onCreate(name.trim())
            name = ""
        }
    }
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "New project",
        detents = listOf(SheetDetent.Fit),
        anchor = anchor,
        leading = { SheetTextButton("Cancel", onDismiss) },
        trailing = { SheetTextButton("Create", create, emphasized = true, enabled = name.isNotBlank()) },
    ) {
        val focus = remember { FocusRequester() }
        Column(Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
            PocketTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Project name",
                focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { create() }),
                helper = if (name.isNotBlank()) "Folder: /workspace/${projectSlug(name)}" else "A starter project the agent can build on.",
            )
        }
        LaunchedEffect(Unit) {
            // Let the sheet finish rising before the keyboard pushes it.
            kotlinx.coroutines.delay(SheetFocusDelayMillis)
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun CloneRepositorySheet(
    visible: Boolean,
    busy: Boolean,
    message: String?,
    onDismiss: () -> Unit,
    onClone: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    val clone = {
        if (url.isNotBlank() && !busy) {
            onClone(url.trim())
            url = ""
        }
    }
    GlassSheet(
        onDismiss = { if (!busy) onDismiss() },
        visible = visible,
        title = "Clone repository",
        detents = listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Cancel", onDismiss, enabled = !busy) },
        trailing = { SheetTextButton(if (busy) "Cloning…" else "Clone", clone, emphasized = true, enabled = url.isNotBlank() && !busy) },
    ) {
        val focus = remember { FocusRequester() }
        Column(Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl)) {
            PocketTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://github.com/owner/repository.git",
                focusRequester = focus,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { clone() }),
                helper = message ?: "A public HTTPS repository. Its full Git history and current branch are kept.",
            )
        }
        LaunchedEffect(Unit) {
            // Let the sheet finish rising before the keyboard pushes it.
            kotlinx.coroutines.delay(SheetFocusDelayMillis)
            runCatching { focus.requestFocus() }
        }
    }
}

@Composable
private fun GitHubSheet(
    visible: Boolean,
    state: AppUiState,
    onDismiss: () -> Unit,
    onStartLogin: () -> Unit,
    onGenerateNewCode: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onClone: (GitHubRepository) -> Unit,
) {
    val colors = PocketColors.current
    val connected = state.githubAuthStatus == GitHubAuthStatus.CONNECTED
    var search by rememberSaveable { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = state.githubLogin?.let { "@$it" } ?: "GitHub",
        detents = if (connected) listOf(SheetDetent.Medium, SheetDetent.Large) else listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Close", onDismiss, enabled = !state.gitCloneRunning) },
        trailing = if (connected) {
            {
                com.jarves.mh.ui.kit.PocketIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Refresh repositories",
                    onClick = onRefresh,
                    enabled = !state.githubRepositoriesLoading,
                )
            }
        } else null,
    ) {
        when (state.githubAuthStatus) {
            GitHubAuthStatus.DISCONNECTED, GitHubAuthStatus.ERROR -> Column(
                Modifier.fillMaxWidth().padding(horizontal = ListInset).padding(bottom = PocketSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    state.githubMessage ?: "Sign in with GitHub's official CLI to clone your public and private repositories.",
                    style = PocketType.subheadline,
                    color = if (state.githubAuthStatus == GitHubAuthStatus.ERROR) colors.red else colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(PocketSpacing.lg))
                PocketButton("Sign in with GitHub", onStartLogin, size = PocketButtonSize.Large, fullWidth = true)
            }
            GitHubAuthStatus.STARTING -> Column(
                Modifier.fillMaxWidth().padding(PocketSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ProgressRing(progress = null)
                Spacer(Modifier.height(PocketSpacing.md))
                Text(state.githubMessage ?: "Starting GitHub sign-in…", style = PocketType.subheadline, color = colors.secondaryLabel)
            }
            GitHubAuthStatus.AWAITING_USER -> {
                val clipboard = LocalClipboardManager.current
                val banner = com.jarves.mh.ui.kit.LocalBanner.current
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = ListInset).padding(bottom = PocketSpacing.xxl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Enter this code on the GitHub page that opened in your browser.",
                        style = PocketType.subheadline,
                        color = colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(PocketSpacing.lg))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(PocketShape.md)
                            .background(colors.tertiaryFill)
                            .clickable {
                                state.githubUserCode?.let {
                                    clipboard.setText(AnnotatedString(it))
                                    banner?.show("Code copied", com.jarves.mh.ui.kit.BannerKind.Success)
                                }
                            }
                            .padding(vertical = PocketSpacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(state.githubUserCode.orEmpty(), style = PocketType.title1.copy(fontFamily = PocketType.code.fontFamily), color = MaterialTheme.colorScheme.onSurface)
                    }
                    Spacer(Modifier.height(PocketSpacing.sm))
                    Text("Tap the code to copy it. GitHub connects by itself once you approve.", style = PocketType.footnote, color = colors.secondaryLabel, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(PocketSpacing.lg))
                    PocketButton("Get a new code", onGenerateNewCode, style = PocketButtonStyle.Gray, icon = Icons.Outlined.Refresh, fullWidth = true)
                }
            }
            GitHubAuthStatus.CONNECTED -> {
                val filtered = state.githubRepositories.filter { search.isBlank() || it.fullName.contains(search, ignoreCase = true) }
                Column(Modifier.fillMaxSize()) {
                    SearchField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "Search repositories",
                        modifier = Modifier.padding(horizontal = ListInset).padding(bottom = PocketSpacing.sm),
                    )
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
                        if (state.githubRepositoriesLoading && filtered.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(PocketSpacing.xxl), contentAlignment = Alignment.Center) { ProgressRing(progress = null) }
                            }
                        }
                        item {
                            ListSection(footer = state.githubMessage) {
                                filtered.forEach { repository ->
                                    ListRow(
                                        title = repository.fullName,
                                        subtitle = "${if (repository.private) "Private" else "Public"} · ${repository.defaultBranch}",
                                        subtitleMaxLines = 1,
                                        icon = if (repository.private) Icons.Outlined.Lock else Icons.Outlined.Code,
                                        iconTile = if (repository.private) colors.gray else colors.indigo,
                                        enabled = !state.gitCloneRunning,
                                        onClick = { onClone(repository) },
                                    )
                                }
                            }
                        }
                        item {
                            ListSection(Modifier.padding(top = PocketSpacing.xxl)) {
                                ListRow("Disconnect GitHub", destructive = true, onClick = { confirmDisconnect = true })
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmDisconnect) {
        PocketAlert(
            onDismiss = { confirmDisconnect = false },
            title = "Disconnect GitHub?",
            message = "Projects you already cloned stay on this phone.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmDisconnect = false },
                AlertAction("Disconnect", AlertRole.Destructive) {
                    confirmDisconnect = false
                    onDisconnect()
                },
            ),
        )
    }
}

@Composable
private fun UpdateSheet(
    visible: Boolean,
    state: AppUiState,
    onDismiss: () -> Unit,
    onInstallUpdate: () -> Unit,
) {
    val update = state.appUpdate ?: return
    val context = LocalContext.current
    val colors = PocketColors.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onInstallUpdate()
    }
    val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    val downloading = state.appUpdateStatus == AppUpdateStatus.DOWNLOADING
    val installing = state.appUpdateStatus == AppUpdateStatus.INSTALLING
    val total = state.appUpdateTotalBytes
    val downloaded = state.appUpdateDownloadedBytes
    val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Software update",
        detents = listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Later", onDismiss, enabled = !installing) },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = ListInset).padding(bottom = PocketSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.md),
        ) {
            Text("Mobile Harness ${update.versionName}", style = PocketType.title3, color = MaterialTheme.colorScheme.onSurface)
            if (update.sizeBytes > 0) Text(formatMegabytes(update.sizeBytes), style = PocketType.subheadline, color = colors.secondaryLabel)
            Text(
                update.notes.ifBlank { "The latest improvements and fixes for Mobile Harness." },
                style = PocketType.body,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!canInstall) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Top) {
                    com.jarves.mh.ui.kit.SymbolTile(Icons.Outlined.Warning, colors.orange)
                    Spacer(Modifier.size(PocketSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text("Allow installs from Mobile Harness", style = PocketType.body.emphasized, color = MaterialTheme.colorScheme.onSurface)
                        Text("Android needs ‘Install unknown apps’ turned on to install the update.", style = PocketType.subheadline, color = colors.secondaryLabel)
                    }
                }
            }
            if (downloading) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(progress = progress, size = 22.dp, strokeWidth = 2.5.dp)
                    Spacer(Modifier.size(PocketSpacing.md))
                    Text(
                        if (total > 0) "${formatMegabytes(downloaded)} of ${formatMegabytes(total)}" else "Downloading ${formatMegabytes(downloaded)}",
                        style = PocketType.subheadline,
                        color = colors.secondaryLabel,
                    )
                }
            }
            if (installing) Text("Download verified. Opening the Android installer…", style = PocketType.subheadline, color = colors.green)
            state.appUpdateError?.let { Text(it, style = PocketType.subheadline, color = colors.red) }
            PocketButton(
                text = when {
                    !canInstall -> "Allow installs"
                    downloading -> "Downloading…"
                    installing -> "Installing…"
                    else -> "Download and install"
                },
                onClick = {
                    if (!canInstall && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } else {
                        onInstallUpdate()
                    }
                },
                enabled = !downloading && !installing,
                loading = downloading || installing,
                size = PocketButtonSize.Large,
                fullWidth = true,
            )
        }
    }
}
