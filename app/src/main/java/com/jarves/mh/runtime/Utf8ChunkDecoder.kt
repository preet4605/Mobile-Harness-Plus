package com.jarves.mh.runtime

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction

/**
 * Decodes UTF-8 that arrives in chunks. A character split across two chunks is held back until its
 * remaining bytes arrive, instead of becoming replacement characters on both sides of the cut.
 * Malformed bytes still become U+FFFD, as with [ByteArray.decodeToString].
 */
internal class Utf8ChunkDecoder {
    private val decoder: CharsetDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    /** The first bytes (at most 3) of a character the previous chunk cut off. */
    private var carry = ByteArray(0)

    fun decode(bytes: ByteArray, count: Int = bytes.size): String {
        val input = if (carry.isEmpty()) {
            ByteBuffer.wrap(bytes, 0, count)
        } else {
            ByteBuffer.allocate(carry.size + count).apply {
                put(carry)
                put(bytes, 0, count)
                flip()
            }
        }
        // UTF-8 never decodes to more chars than it has bytes.
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false)
        carry = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }

    /** Ends the stream: a character still incomplete becomes U+FFFD. */
    fun finish(): String {
        if (carry.isEmpty()) return ""
        val input = ByteBuffer.wrap(carry)
        carry = ByteArray(0)
        val output = CharBuffer.allocate(input.remaining() + 2)
        decoder.decode(input, output, true)
        decoder.flush(output)
        decoder.reset()
        output.flip()
        return output.toString()
    }
}
