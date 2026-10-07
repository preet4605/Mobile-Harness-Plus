package com.jarves.mh.ui.theme.glass

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import com.jarves.mh.ui.theme.PocketPalette
import com.jarves.mh.ui.theme.cornerRadiusPx
import kotlin.math.max
import kotlin.math.min

/**
 * How a glass shape renders, best first. The tier is chosen per shape from the device, the
 * accessibility settings and whether the shape samples the shared backdrop.
 */
enum class GlassTier {
    /** Android 13+: blur, then the AGSL lens (refraction, dispersion, adaptive wash, edge light, glow). */
    Lens,

    /** Android 12: blur + saturation, fixed wash, gradient rim, glow brush. */
    Blur,

    /** No backdrop sampling: translucent wash over whatever is behind, with rim and glow. */
    Tint,

    /** Reduce transparency, increased contrast, glass off, or Android 11 and below with a backdrop: opaque. */
    Solid,
}

internal fun glassTier(config: LiquidGlassConfig, samplesBackdrop: Boolean, sdk: Int = Build.VERSION.SDK_INT): GlassTier = when {
    !config.isGlassActive -> GlassTier.Solid
    !samplesBackdrop -> GlassTier.Tint
    sdk < Build.VERSION_CODES.S -> GlassTier.Solid
    sdk >= Build.VERSION_CODES.TIRAMISU && config.enableRefraction && GlassShaders.supported -> GlassTier.Lens
    else -> GlassTier.Blur
}

/** Size classes (by the shape's short side) that scale lens depth, lift and shadow. */
internal enum class GlassSizeClass(val lensDp: Float, val shadowDp: Float, val liftDp: Float) {
    /** Buttons, chips, the tab bar circle. */
    Small(lensDp = 8f, shadowDp = 6f, liftDp = 6f),

    /** Bars, the tab bar, the composer. */
    Medium(lensDp = 12f, shadowDp = 10f, liftDp = 5f),

    /** Sheets, menus, cards. */
    Large(lensDp = 20f, shadowDp = 18f, liftDp = 4f);

    companion object {
        fun of(size: Size, density: Density): GlassSizeClass {
            val shortSide = with(density) { size.minDimension.toDp().value }
            return when {
                shortSide < 60f -> Small
                shortSide < 140f -> Medium
                else -> Large
            }
        }
    }
}

/** Touch state of an interactive glass shape: where the finger is and whether it is down. */
@Stable
class GlassPressState {
    var pressed by mutableStateOf(false)
        internal set
    var point by mutableStateOf(Offset.Unspecified)
        internal set
}

/**
 * Observes touches on the shape without consuming them, so the glass can light up and lift
 * under the finger while the children (buttons, tabs) still handle the tap.
 */
internal fun Modifier.glassPressTracking(state: GlassPressState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        state.point = down.position
        state.pressed = true
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                state.point = change.position
                if (!change.pressed) break
            }
        } finally {
            state.pressed = false
        }
    }
}

/**
 * Lift on press: the shape grows a few dp (more for small shapes) and leans toward the finger,
 * then settles back with a soft bounce. [amount] is the spring-driven press, 0..1.
 */
internal fun Modifier.glassLift(amount: () -> Float, press: GlassPressState, enabled: Boolean): Modifier =
    if (!enabled) this else graphicsLayer {
        val a = amount()
        if (a == 0f) return@graphicsLayer
        val lift = GlassSizeClass.of(size, this).liftDp.dp.toPx()
        val grow = (lift * 2f / max(size.maxDimension, 1f)).coerceIn(0.01f, 0.1f)
        scaleX = 1f + grow * a
        scaleY = 1f + grow * a
        val p = press.point
        if (p.isSpecified()) {
            val lean = 3.dp.toPx()
            translationX = ((p.x - size.width / 2f) / max(size.width, 1f) * 2f).coerceIn(-1f, 1f) * lean * a
            translationY = ((p.y - size.height / 2f) / max(size.height, 1f) * 2f).coerceIn(-1f, 1f) * lean * a
        }
    }

private fun Offset.isSpecified() = x.isFinite() && y.isFinite()

