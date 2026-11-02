package com.offlineagent.app.ui.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.ModelRole
import com.offlineagent.app.data.formatBytes
import com.offlineagent.app.models.DownloadState
import com.offlineagent.core.data.ModelEntity
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.models.CatalogModel
import com.offlineagent.core.models.HfUrls
import com.offlineagent.core.models.ModelCatalog
import com.offlineagent.core.models.ModelKind
import kotlinx.coroutines.launch
import java.io.File

/**
 * Phase 5b: the model downloader in one place, reused by Home (first-run onboarding + model card), Agents (catalogue
 * with Download / Use / Delete inline) and Chat (top-bar picker + sheet). All of it drives the existing
 * [com.offlineagent.app.models.ModelDownloads] (WorkManager foreground download, resume, GGUF/SHA-256 checks).
 */
object ModelHub {
    fun installed(models: List<ModelEntity>, m: CatalogModel): ModelEntity? =
        models.firstOrNull { e -> File(e.path).name == m.main.file && File(e.path).isFile }

    fun use(app: OfflineAgentApp, e: ModelEntity, notify: (String) -> Unit = {}) = app.appScope.launch {
        app.models.activate(e)
        if (app.engineController.loaded.value != null) app.engineController.applySettings()?.let(notify)
        app.onyx.refreshModelState()
    }

    fun delete(app: OfflineAgentApp, e: ModelEntity, notify: (String) -> Unit = {}) = app.appScope.launch {
        val wasLoaded = app.engineController.loaded.value?.id == e.id
        app.models.delete(e)
        if (wasLoaded) app.engineController.applySettings()
        app.onyx.refreshModelState()
        notify("Deleted ${e.name.removeSuffix(".gguf")}")
    }

    fun recommended(): CatalogModel = ModelCatalog.recommendChat(com.offlineagent.engine.llamacpp.DeviceInfo.totalRamBytes())
}

// ---------------------------------------------------------------- progress

