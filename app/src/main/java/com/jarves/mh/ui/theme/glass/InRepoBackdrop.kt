package com.jarves.mh.ui.theme.glass

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toIntSize
import com.jarves.mh.ui.theme.PocketPalette

/*
 * In-repo backdrop architecture:
 * Dedicated capture representation owned by BackdropState.
 *
 * Tiers:
 *  - T2 (API 31+, glass active): vibrancy saturation boost + blur of dedicated backdrop capture + rim highlight.
 *  - T1 (API < 31, reduce transparency, glass disabled, or uncaptured): solid surface + rim.
 *  - T3 lens (SDF edge refraction / dispersion): reserved for future enhancement.
 */

/**
 * Immutable snapshot of a completed capture generation.
 * Published atomically to [BackdropState] only after recording finishes,
 * ensuring consumers never sample an in-progress or mutating layer.
 */
@Immutable
internal class CaptureResult(
    val layer: GraphicsLayer,
    val sourceOrigin: Offset,
    val capturedSize: IntSize,
    val captureEpoch: Long,
    val scale: Float = 1f,
) {
    /**
     * Calculates translation offset for a consumer positioned at [consumerOrigin].
     */
    fun consumerDrawOffset(consumerOrigin: Offset): Offset {
        return Offset(
            x = sourceOrigin.x - consumerOrigin.x,
            y = sourceOrigin.y - consumerOrigin.y,
        )
    }
}

/**
 * One shared backdrop. A single [backdropSource] records solid content into dedicated
 * generation layers; every [backdropGlass] consumer samples the published [CaptureResult],
 * blurred and clipped to its own shape.
 *
 * Completed-Capture Architecture:
 * - Content renders normally directly to the screen via [drawContent].
 * - Dedicated generation layers are allocated via [allocateCaptureLayer].
 * - A generation layer is recorded off-screen and ONLY published to [currentResult] AFTER
 *   recording has completed.
 * - Consumers only sample the published completed layer.
 * - Previous published layers are retained long enough (~96ms) to cover in-flight consumers,
 *   preventing rendering tearing or concurrent access.
 */
