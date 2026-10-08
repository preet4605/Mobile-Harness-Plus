package com.jarves.mh.ui.kit

import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketMotion.Token
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.glass.GlassGroup
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens
import com.jarves.mh.ui.theme.medium
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One tab of a [FloatingTabBar]. [selectedIcon] is the form shown while selected; [badge] is a
 * count drawn on the symbol's upper-right corner, shown only above zero.
 */
@Immutable
data class TabItem(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val badge: Int = 0,
)

/** Height of the floating tab bar and of its minimized circle and accessory button. */
val TabBarHeight: Dp = 62.dp

/**
 * The floating glass tab bar.
 * - The selection is a soft capsule that slides between tabs on a spring; drag along the bar and
 *   it follows the finger, landing on the tab under it.
 * - [minimized] shrinks the bar into a circle holding the selected tab's symbol (tap it to
 *   expand again through [onExpand]); the width morphs on a spring and the labels fade.
 * - [accessory] is a separate glass circle at the end (for example Terminal). On Android 13+
 *   the bar and the circle are one piece of glass that separates as they move apart.
 */
@Composable
fun FloatingTabBar(
    tabs: List<TabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minimized: Boolean = false,
    onExpand: () -> Unit = {},
    accessory: (@Composable () -> Unit)? = null,
) {
    CappedTextScale {
        val haptics = rememberHaptics()
        val density = LocalDensity.current
        val collapse = remember { Animatable(if (minimized) 1f else 0f) }
        LaunchedEffect(minimized) { collapse.animateTo(if (minimized) 1f else 0f, PocketMotion.spec(Token.Morph)) }
        val scope = rememberCoroutineScope()
        val position = remember { Animatable(selectedIndex.toFloat()) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(selectedIndex) {
            if (!dragging) position.animateTo(selectedIndex.toFloat(), PocketMotion.spec(Token.Snappy))
        }
        val dragScale = remember { Animatable(1f) }
        val colors = PocketColors.current
        val accent = MaterialTheme.colorScheme.primary
        val heightPx = with(density) { TabBarHeight.toPx() }

        var fullWidth by remember { mutableIntStateOf(0) }
        GlassGroup(modifier, spacing = 14.dp) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PocketSpacing.md),
            ) {
                LiquidGlassSurface(
                    modifier = Modifier
                        .weight(1f)
                        .height(TabBarHeight)
                        .layout { measurable, constraints ->
                            // The glass morphs from the full bar into a circle at the leading edge;
                            // the slot keeps its width so the accessory never jumps.
                            val full = constraints.maxWidth
                            if (full != fullWidth) fullWidth = full
                            val target = lerp(full.toFloat(), heightPx, collapse.value).roundToInt().coerceIn(heightPx.roundToInt(), full)
                            val placeable = measurable.measure(constraints.copy(minWidth = target, maxWidth = target))
                            layout(full, placeable.height) { placeable.place(0, 0) }
                        },
                    shape = PocketShape.capsule,
                    layerSource = LiquidGlassLayers.Background,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        // Expanded tabs.
                        Row(
                            Modifier
                                .fillMaxHeight()
                                // Tabs keep their full-bar layout while the glass narrows (clipped),
                                // so labels never squeeze during the morph.
                                .layout { measurable, constraints ->
                                    val w = if (fullWidth > 0) fullWidth else constraints.maxWidth
                                    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                                    layout(constraints.maxWidth, placeable.height) { placeable.place(0, 0) }
                                }
                                .padding4()
                                .graphicsLayer { alpha = (1f - collapse.value * 2f).coerceIn(0f, 1f) }
                                .drawBehind {
                                    val n = tabs.size.coerceAtLeast(1)
                                    val w = size.width / n
                                    val scale = dragScale.value
                                    val pillW = w * scale
                                    val pillH = size.height * (0.92f + (scale - 1f) * 0.4f).coerceAtMost(1f)
                                    val x = position.value * w + (w - pillW) / 2f
                                    val y = (size.height - pillH) / 2f
                                    // The selection is glass, like the rest of the bar: a faint fill
                                    // with a hairline rim, not an opaque grey pill.
                                    val pillCorner = CornerRadius(pillH / 2f)
                                    drawRoundRect(
                                        color = if (colors.isDark) LiquidGlassTokens.SelectionDark else LiquidGlassTokens.SelectionLight,
                                        topLeft = Offset(x, y),
                                        size = Size(pillW, pillH),
                                        cornerRadius = pillCorner,
                                    )
                                    drawRoundRect(
                                        color = if (colors.isDark) LiquidGlassTokens.GlassBorderDark else LiquidGlassTokens.GlassBorderLight,
                                        topLeft = Offset(x, y),
                                        size = Size(pillW, pillH),
                                        cornerRadius = pillCorner,
                                        style = Stroke(width = 0.5.dp.toPx()),
                                    )
                                }
                                .then(
                                    if (minimized) Modifier else Modifier.pointerInput(tabs.size) {
                                        val n = tabs.size.coerceAtLeast(1)
                                        detectHorizontalDragGestures(
                                            onDragStart = { start ->
                                                dragging = true
                                                scope.launch { dragScale.animateTo(1.12f, PocketMotion.spec(Token.Snappy)) }
                                                scope.launch { position.snapTo((start.x / (size.width / n.toFloat()) - 0.5f).coerceIn(0f, n - 1f)) }
                                            },
                                            onDragEnd = {
                                                dragging = false
                                                val target = position.value.roundToInt().coerceIn(0, n - 1)
                                                scope.launch { dragScale.animateTo(1f, PocketMotion.spec(Token.Release)) }
                                                scope.launch { position.animateTo(target.toFloat(), PocketMotion.spec(Token.Release)) }
                                                if (target != selectedIndex) {
                                                    haptics.selection()
                                                    onSelect(target)
                                                }
                                            },
                                            onDragCancel = {
                                                dragging = false
                                                scope.launch { dragScale.animateTo(1f, PocketMotion.spec(Token.Release)) }
                                                scope.launch { position.animateTo(selectedIndex.toFloat(), PocketMotion.spec(Token.Release)) }
                                            },
                                        ) { change, amount ->
                                            change.consume()
                                            val before = position.value.roundToInt()
                                            val next = (position.value + amount / (size.width / n.toFloat())).coerceIn(0f, n - 1f)
                                            scope.launch { position.snapTo(next) }
                                            if (next.roundToInt() != before) haptics.selection()
                                        }
                                    },
                                )
                                .selectableGroup(),
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                val selected = index == selectedIndex
                                val tint = if (selected) accent else MaterialTheme.colorScheme.onSurface
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .selectable(
                                            selected = selected,
                                            enabled = !minimized,
                                            role = Role.Tab,
                                            interactionSource = null,
                                            indication = null,
                                        ) {
                                            if (!selected) {
                                                haptics.selection()
                                                onSelect(index)
                                            }
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Box(contentAlignment = Alignment.TopEnd) {
                                        Icon(
                                            if (selected) tab.selectedIcon else tab.icon,
                                            contentDescription = null,
                                            tint = tint,
                                            modifier = Modifier.size(22.dp),
                                        )
                                        if (tab.badge > 0) TabBadge(tab.badge, accent)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        tab.label,
                                        style = PocketType.caption2.medium,
                                        color = tint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Clip,
                                    )
                                }
                            }
                        }
                        // Minimized: the selected tab's symbol in a circle.
                        if (collapse.value > 0f) {
                            val tab = tabs.getOrNull(selectedIndex)
                            Box(
                                Modifier
                                    .size(TabBarHeight)
                                    .graphicsLayer { alpha = ((collapse.value - 0.5f) * 2f).coerceIn(0f, 1f) }
                                    .then(
                                        if (minimized) Modifier
                                            .selectable(selected = true, role = Role.Button, interactionSource = null, indication = null) { onExpand() }
                                            .semantics {
                                                contentDescription = "${tab?.label ?: ""}, show tab bar"
                                                onClick(label = "Show tab bar") { onExpand(); true }
                                            }
                                        else Modifier,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (tab != null) Icon(tab.selectedIcon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                            }
                        }
                    }
                }
                if (accessory != null) accessory()
            }
        }
    }
}

