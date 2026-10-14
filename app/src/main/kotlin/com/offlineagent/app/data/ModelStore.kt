package com.offlineagent.app.data

import android.net.Uri
import com.offlineagent.core.data.MiscDao
import com.offlineagent.core.data.ModelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelRole(val label: String, val hint: String) {
    CHAT("Chat", "Answers, Canvas UI and agent tool calls"),
    EMBEDDING("Embedding", "Turns documents into vectors for search (RAG)"),
    VISION("Vision", "Understands attached photos (needs a matching mmproj file)"),
}

/** Multiple local models with roles (chat / embedding / vision). One active model per role. */
class ModelStore(private val dao: MiscDao, private val importer: ModelRepository) {
    val models: Flow<List<ModelEntity>> = dao.observeModels()

    suspend fun all(): List<ModelEntity> = dao.models()
    suspend fun active(role: ModelRole): ModelEntity? =
        dao.models().firstOrNull { it.role == role.name && it.active && File(it.path).isFile }

    /** Moves the Phase 1 single model (SharedPreferences) into the multi-model table once. */
    suspend fun migrateLegacy() {
        if (dao.models().isNotEmpty()) return
        val legacy = importer.current() ?: return
        dao.insertModel(ModelEntity(role = ModelRole.CHAT.name, name = legacy.displayName, path = legacy.file.absolutePath,
            sizeBytes = legacy.sizeBytes, active = true, addedAt = System.currentTimeMillis()))
    }

    suspend fun import(uri: Uri, role: ModelRole, onProgress: (Long, Long) -> Unit): ModelEntity {
        val dir = File(importer.root, role.name.lowercase())
        val stored = importer.importInto(uri, dir, keepOthers = true, onProgress)
        val existing = dao.models().firstOrNull { it.path == stored.file.absolutePath }
        if (existing != null) dao.deleteModel(existing.id)
        val id = dao.insertModel(ModelEntity(role = role.name, name = stored.displayName, path = stored.file.absolutePath,
            sizeBytes = stored.sizeBytes, active = false, addedAt = System.currentTimeMillis()))
        dao.activate(role.name, id)
        return dao.models().first { it.id == id }
    }

    /** Attaches a vision projector (mmproj GGUF) to a vision model. */
    suspend fun importMmproj(model: ModelEntity, uri: Uri, onProgress: (Long, Long) -> Unit): ModelEntity {
        val dir = File(importer.root, "mmproj")
        val stored = importer.importInto(uri, dir, keepOthers = true, onProgress)
        model.mmprojPath?.takeIf { it != stored.file.absolutePath }?.let { File(it).delete() }
        val updated = model.copy(mmprojPath = stored.file.absolutePath)
        dao.updateModel(updated)
        return updated
    }

    suspend fun activate(model: ModelEntity) = dao.activate(model.role, model.id)

    suspend fun delete(model: ModelEntity) = withContext(Dispatchers.IO) {
        File(model.path).delete()
        model.mmprojPath?.let { File(it).delete() }
        dao.deleteModel(model.id)
        if (model.active) dao.models().firstOrNull { it.role == model.role }?.let { dao.activate(it.role, it.id) }
    }
}

/** Recommendations shown in the app (and README). Sizes are approximate. */
object ModelAdvice {
    data class Rec(val role: ModelRole, val name: String, val file: String, val size: String, val ram: String, val url: String, val licence: String, val note: String)

    val recommendations = listOf(
        Rec(ModelRole.CHAT, "Qwen3-1.7B", "Qwen3-1.7B-Q4_K_M.gguf", "1.1 GB", "6 GB+ phone", "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF", "Apache-2.0",
            "Best balance for Canvas + tools on mid-range phones."),
        Rec(ModelRole.CHAT, "Qwen3-4B", "Qwen3-4B-Q4_K_M.gguf", "2.5 GB", "8 GB+ phone", "https://huggingface.co/Qwen/Qwen3-4B-GGUF", "Apache-2.0",
            "Most reliable structured output and planning; slower."),
        Rec(ModelRole.CHAT, "Qwen3-0.6B", "Qwen3-0.6B-Q8_0.gguf", "0.6 GB", "4 GB phone", "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF", "Apache-2.0",
            "Fast, but weak at structured output: canvases are simple and tool choice is shaky."),
        Rec(ModelRole.EMBEDDING, "bge-small-en-v1.5", "bge-small-en-v1.5-q8_0.gguf", "37 MB", "any", "https://huggingface.co/CompendiumLabs/bge-small-en-v1.5-gguf", "MIT",
            "Tiny and fast; English."),
        Rec(ModelRole.EMBEDDING, "nomic-embed-text-v1.5", "nomic-embed-text-v1.5.Q8_0.gguf", "146 MB", "any", "https://huggingface.co/nomic-ai/nomic-embed-text-v1.5-GGUF", "Apache-2.0",
            "Better English retrieval, 8k context."),
        Rec(ModelRole.EMBEDDING, "EmbeddingGemma-300M", "embeddinggemma-300M-Q8_0.gguf", "330 MB", "any", "https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF", "Gemma Terms of Use",
            "Multilingual (Hindi, Telugu…); licence is not OSI."),
        Rec(ModelRole.VISION, "SmolVLM2-256M", "SmolVLM2-256M-Video-Instruct-Q8_0.gguf + mmproj", "0.4 GB", "4 GB phone", "https://huggingface.co/ggml-org/SmolVLM2-256M-Video-Instruct-GGUF", "Apache-2.0",
            "Very fast, basic descriptions."),
        Rec(ModelRole.VISION, "Qwen3-VL-2B-Instruct", "Qwen3-VL-2B-Instruct-Q4_K_M.gguf + mmproj", "1.9 GB", "6 GB+ phone", "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF", "Apache-2.0",
            "Much better reading of text/charts in photos."),
    )

    /** Rough runtime RAM need: weights (mmap) + KV cache (f16) + ~150 MB overhead. */
    fun estimateRamBytes(modelBytes: Long, contextLength: Int, kvBytesPerToken: Long = 114_688): Long =
        modelBytes + contextLength * kvBytesPerToken + (150L shl 20)
}
