package com.jarves.mh.ui.theme.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize

/**
 * The soft edge where content scrolls under a bar: the content blurs progressively toward the
 * screen edge and fades into the canvas, so bar controls stay legible over any content without
 * a hard divider. [strength] (0..1) is usually how far content has scrolled under.
 *
 * Blur and Lens tiers blur the shared backdrop under a gradient mask; other tiers fade only.
 * Put it behind the bar, as a sibling of the scrolling source.
 */
@Composable
fun ScrollEdgeEffect(
    modifier: Modifier = Modifier,
    fromTop: Boolean = true,
    strength: () -> Float,
) {
    val config = LocalLiquidGlassConfig.current
    val backdrop = LocalLiquidGlassBackdrop.current
    val tier = glassTier(config, samplesBackdrop = backdrop != null)
    val blurs = config.progressiveEdge && backdrop != null && (tier == GlassTier.Lens || tier == GlassTier.Blur)
    val canvas = MaterialTheme.colorScheme.background
    val blurLayer = if (blurs) rememberGraphicsLayer() else null
    val maskLayer = if (blurs) rememberGraphicsLayer() else null
    var origin by remember { mutableStateOf<Offset?>(null) }
    if (blurs && backdrop != null) {
        DisposableEffect(backdrop) {
            backdrop.addConsumer()
            onDispose { backdrop.removeConsumer() }
        }
    }
    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val a = strength().coerceIn(0f, 1f)
                if (a <= 0f || size.minDimension <= 0f) return@drawBehind
                val (near, far) = if (fromTop) 0f to size.height else size.height to 0f
                val capture = backdrop?.currentResult
                val at = origin
                if (blurLayer != null && maskLayer != null && capture != null && at != null) {
                    @Suppress("UNUSED_VARIABLE")
                    val epoch = backdrop.captureEpoch
                    val offset = capture.consumerDrawOffset(at)
                    val inverse = 1f / capture.scale
                    blurLayer.renderEffect = null
                    blurLayer.record(size.toIntSize()) {
                        drawRect(canvas)
                        translate(offset.x, offset.y) {
                            scale(inverse, inverse, pivot = Offset.Zero) { drawLayer(capture.layer) }
                        }
                    }
                    val blur = 12.dp.toPx()
                    blurLayer.renderEffect = BlurEffect(blur, blur, TileMode.Clamp)
                    maskLayer.compositingStrategy = CompositingStrategy.Offscreen
                    maskLayer.record(size.toIntSize()) {
                        drawLayer(blurLayer)
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, 1f to Color.Transparent, startY = near, endY = far),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                    maskLayer.alpha = a
                    drawLayer(maskLayer)
                }
                drawRect(
                    Brush.verticalGradient(
                        0f to canvas.copy(alpha = 0.78f * a),
                        0.6f to canvas.copy(alpha = 0.45f * a),
                        1f to canvas.copy(alpha = 0f),
                        startY = near,
                        endY = far,
                    ),
                )
            },
    )
}
