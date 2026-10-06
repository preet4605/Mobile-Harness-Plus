package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeAuthUrlTest {
    private val goodUrl =
        "https://claude.com/cai/oauth/authorize?code=true&client_id=abc&redirect_uri=http%3A%2F%2Flocalhost%3A42345%2Fcallback&state=xyz123"

    @Test
    fun `accepts the official claude hosts over https`() {
        assertTrue(ClaudeAuthUrl.isAllowed(goodUrl))
        assertTrue(ClaudeAuthUrl.isAllowed("https://claude.ai/oauth/authorize?state=1"))
        assertTrue(ClaudeAuthUrl.isAllowed("https://platform.claude.com/oauth/authorize?state=1"))
    }

    @Test
    fun `rejects http look-alike userinfo and non-standard port urls`() {
        assertFalse(ClaudeAuthUrl.isAllowed("http://claude.com/oauth"))
        assertFalse(ClaudeAuthUrl.isAllowed("https://claude.com.evil.example/oauth"))
        assertFalse(ClaudeAuthUrl.isAllowed("https://evilclaude.com/oauth"))
        assertFalse(ClaudeAuthUrl.isAllowed("https://claude.com@evil.example/oauth"))
        assertFalse(ClaudeAuthUrl.isAllowed("https://claude.com:8443/oauth"))
        assertFalse(ClaudeAuthUrl.isAllowed("https://example.com/oauth"))
    }

    @Test
    fun `extract strips terminal codes and returns the exact url`() {
        val output = "\u001B[1mOpening browser\u001B[0m\r\nUse this link:\r\n\u001B[36m$goodUrl\u001B[0m\r\nPaste code here > "
        assertEquals(goodUrl, ClaudeAuthUrl.extract(output, complete = true))
    }

    @Test
    fun `extract ignores a link that touches the end of still-streaming output`() {
        val partial = "Use this link:\nhttps://claude.com/cai/oauth/authorize?code=tr"
        assertNull(ClaudeAuthUrl.extract(partial, complete = false))
        assertEquals(
            "https://claude.com/cai/oauth/authorize?code=tr",
            ClaudeAuthUrl.extract(partial, complete = true),
        )
    }

    @Test
    fun `extract skips disallowed links and trims trailing punctuation`() {
        val output = "Docs: https://example.com/help\nSign in at $goodUrl.\n"
        assertEquals(goodUrl, ClaudeAuthUrl.extract(output, complete = true))
        assertNull(ClaudeAuthUrl.extract("see https://evil.example/phish\n", complete = true))
    }

    @Test
    fun `status output tolerates warning lines around the json`() {
        val output = "warning: update available\n{\"loggedIn\":true,\"authMethod\":\"claude.ai\",\"subscriptionType\":\"pro\"}\ndone\n"
        val status = ClaudeStatusMetadata.fromOutput(output)!!
        assertTrue(status.loggedIn)
        assertEquals("pro", status.subscriptionType)
        assertNull(ClaudeStatusMetadata.fromOutput("no json here"))
    }

    @Test
    fun `success hints do not fire on not logged in`() {
        assertTrue(ClaudeLoginOutput.looksSuccessful("Login successful"))
        assertTrue(ClaudeLoginOutput.looksSuccessful("Logged in as user@example.com"))
        assertFalse(ClaudeLoginOutput.looksSuccessful("Not logged in"))
        assertFalse(ClaudeLoginOutput.looksSuccessful("Waiting for authorization"))
    }

    @Test
    fun `failure output is classified conservatively`() {
        assertEquals(ClaudeAuthErrorKind.NETWORK, ClaudeLoginOutput.classifyFailure("Error: getaddrinfo ENOTFOUND claude.ai"))
        assertEquals(ClaudeAuthErrorKind.CODE_REJECTED, ClaudeLoginOutput.classifyFailure("Invalid code, try again"))
        assertEquals(ClaudeAuthErrorKind.ORG_RESTRICTED, ClaudeLoginOutput.classifyFailure("Your organization requires SSO"))
        assertEquals(ClaudeAuthErrorKind.UNKNOWN, ClaudeLoginOutput.classifyFailure("something odd"))
    }
}

