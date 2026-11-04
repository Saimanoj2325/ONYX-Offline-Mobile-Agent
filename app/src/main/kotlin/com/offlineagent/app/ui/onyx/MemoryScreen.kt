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
    // Phase 6
    val settings by app.settings.settings.collectAsState(initial = com.offlineagent.core.data.AppSettings())
    val contacts by remember { onyx.db.phase6().observeContacts() }.collectAsState(initial = emptyList())
    val routines by remember { onyx.db.phase6().observeRoutines() }.collectAsState(initial = emptyList())
    val historyCount by remember { onyx.db.phase6().observeHistoryCount() }.collectAsState(initial = 0)
    val threads by remember { onyx.db.phase6().observeSummaries() }.collectAsState(initial = emptyList())
    var viewingThread by remember { mutableStateOf<com.offlineagent.core.data.HistoryChunkEntity?>(null) }
    var renaming by remember { mutableStateOf<com.offlineagent.core.data.RoutineEntity?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }

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
                    Text("Say \"remember that …\" or \"forget …\" in any chat, or tap +. Temporary chats never read or write memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Saved memories", style = MaterialTheme.typography.bodyMedium)
                            Text("Learn lasting facts and preferences from chats (marked inferred until you confirm)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.savedMemories, { v -> scope.launch { app.settings.update { it.copy(savedMemories = v) } } })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Reference chat history", style = MaterialTheme.typography.bodyMedium)
                            Text("Use relevant bits of all past chats ($historyCount saved snippets) in new chats and tasks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(settings.referenceChatHistory, { v -> scope.launch { app.settings.update { it.copy(referenceChatHistory = v) } } })
                    }
                    if (historyCount > 0) TextButton(onClick = { scope.launch { onyx.longMemory.history.wipe() } }) { Text("Clear chat-history index") }
                }
            }
            if (memories.isEmpty()) item { Text("No memories yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp)) }
            items(memories, key = { it.id }) { m ->
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            Text("${m.kind.lowercase()} · ${m.source.substringBefore(':')}" + if (m.inferred) " · inferred (${(m.confidence * 100).toInt()}%)" else "",
                                style = MaterialTheme.typography.labelSmall, color = if (m.inferred) NetworkAmber else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (m.inferred) TextButton(onClick = { scope.launch { onyx.longMemory.book.confirm(m.id) } }) { Text("Confirm") }
                        IconButton(onClick = { scope.launch { onyx.dao.upsertMemory(m.copy(pinned = !m.pinned, updatedAt = System.currentTimeMillis())) } }) {
                            Icon(Icons.Default.Star, "Pin", tint = if (m.pinned) NetworkAmber else MaterialTheme.colorScheme.outline)
                        }
                        IconButton(onClick = { editing = m }) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = { scope.launch { onyx.dao.deleteMemory(m.id) } }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
            if (memories.isNotEmpty()) item { TextButton(onClick = { confirmClear = true }) { Text("Delete all memories", color = MaterialTheme.colorScheme.error) } }
            // Phase 6: threads = rolling summaries of past chats (what "reference chat history" recalls first)
            item {
                SectionCard {
                    Text("Threads", style = MaterialTheme.typography.titleSmall)
                    Text("Summaries of your chats that ONYX can recall in new chats. Deleting a thread forgets it here; the chat itself stays.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (threads.isEmpty()) Text("None yet — long chats get a summary once older turns leave the context window.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    threads.distinctBy { it.chatId }.take(30).forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.chatTitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(t.text.removePrefix("Summary: "), style = MaterialTheme.typography.labelSmall, maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { viewingThread = t }) { Text("View") }
                            IconButton(onClick = { scope.launch { onyx.longMemory.deleteThread(t.chatId) } }) { Icon(Icons.Default.Delete, "Delete thread") }
                        }
                    }
                }
            }
            // Phase 6: frequent contacts (learned only from approved calls / messages)
            item {
                SectionCard {
                    Text("Frequent contacts", style = MaterialTheme.typography.titleSmall)
                    if (contacts.isEmpty()) Text("None yet — learned from calls and messages you approve.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    contacts.take(12).forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium)
                                Text("${c.uses}× · ${c.channels.replace(',', ' ')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { onyx.db.phase6().deleteContact(c.key) } }) { Icon(Icons.Default.Delete, "Forget contact") }
                        }
                    }
                }
            }
            // Phase 6: routines the Planner reuses (no planning call)
            item {
                SectionCard {
                    Text("Routines", style = MaterialTheme.typography.titleSmall)
                    Text("A multi-step task that worked twice becomes a routine: next time the agents skip planning.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    routines.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                Text((if (r.successes >= com.offlineagent.core.agents.memory.RoutineBook.ACTIVE_AFTER) "active" else "learning (${r.successes}/2)") +
                                    " · used ${r.uses}× · " + com.offlineagent.core.agents.memory.Routine.stepsFromJson(r.stepsJson).joinToString(" → ") { it.tool },
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(r.enabled, { v -> scope.launch { onyx.longMemory.routines.setEnabled(r.id, v) } })
                            IconButton(onClick = { renaming = r }) { Icon(Icons.Default.Edit, "Rename") }
                            IconButton(onClick = { scope.launch { onyx.longMemory.routines.delete(r.id) } }) { Icon(Icons.Default.Delete, "Delete routine") }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = { confirmWipe = true }) { Text("Wipe ALL memory (memories, chat history index, summaries, contacts, routines)", color = MaterialTheme.colorScheme.error) }
            }
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
                    scope.launch { onyx.dao.upsertMemory(e?.copy(text = text.trim(), updatedAt = now, inferred = false, confirmed = true, confidence = 1.0) ?: AgentMemoryEntity(text = text.trim(), source = "user", createdAt = now, updatedAt = now)) }
                    adding = false; editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false; editing = null }) { Text("Cancel") } })
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Delete all memories?") }, text = { Text("This cannot be undone.") },
        confirmButton = { TextButton(onClick = { scope.launch { onyx.dao.deleteAllMemories() }; confirmClear = false }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
    renaming?.let { r ->
        var name by remember(r.id) { mutableStateOf(r.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename routine") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { scope.launch { onyx.longMemory.routines.rename(r.id, name) }; renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    viewingThread?.let { t ->
        AlertDialog(onDismissRequest = { viewingThread = null }, title = { Text(t.chatTitle) },
            text = { Text(t.text.removePrefix("Summary: ")) },
            confirmButton = { TextButton(onClick = { viewingThread = null }) { Text("Close") } },
            dismissButton = { TextButton(onClick = { scope.launch { onyx.longMemory.deleteThread(t.chatId) }; viewingThread = null }) {
                Text("Delete", color = MaterialTheme.colorScheme.error) } })
    }
    if (confirmWipe) AlertDialog(onDismissRequest = { confirmWipe = false }, title = { Text("Wipe all memory?") },
        text = { Text("Deletes saved memories, the chat-history index, rolling summaries, frequent contacts and routines. Chats themselves stay. This cannot be undone.") },
        confirmButton = { TextButton(onClick = { scope.launch { onyx.longMemory.wipeAll() }; confirmWipe = false }) { Text("Wipe", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } })
}
