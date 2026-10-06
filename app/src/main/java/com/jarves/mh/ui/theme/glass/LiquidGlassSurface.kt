package com.jarves.mh.ui.theme.glass

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketPalette

/**
 * Modern Liquid Glass Surface component.
 *
 * Implements Apple HIG Liquid Glass principles:
 * - Separates floating interactive controls from content.
 * - Dynamic backdrop refraction & blur when glass is active.
 * - Graceful fallback to high-contrast opaque surface when accessibility (Reduce Transparency) is requested.
 * - Physical top-edge rim lighting for tactile depth.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    shape: Shape = RoundedCornerShape(LiquidGlassTokens.ControlRadius),
    tint: Color? = null,
    borderStroke: BorderStroke? = null,
    tonalElevation: Dp = 0.dp,
    layerSource: String? = null,
    backdrop: BackdropState? = null,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val config = LocalLiquidGlassConfig.current
    val isDark = isSystemInDarkTheme()
    val defaultContentColor = if (isDark) Color.White else Color(0xFF0F172A)
    val effectiveContentColor = contentColor ?: defaultContentColor

    if (!config.isGlassActive) {
        // High-contrast, accessibility-safe fallback surface
        val fallbackColor = if (isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface
        val fallbackBorder = borderStroke ?: BorderStroke(
            1.dp,
            if (isDark) PocketPalette.darkBorder else PocketPalette.lightBorder,
        )

        Surface(
            modifier = modifier,
            shape = shape,
            color = fallbackColor,
            contentColor = effectiveContentColor,
            tonalElevation = tonalElevation,
            border = fallbackBorder,
            onClick = onClick ?: {},
            enabled = onClick != null,
        ) {
            CompositionLocalProvider(LocalContentColor provides effectiveContentColor) {
                content()
            }
        }
        return
    }

    // Active Liquid Glass surface
    val resolvedTint = tint ?: if (isDark) LiquidGlassTokens.DarkGlassTint else LiquidGlassTokens.LightGlassTint
    val rimColor = if (isDark) LiquidGlassTokens.GlassRimDark else LiquidGlassTokens.GlassRimLight
    val borderColor = if (isDark) LiquidGlassTokens.GlassBorderDark else LiquidGlassTokens.GlassBorderLight

    var surfaceModifier = modifier.clip(shape)

    // Shared host backdrop (or explicit override). No per-surface GraphicsLayer capture.
    val sharedBackdrop = backdrop ?: LocalLiquidGlassBackdrop.current
    if (layerSource != null && sharedBackdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        surfaceModifier = surfaceModifier.backdropGlass(
            backdrop = sharedBackdrop,
            shape = shape,
            blurDp = material.blurRadius.coerceAtMost(24f),
            tint = Color.Transparent,
        )
    }

    // Apple-style glass: translucent tint, soft vertical sheen, and a specular rim that is
    // brightest at the top-leading edge and fades toward the bottom-trailing edge.
    val sheenTop = if (isDark) Color(0x1FFFFFFF) else Color(0x66FFFFFF)
    val specular = if (isDark) Color(0x66FFFFFF) else Color(0xCCFFFFFF)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
        label = "liquidGlassPressScale",
    )

    surfaceModifier = surfaceModifier
        .background(resolvedTint)
        .background(
            Brush.verticalGradient(
                0f to sheenTop,
                0.5f to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
        .border(
            borderStroke ?: BorderStroke(
                0.75.dp,
                Brush.linearGradient(
                    0f to specular,
                    0.45f to borderColor,
                    1f to rimColor.copy(alpha = rimColor.alpha * 0.6f),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            ),
            shape,
        )

    if (onClick != null) {
        surfaceModifier = surfaceModifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
    }

    Box(
        modifier = surfaceModifier,
        propagateMinConstraints = false,
    ) {
        CompositionLocalProvider(LocalContentColor provides effectiveContentColor) {
            content()
        }
    }
}

/**
 * Modifier to apply Liquid Glass styling to any composable.
 */
fun Modifier.liquidGlass(
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    shape: Shape = RoundedCornerShape(LiquidGlassTokens.ControlRadius),
    layerSource: String? = null,
    tint: Color = Color.Transparent,
    enableRefraction: Boolean = true,
): Modifier {
    // Capture now flows through the host-owned shared backdrop (see LiquidGlassSurface);
    // this plain modifier no longer owns a capture layer.
    return this.clip(shape)
}

/**
 * Modifier to mark this element as a backdrop source layer for Liquid Glass capture.
 */
fun Modifier.asBackdropSource(@Suppress("UNUSED_PARAMETER") layerName: String): Modifier =
    composed { Modifier.hostBackdropSource() }
