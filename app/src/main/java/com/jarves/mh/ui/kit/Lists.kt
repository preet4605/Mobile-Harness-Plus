package com.jarves.mh.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketMotion
import com.jarves.mh.ui.theme.PocketRadius
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized

/** Side margin of grouped lists and the text inset inside rows. */
val ListInset: Dp = 16.dp

/** Size of the coloured symbol tile at the start of a row. */
val ListIconTile: Dp = 30.dp

private val IconGap = 14.dp

/**
 * An inset grouped section: optional header, a rounded card of rows separated by hairlines
 * that start at the row text, and an optional footer. Put rows ([ListRow], [ToggleRow] or
 * anything else) directly inside; separators are added between them.
 */
@Composable
fun ListSection(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = ListInset)) {
        if (header != null) SectionHeader(header)
        SectionCard(content = content)
        if (footer != null) SectionFooter(footer)
    }
}

/** Section title above a grouped card. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PocketType.subheadline.emphasized,
        color = PocketColors.current.secondaryLabel,
        modifier = modifier
            .padding(start = ListInset, end = ListInset, top = PocketSpacing.sm, bottom = PocketSpacing.sm - 2.dp)
            .semantics { heading() },
    )
}

/** Explanatory text under a grouped card. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = PocketType.footnote,
        color = PocketColors.current.secondaryLabel,
        modifier = modifier.padding(start = ListInset, end = ListInset, top = PocketSpacing.sm - 2.dp),
    )
}

/** The rounded card with separators, without the header/footer and side margins. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = PocketColors.current
    var separators by remember { mutableStateOf(IntArray(0)) }
    var insets by remember { mutableStateOf(IntArray(0)) }
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .clip(PocketShape.lg)
            .background(colors.groupedSurface)
            .drawWithContent {
                drawContent()
                val stroke = (0.5.dp.toPx()).coerceAtLeast(1f)
                separators.forEachIndexed { i, y ->
                    val inset = insets.getOrElse(i) { 0 }.toFloat()
                    val (start, end) = if (layoutDirection == LayoutDirection.Ltr) inset to size.width else 0f to size.width - inset
                    drawLine(colors.separator, Offset(start, y - stroke / 2f), Offset(end, y - stroke / 2f), stroke)
                }
            },
    ) { measurables, constraints ->
        val childConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val rowInsets = measurables.map { ((it.parentData as? ListRowInset)?.start ?: ListInset).roundToPx() }
        val width = constraints.maxWidth
        val height = placeables.sumOf { it.height }
        val newSeparators = IntArray((placeables.size - 1).coerceAtLeast(0))
        var y = 0
        placeables.forEachIndexed { i, p ->
            y += p.height
            if (i < newSeparators.size) newSeparators[i] = y
        }
        // Each separator starts where the row below it starts its text.
        val newInsets = IntArray(newSeparators.size) { rowInsets[it + 1] }
        if (!newSeparators.contentEquals(separators)) separators = newSeparators
        if (!newInsets.contentEquals(insets)) insets = newInsets
        layout(width, height) {
            var top = 0
            placeables.forEach { p ->
                p.placeRelative(0, top)
                top += p.height
            }
        }
    }
}

/** Where a row's text starts, so the separator above it lines up with the text. */
private class ListRowInset(val start: Dp) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = this@ListRowInset
}

/** Marks custom rows inside a [SectionCard] with where their text starts. */
fun Modifier.listRowInset(start: Dp): Modifier = this.then(ListRowInset(start))

/** What sits at the end of a [ListRow]. */
enum class ListRowAccessory { None, Chevron, Check }

