package com.jarves.mh.ui

import android.net.Uri
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A ZIP export writes exactly the selected files, never caches or metadata. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelSelectiveExportTest {
    private val app = robolectricApplication()

    private fun export(selection: ExportSelection): Set<String> {
        val project = testProject("Zip")
        listOf("src/A.kt", "src/B.kt", "README.md", "build/out.txt", ".git/HEAD", "node_modules/x.js").forEach {
            app.writeWorkspaceFile(project, it, it)
        }
        val io = ManualDispatcher()
        val viewModel = MainViewModel(app, io)
        viewModel.updateStateForTest { it.copy(activeProject = project) }
        val zip = File(app.cacheDir, "export-${System.nanoTime()}.zip")
        viewModel.exportActiveProject(Uri.fromFile(zip), selection)
        io.runAll()
        assertFalse(viewModel.state.value.projectExportRunning)
        return ZipFile(zip).use { file -> file.entries().toList().filterNot { it.isDirectory }.map { it.name.substringAfter('/') }.toSet() }
    }

    @Test
    fun theDefaultSelectionExportsWhatTheOneTapExportDid() {
        assertEquals(setOf("src/A.kt", "src/B.kt", "README.md"), export(ExportSelection()))
    }

    @Test
    fun onlyTheChosenFilesAreExported() {
        val selection = ExportSelection.None
            .toggled("src/B.kt", isDirectory = false)
            .toggled("build", isDirectory = true)
            .toggled(".git", isDirectory = true)
        assertEquals(setOf("src/B.kt", "build/out.txt"), export(selection))
    }
}
