package com.jarves.mh.ui

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Choreographer
import com.jarves.mh.BuildConfig

/**
 * Debug-build timing for the project-open and glass paths. Logs go to the [TAG] logcat tag, and the
 * same spans are Trace sections for a system trace. Release builds write no logs. Trace calls are
 * wrapped because JVM unit tests run without the Android framework.
 */
internal object OpenPerf {
    const val TAG = "MhOpenPerf"

    fun nowMs(): Long = SystemClock.uptimeMillis()

    fun log(message: String) {
        if (BuildConfig.DEBUG) runCatching { Log.d(TAG, message) }
    }

    /** Runs [block] before the next frame is drawn. Skipped where the framework is stubbed (JVM unit tests). */
    fun onNextFrame(block: () -> Unit) {
        if (BuildConfig.DEBUG) runCatching { Choreographer.getInstance().postFrameCallback { block() } }
    }

    /** Runs [block] inside a Trace section named [name]. Call only from one thread, with no suspension inside. */
    inline fun <T> span(name: String, block: () -> T): T {
        runCatching { Trace.beginSection(name) }
        try {
            return block()
        } finally {
            runCatching { Trace.endSection() }
        }
    }
}
