package com.jarves.mh.ui

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ChatMessage
import com.jarves.mh.model.ProjectChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A long chat opens with only its newest messages; older ones load on demand and are never dropped by a save. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelChatWindowTest {
    private val app = robolectricApplication()

    private fun messages(range: IntRange): List<ChatMessage> =
        range.map { ChatMessage(id = "m$it", fromUser = it % 2 == 0, text = "message $it") }

    private fun ids(range: IntRange): List<String> = range.map { "m$it" }

    /** Saves [chats] for the project, each with its messages; the first chat is the newest and opens first. */
    private fun savedProject(name: String, vararg chats: Pair<ProjectChat, List<ChatMessage>>) = testProject(name).also { project ->
        val prefs = AppPreferences(app)
        prefs.saveProjectChats(project.id, chats.map { it.first })
        chats.forEach { (chat, list) -> prefs.saveMessages(project.id, chat.id, list) }
    }

    /** Writes the pending transcript now, as a finished task does. */
    private fun MainViewModel.persistNow() {
        val method = MainViewModel::class.java.getDeclaredMethod(
            "persistMessages",
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        method.invoke(this, true, true)
    }

    @Test
    fun openingALongChatLoadsOnlyItsNewestMessages() {
        val chat = ProjectChat(title = "Long", updatedAtMillis = 2L)
        val project = savedProject("Long chat", chat to messages(0..39))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)

        viewModel.openProject(project)
        io.runAll()

        assertEquals(ids(15..39), viewModel.state.value.messages.map { it.id })
        assertEquals(15, viewModel.state.value.olderMessageCount)
    }

    @Test
    fun scrollingUpLoadsOlderMessagesAboveTheNewestOnes() {
        val chat = ProjectChat(title = "Long", updatedAtMillis = 2L)
        val project = savedProject("Scroll up", chat to messages(0..39))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(project)
        io.runAll()

        viewModel.loadOlderMessages()
        io.runAll()

        assertEquals(ids(0..39), viewModel.state.value.messages.map { it.id })
        assertEquals(0, viewModel.state.value.olderMessageCount)
    }

    @Test
    fun savingAfterOpenKeepsTheMessagesThatWereNotLoaded() {
        val chat = ProjectChat(title = "Long", updatedAtMillis = 2L)
        val project = savedProject("Keep older", chat to messages(0..39))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(project)
        io.runAll()

        viewModel.sendPrompt("/help")
        viewModel.persistNow()

        val saved = AppPreferences(app).loadMessages(project.id, chat.id)
        assertEquals(ids(0..39), saved.take(40).map { it.id })
        assertEquals(42, saved.size)
    }

    @Test
    fun clearingAfterOpenDropsTheOlderMessagesToo() {
        val chat = ProjectChat(title = "Long", updatedAtMillis = 2L)
        val project = savedProject("Clear", chat to messages(0..39))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(project)
        io.runAll()

        viewModel.sendPrompt("/clear")
        viewModel.persistNow()

        assertEquals(1, AppPreferences(app).loadMessages(project.id, chat.id).size)
        assertEquals(0, viewModel.state.value.olderMessageCount)
    }

    @Test
    fun switchingChatsLoadsTheNewestMessagesOffTheMainThread() {
        val first = ProjectChat(title = "First", updatedAtMillis = 2L)
        val second = ProjectChat(title = "Second", updatedAtMillis = 1L)
        val project = savedProject("Switch", first to messages(0..3), second to messages(100..139))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(project)
        io.runAll()

        viewModel.switchChat(second.id)

        assertTrue(viewModel.state.value.chatLoading)
        assertNull("no chat is active, so nothing is saved over one, until its messages load", viewModel.state.value.activeChatId)
        io.runAll()
        assertEquals(second.id, viewModel.state.value.activeChatId)
        assertEquals(ids(115..139), viewModel.state.value.messages.map { it.id })
        assertEquals(15, viewModel.state.value.olderMessageCount)
    }

    @Test
    fun aNewChatStartsWithNoOlderMessages() {
        val chat = ProjectChat(title = "Long", updatedAtMillis = 2L)
        val project = savedProject("New chat", chat to messages(0..39))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(project)
        io.runAll()

        viewModel.createChat()
        viewModel.persistNow()

        val created = checkNotNull(viewModel.state.value.activeChatId)
        assertEquals(0, viewModel.state.value.olderMessageCount)
        assertEquals(1, AppPreferences(app).loadMessages(project.id, created).size)
        assertEquals(40, AppPreferences(app).loadMessages(project.id, chat.id).size)
    }
}