private val Vibrancy: ColorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.5f) })

/** Light comes from the top-leading corner and eases toward the finger while pressed. */
internal fun glassLightDirection(size: Size, press: Offset, pressAmount: Float): Offset {
    val rest = Offset(-0.6f, -0.8f)
    if (pressAmount <= 0f || !press.isSpecified()) return rest
    val toward = Offset(press.x - size.width / 2f, press.y - size.height / 2f)
    val len = toward.getDistance()
    if (len < 1f) return rest
    val dir = toward / len
    val mixed = rest * (1f - pressAmount * 0.6f) + dir * (pressAmount * 0.6f)
    val m = mixed.getDistance().takeIf { it > 0f } ?: return rest
    return mixed / m
}

/**
 * Glass parameters for one shape. [wash] carries the wash colour and its base alpha.
 */
@Stable
internal class GlassLook(
    val wash: Color,
    val blurDp: Float,
    val isDark: Boolean,
    val canvas: Color,
    val drawRim: Boolean,
    val rimBorder: androidx.compose.foundation.BorderStroke?,
)

/**
 * Draws the glass behind the content for [tier]. One modifier for every tier so the visual
 * recipe lives in one place: canvas → blurred backdrop (→ lens) → wash → rim → glow.
 */
@Composable
internal fun Modifier.glassBackground(
    tier: GlassTier,
    shape: Shape,
    backdrop: BackdropState?,
    look: GlassLook,
    press: GlassPressState,
    pressAmount: () -> Float,
): Modifier {
    val config = LocalLiquidGlassConfig.current
    if (tier == GlassTier.Solid) return solidGlass(shape, look, press, pressAmount, config.increaseContrast)

    val blurLayer = if (tier == GlassTier.Lens || tier == GlassTier.Blur) rememberGraphicsLayer() else null
    val shader: RuntimeShader? = if (tier == GlassTier.Lens && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { GlassShaders.create() }
    } else null
    var origin by remember { mutableStateOf<Offset?>(null) }
    if (backdrop != null && blurLayer != null) {
        DisposableEffect(backdrop) {
            backdrop.addConsumer()
            onDispose { backdrop.removeConsumer() }
        }
    }

    return this
        .then(if (blurLayer != null) Modifier.onGloballyPositioned { origin = it.positionInWindow() } else Modifier)
        .drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val amount = pressAmount()
            val sampled = blurLayer != null && backdrop != null &&
                drawBackdrop(backdrop, blurLayer, shader, origin, outline, shape, look, config, press, amount)
            if (!sampled) {
                // Tint tier, or the first frame before the backdrop is captured.
                if (blurLayer != null) {
                    drawOutline(outline, if (look.isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface)
                }
                drawOutline(outline, look.wash)
                drawSheen(outline, look.isDark)
            }
            if (!sampled || shader == null) drawGlowBrush(outline, press, amount, look.isDark, config)
            if (look.drawRim) drawRim(outline, look, press, amount)
            drawContent()
        }
}

