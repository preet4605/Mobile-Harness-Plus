package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorkspaceIgnoreRulesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val rules = WorkspaceIgnoreRules.parse(
        """
        # Generated
        .gradle-home/
        bin/
        /out
        app/checkpoints/
        /.review/old-docs/
        .externalNativeBuild
        **/generated-*/
        /docs/*
        !/docs/keep/
        *.log
        local.properties
        """.trimIndent(),
    )

    @Test
    fun `directory patterns match by name, by anchored path and with globs`() {
        assertTrue(rules.isIgnoredDirectory(".gradle-home"))
        assertTrue(rules.isIgnoredDirectory("tools/bin"))
        assertTrue(rules.isIgnoredDirectory("out"))
        assertFalse("an anchored pattern matches only at the root", rules.isIgnoredDirectory("src/out"))
        assertTrue(rules.isIgnoredDirectory("app/checkpoints"))
        assertFalse(rules.isIgnoredDirectory("lib/app/checkpoints"))
        assertTrue(rules.isIgnoredDirectory(".review/old-docs"))
        assertFalse(rules.isIgnoredDirectory(".review"))
        assertTrue(rules.isIgnoredDirectory(".externalNativeBuild"))
        assertTrue(rules.isIgnoredDirectory("a/b/generated-sources"))
        assertTrue(rules.isIgnoredDirectory("docs/archive"))
        assertFalse("the last matching rule wins", rules.isIgnoredDirectory("docs/keep"))
        assertFalse(rules.isIgnoredDirectory("docs"))
        assertFalse(rules.isIgnoredDirectory("src"))
    }

    @Test
    fun `files are judged only by the folders they are in`() {
        assertTrue(rules.isInIgnoredDirectory(".gradle-home/caches/8.9/a.bin"))
        assertTrue(rules.isInIgnoredDirectory("app/checkpoints/p/latest/project/x.kt"))
        assertFalse("ignored single files stay protected", rules.isInIgnoredDirectory("local.properties"))
        assertFalse(rules.isInIgnoredDirectory("logs/build.log"))
        assertFalse(rules.isInIgnoredDirectory("src/main/App.kt"))
        assertFalse(rules.isInIgnoredDirectory(".gradle-home"))
    }

    @Test
    fun `missing or oversized gitignore ignores nothing`() {
        val root = tempFolder.newFolder("root")
        assertTrue(WorkspaceIgnoreRules.load(root).isEmpty)
        File(root, ".gitignore").writeText("x/\n".repeat(40_000))
        assertTrue(WorkspaceIgnoreRules.load(root).isEmpty)
        assertEquals(false, WorkspaceIgnoreRules.parse("# only a comment\n\n").isInIgnoredDirectory("x/y"))
    }
}
