package com.jarves.mh.ui.theme.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GlassEngineTest {

    private val density = Density(1f)

    @Test
    fun tier_followsDeviceAccessibilityAndSampling() {
        val on = LiquidGlassConfig()
        assertEquals(GlassTier.Solid, glassTier(on.copy(reduceTransparency = true), samplesBackdrop = true, sdk = 34))
        assertEquals(GlassTier.Solid, glassTier(on.copy(increaseContrast = true), samplesBackdrop = true, sdk = 34))
        assertEquals(GlassTier.Solid, glassTier(on, samplesBackdrop = true, sdk = 30))
        assertEquals(GlassTier.Tint, glassTier(on, samplesBackdrop = false, sdk = 30))
        assertEquals(GlassTier.Blur, glassTier(on, samplesBackdrop = true, sdk = 31))
        assertEquals(GlassTier.Blur, glassTier(on.copy(enableRefraction = false), samplesBackdrop = true, sdk = 34))
    }

    @Test
    fun sizeClass_scalesWithTheShortSide() {
        assertEquals(GlassSizeClass.Small, GlassSizeClass.of(Size(44f, 44f), density))
        assertEquals(GlassSizeClass.Medium, GlassSizeClass.of(Size(340f, 64f), density))
        assertEquals(GlassSizeClass.Large, GlassSizeClass.of(Size(393f, 420f), density))
        val order = GlassSizeClass.entries
        order.zipWithNext().forEach { (a, b) ->
            assertTrue(a.lensDp < b.lensDp)
            assertTrue(a.shadowDp < b.shadowDp)
            assertTrue("small shapes lift more", a.liftDp >= b.liftDp)
        }
    }

    @Test
    fun light_restsTopLeadingAndLeansTowardTheFinger() {
        val size = Size(200f, 50f)
        val rest = glassLightDirection(size, Offset.Unspecified, 0f)
        assertTrue(rest.x < 0f && rest.y < 0f)
        assertEquals(1f, rest.getDistance(), 1e-4f)
        val pressedRight = glassLightDirection(size, Offset(190f, 25f), 1f)
        assertTrue("light swings toward a press on the right", pressedRight.x > rest.x)
        assertEquals(1f, pressedRight.getDistance(), 1e-4f)
    }

    @Test
    fun scrolling_recapturesAboutThirtyTimesAPastSecondAtReducedResolution() {
        val config = LiquidGlassConfig()
        assertEquals(SCROLL_RECAPTURE_INTERVAL_MS, config.debounceMs)
        assertEquals(0.5f, config.scaleFactor)
        // A scroll invalidation 4 ms after the last capture waits for the rest of the interval.
        assertEquals(
            SCROLL_RECAPTURE_INTERVAL_MS - 4L,
            backdropRecaptureDelayMs(1_004L, 1_000L, invalidated = true, resized = false, minIntervalMs = config.debounceMs, contentIntervalMs = CONTENT_RECAPTURE_INTERVAL_MS),
        )
        // Once the interval has passed, the next scroll capture is immediate.
        assertEquals(
            0L,
            backdropRecaptureDelayMs(1_000L + SCROLL_RECAPTURE_INTERVAL_MS, 1_000L, invalidated = true, resized = false, minIntervalMs = config.debounceMs, contentIntervalMs = CONTENT_RECAPTURE_INTERVAL_MS),
        )
        // Content animating inside the source stays throttled.
        assertTrue(
            backdropRecaptureDelayMs(1_004L, 1_000L, invalidated = false, resized = false, minIntervalMs = 0L, contentIntervalMs = CONTENT_RECAPTURE_INTERVAL_MS) > 0L,
        )
    }

    @Test
    fun shaderUniforms_matchTheKotlinSide() {
        val declared = Regex("""uniform\s+\w+\s+(\w+)""").findAll(GlassShaderSource).map { it.groupValues[1] }.toSet()
        val engine = listOf("src/main/java/com/jarves/mh/ui/theme/glass/GlassEngine.kt", "app/src/main/java/com/jarves/mh/ui/theme/glass/GlassEngine.kt")
            .map(::File).first { it.isFile }.readText()
        val set = Regex("""setFloatUniform\("(\w+)"""").findAll(engine).map { it.groupValues[1] }.toSet()
        assertEquals("every uniform is set, and only declared ones", declared - "content", set)
        assertTrue("the lens samples the blurred content", GlassShaderSource.contains("content.eval("))
    }
}
