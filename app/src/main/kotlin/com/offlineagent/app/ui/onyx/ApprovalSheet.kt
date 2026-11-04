package com.offlineagent.app.ui.onyx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.offlineagent.app.onyx.OnyxApproval
import com.offlineagent.app.ui.theme.riskColor
import com.offlineagent.core.agents.Risk

/**
 * Confirmation sheet for sensitive / irreversible actions. Shows the risk level, what will happen, exact arguments and
 * whether it needs the network. HIGH/CRITICAL actions can never be "always allowed". Dismissing = Deny.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(a: OnyxApproval, onAnswer: (allow: Boolean, always: Boolean) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val r = a.request
    val rc = riskColor(r.risk)
    ModalBottomSheet(onDismissRequest = { onAnswer(false, false) }, sheetState = sheet) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (r.risk >= Risk.HIGH) Icon(Icons.Default.Warning, null, tint = rc)
                Text("Allow this action?", style = MaterialTheme.typography.headlineSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("${r.risk.label} risk", rc)
                NetworkBadge(r.needsNetwork)
                Pill(r.tool, MaterialTheme.colorScheme.primary, dot = false)
            }
            Text(r.title, style = MaterialTheme.typography.titleMedium)
            Text(r.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (r.sideEffects != "none") Text("Effect: ${r.sideEffects}", style = MaterialTheme.typography.bodyMedium)
            if (r.risk == Risk.CRITICAL) Text("This may not be undoable.", color = rc, style = MaterialTheme.typography.labelLarge)
            if (r.args.isNotEmpty()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(r.args.entries.joinToString("\n") { (k, v) -> "$k: ${v.toString().trim('"').take(300)}" }, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState()))
            }
            if (r.needsNetwork) Text("If you're offline it will be queued and run automatically when you're back online.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onAnswer(true, false) }, modifier = Modifier.fillMaxWidth(),
                colors = if (r.risk >= Risk.HIGH) ButtonDefaults.buttonColors(containerColor = rc) else ButtonDefaults.buttonColors()) { Text("Allow once") }
            if (a.canAlwaysAllow) OutlinedButton(onClick = { onAnswer(true, true) }, modifier = Modifier.fillMaxWidth()) { Text(a.scopeLabel ?: "Always allow ${r.tool}") }
            TextButton(onClick = { onAnswer(false, false) }, modifier = Modifier.fillMaxWidth()) { Text("Deny") }
        }
    }
}
