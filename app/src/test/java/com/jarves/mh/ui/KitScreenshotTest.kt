package com.jarves.mh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.BannerHost
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.BannerState
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.FloatingTabBar
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuDivider
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.OverlayHost
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.TabBarAccessory
import com.jarves.mh.ui.kit.TabItem
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberOverlayAnchor
import com.jarves.mh.ui.kit.GlassToolbarButton
import com.jarves.mh.ui.kit.GlassToolbarGroup
import com.jarves.mh.ui.kit.GlassToolbarItem
import com.jarves.mh.ui.kit.LargeTitle
import com.jarves.mh.ui.kit.LargeTitleScaffold
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SearchField
import com.jarves.mh.ui.kit.ToggleRow
import com.jarves.mh.ui.kit.rememberTitleCollapse
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketTheme
import com.jarves.mh.ui.theme.glass.LiquidGlassConfig
import com.jarves.mh.ui.theme.glass.LiquidGlassHost
import com.jarves.mh.ui.theme.glass.hostBackdropSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Opt-in renders of the component kit: ./gradlew :app:testOnlineDebugUnitTest -Pscreenshots
 * Light and dark, at font scale 1.0 and 1.3.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
class KitScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String, dark: Boolean, fontScale: Float, content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                PocketTheme(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT) {
                    LiquidGlassHost(
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        config = LiquidGlassConfig(),
                    ) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val scale = if (fontScale > 1f) "-large" else ""
        captureScreenRoboImage("${System.getProperty("roborazzi.output.dir")}/kit-$name${if (dark) "-dark" else "-light"}$scale.png")
    }

    @Composable
    private fun Catalog() {
        val listState = rememberLazyListState()
        val collapse by rememberTitleCollapse(listState)
        var query by remember { mutableStateOf("") }
        var notify by remember { mutableStateOf(true) }
        var reduce by remember { mutableStateOf(false) }
        LargeTitleScaffold(
            title = "Settings",
            collapse = { collapse },
            trailing = {
                GlassToolbarGroup {
                    GlassToolbarItem(Icons.Outlined.Add, "Add", {})
                    GlassToolbarItem(Icons.Outlined.MoreHoriz, "More", {})
                }
            },
            leading = { GlassToolbarButton(Icons.Outlined.Search, "Search", {}) },
        ) { padding ->
            val colors = PocketColors.current
            LazyColumn(
                state = listState,
                contentPadding = padding,
                modifier = Modifier.fillMaxSize().hostBackdropSource(),
            ) {
                item { LargeTitle("Settings") }
                item { Box(Modifier.padding(horizontal = 16.dp)) { SearchField(query, { query = it }) } }
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    ListSection(header = "Appearance", footer = "Glass turns into solid surfaces with stronger borders.") {
                        ListRow("Theme", icon = Icons.Outlined.DarkMode, iconTile = colors.indigo, value = "System", accessory = ListRowAccessory.Chevron, onClick = {})
                        ToggleRow("Reduce transparency", reduce, { reduce = it }, icon = Icons.Outlined.Storage, iconTile = colors.gray)
                        ToggleRow("Notifications", notify, { notify = it }, icon = Icons.Outlined.Notifications, iconTile = colors.red)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
                item {
                    ListSection(header = "Recent") {
                        ListRow("weather-app", subtitle = "Kotlin · 5 min ago", icon = Icons.Outlined.Folder, iconTile = colors.blue, accessory = ListRowAccessory.Chevron, onClick = {})
                        ListRow("landing-page", subtitle = "TypeScript · 3 h ago", icon = Icons.Outlined.Folder, iconTile = colors.orange, accessory = ListRowAccessory.Chevron, onClick = {})
                        ListRow("Delete project", destructive = true, onClick = {})
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PocketButton("Continue", {}, style = PocketButtonStyle.Filled, size = PocketButtonSize.Large, fullWidth = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PocketButton("Tinted", {}, style = PocketButtonStyle.Tinted)
                            PocketButton("Gray", {}, style = PocketButtonStyle.Gray)
                            PocketButton("Plain", {}, style = PocketButtonStyle.Plain)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PocketButton("Glass", {}, style = PocketButtonStyle.Glass)
                            PocketButton("Prominent", {}, style = PocketButtonStyle.GlassProminent)
                            PocketButton("Delete", {}, style = PocketButtonStyle.Tinted, destructive = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProgressRing(0.62f)
                            ProgressRing(null)
                        }
                    }
                }
                item { EmptyState(Icons.Outlined.Folder, "No projects yet", Modifier.fillMaxWidth(), "Create one to start working with an agent.", "New project", {}) }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    @Composable
    private fun WithOverlay(kind: String) {
        val banner = remember { BannerState() }
        BannerHost(banner, Modifier.fillMaxSize()) {
            OverlayHost(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    Catalog()
                    val anchor = rememberOverlayAnchor()
                    var tab by remember { mutableStateOf(0) }
                    Box(
                        Modifier
                            .align(androidx.compose.ui.Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        FloatingTabBar(
                            tabs = listOf(
                                TabItem("Projects", Icons.Outlined.Folder),
                                TabItem("Agent", Icons.Outlined.Storage),
                                TabItem("Settings", Icons.Outlined.DarkMode),
                            ),
                            selectedIndex = tab,
                            onSelect = { tab = it },
                            minimized = kind == "minimized",
                            accessory = {
                                TabBarAccessory(Icons.Outlined.Search, "Terminal", {}, Modifier.overlayAnchor(anchor))
                            },
                        )
                    }
                    when (kind) {
                        "sheet" -> GlassSheet(onDismiss = {}, title = "New project", trailing = {
                            PocketButton("Create", {}, style = PocketButtonStyle.Plain)
                        }) {
                            ListSection(header = "Name", footer = "Letters, numbers and dashes.") {
                                ListRow("weather-app", onClick = {})
                            }
                            Spacer(Modifier.height(16.dp))
                            ListSection(header = "Start from") {
                                ListRow("Empty project", accessory = ListRowAccessory.Check, onClick = {})
                                ListRow("Clone a Git repository", accessory = ListRowAccessory.Chevron, onClick = {})
                                ListRow("Import a ZIP file", accessory = ListRowAccessory.Chevron, onClick = {})
                            }
                        }
                        "menu" -> GlassMenu(expanded = true, onDismiss = {}, anchor = anchor) {
                            GlassMenuItem("Rename", {}, icon = Icons.Outlined.Notifications)
                            GlassMenuItem("Show in Files", {}, icon = Icons.Outlined.Folder)
                            GlassMenuDivider()
                            GlassMenuItem("Delete", {}, destructive = true)
                        }
                    }
                    androidx.compose.runtime.LaunchedEffect(kind) {
                        if (kind == "banner") banner.show("Repository cloned successfully", BannerKind.Success)
                    }
                }
            }
        }
    }

    @Test fun sheetLight() = shot("sheet", false, 1f) { WithOverlay("sheet") }
    @Test fun sheetDark() = shot("sheet", true, 1f) { WithOverlay("sheet") }
    @Test fun menuLight() = shot("menu", false, 1f) { WithOverlay("menu") }
    @Test fun menuDark() = shot("menu", true, 1f) { WithOverlay("menu") }
    @Test fun bannerLight() = shot("banner", false, 1f) { WithOverlay("banner") }
    @Test fun bannerDark() = shot("banner", true, 1.3f) { WithOverlay("banner") }
    @Test fun tabBarMinimizedLight() = shot("tabbar-min", false, 1f) { WithOverlay("minimized") }
    @Test fun alertLight() = shot("alert", false, 1f) {
        Catalog()
        PocketAlert(
            onDismiss = {},
            title = "Delete “weather-app”?",
            message = "Its chats, files and terminal history are removed from this phone.",
            actions = listOf(AlertAction("Cancel", AlertRole.Cancel) {}, AlertAction("Delete", AlertRole.Destructive) {}),
        )
    }
    @Test fun alertDark() = shot("alert", true, 1f) {
        Catalog()
        PocketAlert(
            onDismiss = {},
            title = "Rename project",
            actions = listOf(AlertAction("Cancel", AlertRole.Cancel) {}, AlertAction("Save") {}),
        ) {
            PocketTextField("weather-app", {}, placeholder = "Project name")
        }
    }

    @Test fun catalogLight() = shot("catalog", false, 1f) { Catalog() }
    @Test fun catalogDark() = shot("catalog", true, 1f) { Catalog() }
    @Test fun catalogLightLarge() = shot("catalog", false, 1.3f) { Catalog() }
    @Test fun catalogDarkLarge() = shot("catalog", true, 1.3f) { Catalog() }
}
