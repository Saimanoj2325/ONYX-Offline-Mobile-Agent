package com.offlineagent.core.agents.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 0.6.2.2: questions ABOUT earlier chats ("summarise our previous chats", "what did we talk about yesterday?").
 *
 * BM25 retrieval ([ChatHistoryIndex.search]) matches words, and a question like "give me 2-3 lines of summary of our
 * previous chats" shares no words with the chats themselves, so the model got nothing real and invented "Chat 1:
 * [text]". For these questions the context is instead the Threads (each chat's rolling summary) plus the newest
 * exchanges of recent chats, newest first, from onyx-agents.db. Temporary chats and the Use memory / Reference chat
 * history switches are respected: when off, the block says so, so the model tells the user instead of making it up.
 */
object PreviousChats {
    private val ABOUT = Regex(
        "\\b(previous|past|earlier|older|last|recent|other|old|our|my) (chats?|conversations?|sessions?|discussions?)\\b" +
            "|\\bwhat (did|have) (we|i) (talk|talked|discuss|discussed|chat|chatted|say|said|ask|asked)\\b" +
            "|\\b(we|i) (talked|discussed|chatted|spoke) about (yesterday|earlier|before|last)\\b" +
            "|\\b(chat|conversation) history\\b|\\brecap of (our|my)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isAbout(text: String): Boolean = ABOUT.containsMatchIn(text)

    /** Time window named in the question ("yesterday", "today", "last week"), else null (all chats). */
    fun window(text: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): LongRange? {
        val t = text.lowercase()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun day(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        return when {
            "yesterday" in t -> day(today.minusDays(1)) until day(today)
            Regex("\\btoday\\b|\\bthis morning\\b").containsMatchIn(t) -> day(today) until day(today.plusDays(1))
            Regex("\\b(last|this|past) week\\b").containsMatchIn(t) -> day(today.minusDays(7)) until day(today.plusDays(1))
            else -> null
        }
    }

    /** One earlier chat: its Threads summary when it has one, else its newest exchanges. */
    data class Digest(val chatId: Long, val title: String, val lastAt: Long, val summary: String?, val snippets: List<String>)

    /** Recent chats (newest first) from the history index, excluding [excludeChatId], optionally within [range]. */
    fun digests(chunks: List<HistoryChunk>, excludeChatId: Long?, range: LongRange?, limit: Int = 6): List<Digest> =
        chunks.filter { it.chatId != excludeChatId }
            .groupBy { it.chatId }
            .mapNotNull { (id, cs) ->
                val inRange = if (range == null) cs else cs.filter { it.createdAt in range }
                if (inRange.isEmpty()) return@mapNotNull null
                val summary = cs.filter { it.summary }.maxByOrNull { it.createdAt }?.text?.removePrefix("Summary: ")
                val ex = inRange.filter { !it.summary }.sortedByDescending { it.createdAt }.take(2).map { it.text }
                Digest(id, cs.maxBy { it.createdAt }.chatTitle, inRange.maxOf { it.createdAt }, summary, ex)
            }
            .sortedByDescending { it.lastAt }
            .take(limit)

    enum class Status { OK, TEMPORARY, OFF, EMPTY }

    data class Block(val text: String, val status: Status, val chats: Int)

    /** The context block for the question, within about [budgetTokens] (summaries first, then snippets). */
    fun block(digests: List<Digest>, budgetTokens: Int, rangeLabel: String? = null): Block {
        if (digests.isEmpty()) return Block(
            "Earlier chats: none ${rangeLabel?.let { "from $it " } ?: ""}are saved in memory yet. Say so plainly; do not invent any.", Status.EMPTY, 0)
        val head = "Your earlier chats with the user${rangeLabel?.let { " ($it)" } ?: ""}, newest first. Use only these facts; do not invent chats:"
        val lines = mutableListOf(head)
        var used = Text.approxTokens(head)
        var n = 0
        for (d in digests) {
            val body = (d.summary?.let { "Summary: $it" } ?: d.snippets.joinToString(" | ")).replace('\n', ' ').trim()
            if (body.isEmpty()) continue
            val room = (budgetTokens - used) * 4
            if (room < 80) break
            val line = "- \"${d.title.take(50)}\": " + body.take(minOf(room - 60, if (d.summary != null) 420 else 300).coerceAtLeast(40))
            lines += line; used += Text.approxTokens(line); n++
        }
        return Block(lines.joinToString("\n"), if (n > 0) Status.OK else Status.EMPTY, n)
    }

    /**
     * Everything the chat / canvas path needs: the block for [query], honouring the temporary chat ([scope]) and the
     * memory switches ([settings]). Reads [index] only when allowed.
     */
    suspend fun contextFor(
        query: String, index: ChatHistoryIndex, chatId: Long?, scope: MemoryScope, settings: MemorySettings,
        budgetTokens: Int, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault(),
    ): Block {
        if (!scope.reads) return Block("This is a temporary chat: earlier chats are not used here. Tell the user that, briefly.", Status.TEMPORARY, 0)
        if (!settings.referenceChatHistory) return Block(
            "Reference chat history is turned off in Settings → Memory, so earlier chats are not available. Tell the user that, briefly; do not invent any.", Status.OFF, 0)
        val range = window(query, now, zone)
        val label = when {
            range == null -> null
            "yesterday" in query.lowercase() -> "yesterday"
            Regex("\\btoday\\b|\\bthis morning\\b", RegexOption.IGNORE_CASE).containsMatchIn(query) -> "today"
            else -> "the last 7 days"
        }
        return block(digests(index.allChunks(), chatId, range), budgetTokens, label)
    }
}
