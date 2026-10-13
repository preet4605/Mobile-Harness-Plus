package com.jarves.mh.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.jarves.mh.mods.ModDrawing
import com.jarves.mh.mods.ModHost
import com.jarves.mh.mods.ModNode
import com.jarves.mh.mods.ModPlace
import com.jarves.mh.ui.kit.PocketButton
import com.jarves.mh.ui.kit.PocketButtonSize
import com.jarves.mh.ui.kit.PocketButtonStyle
import com.jarves.mh.ui.kit.PocketTextField
import com.jarves.mh.ui.kit.PocketToggle
import com.jarves.mh.ui.kit.ProgressRing
import com.jarves.mh.ui.kit.SegmentedControl
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Mod surfaces (spec v2 §4.4): trees a mod's `ui.render` hooks return, drawn with the app's own
 * components. Nothing here runs mod code; a tap or an edit goes back to the mod as a handler key.
 */

/** Registers [holder] as showing [surface] of [modId] while this is on screen; the host draws only shown surfaces. */
@Composable
internal fun ShowModSurface(host: ModHost, modId: String, surface: String, holder: String) {
    DisposableEffect(host, modId, surface, holder) {
        host.show(modId, surface, holder, true)
        onDispose { host.show(modId, surface, holder, false) }
    }
}

/** A drawn tree, or the reason there is none. [empty] is shown when the mod draws nothing. */
@Composable
internal fun ModDrawingView(
    drawing: ModDrawing?,
    dir: File,
    onEvent: (String, Any?) -> Unit,
    modifier: Modifier = Modifier,
    empty: String? = "Nothing to show yet.",
) {
    val colors = PocketColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
        when {
            drawing == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
                ProgressRing(null, size = 18.dp, strokeWidth = 2.dp)
                Text("Drawing…", style = PocketType.footnote, color = colors.secondaryLabel)
            }
            drawing.tree != null -> ModElement(drawing.tree, dir, onEvent)
            drawing.message == null && empty != null -> Text(empty, style = PocketType.footnote, color = colors.secondaryLabel)
        }
        drawing?.message?.let { Text(it, style = PocketType.footnote, color = if (drawing.drawn) colors.orange else colors.secondaryLabel) }
    }
}

/**
 * A pane tree as lazy list items: a Column or List at the top becomes one item per child, so long
 * dashboards scroll smoothly inside the page's list.
 */
internal fun LazyListScope.modPaneItems(
    keyPrefix: String,
    drawing: ModDrawing?,
    dir: File,
    onEvent: (String, Any?) -> Unit,
    itemModifier: Modifier,
) {
    val tree = drawing?.tree
    if (tree != null && (tree.type == "Column" || tree.type == "List") && !tree.bool("card") && drawing.message == null) {
        val gap = (tree.number("gap") ?: 8.0).dp
        itemsIndexed(tree.children, key = { i, child -> "$keyPrefix:${child.string("key") ?: i}" }) { i, child ->
            ModElement(child, dir, onEvent, itemModifier.padding(top = if (i == 0) 0.dp else gap))
        }
    } else {
        item(key = "$keyPrefix:single") { ModDrawingView(drawing, dir, onEvent, itemModifier) }
    }
}

