package com.jarves.mh.ui.theme.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.builditcode.glass.LocalBackdropLayerManager
import com.builditcode.glass.TriLevelLayout
import com.builditcode.glass.TrilevelLayers
import com.builditcode.glass.rememberBackdropManager

/**
 * Host container providing backdrop capture management and Liquid Glass configuration.
 *
 * Wrap screens or UI roots with [LiquidGlassHost] to enable high-performance
 * backdrop refraction and blur for all nested [LiquidGlassSurface] components.
 */
@Composable
fun LiquidGlassHost(
    modifier: Modifier = Modifier,
    config: LiquidGlassConfig = LiquidGlassConfig(),
    content: @Composable () -> Unit,
) {
    val manager = rememberBackdropManager(
        defaultScaleFactor = config.scaleFactor,
        defaultDebounceMs = config.debounceMs,
    )

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager,
        LocalLiquidGlassConfig provides config,
    ) {
        Box(modifier = modifier) {
            content()
        }
    }
}

/**
 * Convenience layout implementing Apple HIG's 3-tier visual hierarchy:
 * 1. [background] — Underlying content / canvas layer (captured by Liquid Glass)
 * 2. [foreground] — Standard content layer elements (cards, text, lists)
 * 3. [overlay] — Floating Liquid Glass controls, bars, and navigation elements
 */
@Composable
fun LiquidGlassTriLevelLayout(
    modifier: Modifier = Modifier,
    config: LiquidGlassConfig = LiquidGlassConfig(),
    background: @Composable () -> Unit,
    foreground: @Composable () -> Unit,
    overlay: @Composable () -> Unit,
) {
    val manager = rememberBackdropManager(
        defaultScaleFactor = config.scaleFactor,
        defaultDebounceMs = config.debounceMs,
    )

    CompositionLocalProvider(
        LocalBackdropLayerManager provides manager,
        LocalLiquidGlassConfig provides config,
    ) {
        TriLevelLayout(
            modifier = modifier,
            scaleFactor = config.scaleFactor,
            debounceMs = config.debounceMs,
            manager = manager,
            background = { background() },
            foreground = { foreground() },
            overlay = { overlay() },
        )
    }
}

/**
 * Predefined layer names for backdrop capture and refraction.
 */
object LiquidGlassLayers {
    const val Background: String = TrilevelLayers.Background
    const val Foreground: String = TrilevelLayers.Foreground
    const val Overlay: String = TrilevelLayers.Overlay
}