class ClaudeLoginEngineTest {
    private val url = "https://claude.com/cai/oauth/authorize?code=true&client_id=abc&state=xyz123"

    private class FakeProcess(
        private val clock: () -> Long,
        private val script: List<Pair<Long, String>>,
        private val exitAtMs: Long? = null,
    ) : ClaudeCliProcess {
        private var delivered = 0
        val written = StringBuilder()
        var destroyed = false

        override val isAlive: Boolean get() = !destroyed && (exitAtMs == null || clock() < exitAtMs)

        override fun readNewOutput(): String {
            val out = StringBuilder()
            while (delivered < script.size && script[delivered].first <= clock()) {
                out.append(script[delivered].second)
                delivered++
            }
            return out.toString()
        }

        override fun write(text: String) {
            written.append(text)
        }

        override fun destroy() {
            destroyed = true
        }
    }

    private class Harness(
        script: List<Pair<Long, String>>,
        exitAtMs: Long? = null,
        private val signedInAtMs: Long? = null,
        deadlineMs: Long = ClaudeLoginEngine.DEFAULT_DEADLINE_MS,
        launchFails: Boolean = false,
        private val onTick: (Long, ClaudeLoginEngine) -> Unit = { _, _ -> },
    ) {
        var now = 0L
        val states = mutableListOf<ClaudeAuthState>()
        val urls = mutableListOf<String>()
        var statusCalls = 0
        val process = FakeProcess({ now }, script, exitAtMs)
        val engine: ClaudeLoginEngine = ClaudeLoginEngine(
            launcher = ClaudeCliLauncher { args ->
                assertEquals(listOf("auth", "login", "--claudeai"), args)
                if (launchFails) error("spawn failed")
                process
            },
            queryStatus = {
                statusCalls++
                val signedIn = signedInAtMs != null && now >= signedInAtMs
                ClaudeStatusMetadata(loggedIn = signedIn, subscriptionType = if (signedIn) "pro" else null)
            },
            onState = { states += it },
            onUrl = { urls += it },
            sleep = { ms ->
                now += ms
                onTick(now, engineRef())
            },
            nowMs = { now },
            deadlineMs = deadlineMs,
        )
        private fun engineRef(): ClaudeLoginEngine = engine
        fun run(): ClaudeLoginEngine.Outcome = engine.run()
    }

    @Test
    fun `browser callback completes sign in and opens the link exactly once`() {
        val h = Harness(
            script = listOf(0L to "Opening browser...\n$url\n"),
            exitAtMs = 5_000,
            signedInAtMs = 5_000,
        )
        val outcome = h.run()
        assertTrue(outcome is ClaudeLoginEngine.Outcome.SignedIn)
        assertEquals(listOf(url), h.urls)
        assertTrue(h.states.any { it.status == ClaudeAuthStatusState.AWAITING_AUTH && it.authorizationUrl == url })
        assertEquals(ClaudeAuthStatusState.SIGNED_IN, h.states.last().status)
        assertEquals("pro", h.states.last().subscriptionType)
        assertTrue("process must be cleaned up", h.process.destroyed)
    }

    @Test
    fun `a truncated link is never opened before it is complete`() {
        val h = Harness(
            script = listOf(
                0L to "Use this link:\nhttps://claude.com/cai/oauth/authorize?code=tr",
                500L to "ue&client_id=abc&state=xyz123\n",
            ),
            exitAtMs = 6_000,
            signedInAtMs = 6_000,
        )
        h.run()
        assertEquals(listOf(url), h.urls)
    }

    @Test
    fun `a pasted code is written to the cli and verified through status`() {
        val h = Harness(
            script = listOf(0L to "Paste code here if prompted > $url\n"),
            signedInAtMs = 4_000,
            onTick = { now, engine -> if (now == 2_000L) engine.submitCode("  abc-123  ") },
        )
        val outcome = h.run()
        assertTrue(outcome is ClaudeLoginEngine.Outcome.SignedIn)
        assertEquals("abc-123\r\n", h.process.written.toString())
        assertTrue(h.process.destroyed)
    }

