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
        if (!mutex.tryLock()) throw BusyException("The engine is busy (loading or already generating)")
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
    private fun generateLocked(info: ModelInfo, request: GenerationRequest): Flow<GenerationEvent> = flow {
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
            var prefixState = -1
            val prefix = template.systemPrefix(messages)
            val nKeep = prefix?.let { engine.countTokens(it) } ?: 0
            if (request.cachePrefix && prefix != null && images.isEmpty() && nKeep >= PREFIX_CACHE_MIN_TOKENS) {
                prefixState = try {
                    abortOnCancel { engine.prefixCache(prefix, prefixCacheFile?.invoke(info, prefix)) }
                } catch (e: GenerationAbortedException) { -1 }
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                val prompt = template.render(messages, request.thinking)
                try {
                    promptTokens = abortOnCancel { engine.beginCompletion(prompt, request.sampling, request.maxNewTokens, request.grammar, images, nKeep) }
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
            if (!aborted) emit(GenerationEvent.PromptProcessed(promptTokens, promptMillis))

            val decoder = Utf8StreamDecoder()
            var generated = 0
            val genStart = nanoTime()
            var reason: StopReason = if (aborted) StopReason.CANCELLED else StopReason.END_OF_TURN
            while (!aborted) {
                currentCoroutineContext().ensureActive()
                if (cancelRequested.get()) { reason = StopReason.CANCELLED; break }
                when (val step = engine.nextToken()) {
                    is TokenStep.Piece -> {
                        generated++
                        val text = decoder.feed(step.bytes)
                        if (text.isNotEmpty()) {
                            emit(GenerationEvent.Token(text, generated, (nanoTime() - genStart) / 1_000_000))
                        }
                    }
                    is TokenStep.End -> {
                        reason = if (cancelRequested.get()) StopReason.CANCELLED else step.reason
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
                    ),
                ),
            )
        }
    }.flowOn(engineDispatcher)

    override fun cancel() {
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

    /** Exact token count with the loaded model's tokenizer (null when no model is loaded or the engine is busy). */
    override suspend fun countTokens(text: String): Int? {
        if (loaded == null || !mutex.tryLock()) return null
        return try { withContext(engineDispatcher) { engine.countTokens(text) } } finally { mutex.unlock() }
    }

    companion object {
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
