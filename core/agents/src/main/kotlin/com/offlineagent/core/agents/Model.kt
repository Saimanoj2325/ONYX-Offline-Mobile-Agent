package com.offlineagent.core.agents

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Phase 5 multi-agent model. Every agent is a *role* that runs on the SAME loaded LLM (through [InferenceQueue]) plus
 * deterministic Kotlin. Agents never call each other directly: the [Orchestrator] delegates [TaskNode]s and agents answer
 * with typed [AgentMessage]s that are also written to the [Blackboard].
 */
@Serializable
enum class AgentRole(val title: String, val summary: String) {
    ORCHESTRATOR("Orchestrator", "Delegates work, retries, recovers"),
    PLANNER("Planner", "Breaks the goal into a task graph"),
    MEMORY("Memory", "Recalls your memories, documents and notes"),
    CONTEXT("Context", "Reads device state and the screen"),
    DEVICE("Device", "Reads from phone APIs and tools"),
    VERIFIER("Verifier", "Checks results with rules + the model"),
    EXECUTOR("Executor", "Runs approved actions only"),
    WRITER("Writer", "Composes the final answer"),
}

/** Risk level of an action, shown on the confirmation sheet. */
@Serializable
enum class Risk(val label: String) {
    NONE("No risk"), LOW("Low"), MEDIUM("Medium"), HIGH("High"), CRITICAL("Irreversible");
    val needsApproval: Boolean get() = this >= MEDIUM
}

@Serializable
enum class NodeStatus { PENDING, RUNNING, VERIFYING, DONE, FAILED, SKIPPED, QUEUED_OFFLINE, DENIED;
    val isTerminal: Boolean get() = this == DONE || this == FAILED || this == SKIPPED || this == QUEUED_OFFLINE || this == DENIED
    /** Terminal states that let dependants continue (a queued-offline action has a stand-in result). */
    val satisfiesDependants: Boolean get() = this == DONE || this == QUEUED_OFFLINE
}

/** One node of the task DAG. [tool] + [args] only for DEVICE (read) / EXECUTOR (action) nodes. */
@Serializable
data class TaskNode(
    val id: String,
    val agent: AgentRole,
    val goal: String,
    val tool: String? = null,
    val args: JsonObject = JsonObject(emptyMap()),
    val dependsOn: List<String> = emptyList(),
    val verify: Boolean = true,
)

@Serializable
data class TaskPlan(val goal: String, val nodes: List<TaskNode>, val source: String = "rules") {
    /** Validation: unique ids, known deps, no cycles. Returns problems (empty = valid). */
    fun problems(): List<String> {
        val out = mutableListOf<String>()
        val ids = nodes.map { it.id }
        if (ids.toSet().size != ids.size) out += "duplicate node ids"
        val known = ids.toSet()
        nodes.forEach { n -> n.dependsOn.filter { it !in known }.forEach { out += "${n.id} depends on unknown $it" } }
        if (out.isEmpty() && levels() == null) out += "cycle in task graph"
        return out
    }

    /** Topological levels (nodes in the same level are independent → can run in parallel). Null if cyclic. */
    fun levels(): List<List<TaskNode>>? {
        val remaining = nodes.associateBy { it.id }.toMutableMap()
        val done = mutableSetOf<String>()
        val levels = mutableListOf<List<TaskNode>>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.values.filter { n -> n.dependsOn.all { it in done } }
            if (ready.isEmpty()) return null
            levels += ready
            ready.forEach { remaining.remove(it.id); done += it.id }
        }
        return levels
    }
}

/** Typed messages between agents (also the audit/activity vocabulary). */
@Serializable
sealed interface AgentMessage {
    val from: AgentRole
    val to: AgentRole

    @Serializable @SerialName("delegate")
    data class Delegate(override val from: AgentRole, override val to: AgentRole, val node: TaskNode, val attempt: Int) : AgentMessage

    @Serializable @SerialName("result")
    data class Result(override val from: AgentRole, override val to: AgentRole, val nodeId: String, val output: String, val data: JsonObject? = null) : AgentMessage

    @Serializable @SerialName("failure")
    data class Failure(override val from: AgentRole, override val to: AgentRole, val nodeId: String, val error: String, val retryable: Boolean) : AgentMessage

