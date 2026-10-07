package com.jarves.mh.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType

/**
 * Circular progress. [progress] in 0..1 draws a determinate ring that springs to new values;
 * null spins an open arc (only while it is on screen).
 */
@Composable
fun ProgressRing(
    progress: Float?,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    strokeWidth: Dp = 3.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = PocketColors.current.fill,
) {
    val description = if (progress == null) "In progress" else "${(progress * 100).toInt()} percent"
    val semantics = Modifier.semantics {
        contentDescription = description
        if (progress != null) progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }
    if (progress != null) {
        val animated by animateFloatAsState(
            progress.coerceIn(0f, 1f),
            PocketMotion.spec(PocketMotion.Token.Smooth),
            label = "ringProgress",
        )
        Canvas(modifier.size(size).then(semantics)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    } else {
        val transition = rememberInfiniteTransition(label = "ringSpin")
        val angle by transition.animateFloat(
            0f,
            360f,
            infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "ringAngle",
        )
        Canvas(modifier.size(size).then(semantics)) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(trackColor, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            rotate(angle) {
                drawArc(color, -90f, 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

/** A centred symbol, title, one line of explanation and an optional action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = PocketColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = PocketSpacing.jumbo, vertical = PocketSpacing.jumbo),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.tertiaryLabel, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(PocketSpacing.md))
        Text(title, style = PocketType.title3, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(PocketSpacing.xs))
            Text(
                message,
                style = PocketType.subheadline,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(PocketSpacing.lg))
            PocketButton(actionLabel, onAction, style = PocketButtonStyle.Tinted)
        }
    }
}

/**
 * Search field: 36 dp tall fill with a magnifier, placeholder and a clear button once there is
 * text. The whole 44 dp row focuses the field.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    onSearch: (() -> Unit)? = null,
) {
    val colors = PocketColors.current
    val focus = remember { FocusRequester() }
    Box(modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(PocketShape.sm)
                .background(colors.tertiaryFill)
                .padding(horizontal = PocketSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.secondaryLabel, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(PocketSpacing.xs + 2.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = PocketType.body, color = colors.secondaryLabel, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = PocketType.body.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = placeholder },
                )
            }
            if (value.isNotEmpty()) {
                PocketIconButton(
                    icon = Icons.Rounded.Cancel,
                    contentDescription = "Clear search",
                    onClick = { onValueChange("") },
                    tint = colors.tertiaryLabel,
                    iconSize = 18.dp,
                )
            }
        }
    }
}
