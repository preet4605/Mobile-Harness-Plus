package com.jarves.mh.runtime.task

import java.util.ArrayDeque

/**
 * Memory-bounded rolling buffer for process output and terminal streams.
 * Prevents OutOfMemoryErrors from long-running or verbose agent processes
 * while preserving recent lines and the latest useful diagnostic errors.
 */
class BoundedOutputBuffer(
    val maxLines: Int = DEFAULT_MAX_LINES,
    val maxBytes: Int = DEFAULT_MAX_BYTES
) {
    companion object {
        const val DEFAULT_MAX_LINES = 500
        const val DEFAULT_MAX_BYTES = 128 * 1024 // 128 KB in memory
    }

    private val lines = ArrayDeque<String>(maxLines)
    private var currentByteCount: Int = 0
    @Volatile private var lastErrorCandidate: String? = null

    @Synchronized
    fun appendLine(line: String) {
        val trimmed = line.trimEnd('\r')
        if (isErrorLine(trimmed)) {
            lastErrorCandidate = trimmed
        }

        val lineBytes = trimmed.length * 2 // approx memory bytes
        lines.addLast(trimmed)
        currentByteCount += lineBytes

        while (lines.size > maxLines || currentByteCount > maxBytes) {
            if (lines.isEmpty()) break
            val removed = lines.removeFirst()
            currentByteCount -= (removed.length * 2)
        }
    }

    @Synchronized
    fun appendText(chunk: String) {
        var start = 0
        while (start < chunk.length) {
            val newline = chunk.indexOf('\n', start)
            if (newline >= 0) {
                appendLine(chunk.substring(start, newline))
                start = newline + 1
            } else {
                appendLine(chunk.substring(start))
                break
            }
        }
    }

    @Synchronized
    fun getLines(): List<String> = lines.toList()

    @Synchronized
    fun getRecentOutput(limit: Int = 50): String {
        return lines.toList().takeLast(limit).joinToString("\n")
    }

    @Synchronized
    fun getLastError(): String? = lastErrorCandidate

    @Synchronized
    fun clear() {
        lines.clear()
        currentByteCount = 0
        lastErrorCandidate = null
    }

    val lineCount: Int
        @Synchronized get() = lines.size

    val byteCount: Int
        @Synchronized get() = currentByteCount

    private fun isErrorLine(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("error:") ||
                lower.contains("fatal:") ||
                lower.contains("exception:") ||
                lower.contains("failed:") ||
                lower.contains("traceback (most recent call last)") ||
                lower.contains("syntaxerror")
    }
}
