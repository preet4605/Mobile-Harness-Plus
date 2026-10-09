package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    private fun writeRaw(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun backupsOf(file: File): List<File> = file.parentFile?.listFiles().orEmpty()
        .filter { it.name.startsWith("${file.name}.corrupt-") }

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
}
