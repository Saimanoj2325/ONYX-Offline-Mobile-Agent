package com.offlineagent.core.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between the app (and later the agent runtime) and any on-device LLM engine.
 *
 * Implementations MUST:
 *  - run every native/engine call off the main thread (one dedicated background thread);
 *  - stream tokens as a cold [Flow] that stops promptly when its collector is cancelled;
 *  - never reach the network.
 *
 * Phase 1 implementation: [EngineInferenceAdapter] over a llama.cpp [TokenEngine].
 * A QVAC (or other) engine can be swapped in by providing another [TokenEngine] or a
 * whole new [InferenceAdapter].
 */
interface InferenceAdapter {
    val state: StateFlow<EngineState>

    /** Loads a model, unloading any previous one. Throws [ModelLoadException] on failure. */
    suspend fun load(config: LoadConfig): ModelInfo

    /**
     * Streams a reply for [request]. Emits [GenerationEvent.PromptProcessed], then
     * [GenerationEvent.Token]s, then exactly one [GenerationEvent.Completed] (also on cancel via [cancel]).
     * Cancelling the collecting coroutine aborts native work as soon as possible.
     */
    fun generate(request: GenerationRequest): Flow<GenerationEvent>

    /** Requests the in-flight generation (if any) to stop. Safe from any thread. */
    fun cancel()

    /** Frees the model and context. Cancels any generation first. */
    suspend fun unload(reason: UnloadReason = UnloadReason.USER)

    /** Human readable CPU/backend capabilities string (for diagnostics). */
    suspend fun systemInfo(): String
}

/**
 * Low-level, blocking, single-threaded engine contract (what a JNI binding provides).
 * All methods except [requestAbort] are only ever called from the adapter's dedicated thread.
 */
interface TokenEngine {
    /** Loads model + context. Throws [ModelLoadException]. */
    fun load(config: LoadConfig): ModelInfo

    /**
     * Tokenizes and evaluates [prompt] (re-using any matching KV-cache prefix) and prepares
     * sampling. Returns the number of prompt tokens.
     * Throws [PromptTooLongException], [GenerationAbortedException] or [InferenceException].
     */
    fun beginCompletion(
        prompt: String,
        sampling: SamplingParams,
        maxNewTokens: Int,
        grammar: String? = null,
        images: List<ImageData> = emptyList(),
    ): Int

    /**
     * Formats a conversation with the model's own chat template when the engine knows it (non-ChatML models).
     * Returns null when unsupported; the adapter then falls back to ChatML.
     */
    fun formatChat(messages: List<ChatMessage>): String? = null

    /** Placeholder the engine replaces with an image's embeddings (one per image, in order). */
    val mediaMarker: String get() = "<__media__>"

    /** Samples + evaluates one token. */
    fun nextToken(): TokenStep

    /** Thread-safe: asks a running [beginCompletion] / [nextToken] to stop early. */
    fun requestAbort()

    fun unload()

    fun systemInfo(): String
}

sealed interface TokenStep {
    /** Raw UTF-8 bytes of the token piece (may be a partial code point). */
    class Piece(val bytes: ByteArray) : TokenStep
    data class End(val reason: StopReason) : TokenStep
}
