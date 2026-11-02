package com.offlineagent.app.ui.onyx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.core.agents.TaskStatus
import com.offlineagent.core.data.OutboxEntity
import com.offlineagent.core.data.TaskRunEntity
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Tasks: the running one (live timeline), resumable (interrupted), history, and the offline outbox. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(app: OfflineAgentApp, notify: (String) -> Unit, modifier: Modifier = Modifier) {
    val onyx = app.onyx
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val runs by remember { onyx.dao.observeRuns() }.collectAsState(initial = emptyList())
    val outbox by remember { onyx.dao.observeOutbox() }.collectAsState(initial = emptyList())
    val current by onyx.current.collectAsState()
    val online by onyx.monitor.online.collectAsState()
    val scope = rememberCoroutineScope()
    val resumable = runs.filter { r -> !TaskStatus.valueOf(r.status).isTerminal && !(onyx.isRunning && current?.runId == r.runId) }
    val queued = outbox.count { it.status == "QUEUED" || it.status == "RUNNING" }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tasks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Pill(if (online) "Online" else "Offline", if (online) NetworkAmber else OfflineGreen)
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Running", "Resumable (${resumable.size})", "History", "Outbox ($queued)").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, maxLines = 1) })
            }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            when (tab) {
                0 -> {
                    val b = current
                    if (b != null && onyx.isRunning) item(key = b.runId) { AgentTraceCard(b, canAlwaysAllow = { onyx.pending.value?.canAlwaysAllow == true }, onApproval = { a, al -> onyx.answer(a, al) }) }
                    else item { Empty("Nothing running. Ask ONYX from Home.") }
                }
                1 -> if (resumable.isEmpty()) item { Empty("No interrupted tasks. If Android stops ONYX mid-task, it shows up here and resumes automatically.") }
                    else items(resumable, key = { it.runId }) { r ->
                        RunCard(r) {
                            TextButton(onClick = { if (onyx.resume(r.runId) == null) notify("Another task is running.") }) { Text("Resume") }
                            TextButton(onClick = { scope.launch { onyx.dao.deleteRun(r.runId) } }) { Text("Discard") }
                        }
                    }
                2 -> items(runs.filter { TaskStatus.valueOf(it.status).isTerminal }, key = { it.runId }) { r ->
                    RunCard(r) { TextButton(onClick = { scope.launch { onyx.dao.deleteRun(r.runId) } }) { Text("Delete") } }
                }
                3 -> {
                    item {
                        Text(if (online) "Online: queued actions run now." else "Offline: actions that need the internet wait here and run automatically when a connection is back.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (outbox.isEmpty()) item { Empty("Outbox is empty.") }
                    items(outbox, key = { it.id }) { o -> OutboxCard(o) { scope.launch { onyx.dao.cancelOutbox(o.id) } } }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))

@Composable
private fun RunCard(r: TaskRunEntity, actions: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.request, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Pill(r.status.lowercase().replace('_', ' '), statusColor(r.status))
            }
            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.updatedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            (r.answer ?: r.error)?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Row { actions() }
        }
    }
}

@Composable
private fun OutboxCard(o: OutboxEntity, onCancel: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(o.tool, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                NetworkBadge(true)
                Pill(o.status.lowercase(), statusColor(o.status))
            }
            Text(o.argsJson.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            Text("Attempts ${o.attempts}/${o.maxAttempts}" + (o.lastError?.let { " · last error: ${it.take(80)}" } ?: ""), style = MaterialTheme.typography.labelSmall)
            o.result?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis) }
            if (o.status == "QUEUED") TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun statusColor(s: String) = when (s) {
    "COMPLETED", "DONE" -> OfflineGreen
    "FAILED" -> MaterialTheme.colorScheme.error
    "QUEUED", "INTERRUPTED", "AWAITING_APPROVAL" -> NetworkAmber
    "CANCELLED" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.primary
}
