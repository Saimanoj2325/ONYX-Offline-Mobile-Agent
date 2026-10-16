package com.offlineagent.engine.llamacpp

/** Raw JNI surface of libofflineagent_llama.so. See src/main/cpp/llama_jni.cpp. */
internal object LlamaNative {
    const val BEGIN_ERR_TOO_LONG = -1
    const val BEGIN_ERR_ABORTED = -2
    const val BEGIN_ERR_DECODE = -3
    const val BEGIN_ERR_TOKENIZE = -4
    const val BEGIN_ERR_GRAMMAR = -5

    const val STOP_NONE = 0
    const val STOP_END_OF_TURN = 1
    const val STOP_MAX_TOKENS = 2
    const val STOP_CONTEXT_FULL = 3
    const val STOP_CANCELLED = 4
    const val STOP_ERROR = 5

    @Volatile private var libraryLoaded = false

    @Synchronized
    fun ensureLoaded(nativeLibDir: String) {
        if (libraryLoaded) return
        System.loadLibrary("offline_agent_llama")
        nativeInit(nativeLibDir)
        libraryLoaded = true
    }

    external fun nativeInit(nativeLibDir: String)
    /** opts/fopts layout: see llama_jni.cpp nativeLoad. */
    external fun nativeLoad(path: String, opts: IntArray, fopts: FloatArray, cpus: IntArray): Long
    external fun nativeLastError(): String
    external fun nativeModelInfo(handle: Long): Array<String>
    external fun nativeBegin(
        handle: Long, promptUtf8: ByteArray, temp: Float, topK: Int, topP: Float, minP: Float,
        repeatPenalty: Float, seed: Int, maxNew: Int,
        grammarUtf8: ByteArray?, imagesRgb: Array<ByteArray>?, widths: IntArray?, heights: IntArray?, nKeep: Int,
    ): Int
    external fun nativeStats(handle: Long): IntArray
    external fun nativeCountTokens(handle: Long, textUtf8: ByteArray): Int
    external fun nativePrefixCache(handle: Long, prefixUtf8: ByteArray, path: String, save: Boolean): Int
    external fun nativeAttachDraft(handle: Long, path: String?, nDraft: Int, nThreads: Int, typeK: Int): Boolean
    external fun nativeAttachMmproj(handle: Long, mmprojPath: String, nThreads: Int): Boolean
    external fun nativeMediaMarker(): String
    external fun nativeApplyTemplate(handle: Long, roles: Array<String>, contentsUtf8: Array<ByteArray>): ByteArray?
    external fun nativeLoadEmbedder(path: String, nCtx: Int, nThreads: Int): Long
    external fun nativeEmbed(handle: Long, textUtf8: ByteArray): FloatArray?
    external fun nativeEmbedderDim(handle: Long): Int
    external fun nativeFreeEmbedder(handle: Long)
    external fun nativeNext(handle: Long): ByteArray?
    external fun nativeStopReason(handle: Long): Int
    external fun nativeAbort(handle: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeSystemInfo(): String
}
