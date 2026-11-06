package com.offlineagent.core.inference

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/*
 * 0.6.2.3 typing-time prefill ("reading ahead") and optional "reasoning ahead".
 *
 * Idea adapted from llama.cpp discussion #31250 and MAFDET by TheLastOxygen (Apache-2.0,
 * https://github.com/TheLastOxygen/mafdet): keep the KV cache in step with the draft while the user types, so only the
 * changed tail is computed, and (for thinking models) let the model start its reasoning before Enter. No code was
 * copied; the wording of the frame below is ONYX's own. See NOTICE.
 */

/** Longest-common-prefix arithmetic for a KV cache that follows a changing prompt. */
object PrefixMath {
    /** Number of leading tokens [a] and [b] share. */
    fun commonPrefix(a: IntArray, b: IntArray): Int {
        val n = minOf(a.size, b.size)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /**
     * What it costs to move a cache holding [cached] to [next]: keep [keep] tokens, drop [rollback] cached tokens after
     * them, compute [evaluate] new ones. With [needLogits] (a real request, which must sample) at least one token is
     * computed even when [next] is fully cached, as llama.cpp needs fresh logits.
     */
    data class Plan(val keep: Int, val rollback: Int, val evaluate: Int)

    fun plan(cached: IntArray, next: IntArray, needLogits: Boolean = false): Plan {
        var keep = commonPrefix(cached, next)
        if (needLogits && keep == next.size && keep > 0) keep--
        return Plan(keep, cached.size - keep, next.size - keep)
    }
}

/**
 * Reasoning-ahead formatting (thinking models only, Qwen3-style `<think>` blocks). While the user types, the assistant
 * turn is opened with a frame that asks for material, not guesses; each changed draft adds a `New words: "…"` line; on
 * Enter one sentence says the message is complete and the model finishes its reasoning and answers.
 */
object ReasoningAhead {
    const val OPEN = "<think>"
    const val CLOSE = "</think>"

    /** ONYX's frame (own wording): one line of material per key term, name or number already written. */
    const val FRAME = "While the message is still being written, I collect material for the answer: one short line per key " +
        "term, name or number already written — what it is, the facts I know about it, or a partial result. " +
        "I do not guess the question and I do not comment on the text itself.\n" +
        "- \"Ohm's law\": V = I × R; 2 A through 5 Ω gives 10 V.\n"

    const val COMPLETE = "The message is now complete. I finish my reasoning from these notes and answer it.\n"

    /** Meta words that pull the notes away from the content; a small negative bias, only while reasoning ahead. */
    val BIAS_WORDS = listOf(" user", "user", " User", " typing", " typed", " unfinished", " incomplete", " Unfinished")
    const val BIAS = -2.5f

    fun newWordsLine(words: String): String = "New words: \"${words.trim().replace('"', '\'')}\"\n"

    /**
     * Words of [now] that were not in [before]: the changed middle between their common prefix and common suffix, widened
     * to whole words. Empty when nothing new was written (e.g. only a deletion).
     */
    fun newWords(before: String, now: String): String {
        if (before == now) return ""
        var p = 0
        val n = minOf(before.length, now.length)
        while (p < n && before[p] == now[p]) p++
        var q = 0
        while (q < n - p && before[before.length - 1 - q] == now[now.length - 1 - q]) q++
        var start = p
        // widen to whole words only when the change starts / ends inside a word ("9" -> "90" names "90")
        if (start < now.length && !now[start].isWhitespace()) while (start > 0 && !now[start - 1].isWhitespace()) start--
        var end = now.length - q
        while (end < now.length && !now[end].isWhitespace()) end++
        return if (end > start) now.substring(start, end).trim() else ""
    }

    /** True when [assistantText] (prefix + generated) opened a think block and never closed it: the turn ended inside it. */
    fun needsClose(assistantText: String): Boolean {
        val o = assistantText.lastIndexOf(OPEN)
        return o >= 0 && assistantText.indexOf(CLOSE, o) < 0
    }

    /** The close the Qwen3 chat template writes ("…\n</think>\n\n"). */
    fun closeFor(assistantText: String): String = (if (assistantText.endsWith("\n")) "" else "\n") + CLOSE + "\n\n"

    /** The notes as the user sees them in the reasoning panel (frame left out). */
    fun shown(notes: String): String = "$OPEN\n$notes"
}

/**
 * The notes written ahead for one draft session. [maxTokens] caps what the phone spends on ahead reasoning (battery).
 * The user message in the prompt is always the CURRENT draft; the notes follow it (a change only costs re-reading the
 * notes, never re-writing them).
 */
class ReasoningAheadSession(val maxTokens: Int = 256) {
    private val notes = StringBuilder()
    private var lastDraft: String? = null
    var tokens = 0; private set
    var closed = false; private set
    val budgetLeft: Int get() = (maxTokens - tokens).coerceAtLeast(0)
    val text: String get() = notes.toString()

