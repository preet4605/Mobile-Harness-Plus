package com.jarves.mh.runtime

import com.jarves.mh.model.DiffLineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class WorkspaceCheckpointsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `isInternalRuntimePath filters build gradle git and claude metadata`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder("checkpoints_base"))

        assertTrue(checkpoints.isInternalRuntimePath(".git/HEAD"))
        assertTrue(checkpoints.isInternalRuntimePath(".git/objects/pack/pack.idx"))
        assertTrue(checkpoints.isInternalRuntimePath(".gradle/caches/modules-2/modules-2.lock"))
        assertTrue(checkpoints.isInternalRuntimePath("app/build/outputs/apk/online/debug/app-online-debug.apk"))
        assertTrue(checkpoints.isInternalRuntimePath("build/classes/kotlin/main/App.class"))
        assertTrue(checkpoints.isInternalRuntimePath(".claude/config.json"))
        assertTrue(checkpoints.isInternalRuntimePath(".claude.json"))
        assertTrue(checkpoints.isInternalRuntimePath("node_modules/react/index.js"))
        assertTrue(checkpoints.isInternalRuntimePath("dist/bundle.tar.zst"))

        assertFalse(checkpoints.isInternalRuntimePath("app-online-debug.apk"))
        assertFalse(checkpoints.isInternalRuntimePath("mobile-harness-dev.apk"))
        assertFalse(checkpoints.isInternalRuntimePath("src/main/java/Main.kt"))
        assertFalse(checkpoints.isInternalRuntimePath("build.gradle.kts"))
    }

    @Test
    fun `isInternalRuntimePath keeps chat attachment folders out of checkpoints only`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder("checkpoints_attachments"))
        val chatId = "3f2b8c1e-7d4a-4b6e-9a1f-0c2d3e4f5a6b"

        assertTrue(checkpoints.isInternalRuntimePath("attachments/$chatId/report.pdf"))
        assertFalse(checkpoints.isInternalRuntimePath("attachments/notes.md"))
        assertFalse(checkpoints.isInternalRuntimePath("docs/attachments/$chatId/x.png"))
    }

    @Test
    fun `snapshot and changedFiles ignores build directory changes`() {
        val filesDir = tempFolder.newFolder("app_files")
        val checkpoints = WorkspaceCheckpoints(filesDir)

        val workspace = tempFolder.newFolder("test_workspace")
        val sourceFile = File(workspace, "Hello.kt").apply { writeText("fun main() = println(\"Hi\")") }
        val buildDir = File(workspace, "build/intermediates").apply { mkdirs() }
        File(buildDir, "temp.dex").writeBytes(byteArrayOf(1, 2, 3))

        val before = checkpoints.snapshot(workspace)
        assertTrue(before.containsKey("Hello.kt"))
        assertFalse("build/ files must not be part of snapshot", before.containsKey("build/intermediates/temp.dex"))

        // Add an APK in root and another file in build/
        val apkFile = File(workspace, "app-online-debug.apk").apply {
            writeBytes(ByteArray(1024) { 0 }) // null bytes -> binary
        }
        File(buildDir, "new_intermediate.class").writeBytes(byteArrayOf(4, 5, 6))

        val changed = checkpoints.changedFiles(workspace, before)
        assertEquals(listOf("app-online-debug.apk"), changed)
    }

    @Test
    fun `buildChangeDetails safely handles large APK files without OOM`() {
        val filesDir = tempFolder.newFolder("app_files_oom")
        val checkpoints = WorkspaceCheckpoints(filesDir)

        val workspace = tempFolder.newFolder("ws_apk")
        val apkName = "app-online-debug.apk"
        val apkFile = File(workspace, apkName)

        // Create a sparse 15 MB file simulating a large generated APK
        RandomAccessFile(apkFile, "rw").use { raf ->
            raf.setLength(15L * 1024L * 1024L)
        }

        val details = checkpoints.buildChangeDetails("proj-1", workspace, listOf(apkName))
        assertEquals(1, details.size)
        val item = details[0]
        assertEquals(apkName, item.path)
        assertTrue("APK must be marked as binary", item.binary)
        assertEquals(1, item.additions)
        assertEquals(0, item.deletions)
        assertEquals(1, item.diffLines.size)
        assertEquals(DiffLineType.INFO, item.diffLines[0].type)
        assertTrue(item.diffLines[0].text.contains("Binary file changed"))
        assertTrue(item.diffLines[0].text.contains("15.0 MB"))
    }

    @Test
    fun `buildChangeDetails handles regular text files correctly`() {
        val filesDir = tempFolder.newFolder("app_files_txt")
        val checkpoints = WorkspaceCheckpoints(filesDir)

        val workspace = tempFolder.newFolder("ws_txt")
        val txtFile = File(workspace, "README.md").apply {
            writeText("Line 1\nLine 2\n")
        }

        val details = checkpoints.buildChangeDetails("proj-2", workspace, listOf("README.md"))
        assertEquals(1, details.size)
        val item = details[0]
        assertEquals("README.md", item.path)
        assertFalse("README.md must not be binary", item.binary)
        assertEquals(2, item.additions)
        assertEquals(0, item.deletions)
    }

    @Test
    fun `buildChangeDetails never reads through a final symlink outside workspace`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        File(workspace, "notes.txt").writeText("inside baseline\n")
        checkpoints.createCheckpoint("project", workspace)
        val outside = tempFolder.newFile().apply { writeText("OUTSIDE SECRET\n") }
        File(workspace, "notes.txt").delete()
        java.nio.file.Files.createSymbolicLink(File(workspace, "notes.txt").toPath(), outside.toPath())

        val details = checkpoints.buildChangeDetails("project", workspace, listOf("notes.txt"))

        assertEquals(1, details.size)
        details[0].diffLines.forEach { line ->
            assertFalse("outside content reached the diff: ${line.text}", line.text.contains("OUTSIDE SECRET"))
        }
        // A link that replaced a tracked file reads as absent, the same as snapshot(), which skips links.
        assertEquals(0, details[0].additions)
        assertEquals(1, details[0].deletions)
    }

    @Test
    fun `TEST 1 create checkpoint with tag step-1 and verify it exists`() {
        val filesDir = tempFolder.newFolder("cp_t1")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t1")
        File(workspace, "Main.kt").writeText("fun main() {}")

        checkpoints.createCheckpoint("proj-p", workspace, "step-1")

        assertTrue(checkpoints.checkpointExists("proj-p", "step-1"))
        val checkpointDir = checkpoints.checkpointDir("proj-p", "step-1")
        assertTrue(File(checkpointDir, "project/Main.kt").isFile)
        assertEquals("fun main() {}", File(checkpointDir, "project/Main.kt").readText())
    }

    @Test
    fun `TEST 2 create step-1 and step-2 and verify both exist independently`() {
        val filesDir = tempFolder.newFolder("cp_t2")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t2")
        val file = File(workspace, "code.txt")

        file.writeText("version 1")
        checkpoints.createCheckpoint("proj-p", workspace, "step-1")

        file.writeText("version 2")
        checkpoints.createCheckpoint("proj-p", workspace, "step-2")

        assertTrue(checkpoints.checkpointExists("proj-p", "step-1"))
        assertTrue(checkpoints.checkpointExists("proj-p", "step-2"))

        val dir1 = checkpoints.checkpointDir("proj-p", "step-1")
        val dir2 = checkpoints.checkpointDir("proj-p", "step-2")

        assertEquals("version 1", File(dir1, "project/code.txt").readText())
        assertEquals("version 2", File(dir2, "project/code.txt").readText())
        val list = checkpoints.listCheckpoints("proj-p")
        assertTrue(list.contains("step-1"))
        assertTrue(list.contains("step-2"))
    }

    @Test
    fun `TEST 3 modify file after step-1 create step-2 modify again restore step-1`() {
        val filesDir = tempFolder.newFolder("cp_t3")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t3")
        val file = File(workspace, "tracked.txt")

        file.writeText("step 1 content")
        checkpoints.createCheckpoint("proj-p", workspace, "step-1")

        file.writeText("step 2 content")
        checkpoints.createCheckpoint("proj-p", workspace, "step-2")

        file.writeText("step 3 content")
        assertEquals("step 3 content", file.readText())

        val restored = checkpoints.restoreCheckpoint("proj-p", workspace, "step-1")
        assertTrue(restored)
        assertEquals("step 1 content", file.readText())
    }

    @Test
    fun `TEST 4 restore step-2 after creating both restores independently`() {
        val filesDir = tempFolder.newFolder("cp_t4")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t4")
        val file = File(workspace, "data.txt")

        file.writeText("step 1 state")
        checkpoints.createCheckpoint("proj-p", workspace, "step-1")

        file.writeText("step 2 state")
        checkpoints.createCheckpoint("proj-p", workspace, "step-2")

        file.writeText("uncommitted state")

        val restored = checkpoints.restoreCheckpoint("proj-p", workspace, "step-2")
        assertTrue(restored)
        assertEquals("step 2 state", file.readText())
    }

    @Test
    fun `TEST 5 verify restoring step-1 does not accidentally use step-2`() {
        val filesDir = tempFolder.newFolder("cp_t5")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t5")
        val file = File(workspace, "config.json")

        file.writeText("{\"step\": 1}")
        checkpoints.createCheckpoint("proj-p", workspace, "step-1")

        file.writeText("{\"step\": 2}")
        checkpoints.createCheckpoint("proj-p", workspace, "step-2")

        // First restore step-2
        checkpoints.restoreCheckpoint("proj-p", workspace, "step-2")
        assertEquals("{\"step\": 2}", file.readText())

        // Now restore step-1 and assert it is strictly step-1, not step-2
        val restored = checkpoints.restoreCheckpoint("proj-p", workspace, "step-1")
        assertTrue(restored)
        assertEquals("{\"step\": 1}", file.readText())
        assertFalse(file.readText().contains("\"step\": 2"))
    }

    @Test
    fun `TEST 6 verify invalid tags are rejected`() {
        val filesDir = tempFolder.newFolder("cp_t6")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t6")

        val invalidTags = listOf(
            "../step",
            "../../escape",
            "/absolute",
            "step/one",
            "",
            "   ",
            "step\u0000tag",
            "step\ntag",
            "step\ttag",
            "..",
            ".",
            "step\\backslash",
        )

        invalidTags.forEach { tag ->
            try {
                checkpoints.validateCheckpointTag(tag)
                org.junit.Assert.fail("Expected IllegalArgumentException for tag: '$tag'")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            try {
                checkpoints.checkpointDir("proj-p", tag)
                org.junit.Assert.fail("Expected IllegalArgumentException in checkpointDir for tag: '$tag'")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            try {
                checkpoints.createCheckpoint("proj-p", workspace, tag)
                org.junit.Assert.fail("Expected IllegalArgumentException in createCheckpoint for tag: '$tag'")
            } catch (e: IllegalArgumentException) {
                // Expected
            }

            try {
                checkpoints.restoreCheckpoint("proj-p", workspace, tag)
                org.junit.Assert.fail("Expected IllegalArgumentException in restoreCheckpoint for tag: '$tag'")
            } catch (e: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun `TEST 7 verify checkpoint storage retention preserves task baseline and newest two steps`() {
        val filesDir = tempFolder.newFolder("cp_t7")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t7")
        val file = File(workspace, "feature.kt")

        // 1. Create task-baseline
        file.writeText("// Baseline")
        checkpoints.createCheckpoint("proj-p", workspace, "task-baseline")
        assertTrue(checkpoints.checkpointExists("proj-p", "task-baseline"))

        // 2. Create step-1
        file.writeText("// Step 1")
        checkpoints.createCheckpoint("proj-p", workspace, "step-1")
        assertTrue(checkpoints.checkpointExists("proj-p", "step-1"))

        // 3. Create step-2
        file.writeText("// Step 2")
        checkpoints.createCheckpoint("proj-p", workspace, "step-2")
        assertTrue(checkpoints.checkpointExists("proj-p", "step-1"))
        assertTrue(checkpoints.checkpointExists("proj-p", "step-2"))

        // 4. Create step-3 (total 3 step checkpoints; max retention = 2)
        file.writeText("// Step 3")
        checkpoints.createCheckpoint("proj-p", workspace, "step-3")

        // Task baseline MUST remain intact
        assertTrue("task-baseline must remain", checkpoints.checkpointExists("proj-p", "task-baseline"))
        assertEquals("// Baseline", File(checkpoints.checkpointDir("proj-p", "task-baseline"), "project/feature.kt").readText())

        // Newest two step checkpoints (step-2 and step-3) MUST remain
        assertTrue("step-2 must remain", checkpoints.checkpointExists("proj-p", "step-2"))
        assertTrue("step-3 must remain", checkpoints.checkpointExists("proj-p", "step-3"))
        assertEquals("// Step 2", File(checkpoints.checkpointDir("proj-p", "step-2"), "project/feature.kt").readText())
        assertEquals("// Step 3", File(checkpoints.checkpointDir("proj-p", "step-3"), "project/feature.kt").readText())

        // Older step checkpoint (step-1) MUST be purged
        assertFalse("step-1 must be purged", checkpoints.checkpointExists("proj-p", "step-1"))
        assertFalse(checkpoints.checkpointDir("proj-p", "step-1").exists())

        // 5. Create step-4: step-2 should be purged, step-3 and step-4 remain, baseline remains
        file.writeText("// Step 4")
        checkpoints.createCheckpoint("proj-p", workspace, "step-4")
        assertTrue("task-baseline must remain after step-4", checkpoints.checkpointExists("proj-p", "task-baseline"))
        assertFalse("step-2 must be purged after step-4", checkpoints.checkpointExists("proj-p", "step-2"))
        assertTrue("step-3 must remain after step-4", checkpoints.checkpointExists("proj-p", "step-3"))
        assertTrue("step-4 must remain after step-4", checkpoints.checkpointExists("proj-p", "step-4"))
    }

    @Test
    fun `TEST 8 verify existing latest compatibility behavior`() {
        val filesDir = tempFolder.newFolder("cp_t8")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t8")
        val file = File(workspace, "Legacy.kt").apply { writeText("original content") }

        // Default 1-arg createCheckpoint writes to latest
        checkpoints.createCheckpoint("proj-p", workspace)
        val latestDir = checkpoints.checkpointDir("proj-p")
        assertEquals(File(filesDir, "checkpoints/proj-p/latest").canonicalPath, latestDir.canonicalPath)
        assertTrue(File(latestDir, "project/Legacy.kt").isFile)

        // Save changed paths on latest
        checkpoints.saveChangedPaths("proj-p", listOf("Legacy.kt"))
        assertEquals(listOf("Legacy.kt"), checkpoints.readChangedPaths("proj-p"))

        // When changes.json exists, re-creating latest does not overwrite baseline
        file.writeText("mutated content")
        checkpoints.createCheckpoint("proj-p", workspace)
        assertEquals("original content", File(latestDir, "project/Legacy.kt").readText())

        // Remove changed path deletes directory when empty
        checkpoints.removeChangedPath("proj-p", "Legacy.kt")
        assertFalse(latestDir.exists())
    }

    @Test
    fun `TEST 9 verify all restored paths remain inside workspace root`() {
        val filesDir = tempFolder.newFolder("cp_t9")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_t9")

        // Unsafe path via traversal is rejected by safeWorkspaceFile
        try {
            checkpoints.safeWorkspaceFile(workspace, "../escaped.txt")
            org.junit.Assert.fail("Expected IllegalArgumentException for relative traversal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("escapes project") == true)
        }

        try {
            checkpoints.safeWorkspaceFile(workspace, "/root/escaped.txt")
            org.junit.Assert.fail("Expected IllegalArgumentException for absolute path")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Unsafe workspace path") == true)
        }

        // Subdirectory restores remain inside workspace
        val subFile = File(workspace, "sub/dir/Valid.kt").apply {
            parentFile?.mkdirs()
            writeText("nested code")
        }
        checkpoints.createCheckpoint("proj-p", workspace, "step-1")
        subFile.writeText("changed nested code")

        val restored = checkpoints.restoreCheckpoint("proj-p", workspace, "step-1")
        assertTrue(restored)
        assertEquals("nested code", subFile.readText())
        assertTrue(subFile.canonicalFile.toPath().startsWith(workspace.canonicalFile.toPath()))
    }

    @Test
    fun `test CheckpointMetadata records fingerprints and changes accurately`() {
        val filesDir = tempFolder.newFolder("cp_meta")
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder("ws_meta")
        File(workspace, "A.txt").writeText("Alpha")
        File(workspace, "B.txt").writeText("Beta")

        checkpoints.createCheckpoint("proj-meta", workspace, "step-1")
        val metadata = checkpoints.readMetadata("proj-meta", "step-1")
        org.junit.Assert.assertNotNull(metadata)
        assertEquals("proj-meta", metadata?.projectId)
        assertEquals("step-1", metadata?.checkpointTag)
        assertEquals(listOf("A.txt", "B.txt"), metadata?.backedUpFiles?.sorted())
        assertTrue(metadata?.fingerprints?.containsKey("A.txt") == true)
        assertTrue(metadata?.fingerprints?.containsKey("B.txt") == true)

        checkpoints.saveChangedPaths("proj-meta", listOf("A.txt"), "step-1")
        val updated = checkpoints.readMetadata("proj-meta", "step-1")
        assertEquals(listOf("A.txt"), updated?.changes)
    }

    @Test
    fun `restore refuses oversized originals and keeps all changes pending`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        val original = File(workspace, "original.bin")
        RandomAccessFile(original, "rw").use { it.setLength(WorkspaceCheckpoints.MAX_CHECKPOINT_COPY_BYTES + 1) }
        checkpoints.createCheckpoint("project", workspace)
        original.writeText("changed original")
        val created = File(workspace, "new.txt").apply { writeText("new content") }
        checkpoints.saveChangedPaths("project", listOf("original.bin", "new.txt"))

        assertFalse(checkpoints.restoreCheckpoint("project", workspace))
        assertEquals("changed original", original.readText())
        assertTrue(created.isFile)
        assertEquals(listOf("new.txt", "original.bin"), checkpoints.readChangedPaths("project"))
    }

    @Test
    fun `restore refuses missing backups before touching other files`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        val first = File(workspace, "a.txt").apply { writeText("original a") }
        val second = File(workspace, "b.txt").apply { writeText("original b") }
        checkpoints.createCheckpoint("project", workspace)
        File(checkpoints.checkpointDir("project"), "project/b.txt").delete()
        first.writeText("changed a")
        second.writeText("changed b")
        checkpoints.saveChangedPaths("project", listOf("a.txt", "b.txt"))

        assertFalse(checkpoints.restoreCheckpoint("project", workspace))
        assertEquals("changed a", first.readText())
        assertEquals("changed b", second.readText())
    }

    @Test
    fun `restore refuses truncated backups`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        val original = File(workspace, "original.txt").apply { writeText("complete original") }
        checkpoints.createCheckpoint("project", workspace)
        File(checkpoints.checkpointDir("project"), "project/original.txt").writeText("partial")
        original.writeText("changed")
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        assertFalse(checkpoints.restoreCheckpoint("project", workspace))
        assertEquals("changed", original.readText())
    }

    @Test
    fun `legacy checkpoint cannot delete a file without a backup`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        val original = File(workspace, "original.txt").apply { writeText("keep me") }
        File(checkpoints.checkpointDir("project"), "project").mkdirs()
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        assertFalse(checkpoints.restoreCheckpoint("project", workspace))
        assertEquals("keep me", original.readText())
    }

    @Test
    fun `checkpoint restores originals and removes proven new files after store recreation`() {
        val filesDir = tempFolder.newFolder()
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = tempFolder.newFolder()
        val original = File(workspace, "original.txt").apply { writeText("baseline") }
        checkpoints.createCheckpoint("project", workspace)
        original.delete()
        val created = File(workspace, "new.txt").apply { writeText("created") }
        checkpoints.saveChangedPaths("project", listOf("original.txt", "new.txt"))

        assertTrue(WorkspaceCheckpoints(filesDir).restoreCheckpoint("project", workspace))
        assertEquals("baseline", original.readText())
        assertFalse(created.exists())
    }

    @Test
    fun `checkpoint refuses restore to a different workspace root`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        File(workspace, "original.txt").writeText("baseline")
        checkpoints.createCheckpoint("project", workspace)
        checkpoints.saveChangedPaths("project", listOf("original.txt"))
        val otherWorkspace = tempFolder.newFolder()
        val unrelated = File(otherWorkspace, "original.txt").apply { writeText("unrelated") }

        assertFalse(checkpoints.restoreCheckpoint("project", otherWorkspace))
        assertEquals("unrelated", unrelated.readText())
    }

    @Test
    fun `restore never follows a final symlink outside workspace`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        val original = File(workspace, "original.txt").apply { writeText("baseline") }
        checkpoints.createCheckpoint("project", workspace)
        val outside = tempFolder.newFile().apply { writeText("outside") }
        original.delete()
        java.nio.file.Files.createSymbolicLink(original.toPath(), outside.toPath())
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        assertFalse(checkpoints.restoreCheckpoint("project", workspace))
        assertEquals("outside", outside.readText())
    }

    @Test
    fun `single file Undo retains oversized pending original`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "large.bin")
        RandomAccessFile(original, "rw").use { it.setLength(WorkspaceCheckpoints.MAX_CHECKPOINT_COPY_BYTES + 1) }
        checkpoints.createCheckpoint("project", workspace)
        original.writeText("changed")
        checkpoints.saveChangedPaths("project", listOf("large.bin"))

        assertFalse(checkpoints.undoFileChange("project", "large.bin"))
        assertEquals("changed", original.readText())
        assertEquals(listOf("large.bin"), checkpoints.readChangedPaths("project"))
        assertTrue(checkpoints.checkpointExists("project"))
    }

    @Test
    fun `Undo all refuses incomplete backup and preserves pending baseline`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("baseline") }
        checkpoints.createCheckpoint("project", workspace)
        File(checkpoints.checkpointDir("project"), "project/original.txt").delete()
        original.writeText("changed")
        val created = File(workspace, "new.txt").apply { writeText("new") }
        checkpoints.saveChangedPaths("project", listOf("original.txt", "new.txt"))

        assertFalse(checkpoints.undoLastChanges("project"))
        assertEquals("changed", original.readText())
        assertTrue(created.isFile)
        assertEquals(listOf("new.txt", "original.txt"), checkpoints.readChangedPaths("project"))
    }

    @Test
    fun `nested root and Undo baseline survive store recreation and reconfiguration`() {
        val filesDir = tempFolder.newFolder()
        val checkpoints = WorkspaceCheckpoints(filesDir, rootPathResolver = { "apps/demo" })
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("baseline") }
        val enclosing = File(filesDir, "workspaces/project/original.txt").apply { writeText("enclosing") }
        checkpoints.createCheckpoint("project", workspace)
        original.writeText("changed")
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        val recreated = WorkspaceCheckpoints(filesDir, rootPathResolver = { "apps/demo" })
        recreated.configureProjectRoot("project", "apps/demo")
        assertEquals(workspace.canonicalFile, recreated.ensureWorkspace("project"))
        assertTrue(recreated.undoFileChange("project", "original.txt"))
        assertEquals("baseline", original.readText())
        assertEquals("enclosing", enclosing.readText())
        assertFalse(recreated.checkpointExists("project"))
    }

    @Test
    fun `malformed metadata cannot authorize deletion`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        checkpoints.createCheckpoint("project", workspace)
        val created = File(workspace, "new.txt").apply { writeText("new") }
        checkpoints.saveChangedPaths("project", listOf("new.txt"))
        File(checkpoints.checkpointDir("project"), "metadata.json").writeText("{truncated")

        assertFalse(checkpoints.undoLastChanges("project"))
        assertTrue(created.isFile)
        assertTrue(checkpoints.checkpointExists("project"))
    }

    @Test
    fun `disguised original path cannot be deleted as a new file`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "large.bin")
        RandomAccessFile(original, "rw").use { it.setLength(WorkspaceCheckpoints.MAX_CHECKPOINT_COPY_BYTES + 1) }
        checkpoints.createCheckpoint("project", workspace)
        original.writeText("changed")
        checkpoints.saveChangedPaths("project", listOf("./large.bin"))

        assertFalse(checkpoints.undoLastChanges("project"))
        assertEquals("changed", original.readText())
    }

    @Test
    fun `Undo all removes only proven new files and clears successful pending changes`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("baseline") }
        checkpoints.createCheckpoint("project", workspace)
        original.writeText("changed")
        val created = File(workspace, "new.txt").apply { writeText("new") }
        checkpoints.saveChangedPaths("project", listOf("original.txt", "new.txt"))

        assertTrue(checkpoints.undoLastChanges("project"))
        assertEquals("baseline", original.readText())
        assertFalse(created.exists())
        assertFalse(checkpoints.checkpointExists("project"))
    }

    @Test
    fun `interrupted checkpoint is not a legacy checkpoint or a usable recovery baseline`() {
        val filesDir = tempFolder.newFolder()
        val checkpoints = WorkspaceCheckpoints(filesDir)
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("current content") }
        val checkpoint = checkpoints.checkpointDir("project", "step-1")
        File(checkpoint, "project").mkdirs()
        File(checkpoint, "project/original.txt").writeText("incomplete backup")
        File(checkpoint, "incomplete").writeText("")

        val recreated = WorkspaceCheckpoints(filesDir)
        assertFalse(recreated.restoreCheckpoint("project", workspace, "step-1"))
        assertFalse(recreated.checkpointExists("project", "step-1"))
        assertTrue(recreated.listCheckpoints("project").isEmpty())
        assertEquals("current content", original.readText())
    }

    @Test
    fun `legacy checkpoint still restores an available original backup`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("changed") }
        val backup = File(checkpoints.checkpointDir("project"), "project").apply { mkdirs() }
        File(backup, "original.txt").writeText("baseline")
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        assertTrue(checkpoints.undoFileChange("project", "original.txt"))
        assertEquals("baseline", original.readText())
    }

    @Test
    fun `kept backup stays usable by a later Undo while other changes remain pending`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("original") }
        File(workspace, "other.txt").writeText("other baseline")
        checkpoints.createCheckpoint("project", workspace)
        checkpoints.saveChangedPaths("project", listOf("original.txt", "other.txt"))
        // Keep updates the baseline, then removes its pending path in each bridge.
        File(checkpoints.checkpointDir("project"), "project/original.txt").writeText("accepted content")
        checkpoints.removeChangedPath("project", "original.txt")
        original.writeText("later edit")
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        assertTrue(checkpoints.undoFileChange("project", "original.txt"))
        assertEquals("accepted content", original.readText())
        assertEquals(listOf("other.txt"), checkpoints.readChangedPaths("project"))
    }

    @Test
    fun `kept deletion becomes a proven absence for a later creation`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val original = File(workspace, "original.txt").apply { writeText("original") }
        File(workspace, "other.txt").writeText("other baseline")
        checkpoints.createCheckpoint("project", workspace)
        checkpoints.saveChangedPaths("project", listOf("original.txt", "other.txt"))
        original.delete()
        File(checkpoints.checkpointDir("project"), "project/original.txt").delete()
        checkpoints.removeChangedPath("project", "original.txt")
        original.writeText("later creation")
        checkpoints.saveChangedPaths("project", listOf("original.txt"))

        assertTrue(checkpoints.undoFileChange("project", "original.txt"))
        assertFalse(original.exists())
        assertEquals(listOf("other.txt"), checkpoints.readChangedPaths("project"))
    }

    @Test
    fun `checkpoint preserves case insensitive ignored directory behavior`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = tempFolder.newFolder()
        File(workspace, "Build/original.txt").apply { parentFile?.mkdirs(); writeText("generated") }
        checkpoints.createCheckpoint("project", workspace)

        assertFalse(File(checkpoints.checkpointDir("project"), "project/Build/original.txt").exists())
    }

    @Test
    fun `paths below an original skipped directory cannot be classified as new`() {
        val checkpoints = WorkspaceCheckpoints(tempFolder.newFolder())
        val workspace = checkpoints.ensureWorkspace("project")
        val outside = tempFolder.newFolder()
        File(outside, "original.txt").writeText("outside original")
        val linkedDirectory = File(workspace, "linked")
        java.nio.file.Files.createSymbolicLink(linkedDirectory.toPath(), outside.toPath())
        checkpoints.createCheckpoint("project", workspace)
        java.nio.file.Files.delete(linkedDirectory.toPath())
        linkedDirectory.mkdirs()
        val current = File(linkedDirectory, "original.txt").apply { writeText("current copy") }
        checkpoints.saveChangedPaths("project", listOf("linked/original.txt"))

        assertFalse(checkpoints.undoLastChanges("project"))
        assertEquals("current copy", current.readText())
        assertEquals("outside original", File(outside, "original.txt").readText())
    }

}
