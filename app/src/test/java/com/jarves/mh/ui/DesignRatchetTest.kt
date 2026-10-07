package com.jarves.mh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Screens take sizes, shapes and colours from the design tokens (PocketType, PocketShape,
 * PocketColors) instead of literals. The literal counts per screen file may only go down;
 * lower a ceiling when a screen gets cleaner, and never raise one.
 */
class DesignRatchetTest {

    private data class Ceiling(val fontSizes: Int, val shapes: Int, val colors: Int)

    private val ceilings = mapOf(
        "CliParityComponents.kt" to Ceiling(108, 35, 14),
        "MarkdownText.kt" to Ceiling(5, 3, 0),
        "MemoryViewerSheet.kt" to Ceiling(12, 7, 0),
        "PocketDevApp.kt" to Ceiling(108, 53, 38),
        "TerminalScreen.kt" to Ceiling(10, 5, 7),
    )

    private val uiDir: File by lazy {
        listOf(File("src/main/java/com/jarves/mh/ui"), File("app/src/main/java/com/jarves/mh/ui"))
            .first { it.isDirectory }
    }

    private fun screens(): List<File> = uiDir.listFiles { f -> f.isFile && f.extension == "kt" }!!.sortedBy { it.name }

    private fun count(text: String, literal: String) = Regex(Regex.escape(literal)).findAll(text).count()

    @Test
    fun literalCounts_onlyGoDown() {
        val failures = screens().mapNotNull { file ->
            val text = file.readText()
            val ceiling = ceilings[file.name] ?: Ceiling(0, 0, 0)
            val found = Ceiling(count(text, "fontSize ="), count(text, "RoundedCornerShape("), count(text, "Color(0x"))
            val over = listOfNotNull(
                "fontSize ${found.fontSizes} > ${ceiling.fontSizes}".takeIf { found.fontSizes > ceiling.fontSizes },
                "RoundedCornerShape ${found.shapes} > ${ceiling.shapes}".takeIf { found.shapes > ceiling.shapes },
                "Color(0x ${found.colors} > ${ceiling.colors}".takeIf { found.colors > ceiling.colors },
            )
            if (over.isEmpty()) null else "${file.name}: ${over.joinToString()} (use PocketType / PocketShape / PocketColors)"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun screens_useOutlinedOrRoundedSymbols() {
        screens().forEach { file ->
            val text = file.readText()
            assertFalse("${file.name} uses filled icons", Regex("""Icons\.(Default|Filled)\.|AutoMirrored\.Filled\.|icons\.filled\.""").containsMatchIn(text))
        }
    }
}
