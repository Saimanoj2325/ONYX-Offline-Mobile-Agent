package com.offlineagent.core.runtime.chat

import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.StopReason
import com.offlineagent.core.inference.ThinkSplitter

/**
 * Phase 4b streaming reducer: turns the engine's [GenerationEvent]s into what the chat bubble shows, token by token.
 *
 *  - before the first token: a status line ("Loading model…", "Reading prompt… 340/812");
 *  - while the model thinks: the reasoning streams live and the Thought section is open ([thinkingOpen]);
 *  - as soon as the answer starts: the Thought section collapses and the answer streams as Markdown/OpenUI;
 *  - the footer shows tokens, tok/s and time to first token.
 *
 * Pure Kotlin (JVM-tested). One instance per answer; not thread-safe (feed it from one coroutine).
 */
class StreamingTurn(
    private val startedAtMs: Long,
    initialStatus: String = "Loading model…",
) {
    enum class Phase { PREPARING, READING_PROMPT, THINKING, ANSWERING, DONE }

    data class View(
        val phase: Phase,
        /** Status line shown while nothing visible has arrived (never blank while preparing). */
        val status: String,
        val reasoning: String,
        val answer: String,
        /** Thought section: expanded while the model is thinking, collapsed once the answer starts. */
        val thinkingOpen: Boolean,
        val promptDone: Int = 0,
        val promptTotal: Int = 0,
        val promptReused: Int = 0,
        val promptTokens: Int = 0,
        val promptMillis: Long = 0,
        val tokens: Int = 0,
        val tokensPerSecond: Double = 0.0,
        /** Time from the request to the first visible token (ms); 0 = not yet. */
        val firstTokenMillis: Long = 0,
        val stopReason: StopReason? = null,
    ) {
        /** Something visible to render in the bubble body (reasoning or answer). */
        val hasVisibleText: Boolean get() = reasoning.isNotBlank() || answer.isNotBlank()
        /** 0..1 prompt-reading progress, or null when unknown. */
        val promptFraction: Float? get() = if (promptTotal > 0) (promptDone.toFloat() / promptTotal).coerceIn(0f, 1f) else null
    }

    private val splitter = ThinkSplitter()
    private var view = View(Phase.PREPARING, initialStatus, "", "", thinkingOpen = false)
    private var lastEmitMs = Long.MIN_VALUE / 2
    private var lastEmittedPhase: Phase? = null
    private var lastEmittedStatus: String? = null

    val current: View get() = view

    /** Status line before tokens arrive (model loading, document search, summarising…). */
    fun status(text: String): View {
        if (view.phase == Phase.PREPARING || view.phase == Phase.READING_PROMPT) view = view.copy(status = text)
        return view
    }

    fun on(event: GenerationEvent, nowMs: Long): View {
        view = when (event) {
            is GenerationEvent.PromptProgress -> if (view.phase == Phase.PREPARING || view.phase == Phase.READING_PROMPT) view.copy(
                phase = Phase.READING_PROMPT,
                status = readingStatus(event.done, event.total),
                promptDone = event.done, promptTotal = event.total, promptReused = event.reused,
            ) else view
            is GenerationEvent.PromptProcessed -> view.copy(
                phase = if (view.phase == Phase.PREPARING || view.phase == Phase.READING_PROMPT) Phase.READING_PROMPT else view.phase,
                status = "Writing…", promptTokens = event.promptTokens, promptMillis = event.promptMillis,
                promptDone = if (view.promptTotal > 0) view.promptTotal else view.promptDone,
            )
            is GenerationEvent.Token -> {
                val p = splitter.append(event.text)
                val first = if (view.firstTokenMillis == 0L && (p.reasoning.isNotBlank() || p.answer.isNotBlank())) (nowMs - startedAtMs).coerceAtLeast(1) else view.firstTokenMillis
                val phase = when {
                    p.thinkingInProgress -> Phase.THINKING
                    p.answer.isNotEmpty() -> Phase.ANSWERING
                    view.phase == Phase.THINKING -> Phase.ANSWERING // think block closed, answer not started yet
                    else -> view.phase
                }
                view.copy(
                    phase = phase, reasoning = p.reasoning, answer = p.answer,
                    thinkingOpen = phase == Phase.THINKING,
                    status = if (phase == Phase.THINKING) "Thinking…" else view.status,
                    tokens = event.generatedTokens, tokensPerSecond = event.tokensPerSecond, firstTokenMillis = first,
                )
            }
            is GenerationEvent.Completed -> finish(event.stats)
        }
        return view
    }

    private fun finish(st: GenerationStats): View {
        val p = splitter.current()
        return view.copy(
            phase = Phase.DONE, reasoning = p.reasoning, answer = p.answer.trim(), thinkingOpen = false,
            tokens = st.generatedTokens, tokensPerSecond = st.tokensPerSecond,
            promptTokens = st.promptTokens.takeIf { it > 0 } ?: view.promptTokens,
            promptMillis = st.promptMillis.takeIf { it > 0 } ?: view.promptMillis,
            promptReused = st.reusedTokens.takeIf { it > 0 } ?: view.promptReused,
            firstTokenMillis = view.firstTokenMillis.takeIf { it > 0 } ?: st.firstTokenMillis,
            stopReason = st.stopReason,
        )
    }

    /**
     * Throttle for UI updates: true for the first visible token, for every phase/status change and at most every
     * [intervalMs] otherwise (≤ 20 recompositions per second; a phone decoding 3–4 tok/s shows every token).
     */
    fun shouldEmit(nowMs: Long, intervalMs: Long = 50): Boolean {
        val v = view
        val changed = v.phase != lastEmittedPhase || v.status != lastEmittedStatus
        if (changed || nowMs - lastEmitMs >= intervalMs) {
            lastEmitMs = nowMs; lastEmittedPhase = v.phase; lastEmittedStatus = v.status
            return true
        }
        return false
    }

    companion object {
        fun readingStatus(done: Int, total: Int) = if (total > 0) "Reading prompt… $done/$total" else "Reading prompt…"

        /** Footer: "212 tok · 3.6 tok/s · first token 4.2 s". */
        fun footer(v: View): String = buildString {
            if (v.tokens > 0) append(v.tokens).append(" tok · ").append("%.1f".format(java.util.Locale.US, v.tokensPerSecond)).append(" tok/s")
            if (v.firstTokenMillis > 0) {
                if (isNotEmpty()) append(" · ")
                append("first token ").append(seconds(v.firstTokenMillis))
            }
            if (v.stopReason == StopReason.CANCELLED) append(" · stopped")
        }

        /** Extra detail for the saved message footer: prompt size and how much of it was reused. */
        fun promptDetail(v: View): String = if (v.promptTokens <= 0) "" else buildString {
            append("prompt ").append(v.promptTokens).append(" tok")
            if (v.promptReused > 0) append(" (").append(v.promptReused).append(" cached)")
        }

        fun seconds(ms: Long): String = if (ms < 10_000) "%.1f s".format(java.util.Locale.US, ms / 1000.0) else "${ms / 1000} s"
    }
}

