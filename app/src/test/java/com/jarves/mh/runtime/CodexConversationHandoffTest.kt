package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexConversationHandoffTest {
    private val thread = "12345678-1234-1234-1234-123456789abc"
    private val key = "chat-route"
    private val workspace = "/workspace/project"
    private fun fixture(test: (File, CodexAccounts, CodexAccount, CodexAccount, CodexConversationHandoff) -> Unit) {
        val root = Files.createTempDirectory("codex-handoff").toFile()
        try {
            val accounts = CodexAccounts(root)
            val a = accounts.create().also { accounts.complete(it.id, "A") }.let { accounts.requireUsable(it.id) }
            val b = accounts.create().also { accounts.complete(it.id, "B") }.let { accounts.requireUsable(it.id) }
            accounts.credentialFile(a.id).writeText("fixture"); accounts.credentialFile(b.id).writeText("fixture")
            test(root, accounts, a, b, CodexConversationHandoff(root))
        } finally { root.deleteRecursively() }
    }
    private fun rollout(accounts: CodexAccounts, account: CodexAccount, body: String = "A context"): File =
        File(accounts.hostHome(account.id), "sessions/2026/10/13/rollout-2026-10-13T00-00-00-$thread.jsonl").apply {
            parentFile!!.mkdirs()
            writeText(JSONObject().put("type", "session_meta").put("payload", JSONObject().put("id", thread).put("cwd", workspace).put("cli_version", "0.161.0")).toString() + "\n" +
                JSONObject().put("type", "response_item").put("payload", JSONObject().put("type", "message").put("content", body)).toString() + "\n")
        }
    @Test fun completeRolloutReplacesStaleTargetAndIncludesCompactionAndTools() = fixture { _, accounts, a, b, store ->
        val source = rollout(accounts, a, "A tool output and compacted context")
        rollout(accounts, b, "stale B context")
        store.adopt(key, "project:chat", a, thread, source.path, workspace)
        store.claim(key, "task-b", b, workspace)
        val imported = store.importForTask(key, "task-b", b, workspace)
        assertEquals(thread, imported)
        assertEquals(source.readText(), rolloutFile(accounts, b).readText())
        rolloutFile(accounts, b).appendText("{\"type\":\"compacted\",\"payload\":{\"message\":\"B latest\"}}\n")
        store.complete(key, "task-b", b, thread, rolloutFile(accounts, b).path, workspace)
        store.claim(key, "task-a", a, workspace)
        store.importForTask(key, "task-a", a, workspace)
        assertTrue(rolloutFile(accounts, a).readText().contains("B latest"))
    }
    private fun rolloutFile(accounts: CodexAccounts, account: CodexAccount) = File(accounts.hostHome(account.id), "sessions/2026/10/13/rollout-2026-10-13T00-00-00-$thread.jsonl")
    @Test fun interruptedClaimSurvivesRestartAndBlocksAnotherTurn() = fixture { root, accounts, a, b, store ->
        store.adopt(key, "project:chat", a, thread, rollout(accounts, a).path, workspace)
        store.claim(key, "unfinished", b, workspace)
        assertThrows(IllegalStateException::class.java) { CodexConversationHandoff(root).claim(key, "next", a, workspace) }
        assertEquals(a.id, store.owner(key)?.accountId)
    }
    @Test fun oldCompletionCannotOverwriteNewTaskOwnership() = fixture { _, accounts, a, b, store ->
        store.claim(key, "new", b, workspace)
        assertThrows(IllegalStateException::class.java) { store.complete(key, "old", a, thread, rollout(accounts, a).path, workspace) }
    }
    @Test fun symlinkRolloutAndWrongWorkspaceFailWithoutReplacingDestination() = fixture { _, accounts, a, b, store ->
        val source = rollout(accounts, a)
        source.writeText(source.readText().replace(workspace, "/workspace/other"))
        assertThrows(IllegalArgumentException::class.java) { store.adopt(key, "project:chat", a, thread, source.path, workspace) }
        assertFalse(rolloutFile(accounts, b).exists())
    }
    @Test fun reconnectGenerationBlocksTransferFromOldCredentialsScope() = fixture { _, accounts, a, b, store ->
        store.adopt(key, "project:chat", a, thread, rollout(accounts, a).path, workspace)
        accounts.complete(a.id, "Reconnected A")
        store.claim(key, "task", b, workspace)
        assertThrows(IllegalArgumentException::class.java) { store.importForTask(key, "task", b, workspace) }
    }
    @Test fun planAndGoalAffinitySurviveRestartAndClearOnlyTheirChat() = fixture { root, _, a, b, store ->
        store.claim(key, "task", a, workspace, chatKey = "project:chat", goal = true)
        store.savePlanMode(key, "project:chat", true)
        val reopened = CodexConversationHandoff(root)
        assertEquals(a.id, reopened.goalOwner(key))
        assertTrue(reopened.planMode(key))
        reopened.savePlanMode("other", "project:other", true)
        reopened.clearChat("project:chat")
        assertNull(reopened.goalOwner(key));assertFalse(reopened.planMode(key));assertTrue(reopened.planMode("other"))
    }
    @Test fun symlinkSourceAndDestinationParentsFailWithoutTouchingExistingFiles() = fixture { root, accounts, a, b, store ->
        val source = rollout(accounts, a)
        val saved = File(root, "saved.jsonl").apply { writeText(source.readText()) }
        source.delete(); Files.createSymbolicLink(source.toPath(), saved.toPath())
        assertThrows(IllegalArgumentException::class.java) { store.adopt(key, "project:chat", a, thread, source.path, workspace) }
        source.delete(); source.writeText(saved.readText())
        store.adopt(key, "project:chat", a, thread, source.path, workspace)
        val outside = File(root, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(accounts.hostHome(b.id), "sessions").toPath(), outside.toPath())
        store.claim(key, "task", b, workspace)
        assertThrows(IllegalArgumentException::class.java) { store.importForTask(key, "task", b, workspace) }
        assertTrue(outside.listFiles()!!.isEmpty())
        assertEquals(a.id, store.owner(key)!!.accountId)
    }
    @Test fun malformedTruncatedWrongIdentityAndOversizedSourcesAreRejected() = fixture { _, accounts, a, _, store ->
        val source = rollout(accounts, a)
        val valid = source.readText()
        for (bad in listOf(valid.dropLast(1), valid + "invalid-json\n", valid.replace(thread, "87654321-1234-1234-1234-123456789abc"), valid.replace("session_meta", "response_item"))) {
            source.writeText(bad)
            assertThrows(IllegalArgumentException::class.java) { store.adopt(key, "project:chat", a, thread, source.path, workspace) }
            assertNull(store.owner(key))
        }
        java.io.RandomAccessFile(source, "rw").use { it.setLength(32L * 1024 * 1024 + 1) }
        assertThrows(IllegalArgumentException::class.java) { store.adopt(key, "project:chat", a, thread, source.path, workspace) }
        assertNull(store.owner(key))
    }
    @Test fun failedSameAccountWorkCannotChangeFrozenContextAndClearStartsFresh() = fixture { root, accounts, a, _, store ->
        val source = rollout(accounts, a, "verified original")
        store.adopt(key, "project:chat", a, thread, source.path, workspace)
        store.claim(key, "failed", a, workspace, chatKey = "project:chat")
        store.importForTask(key, "failed", a, workspace)
        source.appendText("{\"type\":\"response_item\",\"payload\":{\"text\":\"uncertain work\"}}\n")
        val snapshots = File(root, "root/.codex-harness-conversations").walkTopDown().filter { it.isFile && it.extension == "jsonl" }.toList()
        assertEquals(1, snapshots.size)
        assertFalse(snapshots.single().readText().contains("uncertain work"))
        store.clearChat("project:chat")
        assertTrue(store.wasReset(key)); assertNull(store.owner(key))
        assertFalse(snapshots.single().exists())
        assertThrows(IllegalStateException::class.java) { store.adopt(key, "project:chat", a, thread, source.path, workspace) }
        store.claim(key, "fresh", a, workspace)
        assertNull(store.importForTask(key, "fresh", a, workspace))
    }
    @Test fun completedGoalReleasesRotationButHistoricalClearCannotOverwriteLaterContext() = fixture { _, accounts, a, b, store ->
        accounts.balancingEnabled = true
        assertEquals(a.id, accounts.selectForTask().id)
        val source = rollout(accounts, a)
        store.adopt(key, "project:chat", a, thread, source.path, workspace)
        store.claim(key, "goal", a, workspace, goal = true)
        store.complete(key, "goal", a, thread, source.path, workspace, goalStatus = "complete")
        assertNull(store.goalOwner(key)); assertEquals(a.id, store.savedGoalOwner(key))
        assertEquals(b.id, accounts.selectForTask().id)
        store.claim(key, "later", b, workspace); store.importForTask(key, "later", b, workspace)
        rolloutFile(accounts, b).appendText("{\"type\":\"response_item\",\"payload\":{\"text\":\"B later context\"}}\n")
        store.complete(key, "later", b, thread, rolloutFile(accounts, b).path, workspace)
        store.claim(key, "clear", a, workspace, goalControl = true)
        store.completeControl(key, "clear", goalStatus = "cleared")
        assertEquals(b.id, store.owner(key)!!.accountId); assertNull(store.savedGoalOwner(key))
        store.claim(key, "return", a, workspace); store.importForTask(key, "return", a, workspace)
        assertTrue(source.readText().contains("B later context"))
    }
    @Test fun explicitSavedGoalResumeImportsCurrentContextBeforeRepinningOwner() = fixture { _, accounts, a, b, store ->
        val source = rollout(accounts, a)
        store.adopt(key, "project:chat", a, thread, source.path, workspace, goal = true)
        store.claim(key, "goal", a, workspace)
        store.complete(key, "goal", a, thread, source.path, workspace, goalStatus = "complete")
        store.claim(key, "b", b, workspace); store.importForTask(key, "b", b, workspace)
        rolloutFile(accounts, b).appendText("{\"type\":\"response_item\",\"payload\":{\"text\":\"B latest\"}}\n")
        store.complete(key, "b", b, thread, rolloutFile(accounts, b).path, workspace)
        store.claim(key, "resume", a, workspace, goalControl = true)
        store.importForTask(key, "resume", a, workspace); store.pinSavedGoal(key, "resume", a)
        assertEquals(a.id, store.goalOwner(key)); assertTrue(source.readText().contains("B latest"))
    }
    @Test fun processDeathGoalRecoveryRequiresExplicitSameOwnerAndKeepsGeneration() = fixture { root, accounts, a, b, store ->
        store.claim(key, "interrupted-goal", a, workspace, goal = true)
        val reopened = CodexConversationHandoff(root)
        assertThrows(IllegalStateException::class.java) { reopened.claim(key, "wrong", b, workspace, goalControl = true) }
        reopened.claim(key, "resume", a, workspace, goalControl = true)
        accounts.complete(a.id, "Reconnected")
        assertThrows(IllegalArgumentException::class.java) { reopened.claim(key, "after-reconnect", accounts.requireUsable(a.id), workspace, goalControl = true) }
    }

    @Test fun nativeChildThreadsPinTheirChatInsteadOfCopyingOnlyParentContext() = fixture { root, accounts, a, b, store ->
        val source = rollout(accounts, a)
        source.appendText("{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"spawn_agent\",\"arguments\":\"{}\"}}\n")
        store.adopt(key, "project:chat", a, thread, source.path, workspace)
        val reopened = CodexConversationHandoff(root)
        assertEquals(a.id, reopened.owner(key)?.accountId)
        assertEquals(a.id, reopened.childThreadOwner(key))
        assertThrows(IllegalArgumentException::class.java) { reopened.claim(key, "other", b, workspace) }
        reopened.claim(key, "same", a, workspace, chatKey = "project:chat")
        assertEquals(thread, reopened.importForTask(key, "same", a, workspace))
        assertFalse(rolloutFile(accounts, b).exists())
        reopened.clearChat("project:chat")
        assertNull(reopened.owner(key))
        assertNull(reopened.childThreadOwner(key))
    }

    @Test fun savedHistoricalGoalControlCannotClearUncertainOrdinaryWork() = fixture { root, accounts, a, b, store ->
        val source = rollout(accounts, a)
        store.adopt(key, "project:chat", a, thread, source.path, workspace, goal = true)
        store.claim(key, "goal", a, workspace)
        store.complete(key, "goal", a, thread, source.path, workspace, goalStatus = "complete")
        store.claim(key, "uncertain", b, workspace)
        assertThrows(IllegalStateException::class.java) { CodexConversationHandoff(root).claim(key, "status", a, workspace, goalControl = true) }
        assertThrows(IllegalStateException::class.java) { store.requireReady(key) }
    }

}
