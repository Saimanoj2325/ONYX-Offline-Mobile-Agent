package com.offlineagent.core.inference

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The numbers that decide how much memory a context window costs. Read from the GGUF header (no model load). */
data class ModelArch(
    val architecture: String,
    val layers: Int,
    val kvHeads: Int,
    val heads: Int,
    val embedding: Int,
    /** Per-head key/value width (Qwen3: 128). */
    val keyLength: Int,
    val valueLength: Int,
    val trainedContext: Int,
    val vocabSize: Int = 0,
    val fileBytes: Long = 0,
) {
    /** KV-cache bytes per token for K+V at [type]. */
    fun kvBytesPerToken(type: KvCacheType): Double =
        layers.toDouble() * kvHeads * (keyLength * type.kBytes + valueLength * type.vBytes)
}

/**
 * Minimal GGUF metadata reader (v2/v3, little-endian). Reads only the key/value header, never the tensors.
 *
 * Phase 4b (ANR fix, seen on a OnePlus CPH2569): the old reader did two tiny `RandomAccessFile` syscalls per array
 * element, so skipping Qwen3's 151 k-entry `tokenizer.ggml.tokens` / `merges` string arrays cost ~600 k syscalls
 * (tens of seconds on a phone, and it ran on the main thread). Now:
 *  - reads go through a 64 KB buffer over a [java.nio.channels.FileChannel] (large skips reposition the channel);
 *  - [read] can stop as soon as the wanted keys are found ([stopWhen]); [arch] stops before the tokenizer payload;
 *  - results are cached per (path, size, mtime) — callers still must not call this on the main thread.
 */
object GgufMetadata {
    private const val T_UINT8 = 0; private const val T_INT8 = 1; private const val T_UINT16 = 2; private const val T_INT16 = 3
    private const val T_UINT32 = 4; private const val T_INT32 = 5; private const val T_FLOAT32 = 6; private const val T_BOOL = 7
    private const val T_STRING = 8; private const val T_ARRAY = 9; private const val T_UINT64 = 10; private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    private data class CacheKey(val path: String, val size: Long, val mtime: Long)
    private val archCache = java.util.concurrent.ConcurrentHashMap<CacheKey, ModelArch>()
    private val archMisses = java.util.concurrent.ConcurrentHashMap<CacheKey, Boolean>()

    /** Number of real header parses (tests check the cache). */
    @Volatile var parses: Int = 0
        private set

    /**
     * Returns scalar metadata (strings and numbers; arrays become their length under "<key>.length", payload skipped).
     * [stopWhen] is checked after every key: return true to stop early (e.g. once all needed keys are known).
     * An array whose length was recorded is only skipped if reading continues.
     */
    fun read(file: File, maxKeys: Int = 512, stopWhen: (Map<String, Any>) -> Boolean = { false }): Map<String, Any> =
        java.io.FileInputStream(file).use { fis ->
            parses++
            val r = Reader(fis.channel)
            val magic = r.bytes(4)
            require(String(magic, Charsets.US_ASCII) == "GGUF") { "not a GGUF file" }
            val version = r.u32()
            require(version in 2..3) { "unsupported GGUF version $version" }
            r.u64() // tensor count
            val kvCount = r.u64()
            val out = LinkedHashMap<String, Any>()
            for (i in 0 until minOf(kvCount, maxKeys.toLong())) {
                val key = r.str()
                val type = r.u32().toInt()
                if (type == T_ARRAY) {
                    val et = r.u32().toInt()
                    val n = r.u64()
                    require(n >= 0) { "bad array length" }
                    out["$key.length"] = n
                    if (stopWhen(out)) break
                    // Skip the payload: fixed-size elements in one jump, strings length by length (buffered, no syscalls).
                    if (et == T_STRING) { var k = 0L; while (k < n) { r.skip(r.u64()); k++ } } else r.skip(n * elementSize(et))
                } else {
                    out[key] = r.value(type)
                    if (stopWhen(out)) break
                }
            }
            out
        }

