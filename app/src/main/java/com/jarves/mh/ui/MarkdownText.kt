package com.jarves.mh.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.ui.theme.PocketColors
import com.jarves.mh.ui.theme.PocketRadius
import com.jarves.mh.ui.theme.PocketShape
import com.jarves.mh.ui.theme.PocketSpacing
import com.jarves.mh.ui.theme.PocketType
import com.jarves.mh.ui.theme.emphasized
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock
    data class BulletItem(val depth: Int, val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    onRunCode: ((String) -> Unit)? = null,
) {
    val blocks = remember(markdown) { MarkdownParseCache.blocksFor(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> HeaderBlock(block)
                is MarkdownBlock.CodeBlock -> CodeSnippetBlock(block, onRunCode)
                is MarkdownBlock.BulletItem -> BulletBlock(block, color)
                is MarkdownBlock.NumberedItem -> NumberedBlock(block, color)
                is MarkdownBlock.BlockQuote -> QuoteBlock(block)
                is MarkdownBlock.HorizontalRule -> HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = PocketSpacing.xs),
                )
                is MarkdownBlock.Paragraph -> {
                    RichInlineText(
                        text = block.text,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBlock(header: MarkdownBlock.Header) {
    val style = when (header.level) {
        1 -> PocketType.title3.copy(fontWeight = FontWeight.Bold)
        2 -> PocketType.headline.copy(fontWeight = FontWeight.Bold)
        else -> PocketType.subheadline.emphasized
    }
    InlineMarkdownText(
        text = formatInlineMarkdown(header.text),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = PocketSpacing.xs),
    )
}

/**
 * Lists share one grid: a marker column of [ListMarkerWidth], then the text. Nested items move in
 * by one marker column per level, so a child always sits under its parent's text.
 */
private val ListMarkerWidth = 20.dp

@Composable
private fun BulletBlock(item: MarkdownBlock.BulletItem, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ListMarkerWidth * item.depth),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(ListMarkerWidth)
                .padding(top = PocketSpacing.sm),
        ) {
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
            )
        }
        RichInlineText(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberedBlock(item: MarkdownBlock.NumberedItem, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = item.number,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant),
            softWrap = false,
            modifier = Modifier.width(ListMarkerWidth),
        )
        RichInlineText(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuoteBlock(quote: MarkdownBlock.BlockQuote) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(24.dp)
                .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(PocketSpacing.sm))
        InlineMarkdownText(
            text = formatInlineMarkdown(quote.text),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
private fun CodeSnippetBlock(block: MarkdownBlock.CodeBlock, onRunCode: ((String) -> Unit)?) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Code is content: a solid neutral surface that follows the app theme.
    val scheme = MaterialTheme.colorScheme
    val codeShape = RoundedCornerShape(PocketRadius.md)
    Surface(
        shape = codeShape,
        color = scheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, scheme.outlineVariant, codeShape),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.language.ifBlank { "code" },
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val shellLanguage = block.language.lowercase() in setOf("", "bash", "sh", "shell", "zsh", "console", "terminal")
                    if (onRunCode != null && shellLanguage) {
                        IconButton(
                            onClick = { onRunCode(block.code.trim()) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = "Run in project terminal",
                                tint = scheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(block.code))
                            copied = true
                            scope.launch {
                                delay(2000)
                                copied = false
                            }
                        },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                            contentDescription = "Copy code",
                            tint = if (copied) scheme.tertiary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            val scroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .scrollEdgeFade(scroll)
                    .horizontalScroll(scroll)
                    .padding(PocketSpacing.md),
            ) {
                Text(
                    text = block.code,
                    style = PocketType.code,
                    color = scheme.onSurface,
                )
            }
        }
    }
}

private val CodeEdgeFade = 24.dp

/** Fades long code lines out at the edge they continue past, so a clipped line reads as scrollable. */
private fun Modifier.scrollEdgeFade(scroll: ScrollState): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = CodeEdgeFade.toPx()
        if (scroll.value < scroll.maxValue) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.value > 0) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

