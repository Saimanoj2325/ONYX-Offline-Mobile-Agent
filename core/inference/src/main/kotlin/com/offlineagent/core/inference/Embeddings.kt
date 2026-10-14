package com.offlineagent.core.inference

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Blocking sentence-embedding engine (llama.cpp embedding mode). Single-threaded, like [TokenEngine]. */
interface EmbeddingEngine {
    val dimension: Int
    val isLoaded: Boolean
    fun load(path: String, contextLength: Int, threads: Int)
    /** Returns an L2-normalised vector. */
    fun embed(text: String): FloatArray
    fun unload()
}

/**
 * Coroutine wrapper: every call runs on its own dedicated thread ("embed-engine"), never the main thread,
 * and never concurrently. Loads lazily from [modelPath] on first use.
 */
class EmbeddingService(private val engine: EmbeddingEngine, private val dispatcher: CoroutineDispatcher) {
    private val mutex = Mutex()
    @Volatile var loadedPath: String? = null
        private set

    val dimension: Int get() = engine.dimension

    suspend fun ensureLoaded(modelPath: String, threads: Int, contextLength: Int = 512) = mutex.withLock {
        withContext(dispatcher) {
            if (loadedPath != modelPath || !engine.isLoaded) {
                engine.load(modelPath, contextLength, threads)
                loadedPath = modelPath
            }
        }
    }

    suspend fun embed(text: String): FloatArray = mutex.withLock { withContext(dispatcher) { engine.embed(text) } }

    suspend fun unload() = mutex.withLock {
        withContext(dispatcher) { engine.unload(); loadedPath = null }
    }
}
