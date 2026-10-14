package com.jarves.mh.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LoginAuditRegressionTest {
    @Test fun `invalid file must not authenticate as ChatGPT`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readAccount = { null })
        controller.accounts.migrateLegacy("Legacy")
        val account = controller.accounts.create()
        controller.accounts.complete(account.id, "A")
        controller.selectAccount(account.id)
        controller.accounts.credentialFile(account.id).writeText("garbage")
        controller.refreshStatus()
        assertEquals(CodexAuthStatus.SIGNED_OUT, controller.state.value.status)
    }

    @Test fun `API key must not be migrated as ChatGPT account`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readAccount = { null })
        controller.accounts.credentialFile(CodexAccounts.LEGACY).apply {
            parentFile!!.mkdirs()
            writeText("""{"OPENAI_API_KEY":"non-secret-fixture"}""")
        }
        controller.refreshStatus()
        assertTrue(controller.accounts.list().isEmpty())
    }

    @Test fun `refresh must preserve custom account label`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(),
            readAccount = { CodexAccountDetails("fixture@example.test", "plus") },
            readStatus = { CodexCredentialStatus.CHATGPT })
        controller.accounts.migrateLegacy("Legacy")
        val account = controller.accounts.create()
        controller.accounts.complete(account.id, "A")
        controller.selectAccount(account.id)
        controller.renameAccount(account.id, "Work account")
        controller.refreshStatus()
        assertEquals("Work account", controller.accounts.requireUsable(account.id).label)
    }

    @Test fun `deleted account credentials must not be restored from cache`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val controller = CodexAuthController(context, readAccount = { null })
        controller.accounts.migrateLegacy("Legacy")
        val account = controller.accounts.create()
        controller.accounts.complete(account.id, "A")
        controller.selectAccount(account.id)
        val stale = File(context.cacheDir, "credential-mask/${account.id}/auth.json")
        stale.parentFile!!.mkdirs()
        stale.writeText("""{"tokens":"non-secret-stale-fixture"}""")
        controller.accounts.credentialFile(account.id).delete()
        controller.refreshStatus()
        assertFalse(controller.accounts.credentialFile(account.id).exists())
        assertEquals(CodexAuthStatus.SIGNED_OUT, controller.state.value.status)
    }
    @Test fun `remove account must erase cached credential copy`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val controller = CodexAuthController(context, readAccount = { null })
        controller.accounts.migrateLegacy("Legacy")
        val account = controller.accounts.create()
        controller.accounts.complete(account.id, "A")
        controller.selectAccount(account.id)
        val stale = File(context.cacheDir, "credential-mask/${account.id}/auth.json")
        stale.parentFile!!.mkdirs()
        stale.writeText("""{"tokens":"non-secret-stale-fixture"}""")
        controller.accounts.credentialFile(account.id).writeText("""{"tokens":"non-secret-fixture"}""")
        controller.logout(account.id)
        assertFalse(controller.accounts.list().any { it.id == account.id })
        assertFalse(stale.exists())
    }
    @Test fun `failed reconnect leaves the original account usable`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readAccount = { null }, readStatus = { CodexCredentialStatus.CHATGPT },
            launchOverride = { _, _, _ -> AuthFixtureProcess(false, 1) })
        controller.accounts.migrateLegacy("Legacy")
        val account = controller.accounts.create()
        controller.accounts.complete(account.id, "Original")
        controller.selectAccount(account.id)
        controller.beginLogin(account.id)
        assertEquals(account.id, controller.accounts.selected().id)
    }
    @Test fun `second login cannot fall back to legacy or shared credentials`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val controller = CodexAuthController(context, readAccount = { null },
            launchOverride = { _, _, _ -> AuthFixtureProcess(false, 0) })
        controller.accounts.migrateLegacy("Legacy")
        controller.accounts.credentialFile(CodexAccounts.LEGACY).apply {
            parentFile!!.mkdirs(); writeText("non-secret-legacy-fixture")
        }
        File(context.cacheDir, "credential-mask/auth.json").apply {
            parentFile!!.mkdirs(); writeText("non-secret-shared-fixture")
        }
        controller.beginLogin()
        assertEquals(listOf(CodexAccounts.LEGACY), controller.accounts.list().map { it.id })
    }

    @Test fun `status parser rejects API key and malformed status`() {
        assertEquals(CodexCredentialStatus.CHATGPT, codexCredentialStatus("Logged in using ChatGPT\n", 0))
        assertEquals(CodexCredentialStatus.SIGNED_OUT, codexCredentialStatus("Not logged in\n", 1))
        assertEquals(CodexCredentialStatus.SIGNED_OUT, codexCredentialStatus("Logged in using an API key - fixture", 0))
        assertEquals(CodexCredentialStatus.UNAVAILABLE, codexCredentialStatus("Logged in using ChatGPT", 127))
        assertEquals(CodexCredentialStatus.UNAVAILABLE, codexCredentialStatus("garbage", 0))
        assertEquals(CodexCredentialStatus.SIGNED_OUT, codexCredentialStatus("Error checking login status: expected value at line 1 column 1", 1))
    }
    @Test fun `explicit sign-out invalidates an older ready account record`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(),
            readStatus = { CodexCredentialStatus.SIGNED_OUT })
        controller.accounts.migrateLegacy("Older migrated account")
        controller.refreshStatus()
        assertTrue(controller.accounts.list().single().needsSignIn)
        assertFalse(controller.hasCredentials())
    }

    @Test fun `new login replaces an unusable default from an older build`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readAccount = { null },
            readStatus = { if (it == CodexAccounts.LEGACY) CodexCredentialStatus.SIGNED_OUT else CodexCredentialStatus.CHATGPT },
            launchOverride = { _, _, _ -> AuthFixtureProcess(false, 0) })
        controller.accounts.migrateLegacy("Older migrated account")
        controller.beginLogin()
        assertNotEquals(CodexAccounts.LEGACY, controller.accounts.selectedId())
        assertTrue(controller.hasCredentials())
        assertTrue(controller.accounts.list().first { it.id == CodexAccounts.LEGACY }.needsSignIn)
    }

    @Test fun `new login still preserves a healthy existing default`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readAccount = { null },
            readStatus = { CodexCredentialStatus.CHATGPT },
            launchOverride = { _, _, _ -> AuthFixtureProcess(false, 0) })
        controller.accounts.migrateLegacy("Healthy default")
        controller.beginLogin()
        assertEquals(CodexAccounts.LEGACY, controller.accounts.selectedId())
        assertEquals(2, controller.accounts.list().size)
        assertTrue(controller.hasCredentials())
    }
}