    @Serializable @SerialName("verification")
    data class Verification(override val from: AgentRole, override val to: AgentRole, val nodeId: String, val passed: Boolean, val issues: List<String>) : AgentMessage

    @Serializable @SerialName("approval")
    data class ApprovalAsked(override val from: AgentRole, override val to: AgentRole, val nodeId: String, val tool: String, val risk: Risk) : AgentMessage

    @Serializable @SerialName("queued")
    data class QueuedOffline(override val from: AgentRole, override val to: AgentRole, val nodeId: String, val tool: String, val outboxId: String) : AgentMessage
}

/** What an agent returns for a node. */
sealed interface NodeOutcome {
    data class Ok(val output: String, val data: JsonObject? = null) : NodeOutcome
    data class Fail(val error: String, val retryable: Boolean = true) : NodeOutcome
    data class Denied(val reason: String) : NodeOutcome
    data class Queued(val outboxId: String, val note: String) : NodeOutcome
}

@Serializable
data class NodeState(
    val id: String,
    val agent: AgentRole,
    val goal: String,
    val status: NodeStatus = NodeStatus.PENDING,
    val attempts: Int = 0,
    val output: String = "",
    val error: String? = null,
    val verified: Boolean? = null,
    val startedAt: Long = 0,
    val endedAt: Long = 0,
    /** Set when the node was re-delegated to another agent after failures. */
    val delegatedTo: AgentRole? = null,
    /** Phase 5b: the model text this node produced while working (planner steps, filled arguments, answer draft). */
    val thought: String = "",
    /** Phase 5b: why the node is being retried right now (verification issue or tool error), shown as "↻ retry". */
    val retryReason: String? = null,
    /** Phase 5b: issues the Verifier found on the last check (empty = passed or not checked). */
    val issues: List<String> = emptyList(),
)

@Serializable
enum class TaskStatus { PLANNING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, INTERRUPTED;
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

/** Persisted after every node: enough to resume after process death without re-running finished nodes. */
@Serializable
data class TaskCheckpoint(
    val runId: String,
    val request: String,
    val status: TaskStatus,
    val plan: TaskPlan? = null,
    val nodes: List<NodeState> = emptyList(),
    val facts: Map<String, String> = emptyMap(),
    val answer: String? = null,
    val error: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Phase 5b: earlier chat turns this task continued from (chat mode). */
    val conversation: String = "",
    val route: Route? = null,
) {
    fun toJson(): String = AgentJson.encodeToString(serializer(), this)
    companion object { fun fromJson(s: String): TaskCheckpoint = AgentJson.decodeFromString(serializer(), s) }
}

/** Live activity for the UI timeline. */
sealed interface ActivityEvent {
    val at: Long
    data class AgentWorking(val agent: AgentRole, val what: String, override val at: Long) : ActivityEvent
    data class Message(val message: AgentMessage, override val at: Long) : ActivityEvent
    data class PlanReady(val plan: TaskPlan, override val at: Long) : ActivityEvent
    data class Note(val text: String, override val at: Long) : ActivityEvent
    /** Phase 5b: one finished model call (real latency + the text it produced) — detail view and harness timings. */
    data class ModelCall(val agent: AgentRole, val key: String, val label: String?, val millis: Long, val waitedMillis: Long,
                         val output: String, override val at: Long) : ActivityEvent
}

/**
 * Phase 5b: text an agent's model call is producing RIGHT NOW (accumulated), keyed by node id ("plan" for the
 * planner). [waiting] = queued behind another agent's model call (one shared model).
 */
data class LiveThought(val agent: AgentRole, val key: String, val text: String, val waiting: Boolean, val startedAt: Long, val updates: Int = 0)

/** How the orchestrator routed a request (Phase 5b fast paths). */
@Serializable
enum class Route(val label: String) {
    DIRECT("direct answer"),   // no tools needed: skip the planner, Writer answers (1 model call)
    RULES("rule fast path"),   // one obvious tool step with complete arguments: skip the planner model
    PLANNED("planned"),        // the Planner model built the task graph
    FALLBACK("rules"),         // no model / planner failed: deterministic rules
    SCREEN("screen agent"),    // Phase 6.1: in-app request → screen_open_app + screen_task (no planner call)
}

val AgentJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