    /** Opening of the assistant turn for [draft]: frame + notes so far + a `New words` line when the draft changed. */
    fun prefixFor(draft: String): String = buildString {
        append(ReasoningAhead.OPEN).append('\n').append(ReasoningAhead.FRAME).append(pendingNotes(draft))
    }

    private fun pendingNotes(draft: String): String {
        val prev = lastDraft ?: return notes.toString()
        val words = ReasoningAhead.newWords(prev, draft)
        if (words.isEmpty()) return notes.toString()
        val base = notes.toString()
        return base + (if (base.isEmpty() || base.endsWith("\n")) "" else "\n") + ReasoningAhead.newWordsLine(words)
    }

    /** Records a chunk generated after [prefixFor] of [draft]. */
    fun accept(draft: String, generated: AheadResult) {
        val n = pendingNotes(draft)
        notes.setLength(0)
        notes.append(n).append(generated.text)
        lastDraft = draft
        tokens += generated.tokens
        if (generated.stop == StopReason.END_OF_TURN) closed = true
    }

    /** The first chunk is anchored on [draft] without a `New words` line. */
    fun start(draft: String) { if (lastDraft == null) lastDraft = draft }

    /** Assistant prefix sent with the real request on Enter. */
    fun finalPrefix(final: String): String {
        val p = prefixFor(final)
        return p + (if (p.endsWith("\n")) "" else "\n") + ReasoningAhead.COMPLETE
    }

    fun shownFor(final: String): String {
        val n = pendingNotes(final)
        return ReasoningAhead.shown(n + (if (n.isEmpty() || n.endsWith("\n")) "" else "\n"))
    }

    val hasNotes: Boolean get() = tokens > 0
}

/**
 * The engine side the controller drives. The app wraps the adapter so that every call runs at the lowest priority of
 * the inference queue and is preempted by any real request.
 */
interface AheadEngine {
    suspend fun prefill(request: GenerationRequest): PrefillResult?
    suspend fun reasonAhead(request: GenerationRequest, maxTokens: Int, biasWords: List<String>, bias: Float): AheadResult?
}

/**
 * 0.6.2.3: follows the chat composer. After the user pauses ([Config.debounceMs], or at least every [Config.maxWaitMs]
 * of continuous typing) it builds the exact request Send would build ([build]) and prefills it; with reasoning ahead on
 * and a thinking request it then writes notes in chunks until [Config.reasonCapTokens]. Pure coroutine logic: the
 * clock, the gate (power governor, screen, background, settings) and the engine are injected, so it runs on virtual
 * time in tests.
 */
