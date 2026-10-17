package com.offlineagent.core.inference

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

class GgufAndContextPlannerTest {
    /** Writes a tiny GGUF v3 header with the keys a Qwen3-1.7B file has (no tensors). */
    private fun fakeGguf(): File {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(s: String) { val b = s.toByteArray(); u64(b.size.toLong()); out.write(b) }
        out.write("GGUF".toByteArray()); u32(3); u64(0)
        val kvs = listOf<Pair<String, Any>>(
            "general.architecture" to "qwen3", "qwen3.block_count" to 28, "qwen3.context_length" to 40960, "qwen3.embedding_length" to 2048,
            "qwen3.attention.head_count" to 16, "qwen3.attention.head_count_kv" to 8, "qwen3.attention.key_length" to 128,
            "qwen3.attention.value_length" to 128, "general.flag" to true, "tokenizer.ggml.tokens" to listOf("a", "bb", "ccc"),
        )
        u64(kvs.size.toLong())
        for ((k, v) in kvs) {
            str(k)
            when (v) {
                is String -> { u32(8); str(v) }
                is Int -> { u32(4); u32(v) }
                is Boolean -> { u32(7); out.write(if (v) 1 else 0) }
                is List<*> -> { u32(9); u32(8); u64(v.size.toLong()); v.forEach { str(it as String) } }
            }
        }
        return File.createTempFile("fake", ".gguf").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    @Test fun readsArchitectureFromGgufHeader() {
        val a = GgufMetadata.arch(fakeGguf())!!
        assertEquals("qwen3", a.architecture)
        assertEquals(28, a.layers); assertEquals(8, a.kvHeads); assertEquals(128, a.keyLength); assertEquals(40960, a.trainedContext)
        assertEquals(3, a.vocabSize)
        // Qwen3-1.7B: 28 layers * 8 kv heads * (128+128) * 2 bytes = 114688 B/token at f16
        assertEquals(114688.0, a.kvBytesPerToken(KvCacheType.F16), 0.1)
        assertEquals(114688.0 * 34 / 64, a.kvBytesPerToken(KvCacheType.Q8_0), 0.1)
        assertNull(GgufMetadata.arch(File.createTempFile("notgguf", ".bin").apply { writeText("nope"); deleteOnExit() }))
    }

    private val qwen17 = ModelArch("qwen3", 28, 8, 16, 2048, 128, 128, 40960, 151936, 1_100_000_000L)

    @Test fun ramTiersPickContextDefaults() {
        val gb = 1024L * 1024 * 1024
        assertEquals(RamTier.LARGE, ContextPlanner.tier((7.4 * gb).toLong()))
        assertEquals(RamTier.MEDIUM, ContextPlanner.tier((5.6 * gb).toLong()))
        assertEquals(RamTier.SMALL, ContextPlanner.tier((3.7 * gb).toLong()))
        val p8 = ContextPlanner.plan((7.4 * gb).toLong(), qwen17)
        // Phase 4 (P0): 8K + f16 KV on 8 GB phones (and on 12 GB+); larger windows are opt-in.
        assertEquals(8192, p8.recommendedContext)
        assertEquals(KvCacheType.F16, p8.recommendedKv)
        assertEquals(KvCacheType.F16, ContextPlanner.kvFor((7.4 * gb).toLong(), qwen17, 32768))
        assertEquals(8192, ContextPlanner.plan((12.0 * gb).toLong(), qwen17).recommendedContext)
        assertEquals(8192, p8.maxOutputTokens)
        val o32 = p8.options.first { it.tokens == 32768 }
        assertNotNull(o32.warning); assertFalse(o32.needsYarn) // f16 32K is blocked or flagged on 8 GB
        val o64 = p8.options.first { it.tokens == 65536 }
        assertTrue(o64.needsYarn); assertTrue(o64.warning!!.contains("experimental") || o64.blocked)
        assertEquals(4096, ContextPlanner.plan((5.6 * gb).toLong(), qwen17).recommendedContext)
        assertEquals(4096, ContextPlanner.plan((3.7 * gb).toLong(), qwen17).recommendedContext)
    }

    @Test fun memoryEstimateGrowsWithContextAndShrinksWithQuantisedKv() {
        val e16 = ContextPlanner.estimate(qwen17, 16384, KvCacheType.F16, flashAttention = true)
        val e8 = ContextPlanner.estimate(qwen17, 16384, KvCacheType.Q8_0)
        val e4 = ContextPlanner.estimate(qwen17, 16384, KvCacheType.Q4_0)
        assertTrue(e16.kvBytes > e8.kvBytes && e8.kvBytes > e4.kvBytes)
        // 16K q8_0 KV for Qwen3-1.7B ~ 0.93 GiB
        assertEquals(0.93, e8.kvBytes / (1024.0 * 1024 * 1024), 0.02)
        val noFa = ContextPlanner.estimate(qwen17, 16384, KvCacheType.F16, flashAttention = false)
        assertTrue(noFa.computeBytes > e16.computeBytes)
    }

    @Test fun performanceCoreIds() {
        // 2x X3 @3.2 + 3x A715 @2.8 + 3x A510 @2.0
        val f = listOf(2000000L, 2000000, 2000000, 2800000, 2800000, 2800000, 3200000, 3200000)
        assertEquals(listOf(3, 4, 5, 6, 7), ThreadPolicy.performanceCoreIds(f))
        assertTrue(ThreadPolicy.performanceCoreIds(List(8) { 2000000L }).isEmpty())
        assertTrue(ThreadPolicy.performanceCoreIds(emptyList()).isEmpty())
    }

    @Test fun loadConfigAllowsLongContext() {
        LoadConfig("/x", contextLength = 131072, kvCacheType = KvCacheType.Q4_0, ropeScaling = RopeScaling.YARN)
        try { LoadConfig("/x", contextLength = 262144); throw AssertionError("accepted") } catch (e: IllegalArgumentException) { }
    }
    @Test fun autoKvIsAlwaysF16AndContextIsLoweredToFit() {
        val gb = 1024L * 1024 * 1024
        val qwen4b = ModelArch("qwen3", 36, 8, 32, 2560, 128, 128, 40960, 151936, 2_500_000_000L)
        assertEquals(KvCacheType.F16, ContextPlanner.kvFor((7.4 * gb).toLong(), qwen4b, 16384))
        assertEquals(KvCacheType.F16, ContextPlanner.plan((7.4 * gb).toLong(), qwen4b).recommendedKv)
        assertEquals(KvCacheType.F16, ContextPlanner.kvFor((2.8 * gb).toLong(), qwen17, 2048))
        // 32K f16 for a 4B model does not fit an 8 GB phone's budget: the window is halved until it does.
        val fit = ContextPlanner.fitContext((7.4 * gb).toLong(), qwen4b, 32768, KvCacheType.F16)
        assertTrue(fit in 2048..16384)
        assertEquals(8192, ContextPlanner.fitContext((7.4 * gb).toLong(), qwen17, 8192, KvCacheType.F16))
        // Mixed K/V types are accounted separately.
        assertEquals(114688.0, qwen17.kvBytesPerToken(KvCacheType.F16), 0.1)
        assertTrue(qwen17.kvBytesPerToken(KvCacheType.Q4_0) < qwen17.kvBytesPerToken(KvCacheType.Q8_0))
    }
}

class AdapterPhase3Test {
    private class PrefixEngine : TokenEngine {
        val keeps = mutableListOf<Int>()
        val prefixes = mutableListOf<Pair<String, String?>>()
        val prompts = mutableListOf<String>()
        var tokenLimit = Int.MAX_VALUE
        override fun load(config: LoadConfig) = ModelInfo(config.modelPath, "qwen3", 1, 1, 40960, config.contextLength, 4, "<|im_start|><|im_end|><think>", "CPU")
        override fun beginCompletion(prompt: String, sampling: SamplingParams, maxNewTokens: Int, grammar: String?, images: List<ImageData>) = error("old overload used")
        override fun beginCompletion(prompt: String, sampling: SamplingParams, maxNewTokens: Int, grammar: String?, images: List<ImageData>, nKeep: Int): Int {
            keeps += nKeep; prompts += prompt; return prompt.length / 4
        }
        override fun countTokens(text: String) = text.length / 4
        override fun prefixCache(prefix: String, cacheFile: String?): Int { prefixes += prefix to cacheFile; return 1 }
        override fun runtimeStats() = intArrayOf(120, 4096, 1, 30, 21, 120)
        private var n = 0
        override fun nextToken(): TokenStep = if (n++ < 3) TokenStep.Piece("x".toByteArray()) else TokenStep.End(StopReason.END_OF_TURN)
        override fun requestAbort() {}
        override fun unload() {}
        override fun systemInfo() = ""
    }

