package com.offlineagent.app.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlineagent.app.BuildConfig
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.automation.AutomationController
import com.offlineagent.app.automation.OfflineAccessibilityService
import com.offlineagent.app.data.ModelRole
import com.offlineagent.app.phone.NotificationCaptureService
import com.offlineagent.app.phone.SafFilesPort
import com.offlineagent.app.ui.library.ModelsScreen
import com.offlineagent.app.vault.VaultUnlockActivity
import com.offlineagent.core.data.AppSettings
import com.offlineagent.core.inference.ContextPlanner
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.runtime.phone.CustomField
import com.offlineagent.core.runtime.phone.Macro
import com.offlineagent.core.runtime.phone.MacroSchedule
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.Profile
import com.offlineagent.core.runtime.phone.ProfileKey
import com.offlineagent.engine.llamacpp.DeviceInfo
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Page(val title: String, val hint: String) {
    MODELS("Models", "Import, choose and load chat, embedding, vision and draft models"),
    PERFORMANCE("Performance & long context", "Context window (8K default; 16K+ opt-in), KV cache, speed-ups, memory"),
    PHONE("Phone actions", "Alarms, reminders, apps, message drafts, device settings, files, notifications, screen automation"),
    PROFILE("Profile & autofill", "Encrypted profile used to fill forms in any app"),
    AUTOMATION("Automation & macros", "Teach by demonstration, schedules, full action log, kill switch"),
    MCP("MCP tool servers", "Built-in on-device servers, other apps' servers" + if (BuildConfig.NETWORK_MCP) ", LAN/remote servers" else ""),
    DIAGNOSTICS("Diagnostics & crash logs", "Self-test, safe mode, compatibility CPU kernels, crash reports, export logs"),
}

