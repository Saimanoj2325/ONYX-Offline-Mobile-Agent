package com.offlineagent.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.offlineagent.core.data.ChunkEntity
import com.offlineagent.core.data.DocumentDao
import com.offlineagent.core.data.DocumentEntity
import com.offlineagent.core.inference.EmbeddingService
import com.offlineagent.core.runtime.rag.ChunkCandidate
import com.offlineagent.core.runtime.rag.Chunker
import com.offlineagent.core.runtime.rag.HybridRanker
import com.offlineagent.core.runtime.rag.KeywordScorer
import com.offlineagent.core.runtime.rag.RetrievedChunk
import com.offlineagent.core.runtime.rag.VectorMath
import com.offlineagent.engine.llamacpp.DeviceInfo
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class IndexProgress(val title: String, val phase: String, val done: Int, val total: Int) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Background document indexing: SAF import -> text extraction (PdfBox-Android for PDF; txt/md as UTF-8) ->
 * overlapping chunks -> on-device embeddings (if an embedding model is active) -> Room (+FTS4 via triggers).
 * Runs on IO/embedding threads, reports progress, can be cancelled. Without an embedding model the document is still
 * searchable by keywords.
 */
class DocumentIndexer(
    private val context: Context,
    private val dao: DocumentDao,
    private val embeddings: EmbeddingService,
    private val models: ModelStore,
    private val scope: CoroutineScope,
) {
    private val docsDir = File(context.filesDir, "docs").apply { mkdirs() }
    private val _progress = MutableStateFlow<IndexProgress?>(null)
    val progress: StateFlow<IndexProgress?> = _progress.asStateFlow()
    private var job: Job? = null

    val isBusy: Boolean get() = job?.isActive == true

    fun import(uris: List<Uri>, onDone: (String) -> Unit) {
        if (isBusy) return onDone("Indexing is already running")
        job = scope.launch(Dispatchers.IO) {
            var ok = 0
            val errors = mutableListOf<String>()
            for (uri in uris) {
                try { indexOne(uri); ok++ } catch (e: CancellationException) { throw e } catch (e: Throwable) { errors += (e.message ?: e.toString()) }
            }
            _progress.value = null
            onDone(if (errors.isEmpty()) "Indexed $ok document(s)" else "Indexed $ok, failed ${errors.size}: ${errors.first()}")
        }.also { j -> j.invokeOnCompletion { if (it is CancellationException) { _progress.value = null; onDone("Indexing cancelled") } } }
    }

    fun cancel() { job?.cancel() }

    private suspend fun indexOne(uri: Uri) {
        val resolver = context.contentResolver
        var name = "document"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { name = it } }
        val mime = resolver.getType(uri) ?: ""
        val isPdf = mime == "application/pdf" || name.endsWith(".pdf", true)
        val textFile = File(docsDir, "${System.currentTimeMillis()}.txt")
        val docId = dao.insertDocument(DocumentEntity(title = name, mime = if (isPdf) "application/pdf" else "text/plain", status = "INDEXING", textPath = textFile.absolutePath, createdAt = System.currentTimeMillis()))
        try {
            _progress.value = IndexProgress(name, "Extracting text", 0, 0)
            val text = if (isPdf) extractPdf(uri, name) else resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: throw IllegalStateException("Could not read $name")
            if (text.isBlank()) throw IllegalStateException("$name has no extractable text (scanned PDF? OCR is not supported yet)")
            textFile.writeText(text)
            val chunks = Chunker.chunk(text, maxChars = 900, overlapChars = 150)
            val embedModel = models.active(ModelRole.EMBEDDING)
            if (embedModel != null) embeddings.ensureLoaded(embedModel.path, DeviceInfo.recommendedThreads())
            val rows = ArrayList<ChunkEntity>(chunks.size)
            chunks.forEachIndexed { i, c ->
                currentCoroutineContext().ensureActive()
                _progress.value = IndexProgress(name, if (embedModel != null) "Embedding" else "Indexing", i, chunks.size)
                val vec = if (embedModel != null) VectorMath.toBytes(embeddings.embed(c.text)) else null
                rows += ChunkEntity(documentId = docId, idx = i, text = c.text, embedding = vec)
                if (rows.size >= 32) { dao.insertChunks(rows.toList()); rows.clear() }
            }
            if (rows.isNotEmpty()) dao.insertChunks(rows)
            dao.updateDocument(dao.document(docId)!!.copy(status = "READY", chars = text.length, chunkCount = chunks.size, embeddingModel = embedModel?.name))
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                dao.deleteChunks(docId)
                if (e is CancellationException) { dao.deleteDocument(docId); textFile.delete() }
                else dao.document(docId)?.let { dao.updateDocument(it.copy(status = "FAILED", error = e.message)) }
            }
            throw e
        }
    }

    private suspend fun extractPdf(uri: Uri, name: String): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val input = context.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open $name")
        return input.use { s ->
            PDDocument.load(s).use { doc ->
                val pages = doc.numberOfPages
                val stripper = PDFTextStripper()
                val sb = StringBuilder()
                for (p in 1..pages) {
                    currentCoroutineContext().ensureActive()
                    _progress.value = IndexProgress(name, "Reading PDF page $p/$pages", p - 1, pages)
                    stripper.startPage = p; stripper.endPage = p
                    sb.append(stripper.getText(doc)).append("\n\n")
                }
                sb.toString()
            }
        }
    }

    /** Re-embeds every document with the current embedding model (after the user switches models). */
    fun reembedAll(onDone: (String) -> Unit) {
        if (isBusy) return onDone("Indexing is already running")
        job = scope.launch(Dispatchers.IO) {
            val m = models.active(ModelRole.EMBEDDING) ?: return@launch onDone("No embedding model selected")
            embeddings.ensureLoaded(m.path, DeviceInfo.recommendedThreads())
            for (d in dao.documents().filter { it.status == "READY" }) {
                val text = File(d.textPath).takeIf { it.isFile }?.readText() ?: continue
                val chunks = Chunker.chunk(text, 900, 150)
                dao.deleteChunks(d.id)
                val rows = chunks.mapIndexed { i, c ->
                    currentCoroutineContext().ensureActive()
                    _progress.value = IndexProgress(d.title, "Re-embedding", i, chunks.size)
                    ChunkEntity(documentId = d.id, idx = i, text = c.text, embedding = VectorMath.toBytes(embeddings.embed(c.text)))
                }
                dao.insertChunks(rows)
                dao.updateDocument(d.copy(embeddingModel = m.name, chunkCount = chunks.size))
            }
            _progress.value = null
            onDone("Re-embedded all documents")
        }
    }

    suspend fun delete(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        dao.deleteDocument(doc.id)
        File(doc.textPath).delete()
    }
}

