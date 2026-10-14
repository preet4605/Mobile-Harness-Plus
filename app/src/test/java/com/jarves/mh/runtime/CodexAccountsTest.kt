package com.jarves.mh.runtime

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class CodexAccountsTest {
    @Test fun `accounts persist selection without sharing homes`() {
        val root = Files.createTempDirectory("accounts").toFile()
        try {
            val store = CodexAccounts(root)
            val a = store.create()
            val b = store.create()
            store.complete(a.id, "First")
            store.complete(b.id, "Second")
            store.select(a.id)
            assertEquals(a.id, CodexAccounts(root).selected().id)
            assertNotEquals(store.home(a.id), store.home(b.id))
            store.remove(b.id)
            assertEquals(a.id, store.selected().id)
        } finally { root.deleteRecursively() }
    }
    @Test fun `legacy home retained and invalid paths rejected`() {
        val root = Files.createTempDirectory("accounts").toFile()
        try {
            val store = CodexAccounts(root)
            assertEquals("/root/.codex", store.home(CodexAccounts.LEGACY))
            assertThrows(IllegalArgumentException::class.java) { store.home("../escape") }
            assertThrows(IllegalArgumentException::class.java) { store.select("missing") }
            assertEquals(CodexAccounts.LEGACY, CodexAccounts.taskAccount("CHATGPT"))
        } finally { root.deleteRecursively() }
    }
    @Test fun `incomplete login is not selectable after restart`() {
        val root = Files.createTempDirectory("accounts").toFile()
        try {
            val store = CodexAccounts(root)
            val pending = store.create()
            assertFalse(CodexAccounts(root).list().any { it.id == pending.id })
            assertThrows(IllegalArgumentException::class.java) { store.select(pending.id) }
            assertEquals(pending.id, CodexAccounts.taskAccount(CodexAccounts.taskProvider(pending.id)))
        } finally { root.deleteRecursively() }
    }
    @Test fun `adding second account preserves first account as default`() {
        val root = Files.createTempDirectory("accounts").toFile()
        try {
            val store = CodexAccounts(root)
            val first = store.create()
            store.complete(first.id, "First")
            assertEquals(first.id, store.selected().id)
            val second = store.create()
            store.complete(second.id, "Second")
            assertEquals(first.id, store.selected().id)
            assertEquals(2, store.list().size)
        } finally { root.deleteRecursively() }
    }
}