@Stable
class BackdropState internal constructor(
    private val graphicsContext: GraphicsContext?,
    private val coroutineScope: CoroutineScope?,
    private val fallbackLayer: GraphicsLayer,
) {
    /** Backward compatibility constructor. */
    internal constructor(captureLayer: GraphicsLayer) : this(
        graphicsContext = null,
        coroutineScope = null,
        fallbackLayer = captureLayer,
    )

    /** Currently published, completed capture generation. Null until the first capture completes. */
    internal var currentResult by mutableStateOf<CaptureResult?>(null)
        private set

    /** Backward compatibility alias returning the currently published layer or fallback. */
    internal val captureLayer: GraphicsLayer
        get() = currentResult?.layer ?: fallbackLayer

    /** Backward compatibility alias. */
    internal val layer: GraphicsLayer
        get() = captureLayer

    /** Window position of the source; consumers offset their sample against it. */
    internal var sourceOrigin by mutableStateOf<Offset?>(null)

    /** Indicates whether the capture representation has recorded at least one valid frame. */
    internal val hasCapture: Boolean
        get() = currentResult != null

    /** Monotonically increasing epoch counter incremented whenever a new generation is published. */
    internal var captureEpoch by mutableLongStateOf(0L)
        private set

    /**
     * Generic invalidation counter modeled on BuildItCode's sourceInvalidator.
     * Observed by [backdropSource] to trigger recapture on demand.
     */
    internal var sourceInvalidator by mutableLongStateOf(0L)
        private set

    /**
     * Generic nested scroll connection that automatically invalidates and triggers recapture
     * when any descendant scrollable container (e.g. LazyColumn or scrollable list) scrolls.
     */
    internal val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (consumed.x != 0f || consumed.y != 0f) {
                requestCapture()
            }
            return Offset.Zero
        }
    }

    /**
     * Allocates a fresh GraphicsLayer for a new capture generation.
     * Never reuses the currently published layer being sampled by consumers.
     */
    internal fun allocateCaptureLayer(): GraphicsLayer {
        return graphicsContext?.createGraphicsLayer() ?: fallbackLayer
    }

    /**
     * Atomically publishes a newly completed capture generation.
     * Consumers will begin sampling [recordedLayer] on their next draw pass.
     * The [previousResult] layer is retained for ~96ms (~6 frames @ 60Hz)
     * so any in-flight consumer draw operations complete safely before release.
     */
    internal fun publishCapture(
        recordedLayer: GraphicsLayer,
        origin: Offset,
        size: IntSize,
        scale: Float = 1f,
    ) {
        val previousResult = currentResult
        val newEpoch = captureEpoch + 1L
        sourceOrigin = origin
        captureEpoch = newEpoch
        currentResult = CaptureResult(
            layer = recordedLayer,
            sourceOrigin = origin,
            capturedSize = size,
            captureEpoch = newEpoch,
            scale = scale,
        )

        if (previousResult != null &&
            previousResult.layer !== fallbackLayer &&
            previousResult.layer !== recordedLayer
        ) {
            releaseLayerLater(previousResult.layer)
        }
    }

    private fun releaseLayerLater(layer: GraphicsLayer) {
        val gc = graphicsContext
        val scope = coroutineScope
        if (gc != null && scope != null) {
            scope.launch {
                try {
                    delay(96L)
                    gc.releaseGraphicsLayer(layer)
                } catch (_: Throwable) {
                    // Ignored on cancellation
                }
            }
        }
    }

    internal fun releaseLayerImmediately(layer: GraphicsLayer) {
        if (layer !== fallbackLayer) {
            try {
                graphicsContext?.releaseGraphicsLayer(layer)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Generic invalidation trigger modeled on BuildItCode's requestCapture().
     * Recaptures the backdrop source without any UI-specific scroll hooks.
     */
    fun requestCapture() {
        sourceInvalidator++
    }

    /**
     * Requests capture only if no completed capture result has been recorded yet.
     */
    fun requestCaptureIfMissing() {
        if (currentResult == null) {
            requestCapture()
        }
    }

    internal fun dispose() {
        currentResult?.layer?.let {
            if (it !== fallbackLayer) {
                try {
                    graphicsContext?.releaseGraphicsLayer(it)
                } catch (_: Throwable) {}
            }
        }
        currentResult = null
    }
}

@Composable
fun rememberBackdropState(): BackdropState {
    val fallbackLayer = rememberGraphicsLayer()
    val graphicsContext = LocalGraphicsContext.current
    val coroutineScope = rememberCoroutineScope()

    val state = remember(graphicsContext, coroutineScope) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fallbackLayer.compositingStrategy = CompositingStrategy.Offscreen
        }
        BackdropState(
            graphicsContext = graphicsContext,
            coroutineScope = coroutineScope,
            fallbackLayer = fallbackLayer,
        )
    }

    DisposableEffect(state) {
        onDispose {
            state.dispose()
        }
    }

    return state
}

/**
 * Marks solid content as the backdrop source. Glass consumers must be siblings of the node
 * carrying this modifier, never descendants (a descendant would record itself).
 *
 * Dedicated Capture Architecture:
 * 1. The source content renders normally to the screen via [drawContent].
 * 2. It ALSO feeds a dedicated offscreen generation layer allocated per capture cycle.
 * 3. Only published to [state.currentResult] after recording successfully completes.
 */
fun Modifier.backdropSource(state: BackdropState): Modifier = this
    .nestedScroll(state.nestedScrollConnection)
    .then(BackdropSourceElement(state))

private data class BackdropSourceElement(
    val state: BackdropState,
) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create(): BackdropSourceNode = BackdropSourceNode(state)

    override fun update(node: BackdropSourceNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "backdropSource"
        properties["state"] = state
    }
}

