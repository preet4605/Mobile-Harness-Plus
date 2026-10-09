package com.jarves.mh.ui

import android.app.Application
import com.jarves.mh.model.Project
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.robolectric.RuntimeEnvironment

/**
 * Stands in for Dispatchers.IO in MainViewModel tests. Work waits in a queue until a test runs it,
 * so each test decides when file work finishes. Main continuations run inline on the Robolectric
 * test thread, so a finished task updates the state before the test continues.
 */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    /** Tasks that were dispatched and have not run yet. */
    val pendingCount: Int get() = queue.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    /** Runs the oldest queued task. Returns false when nothing is queued. */
    fun runNext(): Boolean {
        val task = queue.removeFirstOrNull() ?: return false
        task.run()
        return true
    }

    /** Runs queued tasks, including tasks queued while running, until none remain. */
    fun runAll() {
        while (runNext()) {
            // Each iteration runs one task.
        }
    }
}

internal fun robolectricApplication(): Application = RuntimeEnvironment.getApplication()

internal fun testProject(name: String, rootPath: String = ""): Project =
    Project(name = name, description = "", language = "Kotlin", rootPath = rootPath)

/** The workspace folder MainViewModel uses for [project]. */
internal fun Application.workspaceDir(project: Project): File = File(filesDir, "workspaces/${project.id}")

/** Writes a text file below the project's workspace root, creating folders as needed. */
internal fun Application.writeWorkspaceFile(project: Project, relativePath: String, text: String): File =
    File(workspaceDir(project), relativePath).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

/**
 * Sets UI state that a test needs in place before it drives the ViewModel, such as a selected
 * project or a running task. It writes the private state holder because the ViewModel has no
 * production entry point for these preconditions.
 */
@Suppress("UNCHECKED_CAST")
internal fun MainViewModel.updateStateForTest(transform: (AppUiState) -> AppUiState) {
    val field = MainViewModel::class.java.getDeclaredField("_state")
    field.isAccessible = true
    (field.get(this) as MutableStateFlow<AppUiState>).update(transform)
}
