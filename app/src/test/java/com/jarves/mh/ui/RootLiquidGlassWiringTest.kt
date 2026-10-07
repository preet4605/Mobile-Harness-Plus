package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for real blur on the root screens (Projects, Agent, Settings), shared by
 * every harness. Each screen's scrolling list is the one shared backdrop source and the glass
 * buttons of its large-title bar sample it as siblings; Root's own content box must not also be
 * a source (that nested capture would include the bars and make them sample themselves).
 */
class RootLiquidGlassWiringTest {

    private fun read(name: String): String {
        val relative = "src/main/java/com/jarves/mh/ui/$name"
        return listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun body(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val end = source.indexOf("\n}\n", start)
        assertTrue("$signature end not found", end > start)
        return source.substring(start, end)
    }

    private val app by lazy { read("PocketDevApp.kt") }
    private val root by lazy { body(app, "private fun RootScreenHost(") }
    private val projects by lazy { body(read("ProjectsScreen.kt"), "fun ProjectsScreen(") }
    private val agent by lazy { body(read("AgentScreen.kt"), "fun AgentScreen(") }
    private val settings by lazy { body(read("SettingsScreenModern.kt"), "fun SettingsScreen(") }

    private fun sources(body: String) = Regex("""hostBackdropSource\(\)""").findAll(body).count()

    @Test
    fun root_contentBoxIsNotItselfASource() {
        assertFalse(root.contains("asBackdropSource("))
        assertEquals(0, sources(root))
        assertTrue("Root dock is the floating glass tab bar", root.contains("FloatingTabBar("))
        assertTrue("The tab bar samples the shared backdrop", read("kit/TabBar.kt").contains("layerSource = LiquidGlassLayers.Background"))
    }

    @Test
    fun rootScreens_listIsTheSingleSourceAndBarSamplesIt() {
        mapOf("Projects" to projects, "Agent" to agent, "Settings" to settings).forEach { (name, body) ->
            assertEquals("$name must register exactly one backdrop source", 1, sources(body))
            assertTrue("$name uses the large-title frame", body.contains("LargeTitleScaffold("))
            val list = body.substring(body.indexOf("LazyColumn(", body.indexOf("{ padding ->")))
            assertTrue("$name list must be the source", list.substring(0, 200).contains(".hostBackdropSource()"))
            assertTrue("$name list must scroll under the bar", list.contains("top = padding.calculateTopPadding()"))
            assertFalse("$name must not fall back to M3 TopAppBar", body.contains("TopAppBar("))
            assertFalse("$name must not use Material sheets", body.contains("ModalBottomSheet("))
        }
        // The bar's glass buttons sample the list as siblings.
        assertTrue(read("kit/Toolbar.kt").contains("layerSource = LiquidGlassLayers.Background"))
    }

    @Test
    fun glass_hidesSharpContentUnderTheBlur() {
        val backdrop = read("theme/glass/GlassEngine.kt")
        val canvas = backdrop.indexOf("drawOutline(outline, look.canvas)")
        val blur = backdrop.indexOf("drawLayer(blurLayer)")
        assertTrue("opaque canvas must be drawn before the blurred capture", canvas in 0 until blur)
    }
}
