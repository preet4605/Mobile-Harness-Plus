package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8ChunkDecoderTest {
    @Test fun characterSplitAcrossChunksDecodesWhole() {
        val text = "build ✓ — 日本語 🚀 done"
        val bytes = text.toByteArray()
        // Every cut point, including ones inside 2-, 3- and 4-byte characters.
        for (cut in 0..bytes.size) {
            val decoder = Utf8ChunkDecoder()
            val decoded = decoder.decode(bytes.copyOfRange(0, cut)) +
                decoder.decode(bytes.copyOfRange(cut, bytes.size)) + decoder.finish()
            assertEquals("cut at $cut", text, decoded)
        }
    }

    @Test fun decodesOnlyTheCountedBytes() {
        val buffer = "héllo".toByteArray().copyOf(32)
        assertEquals("héllo", Utf8ChunkDecoder().decode(buffer, "héllo".toByteArray().size))
    }

    @Test fun truncatedCharacterAtTheEndBecomesReplacement() {
        val decoder = Utf8ChunkDecoder()
        val rocket = "🚀".toByteArray()
        assertEquals("a", decoder.decode(byteArrayOf('a'.code.toByte()) + rocket.copyOf(2)))
        assertEquals("�", decoder.finish())
    }

    @Test fun malformedBytesBecomeReplacementLikeDecodeToString() {
        val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0x62)
        assertEquals(bytes.decodeToString(), Utf8ChunkDecoder().decode(bytes))
    }
}
