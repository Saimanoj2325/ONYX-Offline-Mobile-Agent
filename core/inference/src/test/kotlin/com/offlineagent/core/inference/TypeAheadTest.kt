package com.offlineagent.core.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine fake with a REAL KV mirror: one token per character, longest-common-prefix reuse exactly like llama_jni.cpp
 * (eval_text keeps finished batches on abort; a sampling request re-evaluates >= 1 token).
 */
class KvFakeEngine(var script: List<String> = listOf("ok"), var perTokenMs: Long = 0, val batch: Int = 16) : TokenEngine {
    val kv = Collections.synchronizedList(mutableListOf<Int>())
    val beginPrompts = Collections.synchronizedList(mutableListOf<String>())
    val prefillPrompts = Collections.synchronizedList(mutableListOf<String>())
    /** Tokens computed by each beginCompletion (the "rest" after Send). */
    val beginEvaluated = Collections.synchronizedList(mutableListOf<Int>())
    val nextCalls = AtomicInteger(0)
    val abort = AtomicBoolean(false)
    var bias: IntArray = IntArray(0)
    private var idx = 0
    private var loaded = false

    private fun toks(s: String) = s.map { it.code }.toIntArray()

    /** eval_text: returns [total, reused, evaluated]; throws on abort after keeping finished batches. */
    private fun eval(tokens: IntArray, needLogits: Boolean): IntArray {
        val plan = PrefixMath.plan(kv.toIntArray(), tokens, needLogits)
        while (kv.size > plan.keep) kv.removeAt(kv.size - 1)
        var i = plan.keep
        while (i < tokens.size) {
            val end = minOf(tokens.size, i + batch)
            if (perTokenMs > 0) {
                repeat(end - i) {
                    if (abort.get()) throw GenerationAbortedException()
                    Thread.sleep(perTokenMs)
                }
            }
            if (abort.get()) throw GenerationAbortedException()
            for (j in i until end) kv += tokens[j]
            i = end
        }
        return intArrayOf(tokens.size, plan.keep, plan.evaluate)
    }

    override fun load(config: LoadConfig): ModelInfo {
        loaded = true
        return ModelInfo(config.modelPath, "qwen3 1.7B Q8_0", 1, 1, 40960, config.contextLength, config.threads, "<|im_start|><think>", "CPU")
    }

    override fun beginCompletion(prompt: String, sampling: SamplingParams, maxNewTokens: Int, grammar: String?, images: List<ImageData>): Int {
        abort.set(false)
        beginPrompts += prompt
        val r = eval(toks(prompt), needLogits = true)
        beginEvaluated += r[2]
        idx = 0
        return r[0]
    }

    override fun prefill(prompt: String, nKeep: Int): IntArray? {
        abort.set(false)
        prefillPrompts += prompt
        return eval(toks(prompt), needLogits = false)
    }

    override fun countTokens(text: String): Int = text.length
    override fun tokenIds(text: String): IntArray = toks(text)
    override fun setLogitBias(tokenIds: IntArray, bias: Float): Boolean { this.bias = tokenIds; return true }

    override fun nextToken(): TokenStep {
        nextCalls.incrementAndGet()
        if (perTokenMs > 0) Thread.sleep(perTokenMs)
        if (abort.get()) return TokenStep.End(StopReason.CANCELLED)
        if (idx >= script.size) return TokenStep.End(StopReason.END_OF_TURN)
        val p = script[idx++]
        kv += p.map { it.code }
        return TokenStep.Piece(p.toByteArray())
    }

    override fun requestAbort() { abort.set(true) }
    override fun unload() { loaded = false }
    override fun systemInfo() = "fake"
}

@OptIn(ExperimentalCoroutinesApi::class)
class TypeAheadTest {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    private val engineThread = executor.asCoroutineDispatcher()
    @After fun tearDown() { executor.shutdownNow() }

    private val system = "You are ONYX, an offline assistant. Answer briefly. ".repeat(3)
    private fun req(draft: String, thinking: Boolean = false) = GenerationRequest(
        listOf(ChatMessage(Role.SYSTEM, system), ChatMessage(Role.USER, "earlier question"), ChatMessage(Role.ASSISTANT, "earlier answer"), ChatMessage(Role.USER, draft)),
        thinking = thinking, maxNewTokens = 64, cachePrefix = false,
    )

