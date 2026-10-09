package com.jarves.mh.ui

import com.jarves.mh.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F12 and M6: a late file read must not publish into a newer viewer, a closed viewer, or a
 * viewer that belongs to another project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelFileViewerTest {
    private val app = robolectricApplication()

    private fun entry(path: String) = WorkspaceEntry(path = path, name = path, isDirectory = false, depth = 0)

    private fun viewModelWithFiles(io: ManualDispatcher, project: com.jarves.mh.model.Project): MainViewModel {
        app.writeWorkspaceFile(project, "a.txt", "alpha")
        app.writeWorkspaceFile(project, "b.txt", "beta")
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }
        return viewModel
    }

    @Test
    fun lateReadForAnEarlierFileDoesNotPublishIntoTheNewerViewer() {
        val io = ManualDispatcher()
        val viewModel = viewModelWithFiles(io, testProject("Viewer"))

        viewModel.openFile(entry("a.txt"))
        viewModel.openFile(entry("b.txt"))
        // The read for a.txt finishes after b.txt was requested.
        io.runNext()

        assertNotEquals("alpha", viewModel.state.value.openedFileContent)
        io.runAll()
        assertEquals("b.txt", viewModel.state.value.openedFilePath)
        assertEquals("beta", viewModel.state.value.openedFileContent)
    }

    @Test
    fun closingTheViewerDuringARead_keepsItClosed() {
        val io = ManualDispatcher()
        val viewModel = viewModelWithFiles(io, testProject("Closed"))

        viewModel.openFile(entry("a.txt"))
        viewModel.closeFile()
        io.runAll()

        assertNull(viewModel.state.value.openedFilePath)
        assertNull(viewModel.state.value.openedFileContent)
        assertFalse(viewModel.state.value.fileContentLoading)
    }

    @Test
    fun closingTheProjectClearsTheOpenViewer() {
        val io = ManualDispatcher()
        val viewModel = viewModelWithFiles(io, testProject("Closing"))
        viewModel.openFile(entry("a.txt"))
        io.runAll()
        assertEquals("alpha", viewModel.state.value.openedFileContent)

        viewModel.closeProject()

        assertNull(viewModel.state.value.openedFilePath)
        assertNull(viewModel.state.value.openedFileContent)
    }
}
