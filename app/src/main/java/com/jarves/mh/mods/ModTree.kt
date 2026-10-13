package com.jarves.mh.mods

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * A layout a mod's `ui.render` hook returned (spec v2 §4.4), checked and ready to draw. The app
 * draws it with its own components: no mod script runs on screen. Function props arrive as
 * handler keys ("h12"); a tap sends the key back to the mod.
 */
@Immutable
data class ModNode(
    val type: String,
    val props: Map<String, Any?> = emptyMap(),
    val children: List<ModNode> = emptyList(),
    /** For [TEXT] nodes only. */
    val text: String? = null,
) {
    fun string(name: String): String? = props[name] as? String
    fun bool(name: String): Boolean = props[name] == true
    fun number(name: String): Double? = (props[name] as? Number)?.toDouble()
    fun handler(name: String): String? = props[name] as? String

    /** The text of a Text, Markdown, Code, Button or Chip: its `label` prop or its text children. */
    fun content(): String = string("label") ?: children.joinToString("") { it.text.orEmpty() }

    companion object {
        const val TEXT = "#text"
    }
}

class ModTreeException(message: String) : Exception(message)

object ModTree {
    const val MAX_NODES = 2_000
    const val MAX_DEPTH = 32
    const val MAX_TEXT = 100_000
    const val MAX_IMAGE_BYTES = 512 * 1024

    /** Every element a tree may use, which `$.ui.resolve(e)` hands to mods. */
    val ELEMENTS = listOf(
        "Column", "Row", "Box", "Text", "Markdown", "Code", "Button", "Toggle", "TextField",
        "Select", "Progress", "Divider", "Spacer", "Image", "List", "Chip",
    )

    private val TEXT_ONLY = setOf("Text", "Markdown", "Code", "Button", "Chip")
    private val NO_CHILDREN = setOf("Toggle", "TextField", "Select", "Progress", "Divider", "Spacer", "Image")
    private val COLORS = setOf("primary", "secondary", "tertiary", "accent", "red", "orange", "yellow", "green", "blue", "purple", "pink", "gray")
    private val ALIGN = setOf("start", "center", "end")
    private val TEXT_STYLES = setOf("largeTitle", "title", "title2", "title3", "headline", "body", "callout", "subheadline", "footnote", "caption")
    private val BUTTON_STYLES = setOf("filled", "tinted", "gray", "plain", "glass")
    private val HANDLER = Regex("^h[0-9]{1,9}$")
    private val ASSET = Regex("^[A-Za-z0-9._/-]{1,200}\\.(png|jpe?g|webp)$")
    private val DATA_IMAGE = Regex("^data:image/(png|jpeg);base64,[A-Za-z0-9+/=\\s]+$")

    private sealed interface Prop
    private data object Str : Prop
    private data object Bool : Prop
    private data class Num(val min: Double, val max: Double) : Prop
    private data class OneOf(val values: Set<String>) : Prop
    private data object Handler : Prop
    private data object Options : Prop
    private data object Src : Prop
    /** A number from 0 to 1, or null for "in progress". */
    private data object Fraction : Prop

    private val LAYOUT = mapOf(
        "gap" to Num(0.0, 48.0), "padding" to Num(0.0, 48.0), "align" to OneOf(ALIGN), "card" to Bool, "grow" to Bool,
    )
    private val COMMON = mapOf("key" to Str, "grow" to Bool)

    private val PROPS: Map<String, Map<String, Prop>> = mapOf(
        "Column" to LAYOUT,
        "Box" to LAYOUT,
        "List" to LAYOUT,
        "Row" to LAYOUT + ("scroll" to Bool),
        "Text" to mapOf(
            "label" to Str, "style" to OneOf(TEXT_STYLES), "color" to OneOf(COLORS), "bold" to Bool, "mono" to Bool,
            "maxLines" to Num(1.0, 200.0), "align" to OneOf(ALIGN),
        ),
        "Markdown" to mapOf("label" to Str),
        "Code" to mapOf("label" to Str, "format" to OneOf(setOf("plain", "diff")), "maxLines" to Num(1.0, 500.0)),
        "Button" to mapOf(
            "label" to Str, "onPress" to Handler, "style" to OneOf(BUTTON_STYLES), "destructive" to Bool, "disabled" to Bool,
        ),
        "Chip" to mapOf("label" to Str, "color" to OneOf(COLORS), "onPress" to Handler, "selected" to Bool),
        "Toggle" to mapOf("label" to Str, "value" to Bool, "onChange" to Handler, "disabled" to Bool),
        "TextField" to mapOf(
            "label" to Str, "value" to Str, "placeholder" to Str, "onChange" to Handler, "onSubmit" to Handler,
            "multiline" to Bool, "disabled" to Bool,
        ),
        "Select" to mapOf("label" to Str, "value" to Str, "options" to Options, "onChange" to Handler, "disabled" to Bool),
        "Progress" to mapOf("value" to Fraction, "label" to Str, "color" to OneOf(COLORS)),
        "Divider" to emptyMap(),
        "Spacer" to mapOf("size" to Num(0.0, 96.0)),
        "Image" to mapOf("src" to Src, "height" to Num(16.0, 480.0), "description" to Str, "fit" to OneOf(setOf("fit", "fill"))),
    )

