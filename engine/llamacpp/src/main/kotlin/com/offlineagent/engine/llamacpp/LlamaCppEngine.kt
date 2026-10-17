package com.offlineagent.engine.llamacpp

import android.content.Context
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.GrammarException
import com.offlineagent.core.inference.ImageData
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
import com.offlineagent.core.inference.EmbeddingService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** One file per (model, context settings, system prompt). Old ones are pruned to [MAX_FILES]. */
object PrefixCacheFiles {
    const val MAX_FILES = 8
    /** Everything else that changes the KV layout/contents (flash attention, batch, CPU variant, cache format). */
    @Volatile var configTag: String = ""

    fun path(dir: File, info: ModelInfo, prefix: String): String {
        dir.mkdirs()
        val f = File(info.path)
        val key = listOf("v4", info.path, f.length(), f.lastModified(), info.contextLength, info.kvCacheType.name, configTag, LlamaNative.cpuVariant, prefix).joinToString("\u0000")
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
        val target = File(dir, "$hash.kv")
        prune(dir, keep = target)
        return target.absolutePath
    }

    private fun prune(dir: File, keep: File) {
        val files = dir.listFiles { x -> x.name.endsWith(".kv") }?.sortedByDescending { it.lastModified() } ?: return
        files.filter { it != keep }.drop(MAX_FILES - 1).forEach { it.delete() }
        if (keep.isFile) keep.setLastModified(System.currentTimeMillis())
    }
}

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
        val opts = intArrayOf(
            config.contextLength, config.threads, config.batchSize, config.ubatchSize, if (config.useMmap) 1 else 0,
            config.kvCacheType.ggmlType, config.kvCacheType.vGgmlType, config.flashAttention.native, config.ropeScaling.native,
            config.yarnOriginalContext, config.pollLevel, if (config.warmup) 1 else 0,
        )
        PrefixCacheFiles.configTag = "fa=${config.flashAttention}/b=${config.batchSize}/ub=${config.ubatchSize}/rope=${config.ropeScaling}"
        val h = try {
            LlamaNative.nativeLoad(file.absolutePath, opts, floatArrayOf(0f), config.cpuIds.toIntArray())
        } catch (e: Throwable) { // UnsatisfiedLinkError / OOM in JNI glue: report, never crash the app
            throw ModelLoadException("Native load failed: ${e.message ?: e}", e)
        }
        if (h == 0L) throw ModelLoadException(LlamaNative.nativeLastError().ifBlank { "Unknown load error" })
        handle = h
        var vision = false
        val mmproj = config.mmprojPath
        if (mmproj != null) {
            if (!File(mmproj).isFile) { unload(); throw ModelLoadException("Vision projector file not found") }
            if (!LlamaNative.nativeAttachMmproj(h, mmproj, config.threads)) {
                val err = LlamaNative.nativeLastError()
                unload()
                throw ModelLoadException(err.ifBlank { "Could not load the vision projector" })
            }
            vision = true
        }
        var speculative = false
        val draft = config.draftModelPath
        if (draft != null && mmproj == null) {
            if (!File(draft).isFile) { unload(); throw ModelLoadException("Draft model file not found") }
            if (!LlamaNative.nativeAttachDraft(h, draft, config.draftTokens, config.threads, config.kvCacheType.ggmlType)) {
                val err = LlamaNative.nativeLastError()
                unload()
                throw ModelLoadException(err.ifBlank { "Could not load the draft model" })
            }
            speculative = true
        }
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
            supportsImages = vision,
            layers = f.getOrNull(6)?.toIntOrNull() ?: 0,
            kvHeads = f.getOrNull(7)?.toIntOrNull() ?: 0,
            embedding = f.getOrNull(8)?.toIntOrNull() ?: 0,
            heads = f.getOrNull(9)?.toIntOrNull() ?: 0,
            canShift = f.getOrNull(10) == "1",
            kvCacheType = config.kvCacheType,
            speculative = speculative,
        )
    }

    override fun beginCompletion(
        prompt: String,
        sampling: SamplingParams,
        maxNewTokens: Int,
        grammar: String?,
        images: List<ImageData>,
    ): Int = beginCompletion(prompt, sampling, maxNewTokens, grammar, images, 0)

    override fun beginCompletion(
        prompt: String,
        sampling: SamplingParams,
        maxNewTokens: Int,
        grammar: String?,
        images: List<ImageData>,
        nKeep: Int,
    ): Int {
        val h = handle.takeIf { it != 0L } ?: throw NotLoadedException()
        val rc = LlamaNative.nativeBegin(
            h, prompt.toByteArray(Charsets.UTF_8), sampling.temperature, sampling.topK, sampling.topP,
            sampling.minP, sampling.repeatPenalty, sampling.seed, maxNewTokens,
            grammar?.toByteArray(Charsets.UTF_8),
            if (images.isEmpty()) null else images.map { it.rgb }.toTypedArray(),
            if (images.isEmpty()) null else images.map { it.width }.toIntArray(),
            if (images.isEmpty()) null else images.map { it.height }.toIntArray(),
            nKeep,
        )
        return when (rc) {
            LlamaNative.BEGIN_ERR_TOO_LONG -> throw PromptTooLongException(-1, -1)
            LlamaNative.BEGIN_ERR_ABORTED -> throw GenerationAbortedException()
            LlamaNative.BEGIN_ERR_GRAMMAR -> throw GrammarException(LlamaNative.nativeLastError())
            LlamaNative.BEGIN_ERR_DECODE, LlamaNative.BEGIN_ERR_TOKENIZE -> throw InferenceException(LlamaNative.nativeLastError())
            else -> rc.also { lastPromptTokens = it }
        }
    }

    override fun countTokens(text: String): Int? {
        val h = handle.takeIf { it != 0L } ?: return null
        return LlamaNative.nativeCountTokens(h, text.toByteArray(Charsets.UTF_8))
    }

    override fun prefixCache(prefix: String, cacheFile: String?): Int {
        val h = handle.takeIf { it != 0L } ?: return -1
        cacheFile?.let { File(it).parentFile?.mkdirs() }
        return LlamaNative.nativePrefixCache(h, prefix.toByteArray(Charsets.UTF_8), cacheFile ?: "", cacheFile != null)
    }

    override fun runtimeStats(): IntArray {
        val h = handle.takeIf { it != 0L } ?: return IntArray(6)
        return LlamaNative.nativeStats(h)
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

    override fun formatChat(messages: List<ChatMessage>): String? {
        val h = handle.takeIf { it != 0L } ?: return null
        val out = LlamaNative.nativeApplyTemplate(
            h,
            messages.map { it.role.name.lowercase() }.toTypedArray(),
            messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        ) ?: return null
        return String(out, Charsets.UTF_8)
    }

    override val mediaMarker: String by lazy {
        runCatching { LlamaNative.ensureLoaded(nativeLibDir); LlamaNative.nativeMediaMarker() }.getOrDefault("<__media__>")
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
        fun newEngineDispatcher(name: String = "llm-engine"): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread(null, r, name, 16L * 1024 * 1024).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.asCoroutineDispatcher()

        fun createEmbedder(context: Context): EmbeddingService =
            EmbeddingService(LlamaEmbeddingEngine(context.applicationInfo.nativeLibraryDir), newEngineDispatcher("embed-engine"))

        fun prefixCacheDir(context: Context) = File(context.cacheDir, "prompt-cache")

        /** Deletes every saved prompt-prefix KV file (after a crash, a failed self-test, or a settings migration). */
        fun clearPrefixCache(context: Context) { prefixCacheDir(context).listFiles()?.forEach { it.delete() } }

        fun createAdapter(context: Context): EngineInferenceAdapter {
            val cacheDir = prefixCacheDir(context)
            return EngineInferenceAdapter(
                engine = LlamaCppEngine(context.applicationInfo.nativeLibraryDir),
                engineDispatcher = newEngineDispatcher(),
                prefixCacheFile = { info, prefix -> PrefixCacheFiles.path(cacheDir, info, prefix) },
            )
        }
    }
}
