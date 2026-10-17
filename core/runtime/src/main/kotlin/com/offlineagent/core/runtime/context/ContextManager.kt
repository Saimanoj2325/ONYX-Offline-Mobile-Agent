package com.offlineagent.core.runtime.context

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.Role

/** One stored chat message (already persisted; [id] increases with time). */
data class Turn(val id: Long, val role: Role, val text: String)

/** Rolling summary of the oldest part of a conversation: covers every turn with id <= [upToId]. */
data class ConversationSummary(val text: String, val upToId: Long)

/** A passage paged back in from long-term memory (older turns of any chat, summaries, documents). */
data class MemoryPage(val source: String, val text: String, val score: Float, val turnIds: Set<Long> = emptySet())

fun interface TokenCounter {
    /** Exact count when a model is loaded; implementations fall back to [estimate]. */
    suspend fun count(text: String): Int

    companion object {
        /** Qwen/Llama BPE averages ~3.5-4 chars/token for English; 3.2 keeps us on the safe side for code/Indic text. */
        fun estimate(text: String): Int = (text.length / 3.2).toInt() + 1
        val ESTIMATE = TokenCounter { estimate(it) }
    }
}

fun interface ConversationSummarizer {
    /** Folds [turns] into [previous] (may be null) and returns a new summary of at most ~[maxWords] words. */
    suspend fun summarize(previous: String?, turns: List<Turn>, maxWords: Int): String
}

fun interface LongTermMemory {
    /** Most relevant pages for [query], skipping anything built from [excludeTurnIds] (already in the window). */
    suspend fun recall(query: String, excludeTurnIds: Set<Long>, k: Int): List<MemoryPage>
}

data class ContextConfig(
    /** Start folding old turns into the summary when live history exceeds this share of the budget. */
    val summarizeAt: Double = 0.75,
    /** Share of the budget long-term memory pages may use. */
    val memoryShare: Double = 0.15,
    val memoryPages: Int = 4,
    /**
     * Phase 4b: hard cap on memory-page tokens per request. Every memory token is prefilled again on each turn
     * (it sits in the new user message), so on a phone it adds directly to the time before the first token.
     */
    val memoryMaxTokens: Int = Int.MAX_VALUE,
    val summaryWords: Int = 250,
    /** Max tokens of turns folded per summarisation call (each call must itself fit in the window). */
    val maxTokensPerSummaryCall: Int = 3000,
    /** Tokens reserved for chat-template markup per message. */
    val perMessageOverhead: Int = 8,
    val safetyMargin: Int = 64,
)

data class ContextPlanResult(
    val messages: List<ChatMessage>,
    /** Estimated prompt tokens of [messages]. */
    val promptTokens: Int,
    val budget: Int,
    val summary: ConversationSummary?,
    /** True when [summary] is new and must be persisted. */
    val summaryChanged: Boolean,
    val pages: List<MemoryPage>,
    /** Live turns that did not fit and were not (yet) summarised. */
    val droppedTurns: Int,
    val liveTurns: Int,
)

/**
 * Builds the prompt for one chat request inside a fixed context window, so a conversation can go on "forever":
 *
 *   [system prompt]                               <- constant: its KV cache is reused across turns and app restarts
 *   (user) Summary of our earlier conversation…   <- rolling summary of folded turns, changes rarely
 *   (assistant) Understood.
 *   …recent turns, newest that fit…               <- unchanged between turns: KV prefix reuse
 *   (user) [relevant memory pages] + question     <- only this part is new each turn
 *
 * When live history passes [ContextConfig.summarizeAt] of the budget (or no longer fits), the oldest turns are folded
 * into the summary with the model itself. Anything older is still reachable through [LongTermMemory], which pages
 * relevant old turns/summaries/documents back into the final user message.
 */
