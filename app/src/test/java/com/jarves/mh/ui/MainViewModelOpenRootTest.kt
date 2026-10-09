package com.jarves.mh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F09: opening a project must not create or configure its workspace on the calling thread. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelOpenRootTest {
    private val app = robolectricApplication()

    @Test
    fun openingAProjectDoesNotTouchItsWorkspaceBeforeTheIoWorkRuns() {
        val project = testProject("Opened")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)

        viewModel.openProject(project)

        assertFalse("the workspace must not be created on the calling thread", app.workspaceDir(project).exists())
        io.runAll()
        assertTrue(app.workspaceDir(project).isDirectory)
        assertEquals(project.id, viewModel.state.value.activeProject?.id)
        assertFalse(viewModel.state.value.chatLoading)
    }
}