/** Phase 4b: chat system prompt, sized for phone prefill (see [com.offlineagent.core.openui.OpenUiPrompt.cheatSheet]). */
object ChatPrompts {
    enum class RichUi { OFF, CHEAT_SHEET, COMPACT, FULL }

    const val BASE =
        "You are a helpful assistant running fully offline on the user's Android phone. Answer clearly and concisely. " +
            "Use Markdown: headings, bullet lists, tables, **bold** and fenced code blocks where they help. " +
            "If the user writes \"Form submitted: …\", those are the values they entered in your form; continue with them."

    private val UI_WORDS = Regex(
        "\\b(chart|charts|graph|graphs|plot|pie|dashboard|form|forms|widget|ui|interface|quiz|survey|visuali[sz]e|visuali[sz]ation|interactive|tabs|buttons|card|cards|openui)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The user explicitly asks for UI / a visual answer. */
    fun wantsUi(text: String): Boolean = UI_WORDS.containsMatchIn(text)

    /**
     * Settings → Rich answers: OFF | COMPACT | FULL | AUTO. AUTO (default) = the short cheat sheet; the full library
     * only when the user asks for UI in this message.
     */
    fun level(setting: String, userText: String): RichUi = when (setting) {
        "OFF" -> RichUi.OFF
        "FULL" -> RichUi.FULL
        "COMPACT" -> RichUi.COMPACT
        else -> if (wantsUi(userText)) RichUi.FULL else RichUi.CHEAT_SHEET
    }

    fun system(level: RichUi, extra: String? = null): String = buildString {
        append(BASE)
        val lib = com.offlineagent.core.openui.OpenUiLibrary.DEFAULT
        when (level) {
            RichUi.OFF -> Unit
            RichUi.CHEAT_SHEET -> append("\n\n").append(com.offlineagent.core.openui.OpenUiPrompt.cheatSheet())
            RichUi.COMPACT -> append("\n\n").append(com.offlineagent.core.openui.OpenUiPrompt.generate(lib.compact()))
            RichUi.FULL -> append("\n\n").append(com.offlineagent.core.openui.OpenUiPrompt.generate(lib))
        }
        if (!extra.isNullOrBlank()) append("\n\n").append(extra)
    }
}