/** Hybrid (vector + FTS4 keyword) retrieval over Room. */
class Retriever(private val dao: DocumentDao, private val embeddings: EmbeddingService, private val models: ModelStore) {
    suspend fun hasDocuments(): Boolean = dao.documents().any { it.status == "READY" }

    suspend fun search(query: String, k: Int = 4): List<RetrievedChunk> = withContext(Dispatchers.IO) {
        val docs = dao.documents().filter { it.status == "READY" }.associateBy { it.id }
        if (docs.isEmpty()) return@withContext emptyList()
        val embedModel = models.active(ModelRole.EMBEDDING)
        val qVec = if (embedModel != null && docs.values.any { it.embeddingModel != null }) {
            runCatching { embeddings.ensureLoaded(embedModel.path, DeviceInfo.recommendedThreads()); embeddings.embed(query) }.getOrNull()
        } else null
        val candidates: List<ChunkEntity> = if (qVec != null) {
            dao.readyChunks() // brute force over all chunks: a few thousand vectors is ~ms on a phone
        } else {
            val match = KeywordScorer.ftsQuery(query) ?: return@withContext emptyList()
            runCatching { dao.keywordSearch(match, 200) }.getOrDefault(emptyList())
        }
        HybridRanker.rank(
            query, qVec,
            candidates.mapNotNull { c ->
                val d = docs[c.documentId] ?: return@mapNotNull null
                ChunkCandidate(c.id, c.documentId, d.title, c.text, c.embedding?.let { VectorMath.fromBytes(it) })
            },
            k = k,
        )
    }

    suspend fun documentText(titleOrId: String, maxChars: Int): Pair<DocumentEntity, String>? = withContext(Dispatchers.IO) {
        val docs = dao.documents().filter { it.status == "READY" }
        val d = docs.firstOrNull { it.id.toString() == titleOrId } ?: docs.firstOrNull { it.title.equals(titleOrId, true) }
            ?: docs.firstOrNull { it.title.contains(titleOrId, true) || titleOrId.contains(it.title.substringBeforeLast('.'), true) }
            ?: docs.maxByOrNull { d -> KeywordScorer.score(titleOrId, listOf(d.title)).first() }?.takeIf { KeywordScorer.score(titleOrId, listOf(it.title)).first() > 0f }
            ?: return@withContext null
        d to (File(d.textPath).takeIf { it.isFile }?.readText()?.take(maxChars) ?: "")
    }
}