@Composable
fun SettingsScreen(app: OfflineAgentApp, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val p = page?.let { Page.valueOf(it) }
    if (p != null) BackHandler { page = null }
    when (p) {
        null -> LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text(if (BuildConfig.NETWORK_MCP) "Connected build: the internet permission is used only for MCP servers you add. Models still run on this phone."
                    else "Offline build: this app has no internet permission. Everything runs and stays on this phone.", style = MaterialTheme.typography.bodySmall)
            }
            items(Page.entries) { pg ->
                Card(Modifier.fillMaxWidth().clickable { page = pg.name }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(pg.title, style = MaterialTheme.typography.titleMedium)
                        Text(pg.hint, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        else -> Column(modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                TextButton(onClick = { page = null }) { Text("‹ Settings") }
                Text(p.title, style = MaterialTheme.typography.titleLarge)
            }
            when (p) {
                Page.MODELS -> ModelsScreen(app, notify)
                Page.PERFORMANCE -> PerformancePage(app, notify)
                Page.PHONE -> PhoneActionsPage(app, notify)
                Page.PROFILE -> ProfilePage(app, notify)
                Page.AUTOMATION -> AutomationPage(app, notify)
                Page.MCP -> McpPage(app, notify)
                Page.DIAGNOSTICS -> DiagnosticsPage(app, notify)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
}

// ---------------------------------------------------------------- performance & long context

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PerformancePage(app: OfflineAgentApp, notify: (String) -> Unit) {
    val s by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val models by app.models.models.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val chatModel = models.firstOrNull { it.role == ModelRole.CHAT.name && it.active }
    // GGUF header reads happen off the main thread (Settings must open instantly, with or without a model).
    val plan by androidx.compose.runtime.produceState(ContextPlanner.plan(DeviceInfo.totalRamBytes(), null, s.ubatchSize), chatModel?.path, s.ubatchSize) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.engineController.planFor(chatModel?.path, s) }.getOrElse { ContextPlanner.plan(DeviceInfo.totalRamBytes(), null) } }
    }
    val arch by androidx.compose.runtime.produceState<com.offlineagent.core.inference.ModelArch?>(null, chatModel?.path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { chatModel?.path?.let { app.engineController.archOf(it) } }
    }
    val ctx = app.engineController.effectiveContext(s, plan)
    val kv = app.engineController.kvFor(s, chatModel?.path, ctx)
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { if (!app.settings.update(f)) notify("Could not save the setting") }
    /** Settings that change how the model is loaded: save, then unload + reload in the background (never blocks the UI). */
    fun updReload(f: (AppSettings) -> AppSettings) = app.appScope.launch {
        if (!app.settings.update(f)) { notify("Could not save the setting"); return@launch }
        notify(app.engineController.applySettings())
    }
    val health by app.engineController.health.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Phone: ${ContextPlanner.gb(DeviceInfo.totalRamBytes())} RAM (${plan.tier.label} class) · ${DeviceInfo.recommendedThreads()} performance cores " +
                DeviceInfo.performanceCoreIds().let { if (it.isEmpty()) "" else "(CPU ${it.joinToString()})" }, style = MaterialTheme.typography.bodySmall)
            Text("Model: ${chatModel?.name ?: "none selected"}" + (arch?.let { " · ${it.layers} layers, trained ${it.trainedContext / 1024}K context" } ?: ""), style = MaterialTheme.typography.bodySmall)
            Section("Context window")
            Text("Default for this phone: ${plan.recommendedContext / 1024}K. Bigger windows remember more of a long chat or document but use RAM and make the first answer slower.",
                style = MaterialTheme.typography.bodySmall)
            if (s.safeMode || health.compatCpu) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(10.dp)) {
                    if (s.safeMode) {
                        Text("Safe mode is on", style = MaterialTheme.typography.titleSmall)
                        Text((s.safeModeReason.ifBlank { "Turned on after a problem." }) + " Uses 4K context, f16 KV, no flash attention, no prompt cache.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { app.appScope.launch { app.engineController.exitSafeMode(); notify(app.engineController.applySettings()) } }) { Text("Exit safe mode") }
                    }
                    if (health.compatCpu) Text("Compatibility CPU kernels are on (slower, most compatible). Change under Settings → Diagnostics.", style = MaterialTheme.typography.bodySmall)
                }
            }
            FilterChip(selected = s.contextLength == 0, onClick = { updReload { it.copy(contextLength = 0) } }, label = { Text("Auto (${plan.recommendedContext / 1024}K)") })
            SwitchRow("Allow large context (16K+, experimental)", "Bigger windows use much more RAM and are slower to start. Off = 8K maximum.", s.largeContext) { v ->
                updReload { it.copy(largeContext = v, contextLength = if (!v && it.contextLength > ContextPlanner.LARGE_CONTEXT) 0 else it.contextLength) }
            }
        }
        items(plan.options) { o ->
            val est = arch?.let { ContextPlanner.estimate(it, o.tokens, if (s.kvCache == "AUTO") ContextPlanner.kvFor(DeviceInfo.totalRamBytes(), it, o.tokens, s.ubatchSize) else kv, s.ubatchSize) } ?: o.estimate
            val selected = s.contextLength == o.tokens
            val allowed = !o.blocked && (!o.needsYarn || s.yarn) && (o.tokens <= ContextPlanner.LARGE_CONTEXT || s.largeContext)
            Card(Modifier.fillMaxWidth().clickable(enabled = allowed) { updReload { it.copy(contextLength = o.tokens) } },
                colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${o.tokens / 1024}K tokens" + (if (selected) "  ✓" else "") + (if (o.needsYarn) "  · YaRN (experimental)" else ""), style = MaterialTheme.typography.titleSmall)
                    Text(est?.let { "≈ ${ContextPlanner.gb(it.totalBytes)} total (model ${ContextPlanner.gb(it.weightsBytes)}, KV ${ContextPlanner.gb(it.kvBytes)}, buffers ${ContextPlanner.gb(it.computeBytes)})" } ?: "select a chat model for estimates",
                        style = MaterialTheme.typography.bodySmall)
                    o.warning?.let { Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    if (o.needsYarn && !s.yarn) Text("Turn on YaRN below to use this size.", style = MaterialTheme.typography.bodySmall)
                    if (o.tokens > ContextPlanner.LARGE_CONTEXT && !s.largeContext) Text("Turn on “Allow large context” to use this size.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            SwitchRow("YaRN rope scaling (experimental)", "Stretches the model beyond its trained context (64K/128K). Quality and speed on phones are untested.", s.yarn) { v -> updReload { it.copy(yarn = v) } }
            Section("KV cache precision")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.kvCache == "AUTO", onClick = { updReload { it.copy(kvCache = "AUTO") } }, label = { Text("Auto (f16)") })
                KvCacheType.entries.forEach { t ->
                    FilterChip(selected = s.kvCache == t.name, onClick = { updReload { it.copy(kvCache = t.name) } }, label = { Text(t.label) })
                }
            }
            Text("Auto = f16: the most reliable setting and the fastest prompt reading on phone CPUs. q8_0 halves KV memory, q4_0 quarters it (lower quality); both force flash attention and are experimental on phones.", style = MaterialTheme.typography.bodySmall)
            Section("Speed")
            Text("Flash attention", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("AUTO", "ON", "OFF").forEach { v -> FilterChip(selected = s.flashAttention == v, onClick = { updReload { it.copy(flashAttention = v) } }, label = { Text(v.lowercase()) }) } }
            Text("Batch / micro-batch (prompt reading speed vs. memory)", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(256 to 256, 512 to 512, 1024 to 512, 2048 to 512).forEach { (b, u) ->
                    FilterChip(selected = s.batchSize == b && s.ubatchSize == u, onClick = { updReload { it.copy(batchSize = b, ubatchSize = u) } }, label = { Text("$b/$u") })
                }
            }
            val rec = DeviceInfo.recommendedThreads()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CPU threads: ${if (s.threads == 0) "auto ($rec, pinned to performance cores)" else s.threads}", modifier = Modifier.weight(1f))
                TextButton(onClick = { upd { it.copy(threads = ((if (it.threads == 0) rec else it.threads) - 1).coerceAtLeast(1)) } }) { Text("−") }
                TextButton(onClick = { upd { it.copy(threads = ((if (it.threads == 0) rec else it.threads) + 1).coerceAtMost(8)) } }) { Text("+") }
                TextButton(onClick = { upd { it.copy(threads = 0) } }) { Text("Auto") }
            }
            val hasDraft = models.any { it.role == ModelRole.DRAFT.name && it.active }
            SwitchRow("Speculative decoding", if (hasDraft) "A small draft model guesses ${s.draftTokens} tokens ahead; the main model checks them in one pass. Same output, often faster."
                else "Add a draft model first (Models → Draft, e.g. Qwen3-0.6B for Qwen3-1.7B/4B).", s.speculative, enabled = hasDraft) { v -> upd { it.copy(speculative = v) } }
            if (s.speculative) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(4, 6, 8).forEach { n -> FilterChip(selected = s.draftTokens == n, onClick = { upd { it.copy(draftTokens = n) } }, label = { Text("$n draft tokens") }) } }
            SwitchRow("Prompt cache", "Keeps the system prompt's KV cache in memory and on disk, so the first answer after a restart starts much faster.", s.prefixCache) { v -> upd { it.copy(prefixCache = v) } }
            SwitchRow("Keep model warm", "Stays loaded in the background; freed only when Android is short of memory.", s.keepWarm) { v -> upd { it.copy(keepWarm = v) } }
            Section("Answers & memory")
            Text("Rich answers (OpenUI components: tables, charts, forms…)", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("AUTO" to "Auto", "FULL" to "All components", "COMPACT" to "Core only", "OFF" to "Off (Markdown)").forEach { (v, l) ->
                    FilterChip(selected = s.richUi == v, onClick = { upd { it.copy(richUi = v) } }, label = { Text(l) })
                }
            }
            Text("Auto: all components for 3B+ models, the core set (shorter prompt) for smaller ones.", style = MaterialTheme.typography.bodySmall)
            Text("Maximum answer length", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1024, 2048, 4096, 8192).forEach { n ->
                    FilterChip(selected = s.maxOutputTokens == n, onClick = { upd { it.copy(maxOutputTokens = n) } }, label = { Text(if (n == 0) "Auto (${plan.maxOutputTokens / 1024}K)" else "${n / 1024}K") })
                }
            }
            SwitchRow("Summarise old messages", "When a chat fills ~75% of the window, the oldest turns are folded into a short summary.", s.autoSummarize) { v -> upd { it.copy(autoSummarize = v) } }
            SwitchRow("Long-term memory", "Old turns, summaries and your documents are indexed on the phone and paged back in when relevant — memory beyond the window.", s.longTermMemory) { v -> upd { it.copy(longTermMemory = v) } }
            TextButton(onClick = { scope.launch { app.chatMemory.clear(); notify("Long-term memory cleared") } }) { Text("Clear long-term memory") }
            Section("Apply")
            Text("Current: ${ctx / 1024}K context, KV ${kv.label}, flash attention ${s.flashAttention.lowercase()}, batch ${s.batchSize}/${s.ubatchSize}" +
                if (s.speculative) ", speculative on" else "", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { app.appScope.launch { notify(app.engineController.applySettings()) } }) { Text("Reload model with these settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---------------------------------------------------------------- phone actions

@Composable
private fun PhoneActionsPage(app: OfflineAgentApp, notify: (String) -> Unit) {
    val s by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var disclosure by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { SafFilesPort.persist(ctx, uri); tick++; notify("Folder granted") }
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    fun toggle(g: PhoneGroup, on: Boolean) = scope.launch { app.settings.update { it.copy(phoneGroups = if (on) it.phoneGroups + g.name else it.phoneGroups - g.name) } }
    val tools = remember { app.mcp.phone.toolGroups.entries.groupBy({ it.value }, { it.key }) }

    LaunchedEffect(tick) { }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Each group adds typed tools the assistant can use. Every action that changes something, opens another app or reads personal data asks for your approval first (you can “always allow” the harmless ones). Messages and calls are only drafted — you press Send.",
                style = MaterialTheme.typography.bodySmall)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    SwitchRow("Automation master switch (kill switch)", "Off = every screen automation, macro and form fill is refused immediately.", s.automationEnabled) { v ->
                        scope.launch { app.settings.update { it.copy(automationEnabled = v) } }; if (!v) app.automation.stopAll()
                    }
                }
            }
        }
        items(PhoneGroup.entries) { g ->
            val on = g.name in s.phoneGroups
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    SwitchRow(g.title, g.description, on) { v ->
                        if (v && g == PhoneGroup.SCREEN_AUTOMATION && !OfflineAccessibilityService.isEnabled(ctx)) disclosure = true
                        toggle(g, v)
                        if (v) when (g) {
                            PhoneGroup.CALENDAR_CONTACTS -> permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR))
                            PhoneGroup.ALARMS -> if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                            else -> Unit
                        }
                    }
                    Text("Tools: " + (tools[g]?.joinToString() ?: "—"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    Text("Android access: ${g.androidAccess}", style = MaterialTheme.typography.labelSmall)
                    if (on) when (g) {
                        PhoneGroup.CALENDAR_CONTACTS -> if (!has(Manifest.permission.READ_CONTACTS) || !has(Manifest.permission.READ_CALENDAR))
                            TextButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR)) }) { Text("Grant contacts & calendar") }
                        PhoneGroup.DEVICE_SETTINGS -> {
                            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            if (!nm.isNotificationPolicyAccessGranted) TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }) { Text("Grant Do Not Disturb access (for DND / silent)") }
                        }
                        PhoneGroup.NOTIFICATIONS -> if (!NotificationCaptureService.isEnabled(ctx))
                            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Allow notification access in Android settings") }
                            else Text("Notification access on. Notifications are kept in memory only, never saved.", style = MaterialTheme.typography.bodySmall)
                        PhoneGroup.SCREEN_AUTOMATION -> {
                            if (!OfflineAccessibilityService.isEnabled(ctx)) TextButton(onClick = { disclosure = true }) { Text("Turn on the accessibility service…") }
                            else Row { Text("Service on.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = { OfflineAccessibilityService.instance?.disableSelf() }) { Text("Turn service off") } }
                            SwitchRow("Use the vision model on screenshots", "Slower; needs a vision model. Off = view tree only.", s.screenVision) { v -> scope.launch { app.settings.update { it.copy(screenVision = v) } } }
                            Text("Blocked by default: banking, UPI and payment apps (" + com.offlineagent.core.runtime.phone.AutomationPolicy.BLOCKED_PACKAGES.size + " known + any app whose package mentions bank/upi/wallet/pay apps).",
                                style = MaterialTheme.typography.bodySmall)
                            if (s.automationAllowedApps.isNotEmpty()) Row { Text("Allowed anyway: ${s.automationAllowedApps.joinToString()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { scope.launch { app.settings.update { it.copy(automationAllowedApps = emptySet()) } } }) { Text("Reset") } }
                        }
                        PhoneGroup.AUTOFILL -> {
                            val am = ctx.getSystemService(AutofillManager::class.java)
                            if (am?.hasEnabledAutofillServices() != true) TextButton(onClick = {
                                ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + ctx.packageName)))
                            }) { Text("Make Offline Agent your autofill service") } else Text("Autofill service on.", style = MaterialTheme.typography.bodySmall)
                        }
                        PhoneGroup.FILES -> {
                            val grants = remember(tick) { ctx.contentResolver.persistedUriPermissions }
                            grants.forEach { g2 ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(Uri.decode(g2.uri.lastPathSegment ?: g2.uri.toString()), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { SafFilesPort.revoke(ctx, g2.uri); tick++ }) { Text("Remove") }
                                }
                            }
                            TextButton(onClick = { folderPicker.launch(null) }) { Text("Add folder…") }
                        }
                        else -> Unit
                    }
                }
            }
        }
        item {
            Text("Play policy notes: no SMS/call-log permissions (drafts use intents), no QUERY_ALL_PACKAGES, accessibility only with this disclosure and on user request, notifications kept in memory only. See docs/PHASE3.md.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 16.dp))
        }
    }

    if (disclosure) AlertDialog(
        onDismissRequest = { disclosure = false },
        title = { Text("Screen automation — please read") },
        text = {
            Text("Offline Agent will use Android's Accessibility service to READ the content of the screen and to TAP, TYPE and SCROLL in other apps — " +
                "only when you ask it to do a task.\n\n• Everything stays on this phone; this build has no internet access.\n" +
                "• Each step is shown in a floating panel with Pause and Stop.\n• It asks before anything that submits, pays, sends, books or deletes.\n" +
                "• Banking and payment apps are blocked. CAPTCHAs are never solved; passwords are never typed; one-time codes only if you give them.\n" +
                "• Every action is written to the action log (Settings → Automation).\n• Turn it off at any time (kill switch above, or Android settings).\n\n" +
                "Next: Android settings → Accessibility → Offline Agent screen automation → On.")
        },
        confirmButton = { TextButton(onClick = { disclosure = false; AutomationController.openAccessibilitySettings(ctx) }) { Text("I understand, open settings") } },
        dismissButton = { TextButton(onClick = { disclosure = false; scope.launch { app.settings.update { it.copy(phoneGroups = it.phoneGroups - PhoneGroup.SCREEN_AUTOMATION.name) } } }) { Text("Not now") } },
    )
}

