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
class Blackboard(val runId: String, val request: String, private val clock: () -> Long = System::currentTimeMillis) {
    data class State(
        val status: TaskStatus = TaskStatus.PLANNING,
        val plan: TaskPlan? = null,
        val nodes: Map<String, NodeState> = emptyMap(),
        val facts: Map<String, String> = emptyMap(),
        val working: Map<AgentRole, String> = emptyMap(),
        val answer: String? = null,
        val error: String? = null,
        val createdAt: Long = 0,
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
    fun finish(status: TaskStatus, answer: String?, error: String?) = _state.update { it.copy(status = status, answer = answer, error = error, working = emptyMap()) }

    fun checkpoint(): TaskCheckpoint {
        val s = _state.value
        return TaskCheckpoint(runId, request, s.status, s.plan, s.nodes.values.toList(), s.facts, s.answer, s.error, s.createdAt, clock())
    }

    /** Restores a saved run; unfinished nodes go back to PENDING (attempt counts are kept). */
    fun restore(cp: TaskCheckpoint) {
        val nodes = cp.nodes.associate { n ->
            n.id to if (n.status.isTerminal) n else n.copy(status = NodeStatus.PENDING, error = null)
        }
        _state.value = State(TaskStatus.RUNNING, cp.plan, nodes, cp.facts, emptyMap(), null, null, cp.createdAt)
        note("Resumed: ${nodes.values.count { it.status.isTerminal }}/${nodes.size} steps already done")
    }
}
