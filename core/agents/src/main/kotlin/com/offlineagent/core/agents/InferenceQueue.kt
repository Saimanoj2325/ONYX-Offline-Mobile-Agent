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
    /** [PREFILL] (0.6.2.3 typing-time read-ahead) is below everything and preemptible: see [tryRunPreemptible]. */
    enum class Priority(val weight: Int) { PREFILL(-1), BACKGROUND(0), NORMAL(1), PLANNING(2), INTERACTIVE(3) }

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

    /** Preempt hook of the read-ahead holding the model right now (null = none / a normal job). */
    private var preemptHook: (() -> Unit)? = null

    /** Read-aheads interrupted because a real job arrived (tests / diagnostics). */
    @Volatile var preemptions = 0; private set

    /**
     * 0.6.2.3: runs a typing-time read-ahead at [Priority.PREFILL] ONLY when the model is free, the queue is not paused
     * and nobody is waiting (else null: skipped, never queued). A job that arrives while it runs calls [onPreempt]
     * (which aborts the engine work) and is granted as soon as [block] returns.
     */
    suspend fun <T> tryRunPreemptible(prefixKey: String?, onPreempt: () -> Unit, block: suspend () -> T): T? {
        synchronized(lock) {
            if (busy || paused || waiters.isNotEmpty()) return null
            busy = true
            preemptHook = onPreempt
            grantOrder += prefixKey
            _stats.value = _stats.value.copy(running = true, runningLabel = prefixKey)
        }
        try {
            return block()
        } finally {
            synchronized(lock) {
                busy = false
                preemptHook = null
                _stats.value = _stats.value.copy(running = false, runningLabel = null)
                dispatchLocked()
            }
        }
    }

    suspend fun <T> run(priority: Priority, prefixKey: String?, onStart: () -> Unit = {}, block: suspend (AgentLlm) -> T): T {
        if (coroutineContext[Holder]?.queue === this) { onStart(); return block(llm) } // re-entrant call: we already hold the model
        val w: Waiter
        val preempt: (() -> Unit)?
        synchronized(lock) {
            w = Waiter(priority, prefixKey, seq++, CompletableDeferred())
            waiters += w
            _stats.value = _stats.value.copy(queued = waiters.size, maxDepth = maxOf(_stats.value.maxDepth, waiters.size))
            preempt = preemptHook.also { if (it != null) { preemptHook = null; preemptions++ } }
            dispatchLocked()
        }
        preempt?.invoke()
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
