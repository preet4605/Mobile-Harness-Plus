package com.jarves.mh.ui.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/** Largest text scale inside fixed-height chrome (bars, tab bar, segmented controls, sheet headers). */
const val ChromeMaxFontScale: Float = 1.3f

/**
 * Bars, the tab bar, segmented controls and sheet headers have a fixed height, so their text
 * grows with the system text size only up to [max]; the content they frame keeps scaling fully.
 * Below [max] nothing changes.
 */
@Composable
fun CappedTextScale(max: Float = ChromeMaxFontScale, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max), content = content)
    }
}
