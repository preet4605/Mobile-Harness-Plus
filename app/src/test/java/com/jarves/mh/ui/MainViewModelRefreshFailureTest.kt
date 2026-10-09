package com.jarves.mh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F08: a failed refresh must clear the files spinner and be recorded, not escape viewModelScope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelRefreshFailureTest {
    private val app = robolectricApplication()

    @Test
    fun failedRefreshClearsFilesLoadingAndRecordsAToast() {
        // A root outside the workspace makes projectWorkspaceRoot throw.
        val project = testProject("Escaping", rootPath = "../outside")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }

        viewModel.refreshProjectFiles()
        io.runAll()

        assertFalse("the files spinner must stop after a failed refresh", viewModel.state.value.filesLoading)
        assertNotNull(viewModel.state.value.toastMessage)
    }
}
