package com.offlineagent.engine.llamacpp

/** Raw JNI surface of libofflineagent_llama.so. See src/main/cpp/llama_jni.cpp. */
internal object LlamaNative {
    const val BEGIN_ERR_TOO_LONG = -1
    const val BEGIN_ERR_ABORTED = -2
    const val BEGIN_ERR_DECODE = -3
    const val BEGIN_ERR_TOKENIZE = -4

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
    external fun nativeLoad(path: String, nCtx: Int, nThreads: Int, nBatch: Int, useMmap: Boolean): Long
    external fun nativeLastError(): String
    external fun nativeModelInfo(handle: Long): Array<String>
    external fun nativeBegin(
        handle: Long, promptUtf8: ByteArray, temp: Float, topK: Int, topP: Float, minP: Float,
        repeatPenalty: Float, seed: Int, maxNew: Int,
    ): Int
    external fun nativeNext(handle: Long): ByteArray?
    external fun nativeStopReason(handle: Long): Int
    external fun nativeAbort(handle: Long)
    external fun nativeUnload(handle: Long)
    external fun nativeSystemInfo(): String
}
