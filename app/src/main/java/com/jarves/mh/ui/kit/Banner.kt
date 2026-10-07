package com.jarves.mh.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketMotion.Token
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.medium
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class BannerKind { Info, Success, Error }

@Immutable
data class BannerMessage(val text: String, val kind: BannerKind, val id: Long)

/** Short status messages that drop in from the top (instead of toasts). */
@Stable
class BannerState {
    var current by mutableStateOf<BannerMessage?>(null)
        private set
    private var nextId = 0L

    fun show(text: String, kind: BannerKind = BannerKind.Info) {
        current = BannerMessage(text, kind, nextId++)
    }

    fun dismiss(id: Long) {
        if (current?.id == id) current = null
    }
}

val LocalBanner = staticCompositionLocalOf<BannerState?> { null }

/** Shows [text] in the app's banner, or does nothing outside a [BannerHost]. */
@Composable
fun rememberBanner(): (String, BannerKind) -> Unit {
    val state = LocalBanner.current
    return remember(state) { { text, kind -> state?.show(text, kind) } }
}

/**
 * Hosts the top banner over [content]: a glass capsule that drops from the top with a soft
 * bounce, stays for a few seconds (longer for errors), and can be swiped up to dismiss.
 * Accessibility services announce it politely.
 */
@Composable
fun BannerHost(state: BannerState, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    CompositionLocalProvider(LocalBanner provides state) {
        Box(modifier) {
            content()
            val message = state.current
            if (message != null) {
                androidx.compose.runtime.key(message.id) { BannerView(message, onGone = { state.dismiss(message.id) }) }
            }
        }
    }
}

@Composable
private fun BoxScope.BannerView(message: BannerMessage, onGone: () -> Unit) {
    val colors = PocketColors.current
    val scope = rememberCoroutineScope()
    val presence = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var height by remember { mutableStateOf(0) }
    val dismissDistance = with(LocalDensity.current) { 32.dp.toPx() }
    suspend fun hide() {
        presence.animateTo(0f, PocketMotion.spec(Token.Smooth))
        onGone()
    }
    LaunchedEffect(message.id) {
        presence.animateTo(1f, PocketMotion.spec(Token.Snappy))
        delay(if (message.kind == BannerKind.Error) 6_000L else 3_500L)
        hide()
    }
    val (icon, tint) = when (message.kind) {
        BannerKind.Info -> Icons.Outlined.Info to MaterialTheme.colorScheme.primary
        BannerKind.Success -> Icons.Outlined.CheckCircle to colors.green
        BannerKind.Error -> Icons.Outlined.ErrorOutline to colors.red
    }
    LiquidGlassSurface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = PocketSpacing.sm, start = PocketSpacing.lg, end = PocketSpacing.lg)
            .widthIn(max = 520.dp)
            .onSizeChanged { height = it.height }
            .graphicsLayer {
                val p = presence.value
                translationY = -(1f - p) * (height + 64.dp.toPx()) + drag.value.coerceAtMost(0f)
                alpha = p.coerceIn(0f, 1f)
            }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> scope.launch { drag.snapTo((drag.value + delta).coerceAtMost(0f)) } },
                onDragStopped = { velocity ->
                    if (drag.value < -dismissDistance || velocity < -600f) hide()
                    else drag.animateTo(0f, PocketMotion.spec(Token.Release), initialVelocity = velocity)
                },
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = PocketShape.capsule,
        layerSource = LiquidGlassLayers.Background,
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(PocketSpacing.sm))
            Text(
                message.text,
                style = PocketType.subheadline.medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