    @Test
    fun `a rejected code fails after bounded status checks`() {
        val h = Harness(
            script = listOf(0L to "$url\n"),
            signedInAtMs = null,
            onTick = { now, engine -> if (now == 1_000L) engine.submitCode("wrong") },
        )
        val outcome = h.run()
        assertTrue(outcome is ClaudeLoginEngine.Outcome.Failed)
        assertEquals(ClaudeAuthErrorKind.CODE_REJECTED, (outcome as ClaudeLoginEngine.Outcome.Failed).kind)
        assertEquals(ClaudeLoginEngine.CODE_CHECKS, h.statusCalls)
        assertEquals(ClaudeAuthStatusState.ERROR, h.states.last().status)
        assertEquals(ClaudeAuthErrorKind.CODE_REJECTED, h.states.last().errorKind)
    }

    @Test
    fun `hitting the deadline stops the process and reports a timeout`() {
        val h = Harness(script = listOf(0L to "$url\n"), deadlineMs = 10_000)
        val outcome = h.run()
        assertEquals(ClaudeAuthErrorKind.TIMEOUT, (outcome as ClaudeLoginEngine.Outcome.Failed).kind)
        assertTrue(h.process.destroyed)
        assertTrue("clock must not run far past the deadline", h.now <= 10_200)
    }

    @Test
    fun `cancel stops the process and returns to signed out`() {
        val h = Harness(
            script = listOf(0L to "$url\n"),
            onTick = { now, engine -> if (now == 1_000L) engine.cancel() },
        )
        assertEquals(ClaudeLoginEngine.Outcome.Cancelled, h.run())
        assertEquals(ClaudeAuthStatusState.SIGNED_OUT, h.states.last().status)
        assertEquals("Sign in cancelled.", h.states.last().message)
        assertTrue(h.process.destroyed)
    }

    @Test
    fun `exit without any link reports no link`() {
        val h = Harness(script = listOf(0L to "starting...\n"), exitAtMs = 1_000)
        val outcome = h.run() as ClaudeLoginEngine.Outcome.Failed
        assertEquals(ClaudeAuthErrorKind.NO_URL, outcome.kind)
        assertTrue(h.urls.isEmpty())
    }

    @Test
    fun `a link on a foreign host is never opened`() {
        val h = Harness(script = listOf(0L to "Sign in: https://evil.example/phish\n"), exitAtMs = 1_000)
        val outcome = h.run()
        assertTrue(outcome is ClaudeLoginEngine.Outcome.Failed)
        assertTrue(h.urls.isEmpty())
    }

    @Test
    fun `spawn failure is reported without a process`() {
        val h = Harness(script = emptyList(), launchFails = true)
        val outcome = h.run() as ClaudeLoginEngine.Outcome.Failed
        assertEquals(ClaudeAuthErrorKind.SPAWN_FAILED, outcome.kind)
    }

    @Test
    fun `a success-looking line without a real session does not end the attempt`() {
        val h = Harness(
            script = listOf(0L to "$url\n", 1_000L to "Login successful\n"),
            signedInAtMs = null,
            deadlineMs = 30_000,
        )
        val outcome = h.run()
        assertEquals(ClaudeAuthErrorKind.TIMEOUT, (outcome as ClaudeLoginEngine.Outcome.Failed).kind)
        // The hint is verified once, not on every poll.
        assertEquals(ClaudeLoginEngine.HINT_CHECKS, h.statusCalls)
    }
}

class ClaudeAuthStateTest {
    @Test
    fun `display text covers every state without exposing details`() {
        assertEquals("Not signed in", ClaudeAuthState().displayStatus)
        assertEquals("Checking your sign in…", ClaudeAuthState(ClaudeAuthStatusState.VERIFYING).displayStatus)
        assertEquals("Session expired — sign in again", ClaudeAuthState(ClaudeAuthStatusState.EXPIRED).displayStatus)
        val signedIn = ClaudeAuthState.signedIn(ClaudeStatusMetadata(loggedIn = true, subscriptionType = "max"))
        assertEquals("Signed in — Claude Max", signedIn.displayStatus)
        assertNull("sign-in state must not carry a link", signedIn.authorizationUrl)
    }
}
