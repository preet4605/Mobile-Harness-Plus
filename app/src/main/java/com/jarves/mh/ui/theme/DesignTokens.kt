package com.jarves.mh.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.pow

/**
 * Design tokens for the app-wide Liquid design language
 * (spec: project files design/liquid-design-language-2026-10-07.md).
 * Solid neutral content surfaces, concentric radii, a 4-pt spacing scale.
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

/** Concentric radius scale; also backs MaterialTheme.shapes (extraSmall → extraLarge). */
object PocketRadius {
    val xs: Dp = 6.dp
    val sm: Dp = 10.dp
    val md: Dp = 14.dp
    val lg: Dp = 20.dp
    val xl: Dp = 28.dp
    val full: Dp = 999.dp
}

object PocketPalette {
    // Canvas background (matches colorScheme.background)
    val darkCanvas = Color(0xFF000000)
    val lightCanvas = Color(0xFFF2F2F7)

    // Solid content surfaces (matches colorScheme.surface)
    val darkCardSurface = Color(0xFF1C1C1E)
    val lightCardSurface = Color(0xFFFFFFFF)

    // Secondary/Nested tile surfaces (matches colorScheme.surfaceVariant)
    val darkTileSurface = Color(0xFF2C2C2E)
    val lightTileSurface = Color(0xFFE9E9EE)

    // Hairline borders
    val darkBorder = Color(0xFF2C2C2E)
    val lightBorder = Color(0xFFE5E5EA)

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
 * Solid content card: neutral surface, hairline border, optional soft shadow (0 by default,
 * content cards sit flat on the grouped canvas).
 */
@Composable
fun Modifier.softCard(
    shape: Shape = RoundedCornerShape(PocketRadius.lg),
    elevation: Dp = 0.dp,
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    onClick: (() -> Unit)? = null,
): Modifier {
    val surfaceColor = if (isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface
    val borderColor = if (isDark) PocketPalette.darkBorder else PocketPalette.lightBorder
    val shadowColor = if (isDark) PocketPalette.darkShadow else PocketPalette.lightShadow

    var base = this
        .then(
            if (elevation > 0.dp) {
                Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    ambientColor = shadowColor,
                    spotColor = shadowColor,
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(surfaceColor)
        .border(BorderStroke(0.5.dp, borderColor), shape)

    if (onClick != null) {
        base = base.tactilePress(onClick = onClick)
    }
    return base
}

/** Click with the shared press response: a 0.97 spring scale instead of a ripple. */
@Composable
fun Modifier.tactilePress(
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PocketMotion.PressScale else 1f,
        animationSpec = PocketMotion.pressSpring(),
        label = "tactilePressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
}

/**
 * Shared motion tokens. Every UI animation is a spring, so it can be interrupted and keeps its
 * velocity. Each token is a perceptual duration and bounce, converted with mass 1 as
 * stiffness = (2π / duration)² and dampingRatio = 1 − bounce.
 */
object PocketMotion {
    const val PressScale: Float = 0.97f

    enum class Token(val durationSeconds: Float, val bounce: Float) {
        /** Values that follow the finger: drag offsets, sheet detents while dragging. */
        Track(0.20f, 0f),

        /** Colour, opacity and small state changes; tab content cross-fades. */
        Quick(0.25f, 0f),

        /** Taps, toggles, the selection capsule, menus opening. */
        Snappy(0.35f, 0.15f),

        /** Push and pop navigation, presenting sheets. */
        Smooth(0.45f, 0f),

        /** Glass shape morphs. */
        Morph(0.50f, 0.15f),

        /** Settling after a fling. */
        Release(0.50f, 0.30f),
        ;

        val stiffness: Float
            get() = (2.0 * PI / durationSeconds).pow(2).toFloat()

        val dampingRatio: Float
            get() = 1f - bounce
    }

    /**
     * Reduce motion (system "Remove animations"): springs lose their bounce and navigation
     * slides become cross-fades. Set once at the app root from the glass config.
     */
    var reduced: Boolean by mutableStateOf(false)

    fun <T> spec(token: Token, visibilityThreshold: T? = null): SpringSpec<T> = spring(
        dampingRatio = if (reduced) 1f else token.dampingRatio,
        stiffness = token.stiffness,
        visibilityThreshold = visibilityThreshold,
    )

    fun <T> pressSpring() = spring<T>(dampingRatio = if (reduced) 1f else 0.6f, stiffness = 500f)

    /** Selection moves (segmented thumb, tab capsule). */
    fun <T> selectionSpring(): SpringSpec<T> = spec(Token.Snappy)
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
    isDark: Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    onClick: (() -> Unit)? = null,
) {
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .softCard(
                shape = RoundedCornerShape(PocketRadius.lg),
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
                            shape = RoundedCornerShape(PocketRadius.xs),
                            color = iconTint.copy(alpha = 0.14f),
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
                color = textPrimary,
            )

            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.height(PocketSpacing.xxs))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = textSecondary,
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
        border = if (!primary) BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant) else null,
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
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
