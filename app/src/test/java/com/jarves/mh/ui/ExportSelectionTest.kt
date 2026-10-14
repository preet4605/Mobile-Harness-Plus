package com.jarves.mh.ui

import com.jarves.mh.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportSelectionTest {
    private fun dir(path: String) = WorkspaceEntry(path, path.substringAfterLast('/'), true, path.count { it == '/' })
    private fun file(path: String) = WorkspaceEntry(path, path.substringAfterLast('/'), false, path.count { it == '/' })

    @Test
    fun theDefaultMatchesTheOneTapExport() {
        val selection = ExportSelection()
        assertTrue(selection.includes("src/A.kt"))
        assertTrue(selection.includes("src/generated/B.kt"))
        assertFalse(selection.includes("build"))
        assertFalse(selection.includes("app/build/outputs/x.apk"))
        assertFalse(selection.includes("web/.next/cache"))
    }

    @Test
    fun theLongestRuleWins() {
        val selection = ExportSelection()
            .toggled("src", isDirectory = true)
            .toggled("src/main/Keep.kt", isDirectory = false)
        assertFalse(selection.includes("src/Other.kt"))
        assertTrue(selection.includes("src/main/Keep.kt"))
        assertFalse(selection.includes("src/main/KeepNot.kt"))
        assertTrue(selection.includes("README.md"))
    }

    @Test
    fun aRuleDoesNotMatchASiblingWithTheSamePrefix() {
        val selection = ExportSelection().toggled("src", isDirectory = true)
        assertTrue(selection.includes("srcExtra/A.kt"))
    }

    @Test
    fun togglingAFolderDropsRulesBelowIt() {
        val selection = ExportSelection()
            .toggled("src/A.kt", isDirectory = false)
            .toggled("src", isDirectory = true)
        assertEquals(mapOf("src" to false), selection.overrides)
        val back = selection.toggled("src", isDirectory = true)
        assertTrue(back.includes("src/A.kt"))
    }

    @Test
    fun buildOutputCanBeChosenExplicitly() {
        val selection = ExportSelection().toggled("app/build", isDirectory = true)
        assertTrue(selection.includes("app/build/outputs/x.apk"))
        assertTrue(ExportSelection.All.includes("app/build/outputs/x.apk"))
    }

    @Test
    fun foldersAreEnteredWhenSomethingBelowIsChosen() {
        val selection = ExportSelection.None.toggled("app/src/A.kt", isDirectory = false)
        assertTrue(selection.canContainIncluded("app"))
        assertTrue(selection.canContainIncluded("app/src"))
        assertFalse(selection.canContainIncluded("docs"))
        assertFalse(selection.includes("app"))
        assertTrue(selection.includes("app/src/A.kt"))
    }

    @Test
    fun noneIncludesNothing() {
        assertFalse(ExportSelection.None.includes("README.md"))
        assertFalse(ExportSelection.None.canContainIncluded("src"))
    }

    @Test
    fun alwaysExcludedPathsCoverMetadataAndCaches() {
        listOf(".git/config", ".claude/settings.json", ".claude.json", "node_modules/x/index.js", "a/.gradle/c", "attachments/default/a.png")
            .forEach { assertTrue(it, isAlwaysExcludedFromExport(it)) }
        listOf("src/A.kt", "build/x", "attachments/notes.txt").forEach { assertFalse(it, isAlwaysExcludedFromExport(it)) }
    }

    @Test
    fun marksAggregateFilesIntoFolders() {
        val entries = listOf(dir("src"), file("src/A.kt"), file("src/B.kt"), dir("empty"), dir("lib"), file("lib/C.kt"), file("README.md"))
        val selection = ExportSelection().toggled("src/B.kt", isDirectory = false).toggled("lib", isDirectory = true)
        val marks = exportSelectionMarks(entries, selection)
        assertEquals(SelectionMark.SOME, marks["src"])
        assertEquals(SelectionMark.ALL, marks["src/A.kt"])
        assertEquals(SelectionMark.NONE, marks["src/B.kt"])
        assertEquals(SelectionMark.NONE, marks["lib"])
        assertEquals(SelectionMark.ALL, marks["empty"])
        assertEquals(SelectionMark.ALL, marks["README.md"])
    }
}
