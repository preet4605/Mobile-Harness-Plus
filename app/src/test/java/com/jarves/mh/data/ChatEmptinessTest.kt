package com.jarves.mh.data

import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatEmptinessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun assistant(id: String) = ChatMessage(id = id, fromUser = false, text = "reply $id")

    private fun user(id: String) = ChatMessage(id = id, fromUser = true, text = "prompt $id")

    private fun chat(id: String) = ProjectChat(id = id, title = id)

    @Test
    fun assistantOnlyChatCountsAsMessages() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))
        prefs.saveProjectChats("p", listOf(chat("main")))

        prefs.saveMessages("p", "main", listOf(assistant("a1")))

        assertFalse(prefs.hasNoChatMessages("p"))
    }

    @Test
    fun freshProjectHasNoMessages() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))

        prefs.saveProjectChats("p", listOf(chat("fresh")))

        assertTrue(prefs.hasNoChatMessages("p"))
    }

    @Test
    fun chatClearedBackToEmptyListHasNoMessages() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))
        prefs.saveProjectChats("p", listOf(chat("main")))
        prefs.saveMessages("p", "main", listOf(user("u1")))

        prefs.saveMessages("p", "main", emptyList())

        assertTrue(prefs.hasNoChatMessages("p"))
    }

    @Test
    fun chatFileMissingFromIndexStillCountsAsMessages() {
        val prefs = AppPreferences(baseChatsDir = tmp.newFolder("chats"))
        prefs.saveProjectChats("p", emptyList())

        // The index does not list the chat, as after an index write that was lost.
        prefs.saveMessages("p", "main", listOf(user("u1")))

        assertFalse(prefs.hasNoChatMessages("p"))
    }

    @Test
    fun unreadableChatFileKeepsTheProject() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        prefs.saveProjectChats("p", listOf(chat("main")))
        File(File(root, "p"), "main.json").writeText("{ not a chat list")

        assertFalse(prefs.hasNoChatMessages("p"))
    }

    @Test
    fun legacySingleFileWithMessagesKeepsTheProject() {
        val root = tmp.newFolder("chats")
        val prefs = AppPreferences(baseChatsDir = root)
        File(root, "p.json").writeText(JSONArray().put(JSONObject().put("id", "m1")).toString())

        assertFalse(prefs.hasNoChatMessages("p"))
    }
}