@Composable
private fun ModElement(node: ModNode, dir: File, onEvent: (String, Any?) -> Unit, modifier: Modifier = Modifier) {
    val colors = PocketColors.current
    when (node.type) {
        ModNode.TEXT -> Text(node.text.orEmpty(), modifier, style = PocketType.body, color = colors.label)
        "Column", "List", "Box" -> {
            val align = when (node.string("align")) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
            Column(
                container(node, modifier),
                verticalArrangement = Arrangement.spacedBy((node.number("gap") ?: 8.0).dp),
                horizontalAlignment = align,
            ) {
                // `grow` applies in a Row only: a column's height is open-ended while the page scrolls.
                node.children.forEach { child -> ModElement(child, dir, onEvent) }
            }
        }
        "Row" -> {
            val align = when (node.string("align")) { "start" -> Alignment.Top; "end" -> Alignment.Bottom; else -> Alignment.CenterVertically }
            val scroll = if (node.bool("scroll")) Modifier.horizontalScroll(rememberScrollState()) else Modifier
            Row(
                container(node, modifier).then(scroll),
                horizontalArrangement = Arrangement.spacedBy((node.number("gap") ?: 8.0).dp),
                verticalAlignment = align,
            ) {
                node.children.forEach { child ->
                    ModElement(child, dir, onEvent, if (child.bool("grow") && !node.bool("scroll")) Modifier.weight(1f) else Modifier)
                }
            }
        }
        "Text" -> Text(
            node.content(),
            modifier,
            style = textStyle(node.string("style")).let { style ->
                var s = style
                if (node.bool("bold")) s = s.copy(fontWeight = FontWeight.SemiBold)
                if (node.bool("mono")) s = s.copy(fontFamily = FontFamily.Monospace)
                s
            },
            color = color(node.string("color")) ?: colors.label,
            maxLines = node.number("maxLines")?.toInt() ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            textAlign = when (node.string("align")) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> null },
        )
        "Markdown" -> MarkdownText(node.content(), modifier)
        "Code" -> {
            val text = node.content()
            val diff = node.string("format") == "diff"
            val shown = remember(text, diff) { if (diff) diffText(text, colors.green, colors.red, colors.secondaryLabel) else AnnotatedString(text) }
            Box(
                modifier
                    .fillMaxWidth()
                    .clip(PocketShape.md)
                    .background(colors.codeSurface)
                    .horizontalScroll(rememberScrollState())
                    .padding(PocketSpacing.md),
            ) {
                Text(shown, style = PocketType.codeSmall, color = colors.label, maxLines = node.number("maxLines")?.toInt() ?: Int.MAX_VALUE)
            }
        }
        "Button" -> {
            val handler = node.handler("onPress")
            PocketButton(
                text = node.content().ifBlank { "Button" },
                onClick = { handler?.let { onEvent(it, null) } },
                modifier = modifier,
                style = when (node.string("style")) {
                    "tinted" -> PocketButtonStyle.Tinted
                    "gray" -> PocketButtonStyle.Gray
                    "plain" -> PocketButtonStyle.Plain
                    "glass" -> PocketButtonStyle.Glass
                    else -> PocketButtonStyle.Filled
                },
                size = PocketButtonSize.Small,
                enabled = handler != null && !node.bool("disabled"),
                destructive = node.bool("destructive"),
            )
        }
        "Chip" -> {
            val handler = node.handler("onPress")
            val tint = color(node.string("color")) ?: MaterialTheme.colorScheme.primary
            val selected = node.bool("selected")
            Text(
                node.content(),
                modifier
                    .clip(PocketShape.capsule)
                    .background(if (selected) tint else tint.copy(alpha = 0.14f))
                    .then(if (handler != null) Modifier.clickable { onEvent(handler, null) } else Modifier)
                    .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.xs + 2.dp),
                style = PocketType.footnote.copy(fontWeight = FontWeight.Medium),
                color = if (selected) Color.White else tint,
                maxLines = 1,
            )
        }
        "Toggle" -> {
            val handler = node.handler("onChange")
            var checked by remember(node.props["value"]) { mutableStateOf(node.bool("value")) }
            Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(node.string("label").orEmpty(), Modifier.weight(1f), style = PocketType.body, color = colors.label)
                PocketToggle(
                    checked = checked,
                    onCheckedChange = { value ->
                        checked = value
                        handler?.let { onEvent(it, value) }
                    },
                    enabled = handler != null && !node.bool("disabled"),
                    contentDescription = node.string("label"),
                )
            }
        }
        "TextField" -> {
            val onChange = node.handler("onChange")
            val onSubmit = node.handler("onSubmit")
            var text by remember(node.props["value"]) { mutableStateOf(node.string("value").orEmpty()) }
            var edited by remember { mutableStateOf(false) }
            // Edits reach the mod after a short pause, not on every key.
            LaunchedEffect(text, edited) {
                if (!edited || onChange == null) return@LaunchedEffect
                delay(400)
                onEvent(onChange, text)
            }
            val multiline = node.bool("multiline")
            PocketTextField(
                value = text,
                onValueChange = {
                    text = it.take(10_000)
                    edited = true
                },
                modifier = modifier,
                placeholder = node.string("placeholder").orEmpty(),
                label = node.string("label"),
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                enabled = !node.bool("disabled") && (onChange != null || onSubmit != null),
                keyboardOptions = KeyboardOptions(imeAction = if (multiline) ImeAction.Default else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit?.let { onEvent(it, text) } }),
            )
        }
        "Select" -> {
            val handler = node.handler("onChange")
            @Suppress("UNCHECKED_CAST")
            val options = node.props["options"] as? List<Pair<String, String>> ?: emptyList()
            var value by remember(node.props["value"]) { mutableStateOf(node.string("value")) }
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs)) {
                node.string("label")?.let { Text(it, style = PocketType.footnote, color = colors.secondaryLabel) }
                val pick: (String) -> Unit = { v ->
                    value = v
                    if (handler != null) onEvent(handler, v)
                }
                if (options.size in 1..4 && options.all { it.second.length <= 16 }) {
                    SegmentedControl(
                        items = options.map { it.first },
                        selected = value ?: options.first().first,
                        onSelect = pick,
                        label = { v -> options.firstOrNull { it.first == v }?.second ?: v },
                        enabled = handler != null && !node.bool("disabled"),
                    )
                } else {
                    Column(Modifier.fillMaxWidth().clip(PocketShape.lg).background(colors.groupedSurface)) {
                        options.forEach { (v, label) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (handler != null && !node.bool("disabled")) Modifier.clickable { pick(v) } else Modifier)
                                    .padding(horizontal = PocketSpacing.lg, vertical = PocketSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(label, Modifier.weight(1f), style = PocketType.body, color = colors.label)
                                if (v == value) Text("✓", style = PocketType.body, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        "Progress" -> {
            val fraction = node.number("value")?.toFloat()
            val tint = color(node.string("color")) ?: MaterialTheme.colorScheme.primary
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs)) {
                node.string("label")?.let { Text(it, style = PocketType.footnote, color = colors.secondaryLabel) }
                if (fraction == null) {
                    ProgressRing(null, size = 20.dp, strokeWidth = 2.dp, color = tint)
                } else {
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(PocketShape.capsule).background(colors.fill)) {
                        Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(PocketShape.capsule).background(tint))
                    }
                }
            }
        }
        "Divider" -> HorizontalDivider(modifier, color = colors.separator)
        "Spacer" -> Spacer(modifier.height((node.number("size") ?: 8.0).dp))
        "Image" -> {
            val src = node.string("src").orEmpty()
            val bitmap by produceState<ImageBitmap?>(null, dir, src) { value = withContext(Dispatchers.IO) { loadImage(dir, src) } }
            val height = (node.number("height") ?: 160.0).dp
            bitmap?.let {
                Image(
                    it,
                    contentDescription = node.string("description"),
                    modifier = modifier.fillMaxWidth().heightIn(max = height).clip(PocketShape.md),
                    contentScale = if (node.string("fit") == "fill") ContentScale.Crop else ContentScale.Fit,
                )
            } ?: Box(modifier.fillMaxWidth().height(height).clip(PocketShape.md).background(colors.fill))
        }
        else -> Unit
    }
}

