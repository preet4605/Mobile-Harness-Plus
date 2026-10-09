package com.jarves.mh.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptTailReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun readsLatestTailAndNeverReadsOutsideAllowedRoots() {
        val root = temporary.newFolder(); val file = File(root, "log.jsonl")
        file.writeText("old\n".repeat(30000) + "latest\n")
        val lines = TranscriptTailReader.read(file, listOf(root))
        assertTrue(lines.size <= 500); assertTrue(lines.contains("latest"))
        file.appendText("updated\n")
        assertTrue(TranscriptTailReader.read(file, listOf(root)).contains("updated"))
        assertTrue(TranscriptTailReader.read(file, listOf(temporary.newFolder())).isEmpty())
    }
    @Test fun mapsGuestWorkspaceAndRefusesSymlinkEscape() {
        val root = temporary.newFolder(); val workspace = temporary.newFolder()
        val (file, _) = TranscriptTailReader.resolve("/workspace/demo/log.jsonl", root, workspace, "demo", "/root/.claude")!!
        assertEquals(File(workspace, "log.jsonl"), file)
        val outside = temporary.newFile().apply { writeText("private") }
        val symlink = File(workspace, "link")
        java.nio.file.Files.createSymbolicLink(symlink.toPath(), outside.toPath())
        assertTrue(TranscriptTailReader.read(symlink, listOf(workspace)).isEmpty())
    }
}