@Composable
fun DownloadProgressRow(d: DownloadState, onCancel: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth()) {
        when (d.phase) {
            DownloadState.Phase.QUEUED, DownloadState.Phase.VERIFYING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            DownloadState.Phase.DOWNLOADING -> d.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> Unit
        }
        val txt = when (d.phase) {
            DownloadState.Phase.QUEUED -> "Waiting…"
            DownloadState.Phase.DOWNLOADING -> (if (d.fileCount > 1) "File ${d.fileIndex + 1}/${d.fileCount} · " else "") +
                (d.fraction?.let { "${(it * 100).toInt()}% · " } ?: "") + formatBytes(d.downloaded) + (d.total?.let { " / ${formatBytes(it)}" } ?: "") +
                if (d.bytesPerSecond > 0) " · ${formatBytes(d.bytesPerSecond.toLong())}/s" else ""
            DownloadState.Phase.VERIFYING -> "Verifying (GGUF header, size, SHA-256)…"
            DownloadState.Phase.DONE -> "Downloaded and ready ✓"
            DownloadState.Phase.FAILED -> "Failed: ${d.error}"
            DownloadState.Phase.CANCELLED -> "Cancelled (partial file kept — Resume continues)"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(txt, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                color = if (d.phase == DownloadState.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (onCancel != null && d.active) TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

// ---------------------------------------------------------------- catalogue

/** One catalogue model with inline Download / Cancel / Use / Delete and live progress. */
@Composable
fun CatalogRow(app: OfflineAgentApp, m: CatalogModel, recommended: Boolean, installed: ModelEntity?, state: DownloadState?, notify: (String) -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.name + if (recommended) "  ★ recommended" else "", style = MaterialTheme.typography.titleSmall)
                    Text("${formatBytes(m.totalBytes)} · ${m.minRamGb} GB+ RAM · ${m.licence}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    state?.active == true -> TextButton(onClick = { app.modelDownloads.cancel(m.id) }) { Text("Cancel") }
                    installed != null -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (installed.active) Text("In use ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        else FilledTonalButton(onClick = { ModelHub.use(app, installed, notify) }) { Text("Use") }
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                    }
                    else -> Button(onClick = { app.modelDownloads.start(m) }) {
                        Text(if (state?.phase == DownloadState.Phase.CANCELLED || state?.phase == DownloadState.Phase.FAILED) "Resume" else "Download")
                    }
                }
            }
            Text(m.note, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            state?.takeIf { it.phase != DownloadState.Phase.DONE || installed == null }?.let { DownloadProgressRow(it, null) }
        }
    }
    if (confirmDelete && installed != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${m.name}?") },
        text = { Text("Frees ${formatBytes(installed.sizeBytes)}. You can download it again later.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; ModelHub.delete(app, installed, notify) }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
    )
}

/** Paste any Hugging Face GGUF link (chat / embedding / vision). */
@Composable
fun HfLinkDownload(app: OfflineAgentApp) {
    val downloads by app.modelDownloads.states.collectAsState()
    var url by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(ModelKind.CHAT) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Any GGUF from Hugging Face", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("Paste a huggingface.co …/resolve/… or …/blob/… link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModelKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
        }
        val ref = remember(url) { HfUrls.parse(url) }
        if (url.isNotBlank() && ref == null) Text("Not a Hugging Face .gguf file link.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (kind == ModelKind.VISION) Text("Vision models also need their mmproj file: download it as a second link, then attach it in Settings → Models.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { ref?.let { app.modelDownloads.startCustom(it, kind); url = "" } }, enabled = ref != null) { Text("Download") }
        downloads.values.filter { it.id.startsWith("custom-") }.forEach { d -> DownloadProgressRow(d, onCancel = { app.modelDownloads.cancel(d.id) }) }
    }
}

/** The full catalogue + link download (Agents screen, models sheet). */
@Composable
fun ModelCatalogContent(app: OfflineAgentApp, notify: (String) -> Unit, onImport: (() -> Unit)?) {
    val downloads by app.modelDownloads.states.collectAsState()
    val models by app.models.models.collectAsState(initial = emptyList())
    val rec = remember { ModelHub.recommended() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Downloaded once from Hugging Face over HTTPS, then everything runs on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ModelCatalog.all.forEach { m ->
            CatalogRow(app, m, recommended = m.id == rec.id, installed = ModelHub.installed(models, m), state = downloads[m.id], notify = notify)
        }
        // Installed models that are not in the catalogue (pasted links, imported files).
        val extra = models.filter { e -> e.role == ModelRole.CHAT.name && ModelCatalog.all.none { File(e.path).name == it.main.file } }
        if (extra.isNotEmpty()) {
            Text("Other installed chat models", style = MaterialTheme.typography.titleSmall)
            extra.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.name.removeSuffix(".gguf") + " · " + formatBytes(e.sizeBytes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.active) Text("In use ✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    else TextButton(onClick = { ModelHub.use(app, e, notify) }) { Text("Use") }
                    TextButton(onClick = { ModelHub.delete(app, e, notify) }) { Text("Delete") }
                }
            }
        }
        HorizontalDivider()
        HfLinkDownload(app)
        if (onImport != null) { HorizontalDivider(); TextButton(onClick = onImport) { Text("Import a .gguf file / manage installed models…") } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsSheet(app: OfflineAgentApp, onDismiss: () -> Unit, notify: (String) -> Unit) {
    var showImport by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        if (showImport) Column(Modifier.fillMaxHeight(0.92f)) {
            TextButton(onClick = { showImport = false }) { Text("‹ Back to downloads") }
            com.offlineagent.app.ui.library.ModelsScreen(app, notify, Modifier.weight(1f))
        } else Column(Modifier.fillMaxHeight(0.92f).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Models", style = MaterialTheme.typography.titleLarge)
            ModelCatalogContent(app, notify, onImport = { showImport = true })
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- first run

/** First launch with no chat model: recommended model for this phone, one tap, live progress; link + catalogue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingSheet(app: OfflineAgentApp, onDismiss: () -> Unit, onMore: () -> Unit) {
    val downloads by app.modelDownloads.states.collectAsState()
    val ram = remember { com.offlineagent.engine.llamacpp.DeviceInfo.totalRamBytes() }
    val rec = remember { ModelHub.recommended() }
    val st = downloads[rec.id]
    var showLink by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Welcome to ONYX", style = MaterialTheme.typography.headlineSmall)
            Text("ONYX needs one AI model. It downloads once (Wi-Fi recommended) and then works fully offline on this phone.", style = MaterialTheme.typography.bodyMedium)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Recommended for your phone (${"%.0f".format(ram / (1024.0 * 1024 * 1024))} GB RAM)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(rec.name, style = MaterialTheme.typography.titleLarge)
                    Text("${formatBytes(rec.totalBytes)} · ${rec.licence} · ${rec.note}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (st == null || !st.active && st.phase != DownloadState.Phase.DONE) {
                Button(onClick = { app.modelDownloads.start(rec) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (st?.phase == DownloadState.Phase.FAILED || st?.phase == DownloadState.Phase.CANCELLED) "Resume download" else "Download (${formatBytes(rec.totalBytes)})")
                }
            }
            st?.let { DownloadProgressRow(it, onCancel = { app.modelDownloads.cancel(rec.id) }) }
            if (st?.phase == DownloadState.Phase.DONE) Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Start") }
            AnimatedVisibility(showLink) { HfLinkDownload(app) }
            Row {
                if (!showLink) TextButton(onClick = { showLink = true }) { Text("Paste a Hugging Face link") }
                TextButton(onClick = onMore) { Text("All models") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Later") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ---------------------------------------------------------------- Home card

/** Home: active model + switcher, or live download progress, or a one-tap download of the recommended model. */
@Composable
fun ModelCard(app: OfflineAgentApp, onOpenModels: () -> Unit, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val downloads by app.modelDownloads.states.collectAsState()
    val models by app.models.models.collectAsState(initial = emptyList())
    val engine by app.inference.state.collectAsState()
    val chatModels = models.filter { it.role == ModelRole.CHAT.name }
    val active = chatModels.firstOrNull { it.active }
    val dl = downloads.values.firstOrNull { it.active }
    val cs = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                dl != null -> {
                    Text("Downloading ${dl.name}", style = MaterialTheme.typography.titleSmall)
                    DownloadProgressRow(dl, onCancel = { app.modelDownloads.cancel(dl.id) })
                }
                active != null -> {
                    var open by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { open = true }) {
                            Text("MODEL", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(active.name.removeSuffix(".gguf"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                Icon(Icons.Filled.ArrowDropDown, "Switch model")
                            }
                            val sub = when (val e = engine) {
                                is EngineState.Ready -> "Loaded · ready"
                                is EngineState.Generating -> "Working…"
                                is EngineState.Loading -> "Loading…"
                                is EngineState.Failed -> "Load failed: ${e.toString().take(60)}"
                                else -> "Sleeps until needed · ${formatBytes(active.sizeBytes)}"
                            }
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = if (engine is EngineState.Failed) cs.error else cs.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = onOpenModels) { Text("Models") }
                    }
                    Box {
                        DropdownMenu(open, { open = false }) {
                            chatModels.forEach { m ->
                                DropdownMenuItem(text = { Text(m.name.removeSuffix(".gguf") + "  ·  " + formatBytes(m.sizeBytes)) }, leadingIcon = { RadioButton(m.active, null) },
                                    onClick = { open = false; ModelHub.use(app, m, notify) })
                            }
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Download models…") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { open = false; onOpenModels() })
                        }
                    }
                    downloads.values.firstOrNull { it.phase == DownloadState.Phase.FAILED }?.let { DownloadProgressRow(it, null) }
                }
                else -> {
                    val rec = remember { ModelHub.recommended() }
                    Text("No chat model yet", style = MaterialTheme.typography.titleSmall)
                    Text("ONYX uses simple rules until a model is downloaded. Recommended for this phone: ${rec.name} (${formatBytes(rec.totalBytes)}).",
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { app.modelDownloads.start(rec) }) { Text("Download") }
                        OutlinedButton(onClick = onOpenModels) { Text("Other models / link") }
                    }
                    downloads[rec.id]?.let { DownloadProgressRow(it, null) }
                }
            }
        }
    }
    Spacer(Modifier.width(0.dp))
}
