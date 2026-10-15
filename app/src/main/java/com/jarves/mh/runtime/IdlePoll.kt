package com.jarves.mh.runtime

import kotlinx.coroutines.delay

/**
 * Idle wait for output read loops. A task spends most of its time waiting on the model, so
 * a fixed 50 ms poll wakes the CPU 20 times a second for nothing. The wait starts at [minMs]
 * and doubles while nothing new arrives, up to [maxMs]; [reset] after each read keeps
 * streaming output as responsive as before.
 *
 * With the process's [signal], a wait ends as soon as new output is written, so it can back off
 * further, up to [signalMaxMs]; once the output stream has ended, it polls as without a signal
 * until the loop sees the process exit.
 */
internal class IdlePoll(
    private val minMs: Long,
    private val maxMs: Long = minMs * 5,
    private val signal: OutputSignal? = null,
    private val signalMaxMs: Long = maxMs,
) {
    private var next = minMs

    suspend fun await() {
        val wake = signal
        if (wake == null || wake.ended) {
            delay(next.coerceAtMost(maxMs))
            next = (next * 2).coerceAtMost(maxMs)
        } else if (wake.await(next)) {
            next = minMs
        } else {
            next = (next * 2).coerceAtMost(signalMaxMs)
        }
    }

    fun reset() {
        next = minMs
    }
}
