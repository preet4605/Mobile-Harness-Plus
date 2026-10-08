package com.jarves.mh.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketMotion.Token
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens

/**
 * A segmented control for content (not bars): a gray capsule track with a raised thumb that
 * springs to the chosen segment. 32 dp to the eye, 44 dp to the finger; each segment is a radio
 * button to accessibility services.
 */
@Composable
fun <T> SegmentedControl(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
    enabled: Boolean = true,
) {
    CappedTextScale {
        val colors = PocketColors.current
        val haptics = rememberHaptics()
        val index = items.indexOf(selected)
        val position by animateFloatAsState(index.coerceAtLeast(0).toFloat(), PocketMotion.spec(Token.Snappy), label = "segment")
        val thumb = if (colors.isDark) Color.White.copy(alpha = 0.24f) else Color.White
        val shadow = Color.Black.copy(alpha = if (colors.isDark) 0f else 0.08f)
        Box(
            modifier
                .fillMaxWidth()
                .heightIn(min = LiquidGlassTokens.MinTouchTarget)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(32.dp)
                    .clip(PocketShape.capsule)
                    .drawBehind {
                        drawRect(colors.tertiaryFill)
                        if (index >= 0 && items.isNotEmpty()) {
                            val pad = 2.dp.toPx()
                            val w = (size.width - pad * 2) / items.size
                            val h = size.height - pad * 2
                            val left = pad + position * w
                            val r = CornerRadius(h / 2f)
                            drawRoundRect(shadow, Offset(left, pad + 1.dp.toPx()), Size(w, h), r)
                            drawRoundRect(thumb, Offset(left, pad), Size(w, h), r)
                        }
                    },
            )
            Row(Modifier.matchParentSize().selectableGroup()) {
                items.forEach { item ->
                    val isSelected = item == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .selectable(
                                selected = isSelected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (!isSelected) {
                                    haptics.selection()
                                    onSelect(item)
                                }
                            }
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label(item),
                            style = if (isSelected) PocketType.footnote.emphasized else PocketType.footnote,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
