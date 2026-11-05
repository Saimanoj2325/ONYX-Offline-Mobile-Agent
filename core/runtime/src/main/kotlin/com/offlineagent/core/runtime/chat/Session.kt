package com.offlineagent.core.runtime.chat

import com.offlineagent.core.inference.Role
import com.offlineagent.core.runtime.context.ConversationSummarizer
import com.offlineagent.core.runtime.context.ConversationSummary
import com.offlineagent.core.runtime.context.TokenCounter
import com.offlineagent.core.runtime.context.Turn

/**
 * 0.6.2.1: one persisted chat message, as the app keeps it in Room (a mirror of MessageEntity, so the session logic is
 * pure Kotlin and JVM-tested). The session is always rebuilt from these rows — never from the engine's KV cache — so it
 * survives a model reload, an idle unload and an app restart.
 */
data class StoredMessage(
    val id: Long,
    val role: String,
    val text: String,
    val kind: String? = null,
    val reasoning: String = "",
    val uiJson: String? = null,
    val error: String? = null,
    /** For a summary row: "upTo:<last folded message id>". */
    val runId: String? = null,
)

/** Turns the stored rows of a chat into model turns (oldest first). */
object SessionHistory {
    const val KIND_SUMMARY = "summary"
    const val KIND_ONYX = "onyx"
    const val KIND_AUTOMATION = "automation"
    const val KIND_CANVAS = "canvas"
    /** Per-turn cap: answers are kept verbatim up to this size (the token budget trims further when needed). */
    const val MAX_TURN_CHARS = 6000

    /**
     * Live turns of the chat. [dropTrailingUser]: the request being answered right now is already stored as the last
     * user row (possibly followed by its empty placeholder bubble); it is sent separately, so it is not history.
     */
    fun turns(msgs: List<StoredMessage>, dropTrailingUser: Boolean = true): List<Turn> {
        val rows = msgs.filter { it.error == null && it.kind != KIND_SUMMARY }
        val out = rows.mapNotNull { m ->
            when {
                m.role == "user" -> Turn(m.id, Role.USER, m.text.ifBlank { "(photo)" }.take(MAX_TURN_CHARS))
                m.role == "system" -> null
                m.kind == KIND_CANVAS && m.uiJson != null -> Turn(m.id, Role.ASSISTANT, "(I showed a canvas: ${m.text.take(1500)})")
                m.kind == KIND_AUTOMATION && m.text.isNotBlank() -> Turn(m.id, Role.ASSISTANT, "(automation) ${m.text}".take(600))
                m.kind == KIND_ONYX && (m.text.isNotBlank() || m.reasoning.isNotBlank()) -> {
                    // The visible answer verbatim (follow-ups such as "make it a pdf" need all of it), plus the task notes
                    // (steps and their results) without the truncated copy of the answer they end with.
                    val notes = m.reasoning.substringBefore("Answer given:").trim().take(700)
                    val text = when {
                        m.text.isBlank() -> notes
                        notes.isBlank() -> m.text
                        else -> "${m.text}\n\n(task notes: $notes)"
                    }
                    Turn(m.id, Role.ASSISTANT, text.take(MAX_TURN_CHARS))
                }
                m.text.isNotBlank() -> Turn(m.id, Role.ASSISTANT,
                    (if (com.offlineagent.core.openui.OpenUiMessage.hasProgram(m.text)) com.offlineagent.core.openui.OpenUiMessage.toPlainText(m.text) else m.text).take(MAX_TURN_CHARS))
                else -> null
            }
        }
        // Placeholders of the turn in progress (an empty agent / ONYX bubble) produce no turn, so the trailing user turn
        // is the request itself.
        return if (dropTrailingUser && out.lastOrNull()?.role == Role.USER) out.dropLast(1) else out
    }

    /** The newest rolling summary stored for the chat (kind = summary, runId = "upTo:<id>"). */
    fun latestSummary(msgs: List<StoredMessage>): ConversationSummary? =
        msgs.lastOrNull { it.kind == KIND_SUMMARY }?.let { ConversationSummary(it.text, it.runId?.removePrefix("upTo:")?.toLongOrNull() ?: 0) }

    /** The summary row to store for [s] (role system, kind summary, runId "upTo:<id>"). */
    fun summaryRunId(s: ConversationSummary): String = "upTo:${s.upToId}"
}

