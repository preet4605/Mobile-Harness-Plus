package com.jarves.mh.runtime

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CodexAccountIsolationTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = CodexAccounts(folder.root)

    @Test fun `default changes never change persisted task account`() {
        val store = store()
        val a = store.create(); store.complete(a.id, "A")
        val pinned = CodexAccounts.taskProvider(store.selected().id)
        val b = store.create(); store.complete(b.id, "B"); store.select(b.id)
        assertEquals(a.id, CodexAccounts.taskAccount(pinned))
        assertEquals(b.id, CodexAccounts(folder.root).selected().id)
        store.remove(a.id)
        assertThrows(IllegalArgumentException::class.java) { store.requireUsable(CodexAccounts.taskAccount(pinned)!!) }
        assertNull(CodexAccounts.taskAccount("CUSTOM"))
    }

    @Test fun `renaming preserves account identity and native scope`() {
        val store = store()
        val a = store.create(); store.complete(a.id, "A")
        val scope = store.selected().scope
        store.rename(a.id, "Work plan")
        assertEquals("Work plan", CodexAccounts(folder.root).selected().label)
        assertEquals(scope, store.selected().scope)
    }

    @Test fun `disabled default never falls back to another account`() {
        val store = store()
        val a = store.create(); store.complete(a.id, "A")
        val b = store.create(); store.complete(b.id, "B")
        store.setEnabled(a.id, false)
        assertThrows(IllegalArgumentException::class.java) { store.selected() }
        assertEquals(a.id, store.selectedId())
        store.setEnabled(a.id, true)
        assertEquals(a.id, store.selected().id)
    }

    @Test fun `legacy registration is idempotent and removed legacy is not reimported`() {
        val store = store()
        val home = store.hostHome(CodexAccounts.LEGACY).apply { mkdirs() }
        val transcript = home.resolve("session.txt").apply { writeText("history fixture") }
        store.migrateLegacy("Original")
        store.migrateLegacy("Changed")
        assertEquals("Original", store.selected().label)
        assertEquals(1, store.list().size)
        store.remove(CodexAccounts.LEGACY)
        store.migrateLegacy("Again")
        assertTrue(store.list().isEmpty())
        assertNull(store.selectedId())
        assertTrue(transcript.isFile)
    }

    @Test fun `refresh does not change resume scope but reconnect does`() {
        val store = store()
        val a = store.create(); store.complete(a.id, "A")
        val scope = store.selected().scope
        store.credentialFile(a.id).writeText("opaque fixture")
        store.credentialFile(a.id).setLastModified(1234567)
        assertEquals(scope, store.selected().scope)
        store.markExpired(a.id)
        assertThrows(IllegalArgumentException::class.java) { store.requireUsable(a.id) }
        assertTrue(CodexAccounts(folder.root).list().single().needsSignIn)
        store.complete(a.id, "Reconnected")
        assertNotEquals(scope, store.selected().scope)
    }

    @Test fun `replacement login stays isolated from writes by a retired login`() {
        val store = store()
        val old = store.create(); store.complete(old.id, "Original")
        store.markExpired(old.id)
        val replacement = store.create()
        store.credentialFile(replacement.id).writeText("replacement fixture")
        store.complete(replacement.id, "Reconnected")
        store.select(replacement.id)
        store.remove(old.id)
        store.credentialFile(old.id).writeText("late old login fixture")
        assertEquals("replacement fixture", store.credentialFile(store.selected().id).readText())
        assertThrows(IllegalArgumentException::class.java) { store.requireUsable(old.id) }
    }

    @Test fun `system alias for runtime root works while account links are rejected`() {
        val actual = folder.newFolder("private-runtime")
        val alias = folder.root.resolve("system-alias")
        Files.createSymbolicLink(alias.toPath(), actual.toPath())
        val store = CodexAccounts(alias)
        val a = store.create()
        store.complete(a.id, "A")
        assertTrue(store.hostHome(a.id).canonicalPath.startsWith(actual.canonicalPath))
        assertEquals(a.id, CodexAccounts(alias).selected().id)
    }

    @Test fun `account and auth symlink escapes are rejected`() {
        val store = store()
        val a = store.create()
        val outside = folder.newFolder("outside")
        Files.createSymbolicLink(store.hostHome(a.id).resolve("auth.json").toPath(), outside.resolve("auth.json").toPath())
        assertThrows(IllegalArgumentException::class.java) { store.credentialFile(a.id) }
        assertThrows(IllegalArgumentException::class.java) { store.home("../../escape") }
        assertThrows(IllegalArgumentException::class.java) { CodexAccounts.taskAccount("CHATGPT:../escape") }
    }

    @Test fun `launch environment and model caches are account scoped`() {
        val store = store()
        val a = store.create(); val b = store.create()
        assertEquals(store.home(a.id), CodexLaunchBuilder.environment(CodexRoute.ChatGptLogin("", store.home(a.id)), null)["CODEX_HOME"])
        assertNotEquals(
            com.jarves.mh.data.ModelListCache.key(com.jarves.mh.model.AgentKind.CODEX, com.jarves.mh.model.ProviderKind.CHATGPT, "", accountId = a.id),
            com.jarves.mh.data.ModelListCache.key(com.jarves.mh.model.AgentKind.CODEX, com.jarves.mh.model.ProviderKind.CHATGPT, "", accountId = b.id),
        )
        val api = CodexRoute.ApiKey("custom", "https://example.test/v1", "TEST_KEY", "model")
        assertEquals("/root/.codex", CodexLaunchBuilder.environment(api, "fixture")["CODEX_HOME"])
        val hidden = CredentialMounts.hiddenHomes(store.home(a.id))
        assertTrue("/root/.codex" in hidden)
        assertTrue("/root/.codex-accounts" in hidden)
    }

    @Test fun `account read rejects API keys and missing authentication`() {
        assertNull(CodexAppServerProtocol.parseAccount(JSONObject("""{"account":{"type":"apiKey"}}""")))
        assertNull(CodexAppServerProtocol.parseAccount(JSONObject("""{"account":null}""")))
        val parsed = CodexAppServerProtocol.parseAccount(JSONObject("""{"account":{"type":"chatgpt","email":"fixture@example.test","planType":"plus"}}"""))
        assertEquals("fixture@example.test · plus", parsed?.label)
        assertEquals("ChatGPT account", CodexAppServerProtocol.parseAccount(JSONObject("""{"account":{"type":"chatgpt","email":null,"planType":null}}"""))?.label)
        assertEquals("account/read", JSONObject(CodexAppServerProtocol.accountRequest(2)).getString("method"))
    }

    @Test fun `cancelling before login starts must not launch a process`() = runBlocking {
        var launched = false
        val engine = CodexLoginEngine(
            launch = { launched = true; error("Should not launch") },
            hasCredentials = { false }, onState = {},
        )
        engine.cancel()
        assertEquals(CodexLoginEngine.Outcome.Cancelled, engine.run())
        assertFalse(launched)
    }
}
