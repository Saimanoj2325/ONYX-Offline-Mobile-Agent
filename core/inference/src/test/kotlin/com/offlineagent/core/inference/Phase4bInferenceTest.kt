package com.offlineagent.core.inference

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/** Phase 4b: GGUF reader (ANR fix), think-splitting while streaming, prefill progress events. */
class Phase4bInferenceTest {

    /** A GGUF header shaped like Qwen3's: architecture keys, then a huge vocabulary + merges, then more keys. */
    private fun bigGguf(vocab: Int = 160_000, archFirst: Boolean = true): File {
        val out = ByteArrayOutputStream(8 shl 20)
        val b4 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        val b8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun u32(v: Int) { b4.clear(); out.write(b4.putInt(v).array()) }
        fun u64(v: Long) { b8.clear(); out.write(b8.putLong(v).array()) }
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        out.write("GGUF".toByteArray()); u32(3); u64(0)
        val archKeys = listOf("general.architecture" to "qwen3", "qwen3.block_count" to 36, "qwen3.context_length" to 40960, "qwen3.embedding_length" to 2560,
            "qwen3.attention.head_count" to 32, "qwen3.attention.head_count_kv" to 8, "qwen3.attention.key_length" to 128, "qwen3.attention.value_length" to 128)
        val tail = listOf("tokenizer.ggml.bos_token_id" to 151643, "general.file_type" to 15)
        u64((archKeys.size + 3 + tail.size).toLong())
        fun kv(k: String, v: Any) { str(k); when (v) { is String -> { u32(8); str(v) }; is Int -> { u32(4); u32(v) } } }
        fun arrays() {
            str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(vocab.toLong()); repeat(vocab) { str("tok$it") }
            str("tokenizer.ggml.token_type"); u32(9); u32(5); u64(vocab.toLong()); repeat(vocab) { u32(1) }
            str("tokenizer.ggml.merges"); u32(9); u32(8); u64(vocab.toLong()); repeat(vocab) { str("a b$it") }
        }
        if (archFirst) { archKeys.forEach { (k, v) -> kv(k, v) }; arrays() } else { arrays(); archKeys.forEach { (k, v) -> kv(k, v) } }
        tail.forEach { (k, v) -> kv(k, v) }
        return File.createTempFile("big", ".gguf").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    @Test fun ggufArchStopsBeforeTheVocabularyAndIsCached() {
        val f = bigGguf()
        GgufMetadata.clearCache()
        val before = GgufMetadata.parses
        val t0 = System.nanoTime()
        val a = GgufMetadata.arch(f)!!
        val firstMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("qwen3", a.architecture); assertEquals(36, a.layers); assertEquals(8, a.kvHeads); assertEquals(160_000, a.vocabSize)
        assertTrue("arch() took $firstMs ms", firstMs < 1500)
        repeat(5) { assertEquals(a, GgufMetadata.arch(f)) }
        assertEquals("cached per path/size/mtime", before + 1, GgufMetadata.parses)
        // A changed file (new mtime/size) is read again.
        f.appendBytes(ByteArray(16)); f.setLastModified(f.lastModified() + 2000)
        GgufMetadata.arch(f)
        assertEquals(before + 2, GgufMetadata.parses)
    }

    @Test fun ggufBufferedReaderSkipsHugeArraysCorrectly() {
        // Keys after the arrays are only reachable if every string/array was skipped exactly.
        val f = bigGguf(vocab = 50_000, archFirst = false)
        val m = GgufMetadata.read(f)
        assertEquals(50_000L, m["tokenizer.ggml.tokens.length"])
        assertEquals(50_000L, m["tokenizer.ggml.merges.length"])
        assertEquals(151643L, m["tokenizer.ggml.bos_token_id"])
        assertEquals(15L, m["general.file_type"])
        GgufMetadata.clearCache()
        assertEquals(36, GgufMetadata.arch(f)!!.layers) // architecture after the vocabulary also works
    }

    @Test fun ggufTruncatedOrInvalidFilesReturnNull() {
        val f = bigGguf(vocab = 1000)
        val cut = File.createTempFile("cut", ".gguf").apply { writeBytes(f.readBytes().copyOf(200)); deleteOnExit() }
        GgufMetadata.clearCache()
        assertNull(GgufMetadata.arch(cut))
        assertNull(GgufMetadata.arch(File("/nonexistent/x.gguf")))
    }

    @Test fun thinkSplitterStreamsReasoningWithoutHalfTags() {
        val s = ThinkSplitter()
        var p = s.append("<think>\nI need 17*23")
        assertTrue(p.thinkingInProgress); assertEquals("I need 17*23", p.reasoning); assertEquals("", p.answer)
        p = s.append(" = 391.</thi")
        assertTrue(p.thinkingInProgress)
        assertEquals("I need 17*23 = 391.", p.reasoning) // "</thi" held back, never flashes
        p = s.append("nk>\n\n**391**")
        assertFalse(p.thinkingInProgress); assertEquals("I need 17*23 = 391.", p.reasoning); assertEquals("**391**", p.answer)
        assertEquals("**391**", s.current().answer)
    }

    @Test fun thinkSplitterHandlesTemplatePrefilledThink() {
        // Templates that put "<think>" in the prompt: only the closing tag arrives.
        val p = ThinkSplitter.split("reasoning here</think>\n\nThe answer.")
        assertEquals("reasoning here", p.reasoning); assertEquals("The answer.", p.answer)
        // End of stream: nothing is held back (an answer that really ends in "</" stays complete).
        assertEquals("a < b and </", ThinkSplitter().apply { append("a < b and </") }.current().answer)
        assertEquals("a < b and ", ThinkSplitter().append("a < b and </").answer)
    }

    /** Fake engine whose prompt evaluation takes a while and reports progress like the native atomics. */
    private class SlowPrefillEngine : TokenEngine {
        @Volatile var progress = intArrayOf(0, 0, 0)
        private var left = 0
        override fun load(config: LoadConfig) = ModelInfo(path = config.modelPath, description = "fake", sizeBytes = 1, parameterCount = 1, trainedContextLength = 4096, contextLength = 4096, threads = 1, chatTemplate = null, backendInfo = "")
        override fun beginCompletion(prompt: String, sampling: SamplingParams, maxNewTokens: Int, grammar: String?, images: List<ImageData>): Int {
            val total = 600
            for (done in 0..total step 100) { progress = intArrayOf(done, total, 40); Thread.sleep(120) }
            left = 3
            return total + 40
        }
        override fun prefillProgress(): IntArray = progress
        override fun nextToken(): TokenStep = if (left-- > 0) TokenStep.Piece("hi ".toByteArray()) else TokenStep.End(StopReason.END_OF_TURN)
        override fun requestAbort() {}
        override fun unload() {}
        override fun systemInfo() = ""
    }

    @Test fun adapterEmitsPrefillProgressBeforeTheFirstToken() = runBlocking {
        val engine = SlowPrefillEngine()
        val exec = Executors.newSingleThreadExecutor()
        val adapter = EngineInferenceAdapter(engine, exec.asCoroutineDispatcher())
        adapter.load(LoadConfig("x.gguf"))
        val events = adapter.generate(GenerationRequest(listOf(ChatMessage(Role.USER, "hello")), cachePrefix = false)).toList()
        exec.shutdown()
        val progress = events.filterIsInstance<GenerationEvent.PromptProgress>()
        assertTrue("got ${progress.size} progress events", progress.size >= 2)
        assertTrue(progress.all { it.total == 600 && it.reused == 40 })
        assertEquals(progress.map { it.done }.sorted(), progress.map { it.done }) // monotonic
        val firstToken = events.indexOfFirst { it is GenerationEvent.Token }
        assertTrue(events.indexOfLast { it is GenerationEvent.PromptProgress } < firstToken)
        val done = events.last() as GenerationEvent.Completed
        assertEquals(40, done.stats.reusedTokens)
        assertTrue(done.stats.firstTokenMillis > 0)
    }
}
