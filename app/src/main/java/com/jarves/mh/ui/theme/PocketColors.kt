package com.jarves.mh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Semantic colours for screens. Screens use these roles (or the Material colour scheme) instead
 * of hex literals, so light and dark stay consistent and contrast is checked in one place.
 *
 * Labels: [label] > [secondaryLabel] > [tertiaryLabel] > [quaternaryLabel].
 * Fills sit on top of backgrounds for controls: [fill] (switch track) > [secondaryFill] >
 * [tertiaryFill] (search field, gray button) > [quaternaryFill].
 * Grouped lists put [groupedSurface] cards on the [groupedBackground] canvas.
 */
@Immutable
data class PocketColorRoles(
    val isDark: Boolean,
    val accent: Color,
    val label: Color,
    val secondaryLabel: Color,
    val tertiaryLabel: Color,
    val quaternaryLabel: Color,
    val separator: Color,
    val fill: Color,
    val secondaryFill: Color,
    val tertiaryFill: Color,
    val quaternaryFill: Color,
    val groupedBackground: Color,
    val groupedSurface: Color,
    val groupedSurfaceRaised: Color,
    val codeSurface: Color,
    val red: Color,
    val orange: Color,
    val yellow: Color,
    val green: Color,
    val mint: Color,
    val teal: Color,
    val cyan: Color,
    val blue: Color,
    val indigo: Color,
    val purple: Color,
    val pink: Color,
    val brown: Color,
    val gray: Color,
)

internal val LightColorRoles = PocketColorRoles(
    isDark = false,
    accent = LightColors.primary,
    label = LightColors.onBackground,
    secondaryLabel = LightColors.onSurfaceVariant,
    tertiaryLabel = Color(0x4D3C3C43),
    quaternaryLabel = Color(0x2E3C3C43),
    separator = Color(0x4A3C3C43),
    fill = Color(0x33787880),
    secondaryFill = Color(0x29787880),
    tertiaryFill = Color(0x1F767680),
    quaternaryFill = Color(0x14747480),
    groupedBackground = LightColors.background,
    groupedSurface = LightColors.surface,
    groupedSurfaceRaised = LightColors.surfaceVariant,
    codeSurface = Color(0xFFF5F5F8),
    red = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00),
    green = Color(0xFF34C759),
    mint = Color(0xFF00C7BE),
    teal = Color(0xFF30B0C7),
    cyan = Color(0xFF32ADE6),
    blue = Color(0xFF007AFF),
    indigo = Color(0xFF5856D6),
    purple = Color(0xFFAF52DE),
    pink = Color(0xFFFF2D55),
    brown = Color(0xFFA2845E),
    gray = Color(0xFF8E8E93),
)

internal val DarkColorRoles = PocketColorRoles(
    isDark = true,
    accent = DarkColors.primary,
    label = DarkColors.onBackground,
    secondaryLabel = DarkColors.onSurfaceVariant,
    tertiaryLabel = Color(0x4DEBEBF5),
    quaternaryLabel = Color(0x29EBEBF5),
    separator = Color(0x99545458),
    fill = Color(0x5C787880),
    secondaryFill = Color(0x52787880),
    tertiaryFill = Color(0x3D767680),
    quaternaryFill = Color(0x2E767680),
    groupedBackground = DarkColors.background,
    groupedSurface = DarkColors.surface,
    groupedSurfaceRaised = DarkColors.surfaceVariant,
    codeSurface = Color(0xFF111113),
    red = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A),
    green = Color(0xFF30D158),
    mint = Color(0xFF63E6E2),
    teal = Color(0xFF40C8E0),
    cyan = Color(0xFF64D2FF),
    blue = Color(0xFF0A84FF),
    indigo = Color(0xFF5E5CE6),
    purple = Color(0xFFBF5AF2),
    pink = Color(0xFFFF375F),
    brown = Color(0xFFAC8E68),
    gray = Color(0xFF8E8E93),
)

/** Colour roles for the current theme (follows the app's theme setting, not the system's). */
object PocketColors {
    val current: PocketColorRoles
        @Composable
        @ReadOnlyComposable
        get() = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) DarkColorRoles else LightColorRoles
}
