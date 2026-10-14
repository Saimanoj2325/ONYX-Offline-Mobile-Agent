package com.offlineagent.app.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineagent.core.runtime.markdown.Align
import com.offlineagent.core.runtime.markdown.CodeToken
import com.offlineagent.core.runtime.markdown.Inline
import com.offlineagent.core.runtime.markdown.Markdown
import com.offlineagent.core.runtime.markdown.MathText
import com.offlineagent.core.runtime.markdown.MdBlock
import com.offlineagent.core.runtime.markdown.SyntaxHighlighter

/** Callbacks for interactive inline elements. Links never open (the app is offline and opening URLs is not allowed). */
class MarkdownActions(
    val onCitation: (Int) -> Unit = {},
    val onLink: (String) -> Unit = {},
)

/**
 * Renders markdown as native Compose. [text] may still be streaming: unterminated code fences / math render as-is.
 * Parsing is cached per text value and costs well under 1 ms for typical answers.
 */
@Composable
fun MarkdownView(text: String, modifier: Modifier = Modifier, actions: MarkdownActions = MarkdownActions()) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { MdBlockView(it, actions) }
    }
}

@Composable
private fun MdBlockView(b: MdBlock, actions: MarkdownActions) {
    val typo = MaterialTheme.typography
    when (b) {
        is MdBlock.Heading -> Text(
            inlineString(b.inlines, actions),
            style = when (b.level) { 1 -> typo.titleLarge; 2 -> typo.titleMedium; else -> typo.titleSmall }.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(top = 4.dp),
        )
        is MdBlock.Paragraph -> Text(inlineString(b.inlines, actions), style = typo.bodyLarge)
        is MdBlock.Code -> CodeBlock(b.code, b.language)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
            Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.blocks.forEach { MdBlockView(it, actions) }
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    val marker = when {
                        item.task == true -> "☑"
                        item.task == false -> "☐"
                        b.ordered -> "${b.start + i}."
                        else -> "•"
                    }
                    Text(marker, style = typo.bodyLarge, modifier = Modifier.width(if (b.ordered) 28.dp else 18.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { item.blocks.forEach { MdBlockView(it, actions) } }
                }
            }
        }
        is MdBlock.Table -> MdTable(b, actions)
        is MdBlock.Math -> Text(
            MathText.toUnicode(b.tex),
            style = typo.bodyLarge.copy(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        MdBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
}

@Composable
fun inlineString(inlines: List<Inline>, actions: MarkdownActions): AnnotatedString {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(inlines, codeBg, linkColor) {
        buildAnnotatedString { appendInlines(inlines, actions, codeBg, linkColor) }
    }
}

private fun AnnotatedString.Builder.appendInlines(list: List<Inline>, actions: MarkdownActions, codeBg: Color, link: Color) {
    list.forEach { inl ->
        when (inl) {
            is Inline.Text -> append(inl.text)
            is Inline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlines(inl.children, actions, codeBg, link) }
            is Inline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(inl.children, actions, codeBg, link) }
            is Inline.Strike -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(inl.children, actions, codeBg, link) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)) { append(" ${inl.code} ") }
            is Inline.Math -> withStyle(SpanStyle(fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic)) { append(MathText.toUnicode(inl.tex)) }
            is Inline.Link -> withLink(LinkAnnotation.Clickable("link:" + inl.url) { actions.onLink(inl.url) }) {
                withStyle(SpanStyle(color = link, textDecoration = TextDecoration.Underline)) { appendInlines(inl.text, actions, codeBg, link) }
            }
            is Inline.Citation -> withLink(LinkAnnotation.Clickable("cite:" + inl.n) { actions.onCitation(inl.n) }) {
                withStyle(SpanStyle(color = link, fontWeight = FontWeight.Bold, baselineShift = BaselineShift.Superscript, fontSize = 11.sp)) { append("[${inl.n}]") }
            }
            Inline.LineBreak -> append("\n")
        }
    }
}

@Composable
private fun MdTable(t: MdBlock.Table, actions: MarkdownActions) {
    val widths = remember(t) {
        List(t.header.size) { c ->
            val maxChars = (listOf(t.header[c]) + t.rows.map { it.getOrElse(c) { emptyList() } }).maxOf { Markdown.plain(it).length }
            (maxChars * 8 + 24).coerceIn(64, 240).dp
        }
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, border, RoundedCornerShape(6.dp))) {
        Column {
            TableRow(t.header, widths, t.align, actions, header = true)
            t.rows.forEachIndexed { i, r ->
                HorizontalDivider(color = border)
                TableRow(r, widths, t.align, actions, header = false, shaded = i % 2 == 1)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<List<Inline>>, widths: List<androidx.compose.ui.unit.Dp>, align: List<Align>, actions: MarkdownActions, header: Boolean, shaded: Boolean = false) {
    val bg = when {
        header -> MaterialTheme.colorScheme.secondaryContainer
        shaded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        else -> Color.Transparent
    }
    Row(Modifier.background(bg).height(IntrinsicSize.Min)) {
        widths.forEachIndexed { c, w ->
            Text(
                inlineString(cells.getOrElse(c) { emptyList() }, actions),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (header) FontWeight.Bold else FontWeight.Normal),
                textAlign = when (align.getOrElse(c) { Align.LEFT }) { Align.CENTER -> TextAlign.Center; Align.RIGHT -> TextAlign.End; Align.LEFT -> TextAlign.Start },
                modifier = Modifier.width(w).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
fun CodeBlock(code: String, language: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val dark = MaterialTheme.colorScheme.surface.luminanceApprox() < 0.5f
    val colors = if (dark) CodeColors.dark else CodeColors.light
    val highlighted = remember(code, language, dark) {
        val spans = SyntaxHighlighter.highlight(code, language)
        buildAnnotatedString {
            append(code)
            spans.forEach { s -> addStyle(SpanStyle(color = colors.getValue(s.token)), s.start, s.end) }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language.ifBlank { "code" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
            }
            Text(
                highlighted,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
            )
        }
    }
}

private fun Color.luminanceApprox() = 0.299f * red + 0.587f * green + 0.114f * blue

private object CodeColors {
    val light = mapOf(
        CodeToken.PLAIN to Color(0xFF1F2328), CodeToken.KEYWORD to Color(0xFFCF222E), CodeToken.STRING to Color(0xFF0A3069),
        CodeToken.COMMENT to Color(0xFF6E7781), CodeToken.NUMBER to Color(0xFF0550AE), CodeToken.TYPE to Color(0xFF953800),
        CodeToken.FUNCTION to Color(0xFF8250DF),
    )
    val dark = mapOf(
        CodeToken.PLAIN to Color(0xFFE6EDF3), CodeToken.KEYWORD to Color(0xFFFF7B72), CodeToken.STRING to Color(0xFFA5D6FF),
        CodeToken.COMMENT to Color(0xFF8B949E), CodeToken.NUMBER to Color(0xFF79C0FF), CodeToken.TYPE to Color(0xFFFFA657),
        CodeToken.FUNCTION to Color(0xFFD2A8FF),
    )
}
