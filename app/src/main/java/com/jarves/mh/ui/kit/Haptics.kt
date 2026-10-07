package com.jarves.mh.ui.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Haptic feedback with one meaning per call, so the same kind of moment always feels the same.
 * Uses the view's haptic constants, which respect the system's touch-feedback setting.
 */
@Stable
class PocketHaptics internal constructor(private val view: View) {
    /** A value snapped to a new position: tab, segment, picker or detent change. */
    fun selection() = perform(HapticFeedbackConstants.CLOCK_TICK)

    /** A switch turned on or off. */
    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT >= 34 -> if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
            else -> HapticFeedbackConstants.CLOCK_TICK
        },
    )

    /** A light tap on a control that starts something (send, primary button). */
    fun tap() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    /** A long press that opens a menu. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    /** Something the user asked for finished well (approval sent, copied). */
    fun success() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
    )

    /** Something was refused or failed. */
    fun error() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    private fun perform(constant: Int) {
        view.performHapticFeedback(constant)
    }
}

@Composable
fun rememberHaptics(): PocketHaptics {
    val view = LocalView.current
    return remember(view) { PocketHaptics(view) }
}
