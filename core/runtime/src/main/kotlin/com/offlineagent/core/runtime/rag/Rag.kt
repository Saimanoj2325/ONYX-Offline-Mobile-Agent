package com.offlineagent.core.runtime.rag

import kotlin.math.ln
import kotlin.math.sqrt

data class TextChunk(val index: Int, val text: String, val startChar: Int, val endChar: Int)

/**
 * Splits document text into overlapping chunks along natural boundaries (paragraph > sentence > word), so that a
 * retrieved passage reads well and every sentence appears whole in at least one chunk.
 */
object Chunker {
    fun chunk(text: String, maxChars: Int = 900, overlapChars: Int = 150): List<TextChunk> {
        require(maxChars >= 100) { "maxChars too small" }
        require(overlapChars in 0 until maxChars / 2) { "overlap must be < maxChars/2" }
        val clean = normalize(text)
        if (clean.isBlank()) return emptyList()
        val out = mutableListOf<TextChunk>()
        var start = 0
        while (start < clean.length) {
            var end = minOf(clean.length, start + maxChars)
            if (end < clean.length) end = boundary(clean, start, end)
            val piece = clean.substring(start, end).trim()
            if (piece.isNotEmpty()) out += TextChunk(out.size, piece, start, end)
            if (end >= clean.length) break
            // step back by the overlap, then forward to a word boundary so chunks never start mid-word
            var next = maxOf(start + 1, end - overlapChars)
            while (next < end && !clean[next - 1].isWhitespace()) next++
            start = next
        }
        return out
    }

    /** Collapses PDF line-wrapping noise: hyphenated breaks, single newlines inside paragraphs, runs of spaces. */
    fun normalize(text: String): String = text
        .replace("\r\n", "\n").replace('\u00A0', ' ').replace('\t', ' ')
        .replace(Regex("(\\w)-\\n(\\w)"), "$1$2")
        .replace(Regex("[ ]{2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun boundary(s: String, start: Int, end: Int): Int {
        val minEnd = start + (end - start) / 2
        s.lastIndexOf("\n\n", end).takeIf { it >= minEnd }?.let { return it + 2 }
        val sentence = Regex("[.!?।](\\s)").findAll(s.substring(minEnd, end)).lastOrNull()
        if (sentence != null) return minEnd + sentence.range.first + 1
        s.lastIndexOf('\n', end).takeIf { it >= minEnd }?.let { return it + 1 }
        s.lastIndexOf(' ', end).takeIf { it >= minEnd }?.let { return it + 1 }
        return end
    }
}

object VectorMath {
    fun dot(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "dimension mismatch ${a.size} vs ${b.size}" }
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        val d = dot(a, b)
        val na = sqrt(dot(a, a)); val nb = sqrt(dot(b, b))
        return if (na == 0f || nb == 0f) 0f else d / (na * nb)
    }

    fun normalize(v: FloatArray): FloatArray {
        val n = sqrt(dot(v, v))
        return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
    }

    fun toBytes(v: FloatArray): ByteArray {
        val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }

    /** Brute-force top-k by cosine. Fine for a few thousand chunks (≈1 ms per 1000 × 384-d on a phone). */
    fun <T> topK(query: FloatArray, items: List<Pair<T, FloatArray>>, k: Int): List<Pair<T, Float>> {
        val q = normalize(query)
        return items.asSequence()
            .filter { it.second.size == q.size }
            .map { it.first to dot(q, normalize(it.second)) }
            .sortedByDescending { it.second }
            .take(k)
            .toList()
    }
}

/** Lightweight BM25-style keyword scoring used for the hybrid ranker and as the no-embedding-model fallback. */
object KeywordScorer {
    private val stop = setOf(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were", "be", "it", "this", "that",
        "with", "as", "by", "at", "from", "what", "which", "who", "how", "why", "when", "where", "do", "does", "did", "my",
        "me", "i", "you", "your", "about", "can", "could", "would", "should", "please", "tell", "explain", "give",
    )

    fun terms(text: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.filter { it.length > 1 && it !in stop }.toList()

    /** FTS4 MATCH expression (OR of quoted terms), or null if the query has no searchable terms. */
    fun ftsQuery(query: String): String? = terms(query).distinct().take(12).takeIf { it.isNotEmpty() }
        ?.joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"" }

    fun score(query: String, docs: List<String>): List<Float> {
        val q = terms(query).distinct()
        if (q.isEmpty() || docs.isEmpty()) return docs.map { 0f }
        val tokenized = docs.map { terms(it) }
        val avgLen = tokenized.map { it.size }.average().coerceAtLeast(1.0)
        val df = q.associateWith { t -> tokenized.count { t in it } }
        val n = docs.size
        return tokenized.map { toks ->
            val tf = toks.groupingBy { it }.eachCount()
            q.sumOf { t ->
                val f = tf[t] ?: 0
                if (f == 0) 0.0 else {
                    val idf = ln(1.0 + (n - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                    idf * (f * 2.2) / (f + 1.2 * (0.25 + 0.75 * toks.size / avgLen))
                }
            }.toFloat()
        }
    }
}

data class ChunkCandidate(
    val chunkId: Long,
    val documentId: Long,
    val documentTitle: String,
    val text: String,
    val embedding: FloatArray?,
)

data class RetrievedChunk(val candidate: ChunkCandidate, val score: Float, val vectorScore: Float, val keywordScore: Float)

/**
 * Hybrid retrieval: cosine similarity (when embeddings exist) blended with normalised keyword scores.
 * With no embedding model the keyword score alone is used.
 */
object HybridRanker {
    fun rank(query: String, queryEmbedding: FloatArray?, candidates: List<ChunkCandidate>, k: Int = 4, vectorWeight: Float = 0.7f): List<RetrievedChunk> {
        if (candidates.isEmpty()) return emptyList()
        val kw = KeywordScorer.score(query, candidates.map { it.documentTitle + " " + it.text })
        val maxKw = kw.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val useVec = queryEmbedding != null && candidates.any { it.embedding?.size == queryEmbedding.size }
        val q = queryEmbedding?.let { VectorMath.normalize(it) }
        return candidates.mapIndexed { i, c ->
            val v = if (useVec && c.embedding != null && c.embedding.size == q!!.size) VectorMath.cosine(q, c.embedding) else 0f
            val kn = kw[i] / maxKw
            val s = if (useVec) vectorWeight * v + (1 - vectorWeight) * kn else kn
            RetrievedChunk(c, s, v, kn)
        }.filter { it.score > 0.05f }.sortedByDescending { it.score }.take(k)
    }
}

/** Builds the grounded-answer context block and maps [n] citations back to chunks. */
object RagPrompt {
    fun context(chunks: List<RetrievedChunk>, maxCharsPerChunk: Int = 700): String = buildString {
        append("Use these passages from the user's documents. Cite them like [1] or [2] after the facts they support. ")
        append("If the passages do not contain the answer, say so.\n")
        chunks.forEachIndexed { i, c ->
            append("\n[${i + 1}] (").append(c.candidate.documentTitle).append(") ")
            append(c.candidate.text.take(maxCharsPerChunk).replace("\n", " "))
        }
    }

    /** Citation numbers actually used in [answer], in order of first appearance. */
    fun citedNumbers(answer: String, available: Int): List<Int> =
        Regex("\\[(\\d{1,2})]").findAll(answer).map { it.groupValues[1].toInt() }.filter { it in 1..available }.distinct().toList()
}
