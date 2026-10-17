package com.offlineagent.core.inference

/** Role of a chat message. Tool results are fed back as USER turns by the agent runtime. */
enum class Role { SYSTEM, USER, ASSISTANT }

/** A decoded image for vision models: packed RGB, 3 bytes per pixel, row-major. */
class ImageData(val rgb: ByteArray, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0 && rgb.size == width * height * 3) { "RGB buffer must be width*height*3" } }
}

data class ChatMessage(val role: Role, val content: String, val images: List<ImageData> = emptyList())

/** KV-cache element type. ids are ggml_type values passed straight to llama.cpp. */
/**
 * KV cache precision. [ggmlType] is the K cache type, [vGgmlType] the V cache type.
 * Measured on the ARM64 host (llama.cpp CPU backend, Qwen3-1.7B, ~6K-token prompt): f16 reads prompts ~3x faster than
 * q8_0 (54-60 vs 20 tok/s; quantising only K was just as slow), so "Auto" uses f16 whenever it fits in RAM and
 * q8_0 only for windows that would not fit otherwise.
 */
enum class KvCacheType(val ggmlType: Int, val vGgmlType: Int, val kBytes: Double, val vBytes: Double, val label: String) {
    F16(1, 1, 2.0, 2.0, "f16 (fastest, most memory)"),
    Q8_0(8, 8, 34.0 / 32.0, 34.0 / 32.0, "q8_0 (half the memory, slower long prompts)"),
    Q4_0(2, 2, 18.0 / 32.0, 18.0 / 32.0, "q4_0 (smallest, slight quality loss)"),
    ;
    /** Average bytes per K/V element (for estimates that treat K and V alike). */
    val bytesPerElement: Double get() = (kBytes + vBytes) / 2
    val quantisedV: Boolean get() = vGgmlType != 1
    val quantised: Boolean get() = ggmlType != 1 || vGgmlType != 1
}

enum class FlashAttention(val native: Int) { AUTO(-1), OFF(0), ON(1) }

/** RoPE scaling. YARN stretches a model past its trained context (Qwen3: 32K native -> 64K/128K, experimental). */
enum class RopeScaling(val native: Int) { MODEL(-1), NONE(0), YARN(2) }

/** How to load a model. All values are local; there is no remote option by design. */
data class LoadConfig(
    /** Absolute path to a GGUF file in app-internal storage. */
    val modelPath: String,
    /** KV-cache context length in tokens. */
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    /** CPU threads for decode and prompt processing. */
    val threads: Int = ThreadPolicy.FALLBACK_THREADS,
    /** Prompt-processing (logical) batch size. */
    val batchSize: Int = 512,
    /** Physical micro-batch size (compute buffer scales with it). */
    val ubatchSize: Int = 512,
    /** Memory-map the weights instead of reading them into heap. */
    val useMmap: Boolean = true,
    /** Optional vision projector (mmproj GGUF) for image input. */
    val mmprojPath: String? = null,
    /** KV cache precision (K and V). Quantised V forces flash attention on. */
    val kvCacheType: KvCacheType = KvCacheType.F16,
    val flashAttention: FlashAttention = FlashAttention.AUTO,
    val ropeScaling: RopeScaling = RopeScaling.MODEL,
    /** YaRN original (trained) context; 0 = from the model. */
    val yarnOriginalContext: Int = 0,
    /** CPU ids to pin inference threads to (performance cores). Empty = OS default. */
    val cpuIds: List<Int> = emptyList(),
    /** ggml thread-pool busy-poll level 0..100 (higher = lower latency, more power). */
    val pollLevel: Int = 50,
    /** Run one tiny decode after loading so weights are paged in before the first real request. */
    val warmup: Boolean = true,
    /** Optional speculative-decoding draft model (same tokenizer, e.g. Qwen3-0.6B for Qwen3-1.7B/4B). */
    val draftModelPath: String? = null,
    /** Tokens the draft proposes per round. */
    val draftTokens: Int = 6,
    /** Directory for on-disk prompt-prefix KV caches (system prompt + skills). null = off. */
    val prefixCacheDir: String? = null,
) {
    init {
        require(contextLength in 256..MAX_CONTEXT_LENGTH) { "contextLength out of range: $contextLength" }
        require(threads in 1..16) { "threads out of range: $threads" }
        require(batchSize in 32..4096) { "batchSize out of range: $batchSize" }
        require(ubatchSize in 32..4096) { "ubatchSize out of range: $ubatchSize" }
        require(pollLevel in 0..100) { "pollLevel out of range: $pollLevel" }
        require(draftTokens in 1..16) { "draftTokens out of range: $draftTokens" }
    }

    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 2048
        const val MAX_CONTEXT_LENGTH = 131072
    }
}

/** Facts about a loaded model, reported by the engine. */
data class ModelInfo(
    val path: String,
    val description: String,
    val sizeBytes: Long,
    val parameterCount: Long,
    val trainedContextLength: Int,
    val contextLength: Int,
    val threads: Int,
    val chatTemplate: String?,
    val backendInfo: String,
    /** True when a vision projector (mmproj) is attached, i.e. the model accepts images. */
    val supportsImages: Boolean = false,
    /** Architecture numbers used for KV-cache memory estimates (0 = unknown). */
    val layers: Int = 0,
    val kvHeads: Int = 0,
    val embedding: Int = 0,
    val heads: Int = 0,
    /** KV cache can be shifted (context shifting / infinite generation). */
    val canShift: Boolean = false,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    /** Speculative decoding with a draft model is active. */
    val speculative: Boolean = false,
)

