package com.offlineagent.core.agents.memory

import kotlin.math.exp
import kotlin.math.ln

/*
 * Phase 6 M2 — persistent, long, ChatGPT-style memory. Everything here is pure Kotlin (JVM-tested) and on-device:
 *  (a) saved memories: durable facts/preferences extracted from chats (rules first, optional model pass), explicit
 *      "remember …" / "forget …", every entry visible/editable/deletable, inferred ones marked with a confidence;
 *  (b) reference chat history: BM25 retrieval over chunks of ALL past chats (+ rolling summaries), injected into new
 *      chats and agent runs within a token budget;
 *  (c) per-chat rolling summaries (see [RollingSummary]);
 *  (d) temporary chats ([MemoryScope.TEMPORARY]) neither read nor write anything;
 *  plus frequent contacts ([ContactRanking]) and reusable routines (RoutineBook.kt).
 *
 * Retrieval choice: BM25 (Okapi, k1 = 1.2, b = 0.75) with a recency boost instead of embeddings. It needs no second
 * model in RAM (an embedding model would cost ~40–130 MB and a model switch on the single-model runtime), runs in
 * well under 10 ms for a few thousand chunks on a phone core, works for names/numbers/places that small embedding
 * models handle poorly, and is fully explainable. When the user has the bge-small embedding model, the Phase 3 vector
 * search over documents still runs alongside it.
 */

enum class MemoryKind { FACT, PREFERENCE, CONTACT, PROJECT, NOTE }

data class SavedMemory(
    val id: Long = 0,
    val text: String,
    val kind: MemoryKind = MemoryKind.NOTE,
    /** true = ONYX deduced or auto-extracted it (shown as "inferred" with [confidence]); false = the user said "remember". */
    val inferred: Boolean = false,
    val confidence: Double = 1.0,
    /** The user confirmed an inferred memory (it then counts as explicit). */
    val confirmed: Boolean = false,
    /** Where it came from: "user", "chat:<id>", "task:<runId>". */
    val source: String = "user",
    /** Slot for single-valued facts (name, home city, employer…): a new value replaces the old one. */
    val slot: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val useCount: Int = 0,
    val lastUsedAt: Long = 0,
)

/** Storage port (Room in the app, in-memory in tests). */
interface SavedMemoryRepo {
    suspend fun all(): List<SavedMemory>
    suspend fun upsert(m: SavedMemory): Long
    suspend fun delete(id: Long)
    suspend fun deleteAll()
}

class InMemorySavedMemoryRepo : SavedMemoryRepo {
    private val items = linkedMapOf<Long, SavedMemory>()
    private var next = 1L
    override suspend fun all() = synchronized(items) { items.values.toList() }
    override suspend fun upsert(m: SavedMemory): Long = synchronized(items) {
        val id = if (m.id == 0L) next++ else m.id
        items[id] = m.copy(id = id); id
    }
    override suspend fun delete(id: Long) { synchronized(items) { items.remove(id) } }
    override suspend fun deleteAll() { synchronized(items) { items.clear() } }
}

/** What a chat / task may do with memory. TEMPORARY = ChatGPT's "temporary chat": no reading, no writing. */
enum class MemoryScope { NORMAL, TEMPORARY;
    val reads get() = this == NORMAL
    val writes get() = this == NORMAL
}

/** User settings: (a) saved memories, (b) reference chat history; each can be switched off on its own. */
data class MemorySettings(val savedMemories: Boolean = true, val referenceChatHistory: Boolean = true)

object Text {
    private val STOP = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "at", "for", "with", "is", "are", "was", "were", "be", "been",
        "it", "this", "that", "i", "me", "my", "you", "your", "we", "our", "he", "she", "they", "them", "his", "her", "its", "do", "does", "did",
        "have", "has", "had", "can", "could", "would", "should", "will", "please", "about", "what", "which", "who", "how", "when", "where",
        "user", "user's", "so", "as", "by", "from", "up", "just", "also", "very", "really", "some", "any", "all", "not", "no", "yes", "am", "im", "i'm")
    fun tokens(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}+@.']+")).map { it.trim('.', '\'') }.filter { it.length > 1 }
    fun terms(s: String): List<String> = tokens(s).filter { it !in STOP }
    fun jaccard(a: String, b: String): Double {
        val x = terms(a).toSet(); val y = terms(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }
    /** Rough token estimate for budgets (≈ 4 chars/token for English, never 0). */
    fun approxTokens(s: String) = maxOf(1, (s.length + 3) / 4)
}

