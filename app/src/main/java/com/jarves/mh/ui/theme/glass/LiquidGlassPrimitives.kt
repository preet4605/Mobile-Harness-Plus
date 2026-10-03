package com.jarves.mh.ui.theme.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarves.mh.ui.theme.PocketPalette
import com.jarves.mh.ui.theme.PocketRadius
import com.jarves.mh.ui.theme.PocketSpacing

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
    val isDark = isSystemInDarkTheme()
    val activeColor = selectedTint
    val inactiveColor = if (isDark) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF64748B)

    val animatedBgAlpha by animateFloatAsState(
        targetValue = if (selected) 0.10f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "nav_item_bg_alpha"
    )
    val animatedContentColor by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "nav_item_color"
    )
    val animatedScale by animateFloatAsState(
        targetValue = if (selected) 1.01f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "nav_item_scale"
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
                    .graphicsLayer {
                        scaleX = animatedScale
                        scaleY = animatedScale
                    }
                    .clip(RoundedCornerShape(LiquidGlassTokens.ControlRadius))
                    .background(activeColor.copy(alpha = animatedBgAlpha))
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
                    .padding(horizontal = PocketSpacing.sm, vertical = PocketSpacing.xs),
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
    shape: Shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
    tonalElevation: Dp = 2.dp,
    windowInsets: WindowInsets = WindowInsets.statusBars,
) {
    val isDark = isSystemInDarkTheme()
    val primaryForeground = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val secondaryForeground = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)

    LiquidGlassSurface(
        modifier = modifier.fillMaxWidth(),
        material = material,
        shape = shape,
        layerSource = layerSource,
        tonalElevation = tonalElevation,
        contentColor = primaryForeground,
    ) {
        CompositionLocalProvider(LocalContentColor provides primaryForeground) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(windowInsets)
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = PocketSpacing.sm, vertical = 2.dp),
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
                    CompositionLocalProvider(LocalContentColor provides primaryForeground) {
                        title()
                    }
                    if (subtitle != null) {
                        CompositionLocalProvider(LocalContentColor provides secondaryForeground) {
                            subtitle()
                        }
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
    shape: Shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
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
    val isDark = isSystemInDarkTheme()
    val handleColor = if (isDark) Color(0x44FFFFFF) else Color(0x33000000)

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
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
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
                                fontWeight = FontWeight.Bold,
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
    val isDark = isSystemInDarkTheme()
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
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = accentColor,
                fontFamily = FontFamily.Monospace,
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
    layerSource: String? = null,
    accentColor: Color = PocketPalette.orangeAccent,
) {
    val isDark = isSystemInDarkTheme()
    val containerShape = RoundedCornerShape(14.dp)
    val itemShape = RoundedCornerShape(10.dp)

    LiquidGlassSurface(
        modifier = modifier.clip(containerShape),
        material = LiquidGlassMaterial.Thin,
        shape = containerShape,
        tint = if (isDark) Color(0x22121824) else Color(0x14000000),
        borderStroke = BorderStroke(0.5.dp, if (isDark) Color(0x24FFFFFF) else Color(0x18000000)),
        layerSource = layerSource,
    ) {
        Row(
            modifier = Modifier
                .padding(3.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val isSelected = item == selectedItem
                val label = itemLabel(item)
                val icon = itemIcon?.invoke(item)

                if (isSelected) {
                    LiquidGlassSurface(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget),
                        material = LiquidGlassMaterial.UltraThin,
                        shape = itemShape,
                        tint = if (isDark) Color(0x26FFFFFF) else Color(0x60FFFFFF),
                        borderStroke = BorderStroke(0.5.dp, if (isDark) Color(0x38FFFFFF) else Color(0x24000000)),
                        tonalElevation = 1.dp,
                        layerSource = null,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
                                .drawBehind {
                                    drawLine(
                                        color = if (isDark) Color(0x40FFFFFF) else Color(0x60FFFFFF),
                                        start = Offset(4f, 1f),
                                        end = Offset(size.width - 4f, 1f),
                                        strokeWidth = 1.5f,
                                    )
                                }
                                .semantics {
                                    selected = true
                                    contentDescription = label
                                }
                                .padding(horizontal = PocketSpacing.xs, vertical = PocketSpacing.xs),
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
                                        tint = if (isDark) Color.White else Color(0xFF0F172A),
                                        modifier = Modifier.size(15.dp),
                                    )
                                    Spacer(Modifier.width(PocketSpacing.xs))
                                }
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isDark) Color.White else Color(0xFF0F172A),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = LiquidGlassTokens.MinTouchTarget)
                            .clip(itemShape)
                            .clickable(
                                role = Role.Tab,
                                onClick = { onItemSelected(item) },
                            )
                            .semantics {
                                selected = false
                                contentDescription = label
                            }
                            .padding(horizontal = PocketSpacing.xs, vertical = PocketSpacing.xs),
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
                                    tint = if (isDark) Color(0x99CBD5E1) else Color(0x99475569),
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(PocketSpacing.xs))
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Normal,
                                color = if (isDark) Color(0x99CBD5E1) else Color(0x99475569),
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
 * Modular content card providing structured visual hierarchy via Thin glass material.
 * Gracefully falls back to high-contrast opaque surface when Reduce Transparency is enabled.
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
}