    /** Keys [arch] needs (prefixed by the architecture name where relevant). */
    private val ARCH_KEYS = listOf("block_count", "attention.head_count", "attention.head_count_kv", "embedding_length", "context_length")

    fun arch(file: File): ModelArch? {
        val key = runCatching { CacheKey(file.absolutePath, file.length(), file.lastModified()) }.getOrNull() ?: return null
        archCache[key]?.let { return it }
        if (archMisses.containsKey(key)) return null
        val a = runCatching { parseArch(file) }.getOrNull()
        if (a != null) archCache[key] = a else archMisses[key] = true
        return a
    }

    private fun parseArch(file: File): ModelArch? {
        val m = read(file) { got ->
            val a = got["general.architecture"] as? String
            // Stop at the tokenizer vocabulary (its length is all we need) once the architecture keys are known;
            // the vocabulary and merges arrays are by far the biggest part of the header.
            a != null && got.containsKey("tokenizer.ggml.tokens.length") && ARCH_KEYS.all { got.containsKey("$a.$it") }
        }
        val a = m["general.architecture"] as? String ?: return null
        fun int(k: String): Int = (m["$a.$k"] as? Number)?.toInt() ?: 0
        val heads = int("attention.head_count")
        val embd = int("embedding_length")
        val kl = int("attention.key_length").takeIf { it > 0 } ?: if (heads > 0) embd / heads else 0
        val vl = int("attention.value_length").takeIf { it > 0 } ?: kl
        return ModelArch(
            architecture = a, layers = int("block_count"), kvHeads = int("attention.head_count_kv").takeIf { it > 0 } ?: heads,
            heads = heads, embedding = embd, keyLength = kl, valueLength = vl, trainedContext = int("context_length"),
            vocabSize = ((m["tokenizer.ggml.tokens.length"] as? Number)?.toInt() ?: 0), fileBytes = file.length(),
        )
    }

    /** Drops cached headers (tests; a model file replaced in place gets a new mtime anyway). */
    fun clearCache() { archCache.clear(); archMisses.clear() }

    private fun elementSize(t: Int): Long = when (t) {
        T_UINT8, T_INT8, T_BOOL -> 1; T_UINT16, T_INT16 -> 2; T_UINT32, T_INT32, T_FLOAT32 -> 4
        T_UINT64, T_INT64, T_FLOAT64 -> 8; else -> throw IllegalArgumentException("bad GGUF array type $t")
    }

    /** Buffered little-endian reader over a file channel: 64 KB reads, large skips reposition the channel. */
    private class Reader(private val ch: java.nio.channels.FileChannel) {
        private val buf: ByteBuffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN).also { it.limit(0) }

        private fun ensure(n: Int) {
            if (buf.remaining() >= n) return
            buf.compact()
            while (buf.position() < n) {
                val r = ch.read(buf)
                if (r < 0) throw java.io.EOFException("truncated GGUF header")
            }
            buf.flip()
        }

        fun bytes(n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                if (!buf.hasRemaining()) ensure(1)
                val k = minOf(buf.remaining(), n - off)
                buf.get(out, off, k)
                off += k
            }
            return out
        }

        fun u8(): Int { ensure(1); return buf.get().toInt() and 0xFF }
        fun u32(): Long { ensure(4); return buf.int.toLong() and 0xFFFFFFFFL }
        fun i32(): Int { ensure(4); return buf.int }
        fun u64(): Long { ensure(8); return buf.long }
        fun u16(): Int { ensure(2); return buf.short.toInt() and 0xFFFF }
        fun i16(): Int { ensure(2); return buf.short.toInt() }
        fun f32(): Float { ensure(4); return buf.float }
        fun f64(): Double { ensure(8); return buf.double }

        fun skip(n: Long) {
            require(n >= 0) { "bad skip" }
            if (n <= buf.remaining()) { buf.position(buf.position() + n.toInt()); return }
            val beyond = n - buf.remaining()
            val target = ch.position() + beyond
            if (target > ch.size()) throw java.io.EOFException("truncated GGUF header")
            ch.position(target)
            buf.clear(); buf.limit(0)
        }

        fun str(): String {
            val n = u64()
            require(n in 0..1_000_000) { "string too long" }
            return String(bytes(n.toInt()), Charsets.UTF_8)
        }

        fun value(t: Int): Any = when (t) {
            T_UINT8 -> u8(); T_INT8 -> u8().toByte().toInt(); T_BOOL -> u8() != 0
            T_UINT16 -> u16(); T_INT16 -> i16()
            T_UINT32 -> u32(); T_INT32 -> i32(); T_FLOAT32 -> f32()
            T_UINT64, T_INT64 -> u64(); T_FLOAT64 -> f64()
            T_STRING -> str()
            else -> throw IllegalArgumentException("bad GGUF type $t")
        }
    }
}