internal class BackdropSourceNode(
    var state: BackdropState,
) : Modifier.Node(),
    DrawModifierNode,
    ObserverModifierNode,
    GlobalPositionAwareModifierNode {

    private var lastCapturedInvalidator = -1L
    private var lastCapturedSize = IntSize.Zero

    override fun onAttach() {
        super.onAttach()
        state.requestCapture()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val newOrigin = coordinates.positionInWindow()
        if (state.sourceOrigin != newOrigin) {
            state.sourceOrigin = newOrigin
            invalidateDraw()
        }
    }

    override fun onObservedReadsChanged() {
        invalidateDraw()
    }

    override fun onMeasureResultChanged() {
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        // Observe generic sourceInvalidator for manual/coordinated recapture requests
        observeReads {
            state.sourceInvalidator
        }

        // 1. Normal on-screen rendering directly to window canvas
        drawContent()

        // 2. Dedicated completed-capture generation for glass sampling
        if (blurSupported() && size.minDimension > 0f) {
            val origin = state.sourceOrigin ?: Offset.Zero
            val intSize = size.toIntSize()

            // Allocate a fresh layer for this generation — never mutate in-use consumer layers
            val freshLayer = state.allocateCaptureLayer()
            freshLayer.compositingStrategy = CompositingStrategy.Offscreen

            try {
                // Record the complete source subtree into the fresh capture layer
                freshLayer.record(intSize) {
                    this@draw.drawContent()
                }
                lastCapturedInvalidator = state.sourceInvalidator
                lastCapturedSize = intSize

                // Atomically publish ONLY after recording is fully complete
                state.publishCapture(
                    recordedLayer = freshLayer,
                    origin = origin,
                    size = intSize,
                )
            } catch (_: Throwable) {
                // If recording failed, release the un-published freshLayer immediately
                state.releaseLayerImmediately(freshLayer)
            }
        }
    }
}

/**
 * Canonical material recipe roles for backdrop-sampling chrome. Blur/shadow live here so the
 * hierarchy is tuned in one place (not scattered per call site).
 *  real content -> blur -> restrained tint -> faint top sheen -> rim -> soft shadow.
 */
@Stable
class GlassRole(
    val blurDp: Float,
    val shadowDp: Float,
    val sheenAlpha: Float,
    val washAlphaDark: Float = 0.16f,
    val washAlphaLight: Float = 0.25f,
)

object GlassRoles {
    /** Workspace tab island. */
    val Nav = GlassRole(
        blurDp = 28f,
        shadowDp = 8f,
        sheenAlpha = 1f,
        washAlphaDark = 0.16f,
        washAlphaLight = 0.26f,
    )

    /** Chat composer capsule — strongest spatial diffusion destroying high-frequency text. */
    val Composer = GlassRole(
        blurDp = 36f,
        shadowDp = 6f,
        sheenAlpha = 1f,
        washAlphaDark = 0.24f,
        washAlphaLight = 0.38f,
    )

    /** Floating "Latest" pill — medium diffusion. */
    val Latest = GlassRole(
        blurDp = 20f,
        shadowDp = 4f,
        sheenAlpha = 0.75f,
        washAlphaDark = 0.11f,
        washAlphaLight = 0.18f,
    )

    /** Small chips (commands / skills / inspector) — light diffusion. */
    val Chip = GlassRole(
        blurDp = 14f,
        shadowDp = 0f,
        sheenAlpha = 0.6f,
        washAlphaDark = 0.09f,
        washAlphaLight = 0.14f,
    )
}

private val VibrancyFilter: ColorFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(1.5f) },
)

private fun blurSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Glass background for chrome. See file header for tiers.
 */
