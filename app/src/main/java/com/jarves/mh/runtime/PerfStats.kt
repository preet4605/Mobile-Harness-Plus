package com.jarves.mh.runtime

import android.os.Trace
import android.util.Log
import com.jarves.mh.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Dev-app counters for the hot paths of a task: how often each ran and how long it took in total.
 * [time] is also a Trace section, so a system trace shows the same names. [logSummary] prints one
 * `MhPerf` logcat line per task and starts over. The stable app records nothing.
 */
internal object PerfStats {
    const val TAG = "MhPerf"

    private class Stat {
        val count = AtomicLong()
        val nanos = AtomicLong()
    }

    private val stats = ConcurrentHashMap<String, Stat>()

    val enabled: Boolean get() = BuildConfig.DEV_BUILD

    fun count(name: String) {
        if (enabled) stats.getOrPut(name, ::Stat).count.incrementAndGet()
    }

    /** Runs [block] inside a Trace section named [name]. Call with no suspension inside. */
    inline fun <T> time(name: String, block: () -> T): T {
        if (!enabled) return block()
        runCatching { Trace.beginSection(name) }
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, System.nanoTime() - start)
            runCatching { Trace.endSection() }
        }
    }

    @PublishedApi
    internal fun record(name: String, nanos: Long) {
        val stat = stats.getOrPut(name, ::Stat)
        stat.count.incrementAndGet()
        stat.nanos.addAndGet(nanos)
    }

    /** One line such as `task done: event 812x 41 ms, chat.save 30x 95 ms`, then the counters reset. */
    fun summary(): String = stats.entries.sortedBy { it.key }.joinToString(", ") { (name, stat) ->
        val ms = stat.nanos.get() / 1_000_000
        if (stat.nanos.get() == 0L) "$name ${stat.count.get()}x" else "$name ${stat.count.get()}x $ms ms"
    }

    fun logSummary(label: String) {
        if (!enabled || stats.isEmpty()) return
        val line = "$label: ${summary()}"
        stats.clear()
        runCatching { Log.i(TAG, line) }
    }
}
