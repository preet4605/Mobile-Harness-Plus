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
}
