package com.offlineagent.app.ui.library

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.ModelAdvice
import com.offlineagent.app.data.ModelRole
import com.offlineagent.app.data.formatBytes
import com.offlineagent.core.data.AppSettings
import com.offlineagent.core.data.ModelEntity
import com.offlineagent.core.inference.EngineState
import com.offlineagent.engine.llamacpp.DeviceInfo
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- Documents (RAG library)

@Composable
fun DocumentsScreen(app: OfflineAgentApp, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val docsFlow = remember { app.db.documents().observeDocuments() }
    val docs by docsFlow.collectAsStateWithLifecycle(emptyList())
    val progress by app.indexer.progress.collectAsStateWithLifecycle()
    val models by app.models.models.collectAsStateWithLifecycle(emptyList())
    val hasEmbedder = models.any { it.role == ModelRole.EMBEDDING.name && it.active }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) app.indexer.import(uris) { notify(it) }
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Documents", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Import PDFs, .txt or .md files. They are copied as text into the app, split into overlapping chunks and " +
                (if (hasEmbedder) "embedded on-device for semantic search." else "indexed for keyword search. Add an Embedding model (Models tab) for semantic search."),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/*")) }, enabled = progress == null) { Text("Import documents") }
            if (hasEmbedder && docs.any { it.status == "READY" }) OutlinedButton(onClick = { app.indexer.reembedAll { notify(it) } }, enabled = progress == null) { Text("Re-embed all") }
        }
        progress?.let { p ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${p.phase}: ${p.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val f = p.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.total > 0) Text("${p.done} / ${p.total}", style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { app.indexer.cancel() }) { Text("Cancel") }
                    }
                }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(docs, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(
                                when (d.status) {
                                    "READY" -> "${d.chunkCount} chunks · ${d.chars / 1000}k chars · " + (d.embeddingModel?.let { "embedded ($it)" } ?: "keyword only")
                                    "FAILED" -> "Failed: ${d.error}"
                                    else -> "Indexing…"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (d.status == "FAILED") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { scope.launch { app.indexer.delete(d) } }, enabled = progress == null) { Icon(Icons.Filled.Delete, "Delete document") }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Models + settings

@Composable
fun ModelsScreen(app: OfflineAgentApp, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val models by app.models.models.collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val engine by app.inference.state.collectAsStateWithLifecycle()
    val loaded by app.engineController.loaded.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var importing by remember { mutableStateOf<String?>(null) }
    var importFraction by remember { mutableStateOf<Float?>(null) }
    var pendingRole by remember { mutableStateOf(ModelRole.CHAT) }
    var mmprojFor by remember { mutableStateOf<ModelEntity?>(null) }
    var showRecs by remember { mutableStateOf(false) }
    var sysInfo by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = mmprojFor
        importing = if (target != null) "vision projector" else pendingRole.label + " model"
        importFraction = null
        scope.launch {
            try {
                if (target != null) app.models.importMmproj(target, uri) { c, t -> importFraction = if (t > 0) c.toFloat() / t else null }
                else app.models.import(uri, pendingRole) { c, t -> importFraction = if (t > 0) c.toFloat() / t else null }
                notify("Imported.")
            } catch (e: Throwable) { notify("Import failed: ${e.message}") } finally { importing = null; mmprojFor = null }
        }
    }
    val ram = remember {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        }.getOrNull()
    }

    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Models", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
            Text(
                "Everything runs on this phone. The app has no internet permission; download .gguf files on a PC or browser and import them here. " +
                    "Phone RAM: ${ram?.let { formatBytes(it.totalMem) } ?: "?"} (free ${ram?.let { formatBytes(it.availMem) } ?: "?"}).",
                style = MaterialTheme.typography.bodySmall,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Tip: tool calling and Canvas work best with Qwen3-1.7B or Qwen3-4B (Q4_K_M). Qwen3-0.6B is fast but weak at structured output.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showRecs = true }) { Text("Recommended models & RAM guide") }
                }
            }
        }
        importing?.let { what ->
            item {
                Column {
                    Text("Copying $what into app storage…")
                    val f = importFraction
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        ModelRole.entries.forEach { role ->
            item(key = "h-$role") {
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(role.label, style = MaterialTheme.typography.titleMedium)
                        Text(role.hint, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { pendingRole = role; mmprojFor = null; picker.launch(arrayOf("*/*")) }, enabled = importing == null) { Text("Add") }
                }
            }
            items(models.filter { it.role == role.name }, key = { it.id }) { m ->
                ModelCard(m, isLoaded = loaded?.id == m.id && (engine is EngineState.Ready || engine is EngineState.Generating), settings = settings,
                    onActivate = { scope.launch { app.models.activate(m) } },
                    onLoad = { scope.launch { try { app.engineController.load(m); notify("Loaded ${m.name}") } catch (e: Throwable) { notify("Load failed: ${e.message}") } } },
                    onUnload = { scope.launch { app.inference.unload(); app.engineController.markUnloaded() } },
                    onDelete = { scope.launch { if (loaded?.id == m.id) { app.inference.unload(); app.engineController.markUnloaded() }; app.models.delete(m) } },
                    onAddMmproj = { mmprojFor = m; picker.launch(arrayOf("*/*")) },
                )
            }
        }
        item {
            HorizontalDivider()
            Text("Settings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Text("Context length (applies on next load). 4096 is recommended for Canvas and skills.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2048, 4096, 8192).forEach { n ->
                    FilterChip(selected = settings.contextLength == n, onClick = { scope.launch { app.settings.update { it.copy(contextLength = n) } } }, label = { Text("$n") })
                }
            }
            val rec = DeviceInfo.recommendedThreads()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CPU threads: ${if (settings.threads == 0) "auto ($rec)" else settings.threads}", modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { app.settings.update { it.copy(threads = ((if (it.threads == 0) rec else it.threads) - 1).coerceAtLeast(1)) } } }) { Text("−") }
                TextButton(onClick = { scope.launch { app.settings.update { it.copy(threads = ((if (it.threads == 0) rec else it.threads) + 1).coerceAtMost(8)) } } }) { Text("+") }
                TextButton(onClick = { scope.launch { app.settings.update { it.copy(threads = 0) } } }) { Text("Auto") }
            }
            if (settings.alwaysAllowedTools.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Always allowed without asking: ${settings.alwaysAllowedTools.joinToString()}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { app.settings.update { it.copy(alwaysAllowedTools = emptySet()) } } }) { Text("Reset") }
            }
            TextButton(onClick = { scope.launch { sysInfo = app.inference.systemInfo() } }) { Text("CPU features (llama.cpp)") }
            sysInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (showRecs) AlertDialog(
        onDismissRequest = { showRecs = false },
        confirmButton = { TextButton(onClick = { showRecs = false }) { Text("Close") } },
        title = { Text("Recommended models") },
        text = { RecommendationList(settings.contextLength) },
    )
}

