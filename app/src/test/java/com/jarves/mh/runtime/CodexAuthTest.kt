package com.jarves.mh.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The prompt text below is rebuilt from the Codex 0.161.0 source (`device_code_auth.rs`), including
 * its ANSI colour codes. It has NOT been captured from a live `codex login --device-auth` run.
 */
private const val ESC = "\u001B"
private val SOURCE_PROMPT =
    "\nWelcome to Codex [v$ESC[90m0.161.0$ESC[0m]\n$ESC[90mOpenAI's command-line coding agent$ESC[0m\n" +
        "\nFollow these steps to sign in with ChatGPT using device code authorization:\n" +
        "\n1. Open this link in your browser and sign in to your account\n   $ESC[94mhttps://auth.openai.com/codex/device$ESC[0m\n" +
        "\n2. Enter this one-time code $ESC[90m(expires in 15 minutes)$ESC[0m\n   $ESC[94mABCD-1234$ESC[0m\n" +
        "\n$ESC[90mContinue only if you started this login in Codex. If a website or another person gave you this code, cancel.$ESC[0m\n"

class CodexLoginOutputTest {
    @Test
    fun parsesLinkAndCodeFromTheColouredPrompt() {
        val parsed = CodexLoginOutput.parseDeviceCode(SOURCE_PROMPT)
        assertEquals(CodexDeviceCode("https://auth.openai.com/codex/device", "ABCD-1234"), parsed)
    }

    @Test
    fun parsesPlainTextAndWindowsLineEndings() {
        val plain = CodexLoginOutput.clean(SOURCE_PROMPT).replace("\n", "\r\n")
        assertEquals("ABCD-1234", CodexLoginOutput.parseDeviceCode(plain)?.code)
    }

    @Test
    fun aHalfWrittenCodeLineIsNeverReturned() {
        val upToCode = SOURCE_PROMPT.substringBefore("ABCD-1234")
        assertNull(CodexLoginOutput.parseDeviceCode(upToCode))
        assertNull(CodexLoginOutput.parseDeviceCode(upToCode + "ABCD-12"))
        assertNull(CodexLoginOutput.parseDeviceCode(SOURCE_PROMPT.substringBefore("2. Enter")))
    }

    @Test
    fun theWarningSentenceIsNotMistakenForTheCode() {
        val noCode = "Open https://auth.openai.com/codex/device\nEnter this one-time code (expires in 15 minutes)\n" +
            "Continue only if you started this login in Codex.\n"
        assertNull(CodexLoginOutput.parseDeviceCode(noCode))
    }

    @Test
    fun emptyAndGarbageOutputIsIgnored() {
        listOf("", "\n", "garbage\u0000\u0001 without markers\n", "https://x.example\n").forEach {
            assertNull(CodexLoginOutput.parseDeviceCode(it))
        }
    }

    @Test
    fun failureMessagesAreShortAndActionable() {
        fun msg(raw: String, exit: Int? = 1) = CodexLoginOutput.failureMessage(raw, exit)
        assertTrue(msg("Error logging in with device code: error sending request for url (https://auth.openai.com/x)").contains("internet"))
        assertTrue(msg("device code login is not enabled for this Codex server.").contains("isn't enabled"))
        assertTrue(msg("device auth timed out after 15 minutes").contains("expired"))
        assertTrue(msg("", 127).contains("Reinstall"))
        assertEquals("Sign in didn't finish. Try again.", msg("", 1))
        assertFalse(msg("some text with token=secret").contains("secret"))
    }
}

class CodexAuthStateTest {
    @Test
    fun displayStatusMatchesTheStatus() {
        assertEquals("Signed in with ChatGPT", CodexAuthState(CodexAuthStatus.SIGNED_IN).displayStatus)
        assertEquals("Not signed in", CodexAuthState().displayStatus)
        assertEquals("boom", CodexAuthState(CodexAuthStatus.ERROR, message = "boom").displayStatus)
        assertEquals("Sign in cancelled.", CodexAuthState(CodexAuthStatus.SIGNED_OUT, message = "Sign in cancelled.").displayStatus)
    }
}

private class FakeLoginProcess(
    chunks: List<String>,
    private val exit: Int?,
    private val staysAlive: Boolean = false,
) : CodexLoginProcess {
    private val queue = ArrayDeque(chunks)
    @Volatile var destroyed = false

    override val isAlive: Boolean
        get() = !destroyed && (staysAlive || queue.isNotEmpty())

    @Synchronized
    override fun readNewOutput(): String = queue.removeFirstOrNull().orEmpty()

    override fun exitCode(): Int? = if (destroyed) 137 else exit

    override fun destroy() {
        destroyed = true
    }
}

class CodexLoginEngineTest {
    private fun engine(
        process: () -> CodexLoginProcess,
        credentials: () -> Boolean,
        states: MutableList<CodexAuthState>,
        timeoutMs: Long = 60_000L,
        codeWaitMs: Long = 60_000L,
        now: () -> Long = System::currentTimeMillis,
    ) = CodexLoginEngine(
        launch = process,
        hasCredentials = credentials,
        onState = { states.add(it) },
        pollMs = 2L,
        timeoutMs = timeoutMs,
        codeWaitMs = codeWaitMs,
        now = now,
    )

