package com.jarves.mh.ui.theme.glass

import android.os.Build
import android.os.SystemClock
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
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
import androidx.compose.ui.graphics.drawscope.scale
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
import com.jarves.mh.ui.OpenPerf
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
        publisher: Any? = null,
    ) {
        this.publisher = publisher
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

    /** Glass shapes currently sampling this backdrop. With none, the source skips capturing. */
    internal var consumers: Int = 0
        private set

    internal fun addConsumer() {
        consumers++
        OpenPerf.log("glass consumer added, $consumers live")
        // A shape that appears after captures were skipped needs a fresh frame.
        requestCapture()
    }

    internal fun removeConsumer() {
        consumers = (consumers - 1).coerceAtLeast(0)
    }

    /** Source that published [currentResult]; only that source may clear it. */
    private var publisher: Any? = null

    /**
     * Drops the published capture when its source leaves composition, so no consumer keeps
     * sampling a stale frame of a screen that is no longer shown (the next source recaptures).
     */
    internal fun clearCapture(publisher: Any) {
        if (this.publisher !== publisher) return
        val previousResult = currentResult
        this.publisher = null
        currentResult = null
        if (previousResult != null && previousResult.layer !== fallbackLayer) {
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
    GlobalPositionAwareModifierNode,
    CompositionLocalConsumerModifierNode {

    private var lastCapturedInvalidator = -1L
    private var lastCapturedSize = IntSize.Zero
    private var lastCaptureAtMs = NO_CAPTURE
    private var pendingRecapture: Job? = null

    override fun onAttach() {
        super.onAttach()
        state.requestCapture()
    }

    override fun onDetach() {
        pendingRecapture = null
        lastCaptureAtMs = NO_CAPTURE
        lastCapturedInvalidator = -1L
        lastCapturedSize = IntSize.Zero
        state.clearCapture(this)
        super.onDetach()
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
        var invalidator = 0L
        observeReads {
            invalidator = state.sourceInvalidator
        }

        // 1. Normal on-screen rendering directly to window canvas
        drawContent()

        // 2. Dedicated completed-capture generation for glass sampling
        if (!blurSupported() || size.minDimension <= 0f) return
        // Nothing samples the backdrop right now: skip the capture work entirely.
        if (state.consumers == 0 && state.hasCapture) return
        val intSize = size.toIntSize()
        val now = SystemClock.uptimeMillis()
        val waitMs = backdropRecaptureDelayMs(
            nowMs = now,
            lastCaptureAtMs = lastCaptureAtMs,
            invalidated = invalidator != lastCapturedInvalidator,
            resized = intSize != lastCapturedSize,
            minIntervalMs = currentValueOf(LocalLiquidGlassConfig).debounceMs,
            contentIntervalMs = CONTENT_RECAPTURE_INTERVAL_MS,
        )
        if (waitMs > 0L) {
            // Throttled: one trailing redraw so the final frame of a scroll/animation is captured.
            if (pendingRecapture?.isActive != true) {
                pendingRecapture = coroutineScope.launch {
                    delay(waitMs)
                    invalidateDraw()
                }
            }
            return
        }
        pendingRecapture?.cancel()
        pendingRecapture = null

        val origin = state.sourceOrigin ?: Offset.Zero
        // Captured at reduced resolution (a quarter of the pixels at 0.5): glass only shows a
        // blurred impression, so the lost detail is invisible and every-frame capture stays cheap.
        val scale = currentValueOf(LocalLiquidGlassConfig).scaleFactor.coerceIn(0.25f, 1f)
        val scaledSize = IntSize(
            (intSize.width * scale).toInt().coerceAtLeast(1),
            (intSize.height * scale).toInt().coerceAtLeast(1),
        )

        // Allocate a fresh layer for this generation — never mutate in-use consumer layers
        val freshLayer = state.allocateCaptureLayer()
        freshLayer.compositingStrategy = CompositingStrategy.Offscreen

        try {
            // Record the complete source subtree into the fresh capture layer
            val recordStart = OpenPerf.nowMs()
            OpenPerf.span("glass.capture") {
                freshLayer.record(scaledSize) {
                    scale(scale, scale, pivot = Offset.Zero) {
                        this@draw.drawContent()
                    }
                }
            }
            OpenPerf.log("glass capture recorded in ${OpenPerf.nowMs() - recordStart} ms")
            lastCapturedInvalidator = invalidator
            lastCapturedSize = intSize
            lastCaptureAtMs = now

            // Atomically publish ONLY after recording is fully complete
            state.publishCapture(
                recordedLayer = freshLayer,
                origin = origin,
                size = intSize,
                scale = scale,
                publisher = this@BackdropSourceNode,
            )
        } catch (_: Throwable) {
            // If recording failed, release the un-published freshLayer immediately
            state.releaseLayerImmediately(freshLayer)
        }
    }
}

internal const val NO_CAPTURE = Long.MIN_VALUE

/**
 * Redraws not caused by an explicit invalidation (scroll, requestCapture) come from content
 * animating inside the source, e.g. a streaming reply or the thinking indicator. Glass only
 * needs a blurred impression of that content, so it is refreshed at ~4 Hz instead of every frame.
 * Each refresh re-renders the source and re-blurs every glass surface, so this stays low.
 */
internal const val CONTENT_RECAPTURE_INTERVAL_MS = 250L

/**
 * How long a source must wait before its next capture; 0 means capture now.
 * The first capture and size changes are immediate, invalidations (scroll, explicit requests)
 * are capped at [minIntervalMs], and other content redraws at [contentIntervalMs].
 */
internal fun backdropRecaptureDelayMs(
    nowMs: Long,
    lastCaptureAtMs: Long,
    invalidated: Boolean,
    resized: Boolean,
    minIntervalMs: Long,
    contentIntervalMs: Long,
): Long {
    if (lastCaptureAtMs == NO_CAPTURE || resized) return 0L
    val required = if (invalidated) minIntervalMs else contentIntervalMs
    return (required - (nowMs - lastCaptureAtMs)).coerceAtLeast(0L)
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
        blurDp = 10f,
        shadowDp = 8f,
        sheenAlpha = 1f,
        washAlphaDark = 0.16f,
        washAlphaLight = 0.26f,
    )

    /** Chat composer capsule: the most diffusion, so text behind it never competes with what you type. */
    val Composer = GlassRole(
        blurDp = 14f,
        shadowDp = 6f,
        sheenAlpha = 1f,
        washAlphaDark = 0.24f,
        washAlphaLight = 0.38f,
    )

    /** Floating "Latest" pill — medium diffusion. */
    val Latest = GlassRole(
        blurDp = 8f,
        shadowDp = 4f,
        sheenAlpha = 0.75f,
        washAlphaDark = 0.11f,
        washAlphaLight = 0.18f,
    )

    /** Small chips (commands / skills / inspector) — light diffusion. */
    val Chip = GlassRole(
        blurDp = 6f,
        shadowDp = 0f,
        sheenAlpha = 0.6f,
        washAlphaDark = 0.09f,
        washAlphaLight = 0.14f,
    )
}

private fun blurSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The one specular rim shared by every glass surface: bright at the top-leading edge, nearly
 * clear across the middle, with faint grounding at the bottom-trailing edge.
 */
internal fun glassRimBrush(isDark: Boolean): Brush = if (isDark) {
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