    private suspend fun adapter(engine: KvFakeEngine) = EngineInferenceAdapter(engine, engineThread).also { it.load(LoadConfig("/m.gguf", contextLength = 8192)) }

    // ------------------------------------------------------------------ rollback math

    @Test fun commonPrefixAppendEditDelete() {
        fun t(s: String) = s.map { it.code }.toIntArray()
        // typing appends: everything cached is kept, only the new tail is computed
        assertEquals(PrefixMath.Plan(keep = 5, rollback = 0, evaluate = 6), PrefixMath.plan(t("hello"), t("hello world")))
        // an edit in the middle rolls back to the edit and recomputes from there
        val mid = PrefixMath.plan(t("the cat sat on the mat"), t("the dog sat on the mat"))
        assertEquals(4, mid.keep); assertEquals(18, mid.rollback); assertEquals(18, mid.evaluate)
        // a deletion at the end only rolls back (nothing new to compute)
        assertEquals(PrefixMath.Plan(keep = 8, rollback = 3, evaluate = 0), PrefixMath.plan(t("what is xyz"), t("what is ")))
        // deleting a word in the middle
        val del = PrefixMath.plan(t("a very big cat"), t("a big cat"))
        assertEquals(2, del.keep); assertEquals(12, del.rollback); assertEquals(7, del.evaluate)
        // fully cached: a sampling request still needs one token for fresh logits; a prefill needs none
        assertEquals(PrefixMath.Plan(3, 1, 1), PrefixMath.plan(t("same"), t("same"), needLogits = true))
        assertEquals(PrefixMath.Plan(4, 0, 0), PrefixMath.plan(t("same"), t("same")))
        assertEquals(0, PrefixMath.commonPrefix(t(""), t("x")))
    }

    // ------------------------------------------------------------------ adapter

    @Test fun sendReusesPrefillOnlyTailIsComputed() = runBlocking<Unit> {
        val engine = KvFakeEngine()
        val a = adapter(engine)
        val p1 = a.prefill(req("what is a transf"))!!
        assertFalse(p1.aborted)
        assertTrue(p1.evaluatedTokens > 100)
        // the user keeps typing: only the changed tail is read
        val p2 = a.prefill(req("what is a transformer?"))!!
        val lcp = p1.prompt.zip(p2.prompt).takeWhile { (x, y) -> x == y }.size
        assertEquals(lcp, p2.reusedTokens)
        assertEquals(p2.promptTokens - lcp, p2.evaluatedTokens)
        assertTrue("only the tail after the shared prefix: ${p2.evaluatedTokens}", p2.evaluatedTokens < 40)
        // Send with the same text: one token (fresh logits) is all that is left
        val done = a.generate(req("what is a transformer?")).toList().last() as GenerationEvent.Completed
        assertEquals(1, engine.beginEvaluated.single())
        assertEquals(StopReason.END_OF_TURN, done.stats.stopReason)
        // a mid-text edit before Send rolls back to the edit only
        a.prefill(req("what is a transformer?"))
        a.generate(req("what is the transformer?")).toList()
        val edit = engine.beginEvaluated.last()
        assertTrue("edit recomputes from the edit point, not the whole prompt: $edit", edit in 10..60)
    }

    @Test fun prefillPromptIsIdenticalToSendPrompt() = runBlocking<Unit> {
        for (thinking in listOf(false, true)) {
            val engine = KvFakeEngine()
            val a = adapter(engine)
            val r = a.prefill(req("explain kv cache reuse", thinking))!!
            a.generate(req("explain kv cache reuse", thinking)).toList()
            assertEquals(engine.beginPrompts.single(), r.prompt)
            assertEquals(engine.prefillPrompts.single(), r.prompt)
        }
    }

