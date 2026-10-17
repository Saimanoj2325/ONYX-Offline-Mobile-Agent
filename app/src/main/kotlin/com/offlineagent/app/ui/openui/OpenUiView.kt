package com.offlineagent.app.ui.openui

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineagent.app.ui.canvas.TableView
import com.offlineagent.app.ui.markdown.CodeBlock
import com.offlineagent.app.ui.markdown.MarkdownActions
import com.offlineagent.app.ui.markdown.MarkdownView
import com.offlineagent.core.openui.FormModel
import com.offlineagent.core.openui.FormSubmission
import com.offlineagent.core.openui.OpenUiMessage
import com.offlineagent.core.openui.OpenUiParser
import com.offlineagent.core.openui.OpenUiResolver
import com.offlineagent.core.openui.OpenUiStreamParser
import com.offlineagent.core.openui.OuAction
import com.offlineagent.core.openui.Resolved
import com.offlineagent.core.openui.Segment
import com.offlineagent.core.openui.UiNode
import java.io.File

/**
 * Native Compose renderer for OpenUI Lang (https://www.openui.com, MIT — component names/props follow OpenUI's default
 * library). Renders incrementally while tokens stream: the program is re-parsed on every update (complete statements
 * + the trailing partial one) and missing references simply don't show yet.
 */
class OpenUiHost(
    /** Sends text as the next user turn (follow-ups, @ToAssistant, form submissions). */
    val send: (String) -> Unit,
    /** Links never open by themselves in this offline app: the host copies or asks. */
    val openUrl: (String) -> Unit,
    /** "attachment:N" → the N-th photo of this chat. */
    val resolveImage: (String) -> File? = { null },
    val markdown: MarkdownActions = MarkdownActions(),
    /** Disable buttons/forms (e.g. while another answer is generating). */
    val interactive: Boolean = true,
)

/** A whole assistant message: Markdown prose + any ```openui-lang blocks, rendered in order. */
@Composable
fun OpenUiMessageView(text: String, streaming: Boolean, host: OpenUiHost, modifier: Modifier = Modifier) {
    val segments = remember(text, streaming) { OpenUiMessage.split(text, streaming) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.forEachIndexed { i, s ->
            when (s) {
                is Segment.Markdown -> MarkdownView(s.text, actions = host.markdown)
                is Segment.Program -> OpenUiProgramView(s.source, streaming = streaming && !s.closed, host = host, key = "seg$i")
            }
        }
    }
}

