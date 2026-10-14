package com.jarves.mh.runtime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AuthProcessCleanupTest {
    @Test fun `mask cleanup removes links but preserves their targets`() {
        val root = Files.createTempDirectory("auth-cleanup-fixture").toFile()
        try {
            val project = root.resolve("project").apply { mkdirs() }
            val original = project.resolve("chat.txt").apply { writeText("Keep project data") }
            val mask = root.resolve("mask").apply { mkdirs() }
            Files.createSymbolicLink(mask.resolve("linked-project").toPath(), project.toPath())
            deleteAuthDirectory(mask)
            assertFalse(mask.exists())
            assertEquals("Keep project data", original.readText())
        } finally { root.deleteRecursively() }
    }
}
