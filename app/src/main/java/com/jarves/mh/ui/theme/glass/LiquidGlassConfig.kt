package com.jarves.mh.ui.theme.glass

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Runtime configuration for Liquid Glass rendering, performance budgets, and accessibility.
 *
 * @param enabled Master toggle to enable or disable glass effects globally.
 * @param enableRefraction Whether runtime shader refraction is active (requires API 33+ for HW shaders).
 * @param reduceTransparency Accessibility override. When true, switches to opaque, high-contrast surfaces.
 * @param scaleFactor Backdrop capture downscaling (0.5f balances high fidelity with smooth 60fps rendering).
 * @param debounceMs Debounce interval in ms for backdrop invalidation.
 */
@Immutable
data class LiquidGlassConfig(
    val enabled: Boolean = true,
    val enableRefraction: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
    val reduceTransparency: Boolean = false,
    val scaleFactor: Float = 0.5f,
    val debounceMs: Long = 16L,
) {
    /**
     * Returns true if glass effects should actively render.
     */
    val isGlassActive: Boolean
        get() = enabled && !reduceTransparency

    companion object {
        /**
         * Resolves a [LiquidGlassConfig] by combining user preference with system accessibility settings.
         */
        fun resolve(
            context: android.content.Context? = null,
            userPreference: Boolean = false,
            enabled: Boolean = true,
            scaleFactor: Float = 0.5f,
            debounceMs: Long = 16L,
        ): LiquidGlassConfig {
            val systemReduced = if (context != null && Build.VERSION.SDK_INT >= 34) {
                runCatching {
                    val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
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
            )
        }

        /**
         * Resolves a [LiquidGlassConfig] directly from [AppPreferences].
         */
        fun fromPreferences(
            preferences: com.jarves.mh.data.AppPreferences,
            context: android.content.Context? = null,
            enabled: Boolean = true,
            scaleFactor: Float = 0.5f,
            debounceMs: Long = 16L,
        ): LiquidGlassConfig {
            return resolve(
                context = context,
                userPreference = preferences.reduceTransparency,
                enabled = enabled,
                scaleFactor = scaleFactor,
                debounceMs = debounceMs,
            )
        }
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
