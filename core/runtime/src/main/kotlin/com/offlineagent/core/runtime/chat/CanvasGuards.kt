package com.offlineagent.core.runtime.chat

/**
 * 0.6.2.2: stops a generation that is going round in circles. Small models (Qwen3 1.7B on Sai's phone) can loop on
 * the same few lines until the token cap; at 5 tok/s that is minutes of a flickering canvas or a wall of repeated text.
 *
 * Feed every streamed piece to [feed]; when it returns a reason, cancel the generation and keep what came before.
 *  - [Mode.LINES] (canvas / openui-lang): a complete statement line seen before, or the same name assigned
 *    [maxAssignments] times, is a loop — a real program never repeats a whole line;
 *  - [Mode.TEXT] (chat answers): the tail of the text is one block of 12..400 characters repeated [minRepeats]
 *    times back to back (an n-gram / sentence loop). Tables and lists are not affected: their rows differ.
 * Pure Kotlin, one instance per generation.
 */
class LoopGuard(
    private val mode: Mode,
    private val maxAssignments: Int = 3,
    private val minRepeats: Int = 3,
) {
    enum class Mode { LINES, TEXT }

    private val buf = StringBuilder()
    private var lineStart = 0
    private val seenLines = HashSet<String>()
    private val assignments = HashMap<String, Int>()
    private var lastCheck = 0
    var reason: String? = null
        private set

    val stopped: Boolean get() = reason != null

    /** Adds streamed text; returns the stop reason the first time a loop is detected, else null. */
    fun feed(piece: String): String? {
        if (reason != null || piece.isEmpty()) return null
        buf.append(piece)
        reason = when (mode) {
            Mode.LINES -> checkLines()
            Mode.TEXT -> if (buf.length - lastCheck >= 16) { lastCheck = buf.length; checkTail() } else null
        }
        return reason
    }

    private fun checkLines(): String? {
        while (true) {
            val nl = buf.indexOf("\n", lineStart)
            if (nl < 0) return null
            val line = buf.substring(lineStart, nl).trim().replace(WS, " ")
            lineStart = nl + 1
            if (line.length < 8) continue
            if (!seenLines.add(line)) return "repeated line: ${line.take(60)}"
            ASSIGN.find(line)?.groupValues?.get(1)?.let { name ->
                val n = (assignments[name] ?: 0) + 1
                assignments[name] = n
                if (n >= maxAssignments) return "`$name` assigned $n times"
            }
        }
    }

    private fun checkTail(): String? {
        val n = buf.length
        val maxP = minOf(400, n / minRepeats)
        for (p in 12..maxP) {
            var ok = true
            val start = n - p * minRepeats
            if (start < 0) break
            // Compare each of the last (minRepeats - 1) blocks with the final block.
            loop@ for (k in 1 until minRepeats) {
                val a = n - p * (k + 1)
                for (i in 0 until p) if (buf[a + i] != buf[n - p + i]) { ok = false; break@loop }
            }
            if (ok) {
                val unit = buf.substring(n - p, n)
                if (unit.count { it.isLetterOrDigit() } >= 6) return "repeating text: ${unit.trim().take(60)}"
            }
        }
        return null
    }

    private companion object {
        val WS = Regex("\\s+")
        val ASSIGN = Regex("^\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)")
    }
}

/**
 * 0.6.2.2: which Canvas-mode messages are really chat. Sai asked "can you give me 2-3 lines of summary of our previous
 * chats?" with Canvas on; the canvas designer turned it into a looping placeholder UI. A question or chat request with
 * no UI / design intent is answered as normal chat (with an "Answered in chat" note); tables, charts, plans, forms,
 * dashboards and edits of the current canvas stay in Canvas. Builds on [ChatPrompts.wantsUi].
 */
