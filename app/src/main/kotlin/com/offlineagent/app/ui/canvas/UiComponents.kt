@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.offlineagent.app.ui.canvas

import android.graphics.BitmapFactory
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineagent.app.ui.markdown.CodeBlock
import com.offlineagent.app.ui.markdown.MarkdownView
import com.offlineagent.core.runtime.ui.UiBlock
import com.offlineagent.core.runtime.ui.UiDoc
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** Interaction hooks for canvas components. */
class UiActions(
    /** An action button was tapped: send its prompt as a new user message. */
    val onPrompt: (String) -> Unit = {},
    /** Resolves "attachment:N" image refs to files the user attached in this chat. */
    val resolveImage: (String) -> File? = { null },
)

/** Renders a whole document (title + blocks). */
@Composable
fun UiDocView(doc: UiDoc, actions: UiActions, modifier: Modifier = Modifier, showTitle: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle && doc.title.isNotBlank()) Text(doc.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        doc.blocks.forEachIndexed { i, b -> UiBlockView(b, actions, key = "$i-${b.type}") }
    }
}

@Composable
fun UiBlockView(b: UiBlock, actions: UiActions, key: String = "") {
    when (b) {
        is UiBlock.Markdown -> MarkdownView(b.text)
        is UiBlock.Heading -> Text(
            b.text, fontWeight = FontWeight.Bold,
            style = when (b.level) { 1 -> MaterialTheme.typography.titleLarge; 2 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.titleSmall },
        )
        is UiBlock.Callout -> Callout(b)
        is UiBlock.KeyValue -> KeyValueCard(b)
        is UiBlock.Table -> TableView(b.title, b.columns, b.rows)
        is UiBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEachIndexed { i, s ->
                Row { Text(if (b.ordered) "${i + 1}." else "•", modifier = Modifier.width(26.dp)); MarkdownView(s) }
            }
        }
        is UiBlock.Checklist -> ChecklistView(b, key)
        is UiBlock.Steps -> StepsView(b)
        is UiBlock.Chart -> ChartView(b)
        is UiBlock.Code -> CodeBlock(b.code, b.language)
        is UiBlock.Flashcards -> FlashcardsView(b)
        is UiBlock.Quiz -> QuizView(b, key)
        is UiBlock.Image -> ImageBlock(b, actions)
        is UiBlock.Comparison -> ComparisonView(b)
        is UiBlock.Actions -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.buttons.forEach { a -> AssistChip(onClick = { actions.onPrompt(a.prompt) }, label = { Text(a.label) }) }
        }
    }
}

@Composable
private fun SectionTitle(t: String) { if (t.isNotBlank()) Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp)) }

@Composable
private fun Callout(b: UiBlock.Callout) {
    val (bg, fg, icon) = when (b.tone) {
        "warning" -> Triple(Color(0xFFFFF4E5), Color(0xFF8A4B00), "⚠")
        "success" -> Triple(Color(0xFFE7F6EC), Color(0xFF1B5E20), "✓")
        "tip" -> Triple(Color(0xFFEDE7F6), Color(0xFF4527A0), "💡")
        else -> Triple(Color(0xFFE3F2FD), Color(0xFF0D47A1), "ℹ")
    }
    Surface(color = bg, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp)) {
            Text(icon, color = fg, fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Column {
                if (b.title.isNotBlank()) Text(b.title, color = fg, fontWeight = FontWeight.Bold)
                Text(b.text, color = fg)
            }
        }
    }
}

@Composable
private fun KeyValueCard(b: UiBlock.KeyValue) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SectionTitle(b.title)
            b.items.forEachIndexed { i, (k, v) ->
                if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row {
                    Text(k, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f))
                    Text(v, modifier = Modifier.weight(0.6f))
                }
            }
        }
    }
}

