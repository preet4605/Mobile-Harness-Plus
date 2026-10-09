package com.jarves.mh.runtime

import java.io.InputStream
import java.io.OutputStream

/** Drain every byte even after storage reaches its budget, so children cannot block on a pipe. */
internal object BoundedProcessCapture {
    fun copy(source: InputStream, destination: OutputStream, limit: Long, onLimit: () -> Unit) {
        require(limit >= 0)
        val bytes = ByteArray(8192)
        var written = 0L
        var overflow = false
        while (true) {
            val count = source.read(bytes)
            if (count < 0) break
            val kept = minOf(count.toLong(), limit - written).toInt()
            if (kept > 0) {
                destination.write(bytes, 0, kept)
                destination.flush()
                written += kept
            }
            if (kept < count && !overflow) {
                overflow = true
                onLimit()
            }
        }
    }
}
