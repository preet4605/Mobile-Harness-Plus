package com.jarves.mh.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarves.mh.model.ChangeItem
import com.jarves.mh.model.DiffLine
import com.jarves.mh.model.DiffLineType
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListRowAccessory
import com.jarves.mh.ui.kit.SectionHeader
import com.jarves.mh.ui.kit.OverlayAnchor
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.SectionCard
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.medium

/** "+12 −3" for a set of changes. */
internal fun changeTotals(changes: List<ChangeItem>): Pair<Int, Int> =
    changes.sumOf { it.additions } to changes.sumOf { it.deletions }

internal fun changesSummary(changes: List<ChangeItem>): String =
    if (changes.size == 1) "1 file changed" else "${changes.size} files changed"

/**
 * The Files tab's review of the last task's changes: a summary row that opens [ChangesReviewSheet]
 * for each file's diff, then Keep all and Undo all right here. Undoing everything asks first.
 */
@Composable
internal fun ChangesFilesSection(
    changes: List<ChangeItem>,
    onReview: () -> Unit,
    onUndoAll: () -> Unit,
    onKeepAll: () -> Unit,
) {
    var confirmUndo by remember { mutableStateOf(false) }
    val colors = PocketColors.current
    val (added, removed) = changeTotals(changes)
    Column(Modifier.fillMaxWidth().padding(horizontal = ListInset).padding(bottom = PocketSpacing.lg)) {
        SectionHeader("Changes")
        SectionCard {
            ListRow(
                title = changesSummary(changes),
                subtitle = "+$added −$removed · Review changes",
                icon = Icons.Outlined.Description,
                iconTile = colors.blue,
                accessory = ListRowAccessory.Chevron,
                onClick = onReview,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = PocketSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
        ) {
            PocketButton(
                text = "Undo all",
                onClick = { confirmUndo = true },
                style = PocketButtonStyle.Gray,
                destructive = true,
                size = PocketButtonSize.Large,
                modifier = Modifier.weight(1f),
                fullWidth = true,
            )
            PocketButton(
                text = "Keep all",
                onClick = onKeepAll,
                style = PocketButtonStyle.Filled,
                size = PocketButtonSize.Large,
                modifier = Modifier.weight(1f),
                fullWidth = true,
            )
        }
    }
    if (confirmUndo) {
        PocketAlert(
            onDismiss = { confirmUndo = false },
            title = "Undo all changes?",
            message = "Every file the last task changed goes back to how it was before the task.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmUndo = false },
                AlertAction("Undo all", AlertRole.Destructive) {
                    confirmUndo = false
                    onUndoAll()
                },
            ),
        )
    }
}

/**
 * Review of the last task's changes in a sheet: one row per file (tap to read its diff and keep
 * or undo just that file), then Undo all / Keep all. Undoing everything asks first.
 */
@Composable
internal fun ChangesReviewSheet(
    visible: Boolean,
    changes: List<ChangeItem>,
    anchor: OverlayAnchor?,
    onDismiss: () -> Unit,
    onUndoAll: () -> Unit,
    onKeepAll: () -> Unit,
    onUndoFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
) {
    var confirmUndo by remember { mutableStateOf(false) }
    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Changes",
        anchor = anchor,
    ) {
        ChangesReviewContent(
            changes = changes,
            onUndoAll = { confirmUndo = true },
            onKeepAll = {
                onKeepAll()
                onDismiss()
            },
            onUndoFile = onUndoFile,
            onKeepFile = onKeepFile,
        )
    }
    if (confirmUndo) {
        PocketAlert(
            onDismiss = { confirmUndo = false },
            title = "Undo all changes?",
            message = "Every file the last task changed goes back to how it was before the task.",
            actions = listOf(
                AlertAction("Cancel", AlertRole.Cancel) { confirmUndo = false },
                AlertAction("Undo all", AlertRole.Destructive) {
                    confirmUndo = false
                    onUndoAll()
                    onDismiss()
                },
            ),
        )
    }
}

