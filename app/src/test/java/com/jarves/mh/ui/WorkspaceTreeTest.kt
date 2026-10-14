package com.jarves.mh.ui

import com.jarves.mh.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceTreeTest {
    private fun dir(path: String) = WorkspaceEntry(path, path.substringAfterLast('/'), true, path.count { it == '/' })
    private fun file(path: String) = WorkspaceEntry(path, path.substringAfterLast('/'), false, path.count { it == '/' })

    @Test
    fun childrenFollowTheirFolderWithFoldersFirst() {
        val sorted = sortWorkspaceEntries(
            listOf(file("b.txt"), file("src/z.kt"), dir("src"), dir("src/main"), file("src/main/A.kt"), file("README.md")),
        )

        assertEquals(
            listOf("src", "src/main", "src/main/A.kt", "src/z.kt", "b.txt", "README.md"),
            sorted.map { it.path },
        )
    }

    @Test
    fun entriesWithoutAListedParentAreKept() {
        val sorted = sortWorkspaceEntries(listOf(file("a.txt"), file("missing/child.txt")))

        assertEquals(listOf("a.txt", "missing/child.txt"), sorted.map { it.path })
    }
}
