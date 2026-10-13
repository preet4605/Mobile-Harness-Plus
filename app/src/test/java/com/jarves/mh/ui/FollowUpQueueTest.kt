package com.jarves.mh.ui

import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpQueueTest {
    private val project = Project(id = "p", name = "Project", description = "", language = "Kotlin")
    private fun item(id: String, projectId: String = "p", chatId: String = "chat") =
        QueuedFollowUp(id = id, projectId = projectId, chatId = chatId, prompt = "prompt $id")

    @Test fun queuedCodexAccountSurvivesSteerAndDrain() {
        val first = item("a").copy(codexAccountId = "account-a")
        val second = item("b").copy(codexAccountId = "account-b")
        val queue = FollowUpQueue.steerToFront(listOf(first, second), "b")
        val (next, rest) = FollowUpQueue.popFor(queue, "p", "chat")
        assertEquals("account-b", next?.codexAccountId)
        assertEquals("account-a", rest.single().codexAccountId)
    }

    @Test fun enqueueKeepsOrder() {
        val queue = FollowUpQueue.enqueue(FollowUpQueue.enqueue(emptyList(), item("a")), item("b"))
        assertEquals(listOf("a", "b"), queue.map { it.id })
    }

    @Test fun steerMovesItemToFrontAndKeepsTheRestInOrder() {
        val queue = listOf(item("a"), item("b"), item("c"))
        assertEquals(listOf("c", "a", "b"), FollowUpQueue.steerToFront(queue, "c").map { it.id })
        assertEquals(listOf("a", "b", "c"), FollowUpQueue.steerToFront(queue, "a").map { it.id })
    }

    @Test fun steerUnknownIdLeavesQueueUnchanged() {
        val queue = listOf(item("a"), item("b"))
        assertSame(queue, FollowUpQueue.steerToFront(queue, "missing"))
    }

    @Test fun removeDropsOnlyThatItem() {
        val queue = listOf(item("a"), item("b"), item("c"))
        assertEquals(listOf("a", "c"), FollowUpQueue.remove(queue, "b").map { it.id })
        assertEquals(listOf("a", "b", "c"), FollowUpQueue.remove(queue, "missing").map { it.id })
    }

    @Test fun popTakesTheFirstItemForTheProjectAndChat() {
        val attachment = ChatAttachment(displayName = "a.png", relativePath = "a.png", mimeType = "image/png", sizeBytes = 1)
        val first = item("a").copy(attachments = listOf(attachment))
        val (next, rest) = FollowUpQueue.popFor(listOf(first, item("b")), "p", "chat")
        assertEquals(first, next)
        assertEquals(listOf(attachment), next?.attachments)
        assertEquals(listOf("b"), rest.map { it.id })
    }

    @Test fun popDropsFollowUpsQueuedForAnotherProjectOrChat() {
        val queue = listOf(item("x", projectId = "other"), item("y", chatId = "other-chat"), item("a"))
        val (next, rest) = FollowUpQueue.popFor(queue, "p", "chat")
        assertEquals("a", next?.id)
        assertTrue(rest.isEmpty())
    }

    @Test fun popOnEmptyOrForeignQueueReturnsNothing() {
        assertNull(FollowUpQueue.popFor(emptyList(), "p", "chat").first)
        val (next, rest) = FollowUpQueue.popFor(listOf(item("x", projectId = "other")), "p", "chat")
        assertNull(next)
        assertTrue(rest.isEmpty())
    }

    @Test fun chatKeyMatchesTheSupervisorDefault() {
        assertEquals("default", FollowUpQueue.chatKey(null))
        assertEquals("chat", FollowUpQueue.chatKey("chat"))
    }

    @Test fun nextStartsOnlyWhenTheChatIsFree() {
        val ready = AppUiState(activeProject = project, activeChatId = "chat", queuedFollowUps = listOf(item("a")))
        assertTrue(FollowUpQueue.canStartNext(ready))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(queuedFollowUps = emptyList())))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(activeProject = null)))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(isRunning = true)))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(isStopping = true)))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(chatLoading = true)))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(projectTerminalRunning = true)))
        assertFalse(FollowUpQueue.canStartNext(ready.copy(androidBuildRunning = true)))
    }
}
