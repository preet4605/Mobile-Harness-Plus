package com.jarves.mh.runtime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CredentialMountsTest {
    @Test fun `active home stays unchanged while all existing siblings are masked`() {
        val dir = Files.createTempDirectory("mount-fixture").toFile()
        try {
            val root = dir.resolve("rootfs").apply { mkdirs() }
            val accounts = CodexAccounts(root)
            val a = accounts.create(); val b = accounts.create()
            accounts.credentialFile(a.id).writeText("non-secret-A")
            accounts.credentialFile(b.id).writeText("non-secret-B")
            val mask = dir.resolve("mask")
            val binds = CredentialMounts.bindings(root, mask, accounts.home(a.id))
            assertTrue(mask.listFiles()!!.isEmpty())
            assertFalse(binds.any { it.endsWith(":/root/.codex-accounts") })
            assertTrue(binds.contains("${mask.absolutePath}:${accounts.home(b.id)}"))
            assertFalse(binds.any { it.startsWith(accounts.hostHome(b.id).absolutePath + ":") })
            assertFalse(binds.any { it.endsWith(":" + accounts.home(a.id)) })
            assertEquals("non-secret-A", accounts.credentialFile(a.id).readText())
            mask.resolve("auth.json").writeText("non-secret-stale")
            assertThrows(IllegalArgumentException::class.java) { CredentialMounts.bindings(root, mask, accounts.home(a.id)) }
        } finally { dir.deleteRecursively() }
    }
}
