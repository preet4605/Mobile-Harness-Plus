package com.jarves.mh.ui

import com.jarves.mh.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Editing the open file: draft, save with the file's own line breaks, conflicts, and what can't be edited. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelFileEditTest {
    private val app = robolectricApplication()

    private fun entry(path: String) = WorkspaceEntry(path = path, name = path.substringAfterLast('/'), isDirectory = false, depth = 0)

    private fun open(project: com.jarves.mh.model.Project, path: String, io: ManualDispatcher): MainViewModel {
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }
        viewModel.openFile(entry(path))
        io.runAll()
        return viewModel
    }

    @Test
    fun savingWritesTheDraftInTheFilesLineBreakStyle() {
        val project = testProject("Edit")
        val file = app.writeWorkspaceFile(project, "a.txt", "one\r\ntwo\r\n")
        val io = ManualDispatcher()
        val viewModel = open(project, "a.txt", io)

        viewModel.beginFileEdit()
        assertEquals("one\ntwo\n", viewModel.state.value.openedFileEdit?.draft)
        viewModel.updateFileDraft("one\ntwo\nthree")
        assertTrue(viewModel.state.value.openedFileEdit!!.dirty)
        viewModel.saveOpenedFile()
        io.runAll()

        assertEquals("one\r\ntwo\r\nthree\r\n", file.readText())
        assertNull(viewModel.state.value.openedFileEdit)
        assertEquals("one\r\ntwo\r\nthree\r\n", viewModel.state.value.openedFileContent)
    }

    @Test
    fun anOutsideChangeIsAConflictAndIsNotOverwritten() {
        val project = testProject("Conflict")
        val file = app.writeWorkspaceFile(project, "a.txt", "old")
        val io = ManualDispatcher()
        val viewModel = open(project, "a.txt", io)

        viewModel.beginFileEdit()
        viewModel.updateFileDraft("mine")
        file.writeText("theirs")
        viewModel.saveOpenedFile()
        io.runAll()

        assertEquals("theirs", file.readText())
        assertTrue(viewModel.state.value.openedFileEdit!!.conflict)
        viewModel.reloadOpenedFile()
        io.runAll()
        assertEquals("theirs", viewModel.state.value.openedFileContent)
        assertNull(viewModel.state.value.openedFileEdit)
    }

    @Test
    fun editingIsRefusedWhileATaskRuns() {
        val project = testProject("Busy")
        app.writeWorkspaceFile(project, "a.txt", "text")
        val io = ManualDispatcher()
        val viewModel = open(project, "a.txt", io)
        viewModel.updateStateForTest { it.copy(isRunning = true) }

        viewModel.beginFileEdit()

        assertNull(viewModel.state.value.openedFileEdit)
    }

    @Test
    fun binaryFilesCantBeEdited() {
        val project = testProject("Binary")
        app.workspaceDir(project).mkdirs()
        java.io.File(app.workspaceDir(project), "b.bin").writeBytes(byteArrayOf(1, 0, 2))
        val io = ManualDispatcher()
        val viewModel = open(project, "b.bin", io)

        viewModel.beginFileEdit()

        assertEquals(FileReadOnlyReason.BINARY, viewModel.state.value.openedFileReadOnlyReason)
        assertNull(viewModel.state.value.openedFileEdit)
    }

    @Test
    fun largeFilesAreShownTruncatedAndReadOnly() {
        val project = testProject("Large")
        app.writeWorkspaceFile(project, "big.txt", "x".repeat(MAX_EDITABLE_FILE_BYTES + 10))
        val io = ManualDispatcher()
        val viewModel = open(project, "big.txt", io)

        viewModel.beginFileEdit()

        assertEquals(FileReadOnlyReason.TOO_LARGE, viewModel.state.value.openedFileReadOnlyReason)
        assertTrue(viewModel.state.value.openedFileContent!!.contains("[File truncated"))
        assertNull(viewModel.state.value.openedFileEdit)
    }

    @Test
    fun cancellingDropsTheDraftAndLeavesTheFileAlone() {
        val project = testProject("Cancel")
        val file = app.writeWorkspaceFile(project, "a.txt", "keep")
        val io = ManualDispatcher()
        val viewModel = open(project, "a.txt", io)

        viewModel.beginFileEdit()
        viewModel.updateFileDraft("changed")
        viewModel.cancelFileEdit()
        io.runAll()

        assertNull(viewModel.state.value.openedFileEdit)
        assertEquals("keep", file.readText())
        assertNotNull(viewModel.state.value.openedFileContent)
        assertFalse(viewModel.state.value.fileContentLoading)
    }
}
