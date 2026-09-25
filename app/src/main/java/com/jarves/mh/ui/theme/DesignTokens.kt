package com.jarves.mh.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dense Minimalist & Premium Utility Design Tokens.
 * Combines Editorial Minimalism, Dense Information Design, Soft-Card Neumorphic Cues,
 * and Apple/Bento Modular Composition.
 */
object PocketSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val jumbo: Dp = 32.dp
}

object PocketRadius {
    val xs: Dp = 6.dp
    val sm: Dp = 8.dp
    val md: Dp = 14.dp
    val lg: Dp = 18.dp
    val xl: Dp = 24.dp
    val full: Dp = 999.dp
}

object PocketPalette {
    // Canvas background
    val darkCanvas = Color(0xFF0B0E14)
    val lightCanvas = Color(0xFFF8FAFC)

    // Soft-card surfaces
    val darkCardSurface = Color(0xFF131821)
    val lightCardSurface = Color(0xFFFFFFFF)

    // Secondary/Nested tile surfaces
    val darkTileSurface = Color(0xFF19202C)
    val lightTileSurface = Color(0xFFF1F5F9)

    // Hairline borders
    val darkBorder = Color(0xFF222B38)
    val lightBorder = Color(0xFFE2E8F0)

    // Top-edge light bevel highlight (subtle neumorphic rim)
    val darkRimHighlight = Color(0x14FFFFFF) // 8% white
    val lightRimHighlight = Color(0x99FFFFFF) // 60% white

    // Ambient diffuse shadows
    val darkShadow = Color(0x59000000) // 35% black
    val lightShadow = Color(0x100F172A) // 6% slate-900

    // Accents
    val orangeAccent = Color(0xFFF28C52)
    val blueAccent = Color(0xFF8EA8FF)
    val greenAccent = Color(0xFF69D69E)
    val redAccent = Color(0xFFF87171)
}

/**
 * Modifier that applies the Soft-Card UI styling:
 * Smooth rounded corners, diffuse ambient shadow, hairline border, and a subtle
 * top-edge light rim bevel for physical, tactile presence.
 */
@Composable
fun Modifier.softCard(
    shape: Shape = RoundedCornerShape(PocketRadius.lg),
    elevation: Dp = 2.dp,
    isDark: Boolean = isSystemInDarkTheme(),
    onClick: (() -> Unit)? = null,
): Modifier {
    val surfaceColor = if (isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface
    val borderColor = if (isDark) PocketPalette.darkBorder else PocketPalette.lightBorder
    val rimColor = if (isDark) PocketPalette.darkRimHighlight else PocketPalette.lightRimHighlight
    val shadowColor = if (isDark) PocketPalette.darkShadow else PocketPalette.lightShadow

    var base = this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = shadowColor,
            spotColor = shadowColor,
        )
        .clip(shape)
        .background(surfaceColor)
        .border(BorderStroke(1.dp, borderColor), shape)
        .drawBehind {
            // Draw 1dp subtle top-edge rim highlight
            drawLine(
                color = rimColor,
                start = Offset(0f, 1f),
                end = Offset(size.width, 1f),
                strokeWidth = 2f,
            )
        }

    if (onClick != null) {
        base = base.tactilePress(onClick = onClick)
    }
    return base
}

@Composable
fun Modifier.tactilePress(
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onClick,
    )
}

/**
 * Modular Apple/Bento-inspired metric tile component for dense mobile dashboards.
 */
@Composable
fun BentoTile(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = PocketPalette.orangeAccent,
    badgeText: String? = null,
    badgeColor: Color = PocketPalette.blueAccent,
    isDark: Boolean = isSystemInDarkTheme(),
    onClick: (() -> Unit)? = null,
) {
    val tileSurface = if (isDark) PocketPalette.darkTileSurface else PocketPalette.lightTileSurface
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .softCard(
                shape = RoundedCornerShape(PocketRadius.lg),
                elevation = 1.dp,
                isDark = isDark,
                onClick = onClick,
            )
            .padding(PocketSpacing.md),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Surface(
                            shape = RoundedCornerShape(PocketRadius.sm),
                            color = iconTint.copy(alpha = 0.12f),
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = iconTint,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(PocketSpacing.xs))
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textSecondary,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (badgeText != null) {
                    Spacer(Modifier.width(PocketSpacing.xs))
                    StatusPill(
                        text = badgeText,
                        color = badgeColor,
                    )
                }
            }

            Spacer(Modifier.height(PocketSpacing.sm))

            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textPrimary,
                fontSize = 16.sp,
            )

            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(PocketSpacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Compact status pill for dense telemetry (Git branch, model tier, subagent count).
 */
@Composable
fun StatusPill(
    text: String,
    color: Color = PocketPalette.blueAccent,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(PocketRadius.full),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.35f)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = text,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = color,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * Tactile action button combining editorial typography with physical depth.
 */
@Composable
fun TactileActionButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val containerColor = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(PocketRadius.md)

    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = containerColor,
        shadowElevation = if (primary) 2.dp else 1.dp,
        border = if (!primary) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
        modifier = modifier.height(44.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PocketSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(PocketSpacing.xs))
            }
            Text(
                text = text,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    }
}
