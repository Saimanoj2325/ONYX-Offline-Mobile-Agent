package com.offlineagent.app.ui.onyx

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.ui.theme.agentColor
import com.offlineagent.app.ui.theme.riskColor
import com.offlineagent.core.agents.AgentRole
import com.offlineagent.core.agents.DeviceSnapshot
import com.offlineagent.core.agents.riskOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Agents & settings: device tier + runtime plan, model picks, the agent roster, tools with badges, privacy & audit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentsScreen(app: OfflineAgentApp, onOpenSettings: () -> Unit, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val onyx = app.onyx
    val plan by onyx.runtimePlan.collectAsState()
    val prefs by onyx.prefs.values.collectAsState()
    val q by onyx.queue.stats.collectAsState()
    val thermal by onyx.monitor.thermal.collectAsState()
    val saver by onyx.monitor.powerSave.collectAsState()
    val audit by remember { onyx.dao.observeAudit() }.collectAsState(initial = emptyList())
    var snap by remember { mutableStateOf<DeviceSnapshot?>(null) }
    var showAllTools by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(thermal, saver) { snap = withContext(Dispatchers.IO) { onyx.monitor.snapshot() }; onyx.refreshDevice() }
    val tools = remember(showAllTools) { onyx.gateway.specs().sortedBy { it.name } }
    val netTools = remember { onyx.networkTools() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        notify(if (r.values.all { it }) "Permissions granted" else "Some permissions were denied; those features stay off.")
    }
    fun open(intent: Intent) = runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { notify("This phone has no screen for that.") }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Agents", style = MaterialTheme.typography.headlineMedium) }
        item {
            SectionCard("This phone") {
                val s = snap
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan?.let { Pill(it.tier.label, MaterialTheme.colorScheme.primary) }
                    Pill("Thermal: ${thermal.name.lowercase()}", if (thermal.ordinal >= 2) NetworkAmber else OfflineGreen)
                    if (saver) Pill("Battery saver", NetworkAmber)
                }
                if (s != null && s.freeStorageMb >= 0) Text("Storage free: ${"%.1f".format(s.freeStorageMb / 1024.0)} GB · CPU: " +
                    (if (s.dotprod) "dot-product" else "no dot-product") + (if (s.i8mm) " + i8mm" else " (no i8mm)"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s != null) Text("${"%.1f".format(s.totalRamMb / 1024.0)} GB RAM (${"%.1f".format(s.availRamMb / 1024.0)} GB free) · ${s.cpuCores} cores (${s.performanceCores} fast) · ${s.soc} · Android API ${s.sdkInt}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                plan?.let { p ->
                    Text("Recommended runtime: ${p.contextLength / 1024}K context · ${p.threads} threads · batch ${p.batch}", style = MaterialTheme.typography.bodyMedium)
                    p.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = NetworkAmber) }
                    HorizontalDivider()
                    Text("Recommended models", style = MaterialTheme.typography.titleSmall)
                    p.picks.forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name + if (m.primary) "  ★" else "", style = MaterialTheme.typography.bodyMedium)
                                Text(m.why + if (m.residentMb > 0) " · ~${"%.1f".format(m.residentMb / 1024.0)} GB in use" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                m.warning?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = NetworkAmber) }
                            }
                        }
                    }
                    Text("All agents share one loaded model; it is never loaded twice.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onOpenSettings) { Text("Import a file / model settings") }
                }
                Text("Inference queue: ${q.completed} calls done · ${q.queued} waiting · ${q.prefixReuses} prompt-cache reuses" + (q.pausedReason?.let { " · paused ($it)" } ?: ""),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Phase 5b: the download catalogue lives here again — Download / Use / Delete with live progress.
        item {
            SectionCard("Models") {
                com.offlineagent.app.ui.models.ModelCatalogContent(app, notify, onImport = onOpenSettings)
            }
        }
        // Phase 6: on-phone benchmark (results stored on the phone, newest first).
        item {
            val bs by onyx.benchmark.state.collectAsState()
            val results by remember { onyx.db.phase6().observeBench() }.collectAsState(initial = emptyList())
            SectionCard("Benchmark") {
                Text("Reloads the chat model, then measures prompt speed, generation speed (n-gram speculation off and on), load time, peak memory and heat.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (val st = bs) {
                    is com.offlineagent.app.onyx.Benchmark.State.Running -> Text(st.step, style = MaterialTheme.typography.bodyMedium)
                    is com.offlineagent.app.onyx.Benchmark.State.Failed -> Text(st.message, style = MaterialTheme.typography.bodySmall, color = NetworkAmber)
                    else -> Unit
                }
                Button(enabled = bs !is com.offlineagent.app.onyx.Benchmark.State.Running, onClick = { scope.launch { onyx.benchmark.run() } }) { Text("Run benchmark") }
                results.take(6).forEach { r ->
                    Column {
                        Text("${r.model} · ${r.threads} threads · ${r.contextLength / 1024}K", style = MaterialTheme.typography.bodyMedium)
                        Text("Prompt ${"%.1f".format(r.promptTps)} tok/s · Gen ${"%.2f".format(r.genTps)} tok/s · with speculation ${"%.2f".format(r.specGenTps)} tok/s (${r.specAccepted}/${r.specDrafted} accepted)",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Load ${r.loadMs} ms · peak ${r.peakRssMb} MB · ${r.thermal.lowercase()} · battery ${r.battery}% · " +
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at)),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (results.isNotEmpty()) TextButton(onClick = { scope.launch { onyx.db.phase6().clearBench() } }) { Text("Clear results") }
            }
        }
        item {
            SectionCard("The team") {
                AgentRole.entries.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(agentColor(r)))
                        Spacer(Modifier.width(10.dp))
                        Text(r.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
                        Text(r.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            SectionCard("Behaviour") {
                ToggleRow("Model double-checks answers", "Verifier asks the model too (slower, more reliable)", prefs.llmVerification) { v -> onyx.prefs.update { it.copy(llmVerification = v) } }
                ToggleRow("Speak answers", "Text-to-speech after each task", prefs.voiceReplies) { v -> onyx.prefs.update { it.copy(voiceReplies = v) } }
                ToggleRow("Pause when hot", "Hold model work at thermal SEVERE or worse", prefs.thermalGuard) { v -> onyx.prefs.update { it.copy(thermalGuard = v) } }
                ToggleRow("Material You colours", "Use your wallpaper colours (Android 12+)", prefs.dynamicColor) { v -> onyx.prefs.update { it.copy(dynamicColor = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("DARK" to "Dark", "LIGHT" to "Light", "SYSTEM" to "System").forEach { (k, l) ->
                        FilterChip(prefs.theme == k, { onyx.prefs.update { it.copy(theme = k) } }, label = { Text(l) })
                    }
                }
            }
        }
        item {
            SectionCard("Permissions (all optional)") {
                Text("Each feature asks only when you turn it on. Android does not let any app toggle Wi-Fi or mobile data, or read other apps' data.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = {
                        perms.launch(buildList { add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.WRITE_CALENDAR); add(Manifest.permission.READ_CALENDAR)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS) }.toTypedArray())
                    }) { Text("Mic, calendar, notifications") }
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access") }
                    if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = { open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + ctx.packageName))) }) { Text("Exact alarms") }
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))) }) { Text("Brightness") }
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("Do Not Disturb") }
                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Screen reading") }
                }
            }
        }
        item {
            SectionCard("Tools (${tools.size})") {
                Text("Badges show what works offline. Risky tools always ask on a confirmation sheet first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (if (showAllTools) tools else tools.take(8)).forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val r = riskOf(t)
                        Pill(r.label, riskColor(r), dot = false)
                        NetworkBadge(t.name in netTools)
                    }
                }
                TextButton(onClick = { showAllTools = !showAllTools }) { Text(if (showAllTools) "Show fewer" else "Show all tools") }
            }
        }
        item {
            SectionCard("Privacy & audit log") {
                Text("Everything stays on this phone. Every approval, denial, sensitive action and queued network call is logged here.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (audit.isEmpty()) Text("No entries yet.", style = MaterialTheme.typography.bodySmall)
                audit.take(30).forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(riskColor(runCatching { com.offlineagent.core.agents.Risk.valueOf(a.risk) }.getOrDefault(com.offlineagent.core.agents.Risk.NONE))))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${a.actor}: ${a.action}", style = MaterialTheme.typography.bodySmall)
                            Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(a.at)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (audit.isNotEmpty()) TextButton(onClick = { scope.launch { onyx.dao.clearAudit() } }) { Text("Clear audit log") }
            }
        }
        item { OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("All settings (models, performance, phone actions, MCP)") } }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value, onChange)
    }
}
