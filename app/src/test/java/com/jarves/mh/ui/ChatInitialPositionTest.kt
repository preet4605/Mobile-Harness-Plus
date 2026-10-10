package com.jarves.mh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ChatInitialPositionTest {
    @get:Rule val compose = createComposeRule()

    private val chatId = mutableStateOf("first-chat")
    private val projectId = mutableStateOf("first-project")
    private val itemCount = mutableStateOf(2_000)
    private val firstPositions = linkedMapOf<String, Int>()
    private val composedItems = mutableSetOf<Int>()
    private lateinit var listState: LazyListState
    private lateinit var scope: CoroutineScope

    private fun showChat() {
        compose.setContent {
            val id = chatId.value
            val project = projectId.value
            val count = itemCount.value
            val state = rememberChatListState(id, count, project)
            val coroutineScope = rememberCoroutineScope()
            val initialIndex = state.firstVisibleItemIndex
            SideEffect {
                listState = state
                scope = coroutineScope
                firstPositions.putIfAbsent("$project/$id", initialIndex)
            }
            LazyColumn(state = state, modifier = Modifier.height(300.dp)) {
                items(count, key = { "$id-$it" }) { index ->
                    SideEffect { composedItems.add(index) }
                    Box(Modifier.height(100.dp))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun longHistoryStartsAtNewestBeforeFirstLayout() {
        showChat()
        compose.runOnIdle {
            println("Initial chat index=${firstPositions.getValue("first-project/first-chat")}; composed=${composedItems.sorted()}")
            assertEquals(1_999, firstPositions.getValue("first-project/first-chat"))
            assertFalse("Oldest messages should not be composed on opening", 0 in composedItems)
        }
    }

    @Test fun emptyHistoryStartsAtZero() {
        itemCount.value = 0
        showChat()
        compose.runOnIdle { assertEquals(0, firstPositions.getValue("first-project/first-chat")) }
    }

    @Test fun singleMessageStartsAtZero() {
        itemCount.value = 1
        showChat()
        compose.runOnIdle { assertEquals(0, firstPositions.getValue("first-project/first-chat")) }
    }

    @Test fun livePanelAndApprovalAreIncludedInInitialPosition() {
        itemCount.value = 12 // Ten messages, a live panel, and an approval card.
        showChat()
        compose.runOnIdle { assertEquals(11, firstPositions.getValue("first-project/first-chat")) }
    }

    @Test fun switchingChatStartsAtItsNewestItemImmediately() {
        showChat()
        compose.runOnIdle {
            itemCount.value = 40
            chatId.value = "second-chat"
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(39, firstPositions.getValue("first-project/second-chat")) }
    }

    @Test fun newMessagesDoNotResetReaderPosition() {
        showChat()
        compose.runOnIdle { scope.launch { listState.scrollToItem(12) } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(12, listState.firstVisibleItemIndex)
            itemCount.value += 1
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(12, listState.firstVisibleItemIndex) }
    }

    @Test fun sameChatIdInAnotherProjectGetsItsOwnListState() {
        showChat()
        lateinit var previous: LazyListState
        compose.runOnIdle {
            previous = listState
            projectId.value = "second-project"
            itemCount.value = 40
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotSame(previous, listState)
            assertEquals(39, firstPositions.getValue("second-project/first-chat"))
        }
    }
}