    @Test fun passesKeepAndUsesPrefixCacheAndReportsStats() = runBlocking {
        val engine = PrefixEngine()
        val disp = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val a = EngineInferenceAdapter(engine, disp, prefixCacheFile = { _, p -> "/cache/${p.hashCode()}.kv" })
        a.load(LoadConfig("/m.gguf", contextLength = 4096))
        val sys = "S".repeat(400) // ~100 tokens > PREFIX_CACHE_MIN_TOKENS
        val evs = a.generate(GenerationRequest(listOf(ChatMessage(Role.SYSTEM, sys), ChatMessage(Role.USER, "hi")))).toList()
        val done = evs.filterIsInstance<GenerationEvent.Completed>().single().stats
        val prefix = engine.prefixes.single().first
        assertTrue(engine.prompts.single().startsWith(prefix))
        assertEquals(prefix.length / 4, engine.keeps.single())
        assertEquals(1, done.prefixCache)
        assertEquals(1, done.contextShifts)
        assertEquals(0.7, done.acceptanceRate, 1e-9)
        assertEquals(120, done.contextUsed)
        // short system prompts skip the disk cache
        a.generate(GenerationRequest(listOf(ChatMessage(Role.SYSTEM, "tiny"), ChatMessage(Role.USER, "hi")), cachePrefix = true)).toList()
        assertEquals(1, engine.prefixes.size)
        disp.close()
    }

