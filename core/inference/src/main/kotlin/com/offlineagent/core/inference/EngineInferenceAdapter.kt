package com.offlineagent.core.inference

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [InferenceAdapter] state machine on top of a blocking [TokenEngine].
 *
 * Threading: every [TokenEngine] call runs on [engineDispatcher], which in the app is a
 * dedicated single thread ("llm-engine"). Only [TokenEngine.requestAbort] is called from
 * other threads. A [Mutex] serialises load / generate / unload.
 *
 * States: Unloaded -> Loading -> Ready <-> Generating; Loading -> Failed; any -> Unloaded.
 */
class EngineInferenceAdapter(
    private val engine: TokenEngine,
    private val engineDispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long = System::nanoTime,
    private val templateFor: ((ModelInfo) -> PromptTemplate)? = null,
    /** Where the system-prompt KV cache for (model, prefix) is stored; null = in-memory reuse only. */
    private val prefixCacheFile: ((ModelInfo, String) -> String?)? = null,
) : InferenceAdapter {

    private val _state = MutableStateFlow<EngineState>(EngineState.Unloaded())
    override val state: StateFlow<EngineState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var loaded: ModelInfo? = null

    /** Prompt-prefix KV cache (memory + disk). Turned off by the app in safe mode or from Settings. */
    @Volatile var prefixCacheEnabled: Boolean = true

    override suspend fun load(config: LoadConfig): ModelInfo {
        cancel()
        return mutex.withLock {
            withContext(engineDispatcher) {
                if (loaded != null) {
                    engine.unload()
                    loaded = null
                }
                _state.value = EngineState.Loading(config.modelPath)
                try {
                    val info = engine.load(config)
                    if (config.ngramDraft > 0) runCatching { engine.setNgram(config.ngramMin, config.ngramMax, config.ngramDraft) }
                    loaded = info
                    _state.value = EngineState.Ready(info)
                    info
                } catch (e: Throwable) {
                    val err = e as? ModelLoadException ?: ModelLoadException(e.message ?: e.toString(), e)
                    _state.value = EngineState.Failed(err.message ?: "Model failed to load", err)
                    throw err
                }
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        require(request.maxNewTokens > 0) { "maxNewTokens must be > 0" }
        // Busy check happens in the collector's context so a second request is rejected
        // immediately instead of silently queueing behind the engine thread.
        if (!mutex.tryLock()) {
            // 0.6.2.3: a read-ahead (typing-time prefill / reasoning ahead) never makes a real request wait or fail:
            // it is aborted (its finished batches stay cached) and the request takes the engine right after it.
            var preempted = false
            while (!mutex.tryLock()) {
                if (!mutex.holdsLock(READ_AHEAD_OWNER)) throw BusyException("The engine is busy (loading or already generating)")
                if (!preempted) { preempted = true; preemptions++ }
                // Repeated: the native abort flag is reset at the start of each engine call, so one request could be lost
                // in the gap between two calls of the read-ahead.
                engine.requestAbort()
                kotlinx.coroutines.delay(PREEMPT_POLL_MS)
            }
        }
        try {
            val info = loaded ?: throw NotLoadedException()
            cancelRequested.set(false)
            _state.value = EngineState.Generating(info)
            emitAll(generateLocked(info, request))
        } finally {
            cancelRequested.set(false)
            loaded?.let { if (_state.value is EngineState.Generating) _state.value = EngineState.Ready(it) }
            mutex.unlock()
        }
    }

    /** The actual token loop; runs entirely on [engineDispatcher]. Caller holds [mutex]. */
    private fun generateLocked(info: ModelInfo, request: GenerationRequest): Flow<GenerationEvent> = kotlinx.coroutines.flow.channelFlow {
        suspend fun emit(e: GenerationEvent) = send(e)
        val tRequest = nanoTime()
        var firstTokenMillis = 0L
        run {
            val template = templateFor?.invoke(info) ?: PromptTemplates.forModel(info, engine)
            val images = request.messages.lastOrNull { it.role == Role.USER }?.images.orEmpty()

            // Only the latest user turn keeps its images (older images are dropped from the prompt to save context).
            val lastUser = request.messages.indexOfLast { it.role == Role.USER }
            val original = request.messages.mapIndexed { i, m -> if (i != lastUser && m.images.isNotEmpty()) m.copy(images = emptyList()) else m }
            var messages = PromptFitter.fitByCharacters(original, info.contextLength, request.maxNewTokens)
            // Exact fit with the real tokenizer (cheap: tokenizing 16K tokens takes milliseconds).
            messages = PromptFitter.fitByTokens(messages, info.contextLength, request.maxNewTokens) { m ->
                engine.countTokens(template.render(m, request.thinking))
            }
            val t0 = nanoTime()
            var promptTokens: Int
            var aborted = false
            val prefix = template.systemPrefix(messages)
            val nKeep = prefix?.let { engine.countTokens(it) } ?: 0
            val prefixState = withPrefillProgress { usePrefixCache(info, request, prefix, nKeep, images.isEmpty()) }
            var prompt = ""
            while (true) {
                currentCoroutineContext().ensureActive()
                prompt = promptOf(template, messages, request)
                try {
                    promptTokens = withPrefillProgress { abortOnCancel { engine.beginCompletion(prompt, request.sampling, request.maxNewTokens, request.grammar, images, nKeep) } }
                    break
                } catch (e: PromptTooLongException) {
                    val shorter = PromptFitter.dropOldestTurn(messages) ?: throw e
                    messages = shorter
                } catch (e: GenerationAbortedException) {
                    currentCoroutineContext().ensureActive()
                    promptTokens = 0
                    aborted = true
                    break
                }
            }
            val dropped = original.size - messages.size
            val promptMillis = (nanoTime() - t0) / 1_000_000
            val reused = runCatching { engine.prefillProgress()?.getOrNull(2) ?: 0 }.getOrDefault(0)
            if (!aborted) emit(GenerationEvent.PromptProcessed(promptTokens, promptMillis))

            val decoder = Utf8StreamDecoder()
            var generated = 0
            val genStart = nanoTime()
            var reason: StopReason = if (aborted) StopReason.CANCELLED else StopReason.END_OF_TURN
            // 0.6.2.3 reasoning ahead: the think block written while the user typed is part of the prompt; show it first.
            if (!aborted && !request.assistantPrefixShown.isNullOrEmpty()) emit(GenerationEvent.Token(request.assistantPrefixShown, 0, 0))
            val produced = StringBuilder()
            var closedThink = false
            while (!aborted) {
                currentCoroutineContext().ensureActive()
                if (cancelRequested.get()) { reason = StopReason.CANCELLED; break }
                when (val step = engine.nextToken()) {
                    is TokenStep.Piece -> {
                        generated++
                        val text = decoder.feed(step.bytes)
                        if (text.isNotEmpty()) {
                            produced.append(text)
                            if (firstTokenMillis == 0L) firstTokenMillis = ((nanoTime() - tRequest) / 1_000_000).coerceAtLeast(1)
                            emit(GenerationEvent.Token(text, generated, (nanoTime() - genStart) / 1_000_000))
                        }
                    }
                    is TokenStep.End -> {
                        reason = if (cancelRequested.get()) StopReason.CANCELLED else step.reason
                        // The model ended its turn inside the think block it was continuing (no answer at all): close the
                        // block the way the chat template does and let it write the answer, once.
                        if (reason == StopReason.END_OF_TURN && request.closeThinkOnEarlyEnd && !closedThink &&
                            ReasoningAhead.needsClose(request.assistantPrefix.orEmpty() + produced)) {
                            closedThink = true
                            val close = ReasoningAhead.closeFor(request.assistantPrefix.orEmpty() + produced)
                            emit(GenerationEvent.Token(close, generated, (nanoTime() - genStart) / 1_000_000))
                            val again = try {
                                abortOnCancel { engine.beginCompletion(prompt + produced + close, request.sampling, (request.maxNewTokens - generated).coerceAtLeast(32), request.grammar, emptyList(), nKeep) }
                            } catch (e: GenerationAbortedException) { -1 }
                            produced.append(close)
                            if (again > 0) continue
                        }
                        break
                    }
                }
            }
            val tail = decoder.flush()
            val genMillis = (nanoTime() - genStart) / 1_000_000
            if (tail.isNotEmpty()) emit(GenerationEvent.Token(tail, generated, genMillis))
            val rs = runCatching { engine.runtimeStats() }.getOrDefault(IntArray(6))
            emit(
                GenerationEvent.Completed(
                    GenerationStats(
                        promptTokens = promptTokens,
                        generatedTokens = generated,
                        promptMillis = promptMillis,
                        generationMillis = if (aborted) 0 else genMillis,
                        stopReason = reason,
                        contextUsed = rs.getOrElse(0) { 0 },
                        contextLength = info.contextLength,
                        contextShifts = rs.getOrElse(2) { 0 },
                        draftedTokens = rs.getOrElse(3) { 0 },
                        acceptedTokens = rs.getOrElse(4) { 0 },
                        droppedMessages = dropped,
                        prefixCache = prefixState,
                        reusedTokens = reused,
                        firstTokenMillis = firstTokenMillis,
                    ),
                ),
            )
        }
    }.flowOn(engineDispatcher)

    /** The exact prompt text [generate] evaluates for [messages] (rendered conversation + any reasoning-ahead prefix). */
    private fun promptOf(template: PromptTemplate, messages: List<ChatMessage>, request: GenerationRequest): String =
        template.render(messages, request.thinking) + request.assistantPrefix.orEmpty()

    /** System-prompt KV prefix cache (memory + disk) step shared by [generate] and [prefill]. Caller holds [mutex]. */
    private suspend fun usePrefixCache(info: ModelInfo, request: GenerationRequest, prefix: String?, nKeep: Int, textOnly: Boolean): Int {
        if (!(prefixCacheEnabled && request.cachePrefix && prefix != null && textOnly && nKeep >= PREFIX_CACHE_MIN_TOKENS)) return -1
        val file = try { prefixCacheFile?.invoke(info, prefix) } catch (e: Exception) { null }
        val st = try {
            abortOnCancel { engine.prefixCache(prefix, file) }
        } catch (e: GenerationAbortedException) { -1 }
        // Fail safe: a cache file that could not be used is deleted (the native side already drops files whose
        // token list does not match; this also covers read/eval errors).
        if (st < 0 && file != null) runCatching { java.io.File(file).delete() }
        return st
    }

    /** True while a read-ahead holds the engine (tests / diagnostics). */
    val readingAhead: Boolean get() = mutex.holdsLock(READ_AHEAD_OWNER)

    /** Count of read-aheads interrupted by a real request / cancel (tests / diagnostics). */
    @Volatile var preemptions: Int = 0; private set

    @Volatile private var aheadRunning = false

    override fun preemptPrefill() {
        if (aheadRunning) engine.requestAbort()
    }

    /** Same fitting as [generate] (by characters, then exact tokens), so every read-ahead renders the same prompt. */
    private fun fitted(info: ModelInfo, template: PromptTemplate, request: GenerationRequest): List<ChatMessage> {
        val m = PromptFitter.fitByCharacters(request.messages, info.contextLength, request.maxNewTokens)
        return PromptFitter.fitByTokens(m, info.contextLength, request.maxNewTokens) { x -> engine.countTokens(template.render(x, request.thinking)) }
    }

    /**
     * 0.6.2.3 reading ahead: puts the prompt [generate] would build for [request] into the KV cache and samples nothing
     * (no token is generated, so no tool call can ever be parsed or run). Skipped (null) when the engine is busy, no model
     * is loaded, the request has images, or the engine cannot prefill. Same fitting, template and system-prefix cache as
     * [generate], so the prompt is byte-identical and [generate] then only evaluates what changed.
     */
    override suspend fun prefill(request: GenerationRequest): PrefillResult? {
        if (request.messages.any { it.images.isNotEmpty() }) return null
        if (!mutex.tryLock(READ_AHEAD_OWNER)) return null
        aheadRunning = true
        try {
            val info = loaded ?: return null
            return withContext(engineDispatcher) {
                val t0 = nanoTime()
                val template = templateFor?.invoke(info) ?: PromptTemplates.forModel(info, engine)
                var messages = fitted(info, template, request)
                val prefix = template.systemPrefix(messages)
                val nKeep = prefix?.let { engine.countTokens(it) } ?: 0
                usePrefixCache(info, request, prefix, nKeep, true)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val prompt = promptOf(template, messages, request)
                    try {
                        val r = abortOnCancel { engine.prefill(prompt, nKeep) } ?: return@withContext null
                        return@withContext PrefillResult(prompt, r[0], r.getOrElse(1) { 0 }, r.getOrElse(2) { 0 }, (nanoTime() - t0) / 1_000_000, aborted = false)
                    } catch (e: PromptTooLongException) {
                        messages = PromptFitter.dropOldestTurn(messages) ?: return@withContext null
                    } catch (e: GenerationAbortedException) {
                        val p = runCatching { engine.prefillProgress() }.getOrNull()
                        return@withContext PrefillResult(prompt, 0, p?.getOrElse(2) { 0 } ?: 0, p?.getOrElse(0) { 0 } ?: 0, (nanoTime() - t0) / 1_000_000, aborted = true)
                    }
                }
                @Suppress("UNREACHABLE_CODE") null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // a read-ahead never fails the app: the real request reports any problem itself
        } finally {
            aheadRunning = false
            mutex.unlock(READ_AHEAD_OWNER)
        }
    }

    /**
     * 0.6.2.3 reasoning ahead: continues the prompt of [request] (whose [GenerationRequest.assistantPrefix] holds the open
     * think block) for at most [maxTokens], stopping at "</think>" or the end of turn. The text is ONLY used as notes in
     * the next think block: it is never parsed for tool calls. [biasWords] get [bias] added to their first token's logit.
     */
    override suspend fun reasonAhead(request: GenerationRequest, maxTokens: Int, biasWords: List<String>, bias: Float): AheadResult? {
        if (maxTokens <= 0 || request.messages.any { it.images.isNotEmpty() }) return null
        if (!mutex.tryLock(READ_AHEAD_OWNER)) return null
        aheadRunning = true
        try {
            val info = loaded ?: return null
            return withContext(engineDispatcher) {
                val t0 = nanoTime()
                val template = templateFor?.invoke(info) ?: PromptTemplates.forModel(info, engine)
                val messages = fitted(info, template, request)
                val prefix = template.systemPrefix(messages)
                val nKeep = prefix?.let { engine.countTokens(it) } ?: 0
                usePrefixCache(info, request, prefix, nKeep, true)
                val ids = biasWords.mapNotNull { w -> engine.tokenIds(w)?.firstOrNull() }.distinct().toIntArray()
                if (ids.isNotEmpty()) engine.setLogitBias(ids, bias)
                try {
                    val prompt = promptOf(template, messages, request)
                    try { abortOnCancel { engine.beginCompletion(prompt, request.sampling, maxTokens, null, emptyList(), nKeep) } }
                    catch (e: GenerationAbortedException) { return@withContext AheadResult("", 0, (nanoTime() - t0) / 1_000_000, StopReason.CANCELLED) }
                    val decoder = Utf8StreamDecoder()
                    val out = StringBuilder()
                    var n = 0
                    var why = StopReason.MAX_TOKENS
                    while (n < maxTokens) {
                        currentCoroutineContext().ensureActive()
                        when (val step = engine.nextToken()) {
                            is TokenStep.Piece -> { n++; out.append(decoder.feed(step.bytes)) }
                            is TokenStep.End -> { why = step.reason; break }
                        }
                        if (out.contains(ReasoningAhead.CLOSE)) { why = StopReason.END_OF_TURN; break }
                    }
                    out.append(decoder.flush())
                    val text = out.toString().substringBefore(ReasoningAhead.CLOSE)
                    AheadResult(text, n, (nanoTime() - t0) / 1_000_000, why)
                } finally {
                    if (ids.isNotEmpty()) engine.setLogitBias(IntArray(0), 0f)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return null // a read-ahead never fails the app: the real request reports any problem itself
        } finally {
            aheadRunning = false
            mutex.unlock(READ_AHEAD_OWNER)
        }
    }

    override fun cancel() {
        preemptPrefill()
        if (_state.value is EngineState.Generating) {
            cancelRequested.set(true)
            engine.requestAbort()
        }
    }

    override suspend fun unload(reason: UnloadReason) {
        cancel()
        mutex.withLock {
            withContext(engineDispatcher) {
                if (loaded != null) engine.unload()
                loaded = null
                _state.value = EngineState.Unloaded(reason)
            }
        }
    }

    override suspend fun systemInfo(): String = withContext(engineDispatcher) { engine.systemInfo() }

    override suspend fun setThreads(threads: Int, pollLevel: Int): Boolean = mutex.withLock {
        withContext(engineDispatcher) { if (loaded == null) false else engine.setThreads(threads, pollLevel) }
    }

    override suspend fun setSpeculation(draftTokens: Int, minN: Int, maxN: Int): Boolean = mutex.withLock {
        withContext(engineDispatcher) { if (loaded == null) false else engine.setNgram(minN, maxN, draftTokens) }
    }

    /** Exact token count with the loaded model's tokenizer (null when no model is loaded or the engine is busy). */
    override suspend fun countTokens(text: String): Int? {
        if (loaded == null) return null
        if (!mutex.tryLock()) {
            // 0.6.2.3: a read-ahead must not turn exact counts into estimates (Send would fit a different history and
            // the prompt would no longer match the prefill): interrupt it and count.
            if (!mutex.holdsLock(READ_AHEAD_OWNER)) return null
            var n = 0
            while (!mutex.tryLock()) {
                if (!mutex.holdsLock(READ_AHEAD_OWNER) || n++ > 300) return null
                engine.requestAbort()
                kotlinx.coroutines.delay(PREEMPT_POLL_MS)
            }
        }
        return try { withContext(engineDispatcher) { engine.countTokens(text) } } finally { mutex.unlock() }
    }

    /**
     * Runs a blocking prompt evaluation while a side coroutine reports [GenerationEvent.PromptProgress] every
     * [PROGRESS_INTERVAL_MS] (the engine thread is busy, so the poller runs on [Dispatchers.Default]; the native
     * progress counters are atomics).
     */
    private suspend fun <T> kotlinx.coroutines.channels.ProducerScope<GenerationEvent>.withPrefillProgress(block: suspend () -> T): T {
        val poller = launch(Dispatchers.Default) {
            var last = -1
            while (true) {
                kotlinx.coroutines.delay(PROGRESS_INTERVAL_MS)
                val p = runCatching { engine.prefillProgress() }.getOrNull() ?: continue
                if (p.size >= 2 && p[1] > 0 && p[0] != last) { last = p[0]; trySend(GenerationEvent.PromptProgress(p[0], p[1], p.getOrElse(2) { 0 })) }
            }
        }
        try { return block() } finally { poller.cancel() }
    }

    companion object {
        /** Mutex owner of read-aheads (prefill / reasoning ahead): [generate] preempts it instead of failing busy. */
        private val READ_AHEAD_OWNER = Any()
        const val PROGRESS_INTERVAL_MS = 200L
        const val PREEMPT_POLL_MS = 10L
        /** Below this the system prompt is cheaper to evaluate than to load from disk. */
        const val PREFIX_CACHE_MIN_TOKENS = 64
    }

    /**
     * Runs a blocking engine call; if the calling coroutine is cancelled meanwhile, asks the engine to
     * abort so the call returns quickly instead of finishing a long prompt evaluation.
     */
    private suspend fun <T> abortOnCancel(block: () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) engine.requestAbort()
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
}

/** Keeps a conversation inside the context window (before exact token counts are known). */
object PromptFitter {
    /** Conservative characters-per-token estimate (Qwen tokenizer averages ~3.5–4 for English). */
    const val CHARS_PER_TOKEN = 3

    fun fitByCharacters(messages: List<ChatMessage>, contextLength: Int, maxNewTokens: Int): List<ChatMessage> {
        val budgetChars = (contextLength - maxNewTokens - 32).coerceAtLeast(64) * CHARS_PER_TOKEN
        var current = messages
        while (current.sumOf { it.content.length + 16 } > budgetChars) {
            current = dropOldestTurn(current) ?: break
        }
        return current
    }

    /**
     * Exact fit: drops the oldest turns until the rendered prompt (counted by [count]) leaves room for the reply.
     * [count] returns null when exact counting is unavailable; the messages are then returned unchanged.
     */
    fun fitByTokens(messages: List<ChatMessage>, contextLength: Int, maxNewTokens: Int, count: (List<ChatMessage>) -> Int?): List<ChatMessage> {
        val budget = (contextLength - maxNewTokens - 16).coerceAtLeast(64)
        var current = messages
        while (true) {
            val n = count(current) ?: return current
            if (n <= budget) return current
            current = dropOldestTurn(current) ?: return current
        }
    }

    /**
     * Drops the oldest non-system message (and a following assistant reply, so turns stay paired).
     * Returns null when only the system prompt and the final user message remain.
     */
    fun dropOldestTurn(messages: List<ChatMessage>): List<ChatMessage>? {
        val hasSystem = messages.firstOrNull()?.role == Role.SYSTEM
        val start = if (hasSystem) 1 else 0
        if (messages.size - start <= 1) return null
        val out = messages.toMutableList()
        out.removeAt(start)
        if (out.size - start > 1 && out[start].role == Role.ASSISTANT) out.removeAt(start)
        return out
    }
}
