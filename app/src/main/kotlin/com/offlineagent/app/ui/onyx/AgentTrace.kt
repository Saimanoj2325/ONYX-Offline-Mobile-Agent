package com.offlineagent.app.ui.onyx

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.offlineagent.app.ui.theme.agentColor
import com.offlineagent.app.ui.theme.riskColor
import com.offlineagent.core.agents.ActivityEvent
import com.offlineagent.core.agents.AgentMessage
import com.offlineagent.core.agents.AgentRole
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Blackboard
import com.offlineagent.core.agents.NodeState
import com.offlineagent.core.agents.NodeStatus
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.TaskNode
import com.offlineagent.core.agents.TaskStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/*
 * Phase 5b: the multi-agent "thinking" trace, used inside chat messages and on Home.
 *
 * Everything here is driven by REAL orchestrator state: Blackboard.state (plan, node states, approvals) and
 * Blackboard.live (the text each agent's model call is producing right now, throttled to ≤ 20 updates/s in core).
 * Performance: each step row subscribes only to ITS node and ITS live entry (map + distinctUntilChanged), so a token
 * streaming into one step recomposes that row only, never the whole trace; rows use stable keys (node ids).
 */

/** Short avatar letter per agent. */
private fun AgentRole.letter() = when (this) {
    AgentRole.ORCHESTRATOR -> "O"; AgentRole.PLANNER -> "P"; AgentRole.MEMORY -> "M"; AgentRole.CONTEXT -> "C"
    AgentRole.DEVICE -> "D"; AgentRole.VERIFIER -> "V"; AgentRole.EXECUTOR -> "E"; AgentRole.WRITER -> "W"
}

