package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Chats are stored as pages, so opening one reads only its newest messages and a save never drops the rest. */
class ChatPagedStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pageSize = AppPreferences.CHAT_PAGE_SIZE

    private fun messages(range: IntRange, text: (Int) -> String = { "message $it" }): List<ChatMessage> =
        range.map { ChatMessage(id = "m$it", fromUser = it % 2 == 0, text = text(it)) }

    private fun ids(range: IntRange): List<String> = range.map { "m$it" }

    private fun pagesDir(root: File): File = File(File(root, "p"), "c.pages")

    private fun page(root: File, index: Int): File = File(pagesDir(root), String.format("%08d.json", index))

    private fun fileKey(file: File): Any? = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()

    @Test
    fun savedChatLoadsWholeInOrder() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)

        prefs.saveMessages("p", "c", messages(0..39))

        assertEquals(ids(0..39), AppPreferences(baseChatsDir = root).loadMessages("p", "c").map { it.id })
        assertTrue(page(root, 2).isFile)
        assertFalse(page(root, 3).exists())
    }

    @Test
    fun windowReadsWholePagesFromTheEndUntilEnoughMessages() {
        val root = tmp.newFolder("chats")
        AppPreferences(baseChatsDir = root).saveMessages("p", "c", messages(0..39))

        val window = AppPreferences(baseChatsDir = root).loadMessageWindow("p", "c", minMessages = 15)

        // 40 messages: the last page holds 10, so the page before it is read too.
        assertEquals(pageSize, window.startIndex)
        assertEquals(ids(15..39), window.messages.map { it.id })
    }

    @Test
    fun olderMessagesLoadPageByPageUntilTheStart() {
        val root = tmp.newFolder("chats")
        AppPreferences(baseChatsDir = root).saveMessages("p", "c", messages(0..59))
        val prefs = AppPreferences(baseChatsDir = root)
        val window = prefs.loadMessageWindow("p", "c", minMessages = 15)

        val older = prefs.loadOlderMessages("p", "c", window.startIndex, minMessages = 30)
        val oldest = prefs.loadOlderMessages("p", "c", older.startIndex, minMessages = 30)

        assertEquals(45, window.startIndex)
        assertEquals(15, older.startIndex)
        assertEquals(ids(15..44), older.messages.map { it.id })
        assertEquals(0, oldest.startIndex)
        assertEquals(ids(0..14), oldest.messages.map { it.id })
        assertTrue(prefs.loadOlderMessages("p", "c", 0, minMessages = 30).messages.isEmpty())
    }

    @Test
    fun savingTheNewestWindowKeepsOlderMessages() {
        val root = tmp.newFolder("chats")
        AppPreferences(baseChatsDir = root).saveMessages("p", "c", messages(0..39))
        val prefs = AppPreferences(baseChatsDir = root)
        val window = prefs.loadMessageWindow("p", "c", minMessages = 15)
        val firstPageKey = fileKey(page(root, 0))

        prefs.saveMessages("p", "c", window.messages + messages(40..42), window.startIndex)

        assertEquals(ids(0..42), AppPreferences(baseChatsDir = root).loadMessages("p", "c").map { it.id })
        Assume.assumeNotNull(firstPageKey)
        assertEquals("pages before the window are never rewritten", firstPageKey, fileKey(page(root, 0)))
    }

    @Test
    fun onlyChangedPagesAreRewritten() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val chat = messages(0..39)
        prefs.saveMessages("p", "c", chat)
        val keys = (0..2).map { fileKey(page(root, it)) }
        Assume.assumeNotNull(keys[0])

        prefs.saveMessages("p", "c", chat.dropLast(1) + chat.last().copy(text = "edited"))

        assertEquals(keys[0], fileKey(page(root, 0)))
        assertEquals(keys[1], fileKey(page(root, 1)))
        assertNotEquals(keys[2], fileKey(page(root, 2)))
        assertEquals("edited", prefs.loadMessages("p", "c").last().text)
    }

    @Test
    fun shorterSaveRemovesPagesPastTheEnd() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveMessages("p", "c", messages(0..30))

        prefs.saveMessages("p", "c", messages(0..15))

        assertFalse(page(root, 2).exists())
        assertEquals(ids(0..15), prefs.loadMessages("p", "c").map { it.id })
    }

    @Test
    fun clearingFromTheStartReplacesTheWholeChat() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveMessages("p", "c", messages(0..44))

        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "cleared", fromUser = false, text = "cleared")))

        assertEquals(listOf("cleared"), AppPreferences(baseChatsDir = root).loadMessages("p", "c").map { it.id })
        assertFalse(page(root, 1).exists())
    }

    @Test
    fun pagesPastTheSavedCountAreIgnored() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveMessages("p", "c", messages(0..14))
        // A save that wrote its next page and stopped before the meta.
        page(root, 1).writeText(JSONArray().put(JSONObject().put("id", "ghost").put("fromUser", true).put("text", "x")).toString())

        assertEquals(ids(0..14), AppPreferences(baseChatsDir = root).loadMessages("p", "c").map { it.id })
        assertEquals(ids(0..14), AppPreferences(baseChatsDir = root).loadMessageWindow("p", "c", 15).messages.map { it.id })
    }

    @Test
    fun pagesWithoutMetaAreStillRead() {
        val root = tmp.newFolder("chats")
        AppPreferences(baseChatsDir = root).saveMessages("p", "c", messages(0..19))
        File(pagesDir(root), "meta.json").delete()

        val prefs = AppPreferences(baseChatsDir = root)
        val window = prefs.loadMessageWindow("p", "c", minMessages = 3)
        prefs.saveMessages("p", "c", window.messages + messages(20..20), window.startIndex)

        assertEquals(pageSize, window.startIndex)
        assertEquals(ids(0..20), AppPreferences(baseChatsDir = root).loadMessages("p", "c").map { it.id })
    }

    @Test
    fun saveThatWouldLeaveAGapIsRefused() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)

        val refused = runCatching { prefs.saveMessages("p", "c", messages(15..20), startIndex = pageSize) }

        assertTrue(refused.isFailure)
        assertTrue(prefs.loadMessages("p", "c").isEmpty())
        assertFalse(page(root, 1).exists())
    }

    @Test
    fun singleFileChatMovesToPagesOnFirstOpen() {
        val root = tmp.newFolder("chats")
        val legacy = File(File(root, "p"), "c.json")
        legacy.parentFile?.mkdirs()
        val array = JSONArray()
        (0..19).forEach { array.put(JSONObject().put("id", "m$it").put("fromUser", it % 2 == 0).put("text", "message $it")) }
        legacy.writeText(array.toString())

        val window = AppPreferences(baseChatsDir = root).loadMessageWindow("p", "c", minMessages = 15)

        assertEquals(0, window.startIndex)
        assertEquals(ids(0..19), window.messages.map { it.id })
        assertFalse("the single file is replaced once its pages are written", legacy.exists())
        assertEquals(ids(0..19), AppPreferences(baseChatsDir = root).loadMessages("p", "c").map { it.id })
    }

    @Test
    fun aSingleFileAppearingNextToSavedPagesIsIgnored() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveMessages("p", "c", messages(0..2))
        val stray = File(File(root, "p"), "c.json")
        stray.writeText(JSONArray().put(JSONObject().put("id", "stray").put("fromUser", true).put("text", "x")).toString())

        prefs.saveMessages("p", "c", messages(0..3))

        assertEquals(ids(0..3), prefs.loadMessages("p", "c").map { it.id })
        assertTrue("a file the pages do not own is left alone", stray.exists())
    }

    @Test
    fun promptHistoryReadsNewestPagesUpToTheBudgetPlusTheFirstPage() {
        val root = tmp.newFolder("chats")
        AppPreferences(baseChatsDir = root).saveMessages("p", "c", messages(0..99) { "x".repeat(1_000) })

        val history = AppPreferences(baseChatsDir = root).loadPromptHistory("p", "c", endIndex = 90, budgetChars = 24_000)

        // Two pages of 15,000 characters each cover the budget; page 0 carries the opening goal.
        assertEquals(ids(0..14) + ids(60..89), history.map { it.id })
    }

    @Test
    fun emptiedPagedChatCountsAsNoMessages() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveMessages("p", "c", messages(0..20))
        assertFalse(prefs.hasNoChatMessages("p"))

        prefs.saveMessages("p", "c", emptyList())

        assertTrue(prefs.hasNoChatMessages("p"))
    }
}
