package com.offlineagent.core.models

import java.net.URI
import java.net.URLDecoder

enum class ModelKind { CHAT, EMBEDDING, VISION }

/** One downloadable file (Hugging Face repo + path). */
data class CatalogFile(val repo: String, val file: String, val approxBytes: Long, val revision: String = "main") {
    val url: String get() = HfUrls.resolve(repo, file, revision)
}

/** A curated model: [files] = main GGUF first, then (vision) the mmproj projector. */
data class CatalogModel(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val files: List<CatalogFile>,
    /** Minimum phone RAM (GB, marketing number) we recommend it for. */
    val minRamGb: Int,
    val licence: String,
    val note: String,
    /** Phase 6: f16 KV-cache bytes per context token (from the model's config.json: attention layers × kv heads × head dim × 4). */
    val kvBytesPerToken: Long = 114_688,
    /** Phase 6: mixture-of-experts (only a fraction of the weights is read per token). */
    val moe: Boolean = false,
    /** Phase 6: recurrent/hybrid layers (conv / linear attention): KV rollback is impossible, so no speculative decoding. */
    val hybrid: Boolean = false,
    /** Phase 6: catalogue size class used by the device-tier picker. */
    val sizeClass: CatalogModel.SizeClass = CatalogModel.SizeClass.SMALL,
) {
    enum class SizeClass { TINY, SMALL, MEDIUM, LARGE }
    val totalBytes: Long get() = files.sumOf { it.approxBytes }
    val main: CatalogFile get() = files.first()
    val mmproj: CatalogFile? get() = files.getOrNull(1)
}

