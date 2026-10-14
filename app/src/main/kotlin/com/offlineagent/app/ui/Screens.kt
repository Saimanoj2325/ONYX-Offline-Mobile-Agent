package com.offlineagent.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offlineagent.app.data.formatBytes
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.StopReason

// ---------------------------------------------------------------- Model screen

@Composable
fun ModelScreen(ui: UiState, engine: EngineState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(uri)
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Model", style = MaterialTheme.typography.headlineSmall)
        OfflineBadge()

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1. Model file", style = MaterialTheme.typography.titleMedium)
                val stored = ui.storedModel
                if (stored != null) {
                    Text(stored.displayName, fontWeight = FontWeight.SemiBold)
                    Text("${formatBytes(stored.sizeBytes)} · stored in app-internal storage", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "No model yet. Download a GGUF (e.g. Qwen3-0.6B-Q8_0.gguf) onto the phone first, then pick it here. " +
                            "The app copies it into its private storage and never uses the internet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val imp = ui.importing
                if (imp != null) {
                    val f = imp.fraction
                    if (f != null) LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Copying… ${formatBytes(imp.copied)}" + (if (imp.total > 0) " of ${formatBytes(imp.total)}" else ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::cancelImport) { Text("Cancel copy") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { picker.launch(arrayOf("application/octet-stream", "*/*")) },
                            enabled = engine !is EngineState.Generating && engine !is EngineState.Loading,
                        ) { Text(if (stored == null) "Pick .gguf file" else "Replace model") }
                        if (stored != null) {
                            IconButton(onClick = vm::deleteModel, enabled = engine !is EngineState.Generating) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete model")
                            }
                        }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2. Runtime settings", style = MaterialTheme.typography.titleMedium)
                Text("Context length (tokens)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1024, 2048, 4096).forEach { n ->
                        FilterChip(selected = ui.settings.contextLength == n, onClick = { vm.setContextLength(n) }, label = { Text("$n") })
                    }
                }
                Text(
                    "CPU threads: ${ui.settings.threads}  (recommended ${ui.recommendedThreads} = performance cores, of ${ui.cpuCount} total)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setThreads(ui.settings.threads - 1) }) { Text("−") }
                    OutlinedButton(onClick = { vm.setThreads(ui.settings.threads + 1) }) { Text("+") }
                    TextButton(onClick = { vm.setThreads(ui.recommendedThreads) }) { Text("Reset") }
                }
                Text("Changes apply on next Load.", style = MaterialTheme.typography.labelSmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("3. Engine", style = MaterialTheme.typography.titleMedium)
                EngineStatusLine(engine)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (engine) {
                        is EngineState.Ready, is EngineState.Generating ->
                            OutlinedButton(onClick = vm::unloadModel, enabled = engine is EngineState.Ready) { Text("Unload") }
                        is EngineState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                        else -> Button(onClick = vm::loadModel, enabled = ui.storedModel != null && ui.importing == null) {
                            Text(if (engine is EngineState.Failed) "Retry load" else "Load model")
                        }
                    }
                }
                val info = (engine as? EngineState.Ready)?.model ?: (engine as? EngineState.Generating)?.model
                if (info != null) {
                    InfoRow("Model", info.description)
                    InfoRow("Weights", formatBytes(info.sizeBytes) + " · %.2f B params".format(info.parameterCount / 1e9))
                    InfoRow("Context", "${info.contextLength} (trained ${info.trainedContextLength})")
                    InfoRow("Threads", "${info.threads}")
                    InfoRow("Template", if (info.chatTemplate?.contains("<|im_start|>") == true) "ChatML / Qwen3" else "fallback ChatML")
                    InfoRow("Backend", info.backendInfo.ifBlank { "CPU" })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Device CPU features", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::loadSystemInfo) { Icon(Icons.Filled.Info, contentDescription = "Show CPU info") }
                }
                ui.systemInfo?.let {
                    SelectionContainer { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.widthIn(min = 80.dp), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun EngineStatusLine(engine: EngineState) {
    val (text, color) = when (engine) {
        is EngineState.Unloaded -> "Not loaded" to MaterialTheme.colorScheme.outline
        is EngineState.Loading -> "Loading…" to MaterialTheme.colorScheme.tertiary
        is EngineState.Ready -> "Ready" to MaterialTheme.colorScheme.primary
        is EngineState.Generating -> "Generating…" to MaterialTheme.colorScheme.primary
        is EngineState.Failed -> "Load failed: ${engine.message}" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, RoundedCornerShape(5.dp)))
        Spacer(Modifier.size(8.dp))
        Text(text, color = if (engine is EngineState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun OfflineBadge() {
    AssistChip(onClick = {}, label = { Text("100% on-device · no internet permission") })
}

// ---------------------------------------------------------------- Chat screen

@Composable
fun ChatScreen(ui: UiState, engine: EngineState, vm: MainViewModel, onOpenModel: () -> Unit, modifier: Modifier = Modifier) {
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val last = ui.messages.lastOrNull()

    // Keep the newest text in view while streaming.
    LaunchedEffect(ui.messages.size, last?.text?.length, last?.reasoning?.length) {
        if (ui.messages.isNotEmpty()) listState.scrollToItem(ui.messages.lastIndex, Int.MAX_VALUE / 2)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        // Header: status, thinking toggle, live speed
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                EngineStatusLine(engine)
                val speed = last?.takeIf { it.role == Role.ASSISTANT }?.let { m ->
                    m.stats?.let { "%.1f tok/s".format(it.tokensPerSecond) }
                        ?: if (m.streaming && m.liveTokensPerSecond > 0) "%.1f tok/s".format(m.liveTokensPerSecond) else null
                }
                if (speed != null) Text(speed, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text("Thinking", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.size(4.dp))
            Switch(checked = ui.settings.thinking, onCheckedChange = vm::setThinking, enabled = !ui.generating)
            IconButton(onClick = vm::clearChat, enabled = !ui.generating && ui.messages.isNotEmpty()) {
                Icon(Icons.Filled.Refresh, contentDescription = "New chat")
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (ui.messages.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (engine is EngineState.Ready) {
                        Text("Ask anything. Runs fully on this phone.", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (ui.settings.thinking) "Thinking ON: better reasoning, slower." else "Thinking OFF (/no_think): fastest replies.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text("No model loaded.", style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = onOpenModel) { Text("Go to Model") }
                    }
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(ui.messages, key = { it.id }) { m -> MessageBubble(m) }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (engine is EngineState.Ready || engine is EngineState.Generating) "Message" else "Load a model first") },
                maxLines = 5,
                enabled = engine is EngineState.Ready || engine is EngineState.Generating,
            )
            Spacer(Modifier.size(8.dp))
            if (ui.generating) {
                FilledIconButton(
                    onClick = vm::stop,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    // Stop symbol (filled square)
                    Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onError, RoundedCornerShape(2.dp)))
                }
            } else {
                FilledIconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank() && engine is EngineState.Ready,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(m: UiMessage) {
    val isUser = m.role == Role.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ),
            modifier = Modifier.widthIn(max = 340.dp).animateContentSize(),
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!isUser && (m.reasoning.isNotBlank() || m.thinkingInProgress)) {
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        if (m.thinkingInProgress) "Thinking…" else if (expanded) "Hide reasoning" else "Show reasoning",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                    if (expanded || m.thinkingInProgress) {
                        Text(m.reasoning, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                    }
                }
                when {
                    m.text.isNotEmpty() -> SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
                    m.streaming && !m.thinkingInProgress -> Text("…", style = MaterialTheme.typography.bodyMedium)
                }
                m.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                m.stats?.let { s ->
                    val stop = when (s.stopReason) {
                        StopReason.CANCELLED -> " · stopped"
                        StopReason.MAX_TOKENS -> " · length limit"
                        StopReason.CONTEXT_FULL -> " · context full"
                        StopReason.ERROR -> " · error"
                        StopReason.END_OF_TURN -> ""
                    }
                    Text(
                        "${s.generatedTokens} tok · %.1f tok/s · prompt ${s.promptTokens} tok in %.1fs".format(
                            s.tokensPerSecond, s.promptMillis / 1000.0,
                        ) + stop,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
fun Spacer8() = Spacer(Modifier.height(8.dp))
