package com.jarves.mh.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.unit.lerp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketMotion.Token
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassMaterial
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.glass.isGlassDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Overlay host: sheets, menus and popovers render inside the app window (not separate dialog
// windows), above the screen, so they can be real glass sampling the screen behind them and can
// grow out of the control that opened them.
// ---------------------------------------------------------------------------------------------

/** The control an overlay grows out of; focus returns to it when the overlay closes. */
@Stable
class OverlayAnchor {
    internal var bounds by mutableStateOf<Rect?>(null)
    internal val focus = FocusRequester()

    internal fun restoreFocus() {
        runCatching { focus.requestFocus() }
    }
}

@Composable
fun rememberOverlayAnchor(): OverlayAnchor = remember { OverlayAnchor() }

/** Marks the control that opens an overlay with [anchor]. */
fun Modifier.overlayAnchor(anchor: OverlayAnchor): Modifier = this
    .onGloballyPositioned { anchor.bounds = it.boundsInWindow() }
    .focusRequester(anchor.focus)

internal class OverlayEntry(val id: Long, modal: Boolean) {
    var frame: @Composable (OverlayPhase) -> Unit by mutableStateOf({})
    var dismissed by mutableStateOf(false)
    var modal by mutableStateOf(modal)
}

/** Where an overlay is in its life: [exiting] once its caller dismissed it; call [remove] when the exit animation ends. */
@Stable
class OverlayPhase internal constructor(val exiting: Boolean, val remove: () -> Unit)

@Stable
class OverlayHostState internal constructor() {
    internal val entries = mutableStateListOf<OverlayEntry>()
    internal var origin by mutableStateOf(Offset.Zero)
    private var nextId = 0L
    internal fun newEntry(modal: Boolean) = OverlayEntry(nextId++, modal)
    val hasModal: Boolean get() = entries.any { it.modal && !it.dismissed }
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

/**
 * Hosts in-window overlays above [content]. While a modal overlay is open the screen behind it
 * is hidden from accessibility services, as it would be behind a dialog window.
 */
@Composable
fun OverlayHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { OverlayHostState() }
    CompositionLocalProvider(LocalOverlayHost provides state) {
        Box(modifier.onGloballyPositioned { state.origin = it.positionInWindow() }) {
            Box(Modifier.fillMaxSize().then(if (state.hasModal) Modifier.clearAndSetSemantics { } else Modifier)) {
                content()
            }
            state.entries.forEach { entry ->
                key(entry.id) {
                    entry.frame(OverlayPhase(entry.dismissed) { state.entries.remove(entry) })
                }
            }
        }
    }
}

/**
 * Renders [frame] in the nearest [OverlayHost] while the caller is composed. When the caller
 * leaves composition the frame stays for its exit animation (it gets `exiting = true` and must
 * not compose caller content any more). Without a host it renders in place.
 */
@Composable
internal fun OverlayPortal(modal: Boolean, frame: @Composable (OverlayPhase) -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        frame(OverlayPhase(false) {})
        return
    }
    val entry = remember(host) { host.newEntry(modal) }
    SideEffect {
        entry.frame = frame
        entry.modal = modal
    }
    DisposableEffect(host, entry) {
        host.entries.add(entry)
        onDispose { entry.dismissed = true }
    }
}

/**
 * Makes this element the target for touches inside it without consuming them, so they never fall
 * through to a dismiss scrim behind it while its own controls still get every tap.
 */
internal fun Modifier.absorbPointer(): Modifier = this.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Final)
    }
}

// ---------------------------------------------------------------------------------------------
// Sheet
// ---------------------------------------------------------------------------------------------

/** Heights a sheet can rest at. [Fit] sizes to the content (up to [Large]). */
enum class SheetDetent { Fit, Medium, Large }

private val SheetInset = 8.dp
private val SheetRadius = 34.dp