/** A small accent capsule with a count, on the upper-right corner of a tab symbol. */
@Composable
private fun TabBadge(count: Int, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .offset(x = 8.dp, y = (-6).dp)
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(PocketShape.capsule)
            .background(color)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            style = PocketType.caption2.emphasized,
            color = androidx.compose.ui.graphics.Color.White,
            maxLines = 1,
        )
    }
}

/** A round glass button the size of the tab bar, for the bar's [FloatingTabBar] accessory slot. */
@Composable
fun TabBarAccessory(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    LiquidGlassSurface(
        modifier = modifier.size(TabBarHeight),
        shape = PocketShape.capsule,
        layerSource = LiquidGlassLayers.Background,
        onClick = onClick,
        semanticRole = Role.Button,
        contentDescription = contentDescription,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
    }
}

private fun Modifier.padding4(): Modifier = this.then(Modifier.layout { measurable, constraints ->
    val inset = 4.dp.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = (constraints.minWidth - inset * 2).coerceAtLeast(0),
            maxWidth = (constraints.maxWidth - inset * 2).coerceAtLeast(0),
            minHeight = (constraints.minHeight - inset * 2).coerceAtLeast(0),
            maxHeight = (constraints.maxHeight - inset * 2).coerceAtLeast(0),
        ),
    )
    layout(placeable.width + inset * 2, placeable.height + inset * 2) { placeable.place(inset, inset) }
})

/**
 * Scroll-driven minimize for a [FloatingTabBar]: scrolling content down minimizes the bar,
 * scrolling up (or reaching the top) brings it back. Attach [connection] with `nestedScroll`.
 * It stays expanded while TalkBack touch exploration is on, so the tabs are always reachable.
 */
@Stable
class TabBarMinimizeState internal constructor(private val threshold: Float) {
    var minimized by mutableStateOf(false)
    internal var enabled by mutableStateOf(true)
    private var travel by mutableFloatStateOf(0f)

    fun expand() {
        minimized = false
        travel = 0f
    }

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled) {
                if (minimized) minimized = false
                return Offset.Zero
            }
            val dy = consumed.y
            if (dy == 0f) {
                // Pulled past the top: show the bar.
                if (available.y > 0f && minimized) expand()
                return Offset.Zero
            }
            travel = if ((travel < 0f) == (dy < 0f)) travel + dy else dy
            if (travel < -threshold && !minimized) minimized = true
            if (travel > threshold && minimized) minimized = false
            return Offset.Zero
        }
    }
}

@Composable
fun rememberTabBarMinimizeState(): TabBarMinimizeState {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    val state = remember(threshold) { TabBarMinimizeState(threshold) }
    val touchExploration by rememberTouchExplorationEnabled()
    state.enabled = !touchExploration
    if (touchExploration && state.minimized) state.minimized = false
    return state
}

/** Whether TalkBack (or another touch-exploration service) is on; updates live. */
@Composable
fun rememberTouchExplorationEnabled(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val state = remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { state.value = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return state
}