object CanvasRouting {
    /** Things a canvas is for (beyond [ChatPrompts.wantsUi]'s chart/form/dashboard words). */
    private val UI_NOUNS = Regex(
        "\\b(table|tables|spreadsheet|chart|charts|graph|graphs|plot|pie|dashboard|form|forms|plan|planner|itinerary|schedule|timetable|" +
            "tracker|checklist|check list|kanban|board|layout|ui|widget|card|cards|quiz|survey|budget|comparison|compare|matrix|grid|" +
            "calendar|roadmap|timeline|steps|step-by-step|template|invoice|menu|poll|canvas|interface|interactive|tabs|buttons|openui|visuali[sz]ation)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CREATE = Regex(
        "\\b(make|create|build|design|draw|generate|render|show|put|turn|convert|lay out|organi[sz]e|visuali[sz]e|tabulate|chart|plot|format|prepare|draft)\\b",
        RegexOption.IGNORE_CASE,
    )
    /** Edits of a canvas that already exists ("add a row", "make it blue", "remove the second column"). */
    private val EDIT = Regex(
        "^\\s*(add|remove|delete|change|rename|replace|move|sort|swap|make it|make the|update|insert|drop|hide|show|use|set|increase|decrease|undo)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val QUESTION_START = Regex(
        "^\\s*(what|what's|whats|why|how|who|whom|whose|when|where|which|is|are|am|was|were|do|does|did|can|could|would|will|should|shall|" +
            "may|might|have|has|had|tell|explain|describe|define|summari[sz]e|summary|translate|recap|remind me|give me|i want to know|" +
            "hi|hello|hey|thanks|thank you|ok|okay)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CHAT_PHRASES = Regex(
        "\\b(summar(y|ies|i[sz]e)|explain|meaning of|what is|what are|who is|tell me about|in \\d+(-\\d+)? (lines|sentences|words)|" +
            "previous chats?|past chats?|earlier chats?|our (chats?|conversations?)|we (talked|discussed|spoke))\\b",
        RegexOption.IGNORE_CASE,
    )

    /** True when the message asks for a canvas: a create verb before a UI noun, or a bare noun phrase ("a table of …"). */
    fun wantsCanvas(text: String): Boolean {
        val t = text.trim()
        val noun = UI_NOUNS.find(t) ?: return ChatPrompts.wantsUi(t) && !QUESTION_START.containsMatchIn(t) && !t.endsWith("?")
        val verb = CREATE.find(t)
        if (verb != null && verb.range.first < noun.range.first) return true
        // "a weekly study plan for exams", "table of planets" (noun phrase, no question)
        return !QUESTION_START.containsMatchIn(t) && !t.endsWith("?") && !CHAT_PHRASES.containsMatchIn(t)
    }

    /**
     * True when a Canvas-mode message should be answered as chat. [hasCanvas] = the chat already shows a canvas,
     * so short edit commands ("add a row for Friday") stay in Canvas.
     */
    fun answerInChat(text: String, hasCanvas: Boolean = false): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (hasCanvas && EDIT.containsMatchIn(t)) return false
        if (wantsCanvas(t)) return false
        if (t.endsWith("?") || QUESTION_START.containsMatchIn(t) || CHAT_PHRASES.containsMatchIn(t)) return true
        return false // anything else in Canvas mode ("weekly gym routine for beginners") is still designed as a canvas
    }

    const val NOTE = "Answered in chat · Canvas is for tables, charts, plans and forms"
}

/**
 * 0.6.2.2: device-aware prompt budget. What is NEW in a prompt (not covered by the cached static system prefix) is
 * prefilled at the phone's prompt speed before the first token; Sai's canvas turn sent the 2 000-token canvas library
 * plus a 700-token session block plus memory to a 1.7B model on CPU. The budget keeps the dynamic part to about
 * [targetSeconds] of prefill: measured prompt speed when there is one ([PrefillMeter]), else a conservative per-tier
 * guess. History is trimmed first, then memory; the request itself is never cut.
 */
object PromptBudget {
    enum class Tier { ENTRY, MID, HIGH, FLAGSHIP }