/**
 * A glass sheet inside the app window.
 * - Rests at [detents]; drag the grabber or the content past its top to move between them. It
 *   follows the finger and snaps with the fling's velocity; dragging below the lowest detent
 *   dismisses it.
 * - At Medium it floats inset from the screen edges as glass; at Large it meets the edges and
 *   turns opaque, because it is then the content.
 * - With an [anchor] it grows out of that control and shrinks back into it.
 * - Back closes it first, the keyboard pushes it up, and accessibility services read it as a
 *   pane named [title] with everything behind it hidden.
 */
@Composable
fun GlassSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    title: String? = null,
    detents: List<SheetDetent> = listOf(SheetDetent.Medium, SheetDetent.Large),
    initialDetent: SheetDetent = detents.first(),
    anchor: OverlayAnchor? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!visible) return
    OverlayPortal(modal = true) { phase ->
        SheetFrame(phase, onDismiss, modifier, title, detents.sortedBy { it.ordinal }, initialDetent, anchor, leading, trailing, content)
    }
}

@Composable
private fun SheetFrame(
    phase: OverlayPhase,
    onDismiss: () -> Unit,
    modifier: Modifier,
    title: String?,
    detents: List<SheetDetent>,
    initialDetent: SheetDetent,
    anchor: OverlayAnchor?,
    leading: (@Composable RowScope.() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PocketColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val presence = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var detentIndex by remember { mutableIntStateOf(detents.indexOf(initialDetent).coerceAtLeast(0)) }
    var fitHeight by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val host = LocalOverlayHost.current
    val startBounds = remember { anchor?.bounds }

    LaunchedEffect(phase.exiting) {
        if (!phase.exiting) {
            presence.animateTo(1f, PocketMotion.spec(if (startBounds != null) Token.Morph else Token.Smooth))
            runCatching { focus.requestFocus() }
        } else {
            presence.animateTo(0f, PocketMotion.spec(Token.Smooth))
            anchor?.restoreFocus()
            phase.remove()
        }
    }
    BackHandler(enabled = !phase.exiting) { onDismiss() }

    val scrimAlpha = if (isGlassDarkTheme()) 0.45f else 0.22f
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = presence.value.coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = scrimAlpha))
                .pointerInput(phase.exiting) { if (!phase.exiting) detectTapGestures { onDismiss() } },
        )
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        ) {
            val full = constraints.maxHeight.toFloat()
            val statusTop = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx() }
            val large = full - statusTop - with(density) { 10.dp.toPx() }
            val medium = min(full * 0.55f, large)
            fun heightOf(d: SheetDetent): Float = when (d) {
                SheetDetent.Large -> large
                SheetDetent.Medium -> medium
                SheetDetent.Fit -> if (fitHeight > 0) min(fitHeight.toFloat(), large) else medium
            }
            val heights = detents.map(::heightOf)
            val current = heights[detentIndex.coerceIn(0, heights.lastIndex)]
            val maxHeight = heights.max()
            val fitsContent = detents == listOf(SheetDetent.Fit)

            fun visibleHeight() = (current - drag.value).coerceIn(0f, maxHeight + with(density) { 24.dp.toPx() })

            fun settle(velocity: Float) {
                val visible = visibleHeight()
                // Project where the fling would carry the sheet, then rest at the nearest detent.
                val projected = visible - velocity * 0.18f
                val lowest = heights.min()
                if (projected < lowest * 0.55f) {
                    onDismiss()
                    return
                }
                val target = heights.indices.minBy { abs(heights[it] - projected) }
                if (target != detentIndex) {
                    detentIndex = target
                }
                scope.launch {
                    drag.snapTo(heights[target] - visible)
                    drag.animateTo(0f, PocketMotion.spec(Token.Release), initialVelocity = velocity)
                }
            }

            val nested = remember(heights, detentIndex) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        // Finger moving up while the sheet can still grow: grow the sheet first.
                        if (available.y < 0f && visibleHeight() < maxHeight) {
                            val room = maxHeight - visibleHeight()
                            val take = max(available.y, -room)
                            scope.launch { drag.snapTo(drag.value + take) }
                            return Offset(0f, take)
                        }
                        return Offset.Zero
                    }

                    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                        // Content is at its top and the finger keeps pulling down: move the sheet.
                        if (available.y > 0f && source == NestedScrollSource.UserInput) {
                            scope.launch { drag.snapTo(drag.value + available.y) }
                            return Offset(0f, available.y)
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPreFling(available: Velocity): Velocity {
                        if (drag.value != 0f) {
                            settle(available.y)
                            return available
                        }
                        return Velocity.Zero
                    }
                }
            }

            val largeProgress = if (SheetDetent.Large in detents && heights.size > 1) {
                ((visibleHeight() - medium) / max(large - medium, 1f)).coerceIn(0f, 1f)
            } else if (detents == listOf(SheetDetent.Large)) 1f else 0f
            val inset = SheetInset * (1f - largeProgress)
            val insetPx = with(density) { inset.toPx() }
            val fullWidth = constraints.maxWidth.toFloat()
            // While growing out of an anchor, the corner radius eases from the anchor's (a capsule
            // for round buttons, measured in the scaled panel) to the sheet's.
            val morphRadius: Dp? = startBounds?.let { b ->
                val p = presence.value.coerceIn(0f, 1f)
                if (p >= 1f) null else {
                    val sy = (b.height / max(current, 1f)).coerceIn(0.05f, 1f)
                    val startRadius = with(density) { (b.minDimension / 2f / sy).toDp() }
                    lerp(startRadius.coerceAtMost(with(density) { (current / 2f).toDp() }), SheetRadius, p)
                }
            }
            val topRadius = morphRadius ?: SheetRadius
            val bottomRadius = morphRadius ?: (SheetRadius * (1f - largeProgress))
            val shape = RoundedCornerShape(topStart = topRadius, topEnd = topRadius, bottomStart = bottomRadius, bottomEnd = bottomRadius)
            val panelHeightPx = if (fitsContent) null else maxHeight
            val frozen = rememberGraphicsLayer()
            var contentSize by remember { mutableStateOf(IntSize.Zero) }

            Box(
                modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = inset, end = inset, bottom = inset)
                    .fillMaxWidth()
                    .then(
                        if (panelHeightPx != null) Modifier.height(with(density) { panelHeightPx.toDp() })
                        else Modifier.heightIn(max = with(density) { large.toDp() }),
                    )
                    .onSizeChanged { if (fitsContent && !phase.exiting) fitHeight = it.height }
                    .offset { IntOffset(0, ((panelHeightPx ?: fitHeight.toFloat()) - visibleHeight()).roundToInt().coerceAtLeast(0)) }
                    .graphicsLayer {
                        val p = presence.value
                        val b = startBounds
                        if (b != null && host != null) {
                            // Grow out of the anchor: the visible panel starts exactly on the anchor's bounds.
                            transformOrigin = TransformOrigin(0f, 0f)
                            val visible = max(visibleHeight(), 1f)
                            val panelLeft = insetPx
                            val panelTop = full - insetPx - visible
                            scaleX = lerp((b.width / max(fullWidth - insetPx * 2f, 1f)).coerceIn(0.05f, 1f), 1f, p)
                            scaleY = lerp((b.height / visible).coerceIn(0.05f, 1f), 1f, p)
                            translationX = lerp(b.left - host.origin.x - panelLeft, 0f, p)
                            translationY = lerp(b.top - host.origin.y - panelTop, 0f, p)
                            alpha = (p * 4f).coerceIn(0f, 1f)
                        } else {
                            translationY = (1f - p) * (visibleHeight() + insetPx + 24.dp.toPx())
                        }
                    }
                    .semantics {
                        isTraversalGroup = true
                        traversalIndex = -1f
                        if (title != null) paneTitle = title
                        dismiss { onDismiss(); true }
                    }
                    .focusRequester(focus)
                    .absorbPointer(),
            ) {
                LiquidGlassSurface(
                    modifier = Modifier.matchParentSize(),
                    material = LiquidGlassMaterial.Thick,
                    shape = shape,
                    layerSource = LiquidGlassLayers.Background,
                    allowLens = false,
                ) {
                    // Opaque at the large detent: the sheet is then the content.
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = largeProgress }.background(colors.groupedBackground))
                }
                val contentAlpha = { if (startBounds != null) ((presence.value - 0.35f) / 0.65f).coerceIn(0f, 1f) else 1f }
                Column(
                    Modifier
                        .then(if (fitsContent) Modifier else Modifier.fillMaxSize())
                        .graphicsLayer { alpha = contentAlpha() }
                        .nestedScroll(nested),
                ) {
                    SheetHeader(
                        title = title,
                        leading = leading,
                        trailing = trailing,
                        modifier = Modifier.draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { delta -> scope.launch { drag.snapTo(drag.value + delta) } },
                            onDragStopped = { velocity -> settle(velocity) },
                        ),
                    )
                    // Caller content is live while shown; during the exit it is a frozen frame,
                    // because the caller may already have cleared the state it reads.
                    val body = Modifier
                        .fillMaxWidth()
                        .freezeOnExit(phase.exiting, frozen, contentSize) { contentSize = it }
                    if (fitsContent) {
                        Column(body) { if (!phase.exiting) content() }
                    } else {
                        Column(body.weight(1f)) { if (!phase.exiting) content() }
                    }
                }
            }
        }
    }
}

