package com.jarves.mh.ui

import com.jarves.mh.model.RuntimeEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** UI updates per second while a reply streams: one merged delta per window instead of one per token. */
internal const val ASSISTANT_DELTA_WINDOW_MS = 50L

/**
 * Merges consecutive [RuntimeEvent.AssistantDelta]s of one session into a single delta,
 * emitted at most [windowMillis] after its first token. Any other event (or a delta from
 * another session) flushes the pending text first, so the order of everything the
 * ViewModel sees is unchanged; only the number of state updates per reply drops.
 */
internal fun Flow<RuntimeEvent>.coalesceAssistantDeltas(
    windowMillis: Long = ASSISTANT_DELTA_WINDOW_MS,
): Flow<RuntimeEvent> = channelFlow {
    val mutex = Mutex()
    var sessionId: String? = null
    val text = StringBuilder()
    var timer: Job? = null

    suspend fun flushLocked() {
        val id = sessionId ?: return
        timer?.cancel()
        timer = null
        sessionId = null
        val merged = text.toString()
        text.setLength(0)
        send(RuntimeEvent.AssistantDelta(id, merged))
    }

    collect { event ->
        mutex.withLock {
            if (event is RuntimeEvent.AssistantDelta) {
                if (sessionId != null && sessionId != event.sessionId) flushLocked()
                if (sessionId == null) {
                    sessionId = event.sessionId
                    timer = launch {
                        delay(windowMillis)
                        mutex.withLock {
                            // Detach first so the flush does not cancel the coroutine running it.
                            timer = null
                            flushLocked()
                        }
                    }
                }
                text.append(event.text)
            } else {
                flushLocked()
                send(event)
            }
        }
    }
    mutex.withLock { flushLocked() }
}