// ---------------------------------------------------------------- profile vault

@Composable
private fun ProfilePage(app: OfflineAgentApp, notify: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by app.vault.profile.collectAsStateWithLifecycle()
    val unlock = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != android.app.Activity.RESULT_OK) notify("Profile stays locked")
    }
    var edits by remember(profile) { mutableStateOf(profile?.values ?: emptyMap()) }
    var custom by remember(profile) { mutableStateOf(profile?.custom ?: emptyList()) }
    var newLabel by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newSensitive by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Your details are encrypted with a key in Android's secure hardware" + (if (app.vault.deviceSecure) " that needs your fingerprint/face or screen lock." else ". Set a screen lock so the key also requires your fingerprint or PIN.") +
                " Used by autofill and by “fill this form”. No card numbers or passwords unless you add them yourself.", style = MaterialTheme.typography.bodySmall)
            if (profile == null) Button(onClick = { unlock.launch(Intent(ctx, VaultUnlockActivity::class.java)) }, modifier = Modifier.padding(top = 8.dp)) { Text(if (app.vault.exists) "Unlock profile" else "Create profile") }
            else Row { OutlinedButton(onClick = { app.vault.lock() }) { Text("Lock now") }; Spacer(Modifier.padding(4.dp)); TextButton(onClick = { confirmWipe = true }) { Text("Delete profile") } }
        }
        if (profile != null) {
            ProfileKey.entries.groupBy { it.section }.forEach { (section, keys) ->
                item { Section(section) }
                items(keys) { k ->
                    OutlinedTextField(value = edits[k.name] ?: "", onValueChange = { v -> edits = edits + (k.name to v) }, label = { Text(k.label) },
                        modifier = Modifier.fillMaxWidth(), singleLine = k !in setOf(ProfileKey.SUMMARY, ProfileKey.EXPERIENCE, ProfileKey.PROJECTS, ProfileKey.SKILLS))
                }
            }
            item {
                Section("Custom fields")
                custom.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.label}: ${if (c.sensitive) "••••" else c.value}" + if (c.sensitive) " (sensitive)" else "", Modifier.weight(1f))
                        TextButton(onClick = { custom = custom - c }) { Text("Remove") }
                    }
                }
                OutlinedTextField(newLabel, { newLabel = it }, label = { Text("Field label (as forms call it)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(newValue, { newValue = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(newSensitive, { newSensitive = it }); Text("Sensitive (only offered when a field's label matches exactly; never guessed)", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = newLabel.isNotBlank() && newValue.isNotBlank(), onClick = { custom = custom + CustomField(newLabel.trim(), newValue.trim(), newSensitive); newLabel = ""; newValue = ""; newSensitive = false }) { Text("Add field") }
                Button(onClick = {
                    scope.launch {
                        try { app.vault.save(Profile(edits.filterValues { it.isNotBlank() }, custom)); notify("Profile saved (encrypted)") }
                        catch (e: Throwable) { notify("Unlock again to save (${e.javaClass.simpleName})"); unlock.launch(Intent(ctx, VaultUnlockActivity::class.java)) }
                    }
                }, modifier = Modifier.padding(vertical = 12.dp)) { Text("Save profile") }
            }
        }
    }
    if (confirmWipe) AlertDialog(onDismissRequest = { confirmWipe = false }, title = { Text("Delete your profile?") }, text = { Text("The encrypted file and its key are destroyed.") },
        confirmButton = { TextButton(onClick = { app.vault.wipe(); confirmWipe = false; notify("Profile deleted") }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } })
}

