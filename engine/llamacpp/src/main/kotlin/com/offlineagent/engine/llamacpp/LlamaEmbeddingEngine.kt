package com.offlineagent.engine.llamacpp

import com.offlineagent.core.inference.EmbeddingEngine
import com.offlineagent.core.inference.InferenceException
import com.offlineagent.core.inference.ModelLoadException
import java.io.File

/** llama.cpp sentence-embedding model (separate model + context from the chat model). Single-threaded use. */
class LlamaEmbeddingEngine(private val nativeLibDir: String) : EmbeddingEngine {
    @Volatile private var handle = 0L
    override var dimension: Int = 0
        private set

    override fun load(path: String, contextLength: Int, threads: Int) {
        if (!File(path).isFile) throw ModelLoadException("Embedding model file not found")
        try { LlamaNative.ensureLoaded(nativeLibDir) } catch (e: UnsatisfiedLinkError) {
            throw ModelLoadException("Native inference library failed to load: ${e.message}", e)
        }
        unload()
        val h = LlamaNative.nativeLoadEmbedder(path, contextLength, threads)
        if (h == 0L) throw ModelLoadException(LlamaNative.nativeLastError().ifBlank { "Could not load embedding model" })
        handle = h
        dimension = LlamaNative.nativeEmbedderDim(h)
    }

    override fun embed(text: String): FloatArray {
        val h = handle.takeIf { it != 0L } ?: throw InferenceException("Embedding model not loaded")
        return LlamaNative.nativeEmbed(h, text.toByteArray(Charsets.UTF_8))
            ?: throw InferenceException(LlamaNative.nativeLastError().ifBlank { "Embedding failed" })
    }

    override val isLoaded: Boolean get() = handle != 0L

    override fun unload() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.nativeFreeEmbedder(h)
    }
}
