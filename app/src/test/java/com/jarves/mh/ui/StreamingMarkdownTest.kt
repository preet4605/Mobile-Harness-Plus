package com.jarves.mh.ui

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTest {
    private val pieces = listOf(
        "# Heading", "## Sub", "Plain text line", "more **bold** text", "", "", "- bullet", "* star item",
        "  indented continuation", "    deeper", "1. first", "2) second", "> quote", "---", "```kotlin",
        "val x = 1", "```", "```", "• dot item", "   ", "text with `code`", "\t tabbed",
    )

    private fun randomMarkdown(random: Random): String = buildString {
        repeat(random.nextInt(1, 40)) {
            append(pieces[random.nextInt(pieces.size)])
            append(listOf("\n", "\n", "\n", "\r\n", "\r")[random.nextInt(5)])
        }
        if (random.nextBoolean()) append(pieces[random.nextInt(pieces.size)])
    }

    @Test fun everyStreamedPrefixParsesLikeTheWholePrefix() {
        val random = Random(1234)
        repeat(400) { round ->
            val text = randomMarkdown(random)
            val streaming = StreamingMarkdown()
            var end = 0
            while (end < text.length) {
                end = minOf(text.length, end + random.nextInt(1, 12))
                val prefix = text.substring(0, end)
                assertEquals("round $round at $end of ${text.length}: ${prefix.replace("\n", "⏎")}", parseMarkdown(prefix), streaming.blocksFor(prefix))
            }
        }
    }

    @Test fun aLongReplyResumesNearItsEnd() {
        val reply = (1..50).joinToString("\n\n") { "## Part $it\n\nSome text for part $it." }
        val parse = parseMarkdownResumable(reply)
        assertTrue("resume point is in the last part", parse.resumeOffset > reply.length - 40)
        assertEquals(parseMarkdown(reply + " More."), parse.extend(reply + " More.").blocks)
    }

    @Test fun aDifferentTextStartsOver() {
        val parse = parseMarkdownResumable("# One\n\nbody\n")
        assertEquals(parseMarkdown("# Two\n\nbody\n"), parse.extend("# Two\n\nbody\n").blocks)
    }

    @Test fun parseIsUnchangedForCrlfAndCr() {
        assertEquals(parseMarkdown("a\nb\n\n# h"), parseMarkdown("a\r\nb\r\n\r\n# h"))
        assertEquals(parseMarkdown("a\nb\n\n# h"), parseMarkdown("a\rb\r\r# h"))
    }
}
