package com.offlineagent.core.agents.memory

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Phase 6 M2 workflow adaptation: a multi-step plan that succeeded is remembered as a candidate; once the SAME kind of
 * request succeeds again (≥ [RoutineBook.ACTIVE_AFTER] successes) it becomes a routine, and the Planner reuses its steps
 * instead of making a planning model call (arguments are still filled fresh for the new request). Users can view,
 * rename, disable and delete routines.
 */
@Serializable
data class RoutineStep(val tool: String, val goal: String, val after: List<Int> = emptyList())

data class Routine(
    val id: Long = 0,
    val name: String,
    val example: String,
    val keywords: Set<String>,
    val steps: List<RoutineStep>,
    val successes: Int = 1,
    val uses: Int = 0,
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val lastUsedAt: Long = 0,
) {
    val active get() = enabled && successes >= RoutineBook.ACTIVE_AFTER
    val tools get() = steps.map { it.tool }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun stepsToJson(s: List<RoutineStep>) = json.encodeToString(ListSerializer(RoutineStep.serializer()), s)
        fun stepsFromJson(s: String): List<RoutineStep> = runCatching { json.decodeFromString(ListSerializer(RoutineStep.serializer()), s) }.getOrDefault(emptyList())
    }
}

interface RoutineRepo {
    suspend fun all(): List<Routine>
    suspend fun upsert(r: Routine): Long
    suspend fun delete(id: Long)
    suspend fun deleteAll()
}

class InMemoryRoutineRepo : RoutineRepo {
    private val m = linkedMapOf<Long, Routine>(); private var next = 1L
    override suspend fun all() = synchronized(m) { m.values.toList() }
    override suspend fun upsert(r: Routine): Long = synchronized(m) { val id = if (r.id == 0L) next++ else r.id; m[id] = r.copy(id = id); id }
    override suspend fun delete(id: Long) { synchronized(m) { m.remove(id) } }
    override suspend fun deleteAll() { synchronized(m) { m.clear() } }
}

class RoutineBook(private val repo: RoutineRepo, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val ACTIVE_AFTER = 2
        const val MATCH = 0.6
        /** Words that differ between runs of the same routine (times, numbers, names in quotes) are dropped. */
        private val SLOT = Regex("\\b\\d+([:.]\\d+)?\\s*(am|pm)?\\b|\"[^\"]*\"|'[^']*'", RegexOption.IGNORE_CASE)
        private val FILLER = setOf("today", "tomorrow", "tonight", "now", "later", "morning", "evening", "saying", "called", "named", "minutes", "hours")
        fun keywords(request: String): Set<String> = Text.terms(request.replace(SLOT, " ")).filter { it !in FILLER && it.length > 2 }.toSet()
        fun similarity(a: Set<String>, b: Set<String>): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else (a intersect b).size.toDouble() / (a union b).size
        fun defaultName(request: String): String = request.trim().replace(SLOT, "…").take(48)
    }

    suspend fun all(): List<Routine> = repo.all().sortedByDescending { it.lastUsedAt }

    /** Best ACTIVE routine for [request] whose tools are all still [available], or null. */
    suspend fun match(request: String, available: Set<String>): Routine? {
        val k = keywords(request)
        return repo.all().filter { it.active && it.tools.all { t -> t in available } }
            .map { it to similarity(k, it.keywords) }.filter { it.second >= MATCH }.maxByOrNull { it.second }?.first
    }

    /** Records a SUCCESSFUL multi-step plan (≥ 2 tool steps). Returns the routine (new or updated). */
    suspend fun recordSuccess(request: String, steps: List<RoutineStep>): Routine? {
        if (steps.size < 2) return null
        val k = keywords(request)
        val now = clock()
        val same = repo.all().filter { it.tools == steps.map { s -> s.tool } }.map { it to similarity(k, it.keywords) }.filter { it.second >= MATCH }.maxByOrNull { it.second }?.first
        val r = if (same != null) same.copy(successes = same.successes + 1, keywords = same.keywords union k, lastUsedAt = now)
            else Routine(name = defaultName(request), example = request.take(200), keywords = k, steps = steps, createdAt = now, lastUsedAt = now)
        val id = repo.upsert(r)
        return r.copy(id = id)
    }

    suspend fun markUsed(id: Long) { repo.all().firstOrNull { it.id == id }?.let { repo.upsert(it.copy(uses = it.uses + 1, lastUsedAt = clock())) } }
    suspend fun rename(id: Long, name: String) { repo.all().firstOrNull { it.id == id }?.let { repo.upsert(it.copy(name = name.trim().take(60))) } }
    suspend fun setEnabled(id: Long, enabled: Boolean) { repo.all().firstOrNull { it.id == id }?.let { repo.upsert(it.copy(enabled = enabled)) } }
    suspend fun delete(id: Long) = repo.delete(id)
    suspend fun wipe() = repo.deleteAll()
}
