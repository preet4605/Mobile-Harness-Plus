package com.jarves.mh.ui

import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** M7: a symlinked folder must not be suggested as the project root, even when it points outside the workspace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelNestedRootTest {
    private val app = robolectricApplication()

    @Test
    fun suggestedRootSkipsASymlinkedFolderThatPointsOutsideTheWorkspace() {
        val project = testProject("Linked")
        val outside = File(app.cacheDir, "outside-${UUID.randomUUID()}")
            .apply { mkdirs() }
            .also { File(it, "index.js").writeText("outside") }
        val workspace = app.workspaceDir(project).apply { mkdirs() }
        Files.createSymbolicLink(File(workspace, "linked").toPath(), outside.toPath())
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }

        viewModel.refreshProjectFiles()
        io.runAll()

        assertNull(viewModel.state.value.suggestedProjectRoot)
    }

    @Test
    fun suggestedRootStillFindsARealNestedFolder() {
        val project = testProject("Nested")
        app.writeWorkspaceFile(project, "app/index.js", "inside")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }

        viewModel.refreshProjectFiles()
        io.runAll()

        assertEquals("app", viewModel.state.value.suggestedProjectRoot)
    }
}
