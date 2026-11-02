package com.offlineagent.app.ui.onyx

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offlineagent.app.ui.markdown.MarkdownView
import com.offlineagent.app.ui.theme.agentColor
import com.offlineagent.core.agents.ActivityEvent
import com.offlineagent.core.agents.AgentMessage
import com.offlineagent.core.agents.AgentRole
import com.offlineagent.core.agents.Blackboard
import com.offlineagent.core.agents.NodeState
import com.offlineagent.core.agents.NodeStatus
import com.offlineagent.core.agents.TaskStatus

/**
 * Live "agent activity" for one task: who is working, the plan as DAG levels (parallel branches drawn side by side
 * with a bracket), per-step status, retries/re-delegation, verification ticks, then the answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityTimeline(board: Blackboard, modifier: Modifier = Modifier, onContinueInChat: (String) -> Unit = {}, onSpeak: (String) -> Unit = {}) {
    val st by board.state.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().animateContentSize(), shape = MaterialTheme.shapes.large, color = cs.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(board.request, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (label, color) = statusLabel(st.status)
                Pill(label, color)
                st.plan?.let { Text("${it.nodes.size} steps · plan by ${it.source}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
            }
            // Who is working right now (several at once = parallel branches).
            AnimatedVisibility(st.working.isNotEmpty(), enter = fadeIn() + expandVertically()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.working.forEach { (role, what) -> AgentChip(role, what) }
                }
            }
            val plan = st.plan
            if (plan == null) {
                Row(verticalAlignment = Alignment.CenterVertically) { AgentChip(AgentRole.PLANNER, "Planning…") }
            } else {
                val levels = plan.levels() ?: listOf(plan.nodes)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    levels.forEach { level ->
                        val states = level.mapNotNull { st.nodes[it.id] }
                        if (states.size > 1) ParallelGroup(states) else states.firstOrNull()?.let { StepRow(it) }
                    }
                }
            }
            st.error?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }
            st.answer?.let { ans ->
                Surface(shape = MaterialTheme.shapes.medium, color = cs.surfaceContainerHigh) {
                    Column(Modifier.padding(14.dp)) {
                        MarkdownView(ans)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onContinueInChat(board.request) }) { Text("Continue in chat") }
                            TextButton(onClick = { onSpeak(ans) }) { Text("Read aloud") }
                        }
                    }
                }
            }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide agent messages" else "Agent messages") }
            if (showLog) MessageLog(board)
        }
    }
}

@Composable
private fun statusLabel(s: TaskStatus): Pair<String, Color> = when (s) {
    TaskStatus.PLANNING -> "Planning" to agentColor(AgentRole.PLANNER)
    TaskStatus.RUNNING -> "Working" to MaterialTheme.colorScheme.primary
    TaskStatus.AWAITING_APPROVAL -> "Needs your OK" to Color(0xFFFFC970)
    TaskStatus.COMPLETED -> "Done" to OfflineGreen
    TaskStatus.FAILED -> "Failed" to MaterialTheme.colorScheme.error
    TaskStatus.CANCELLED -> "Stopped" to MaterialTheme.colorScheme.outline
    TaskStatus.INTERRUPTED -> "Interrupted" to Color(0xFFFFC970)
}

@Composable
fun AgentChip(role: AgentRole, what: String?) {
    val c = agentColor(role)
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        WorkingDots(c); Spacer(Modifier.width(6.dp))
        Text(role.title, style = MaterialTheme.typography.labelSmall, color = c, fontWeight = FontWeight.Bold)
        what?.let { Text(" · " + it.take(40), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
    }
}

@Composable
private fun ParallelGroup(states: List<NodeState>) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)))
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("IN PARALLEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            states.forEach { StepRow(it) }
        }
    }
}

@Composable
private fun StepRow(n: NodeState) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusDot(n.status, agentColor(n.agent))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(n.goal, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (n.status == NodeStatus.SKIPPED) cs.outline else cs.onSurface)
            val extras = buildList {
                add(n.agent.title)
                if (n.attempts > 1) add("try ${n.attempts}")
                n.delegatedTo?.let { add("re-delegated → ${it.title}") }
                when (n.verified) { true -> add("verified ✓"); false -> add("unverified"); null -> Unit }
                when (n.status) { NodeStatus.QUEUED_OFFLINE -> add("queued for network"); NodeStatus.DENIED -> add("you declined"); NodeStatus.SKIPPED -> add("skipped"); else -> Unit }
            }
            Text(extras.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = agentColor(n.agent).copy(alpha = 0.9f))
            n.error?.takeIf { n.status == NodeStatus.FAILED }?.let { Text(it.take(160), style = MaterialTheme.typography.labelSmall, color = cs.error) }
        }
    }
}

@Composable
private fun StatusDot(s: NodeStatus, accent: Color) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (s) {
            NodeStatus.RUNNING, NodeStatus.VERIFYING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = if (s == NodeStatus.VERIFYING) agentColor(AgentRole.VERIFIER) else accent)
            NodeStatus.DONE -> Box(Modifier.size(20.dp).clip(CircleShape).background(OfflineGreen.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = OfflineGreen, modifier = Modifier.size(14.dp)) }
            NodeStatus.FAILED, NodeStatus.DENIED -> Box(Modifier.size(20.dp).clip(CircleShape).background(cs.error.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, null, tint = cs.error, modifier = Modifier.size(14.dp)) }
            NodeStatus.QUEUED_OFFLINE -> Box(Modifier.size(20.dp).clip(CircleShape).background(NetworkAmber.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Refresh, null, tint = NetworkAmber, modifier = Modifier.size(14.dp)) }
            NodeStatus.SKIPPED -> Box(Modifier.size(10.dp).clip(CircleShape).background(cs.outlineVariant))
            NodeStatus.PENDING -> Box(Modifier.size(14.dp).clip(CircleShape).border(2.dp, cs.outlineVariant, CircleShape))
        }
    }
}

@Composable
private fun MessageLog(board: Blackboard) {
    val st by board.state.collectAsState() // recompose as the run advances
    val events = remember(st) { board.eventLog().takeLast(40) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.clickable { }) {
        events.forEach { e ->
            val line = when (e) {
                is ActivityEvent.Message -> when (val m = e.message) {
                    is AgentMessage.Delegate -> "${m.from.title} → ${m.to.title}: ${m.node.goal}${if (m.attempt > 1) " (try ${m.attempt})" else ""}"
                    is AgentMessage.Result -> "${m.from.title} ✓ ${m.nodeId}: ${m.output.take(80)}"
                    is AgentMessage.Failure -> "${m.from.title} ✗ ${m.nodeId}: ${m.error.take(80)}"
                    is AgentMessage.Verification -> "Verifier ${if (m.passed) "✓" else "✗"} ${m.nodeId}${if (m.issues.isNotEmpty()) ": " + m.issues.joinToString() else ""}"
                    is AgentMessage.ApprovalAsked -> "${m.from.title} asks approval: ${m.tool} (${m.risk.label})"
                    is AgentMessage.QueuedOffline -> "${m.from.title} queued ${m.tool} for when you're online"
                }
                is ActivityEvent.PlanReady -> "Planner: ${e.plan.nodes.size} steps (${e.plan.source})"
                is ActivityEvent.Note -> e.text
                is ActivityEvent.AgentWorking -> null
            } ?: return@forEach
            Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
