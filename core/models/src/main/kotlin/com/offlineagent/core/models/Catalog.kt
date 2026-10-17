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
) {
    val totalBytes: Long get() = files.sumOf { it.approxBytes }
    val main: CatalogFile get() = files.first()
    val mmproj: CatalogFile? get() = files.getOrNull(1)
}

/** Curated catalogue (all files verified to exist on Hugging Face; sizes approximate, real size comes from HEAD). */
object ModelCatalog {
    private const val MB = 1024L * 1024
    val QWEN3_17B = CatalogModel("qwen3-1.7b", "Qwen3 1.7B", ModelKind.CHAT,
        listOf(CatalogFile("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf", 1107 * MB)), 6, "Apache-2.0",
        "Best balance for phones: chat, rich UI and tools.")
    val QWEN3_4B = CatalogModel("qwen3-4b", "Qwen3 4B", ModelKind.CHAT,
        listOf(CatalogFile("Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf", 2497 * MB)), 10, "Apache-2.0",
        "Smartest; slower. For 12 GB+ phones.")
    val QWEN3_06B = CatalogModel("qwen3-0.6b", "Qwen3 0.6B", ModelKind.CHAT,
        listOf(CatalogFile("Qwen/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q8_0.gguf", 639 * MB)), 3, "Apache-2.0",
        "Fast and small; weak at structured answers.")
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

    val all = listOf(QWEN3_17B, QWEN3_4B, QWEN3_06B, BGE_SMALL, SMOLVLM, QWEN3_VL_2B)

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
