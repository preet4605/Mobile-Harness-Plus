package com.jarves.mh.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens

private val TrackWidth = 51.dp
private val TrackHeight = 31.dp
private val Thumb = 27.dp
private val ThumbInset = 2.dp

/**
 * On/off switch: a 51×31 capsule track (green when on) with a white thumb that springs across
 * and stretches slightly while pressed. Ticks a haptic on change. Touch target is 44 dp tall.
 */
@Composable
fun PocketToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = PocketColors.current
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val track by animateColorAsState(
        if (checked) colors.green else colors.fill,
        PocketMotion.spec(PocketMotion.Token.Quick),
        label = "toggleTrack",
    )
    val travel = TrackWidth - Thumb - ThumbInset * 2
    val offset by animateDpAsState(
        if (checked) travel else 0.dp,
        PocketMotion.spec(PocketMotion.Token.Snappy),
        label = "toggleThumb",
    )
    val stretch by animateDpAsState(
        if (pressed) 6.dp else 0.dp,
        PocketMotion.spec(PocketMotion.Token.Snappy),
        label = "toggleStretch",
    )
    val dim by animateFloatAsState(if (enabled) 1f else 0.4f, label = "toggleEnabled")

    val toggleModifier = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = {
                haptics.toggle(it)
                onCheckedChange(it)
            },
        )
    } else Modifier

    Box(
        modifier
            .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
            .then(toggleModifier)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .graphicsLayer { alpha = dim },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TrackWidth, TrackHeight)
                .clip(PocketShape.capsule)
                .background(track),
        ) {
            // The thumb grows toward the travel direction while pressed.
            Box(
                Modifier
                    .padding(ThumbInset)
                    .offset(x = if (checked) offset - stretch else offset)
                    .width(Thumb + stretch)
                    .height(Thumb)
                    .shadow(3.dp, PocketShape.capsule, clip = false, ambientColor = Color(0x26000000), spotColor = Color(0x33000000))
                    .clip(PocketShape.capsule)
                    .background(Color.White),
            )
        }
    }
}