/** Coloured agent avatar; [active] pulses with a halo. */
@Composable
fun AgentAvatar(role: AgentRole, active: Boolean, size: Int = 28) {
    val c = agentColor(role)
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (active) {
            val t = rememberInfiniteTransition(label = "pulse")
            val s by t.animateFloat(0.85f, 1.35f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")
            val a by t.animateFloat(0.45f, 0.0f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(size.dp).scale(s).clip(CircleShape).background(c.copy(alpha = a)))
        }
        Box(Modifier.size((size - 4).dp).clip(CircleShape).background(c.copy(alpha = if (active) 0.32f else 0.18f)).border(1.dp, c.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text(role.letter(), color = c, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Live elapsed time while the run is going; frozen at the end. Ticks only this text, twice a second. */
@Composable
private fun Elapsed(start: Long, end: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(end) { while (end == 0L) { now = System.currentTimeMillis(); delay(500) } }
    val ms = ((if (end > 0) end else now) - start).coerceAtLeast(0)
    Text(if (ms < 60_000) "%.1fs".format(ms / 1000.0) else "${ms / 60_000}m ${(ms / 1000) % 60}s",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
}

private data class Header(val status: TaskStatus, val working: Map<AgentRole, String>, val planSteps: Int, val route: String?, val source: String?,
                          val createdAt: Long, val finishedAt: Long, val awaiting: ApprovalRequest?, val error: String?)

/**
 * Collapsible "Agents working" card. Header: pulsing orb, live status (which agent is doing what), elapsed time,
 * route + model calls. Body: plan / steps grouped by DAG level (parallel branches side by side as an indented
 * bracket), live thought under the active step, inline approval, and a button to the full-screen detail.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentTraceCard(
    board: Blackboard,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true,
    canAlwaysAllow: (ApprovalRequest) -> Boolean = { false },
    onApproval: (allow: Boolean, always: Boolean) -> Unit = { _, _ -> },
) {
    val header by remember(board) {
        board.state.map { s -> Header(s.status, s.working, s.plan?.nodes?.count { it.id.startsWith("s") } ?: 0, s.route?.label, s.plan?.source, s.createdAt, s.finishedAt, s.awaiting, s.error) }
            .distinctUntilChanged()
    }.collectAsState(initial = Header(TaskStatus.PLANNING, emptyMap(), 0, null, null, System.currentTimeMillis(), 0, null, null))
    val calls by remember(board) { board.state.map { board.eventLog().count { e -> e is ActivityEvent.ModelCall } }.distinctUntilChanged() }.collectAsState(initial = 0)
    val running = !header.status.isTerminal
    var expanded by rememberSaveable(board.runId) { mutableStateOf(initiallyExpanded) }
    // Auto-expand when the user must answer something.
    LaunchedEffect(header.awaiting != null) { if (header.awaiting != null) expanded = true }
    var detail by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val activeRole = header.working.keys.lastOrNull { it != AgentRole.ORCHESTRATOR } ?: header.working.keys.firstOrNull()

    Surface(modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(20.dp), color = cs.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, (activeRole?.let { agentColor(it) } ?: cs.outlineVariant).copy(alpha = if (running) 0.45f else 0.25f))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            // ---------------- header
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                if (running) AgentAvatar(activeRole ?: AgentRole.ORCHESTRATOR, active = true, size = 30)
                else StatusIcon(when (header.status) { TaskStatus.COMPLETED -> NodeStatus.DONE; TaskStatus.CANCELLED -> NodeStatus.SKIPPED; else -> NodeStatus.FAILED }, null, 30)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val title = when (header.status) {
                        TaskStatus.PLANNING, TaskStatus.RUNNING -> "Agents working"
                        TaskStatus.AWAITING_APPROVAL -> "Waiting for your OK"
                        TaskStatus.COMPLETED -> "Agents finished"
                        TaskStatus.FAILED -> "Agents could not finish"
                        TaskStatus.CANCELLED -> "Stopped"
                        TaskStatus.INTERRUPTED -> "Interrupted"
                    }
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    AnimatedContent(targetState = if (running) activeRole?.let { r -> "${r.title}: ${header.working[r].orEmpty()}" } ?: "Starting…" else
                        listOfNotNull(header.route, "${header.planSteps} tool step${if (header.planSteps == 1) "" else "s"}", "$calls model call${if (calls == 1) "" else "s"}").joinToString(" · "),
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(150)) }, label = "sub") { sub ->
                        Text(sub, style = MaterialTheme.typography.labelMedium, color = activeRole?.takeIf { running }?.let { agentColor(it) } ?: cs.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Elapsed(header.createdAt, header.finishedAt)
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Collapse" else "Expand", tint = cs.onSurfaceVariant)
            }
            // ---------------- body
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TraceBody(board)
                    header.awaiting?.let { a -> InlineApproval(a, canAlwaysAllow(a), onApproval) }
                    header.error?.takeIf { header.status == TaskStatus.FAILED }?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        header.source?.let { Text("plan: $it", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                        TextButton(onClick = { detail = true }) { Text("Details") }
                    }
                }
            }
        }
    }
    if (detail) AgentTraceDetail(board) { detail = false }
}

/** Plan view (numbered steps + dependencies) or step view (grouped by DAG level). */
@Composable
private fun TraceBody(board: Blackboard) {
    val plan by remember(board) { board.state.map { it.plan }.distinctUntilChanged() }.collectAsState(initial = board.state.value.plan)
    var planView by rememberSaveable(board.runId) { mutableStateOf(false) }
    val p = plan
    if (p == null) {
        // Planner is still working: show its real streamed output (readable steps) — or "routing" before that.
        PlannerLive(board)
        return
    }
    val toolSteps = p.nodes.count { it.id.startsWith("s") }
    if (toolSteps > 0) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(!planView, { planView = false }, label = { Text("Steps") })
        FilterChip(planView, { planView = true }, label = { Text("Plan") })
    }
    AnimatedContent(planView, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "view") { showPlan ->
        if (showPlan) PlanList(p.nodes)
        else {
            val levels = remember(p) { p.levels() ?: listOf(p.nodes) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { level ->
                    key(level.joinToString { it.id }) {
                        if (level.size > 1) ParallelBranch(board, level) else StepRow(board, level.first())
                    }
                }
            }
        }
    }
}

@Composable
private fun PlannerLive(board: Blackboard) {
    val live by remember(board) { board.live.map { it["plan"] }.distinctUntilChanged() }.collectAsState(initial = null)
    Row(verticalAlignment = Alignment.Top) {
        AgentAvatar(AgentRole.PLANNER, active = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (live == null) "Routing your request…" else if (live?.waiting == true) "Planner · waiting for the model" else "Planner · breaking it into steps",
                style = MaterialTheme.typography.bodyMedium)
            live?.text?.takeIf { it.isNotBlank() }?.let { ThoughtText(it, agentColor(AgentRole.PLANNER), streaming = true) }
        }
    }
}

@Composable
private fun PlanList(nodes: List<TaskNode>) {
    val index = remember(nodes) { nodes.mapIndexed { i, n -> n.id to i + 1 }.toMap() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        nodes.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(22.dp))
                AgentAvatar(n.agent, active = false, size = 22)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(n.goal, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val deps = n.dependsOn.mapNotNull { index[it] }
                    Text(listOfNotNull(n.agent.title, n.tool, if (deps.isEmpty()) "starts first" else "after ${deps.joinToString(", ")}").joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = agentColor(n.agent).copy(alpha = 0.9f))
                }
            }
        }
    }
}

@Composable
private fun ParallelBranch(board: Blackboard, level: List<TaskNode>) {
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("IN PARALLEL · ${level.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            level.forEach { n -> key(n.id) { StepRow(board, n) } }
        }
    }
}

