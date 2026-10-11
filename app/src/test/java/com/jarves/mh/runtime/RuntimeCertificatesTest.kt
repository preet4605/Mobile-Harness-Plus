package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeCertificatesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun root(): File = temp.newFolder().also { root ->
        File(root, "etc/ssl/certs/ca-certificates.crt").apply { parentFile!!.mkdirs(); writeText("public CA fixture") }
    }
    @Test fun missingDefaultPathsAreRepairedAndRepeatedRepairIsStable() {
        val root = root()
        assertTrue(RuntimeCertificates.repair(root))
        val cert = File(root, "usr/lib/ssl/cert.pem")
        assertEquals("public CA fixture", cert.readText())
        assertEquals(File(root, "etc/ssl/certs").canonicalFile, File(root, "usr/lib/ssl/certs").canonicalFile)
        val modified = cert.toPath().let { Files.getLastModifiedTime(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        assertTrue(RuntimeCertificates.repair(root))
        assertEquals(modified, Files.getLastModifiedTime(cert.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
    }
    @Test fun existingCustomCertificateIsPreserved() {
        val root = root()
        val custom = File(root, "usr/lib/ssl/cert.pem").apply { parentFile!!.mkdirs(); writeText("custom trust") }
        RuntimeCertificates.repair(root)
        assertEquals("custom trust", custom.readText())
    }
    @Test fun missingBundleDoesNotCreateBrokenLinks() {
        val root = temp.newFolder()
        assertFalse(RuntimeCertificates.repair(root))
        assertFalse(File(root, "usr/lib/ssl/cert.pem").exists())
    }
    @Test fun escapedParentIsNotWrittenThrough() {
        val root = root(); val outside = temp.newFolder()
        File(root, "usr/lib").mkdirs()
        Files.createSymbolicLink(File(root, "usr/lib/ssl").toPath(), outside.toPath())
        assertFalse(RuntimeCertificates.repair(root))
        assertTrue(outside.list()!!.isEmpty())
    }
}
