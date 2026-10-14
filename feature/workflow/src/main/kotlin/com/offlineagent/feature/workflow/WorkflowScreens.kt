package com.offlineagent.feature.workflow

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineagent.core.data.AgentRunEntity
import com.offlineagent.core.data.AgentStepEntity
import com.offlineagent.core.data.SkillEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Everything the skills screen needs from the app (keeps this module free of app/engine dependencies). */
interface SkillsHost {
    val skills: Flow<List<SkillEntity>>
    /** Returns null on success, or the validation errors. */
    suspend fun save(markdown: String, replacingId: String?): String?
    suspend fun delete(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
    fun run(skillId: String)
    val template: String
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(host: SkillsHost, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val skills by host.skills.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Pair<String?, String>?>(null) } // (id being replaced, markdown)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            if (text == null) notify("Could not read the file") else editing = null to text
        }
    }

    editing?.let { (id, md) ->
        SkillEditor(id, md, onCancel = { editing = null }, onSave = { text ->
            scope.launch {
                val err = host.save(text, id)
                if (err == null) { notify("Skill saved"); editing = null } else notify(err)
            }
        })
        return
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Skills", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text(
                "A skill is a markdown file with front matter: name, description, triggers, the tools it may use and the UI components " +
                    "of its result. Run one from the chat (+ → Run a skill, or type /skill-id).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { editing = null to host.template }) { Text("New skill") }
                OutlinedButton(onClick = { importer.launch(arrayOf("text/markdown", "text/plain", "*/*")) }) { Text("Import .md") }
            }
        }
        items(skills, key = { it.id }) { s ->
            val name = Regex("(?m)^name:\\s*(.+)$").find(s.markdown)?.groupValues?.get(1)?.trim() ?: s.id
            val desc = Regex("(?m)^description:\\s*(.+)$").find(s.markdown)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") ?: ""
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text("/${s.id}" + if (s.builtIn) " · built-in" else "", style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(checked = s.enabled, onCheckedChange = { e -> scope.launch { host.setEnabled(s.id, e) } })
                    }
                    if (desc.isNotBlank()) Text(desc, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { host.run(s.id) }, enabled = s.enabled) { Text("Run") }
                        TextButton(onClick = { editing = (if (s.builtIn) null else s.id) to (if (s.builtIn) s.markdown.replaceFirst(Regex("(?m)^id:\\s*.+$"), "id: ${s.id}-copy") else s.markdown) }) {
                            Text(if (s.builtIn) "Duplicate & edit" else "Edit")
                        }
                        if (!s.builtIn) IconButton(onClick = { scope.launch { host.delete(s.id) } }) { Icon(Icons.Filled.Delete, "Delete skill") }
                    }
                }
            }
        }
        item { Spacer(Modifier.heightIn(min = 24.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillEditor(id: String?, initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (id == null) "New skill" else "Edit /$id") },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel") } },
            actions = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        )
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            modifier = Modifier.fillMaxSize().padding(12.dp),
        )
    }
}

// ---------------------------------------------------------------- Run history

interface RunsHost {
    val runs: Flow<List<AgentRunEntity>>
    fun steps(runId: String): Flow<List<AgentStepEntity>>
    suspend fun delete(runId: String)
    /** Resume an unfinished run; returns false if another run is active. */
    fun resume(runId: String): Boolean
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunsScreen(host: RunsHost, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val runs by host.runs.collectAsState(initial = emptyList())
    var open by remember { mutableStateOf<AgentRunEntity?>(null) }
    val scope = rememberCoroutineScope()
    open?.let { r ->
        val current = runs.firstOrNull { it.runId == r.runId } ?: r
        RunDetail(current, host.steps(r.runId), onBack = { open = null }, onResume = { if (!host.resume(r.runId)) notify("Another run is in progress") })
        return
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Agent runs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text("Every step (plan, tool calls with inputs/outputs, approvals, errors, timing) is checkpointed on the device.", style = MaterialTheme.typography.bodySmall)
        }
        if (runs.isEmpty()) item { Text("No runs yet. Run a skill from the chat.", modifier = Modifier.padding(top = 16.dp)) }
        items(runs, key = { it.runId }) { r ->
            Card(Modifier.fillMaxWidth().clickable { open = r }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.request, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text("${statusLabel(r.status)} · ${r.skillId ?: "agent"} · ${r.iteration} steps · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.createdAt)),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { scope.launch { host.delete(r.runId) } }) { Icon(Icons.Filled.Delete, "Delete run") }
                }
            }
        }
    }
}

private fun statusLabel(s: String) = when (s) {
    "COMPLETED" -> "✓ completed"; "FAILED" -> "✗ failed"; "CANCELLED" -> "■ cancelled"
    "AWAITING_APPROVAL" -> "⏸ waiting for approval"; else -> "… ${s.lowercase()}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunDetail(r: AgentRunEntity, stepsFlow: Flow<List<AgentStepEntity>>, onBack: () -> Unit, onResume: () -> Unit) {
    val steps by stepsFlow.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(statusLabel(r.status)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (r.status in setOf("RUNNING", "PLANNING", "AWAITING_APPROVAL")) TextButton(onClick = onResume) { Text("Resume") } },
        )
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text(r.request, style = MaterialTheme.typography.titleMedium)
                r.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                r.finalAnswer?.let { Text("Answer: $it", style = MaterialTheme.typography.bodyMedium) }
                val total = steps.sumOf { it.durationMs }
                Text("${steps.size} steps · ${total / 1000.0} s total", style = MaterialTheme.typography.labelSmall)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
            items(steps, key = { it.idx }) { s ->
                Card(colors = CardDefaults.cardColors(containerColor = if (s.error != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                    SelectionContainer {
                        Column(Modifier.padding(10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("#${s.idx} ${s.kind}${s.tool?.let { " · $it" } ?: ""} · ${s.durationMs} ms", style = MaterialTheme.typography.labelLarge)
                            if (s.thought.isNotBlank()) Text("thought: ${s.thought}", style = MaterialTheme.typography.bodySmall)
                            if (s.input.isNotBlank()) Text("input: ${s.input.take(1500)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            if (s.output.isNotBlank()) Text("output: ${s.output.take(2000)}", style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                            s.error?.let { Text("error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}