/** One step: avatar (pulses when active), goal, status badge, retry / verification info and the live thought. */
@Composable
private fun StepRow(board: Blackboard, n: TaskNode) {
    val st by remember(board, n.id) { board.state.map { it.nodes[n.id] }.distinctUntilChanged() }.collectAsState(initial = board.node(n.id))
    val awaitingHere by remember(board, n.id) { board.state.map { it.awaiting?.nodeId == n.id }.distinctUntilChanged() }.collectAsState(initial = false)
    val live by remember(board, n.id) { board.live.map { it[n.id] ?: it["verify:${n.id}"] }.distinctUntilChanged() }.collectAsState(initial = null)
    val s = st ?: NodeState(n.id, n.agent, n.goal)
    val role = live?.agent ?: s.delegatedTo ?: n.agent
    val active = s.status == NodeStatus.RUNNING || s.status == NodeStatus.VERIFYING
    val cs = MaterialTheme.colorScheme
    var showOutput by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        AgentAvatar(role, active = active)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.goal, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    color = if (s.status == NodeStatus.SKIPPED) cs.outline else cs.onSurface)
                Spacer(Modifier.width(6.dp))
                StatusIcon(s.status, s.verified, 22, awaiting = awaitingHere)
            }
            val label = when {
                awaitingHere -> "waiting for your approval"
                live?.waiting == true -> "waiting for the model"
                s.status == NodeStatus.VERIFYING -> "Verifier checking"
                s.status == NodeStatus.RUNNING -> if (live != null) "thinking" else "working"
                s.status == NodeStatus.PENDING -> "queued"
                s.status == NodeStatus.DONE -> if (s.verified == true) "done · verified" else if (s.verified == false) "done · unverified" else "done"
                s.status == NodeStatus.FAILED -> "failed"
                s.status == NodeStatus.DENIED -> "you declined"
                s.status == NodeStatus.QUEUED_OFFLINE -> "queued until online"
                else -> "skipped"
            }
            Text(listOfNotNull(role.title, n.tool, label).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = agentColor(role).copy(alpha = 0.95f))
            s.retryReason?.let { Text("↻ retry ${s.attempts} — ${it.take(140)}", style = MaterialTheme.typography.labelSmall, color = NetworkAmber) }
            if (s.attempts > 1 && s.retryReason == null && s.status.isTerminal) Text("↻ ${s.attempts} tries" + (s.delegatedTo?.let { " · re-delegated → ${it.title}" } ?: ""),
                style = MaterialTheme.typography.labelSmall, color = NetworkAmber)
            if (s.issues.isNotEmpty() && s.verified != true) Text("⚠ " + s.issues.joinToString("; ").take(160), style = MaterialTheme.typography.labelSmall, color = NetworkAmber)
            s.error?.takeIf { s.status == NodeStatus.FAILED || s.status == NodeStatus.DENIED }?.let { Text(it.take(180), style = MaterialTheme.typography.labelSmall, color = cs.error) }
            // Live model text for the active step (real tokens), or the finished output on tap.
            val liveText = live?.text?.takeIf { it.isNotBlank() }
            if (liveText != null) ThoughtText(liveText, agentColor(role), streaming = true)
            else if (s.status.isTerminal && (s.thought.isNotBlank() || s.output.isNotBlank()) && n.agent != AgentRole.WRITER) {
                Text(if (showOutput) "Hide output" else "Show output", style = MaterialTheme.typography.labelSmall, color = cs.primary,
                    modifier = Modifier.clickable { showOutput = !showOutput }.padding(vertical = 2.dp))
                AnimatedVisibility(showOutput) { ThoughtText(listOf(s.thought, s.output).filter { it.isNotBlank() }.distinct().joinToString("\n→ "), agentColor(role), streaming = false) }
            }
        }
    }
}

