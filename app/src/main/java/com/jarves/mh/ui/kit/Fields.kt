package com.jarves.mh.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType

/**
 * A text field on a gray fill: 44 dp tall (taller when multi-line), optional label above and
 * helper or error text below. [secure] hides the text with a reveal button.
 */
@Composable
fun PocketTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    helper: String? = null,
    error: String? = null,
    secure: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    enabled: Boolean = true,
    monospace: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val colors = PocketColors.current
    var revealed by rememberSaveable { mutableStateOf(false) }
    val style: TextStyle = (if (monospace) PocketType.code.copy(fontSize = PocketType.body.fontSize) else PocketType.body)
        .copy(color = if (enabled) MaterialTheme.colorScheme.onSurface else colors.tertiaryLabel)
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = PocketType.footnote, color = colors.secondaryLabel, modifier = Modifier.padding(start = PocketSpacing.xs, bottom = PocketSpacing.xs))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(PocketShape.sm)
                .background(colors.tertiaryFill)
                .padding(start = PocketSpacing.md, end = if (secure || trailing != null) PocketSpacing.xxs else PocketSpacing.md),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            Box(Modifier.weight(1f).padding(vertical = PocketSpacing.sm + 3.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = style.copy(color = colors.tertiaryLabel), maxLines = maxLines)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = style,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (secure && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = if (secure) keyboardOptions.copy(keyboardType = KeyboardType.Password) else keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .semantics {
                            contentDescription = label ?: placeholder
                            if (error != null) error(error)
                        },
                )
            }
            if (secure) {
                PocketIconButton(
                    icon = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = if (revealed) "Hide" else "Show",
                    onClick = { revealed = !revealed },
                    tint = colors.secondaryLabel,
                )
            } else if (trailing != null) {
                Spacer(Modifier.width(PocketSpacing.xs))
                trailing()
            }
        }
        val below = error ?: helper
        if (below != null) {
            Text(
                below,
                style = PocketType.footnote,
                color = if (error != null) colors.red else colors.secondaryLabel,
                modifier = Modifier.padding(start = PocketSpacing.xs, top = PocketSpacing.xs),
            )
        }
    }
}
