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
    val a11y = remember(enabled) { runCatching { OfflineAccessibilityService.isEnabled(ctx) }.getOrDefault(false) }

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
            Switch(checked = enabled && screen.available, enabled = screen.available, onCheckedChange = { screen.prefs.setEnabled(it) })
        }
        if (screen.available) {
            Text(
                "Off by default. Kill switch: turning off Phone automation (or the Screen-automation group) in Settings stops every " +
                    "screen step at once, and Stop on the overlay ends the current task. Banking/UPI, password, authenticator, " +
                    "settings and store-purchase screens are always blocked; send/pay/submit/delete asks every time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (enabled && !a11y) Text("Needs the ONYX accessibility service (Settings → Accessibility).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
