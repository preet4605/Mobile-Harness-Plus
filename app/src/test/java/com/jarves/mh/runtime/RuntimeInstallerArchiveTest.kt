package com.jarves.mh.runtime

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuntimeInstallerArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val installer = RuntimeInstaller(RuntimeEnvironment.getApplication())

    // The destination already holds a link, as a tar that created one would leave it.
    // Creating the link directly keeps these tests independent of Os.symlink.

    @Test
    fun `rootfs regular entry replaces an existing link instead of writing through it`() {
        val outside = tempFolder.newFile("outside.txt").apply { writeText("outside") }
        val dest = tempFolder.newFolder("rootfs")
        Files.createSymbolicLink(File(dest, "x").toPath(), outside.toPath())
        val archive = tarGz { regularFile("x", "PWNED") }

        installer.extractRootfs(archive, dest)

        assertEquals("outside", outside.readText())
        assertFalse(Files.isSymbolicLink(File(dest, "x").toPath()))
        assertEquals("PWNED", File(dest, "x").readText())
    }

    @Test
    fun `rootfs hard link refuses a symlink source instead of copying what it points to`() {
        val outside = tempFolder.newFile("secret.txt").apply { writeText("OUTSIDE SECRET") }
        val dest = tempFolder.newFolder("rootfs-hardlink")
        Files.createSymbolicLink(File(dest, "escape").toPath(), outside.toPath())
        val archive = tarGz { hardLink("copy", "escape") }

        val failure = runCatching { installer.extractRootfs(archive, dest) }.exceptionOrNull()

        assertTrue("hard link to a symlink must be refused", failure is IllegalArgumentException)
        assertFalse(File(dest, "copy").exists())
    }

    @Test
    fun `rootfs hard link still copies a regular file inside the destination`() {
        val dest = tempFolder.newFolder("rootfs-hardlink-ok")
        val archive = tarGz {
            regularFile("lib/a.txt", "A")
            hardLink("lib/b.txt", "lib/a.txt")
        }

        installer.extractRootfs(archive, dest)

        assertEquals("A", File(dest, "lib/b.txt").readText())
    }

    @Test
    fun `node regular entry replaces an existing link instead of writing through it`() {
        val outside = tempFolder.newFile("node-outside.txt").apply { writeText("outside") }
        val dest = tempFolder.newFolder("node")
        Files.createSymbolicLink(File(dest, "x").toPath(), outside.toPath())
        val archive = tarGz { regularFile("node-v24.19.0/x", "PWNED") }

        installer.extractNodeArchive(archive, dest)

        assertEquals("outside", outside.readText())
        assertFalse(Files.isSymbolicLink(File(dest, "x").toPath()))
        assertEquals("PWNED", File(dest, "x").readText())
    }

    @Test
    fun `node hard link refuses a symlink source instead of copying what it points to`() {
        val outside = tempFolder.newFile("node-secret.txt").apply { writeText("OUTSIDE SECRET") }
        val dest = tempFolder.newFolder("node-hardlink")
        Files.createSymbolicLink(File(dest, "escape").toPath(), outside.toPath())
        val archive = tarGz { hardLink("node-v24.19.0/copy", "node-v24.19.0/escape") }

        val failure = runCatching { installer.extractNodeArchive(archive, dest) }.exceptionOrNull()

        assertTrue("hard link to a symlink must be refused", failure is IllegalArgumentException)
        assertFalse(File(dest, "copy").exists())
    }

    @Test
    fun `zip regular entry replaces an existing link instead of writing through it`() {
        val outside = tempFolder.newFile("zip-outside.txt").apply { writeText("outside") }
        val dest = tempFolder.newFolder("zip")
        Files.createSymbolicLink(File(dest, "x").toPath(), outside.toPath())
        val archive = tempFolder.newFile("archive.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("x"))
            zip.write("PWNED".toByteArray())
            zip.closeEntry()
        }

        installer.extractZipArchive(archive, dest)

        assertEquals("outside", outside.readText())
        assertFalse(Files.isSymbolicLink(File(dest, "x").toPath()))
        assertEquals("PWNED", File(dest, "x").readText())
    }

    private fun tarGz(entries: TarArchiveOutputStream.() -> Unit): File {
        val archive = tempFolder.newFile()
        TarArchiveOutputStream(GzipCompressorOutputStream(archive.outputStream())).use { tar -> tar.entries() }
        return archive
    }

    private fun TarArchiveOutputStream.regularFile(name: String, content: String) {
        val bytes = content.toByteArray()
        putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
        write(bytes)
        closeArchiveEntry()
    }

    private fun TarArchiveOutputStream.hardLink(name: String, source: String) {
        putArchiveEntry(TarArchiveEntry(name, TarConstants.LF_LINK).apply { linkName = source })
        closeArchiveEntry()
    }
}
