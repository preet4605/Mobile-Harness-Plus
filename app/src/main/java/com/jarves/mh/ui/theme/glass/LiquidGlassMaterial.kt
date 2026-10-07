package com.jarves.mh.ui.theme.glass

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.builditcode.glass.BackdropFilter
import com.jarves.mh.ui.theme.PocketBlue
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange

/**
 * Apple HIG Liquid Glass & Standard Material hierarchy.
 *
 * Implements the Apple Human Interface Guidelines specification:
 * - Liquid Glass for floating interactive controls and navigation (Regular & Clear).
 * - Standard Materials for structure within the content layer (UltraThin, Thin, Regular, Thick).
 * - Stained Glass tints for emphasized interactive actions (primary CTA, status badges).
 */
@Immutable
sealed class LiquidGlassMaterial(
    val blurRadius: Float,
    val refraction: Float,
    val dispersion: Float,
    val edge: Float,
    val defaultAlpha: Float,
) {
    /**
     * Standard Liquid Glass (HIG recommended for controls, toolbars, sidebars, alerts).
     * Balances backdrop peeking with high foreground legibility.
     */
    data object Regular : LiquidGlassMaterial(
        blurRadius = 24f,
        refraction = 0.14f,
        dispersion = 0.04f,
        edge = 0.12f,
        defaultAlpha = 0.78f,
    )

    /**
     * Clear Liquid Glass (HIG recommended for floating controls over rich media/imagery).
     * High translucency with subtle edge refraction.
     */
    data object Clear : LiquidGlassMaterial(
        blurRadius = 12f,
        refraction = 0.08f,
        dispersion = 0.02f,
        edge = 0.08f,
        defaultAlpha = 0.42f,
    )

    /**
     * Ultra-thin standard material for subtle content layer accents and micro-controls.
     */
    data object UltraThin : LiquidGlassMaterial(
        blurRadius = 8f,
        refraction = 0.04f,
        dispersion = 0.01f,
        edge = 0.05f,
        defaultAlpha = 0.28f,
    )

    /**
     * Thin standard material for cards and secondary panels within content.
     */
    data object Thin : LiquidGlassMaterial(
        blurRadius = 16f,
        refraction = 0.09f,
        dispersion = 0.03f,
        edge = 0.08f,
        defaultAlpha = 0.55f,
    )

    /**
     * Thick standard material for sheets, dialogs, and drawer backgrounds.
     * High opacity to guarantee contrast and WCAG AA legibility over complex text/lists.
     */
    data object Thick : LiquidGlassMaterial(
        blurRadius = 36f,
        refraction = 0.18f,
        dispersion = 0.05f,
        edge = 0.15f,
        defaultAlpha = 0.88f,
    )

    /**
     * Custom glass material parameters for specialized UI surfaces.
     */
    data class Custom(
        val blur: Float,
        val refr: Float,
        val disp: Float,
        val edg: Float,
        val alpha: Float,
    ) : LiquidGlassMaterial(blur, refr, disp, edg, alpha)

    /**
     * Converts this material specification to a [BackdropFilter] instance.
     *
     * @param tint Base tint color combined with [defaultAlpha].
     * @param cornerRadiusDp Corner radius for the glass refraction shader.
     * @param enableRefraction If false, falls back to a high-performance [BackdropFilter.Blur].
     */
    fun toBackdropFilter(
        tint: Color = Color.Transparent,
        cornerRadiusDp: Float = 14f,
        enableRefraction: Boolean = true,
    ): BackdropFilter {
        return if (enableRefraction) {
            BackdropFilter.Glass(
                blurRadiusIntensity = blurRadius,
                cornerRadiusDp = cornerRadiusDp,
                refraction = refraction,
                dispersion = dispersion,
                edge = edge,
                tint = tint,
            )
        } else {
            BackdropFilter.Blur(
                blurRadiusIntensity = blurRadius,
                tint = tint,
            )
        }
    }
}

/**
 * Brand-aligned Stained Glass tints and tokens.
 * Follows Apple HIG: "Use color sparingly... prefer tinting the background over symbols/text".
 */
object LiquidGlassTokens {
    // Stained glass tint presets
    val StainedOrange = PocketOrange.copy(alpha = 0.18f)
    val StainedBlue = PocketBlue.copy(alpha = 0.18f)
    val StainedGreen = PocketGreen.copy(alpha = 0.18f)

    // Neutral monochrome tints for light and dark modes (UltraThin-strength defaults)
    val DarkGlassTint = Color(0x2E1C1C1E)
    val LightGlassTint = Color(0x47FFFFFF)

    /** Neutral wash bases: glass has no colour of its own, only a luminance-matched wash. */
    val DarkGlassBase = Color(0xFF1C1C1E)
    val LightGlassBase = Color(0xFFFFFFFF)

    // Border highlights for physical liquid glass rim
    val GlassRimLight = Color(0x77FFFFFF)
    val GlassRimDark = Color(0x33FFFFFF)
    val GlassBorderLight = Color(0x14000000)
    val GlassBorderDark = Color(0x1FFFFFFF)

    // Selection capsule on glass (neutral, the accent goes on the icon/label)
    val SelectionDark = Color(0x1FFFFFFF)
    val SelectionLight = Color(0x0F000000)

    // Press highlight: glass brightens slightly under the finger
    val PressHighlightDark = Color(0x14FFFFFF)
    val PressHighlightLight = Color(0x0D000000)

    // Standard touch-target and radius tokens (concentric with PocketRadius)
    val MinTouchTarget: Dp = 44.dp
    val ControlRadius: Dp = 14.dp
    val PillRadius: Dp = 999.dp
    val SheetRadius: Dp = 28.dp

    /**
     * Default neutral wash for [material]. Thicker materials are more opaque for legibility;
     * the same wash is used over a live backdrop and in tint-only glass.
     */
    fun wash(material: LiquidGlassMaterial, isDark: Boolean): Color {
        val alpha = when (material) {
            LiquidGlassMaterial.Clear -> if (isDark) 0.10f else 0.16f
            LiquidGlassMaterial.UltraThin -> if (isDark) 0.18f else 0.28f
            LiquidGlassMaterial.Thin -> if (isDark) 0.28f else 0.40f
            LiquidGlassMaterial.Regular -> if (isDark) 0.36f else 0.50f
            LiquidGlassMaterial.Thick -> if (isDark) 0.58f else 0.70f
            is LiquidGlassMaterial.Custom -> material.defaultAlpha
        }
        return (if (isDark) DarkGlassBase else LightGlassBase).copy(alpha = alpha)
    }
}
