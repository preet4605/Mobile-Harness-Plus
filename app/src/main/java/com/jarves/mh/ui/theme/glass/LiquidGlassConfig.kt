package com.jarves.mh.ui.theme.glass

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Shortest gap between backdrop captures while a list scrolls (about 30 a second). Each capture
 * re-renders the visible content and re-blurs every glass surface, so capturing on every frame
 * cost the whole frame budget. The list itself still draws at the display rate; glass shows a
 * blurred sample at most this far behind it.
 */
internal const val SCROLL_RECAPTURE_INTERVAL_MS = 33L

/**
 * Runtime configuration for Liquid Glass rendering, performance budgets, and accessibility.
 *
 * @param enabled Master toggle to enable or disable glass effects globally.
 * @param enableRefraction Whether the AGSL lens tier may run (Android 13+ only). Kill switch.
 * @param reduceTransparency Accessibility override. When true, switches to opaque, high-contrast surfaces.
 * @param scaleFactor Backdrop capture resolution (0.5 = half: a quarter of the pixels; the blur hides the loss).
 * @param debounceMs Minimum interval between captures caused by scrolling. Defaults to
 *   [SCROLL_RECAPTURE_INTERVAL_MS]; 0 captures every frame and costs the frame budget.
 * @param dispersion Colour split at the lens edge (0 turns it off). Kill switch.
 * @param adaptiveWash Lens tier: denser wash over bright (dark mode) or dark (light mode) content.
 * @param edgeLight Lens tier: soft inner highlight facing the light.
 * @param pressGlow Light spot under the finger on interactive glass.
 * @param mergeShapes Nearby glass in one group blends into a single shape (lens tier). Kill switch.
 * @param progressiveEdge Blurred, fading scroll edge under bars. Kill switch.
 * @param increaseContrast Accessibility: solid surfaces with a visible border.
 * @param reduceMotion Accessibility: no lift, stretch or bounce; slides become cross-fades.
 */
@Immutable
data class LiquidGlassConfig(
    val enabled: Boolean = true,
    val enableRefraction: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    val reduceTransparency: Boolean = false,
    val scaleFactor: Float = 0.5f,
    val debounceMs: Long = SCROLL_RECAPTURE_INTERVAL_MS,
    val dispersion: Float = 0.12f,
    val adaptiveWash: Boolean = true,
    val edgeLight: Boolean = true,
    val pressGlow: Boolean = true,
    val mergeShapes: Boolean = true,
    val progressiveEdge: Boolean = true,
    val increaseContrast: Boolean = false,
    val reduceMotion: Boolean = false,
) {
    /**
     * Returns true if glass effects should actively render.
     */
    val isGlassActive: Boolean
        get() = enabled && !reduceTransparency && !increaseContrast

    companion object {
        /**
         * Resolves a [LiquidGlassConfig] by combining user preference with system accessibility settings.
         */
        fun resolve(
            context: Context? = null,
            userPreference: Boolean = false,
            enabled: Boolean = true,
            scaleFactor: Float = 0.5f,
            debounceMs: Long = SCROLL_RECAPTURE_INTERVAL_MS,
        ): LiquidGlassConfig {
            val systemReduced = if (context != null && Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                    val method = am?.javaClass?.getMethod("isReduceTransparencyEnabled")
                    (method?.invoke(am) as? Boolean) == true
                }.getOrDefault(false)
            } else {
                false
            }
            return LiquidGlassConfig(
                enabled = enabled,
                reduceTransparency = userPreference || systemReduced,
                scaleFactor = scaleFactor,
                debounceMs = debounceMs,
                increaseContrast = context?.let(::systemIncreasedContrast) ?: false,
                reduceMotion = context?.let(::systemReducedMotion) ?: false,
            )
        }

        /**
         * Resolves a [LiquidGlassConfig] directly from [AppPreferences].
         */
        fun fromPreferences(
            preferences: com.jarves.mh.data.AppPreferences,
            context: Context? = null,
            enabled: Boolean = true,
            scaleFactor: Float = 0.5f,
            debounceMs: Long = SCROLL_RECAPTURE_INTERVAL_MS,
        ): LiquidGlassConfig {
            return resolve(
                context = context,
                userPreference = preferences.reduceTransparency,
                enabled = enabled,
                scaleFactor = scaleFactor,
                debounceMs = debounceMs,
            )
        }

        /** "Remove animations" (or animator scale 0) in system settings. */
        internal fun systemReducedMotion(context: Context): Boolean = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)

        /** Android 14+ contrast setting raised above standard, or high-contrast text on. */
        internal fun systemIncreasedContrast(context: Context): Boolean = runCatching {
            val uiMode = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            val contrast = if (Build.VERSION.SDK_INT >= 34 && uiMode != null) uiMode.contrast else 0f
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            val highText = am?.javaClass?.getMethod("isHighTextContrastEnabled")?.invoke(am) as? Boolean == true
            contrast > 0.25f || highText
        }.getOrDefault(false)
    }
}

/**
 * CompositionLocal providing active [LiquidGlassConfig].
 */
val LocalLiquidGlassConfig = compositionLocalOf { LiquidGlassConfig() }

/**
 * Convenience helper to determine whether glass effects are currently active in composition.
 */
@Composable
fun isLiquidGlassActive(): Boolean {
    return LocalLiquidGlassConfig.current.isGlassActive
}
