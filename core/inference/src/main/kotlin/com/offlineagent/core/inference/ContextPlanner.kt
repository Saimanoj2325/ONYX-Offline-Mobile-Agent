package com.offlineagent.core.inference

import java.io.File
import java.io.RandomAccessFile
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
        layers.toDouble() * kvHeads * (keyLength + valueLength) * type.bytesPerElement
}

/**
 * Minimal GGUF metadata reader (v2/v3, little-endian). Reads only the key/value header, never the tensors, so it is
 * instant even for multi-GB files. Pure Kotlin; used for memory estimates before a model is loaded.
 */
object GgufMetadata {
    private const val T_UINT8 = 0; private const val T_INT8 = 1; private const val T_UINT16 = 2; private const val T_INT16 = 3
    private const val T_UINT32 = 4; private const val T_INT32 = 5; private const val T_FLOAT32 = 6; private const val T_BOOL = 7
    private const val T_STRING = 8; private const val T_ARRAY = 9; private const val T_UINT64 = 10; private const val T_INT64 = 11
    private const val T_FLOAT64 = 12

    /** Returns scalar metadata (strings and numbers; arrays become their length under "<key>.length"). */
    fun read(file: File, maxKeys: Int = 512): Map<String, Any> = RandomAccessFile(file, "r").use { raf ->
        val r = Reader(raf)
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
                out["$key.length"] = n
                // Skip array payload.
                if (et == T_STRING) repeat(n.toInt()) { r.skip(r.u64()) } else r.skip(n * elementSize(et))
            } else {
                out[key] = r.value(type)
            }
        }
        out
    }

    fun arch(file: File): ModelArch? = runCatching {
        val m = read(file)
        val a = m["general.architecture"] as? String ?: return null
        fun int(k: String): Int = (m["$a.$k"] as? Number)?.toInt() ?: 0
        val heads = int("attention.head_count")
        val embd = int("embedding_length")
        val kl = int("attention.key_length").takeIf { it > 0 } ?: if (heads > 0) embd / heads else 0
        val vl = int("attention.value_length").takeIf { it > 0 } ?: kl
        ModelArch(
            architecture = a, layers = int("block_count"), kvHeads = int("attention.head_count_kv").takeIf { it > 0 } ?: heads,
            heads = heads, embedding = embd, keyLength = kl, valueLength = vl, trainedContext = int("context_length"),
            vocabSize = ((m["tokenizer.ggml.tokens.length"] as? Number)?.toInt() ?: 0), fileBytes = file.length(),
        )
    }.getOrNull()

    private fun elementSize(t: Int): Long = when (t) {
        T_UINT8, T_INT8, T_BOOL -> 1; T_UINT16, T_INT16 -> 2; T_UINT32, T_INT32, T_FLOAT32 -> 4
        T_UINT64, T_INT64, T_FLOAT64 -> 8; else -> throw IllegalArgumentException("bad GGUF array type $t")
    }

    private class Reader(val raf: RandomAccessFile) {
        private val b8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun bytes(n: Int): ByteArray = ByteArray(n).also { raf.readFully(it) }
        private fun le(n: Int): ByteBuffer { b8.clear(); raf.readFully(b8.array(), 0, n); b8.limit(n); return b8 }
        fun u32(): Long = le(4).int.toLong() and 0xFFFFFFFFL
        fun u64(): Long = le(8).long
        fun skip(n: Long) { raf.seek(raf.filePointer + n) }
        fun str(): String {
            val n = u64()
            require(n in 0..1_000_000) { "string too long" }
            return String(bytes(n.toInt()), Charsets.UTF_8)
        }
        fun value(t: Int): Any = when (t) {
            T_UINT8 -> raf.readUnsignedByte(); T_INT8 -> raf.readByte().toInt(); T_BOOL -> raf.readByte().toInt() != 0
            T_UINT16 -> le(2).short.toInt() and 0xFFFF; T_INT16 -> le(2).short.toInt()
            T_UINT32 -> u32(); T_INT32 -> le(4).int; T_FLOAT32 -> le(4).float
            T_UINT64, T_INT64 -> u64(); T_FLOAT64 -> le(8).double
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
 *  - 8 GB phones: 16K default (q8_0 KV), 32K allowed with a warning, 64K/128K (YaRN) experimental.
 *  - 6 GB: 8K default.  4 GB: 4K.  < 4 GB: 2K.
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
        RamTier.TINY -> 2048; RamTier.SMALL -> 4096; RamTier.MEDIUM -> 8192; RamTier.LARGE -> 16384; RamTier.XL -> 32768
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
        val kvDefault = if (tier == RamTier.TINY) KvCacheType.Q4_0 else KvCacheType.Q8_0
        val options = STEPS.map { ctx ->
            val est = arch?.let { estimate(it, ctx, kvDefault, ubatch) }
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
        return ContextPlan(tier, rec, kvDefault, maxOutputTokens(tier), options)
    }

    fun gb(b: Long) = "%.1f GB".format(b / (1024.0 * 1024 * 1024))

    const val APP_RAM_FRACTION = 0.55
    const val OVERHEAD = 180L * 1024 * 1024
}
