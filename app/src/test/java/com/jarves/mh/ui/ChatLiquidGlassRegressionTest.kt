package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the Workspace and Chat liquid glass chrome.
 *
 * A checkpoint commit once replaced these call sites with plain Material 3 surfaces and no
 * test noticed. This asserts the call sites directly in the source that every harness (Claude
 * Code, DeepSeek, Antigravity) renders, including the shared-backdrop wiring that gives Chat
 * real blur: the message list is the one source and all chrome floats over it as siblings.
 */
class ChatLiquidGlassRegressionTest {

    private fun read(name: String): String {
        val relative = "src/main/java/com/jarves/mh/ui/$name"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private val source: String by lazy { read("WorkspaceScreen.kt") }
    private val app: String by lazy { read("PocketDevApp.kt") }

    private fun functionBody(name: String, text: String = source): String {
        val start = text.indexOf("fun $name(")
        assertTrue("$name not found", start >= 0)
        val end = text.indexOf("\n}\n", start)
        assertTrue("$name end not found", end > start)
        return text.substring(start, end)
    }

    private val workspace by lazy { functionBody("WorkspaceScreen") }
    private val chat by lazy { functionBody("ChatTab") }
    private val composer by lazy { functionBody("Composer") }

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
    fun workspace_floatingGlassBar_noMaterialChrome() {
        assertTrue("Workspace uses its floating glass bar", workspace.contains("WorkspaceBar("))
        assertTrue("The bar is one row: no second switcher row under it", !workspace.contains("SegmentedControl("))
        assertTrue("Views switch from the menu on the chat title", workspace.contains("onTitleClick = { showViews = true }") && workspace.contains("GlassMenu(expanded = showViews"))
        assertTrue("Bar actions are one glass group", workspace.contains("GlassToolbarGroup {"))
        assertTrue("The bar softens content under it", functionBody("WorkspaceBar").contains("ScrollEdgeEffect("))
        listOf("TopAppBar(", "Scaffold(", "NavigationBar(", "AlertDialog(", "ModalBottomSheet(", "Card(").forEach {
            assertFalse("Workspace must not fall back to Material $it", Regex("""(?<![A-Za-z])${Regex.escape(it)}""").containsMatchIn(source))
        }
    }

    @Test
    fun everyGlassSurface_samplesTheSharedBackground() {
        val surfaces = calls(source, "LiquidGlassSurface")
        assertTrue("Latest, activity, attachment, + button, composer and read-only banner", surfaces.size >= 6)
        surfaces.forEach { call ->
            assertTrue("Chat glass must sample the shared backdrop:\n$call", call.contains("layerSource = LiquidGlassLayers.Background"))
        }
        assertFalse("Chat must not force tint-only glass", source.contains("layerSource = null"))
        assertTrue(calls(chat, "LiquidGlassSurface").single().contains("role = GlassRoles.Latest"))
        assertEquals("+ button and field share the composer glass", 2, calls(composer, "LiquidGlassSurface").count { it.contains("role = GlassRoles.Composer") })
    }

    @Test
    fun chat_registersOneSharedSourceWithChromeAsSiblings() {
        assertEquals("Exactly one backdrop source in Chat", 1, Regex("""hostBackdropSource\(\)""").findAll(chat).count())
        val listStart = chat.indexOf("LazyColumn(")
        assertTrue("The message list itself is the source", callAt(chat, listStart).contains(".hostBackdropSource()"))
        val list = callAt(chat, listStart, withBlock = true)
        assertFalse("Glass inside its own source would record itself", list.contains("LiquidGlassSurface("))
        assertFalse("The composer is not inside the list", list.contains("Composer("))
        assertTrue("The composer floats after the list", chat.indexOf("Composer(") > chat.indexOf(list))
        assertFalse(source.contains("rememberBackdropState("))
        assertFalse(source.contains("LiquidGlassHost("))
        assertEquals("Exactly one LiquidGlassHost at the app root", 1, Regex("""\bLiquidGlassHost\(""").findAll(app).count())
    }

    @Test
    fun workspaceTabs_scrollingViewsAreSources_othersStartBelowTheBar() {
        assertEquals("Files list is its view's one source", 1, Regex("""hostBackdropSource\(\)""").findAll(functionBody("FilesTab")).count())
        assertEquals("Terminal and Preview start below the bar", 2, Regex("""\.padding\(top = top\)""").findAll(workspace).count())
        assertTrue(workspace.contains("topClearance = top"))
        assertTrue("The bar is measured, not guessed", workspace.contains("Modifier.onSizeChanged(onBarSize)"))
    }

    @Test
    fun chatChrome_floatsWithMeasuredClearance() {
        assertEquals("No opaque Material surfaces in Chat", 0, calls(chat, "Surface").size)
        assertTrue(chat.contains("top = topClearance + PocketSpacing.md"))
        assertTrue(chat.contains("bottom = bottomChromeClearance + PocketSpacing.lg"))
        assertTrue(chat.contains(".padding(bottom = bottomChromeClearance + PocketSpacing.md)"))
        assertTrue("Composer stack clears the navigation bar", chat.contains(".navigationBarsPadding()"))
        assertTrue("Composer stack must follow the keyboard", chat.contains(".imePadding()"))
        assertTrue(chat.contains("bottomChromeClearance = clearance"))
        assertTrue("Messages soften under the composer", chat.contains("ScrollEdgeEffect("))
    }

    @Test
    fun chatChrome_followsAppThemeAndSharedTokens() {
        // Glass tint and chrome text must follow the in-app theme, not only the system setting.
        assertFalse(source.contains("isSystemInDarkTheme("))
        assertTrue(chat.contains("start = PocketSpacing.lg,") && chat.contains("end = PocketSpacing.lg,"))
        listOf("fontSize =", "RoundedCornerShape(", "Color(0x").forEach {
            assertFalse("Workspace takes $it from the design tokens", source.contains(it))
        }
    }

    @Test
    fun rootBackdrop_staysIntact() {
        val root = functionBody("RootScreenHost", app)
        // Root's content box is not a source; each root screen's list is (RootLiquidGlassWiringTest).
        assertFalse(root.contains("asBackdropSource("))
        assertTrue("Root dock is the floating glass tab bar", root.contains("FloatingTabBar("))
    }

    @Test
    fun chatGlass_isHarnessAgnostic() {
        source.lines()
            .filter { it.contains("agentKind") || it.contains("AgentKind") }
            .forEach { line ->
                assertFalse(
                    "Glass/backdrop must not depend on the harness: $line",
                    Regex("""layerSource|Backdrop|LiquidGlass|GlassRoles""").containsMatchIn(line),
                )
            }
    }

    @Test
    fun chatControls_keep44dpTouchTargets() {
        assertTrue("Composer never compresses below 52dp", source.contains("private val ComposerMinHeight = 52.dp"))
        assertTrue(composer.contains(".heightIn(min = ComposerMinHeight)"))
        val action = functionBody("ComposerActionButton")
        assertTrue("Send and stop take the touch from a 44dp box", action.indexOf(".size(LiquidGlassTokens.MinTouchTarget)") in 0 until action.indexOf(".clickable("))
        listOf("ActivityCapsule", "FileRow").forEach {
            assertTrue("$it rows are at least 44dp", functionBody(it).contains(".heightIn(min = LiquidGlassTokens.MinTouchTarget)"))
        }
        assertTrue("Latest pill is at least 44dp", chat.contains(".heightIn(min = LiquidGlassTokens.MinTouchTarget)"))
    }
}
