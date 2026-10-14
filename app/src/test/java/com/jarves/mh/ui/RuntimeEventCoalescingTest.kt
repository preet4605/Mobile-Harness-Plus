package com.jarves.mh.ui

import com.jarves.mh.model.RuntimeEvent
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEventCoalescingTest {
    @Test fun burstOfTokensBecomesOneDelta() = runBlocking {
        val events = flow {
            repeat(50) { emit(RuntimeEvent.AssistantDelta("s", "t$it ")) }
        }.coalesceAssistantDeltas(windowMillis = 10_000).toList()
        assertEquals(1, events.size)
        assertEquals((0 until 50).joinToString("") { "t$it " }, (events.single() as RuntimeEvent.AssistantDelta).text)
    }

    @Test fun otherEventsFlushPendingTextFirstAndKeepOrder() = runBlocking {
        val events = flow {
            emit(RuntimeEvent.AssistantDelta("s", "a"))
            emit(RuntimeEvent.AssistantDelta("s", "b"))
            emit(RuntimeEvent.ToolStarted("s", "Bash", "ls"))
            emit(RuntimeEvent.AssistantDelta("s", "c"))
            emit(RuntimeEvent.SessionCompleted("s"))
        }.coalesceAssistantDeltas(windowMillis = 10_000).toList()
        assertEquals(
            listOf(
                RuntimeEvent.AssistantDelta("s", "ab"),
                RuntimeEvent.ToolStarted("s", "Bash", "ls"),
                RuntimeEvent.AssistantDelta("s", "c"),
                RuntimeEvent.SessionCompleted("s"),
            ),
            events,
        )
    }

    @Test fun sessionChangeFlushesPreviousSession() = runBlocking {
        val events = flow {
            emit(RuntimeEvent.AssistantDelta("old", "x"))
            emit(RuntimeEvent.AssistantDelta("new", "y"))
        }.coalesceAssistantDeltas(windowMillis = 10_000).toList()
        assertEquals(listOf(RuntimeEvent.AssistantDelta("old", "x"), RuntimeEvent.AssistantDelta("new", "y")), events)
    }

    @Test fun windowFlushesWhileStreamStaysOpen() = runBlocking {
        val events = flow {
            emit(RuntimeEvent.AssistantDelta("s", "first"))
            delay(300)
            emit(RuntimeEvent.AssistantDelta("s", "second"))
        }.coalesceAssistantDeltas(windowMillis = 20).toList()
        assertEquals(listOf(RuntimeEvent.AssistantDelta("s", "first"), RuntimeEvent.AssistantDelta("s", "second")), events)
    }

    @Test fun allRuntimeCollectorsCoalesceDeltas() {
        val source = File("src/main/java/com/jarves/mh/ui/MainViewModel.kt").readText()
        for (bridge in listOf("claudeRuntime", "dshRuntime", "antigravityRuntime", "codexRuntime")) {
            assertTrue("$bridge must coalesce deltas", source.contains("$bridge.events.coalesceAssistantDeltas().collect"))
        }
    }
}
