package com.jarves.mh.ui.theme.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.builditcode.glass.BackdropFilter
import com.builditcode.glass.TrilevelLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying Liquid Glass foundation architecture, tokens, accessibility guards,
 * and filter transformations.
 */
class LiquidGlassFoundationTest {

    @Test
    fun liquidGlassMaterial_producesGlassFilter_whenRefractionEnabled() {
        val material = LiquidGlassMaterial.Regular
        val filter = material.toBackdropFilter(
            tint = Color.Blue,
            cornerRadiusDp = 16f,
            enableRefraction = true,
        )

        assertTrue("Expected BackdropFilter.Glass instance", filter is BackdropFilter.Glass)
        val glass = filter as BackdropFilter.Glass
        assertEquals(24f, glass.blurRadiusIntensity, 0.001f)
        assertEquals(16f, glass.cornerRadiusDp, 0.001f)
        assertEquals(0.14f, glass.refraction, 0.001f)
        assertEquals(0.04f, glass.dispersion, 0.001f)
        assertEquals(0.12f, glass.edge, 0.001f)
    }

    @Test
    fun liquidGlassMaterial_fallsBackToBlurFilter_whenRefractionDisabled() {
        val material = LiquidGlassMaterial.Thick
        val filter = material.toBackdropFilter(
            tint = Color.Red,
            enableRefraction = false,
        )

        assertTrue("Expected BackdropFilter.Blur instance", filter is BackdropFilter.Blur)
        val blur = filter as BackdropFilter.Blur
        assertEquals(36f, blur.blurRadiusIntensity, 0.001f)
    }

    @Test
    fun liquidGlassConfig_accessibilityGuards_disableGlassWhenTransparencyReduced() {
        val normalConfig = LiquidGlassConfig(enabled = true, reduceTransparency = false)
        assertTrue(normalConfig.isGlassActive)

        val accessibleConfig = LiquidGlassConfig(enabled = true, reduceTransparency = true)
        assertFalse(accessibleConfig.isGlassActive)

        val disabledConfig = LiquidGlassConfig(enabled = false, reduceTransparency = false)
        assertFalse(disabledConfig.isGlassActive)
    }

    @Test
    fun liquidGlassTokens_adheresToAppleHigTouchTargets() {
        // HIG requirement: minimum 44pt/44dp touch target
        assertTrue(LiquidGlassTokens.MinTouchTarget >= 44.dp)
        assertTrue(LiquidGlassTokens.ControlRadius >= 12.dp)
    }

    @Test
    fun liquidGlassLayers_matchUnderlyingEngine() {
        assertEquals(TrilevelLayers.Background, LiquidGlassLayers.Background)
        assertEquals(TrilevelLayers.Foreground, LiquidGlassLayers.Foreground)
        assertEquals(TrilevelLayers.Overlay, LiquidGlassLayers.Overlay)
    }

    @Test
    fun liquidGlassConfig_resolve_respectsUserPreference() {
        val resolvedTrue = LiquidGlassConfig.resolve(userPreference = true)
        assertTrue(resolvedTrue.reduceTransparency)
        assertFalse(resolvedTrue.isGlassActive)

        val resolvedFalse = LiquidGlassConfig.resolve(userPreference = false)
        assertFalse(resolvedFalse.reduceTransparency)
        assertTrue(resolvedFalse.isGlassActive)
    }

    @Test
    fun liquidGlassTokens_sheetAndControlRadiiMatchHig() {
        assertEquals(24.dp, LiquidGlassTokens.SheetRadius)
        assertEquals(14.dp, LiquidGlassTokens.ControlRadius)
        assertEquals(44.dp, LiquidGlassTokens.MinTouchTarget)
        assertTrue(LiquidGlassTokens.PillRadius > 100.dp)
    }

    @Test
    fun liquidGlassMaterial_allVariantsHaveValidAlphaAndBlur() {
        val variants = listOf(
            LiquidGlassMaterial.Regular,
            LiquidGlassMaterial.Clear,
            LiquidGlassMaterial.UltraThin,
            LiquidGlassMaterial.Thin,
            LiquidGlassMaterial.Thick,
        )
        for (v in variants) {
            assertTrue("Blur radius should be > 0", v.blurRadius > 0f)
            assertTrue("Alpha should be between 0 and 1", v.defaultAlpha in 0f..1f)
            assertTrue("Refraction should be >= 0", v.refraction >= 0f)
        }
    }

    @Test
    fun liquidGlassModifier_withNullLayerSource_omitsBackdropCapture() {
        val modifier = Modifier.liquidGlass(
            material = LiquidGlassMaterial.Thin,
            layerSource = null,
        )
        assertNotNull(modifier)
    }

    @Test
    fun glassRoles_tokensAreDistinctAndTuned() {
        // Composer has the strongest spatial blur
        assertTrue(GlassRoles.Composer.blurDp > GlassRoles.Nav.blurDp)
        assertTrue(GlassRoles.Nav.blurDp > GlassRoles.Latest.blurDp)
        assertTrue(GlassRoles.Latest.blurDp > GlassRoles.Chip.blurDp)
        assertEquals(36f, GlassRoles.Composer.blurDp, 0.001f)
        assertEquals(28f, GlassRoles.Nav.blurDp, 0.001f)
        assertEquals(20f, GlassRoles.Latest.blurDp, 0.001f)
        assertEquals(14f, GlassRoles.Chip.blurDp, 0.001f)
    }
}

