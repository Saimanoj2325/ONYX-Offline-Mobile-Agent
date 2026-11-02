package com.offlineagent.core.agents

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared state of one task run. Agents read facts written by earlier nodes (context, memory, tool outputs) and the
 * orchestrator writes node states. All writes are atomic [MutableStateFlow.update]s, so parallel branches never lose an
 * update; the UI observes [state] and [events] directly.
 */
class Blackboard(
    val runId: String,
    val request: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Phase 5b: earlier chat turns (chat mode) so planner/writer can resolve "it", "that", follow-ups. */
    val conversation: String = "",
    /** Minimum gap between published live-thought updates per key (UI throttle; the final text is always published). */
    private val streamThrottleMs: Long = 50,
) {
    data class State(
        val status: TaskStatus = TaskStatus.PLANNING,
        val plan: TaskPlan? = null,
        val nodes: Map<String, NodeState> = emptyMap(),
        val facts: Map<String, String> = emptyMap(),
        val working: Map<AgentRole, String> = emptyMap(),
        val answer: String? = null,
        val error: String? = null,
        val createdAt: Long = 0,
        val route: Route? = null,
        /** Approval the user must answer right now (shown inline in the trace and as a sheet). */
        val awaiting: ApprovalRequest? = null,
        val finishedAt: Long = 0,
    )

    private val _state = MutableStateFlow(State(createdAt = clock()))
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ActivityEvent>(replay = 200, extraBufferCapacity = 200)
    val events: SharedFlow<ActivityEvent> = _events

    /** Ordered event log (kept for the timeline and for tests). */
    private val log = java.util.Collections.synchronizedList(mutableListOf<ActivityEvent>())
    fun eventLog(): List<ActivityEvent> = synchronized(log) { log.toList() }

    fun emit(e: ActivityEvent) { log += e; _events.tryEmit(e) }
    fun message(m: AgentMessage) = emit(ActivityEvent.Message(m, clock()))
    fun note(text: String) = emit(ActivityEvent.Note(text, clock()))

    fun setStatus(s: TaskStatus) = _state.update { it.copy(status = s) }
    fun setRoute(r: Route) = _state.update { it.copy(route = r) }

    // ---------------------------------------------------------------- Phase 5b: live model text (throttled)
    private val _live = MutableStateFlow<Map<String, LiveThought>>(emptyMap())
    /** What each agent's model call is producing right now. Updated at most every [streamThrottleMs] per key. */
    val live: StateFlow<Map<String, LiveThought>> = _live.asStateFlow()
    private val lastPublish = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val latest = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Number of live updates actually published (tests: proves throttling). */
    @Volatile var livePublishes = 0; private set

    fun streamWaiting(key: String, agent: AgentRole) {
        latest[key] = ""
        _live.update { it + (key to LiveThought(agent, key, "", waiting = true, startedAt = clock())) }
    }
    fun streamStarted(key: String, agent: AgentRole) {
        _live.update { m -> m + (key to (m[key]?.copy(waiting = false, startedAt = clock()) ?: LiveThought(agent, key, "", false, clock()))) }
    }
    fun stream(key: String, agent: AgentRole, text: String) {
        latest[key] = text
        val now = clock()
        val last = lastPublish[key] ?: 0L
        if (now - last < streamThrottleMs) return
        lastPublish[key] = now
        publish(key, agent, text)
    }
    /** Publishes the final text, stores it on the node (when [key] is a node id) and clears the live entry. */
    fun streamEnd(key: String, agent: AgentRole, finalText: String? = null) {
        val text = finalText ?: latest[key] ?: ""
        publish(key, agent, text)
        if (_state.value.nodes.containsKey(key)) updateNode(key) { it.copy(thought = text.take(4000)) }
        else if (key == "plan") fact("planner_thought", text.take(2000))
        _live.update { it - key }
        latest.remove(key); lastPublish.remove(key)
    }
    private fun publish(key: String, agent: AgentRole, text: String) {
        livePublishes++
        _live.update { m -> m + (key to (m[key]?.copy(text = text, waiting = false, updates = (m[key]?.updates ?: 0) + 1) ?: LiveThought(agent, key, text, false, clock(), 1))) }
    }

    // ---------------------------------------------------------------- Phase 5b: approvals (one at a time, counted)
    /** Parallel Executor branches ask one at a time (one sheet), so no approval is ever lost or overwritten. */
    val approvalLock = kotlinx.coroutines.sync.Mutex()
    private val approvalsOpen = java.util.concurrent.atomic.AtomicInteger()
    fun approvalStarted(r: ApprovalRequest) { approvalsOpen.incrementAndGet(); _state.update { it.copy(status = TaskStatus.AWAITING_APPROVAL, awaiting = r) } }
    fun approvalEnded() {
        val left = approvalsOpen.decrementAndGet()
        _state.update { st -> if (left <= 0 && st.status == TaskStatus.AWAITING_APPROVAL) st.copy(status = TaskStatus.RUNNING, awaiting = null) else st.copy(awaiting = if (left <= 0) null else st.awaiting) }
    }
    fun setPlan(p: TaskPlan) {
        _state.update { st -> st.copy(plan = p, nodes = p.nodes.associate { n -> n.id to (st.nodes[n.id] ?: NodeState(n.id, n.agent, n.goal)) }) }
        emit(ActivityEvent.PlanReady(p, clock()))
    }
    fun fact(key: String, value: String) = _state.update { it.copy(facts = it.facts + (key to value)) }
    fun facts(): Map<String, String> = _state.value.facts
    fun node(id: String): NodeState? = _state.value.nodes[id]
    fun updateNode(id: String, f: (NodeState) -> NodeState) = _state.update { st ->
        val cur = st.nodes[id] ?: return@update st
        st.copy(nodes = st.nodes + (id to f(cur)))
    }
    fun working(agent: AgentRole, what: String?) {
        _state.update { st -> st.copy(working = if (what == null) st.working - agent else st.working + (agent to what)) }
        if (what != null) emit(ActivityEvent.AgentWorking(agent, what, clock()))
    }
    fun finish(status: TaskStatus, answer: String?, error: String?) {
        _state.update { it.copy(status = status, answer = answer, error = error, working = emptyMap(), awaiting = null, finishedAt = clock(),
            nodes = if (status == TaskStatus.CANCELLED) it.nodes.mapValues { (_, n) -> if (n.status.isTerminal) n else n.copy(status = NodeStatus.SKIPPED, error = "stopped") } else it.nodes) }
        _live.value = emptyMap()
    }

    fun checkpoint(): TaskCheckpoint {
        val s = _state.value
        return TaskCheckpoint(runId, request, s.status, s.plan, s.nodes.values.toList(), s.facts, s.answer, s.error, s.createdAt, clock(), conversation, s.route)
    }

    companion object {
        /** Read-only view of a saved run (Chat shows the trace of older messages after a restart). */
        fun fromCheckpoint(cp: TaskCheckpoint): Blackboard = Blackboard(cp.runId, cp.request, conversation = cp.conversation).also { b ->
            b._state.value = State(cp.status, cp.plan, cp.nodes.associateBy { it.id }, cp.facts, emptyMap(), cp.answer, cp.error, cp.createdAt, cp.route,
                finishedAt = if (cp.status.isTerminal) cp.updatedAt else 0)
        }
    }

    /** Restores a saved run; unfinished nodes go back to PENDING (attempt counts are kept). */
    fun restore(cp: TaskCheckpoint) {
        val nodes = cp.nodes.associate { n ->
            n.id to if (n.status.isTerminal) n else n.copy(status = NodeStatus.PENDING, error = null, retryReason = null)
        }
        _state.value = State(TaskStatus.RUNNING, cp.plan, nodes, cp.facts, emptyMap(), null, null, cp.createdAt, cp.route)
        note("Resumed: ${nodes.values.count { it.status.isTerminal }}/${nodes.size} steps already done")
    }
}
