package com.jarves.mh.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.jarves.mh.ui.kit.BannerHost
import com.jarves.mh.ui.kit.CappedTextScale
import com.jarves.mh.ui.kit.ChromeMaxFontScale
import com.jarves.mh.ui.kit.BannerKind
import com.jarves.mh.ui.kit.BannerState
import com.jarves.mh.ui.kit.FloatingTabBar
import com.jarves.mh.ui.kit.GlassMenu
import com.jarves.mh.ui.kit.GlassMenuItem
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.OverlayHost
import com.jarves.mh.ui.kit.TabBarAccessory
import com.jarves.mh.ui.kit.TabItem
import com.jarves.mh.ui.kit.TabBarMinimizeState
import com.jarves.mh.ui.kit.overlayAnchor
import com.jarves.mh.ui.kit.rememberOverlayAnchor
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketTheme
import com.jarves.mh.ui.theme.glass.LiquidGlassConfig
import com.jarves.mh.ui.theme.glass.LiquidGlassHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * In-window overlays keep the behaviour of the platform dialogs they replace: accessibility
 * reads the sheet as a pane and hides what is behind it, Back and the dismiss action close it,
 * menus close after a choice, banners are announced, and the tab bar minimizes on scroll.
 */
@RunWith(RobolectricTestRunner::class)
// Native graphics: hit testing inside continuous-corner (path) outlines needs real path ops.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OverlayKitTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun host(content: @Composable () -> Unit) {
        compose.setContent {
            PocketTheme(AppThemeMode.LIGHT) {
                LiquidGlassHost(Modifier.fillMaxSize(), config = LiquidGlassConfig()) {
                    OverlayHost(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }

    @Test
    fun sheet_isAPaneAndHidesTheScreenBehindIt() {
        var open by mutableStateOf(true)
        host {
            Box(Modifier.fillMaxSize()) { Text("Behind") }
            GlassSheet(onDismiss = { open = false }, visible = open, title = "Terminal") { Text("Inside") }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Inside").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Terminal")).assertExists()
        compose.onNodeWithText("Behind").assertDoesNotExist()

        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Terminal"))
            .performSemanticsAction(SemanticsActions.Dismiss)
        assertFalse(open)
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        compose.onNodeWithText("Inside").assertDoesNotExist()
        compose.onNodeWithText("Behind").assertExists()
    }

    @Test
    fun sheet_backClosesItFirst() {
        var open by mutableStateOf(true)
        host {
            GlassSheet(onDismiss = { open = false }, visible = open, title = "Changes") { Text("Inside") }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertFalse(open)
    }

    @Test
    fun menu_closesAfterAChoice() {
        var expanded by mutableStateOf(false)
        var renamed = 0
        host {
            val anchor = rememberOverlayAnchor()
            Box(Modifier.fillMaxSize()) {
                Text("Options", Modifier.overlayAnchor(anchor))
                GlassMenu(expanded = expanded, onDismiss = { expanded = false }, anchor = anchor) {
                    GlassMenuItem("Rename", onClick = { renamed++ })
                    GlassMenuItem("Delete", onClick = {}, destructive = true)
                }
            }
        }
        expanded = true
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Rename").performClick()
        assertEquals(1, renamed)
        assertFalse(expanded)
    }

    @Test
    fun menu_followsItsAnchorWhileOpen() {
        var anchorY by mutableStateOf((-24).dp)
        host {
            val anchor = rememberOverlayAnchor()
            Box(Modifier.fillMaxSize()) {
                Text("Options", Modifier.align(Alignment.BottomStart).offset(x = 24.dp, y = anchorY).overlayAnchor(anchor))
                GlassMenu(expanded = true, onDismiss = {}, anchor = anchor) {
                    GlassMenuItem("Attach files", onClick = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val before = compose.onNodeWithText("Attach files").fetchSemanticsNode().boundsInRoot
        val anchorBefore = compose.onNodeWithText("Options").fetchSemanticsNode().boundsInRoot

        compose.runOnIdle { anchorY = (-40).dp }
        compose.waitForIdle()
        val after = compose.onNodeWithText("Attach files").fetchSemanticsNode().boundsInRoot
        val anchorAfter = compose.onNodeWithText("Options").fetchSemanticsNode().boundsInRoot
        assertEquals("The open menu follows the moving composer", anchorAfter.top - anchorBefore.top, after.top - before.top, 1f)
    }

    @Test
    fun sheet_tapsOnThePanelDoNotDismiss() {
        var open by mutableStateOf(true)
        var tapped = 0
        host {
            GlassSheet(onDismiss = { open = false }, visible = open, title = "Changes") {
                Text("Inside")
                androidx.compose.material3.TextButton(onClick = { tapped++ }) { Text("Keep all") }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Inside").performClick()
        assertTrue("tapping sheet content must not dismiss", open)
        compose.onNodeWithText("Keep all").performClick()
        assertEquals(1, tapped)
        assertTrue(open)
    }

    @Test
    fun banner_isAnnouncedAndHidesItself() {
        val banner = BannerState()
        compose.setContent {
            PocketTheme(AppThemeMode.DARK) {
                LiquidGlassHost(Modifier.fillMaxSize(), config = LiquidGlassConfig()) {
                    BannerHost(banner, Modifier.fillMaxSize()) { Text("Screen") }
                }
            }
        }
        compose.runOnIdle { banner.show("Repository cloned successfully", BannerKind.Success) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Repository cloned successfully").assertExists()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
        compose.mainClock.advanceTimeBy(6_000)
        compose.waitForIdle()
        compose.onNodeWithText("Repository cloned successfully").assertDoesNotExist()
    }

    @Test
    fun tabBar_tabsAreSelectableAndAccessoryIsAButton() {
        var selected by mutableIntStateOf(0)
        var terminal = 0
        host {
            FloatingTabBar(
                tabs = listOf(TabItem("Projects", Icons.Outlined.Folder), TabItem("Settings", Icons.Outlined.Settings)),
                selectedIndex = selected,
                onSelect = { selected = it },
                accessory = { TabBarAccessory(Icons.Outlined.Terminal, "Terminal", { terminal++ }) },
            )
        }
        compose.onNodeWithText("Projects").assertExists()
        compose.onNodeWithText("Settings").performClick()
        assertEquals(1, selected)
        compose.onNodeWithContentDescription("Terminal").performClick()
        assertEquals(1, terminal)
    }

    @Test
    fun tabBar_minimizesOnScrollDownAndReturnsOnScrollUp() {
        val state = TabBarMinimizeState(threshold = 24f)
        val c = state.connection
        c.onPostScroll(Offset(0f, -10f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse("small scrolls do not minimize", state.minimized)
        c.onPostScroll(Offset(0f, -20f), Offset.Zero, NestedScrollSource.UserInput)
        assertTrue(state.minimized)
        c.onPostScroll(Offset(0f, 30f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse(state.minimized)
        c.onPostScroll(Offset(0f, -40f), Offset.Zero, NestedScrollSource.UserInput)
        assertTrue(state.minimized)
        // Pulling past the top brings it back even with nothing left to scroll.
        c.onPostScroll(Offset.Zero, Offset(0f, 12f), NestedScrollSource.UserInput)
        assertFalse(state.minimized)
    }

    @Test
    fun tabBar_staysExpandedWhileTouchExplorationIsOn() {
        val state = TabBarMinimizeState(threshold = 24f)
        state.enabled = false
        state.connection.onPostScroll(Offset(0f, -80f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse(state.minimized)
    }

    @Test
    fun bannerKind_readsFailuresAsErrorsAndGuidanceAsInfo() {
        assertEquals(BannerKind.Error, bannerKindFor("Clone failed: timeout"))
        assertEquals(BannerKind.Error, bannerKindFor("Could not install APK: denied"))
        assertEquals(BannerKind.Success, bannerKindFor("Repository cloned successfully"))
        assertEquals(BannerKind.Success, bannerKindFor("Saved AGENTS.md."))
        assertEquals(BannerKind.Info, bannerKindFor("Android build tools are not installed. Add Android in Settings."))
        assertEquals(BannerKind.Info, bannerKindFor("Stop the current agent before switching."))
    }

    @Test
    fun chromeText_growsOnlyToTheCap() {
        var systemScale by androidx.compose.runtime.mutableFloatStateOf(2f)
        var seen = 0f
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, systemScale),
            ) {
                CappedTextScale { seen = androidx.compose.ui.platform.LocalDensity.current.fontScale }
            }
        }
        compose.waitForIdle()
        assertEquals(ChromeMaxFontScale, seen, 1e-6f)
        systemScale = 1.1f
        compose.waitForIdle()
        assertEquals("Below the cap nothing changes", 1.1f, seen, 1e-6f)
    }
}
