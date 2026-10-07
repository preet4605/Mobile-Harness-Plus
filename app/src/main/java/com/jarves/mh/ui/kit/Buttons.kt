package com.jarves.mh.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import com.jarves.mh.ui.theme.glass.LiquidGlassLayers
import com.jarves.mh.ui.theme.glass.LiquidGlassSurface
import com.jarves.mh.ui.theme.glass.LiquidGlassTokens

/**
 * Button styles, from most to least prominent:
 * - [Filled]: the one primary action on a screen (accent fill, white label).
 * - [Tinted]: secondary actions that still need weight (accent wash, accent label).
 * - [Gray]: neutral actions (gray fill, accent label).
 * - [Plain]: text-only actions inside content.
 * - [GlassProminent] / [Glass]: actions that float over content, such as on a bar.
 */
enum class PocketButtonStyle { Filled, Tinted, Gray, Plain, Glass, GlassProminent }

/** Regular is 44 dp tall; Large is 50 dp and used for full-width primary actions. */
enum class PocketButtonSize(val minHeight: Dp, val horizontalPadding: Dp) {
    Small(34.dp, 14.dp),
    Regular(44.dp, 18.dp),
    Large(50.dp, 20.dp),
}

@Composable
fun PocketButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PocketButtonStyle = PocketButtonStyle.Filled,
    size: PocketButtonSize = PocketButtonSize.Regular,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    loading: Boolean = false,
    fullWidth: Boolean = false,
) {
    val colors = PocketColors.current
    val accent = if (destructive) colors.red else MaterialTheme.colorScheme.primary
    val labelStyle = if (size == PocketButtonSize.Large) PocketType.body.emphasized else PocketType.subheadline.emphasized
    val widthModifier = if (fullWidth) Modifier.fillMaxWidth() else Modifier
    // Small buttons still get a 44 dp tall touch area.
    val touch = Modifier.heightIn(min = LiquidGlassTokens.MinTouchTarget)

    val label: @Composable (Color) -> Unit = { color ->
        Row(
            modifier = Modifier
                .then(widthModifier)
                .heightIn(min = size.minHeight)
                .padding(horizontal = size.horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (loading) {
                ProgressRing(progress = null, size = 16.dp, strokeWidth = 2.dp, color = color)
                Spacer(Modifier.width(PocketSpacing.sm))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(PocketSpacing.sm - 2.dp))
            }
            Text(text, style = labelStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }

    when (style) {
        PocketButtonStyle.Glass, PocketButtonStyle.GlassProminent -> {
            val prominent = style == PocketButtonStyle.GlassProminent
            Box(modifier.then(touch).alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
                LiquidGlassSurface(
                    modifier = widthModifier,
                    shape = PocketShape.capsule,
                    layerSource = LiquidGlassLayers.Background,
                    tint = if (prominent) accent.copy(alpha = 0.82f) else null,
                    onClick = if (enabled && !loading) onClick else null,
                    semanticRole = Role.Button,
                    contentDescription = text,
                ) {
                    label(if (prominent) (if (destructive) Color.White else MaterialTheme.colorScheme.onPrimary) else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        else -> {
            val (container, content) = when (style) {
                PocketButtonStyle.Filled -> accent to (if (destructive) Color.White else MaterialTheme.colorScheme.onPrimary)
                PocketButtonStyle.Tinted -> accent.copy(alpha = if (colors.isDark) 0.22f else 0.14f) to accent
                PocketButtonStyle.Gray -> colors.tertiaryFill to accent
                else -> Color.Transparent to accent
            }
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val pressAlpha by animateFloatAsState(
                if (pressed) 0.6f else 1f,
                PocketMotion.spec(PocketMotion.Token.Quick),
                label = "buttonPress",
            )
            val pressScale by animateFloatAsState(
                if (pressed && style != PocketButtonStyle.Plain) PocketMotion.PressScale else 1f,
                PocketMotion.pressSpring(),
                label = "buttonScale",
            )
            Box(
                modifier
                    .then(touch)
                    .then(widthModifier)
                    .graphicsLayer {
                        scaleX = pressScale
                        scaleY = pressScale
                        alpha = (if (enabled) 1f else 0.4f) * pressAlpha
                    }
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = enabled && !loading,
                        role = Role.Button,
                        onClick = onClick,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .then(widthModifier)
                        .clip(PocketShape.capsule)
                        .background(container),
                    contentAlignment = Alignment.Center,
                ) {
                    label(content)
                }
            }
        }
    }
}

/** A circular icon-only button with a 44 dp touch target, for inline content (not bars). */
@Composable
fun PocketIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    filled: Boolean = false,
    enabled: Boolean = true,
    iconSize: Dp = 20.dp,
) {
    val colors = PocketColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(if (pressed) 0.5f else 1f, PocketMotion.spec(PocketMotion.Token.Quick), label = "iconPress")
    Box(
        modifier
            .defaultMinSize(LiquidGlassTokens.MinTouchTarget, LiquidGlassTokens.MinTouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
            .graphicsLayer { alpha = (if (enabled) 1f else 0.35f) * pressAlpha },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(if (filled) 32.dp else iconSize)
                .then(if (filled) Modifier.clip(PocketShape.capsule).background(colors.tertiaryFill) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * A text action in a sheet or bar header ("Cancel", "Done"): accent text with a 44 dp touch
 * area. The confirming action is [emphasized]; the dismissing one is regular weight.
 */
@Composable
fun SheetTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = PocketColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(if (pressed) 0.5f else 1f, PocketMotion.spec(PocketMotion.Token.Quick), label = "sheetButtonPress")
    Box(
        modifier
            .heightIn(min = LiquidGlassTokens.MinTouchTarget)
            .graphicsLayer { alpha = pressAlpha }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = PocketSpacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (emphasized) PocketType.body.emphasized else PocketType.body,
            color = when {
                !enabled -> colors.tertiaryLabel
                destructive -> colors.red
                else -> MaterialTheme.colorScheme.primary
            },
            maxLines = 1,
        )
    }
}
