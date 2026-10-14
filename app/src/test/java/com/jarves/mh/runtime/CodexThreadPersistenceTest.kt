package com.jarves.mh.runtime

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.AgentKind
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodexThreadPersistenceTest {
    @Test fun staleSessionStopCannotKillTheNewOwnedProcess() = runBlocking {
        val bridge = CodexRuntimeBridge(RuntimeEnvironment.getApplication()) { null }
        val process = object : Process() {
            private var alive = true
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
            override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
            override fun waitFor() = 0
            override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
            override fun destroy() { alive = false }
            override fun destroyForcibly(): Process { alive = false; return this }
        }
        CodexRuntimeBridge::class.java.getDeclaredField("activeSessionId").apply { isAccessible = true }.set(bridge, "current-session")
        CodexRuntimeBridge::class.java.getDeclaredField("activeProcess").apply { isAccessible = true }.set(bridge, process)
        bridge.stopSession("old-session", force = true)
        assertTrue(process.isAlive)
        process.destroyForcibly()
        Unit
    }
    @Test fun interruptedOrdinaryTurnsLoseResumeWhileGoalControlsSurviveRestartInTheirScope() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = AppPreferences(context)
        prefs.saveAgentConversation(AgentKind.CODEX, "project", "chat:account-a", "ordinary")
        prefs.saveCodexGoalThread("project", "chat:account-a", "goal")
        assertEquals("ordinary", prefs.consumeAgentConversation(AgentKind.CODEX, "project", "chat:account-a"))
        val reopened = AppPreferences(context)
        assertNull(reopened.loadAgentConversation(AgentKind.CODEX, "project", "chat:account-a"))
        assertEquals("goal", reopened.loadCodexGoalThread("project", "chat:account-a"))
        assertNull(reopened.loadCodexGoalThread("project", "chat:account-b"))
        reopened.clearAgentConversation(AgentKind.CODEX, "project", "chat")
        assertNull(reopened.loadCodexGoalThread("project", "chat:account-a"))
    }

    @Test fun planModePersistsPerChatAndAccountAndIsClearedWithTheChat() {
        val context = RuntimeEnvironment.getApplication()
        AppPreferences(context).saveCodexPlanMode("project", "chat:account-a", true)
        val reopened = AppPreferences(context)
        assertTrue(reopened.codexPlanMode("project", "chat:account-a"))
        assertFalse(reopened.codexPlanMode("project", "chat:account-b"))
        assertFalse(reopened.codexPlanMode("project", "other:account-a"))
        reopened.clearAgentConversation(AgentKind.CODEX, "project", "chat")
        assertFalse(reopened.codexPlanMode("project", "chat:account-a"))
    }
}