/**
 * 0.6.2.1: the whole session as ONE text block, for every route that builds its own prompt (agents planner / argument
 * filler / writer, the legacy tool agent, the report writer, canvas, automation goals). Plain chat uses
 * [com.offlineagent.core.runtime.context.ContextManager] with the same turns and summary, as real chat messages.
 *
 *   What I know (saved memories / other chats)      <- memory block, when not a temporary chat
 *   Summary of earlier messages in this chat: …     <- rolling summary once the window fills
 *   User: … / Assistant: …                          <- recent turns VERBATIM, newest that fit the budget
 *
 * The latest exchange is always present: when even it does not fit, the previous answer is cut to the room left, never
 * dropped. No route sends the latest message alone.
 */
object SessionContext {
    data class Result(
        val text: String,
        val summary: ConversationSummary?,
        /** True when [summary] was just written and must be persisted (Room, kind = summary). */
        val summaryChanged: Boolean,
        val keptTurns: Int,
        val droppedTurns: Int,
        val tokens: Int,
    ) {
        val isEmpty: Boolean get() = text.isBlank()
    }

    /** Token budget of the session block for a route that builds its own prompt, from the context window. */
    fun budgetFor(contextLength: Int): Int = when {
        contextLength <= 2048 -> 450
        contextLength <= 4096 -> 1100
        contextLength <= 8192 -> 2200
        else -> (contextLength / 4).coerceAtMost(4000)
    }

    suspend fun build(
        turns: List<Turn>,
        summary: ConversationSummary?,
        memory: String,
        budgetTokens: Int,
        counter: TokenCounter = TokenCounter.ESTIMATE,
        summarizer: ConversationSummarizer? = null,
        summaryWords: Int = 150,
    ): Result {
        var sum = summary
        var changed = false
        var live = turns.filter { sum == null || it.id > sum!!.upToId }
        val memText = memory.trim()
        val memTokens = if (memText.isBlank()) 0 else counter.count(memText) + 4
        val room0 = (budgetTokens - memTokens).coerceAtLeast(budgetTokens / 2)
        val cache = HashMap<Long, Int>()
        suspend fun t(turn: Turn) = cache.getOrPut(turn.id) { counter.count(turn.text) + 4 }
        suspend fun sumTokens() = sum?.let { counter.count(it.text) + 12 } ?: 0

        // Rolling summary: fold the oldest turns while the live turns would overflow the block (keeps the last 2).
        if (summarizer != null) {
            var guard = 0
            while (live.size > 2 && live.sumOf { t(it) } + sumTokens() > room0 && guard++ < 6) {
                val target = live.sumOf { t(it) } / 2
                val fold = ArrayList<Turn>()
                var acc = 0
                for (turn in live) {
                    if (fold.size >= live.size - 2 || (acc >= target && fold.isNotEmpty())) break
                    fold += turn; acc += t(turn)
                }
                if (fold.lastOrNull()?.role == Role.USER && fold.size < live.size - 2) fold += live[fold.size]
                if (fold.isEmpty()) break
                val text = runCatching { summarizer.summarize(sum?.text, fold, summaryWords).trim() }.getOrDefault("")
                if (text.isBlank()) break
                sum = ConversationSummary(text, fold.last().id)
                changed = true
                live = live.drop(fold.size)
            }
        }

        // Newest turns that fit; the latest exchange always stays (the previous answer is cut to fit if it must be).
        val room = room0 - sumTokens()
        val kept = ArrayList<Turn>()
        var used = 0
        for (turn in live.asReversed()) {
            val n = t(turn)
            if (used + n <= room) { kept += turn; used += n; continue }
            if (kept.size < 2) {
                val left = (room - used).coerceAtLeast(0)
                val chars = (left * 3.2).toInt() - 40
                if (chars > 80) {
                    val cut = turn.copy(text = turn.text.take(chars) + " …(cut)")
                    kept += cut; used += counter.count(cut.text) + 4
                    continue
                }
            }
            break
        }
        kept.reverse()
        val text = buildString {
            if (memText.isNotBlank()) append(memText).append("\n\n")
            if (sum != null || kept.isNotEmpty()) append("Conversation so far in this chat (oldest first):\n")
            sum?.let { append("Summary of earlier messages: ").append(it.text.trim()).append('\n') }
            kept.forEach { append(if (it.role == Role.USER) "User: " else "Assistant: ").append(it.text.trim()).append('\n') }
        }.trim()
        return Result(text, sum, changed, kept.size, live.size - kept.size, memTokens + used + sumTokens())
    }

