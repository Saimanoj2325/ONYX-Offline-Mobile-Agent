package com.offlineagent.core.agents

import com.offlineagent.core.runtime.agent.AgentLlm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The ONE door to the shared local model. Agents run in parallel coroutines, but llama.cpp has one context, so every
 * model call is serialized here:
 *  - higher [Priority] first (the user waiting on an answer beats background verification);
 *  - among equal priority, a job with the SAME [prefixKey] as the job that just ran goes first: its system prompt is
 *    still in the KV cache (the engine reuses the matching prefix), so it skips re-reading it;
 *  - then FIFO.
 * [pause] holds new jobs (thermal SEVERE+ or user pause); the running job is never interrupted.
 */
class InferenceQueue(private val llm: AgentLlm) {
    enum class Priority(val weight: Int) { BACKGROUND(0), NORMAL(1), PLANNING(2), INTERACTIVE(3) }

    data class Stats(val completed: Int = 0, val queued: Int = 0, val running: Boolean = false, val prefixReuses: Int = 0,
                     val maxDepth: Int = 0, val paused: Boolean = false, val pausedReason: String? = null)

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

    suspend fun <T> run(priority: Priority, prefixKey: String?, block: suspend (AgentLlm) -> T): T {
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
            return block(llm)
        } finally {
            synchronized(lock) {
                busy = false
                _stats.value = _stats.value.copy(completed = _stats.value.completed + 1, running = false)
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
        _stats.value = _stats.value.copy(queued = waiters.size, running = true, prefixReuses = _stats.value.prefixReuses + if (reuse) 1 else 0)
        next.gate.complete(Unit)
    }
}
