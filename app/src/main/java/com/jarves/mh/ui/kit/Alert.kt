package com.jarves.mh.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.jarves.mh.ui.theme.ContinuousRoundedShape
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketMotion.Token
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized

enum class AlertRole { Default, Cancel, Destructive }

@Immutable
data class AlertAction(val label: String, val role: AlertRole = AlertRole.Default, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * An alert for decisions the user must make (confirm, destructive actions, short forms). It is
 * a system dialog window, so Back, focus and accessibility behave as they always have; it scales
 * in from 108% over a light dim. Two actions sit side by side, more stack vertically; the cancel
 * action is gray and a destructive one is red.
 */
@Composable
fun PocketAlert(
    onDismiss: () -> Unit,
    title: String,
    actions: List<AlertAction>,
    message: String? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) { window?.setDimAmount(0.32f) }
        val presence = remember { Animatable(0f) }
        LaunchedEffect(Unit) { presence.animateTo(1f, PocketMotion.spec(Token.Snappy)) }
        val colors = PocketColors.current
        Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.Center) {
            // Tap outside to dismiss, as before. A sibling behind the card, so the card's buttons
            // always get their taps.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
            Column(
                Modifier
                    .padding(horizontal = 36.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val p = presence.value
                        scaleX = lerp(1.08f, 1f, p)
                        scaleY = lerp(1.08f, 1f, p)
                        alpha = p.coerceIn(0f, 1f)
                    }
                    .clip(ContinuousRoundedShape(28.dp))
                    .background(colors.groupedSurface)
                    .semantics { paneTitle = title }
                    .absorbPointer()
                    .padding(PocketSpacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = PocketType.headline, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(PocketSpacing.xs))
                    Text(message, style = PocketType.footnote, color = colors.secondaryLabel, textAlign = TextAlign.Center)
                }
                if (content != null) {
                    Spacer(Modifier.height(PocketSpacing.md))
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), content = content)
                }
                Spacer(Modifier.height(PocketSpacing.lg))
                val buttons: @Composable (AlertAction, Modifier) -> Unit = { action, m ->
                    PocketButton(
                        text = action.label,
                        onClick = action.onClick,
                        modifier = m,
                        style = when (action.role) {
                            AlertRole.Cancel -> PocketButtonStyle.Gray
                            AlertRole.Destructive -> PocketButtonStyle.Filled
                            AlertRole.Default -> if (actions.size == 1 || action == actions.last()) PocketButtonStyle.Filled else PocketButtonStyle.Gray
                        },
                        destructive = action.role == AlertRole.Destructive,
                        enabled = action.enabled,
                        fullWidth = true,
                    )
                }
                if (actions.size == 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
                        actions.forEach { buttons(it, Modifier.weight(1f)) }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
                        actions.forEach { buttons(it, Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
}

/** Text style for a button label in an alert (kept here so alert forms match). */
internal val AlertLabel = PocketType.body.emphasized
