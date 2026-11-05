package com.offlineagent.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offlineagent.core.runtime.phone.DeepLinks
import com.offlineagent.core.runtime.phone.Macro
import com.offlineagent.core.runtime.phone.MacroDescribe
import com.offlineagent.core.runtime.phone.MacroStepKind

/** Copies [text] to the clipboard (debug reports). */
internal fun copyText(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Asks for a macro's {{values}} before a run / test run. */
@Composable
internal fun MacroValuesDialog(m: Macro, title: String, onDismiss: () -> Unit, onRun: (List<String>) -> Unit) {
    var values by remember { mutableStateOf(m.params.map { "" }) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This macro uses values. Fill them in for this run:", style = MaterialTheme.typography.bodySmall)
            m.params.forEachIndexed { i, p ->
                val ex = m.steps.firstNotNullOfOrNull { st -> if (st.value == "{{$p}}" || st.selector?.listItemLabel == "{{$p}}") p else null }
                OutlinedTextField(values[i], { v -> values = values.toMutableList().also { it[i] = v } }, label = { Text(ex ?: p) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }, confirmButton = { TextButton(onClick = { onRun(values) }, enabled = values.all { it.isNotBlank() }) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/**
 * Phase 6.2 macro editor: every step in plain words ("Tap chat 'Mom' in WhatsApp", "Type 'hi'"), with move up/down,
 * delete and edit text / chat name; suggested {{values}}; the optional WhatsApp chat-link start; schedule; Test run
 * (highlights each matched element on the overlay before acting).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MacroEditor(m: Macro, appLabel: (String) -> String, onDismiss: () -> Unit, onSave: (Macro) -> Unit, onTestRun: (Macro) -> Unit) {
    var draft by remember { mutableStateOf(m) }
    var phone by remember { mutableStateOf("") }
    fun setStep(i: Int, f: (com.offlineagent.core.runtime.phone.MacroStep) -> com.offlineagent.core.runtime.phone.MacroStep) {
        draft = draft.copy(steps = draft.steps.toMutableList().also { it[i] = f(it[i]) })
    }
    fun finished(d: Macro): Macro {
        val params = d.usedParams()
        return d.copy(name = d.name.trim().ifBlank { "Macro" }, params = params, suggestions = d.suggestions.filter { it.step < d.steps.size })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Macro") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    OutlinedTextField(draft.name, { draft = draft.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(draft.description, { draft = draft.copy(description = it) }, label = { Text("What it does") }, modifier = Modifier.fillMaxWidth())
                    Text("Steps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    if (draft.version < 2) Text("Recorded with an older version: targets are matched by text only. Record it again for exact replay.", style = MaterialTheme.typography.bodySmall)
                }
                items(draft.steps.size) { i ->
                    val st = draft.steps[i]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp)) {
                            Text("${i + 1}. " + MacroDescribe.step(st, appLabel), style = MaterialTheme.typography.bodyMedium)
                            if (st.kind == MacroStepKind.TYPE) {
                                OutlinedTextField(st.value, { v -> setStep(i) { it.copy(value = v) } }, label = { Text("Text to type (use {{name}} for a value)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(st.submit, { c -> setStep(i) { it.copy(submit = c) } }); Text("Press Enter after typing (asks first)", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val sel = st.selector
                            if ((st.kind == MacroStepKind.TAP || st.kind == MacroStepKind.LONG_PRESS) && sel != null && sel.listItemLabel.isNotBlank())
                                OutlinedTextField(sel.listItemLabel, { v -> setStep(i) { it.copy(selector = sel.copy(listItemLabel = v, text = if (sel.text == sel.listItemLabel) v else sel.text)) } },
                                    label = { Text("Item / chat name (found by name, even if the list order changes)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (st.kind == MacroStepKind.WAIT)
                                OutlinedTextField((st.waitMillis / 1000).toString(), { v -> v.toLongOrNull()?.let { s -> setStep(i) { it.copy(waitMillis = s.coerceIn(0, 30) * 1000) } } },
                                    label = { Text("Seconds") }, singleLine = true)
                            Row {
                                TextButton(onClick = { if (i > 0) draft = draft.copy(steps = draft.steps.toMutableList().also { l -> val x = l.removeAt(i); l.add(i - 1, x) }) }, enabled = i > 0) { Text("Up") }
                                TextButton(onClick = { if (i < draft.steps.size - 1) draft = draft.copy(steps = draft.steps.toMutableList().also { l -> val x = l.removeAt(i); l.add(i + 1, x) }) }, enabled = i < draft.steps.size - 1) { Text("Down") }
                                TextButton(onClick = {
                                    draft = draft.copy(steps = draft.steps.toMutableList().also { it.removeAt(i) },
                                        suggestions = draft.suggestions.filter { it.step != i }.map { if (it.step > i) it.copy(step = it.step - 1) else it })
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
                item {
                    if (draft.suggestions.isNotEmpty()) {
                        Text("Make these values (ask each run):", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            draft.suggestions.forEach { sg ->
                                AssistChip(onClick = { draft = draft.applySuggestion(sg) }, label = { Text("'${sg.example.take(20)}' → {{${sg.name}}}") })
                            }
                        }
                    }
                    val used = draft.usedParams()
                    if (used.isNotEmpty()) Text("Values: " + used.joinToString { "{{$it}}" }, style = MaterialTheme.typography.bodySmall)
                    val dl = draft.deepLink
                    if (dl != null) {
                        Text("Faster start (optional)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        val fixedChat = !dl.label.contains("{{") && draft.steps.getOrNull(dl.skipSteps - 1)?.selector?.listItemLabel?.contains("{{") != true
                        Text(if (fixedChat) "Open the chat '${dl.label}' directly with its WhatsApp link, then continue. If the link doesn't work, the recorded steps run instead."
                            else "Not available: the chat is a value that changes each run.", style = MaterialTheme.typography.bodySmall)
                        if (fixedChat) {
                            OutlinedTextField(phone, { phone = it }, label = { Text("Phone number of '${dl.label}' (with country code)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val uri = DeepLinks.whatsappUri(phone)
                                Switch(dl.enabled && dl.uri.isNotBlank(), { on -> draft = draft.copy(deepLink = dl.copy(enabled = on && (uri != null || dl.uri.isNotBlank()), uri = uri ?: dl.uri)) },
                                    enabled = uri != null || dl.uri.isNotBlank())
                                Text(if (dl.enabled) "On" else "Off", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                    Text("Schedule", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none", "daily", "weekly").forEach { k -> FilterChip(selected = draft.schedule.kind == k, onClick = { draft = draft.copy(schedule = draft.schedule.copy(kind = k)) }, label = { Text(k) }) }
                    }
                    if (draft.schedule.kind != "none") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("at %02d:%02d".format(draft.schedule.hour, draft.schedule.minute), Modifier.weight(1f))
                            TextButton(onClick = { draft = draft.copy(schedule = draft.schedule.copy(hour = (draft.schedule.hour + 1) % 24)) }) { Text("+1h") }
                            TextButton(onClick = { draft = draft.copy(schedule = draft.schedule.copy(minute = (draft.schedule.minute + 15) % 60)) }) { Text("+15m") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(draft.schedule.whenCharging, { draft = draft.copy(schedule = draft.schedule.copy(whenCharging = it)) }); Text("Only while charging", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Scheduled runs never press Send / Pay / Submit: they stop there and notify you.", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { onTestRun(finished(draft)) }, modifier = Modifier.padding(top = 6.dp)) { Text("Test run (highlights each step first)") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(finished(draft)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
