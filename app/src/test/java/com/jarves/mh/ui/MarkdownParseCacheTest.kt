package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParseCacheTest {

    @Test
    fun sameTextReturnsTheCachedParse() {
        val first = MarkdownParseCache.blocksFor("# Title\n\nbody text")
        val equalButSeparate = StringBuilder("# Title\n\nbody text").toString()

        assertSame(first, MarkdownParseCache.blocksFor(equalButSeparate))
    }

    @Test
    fun changedTextGetsAFreshParse() {
        val before = MarkdownParseCache.blocksFor("streaming part one")
        val after = MarkdownParseCache.blocksFor("streaming part one and two")

        assertNotSame(before, after)
        assertEquals(parseMarkdown("streaming part one and two"), after)
    }

    @Test
    fun cacheStaysBoundedAndEvictsTheOldestEntry() {
        val first = MarkdownParseCache.blocksFor("entry-0")
        repeat(MarkdownParseCache.MAX_ENTRIES) { MarkdownParseCache.blocksFor("entry-${it + 1}") }

        assertTrue(MarkdownParseCache.entryCount() <= MarkdownParseCache.MAX_ENTRIES)
        assertNotSame(first, MarkdownParseCache.blocksFor("entry-0"))
    }

    @Test
    fun cacheEvictsOldestEntriesWhenTotalCharactersExceedTheBudget() {
        // Three 200k-character texts exceed the 512k-character budget long before the entry count limit.
        val first = MarkdownParseCache.blocksFor("first-" + "a".repeat(200_000))
        MarkdownParseCache.blocksFor("second-" + "b".repeat(200_000))
        MarkdownParseCache.blocksFor("third-" + "c".repeat(200_000))

        assertNotSame(first, MarkdownParseCache.blocksFor("first-" + "a".repeat(200_000)))
    }

    @Test
    fun textLargerThanTheWholeBudgetIsParsedButNotKept() {
        val oversized = "o".repeat(600_000)

        assertNotSame(MarkdownParseCache.blocksFor(oversized), MarkdownParseCache.blocksFor(oversized))
    }

    @Test
    fun orderedListStillParsesAsNumberedItems() {
        val blocks = parseMarkdown("1. first\n2. second\n\nplain")

        assertEquals(2, blocks.filterIsInstance<MarkdownBlock.NumberedItem>().size)
        assertEquals(1, blocks.filterIsInstance<MarkdownBlock.Paragraph>().size)
    }
}
