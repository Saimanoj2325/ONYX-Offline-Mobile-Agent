package com.offlineagent.core.inference

/**
 * Chooses the CPU thread count for inference.
 *
 * Phones are big.LITTLE: decoding is memory-bound and synchronised across threads, so putting work
 * on slow efficiency cores makes every token wait for the slowest thread. We use the
 * performance cores only = all cores whose max frequency is above the lowest cluster's.
 */
object ThreadPolicy {
    const val FALLBACK_THREADS = 4
    const val MAX_THREADS = 8

    /**
     * @param maxFreqsKhz max frequency per CPU (from /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq);
     *        entries <= 0 mean unknown.
     */
    fun performanceCores(maxFreqsKhz: List<Long>): Int {
        val known = maxFreqsKhz.filter { it > 0 }
        if (known.isEmpty() || known.size < maxFreqsKhz.size / 2) return FALLBACK_THREADS
        val minFreq = known.min()
        val fast = known.count { it > minFreq }
        val result = if (fast == 0) {
            // Single cluster (all cores identical): use up to 4 to leave room for the UI thread.
            minOf(known.size, FALLBACK_THREADS)
        } else {
            fast
        }
        return result.coerceIn(1, MAX_THREADS)
    }

    /**
     * CPU ids of the performance cores (all cores faster than the slowest cluster), used to pin the inference
     * thread pool. Empty when the topology is unknown or uniform (the OS scheduler is then left alone).
     */
    fun performanceCoreIds(maxFreqsKhz: List<Long>): List<Int> {
        val known = maxFreqsKhz.filter { it > 0 }
        if (known.isEmpty() || known.size < maxFreqsKhz.size / 2) return emptyList()
        val minFreq = known.min()
        val ids = maxFreqsKhz.indices.filter { maxFreqsKhz[it] > minFreq }
        return ids.take(MAX_THREADS)
    }
}

/** Minimal GGUF sanity checks done before handing a file to the native loader. */
object GgufFile {
    private val MAGIC = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte())

    /** True if [header] (first >= 8 bytes) is a GGUF v2/v3 header. */
    fun hasValidHeader(header: ByteArray): Boolean {
        if (header.size < 8) return false
        for (i in 0 until 4) if (header[i] != MAGIC[i]) return false
        val version = (header[4].toInt() and 0xFF) or
            ((header[5].toInt() and 0xFF) shl 8) or
            ((header[6].toInt() and 0xFF) shl 16) or
            ((header[7].toInt() and 0xFF) shl 24)
        return version in 2..3
    }

    fun looksLikeGgufName(name: String?): Boolean = name?.lowercase()?.endsWith(".gguf") == true

    /**
     * Rough RAM needed to run a model: mmapped weights are paged in on use, so count the file
     * plus KV cache and compute buffers. Used only for a friendly up-front warning.
     */
    fun estimatedRuntimeBytes(fileBytes: Long, contextLength: Int): Long {
        val kvAndCompute = contextLength.toLong() * 160_000L // ~0.33 GB at 2048 ctx: generous for ≤1B models
        return fileBytes + kvAndCompute + 64L * 1024 * 1024
    }
}