    @Test fun noTokenAndNoToolCallDuringPrefill() = runBlocking<Unit> {
        // a script that WOULD be a tool call if anything were generated
        val engine = KvFakeEngine(script = listOf("""{"tool":"send_sms","args":{"to":"mom"}}"""))
        val a = adapter(engine)
        repeat(3) { i -> a.prefill(req("send a message to mom ${"x".repeat(i)}")) }
        assertEquals("prefill never samples or decodes a token", 0, engine.nextCalls.get())
        assertEquals(0, engine.beginPrompts.size)
        assertTrue(a.state.value is EngineState.Ready)
    }

    @Test fun priorityRequestPreemptsPrefill() = runBlocking<Unit> {
        val engine = KvFakeEngine(perTokenMs = 2, batch = 16)
        val a = adapter(engine)
        val long = req("summarise this: " + "lorem ipsum dolor sit amet ".repeat(40))
        val pre = async(kotlinx.coroutines.Dispatchers.Default) { a.prefill(long) }
        withTimeout(5_000) { while (!a.readingAhead) delay(2) }
        delay(60) // some batches finish
        val t0 = System.nanoTime()
        val events = withTimeout(10_000) { a.generate(req("what time is it?")).toList() }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        val r = pre.await()!!
        assertTrue("prefill was interrupted", r.aborted)
        assertEquals(1, a.preemptions)
        assertTrue(events.last() is GenerationEvent.Completed)
        // the request did not wait for the whole prefill (~2.4 s of work)
        assertTrue("waited $waitedMs ms", waitedMs < 1_500)
        // finished batches of the interrupted prefill stayed cached: the request reused the shared prefix
        assertTrue((events.last() as GenerationEvent.Completed).stats.promptTokens > 0)
    }

    @Test fun busyEngineSkipsPrefill() = runBlocking<Unit> {
        val engine = KvFakeEngine(script = List(50) { "a" }, perTokenMs = 2)
        val a = adapter(engine)
        val gen = async(kotlinx.coroutines.Dispatchers.Default) { a.generate(req("hi")).toList() }
        withTimeout(5_000) { while (a.state.value !is EngineState.Generating) delay(1) }
        assertNull("a prefill never waits for or interrupts a real request", a.prefill(req("hello")))
        gen.await()
    }

    // ------------------------------------------------------------------ reasoning ahead

    @Test fun reasoningAheadFrameBridgeAndClose() {
        val s = ReasoningAheadSession(maxTokens = 256)
        s.start("A train leaves at 9")
        val p0 = s.prefixFor("A train leaves at 9")
        assertTrue(p0.startsWith("<think>\n" + ReasoningAhead.FRAME))
        assertFalse(p0.contains("New words"))
        s.accept("A train leaves at 9", AheadResult("- \"9\": departure time 9:00.\n", 32, 10, StopReason.MAX_TOKENS))
        // the draft grows: a New words line names what was typed since
        val p1 = s.prefixFor("A train leaves at 9 and travels 120 km")
        assertTrue(p1, p1.endsWith("- \"9\": departure time 9:00.\nNew words: \"and travels 120 km\"\n"))
        s.accept("A train leaves at 9 and travels 120 km", AheadResult("- \"120 km\": distance.", 20, 10, StopReason.MAX_TOKENS))
        assertEquals(52, s.tokens); assertEquals(204, s.budgetLeft)
        // Enter: the last words + the "complete" sentence, and the user sees the notes without the frame
        val fin = s.finalPrefix("A train leaves at 9 and travels 120 km at 60 km/h. When does it arrive?")
        assertTrue(fin, fin.endsWith("- \"120 km\": distance.\nNew words: \"at 60 km/h. When does it arrive?\"\n" + ReasoningAhead.COMPLETE))
        assertFalse(s.shownFor("x").contains(ReasoningAhead.FRAME))
        assertTrue(s.shownFor("x").startsWith("<think>\n- \"9\""))
        // edits in the middle name the changed words only; pure deletions add no line
        assertEquals("dog", ReasoningAhead.newWords("the cat sat", "the dog sat"))
        assertEquals("", ReasoningAhead.newWords("the cat sat down", "the cat sat"))
        assertEquals("quickly", ReasoningAhead.newWords("he ran", "he ran quickly"))
        // close exactly as the Qwen3 template does
        assertTrue(ReasoningAhead.needsClose("<think>\nnotes"))
        assertFalse(ReasoningAhead.needsClose("<think>\nnotes\n</think>\n\nanswer"))
        assertEquals("\n</think>\n\n", ReasoningAhead.closeFor("<think>\nnotes"))
        assertEquals("</think>\n\n", ReasoningAhead.closeFor("<think>\nnotes\n"))
        assertTrue(ReasoningAhead.BIAS < 0 && ReasoningAhead.BIAS > -5)
        assertTrue(ReasoningAhead.BIAS_WORDS.containsAll(listOf(" user", " typing", " unfinished")))
    }

