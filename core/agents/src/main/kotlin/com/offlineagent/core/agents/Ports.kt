package com.offlineagent.core.agents

import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Tools available to the Device/Executor agents (the app wraps its ToolRegistry + MCP tools). */
interface ToolGateway {
    fun specs(): List<ToolSpec>
    fun spec(name: String): ToolSpec? = specs().firstOrNull { it.name == name }
    /** True when the tool cannot work without internet (remote MCP, URL fetch). Shown as a "Needs network" badge. */
    fun requiresNetwork(name: String): Boolean = false
    /** True when the tool itself runs the LLM (summarize, screen agent): such calls go through the inference queue. */
    fun usesModel(name: String): Boolean = false
    fun risk(name: String, args: JsonObject): Risk = spec(name)?.let { riskOf(it) } ?: Risk.HIGH
    suspend fun execute(name: String, args: JsonObject): ToolResult
}

/** Default risk classification from the existing permission classes + irreversible-sounding names. */
fun riskOf(spec: ToolSpec): Risk {
    val base = when (spec.permission) {
        Permission.READ_ONLY, Permission.UI -> Risk.NONE
        Permission.WRITE_LOCAL -> Risk.LOW
        Permission.SENSITIVE_READ, Permission.DEVICE_CONTROL -> Risk.MEDIUM
        Permission.EXTERNAL_INTENT, Permission.SCREEN_ACTION -> Risk.HIGH
    }
    val irreversible = Regex("delete|remove|send|reply|pay|purchase|transfer|wipe|uninstall").containsMatchIn(spec.name)
    return if (irreversible && base >= Risk.LOW) Risk.CRITICAL else base
}

/** Tools that only read (Device agent) vs. tools that change something (Executor agent, approval-gated). */
fun isActionTool(spec: ToolSpec): Boolean = spec.permission.hasSideEffects

data class MemoryHit(val source: String, val title: String, val text: String, val score: Double = 0.0)

/** User-controlled long-term memory + documents/notes (RAG). */
interface MemoryStore {
    /** False when the user opted out of memory: recall returns nothing and nothing is saved. */
    val enabled: Boolean
    suspend fun recall(query: String, limit: Int): List<MemoryHit>
    suspend fun remember(text: String, source: String) {}
    /**
     * Phase 6: a short "known about the user" block (saved memories only, BM25-ranked) for the Planner, within
     * [budgetTokens]. Empty when memory is off or nothing matches.
     */
    suspend fun profile(query: String, budgetTokens: Int): String = ""
}

/** Phase 6: learning hooks (frequent contacts from approved calls/messages, etc.). Never throw. */
interface AgentHooks {
    suspend fun onToolSucceeded(tool: String, args: JsonObject, risk: Risk) {}
    companion object { val NONE = object : AgentHooks {} }
}

@Serializable
data class DeviceContext(
    val time: String,
    val timezone: String,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val powerSave: Boolean = false,
    val online: Boolean = false,
    val network: String = "unknown",
    val thermal: String = "none",
    val foregroundApp: String? = null,
    val screen: String? = null,
) {
    fun summary(): String = buildString {
        append("Time: $time ($timezone). ")
        batteryPercent?.let { append("Battery: $it%${if (charging == true) " (charging)" else ""}. ") }
        if (powerSave) append("Battery saver ON. ")
        append(if (online) "Online ($network). " else "Offline. ")
        if (thermal != "none") append("Thermal: $thermal. ")
        foregroundApp?.let { append("Foreground app: $it. ") }
        screen?.let { append("\nScreen: ${it.take(1200)}") }
    }.trim()
}

interface ContextProvider {
    suspend fun snapshot(includeScreen: Boolean): DeviceContext
}

data class ApprovalRequest(
    val runId: String,
    val nodeId: String,
    val tool: String,
    val title: String,
    val description: String,
    val args: JsonObject,
    val risk: Risk,
    val sideEffects: String,
    val needsNetwork: Boolean,
)

/** Suspends until the user answers the confirmation sheet. */
fun interface ApprovalGate { suspend fun approve(request: ApprovalRequest): Boolean }

fun interface NetworkStatus { fun isOnline(): Boolean }

/** Persistent queue for network-dependent actions (executed later by WorkManager when connected). */
fun interface OutboxSink { suspend fun enqueue(runId: String, tool: String, args: JsonObject, risk: Risk): String }

fun interface AuditSink { suspend fun record(runId: String, actor: String, action: String, detail: String, risk: Risk) }

interface TaskStore {
    suspend fun save(checkpoint: TaskCheckpoint)
    suspend fun load(runId: String): TaskCheckpoint?
}

class InMemoryTaskStore : TaskStore {
    val saved = java.util.concurrent.ConcurrentHashMap<String, TaskCheckpoint>()
    var saves = 0; private set
    override suspend fun save(checkpoint: TaskCheckpoint) { saves++; saved[checkpoint.runId] = checkpoint }
    override suspend fun load(runId: String) = saved[runId]
}
