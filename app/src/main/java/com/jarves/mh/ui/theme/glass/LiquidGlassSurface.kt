package com.jarves.mh.ui.theme.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketMotion

/**
 * A piece of Liquid Glass: floating controls and bars that sit above content.
 *
 * - With a [layerSource] it samples the shared backdrop: blur, then on Android 13+ the lens
 *   (edge refraction, dispersion, adaptive wash, edge light). Without one it is a translucent
 *   wash over whatever is behind it.
 * - Interactive glass ([onClick], or [interactive] for containers whose children handle taps)
 *   lifts slightly toward the finger and lights up under it, then settles with a soft bounce.
 * - Reduce transparency and increased contrast give an opaque surface with a clear border;
 *   reduce motion removes the lift.
 * - Inside a [GlassGroup] on the lens tier, the group draws one merged glass shape instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    shape: Shape = RoundedCornerShape(LiquidGlassTokens.ControlRadius),
    tint: Color? = null,
    borderStroke: BorderStroke? = null,
    @Suppress("UNUSED_PARAMETER") tonalElevation: Dp = 0.dp,
    layerSource: String? = null,
    backdrop: BackdropState? = null,
    role: GlassRole? = null,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    semanticRole: Role? = null,
    contentDescription: String? = null,
    interactive: Boolean = onClick != null,
    allowLens: Boolean = true,
    content: @Composable () -> Unit,
) {
    val config = LocalLiquidGlassConfig.current
    val isDark = isGlassDarkTheme()
    val effectiveContentColor = contentColor ?: MaterialTheme.colorScheme.onSurface
    val sharedBackdrop = if (layerSource != null) backdrop ?: LocalLiquidGlassBackdrop.current else null
    // Large moving glass (sheets while dragging) skips the lens to stay within the frame budget.
    val tier = glassTier(config, samplesBackdrop = sharedBackdrop != null)
        .let { if (!allowLens && it == GlassTier.Lens) GlassTier.Blur else it }
    val group = LocalGlassGroup.current?.takeIf { it.active && tier == GlassTier.Lens }

    val press = remember { GlassPressState() }
    val pressAmount by animateFloatAsState(
        targetValue = if (press.pressed && interactive) 1f else 0f,
        animationSpec = if (press.pressed) PocketMotion.spec(PocketMotion.Token.Snappy) else PocketMotion.spec(PocketMotion.Token.Release),
        label = "glassPress",
    )
    val look = GlassLook(
        wash = tint ?: LiquidGlassTokens.wash(material, isDark),
        blurDp = role?.blurDp ?: material.blurRadius.coerceAtMost(24f),
        isDark = isDark,
        canvas = MaterialTheme.colorScheme.background,
        drawRim = true,
        rimBorder = borderStroke,
    )

    var surfaceModifier = modifier
        .glassLift({ pressAmount }, press, enabled = interactive && !config.reduceMotion && tier != GlassTier.Solid)
    surfaceModifier = if (group != null) {
        surfaceModifier.glassGroupMember(group, shape, press) { pressAmount }
    } else {
        surfaceModifier
            .glassShadow(shape, tier, isDark)
            .glassBackground(tier, shape, sharedBackdrop, look, press) { pressAmount }
    }
    surfaceModifier = surfaceModifier.clip(shape)
    if (interactive) surfaceModifier = surfaceModifier.glassPressTracking(press)
    if (onClick != null) {
        surfaceModifier = surfaceModifier.combinedClickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = semanticRole,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
    if (contentDescription != null) {
        surfaceModifier = surfaceModifier.semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
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
 * Dark/light decision for glass and its chrome. Follows the app's theme setting (which may differ
 * from the system's), using the same rule as [backdropGlass].
 */
@Composable
@ReadOnlyComposable
fun isGlassDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

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
