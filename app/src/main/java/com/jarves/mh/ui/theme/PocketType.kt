package com.jarves.mh.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Text roles for screens, from Large Title down to Caption 2. Screens pick a role instead of
 * hard-coding a size, so the hierarchy stays consistent and scales with the user's font size.
 * Sizes and line heights follow the platform-standard reading scale; weights are the defaults
 * for each role and can be raised with [emphasized].
 */
object PocketType {
    val largeTitle: TextStyle = TextStyle().tuned(34, 41, FontWeight.Bold, display = true)
    val title1: TextStyle = TextStyle().tuned(28, 34, FontWeight.Bold, display = true)
    val title2: TextStyle = TextStyle().tuned(22, 28, FontWeight.Bold, display = true)
    val title3: TextStyle = TextStyle().tuned(20, 25, FontWeight.SemiBold)
    val headline: TextStyle = TextStyle().tuned(17, 22, FontWeight.SemiBold)
    val body: TextStyle = TextStyle().tuned(17, 22, FontWeight.Normal)
    val callout: TextStyle = TextStyle().tuned(16, 21, FontWeight.Normal)
    val subheadline: TextStyle = TextStyle().tuned(15, 20, FontWeight.Normal)
    val footnote: TextStyle = TextStyle().tuned(13, 18, FontWeight.Normal)
    val caption1: TextStyle = TextStyle().tuned(12, 16, FontWeight.Normal)
    val caption2: TextStyle = TextStyle().tuned(11, 13, FontWeight.Normal)

    /** Monospaced text for code, logs and the terminal. */
    val code: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.em,
    )

    /** Smaller monospaced text for dense logs and diffs. */
    val codeSmall: TextStyle = code.copy(fontSize = 12.sp, lineHeight = 16.sp)
}

/** The same role one weight step heavier (Regular → SemiBold, SemiBold → Bold). */
val TextStyle.emphasized: TextStyle
    get() = copy(
        fontWeight = when (fontWeight) {
            null, FontWeight.Normal, FontWeight.Medium -> FontWeight.SemiBold
            else -> FontWeight.Bold
        },
    )

/** The same role at Medium weight, for labels on controls. */
val TextStyle.medium: TextStyle
    get() = copy(fontWeight = FontWeight.Medium)
