package com.jarves.mh.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F02: a file refresh must publish only for the project root that is still active. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelStaleRefreshTest {
    private val app = robolectricApplication()

    @Test
    fun refreshStartedForAnOldRootPublishesNothingAfterTheRootChanges() {
        val project = testProject("Rooted")
        app.writeWorkspaceFile(project, "old.txt", "from the old root")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }
        viewModel.refreshProjectFiles()

        // Same project id, different root: the queued refresh still holds the old root.
        viewModel.updateStateForTest { it.copy(activeProject = project.copy(rootPath = "nested")) }
        io.runAll()

        assertTrue(viewModel.state.value.workspaceFiles.none { it.name == "old.txt" })
    }

    @Test
    fun refreshPublishesTheFilesOfTheActiveProject() {
        val project = testProject("Current")
        app.writeWorkspaceFile(project, "current.txt", "current")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }

        viewModel.refreshProjectFiles()
        io.runAll()

        assertTrue(viewModel.state.value.workspaceFiles.any { it.name == "current.txt" })
        assertTrue(!viewModel.state.value.filesLoading)
    }
}