@Composable
internal fun ChangesReviewContent(
    changes: List<ChangeItem>,
    onUndoAll: () -> Unit,
    onKeepAll: () -> Unit,
    onUndoFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
) {
    var expandedPath by rememberSaveable { mutableStateOf<String?>(null) }
    if (changes.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.Description,
            title = "No changes to review",
            message = "Files the agent changes show up here until you keep or undo them.",
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    val (added, removed) = changeTotals(changes)
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = PocketSpacing.lg),
        ) {
            item(key = "summary") {
                SectionHeader("${changesSummary(changes)} · +$added −$removed", Modifier.padding(horizontal = ListInset))
            }
            items(changes, key = { it.path }) { change ->
                val expanded = expandedPath == change.path
                Column(Modifier.padding(horizontal = ListInset, vertical = PocketSpacing.xs)) {
                    SectionCard {
                        ListRow(
                            title = change.path.substringAfterLast('/'),
                            subtitle = change.path.substringBeforeLast('/', "").ifBlank { null },
                            subtitleMaxLines = 1,
                            onClick = { expandedPath = if (expanded) null else change.path },
                            trailing = {
                                Text("+${change.additions}", style = PocketType.footnote.medium, color = PocketColors.current.green)
                                Spacer(Modifier.width(PocketSpacing.xs))
                                Text("−${change.deletions}", style = PocketType.footnote.medium, color = PocketColors.current.red)
                                Spacer(Modifier.width(PocketSpacing.xs))
                                Icon(
                                    if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                    contentDescription = if (expanded) "Hide diff" else "Show diff",
                                    tint = PocketColors.current.tertiaryLabel,
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                        )
                        AnimatedVisibility(
                            visible = expanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Column {
                                DiffView(change)
                                Row(
                                    Modifier.fillMaxWidth().padding(PocketSpacing.md),
                                    horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                                ) {
                                    PocketButton(
                                        text = "Undo file",
                                        onClick = {
                                            expandedPath = null
                                            onUndoFile(change.path)
                                        },
                                        style = PocketButtonStyle.Gray,
                                        size = PocketButtonSize.Small,
                                        destructive = true,
                                        modifier = Modifier.weight(1f),
                                        fullWidth = true,
                                    )
                                    PocketButton(
                                        text = "Keep file",
                                        onClick = {
                                            expandedPath = null
                                            onKeepFile(change.path)
                                        },
                                        style = PocketButtonStyle.Tinted,
                                        size = PocketButtonSize.Small,
                                        modifier = Modifier.weight(1f),
                                        fullWidth = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ListInset, vertical = PocketSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
        ) {
            PocketButton(
                text = "Undo all",
                onClick = onUndoAll,
                style = PocketButtonStyle.Gray,
                destructive = true,
                size = PocketButtonSize.Large,
                modifier = Modifier.weight(1f),
                fullWidth = true,
            )
            PocketButton(
                text = "Keep all",
                onClick = onKeepAll,
                style = PocketButtonStyle.Filled,
                size = PocketButtonSize.Large,
                modifier = Modifier.weight(1f),
                fullWidth = true,
            )
        }
    }
}

/** A file's diff: line numbers, +/− marker and code, scrolling sideways, on the code surface. */
@Composable
private fun DiffView(change: ChangeItem) {
    val colors = PocketColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketSpacing.md)
            .clip(PocketShape.sm)
            .background(colors.codeSurface)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = PocketSpacing.xs)
            .semantics { contentDescription = "Diff of ${change.path}" },
    ) {
        if (change.binary) {
            Text("Binary file", style = PocketType.code, color = colors.secondaryLabel, modifier = Modifier.padding(PocketSpacing.sm))
        }
        change.diffLines.forEach { DiffLineText(it) }
    }
}

@Composable
private fun DiffLineText(line: DiffLine) {
    val colors = PocketColors.current
    val marker = when (line.type) {
        DiffLineType.ADDITION -> "+"
        DiffLineType.DELETION -> "−"
        DiffLineType.CONTEXT -> " "
        DiffLineType.INFO -> "·"
    }
    val background = when (line.type) {
        DiffLineType.ADDITION -> colors.green.copy(alpha = if (colors.isDark) 0.16f else 0.12f)
        DiffLineType.DELETION -> colors.red.copy(alpha = if (colors.isDark) 0.16f else 0.10f)
        else -> androidx.compose.ui.graphics.Color.Transparent
    }
    val foreground = when (line.type) {
        DiffLineType.INFO -> colors.secondaryLabel
        else -> MaterialTheme.colorScheme.onSurface
    }
    val oldNumber = line.oldLine?.toString().orEmpty().padStart(4)
    val newNumber = line.newLine?.toString().orEmpty().padStart(4)
    Row(Modifier.background(background).padding(horizontal = PocketSpacing.sm)) {
        Text(
            "$oldNumber $newNumber ",
            style = PocketType.codeSmall,
            color = colors.tertiaryLabel,
            softWrap = false,
        )
        Text(
            "$marker ${line.text}",
            style = PocketType.codeSmall,
            color = foreground,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
}