    /**
     * A screen / automation goal carries a short pointer to the session so "do the same in Telegram" can be resolved,
     * without flooding the small step prompts.
     */
    fun goalWithContext(goal: String, session: String, maxChars: Int = 900): String =
        if (session.isBlank()) goal else "$goal\n\n(Earlier in this chat — use only to resolve references like \"it\" or \"the same\":\n${session.takeLast(maxChars)})"
}

/**
 * 0.6.2.1: reference resolver used ONLY for export / PDF follow-ups ("make it as a pdf", "export this"). Everything
 * else ("it", "that", "do the same for BERT", "in Telugu") works because every route sees the session.
 */
object FollowUp {
    enum class Kind {
        /** Not an export / PDF request that refers back. */
        NONE,
        /** "make it as a pdf", "export this": the PDF is built from the earlier answer's text, no model call. */
        EXPORT,
        /** "make a detailed pdf on it": regenerate (report writer), with the earlier Q&A as context. */
        REWRITE_EXPORT,
    }

    data class Target(val question: String, val answer: String, val answerId: Long)

    /** Start of the chat message that reports an exported PDF (skipped when resolving "it"). */
    const val EXPORT_NOTICE = "Here is the PDF of my answer"

    data class Plan(val kind: Kind, val target: Target?) {
        val title: String get() = target?.let { titleFrom(it.question) } ?: "Document"
    }

    private val EXPORT = Regex("""\b(pdf|export|download|print(able)?|save (it|this|that|them) as|as a (document|file))\b""", RegexOption.IGNORE_CASE)
    private val REFERS = Regex(
        """\b(it|this|that|these|those|them|above|previous|earlier|last one|answer|reply|response|explanation|same|the (whole |full )?(chat|conversation|response|reply|explanation))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CHANGES = Regex(
        """\b(detailed|in detail|in-depth|longer|expand|elaborate|more|add|adding|include|including|with (examples|diagrams|images|references)|rewrite|improve|extend|full report|report on|deeper|comprehensive)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val QUESTION = Regex("""^\s*(what|how|why|when|where|which|who|is|are|does|do)\b""", RegexOption.IGNORE_CASE)
    private val VERB = Regex("""\b(make|export|convert|save|turn|create|generate|download|print|give|put)\b""", RegexOption.IGNORE_CASE)
    private val STOP = setOf("make", "it", "as", "a", "an", "the", "pdf", "this", "that", "into", "to", "export", "convert", "please", "save",
        "of", "on", "answer", "one", "and", "me", "can", "you", "for", "file", "document", "download", "is", "are", "what", "about", "in")

    fun classify(message: String): Kind {
        val m = message.trim()
        if (m.isEmpty() || m.length > 160 || !EXPORT.containsMatchIn(m)) return Kind.NONE
        val words = m.split(Regex("\\s+")).size
        // A question ABOUT pdfs ("what is a pdf and how does it work") is not an export request.
        if (QUESTION.containsMatchIn(m) && !VERB.containsMatchIn(m)) return Kind.NONE
        // "pdf" alone / "as pdf" / "make it as a pdf" / "export this"; a new topic ("pdf on photosynthesis") has no reference.
        val refers = REFERS.containsMatchIn(m) || words <= 3
        if (!refers) return Kind.NONE
        return if (CHANGES.containsMatchIn(m)) Kind.REWRITE_EXPORT else Kind.EXPORT
    }

    /** The answer the message refers to: a named earlier one ("the transformers answer", "the first one"), else the last. */
    fun resolve(message: String, turns: List<Turn>): Target? {
        val pairs = ArrayList<Target>()
        var q: Turn? = null
        for (t in turns) {
            if (t.role == Role.USER) q = t
            else if (t.text.isNotBlank() && !t.text.startsWith("(automation)") && !t.text.startsWith("(I showed a canvas") && !t.text.startsWith(EXPORT_NOTICE))
                pairs += Target(q?.text.orEmpty(), t.text, t.id)
        }
        if (pairs.isEmpty()) return null
        val low = message.lowercase()
        if (Regex("\\bfirst (one|answer|reply)\\b").containsMatchIn(low)) return pairs.first()
        val words = low.split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in STOP }.toSet()
        if (words.isNotEmpty()) {
            val scored = pairs.mapIndexed { i, p -> Triple(i, p, (p.question.lowercase().split(Regex("[^a-z0-9]+")).toSet() intersect words).size) }
            val best = scored.filter { it.third > 0 }.maxWithOrNull(compareBy<Triple<Int, Target, Int>> { it.third }.thenBy { it.first })
            if (best != null) return best.second
        }
        return pairs.last()
    }

