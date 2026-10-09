package com.jarves.mh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F07: a read-only load the user dismissed must not keep loading state or publish late. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelReadOnlyLoadTest {
    private val app = robolectricApplication()

    @Test
    fun closingTheReadOnlyViewWhileItLoadsReleasesInputAndDropsTheLateResult() {
        val active = testProject("Active")
        val other = testProject("Other")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        // A running task makes opening another project a read-only load.
        viewModel.updateStateForTest { it.copy(activeProject = active, isRunning = true) }

        viewModel.openProject(other)
        assertTrue(viewModel.state.value.chatLoading)
        viewModel.closeReadOnlyProject()

        assertFalse("closing the view must release the loading flag", viewModel.state.value.chatLoading)
        io.runAll()
        assertNull(viewModel.state.value.readOnlyChatId)
        assertTrue(viewModel.state.value.readOnlyProjectChats.isEmpty())
    }

    @Test
    fun openingTheActiveProjectDuringAReadOnlyLoadDropsTheLateResult() {
        val active = testProject("Active")
        val other = testProject("Other")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = active, isRunning = true) }

        viewModel.openProject(other)
        // Selecting the active project dismisses the read-only view through the same-project shortcut.
        viewModel.openProject(active)

        assertNull(viewModel.state.value.readOnlyProject)
        assertFalse("the shortcut must release the loading flag", viewModel.state.value.chatLoading)
        io.runAll()
        assertNull(viewModel.state.value.readOnlyChatId)
        assertNull(viewModel.state.value.readOnlyProject)
        assertFalse(viewModel.state.value.chatLoading)
    }
}
