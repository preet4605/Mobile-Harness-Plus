package com.jarves.mh.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key

@Composable
internal fun rememberChatListState(chatId: String?, itemCount: Int, projectId: String? = null): LazyListState =
    key(projectId, chatId) {
        // Reset only for a different chat; appending messages must preserve a reader's position.
        rememberLazyListState(initialFirstVisibleItemIndex = (itemCount - 1).coerceAtLeast(0))
    }