/** Streaming thought text: monospace, tinted, last lines only (tap to expand). */
@Composable
private fun ThoughtText(text: String, tint: Color, streaming: Boolean) {
    var full by remember { mutableStateOf(false) }
    val shown = if (full) text else text.takeLast(320)
    Surface(shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth().padding(top = 4.dp).clickable { full = !full }) {
        Text((if (!full && text.length > 320) "…" else "") + shown + if (streaming) " ▍" else "", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (full) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
    }
}

/** queued ○ · thinking (spinner) · waiting ! · done ✓ · verified ✓✓ · failed ✕ · declined ✕ · offline ↻ · skipped –. */
@Composable
private fun StatusIcon(s: NodeStatus, verified: Boolean?, size: Int, awaiting: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(Triple(s, verified, awaiting), transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) }, label = "status") { (st, v, aw) ->
            val inner = (size * 0.62f).dp
            when {
                aw -> Badge(NetworkAmber, size) { Icon(Icons.Default.Warning, null, tint = NetworkAmber, modifier = Modifier.size(inner)) }
                st == NodeStatus.RUNNING || st == NodeStatus.VERIFYING -> androidx.compose.material3.CircularProgressIndicator(Modifier.size((size * 0.75f).dp), strokeWidth = 2.dp,
                    color = if (st == NodeStatus.VERIFYING) agentColor(AgentRole.VERIFIER) else cs.primary)
                st == NodeStatus.DONE && v == true -> Badge(OfflineGreen, size) { Text("✓✓", color = OfflineGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black) }
                st == NodeStatus.DONE -> Badge(OfflineGreen, size) { Icon(Icons.Default.Check, null, tint = OfflineGreen, modifier = Modifier.size(inner)) }
                st == NodeStatus.FAILED || st == NodeStatus.DENIED -> Badge(cs.error, size) { Icon(Icons.Default.Close, null, tint = cs.error, modifier = Modifier.size(inner)) }
                st == NodeStatus.QUEUED_OFFLINE -> Badge(NetworkAmber, size) { Icon(Icons.Default.Refresh, null, tint = NetworkAmber, modifier = Modifier.size(inner)) }
                st == NodeStatus.SKIPPED -> Box(Modifier.size((size * 0.4f).dp).clip(CircleShape).background(cs.outlineVariant))
                else -> Box(Modifier.size((size * 0.55f).dp).clip(CircleShape).border(2.dp, cs.outlineVariant, CircleShape))
            }
        }
    }
}