@Composable
private fun RecommendationList(ctx: Int) {
    val clipboard = LocalClipboardManager.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("RAM rule of thumb: model file + KV cache (~${ctx * 114 / 1024} MB at context $ctx for Qwen3 1.7B) + ~150 MB. " +
                "Keep total under ~40% of the phone's RAM. Links are copied (the app cannot open the internet).", style = MaterialTheme.typography.bodySmall)
        }
        items(ModelAdvice.recommendations) { r ->
            Column {
                Text("${r.role.label}: ${r.name} (${r.size}, ${r.ram})", style = MaterialTheme.typography.titleSmall)
                Text("${r.note} Licence: ${r.licence}. File: ${r.file}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { clipboard.setText(AnnotatedString(r.url)) }) { Text("Copy link") }
            }
        }
    }
}

@Composable
private fun ModelCard(
    m: ModelEntity, isLoaded: Boolean, settings: AppSettings,
    onActivate: () -> Unit, onLoad: () -> Unit, onUnload: () -> Unit, onDelete: () -> Unit, onAddMmproj: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), colors = if (m.active) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatBytes(m.sizeBytes) + (if (m.active) " · selected" else "") + (if (isLoaded) " · loaded" else "") +
                            (if (m.role != ModelRole.EMBEDDING.name) " · needs ≈${formatBytes(ModelAdvice.estimateRamBytes(m.sizeBytes, settings.contextLength))} RAM" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (m.role == ModelRole.VISION.name) Text(
                        if (m.mmprojPath != null) "Projector: " + java.io.File(m.mmprojPath).name else "⚠ Needs its mmproj file",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (m.mmprojPath == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete model") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!m.active) OutlinedButton(onClick = onActivate) { Text("Use") }
                if (m.role != ModelRole.EMBEDDING.name) {
                    if (isLoaded) OutlinedButton(onClick = onUnload) { Text("Unload") } else OutlinedButton(onClick = onLoad) { Text("Load now") }
                }
                if (m.role == ModelRole.VISION.name) OutlinedButton(onClick = onAddMmproj) { Text(if (m.mmprojPath == null) "Add projector" else "Replace projector") }
            }
        }
    }
    Spacer(Modifier.width(1.dp))
}
