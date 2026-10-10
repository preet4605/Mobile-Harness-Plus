package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MainViewModelTabCancellationTest {
    private val app = robolectricApplication()

    @Test
    fun tabChangeDropsTheOpeningShellAndAllowsReopening() {
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        val project = testProject("Opening")
        viewModel.openProject(project)
        assertTrue(viewModel.state.value.chatLoading)

        viewModel.cancelProjectOpening()
        assertFalse(viewModel.state.value.chatLoading)
        assertFalse(viewModel.state.value.workspaceVisible)
        assertNull(viewModel.state.value.activeProject)
        io.runAll()
        assertNull(viewModel.state.value.activeProject)
        assertTrue(viewModel.state.value.messages.isEmpty())

        viewModel.openProject(project)
        io.runAll()
        assertEquals(project.id, viewModel.state.value.activeProject?.id)
        assertFalse(viewModel.state.value.chatLoading)
        assertTrue(viewModel.state.value.projectChats.isNotEmpty())
    }

    @Test
    fun tabChangeDismissesReadOnlyLoadingAndPreservesTheRunningTask() {
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        val active = testProject("Active")
        viewModel.updateStateForTest { it.copy(activeProject = active, isRunning = true) }
        viewModel.openProject(testProject("Other"))

        viewModel.cancelProjectOpening()
        io.runAll()
        assertEquals(active.id, viewModel.state.value.activeProject?.id)
        assertTrue(viewModel.state.value.isRunning)
        assertNull(viewModel.state.value.readOnlyProject)
        assertFalse(viewModel.state.value.chatLoading)
    }

    @Test
    fun tabChangeAfterChatPublicationDropsThePendingDetails() {
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.openProject(testProject("Partial"))
        repeat(12) {
            if (viewModel.state.value.activeChatId == null) io.runNext()
        }
        assertTrue(viewModel.state.value.activeChatId != null)
        assertTrue(viewModel.state.value.chatLoading)

        viewModel.cancelProjectOpening()
        io.runAll()
        assertNull(viewModel.state.value.activeProject)
        assertNull(viewModel.state.value.activeChatId)
        assertFalse(viewModel.state.value.chatLoading)
        assertFalse(viewModel.state.value.filesLoading)
    }

    @Test
    fun tabChangeDoesNotClearAnAlreadyLoadedProject() {
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        val project = testProject("Ready")
        viewModel.openProject(project)
        io.runAll()
        assertFalse(viewModel.state.value.chatLoading)
        val before = viewModel.state.value

        viewModel.cancelProjectOpening()
        assertEquals(before, viewModel.state.value)
    }
}
