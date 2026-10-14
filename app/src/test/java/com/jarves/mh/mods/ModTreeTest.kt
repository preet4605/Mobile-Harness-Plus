package com.jarves.mh.mods

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModTreeTest {

    private fun el(type: String, props: JSONObject = JSONObject(), vararg children: Any): JSONObject =
        JSONObject().put("type", type).put("props", props).put("children", JSONArray(children.map {
            if (it is String) JSONObject().put("type", "#text").put("text", it) else it
        }))

    private fun refusal(tree: Any?): String = runCatching { ModTree.parse(tree) }.exceptionOrNull()?.message ?: "accepted"

    @Test
    fun `a valid tree keeps its props, handlers and text`() {
        val tree = ModTree.parse(el(
            "Column", JSONObject().put("gap", 12).put("card", true),
            el("Text", JSONObject().put("style", "title2").put("color", "green"), "Hello ", "there"),
            el("Button", JSONObject().put("onPress", "h1").put("style", "tinted"), "Go"),
            el("Select", JSONObject().put("options", JSONArray().put("a").put(JSONObject().put("value", 2).put("label", "Two"))).put("onChange", "h2")),
            el("Progress", JSONObject().put("value", 1.7)),
            el("Image", JSONObject().put("src", "assets/chart.png").put("height", 2000)),
        ))!!
        assertEquals("Column", tree.type)
        assertEquals(12.0, tree.number("gap"))
        assertEquals("Hello there", tree.children[0].content())
        assertEquals("h1", tree.children[1].handler("onPress"))
        assertEquals(listOf("a" to "a", "2" to "Two"), tree.children[2].props["options"])
        assertEquals(1.0, tree.children[3].number("value"))
        assertEquals(480.0, tree.children[4].number("height"))
        assertNull(ModTree.parse(JSONObject.NULL))
    }

    @Test
    fun `select options share the tree text limit`() {
        fun select(vararg choices: Any) = el("Select", JSONObject().put("options", JSONArray(choices.toList())))
        val exact = "x".repeat(ModTree.MAX_TEXT / 2)
        assertEquals(listOf(exact to exact), ModTree.parse(select(exact))!!.props["options"])
        val reason = "It has more than ${ModTree.MAX_TEXT} characters of text."
        assertEquals(reason, refusal(select(exact + "x")))
        assertEquals(reason, refusal(select(JSONObject().put("value", "v").put("label", "x".repeat(ModTree.MAX_TEXT)))))
        assertEquals(reason, refusal(select(exact, "x")))
        assertEquals(reason, refusal(el("Column", JSONObject(), el("Text", JSONObject(), "x"), select(exact))))
        assertEquals(reason, refusal(el("Column", JSONObject(), select(exact), select("x"))))
    }

    @Test
    fun `duplicate explicit sibling keys are refused but keys in separate parents are allowed`() {
        fun keyed(key: String) = el("Divider", JSONObject().put("key", key))
        assertEquals("Column has duplicate child key \"same\".", refusal(el("Column", JSONObject(), keyed("same"), keyed("same"))))
        assertEquals("List has duplicate child key \"same\".", refusal(el("List", JSONObject(), keyed("same"), keyed("same"))))
        assertEquals("accepted", refusal(el("Column", JSONObject(), keyed("1"), el("Divider"))))
        assertEquals("accepted", refusal(el("Column", JSONObject(), el("Column", JSONObject(), keyed("same")), el("Column", JSONObject(), keyed("same")))))
    }

    @Test
    fun `trees the app can't draw are refused with the reason`() {
        assertEquals("\"Blink\" is not an element. Use one of: ${ModTree.ELEMENTS.joinToString()}.", refusal(el("Blink")))
        assertEquals("Text has no \"colour\" prop.", refusal(el("Text", JSONObject().put("colour", "red"), "x")))
        assertEquals("Text can only hold text, not Button.", refusal(el("Text", JSONObject(), el("Button"))))
        assertEquals("Button.onPress must be a function.", refusal(el("Button", JSONObject().put("onPress", "alert(1)"))))
        assertEquals("Toggle takes no children.", refusal(el("Toggle", JSONObject(), "x")))
        assertTrue(refusal(el("Image", JSONObject().put("src", "../secret.png"))).startsWith("Image.src must be"))
        assertTrue(refusal(el("Image", JSONObject().put("src", "https://example.com/a.png"))).startsWith("Image.src must be"))
        val big = "data:image/png;base64," + "A".repeat(ModTree.MAX_IMAGE_BYTES / 3 * 4 + 100)
        assertTrue(refusal(el("Image", JSONObject().put("src", big))).contains("larger than"))

        var deep = el("Text", JSONObject(), "x")
        repeat(ModTree.MAX_DEPTH) { deep = el("Column", JSONObject(), deep) }
        assertEquals("It nests more than ${ModTree.MAX_DEPTH} levels deep.", refusal(deep))
        val wide = el("List", JSONObject(), *Array(ModTree.MAX_NODES) { el("Divider") })
        assertEquals("It has more than ${ModTree.MAX_NODES} elements.", refusal(wide))
        val long = el("Text", JSONObject(), "x".repeat(ModTree.MAX_TEXT + 1))
        assertEquals("It has more than ${ModTree.MAX_TEXT} characters of text.", refusal(long))
    }
}
