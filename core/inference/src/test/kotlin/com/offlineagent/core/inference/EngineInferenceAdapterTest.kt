package com.offlineagent.core.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Scriptable fake of the JNI engine. */
class FakeEngine(
    var tokens: List<String> = listOf("Hel", "lo", "!"),
    var failLoad: String? = null,
    var maxPromptChars: Int = Int.MAX_VALUE,
    var tokenDelayMs: Long = 0,
) : TokenEngine {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val prompts = mutableListOf<String>()
    val aborted = AtomicBoolean(false)
    var blockPrompt: CountDownLatch? = null
    private var index = 0
    var loaded = false

    private fun record(name: String) { calls += name; threads += Thread.currentThread().name }

    override fun load(config: LoadConfig): ModelInfo {
        record("load")
        failLoad?.let { throw ModelLoadException(it) }
        loaded = true
        return ModelInfo(config.modelPath, "qwen3 0.6B Q8_0", 600_000_000, 600_000_000, 40960, config.contextLength, config.threads, "<|im_start|>", "CPU")
    }

    override fun beginCompletion(prompt: String, sampling: SamplingParams, maxNewTokens: Int): Int {
        record("begin")
        check(loaded)
        aborted.set(false)
        prompts += prompt
        if (prompt.length > maxPromptChars) throw PromptTooLongException(prompt.length, maxPromptChars)
        blockPrompt?.let { latch ->
            // simulate a long prompt evaluation that only an abort can interrupt
            while (!aborted.get()) { if (latch.await(5, TimeUnit.MILLISECONDS)) break }
            if (aborted.get()) throw GenerationAbortedException()
        }
        index = 0
        return prompt.length / 4
    }

    override fun nextToken(): TokenStep {
        record("next")
        if (tokenDelayMs > 0) Thread.sleep(tokenDelayMs)
        if (aborted.get()) return TokenStep.End(StopReason.CANCELLED)
        if (index >= tokens.size) return TokenStep.End(StopReason.END_OF_TURN)
        return TokenStep.Piece(tokens[index++].toByteArray())
    }

    override fun requestAbort() { calls += "abort"; aborted.set(true) }
    override fun unload() { record("unload"); loaded = false }
    override fun systemInfo() = "NEON = 1"
}