/** Returns false when there is no capture yet (the caller draws the fallback). */
private fun DrawScope.drawBackdrop(
    backdrop: BackdropState,
    blurLayer: GraphicsLayer,
    shader: RuntimeShader?,
    origin: Offset?,
    outline: Outline,
    shape: Shape,
    look: GlassLook,
    config: LiquidGlassConfig,
    press: GlassPressState,
    amount: Float,
): Boolean {
    @Suppress("UNUSED_VARIABLE")
    val epoch = backdrop.captureEpoch // observe new captures
    val capture = backdrop.currentResult
    if (capture == null) {
        backdrop.requestCaptureIfMissing()
        return false
    }
    if (origin == null || size.minDimension <= 0f) return false

    val offset = capture.consumerDrawOffset(origin)
    val inverseScale = 1f / capture.scale
    blurLayer.renderEffect = null
    blurLayer.compositingStrategy = CompositingStrategy.Offscreen
    blurLayer.colorFilter = if (shader == null) Vibrancy else null
    blurLayer.record(size.toIntSize()) {
        translate(offset.x, offset.y) {
            scale(inverseScale, inverseScale, pivot = Offset.Zero) {
                drawLayer(capture.layer)
            }
        }
    }
    val blurPx = look.blurDp.dp.toPx()
    blurLayer.renderEffect = if (shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        lensEffect(shader, blurPx, shape, look, config, press, amount)
    } else {
        BlurEffect(blurPx, blurPx, TileMode.Clamp)
    }

    val clip = Path().apply { addOutline(outline) }
    // Opaque canvas under the sample: the glass replaces what is behind it, so sharp content
    // never shows through transparent gaps in the capture.
    drawOutline(outline, look.canvas)
    clipPath(clip) { drawLayer(blurLayer) }
    if (shader == null) {
        drawOutline(outline, look.wash)
        drawSheen(outline, look.isDark)
    }
    return true
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun DrawScope.lensEffect(
    shader: RuntimeShader,
    blurPx: Float,
    shape: Shape,
    look: GlassLook,
    config: LiquidGlassConfig,
    press: GlassPressState,
    amount: Float,
): androidx.compose.ui.graphics.RenderEffect {
    val sizeClass = GlassSizeClass.of(size, this)
    val radius = shape.cornerRadiusPx(size, this) ?: min(size.minDimension / 2f, 16.dp.toPx())
    val lens = min(sizeClass.lensDp.dp.toPx(), size.minDimension * 0.4f)
    shader.setGlassUniforms(
        rects = floatArrayOf(0f, 0f, size.width, size.height),
        radii = floatArrayOf(radius),
        smoothK = 0f,
        lensHeight = lens,
        refraction = lens * 0.9f,
        look = look,
        config = config,
        light = glassLightDirection(size, press.point, amount),
        glowPoint = press.point,
        glowStrength = if (config.pressGlow) amount * (if (look.isDark) 0.16f else 0.22f) else 0f,
        glowRadius = max(size.minDimension * 0.6f, 24.dp.toPx()),
        maskShape = false,
        rimWidth = 0f,
        rimStrength = 0f,
    )
    val lensEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
    val blur = android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
    return android.graphics.RenderEffect.createChainEffect(lensEffect, blur).asComposeRenderEffect()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun RuntimeShader.setGlassUniforms(
    rects: FloatArray,
    radii: FloatArray,
    smoothK: Float,
    lensHeight: Float,
    refraction: Float,
    look: GlassLook,
    config: LiquidGlassConfig,
    light: Offset,
    glowPoint: Offset,
    glowStrength: Float,
    glowRadius: Float,
    maskShape: Boolean,
    rimWidth: Float,
    rimStrength: Float,
) {
    val count = rects.size / 4
    setFloatUniform("rects", FloatArray(16).also { rects.copyInto(it, 0, 0, min(16, rects.size)) })
    setFloatUniform("radii", FloatArray(4).also { radii.copyInto(it, 0, 0, min(4, radii.size)) })
    setFloatUniform("count", count.toFloat())
    setFloatUniform("smoothK", smoothK)
    setFloatUniform("lensHeight", lensHeight)
    setFloatUniform("refraction", refraction)
    setFloatUniform("dispersion", config.dispersion.coerceIn(0f, 0.3f))
    setFloatUniform("saturation", 1.5f)
    setFloatUniform("canvas", look.canvas.red, look.canvas.green, look.canvas.blue, 1f)
    setFloatUniform("wash", look.wash.red, look.wash.green, look.wash.blue, look.wash.alpha)
    setFloatUniform("adaptive", if (config.adaptiveWash) 0.22f else 0f)
    setFloatUniform("dark", if (look.isDark) 1f else 0f)
    setFloatUniform("light", light.x, light.y)
    setFloatUniform("edgeLight", if (config.edgeLight) (if (look.isDark) 0.07f else 0.12f) else 0f)
    val glowAt = if (glowPoint.isSpecified()) glowPoint else Offset.Zero
    setFloatUniform("glow", glowAt.x, glowAt.y, if (glowPoint.isSpecified()) glowStrength else 0f)
    setFloatUniform("glowRadius", glowRadius)
    setFloatUniform("maskShape", if (maskShape) 1f else 0f)
    setFloatUniform("rimWidth", rimWidth)
    setFloatUniform("rimStrength", rimStrength)
}

/** A soft vertical sheen on the top half (Blur and Tint tiers). */
private fun DrawScope.drawSheen(outline: Outline, isDark: Boolean) {
    val top = if (isDark) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.40f)
    drawOutline(outline, Brush.verticalGradient(0f to top, 0.5f to Color.Transparent, 1f to Color.Transparent))
}

/** Press glow for tiers without the shader: a radial light at the finger. */
private fun DrawScope.drawGlowBrush(outline: Outline, press: GlassPressState, amount: Float, isDark: Boolean, config: LiquidGlassConfig) {
    if (amount <= 0f || !config.pressGlow || !press.point.isSpecified()) return
    val alpha = amount * (if (isDark) 0.18f else 0.30f)
    val radius = max(size.minDimension * 0.9f, 32.dp.toPx())
    drawOutline(
        outline,
        Brush.radialGradient(
            0f to Color.White.copy(alpha = alpha),
            1f to Color.Transparent,
            center = press.point,
            radius = radius,
        ),
    )
}

/**
 * The specular rim: bright where the edge faces the light, nearly clear across the middle,
 * faint on the far side. Follows the finger while pressed.
 */
private fun DrawScope.drawRim(outline: Outline, look: GlassLook, press: GlassPressState, amount: Float) {
    val border = look.rimBorder
    if (border != null) {
        if (border.width > 0.dp) drawOutline(outline, border.brush, style = Stroke(border.width.toPx()))
        return
    }
    val light = glassLightDirection(size, press.point, amount)
    val center = Offset(size.width / 2f, size.height / 2f)
    val reach = size.getDistance() / 2f
    val start = center + light * reach
    val end = center - light * reach
    val (bright, middle, far) = if (look.isDark) {
        Triple(Color.White.copy(alpha = 0.42f + 0.2f * amount), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.16f))
    } else {
        Triple(Color.White.copy(alpha = 0.80f), Color.Black.copy(alpha = 0.04f), Color.Black.copy(alpha = 0.10f))
    }
    drawOutline(
        outline,
        Brush.linearGradient(0f to bright, 0.5f to middle, 1f to far, start = start, end = end),
        style = Stroke(0.75.dp.toPx()),
    )
}

