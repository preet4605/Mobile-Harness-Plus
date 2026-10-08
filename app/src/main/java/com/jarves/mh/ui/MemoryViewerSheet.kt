package com.jarves.mh.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jarves.mh.data.ContextMemory
import com.jarves.mh.data.MemoryEntry
import com.jarves.mh.data.MemorySource
import com.jarves.mh.ui.kit.AlertAction
import com.jarves.mh.ui.kit.AlertRole
import com.jarves.mh.ui.kit.EmptyState
import com.jarves.mh.ui.kit.GlassSheet
import com.jarves.mh.ui.kit.LazyGroupRow
import com.jarves.mh.ui.kit.ListIconTile
import com.jarves.mh.ui.kit.ListInset
import com.jarves.mh.ui.kit.ListRow
import com.jarves.mh.ui.kit.ListSection
import com.jarves.mh.ui.kit.PocketAlert
import com.jarves.mh.ui.kit.PocketIconButton
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.SectionFooter
import com.jarves.mh.ui.kit.SectionHeader
import com.jarves.mh.ui.kit.SheetDetent
import com.jarves.mh.ui.kit.SheetTextButton
import com.jarves.mh.ui.kit.SymbolTile
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketSpacing

/** A confirmation the memory sheet is waiting on. */
private sealed interface MemoryConfirm {
    data class Delete(val entry: MemoryEntry) : MemoryConfirm
    data object ClearAuto : MemoryConfirm
    data object ClearAll : MemoryConfirm
}

/**
 * The project's persistent memory: facts kept across chats and engines. Facts are added in a
 * small sheet on top; deleting one and clearing ask first.
 */