    fun plan(message: String, turns: List<Turn>): Plan {
        val k = classify(message)
        if (k == Kind.NONE) return Plan(Kind.NONE, null)
        val target = resolve(message, turns) ?: return Plan(Kind.NONE, null)
        return Plan(k, target)
    }

    /** "what are transformers" → "Transformers"; "explain attention in LLMs?" → "Attention in LLMs". */
    fun titleFrom(question: String): String {
        val q = question.trim().lineSequence().firstOrNull().orEmpty().trim().trimEnd('?', '.', '!', ' ')
        if (q.isBlank()) return "Document"
        val stripped = q.replace(Regex(
            """^(please\s+|hey\s+|hi\s+)?((can|could|would) you\s+)?(please\s+)?((tell|teach|explain to) me\s+(about\s+|what\s+(is|are)\s+)?|what\s+(is|are|was|were)\s+|who\s+(is|are|was|were)\s+|explain\s+(about\s+)?|describe\s+|define\s+|give me (an? )?(overview|summary|explanation) of\s+)""",
            RegexOption.IGNORE_CASE), "").replace(Regex("^(an?|the)\\s+", RegexOption.IGNORE_CASE), "").trim()
        val t = stripped.ifBlank { q }.take(100)
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    /** Markdown body for the PDF: the answer text as written, without a leading "# Title" that repeats the title page. */
    fun pdfBody(answer: String, title: String): String {
        val a = answer.substringBefore("\n\n(task notes:").substringBefore("\n\n---\n**PDF saved:**").trim()
        val first = a.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (first.startsWith("# ") && first.removePrefix("# ").trim().equals(title.trim(), ignoreCase = true)) a.substringAfter('\n').trim() else a
    }
}

/**
 * 0.6.2.1: "make it as a pdf" → a PDF of the referenced answer's text exactly as it was written (no model call, nothing
 * regenerated), titled from the question it answered.
 */
class AnswerPdfExporter(
    private val pdf: com.offlineagent.core.runtime.pdf.PdfPort,
    private val images: (String) -> com.offlineagent.core.runtime.pdf.PdfImage? = { null },
    private val fileName: (String) -> String = { com.offlineagent.core.runtime.pdf.CreatePdfTool.safeFileName(it) },
) {
    data class Exported(val title: String, val output: com.offlineagent.core.runtime.pdf.PdfOutput, val doc: com.offlineagent.core.runtime.pdf.PdfDocModel) {
        fun chatMessage(): String = "${FollowUp.EXPORT_NOTICE} (\"$title\").\n\n**PDF saved:** Downloads/OfflineAgent/${output.fileName} (${output.pages} page${if (output.pages == 1) "" else "s"})"
    }

    fun document(plan: FollowUp.Plan): com.offlineagent.core.runtime.pdf.PdfDocModel? {
        val t = plan.target ?: return null
        val title = plan.title
        val body = FollowUp.pdfBody(t.answer, title)
        if (body.isBlank()) return null
        val blocks = com.offlineagent.core.runtime.pdf.PdfContent.fromMarkdown(body, images)
        // A short answer gets no title page / contents: one or two pages read like the chat answer.
        val long = body.length > 2500
        return com.offlineagent.core.runtime.pdf.PdfDocModel(title, blocks, titlePage = long, tableOfContents = long)
    }

    suspend fun export(plan: FollowUp.Plan): Exported? {
        if (plan.kind != FollowUp.Kind.EXPORT) return null
        val doc = document(plan) ?: return null
        return Exported(doc.title, pdf.createPdf(doc, fileName(doc.title)), doc)
    }
}
