package com.jarves.mh.runtime

import kotlinx.coroutines.delay

/**
 * Idle wait for output read loops. A task spends most of its time waiting on the model, so
 * a fixed 50 ms poll wakes the CPU 20 times a second for nothing. The wait starts at [minMs]
 * and doubles while nothing new arrives, up to [maxMs]; [reset] after each read keeps
 * streaming output as responsive as before.
 */
internal class IdlePoll(private val minMs: Long, private val maxMs: Long = minMs * 5) {
    private var next = minMs

    suspend fun await() {
        delay(next)
        next = (next * 2).coerceAtMost(maxMs)
    }

    fun reset() {
        next = minMs
    }
}