/** Grabber plus a 52dp header row: leading action, centred title, trailing action. Draggable. */
@Composable
private fun SheetHeader(
    title: String?,
    leading: (@Composable RowScope.() -> Unit)?,
    trailing: (@Composable RowScope.() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    CappedTextScale {
        Column(modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().padding(top = 5.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .background(PocketColors.current.tertiaryLabel, PocketShape.capsule),
                )
            }
            if (title != null || leading != null || trailing != null) {
                Box(Modifier.fillMaxWidth().height(BarHeight).padding(horizontal = ListInset)) {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (leading != null) leading()
                        Spacer(Modifier.weight(1f))
                        if (trailing != null) trailing()
                    }
                    if (title != null) {
                        Text(
                            title,
                            style = PocketType.headline,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 96.dp)
                                .semantics { heading() },
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(PocketSpacing.sm))
            }
        }
    }
}

/**
 * Records the content every frame while live and keeps drawing the last recording (at the last
 * size) once [exiting], so an overlay can animate out without composing its caller's content.
 */
internal fun Modifier.freezeOnExit(
    exiting: Boolean,
    layer: GraphicsLayer,
    lastSize: IntSize,
    onSize: (IntSize) -> Unit,
): Modifier = this
    .then(if (exiting && lastSize != IntSize.Zero) Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(lastSize.width.coerceIn(constraints.minWidth, constraints.maxWidth), lastSize.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            placeable.place(0, 0)
        }
    } else Modifier)
    .onSizeChanged { if (!exiting) onSize(it) }
    .drawWithContent {
        if (!exiting) {
            layer.record { this@drawWithContent.drawContent() }
        }
        drawLayer(layer)
    }

