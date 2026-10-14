package com.jarves.mh.ui

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.jarves.mh.mods.ModDrawing
import com.jarves.mh.mods.ModNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

class ModPaneKeysTest {
    private fun keys(children: List<ModNode>): List<Any?> {
        val keys = mutableListOf<Any?>()
        val scope = object : LazyListScope {
            override fun items(
                count: Int,
                key: ((Int) -> Any)?,
                contentType: (Int) -> Any?,
                itemContent: @Composable LazyItemScope.(Int) -> Unit,
            ) {
                repeat(count) { keys += key?.invoke(it) }
            }
        }
        scope.modPaneItems("pane", ModDrawing(ModNode("Column", children = children)), File("."), { _, _ -> }, Modifier)
        return keys
    }

    @Test
    fun `explicit numeric keys cannot collide with unkeyed positions and stay stable on reorder`() {
        val explicit = ModNode("Divider", mapOf("key" to "1"))
        val unkeyed = ModNode("Divider")
        val original = keys(listOf(explicit, unkeyed))
        assertNotEquals(original[0], original[1])
        assertEquals(original[0], keys(listOf(unkeyed, explicit))[1])
    }
}
