package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatUserCountTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun user(id: String) = ChatMessage(id = id, fromUser = true, text = "prompt $id")

    private fun assistant(id: String) = ChatMessage(id = id, fromUser = false, text = "reply $id")

    private fun chat(id: String, title: String = id) = ProjectChat(id = id, title = title)

    @Test
    fun countFollowsSavedMessages() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))
        prefs.saveProjectChats("p", listOf(chat("main")))

        prefs.saveMessages("p", "main", listOf(user("u1"), assistant("a1"), user("u2")))
        assertEquals(2, prefs.userMessageCount("p"))

        prefs.saveMessages("p", "main", listOf(user("u1"), assistant("a1"), user("u2"), assistant("a2")))
        assertEquals(2, prefs.userMessageCount("p"))
    }

    @Test
    fun renamingAChatKeepsItsCount() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))
        prefs.saveProjectChats("p", listOf(chat("main")))
        prefs.saveMessages("p", "main", listOf(user("u1"), user("u2")))

        prefs.saveProjectChats("p", listOf(chat("main", title = "Renamed")))

        assertEquals(2, prefs.userMessageCount("p"))
    }

    @Test
    fun newChatWithoutMessagesCountsZero() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))

        prefs.saveProjectChats("p", listOf(chat("fresh")))

        assertEquals(0, prefs.userMessageCount("p"))
    }

    @Test
    fun countSumsAcrossChats() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))
        prefs.saveProjectChats("p", listOf(chat("one"), chat("two")))
        prefs.saveMessages("p", "one", listOf(user("u1")))
        prefs.saveMessages("p", "two", listOf(user("u2"), user("u3")))

        assertEquals(3, prefs.userMessageCount("p"))
    }

    @Test
    fun legacyIndexIsUnknownUntilBackfilled() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        // Messages exist before any index, as in a legacy install.
        prefs.saveMessages("p", "main", listOf(user("u1"), assistant("a1"), user("u2")))
        // Legacy index: no userMessageCount field.
        File(File(root, "p"), "index.json").writeText(
            JSONArray().put(
                JSONObject()
                    .put("id", "main")
                    .put("title", "Main chat")
                    .put("createdAtMillis", 1L)
                    .put("updatedAtMillis", 1L),
            ).toString(),
        )
        assertNull(prefs.userMessageCount("p"))

        prefs.backfillUserMessageCounts("p")

        assertEquals(2, prefs.userMessageCount("p"))
    }
}