/** Device memory class used to choose defaults. */
enum class RamTier(val label: String) { TINY("< 4 GB"), SMALL("4 GB"), MEDIUM("6 GB"), LARGE("8 GB"), XL("12 GB+") }

data class MemoryEstimate(
    val weightsBytes: Long,
    val kvBytes: Long,
    val computeBytes: Long,
    val overheadBytes: Long,
) {
    val totalBytes: Long get() = weightsBytes + kvBytes + computeBytes + overheadBytes
}

data class ContextOption(
    val tokens: Int,
    val estimate: MemoryEstimate?,
    /** Shown next to the option. null = fine. */
    val warning: String?,
    /** Needs RoPE scaling (YaRN) because it is beyond the model's trained context. */
    val needsYarn: Boolean,
    /** Too big for this phone: the option is disabled. */
    val blocked: Boolean,
)

data class ContextPlan(
    val tier: RamTier,
    val recommendedContext: Int,
    val recommendedKv: KvCacheType,
    val maxOutputTokens: Int,
    val options: List<ContextOption>,
)

/**
 * Picks the context window and KV cache precision from the phone's RAM and the model's architecture, and estimates
 * the memory each choice costs (weights + KV + compute buffers + runtime overhead).
 *
 *  - Phase 4 (P0 fix): 8 GB+ phones default to 8K with f16 KV; 16K/32K are opt-in ("Allow large context"),
 *    64K/128K (YaRN) experimental. 6 GB and 4 GB: 4K.  < 4 GB: 2K.
 *    (Phase 3 defaulted to 16K/32K with "Auto" q8_0 KV; a 12 GB phone produced garbage at 32K — see docs/PHASE4.md.)
 *
 * Android leaves an app roughly 45-55 % of physical RAM before the low-memory killer gets aggressive, which is the
 * budget used for "blocked" vs "warning".
 */
object ContextPlanner {
    val STEPS = listOf(2048, 4096, 8192, 16384, 32768, 65536, 131072)

    fun tier(totalRamBytes: Long): RamTier {
        val gb = totalRamBytes / (1024.0 * 1024 * 1024)
        // Phones report less than the marketing number (8 GB phones show ~7.2-7.7 GB).
        return when {
            gb >= 10.5 -> RamTier.XL
            gb >= 6.8 -> RamTier.LARGE
            gb >= 5.0 -> RamTier.MEDIUM
            gb >= 3.4 -> RamTier.SMALL
            else -> RamTier.TINY
        }
    }

    fun defaultContext(tier: RamTier): Int = when (tier) {
        RamTier.TINY -> 2048; RamTier.SMALL -> 4096; RamTier.MEDIUM -> 4096; RamTier.LARGE -> 8192; RamTier.XL -> 8192
    }

    fun maxOutputTokens(tier: RamTier): Int = when (tier) {
        RamTier.TINY -> 1024; RamTier.SMALL -> 2048; RamTier.MEDIUM -> 4096; else -> 8192
    }

