package com.jarves.mh.ui.theme.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketPalette
import com.jarves.mh.ui.theme.PocketRadius
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.tactilePress

/**
 * Reusable Liquid Glass UI Primitives.
 *
 * Implements Apple Human Interface Guidelines:
 * - Liquid Glass for floating navigation, toolbars, and controls.
 * - Standard Materials (UltraThin, Thin, Regular, Thick) for structured content layering.
 * - Guaranteed minimum 44dp touch targets across all interactive elements.
 * - Automatic solid high-contrast fallback when Reduce Transparency is active.
 * - Zero duplicated rendering logic — all primitives delegate directly to [LiquidGlassSurface].
 */

// ============================================================================
// 1. LiquidGlassFloatingNavBar
// ============================================================================

/**
 * Floating navigation bar surface conforming to Apple HIG navigation guidelines.
 * Floats above the content layer, allowing underlying content to peek through with refraction.
 */
@Composable
fun LiquidGlassFloatingNavBar(
    modifier: Modifier = Modifier,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    layerSource: String? = LiquidGlassLayers.Background,
    shape: Shape = RoundedCornerShape(LiquidGlassTokens.PillRadius),
    tonalElevation: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = PocketSpacing.sm, vertical = PocketSpacing.xs),
    content: @Composable RowScope.() -> Unit,
) {
    LiquidGlassSurface(
        modifier = modifier,
        material = material,
        shape = shape,
        layerSource = layerSource,
        tonalElevation = tonalElevation,
    ) {
        Row(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * Navigation item for [LiquidGlassFloatingNavBar] adhering to minimum 44dp touch target
 * and accessibility semantics.
 */
@Composable
fun RowScope.LiquidGlassFloatingNavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    badge: @Composable (() -> Unit)? = null,
    selectedTint: Color = PocketPalette.orangeAccent,
) {
    val isDark = isGlassDarkTheme()
    val activeColor = selectedTint
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Neutral selection capsule; the accent lives on the icon and label only (colour used sparingly).
    val capsuleColor = if (isDark) LiquidGlassTokens.SelectionDark else LiquidGlassTokens.SelectionLight

    val animatedCapsule by animateColorAsState(
        targetValue = if (selected) capsuleColor else Color.Transparent,
        animationSpec = PocketMotion.selectionSpring(),
        label = "nav_item_capsule"
    )
    val animatedContentColor by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = PocketMotion.selectionSpring(),
        label = "nav_item_color"
    )

    Box(
        modifier = modifier.weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides animatedContentColor
        ) {
            Column(
                modifier = Modifier
                    .defaultMinSize(
                        minWidth = LiquidGlassTokens.MinTouchTarget,
                        minHeight = LiquidGlassTokens.MinTouchTarget,
                    )
                    .clip(RoundedCornerShape(LiquidGlassTokens.PillRadius))
                    .background(animatedCapsule)
                    .clickable(
                        enabled = enabled,
                        role = Role.Tab,
                        onClick = onClick,
                    )
                    .semantics {
                        this.selected = selected
                        if (contentDescription != null) {
                            this.contentDescription = contentDescription
                        }
                    }
                    .padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.xs),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    icon()
                    if (badge != null) {
                        Box(modifier = Modifier.align(Alignment.TopEnd)) {
                            badge()
                        }
                    }
                }
                if (label != null) {
                    Spacer(Modifier.height(2.dp))
                    label()
                }
            }
        }
    }
}

// ============================================================================
// 2. LiquidGlassTopBar
// ============================================================================

/**
 * Apple HIG-compliant Top Bar with Liquid Glass refraction, accommodating leading
 * navigation icons, title/subtitle, and trailing actions with >= 44dp hit targets.
 */
@Composable
fun LiquidGlassTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    layerSource: String? = null,
    shape: Shape = RectangleShape,
    tonalElevation: Dp = 2.dp,
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    // A bar is an edge-to-edge glass band, not a floating card: no rim, just a hairline
    // separating it from the content scrolling underneath.
    val hairline = MaterialTheme.colorScheme.outlineVariant
    LiquidGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val y = size.height - 0.5.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
            },
        material = material,
        shape = shape,
        layerSource = layerSource,
        tonalElevation = tonalElevation,
        borderStroke = BorderStroke(0.dp, Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(windowInsets)
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = PocketSpacing.sm, vertical = PocketSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                Box(
                    modifier = Modifier.size(LiquidGlassTokens.MinTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    navigationIcon()
                }
                Spacer(Modifier.width(PocketSpacing.xs))
            } else {
                Spacer(Modifier.width(PocketSpacing.sm))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = PocketSpacing.xs),
                verticalArrangement = Arrangement.Center,
            ) {
                title()
                if (subtitle != null) {
                    subtitle()
                }
            }

            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                    content = actions,
                )
            } else {
                Spacer(Modifier.width(PocketSpacing.sm))
            }
        }
    }
}

@Composable
fun LiquidGlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    layerSource: String? = null,
    shape: Shape = RectangleShape,
    tonalElevation: Dp = 2.dp,
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    LiquidGlassTopBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        subtitle = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        material = material,
        layerSource = layerSource,
        shape = shape,
        tonalElevation = tonalElevation,
        windowInsets = windowInsets,
    )
}

