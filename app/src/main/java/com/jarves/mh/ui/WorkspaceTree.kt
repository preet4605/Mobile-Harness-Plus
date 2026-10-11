package com.jarves.mh.ui

import com.jarves.mh.model.WorkspaceEntry

/**
 * Orders a flat workspace listing as a depth-first tree: each folder is followed by its own
 * children, folders before files, names case-insensitively. The Files tab shows entries in list
 * order, so sorting by parent path put an expanded folder's children after every root entry.
 */
internal fun sortWorkspaceEntries(entries: List<WorkspaceEntry>): List<WorkspaceEntry> {
    val childrenByParent = entries.groupBy { entry -> entry.path.substringBeforeLast('/', "") }
    val siblingOrder = compareByDescending<WorkspaceEntry> { it.isDirectory }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.name }
    return buildList(entries.size) {
        fun appendChildren(parentPath: String) {
            childrenByParent[parentPath].orEmpty().sortedWith(siblingOrder).forEach { entry ->
                add(entry)
                if (entry.isDirectory) appendChildren(entry.path)
            }
        }
        appendChildren("")
        // Keep entries whose parent folder is missing from a capped listing instead of dropping them.
        if (size < entries.size) {
            val emitted = mapTo(HashSet(size)) { it.path }
            entries.filterNot { it.path in emitted }.sortedBy { it.path.lowercase() }.forEach(::add)
        }
    }
}