    /** Conservative prompt tok/s for a ~1.7B Q4 model on CPU when nothing was measured yet. */
    fun assumedPrefillTps(tier: Tier): Double = when (tier) {
        Tier.ENTRY -> 12.0
        Tier.MID -> 20.0
        Tier.HIGH -> 30.0
        Tier.FLAGSHIP -> 45.0
    }

    /** Tokens of dynamic prompt (history + memory + request) that fit [targetSeconds] of prefill. */
    fun dynamicTokens(tier: Tier, measuredTps: Double? = null, targetSeconds: Double = 20.0): Int {
        val tps = measuredTps?.takeIf { it in 1.0..5000.0 } ?: assumedPrefillTps(tier)
        return (tps * targetSeconds).toInt().coerceIn(240, 2400)
    }

    data class Split(val historyTokens: Int, val memoryTokens: Int)

    /**
     * Splits [dynamicBudget] after the request ([requestTokens]): memory keeps up to [memoryWant] tokens but at most
     * 40 % (memory is trimmed after history, so history gives way first), history gets the rest.
     */
    fun split(dynamicBudget: Int, requestTokens: Int, memoryWant: Int, historyWant: Int): Split {
        val room = (dynamicBudget - requestTokens).coerceAtLeast(0)
        var mem = minOf(memoryWant, (room * 0.4).toInt())
        var hist = minOf(historyWant, room - mem)
        // History does not need its share: give it to memory.
        if (hist < room - mem) mem = minOf(memoryWant, room - hist)
        return Split(hist.coerceAtLeast(0), mem.coerceAtLeast(0))
    }

    /** Trims [text] to about [maxTokens] keeping the END (newest lines). */
    fun keepNewest(text: String, maxTokens: Int, count: (String) -> Int = { (it.length + 3) / 4 }): String {
        if (maxTokens <= 0 || text.isBlank()) return ""
        if (count(text) <= maxTokens) return text
        val lines = text.lines()
        val out = ArrayDeque<String>()
        var used = 0
        for (l in lines.asReversed()) {
            val t = count(l) + 1
            if (used + t > maxTokens) break
            out.addFirst(l); used += t
        }
        return out.joinToString("\n")
    }

    /** Trims [text] to about [maxTokens] keeping the START (memory blocks are ranked best-first). */
    fun keepFirst(text: String, maxTokens: Int, count: (String) -> Int = { (it.length + 3) / 4 }): String {
        if (maxTokens <= 0 || text.isBlank()) return ""
        if (count(text) <= maxTokens) return text
        val out = ArrayList<String>()
        var used = 0
        for (l in text.lines()) {
            val t = count(l) + 1
            if (used + t > maxTokens) break
            out += l; used += t
        }
        return out.joinToString("\n")
    }

    /**
     * Prompt order that keeps the prefix cache hitting: the static system prompt is ALWAYS the first message, and
     * everything that changes per turn (memory, earlier chats, session, documents) goes after it, in the user turn.
     */
    fun staticFirst(system: String, dynamicParts: List<String>, request: String): Pair<String, String> =
        system to (dynamicParts.filter { it.isNotBlank() }.joinToString("\n\n") { it.trim() }.let { if (it.isEmpty()) request else "$it\n\nLatest request:\n$request" })
}

/** 0.6.2.2: running estimate of this phone's prompt speed (uncached tokens per second), from real generations. */
class PrefillMeter {
    @Volatile var tokensPerSecond: Double? = null
        private set

    /** Records one prefill: [newTokens] evaluated (prompt minus reused prefix) in [millis]. Tiny prompts are ignored. */
    fun record(newTokens: Int, millis: Long) {
        if (newTokens < 64 || millis <= 0) return
        val tps = newTokens * 1000.0 / millis
        tokensPerSecond = tokensPerSecond?.let { it * 0.6 + tps * 0.4 } ?: tps
    }
}