// ---------------------------------------------------------------------------------------------
// Menu and popover
// ---------------------------------------------------------------------------------------------

internal class MenuScopeState(val dismiss: () -> Unit) {
    var count = 0
}

internal val LocalMenu = staticCompositionLocalOf<MenuScopeState?> { null }

/**
 * A glass menu that pops out of [anchor]: it grows from the anchor's nearest corner with a
 * slight overshoot and its items fade in one after another. Tapping outside, Back, or choosing
 * an item closes it. Put [GlassMenuItem]s and [GlassMenuDivider]s inside.
 */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: OverlayAnchor,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPopover(expanded, onDismiss, anchor, modifier.widthIn(min = 220.dp, max = 280.dp)) {
        val menu = remember { MenuScopeState(onDismiss) }
        CompositionLocalProvider(LocalMenu provides menu) {
            Column(Modifier.padding(vertical = PocketSpacing.xs + 2.dp), content = content)
        }
    }
}

/** A floating glass panel anchored to a control, for small tools and pickers. */
@Composable
fun GlassPopover(
    expanded: Boolean,
    onDismiss: () -> Unit,
    anchor: OverlayAnchor,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!expanded) return
    OverlayPortal(modal = false) { phase ->
        PopoverFrame(phase, onDismiss, anchor, modifier, content)
    }
}

