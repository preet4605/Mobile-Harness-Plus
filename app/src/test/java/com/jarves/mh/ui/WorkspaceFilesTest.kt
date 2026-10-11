package com.jarves.mh.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    // ---- Decoding for the viewer ----

    @Test
    fun validUtf8IsEditable() {
        val decoded = decodeFileForViewer("héllo\n".toByteArray(), truncated = false)
        assertEquals("héllo\n", decoded.text)
        assertNull(decoded.readOnlyReason)
    }

    @Test
    fun nulBytesMeanBinaryAndShowNothing() {
        val decoded = decodeFileForViewer(byteArrayOf(0x50, 0x4b, 0x00, 0x03), truncated = false)
        assertNull(decoded.text)
        assertEquals(FileReadOnlyReason.BINARY, decoded.readOnlyReason)
    }

    @Test
    fun invalidUtf8IsShownButReadOnly() {
        val decoded = decodeFileForViewer(byteArrayOf(0x61, 0xC3.toByte(), 0x28), truncated = false)
        assertNotNull(decoded.text)
        assertEquals(FileReadOnlyReason.INVALID_UTF8, decoded.readOnlyReason)
    }

    @Test
    fun truncatedFilesAreReadOnly() {
        val decoded = decodeFileForViewer("abc".toByteArray(), truncated = true)
        assertEquals("abc", decoded.text)
        assertEquals(FileReadOnlyReason.TOO_LARGE, decoded.readOnlyReason)
    }

    @Test
    fun readFilePrefixReportsWhetherMoreRemains() {
        val file = temp.newFile("big.txt").apply { writeText("x".repeat(10)) }
        val (head, more) = readFilePrefix(file, 4)
        assertEquals("xxxx", String(head))
        assertTrue(more)
        val (all, none) = readFilePrefix(file, 10)
        assertEquals(10, all.size)
        assertFalse(none)
    }

    // ---- Saving keeps the file's style ----

    @Test
    fun crlfFilesAreSavedWithCrlf() {
        val original = "a\r\nb\r\n"
        val draft = editableText(original) + "c\n"
        assertEquals("a\r\nb\r\nc\r\n", textForSave(draft, original))
    }

    @Test
    fun lfFilesStayLf() {
        assertEquals("a\nb\n", textForSave("a\nb\n", "a\n"))
    }

    @Test
    fun aLostFinalNewlineIsRestored() {
        assertEquals("a\nb\n", textForSave("a\nb", "a\n"))
    }

    @Test
    fun noFinalNewlineIsAddedWhenTheOriginalHadNone() {
        assertEquals("a\nb", textForSave("a\nb", "a"))
    }

    @Test
    fun emptyDraftStaysEmpty() {
        assertEquals("", textForSave("", "a\n"))
    }

    @Test
    fun atomicWriteReplacesContentsAndLeavesNoTemporaryFile() {
        val folder = temp.newFolder("w")
        val file = File(folder, "a.sh").apply { writeText("old"); setExecutable(true) }
        writeFileAtomically(file, "new".toByteArray())
        assertEquals("new", file.readText())
        assertTrue(file.canExecute())
        assertEquals(listOf("a.sh"), folder.list()!!.toList())
    }

    @Test
    fun fingerprintsOfBytesAndFileAgree() {
        val file = temp.newFile("f.txt").apply { writeText("same") }
        assertEquals(sha256("same".toByteArray()), sha256(file))
    }

    // ---- The path guard ----

    private fun project(): File = temp.newFolder("project").apply {
        File(this, "src/main").mkdirs()
        File(this, "src/main/A.kt").writeText("a")
        File(this, ".git").mkdirs()
        File(this, ".claude").mkdirs()
    }

    @Test
    fun guardResolvesPathsInsideTheProject() {
        val root = project()
        assertEquals(File(root, "src/main/A.kt").canonicalFile, resolveWorkspacePath(root, "src/main/A.kt"))
        assertEquals(File(root, "src/new.txt").canonicalFile, resolveWorkspacePath(root, "src/new.txt"))
    }

    @Test
    fun guardRefusesTraversal() {
        val root = project()
        listOf("../x", "src/../../x", "src/./main", "src//main", "..", ".", "src\\..\\..", "a\u0000b").forEach { path ->
            assertNull(path, resolveWorkspacePath(root, path))
        }
    }

    @Test
    fun guardReturnsTheRootOnlyWhenAllowed() {
        val root = project()
        assertNull(resolveWorkspacePath(root, ""))
        assertNull(resolveWorkspacePath(root, "/"))
        assertEquals(root.canonicalFile, resolveWorkspacePath(root, "", allowRoot = true))
    }

    @Test
    fun guardRefusesProtectedPaths() {
        val root = project()
        listOf(".git", ".git/config", ".claude", ".claude/settings.json", ".claude.json").forEach { path ->
            assertNull(path, resolveWorkspacePath(root, path))
        }
        // Only the project's own .git is protected, as in the Files tab.
        assertNotNull(resolveWorkspacePath(root, "src/.gitignore"))
    }

    @Test
    fun guardRefusesSymlinksAnywhereOnThePath() {
        val root = project()
        val outside = temp.newFolder("outside").apply { File(this, "secret.txt").writeText("s") }
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "src/inner").toPath(), File(root, "src/main").toPath())
        Files.createSymbolicLink(File(root, "file-link").toPath(), File(root, "src/main/A.kt").toPath())
        assertNull(resolveWorkspacePath(root, "link"))
        assertNull(resolveWorkspacePath(root, "link/secret.txt"))
        assertNull(resolveWorkspacePath(root, "src/inner/A.kt"))
        assertNull(resolveWorkspacePath(root, "file-link"))
    }

    // ---- Names ----

    @Test
    fun ordinaryNamesAreAccepted() {
        listOf("a.txt", ".env", "My File (2).md", "ünïcødé", "x".repeat(255)).forEach { name ->
            assertNull(name, entryNameProblem(name))
        }
    }

    @Test
    fun unusableNamesAreRefused() {
        listOf("", "   ", ".", "..", "a/b", "a\\b", "../x", "a\u0000b", "a\nb", " lead", "trail ", "x".repeat(256), "é".repeat(128))
            .forEach { name -> assertNotNull(name, entryNameProblem(name)) }
    }

    @Test
    fun uniqueChildAddsASuffixOnAClash() {
        val folder = temp.newFolder("u")
        File(folder, "a.txt").writeText("1")
        File(folder, "a-2.txt").writeText("2")
        Files.createSymbolicLink(File(folder, "dangling").toPath(), File(folder, "missing").toPath())
        assertEquals("a-3.txt", uniqueChild(folder, "a.txt").name)
        assertEquals("b.txt", uniqueChild(folder, "b.txt").name)
        assertEquals("dangling-2", uniqueChild(folder, "dangling").name)
        assertEquals(".env", uniqueChild(folder, ".env").name)
    }

    // ---- Deleting ----

    @Test
    fun deletingAFolderRemovesLinksButNotWhatTheyPointTo() {
        val root = project()
        val outside = temp.newFolder("outside")
        val secret = File(outside, "secret.txt").apply { writeText("keep") }
        val doomed = File(root, "doomed").apply { mkdirs() }
        File(doomed, "inner/file.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        Files.createSymbolicLink(File(doomed, "dir-link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(doomed, "file-link").toPath(), secret.toPath())

        deleteWorkspaceTree(doomed)

        assertFalse(doomed.exists())
        assertTrue(secret.exists())
        assertEquals("keep", secret.readText())
    }

    @Test
    fun deletingASingleFile() {
        val root = project()
        val file = File(root, "src/main/A.kt")
        deleteWorkspaceTree(file)
        assertFalse(file.exists())
        assertTrue(File(root, "src/main").isDirectory)
    }

    @Test
    fun guardWorksWhenTheRootIsReachedThroughASymlink() {
        val real = project()
        val alias = File(temp.root, "alias")
        Files.createSymbolicLink(alias.toPath(), real.toPath())
        assertEquals(File(real, "src/main/A.kt").canonicalFile, resolveWorkspacePath(alias, "src/main/A.kt"))
    }
}