class TypeAheadController(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val engine: AheadEngine,
    /** The request Send would build for this draft, or null when the route is not prefillable (agents, canvas, tools…). */
    private val build: suspend (String) -> GenerationRequest?,
    /** Non-null = do not read ahead now (the reason, for the debug line). */
    private val gate: () -> String?,
    /** Makes sure a model is loaded (warm-load when RAM allows); false = skip. */
    private val ensureModel: suspend () -> Boolean = { true },
    private val config: () -> Config = { Config() },
) {
    data class Config(
        val enabled: Boolean = true,
        val debounceMs: Long = 500,
        val maxWaitMs: Long = 1_500,
        val idleStopMs: Long = 60_000,
        val reasonAhead: Boolean = false,
        val reasonChunkTokens: Int = 32,
        val reasonCapTokens: Int = 256,
        val minChars: Int = 2,
    )

    data class Status(
        val state: String = "idle",
        /** Tokens computed while typing in this draft session. */
        val evaluatedTokens: Int = 0,
        /** Prompt tokens that the last prefill left in the cache. */
        val cachedTokens: Int = 0,
        val aheadTokens: Int = 0,
        val prefills: Int = 0,
        val skipped: String? = null,
    )

    /** What Send gets: how much was read while typing, and the notes (reasoning ahead) to continue from. */
    data class Handoff(
        /** Prompt tokens already in the KV cache when typing started (from the earlier turns): not credited to typing. */
        val baselineTokens: Int,
        /** Prompt tokens in the KV cache after the last prefill of this session. */
        val cachedTokens: Int,
        val evaluatedWhileTyping: Int,
        val reasoning: ReasoningAheadSession?,
        val lastPrompt: String?,
    ) {
        /** "prefilled while typing: N tok": of the [reusedAtSend] tokens Send found cached, those typing computed. */
        fun prefilledWhileTyping(reusedAtSend: Int): Int = (minOf(reusedAtSend, cachedTokens) - baselineTokens).coerceAtLeast(0)
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    @Volatile private var draft: String = ""
    @Volatile private var lastChange = 0L
    @Volatile private var firstPending = 0L
    private var timer: Job? = null
    private var worker: Job? = null
    private var doneFor: String? = null
    private var baseline = -1
    private var cached = 0
    private var evaluated = 0
    private var lastPrompt: String? = null
    private var reasoning: ReasoningAheadSession? = null
    private var paused = false

    /** Every composer change. */
    @Synchronized fun onDraft(text: String) {
        val now = clock()
        if (text == draft && timer?.isActive == true) return
        draft = text
        lastChange = now
        if (timer?.isActive != true) firstPending = now
        if (text.isBlank()) { reset(); return }
        if (timer?.isActive == true) return
        timer = scope.launch { tick() }
    }

    private suspend fun tick() {
        while (true) {
            val c = config()
            val now = clock()
            val due = minOf(lastChange + c.debounceMs, firstPending + c.maxWaitMs)
            if (now < due) { delay(due - now); continue }
            break
        }
        synchronized(this) { timer = null }
        runOnce()
    }

    private suspend fun runOnce() {
        val c = config()
        val text = draft
        if (!c.enabled || paused || text.trim().length < c.minChars || text == doneFor) return
        gate()?.let { why -> _status.value = _status.value.copy(state = "skipped", skipped = why); return }
        if (worker?.isActive == true) { reschedule(); return } // the running prefill finishes (its work stays cached); then again
        worker = scope.launch {
            try { work(text, c) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                _status.value = _status.value.copy(state = "skipped", skipped = "error: ${e.message?.take(60)}")
            }
        }
    }

    private suspend fun work(text: String, c: Config) {
        run {
            if (!ensureModel()) { _status.value = _status.value.copy(state = "skipped", skipped = "no model loaded"); return }
            val req = build(text) ?: run { _status.value = _status.value.copy(state = "skipped", skipped = "route not prefillable"); return }
            _status.value = _status.value.copy(state = "reading", skipped = null)
            val r = engine.prefill(req)
            if (r != null) {
                synchronized(this@TypeAheadController) {
                    if (baseline < 0) baseline = r.reusedTokens
                    evaluated += r.evaluatedTokens
                    cached = if (r.aborted) r.reusedTokens + r.evaluatedTokens else r.promptTokens
                    lastPrompt = r.prompt
                    if (!r.aborted) doneFor = text
                }
                _status.value = _status.value.copy(state = if (r.aborted) "paused" else "ready", evaluatedTokens = evaluated, cachedTokens = cached,
                    prefills = _status.value.prefills + 1)
            } else {
                _status.value = _status.value.copy(state = "skipped", skipped = "engine busy")
            }
            if (r != null && !r.aborted && c.reasonAhead && req.thinking) reasonLoop(text, req, c)
            if (draft != text) reschedule()
        }
    }

    private suspend fun reasonLoop(text: String, req: GenerationRequest, c: Config) {
        val session = synchronized(this) { reasoning ?: ReasoningAheadSession(c.reasonCapTokens).also { reasoning = it } }
        session.start(text)
        while (session.budgetLeft > 0 && !session.closed && draft == text && !paused && gate() == null &&
            clock() - lastChange < c.idleStopMs) {
            val prefix = session.prefixFor(text)
            _status.value = _status.value.copy(state = "reasoning")
            val r = engine.reasonAhead(req.copy(assistantPrefix = prefix), minOf(c.reasonChunkTokens, session.budgetLeft),
                ReasoningAhead.BIAS_WORDS, ReasoningAhead.BIAS) ?: break
            if (r.stop == StopReason.CANCELLED && r.tokens == 0) break
            session.accept(text, r)
            _status.value = _status.value.copy(aheadTokens = session.tokens)
            if (r.stop == StopReason.CANCELLED) break
        }
        _status.value = _status.value.copy(state = "ready")
    }

    private fun reschedule() {
        synchronized(this) {
            if (timer?.isActive == true) return
            firstPending = clock()
            timer = scope.launch { tick() }
        }
    }

    /** Screen off, app in background, a priority request: stop scheduling (running work is preempted by the engine). */
    @Synchronized fun pause() { paused = true; timer?.cancel(); timer = null }
    @Synchronized fun resume() { paused = false }

    /** Enter: stops all read-ahead and hands over what was done for [finalText]. */
    @Synchronized fun onSend(finalText: String): Handoff {
        timer?.cancel(); timer = null
        worker?.cancel()
        val h = Handoff(baseline.coerceAtLeast(0), cached, evaluated, reasoning?.takeIf { it.hasNotes }, lastPrompt)
        reset()
        draft = ""
        return h
    }

    @Synchronized private fun reset() {
        timer?.cancel(); timer = null
        doneFor = null
        baseline = -1; cached = 0; evaluated = 0; lastPrompt = null
        reasoning = null
        _status.value = Status()
    }
}
