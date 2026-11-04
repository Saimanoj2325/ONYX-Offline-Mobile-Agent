package com.offlineagent.app.onyx.apps

import android.app.Activity
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.phone.NotificationCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Assistant role helpers (ROLE_ASSISTANT is not requestable on most builds → fall back to the system "Default apps" screen). */
object AssistantRole {
    fun held(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 29 && runCatching { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrDefault(false)
    fun requestIntent(ctx: Context): Intent? = if (Build.VERSION.SDK_INT >= 29) runCatching {
        val rm = ctx.getSystemService(RoleManager::class.java)
        if (rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)) rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT) else null
    }.getOrNull() else null
    fun settingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** "Connect apps" card on the Agents screen: catalogue, inbound channels, notification bridge, assistant role. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppBridgeCard(app: OfflineAgentApp) {
    val ctx = LocalContext.current
    var apps by remember { mutableIntStateOf(-1) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var assistant by remember { mutableStateOf(AssistantRole.held(ctx)) }
    LaunchedEffect(refreshTick) { apps = withContext(Dispatchers.Default) { runCatching { app.onyx.appBridge.bridge.catalogue.refresh().count { it.blocked == null } }.getOrDefault(-1) } }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        assistant = AssistantRole.held(ctx)
        if (r.resultCode != Activity.RESULT_OK && !assistant) runCatching { ctx.startActivity(AssistantRole.settingsIntent()) }
    }
    val notifOn = NotificationCaptureService.isEnabled(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Connect apps", style = MaterialTheme.typography.titleMedium)
        Text(buildString {
            append(if (apps >= 0) "$apps apps ONYX can open, share to or search. " else "Reading installed apps… ")
            append("Banking/UPI, password, authenticator and settings apps are always blocked. ")
            append("Share anything to ONYX, select text → \"Ask ONYX\", or add the Quick Settings tile.")
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Notification replies: ${if (notifOn) "on" else "off (needs Notification access)"} · Default assistant: ${if (assistant) "ONYX" else "not ONYX"}",
            style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { refreshTick++ }) { Text("Rescan apps") }
            if (!notifOn) OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Notification access") }
            if (!assistant) OutlinedButton(onClick = {
                val i = AssistantRole.requestIntent(ctx)
                if (i != null) role.launch(i) else runCatching { ctx.startActivity(AssistantRole.settingsIntent()) }
            }) { Text("Make ONYX the assistant") }
            if (Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = {
                runCatching {
                    ctx.getSystemService(StatusBarManager::class.java).requestAddTileService(ComponentName(ctx, QuickAskTileService::class.java), "Ask ONYX",
                        Icon.createWithResource(ctx, android.R.drawable.ic_btn_speak_now), ctx.mainExecutor) { }
                }
            }) { Text("Add Quick Settings tile") }
        }
    }
}