// ============================================================================
// 3. LiquidGlassSheet
// ============================================================================

/**
 * Liquid Glass Sheet container (modal or bottom sheet surface) utilizing HIG Thick material
 * for enhanced contrast and legibility over detailed content.
 */
@Composable
fun LiquidGlassSheet(
    modifier: Modifier = Modifier,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Thick,
    layerSource: String? = LiquidGlassLayers.Background,
    shape: Shape = RoundedCornerShape(
        topStart = LiquidGlassTokens.SheetRadius,
        topEnd = LiquidGlassTokens.SheetRadius,
    ),
    showHandleBar: Boolean = true,
    tonalElevation: Dp = 8.dp,
    contentPadding: PaddingValues = PaddingValues(PocketSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    val isDark = isGlassDarkTheme()
    val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isDark) 0.45f else 0.35f)

    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        material = material,
        shape = shape,
        layerSource = layerSource,
        tonalElevation = tonalElevation,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (showHandleBar) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 5.dp)
                        .clip(RoundedCornerShape(LiquidGlassTokens.PillRadius))
                        .background(handleColor)
                        .semantics { contentDescription = "Sheet handle" },
                )
                Spacer(Modifier.height(PocketSpacing.md))
            }
            content()
        }
    }
}

// ============================================================================
// 4. LiquidGlassDialog
// ============================================================================

/**
 * Modal Alert / Dialog styled with Apple HIG Liquid Glass principles.
 * Uses Thick material for optimal readability and accessibility semantics.
 */
@Composable
fun LiquidGlassDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    confirmButton: @Composable (() -> Unit)? = null,
    dismissButton: @Composable (() -> Unit)? = null,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Thick,
    layerSource: String? = LiquidGlassLayers.Background,
    shape: Shape = RoundedCornerShape(LiquidGlassTokens.SheetRadius),
    properties: DialogProperties = DialogProperties(),
    content: @Composable (() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties,
    ) {
        LiquidGlassSurface(
            modifier = modifier
                .fillMaxWidth()
                .padding(PocketSpacing.md)
                .semantics { contentDescription = "Dialog" },
            material = material,
            shape = shape,
            layerSource = layerSource,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(PocketSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (content != null) {
                    content()
                } else {
                    if (title != null) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(
                                textAlign = TextAlign.Center,
                            )
                        ) {
                            title()
                        }
                        Spacer(Modifier.height(PocketSpacing.sm))
                    }
                    if (text != null) {
                        CompositionLocalProvider(
                            LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        ) {
                            text()
                        }
                        Spacer(Modifier.height(PocketSpacing.lg))
                    }
                    if (confirmButton != null || dismissButton != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = PocketSpacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (dismissButton != null) {
                                dismissButton()
                            }
                            if (confirmButton != null) {
                                confirmButton()
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 5. LiquidGlassInputCapsule
// ============================================================================

/**
 * Capsule text input field conforming to Apple HIG with a >= 44dp hit target,
 * smooth rounded pill contours, and subtle glass depth.
 */
@Composable
fun LiquidGlassInputCapsule(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Thin,
    layerSource: String? = LiquidGlassLayers.Background,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    contentDescription: String? = "Text input",
) {
    val isDark = isGlassDarkTheme()
    val textColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant

    LiquidGlassSurface(
        modifier = modifier
            .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget),
        material = material,
        shape = RoundedCornerShape(LiquidGlassTokens.PillRadius),
        layerSource = layerSource,
    ) {
        Row(
            modifier = Modifier
                .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
                .padding(horizontal = PocketSpacing.sm, vertical = PocketSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier.size(LiquidGlassTokens.MinTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    leadingIcon()
                }
                Spacer(Modifier.width(PocketSpacing.xxs))
            } else {
                Spacer(Modifier.width(PocketSpacing.sm))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = PocketSpacing.xs),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = placeholderColor,
                        maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            if (contentDescription != null) {
                                this.contentDescription = contentDescription
                            }
                        },
                    enabled = enabled,
                    singleLine = singleLine,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                    cursorBrush = SolidColor(if (isDark) PocketPalette.orangeAccent else MaterialTheme.colorScheme.primary),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                )
            }

            if (trailingIcon != null) {
                Spacer(Modifier.width(PocketSpacing.xxs))
                Box(
                    modifier = Modifier.size(LiquidGlassTokens.MinTouchTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    trailingIcon()
                }
            } else {
                Spacer(Modifier.width(PocketSpacing.sm))
            }
        }
    }
}

// ============================================================================
// 6. LiquidGlassPill
// ============================================================================

/**
 * Status and category pill with stained glass translucency.
 * If clickable, guarantees a minimum 44dp hit target.
 */
@Composable
fun LiquidGlassPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accentColor: Color = PocketPalette.orangeAccent,
    tint: Color? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    material: LiquidGlassMaterial = LiquidGlassMaterial.UltraThin,
    layerSource: String? = LiquidGlassLayers.Background,
    showDot: Boolean = true,
) {
    val resolvedTint = tint ?: if (selected) accentColor.copy(alpha = 0.22f) else accentColor.copy(alpha = 0.10f)

    LiquidGlassSurface(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
                        .clip(RoundedCornerShape(LiquidGlassTokens.PillRadius))
                        .clickable(
                            role = Role.Button,
                            onClick = onClick,
                        )
                        .semantics {
                            this.contentDescription = text
                        }
                } else {
                    Modifier.semantics {
                        this.contentDescription = text
                    }
                }
            ),
        material = material,
        shape = RoundedCornerShape(LiquidGlassTokens.PillRadius),
        tint = resolvedTint,
        layerSource = layerSource,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = PocketSpacing.sm, vertical = PocketSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            } else if (showDot) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(accentColor),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = accentColor,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

