package com.offlineagent.app.onyx.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import kotlinx.coroutines.launch
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.automation.OfflineAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Screen agent (sideload only)" card on the Agents screen: the off-by-default toggle for the autonomous screen tools
 * (screen_*), the kill-switch note, and the learned per-app cards (with delete).
 */
@Composable
fun ScreenAgentCard(app: OfflineAgentApp) {
    val ctx = LocalContext.current
    val screen = app.onyx.screen
    val enabled by screen.prefs.enabled.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var cards by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(tick, enabled) { cards = withContext(Dispatchers.IO) { runCatching { screen.cards.all() }.getOrDefault(emptyMap()) } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Screen agent (sideload only)", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (!screen.available) "Not included in this build (built with -PscreenAgent=false)."
                    else "Lets ONYX read and operate other apps' screens for a task, step by step.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled && screen.available, enabled = screen.available, onCheckedChange = { screen.setEnabled(it) })
        }
        if (screen.available) {
            Text(
                "Off by default. Kill switch: turning off Phone automation (or the Screen-automation group) in Settings stops every " +
                    "screen step at once, and Stop on the overlay ends the current task. Banking/UPI, password, authenticator, " +
                    "store-purchase screens are always blocked; in Settings only Wi-Fi, Bluetooth, Display, Sound, Do Not Disturb and Battery saver " +
                    "pages can be operated (Location, Airplane mode, Mobile data and Hotspot ask every step); send/pay/submit/delete asks every time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ScreenAgentStatusBanner(app, showDismissable = true)
            Text("App cards (what ONYX learned about each app): ${cards.size}", style = MaterialTheme.typography.labelLarge)
            if (cards.isEmpty()) Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            cards.forEach { (pkg, text) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(pkg, style = MaterialTheme.typography.bodyMedium)
                        Text(text.lines().filter { it.isNotBlank() }.take(3).joinToString(" · ").take(160),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { runCatching { screen.cards.delete(pkg) }; tick++ }) { Text("Delete") }
                }
            }
        }
    }
}

/**
 * Phase 6.1: a clear status when the Screen agent and the accessibility service disagree (agent on but service off /
 * not connected, service on but agent off, kill switch or Screen-automation group off), with a button for each fix.
 * Re-checked every 2 s while visible (the user comes back from Android Settings).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ScreenAgentStatusBanner(app: OfflineAgentApp, modifier: Modifier = Modifier, showDismissable: Boolean = true) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val enabled by app.onyx.screen.prefs.enabled.collectAsState()
    var status by remember { mutableStateOf(app.onyx.screen.status()) }
    var dismissed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        while (true) { status = app.onyx.screen.status(); kotlinx.coroutines.delay(2_000) }
    }
    if (status.ok || (status.dismissable && (dismissed || !showDismissable))) return
    androidx.compose.material3.Surface(
        color = if (status.dismissable) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(status.title, style = MaterialTheme.typography.labelLarge)
            Text(status.detail, style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                status.fixes.forEach { fix ->
                    TextButton(onClick = {
                        when (fix) {
                            com.offlineagent.core.screen.ScreenAgentStatus.Fix.OPEN_ACCESSIBILITY -> openAccessibility(ctx)
                            com.offlineagent.core.screen.ScreenAgentStatus.Fix.OPEN_BATTERY -> runCatching {
                                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                            com.offlineagent.core.screen.ScreenAgentStatus.Fix.TURN_ON_AUTOMATION ->
                                scope.launch { app.settings.update { it.copy(automationEnabled = true) } }
                            com.offlineagent.core.screen.ScreenAgentStatus.Fix.ENABLE_SCREEN_GROUP -> scope.launch {
                                app.settings.update { it.copy(phoneGroups = it.phoneGroups + com.offlineagent.core.runtime.phone.PhoneGroup.SCREEN_AUTOMATION.name) }
                            }
                            com.offlineagent.core.screen.ScreenAgentStatus.Fix.TURN_ON_AGENT -> app.onyx.screen.setEnabled(true)
                        }
                        status = app.onyx.screen.status()
                    }) { Text(fix.label) }
                }
                if (status.dismissable) TextButton(onClick = { dismissed = true }) { Text("Not now") }
            }
        }
    }
}

/** Opens this service's own Accessibility page when the phone supports it, else the Accessibility list. */
private fun openAccessibility(ctx: android.content.Context) {
    val component = android.content.ComponentName(ctx, OfflineAccessibilityService::class.java).flattenToString()
    val detail = android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, component).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { ctx.startActivity(detail); true }.getOrDefault(false)) return
    runCatching { com.offlineagent.app.automation.AutomationController.openAccessibilitySettings(ctx) }
}