// ---------------------------------------------------------------- automation & macros

@Composable
private fun AutomationPage(app: OfflineAgentApp, notify: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val macros by app.automation.macros.macros.collectAsStateWithLifecycle()
    val log by app.automationLog.recent.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Macro?>(null) }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Teach by demonstration: tap Record, do the task in any app, then Stop & save. The macro becomes a skill you can run by name, with optional {{values}} and a schedule. Scheduled runs never press submit/pay/send — they stop and notify you.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = {
                    if (!OfflineAccessibilityService.isEnabled(ctx)) notify("Turn on screen automation first (Phone actions)")
                    else try { app.automation.startRecording { m -> if (m != null) editing = m } } catch (e: Throwable) { notify(e.message ?: "Cannot record") }
                }) { Text("Record a macro") }
                OutlinedButton(onClick = { app.automation.stopAll(); notify("Stopped") }) { Text("Stop everything") }
            }
            Section("Macros")
            if (macros.isEmpty()) Text("No macros yet.", style = MaterialTheme.typography.bodySmall)
        }
        items(macros, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(m.name, style = MaterialTheme.typography.titleSmall)
                    Text("${m.steps.size} steps in ${m.startPackage}" + (if (m.params.isNotEmpty()) " · values: ${m.params.joinToString()}" else "") +
                        (if (m.schedule.kind != "none") " · ${m.schedule.kind} %02d:%02d".format(m.schedule.hour, m.schedule.minute) else "") +
                        (if (m.hasIrreversibleSteps) " · has steps that need approval" else ""), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { scope.launch { notify(runCatching { app.automation.play(m, emptyList(), unattended = false) }.getOrElse { it.message ?: "failed" }) } }, enabled = m.params.isEmpty()) { Text("Run") }
                        TextButton(onClick = { editing = m }) { Text("Edit") }
                        TextButton(onClick = { scope.launch { app.automation.deleteMacro(m) } }) { Text("Delete") }
                    }
                }
            }
        }
        item {
            Section("Action log")
            Row { Text("Every automated tap, typed field, approval and refusal (stored on this phone).", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { app.automationLog.clear() }) { Text("Clear") } }
        }
        items(log.take(200)) { e ->
            Text("${fmt.format(Date(e.time))} · ${e.app.substringAfterLast('.')} · ${e.action} → ${e.outcome}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }

    editing?.let { m -> MacroEditor(m, onDismiss = { editing = null }, onSave = { updated -> scope.launch { app.automation.saveMacro(updated); editing = null; notify("Macro saved as skill /macro-${updated.id.take(12)}") } }) }
}

@Composable
private fun MacroEditor(m: Macro, onDismiss: () -> Unit, onSave: (Macro) -> Unit) {
    var name by remember { mutableStateOf(m.name) }
    var desc by remember { mutableStateOf(m.description) }
    var schedule by remember { mutableStateOf(m.schedule) }
    // Typed values can become parameters: tick a step to turn its text into {{valueN}}.
    var paramSteps by remember { mutableStateOf(m.steps.indices.filter { m.steps[it].value.startsWith("{{") }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Macro") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(desc, { desc = it }, label = { Text("What it does") }, modifier = Modifier.fillMaxWidth())
                    Text("Steps (tick typed values that should be asked each time):", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                items(m.steps.indices.toList()) { i ->
                    val st = m.steps[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (st.kind == com.offlineagent.core.runtime.phone.MacroStepKind.TYPE) Checkbox(i in paramSteps, { c -> paramSteps = if (c) paramSteps + i else paramSteps - i })
                        Text("${i + 1}. ${st.kind.name.lowercase()} " + (st.selector?.let { it.text.ifBlank { it.contentDescription.ifBlank { it.hint.ifBlank { it.viewId.substringAfterLast('/') } } } } ?: "") +
                            (if (st.value.isNotBlank()) " “${st.value.take(30)}”" else "") + if (st.irreversible) " ⚠" else "", style = MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Text("Schedule", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("none", "daily", "weekly").forEach { k -> FilterChip(selected = schedule.kind == k, onClick = { schedule = schedule.copy(kind = k) }, label = { Text(k) }) }
                    }
                    if (schedule.kind != "none") Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("at %02d:%02d".format(schedule.hour, schedule.minute), Modifier.weight(1f))
                        TextButton(onClick = { schedule = schedule.copy(hour = (schedule.hour + 1) % 24) }) { Text("+1h") }
                        TextButton(onClick = { schedule = schedule.copy(minute = (schedule.minute + 15) % 60) }) { Text("+15m") }
                    }
                    if (schedule.kind != "none") Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(schedule.whenCharging, { schedule = schedule.copy(whenCharging = it) }); Text("Only while charging", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val params = ArrayList<String>()
                val steps = m.steps.mapIndexed { i, st ->
                    if (i in paramSteps && st.kind == com.offlineagent.core.runtime.phone.MacroStepKind.TYPE) {
                        val p = if (st.value.startsWith("{{")) st.value.removePrefix("{{").removeSuffix("}}") else "value${params.size + 1}"
                        params += p; st.copy(value = "{{$p}}")
                    } else st
                }
                onSave(m.copy(name = name.trim().ifBlank { "Macro" }, description = desc, steps = steps, params = params, schedule = schedule))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- MCP

@Composable
private fun McpPage(app: OfflineAgentApp, notify: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val status by app.mcp.status.collectAsStateWithLifecycle()
    val s by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    var discovered by remember { mutableStateOf(app.mcp.discoverOnDevice()) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) notify("Permission needed to talk to other apps' MCP servers") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("Model Context Protocol (JSON-RPC 2.0): tools/list, tools/call, resources and prompts. MCP tools join the assistant's tools with the same approval flow.", style = MaterialTheme.typography.bodySmall)
            Section("Connected")
        }
        items(status) { st ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${st.name}  ·  ${st.kind}", style = MaterialTheme.typography.titleSmall)
                    Text(st.tools.joinToString(), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    if (st.kind != "Built-in") TextButton(onClick = { scope.launch { app.mcp.disconnect(st.id); app.settings.update { it.copy(mcpServers = it.mcpServers - st.id) } } }) { Text("Disconnect") }
                }
            }
        }
        item {
            Section("Other apps on this phone (binder)")
            Row { Text("${discovered.size} found", Modifier.weight(1f)); TextButton(onClick = { discovered = app.mcp.discoverOnDevice() }) { Text("Scan again") } }
            if (discovered.isEmpty()) Text("Install an app that declares the com.offlineagent.mcp.SERVER service (e.g. the sample “MCP Study Tools”).", style = MaterialTheme.typography.bodySmall)
        }
        items(discovered) { d ->
            val on = d.id in s.mcpServers
            SwitchRow("${d.name} (${d.appLabel})", d.description, on) { v ->
                scope.launch {
                    if (v) {
                        if (Build.VERSION.SDK_INT >= 23) perm.launch(com.offlineagent.mcp.McpAndroid.PERMISSION_USE_MCP)
                        app.mcp.connectOnDevice(d).onSuccess { app.settings.update { it.copy(mcpServers = it.mcpServers + d.id) }; notify("Connected ${d.name}") }
                            .onFailure { notify("Could not connect: ${it.message}") }
                    } else { app.mcp.disconnect(d.id); app.settings.update { it.copy(mcpServers = it.mcpServers - d.id) } }
                }
            }
        }
        item {
            Section("Network servers")
            if (!BuildConfig.NETWORK_MCP) Text("Not available in the offline build (no internet permission). Install the “connected” build to add LAN or remote MCP servers over Streamable HTTP.", style = MaterialTheme.typography.bodySmall)
            else {
                OutlinedTextField(url, { url = it }, label = { Text("Endpoint, e.g. http://192.168.1.20:8000/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("Bearer token (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(onClick = { scope.launch { app.mcp.connectNetwork(url, token).onSuccess { notify("Connected") }.onFailure { notify("Failed: ${it.message}") } } }, enabled = url.startsWith("http")) { Text("Connect") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}


// ---------------------------------------------------------------- diagnostics (Phase 4, P0)

@Composable
private fun DiagnosticsPage(app: OfflineAgentApp, notify: (String) -> Unit) {
    val s by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val health by app.engineController.health.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var reports by remember { mutableStateOf(com.offlineagent.app.diag.Diagnostics.crashReports()) }
    var open by remember { mutableStateOf<String?>(null) }
    var sysInfo by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { sysInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.inference.systemInfo() }.getOrDefault("") } }
    fun share() = app.appScope.launch {
        try {
            val f = com.offlineagent.app.diag.Diagnostics.exportBundle("System info:\n$sysInfo\n\nSettings: $s\nLast load: ${app.engineController.lastConfig}\nSelf-test: ${health.selfTest}")
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .putExtra(android.content.Intent.EXTRA_SUBJECT, "Offline Agent diagnostics").addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.launcher.launch(android.content.Intent.createChooser(send, "Share diagnostics"), "share diagnostics")
        } catch (e: Throwable) { notify("Export failed: ${e.message}") }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Everything here stays on this phone. Nothing is sent unless you share the file yourself.", style = MaterialTheme.typography.bodySmall)
            Section("Model self-test")
            Text(health.selfTest?.let { "${it.verdict.name.lowercase().replace('_', ' ')} — “${it.output.take(40)}” (${it.reason}, ${it.millis} ms)" } ?: "Runs automatically after every model load.",
                style = MaterialTheme.typography.bodySmall)
            health.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            SwitchRow("Safe mode", "4K context, f16 KV, no flash attention, no prompt cache, no draft model, no core pinning." + if (s.safeModeReason.isNotBlank()) " (${s.safeModeReason})" else "", s.safeMode) { v ->
                app.appScope.launch {
                    if (v) app.settings.update { it.copy(safeMode = true, safeModeReason = "Turned on by you.") } else app.engineController.exitSafeMode()
                    notify(app.engineController.applySettings())
                }
            }
            SwitchRow("Compatibility CPU kernels", "Use the basic NEON/dotprod kernels instead of the fastest ones for this CPU (SVE/SME/i8mm). Slower; takes effect after a restart.",
                health.compatCpu) { v ->
                com.offlineagent.app.diag.Diagnostics.setCompatVariant(if (v) com.offlineagent.app.diag.Diagnostics.COMPAT_VARIANT else null)
                notify("Restart the app to apply")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { share() }) { Text("Export & share logs") }
                TextButton(onClick = { com.offlineagent.engine.llamacpp.LlamaCppEngine.clearPrefixCache(ctx); notify("Prompt cache cleared") }) { Text("Clear prompt cache") }
            }
            Section("System")
            Text(sysInfo.ifBlank { "…" }, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Section("Crash reports (${reports.size})")
            if (reports.isEmpty()) Text("No crashes recorded.", style = MaterialTheme.typography.bodySmall)
        }
        items(reports, key = { it.name }) { f ->
            Card(Modifier.fillMaxWidth().clickable { open = if (open == f.name) null else f.name }) {
                Column(Modifier.padding(10.dp)) {
                    val text = remember(f.name) { runCatching { f.readText() }.getOrDefault("") }
                    Text(text.lineSequence().firstOrNull() ?: f.name, style = MaterialTheme.typography.titleSmall)
                    Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(f.lastModified())), style = MaterialTheme.typography.labelSmall)
                    if (open == f.name) androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(text.take(12000), style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
        item {
            Section("Diagnostics log")
            val log = remember(reports) { com.offlineagent.app.diag.Diagnostics.readLog().takeLast(6000) }
            Text(log.ifBlank { "(empty)" }, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            TextButton(onClick = { com.offlineagent.app.diag.Diagnostics.clear(); reports = emptyList(); notify("Cleared") }) { Text("Clear crash reports & log") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