class ContextManager(
    private val counter: TokenCounter,
    private val summarizer: ConversationSummarizer?,
    private val memory: LongTermMemory?,
    private val config: ContextConfig = ContextConfig(),
) {
    suspend fun build(
        system: String,
        history: List<Turn>,
        summary: ConversationSummary?,
        userText: String,
        contextLength: Int,
        maxOutputTokens: Int,
        extraContext: String = "",
    ): ContextPlanResult {
        val budget = (contextLength - maxOutputTokens - config.safetyMargin).coerceAtLeast(256)
        val ov = config.perMessageOverhead
        val fixed = counter.count(system) + counter.count(userText) + counter.count(extraContext) + 3 * ov
        var sum = summary
        var changed = false

        // Turns not covered by the summary, oldest first.
        var live = history.filter { summary == null || it.id > summary.upToId }
        val tokens = HashMap<Long, Int>()
        suspend fun t(turn: Turn) = tokens.getOrPut(turn.id) { counter.count(turn.text) + ov }
        suspend fun liveTokens() = live.sumOf { t(it) }
        suspend fun summaryTokens() = sum?.let { counter.count(it.text) + 2 * ov + 12 } ?: 0

        val memBudget = if (memory != null) minOf((budget * config.memoryShare).toInt(), config.memoryMaxTokens) else 0
        val historyBudget = (budget - fixed - memBudget).coerceAtLeast(0)

        // Fold old turns into the summary while history is over the high-water mark.
        if (summarizer != null) {
            var guard = 0
            while (live.size > 2 && liveTokens() + summaryTokens() > historyBudget * config.summarizeAt && guard++ < 8) {
                // Fold the oldest half of the live turns (bounded per call), keeping user/assistant pairs together.
                val target = liveTokens() / 2
                val fold = ArrayList<Turn>()
                var acc = 0
                for (turn in live) {
                    if (fold.size >= live.size - 2) break
                    if (acc >= target || acc + t(turn) > config.maxTokensPerSummaryCall && fold.isNotEmpty()) break
                    fold += turn; acc += t(turn)
                }
                if (fold.lastOrNull()?.role == Role.USER && fold.size < live.size - 2) fold += live[fold.size]
                if (fold.isEmpty()) break
                val text = summarizer.summarize(sum?.text, fold, config.summaryWords).trim()
                if (text.isBlank()) break
                sum = ConversationSummary(text, fold.last().id)
                changed = true
                live = live.drop(fold.size)
            }
        }

        // Newest turns that fit.
        val room = historyBudget - summaryTokens()
        val kept = ArrayList<Turn>()
        var used = 0
        for (turn in live.asReversed()) {
            val n = t(turn)
            if (used + n > room) break
            kept += turn; used += n
        }
        kept.reverse()
        // A window must not start with an assistant reply.
        while (kept.firstOrNull()?.role == Role.ASSISTANT) { used -= t(kept.first()); kept.removeAt(0) }
        val dropped = live.size - kept.size

        // Page relevant long-term memory back in (excluding what is already visible).
        val pages = ArrayList<MemoryPage>()
        if (memory != null && memBudget > 0 && userText.isNotBlank()) {
            val visible = kept.map { it.id }.toSet()
            var mem = 0
            for (p in memory.recall(userText, visible, config.memoryPages)) {
                if (p.turnIds.isNotEmpty() && p.turnIds.all { it in visible }) continue
                val n = counter.count(p.text) + 6
                if (mem + n > memBudget) continue
                pages += p; mem += n
            }
        }

        val msgs = ArrayList<ChatMessage>()
        msgs += ChatMessage(Role.SYSTEM, system)
        sum?.let {
            msgs += ChatMessage(Role.USER, "Summary of our earlier conversation (for context):\n" + it.text)
            msgs += ChatMessage(Role.ASSISTANT, "Understood. I will keep that in mind.")
        }
        kept.forEach { msgs += ChatMessage(it.role, it.text) }
        val finalUser = buildString {
            if (pages.isNotEmpty()) {
                append("Possibly relevant notes from earlier conversations and documents:\n")
                pages.forEachIndexed { i, p -> append("(").append(i + 1).append(") [").append(p.source).append("] ").append(p.text.trim()).append('\n') }
                append('\n')
            }
            if (extraContext.isNotBlank()) append(extraContext.trim()).append("\n\n")
            append(userText)
        }
        msgs += ChatMessage(Role.USER, finalUser)
        val prompt = fixed + used + summaryTokens() + pages.sumOf { counter.count(it.text) + 6 }
        return ContextPlanResult(msgs, prompt, budget, sum, changed, pages, dropped, kept.size)
    }

    companion object {
        /** Prompt used by the app's [ConversationSummarizer] (one plain completion). */
        fun summaryPrompt(previous: String?, turns: List<Turn>, maxWords: Int): Pair<String, String> {
            val sys = "You compress chat history. Write a factual summary in at most $maxWords words: names, numbers, " +
                "decisions, the user's goals and preferences, open questions. Third person, no preamble."
            val user = buildString {
                if (!previous.isNullOrBlank()) append("Existing summary:\n").append(previous).append("\n\n")
                append("New messages to fold in:\n")
                turns.forEach { append(if (it.role == Role.USER) "User: " else "Assistant: ").append(it.text.take(4000)).append('\n') }
                append("\nUpdated summary:")
            }
            return sys to user
        }
    }
}
