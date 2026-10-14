package com.offlineagent.core.inference

/** Role of a chat message. Tool roles are reserved for the later agent loop (Phase 3+). */
enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

/** How to load a model. All values are local; there is no remote option by design. */
data class LoadConfig(
    /** Absolute path to a GGUF file in app-internal storage. */
    val modelPath: String,
    /** KV-cache context length in tokens. */
    val contextLength: Int = DEFAULT_CONTEXT_LENGTH,
    /** CPU threads for decode and prompt processing. */
    val threads: Int = ThreadPolicy.FALLBACK_THREADS,
    /** Prompt-processing batch size. */
    val batchSize: Int = 512,
    /** Memory-map the weights instead of reading them into heap (always true in Phase 1). */
    val useMmap: Boolean = true,
) {
    init {
        require(contextLength in 256..32768) { "contextLength out of range: $contextLength" }
        require(threads in 1..16) { "threads out of range: $threads" }
        require(batchSize in 32..2048) { "batchSize out of range: $batchSize" }
    }

    companion object {
        const val DEFAULT_CONTEXT_LENGTH = 2048
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
)

enum class StopReason { END_OF_TURN, MAX_TOKENS, CONTEXT_FULL, CANCELLED, ERROR }

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val promptMillis: Long,
    val generationMillis: Long,
    val stopReason: StopReason,
) {
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