    @Test fun earlyEndInsideThinkIsClosedAndAnswered() = runBlocking<Unit> {
        // first turn: the model ends its turn inside the think block (empty answer); after the close it answers
        val engine = KvFakeEngine(script = listOf("done thinking"))
        val a = adapter(engine)
        val prefix = "<think>\n" + ReasoningAhead.FRAME + "- \"2+2\": 4\n" + ReasoningAhead.COMPLETE
        var turn = 0
        val ev = mutableListOf<GenerationEvent>()
        a.generate(req("what is 2+2?", thinking = true).copy(assistantPrefix = prefix, assistantPrefixShown = "<think>\n- \"2+2\": 4\n", closeThinkOnEarlyEnd = true)).collect {
            ev += it
            if (it is GenerationEvent.Token && it.text == "done thinking" && turn++ == 0) engine.script = listOf("The answer is 4.")
        }
        val text = ev.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        assertTrue(text, text.startsWith("<think>\n- \"2+2\": 4\n"))
        assertTrue(text, text.contains("done thinking\n</think>\n\nThe answer is 4."))
        assertEquals(ThinkSplitter.split(text, streaming = false).answer, "The answer is 4.")
        assertEquals(2, engine.beginPrompts.size)
        assertTrue(engine.beginPrompts[0].endsWith(prefix))
        assertTrue(engine.beginPrompts[1].endsWith(prefix + "done thinking\n</think>\n\n"))
    }

    @Test fun reasonAheadStopsAtCloseAndSetsBias() = runBlocking<Unit> {
        val engine = KvFakeEngine(script = listOf("- \"kv\": ", "key/value cache", "</think>", "leak"))
        val a = adapter(engine)
        val r = a.reasonAhead(req("what is kv", thinking = true).copy(assistantPrefix = "<think>\n" + ReasoningAhead.FRAME), 32, listOf(" user"), -2f)!!
        assertEquals("- \"kv\": key/value cache", r.text)
        assertEquals(StopReason.END_OF_TURN, r.stop)
        assertEquals("bias cleared after reasoning ahead", 0, engine.bias.size)
    }

    // ------------------------------------------------------------------ controller (virtual time)

    private class FakeAhead : AheadEngine {
        val prefills = mutableListOf<String>()
        val reasons = mutableListOf<String>()
        var chunk = 32
        override suspend fun prefill(request: GenerationRequest): PrefillResult {
            val d = request.messages.last().content
            prefills += d
            return PrefillResult(d, 400 + d.length, if (prefills.size == 1) 380 else 400, d.length, 5, false)
        }
        override suspend fun reasonAhead(request: GenerationRequest, maxTokens: Int, biasWords: List<String>, bias: Float): AheadResult {
            reasons += request.assistantPrefix.orEmpty()
            delay(100)
            return AheadResult("- note ${reasons.size}\n", minOf(chunk, maxTokens), 100, StopReason.MAX_TOKENS)
        }
    }

    @Test fun controllerDebouncesAndHandsOver() = runTest {
        val eng = FakeAhead()
        val c = TypeAheadController(this, { testScheduler.currentTime }, eng, build = { d -> req(d) }, gate = { null })
        c.onDraft("he"); advanceTimeBy(100)
        c.onDraft("hello"); advanceTimeBy(100)
        c.onDraft("hello wo"); advanceTimeBy(700); runCurrent()
        assertEquals(listOf("hello wo"), eng.prefills)
        // continuous typing (a key every 150 ms, never a 500 ms pause): still read at least every 1.5 s
        var t = "hello wo"
        repeat(20) { t += "r"; c.onDraft(t); advanceTimeBy(150) }
        runCurrent()
        assertTrue("prefills during continuous typing: ${eng.prefills.size}", eng.prefills.size in 2..4)
        advanceTimeBy(700); runCurrent()
        assertEquals(t, eng.prefills.last())
        val h = c.onSend(t)
        assertEquals(380, h.baselineTokens)
        assertEquals(400 + t.length, h.cachedTokens)
        assertEquals("tokens Send found cached that typing computed", 400 + t.length - 1 - 380, h.prefilledWhileTyping(reusedAtSend = 400 + t.length - 1))
        assertEquals(0, TypeAheadController.Handoff(100, 90, 0, null, null).prefilledWhileTyping(500))
        assertEquals("idle", c.status.value.state)
    }

