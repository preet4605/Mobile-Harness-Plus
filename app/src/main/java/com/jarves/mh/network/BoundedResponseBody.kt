package com.jarves.mh.network

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Reads the whole stream as UTF-8 text, failing with [IOException] as soon as more than
 * [maxBytes] bytes arrive, so an oversized or hostile response cannot exhaust memory.
 */
internal fun InputStream.readBoundedText(maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() + count > maxBytes) throw IOException("Response body exceeds the $maxBytes-byte limit")
        output.write(buffer, 0, count)
    }
    return output.toString(Charsets.UTF_8.name())
}