/**
 * A row in a grouped list: optional symbol tile, title with optional subtitle, optional value,
 * and an accessory (chevron for navigation, check for selection). 44 dp minimum, taller with a
 * symbol or subtitle. Pressing highlights the row.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTile: Color? = null,
    value: String? = null,
    accessory: ListRowAccessory = ListRowAccessory.None,
    destructive: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color? = null,
    titleModifier: Modifier = Modifier,
    subtitleColor: Color? = null,
    subtitleMaxLines: Int = 2,
    onLongClick: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = PocketColors.current
    val hasLeading = icon != null || leading != null
    val textStart = if (hasLeading) ListInset + ListIconTile + IconGap else ListInset
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val highlight by animateColorAsState(
        if (pressed && onClick != null) colors.tertiaryFill else Color.Transparent,
        PocketMotion.spec(PocketMotion.Token.Quick),
        label = "rowHighlight",
    )
    val clickModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onLongClick = onLongClick,
            onClick = onClick ?: {},
        )
    } else Modifier
    val minHeight = when {
        subtitle != null -> 60.dp
        hasLeading -> 48.dp
        else -> 44.dp
    }

    Row(
        modifier
            .listRowInset(textStart)
            .fillMaxWidth()
            .background(highlight)
            .then(clickModifier)
            .heightIn(min = minHeight)
            .padding(start = ListInset, end = ListInset - 2.dp, top = PocketSpacing.sm, bottom = PocketSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(ListIconTile), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(IconGap))
        } else if (icon != null) {
            SymbolTile(icon, iconTile ?: colors.gray)
            Spacer(Modifier.width(IconGap))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                title,
                style = PocketType.body,
                color = when {
                    !enabled -> colors.tertiaryLabel
                    destructive -> colors.red
                    titleColor != null -> titleColor
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = titleModifier,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = PocketType.subheadline,
                    color = subtitleColor ?: colors.secondaryLabel,
                    maxLines = subtitleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (value != null) {
            Spacer(Modifier.width(PocketSpacing.sm))
            Text(
                value,
                style = PocketType.body,
                color = colors.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(PocketSpacing.sm))
            trailing()
        }
        when (accessory) {
            ListRowAccessory.Chevron -> {
                Spacer(Modifier.width(PocketSpacing.xs))
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.tertiaryLabel,
                    modifier = Modifier.size(20.dp),
                )
            }
            ListRowAccessory.Check -> {
                Spacer(Modifier.width(PocketSpacing.xs))
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            ListRowAccessory.None -> Unit
        }
    }
}

/** A row with a switch at the end; tapping anywhere on the row toggles it. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTile: Color? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    ListRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        iconTile = iconTile,
        enabled = enabled,
        modifier = modifier,
        onClick = if (enabled) {
            {
                haptics.toggle(!checked)
                onCheckedChange(!checked)
            }
        } else null,
        trailing = { PocketToggle(checked = checked, onCheckedChange = null, enabled = enabled) },
    )
}

/** A white symbol on a rounded coloured tile, as at the start of settings rows. */
@Composable
fun SymbolTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = ListIconTile) {
    Box(
        modifier
            .size(size)
            .clip(com.jarves.mh.ui.theme.ContinuousRoundedShape(size * 0.24f))
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

/**
 * One row of a grouped card inside a lazy list, for long lists where the card can't be one
 * composable: the first row rounds the top corners, the last the bottom, and rows after the
 * first draw the separator above them from [separatorStart].
 */
@Composable
fun LazyGroupRow(
    isFirst: Boolean,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    separatorStart: Dp = ListInset,
    content: @Composable () -> Unit,
) {
    val colors = PocketColors.current
    val shape = remember(isFirst, isLast) { GroupEdgeShape(PocketRadius.lg, isFirst, isLast) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = ListInset)
            .clip(shape)
            .background(colors.groupedSurface)
            .drawWithContent {
                drawContent()
                if (!isFirst) {
                    val stroke = (0.5.dp.toPx()).coerceAtLeast(1f)
                    val inset = separatorStart.toPx()
                    val (start, end) = if (layoutDirection == LayoutDirection.Ltr) inset to size.width else 0f to size.width - inset
                    drawLine(colors.separator, Offset(start, stroke / 2f), Offset(end, stroke / 2f), stroke)
                }
            },
    ) { content() }
}

/** Continuous corners on the top and/or bottom edge only. */
private class GroupEdgeShape(private val radius: Dp, private val top: Boolean, private val bottom: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (!top && !bottom) return Outline.Rectangle(size.toRect())
        val r = with(density) { radius.toPx() }
        // Round the whole rectangle, extended past the square edge so only the wanted corners show.
        val extra = if (top && bottom) 0f else r * 2f
        val path = com.jarves.mh.ui.theme.continuousRoundedRectPath(Size(size.width, size.height + extra), r, com.jarves.mh.ui.theme.ContinuousRoundedShape.DefaultSmoothing)
        if (!top) path.translate(Offset(0f, -extra))
        return Outline.Generic(path)
    }
}
