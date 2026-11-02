package com.offlineagent.core.agents

import com.offlineagent.core.runtime.agent.AgentLlm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * The ONE door to the shared local model. Agents run in parallel coroutines, but llama.cpp has one context, so every
 * model call is serialized here:
 *  - higher [Priority] first (the user waiting on an answer beats background verification);
 *  - among equal priority, a job with the SAME [prefixKey] as the job that just ran goes first: its system prompt is
 *    still in the KV cache (the engine reuses the matching prefix), so it skips re-reading it;
 *  - then FIFO.
 * [pause] holds new jobs (thermal SEVERE+ or user pause); the running job is never interrupted.
 *
 * Phase 5b: the queue is RE-ENTRANT. A job that (indirectly) asks the queue again from inside its own block — e.g. a
 * model-backed tool run through [run] whose implementation calls [complete] — runs inline instead of waiting for
 * itself (which used to be a guaranteed deadlock). Approvals are never awaited while holding the queue: agents ask
 * the user before/after model calls, not inside them.
 */
class InferenceQueue(private val llm: AgentLlm) {
    enum class Priority(val weight: Int) { BACKGROUND(0), NORMAL(1), PLANNING(2), INTERACTIVE(3) }

    data class Stats(val completed: Int = 0, val queued: Int = 0, val running: Boolean = false, val prefixReuses: Int = 0,
                     val maxDepth: Int = 0, val paused: Boolean = false, val pausedReason: String? = null,
                     /** Prefix key / label of the job using the model right now (e.g. "planner", "writer"). */
                     val runningLabel: String? = null)

    /** Marks coroutines that currently hold this queue (re-entrancy). */
    private class Holder(val queue: InferenceQueue) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Holder> }

    private class Waiter(val priority: Priority, val prefixKey: String?, val seq: Long, val gate: CompletableDeferred<Unit>)

    private val lock = Any()
    private val waiters = mutableListOf<Waiter>()
    private var busy = false
    private var seq = 0L
    private var lastPrefix: String? = null
    private var paused = false
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** Order in which prefix keys were granted (tests / diagnostics). */
    val grantOrder = java.util.Collections.synchronizedList(mutableListOf<String?>())

    suspend fun complete(priority: Priority, prefixKey: String?, system: String, user: String, grammar: String?, maxTokens: Int): String =
        run(priority, prefixKey) { it.complete(system, user, grammar, maxTokens) }

    /** Streaming variant: [onPartial] gets the accumulated visible text; [onStart] fires when the model is granted. */
    suspend fun stream(priority: Priority, prefixKey: String?, system: String, user: String, grammar: String?, maxTokens: Int,
                       onStart: () -> Unit = {}, onPartial: (String) -> Unit): String =
        run(priority, prefixKey, onStart) { it.stream(system, user, grammar, maxTokens, onPartial) }

    suspend fun <T> run(priority: Priority, prefixKey: String?, onStart: () -> Unit = {}, block: suspend (AgentLlm) -> T): T {
        if (coroutineContext[Holder]?.queue === this) { onStart(); return block(llm) } // re-entrant call: we already hold the model
        val w: Waiter
        synchronized(lock) {
            w = Waiter(priority, prefixKey, seq++, CompletableDeferred())
            waiters += w
            _stats.value = _stats.value.copy(queued = waiters.size, maxDepth = maxOf(_stats.value.maxDepth, waiters.size))
            dispatchLocked()
        }
        try {
            w.gate.await()
        } catch (e: Throwable) {
            synchronized(lock) {
                if (!waiters.remove(w)) { busy = false; dispatchLocked() } // granted just before cancel: release
                _stats.value = _stats.value.copy(queued = waiters.size)
            }
            throw e
        }
        try {
            onStart()
            return withContext(Holder(this)) { block(llm) }
        } finally {
            synchronized(lock) {
                busy = false
                _stats.value = _stats.value.copy(completed = _stats.value.completed + 1, running = false, runningLabel = null)
                dispatchLocked()
            }
        }
    }

    fun pause(reason: String) = synchronized(lock) { paused = true; _stats.value = _stats.value.copy(paused = true, pausedReason = reason) }
    fun resume() = synchronized(lock) { paused = false; _stats.value = _stats.value.copy(paused = false, pausedReason = null); dispatchLocked() }

    private fun dispatchLocked() {
        if (busy || paused || waiters.isEmpty()) return
        val next = waiters.sortedWith(
            compareByDescending<Waiter> { it.priority.weight }
                .thenByDescending { if (it.prefixKey != null && it.prefixKey == lastPrefix) 1 else 0 }
                .thenBy { it.seq },
        ).first()
        waiters.remove(next)
        busy = true
        val reuse = next.prefixKey != null && next.prefixKey == lastPrefix
        lastPrefix = next.prefixKey
        grantOrder += next.prefixKey
        _stats.value = _stats.value.copy(queued = waiters.size, running = true, runningLabel = next.prefixKey, prefixReuses = _stats.value.prefixReuses + if (reuse) 1 else 0)
        next.gate.complete(Unit)
    }
}