    /**
     * Checks a tree from the mod (JSON as the shim sent it). Null means the mod draws nothing here.
     * Throws [ModTreeException] with the first reason it can't be drawn.
     */
    fun parse(value: Any?): ModNode? {
        if (value == null || value == JSONObject.NULL) return null
        val counter = Counter()
        return node(value, 1, counter, "the tree")
    }

    private class Counter {
        var nodes = 0
        var text = 0
    }

    private fun node(value: Any?, depth: Int, counter: Counter, where: String): ModNode {
        if (depth > MAX_DEPTH) fail("It nests more than $MAX_DEPTH levels deep.")
        if (++counter.nodes > MAX_NODES) fail("It has more than $MAX_NODES elements.")
        if (value is String || value is Number || value is Boolean) return textNode(value.toString(), counter)
        val o = value as? JSONObject ?: fail("$where is not an element.")
        val type = o.optString("type")
        if (type == ModNode.TEXT) return textNode(o.optString("text"), counter)
        val allowed = PROPS[type] ?: fail("\"$type\" is not an element. Use one of: ${ELEMENTS.joinToString()}.")
        val props = LinkedHashMap<String, Any?>()
        o.optJSONObject("props")?.let { given ->
            for (name in given.keys()) {
                if (name == "children") continue
                val raw = given.opt(name)
                if (raw == null || raw == JSONObject.NULL) continue
                val kind = allowed[name] ?: COMMON[name] ?: fail("$type has no \"$name\" prop.")
                props[name] = prop(type, name, kind, raw, counter)
            }
        }
        val rawChildren = o.optJSONArray("children") ?: JSONArray()
        if (type in NO_CHILDREN && rawChildren.length() > 0) fail("$type takes no children.")
        val children = (0 until rawChildren.length()).map { i ->
            val child = rawChildren.opt(i)
            val parsed = node(child, depth + 1, counter, "Child ${i + 1} of $type")
            if (type in TEXT_ONLY && parsed.type != ModNode.TEXT) fail("$type can only hold text, not ${parsed.type}.")
            parsed
        }
        return ModNode(type, props, children)
    }

    private fun textNode(text: String, counter: Counter): ModNode {
        counter.text += text.length
        if (counter.text > MAX_TEXT) fail("It has more than $MAX_TEXT characters of text.")
        return ModNode(ModNode.TEXT, text = text)
    }

    private fun prop(type: String, name: String, kind: Prop, raw: Any, counter: Counter): Any? = when (kind) {
        Str -> (raw as? String ?: raw.takeIf { it is Number }?.toString() ?: fail("$type.$name must be text.")).also {
            counter.text += it.length
            if (counter.text > MAX_TEXT) fail("It has more than $MAX_TEXT characters of text.")
        }
        Bool -> raw as? Boolean ?: fail("$type.$name must be true or false.")
        is Num -> {
            val number = (raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fail("$type.$name must be a number.")
            number.coerceIn(kind.min, kind.max)
        }
        Fraction -> ((raw as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fail("$type.$name must be a number from 0 to 1.")).coerceIn(0.0, 1.0)
        is OneOf -> (raw as? String)?.takeIf { it in kind.values } ?: fail("$type.$name must be one of: ${kind.values.joinToString()}.")
        Handler -> (raw as? String)?.takeIf { HANDLER.matches(it) } ?: fail("$type.$name must be a function.")
        Options -> {
            val array = raw as? JSONArray ?: fail("$type.options must be a list.")
            if (array.length() > 100) fail("$type.options has more than 100 choices.")
            (0 until array.length()).map { i ->
                when (val option = array.opt(i)) {
                    is String -> option to option
                    is JSONObject -> {
                        val v = option.opt("value")?.takeIf { it is String || it is Number }?.toString() ?: fail("$type.options needs a value for each choice.")
                        v to (option.optString("label").ifBlank { v })
                    }
                    else -> fail("$type.options must hold text or { value, label }.")
                }.also { (v, l) -> counter.text += v.length + l.length }
            }
        }
        Src -> {
            val src = raw as? String ?: fail("$type.src must be text.")
            when {
                src.startsWith("data:") -> {
                    if (!DATA_IMAGE.matches(src)) fail("Image.src must be a PNG or JPEG data URL, or an image file in the mod.")
                    if (src.length - src.indexOf(',') - 1 > MAX_IMAGE_BYTES / 3 * 4 + 4) fail("Image.src is larger than ${MAX_IMAGE_BYTES / 1024} KB.")
                }
                !ASSET.matches(src) || src.split('/').any { it == ".." || it == "." || it.isEmpty() } ->
                    fail("Image.src must be a PNG, JPEG or WebP file inside the mod, such as assets/logo.png.")
            }
            src
        }
    }

    private fun fail(reason: String): Nothing = throw ModTreeException(reason)
}
