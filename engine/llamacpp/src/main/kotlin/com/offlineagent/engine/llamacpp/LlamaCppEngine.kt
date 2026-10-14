package com.offlineagent.engine.llamacpp

import android.content.Context
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.GenerationAbortedException
import com.offlineagent.core.inference.InferenceAdapter
import com.offlineagent.core.inference.InferenceException
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.ModelInfo
import com.offlineagent.core.inference.ModelLoadException
import com.offlineagent.core.inference.NotLoadedException
import com.offlineagent.core.inference.PromptTooLongException
import com.offlineagent.core.inference.SamplingParams
import com.offlineagent.core.inference.StopReason
import com.offlineagent.core.inference.TokenEngine
import com.offlineagent.core.inference.TokenStep
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * llama.cpp implementation of [TokenEngine]. Blocking; must be driven from a single thread
 * (see [LlamaCppEngine.createAdapter], which provides one).
 */
class LlamaCppEngine(private val nativeLibDir: String) : TokenEngine {

    @Volatile private var handle: Long = 0L
    private var lastPromptTokens = 0

    override fun load(config: LoadConfig): ModelInfo {
        val file = File(config.modelPath)
        if (!file.isFile) throw ModelLoadException("Model file not found: ${file.name}")
        if (!file.canRead()) throw ModelLoadException("Model file is not readable: ${file.name}")
        try {
            LlamaNative.ensureLoaded(nativeLibDir)
        } catch (e: UnsatisfiedLinkError) {
            throw ModelLoadException("Native inference library failed to load: ${e.message}", e)
        }
        if (handle != 0L) unload()
        val h = LlamaNative.nativeLoad(file.absolutePath, config.contextLength, config.threads, config.batchSize, config.useMmap)
        if (h == 0L) throw ModelLoadException(LlamaNative.nativeLastError().ifBlank { "Unknown load error" })
        handle = h
        val f = LlamaNative.nativeModelInfo(h)
        return ModelInfo(
            path = file.absolutePath,
            description = f[0],
            sizeBytes = f[1].toLongOrNull() ?: file.length(),
            parameterCount = f[2].toLongOrNull() ?: 0,
            trainedContextLength = f[3].toIntOrNull() ?: 0,
            contextLength = f[4].toIntOrNull() ?: config.contextLength,
            threads = config.threads,
            chatTemplate = f[5].ifBlank { null },
            backendInfo = systemInfo().lineSequence().filter { it.startsWith("Device:") || it.startsWith("Backends:") }.joinToString("\n"),
        )
    }

    override fun beginCompletion(prompt: String, sampling: SamplingParams, maxNewTokens: Int): Int {
        val h = handle.takeIf { it != 0L } ?: throw NotLoadedException()
        val rc = LlamaNative.nativeBegin(
            h, prompt.toByteArray(Charsets.UTF_8), sampling.temperature, sampling.topK, sampling.topP,
            sampling.minP, sampling.repeatPenalty, sampling.seed, maxNewTokens,
        )
        return when (rc) {
            LlamaNative.BEGIN_ERR_TOO_LONG -> throw PromptTooLongException(-1, -1)
            LlamaNative.BEGIN_ERR_ABORTED -> throw GenerationAbortedException()
            LlamaNative.BEGIN_ERR_DECODE, LlamaNative.BEGIN_ERR_TOKENIZE -> throw InferenceException(LlamaNative.nativeLastError())
            else -> rc.also { lastPromptTokens = it }
        }
    }

    override fun nextToken(): TokenStep {
        val h = handle.takeIf { it != 0L } ?: return TokenStep.End(StopReason.ERROR)
        val bytes = LlamaNative.nativeNext(h)
        if (bytes != null) return TokenStep.Piece(bytes)
        return TokenStep.End(
            when (LlamaNative.nativeStopReason(h)) {
                LlamaNative.STOP_END_OF_TURN -> StopReason.END_OF_TURN
                LlamaNative.STOP_MAX_TOKENS -> StopReason.MAX_TOKENS
                LlamaNative.STOP_CONTEXT_FULL -> StopReason.CONTEXT_FULL
                LlamaNative.STOP_CANCELLED -> StopReason.CANCELLED
                else -> StopReason.ERROR
            },
        )
    }

    override fun requestAbort() {
        val h = handle
        if (h != 0L) LlamaNative.nativeAbort(h)
    }

    override fun unload() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.nativeUnload(h)
    }

    override fun systemInfo(): String = try {
        LlamaNative.ensureLoaded(nativeLibDir)
        LlamaNative.nativeSystemInfo()
    } catch (e: Throwable) {
        "native library unavailable: ${e.message}"
    }

    companion object {
        /** The one thread that ever touches llama.cpp. Named for profiling/ANR traces. */
        fun newEngineDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread(null, r, "llm-engine", 16L * 1024 * 1024).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.asCoroutineDispatcher()

        fun createAdapter(context: Context): InferenceAdapter =
            EngineInferenceAdapter(
                engine = LlamaCppEngine(context.applicationInfo.nativeLibraryDir),
                engineDispatcher = newEngineDispatcher(),
            )
    }
}
