package com.jarves.mh.ui

import com.jarves.mh.model.WorkspaceEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M2: a file read that fails must be shown in the viewer, not escape viewModelScope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelFileReadFailureTest {
    private val app = robolectricApplication()

    @Test
    fun unsafeProjectRootIsRecordedInTheViewerInsteadOfEscaping() {
        // A root outside the workspace makes projectWorkspaceRoot throw.
        val project = testProject("Unsafe", rootPath = "../outside")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }

        viewModel.openFile(WorkspaceEntry(path = "a.txt", name = "a.txt", isDirectory = false, depth = 0))
        io.runAll()

        assertFalse(viewModel.state.value.fileContentLoading)
        assertTrue(viewModel.state.value.openedFileContent.orEmpty().startsWith("Could not read file"))
    }
}
