package com.jarves.mh.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import androidx.core.view.WindowCompat

val PocketOrange = Color(0xFFF28C52)
val PocketBlue = Color(0xFF8EA8FF)
val PocketGreen = Color(0xFF69D69E)
val PocketBackground = Color(0xFF000000)
val PocketSurface = Color(0xFF1C1C1E)
val PocketSurfaceVariant = Color(0xFF2C2C2E)
val PocketOutline = Color(0xFF38383A)

// Neutral (system-grey) surfaces; brand accents stay. Every role is set explicitly so stock
// M3 dialogs, sheets, menus and chips never fall back to the baseline purple tones.
internal val DarkColors = darkColorScheme(
    primary = PocketOrange,
    onPrimary = Color(0xFF241107),
    primaryContainer = Color(0xFF3D2316),
    onPrimaryContainer = Color(0xFFFFDCC8),
    inversePrimary = Color(0xFFC4501A),
    secondary = PocketBlue,
    onSecondary = Color(0xFF001F58),
    secondaryContainer = Color(0xFF1E2A4A),
    onSecondaryContainer = Color(0xFFD8E2FF),
    tertiary = PocketGreen,
    onTertiary = Color(0xFF00391E),
    tertiaryContainer = Color(0xFF113825),
    onTertiaryContainer = Color(0xFFB8F2D0),
    background = PocketBackground,
    onBackground = Color(0xFFF5F5F7),
    surface = PocketSurface,
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = PocketSurfaceVariant,
    onSurfaceVariant = Color(0xFF98989F),
    surfaceTint = PocketSurface,
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    error = Color(0xFFFF6961),
    onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1A17),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF48484A),
    outlineVariant = PocketOutline,
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF3A3A3C),
    surfaceDim = Color(0xFF000000),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF161618),
    surfaceContainer = PocketSurface,
    surfaceContainerHigh = Color(0xFF242426),
    surfaceContainerHighest = PocketSurfaceVariant,
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFFC4501A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFE5D6),
    onPrimaryContainer = Color(0xFF451A08),
    inversePrimary = PocketOrange,
    secondary = Color(0xFF3366CC),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6FF),
    onSecondaryContainer = Color(0xFF0F2A66),
    tertiary = Color(0xFF17794F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3F5E3),
    onTertiaryContainer = Color(0xFF00391E),
    background = Color(0xFFF2F2F7),
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE9E9EE),
    onSurfaceVariant = Color(0xFF6C6C70),
    surfaceTint = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF2F2F7),
    error = Color(0xFFD92D20),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFDEDEE3),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE5E5EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFE9E9EE),
)

/** Inter (SIL OFL 1.1, bundled and subset to Latin; see assets/licenses/Inter-LICENSE.txt). */
internal val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Inter Display: the optical cut drawn for large sizes, used from 22sp up. */
internal val InterDisplayFamily = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

/**
 * Inter's published dynamic-metrics tracking for a text size, in em:
 * a + b * e^(c * size) with a = -0.0223, b = 0.185, c = -0.1745.
 */
internal fun interTracking(sizeSp: Int): Float =
    (-0.0223 + 0.185 * kotlin.math.exp(-0.1745 * sizeSp)).toFloat()

internal fun TextStyle.tuned(
    size: Int,
    lineHeight: Int,
    weight: FontWeight,
    display: Boolean = false,
): TextStyle = copy(
    fontFamily = if (display) InterDisplayFamily else InterFamily,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    // The display cut is spaced for large sizes already; the text cut follows Inter's curve.
    letterSpacing = if (display) 0.em else interTracking(size).em,
)

/**
 * Clear hierarchy (Large Title → Caption) on M3 roles: heavier titles, tighter tracking.
 * Body sizes stay at the M3 values to keep this dense tool's layouts stable.
 */
internal val PocketTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.tuned(40, 48, FontWeight.Bold, display = true),
        displayMedium = displayMedium.tuned(36, 44, FontWeight.Bold, display = true),
        displaySmall = displaySmall.tuned(34, 41, FontWeight.Bold, display = true),
        headlineLarge = headlineLarge.tuned(34, 41, FontWeight.Bold, display = true),
        headlineMedium = headlineMedium.tuned(28, 34, FontWeight.Bold, display = true),
        headlineSmall = headlineSmall.tuned(22, 28, FontWeight.Bold, display = true),
        titleLarge = titleLarge.tuned(20, 25, FontWeight.SemiBold),
        titleMedium = titleMedium.tuned(17, 22, FontWeight.SemiBold),
        titleSmall = titleSmall.tuned(15, 20, FontWeight.SemiBold),
        bodyLarge = bodyLarge.tuned(16, 22, FontWeight.Normal),
        bodyMedium = bodyMedium.tuned(14, 20, FontWeight.Normal),
        bodySmall = bodySmall.tuned(12, 16, FontWeight.Normal),
        labelLarge = labelLarge.tuned(15, 20, FontWeight.SemiBold),
        labelMedium = labelMedium.tuned(12, 16, FontWeight.Medium),
        labelSmall = labelSmall.tuned(11, 13, FontWeight.Medium),
    )
}

/** Concentric radius scale shared with [PocketRadius], so stock M3 components match. */
internal val PocketShapes = Shapes(
    extraSmall = RoundedCornerShape(PocketRadius.xs),
    small = RoundedCornerShape(PocketRadius.sm),
    medium = RoundedCornerShape(PocketRadius.md),
    large = RoundedCornerShape(PocketRadius.lg),
    extraLarge = RoundedCornerShape(PocketRadius.xl),
)

enum class AppThemeMode { SYSTEM, DARK, LIGHT }

@Composable
fun PocketTheme(themeMode: AppThemeMode = AppThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    MaterialTheme(
        colorScheme = if (isDark) DarkColors else LightColors,
        typography = PocketTypography,
        shapes = PocketShapes,
        content = content,
    )
}
