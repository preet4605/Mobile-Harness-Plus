package com.jarves.mh.ui

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.Project
import com.jarves.mh.ui.theme.InterDisplayFamily
import com.jarves.mh.ui.theme.InterFamily
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketTypography
import com.jarves.mh.ui.theme.interTracking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI

/**
 * Guards batch 1 of the motion work: spring tokens follow the duration/bounce formula, every
 * screen switch is animated instead of a hard cut, and only the destination screen registers
 * the shared backdrop source while a transition runs.
 */
class AppNavigationMotionTest {

    private fun read(name: String): String {
        val relative = "src/main/java/com/jarves/mh/ui/$name"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private val app by lazy { read("PocketDevApp.kt") }

    @Test
    fun springTokens_followDurationAndBounce() {
        PocketMotion.Token.entries.forEach { token ->
            val expected = (2 * PI / token.durationSeconds).let { it * it }.toFloat()
            assertEquals(token.name, expected, token.stiffness, 0.5f)
            assertEquals(token.name, 1f - token.bounce, token.dampingRatio, 1e-6f)
            assertTrue("${token.name} bounce stays in the calm range", token.bounce in 0f..0.3f)
        }
        assertEquals(195f, PocketMotion.Token.Smooth.stiffness, 0.5f)
        assertEquals(0.85f, PocketMotion.Token.Snappy.dampingRatio, 1e-6f)
    }

    @Test
    fun destination_followsScreenPriority() {
        val ready = AppUiState(startupStage = StartupStage.READY, backgroundSetupComplete = true)
        val project = Project(name = "demo", description = "", language = "Kotlin")
        assertEquals(AppDestination.Loading, AppUiState().appDestination())
        assertEquals(AppDestination.BackgroundSetup, ready.copy(backgroundSetupComplete = false).appDestination())
        assertEquals(
            AppDestination.AntigravityOnboarding,
            ready.copy(startupStage = StartupStage.MODEL_SETUP, agentKind = AgentKind.ANTIGRAVITY).appDestination(),
        )
        assertEquals(AppDestination.Root, ready.appDestination())
        assertEquals(AppDestination.Root, ready.copy(activeProject = project).appDestination())
        assertEquals(AppDestination.Workspace, ready.copy(activeProject = project, workspaceVisible = true).appDestination())
        assertEquals(AppDestination.ReadOnlyProject, ready.copy(readOnlyProject = project).appDestination())
        assertTrue(AppDestination.Workspace.depth > AppDestination.Root.depth)
        assertTrue(AppDestination.entries.filter { it.isStartup }.all { it.depth < AppDestination.Root.depth })
    }

    @Test
    fun everyScreenSwitch_isAnimated() {
        listOf("app navigation", "root tab", "workspace tab").forEach { label ->
            assertTrue("$label must be an AnimatedContent", app.contains("label = \"$label\""))
        }
        assertFalse("app screens must not hard-cut on a bare when", app.contains("        when {\n            state.startupStage"))
        assertFalse(app.contains("when (screen) {"))
        assertFalse(app.contains("when (selectedTab) {"))
    }

    @Test
    fun onlyTheDestinationScreen_isTheBackdropSource() {
        assertEquals(3, Regex("""BackdropSourceScope\(active = isTransitionTarget\)""").findAll(app).count())
        assertTrue("outgoing screens keep showing what they showed", app.contains("heldWhileExiting(state)"))
    }

    @Test
    fun typography_usesInterWithItsTrackingCurve() {
        val t = PocketTypography
        assertEquals(InterDisplayFamily, t.headlineLarge.fontFamily)
        assertEquals(InterFamily, t.titleMedium.fontFamily)
        assertEquals(InterFamily, t.bodyLarge.fontFamily)
        assertEquals(interTracking(17), t.titleMedium.letterSpacing.value, 1e-6f)
        assertEquals(-0.0128f, interTracking(17), 1e-4f)
        assertTrue("small labels open up slightly", interTracking(11) > 0f)
    }
}
