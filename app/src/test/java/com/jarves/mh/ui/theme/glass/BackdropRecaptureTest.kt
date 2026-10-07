package com.jarves.mh.ui.theme.glass

import org.junit.Assert.assertEquals
import org.junit.Test

class BackdropRecaptureTest {

    private fun delay(lastAt: Long, now: Long, invalidated: Boolean = false, resized: Boolean = false) =
        backdropRecaptureDelayMs(
            nowMs = now,
            lastCaptureAtMs = lastAt,
            invalidated = invalidated,
            resized = resized,
            minIntervalMs = 16L,
            contentIntervalMs = CONTENT_RECAPTURE_INTERVAL_MS,
        )

    @Test
    fun firstCaptureAndResize_areImmediate() {
        assertEquals(0L, delay(NO_CAPTURE, 1_000L))
        assertEquals(0L, delay(1_000L, 1_001L, resized = true))
    }

    @Test
    fun invalidations_areCappedAtMinInterval() {
        assertEquals(12L, delay(1_000L, 1_004L, invalidated = true))
        assertEquals(0L, delay(1_000L, 1_016L, invalidated = true))
    }

    @Test
    fun contentOnlyRedraws_areThrottled() {
        assertEquals(CONTENT_RECAPTURE_INTERVAL_MS - 8L, delay(1_000L, 1_008L))
        assertEquals(0L, delay(1_000L, 1_000L + CONTENT_RECAPTURE_INTERVAL_MS))
    }
}