    @Test
    fun successPublishesTheCodeThenSignsIn() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val process = FakeLoginProcess(listOf(SOURCE_PROMPT.substring(0, 80), SOURCE_PROMPT.substring(80), ""), exit = 0)
        val outcome = engine({ process }, { true }, states).run()
        assertEquals(CodexLoginEngine.Outcome.SignedIn, outcome)
        assertEquals(CodexAuthStatus.STARTING, states.first().status)
        val awaiting = states.first { it.status == CodexAuthStatus.AWAITING_AUTH }
        assertEquals("ABCD-1234", awaiting.userCode)
        assertEquals("https://auth.openai.com/codex/device", awaiting.verificationUrl)
        assertEquals(CodexAuthStatus.SIGNED_IN, states.last().status)
        assertEquals(1, states.count { it.status == CodexAuthStatus.AWAITING_AUTH })
        assertFalse(process.destroyed)
    }

    @Test
    fun signedInStateCarriesNoCode() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        engine({ FakeLoginProcess(listOf(SOURCE_PROMPT), 0) }, { true }, states).run()
        assertNull(states.last().userCode)
        assertNull(states.last().verificationUrl)
    }

    @Test
    fun exitZeroWithoutSavedCredentialsIsAFailure() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val outcome = engine({ FakeLoginProcess(listOf(SOURCE_PROMPT), 0) }, { false }, states).run()
        assertTrue(outcome is CodexLoginEngine.Outcome.Failed)
        assertEquals(CodexAuthStatus.ERROR, states.last().status)
    }

    @Test
    fun aFailedLoginReportsAFriendlyReason() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val outcome = engine(
            { FakeLoginProcess(listOf("Error logging in with device code: error sending request for url (https://auth.openai.com/x)\n"), 1) },
            { false },
            states,
        ).run()
        val failed = outcome as CodexLoginEngine.Outcome.Failed
        assertTrue(failed.message.contains("internet"))
        assertEquals(failed.message, states.last().message)
    }

    @Test
    fun launchFailureBecomesAFailureNotACrash() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val outcome = engine({ throw IllegalStateException("no runtime") }, { false }, states).run()
        assertTrue(outcome is CodexLoginEngine.Outcome.Failed)
        assertEquals(CodexAuthStatus.ERROR, states.last().status)
    }

    @Test
    fun cancelStopsTheProcessAndReportsCancelled() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val process = FakeLoginProcess(listOf(SOURCE_PROMPT), exit = null, staysAlive = true)
        val engine = engine({ process }, { false }, states)
        val outcome = async(Dispatchers.Default) { engine.run() }
        withTimeout(5_000) { while (states.none { it.status == CodexAuthStatus.AWAITING_AUTH }) delay(2) }
        engine.cancel()
        assertEquals(CodexLoginEngine.Outcome.Cancelled, withTimeout(5_000) { outcome.await() })
        assertTrue(process.destroyed)
        assertEquals(CodexAuthStatus.SIGNED_OUT, states.last().status)
        assertEquals("Sign in cancelled.", states.last().message)
    }

    @Test
    fun cancelBeforeAnyOutputStillStops() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val process = FakeLoginProcess(emptyList(), exit = null, staysAlive = true)
        val engine = engine({ process }, { false }, states)
        val outcome = async(Dispatchers.Default) { engine.run() }
        withTimeout(5_000) { while (states.isEmpty()) delay(2) }
        engine.cancel()
        assertEquals(CodexLoginEngine.Outcome.Cancelled, withTimeout(5_000) { outcome.await() })
        assertTrue(process.destroyed)
    }

    @Test
    fun theLoginGivesUpAfterTheTimeoutAndKillsTheProcess() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        var clock = 0L
        val process = FakeLoginProcess(listOf(SOURCE_PROMPT), exit = null, staysAlive = true)
        val outcome = engine({ process }, { false }, states, timeoutMs = 1_000L, now = { clock.also { clock += 400L } }).run()
        assertTrue(outcome is CodexLoginEngine.Outcome.Failed)
        assertTrue(process.destroyed)
        assertTrue(states.last().message!!.contains("expired"))
    }

    @Test
    fun noCodeWithinTheWaitIsAFastFailureNotAHang() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        var clock = 0L
        val process = FakeLoginProcess(listOf("something unexpected\n"), exit = null, staysAlive = true)
        val outcome = engine({ process }, { false }, states, codeWaitMs = 1_000L, now = { clock.also { clock += 400L } }).run()
        val failed = outcome as CodexLoginEngine.Outcome.Failed
        assertTrue(failed.message.contains("sign-in code"))
        assertTrue(process.destroyed)
        assertTrue(states.none { it.status == CodexAuthStatus.AWAITING_AUTH })
    }

    @Test
    fun theCodeWaitDoesNotApplyOnceTheCodeIsShown() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        var clock = 0L
        val process = FakeLoginProcess(listOf(SOURCE_PROMPT, "", "", "", "", ""), exit = 0)
        val outcome = engine({ process }, { true }, states, codeWaitMs = 1_000L, now = { clock.also { clock += 400L } }).run()
        assertEquals(CodexLoginEngine.Outcome.SignedIn, outcome)
    }

    @Test
    fun coroutineCancellationKillsTheProcess() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val process = FakeLoginProcess(emptyList(), exit = null, staysAlive = true)
        val job = async(Dispatchers.Default) { engine({ process }, { false }, states).run() }
        withTimeout(5_000) { while (states.isEmpty()) delay(2) }
        job.cancel()
        try {
            job.await()
        } catch (expected: CancellationException) {
            // expected
        }
        withTimeout(5_000) { while (!process.destroyed) delay(2) }
        assertTrue(process.destroyed)
    }

    @Test
    fun hugeOutputIsBoundedAndDoesNotBreakParsing() = runBlocking {
        val states = CopyOnWriteArrayList<CodexAuthState>()
        val noise = "x".repeat(40_000) + "\n"
        val process = FakeLoginProcess(listOf(SOURCE_PROMPT, noise, noise, noise), exit = 1)
        val outcome = engine({ process }, { false }, states).run()
        assertTrue(outcome is CodexLoginEngine.Outcome.Failed)
        assertTrue(states.any { it.userCode == "ABCD-1234" })
    }
}
