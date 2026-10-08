package com.jarves.mh.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.glass.ScrollEdgeEffect

/** Height of the bar row under the status bar. */
val BarHeight: Dp = 52.dp

/** How far the list scrolls before the large title has fully moved into the bar. */
private val CollapseDistance = 44.dp

/**
 * Fraction (0 = expanded, 1 = collapsed) of the large title for a list whose first item is
 * [LargeTitle]. Scroll-linked, so it tracks the finger with no animation of its own.
 */
@Composable
fun rememberTitleCollapse(listState: LazyListState): State<Float> {
    val distance = with(LocalDensity.current) { CollapseDistance.toPx() }
    return remember(listState, distance) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1f
            else (listState.firstVisibleItemScrollOffset / distance).coerceIn(0f, 1f)
        }
    }
}

@Composable
fun rememberTitleCollapse(scrollState: ScrollState): State<Float> {
    val distance = with(LocalDensity.current) { CollapseDistance.toPx() }
    return remember(scrollState, distance) {
        derivedStateOf { (scrollState.value / distance).coerceIn(0f, 1f) }
    }
}

/** The large title, as the first item of a scrolling list under a [LargeTitleBar]. */
@Composable
fun LargeTitle(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth().padding(start = ListInset + 4.dp, end = ListInset, top = PocketSpacing.xs, bottom = PocketSpacing.sm)) {
        Text(
            title,
            style = PocketType.largeTitle,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        if (subtitle != null) {
            Text(subtitle, style = PocketType.subheadline, color = PocketColors.current.secondaryLabel)
        }
    }
}

/**
 * The bar over a large-title list: glass toolbar buttons at either end, an inline title that
 * fades in as the large title scrolls under it, and a soft edge that keeps the controls
 * legible over the content once anything is underneath. No hairline.
 */
@Composable
fun LargeTitleBar(
    title: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    alwaysShowTitle: Boolean = false,
) {
    CappedTextScale {
        Box(modifier.fillMaxWidth()) {
            // Soft scroll edge: content blurs and fades into the canvas under the bar.
            ScrollEdgeEffect(Modifier.matchParentSize(), fromTop = true) { (collapse() * 2f).coerceIn(0f, 1f) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(bottom = PocketSpacing.md),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(BarHeight)
                        .padding(horizontal = ListInset),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                ) {
                    if (leading != null) leading()
                    Spacer(Modifier.weight(1f))
                    if (trailing != null) trailing()
                }
                Text(
                    title,
                    style = PocketType.headline,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 112.dp)
                        .graphicsLayer {
                            alpha = if (alwaysShowTitle) 1f else ((collapse() - 0.55f) / 0.45f).coerceIn(0f, 1f)
                        },
                )
            }
        }
    }
}

/** Top padding a list needs to start below a [LargeTitleBar] (status bar + bar). */
@Composable
fun largeTitleBarPadding(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BarHeight

/**
 * Screen frame for a large-title list. [content] receives the padding to start below the bar
 * and is expected to put [LargeTitle] first; the bar draws over it.
 */
@Composable
fun LargeTitleScaffold(
    title: String,
    collapse: () -> Float,
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val top = largeTitleBarPadding()
    Box(modifier.fillMaxSize()) {
        content(PaddingValues(top = top))
        LargeTitleBar(title = title, collapse = collapse, leading = leading, trailing = trailing)
        if (overlay != null) overlay()
    }
}

/** Width that keeps leading and trailing bar areas balanced when one side is empty. */
@Composable
fun BarSpacer() = Spacer(Modifier.width(44.dp))