    @Test fun governorGateSkipsPrefill() = runTest {
        val eng = FakeAhead()
        var why: String? = "battery saver"
        val c = TypeAheadController(this, { testScheduler.currentTime }, eng, build = { d -> req(d) }, gate = { why })
        c.onDraft("what is the capital of france"); advanceTimeBy(2_000); runCurrent()
        assertTrue(eng.prefills.isEmpty())
        assertEquals("battery saver", c.status.value.skipped)
        why = null
        c.onDraft("what is the capital of france?"); advanceTimeBy(2_000); runCurrent()
        assertEquals(1, eng.prefills.size)
        // paused (screen off / background): nothing more is scheduled
        c.pause()
        c.onDraft("what is the capital of france? and"); advanceTimeBy(2_000); runCurrent()
        assertEquals(1, eng.prefills.size)
    }

    @Test fun routeNotPrefillableIsSkipped() = runTest {
        val eng = FakeAhead()
        val c = TypeAheadController(this, { testScheduler.currentTime }, eng, build = { null }, gate = { null })
        c.onDraft("open whatsapp and send hi"); advanceTimeBy(2_000); runCurrent()
        assertTrue(eng.prefills.isEmpty())
        assertEquals("route not prefillable", c.status.value.skipped)
    }

    @Test fun reasoningAheadIsCappedAndIdleStops() = runTest {
        val eng = FakeAhead()
        val c = TypeAheadController(this, { testScheduler.currentTime }, eng, build = { d -> req(d, thinking = true) }, gate = { null },
            config = { TypeAheadController.Config(reasonAhead = true, reasonCapTokens = 256, reasonChunkTokens = 32) })
        c.onDraft("a shop sells pens at 12 rupees"); advanceTimeBy(60_000); runCurrent()
        assertEquals("8 chunks of 32 = the 256-token cap", 8, eng.reasons.size)
        assertTrue(eng.reasons.first().startsWith("<think>\n" + ReasoningAhead.FRAME))
        assertTrue(eng.reasons.last().contains("- note 7\n"))
        val h = c.onSend("a shop sells pens at 12 rupees. how much for 3?")
        assertNotNull(h.reasoning)
        assertEquals(256, h.reasoning!!.tokens)
        assertTrue(h.reasoning!!.finalPrefix("a shop sells pens at 12 rupees. how much for 3?").endsWith("New words: \"rupees. how much for 3?\"\n" + ReasoningAhead.COMPLETE) ||
            h.reasoning!!.finalPrefix("a shop sells pens at 12 rupees. how much for 3?").contains("New words:"))
        // idle: after 60 s without a change no more ahead reasoning is started
        val eng2 = FakeAhead().apply { chunk = 1 }
        var now = 0L
        val c2 = TypeAheadController(this, { now }, eng2, build = { d -> req(d, thinking = true) }, gate = { null },
            config = { TypeAheadController.Config(reasonAhead = true, reasonCapTokens = 10_000, reasonChunkTokens = 1, idleStopMs = 60_000) })
        c2.onDraft("solve x + 2 = 5")
        now = 600; advanceTimeBy(600); runCurrent()
        val before = eng2.reasons.size
        now = 61_000; advanceTimeBy(1_000); runCurrent()
        advanceTimeBy(10_000); runCurrent()
        assertTrue("stopped after idle: ${eng2.reasons.size} vs $before", eng2.reasons.size <= before + 1)
        c2.onSend("solve x + 2 = 5")
    }
}
