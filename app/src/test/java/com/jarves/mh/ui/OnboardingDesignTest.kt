package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guard for the setup and sign-in screens every harness (Claude Code, DeepSeek,
 * Antigravity) starts with: they stay on the shared page frame and kit controls, and the
 * permission flow keeps asking only after an explicit tap.
 */
class OnboardingDesignTest {

    private val source: String by lazy {
        val relative = "src/main/java/com/jarves/mh/ui/OnboardingScreens.kt"
        listOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("OnboardingScreens.kt not found from ${File(".").absolutePath}")
    }

    private fun functionBody(name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("$name not found", start >= 0)
        val end = source.indexOf("\n}\n", start)
        assertTrue("$name end not found", end > start)
        return source.substring(start, end)
    }

    @Test
    fun setupScreens_useTheKitNotMaterialChrome() {
        listOf(
            "Scaffold(", "TopAppBar(", "ModalBottomSheet(", "AlertDialog(", "OutlinedTextField(",
            "OutlinedButton(", "TextButton(", "LinearProgressIndicator(", "CircularProgressIndicator(", "Card(",
        ).forEach {
            assertFalse("Setup screens must not fall back to Material $it", Regex("""(?<![A-Za-z])${Regex.escape(it)}""").containsMatchIn(source))
        }
        assertFalse(Regex("""(?<![A-Za-z.])Button\(""").containsMatchIn(source))
    }

    @Test
    fun everyPage_isOneBackdropSourceUnderTheGlassBar() {
        val frame = functionBody("OnboardingScaffold")
        assertEquals("The page itself is the one source", 1, Regex("""hostBackdropSource\(\)""").findAll(source).count())
        assertTrue(frame.contains("LargeTitleBar("))
        assertTrue("Pinned actions clear the navigation bar", frame.contains(".navigationBarsPadding()"))
        assertTrue("Pinned actions follow the keyboard", frame.contains(".imePadding()"))
        assertTrue("Content clears the measured actions", frame.contains("onSizeChanged { actionsHeight"))
        listOf("setup step", "provider step", "permission step").forEach { label ->
            assertTrue("$label moves with an animated transition", source.contains("label = \"$label\""))
        }
        assertEquals("Each stepped flow scopes its outgoing page", 2, Regex("""BackdropSourceScope\(active = isTransitionTarget\)""").findAll(source).count())
    }

    @Test
    fun permissionFlow_asksOnlyAfterAnExplicitTap() {
        val screen = functionBody("BackgroundTaskSetupScreen")
        val tap = screen.indexOf("val onPrimary")
        assertTrue(tap >= 0)
        listOf(
            "RuntimeExecutionService.ensureNotificationChannels(context)",
            "notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)",
            "Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS",
            "Settings.ACTION_APPLICATION_DETAILS_SETTINGS",
        ).forEach {
            assertTrue("$it runs from the button", screen.indexOf(it) > tap)
            assertEquals("$it has one call site", 1, Regex(Regex.escape(it)).findAll(screen).count())
        }
    }

    @Test
    fun setupScreens_takeEverythingFromTokens() {
        listOf("fontSize =", "RoundedCornerShape(", "Color(0x", "isSystemInDarkTheme(").forEach {
            assertFalse("Setup screens take $it from the design tokens", source.contains(it))
        }
    }
}