private fun Size.getDistance(): Float = kotlin.math.sqrt(width * width + height * height)

/** Opaque surfaces for reduce transparency and increased contrast, with a clear border. */
private fun Modifier.solidGlass(
    shape: Shape,
    look: GlassLook,
    press: GlassPressState,
    pressAmount: () -> Float,
    increaseContrast: Boolean,
): Modifier = drawWithContent {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, if (look.isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface)
    val highlight = pressAmount() * (if (look.isDark) 0.10f else 0.06f)
    if (highlight > 0f) drawOutline(outline, (if (look.isDark) Color.White else Color.Black).copy(alpha = highlight))
    val borderColor = when {
        increaseContrast -> if (look.isDark) Color.White.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.45f)
        look.isDark -> PocketPalette.darkBorder
        else -> PocketPalette.lightBorder
    }
    drawOutline(outline, borderColor, style = Stroke((if (increaseContrast) 1.dp else 0.75.dp).toPx()))
    drawContent()
}

/**
 * Soft ambient shadow scaled by size class, only where the glass is opaque underneath (a
 * shadow under translucent glass would show through it).
 */
internal fun Modifier.glassShadow(shape: Shape, tier: GlassTier, isDark: Boolean): Modifier =
    if (tier == GlassTier.Tint) this else graphicsLayer {
        val sizeClass = GlassSizeClass.of(size, this)
        shadowElevation = sizeClass.shadowDp.dp.toPx() * (if (isDark) 0.6f else 1f)
        this.shape = shape
        clip = false
        ambientShadowColor = Color.Black.copy(alpha = if (isDark) 0.4f else 0.10f)
        spotShadowColor = Color.Black.copy(alpha = if (isDark) 0.5f else 0.16f)
    }