internal fun buildInlineMarkdown(
    text: String,
    primaryColor: Color,
    codeColor: Color,
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = text.length

        while (i < len) {
            when {
                // Inline Code: `code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        val codeContent = text.substring(i + 1, end)
                        val nextChar = text.getOrNull(end + 1)
                        val hasTrailingPunctuation = nextChar != null && nextChar in ":,.;!?)'\""
                        val trailingSpace = if (hasTrailingPunctuation) "" else " "
                        pushStringAnnotation(InlineCodeTag, CodeChipAnnotation)
                        withStyle(
                            PocketType.codeSpan.copy(
                                color = codeColor,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        ) {
                            append(" $codeContent$trailingSpace")
                        }
                        pop()
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold & Italic: ***text***
                text.startsWith("***", i) -> {
                    val end = text.indexOf("***", i + 3)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 3, end))
                        }
                        i = end + 3
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold: **text** or __text__
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *text* (avoiding single bullet at start)
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && end > i + 1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Strikethrough: ~~text~~
                text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Link: [label](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val openParen = if (closeBracket != -1) text.indexOf('(', closeBracket) else -1
                    val closeParen = if (openParen == closeBracket + 1) text.indexOf(')', openParen) else -1

                    if (closeBracket != -1 && openParen == closeBracket + 1 && closeParen != -1) {
                        val rawLabel = text.substring(i + 1, closeBracket)
                        val isCodeLabel = rawLabel.startsWith("`") && rawLabel.endsWith("`") && rawLabel.length >= 2
                        val label = if (isCodeLabel) rawLabel.removeSurrounding("`") else rawLabel

                        if (isCodeLabel) pushStringAnnotation(InlineCodeTag, LabelChipAnnotation)
                        withStyle(
                            SpanStyle(
                                color = primaryColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = if (isCodeLabel) FontWeight.SemiBold else FontWeight.Medium,
                                fontFamily = if (isCodeLabel) FontFamily.Monospace else null,
                            ),
                        ) {
                            if (isCodeLabel) append(" ")
                            append(label)
                            if (isCodeLabel) append(" ")
                        }
                        if (isCodeLabel) pop()
                        i = closeParen + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

/** Tags inline code spans so [InlineMarkdownText] can draw a rounded chip behind each. */
internal const val InlineCodeTag = "inlineCode"
private const val CodeChipAnnotation = "code"
private const val LabelChipAnnotation = "label"
private val InlineCodeRadius = 5.dp

@Composable
private fun formatInlineMarkdown(text: String): AnnotatedString {
    val codeColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary

    return remember(text, codeColor, primaryColor) {
        buildInlineMarkdown(text, primaryColor, codeColor)
    }
}

/**
 * Text with a rounded chip behind each inline code span. Chips are drawn from the laid-out glyph
 * boxes, so they wrap with the text and the string itself stays selectable and copyable.
 */
@Composable
private fun InlineMarkdownText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val chipColor = MaterialTheme.colorScheme.surfaceVariant
    var chips by remember(text) { mutableStateOf(emptyList<InlineCodeChip>()) }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.drawBehind {
            chips.forEach { chip ->
                drawRoundRect(
                    color = if (chip.label) chipColor.copy(alpha = 0.45f) else chipColor,
                    topLeft = chip.bounds.topLeft,
                    size = chip.bounds.size,
                    cornerRadius = CornerRadius(InlineCodeRadius.toPx()),
                )
            }
        },
        onTextLayout = { chips = inlineCodeChips(it, text) },
    )
}

private data class InlineCodeChip(val bounds: Rect, val label: Boolean)

/** One chip per code span per line it occupies, sized to the glyph boxes on that line. */
private fun inlineCodeChips(layout: TextLayoutResult, text: AnnotatedString): List<InlineCodeChip> {
    val chips = mutableListOf<InlineCodeChip>()
    text.getStringAnnotations(InlineCodeTag, 0, text.length).forEach { span ->
        var offset = span.start
        while (offset < span.end) {
            val line = layout.getLineForOffset(offset)
            val lineEnd = minOf(layout.getLineEnd(line), span.end)
            if (lineEnd <= offset) break
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (i in offset until lineEnd) {
                val box = layout.getBoundingBox(i)
                left = minOf(left, box.left)
                top = minOf(top, box.top)
                right = maxOf(right, box.right)
                bottom = maxOf(bottom, box.bottom)
            }
            if (right > left) chips += InlineCodeChip(Rect(left, top, right, bottom), span.item == LabelChipAnnotation)
            offset = lineEnd
        }
    }
    return chips
}

/** A piece of running text: prose with inline chips, or a long command or hash on its own. */
private sealed interface InlineSegment {
    data class Prose(val text: String) : InlineSegment
    data class Code(val code: String) : InlineSegment
}

/** Inline code longer than this leaves the sentence and becomes a monospace block of its own. */
private const val LongCodeLength = 24
private val InlineCodeSpan = Regex("`([^`\\n]+)`")

private fun splitLongCode(text: String): List<InlineSegment> {
    val segments = mutableListOf<InlineSegment>()
    var cursor = 0
    InlineCodeSpan.findAll(text).forEach { match ->
        val code = match.groupValues[1]
        if (code.length > LongCodeLength) {
            val prose = text.substring(cursor, match.range.first).trim()
            if (prose.isNotEmpty()) segments += InlineSegment.Prose(prose)
            segments += InlineSegment.Code(code)
            cursor = match.range.last + 1
        }
    }
    if (segments.isEmpty()) return listOf(InlineSegment.Prose(text))
    val rest = text.substring(cursor).trim()
    if (rest.isNotEmpty()) segments += InlineSegment.Prose(rest)
    return segments
}

/**
 * Running text. Short code stays an inline chip; a long command or hash is lifted out into a
 * [LongCodeBlock] so it wraps cleanly instead of breaking into fragments mid-sentence.
 */
@Composable
private fun RichInlineText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val segments = remember(text) { splitLongCode(text) }
    if (segments.size == 1) {
        InlineMarkdownText(text = formatInlineMarkdown(text), style = style, color = color, modifier = modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PocketSpacing.sm)) {
        segments.forEach { segment ->
            when (segment) {
                is InlineSegment.Prose -> InlineMarkdownText(text = formatInlineMarkdown(segment.text), style = style, color = color)
                is InlineSegment.Code -> LongCodeBlock(segment.code)
            }
        }
    }
}

/** A long command or hash: monospace on the code surface, wrapping rather than scrolling. */
@Composable
private fun LongCodeBlock(code: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(PocketShape.sm)
            .background(PocketColors.current.codeSurface)
            .padding(horizontal = PocketSpacing.md, vertical = PocketSpacing.sm),
    ) {
        Text(code, style = PocketType.codeSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

private val OrderedListItem = Regex("^([0-9]+[.)])\\s+(.*)")

/**
 * Parsed blocks by exact text. A row that leaves the list and comes back reuses its parse instead of
 * parsing again. A streaming message changes its text and so gets a fresh entry; old entries age out.
 * Entries are bounded by count and by total characters, so streamed prefixes cannot pile up. A text
 * larger than the whole budget is parsed but not kept.
 */
internal object MarkdownParseCache {
    internal const val MAX_ENTRIES = 128
    internal const val MAX_TOTAL_CHARS = 512 * 1024

    private val cache = LinkedHashMap<String, List<MarkdownBlock>>(MAX_ENTRIES, 0.75f, true)
    private var totalChars = 0

    /** Parses outside the lock, so concurrent misses do not queue behind each other's parse. */
    fun blocksFor(markdown: String): List<MarkdownBlock> {
        lookup(markdown)?.let { return it }
        val parsed = parseMarkdown(markdown)
        return if (markdown.length > MAX_TOTAL_CHARS) parsed else store(markdown, parsed)
    }

    @Synchronized
    private fun lookup(markdown: String): List<MarkdownBlock>? = cache[markdown]

    @Synchronized
    private fun store(markdown: String, parsed: List<MarkdownBlock>): List<MarkdownBlock> {
        // Another caller may have stored the same text while this one was parsing; keep its entry.
        cache[markdown]?.let { return it }
        cache[markdown] = parsed
        totalChars += markdown.length
        val entries = cache.entries.iterator()
        while (cache.size > MAX_ENTRIES || totalChars > MAX_TOTAL_CHARS) {
            val eldest = entries.next()
            totalChars -= eldest.key.length
            entries.remove()
        }
        return parsed
    }

    @Synchronized
    internal fun entryCount(): Int = cache.size
}

internal fun parseMarkdown(raw: String): List<MarkdownBlock> {
    val lines = raw.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var inCodeBlock = false
    var codeLang = ""
    val codeLines = mutableListOf<String>()
    val currentParagraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (currentParagraphLines.isNotEmpty()) {
            val text = currentParagraphLines.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                blocks.add(MarkdownBlock.Paragraph(text))
            }
            currentParagraphLines.clear()
        }
    }

    for (line in lines) {
        val trimmed = line.trim()
        val leadingSpaces = line.takeWhile { it.isWhitespace() }.length

        if (trimmed.startsWith("```")) {
            if (inCodeBlock) {
                blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
                codeLines.clear()
                codeLang = ""
                inCodeBlock = false
            } else {
                flushParagraph()
                inCodeBlock = true
                codeLang = trimmed.removePrefix("```").trim()
            }
            continue
        }

        if (inCodeBlock) {
            codeLines.add(line)
            continue
        }

        if (trimmed.isEmpty()) {
            flushParagraph()
            continue
        }

        // Horizontal Rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            flushParagraph()
            blocks.add(MarkdownBlock.HorizontalRule)
            continue
        }

        // Headings (#, ##, ###)
        if (trimmed.startsWith("#")) {
            flushParagraph()
            val level = trimmed.takeWhile { it == '#' }.length
            val text = trimmed.drop(level).trim()
            blocks.add(MarkdownBlock.Header(level, text))
            continue
        }

        // Blockquotes (> text)
        if (trimmed.startsWith(">")) {
            flushParagraph()
            val text = trimmed.removePrefix(">").trim()
            blocks.add(MarkdownBlock.BlockQuote(text))
            continue
        }

        // Unordered List (- item, * item, + item, • item)
        val isUnordered = trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") || trimmed.startsWith("• ")
        if (isUnordered) {
            flushParagraph()
            val indent = (leadingSpaces / 2).coerceAtMost(4)
            val text = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.BulletItem(indent, text))
            continue
        }

        // Ordered List (1. item, 2. item)
        val orderedMatch = OrderedListItem.find(trimmed)
        if (orderedMatch != null) {
            flushParagraph()
            val num = orderedMatch.groupValues[1]
            val text = orderedMatch.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(num, text))
            continue
        }

        // Indented continuation of list item or blockquote
        if (leadingSpaces >= 2 && currentParagraphLines.isEmpty() && blocks.isNotEmpty()) {
            val lastBlock = blocks.last()
            when (lastBlock) {
                is MarkdownBlock.BulletItem -> {
                    blocks[blocks.lastIndex] = lastBlock.copy(text = "${lastBlock.text}\n$trimmed")
                    continue
                }
                is MarkdownBlock.NumberedItem -> {
                    blocks[blocks.lastIndex] = lastBlock.copy(text = "${lastBlock.text}\n$trimmed")
                    continue
                }
                is MarkdownBlock.BlockQuote -> {
                    blocks[blocks.lastIndex] = lastBlock.copy(text = "${lastBlock.text}\n$trimmed")
                    continue
                }
                else -> {}
            }
        }

        // Normal paragraph text
        currentParagraphLines.add(line)
    }

    if (inCodeBlock) {
        blocks.add(MarkdownBlock.CodeBlock(codeLang, codeLines.joinToString("\n")))
    }
    flushParagraph()

    return blocks
}
