package com.offlineagent.app.ui.onyx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.IntentRequest
import com.offlineagent.core.data.AgentMemoryEntity
import kotlinx.coroutines.launch

/** User-controlled memory: view, add, edit, pin, delete, export (share JSON), clear all, and opt out entirely. */
@Composable
fun MemoryScreen(app: OfflineAgentApp, share: (IntentRequest) -> Unit, modifier: Modifier = Modifier) {
    val onyx = app.onyx
    val prefs by onyx.prefs.values.collectAsState()
    val memories by remember { onyx.dao.observeMemories() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<AgentMemoryEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Memory", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { share(IntentRequest.Share(onyx.exportMemories())) } }) { Icon(Icons.Default.Share, "Export") }
                }
            }
            item {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use memory", style = MaterialTheme.typography.titleSmall)
                            Text(if (prefs.memoryEnabled) "Agents recall these notes and your documents. Stored only on this phone." else "Off: nothing is recalled or saved.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(prefs.memoryEnabled, { v -> onyx.prefs.update { it.copy(memoryEnabled = v) } })
                    }
                    Text("Say \"remember that …\" to add one, or tap +.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (memories.isEmpty()) item { Text("No memories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(memories, key = { it.id }) { m ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            Text("${m.category} · ${m.source.substringBefore(':')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { scope.launch { onyx.dao.upsertMemory(m.copy(pinned = !m.pinned, updatedAt = System.currentTimeMillis())) } }) {
                            Icon(Icons.Default.Star, "Pin", tint = if (m.pinned) NetworkAmber else MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { scope.launch { onyx.dao.deleteMemory(m.id) } }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            if (memories.isNotEmpty()) item { TextButton(onClick = { confirmClear = true }) { Text("Delete all memories", color = MaterialTheme.colorScheme.error) } }
        }
        ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp))
    }

    if (adding || editing != null) {
        var text by remember(editing) { mutableStateOf(editing?.text ?: "") }
        AlertDialog(onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing == null) "New memory" else "Edit memory") },
            text = { OutlinedTextField(text, { text = it }, minLines = 2, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    val now = System.currentTimeMillis(); val e = editing
                    scope.launch { onyx.dao.upsertMemory(e?.copy(text = text.trim(), updatedAt = now) ?: AgentMemoryEntity(text = text.trim(), source = "user", createdAt = now, updatedAt = now)) }
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Delete all memories?") }, text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = { scope.launch { onyx.dao.deleteAllMemories() }; confirmClear = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}
