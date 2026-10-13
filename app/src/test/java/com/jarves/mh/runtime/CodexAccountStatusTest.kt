package com.jarves.mh.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodexAccountStatusTest {
    @Test fun `late status for previous account cannot authenticate the new default`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var blockedId = ""
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readStatus = { id ->
            if (id == blockedId) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            CodexCredentialStatus.CHATGPT
        })
        controller.accounts.migrateLegacy("Legacy")
        val a = controller.accounts.create(); controller.accounts.complete(a.id, "A")
        val b = controller.accounts.create(); controller.accounts.complete(b.id, "B")
        controller.selectAccount(a.id)
        blockedId = a.id
        val request = async(Dispatchers.Default) { controller.refreshStatus() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            controller.selectAccount(b.id)
        } finally { release.countDown() }
        request.await()
        assertEquals(CodexAuthStatus.SIGNED_OUT, controller.state.value.status)
        controller.refreshStatus()
        assertEquals(CodexAuthStatus.SIGNED_IN, controller.state.value.status)
    }

    @Test fun `older failed status cannot overwrite a newer successful refresh`() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readStatus = {
            if (calls.incrementAndGet() == 1) {
                started.countDown(); check(release.await(5, TimeUnit.SECONDS)); CodexCredentialStatus.UNAVAILABLE
            } else CodexCredentialStatus.CHATGPT
        })
        controller.accounts.migrateLegacy("Legacy")
        val old = async(Dispatchers.Default) { controller.refreshStatus() }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            controller.refreshStatus()
        } finally { release.countDown() }
        old.await()
        assertEquals(CodexAuthStatus.SIGNED_IN, controller.state.value.status)
    }

    @Test fun `temporary status failure preserves previously verified login`() = runBlocking {
        var status = CodexCredentialStatus.CHATGPT
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readStatus = { status })
        controller.accounts.migrateLegacy("Legacy")
        val a = controller.accounts.create(); controller.accounts.complete(a.id, "Existing")
        controller.accounts.credentialFile(a.id).writeText("non-secret-fixture")
        controller.selectAccount(a.id)
        controller.refreshStatus()
        status = CodexCredentialStatus.UNAVAILABLE
        controller.refreshStatus()
        assertEquals(CodexAuthStatus.SIGNED_IN, controller.state.value.status)
        status = CodexCredentialStatus.SIGNED_OUT
        controller.refreshStatus()
        assertEquals(CodexAuthStatus.SIGNED_OUT, controller.state.value.status)
    }

    @Test fun `legacy migration requires confirmed ChatGPT status`() = runBlocking {
        val controller = CodexAuthController(RuntimeEnvironment.getApplication(), readAccount = { null },
            readStatus = { CodexCredentialStatus.CHATGPT })
        controller.refreshStatus()
        assertTrue(controller.accounts.legacyRegistered())
        assertEquals(CodexAccounts.LEGACY, controller.accounts.selectedId())
        assertEquals(CodexAuthStatus.SIGNED_IN, controller.state.value.status)
    }
}