@Composable
fun TableView(title: String, columns: List<String>, rows: List<List<String>>) {
    val widths = remember(columns, rows) {
        columns.indices.map { c -> ((listOf(columns[c]) + rows.map { it.getOrElse(c) { "" } }).maxOf { it.length } * 8 + 24).coerceIn(70, 220).dp }
    }
    val border = MaterialTheme.colorScheme.outlineVariant
    Column {
        SectionTitle(title)
        Box(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, border, RoundedCornerShape(8.dp))) {
            Column {
                Row(Modifier.background(MaterialTheme.colorScheme.secondaryContainer).height(IntrinsicSize.Min)) {
                    columns.forEachIndexed { c, h -> Text(h, fontWeight = FontWeight.Bold, modifier = Modifier.width(widths[c]).padding(8.dp)) }
                }
                rows.forEachIndexed { i, r ->
                    HorizontalDivider(color = border)
                    Row(Modifier.background(if (i % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else Color.Transparent).height(IntrinsicSize.Min)) {
                        columns.indices.forEach { c -> Text(r.getOrElse(c) { "" }, modifier = Modifier.width(widths[c]).padding(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistView(b: UiBlock.Checklist, key: String) {
    val checked = remember(key, b) { mutableStateListOf(*b.items.map { it.done }.toTypedArray()) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            if (b.title.isNotBlank()) Text("${b.title}  (${checked.count { it }}/${b.items.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(8.dp))
            b.items.forEachIndexed { i, item ->
                Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = checked[i], onCheckedChange = { checked[i] = it })
                    Text(item.text, style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = if (checked[i]) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                    ))
                }
            }
        }
    }
}

@Composable
private fun StepsView(b: UiBlock.Steps) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column {
        SectionTitle(b.title)
        b.items.forEachIndexed { i, s ->
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(36.dp)) {
                    Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    if (i < b.items.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(line))
                }
                Column(Modifier.padding(start = 8.dp, bottom = 14.dp)) {
                    Text(s.title, fontWeight = FontWeight.SemiBold)
                    if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val palette = listOf(Color(0xFF4F7CFF), Color(0xFFFF8A3D), Color(0xFF2EB67D), Color(0xFFE5484D), Color(0xFF9B5DE5), Color(0xFF00B8D9), Color(0xFFF2C94C), Color(0xFF7D8590))

@Composable
private fun ChartView(b: UiBlock.Chart) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = onSurface)
    Column(Modifier.semantics { contentDescription = "${b.kind} chart ${b.title}: " + b.labels.indices.joinToString { i -> b.labels[i] + " " + b.series.joinToString("/") { s -> fmt(s.values.getOrElse(i) { 0.0 }) } } }) {
        SectionTitle(b.title)
        if (b.kind == "pie") {
            val values = b.labels.indices.map { i -> b.series.firstOrNull()?.values?.getOrElse(i) { 0.0 }?.coerceAtLeast(0.0) ?: 0.0 }
            val total = values.sum().takeIf { it > 0 } ?: 1.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Canvas(Modifier.size(160.dp)) {
                    var start = -90f
                    values.forEachIndexed { i, v ->
                        val sweep = (v / total * 360f).toFloat()
                        drawArc(palette[i % palette.size], start, sweep, useCenter = true, size = Size(size.minDimension, size.minDimension))
                        start += sweep
                    }
                }
                Column(Modifier.padding(start = 12.dp)) {
                    b.labels.forEachIndexed { i, l ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(palette[i % palette.size], CircleShape))
                            Text("  $l — ${fmt(values[i])} (${"%.0f".format(values[i] / total * 100)}%)", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else BarLineChart(b)
    }
}

@Composable
private fun BarLineChart(b: UiBlock.Chart) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = onSurface)
    Column {
        val all = b.series.flatMap { it.values }
        val maxV = max(all.maxOrNull() ?: 1.0, 0.0).let { if (it == 0.0) 1.0 else it }
        val minV = min(all.minOrNull() ?: 0.0, 0.0)
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val left = 40f; val bottom = size.height - 28f; val top = 8f; val right = size.width - 8f
            val h = bottom - top
            fun y(v: Double) = (bottom - (v - minV) / (maxV - minV) * h).toFloat()
            // grid + y labels
            for (k in 0..4) {
                val v = minV + (maxV - minV) * k / 4
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(right, yy), strokeWidth = 1f)
                val tl = measurer.measure(fmt(v), labelStyle)
                drawText(tl, topLeft = Offset(left - tl.size.width - 4f, yy - tl.size.height / 2))
            }
            val n = b.labels.size.coerceAtLeast(1)
            val slot = (right - left) / n
            b.labels.forEachIndexed { i, l ->
                val tl = measurer.measure(l, labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = max(1, slot.toInt())))
                drawText(tl, topLeft = Offset(left + slot * i + (slot - tl.size.width) / 2, bottom + 6f))
            }
            if (b.kind == "line") {
                b.series.forEachIndexed { si, s ->
                    val p = Path()
                    s.values.forEachIndexed { i, v ->
                        val x = left + slot * i + slot / 2
                        if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v))
                        drawCircle(palette[si % palette.size], 4f, Offset(x, y(v)))
                    }
                    drawPath(p, palette[si % palette.size], style = Stroke(width = 4f))
                }
            } else {
                val ns = b.series.size.coerceAtLeast(1)
                val barW = slot * 0.8f / ns
                b.series.forEachIndexed { si, s ->
                    s.values.forEachIndexed { i, v ->
                        val x = left + slot * i + slot * 0.1f + barW * si
                        val y0 = y(0.0); val y1 = y(v)
                        drawRect(palette[si % palette.size], Offset(x, min(y0, y1)), Size(barW * 0.9f, kotlin.math.abs(y0 - y1)))
                    }
                }
            }
        }
        if (b.series.size > 1 || b.kind == "line") Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            b.series.forEachIndexed { si, s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(palette[si % palette.size], CircleShape))
                    Text(" ${s.name}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun fmt(d: Double) = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e9) d.toLong().toString() else "%.2f".format(d)

@Composable
private fun FlashcardsView(b: UiBlock.Flashcards) {
    var index by rememberSaveable(b) { mutableIntStateOf(0) }
    var flipped by rememberSaveable(b, index) { mutableStateOf(false) }
    val card = b.cards.getOrNull(index) ?: return
    Column {
        SectionTitle(b.title)
        Card(
            Modifier.fillMaxWidth().heightIn(min = 140.dp).clickable { flipped = !flipped }.animateContentSize(),
            colors = CardDefaults.cardColors(containerColor = if (flipped) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(16.dp), contentAlignment = Alignment.Center) {
                Text(if (flipped) card.back else card.front, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { index = (index - 1 + b.cards.size) % b.cards.size }, enabled = b.cards.size > 1) { Text("‹ Prev") }
            Spacer(Modifier.weight(1f))
            Text("${index + 1} / ${b.cards.size} · tap to flip", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { index = (index + 1) % b.cards.size }, enabled = b.cards.size > 1) { Text("Next ›") }
        }
    }
}

@Composable
private fun QuizView(b: UiBlock.Quiz, key: String) {
    val answers = remember(key, b) { mutableStateListOf(*Array(b.questions.size) { -1 }) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(b.title)
        b.questions.forEachIndexed { qi, q ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${qi + 1}. ${q.question}", fontWeight = FontWeight.SemiBold)
                    q.options.forEachIndexed { oi, o ->
                        val chosen = answers[qi]
                        val color = when {
                            chosen < 0 -> Color.Unspecified
                            oi == q.answer -> Color(0xFF2E7D32)
                            oi == chosen -> Color(0xFFC62828)
                            else -> Color.Unspecified
                        }
                        Row(Modifier.fillMaxWidth().clickable(enabled = chosen < 0) { answers[qi] = oi }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == oi, onClick = { if (chosen < 0) answers[qi] = oi })
                            Text(o, color = color)
                        }
                    }
                    if (answers[qi] >= 0) {
                        Text(
                            (if (answers[qi] == q.answer) "Correct! " else "Answer: ${q.options.getOrElse(q.answer) { "?" }}. ") + q.explanation,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        val done = answers.count { it >= 0 }
        if (done == b.questions.size && done > 0) Text("Score: ${b.questions.indices.count { answers[it] == b.questions[it].answer }} / ${b.questions.size}", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ImageBlock(b: UiBlock.Image, actions: UiActions) {
    val file = remember(b.ref) { actions.resolveImage(b.ref) }
    val bmp = remember(file) {
        file?.takeIf { it.isFile }?.let { f -> BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() }
    }
    Column {
        if (bmp != null) Image(bmp, b.caption, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp))
        else Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Text("Image ${b.ref} is not available (only photos you attached can be shown).", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (b.caption.isNotBlank()) Text(b.caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ComparisonView(b: UiBlock.Comparison) {
    val colW = 130.dp
    val border = MaterialTheme.colorScheme.outlineVariant
    Column {
        SectionTitle(b.title)
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.border(1.dp, border, RoundedCornerShape(8.dp))) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Spacer(Modifier.width(110.dp))
                    b.options.forEachIndexed { i, o ->
                        Box(Modifier.width(colW).fillMaxHeight().background(palette[i % palette.size].copy(alpha = 0.18f)).padding(8.dp)) {
                            Text(o, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                b.criteria.forEach { c ->
                    HorizontalDivider(color = border)
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Text(c.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(110.dp).padding(8.dp))
                        b.options.indices.forEach { i -> Text(c.values.getOrElse(i) { "—" }, modifier = Modifier.width(colW).padding(8.dp)) }
                    }
                }
            }
        }
    }
}