@OptIn(ExperimentalCoroutinesApi::class)
class EngineInferenceAdapterTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    private val engineThread = executor.asCoroutineDispatcher()

    @After fun tearDown() { executor.shutdownNow() }

    private val user = listOf(ChatMessage(Role.USER, "hi"))

    @Test
    fun loadGenerateUnloadLifecycle() = runBlocking<Unit> {
        val engine = FakeEngine()
        val adapter = EngineInferenceAdapter(engine, engineThread)
        assertEquals(EngineState.Unloaded(), adapter.state.value)

        val info = adapter.load(LoadConfig("/m.gguf", threads = 4))
        assertEquals(EngineState.Ready(info), adapter.state.value)

        val events = adapter.generate(GenerationRequest(user)).toList()
        val text = events.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        assertEquals("Hello!", text)
        assertTrue(events.first() is GenerationEvent.PromptProcessed)
        val done = events.last() as GenerationEvent.Completed
        assertEquals(StopReason.END_OF_TURN, done.stats.stopReason)
        assertEquals(3, done.stats.generatedTokens)
        assertEquals(EngineState.Ready(info), adapter.state.value)
        assertTrue("thinking-off prompt", engine.prompts.single().endsWith("<think>\n\n</think>\n\n"))

        adapter.unload(UnloadReason.LOW_MEMORY)
        assertEquals(EngineState.Unloaded(UnloadReason.LOW_MEMORY), adapter.state.value)
        assertEquals(listOf("load", "begin", "next", "next", "next", "next", "unload"), engine.calls.toList())
    }

    @Test
    fun allEngineCallsRunOnTheDedicatedThread() = runBlocking<Unit> {
        val engine = FakeEngine()
        val adapter = EngineInferenceAdapter(engine, engineThread)
        adapter.load(LoadConfig("/m.gguf"))
        adapter.generate(GenerationRequest(user)).toList()
        adapter.systemInfo()
        adapter.unload()
        assertEquals(setOf("llm-engine"), engine.threads.map { it.substringBefore(" @") }.toSet())
    }

    @Test
    fun loadFailureMovesToFailedState() = runBlocking<Unit> {
        val adapter = EngineInferenceAdapter(FakeEngine(failLoad = "bad gguf"), engineThread)
        try {
            adapter.load(LoadConfig("/bad.gguf")); fail("expected failure")
        } catch (e: ModelLoadException) {
            assertEquals("bad gguf", e.message)
        }
        val s = adapter.state.value
        assertTrue(s is EngineState.Failed && s.message == "bad gguf")
    }

    @Test
    fun generateWithoutModelFails() = runTest(UnconfinedTestDispatcher()) {
        val adapter = EngineInferenceAdapter(FakeEngine(), engineThread)
        try { adapter.generate(GenerationRequest(user)).toList(); fail() } catch (_: NotLoadedException) {}
    }

    @Test
    fun cancelStopsGenerationAndKeepsModelLoaded() = runBlocking<Unit> {
        val engine = FakeEngine(tokens = List(10_000) { "t" }, tokenDelayMs = 1)
        val adapter = EngineInferenceAdapter(engine, engineThread)
        val info = adapter.load(LoadConfig("/m.gguf"))
        val seen = CompletableDeferred<Unit>()
        val result = async {
            adapter.generate(GenerationRequest(user, maxNewTokens = 10_000)).toList().also { }
        }
        // wait until generating, then press "stop"
        withTimeout(5_000) { adapter.state.first { it is EngineState.Generating } }
        Thread.sleep(20)
        adapter.cancel()
        val events = withTimeout(5_000) { result.await() }
        val done = events.last() as GenerationEvent.Completed
        assertEquals(StopReason.CANCELLED, done.stats.stopReason)
        assertTrue(done.stats.generatedTokens < 10_000)
        assertEquals(EngineState.Ready(info), adapter.state.value)
        seen.complete(Unit)
        // engine is usable again
        engine.tokens = listOf("ok")
        val again = adapter.generate(GenerationRequest(user)).toList()
        assertEquals(StopReason.END_OF_TURN, (again.last() as GenerationEvent.Completed).stats.stopReason)
    }

    @Test
    fun cancellingCollectorAbortsLongPromptEvaluation() = runBlocking<Unit> {
        val engine = FakeEngine().apply { blockPrompt = CountDownLatch(1) }
        val adapter = EngineInferenceAdapter(engine, engineThread)
        adapter.load(LoadConfig("/m.gguf"))
        val job = async { adapter.generate(GenerationRequest(user)).toList() }
        withTimeout(5_000) { adapter.state.first { it is EngineState.Generating } }
        Thread.sleep(20)
        job.cancel()
        withTimeout(5_000) { adapter.state.first { it is EngineState.Ready } }
        assertTrue("native abort requested", engine.calls.contains("abort"))
    }

    @Test
    fun promptTooLongDropsOldTurnsAndRetries() = runBlocking<Unit> {
        val engine = FakeEngine(maxPromptChars = 200)
        val adapter = EngineInferenceAdapter(engine, engineThread)
        adapter.load(LoadConfig("/m.gguf"))
        val history = listOf(
            ChatMessage(Role.USER, "old question " + "x".repeat(150)),
            ChatMessage(Role.ASSISTANT, "old answer"),
            ChatMessage(Role.USER, "new question"),
        )
        val events = adapter.generate(GenerationRequest(history)).toList()
        assertTrue(events.last() is GenerationEvent.Completed)
        assertEquals(2, engine.prompts.size)
        assertTrue(!engine.prompts.last().contains("old question"))
    }

    @Test
    fun secondConcurrentGenerateIsRejected() = runBlocking<Unit> {
        val engine = FakeEngine(tokens = List(2000) { "t" }, tokenDelayMs = 1)
        val adapter = EngineInferenceAdapter(engine, engineThread)
        adapter.load(LoadConfig("/m.gguf"))
        val first = async { adapter.generate(GenerationRequest(user, maxNewTokens = 2000)).toList() }
        withTimeout(5_000) { adapter.state.first { it is EngineState.Generating } }
        val second = runCatching { adapter.generate(GenerationRequest(user)).toList() }
        assertTrue("second call must be rejected, got $second", second.exceptionOrNull() is BusyException)
        adapter.cancel()
        first.await()
    }

    @Test
    fun unloadDuringGenerationCancelsFirst() = runBlocking<Unit> {
        val engine = FakeEngine(tokens = List(10_000) { "t" }, tokenDelayMs = 1)
        val adapter = EngineInferenceAdapter(engine, engineThread)
        adapter.load(LoadConfig("/m.gguf"))
        val gen = async { adapter.generate(GenerationRequest(user, maxNewTokens = 10_000)).toList() }
        withTimeout(5_000) { adapter.state.first { it is EngineState.Generating } }
        withTimeout(5_000) { adapter.unload(UnloadReason.LOW_MEMORY) }
        val done = gen.await().last() as GenerationEvent.Completed
        assertEquals(StopReason.CANCELLED, done.stats.stopReason)
        assertEquals(EngineState.Unloaded(UnloadReason.LOW_MEMORY), adapter.state.value)
        assertEquals("unload", engine.calls.last())
    }

    @Test
    fun tokensPerSecondUsesGenerationTimeOnly() {
        val s = GenerationStats(100, 50, promptMillis = 1000, generationMillis = 2000, stopReason = StopReason.END_OF_TURN)
        assertEquals(25.0, s.tokensPerSecond, 0.001)
        assertEquals(100.0, s.promptTokensPerSecond, 0.001)
    }
}