/** Curated catalogue (all files verified to exist on Hugging Face; sizes approximate, real size comes from HEAD). */
object ModelCatalog {
    private const val MB = 1024L * 1024
    // Phase 6: every chat entry below was re-verified on Hugging Face on 2026-11-03 via the repo tree API
    // (https://huggingface.co/api/models/<repo>/tree/main); approxBytes are the EXACT byte sizes listed there.
    // Q4_0 is preferred where available: on ARMv8.2 + dotprod CPUs (no i8mm, e.g. Snapdragon 782G) llama.cpp repacks it to
    // q4_0_4x4 for faster prompt processing; Q4_K_M also repacks (8x4) and is used where it is the established default.
    val QWEN3_17B = CatalogModel("qwen3-1.7b", "Qwen3 1.7B", ModelKind.CHAT,
        listOf(CatalogFile("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf", 1_107_409_472)), 6, "Apache-2.0",
        "Default agent model: reliable tool calls and planning. ~2.2 GB in use at 8K context.",
        kvBytesPerToken = 114_688, sizeClass = CatalogModel.SizeClass.SMALL)
    val QWEN3_4B = CatalogModel("qwen3-4b", "Qwen3 4B", ModelKind.CHAT,
        listOf(CatalogFile("Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf", 2497 * MB)), 10, "Apache-2.0",
        "Smartest; slower. For 12 GB+ phones.", kvBytesPerToken = 147_456, sizeClass = CatalogModel.SizeClass.MEDIUM)
    val QWEN3_06B = CatalogModel("qwen3-0.6b", "Qwen3 0.6B", ModelKind.CHAT,
        listOf(CatalogFile("Qwen/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q8_0.gguf", 639_446_688)), 3, "Apache-2.0",
        "Fast and small; weak at structured answers.", kvBytesPerToken = 114_688, sizeClass = CatalogModel.SizeClass.TINY)
    // Phase 5: more chat models for the device-tier recommender (files checked on Hugging Face, Nov 2026).
    val LLAMA32_3B = CatalogModel("llama3.2-3b", "Llama 3.2 3B Instruct", ModelKind.CHAT,
        listOf(CatalogFile("bartowski/Llama-3.2-3B-Instruct-GGUF", "Llama-3.2-3B-Instruct-Q4_K_M.gguf", 1926 * MB)), 8, "Llama 3.2 Community",
        "Good English writing and tool use; no thinking mode.", kvBytesPerToken = 114_688, sizeClass = CatalogModel.SizeClass.MEDIUM)
    val GEMMA3_1B = CatalogModel("gemma3-1b", "Gemma 3 1B", ModelKind.CHAT,
        listOf(CatalogFile("ggml-org/gemma-3-1b-it-GGUF", "gemma-3-1b-it-Q4_K_M.gguf", 769 * MB)), 4, "Gemma Terms",
        "Small and quick; fine for short answers on 4 GB phones.", kvBytesPerToken = 26_624, sizeClass = CatalogModel.SizeClass.TINY)
    // ---- Phase 6 (verified 2026-11-03, exact sizes)
    val QWEN35_08B = CatalogModel("qwen3.5-0.8b", "Qwen3.5 0.8B", ModelKind.CHAT,
        listOf(CatalogFile("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_0.gguf", 507_154_688)), 3, "Apache-2.0",
        "Tiny hybrid model (linear attention): very small KV cache, good for 4 GB phones.",
        kvBytesPerToken = 12_288, hybrid = true, sizeClass = CatalogModel.SizeClass.TINY)
    val LFM25_12B = CatalogModel("lfm2.5-1.2b", "LFM2.5 1.2B Instruct", ModelKind.CHAT,
        listOf(CatalogFile("LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LFM2.5-1.2B-Instruct-Q4_0.gguf", 695_751_488)), 4, "LFM Open License v1.0",
        "Liquid's fast on-device model (conv + attention); tool calling; low RAM.",
        kvBytesPerToken = 12_288, hybrid = true, sizeClass = CatalogModel.SizeClass.TINY)
    val QWEN35_2B = CatalogModel("qwen3.5-2b", "Qwen3.5 2B", ModelKind.CHAT,
        listOf(CatalogFile("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B-Q4_0.gguf", 1_214_873_856)), 6, "Apache-2.0",
        "Hybrid model with a tiny KV cache: long chats on 6–8 GB phones.",
        kvBytesPerToken = 12_288, hybrid = true, sizeClass = CatalogModel.SizeClass.SMALL)
    val QWEN3_4B_2507 = CatalogModel("qwen3-4b-2507", "Qwen3 4B Instruct 2507", ModelKind.CHAT,
        listOf(CatalogFile("unsloth/Qwen3-4B-Instruct-2507-GGUF", "Qwen3-4B-Instruct-2507-Q4_0.gguf", 2_375_773_280)), 8, "Apache-2.0",
        "Stronger non-thinking 4B for writing and tools; ~3 GB in use at 4K. Needs ~4 GB free RAM.",
        kvBytesPerToken = 147_456, sizeClass = CatalogModel.SizeClass.MEDIUM)
    val QWEN35_4B = CatalogModel("qwen3.5-4b", "Qwen3.5 4B", ModelKind.CHAT,
        listOf(CatalogFile("unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_0.gguf", 2_583_221_408)), 8, "Apache-2.0",
        "Hybrid 4B with a small KV cache: quality mode for long context. Needs ~4 GB free RAM.",
        kvBytesPerToken = 32_768, hybrid = true, sizeClass = CatalogModel.SizeClass.MEDIUM)
    val GEMMA4_E2B = CatalogModel("gemma4-e2b", "Gemma 4 E2B", ModelKind.CHAT,
        listOf(CatalogFile("ggml-org/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_0.gguf", 2_841_481_184)), 8, "Gemma Terms",
        "Google's on-device model (text). Per-layer embeddings are read sparsely. Needs ~4 GB free RAM.",
        kvBytesPerToken = 14_336, sizeClass = CatalogModel.SizeClass.MEDIUM)
    val LFM25_8B_A1B = CatalogModel("lfm2.5-8b-a1b", "LFM2.5 8B-A1B (MoE)", ModelKind.CHAT,
        listOf(CatalogFile("LiquidAI/LFM2.5-8B-A1B-GGUF", "LFM2.5-8B-A1B-Q4_0.gguf", 4_844_678_368)), 12, "LFM Open License v1.0",
        "Big-model quality at ~1.5B active params. Only when ~6.5 GB RAM is free (12 GB phones).",
        kvBytesPerToken = 12_288, moe = true, hybrid = true, sizeClass = CatalogModel.SizeClass.LARGE)
    val BGE_SMALL = CatalogModel("bge-small-en", "bge-small-en v1.5 (embeddings)", ModelKind.EMBEDDING,
        listOf(CatalogFile("CompendiumLabs/bge-small-en-v1.5-gguf", "bge-small-en-v1.5-q8_0.gguf", 36 * MB)), 2, "MIT",
        "Needed to search your documents (RAG).")
    val SMOLVLM = CatalogModel("smolvlm2-256m", "SmolVLM2 256M (vision)", ModelKind.VISION,
        listOf(CatalogFile("ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "SmolVLM2-256M-Video-Instruct-Q8_0.gguf", 175 * MB),
            CatalogFile("ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "mmproj-SmolVLM2-256M-Video-Instruct-Q8_0.gguf", 104 * MB)), 4, "Apache-2.0",
        "Tiny photo understanding.")
    val QWEN3_VL_2B = CatalogModel("qwen3-vl-2b", "Qwen3-VL 2B (vision)", ModelKind.VISION,
        listOf(CatalogFile("Qwen/Qwen3-VL-2B-Instruct-GGUF", "Qwen3VL-2B-Instruct-Q4_K_M.gguf", 1107 * MB),
            CatalogFile("Qwen/Qwen3-VL-2B-Instruct-GGUF", "mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf", 445 * MB)), 6, "Apache-2.0",
        "Reads text and charts in photos.")

    val all = listOf(QWEN3_17B, QWEN3_4B, QWEN3_06B, LLAMA32_3B, GEMMA3_1B, QWEN35_08B, LFM25_12B, QWEN35_2B, QWEN3_4B_2507, QWEN35_4B,
        GEMMA4_E2B, LFM25_8B_A1B, BGE_SMALL, SMOLVLM, QWEN3_VL_2B)

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** The one chat model the first-launch sheet recommends for a phone with [totalRamBytes]. */
    fun recommendChat(totalRamBytes: Long): CatalogModel {
        val gb = totalRamBytes / (1024.0 * 1024 * 1024)
        // Phones report less than the marketing number (8 GB → ~7.3).
        return when {
            gb < 5.0 -> QWEN3_06B
            else -> QWEN3_17B // also on 12 GB+: fast, and the 4B is one tap away
        }
    }
}

/** A parsed Hugging Face file reference. */
data class HfRef(val repo: String, val revision: String, val path: String) {
    val url: String get() = HfUrls.resolve(repo, path, revision)
    val fileName: String get() = path.substringAfterLast('/')
}

object HfUrls {
    fun resolve(repo: String, file: String, revision: String = "main"): String =
        "https://huggingface.co/$repo/resolve/$revision/" + file.split('/').joinToString("/") { enc(it) }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Accepts a pasted Hugging Face file link — `/resolve/<rev>/<path>` or the web page `/blob/<rev>/<path>`, on
     * huggingface.co or hf.co, with or without `?download=true` — and returns the repo/revision/path, or null.
     */
    fun parse(input: String): HfRef? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host != "huggingface.co" && host != "hf.co" && host != "www.huggingface.co") return null
        val parts = (uri.rawPath ?: return null).trim('/').split('/').map { URLDecoder.decode(it, "UTF-8") }
        // <owner>/<name>/(resolve|blob)/<rev>/<path...>
        if (parts.size < 5 || (parts[2] != "resolve" && parts[2] != "blob")) return null
        val path = parts.drop(4).joinToString("/")
        if (!path.lowercase().endsWith(".gguf")) return null
        return HfRef("${parts[0]}/${parts[1]}", parts[3], path)
    }

    /** Hosts the downloader may contact: Hugging Face and its CDN/Xet storage. Nothing else, ever. */
    fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "huggingface.co" || h == "hf.co" || h.endsWith(".huggingface.co") || h.endsWith(".hf.co")
    }
}