@Composable
private fun Badge(c: Color, size: Int, content: @Composable () -> Unit) =
    Box(Modifier.size(size.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { content() }

/** Approval inside the trace (same answer path as the bottom sheet). */
@Composable
private fun InlineApproval(a: ApprovalRequest, canAlways: Boolean, onAnswer: (Boolean, Boolean) -> Unit) {
    val rc = riskColor(a.risk)
    Surface(shape = RoundedCornerShape(14.dp), color = rc.copy(alpha = 0.10f), border = androidx.compose.foundation.BorderStroke(1.dp, rc.copy(alpha = 0.5f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, null, tint = rc, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text("Allow ${a.tool}?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Pill("${a.risk.label} risk", rc)
            }
            Text(a.title, style = MaterialTheme.typography.bodySmall)
            Text(a.args.entries.joinToString("\n") { (k, v) -> "$k: ${v.toString().trim('"').take(160)}" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (a.sideEffects.isNotBlank() && a.sideEffects != "none") Text("Effect: ${a.sideEffects}", style = MaterialTheme.typography.labelSmall)
            if (a.needsNetwork) Text("Needs internet — queued if you are offline.", style = MaterialTheme.typography.labelSmall, color = NetworkAmber)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(true, false) }, colors = if (a.risk >= Risk.HIGH) ButtonDefaults.buttonColors(containerColor = rc) else ButtonDefaults.buttonColors()) { Text("Allow once") }
                OutlinedButton(onClick = { onAnswer(false, false) }) { Text("Deny") }
                if (canAlways) TextButton(onClick = { onAnswer(true, true) }) { Text("Always") }
            }
        }
    }
}

// ---------------------------------------------------------------- full-screen detail

/** Every agent's input and output messages, model calls with real latencies, and the raw message log. */
@Composable
fun AgentTraceDetail(board: Blackboard, onDismiss: () -> Unit) {
    val st by board.state.collectAsState()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            val events = remember(st) { board.eventLog() }
            val calls = events.filterIsInstance<ActivityEvent.ModelCall>()
            LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Agent trace", style = MaterialTheme.typography.headlineSmall)
                            Text(board.request, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(listOfNotNull(st.route?.label, st.plan?.source?.let { "plan: $it" }, "${calls.size} model calls", "${calls.sumOf { it.millis } / 1000.0}s on the model").joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
                    }
                }
                st.facts["planner_thought"]?.let { pt -> item(key = "planner") { DetailCard(AgentRole.PLANNER, "Plan", listOf("Output" to pt)) } }
                val plan = st.plan
                if (plan != null) items(plan.nodes, key = { it.id }) { n ->
                    val s = st.nodes[n.id]
                    val dur = s?.let { if (it.endedAt > 0 && it.startedAt > 0) " · ${(it.endedAt - it.startedAt)} ms" else "" } ?: ""
                    DetailCard(s?.delegatedTo ?: n.agent, "${n.id} · ${s?.status?.name?.lowercase() ?: "pending"}$dur", buildList {
                        add("Input" to (listOfNotNull(n.goal, n.tool?.let { "tool: $it" }, n.args.takeIf { it.isNotEmpty() }?.toString(), n.dependsOn.takeIf { it.isNotEmpty() }?.let { "after: ${it.joinToString()}" }).joinToString("\n")))
                        s?.thought?.takeIf { it.isNotBlank() }?.let { add("Model output" to it) }
                        s?.output?.takeIf { it.isNotBlank() && it != s.thought }?.let { add("Result" to it) }
                        s?.error?.let { add("Error" to it) }
                        if (s != null && s.issues.isNotEmpty()) add("Verifier" to s.issues.joinToString("\n"))
                        if (s != null && s.attempts > 1) add("Attempts" to "${s.attempts}" + (s.delegatedTo?.let { " (re-delegated to ${it.title})" } ?: ""))
                        s?.verified?.let { add("Verified" to if (it) "yes" else "no — kept as the last attempt") }
                    })
                }
                if (calls.isNotEmpty()) {
                    item { Text("Model calls (one shared model, in order)", style = MaterialTheme.typography.titleMedium) }
                    items(calls.size) { i ->
                        val c = calls[i]
                        Row(verticalAlignment = Alignment.Top) {
                            AgentAvatar(c.agent, active = false, size = 22); Spacer(Modifier.width(8.dp))
                            Column {
                                Text("${c.agent.title} · ${c.key} · ${c.millis} ms" + if (c.waitedMillis > 50) " (waited ${c.waitedMillis} ms)" else "", style = MaterialTheme.typography.labelMedium)
                                Text(c.output.take(400), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item { HorizontalDivider(); Text("Messages", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(events.mapNotNull { messageLine(it) }) { line ->
                    Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DetailCard(role: AgentRole, title: String, rows: List<Pair<String, String>>) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(role, active = false, size = 24); Spacer(Modifier.width(8.dp))
                Text("${role.title} · $title", style = MaterialTheme.typography.titleSmall, color = agentColor(role))
            }
            rows.forEach { (k, v) ->
                Text(k.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(v.take(4000), style = MaterialTheme.typography.bodySmall, fontFamily = if (k == "Model output") FontFamily.Monospace else null) }
            }
        }
    }
}

private fun messageLine(e: ActivityEvent): String? = when (e) {
    is ActivityEvent.Message -> when (val m = e.message) {
        is AgentMessage.Delegate -> "${m.from.title} → ${m.to.title}: ${m.node.goal}${if (m.attempt > 1) " (try ${m.attempt})" else ""}"
        is AgentMessage.Result -> "${m.from.title} ✓ ${m.nodeId}: ${m.output.take(100)}"
        is AgentMessage.Failure -> "${m.from.title} ✕ ${m.nodeId}: ${m.error.take(100)}${if (m.retryable) " (will retry)" else ""}"
        is AgentMessage.Verification -> "Verifier ${if (m.passed) "✓" else "✕"} ${m.nodeId}${if (m.issues.isNotEmpty()) ": " + m.issues.joinToString() else ""}"
        is AgentMessage.ApprovalAsked -> "${m.from.title} asks approval: ${m.tool} (${m.risk.label})"
        is AgentMessage.QueuedOffline -> "${m.from.title} queued ${m.tool} for when you're online"
    }
    is ActivityEvent.PlanReady -> "Planner: ${e.plan.nodes.size} steps (${e.plan.source})"
    is ActivityEvent.Note -> e.text
    is ActivityEvent.ModelCall -> null
    is ActivityEvent.AgentWorking -> null
}

/** The answer under a trace: streams the Writer's tokens live, then the final text. */
@Composable
fun liveAnswer(board: Blackboard): String {
    val live by remember(board) { board.live.map { it["answer"]?.text }.distinctUntilChanged() }.collectAsState(initial = null)
    val final by remember(board) { board.state.map { it.answer }.distinctUntilChanged() }.collectAsState(initial = board.state.value.answer)
    return final ?: live ?: ""
}

