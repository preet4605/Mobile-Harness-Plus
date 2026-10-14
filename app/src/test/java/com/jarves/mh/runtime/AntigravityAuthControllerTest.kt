package com.jarves.mh.runtime

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AntigravityAuthControllerTest {
    private fun manager(): AntigravityAccountManager = AntigravityAccountManager(
        customAccountsDir = File(RuntimeEnvironment.getApplication().filesDir, "fixture-accounts"),
        loadAccountsOverride = { emptyList() }, saveAccountsOverride = {},
    )

    @Test fun `success screen without saved token fails instead of signing in`() = runBlocking {
        val accounts = manager()
        var signedIn = false
        val controller = AntigravityAuthController(RuntimeEnvironment.getApplication(), accounts, false, "",
            { value, _ -> signedIn = value }, launchOverride = { _, output ->
                output.writeText("Antigravity CLI Google AI for shortcuts\n")
                AuthFixtureProcess(false)
            })
        controller.beginLogin()
        assertFalse(File(RuntimeEnvironment.getApplication().cacheDir, "antigravity-auth-output.log").exists())
        assertEquals(AntigravityAuthStatus.ERROR, controller.state.value.status)
        assertFalse(signedIn)
        assertTrue(accounts.accountsList().isEmpty())
    }

    @Test fun `saved token plus success screen completes and reaps child`() = runBlocking {
        val accounts = manager()
        val process = AuthFixtureProcess()
        val controller = AntigravityAuthController(RuntimeEnvironment.getApplication(), accounts, false, "",
            { _, _ -> }, launchOverride = { id, output ->
                accounts.getAccountTokenHostFile(id).apply { parentFile!!.mkdirs(); writeText("non-secret-token") }
                output.writeText("Antigravity CLI Google AI for shortcuts fixture@example.test\n")
                process
            })
        controller.beginLogin()
        assertFalse(File(RuntimeEnvironment.getApplication().cacheDir, "antigravity-auth-output.log").exists())
        assertEquals(AntigravityAuthStatus.SIGNED_IN, controller.state.value.status)
        assertEquals(1, accounts.accountsList().size)
        assertFalse(process.isAlive)
    }

    @Test fun `concurrent starts launch once and cancel cannot become an error`() = runBlocking {
        val calls = AtomicInteger()
        val started = CountDownLatch(1)
        val process = AuthFixtureProcess()
        val controller = AntigravityAuthController(RuntimeEnvironment.getApplication(), manager(), false, "",
            { _, _ -> }, launchOverride = { _, output ->
                calls.incrementAndGet(); output.writeText(""); started.countDown(); process
            })
        val login = async(Dispatchers.Default) { controller.beginLogin() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        controller.beginLogin()
        controller.cancel()
        withTimeout(5_000) { login.await() }
        assertFalse(File(RuntimeEnvironment.getApplication().cacheDir, "antigravity-auth-output.log").exists())
        assertEquals(1, calls.get())
        assertEquals(AntigravityAuthStatus.SIGNED_OUT, controller.state.value.status)
        assertTrue(process.destroyed)
    }

    @Test fun `silent login has a bounded deadline and kills child`() = runBlocking {
        val process = AuthFixtureProcess()
        val controller = AntigravityAuthController(RuntimeEnvironment.getApplication(), manager(), false, "",
            { _, _ -> }, launchOverride = { _, output -> output.writeText(""); process }, loginTimeoutMs = 1)
        withTimeout(5_000) { controller.beginLogin() }
        assertEquals(AntigravityAuthStatus.ERROR, controller.state.value.status)
        assertTrue(process.destroyed)
    }

    @Test fun `exit after URL reports failure instead of waiting for code forever`() = runBlocking {
        val controller = AntigravityAuthController(RuntimeEnvironment.getApplication(), manager(), false, "",
            { _, _ -> }, launchOverride = { _, output ->
                output.writeText("Open https://accounts.google.com/o/oauth2/auth?client_id=fixture&code_challenge=fixture&state=fixture\n")
                AuthFixtureProcess(false, 1)
            })
        controller.beginLogin()
        assertFalse(File(RuntimeEnvironment.getApplication().cacheDir, "antigravity-auth-output.log").exists())
        assertEquals(AntigravityAuthStatus.ERROR, controller.state.value.status)
    }
    @Test fun `failed login cleanup must not follow a link into project data`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val accounts = manager()
        val original = File(context.filesDir, "project-original").apply { mkdirs() }
        val marker = File(original, "chat.txt").apply { writeText("Keep this project data") }
        val controller = AntigravityAuthController(context, accounts, false, "", { _, _ -> },
            launchOverride = { id, output ->
                java.nio.file.Files.createSymbolicLink(
                    File(accounts.getAccountHomeHostDir(id), "unexpected-link").toPath(), original.toPath())
                output.writeText("")
                AuthFixtureProcess(false, 1)
            })
        controller.beginLogin()
        assertTrue(marker.isFile)
        assertEquals("Keep this project data", marker.readText())
    }
    @Test fun `account removal preserves project data behind account links`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val accounts = manager()
        val id = "fixture-account"
        val original = File(context.filesDir, "project-original").apply { mkdirs() }
        val marker = File(original, "chat.txt").apply { writeText("Keep this project data") }
        val home = accounts.getAccountHomeHostDir(id).apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(home, "unexpected-link").toPath(), original.toPath())
        accounts.addAccount(id, "fixture@example.test")
        val controller = AntigravityAuthController(context, accounts, true, "fixture@example.test", { _, _ -> })
        controller.logout(id)
        assertFalse(home.exists())
        assertEquals("Keep this project data", marker.readText())
    }

    @Test fun `account paths reject traversal and credential symlink escapes`() {
        val context = RuntimeEnvironment.getApplication()
        val accounts = manager()
        assertThrows(IllegalArgumentException::class.java) { accounts.getAccountHomeHostDir("../project") }
        val home = accounts.getAccountHomeHostDir("fixture-account").apply { mkdirs() }
        val outside = File(context.filesDir, "outside").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(home, ".gemini").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { accounts.getAccountTokenHostFile("fixture-account") }
    }
}
