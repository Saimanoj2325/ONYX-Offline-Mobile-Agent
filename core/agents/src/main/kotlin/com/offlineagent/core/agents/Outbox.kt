package com.offlineagent.core.agents

import com.offlineagent.core.runtime.tools.ToolResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class OutboxStatus { QUEUED, RUNNING, DONE, FAILED, CANCELLED;
    val isOpen: Boolean get() = this == QUEUED || this == RUNNING
}

/** A network-dependent action the user already approved, waiting for connectivity. */
@Serializable
data class OutboxItem(
    val id: String,
    val runId: String,
    val tool: String,
    val argsJson: String,
    val risk: Risk,
    val status: OutboxStatus = OutboxStatus.QUEUED,
    val attempts: Int = 0,
    val maxAttempts: Int = 5,
    val createdAt: Long,
    val nextAttemptAt: Long = createdAt,
    val lastError: String? = null,
    val result: String? = null,
) {
    val args: JsonObject get() = AgentJson.parseToJsonElement(argsJson).jsonObject
}

interface OutboxStore {
    suspend fun insert(item: OutboxItem)
    suspend fun update(item: OutboxItem)
    suspend fun open(): List<OutboxItem>
}

class InMemoryOutboxStore : OutboxStore {
    val items = java.util.concurrent.ConcurrentHashMap<String, OutboxItem>()
    override suspend fun insert(item: OutboxItem) { items[item.id] = item }
    override suspend fun update(item: OutboxItem) { items[item.id] = item }
    override suspend fun open() = items.values.filter { it.status.isOpen }.sortedBy { it.createdAt }
}

/**
 * Drains the outbox (called by a WorkManager job with a CONNECTED constraint, and on app start). Items run in creation
 * order; a retryable failure backs off exponentially (30 s · 2^attempt, max 1 h) up to [OutboxItem.maxAttempts];
 * a non-retryable failure fails at once. An item interrupted mid-run (process death) is retried.
 */
class OutboxProcessor(
    private val store: OutboxStore,
    private val tools: ToolGateway,
    private val network: NetworkStatus,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class DrainResult(val done: Int = 0, val failed: Int = 0, val retryLater: Int = 0, val offline: Boolean = false) {
        /** True when WorkManager should run again later. */
        val needsRetry: Boolean get() = retryLater > 0 || offline
    }

    suspend fun enqueue(runId: String, tool: String, args: JsonObject, risk: Risk): String {
        val id = "obx-" + java.util.UUID.randomUUID().toString().take(8)
        store.insert(OutboxItem(id, runId, tool, args.toString(), risk, createdAt = clock()))
        return id
    }

    suspend fun drain(onFinished: suspend (OutboxItem) -> Unit = {}): DrainResult {
        var r = DrainResult()
        for (item in store.open()) {
            if (!network.isOnline()) return r.copy(offline = true, retryLater = r.retryLater + 1)
            if (item.nextAttemptAt > clock()) { r = r.copy(retryLater = r.retryLater + 1); continue }
            val running = item.copy(status = OutboxStatus.RUNNING, attempts = item.attempts + 1)
            store.update(running)
            val res = runCatching { tools.execute(item.tool, item.args) }.getOrElse { ToolResult.Error(it.message ?: it.toString(), retryable = true) }
            val next = when (res) {
                is ToolResult.Ok -> if (res.json["error"] != null) failOrRetry(running, res.json.toString(), true) else running.copy(status = OutboxStatus.DONE, result = (res.summary.ifBlank { res.json.toString() }).take(4000), lastError = null)
                is ToolResult.Error -> failOrRetry(running, res.message, res.retryable)
            }
            store.update(next)
            when (next.status) {
                OutboxStatus.DONE -> { r = r.copy(done = r.done + 1); onFinished(next) }
                OutboxStatus.FAILED -> { r = r.copy(failed = r.failed + 1); onFinished(next) }
                else -> r = r.copy(retryLater = r.retryLater + 1)
            }
        }
        return r
    }

    private fun failOrRetry(item: OutboxItem, error: String, retryable: Boolean): OutboxItem =
        if (!retryable || item.attempts >= item.maxAttempts) item.copy(status = OutboxStatus.FAILED, lastError = error.take(500))
        else item.copy(status = OutboxStatus.QUEUED, lastError = error.take(500), nextAttemptAt = clock() + backoffMs(item.attempts))

    companion object {
        fun backoffMs(attempts: Int): Long = (30_000L shl (attempts - 1).coerceIn(0, 10)).coerceAtMost(3_600_000L)
    }
}
