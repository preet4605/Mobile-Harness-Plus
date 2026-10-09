package com.jarves.mh.ui

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ProjectChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F01: creating a chat must not rewrite a project's chat index before that index has loaded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelChatLoadingTest {
    private val app = robolectricApplication()

    private fun savedIndex(projectId: String): List<String> =
        AppPreferences(app).loadProjectChats(projectId).map { it.id }

    @Test
    fun createChatWhileTheProjectOpensKeepsTheSavedChatIndex() {
        val project = testProject("Opening")
        val saved = ProjectChat(title = "Saved chat")
        AppPreferences(app).saveProjectChats(project.id, listOf(saved))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)

        viewModel.openProject(project)
        assertTrue(viewModel.state.value.chatLoading)
        viewModel.createChat()

        assertEquals(listOf(saved.id), savedIndex(project.id))
        io.runAll()
        assertEquals(listOf(saved.id), viewModel.state.value.projectChats.map { it.id })
        assertEquals(saved.id, viewModel.state.value.activeChatId)
    }

    @Test
    fun createChatAfterAFailedOpenKeepsTheSavedChatIndex() {
        val project = testProject("Failed")
        val saved = ProjectChat(title = "Saved chat")
        AppPreferences(app).saveProjectChats(project.id, listOf(saved))
        val viewModel = MainViewModel(app, ManualDispatcher())
        // A failed open leaves the project selected, loading cleared, and no chats loaded.
        viewModel.updateStateForTest { it.copy(activeProject = project, chatLoading = false, projectChats = emptyList()) }

        viewModel.createChat()

        assertEquals(listOf(saved.id), savedIndex(project.id))
        assertNotNull(viewModel.state.value.toastMessage)
    }

    @Test
    fun createChatOnceLoadedAddsTheChatToTheSavedIndex() {
        val project = testProject("Loaded")
        val saved = ProjectChat(title = "Saved chat")
        AppPreferences(app).saveProjectChats(project.id, listOf(saved))
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(project)
        io.runAll()

        viewModel.createChat()

        val created = checkNotNull(viewModel.state.value.activeChatId)
        assertEquals(listOf(created, saved.id), savedIndex(project.id))
    }
}
