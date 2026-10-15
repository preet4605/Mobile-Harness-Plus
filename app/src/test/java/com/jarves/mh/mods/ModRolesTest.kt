package com.jarves.mh.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModRolesTest {
    @get:Rule val temp = TemporaryFolder()

    private val coder = ModRole("coder", "Coder", "codex", "gpt-5-codex", "high")

    @Test
    fun `picks apply over the mod's role, and another harness drops its model and effort`() {
        assertEquals(coder, ModRoles.apply(coder, null))
        assertEquals(coder.copy(model = "o3"), ModRoles.apply(coder, ModRoles.Choice(model = "o3")))
        assertEquals(ModRole("coder", "Coder", "claude"), ModRoles.apply(coder, ModRoles.Choice(harness = "claude")))
        assertEquals(ModRole("coder", "Coder", "claude", "opus", null), ModRoles.apply(coder, ModRoles.Choice(harness = "claude", model = "opus")))
    }

    @Test
    fun `picks and role chats are kept in the mod's data folder`() {
        val dir = { id: String -> File(temp.root, id) }
        val roles = ModRoles(dir)
        roles.setChoice("a.mod", "coder", ModRoles.Choice(harness = "claude"))
        roles.setChat("a.mod", "p1", "coder", "chat-1")
        roles.setChat("a.mod", "p2", "coder", "chat-2")
        assertEquals("claude", roles.choices.value["a.mod"]?.get("coder")?.harness)

        val again = ModRoles(dir)
        assertEquals("claude", again.effective("a.mod", coder).harness)
        assertEquals("chat-1", again.chat("a.mod", "p1", "coder"))
        assertEquals("chat-2", again.chat("a.mod", "p2", "coder"))
        assertNull(again.chat("a.mod", "p1", "reviewer"))
        again.setChoice("a.mod", "coder", ModRoles.Choice())
        assertEquals(coder, ModRoles(dir).effective("a.mod", coder))
    }
}
