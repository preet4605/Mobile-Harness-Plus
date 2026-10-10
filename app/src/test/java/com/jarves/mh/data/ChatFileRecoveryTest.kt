package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
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

class ChatFileRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(id: String, fromUser: Boolean, text: String): JSONObject = JSONObject()
        .put("id", id)
        .put("fromUser", fromUser)
        .put("text", text)
        .put("createdAt", "2026-10-01T00:00:00Z")

    private fun chatFile(root: File, projectId: String, chatId: String): File =
        File(File(root, projectId), "$chatId.json")

    /** The first page of a chat saved in the paged format. */
    private fun firstPage(root: File, projectId: String, chatId: String): File =
        File(File(File(root, projectId), "$chatId.pages"), "00000000.json")

    private fun writeRaw(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun backupsOf(file: File): List<File> = file.parentFile?.listFiles().orEmpty()
        .filter { it.name.startsWith("${file.name}.corrupt-") }

    private fun movedAsideIndexes(root: File, projectId: String): List<File> =
        File(root, projectId).listFiles().orEmpty().filter { it.name.startsWith("index.json.corrupt-") }

    private fun indexFile(root: File, projectId: String): File = File(File(root, projectId), "index.json")

    @Test
    fun malformedEntrySkipsOnlyItself() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val array = JSONArray()
            .put(json("a", true, "first"))
            .put(JSONObject().put("id", "broken").put("fromUser", true))
            .put(json("b", false, "second"))
        writeRaw(chatFile(root, "p", "c"), array.toString())

        assertEquals(listOf("a", "b"), prefs.loadMessages("p", "c").map { it.id })
    }

    @Test
    fun saveAfterMalformedEntryKeepsOriginalAsBackup() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = chatFile(root, "p", "c")
        val original = JSONArray().put(json("a", true, "first")).put(JSONObject().put("id", "broken")).toString()
        writeRaw(file, original)

        val loaded = prefs.loadMessages("p", "c")
        prefs.saveMessages("p", "c", loaded + ChatMessage(id = "new", fromUser = false, text = "reply"))

        assertEquals(original, backupsOf(file).single().readText())
        assertEquals(listOf("a", "new"), prefs.loadMessages("p", "c").map { it.id })
    }

    @Test
    fun unreadableFileIsNotRewrittenByLoadAndIsBackedUpBeforeSave() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = chatFile(root, "p", "c")
        val original = "{\"truncated\":"
        writeRaw(file, original)

        assertTrue(prefs.loadMessages("p", "c").isEmpty())
        assertEquals(original, file.readText())
        assertTrue(backupsOf(file).isEmpty())

        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "new", fromUser = true, text = "hi")))

        assertEquals(original, backupsOf(file).single().readText())
        assertEquals(listOf("new"), prefs.loadMessages("p", "c").map { it.id })
    }

    @Test
    fun saveWithoutPriorLoadStillBacksUpCorruptFile() {
        val root = tmp.newFolder("chats")
        val file = chatFile(root, "p", "c")
        writeRaw(file, "not json at all")

        AppPreferences(baseChatsDir = root)
            .saveMessages("p", "c", listOf(ChatMessage(id = "new", fromUser = true, text = "hi")))

        assertEquals("not json at all", backupsOf(file).single().readText())
    }

    @Test
    fun cleanFileIsSavedWithoutBackup() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = chatFile(root, "p", "c")

        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "a", fromUser = true, text = "one")))
        prefs.saveMessages(
            "p",
            "c",
            listOf(
                ChatMessage(id = "a", fromUser = true, text = "one"),
                ChatMessage(id = "b", fromUser = false, text = "two"),
            ),
        )

        assertTrue(backupsOf(file).isEmpty())
        assertEquals(listOf("a", "b"), prefs.loadMessages("p", "c").map { it.id })
    }

    @Test
    fun unreadableIndexIsMovedAsideBeforeTheNextSave() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val original = "{\"truncated\":"
        writeRaw(indexFile(root, "p"), original)

        prefs.saveProjectChats("p", listOf(ProjectChat(id = "fresh", title = "fresh")))

        val moved = movedAsideIndexes(root, "p")
        assertEquals("unreadable index must be moved aside, not overwritten", 1, moved.size)
        assertEquals(original, moved.single().readText())
        assertEquals(listOf("fresh"), prefs.loadProjectChats("p").map { it.id })
    }

    @Test
    fun loadMovesUnreadableIndexAsideAndNextSaveCreatesAFreshOne() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val original = "{\"truncated\":"
        writeRaw(indexFile(root, "p"), original)

        assertTrue(prefs.loadProjectChats("p").isEmpty())
        val moved = movedAsideIndexes(root, "p")
        assertEquals("load must move the unreadable index aside", 1, moved.size)
        assertEquals(original, moved.single().readText())

        prefs.saveProjectChats("p", listOf(ProjectChat(id = "fresh", title = "fresh")))

        assertEquals(listOf("fresh"), prefs.loadProjectChats("p").map { it.id })
        assertEquals(1, movedAsideIndexes(root, "p").size)
    }

    @Test
    fun readableIndexIsNeverMovedAside() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveProjectChats(
            "p",
            listOf(
                ProjectChat(id = "a", title = "A", updatedAtMillis = 1L),
                ProjectChat(id = "b", title = "B", updatedAtMillis = 2L),
            ),
        )

        assertEquals(listOf("b", "a"), prefs.loadProjectChats("p").map { it.id })
        prefs.saveProjectChats("p", listOf(ProjectChat(id = "a", title = "A", updatedAtMillis = 1L)))

        assertTrue(movedAsideIndexes(root, "p").isEmpty())
        assertEquals(listOf("a"), prefs.loadProjectChats("p").map { it.id })
    }

    @Test
    fun indexIsReplacedByRenameNotRewrittenInPlace() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val index = indexFile(root, "p").toPath()
        prefs.saveProjectChats("p", listOf(ProjectChat(id = "a", title = "A")))
        val before = Files.readAttributes(index, BasicFileAttributes::class.java).fileKey()
        Assume.assumeNotNull(before)

        prefs.saveProjectChats("p", listOf(ProjectChat(id = "a", title = "A"), ProjectChat(id = "b", title = "B")))

        assertNotEquals(
            "index must be written to a temp file and renamed over, so a crash cannot truncate it",
            before,
            Files.readAttributes(index, BasicFileAttributes::class.java).fileKey(),
        )
    }

    @Test
    fun movedAsideIndexStopsLegacyMainMigrationFromAttachingOldChats() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        writeRaw(File(File(root, "p"), "index.json.corrupt-1"), "{\"truncated\":")
        writeRaw(chatFile(root, "p", "main"), JSONArray().put(json("a", true, "old")).toString())

        assertTrue("no chat list may be rebuilt while a moved-aside index exists", prefs.loadProjectChats("p").isEmpty())
        assertFalse(indexFile(root, "p").exists())
    }

    @Test
    fun missingIndexWithoutMovedAsideFileStillMigratesTheMainChat() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)

        assertEquals(listOf("main"), prefs.loadProjectChats("p").map { it.id })
        assertTrue(indexFile(root, "p").isFile)
    }

    @Test
    fun verifiedFileDamagedLaterIsBackedUpOnTheNextSave() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = firstPage(root, "p", "c")
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "a", fromUser = true, text = "one")))

        val damaged = "{\"damaged\":"
        writeRaw(file, damaged)
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "b", fromUser = false, text = "two")))

        assertEquals("damage after verification must be backed up", listOf(damaged), backupsOf(file).map { it.readText() })
        assertEquals(listOf("b"), prefs.loadMessages("p", "c").map { it.id })
    }

    @Test
    fun damageSeenOnLoadIsBackedUpOnTheNextSave() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = firstPage(root, "p", "c")
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "a", fromUser = true, text = "one")))
        val damaged = "not json at all"
        writeRaw(file, damaged)

        assertTrue(prefs.loadMessages("p", "c").isEmpty())
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "b", fromUser = false, text = "two")))

        assertEquals(listOf(damaged), backupsOf(file).map { it.readText() })
    }

    @Test
    fun fileRecreatedAfterDeleteDoesNotInheritVerifiedTrust() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = firstPage(root, "p", "c")
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "a", fromUser = true, text = "one")))
        prefs.deleteProjectChats("p")
        val damaged = "not json at all"
        writeRaw(file, damaged)

        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "b", fromUser = false, text = "two")))

        assertEquals(listOf(damaged), backupsOf(file).map { it.readText() })
    }

    @Test
    fun eachDamageEventIsBackedUpSeparately() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        val file = firstPage(root, "p", "c")
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "a", fromUser = true, text = "one")))
        writeRaw(file, "{\"first\":")
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "b", fromUser = true, text = "two")))
        writeRaw(file, "{\"second\":")
        prefs.saveMessages("p", "c", listOf(ChatMessage(id = "c", fromUser = true, text = "three")))

        assertEquals(listOf("{\"first\":", "{\"second\":"), backupsOf(file).map { it.readText() }.sorted())
    }
}