// ------------------------------------------------------------------------------------------------ extraction

data class Extracted(val text: String, val kind: MemoryKind, val confidence: Double, val slot: String? = null)

sealed interface MemoryCommand {
    data class Remember(val text: String) : MemoryCommand
    data class Forget(val query: String) : MemoryCommand
}

/**
 * Rule-based extraction (fast, deterministic, no model call). Turns first-person statements into third-person
 * memories ("I live in Hyderabad" → "Lives in Hyderabad"). Questions, hypotheticals and other people's statements are
 * ignored. The optional model pass ([ModelMemoryExtractor]) only runs when these rules find nothing.
 */
object MemoryExtractor {
    private val ws = Regex("\\s+")
    private val tail = Regex("(?:,?\\s+(?:by the way|btw|though|too|as well|now|anymore|lol))+$", RegexOption.IGNORE_CASE)
    private fun clean(s: String) = s.trim().trimEnd('.', '!', ',', ';').replace(tail, "").trim().trimEnd('.', '!', ',', ';').replace(ws, " ")
    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }

    fun command(message: String): MemoryCommand? {
        val m = message.trim()
        val forget = Regex("^(?:please\\s+)?forget\\s+(?:that\\s+|about\\s+|what i (?:said|told you) about\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(m)
            ?: Regex("^(?:please\\s+)?(?:delete|remove|erase)\\s+(?:the\\s+|my\\s+|any\\s+)?memor(?:y|ies)\\s+(?:about|of|that|saying)\\s+(.+)$", RegexOption.IGNORE_CASE).find(m)
        forget?.let { r -> val q = clean(r.groupValues[1]); if (q.isNotBlank() && !q.equals("it", true) && !q.equals("everything", true)) return MemoryCommand.Forget(q) }
        // "remember to buy milk" is a reminder request, not a memory.
        if (Regex("^(?:please\\s+)?(?:remember|don't forget|do not forget)\\s+to\\b", RegexOption.IGNORE_CASE).containsMatchIn(m)) return null
        Regex("^(?:please\\s+)?(?:remember|don't forget|do not forget|keep in mind|save (?:a |this )?memory)(?:\\s+that)?[:,]?\\s+(.+)$", RegexOption.IGNORE_CASE)
            .find(m)?.let { r -> val t = clean(r.groupValues[1]); if (t.length >= 3) return MemoryCommand.Remember(firstToThird(t)) }
        return null
    }

    /** "I prefer tea" → "Prefers tea"; "my sister is Anu" → "User's sister is Anu". */
    fun firstToThird(t: String): String {
        var s = clean(t)
        s = s.replace(Regex("^i am\\b|^i'm\\b", RegexOption.IGNORE_CASE), "Is")
            .replace(Regex("^i\\s+(\\w+)", RegexOption.IGNORE_CASE)) { mr -> verb3(mr.groupValues[1]) }
            .replace(Regex("\\bmy\\b", RegexOption.IGNORE_CASE), "User's")
            .replace(Regex("\\bme\\b", RegexOption.IGNORE_CASE), "the user")
            .replace(Regex("\\bI\\b"), "the user")
        return cap(s)
    }

    private fun verb3(v: String): String = when (v.lowercase()) {
        "have" -> "Has"; "do" -> "Does"; "go" -> "Goes"; "am" -> "Is"; "was" -> "Was"
        else -> cap(v.lowercase()).let { if (it.endsWith("s") || it.endsWith("sh") || it.endsWith("ch")) it + "es" else if (it.endsWith("y") && it.length > 2 && it[it.length - 2] !in "aeiou") it.dropLast(1) + "ies" else it + "s" }
    }

    private data class Rule(val re: Regex, val kind: MemoryKind, val conf: Double, val slot: String?, val render: (MatchResult) -> String)
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)
    private val RULES = listOf(
        Rule(r("\\bmy name is ([\\p{L} .'-]{2,40})"), MemoryKind.FACT, 0.95, "name") { "User's name is ${clean(it.groupValues[1])}" },
        Rule(r("\\bcall me ([\\p{L} .'-]{2,30})"), MemoryKind.PREFERENCE, 0.9, "nickname") { "Wants to be called ${clean(it.groupValues[1])}" },
        Rule(r("\\bi (?:live|stay) in ([\\p{L}0-9 ,.'-]{2,60})"), MemoryKind.FACT, 0.85, "home") { "Lives in ${clean(it.groupValues[1])}" },
        Rule(r("\\bi(?: am|'m) (?:from|based in) ([\\p{L} ,.'-]{2,60})"), MemoryKind.FACT, 0.8, "home") { "Is from ${clean(it.groupValues[1])}" },
        Rule(r("\\bi work (?:at|for|in) ([\\p{L}0-9 &.,'-]{2,60})"), MemoryKind.FACT, 0.85, "employer") { "Works at ${clean(it.groupValues[1])}" },
        Rule(r("\\bi(?: am|'m) (?:a|an) ([\\p{L} -]{3,40}?) (?:at|in) ([\\p{L}0-9 &.,'-]{2,60})"), MemoryKind.FACT, 0.75, "role") { "Is a ${clean(it.groupValues[1])} at ${clean(it.groupValues[2])}" },
        Rule(r("\\bi(?: am|'m) (?:a |an )?(student|engineer|developer|doctor|teacher|designer|nurse|lawyer|intern)\\b(?: at ([\\p{L}0-9 &.,'-]{2,60}))?"), MemoryKind.FACT, 0.7, "role") {
            "Is a ${it.groupValues[1].lowercase()}" + (it.groupValues[2].takeIf { g -> g.isNotBlank() }?.let { g -> " at ${clean(g)}" } ?: "") },
        Rule(r("\\bi(?: am|'m) allergic to ([\\p{L} ,'-]{2,60})"), MemoryKind.FACT, 0.95, null) { "Is allergic to ${clean(it.groupValues[1])}" },
        Rule(r("\\bi(?: am|'m) (?:a )?(vegetarian|vegan|pescatarian|diabetic|lactose intolerant)\\b"), MemoryKind.PREFERENCE, 0.9, "diet") { "Is ${it.groupValues[1].lowercase()}" },
        Rule(r("\\bmy birthday is (?:on )?([\\p{L}0-9 ,/-]{3,30})"), MemoryKind.FACT, 0.9, "birthday") { "Birthday is ${clean(it.groupValues[1])}" },
        Rule(r("\\bmy (wife|husband|partner|mom|mother|mum|dad|father|sister|brother|son|daughter|boss|manager|best friend|roommate)(?:'s name)? is (?:called |named )?([\\p{L} .'-]{2,30})"),
            MemoryKind.CONTACT, 0.85, null) { "User's ${it.groupValues[1].lowercase()} is ${clean(it.groupValues[2])}" },
        Rule(r("\\bi(?: am|'m) (?:currently )?working on ([\\p{L}0-9 &.,:'-]{3,80})"), MemoryKind.PROJECT, 0.75, null) { "Is working on ${clean(it.groupValues[1])}" },
        Rule(r("\\bi (?:really )?(?:prefer|like|love|enjoy) ([\\p{L}0-9 &.,'-]{2,60})"), MemoryKind.PREFERENCE, 0.7, null) { m -> firstToThird(m.value) },
        Rule(r("\\bi (?:really )?(?:hate|dislike|don't like|do not like|can't stand) ([\\p{L}0-9 &.,'-]{2,60})"), MemoryKind.PREFERENCE, 0.7, null) { m ->
            "Dislikes ${clean(m.groupValues[1])}" },
        Rule(r("\\bi (?:usually|always|normally) ([\\p{L}0-9 &.,:'-]{3,60})"), MemoryKind.PREFERENCE, 0.6, null) { m -> "Usually ${clean(m.groupValues[1])}" },
    )

    /** Durable facts in one user message (empty for questions / hypotheticals / short chit-chat). */
    fun extract(message: String): List<Extracted> {
        val m = message.trim()
        if (m.length < 6 || command(m) != null) return emptyList()
        val sentences = m.split(Regex("(?<=[.!?\\n])\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        val out = mutableListOf<Extracted>()
        for (s in sentences) {
            if (s.endsWith("?") || Regex("^(if|what if|suppose|imagine|would|could|can|do|does|is|are|should)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) continue
            if (Regex("\\b(he|she|they) (said|says|thinks)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)) continue
            for (rule in RULES) {
                val hit = rule.re.find(s) ?: continue
                val text = rule.render(hit).trim().take(200)
                if (text.length >= 6 && out.none { Text.jaccard(it.text, text) > 0.7 }) out += Extracted(text, rule.kind, rule.conf, rule.slot)
            }
        }
        return out.take(4)
    }

    /** True when a message looks like it MIGHT hold a personal fact the rules missed (gate for the model pass). */
    fun mightHoldFact(message: String): Boolean =
        message.length in 12..600 && !message.trim().endsWith("?") && Regex("\\b(i|i'm|i am|my|mine|we|our)\\b", RegexOption.IGNORE_CASE).containsMatchIn(message)
}

// ------------------------------------------------------------------------------------------------ the memory book

/** Result of processing one user message: shown as the "Memory updated" chip on that message. */
data class MemoryUpdate(val saved: List<SavedMemory> = emptyList(), val forgotten: List<SavedMemory> = emptyList(), val explicit: Boolean = false) {
    val changed get() = saved.isNotEmpty() || forgotten.isNotEmpty()
    fun chip(): String? = when {
        forgotten.isNotEmpty() && saved.isEmpty() -> "Memory updated · forgot ${forgotten.size}"
        saved.isNotEmpty() -> "Memory updated"
        else -> null
    }
}

/**
 * Saved memories: dedupe, single-value slots (a new home city replaces the old one), explicit remember/forget,
 * confirm/edit/delete, ranking for retrieval.
 */
class MemoryBook(private val repo: SavedMemoryRepo, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun all(): List<SavedMemory> = repo.all().sortedWith(compareByDescending<SavedMemory> { it.pinned }.thenByDescending { it.updatedAt })

    /** Processes a user message in [scope]. Explicit commands work even when auto-saving is off; TEMPORARY does nothing. */
    suspend fun onUserMessage(message: String, source: String, scope: MemoryScope, settings: MemorySettings): MemoryUpdate {
        if (!scope.writes) return MemoryUpdate()
        when (val c = MemoryExtractor.command(message)) {
            is MemoryCommand.Remember -> return MemoryUpdate(saved = listOf(save(c.text, MemoryKind.NOTE, inferred = false, confidence = 1.0, source = source, slot = slotOf(c.text))), explicit = true)
            is MemoryCommand.Forget -> return MemoryUpdate(forgotten = forget(c.query), explicit = true)
            null -> Unit
        }
        if (!settings.savedMemories) return MemoryUpdate()
        val saved = MemoryExtractor.extract(message).map { save(it.text, it.kind, inferred = true, confidence = it.confidence, source = source, slot = it.slot) }
        return MemoryUpdate(saved = saved)
    }

    /** Adds facts a model pass found (always inferred, capped confidence). */
    suspend fun addInferred(items: List<Extracted>, source: String): List<SavedMemory> =
        items.map { save(it.text, it.kind, inferred = true, confidence = it.confidence.coerceAtMost(0.7), source = source, slot = it.slot) }

    private fun slotOf(text: String): String? = MemoryExtractor.extract("I " + text.replaceFirstChar { it.lowercase() }).firstOrNull()?.slot
        ?: MemoryExtractor.extract(text).firstOrNull()?.slot

    suspend fun save(text: String, kind: MemoryKind, inferred: Boolean, confidence: Double, source: String, slot: String? = null): SavedMemory {
        val now = clock()
        val existing = repo.all()
        // Same slot (single-valued fact): replace — recency wins over an old value.
        val sameSlot = slot?.let { s -> existing.firstOrNull { it.slot == s } }
        val dup = sameSlot ?: existing.firstOrNull { Text.jaccard(it.text, text) >= 0.75 || it.text.equals(text, true) }
        val m = if (dup != null) dup.copy(text = text, kind = kind, updatedAt = now,
            // an explicit statement upgrades an inferred one; an inferred repeat raises confidence a little
            inferred = dup.inferred && inferred, confidence = if (inferred) minOf(0.95, maxOf(dup.confidence, confidence) + 0.05) else 1.0,
            slot = slot ?: dup.slot, source = source)
        else SavedMemory(text = text, kind = kind, inferred = inferred, confidence = confidence, source = source, slot = slot, createdAt = now, updatedAt = now)
        val id = repo.upsert(m)
        return m.copy(id = id)
    }

    /** "forget X": deletes every memory that matches the phrase (substring or strong word overlap). */
    suspend fun forget(query: String): List<SavedMemory> {
        val q = query.trim().removePrefix("that ").removePrefix("about ")
        val hits = repo.all().filter { m ->
            m.text.contains(q, ignoreCase = true) || run {
                val qt = Text.terms(q).toSet()
                qt.isNotEmpty() && (qt intersect Text.terms(m.text).toSet()).size.toDouble() / qt.size >= 0.6
            }
        }
        hits.forEach { repo.delete(it.id) }
        return hits
    }

    suspend fun confirm(id: Long) { repo.all().firstOrNull { it.id == id }?.let { repo.upsert(it.copy(confirmed = true, inferred = false, confidence = 1.0, updatedAt = clock())) } }
    suspend fun edit(id: Long, text: String) { repo.all().firstOrNull { it.id == id }?.let { repo.upsert(it.copy(text = text.trim(), inferred = false, confirmed = true, confidence = 1.0, updatedAt = clock())) } }
    suspend fun pin(id: Long, pinned: Boolean) { repo.all().firstOrNull { it.id == id }?.let { repo.upsert(it.copy(pinned = pinned)) } }
    suspend fun delete(id: Long) = repo.delete(id)
    suspend fun wipe() = repo.deleteAll()

    /** Memories most relevant to [query]: BM25 over the memory texts + pinned/confidence/recency boosts. */
    suspend fun relevant(query: String, limit: Int): List<SavedMemory> {
        val all = repo.all()
        if (all.isEmpty()) return emptyList()
        val idx = Bm25Index<SavedMemory>().apply { all.forEach { add(it, it.text) } }
        val scored = idx.search(query, all.size).associate { it.item.id to it.score }
        val now = clock()
        return all.map { m ->
            val bm = scored[m.id] ?: 0.0
            val recency = exp(-(now - m.updatedAt).coerceAtLeast(0) / (30.0 * DAY))
            m to (bm + (if (m.pinned) 2.0 else 0.0) + 0.3 * recency + (if (m.slot in setOf("name", "nickname")) 0.5 else 0.0)) * (if (m.inferred) m.confidence else 1.0)
        }.filter { it.second > 0.25 }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    companion object { const val DAY = 86_400_000.0 }
}

// ------------------------------------------------------------------------------------------------ BM25

class Bm25Index<T>(private val k1: Double = 1.2, private val b: Double = 0.75) {
    private data class Doc<T>(val item: T, val tf: Map<String, Int>, val len: Int)
    data class Hit<T>(val item: T, val score: Double)
    private val docs = mutableListOf<Doc<T>>()
    private val df = HashMap<String, Int>()
    private var totalLen = 0L

    val size get() = docs.size

    fun add(item: T, text: String) {
        val terms = Text.terms(text)
        val tf = terms.groupingBy { it }.eachCount()
        docs += Doc(item, tf, terms.size)
        tf.keys.forEach { df[it] = (df[it] ?: 0) + 1 }
        totalLen += terms.size
    }

    fun removeIf(pred: (T) -> Boolean) {
        val gone = docs.filter { pred(it.item) }
        gone.forEach { d -> d.tf.keys.forEach { k -> df[k] = (df[k] ?: 1) - 1; if (df[k]!! <= 0) df.remove(k) }; totalLen -= d.len }
        docs.removeAll(gone.toSet())
    }

    fun search(query: String, limit: Int): List<Hit<T>> {
        if (docs.isEmpty()) return emptyList()
        val q = Text.terms(query).distinct()
        if (q.isEmpty()) return emptyList()
        val n = docs.size.toDouble()
        val avg = (totalLen.toDouble() / docs.size).coerceAtLeast(1.0)
        return docs.map { d ->
            var s = 0.0
            for (t in q) {
                val f = d.tf[t] ?: continue
                val idf = ln(1 + (n - (df[t] ?: 0) + 0.5) / ((df[t] ?: 0) + 0.5))
                s += idf * (f * (k1 + 1)) / (f + k1 * (1 - b + b * d.len / avg))
            }
            Hit(d.item, s)
        }.filter { it.score > 0 }.sortedByDescending { it.score }.take(limit)
    }
}

// ------------------------------------------------------------------------------------------------ chat history

/** One retrievable piece of a past chat: a user/assistant exchange or a rolling summary. */
data class HistoryChunk(val id: Long = 0, val chatId: Long, val chatTitle: String, val text: String, val createdAt: Long, val summary: Boolean = false)

interface HistoryRepo {
    suspend fun all(): List<HistoryChunk>
    suspend fun add(c: HistoryChunk): Long
    suspend fun deleteChat(chatId: Long)
    /** Removes only the rolling-summary chunks of [chatId] (used to replace a chat's summary instead of appending). */
    suspend fun deleteSummaries(chatId: Long)
    suspend fun deleteMatching(phrase: String): Int
    suspend fun deleteAll()
}

class InMemoryHistoryRepo : HistoryRepo {
    private val items = mutableListOf<HistoryChunk>(); private var next = 1L
    override suspend fun all() = synchronized(items) { items.toList() }
    override suspend fun add(c: HistoryChunk): Long = synchronized(items) { val id = next++; items += c.copy(id = id); id }
    override suspend fun deleteChat(chatId: Long) { synchronized(items) { items.removeAll { it.chatId == chatId } } }
    override suspend fun deleteSummaries(chatId: Long) { synchronized(items) { items.removeAll { it.chatId == chatId && it.summary } } }
    override suspend fun deleteMatching(phrase: String): Int = synchronized(items) { val n = items.size; items.removeAll { it.text.contains(phrase, true) }; n - items.size }
    override suspend fun deleteAll() { synchronized(items) { items.clear() } }
}

/**
 * "Reference chat history": indexes every finished exchange of every NORMAL chat (temporary chats are never indexed)
 * and retrieves the best snippets for a new message, excluding the current chat's recent turns (already in context).
 */
class ChatHistoryIndex(private val repo: HistoryRepo, private val clock: () -> Long = System::currentTimeMillis) {
    private var index: Bm25Index<HistoryChunk>? = null
    private suspend fun idx(): Bm25Index<HistoryChunk> = index ?: Bm25Index<HistoryChunk>().also { i -> repo.all().forEach { i.add(it, it.chatTitle + " " + it.text) }; index = i }

    suspend fun addExchange(chatId: Long, title: String, user: String, assistant: String, scope: MemoryScope) {
        if (!scope.writes || (user.isBlank() && assistant.isBlank())) return
        val text = "User: ${user.take(500)}\nAssistant: ${assistant.take(700)}"
        val c = HistoryChunk(chatId = chatId, chatTitle = title, text = text, createdAt = clock())
        val id = repo.add(c)
        idx().add(c.copy(id = id), title + " " + text)
    }

    suspend fun addSummary(chatId: Long, title: String, summary: String, scope: MemoryScope) {
        if (!scope.writes || summary.isBlank()) return
        // One summary per chat: the new rolling summary REPLACES the old one (it already folds the old one in).
        repo.deleteSummaries(chatId)
        idx().removeIf { it.chatId == chatId && it.summary }
        val c = HistoryChunk(chatId = chatId, chatTitle = title, text = "Summary: ${summary.take(1200)}", createdAt = clock(), summary = true)
        val id = repo.add(c)
        idx().add(c.copy(id = id), title + " " + c.text)
    }

    /** "Threads" on the Memory screen: the current summary of each chat, newest first. */
    suspend fun threads(): List<HistoryChunk> =
        repo.all().filter { it.summary }.groupBy { it.chatId }.map { (_, v) -> v.maxBy { it.createdAt } }.sortedByDescending { it.createdAt }

    /** Deletes a thread from memory: its summary and every indexed exchange of that chat (the chat itself stays). */
    suspend fun deleteThread(chatId: Long) = deleteChat(chatId)

    suspend fun search(query: String, limit: Int, excludeChatId: Long? = null): List<HistoryChunk> {
        val now = clock()
        return idx().search(query, limit * 4)
            .filter { it.item.chatId != excludeChatId }
            .map { h -> h to h.score * (1.0 + 0.25 * exp(-(now - h.item.createdAt).coerceAtLeast(0) / (14.0 * MemoryBook.DAY))) * (if (h.item.summary) 1.1 else 1.0) }
            .filter { it.second >= 1.0 }
            .sortedByDescending { it.second }.take(limit).map { it.first.item }
    }

    suspend fun forget(phrase: String): Int { val n = repo.deleteMatching(phrase); index = null; return n }
    suspend fun deleteChat(chatId: Long) { repo.deleteChat(chatId); index = null }
    suspend fun wipe() { repo.deleteAll(); index = null }
}

/**
 * Per-chat rolling summary (c): when the turns that no longer fit the window exceed [triggerTokens], they are folded
 * into the running summary (model summariser when available, extractive fallback otherwise). The summary is kept in
 * the chat and also indexed for cross-chat retrieval.
 */
object RollingSummary {
    data class Turn(val role: String, val text: String)

    /** Which leading turns fall out of a [windowTokens] budget (the newest turns are kept). */
    fun overflow(turns: List<Turn>, windowTokens: Int): Int {
        var used = 0; var keep = 0
        for (t in turns.asReversed()) { used += Text.approxTokens(t.text) + 4; if (used > windowTokens) break; keep++ }
        return (turns.size - keep).coerceAtLeast(0)
    }

    /** Extractive fallback: first sentence of each user turn + key facts, capped. */
    fun extractive(previous: String, dropped: List<Turn>, maxChars: Int = 900): String {
        val bits = dropped.filter { it.role == "user" }.map { it.text.split(Regex("(?<=[.!?])\\s")).first().take(160) }
        val merged = (if (previous.isBlank()) "" else "$previous ") + bits.joinToString(" · ")
        return if (merged.length <= maxChars) merged.trim() else "…" + merged.takeLast(maxChars).trim()
    }

    fun prompt(previous: String, dropped: List<Turn>): Pair<String, String> =
        "Update the running summary of a conversation. Keep names, dates, decisions, open questions. Max 120 words." to
            ("Summary so far: ${previous.ifBlank { "(none)" }}\n\nNew turns:\n" + dropped.joinToString("\n") { "${it.role}: ${it.text.take(600)}" })
}

/** Builds the memory block for a prompt within a token budget: saved memories first, then chat-history snippets. */
object MemoryContext {
    data class Block(val text: String, val memories: List<SavedMemory>, val history: List<HistoryChunk>, val tokens: Int)

    fun build(memories: List<SavedMemory>, history: List<HistoryChunk>, budgetTokens: Int): Block {
        val lines = mutableListOf<String>()
        var used = 0
        val usedMem = mutableListOf<SavedMemory>(); val usedHist = mutableListOf<HistoryChunk>()
        if (memories.isNotEmpty()) {
            val head = "Known about the user (saved memories):"
            used += Text.approxTokens(head)
            lines += head
            for (m in memories) {
                val l = "- " + m.text + if (m.inferred) " (inferred)" else ""
                val t = Text.approxTokens(l)
                if (used + t > budgetTokens) break
                lines += l; used += t; usedMem += m
            }
        }
        if (history.isNotEmpty() && used < budgetTokens - 24) {
            val head = "From earlier chats:"
            lines += head; used += Text.approxTokens(head)
            for (h in history) {
                val room = (budgetTokens - used) * 4
                if (room < 80) break
                val l = "- [${h.chatTitle.take(40)}] " + h.text.replace('\n', ' ').take(minOf(room, 360))
                lines += l; used += Text.approxTokens(l); usedHist += h
            }
            if (usedHist.isEmpty()) lines.removeAt(lines.size - 1)
        }
        val text = if (usedMem.isEmpty() && usedHist.isEmpty()) "" else lines.joinToString("\n")
        return Block(text, usedMem, usedHist, if (text.isEmpty()) 0 else used)
    }
}

// ------------------------------------------------------------------------------------------------ frequent contacts

data class ContactUse(val key: String, val name: String, val number: String? = null, val uses: Int = 0, val lastUsedAt: Long = 0, val channels: Set<String> = emptySet())

interface ContactUseRepo {
    suspend fun all(): List<ContactUse>
    suspend fun upsert(c: ContactUse)
    suspend fun delete(key: String)
    suspend fun deleteAll()
}

class InMemoryContactRepo : ContactUseRepo {
    private val m = linkedMapOf<String, ContactUse>()
    override suspend fun all() = synchronized(m) { m.values.toList() }
    override suspend fun upsert(c: ContactUse) { synchronized(m) { m[c.key] = c } }
    override suspend fun delete(key: String) { synchronized(m) { m.remove(key) } }
    override suspend fun deleteAll() { synchronized(m) { m.clear() } }
}

/**
 * Frequently used contacts, learned ONLY from approved calls/messages (tool use), ranked by frequency × recency
 * (frecency, 14-day half-life). [resolve] answers "message Anu" with the contact the user actually talks to.
 */
class ContactRanking(private val repo: ContactUseRepo, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val HALF_LIFE_DAYS = 14.0
        fun key(name: String, number: String?): String = number?.filter { it.isDigit() }?.takeLast(10)?.takeIf { it.length >= 6 } ?: name.trim().lowercase()
        /** Tool args that name a contact (call / SMS / messaging / share-to / notification reply). */
        val CONTACT_TOOLS = Regex("dial|call|sms|message|whatsapp|send_text|draft_message|share_to|notif_reply|email")
    }

    fun score(c: ContactUse, now: Long = clock()): Double {
        val ageDays = (now - c.lastUsedAt).coerceAtLeast(0) / MemoryBook.DAY
        return ln(1.0 + c.uses) + 2.0 * Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
    }

    suspend fun record(name: String, number: String?, channel: String) {
        if (name.isBlank() && number.isNullOrBlank()) return
        val k = key(name, number)
        val old = repo.all().firstOrNull { it.key == k }
        repo.upsert(ContactUse(k, name.ifBlank { old?.name ?: number.orEmpty() }, number ?: old?.number, (old?.uses ?: 0) + 1, clock(), (old?.channels ?: emptySet()) + channel))
    }

    suspend fun top(limit: Int): List<ContactUse> { val now = clock(); return repo.all().sortedByDescending { score(it, now) }.take(limit) }

    /** Best learned contact whose name starts with / contains [query] (word-wise), or null. */
    suspend fun resolve(query: String): ContactUse? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val now = clock()
        return repo.all().filter { c -> c.name.lowercase().split(' ').any { it.startsWith(q) } || c.name.lowercase().contains(q) }
            .maxByOrNull { score(it, now) }
    }

    suspend fun forget(key: String) = repo.delete(key)
    suspend fun wipe() = repo.deleteAll()
}
