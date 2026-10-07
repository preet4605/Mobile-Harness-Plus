package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the shared Workspace/Chat Liquid Glass chrome (LG-1/LG-4).
 *
 * A checkpoint commit once replaced these call sites with plain Material 3 surfaces and no
 * test noticed. The project has no Compose UI test infrastructure, so this asserts the call
 * sites directly in the source that every harness (Claude Code, DeepSeek, Antigravity) renders.
 */
class ChatLiquidGlassRegressionTest {

    private val source: String by lazy {
        val relative = "src/main/java/com/jarves/mh/ui/PocketDevApp.kt"
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("PocketDevApp.kt not found from ${File(".").absolutePath}")
    }

    private fun functionBody(name: String): String {
        val start = source.indexOf("private fun $name(")
        assertTrue("$name not found", start >= 0)
        val end = source.indexOf("\n}\n", start)
        assertTrue("$name end not found", end > start)
        return source.substring(start, end)
    }

    private val workspace by lazy { functionBody("WorkspaceScreen") }
    private val chat by lazy { functionBody("ChatTab") }

    /** The closest `Surface(` call before [marker] must be a `LiquidGlassSurface(`. */
    private fun assertWrappedInGlass(body: String, marker: String, component: String) {
        val markerIndex = body.indexOf(marker)
        assertTrue("$component marker '$marker' not found", markerIndex >= 0)
        val surfaceIndex = body.lastIndexOf("Surface(", markerIndex)
        assertTrue("$component has no enclosing surface", surfaceIndex >= 0)
        val prefix = body.substring((surfaceIndex - "LiquidGlass".length).coerceAtLeast(0), surfaceIndex)
        assertEquals("$component must render via LiquidGlassSurface", "LiquidGlass", prefix)
    }

    @Test
    fun workspace_topBarAndTabs_useLiquidGlass() {
        assertTrue("Workspace top bar must be LiquidGlassTopBar", workspace.contains("LiquidGlassTopBar("))
        assertTrue("Workspace tabs must be LiquidGlassSegmentedControl", workspace.contains("LiquidGlassSegmentedControl("))
        assertFalse("Workspace must not fall back to M3 TopAppBar", workspace.contains("TopAppBar("))
        assertFalse("Workspace must not fall back to M3 NavigationBar", workspace.contains("NavigationBar("))
        assertTrue(workspace.contains("containerColor = Color.Transparent"))
        assertTrue(workspace.contains("contentWindowInsets = WindowInsets(0, 0, 0, 0)"))
    }

    @Test
    fun chat_latestChipsAndComposer_useLiquidGlassSurface() {
        assertWrappedInGlass(chat, "\"Latest\"", "Latest pill")
        assertWrappedInGlass(chat, "\"/ Commands\"", "Commands chip")
        assertWrappedInGlass(chat, "Text(\"Skills\"", "Skills chip")
        assertWrappedInGlass(chat, "\"Inspector (", "Inspector chip")
        assertWrappedInGlass(chat, "RoundedCornerShape(26.dp)", "Composer")
    }

    @Test
    fun chatGlass_usesSharedHostWithoutBackdropCapture() {
        assertFalse(workspace.contains("rememberBackdropState("))
        assertFalse(workspace.contains("LiquidGlassHost("))
        assertEquals("Exactly one LiquidGlassHost at the app root", 1, Regex("""\bLiquidGlassHost\(""").findAll(source).count())
        assertFalse("Chat glass stays tint-only until real blur is designed", Regex("""layerSource = LiquidGlassLayers""").containsMatchIn(workspace))
    }

    @Test
    fun chatControls_keep44dpTouchTargets() {
        assertEquals("All three chips use the 44dp touch wrapper", 3, Regex("""ChatChipTouchTarget\(onClick""").findAll(chat).count())
        assertFalse("Workspace/Chat icon controls must not shrink below 44dp", Regex("""\.size\((3\d|4[0-3])\.dp\)""").containsMatchIn(workspace))
        assertTrue(source.contains(".heightIn(min = LiquidGlassTokens.MinTouchTarget)\n            .clickable(role = Role.Button"))
    }
}
