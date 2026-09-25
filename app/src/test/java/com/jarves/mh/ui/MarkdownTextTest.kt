package com.jarves.mh.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextTest {

    @Test
    fun testParseMarkdownBulletsAndContinuation() {
        val markdown = """
            - Result: BUILD SUCCESSFUL (all 26 test tasks passed).
            - Artifacts:
              - `app-online-debug.apk`: 110,604,971 bytes (~105.5 MB)
              - `mobile-harness-dev.apk`: 110,604,971 bytes (~105.5 MB)
            - MD5 Checksum:
              f610997c00932f68a61b6cb761bcb99a
        """.trimIndent()

        val blocks = parseMarkdown(markdown)
        assertEquals(5, blocks.size)

        // First bullet
        val b0 = blocks[0] as MarkdownBlock.BulletItem
        assertEquals(0, b0.depth)
        assertEquals("Result: BUILD SUCCESSFUL (all 26 test tasks passed).", b0.text)

        val b1 = blocks[1] as MarkdownBlock.BulletItem
        assertEquals(0, b1.depth)
        assertEquals("Artifacts:", b1.text)

        // Nested bullets
        val b2 = blocks[2] as MarkdownBlock.BulletItem
        assertEquals(1, b2.depth)
        assertEquals("`app-online-debug.apk`: 110,604,971 bytes (~105.5 MB)", b2.text)

        val b3 = blocks[3] as MarkdownBlock.BulletItem
        assertEquals(1, b3.depth)
        assertEquals("`mobile-harness-dev.apk`: 110,604,971 bytes (~105.5 MB)", b3.text)

        // Indented continuation line under bullet
        val b4 = blocks[4] as MarkdownBlock.BulletItem
        assertEquals(0, b4.depth)
        assertEquals("MD5 Checksum:\nf610997c00932f68a61b6cb761bcb99a", b4.text)
    }

    @Test
    fun testParseMarkdownOrderedAndCodeBlock() {
        val markdown = """
            3. APK Assembly & Distribution:
            ```bash
            ./gradlew assembleOnlineDebug
            ```
            - Result: BUILD SUCCESSFUL in 30s.
        """.trimIndent()

        val blocks = parseMarkdown(markdown)
        assertEquals(3, blocks.size)

        val num = blocks[0] as MarkdownBlock.NumberedItem
        assertEquals("3.", num.number)
        assertEquals("APK Assembly & Distribution:", num.text)

        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("bash", code.language)
        assertEquals("./gradlew assembleOnlineDebug", code.code)

        val bullet = blocks[2] as MarkdownBlock.BulletItem
        assertEquals("Result: BUILD SUCCESSFUL in 30s.", bullet.text)
    }

    @Test
    fun testBuildInlineMarkdownStripsLinkCodeBackticks() {
        val raw = "[`app-online-debug.apk`](file:///workspace/clever-kalam/app-online-debug.apk)"
        val primary = Color.Cyan
        val codeBg = Color.DarkGray
        val codeColor = Color.Yellow

        val annotated = buildInlineMarkdown(raw, primary, codeBg, codeColor)
        val text = annotated.text

        // Backticks should be stripped from link label
        assertEquals(" app-online-debug.apk ", text)
        assertFalse(text.contains('`'))

        // Style should have underline and monospace
        assertTrue(annotated.spanStyles.isNotEmpty())
        val style = annotated.spanStyles[0].item
        assertEquals(TextDecoration.Underline, style.textDecoration)
        assertEquals(FontFamily.Monospace, style.fontFamily)
        assertEquals(primary, style.color)
    }

    @Test
    fun testBuildInlineMarkdownTrailingPunctuation() {
        val raw = "File `app.apk`: ready for test."
        val primary = Color.Cyan
        val codeBg = Color.DarkGray
        val codeColor = Color.Yellow

        val annotated = buildInlineMarkdown(raw, primary, codeBg, codeColor)
        val text = annotated.text

        // There should not be a space between app.apk and the colon
        assertEquals("File  app.apk: ready for test.", text)
    }
}
