package com.jarves.mh.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.ui.theme.glass.LiquidGlassMaterial
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards for the app-wide Liquid design language tokens (colour, type, shape, glass wash). */
class LiquidDesignLanguageTest {

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return maxOf(la, lb) / minOf(la, lb)
    }

    private fun assertAA(name: String, fg: Color, bg: Color) {
        val ratio = contrast(fg, bg)
        assertTrue("$name contrast $ratio < 4.5", ratio >= 4.5f)
    }

    private fun assertReadable(scheme: ColorScheme, mode: String) {
        assertAA("$mode onPrimary/primary", scheme.onPrimary, scheme.primary)
        assertAA("$mode onSurface/surface", scheme.onSurface, scheme.surface)
        assertAA("$mode onBackground/background", scheme.onBackground, scheme.background)
        assertAA("$mode onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface)
        assertAA("$mode onSurfaceVariant/background", scheme.onSurfaceVariant, scheme.background)
        assertAA("$mode onError/error", scheme.onError, scheme.error)
    }

    @Test
    fun colorSchemes_meetTextContrast() {
        assertReadable(DarkColors, "dark")
        assertReadable(LightColors, "light")
    }

    @Test
    fun colorSchemes_haveNoTonalTintAndNeutralContainers() {
        for (scheme in listOf(DarkColors, LightColors)) {
            // No accent cast on elevated M3 surfaces.
            assertEquals(scheme.surface, scheme.surfaceTint)
            // Dialog/sheet/menu containers are neutral greys (equal RGB), never M3 baseline purple.
            listOf(
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            ).forEach { c ->
                assertTrue("container $c is not neutral", c.blue - c.red in 0f..0.03f && c.green - c.red in 0f..0.03f)
            }
        }
    }

    @Test
    fun palette_matchesSchemeSurfaces() {
        assertEquals(DarkColors.background, PocketPalette.darkCanvas)
        assertEquals(LightColors.background, PocketPalette.lightCanvas)
        assertEquals(DarkColors.surface, PocketPalette.darkCardSurface)
        assertEquals(LightColors.surface, PocketPalette.lightCardSurface)
        assertEquals(DarkColors.surfaceVariant, PocketPalette.darkTileSurface)
        assertEquals(LightColors.surfaceVariant, PocketPalette.lightTileSurface)
    }

    @Test
    fun typography_followsHierarchy() {
        val t = PocketTypography
        val titles = listOf(t.headlineLarge, t.headlineMedium, t.headlineSmall, t.titleLarge, t.titleMedium, t.titleSmall)
        val body = listOf(t.bodyLarge, t.bodyMedium, t.bodySmall)
        (titles.zipWithNext() + body.zipWithNext()).forEach { (a, b) -> assertTrue(a.fontSize.value > b.fontSize.value) }
        // Headlines out-rank body text.
        assertTrue(t.titleMedium.fontSize.value > t.bodyLarge.fontSize.value)
        assertEquals(FontWeight.Bold, t.headlineMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, t.titleMedium.fontWeight)
        assertEquals(17.sp, t.titleMedium.fontSize)
        // Body sizes stay at the dense M3 values; nothing drops below the 11sp floor.
        assertEquals(16.sp, t.bodyLarge.fontSize)
        assertEquals(14.sp, t.bodyMedium.fontSize)
        assertTrue(t.labelSmall.fontSize.value >= 11f)
        // Tight tracking on titles (no M3 positive letter spacing).
        assertTrue(t.titleMedium.letterSpacing.value <= 0f)
        assertTrue(t.bodyLarge.letterSpacing.value <= 0f)
    }

    @Test
    fun shapes_shareConcentricRadiusScale() {
        assertEquals(RoundedCornerShape(PocketRadius.md), PocketShapes.medium)
        assertEquals(RoundedCornerShape(PocketRadius.lg), PocketShapes.large)
        assertEquals(RoundedCornerShape(PocketRadius.xl), PocketShapes.extraLarge)
        assertEquals(LiquidGlassTokens.ControlRadius, PocketRadius.md)
        assertEquals(LiquidGlassTokens.SheetRadius, PocketRadius.xl)
        val scale = listOf(PocketRadius.xs, PocketRadius.sm, PocketRadius.md, PocketRadius.lg, PocketRadius.xl)
        scale.zipWithNext().forEach { (a, b) -> assertTrue(a < b) }
        assertTrue(LiquidGlassTokens.MinTouchTarget >= 44.dp)
    }

    @Test
    fun glassWash_isNeutralAndThickensWithMaterial() {
        for (isDark in listOf(true, false)) {
            val order = listOf(
                LiquidGlassMaterial.Clear,
                LiquidGlassMaterial.UltraThin,
                LiquidGlassMaterial.Thin,
                LiquidGlassMaterial.Regular,
                LiquidGlassMaterial.Thick,
            ).map { LiquidGlassTokens.wash(it, isDark) }
            order.zipWithNext().forEach { (a, b) -> assertTrue(a.alpha < b.alpha) }
            order.forEach { c -> assertTrue("glass wash must be neutral", c.red == c.blue || (c.blue - c.red) < 0.03f) }
        }
    }
}