@Composable
private fun container(node: ModNode, modifier: Modifier): Modifier {
    val padding = node.number("padding")
    return if (node.bool("card")) {
        modifier.fillMaxWidth().clip(PocketShape.lg).background(PocketColors.current.groupedSurface).padding((padding ?: 12.0).dp)
    } else {
        modifier.padding((padding ?: 0.0).dp)
    }
}

private fun textStyle(name: String?): TextStyle = when (name) {
    "largeTitle" -> PocketType.largeTitle
    "title" -> PocketType.title1
    "title2" -> PocketType.title2
    "title3" -> PocketType.title3
    "headline" -> PocketType.headline
    "callout" -> PocketType.callout
    "subheadline" -> PocketType.subheadline
    "footnote" -> PocketType.footnote
    "caption" -> PocketType.caption1
    else -> PocketType.body
}

@Composable
private fun color(name: String?): Color? {
    val colors = PocketColors.current
    return when (name) {
        "primary" -> colors.label
        "secondary" -> colors.secondaryLabel
        "tertiary" -> colors.tertiaryLabel
        "accent" -> MaterialTheme.colorScheme.primary
        "red" -> colors.red
        "orange" -> colors.orange
        "yellow" -> colors.yellow
        "green" -> colors.green
        "blue" -> colors.blue
        "purple" -> colors.purple
        "pink" -> colors.pink
        "gray" -> colors.gray
        else -> null
    }
}

private fun diffText(text: String, added: Color, removed: Color, meta: Color): AnnotatedString = buildAnnotatedString {
    text.lines().forEachIndexed { i, line ->
        if (i > 0) append('\n')
        val tint = when {
            line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@") -> meta
            line.startsWith("+") -> added
            line.startsWith("-") -> removed
            else -> null
        }
        if (tint == null) append(line) else withStyle(SpanStyle(color = tint)) { append(line) }
    }
}