/** Sampling settings. Defaults follow Qwen3's published non-thinking recommendations. */
data class SamplingParams(
    val temperature: Float = 0.7f,
    val topK: Int = 20,
    val topP: Float = 0.8f,
    val minP: Float = 0.0f,
    val repeatPenalty: Float = 1.05f,
    val seed: Int = -1,
) {
    companion object {
        /** Qwen3 recommended settings with thinking disabled. */
        val QWEN3_NON_THINKING = SamplingParams(temperature = 0.7f, topK = 20, topP = 0.8f, minP = 0f)

        /** Qwen3 recommended settings with thinking enabled. */
        val QWEN3_THINKING = SamplingParams(temperature = 0.6f, topK = 20, topP = 0.95f, minP = 0f)
    }
}

data class GenerationRequest(
    val messages: List<ChatMessage>,
    /** false = "thinking off" (Qwen3 /no_think + empty think block). Default off for speed. */
    val thinking: Boolean = false,
    val maxNewTokens: Int = 512,
    val sampling: SamplingParams = if (thinking) SamplingParams.QWEN3_THINKING else SamplingParams.QWEN3_NON_THINKING,
    /** Re-use / persist the KV cache of the system prompt across requests and app restarts. */
    val cachePrefix: Boolean = true,
    /**
     * Optional GBNF grammar (root rule "root"). When set, the engine can only emit text the grammar accepts:
     * used for Canvas UI JSON and agent tool calls so the output always parses.
     */
    val grammar: String? = null,
)

enum class StopReason { END_OF_TURN, MAX_TOKENS, CONTEXT_FULL, CANCELLED, ERROR }

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMillis: Long,
    val generationMillis: Long,
    val stopReason: StopReason,
    /** Tokens in the KV cache after this request (prompt + generated, after any shifting). */
    val contextUsed: Int = 0,
    val contextLength: Int = 0,
    /** How many times the window slid (old tokens discarded) during generation. */
    val contextShifts: Int = 0,
    /** Speculative decoding: tokens proposed by the draft model and how many the target accepted. */
    val draftedTokens: Int = 0,
    val acceptedTokens: Int = 0,
    /** Messages dropped from the start of the conversation to fit the window. */
    val droppedMessages: Int = 0,
    /** System-prompt prefix cache: 0 already in KV, 1 loaded from disk, 2 evaluated+saved, -1 not used. */
    val prefixCache: Int = -1,
    /** Phase 4b: prompt tokens reused from the live KV cache (prefix reuse between turns). */
    val reusedTokens: Int = 0,
    /** Phase 4b: time from the request to the first visible token (ms), 0 = none. */
    val firstTokenMillis: Long = 0,
) {
    val acceptanceRate: Double get() = if (draftedTokens <= 0) 0.0 else acceptedTokens.toDouble() / draftedTokens

    /** Decode speed: generated tokens / time spent generating (excludes prompt processing). */
    val tokensPerSecond: Double
        get() = if (generationMillis <= 0) 0.0 else generatedTokens * 1000.0 / generationMillis

    val promptTokensPerSecond: Double
        get() = if (promptMillis <= 0) 0.0 else promptTokens * 1000.0 / promptMillis
}

sealed interface GenerationEvent {
    /** Prompt has been evaluated; first token is about to be sampled. */
    data class PromptProcessed(val promptTokens: Int, val promptMillis: Long) : GenerationEvent

    /** A decoded text fragment (may be empty-safe joined). [generatedTokens] counts tokens so far. */
    data class Token(val text: String, val generatedTokens: Int, val elapsedMillis: Long) : GenerationEvent {
        val tokensPerSecond: Double get() = if (elapsedMillis <= 0) 0.0 else generatedTokens * 1000.0 / elapsedMillis
    }

    data class Completed(val stats: GenerationStats) : GenerationEvent

    /**
     * Phase 4b: prompt evaluation progress ("Reading prompt… [done]/[total]"); [reused] tokens came from the KV cache
     * and are not evaluated again. Emitted a few times per second while a long prompt is read.
     */
    data class PromptProgress(val done: Int, val total: Int, val reused: Int) : GenerationEvent
}

/** Lifecycle of the inference adapter. */
sealed interface EngineState {
    /** No model in memory. [reason] says why the last one was unloaded (null = never loaded). */
    data class Unloaded(val reason: UnloadReason? = null) : EngineState
    data class Loading(val modelPath: String) : EngineState
    data class Ready(val model: ModelInfo) : EngineState
    data class Generating(val model: ModelInfo) : EngineState
    data class Failed(val message: String, val cause: Throwable? = null) : EngineState
}

enum class UnloadReason { USER, LOW_MEMORY, SHUTDOWN }

open class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
class ModelLoadException(message: String, cause: Throwable? = null) : InferenceException(message, cause)
class PromptTooLongException(val promptTokens: Int, val contextLength: Int) :
    InferenceException("Prompt is $promptTokens tokens but the context holds $contextLength")
class GenerationAbortedException : InferenceException("Generation aborted")
class NotLoadedException : InferenceException("No model is loaded")
class BusyException(message: String) : InferenceException(message)
class GrammarException(message: String) : InferenceException(message)
