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
 * sites directly in the source that every harness (Claude Code, DeepSeek, Antigravity) renders,
 * including the shared-backdrop wiring that gives Chat real blur (Phase 6B).
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
        val start = source.indexOf("fun $name(")
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

    /** Argument list (and trailing lambda, if [withBlock]) of the call starting at [start]. */
    private fun callAt(body: String, start: Int, withBlock: Boolean = false): String {
        var i = body.indexOf('(', start)
        var depth = 0
        while (i < body.length) {
            when (body[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) break
            }
            i++
        }
        if (!withBlock) return body.substring(start, i + 1)
        var j = body.indexOf('{', i)
        depth = 0
        while (j < body.length) {
            when (body[j]) {
                '{' -> depth++
                '}' -> if (--depth == 0) break
            }
            j++
        }
        return body.substring(start, j + 1)
    }

    private fun calls(body: String, name: String): List<String> =
        Regex("""(?<![A-Za-z])${Regex.escape(name)}\(""").findAll(body).map { callAt(body, it.range.first) }.toList()

    @Test
    fun chatGlass_bindsEveryConsumerToSharedBackgroundLayer() {
        val surfaces = calls(chat, "LiquidGlassSurface")
        assertEquals("Latest, telemetry, 3 chips and composer", 6, surfaces.size)
        surfaces.forEach { call ->
            assertTrue("Chat glass must sample the shared backdrop:\n$call", call.contains("layerSource = LiquidGlassLayers.Background"))
            assertTrue("Chat glass must use an existing GlassRole:\n$call", Regex("""role = GlassRoles\.(Latest|Chip|Composer)""").containsMatchIn(call))
        }
        assertFalse("Chat must not force tint-only glass", chat.contains("layerSource = null"))
    }

    @Test
    fun workspaceChrome_samplesBackdropOnlyWhileChatScrollsUnderIt() {
        assertTrue(
            Regex("""val chromeLayer = if \(selectedTab == WorkspaceTab\.CHAT\) LiquidGlassLayers\.Background else null""")
                .containsMatchIn(workspace),
        )
        (calls(workspace, "LiquidGlassTopBar") + calls(workspace, "LiquidGlassSegmentedControl")).forEach { call ->
            assertTrue("Workspace chrome must bind to chromeLayer:\n${call.take(120)}", call.contains("layerSource = chromeLayer"))
        }
        // Chat content runs edge to edge under the bars; other tabs stay padded and clipped.
        assertTrue(Regex("""if \(tab == WorkspaceTab\.CHAT\) \{\s*Modifier\.fillMaxSize\(\)\s*\} else \{\s*Modifier\.fillMaxSize\(\)\.padding\(padding\)\.clipToBounds\(\)""").containsMatchIn(workspace))
        assertTrue("ChatTab must receive the bar insets as content clearance", workspace.contains("chromePadding = padding"))
    }

    @Test
    fun chat_registersOneSharedSourceWithChromeAsSiblings() {
        assertEquals("Exactly one backdrop source in Chat", 1, Regex("""hostBackdropSource\(\)""").findAll(chat).count())
        val list = callAt(chat, chat.indexOf("LazyColumn("), withBlock = true)
        assertTrue("The message list itself is the source", callAt(chat, chat.indexOf("LazyColumn(")).contains(".hostBackdropSource()"))
        assertFalse("Glass inside its own source would record itself", list.contains("LiquidGlassSurface("))
        assertFalse(workspace.contains("rememberBackdropState("))
        assertFalse(chat.contains("rememberBackdropState("))
        assertFalse(workspace.contains("LiquidGlassHost("))
        assertEquals("Exactly one LiquidGlassHost at the app root", 1, Regex("""\bLiquidGlassHost\(""").findAll(source).count())
    }

    @Test
    fun chatChrome_floatsWithMeasuredClearance() {
        assertEquals("Only the read-only banner may stay an opaque M3 Surface", 1, calls(chat, "Surface").size)
        assertTrue(chat.contains("top = chromePadding.calculateTopPadding() + PocketSpacing.md"))
        assertTrue(chat.contains("bottom = bottomChromeClearance + PocketSpacing.lg"))
        assertTrue(chat.contains(".padding(bottom = bottomChromeClearance + PocketSpacing.md)"))
        assertTrue("Composer stack must follow the keyboard", chat.contains(".imePadding()"))
        assertTrue(chat.contains("bottomChromeClearance = clearance"))
    }

    @Test
    fun chatChrome_followsAppThemeAndSharedTokens() {
        // Glass tint and chrome text must follow the in-app theme, not only the system setting.
        assertFalse(workspace.contains("isSystemInDarkTheme("))
        assertFalse(chat.contains("isSystemInDarkTheme("))
        // Composer stack and dock share one horizontal margin; secondary glass is pill-shaped.
        assertTrue(workspace.contains(".padding(start = PocketSpacing.lg, end = PocketSpacing.lg, bottom = PocketSpacing.sm)"))
        assertTrue(chat.contains("start = PocketSpacing.lg,") && chat.contains("end = PocketSpacing.lg,"))
        assertEquals("Latest, telemetry and 3 chips are pills", 5, Regex("""RoundedCornerShape\(LiquidGlassTokens\.PillRadius\)""").findAll(chat).count())
    }

    @Test
    fun rootBackdrop_staysIntact() {
        val root = functionBody("RootScreenHost")
        // Root's content box is not a source; each root screen's list is (RootLiquidGlassWiringTest).
        assertFalse(root.contains("asBackdropSource("))
        assertTrue("Root dock is the floating glass tab bar", root.contains("FloatingTabBar("))
    }

    @Test
    fun chatGlass_isHarnessAgnostic() {
        (workspace + chat).lines()
            .filter { it.contains("agentKind") || it.contains("AgentKind") }
            .forEach { line ->
                assertFalse(
                    "Glass/backdrop must not depend on the harness: $line",
                    Regex("""layerSource|Backdrop|LiquidGlass|chromeLayer|GlassRoles""").containsMatchIn(line),
                )
            }
    }

    @Test
    fun chatControls_keep44dpTouchTargets() {
        assertEquals("All three chips use the 44dp touch wrapper", 3, Regex("""ChatChipTouchTarget\(onClick""").findAll(chat).count())
        assertFalse("Workspace/Chat icon controls must not shrink below 44dp", Regex("""\.size\((3\d|4[0-3])\.dp\)""").containsMatchIn(workspace))
        assertTrue(source.contains(".heightIn(min = LiquidGlassTokens.MinTouchTarget)\n            .clickable(role = Role.Button"))
    }
}