// ============================================================================
// 7. LiquidGlassSegmentedControl
// ============================================================================

/**
 * Mutually exclusive segmented control container with glass backdrop and highlighted
 * active thumb, ensuring >= 44dp hit target per segment.
 */
@Composable
fun <T> LiquidGlassSegmentedControl(
    items: List<T>,
    selectedItem: T,
    onItemSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    itemLabel: (T) -> String = { it.toString() },
    itemIcon: ((T) -> ImageVector?)? = null,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Regular,
    layerSource: String? = LiquidGlassLayers.Background,
    accentColor: Color = PocketPalette.orangeAccent,
) {
    val isDark = isGlassDarkTheme()
    val capsule = RoundedCornerShape(LiquidGlassTokens.PillRadius)
    // Raised neutral thumb that slides between segments; no accent outline (colour used sparingly).
    val thumbColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.92f)
    val thumbBorder = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val selectedIndex = items.indexOf(selectedItem)
    val gap = PocketSpacing.xxs

    LiquidGlassSurface(
        modifier = modifier.clip(capsule),
        material = material,
        shape = capsule,
        layerSource = layerSource,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .padding(3.dp)
                .fillMaxWidth(),
        ) {
            val count = items.size.coerceAtLeast(1)
            val segmentWidth = (maxWidth - gap * (count - 1)) / count
            val thumbOffset by animateDpAsState(
                targetValue = (segmentWidth + gap) * selectedIndex.coerceAtLeast(0),
                animationSpec = PocketMotion.selectionSpring(),
                label = "segmentedThumbOffset",
            )
            if (selectedIndex >= 0) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(thumbOffset.roundToPx(), 0) }
                        .width(segmentWidth)
                        .height(LiquidGlassTokens.MinTouchTarget)
                        .clip(capsule)
                        .background(thumbColor)
                        .border(0.5.dp, thumbBorder, capsule),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEach { item ->
                    val isSelected = item == selectedItem
                    val label = itemLabel(item)
                    val icon = itemIcon?.invoke(item)
                    val labelColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = PocketMotion.selectionSpring(),
                        label = "segmentLabelColor",
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(LiquidGlassTokens.MinTouchTarget)
                            .clip(capsule)
                            .clickable(
                                role = Role.Tab,
                                onClick = { onItemSelected(item) },
                            )
                            .semantics {
                                selected = isSelected
                                contentDescription = label
                            }
                            .padding(horizontal = PocketSpacing.xs),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            if (icon != null) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = if (isSelected) accentColor else labelColor,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(PocketSpacing.xs))
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = labelColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 8. LiquidGlassCard
// ============================================================================

/**
 * Content card. Cards are content, so by default they are solid (no Liquid Glass in the
 * content layer): neutral surface, optional stained [tint] overlay, hairline or custom border.
 * Passing a [layerSource] opts a floating card into real glass sampling the shared backdrop.
 */
@Composable
fun LiquidGlassCard(
    modifier: Modifier = Modifier,
    material: LiquidGlassMaterial = LiquidGlassMaterial.Thin,
    shape: Shape = RoundedCornerShape(PocketRadius.lg),
    layerSource: String? = null,
    tint: Color? = null,
    borderStroke: BorderStroke? = null,
    tonalElevation: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (layerSource != null) {
        LiquidGlassSurface(
            modifier = modifier
                .then(
                    if (onClick != null) {
                        Modifier
                            .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
                            .clip(shape)
                            .clickable(
                                role = Role.Button,
                                onClick = onClick,
                            )
                    } else Modifier
                ),
            material = material,
            shape = shape,
            layerSource = layerSource,
            tint = tint,
            borderStroke = borderStroke,
            tonalElevation = tonalElevation,
            content = content,
        )
        return
    }

    val isDark = isGlassDarkTheme()
    val surface = if (isDark) PocketPalette.darkCardSurface else PocketPalette.lightCardSurface
    val hairline = BorderStroke(0.5.dp, if (isDark) PocketPalette.darkBorder else PocketPalette.lightBorder)
    val contentColor = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
                        .tactilePress(onClick)
                } else Modifier
            )
            .clip(shape)
            .background(surface)
            .then(if (tint != null) Modifier.background(tint) else Modifier)
            .border(borderStroke ?: hairline, shape),
        propagateMinConstraints = false,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            content()
        }
    }
}
