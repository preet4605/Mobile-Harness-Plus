package com.jarves.mh.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Creating, renaming and deleting from the Files tab stays inside the project and waits for running work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelFileManagementTest {
    private val app = robolectricApplication()

    private fun setUp(name: String): Triple<MainViewModel, ManualDispatcher, File> {
        val project = testProject(name)
        app.writeWorkspaceFile(project, "src/A.kt", "a")
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }
        return Triple(viewModel, io, app.workspaceDir(project).canonicalFile)
    }

    @Test
    fun createsFilesAndFoldersInTheChosenFolder() {
        val (viewModel, io, root) = setUp("Create")

        viewModel.createWorkspaceEntry("src", "B.kt", isDirectory = false)
        io.runAll()
        viewModel.createWorkspaceEntry("", "docs", isDirectory = true)
        io.runAll()

        assertTrue(File(root, "src/B.kt").isFile)
        assertTrue(File(root, "docs").isDirectory)
        assertFalse(viewModel.state.value.fileOperationRunning)
        assertTrue(viewModel.state.value.workspaceFiles.any { it.path == "src/B.kt" })
    }

    @Test
    fun creatingNeverReplacesAnExistingFile() {
        val (viewModel, io, root) = setUp("Exists")

        viewModel.createWorkspaceEntry("src", "A.kt", isDirectory = false)
        io.runAll()

        assertEquals("a", File(root, "src/A.kt").readText())
        assertEquals("A.kt already exists", viewModel.state.value.toastMessage)
    }

    @Test
    fun renamesInPlaceAndRefusesNamesThatLeaveTheFolder() {
        val (viewModel, io, root) = setUp("Rename")

        viewModel.renameWorkspaceEntry("src/A.kt", "../../escaped.kt")
        io.runAll()
        assertTrue(File(root, "src/A.kt").isFile)

        viewModel.renameWorkspaceEntry("src/A.kt", "Main.kt")
        io.runAll()
        assertFalse(File(root, "src/A.kt").exists())
        assertEquals("a", File(root, "src/Main.kt").readText())
    }

    @Test
    fun deleteRefusesPathsOutsideTheProjectAndProtectedOnes() {
        val (viewModel, io, root) = setUp("Delete")
        val outside = File(root.parentFile, "victim-${System.nanoTime()}").apply { writeText("keep") }
        File(root, ".git").mkdirs()
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

        listOf("../${outside.name}", "link", ".git", "", "src/../..").forEach { path ->
            viewModel.deleteWorkspaceEntry(path)
            io.runAll()
        }

        assertTrue(outside.exists())
        assertTrue(File(root, ".git").isDirectory)
        assertTrue(Files.isSymbolicLink(File(root, "link").toPath()))
        assertTrue(root.isDirectory)
        outside.delete()
    }

    @Test
    fun deletesAFolderWithEverythingInIt() {
        val (viewModel, io, root) = setUp("DeleteFolder")

        viewModel.deleteWorkspaceEntry("src")
        io.runAll()

        assertFalse(File(root, "src").exists())
        assertEquals("src deleted", viewModel.state.value.toastMessage)
    }

    @Test
    fun fileOperationsWaitForARunningTaskTerminalOrExport() {
        val (viewModel, io, root) = setUp("Busy")
        listOf<(AppUiState) -> AppUiState>(
            { it.copy(isRunning = true) },
            { it.copy(projectTerminalRunning = true) },
            { it.copy(projectExportRunning = true) },
        ).forEach { busy ->
            viewModel.updateStateForTest(busy)
            viewModel.deleteWorkspaceEntry("src/A.kt")
            io.runAll()
            assertTrue(File(root, "src/A.kt").isFile)
            assertEquals(WORKSPACE_BUSY_MESSAGE, viewModel.state.value.toastMessage)
            viewModel.updateStateForTest { it.copy(isRunning = false, projectTerminalRunning = false, projectExportRunning = false) }
        }
    }
}