@Composable
fun OpenUiProgramView(source: String, streaming: Boolean, host: OpenUiHost, modifier: Modifier = Modifier, key: String = "") {
    // Phase 4b: components render as soon as the partial program resolves; until then the program text itself streams
    // (never a blank bubble while tokens arrive).
    val view = remember(source, streaming) { com.offlineagent.core.openui.OpenUiStreaming.view(source, streaming) }
    Column(modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (view) {
            is com.offlineagent.core.openui.ProgramView.Components -> {
                Node(view.resolved.root!!, host, "$key/")
                if (!streaming) view.resolved.proseLines.takeIf { it.isNotEmpty() }?.let { MarkdownView(it.joinToString("\n"), actions = host.markdown) }
            }
            is com.offlineagent.core.openui.ProgramView.Raw -> if (!view.final) {
                Text("Building UI…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(view.text.takeLast(1200), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Nothing usable: degrade to the raw text so nothing is lost.
                Text("This part could not be displayed as components:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                CodeBlock(source, "openui")
            }
        }
    }
}

private fun act(a: OuAction?, host: OpenUiHost, label: String? = null) {
    when (a) {
        is OuAction.ToAssistant -> host.send(a.message.ifBlank { label ?: "" })
        is OuAction.OpenUrl -> host.openUrl(a.url)
        is OuAction.Many -> a.actions.forEach { act(it, host, label) }
        is OuAction.Submit -> Unit // handled by FormView
        is OuAction.Custom, null -> if (!label.isNullOrBlank()) host.send(label)
    }
}

private fun gap(s: String?) = when (s) { "none" -> 0.dp; "xs" -> 2.dp; "s" -> 4.dp; "l" -> 16.dp; "xl" -> 24.dp; "2xl" -> 32.dp; else -> 8.dp }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Node(n: UiNode, host: OpenUiHost, path: String) {
    when (n.type) {
        "Stack" -> {
            val kids = n.nodes("children")
            val g = gap(n.str("gap"))
            if (n.str("direction") == "row") {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(g), verticalArrangement = Arrangement.spacedBy(g)) {
                    kids.forEachIndexed { i, k -> Box(Modifier.widthIn(min = 140.dp).weight(1f, fill = true)) { Node(k, host, "$path$i/") } }
                }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(g)) { kids.forEachIndexed { i, k -> Node(k, host, "$path$i/") } }
        }
        "Card" -> {
            val variant = n.str("variant")
            val colors = when (variant) {
                "sunk" -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)
                "clear" -> CardDefaults.cardColors(containerColor = Color.Transparent)
                else -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            }
            Card(Modifier.fillMaxWidth(), colors = colors) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(gap(n.str("gap")))) { n.nodes("children").forEachIndexed { i, k -> Node(k, host, "$path$i/") } }
            }
        }
        "CardHeader" -> Column {
            n.str("title")?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            n.str("subtitle")?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        "TextContent" -> {
            val t = n.str("text") ?: ""
            when (n.str("size")) {
                "large-heavy" -> Text(t, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                "large" -> Text(t, style = MaterialTheme.typography.titleMedium)
                "small-heavy" -> Text(t, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                "small" -> Text(t, style = MaterialTheme.typography.bodySmall)
                else -> MarkdownView(t, actions = host.markdown)
            }
        }
        "MarkDownRenderer" -> {
            val md = n.str("textMarkdown") ?: ""
            if (n.str("variant") == "card" || n.str("variant") == "sunk") Card(Modifier.fillMaxWidth()) { MarkdownView(md, Modifier.padding(12.dp), host.markdown) }
            else MarkdownView(md, actions = host.markdown)
        }
        "Callout", "TextCallout" -> CalloutView(n.str("variant") ?: "info", n.str("title"), n.str("description"))
        "CodeBlock" -> CodeBlock(n.str("codeString") ?: "", n.str("language") ?: "")
        "Image", "ImageBlock" -> ImageView(n.str("src"), n.str("alt"), host)
        "Separator" -> HorizontalDivider()
        "Table" -> {
            val cols = n.nodes("columns").map { it.str("label") ?: "" }
            val rows = n.list("rows").map { r -> (r as? List<*>)?.map { c -> when (c) { is Double -> UiNode.fmt(c); null -> ""; else -> c.toString() } } ?: emptyList() }
            if (cols.isNotEmpty()) TableView("", cols, rows.map { r -> List(cols.size) { r.getOrNull(it) ?: "" } })
        }
        "BarChart", "HorizontalBarChart", "LineChart", "AreaChart" -> XYChart(n)
        "PieChart" -> PieChartView(n)
        "Steps" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            n.nodes("items").forEachIndexed { i, s ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(s.str("title") ?: "", style = MaterialTheme.typography.titleSmall)
                        s.str("details")?.takeIf { it.isNotBlank() }?.let { MarkdownView(it, actions = host.markdown) }
                    }
                }
            }
        }
        "Tabs" -> TabsView(n, host, path)
        "Accordion" -> Column { n.nodes("items").forEachIndexed { i, it -> AccordionItemView(it, host, "$path$i/") } }
        "Form" -> FormView(n, host, path)
        "Buttons" -> {
            val bs = n.nodes("buttons")
            if (n.str("direction") == "column") Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { bs.forEach { ButtonView(it, host, null) } }
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { bs.forEach { ButtonView(it, host, null) } }
        }
        "Button" -> ButtonView(n, host, null)
        "FollowUpBlock" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            n.nodes("items").forEach { f -> val t = f.str("text") ?: ""; SuggestionChip(onClick = { host.send(t) }, enabled = host.interactive, label = { Text(t, maxLines = 2) }) }
        }
        "FollowUpItem" -> { val t = n.str("text") ?: ""; SuggestionChip(onClick = { host.send(t) }, enabled = host.interactive, label = { Text(t) }) }
        "ListBlock" -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            n.nodes("items").forEachIndexed { i, it ->
                val a = it.action("action")
                Row(Modifier.fillMaxWidth().then(if (a != null && host.interactive) Modifier.clickable { act(a, host, it.str("title")) } else Modifier).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val img = (it.props["image"] as? Map<*, *>)?.get("src")?.toString()
                    if (n.str("variant") == "image" && img != null) Box(Modifier.size(44.dp)) { ImageView(img, null, host) }
                    else Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(it.str("title") ?: "", style = MaterialTheme.typography.bodyLarge)
                        it.str("subtitle")?.let { s -> Text(s, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (a != null) Text(it.str("actionLabel") ?: "›", color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider()
            }
        }
        "TagBlock" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { n.list("tags").forEach { TagView(it.toString(), "neutral") } }
        "Tag" -> TagView(n.str("text") ?: "", n.str("variant") ?: "neutral")
        // Field components outside a Form: show them as a single-field form so the values can still be sent.
        "FormControl", "Input", "TextArea", "Select", "CheckBoxGroup", "RadioGroup", "Slider", "SwitchGroup" -> {
            val fc = if (n.type == "FormControl") n else UiNode("FormControl", mapOf("label" to (n.str("name") ?: ""), "input" to n))
            FormView(UiNode("Form", mapOf("name" to (n.str("name") ?: "form"), "fields" to listOf(fc))), host, path)
        }
        else -> n.str("text")?.let { Text(it) } // TabItem/AccordionItem/Series/Slice/Col… are rendered by their parents
    }
}

@Composable
private fun CalloutView(variant: String, title: String?, desc: String?) {
    val (bg, fg) = when (variant) {
        "warning" -> Color(0xFFFFF3CD) to Color(0xFF664D03)
        "error", "danger" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "success" -> Color(0xFFD1E7DD) to Color(0xFF0F5132)
        "neutral" -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = bg, contentColor = fg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            title?.takeIf { it.isNotBlank() }?.let { Text(it, fontWeight = FontWeight.SemiBold) }
            desc?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun TagView(text: String, variant: String) {
    val c = when (variant) { "success" -> Color(0xFF2E7D32); "warning" -> Color(0xFFB26A00); "danger" -> MaterialTheme.colorScheme.error; "info" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Text(text, color = c, style = MaterialTheme.typography.labelMedium, modifier = Modifier.border(1.dp, c, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
private fun ImageView(src: String?, alt: String?, host: OpenUiHost) {
    val file = remember(src) { src?.let { host.resolveImage(it) } }
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, file) {
        value = file?.let { f -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })?.asImageBitmap() } }
    }
    val b = bmp
    if (b != null) Image(b, alt, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp))
    else Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(8.dp)) {
        // Remote images are never fetched (offline app): show the description instead.
        Text("🖼 ${alt ?: src ?: "image"}", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TabsView(n: UiNode, host: OpenUiHost, path: String) {
    val items = n.nodes("items")
    if (items.isEmpty()) return
    var sel by rememberSaveable(path) { mutableStateOf(0) }
    val idx = sel.coerceIn(0, items.size - 1)
    Column {
        PrimaryScrollableTabRow(selectedTabIndex = idx, edgePadding = 0.dp) {
            items.forEachIndexed { i, t -> Tab(selected = i == idx, onClick = { sel = i }, text = { Text(t.str("trigger") ?: "Tab ${i + 1}", maxLines = 1) }) }
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { items[idx].nodes("content").forEachIndexed { i, k -> Node(k, host, "$path$idx/$i/") } }
    }
}

@Composable
private fun AccordionItemView(n: UiNode, host: OpenUiHost, path: String) {
    var open by rememberSaveable(path) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.str("trigger") ?: "", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (open) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { n.nodes("content").forEachIndexed { i, k -> Node(k, host, "$path$i/") } }
        HorizontalDivider()
    }
}

@Composable
private fun ButtonView(b: UiNode, host: OpenUiHost, onSubmit: (() -> Unit)?) {
    val label = b.str("label") ?: "OK"
    val a = b.action("action")
    val click = { if (a is OuAction.Submit && onSubmit != null) onSubmit() else act(a, host, label) }
    val destructive = b.str("type") == "destructive"
    when (b.str("variant")) {
        "secondary" -> OutlinedButton(onClick = click, enabled = host.interactive) { Text(label) }
        "tertiary" -> TextButton(onClick = click, enabled = host.interactive) { Text(label) }
        else -> Button(onClick = click, enabled = host.interactive,
            colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors()) { Text(label) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormView(form: UiNode, host: OpenUiHost, path: String) {
    val fields = remember(form) { FormModel.fields(form) }
    val values = remember(path, fields.map { it.name }) { mutableStateMapOf<String, Any>().apply { putAll(FormModel.initialValues(fields)) } }
    var errors by remember(path) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sent by rememberSaveable(path) { mutableStateOf(false) }
    val name = form.str("name") ?: "form"
    val submit = {
        errors = FormModel.validate(fields, values)
        if (errors.isEmpty()) { sent = true; host.send(FormSubmission.toUserMessage(name, fields, values)) }
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            fields.forEach { f ->
                val err = errors[f.name]
                Column {
                    Text(f.label + if (f.rules.any { it.startsWith("required") }) " *" else "", style = MaterialTheme.typography.labelLarge)
                    when (f.kind) {
                        "Input", "TextArea" -> OutlinedTextField(
                            value = values[f.name]?.toString() ?: "", onValueChange = { values[f.name] = it }, enabled = host.interactive && !sent,
                            placeholder = f.placeholder?.let { p -> { Text(p) } }, isError = err != null, singleLine = f.kind == "Input",
                            minLines = if (f.kind == "TextArea") 3 else 1, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (f.inputType == "password") PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = when (f.inputType) {
                                "email" -> KeyboardType.Email; "number" -> KeyboardType.Number; "url" -> KeyboardType.Uri; "password" -> KeyboardType.Password; else -> KeyboardType.Text
                            }),
                        )
                        "Select" -> {
                            var open by remember { mutableStateOf(false) }
                            val cur = values[f.name]?.toString().orEmpty()
                            Box {
                                OutlinedButton(onClick = { open = true }, enabled = host.interactive && !sent) {
                                    Text(f.options.firstOrNull { it.first == cur }?.second ?: f.placeholder ?: "Select…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                DropdownMenu(open, { open = false }) { f.options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { values[f.name] = v; open = false }) } }
                            }
                        }
                        "CheckBoxGroup", "SwitchGroup" -> {
                            @Suppress("UNCHECKED_CAST") val set = (values[f.name] as? Set<String>) ?: emptySet()
                            f.options.forEach { (v, l) ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = host.interactive && !sent) { values[f.name] = if (v in set) set - v else set + v }) {
                                    if (f.kind == "SwitchGroup") { Text(l, Modifier.weight(1f)); Switch(v in set, { on -> values[f.name] = if (on) set + v else set - v }, enabled = host.interactive && !sent) }
                                    else { Checkbox(v in set, { on -> values[f.name] = if (on) set + v else set - v }, enabled = host.interactive && !sent); Text(l) }
                                }
                            }
                        }
                        "RadioGroup" -> f.options.forEach { (v, l) ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = host.interactive && !sent) { values[f.name] = v }) {
                                RadioButton(values[f.name] == v, { values[f.name] = v }, enabled = host.interactive && !sent); Text(l)
                            }
                        }
                        "Slider" -> {
                            val v = (values[f.name] as? Double) ?: f.min ?: 0.0
                            val lo = (f.min ?: 0.0).toFloat(); val hi = (f.max ?: 100.0).toFloat().coerceAtLeast(lo + 1)
                            val steps = f.step?.takeIf { it > 0 }?.let { ((hi - lo) / it).toInt() - 1 }?.coerceIn(0, 200) ?: 0
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Slider(v.toFloat(), { values[f.name] = it.toDouble() }, valueRange = lo..hi, steps = steps, enabled = host.interactive && !sent, modifier = Modifier.weight(1f))
                                Text(UiNode.fmt(Math.round(v * 100) / 100.0), Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    f.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    err?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                }
            }
            val buttons = form.node("buttons")?.nodes("buttons").orEmpty()
            if (sent) Text("Sent ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (buttons.none { it.action("action") is OuAction.Submit }) Button(onClick = submit, enabled = host.interactive) { Text("Submit") }
                buttons.forEach { ButtonView(it, host, submit) }
            }
        }
    }
}

// ---------------------------------------------------------------- charts (drawn on Canvas)

private val PALETTE = listOf(Color(0xFF4F7DF3), Color(0xFFF2994A), Color(0xFF27AE60), Color(0xFFEB5757), Color(0xFF9B51E0), Color(0xFF2D9CDB), Color(0xFFF2C94C), Color(0xFF6FCF97))

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(names: List<String>) {
    if (names.size <= 1 && names.firstOrNull().isNullOrBlank()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        names.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(PALETTE[i % PALETTE.size], CircleShape)); Spacer(Modifier.width(4.dp))
                Text(s, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun XYChart(n: UiNode) {
    val labels = n.list("labels").map { it.toString() }
    val series = n.nodes("series").map { s -> (s.str("category") ?: "") to s.list("values").mapNotNull { (it as? Double) ?: it?.toString()?.toDoubleOrNull() } }
    if (labels.isEmpty() || series.isEmpty()) return
    val horizontal = n.type == "HorizontalBarChart"
    val stacked = n.str("variant") == "stacked"
    val isLine = n.type == "LineChart" || n.type == "AreaChart"
    val maxV = if (stacked && !isLine) labels.indices.maxOf { i -> series.sumOf { it.second.getOrNull(i)?.coerceAtLeast(0.0) ?: 0.0 } } else series.maxOf { it.second.maxOrNull() ?: 0.0 }
    val minV = minOf(0.0, series.minOf { it.second.minOrNull() ?: 0.0 })
    val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
    val axis = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        n.str("yLabel")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor) }
        Row {
            Column(Modifier.height(if (horizontal) (labels.size * 28).coerceAtLeast(120).dp else 180.dp), verticalArrangement = Arrangement.SpaceBetween) {
                if (!horizontal) { Text(UiNode.fmt(maxV), style = MaterialTheme.typography.labelSmall, color = labelColor); Text(UiNode.fmt(minV), style = MaterialTheme.typography.labelSmall, color = labelColor) }
                else labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 90.dp)) }
            }
            Canvas(Modifier.weight(1f).height(if (horizontal) (labels.size * 28).coerceAtLeast(120).dp else 180.dp).padding(start = 6.dp)) {
                val w = size.width; val h = size.height
                drawLine(axis, Offset(0f, h), Offset(w, h)); drawLine(axis, Offset(0f, 0f), Offset(0f, h))
                fun y(v: Double) = (h - ((v - minV) / range * h)).toFloat()
                if (isLine) {
                    val step = if (labels.size > 1) w / (labels.size - 1) else w
                    series.forEachIndexed { si, (_, vals) ->
                        val c = PALETTE[si % PALETTE.size]
                        val p = Path()
                        vals.take(labels.size).forEachIndexed { i, v -> val x = i * step; if (i == 0) p.moveTo(x, y(v)) else p.lineTo(x, y(v)) }
                        if (n.type == "AreaChart" && vals.isNotEmpty()) {
                            val a = Path().apply { addPath(p); lineTo((vals.take(labels.size).size - 1) * step, h); lineTo(0f, h); close() }
                            drawPath(a, c.copy(alpha = 0.25f))
                        }
                        drawPath(p, c, style = Stroke(width = 5f))
                        vals.take(labels.size).forEachIndexed { i, v -> drawCircle(c, 6f, Offset(i * step, y(v))) }
                    }
                } else if (!horizontal) {
                    val group = w / labels.size
                    labels.indices.forEach { i ->
                        var acc = 0.0
                        series.forEachIndexed { si, (_, vals) ->
                            val v = vals.getOrNull(i) ?: return@forEachIndexed
                            val c = PALETTE[si % PALETTE.size]
                            if (stacked) {
                                val top = y(acc + v); val bottom = y(acc); acc += v
                                drawRect(c, Offset(i * group + group * 0.15f, top), Size(group * 0.7f, bottom - top))
                            } else {
                                val bw = group * 0.8f / series.size
                                val x = i * group + group * 0.1f + si * bw
                                val top = y(maxOf(v, 0.0)); val bottom = y(minOf(v, 0.0))
                                drawRect(c, Offset(x, top), Size(bw * 0.9f, (bottom - top).coerceAtLeast(1f)))
                            }
                        }
                    }
                } else {
                    val group = h / labels.size
                    labels.indices.forEach { i ->
                        var acc = 0.0
                        series.forEachIndexed { si, (_, vals) ->
                            val v = vals.getOrNull(i)?.coerceAtLeast(0.0) ?: return@forEachIndexed
                            val c = PALETTE[si % PALETTE.size]
                            val bh = if (stacked) group * 0.7f else group * 0.8f / series.size
                            val top = if (stacked) i * group + group * 0.15f else i * group + group * 0.1f + si * bh
                            val x0 = if (stacked) (acc / range * w).toFloat() else 0f
                            val len = (v / range * w).toFloat()
                            acc += v
                            drawRect(c, Offset(x0, top), Size(len.coerceAtLeast(1f), bh * 0.9f))
                        }
                    }
                }
            }
        }
        if (!horizontal) Row(Modifier.fillMaxWidth().padding(start = 28.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.SpaceBetween) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1, modifier = Modifier.padding(end = 8.dp)) }
        }
        n.str("xLabel")?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.align(Alignment.CenterHorizontally)) }
        Legend(series.map { it.first })
    }
}

@Composable
private fun PieChartView(n: UiNode) {
    val slices = n.nodes("slices").mapNotNull { s -> s.num("value")?.takeIf { it > 0 }?.let { (s.str("category") ?: "") to it } }
    if (slices.isEmpty()) return
    val total = slices.sumOf { it.second }
    val donut = n.str("variant") == "donut"
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(150.dp)) {
            var start = -90f
            slices.forEachIndexed { i, (_, v) ->
                val sweep = (v / total * 360).toFloat()
                if (donut) drawArc(PALETTE[i % PALETTE.size], start, sweep, false, style = Stroke(width = size.minDimension * 0.22f), topLeft = Offset(size.minDimension * 0.11f, size.minDimension * 0.11f), size = Size(size.minDimension * 0.78f, size.minDimension * 0.78f))
                else drawArc(PALETTE[i % PALETTE.size], start, sweep, true)
                start += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            slices.forEachIndexed { i, (l, v) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(PALETTE[i % PALETTE.size], CircleShape)); Spacer(Modifier.width(6.dp))
                    Text("$l  ${"%.0f".format(v / total * 100)}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
