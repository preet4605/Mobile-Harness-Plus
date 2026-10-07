package com.jarves.mh.ui.theme.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import com.jarves.mh.ui.theme.cornerRadiusPx
import kotlin.math.max
import kotlin.math.min

/** One glass shape inside a [GlassGroup], in window coordinates. */
@Stable
internal class GlassGroupMember(
    val bounds: Rect,
    val shape: Shape,
    val press: GlassPressState,
    val pressAmount: () -> Float,
)

@Stable
internal class GlassGroupState(val active: Boolean) {
    val members = mutableStateMapOf<Any, GlassGroupMember>()
}

internal val LocalGlassGroup = compositionLocalOf<GlassGroupState?> { null }

/**
 * Glass shapes placed close together inside a group render as one piece of glass on the lens
 * tier: their outlines blend with a smooth union over [spacing], so they merge as they move
 * toward each other and pull apart as they separate (for example the tab bar shrinking next to
 * the composer). Below Android 13, or with merging turned off, each member draws its own glass.
 *
 * The group must cover all members; it samples the shared backdrop like any other glass.
 */
@Composable
fun GlassGroup(
    modifier: Modifier = Modifier,
    spacing: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val config = LocalLiquidGlassConfig.current
    val backdrop = LocalLiquidGlassBackdrop.current
    val merging = config.mergeShapes && backdrop != null &&
        glassTier(config, samplesBackdrop = true) == GlassTier.Lens
    val state = remember(merging) { GlassGroupState(merging) }
    val groupModifier = if (merging && backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        modifier.mergedGlass(state, backdrop, spacing)
    } else modifier
    CompositionLocalProvider(LocalGlassGroup provides state) {
        Box(groupModifier, content = content)
    }
}

/** Registers a member's window bounds with its group instead of drawing its own glass. */
@Composable
internal fun Modifier.glassGroupMember(
    group: GlassGroupState,
    shape: Shape,
    press: GlassPressState,
    pressAmount: () -> Float,
): Modifier {
    val key = remember { Any() }
    DisposableEffect(group, key) {
        onDispose { group.members.remove(key) }
    }
    return this.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        val current = group.members[key]
        if (current == null || current.bounds != bounds || current.shape != shape) {
            group.members[key] = GlassGroupMember(bounds, shape, press, pressAmount)
        }
    }
}

@Composable
private fun Modifier.mergedGlass(state: GlassGroupState, backdrop: BackdropState, spacing: Dp): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val layer = rememberGraphicsLayer()
    val shader: RuntimeShader? = remember { GlassShaders.create() }
    val config = LocalLiquidGlassConfig.current
    val isDark = isGlassDarkTheme()
    val look = GlassLook(
        wash = LiquidGlassTokens.wash(LiquidGlassMaterial.Regular, isDark),
        blurDp = LiquidGlassMaterial.Regular.blurRadius.coerceAtMost(24f),
        isDark = isDark,
        canvas = MaterialTheme.colorScheme.background,
        drawRim = false,
        rimBorder = null,
    )
    var origin by remember { mutableStateOf<Offset?>(null) }
    DisposableEffect(backdrop) {
        backdrop.addConsumer()
        onDispose { backdrop.removeConsumer() }
    }
    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            val members = state.members.values.toList().take(4)
            val capture = backdrop.currentResult
            @Suppress("UNUSED_VARIABLE")
            val epoch = backdrop.captureEpoch
            val at = origin
            if (shader == null || members.isEmpty() || at == null) return@drawBehind
            if (capture == null) {
                backdrop.requestCaptureIfMissing()
                return@drawBehind
            }
            val rects = FloatArray(16)
            val radii = FloatArray(4)
            var pressed: GlassGroupMember? = null
            var pressedAmount = 0f
            members.forEachIndexed { i, m ->
                val amount = m.pressAmount()
                // Lift: the pressed member grows a little inside the merged shape.
                val grow = if (config.reduceMotion) 0f else amount * 3.dp.toPx()
                val left = m.bounds.left - at.x - grow
                val top = m.bounds.top - at.y - grow
                rects[i * 4] = left
                rects[i * 4 + 1] = top
                rects[i * 4 + 2] = m.bounds.width + grow * 2f
                rects[i * 4 + 3] = m.bounds.height + grow * 2f
                radii[i] = (m.shape.cornerRadiusPx(m.bounds.size, this) ?: min(m.bounds.size.minDimension / 2f, 16.dp.toPx())) + grow
                if (amount > pressedAmount) {
                    pressedAmount = amount
                    pressed = m
                }
            }
            val glowMember = pressed
            val glowPoint = if (glowMember != null && glowMember.press.point.x.isFinite()) {
                Offset(glowMember.bounds.left - at.x, glowMember.bounds.top - at.y) + glowMember.press.point
            } else Offset.Unspecified
            val smallest = members.minOf { it.bounds.size.minDimension }
            val lens = min(GlassSizeClass.Small.lensDp.dp.toPx(), smallest * 0.4f)
            shader.setGlassUniforms(
                rects = rects.copyOf(members.size * 4),
                radii = radii,
                smoothK = spacing.toPx(),
                lensHeight = lens,
                refraction = lens * 0.9f,
                look = look,
                config = config,
                light = Offset(-0.6f, -0.8f),
                glowPoint = glowPoint,
                glowStrength = if (config.pressGlow) pressedAmount * (if (isDark) 0.16f else 0.22f) else 0f,
                glowRadius = max(smallest * 0.6f, 24.dp.toPx()),
                maskShape = true,
                rimWidth = 1.dp.toPx(),
                rimStrength = if (isDark) 0.35f else 0.55f,
            )
            val offset = capture.consumerDrawOffset(at)
            val inverse = 1f / capture.scale
            layer.renderEffect = null
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.record(size.toIntSize()) {
                translate(offset.x, offset.y) {
                    scale(inverse, inverse, pivot = Offset.Zero) { drawLayer(capture.layer) }
                }
            }
            val blurPx = look.blurDp.dp.toPx()
            layer.renderEffect = android.graphics.RenderEffect.createChainEffect(
                android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"),
                android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP),
            ).asComposeRenderEffect()
            drawLayer(layer)
        }
}