/** A PNG/JPEG data URL or an image file inside the mod's folder, scaled down to at most 1024 px. */
private fun loadImage(dir: File, src: String): ImageBitmap? = runCatching {
    val bytes = if (src.startsWith("data:")) {
        Base64.decode(src.substringAfter(','), Base64.DEFAULT)
    } else {
        val base = dir.canonicalFile
        val file = File(base, src).canonicalFile
        if (!file.toPath().startsWith(base.toPath()) || !file.isFile || file.length() > 4L * 1024 * 1024) return null
        file.readBytes()
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1024) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** True when a mod has a panel switched on for the workspace's Mods tab. */
@Composable
internal fun rememberModPanesAvailable(): Boolean {
    val host = ModHost.current.collectAsState().value ?: return false
    // Only the yes/no reaches the workspace, not every redraw of a mod's panes.
    val available = remember(host) {
        host.surfaces.map { all -> all.values.any { ModPlace.WORKSPACE in it.shownIn && it.panes.isNotEmpty() } }.distinctUntilChanged()
    }
    return available.collectAsState(false).value
}

/**
 * Status chips and rows of mods switched on for projects, above the message box (spec v2 §4.3
 * band and status). Draws nothing when no mod shows here.
 */
@Composable
internal fun ModProjectStrip(modifier: Modifier = Modifier) {
    val host = ModHost.current.collectAsState().value ?: return
    val all by host.surfaces.collectAsState()
    val chips = all.values.filter { ModPlace.STATUS in it.shownIn && !it.status.isNullOrBlank() }
    val bands = all.values.filter { ModPlace.BAND in it.shownIn }
    if (chips.isEmpty() && bands.isEmpty()) return
    val colors = PocketColors.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PocketSpacing.xs)) {
        if (chips.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(PocketSpacing.xs)) {
                chips.forEach { mod ->
                    Text(
                        "${mod.name} · ${mod.status}",
                        Modifier
                            .clip(PocketShape.capsule)
                            .background(colors.groupedSurface)
                            .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.xs),
                        style = PocketType.caption1,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
        }
        bands.forEach { mod ->
            key(mod.modId) {
                ShowModSurface(host, mod.modId, ModHost.BAND, BAND_HOLDER)
                val tree = mod.drawings[ModHost.BAND]?.tree
                if (tree != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(PocketShape.lg)
                            .background(colors.groupedSurface)
                            .verticalScroll(rememberScrollState())
                            .padding(PocketSpacing.md),
                    ) {
                        ModElement(tree, mod.dir, { handler, value -> host.press(mod.modId, ModHost.BAND, handler, value) })
                    }
                }
            }
        }
    }
}

/** The workspace's Mods tab: panels mods show in projects, one at a time. */
@Composable
internal fun ModWorkspacePanes(modifier: Modifier = Modifier) {
    val host = ModHost.current.collectAsState().value ?: return
    val all by host.surfaces.collectAsState()
    val choices = all.values.filter { ModPlace.WORKSPACE in it.shownIn }.flatMap { mod -> mod.panes.map { mod to it } }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val current = choices.firstOrNull { (mod, pane) -> "${mod.modId}/${pane.id}" == chosen } ?: choices.firstOrNull()
    val colors = PocketColors.current
    if (current == null) {
        Box(modifier.fillMaxSize().padding(PocketSpacing.lg)) {
            Text("No mod panels are switched on for projects.", style = PocketType.footnote, color = colors.secondaryLabel)
        }
        return
    }
    val (mod, pane) = current
    ShowModSurface(host, mod.modId, pane.surface, PANE_HOLDER)
    val onEvent: (String, Any?) -> Unit = { handler, value -> host.press(mod.modId, pane.surface, handler, value) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = PocketSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
    ) {
        if (choices.size > 1) {
            item(key = "choices") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = PocketSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(PocketSpacing.sm),
                ) {
                    choices.forEach { (m, p) ->
                        val id = "${m.modId}/${p.id}"
                        PocketButton(
                            "${m.name} · ${p.title}",
                            { chosen = id },
                            style = if (m.modId == mod.modId && p.id == pane.id) PocketButtonStyle.Filled else PocketButtonStyle.Gray,
                            size = PocketButtonSize.Small,
                        )
                    }
                }
            }
        } else {
            item(key = "title") {
                Text("${mod.name} · ${pane.title}", Modifier.padding(horizontal = PocketSpacing.lg), style = PocketType.headline, color = colors.label)
            }
        }
        modPaneItems("ws-${mod.modId}-${pane.id}", mod.drawings[pane.surface], mod.dir, onEvent, Modifier.padding(horizontal = PocketSpacing.lg))
    }
}

private const val BAND_HOLDER = "workspace-band"
private const val PANE_HOLDER = "workspace-pane"
