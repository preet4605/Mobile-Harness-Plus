package com.jarves.mh.ui

/**
 * Sanitized tail of a terminal stream, built chunk by chunk. Each chunk is sanitized once,
 * so a long build log costs O(n) in total instead of re-sanitizing the whole buffer per
 * chunk, and memory stays bounded by [maxChars]. An escape sequence split across two chunks
 * is held back (at most [MAX_CARRY] chars) until it is complete.
 */
internal class TerminalOutputTail(private val maxChars: Int) {
    private val clean = StringBuilder()
    private var carry = ""

    fun append(chunk: String) {
        val text = carry + chunk
        var cut = text.length
        val escape = text.lastIndexOf('\u001b')
        if (escape >= 0 && text.length - escape < MAX_CARRY) {
            val match = ANSI_TERMINAL_SEQUENCE.find(text, escape)
            if (match == null || match.range.first != escape) cut = escape
        }
        clean.append(sanitizeTerminalOutput(text.substring(0, cut)))
        carry = text.substring(cut)
        // Trim in large steps so the copy is amortized.
        if (clean.length > maxChars * 2) clean.delete(0, clean.length - maxChars)
    }

    /** Flushes a held-back partial sequence at the end of the stream. */
    fun finish() {
        if (carry.isEmpty()) return
        clean.append(sanitizeTerminalOutput(carry))
        carry = ""
    }

    /** The last [maxChars] sanitized characters. */
    fun text(): String = if (clean.length > maxChars) clean.substring(clean.length - maxChars) else clean.toString()

    /** The most recent [chars] sanitized characters, for prompt and URL checks. */
    fun recent(chars: Int): String = clean.substring((clean.length - chars).coerceAtLeast(0))

    companion object {
        const val MAX_CARRY = 512
    }
}

/** Live terminal output is published at most this often; the final output is always published. */
internal const val TERMINAL_PUBLISH_INTERVAL_MS = 100L

/** Idle polling starts here and backs off to [TERMINAL_IDLE_POLL_MAX_MS] while nothing new arrives. */
internal const val TERMINAL_IDLE_POLL_MIN_MS = 50L
internal const val TERMINAL_IDLE_POLL_MAX_MS = 250L

/** Lines per block in [terminalOutputBlocks]. */
internal const val TERMINAL_BLOCK_LINES = 64

/**
 * [text] cut at every [linesPerBlock]th line break, counted from its start, without the break
 * itself: stacked one under another the blocks read exactly like [text]. Appending to [text] leaves
 * every block but the last unchanged.
 */
internal fun terminalOutputBlocks(text: String, linesPerBlock: Int = TERMINAL_BLOCK_LINES): List<String> {
    val blocks = ArrayList<String>(text.length / (linesPerBlock * 40) + 1)
    var start = 0
    var lines = 0
    for (index in text.indices) {
        if (text[index] != '\n') continue
        lines++
        if (lines == linesPerBlock) {
            blocks += text.substring(start, index)
            start = index + 1
            lines = 0
        }
    }
    blocks += text.substring(start)
    return blocks
}