@Composable
private fun PopoverFrame(
    phase: OverlayPhase,
    onDismiss: () -> Unit,
    anchor: OverlayAnchor,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val presence = remember { Animatable(0f) }
    val host = LocalOverlayHost.current
    val origin = host?.origin ?: Offset.Zero
    val anchorBounds = remember { anchor.bounds } ?: Rect(Offset.Zero, androidx.compose.ui.geometry.Size.Zero)
    LaunchedEffect(phase.exiting) {
        if (!phase.exiting) {
            presence.animateTo(1f, PocketMotion.spec(Token.Snappy))
        } else {
            presence.animateTo(0f, PocketMotion.spec(Token.Quick))
            anchor.restoreFocus()
            phase.remove()
        }
    }
    BackHandler(enabled = !phase.exiting) { onDismiss() }
    var pivot by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val frozen = rememberGraphicsLayer()
    var contentSize by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val margin = with(LocalDensity.current) { PocketSpacing.md.roundToPx() }
    Box(Modifier.fillMaxSize()) {
        // Tapping outside closes it. A sibling behind the panel, not its parent, so taps on the
        // panel's items are never claimed by the dismiss gesture.
        Box(Modifier.fillMaxSize().pointerInput(phase.exiting) { if (!phase.exiting) detectTapGestures { onDismiss() } })
        Layout(
            content = {
                LiquidGlassSurface(
                    modifier = modifier
                        .graphicsLayer {
                            val p = presence.value
                            transformOrigin = pivot
                            scaleX = lerp(0.4f, 1f, p)
                            scaleY = lerp(0.4f, 1f, p)
                            alpha = p.coerceIn(0f, 1f)
                        }
                        .semantics { isTraversalGroup = true; dismiss { onDismiss(); true } }
                        .absorbPointer(),
                    // Thick: menus hold text over arbitrary content and must stay legible.
                    material = LiquidGlassMaterial.Thick,
                    shape = PocketShape.lg,
                    layerSource = LiquidGlassLayers.Background,
                ) {
                    Box(Modifier.freezeOnExit(phase.exiting, frozen, contentSize) { contentSize = it }) {
                        if (!phase.exiting) content()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth - margin * 2, maxHeight = constraints.maxHeight - margin * 2)
            val placeable = measurables.first().measure(loose)
            val a = anchorBounds.translate(-origin)
            val alignEnd = a.center.x > constraints.maxWidth / 2f
            val x = if (alignEnd) (a.right - placeable.width).roundToInt() else a.left.roundToInt()
            val below = a.bottom + gap + placeable.height <= constraints.maxHeight - margin
            val y = if (below) (a.bottom + gap).roundToInt() else (a.top - gap - placeable.height).roundToInt()
            val newPivot = TransformOrigin(if (alignEnd) 1f else 0f, if (below) 0f else 1f)
            if (newPivot != pivot) pivot = newPivot
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(
                    x.coerceIn(margin, (constraints.maxWidth - placeable.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (constraints.maxHeight - placeable.height - margin).coerceAtLeast(margin)),
                )
            }
        }
    }
}

/** One action in a [GlassMenu]: label, optional trailing symbol, destructive in red. Closes the menu. */
@Composable
fun GlassMenuItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    checked: Boolean = false,
) {
    val menu = LocalMenu.current
    val index = remember { menu?.let { it.count++ } ?: 0 }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(15L * index)
        appear.animateTo(1f, PocketMotion.spec(Token.Quick))
    }
    val colors = PocketColors.current
    val tint = when {
        !enabled -> colors.tertiaryLabel
        destructive -> colors.red
        else -> MaterialTheme.colorScheme.onSurface
    }
    ListRow(
        title = text,
        modifier = modifier.graphicsLayer { alpha = appear.value },
        titleColor = tint,
        enabled = enabled,
        onClick = {
            menu?.dismiss?.invoke()
            onClick()
        },
        trailing = {
            if (checked) {
                androidx.compose.material3.Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else if (icon != null) {
                androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
        },
    )
}

@Composable
fun GlassMenuDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = PocketSpacing.xs)
            .height(6.dp)
            .background(PocketColors.current.quaternaryFill),
    )
}
