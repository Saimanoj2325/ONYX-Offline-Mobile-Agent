package com.offlineagent.tools

import com.offlineagent.core.inference.AheadEngine
import com.offlineagent.core.inference.AheadResult
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.FlashAttention
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.PrefillResult
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.TypeAheadController
import com.offlineagent.core.runtime.chat.ChatPrompts
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

/**
 * 0.6.2.3 on a REAL GGUF: time from Enter to the first answer token for a ~600-token chat prompt, with and without
 * typing-time prefill. The message is "typed" in real time at [CPS] characters per second through the real
 * [TypeAheadController] (debounce 500 ms, max wait 1.5 s) and the real adapter + JNI engine.
 *
 *  cold  = nothing of this prompt in the KV cache (fresh start / other chat before);
 *  warm  = system prompt + earlier turns already cached (the usual case: the previous turn of the same chat).
 *
 * Env: MODEL, THREADS (2), CTX (4096), CPS (6), REPS (2).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: error("MODEL")
    val threads = System.getenv("THREADS")?.toIntOrNull() ?: 2
    val ctx = System.getenv("CTX")?.toIntOrNull() ?: 4096
    val cps = System.getenv("CPS")?.toDoubleOrNull() ?: 6.0
    val reps = System.getenv("REPS")?.toIntOrNull() ?: 2
    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val engine = LlamaCppEngine(nativeDir)
    val adapter = EngineInferenceAdapter(engine, exec.asCoroutineDispatcher())
    val info = adapter.load(LoadConfig(model, contextLength = ctx, threads = threads, batchSize = 256, ubatchSize = 256,
        kvCacheType = KvCacheType.F16, flashAttention = FlashAttention.AUTO))
    println("== ${File(model).name} ctx ${info.contextLength} threads $threads, typing at $cps chars/s")

    val system = ChatPrompts.system(ChatPrompts.RichUi.CHEAT_SHEET)
    val history = listOf(
        "what are transformers in deep learning" to "Transformers are neural networks built on self-attention. Each token attends to every other token, so the model learns long-range context in parallel. They power GPT, BERT and most modern language models. Key parts: embeddings, multi-head attention, feed-forward layers, residual connections and layer norm.",
        "and its disadvantages?" to "Main disadvantages: attention cost grows with the square of the sequence length, they need a lot of data and compute to train, they are hard to interpret, and long contexts need a lot of memory for the KV cache. Small on-device models trade quality for speed.",
        "what is the difference between precision and recall" to "Precision is the share of predicted positives that are correct; recall is the share of real positives the model finds. Raising the threshold usually raises precision and lowers recall. F1 is their harmonic mean.",
    )
    val message = "Okay, now compare the KV cache memory of a 1.7B model at 8K context in f16 and q8_0, and tell me which one " +
        "is better for a phone with 8 GB of RAM, in two short lines."
    fun req(draft: String) = GenerationRequest(
        buildList {
            add(ChatMessage(Role.SYSTEM, system))
            history.forEach { (q, a) -> add(ChatMessage(Role.USER, q)); add(ChatMessage(Role.ASSISTANT, a)) }
            add(ChatMessage(Role.USER, draft))
        },
        thinking = false, maxNewTokens = 8, cachePrefix = false,
    )
    val total = engine.countTokens(com.offlineagent.core.inference.ChatMlTemplate(true).render(req(message).messages, false)) ?: -1
    println("prompt: $total tokens (message ${message.length} chars = ${"%.0f".format(message.length / cps)} s of typing)")

    suspend fun coldKv() { adapter.prefill(GenerationRequest(listOf(ChatMessage(Role.SYSTEM, "Z"), ChatMessage(Role.USER, "z")), maxNewTokens = 8, cachePrefix = false)) }
    suspend fun warmKv() { coldKv(); adapter.prefill(req("hi")) }

    data class Row(val label: String, val ttftMs: Long, val promptMs: Long, val rest: Int, val reused: Int, val typedPrefill: Int)

    suspend fun send(label: String, h: TypeAheadController.Handoff?): Row {
        val t0 = System.nanoTime()
        var first = 0L
        var stats: com.offlineagent.core.inference.GenerationStats? = null
        adapter.generate(req(message)).collect { ev ->
            if (ev is GenerationEvent.Token && first == 0L) first = (System.nanoTime() - t0) / 1_000_000
            if (ev is GenerationEvent.Completed) stats = ev.stats
        }
        val st = stats!!
        return Row(label, first, st.promptMillis, st.promptTokens - st.reusedTokens, st.reusedTokens, h?.prefilledWhileTyping(st.reusedTokens) ?: 0)
    }

    suspend fun typed(label: String): Row {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ahead = object : AheadEngine {
            override suspend fun prefill(request: GenerationRequest): PrefillResult? = adapter.prefill(request)
            override suspend fun reasonAhead(request: GenerationRequest, maxTokens: Int, biasWords: List<String>, bias: Float): AheadResult? = null
        }
        val c = TypeAheadController(scope, System::currentTimeMillis, ahead, build = { d -> req(d) }, gate = { null })
        val stepMs = (1000.0 / cps).toLong()
        for (i in 1..message.length) { c.onDraft(message.substring(0, i)); delay(stepMs) }
        // Enter right after the last character (no pause, no re-reading): the worst case for reading ahead
        val st = c.status.value
        val h = c.onSend(message)
        scope.cancel()
        println("   typing: ${st.prefills} prefills, ${st.evaluatedTokens} tok computed while typing")
        return send(label, h)
    }

    val rows = mutableListOf<Row>()
    repeat(reps) { r ->
        coldKv(); rows += send("cold, no read-ahead #${r + 1}", null)
        warmKv(); rows += send("warm, no read-ahead #${r + 1}", null)
        coldKv(); rows += typed("cold, read while typing #${r + 1}")
        warmKv(); rows += typed("warm, read while typing #${r + 1}")
    }
    println()
    println("| run | wait after Enter (first token) | prompt eval after Enter | tokens left after Enter | reused from KV | prefilled while typing |")
    println("|---|---|---|---|---|---|")
    rows.forEach { println("| ${it.label} | ${it.ttftMs} ms | ${it.promptMs} ms | ${it.rest} | ${it.reused} | ${it.typedPrefill} |") }
    fun mean(p: String) = rows.filter { it.label.startsWith(p) }.map { it.ttftMs }.average()
    println()
    println("MEAN cold no-ahead ${"%.0f".format(mean("cold, no"))} ms | cold ahead ${"%.0f".format(mean("cold, read"))} ms | " +
        "warm no-ahead ${"%.0f".format(mean("warm, no"))} ms | warm ahead ${"%.0f".format(mean("warm, read"))} ms")
    adapter.unload()
    exec.shutdown()
}