    /** Memory for [arch] at [ctx] tokens. Compute buffer assumes flash attention when the KV cache is quantised. */
    fun estimate(arch: ModelArch, ctx: Int, kv: KvCacheType, ubatch: Int = 512, flashAttention: Boolean = kv != KvCacheType.F16): MemoryEstimate {
        val kvBytes = (arch.kvBytesPerToken(kv) * ctx).toLong()
        val embd = arch.embedding.coerceAtLeast(512).toLong()
        // Activations for one micro-batch (~ a dozen fp32 tensors of [ubatch x embd]), plus logits rows, plus the
        // attention score matrix when flash attention is off (heads x ctx x ubatch fp32).
        var compute = ubatch * embd * 4L * 12 + arch.vocabSize.coerceAtLeast(32000).toLong() * 4L * 8
        if (!flashAttention) compute += arch.heads.coerceAtLeast(1).toLong() * ctx * ubatch * 4L
        return MemoryEstimate(arch.fileBytes, kvBytes, compute, OVERHEAD)
    }

    fun plan(totalRamBytes: Long, arch: ModelArch?, ubatch: Int = 512): ContextPlan {
        val tier = tier(totalRamBytes)
        val budget = (totalRamBytes * APP_RAM_FRACTION).toLong()
        val trained = arch?.trainedContext?.takeIf { it > 0 } ?: 32768
        val options = STEPS.map { ctx ->
            val est = arch?.let { estimate(it, ctx, kvFor(totalRamBytes, it, ctx, ubatch), ubatch) }
            val yarn = ctx > trained
            val tooBig = est != null && est.totalBytes > budget
            val warning = when {
                yarn && ctx > trained * 4 -> "beyond 4x the trained context: not supported"
                tooBig -> "needs ~${gb(est!!.totalBytes)} — more than this phone can spare"
                yarn -> "experimental: YaRN rope scaling past the model's trained ${trained / 1024}K; slower and less accurate"
                ctx >= 32768 -> "large: first answer in a long chat can take minutes on a phone CPU"
                est != null && est.totalBytes > budget * 0.8 -> "tight on memory: other apps may be closed"
                else -> null
            }
            ContextOption(ctx, est, warning, yarn, blocked = tooBig || ctx > trained * 4)
        }
        var rec = defaultContext(tier).coerceAtMost(trained)
        // Never recommend something that does not fit.
        while (rec > 2048 && options.firstOrNull { it.tokens == rec }?.blocked == true) rec /= 2
        val kvDefault = arch?.let { kvFor(totalRamBytes, it, rec, ubatch) } ?: KvCacheType.F16
        return ContextPlan(tier, rec, kvDefault, maxOutputTokens(tier), options)
    }

    /**
     * "Auto" KV precision. Phase 4: always f16 — the most-tested llama.cpp configuration and the fastest prompt reading
     * on phone CPUs. Quantised KV (q8_0/q4_0, which force flash attention) is an explicit user choice only. When f16
     * does not fit, [fitContext] lowers the window instead of silently switching precision.
     */
    @Suppress("UNUSED_PARAMETER")
    fun kvFor(totalRamBytes: Long, arch: ModelArch, ctx: Int, ubatch: Int = 512): KvCacheType = KvCacheType.F16

    /** Largest window ≤ [ctx] (halving, min 2048) whose estimate fits the app's RAM budget with [kv]. */
    fun fitContext(totalRamBytes: Long, arch: ModelArch?, ctx: Int, kv: KvCacheType, ubatch: Int = 512, flashAttention: Boolean = kv != KvCacheType.F16): Int {
        if (arch == null) return ctx
        val budget = totalRamBytes * APP_RAM_FRACTION
        var c = ctx
        while (c > 2048 && estimate(arch, c, kv, ubatch, flashAttention).totalBytes > budget) c /= 2
        return c
    }

    /** Context sizes above this need the user's opt-in. */
    const val LARGE_CONTEXT = 8192

    fun gb(b: Long) = "%.1f GB".format(b / (1024.0 * 1024 * 1024))

    const val APP_RAM_FRACTION = 0.55
    const val F16_HEADROOM = 0.8
    const val OVERHEAD = 180L * 1024 * 1024
}