    @Test fun exactTokenFitDropsOldestTurns() {
        val msgs = listOf(ChatMessage(Role.SYSTEM, "s")) + (1..20).flatMap { listOf(ChatMessage(Role.USER, "u$it " + "w".repeat(100)), ChatMessage(Role.ASSISTANT, "a$it")) } + ChatMessage(Role.USER, "last")
        val fitted = PromptFitter.fitByTokens(msgs, contextLength = 600, maxNewTokens = 100) { m -> m.sumOf { it.content.length } / 4 }
        assertTrue(fitted.size < msgs.size)
        assertEquals(Role.SYSTEM, fitted.first().role)
        assertEquals("last", fitted.last().content)
        assertTrue(fitted.sumOf { it.content.length } / 4 <= 600 - 100 - 16)
        assertEquals(msgs, PromptFitter.fitByTokens(msgs, 600, 100) { null })
    }

    @Test fun chatMlSystemPrefixMatchesRenderedPrompt() {
        val m = listOf(ChatMessage(Role.SYSTEM, "You are X."), ChatMessage(Role.USER, "hello"))
        val p = Qwen3ChatTemplate.systemPrefix(m)!!
        assertTrue(Qwen3ChatTemplate.render(m, false).startsWith(p))
        assertNull(Qwen3ChatTemplate.systemPrefix(listOf(ChatMessage(Role.USER, "x"))))
    }

}