@Composable
fun MemoryViewerSheet(
    memory: ContextMemory,
    onDismiss: () -> Unit,
    onAddEntry: (key: String, value: String) -> Unit,
    onDeleteEntry: (entryId: String) -> Unit,
    onClearAuto: () -> Unit,
    onClearAll: () -> Unit,
    visible: Boolean = true,
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<MemoryConfirm?>(null) }
    LaunchedEffect(visible) {
        // The form and confirmations belong to one visit; don't bring them back next time.
        if (!visible) {
            showAddForm = false
            pending = null
        }
    }
    val entries = memory.entries
    val autoCount = entries.count { it.source == MemorySource.AUTO }
    val saveFact = {
        val k = newKey.trim()
        val v = newValue.trim()
        if (k.isNotBlank() && v.isNotBlank()) {
            onAddEntry(k, v)
            newKey = ""
            newValue = ""
            showAddForm = false
        }
    }

    GlassSheet(
        onDismiss = onDismiss,
        visible = visible,
        title = "Memory",
        trailing = { PocketIconButton(Icons.Outlined.Add, "Add fact", onClick = { showAddForm = true }) },
    ) {
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Psychology,
                title = "No memories yet",
                message = "Facts like the project’s language, framework and your guidelines are saved after sessions. You can also add them yourself.",
                actionLabel = "Add fact",
                onAction = { showAddForm = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = PocketSpacing.xxl)) {
                item(key = "header") {
                    SectionHeader(
                        if (entries.size == 1) "1 fact" else "${entries.size} facts",
                        Modifier.padding(horizontal = ListInset).padding(top = PocketSpacing.xs),
                    )
                }
                itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                    LazyGroupRow(
                        isFirst = index == 0,
                        isLast = index == entries.lastIndex,
                        separatorStart = ListInset + ListIconTile + 14.dp,
                    ) {
                        MemoryEntryRow(entry, onDelete = { pending = MemoryConfirm.Delete(entry) })
                    }
                }
                item(key = "footer") {
                    SectionFooter("Kept across chats and engines.", Modifier.padding(horizontal = ListInset))
                }
                item(key = "clear") {
                    ListSection(Modifier.padding(top = PocketSpacing.xl)) {
                        if (autoCount > 0) {
                            ListRow(
                                "Clear automatic facts",
                                value = "$autoCount",
                                destructive = true,
                                onClick = { pending = MemoryConfirm.ClearAuto },
                            )
                        }
                        ListRow("Clear all", destructive = true, onClick = { pending = MemoryConfirm.ClearAll })
                    }
                }
            }
        }
    }

    // Called after the memory sheet so it stacks on top of it.
    GlassSheet(
        onDismiss = { showAddForm = false },
        visible = visible && showAddForm,
        title = "New fact",
        detents = listOf(SheetDetent.Fit),
        leading = { SheetTextButton("Cancel", { showAddForm = false }) },
        trailing = {
            SheetTextButton("Save", saveFact, emphasized = true, enabled = newKey.isNotBlank() && newValue.isNotBlank())
        },
    ) {
        val focus = remember { FocusRequester() }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ListInset)
                .padding(top = PocketSpacing.sm, bottom = PocketSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.lg),
        ) {
            PocketTextField(
                value = newKey,
                onValueChange = { newKey = it },
                label = "Key",
                placeholder = "project-framework",
                monospace = true,
                focusRequester = focus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            PocketTextField(
                value = newValue,
                onValueChange = { newValue = it },
                label = "Fact",
                placeholder = "Kotlin with Jetpack Compose",
                singleLine = false,
                minLines = 2,
                maxLines = 4,
            )
        }
        LaunchedEffect(Unit) {
            // Let the sheet finish rising before the keyboard pushes it.
            kotlinx.coroutines.delay(SheetFocusDelayMillis)
            runCatching { focus.requestFocus() }
        }
    }

    if (visible) {
        when (val confirm = pending) {
            is MemoryConfirm.Delete -> PocketAlert(
                onDismiss = { pending = null },
                title = "Delete “${confirm.entry.key}”?",
                message = "This fact is removed from memory.",
                actions = listOf(
                    AlertAction("Cancel", AlertRole.Cancel) { pending = null },
                    AlertAction("Delete", AlertRole.Destructive) {
                        pending = null
                        onDeleteEntry(confirm.entry.id)
                    },
                ),
            )
            MemoryConfirm.ClearAuto -> PocketAlert(
                onDismiss = { pending = null },
                title = "Clear automatic facts?",
                message = "Facts the agent saved on its own are removed. Facts you added stay.",
                actions = listOf(
                    AlertAction("Cancel", AlertRole.Cancel) { pending = null },
                    AlertAction("Clear", AlertRole.Destructive) {
                        pending = null
                        onClearAuto()
                    },
                ),
            )
            MemoryConfirm.ClearAll -> PocketAlert(
                onDismiss = { pending = null },
                title = "Clear all memory?",
                message = "All ${entries.size} facts for this project are removed. This can’t be undone.",
                actions = listOf(
                    AlertAction("Cancel", AlertRole.Cancel) { pending = null },
                    AlertAction("Clear all", AlertRole.Destructive) {
                        pending = null
                        onClearAll()
                    },
                ),
            )
            null -> Unit
        }
    }
}

@Composable
private fun MemoryEntryRow(
    entry: MemoryEntry,
    onDelete: () -> Unit,
) {
    val colors = PocketColors.current
    val isAuto = entry.source == MemorySource.AUTO
    ListRow(
        title = entry.key,
        subtitle = entry.value,
        subtitleMaxLines = 6,
        leading = {
            SymbolTile(
                if (isAuto) Icons.Outlined.AutoAwesome else Icons.Outlined.Person,
                if (isAuto) colors.purple else colors.blue,
                Modifier.semantics { contentDescription = if (isAuto) "Saved automatically" else "Added by you" },
            )
        },
        trailing = {
            PocketIconButton(
                Icons.Outlined.Delete,
                "Delete ${entry.key}",
                onClick = onDelete,
                tint = colors.secondaryLabel,
            )
        },
    )
}