@Composable
fun Modifier.backdropGlass(
    backdrop: BackdropState?,
    shape: Shape,
    blurDp: Float = 20f,
    tint: Color? = null,
    borderStroke: BorderStroke? = null,
    role: GlassRole? = null,
): Modifier {
    val config = LocalLiquidGlassConfig.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    if (backdrop == null || !config.isGlassActive || !blurSupported()) {
        val solid = if (isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface
        val rim = borderStroke ?: BorderStroke(
            0.75.dp,
            if (isDark) LiquidGlassTokens.GlassRimDark else LiquidGlassTokens.GlassRimLight,
        )
        return this.background(solid, shape).border(rim, shape)
    }

    val washAlpha = if (isDark) (role?.washAlphaDark ?: 0.16f) else (role?.washAlphaLight ?: 0.25f)
    val baseWashColor = if (isDark) Color(0xFF111827) else Color.White
    val surfaceTint = tint ?: baseWashColor.copy(alpha = washAlpha)
    val effectiveBlurDp = role?.blurDp ?: blurDp
    val sheenColor = (if (isDark) Color(0x1FFFFFFF) else Color(0x59FFFFFF))
        .let { it.copy(alpha = it.alpha * (role?.sheenAlpha ?: 0f)) }
    val blurLayer = rememberGraphicsLayer()
    var origin by remember { mutableStateOf<Offset?>(null) }

    val rimBrush = if (isDark) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.42f),
                Color.White.copy(alpha = 0.06f),
                Color.White.copy(alpha = 0.14f),
            ),
            start = Offset.Zero,
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.75f),
                Color(0x08000000),
                Color(0x18000000),
            ),
            start = Offset.Zero,
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
    }

    return this
        .then(
            if (role != null && role.shadowDp > 0f) {
                Modifier.shadow(
                    elevation = role.shadowDp.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x14000000),
                    spotColor = Color(0x33000000),
                )
            } else {
                Modifier
            },
        )
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawWithContent {
            val position = origin
            val captureResult = backdrop.currentResult
            // Read captureEpoch to automatically invalidate and synchronize with published captures
            @Suppress("UNUSED_VARIABLE")
            val epoch = backdrop.captureEpoch

            if (captureResult == null) {
                backdrop.requestCaptureIfMissing()
            }

            if (position != null && size.minDimension > 0f && captureResult != null) {
                val blurRadiusPx = effectiveBlurDp.dp.toPx()
                val offset = captureResult.consumerDrawOffset(position)

                // 1. POST-RECORD RENDEREFFECT: clear renderEffect before record()
                blurLayer.renderEffect = null
                blurLayer.colorFilter = VibrancyFilter
                blurLayer.alpha = 1f
                blurLayer.compositingStrategy = CompositingStrategy.Offscreen

                // 2. Sample completed capture result into blurLayer without RenderEffect attached
                blurLayer.record(drawContext.density, layoutDirection, size.toIntSize()) {
                    translate(
                        left = offset.x,
                        top = offset.y,
                    ) {
                        drawLayer(captureResult.layer)
                    }
                }

                // 3. Attach RenderEffect POST-RECORD
                blurLayer.renderEffect = BlurEffect(
                    radiusX = blurRadiusPx,
                    radiusY = blurRadiusPx,
                    edgeTreatment = TileMode.Clamp,
                )

                // 4. Draw blurred layer clipped to shape
                val outline = shape.createOutline(size, layoutDirection, this)
                val clip = Path().apply { addOutline(outline) }
                clipPath(clip) {
                    drawLayer(blurLayer)
                }

                drawOutline(outline, surfaceTint)
                if (sheenColor.alpha > 0f) {
                    drawOutline(
                        outline,
                        Brush.verticalGradient(
                            0f to sheenColor,
                            0.55f to Color.Transparent,
                            1f to Color.Transparent,
                        ),
                    )
                }
                // Directional specular rim highlight (top-left specular light, bottom subtle grounding)
                drawOutline(
                    outline,
                    rimBrush,
                    style = Stroke(width = 0.75.dp.toPx()),
                )
            } else {
                // High-contrast solid fallback while waiting for capture or if unpositioned
                val outline = shape.createOutline(size, layoutDirection, this)
                val solid = if (isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface
                drawOutline(outline, solid)
                drawOutline(
                    outline,
                    rimBrush,
                    style = Stroke(width = 0.75.dp.toPx()),
                )
            }
            drawContent()
        }
}

/**
 * Drop-in glass container for chrome that samples [backdrop].
 */
@Composable
fun BackdropGlassSurface(
    backdrop: BackdropState?,
    shape: Shape,
    modifier: Modifier = Modifier,
    blurDp: Float = 10f,
    tint: Color? = null,
    borderStroke: BorderStroke? = null,
    role: GlassRole? = null,
    content: @Composable () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val contentColor = if (isDark) Color.White else Color(0xFF0F172A)
    Box(modifier.backdropGlass(backdrop, shape, blurDp, tint, borderStroke, role)) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
