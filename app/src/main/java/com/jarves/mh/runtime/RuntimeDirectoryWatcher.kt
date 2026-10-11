package com.jarves.mh.runtime

import android.os.FileObserver
import java.io.File
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One lifecycle-owned watcher; a conflated signal coalesces bursts without idle scans. */
internal suspend fun watchRuntimeDirectory(directory: File, drain: suspend () -> Unit) {
    directory.mkdirs()
    val events = Channel<Unit>(Channel.CONFLATED)
    val observer = object : FileObserver(directory.absolutePath, CLOSE_WRITE or MOVED_TO) {
        override fun onEvent(event: Int, path: String?) { events.trySend(Unit) }
    }
    observer.startWatching()
    events.trySend(Unit)
    try {
        for (event in events) {
            currentCoroutineContext().ensureActive()
            drain()
        }
    } finally {
        observer.stopWatching()
        events.close()
    }
}
