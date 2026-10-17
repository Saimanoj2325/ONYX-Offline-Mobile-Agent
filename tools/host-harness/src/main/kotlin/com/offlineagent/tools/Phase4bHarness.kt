package com.offlineagent.tools

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.FlashAttention
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.GgufMetadata
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.SamplingParams
import com.offlineagent.core.inference.SelfTest
import com.offlineagent.core.openui.OpenUiLibrary
import com.offlineagent.core.openui.OpenUiPrompt
import com.offlineagent.core.runtime.chat.ChatPrompts
import com.offlineagent.core.runtime.chat.StreamingTurn
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Phase 4b host measurements with REAL models through the real JNI bridge:
 *  1. GGUF header read: Phase 4 reader (tiny RandomAccessFile reads, full vocabulary skip) vs the new buffered,
 *     early-stopping reader + cache (the ANR on the phone).
 *  2. Chat system-prompt size: Phase 4 (full / compact OpenUI library) vs Phase 4b (cheat sheet), real tokenizer.
 *  3. Time to first token, as the app does it (load → self-test → first message; then a second turn), old vs new prompt.
 *  4. Prefill progress events and the streaming reducer (thinking on: reasoning streams, then the answer).
 *  5. Prefill speed by batch size (BATCHES env, e.g. "128,256,512").
 * Env: MODEL (gguf), THREADS (default 2), CTX (default 8192), BATCHES, ONLY (comma list of: gguf,prompt,ttft,stream,batch).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: error("MODEL")
    val threads = (System.getenv("THREADS")?.toIntOrNull() ?: 2).coerceIn(1, 8)
    val ctx = System.getenv("CTX")?.toIntOrNull() ?: 8192
    val only = System.getenv("ONLY")?.split(',')?.toSet()
    fun want(s: String) = only == null || s in only
    val failures = mutableListOf<String>()
    fun check(cond: Boolean, what: String) { println((if (cond) "  PASS " else "  FAIL ") + what); if (!cond) failures += what }
    fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
    println("== model ${File(model).name}, threads=$threads, ctx=$ctx")

    // ---------------------------------------------------------------- 1. GGUF header
    if (want("gguf")) {
        val f = File(model)
        var t0 = System.nanoTime()
        val old = OldGguf.read(f)
        val oldMs = ms(t0)
        GgufMetadata.clearCache()
        t0 = System.nanoTime()
        val a = GgufMetadata.arch(f)
        val newMs = ms(t0)
        t0 = System.nanoTime()
        val a2 = GgufMetadata.arch(f)
        val cachedUs = (System.nanoTime() - t0) / 1000
        t0 = System.nanoTime()
        val full = GgufMetadata.read(f)
        val fullMs = ms(t0)
        println("== GGUF header: Phase 4 reader ${oldMs} ms (${old.size} keys) | Phase 4b arch() ${newMs} ms | cached ${cachedUs} µs | full buffered read ${fullMs} ms (${full.size} keys)")
        println("   arch = $a")
        check(a != null && a == a2, "arch read + cached")
        check(a?.layers == (old["${a?.architecture}.block_count"] as? Number)?.toInt(), "same block_count as the old reader")
        check(a?.vocabSize == (old["tokenizer.ggml.tokens.length"] as? Number)?.toInt(), "same vocabulary size as the old reader")
        check(full.keys.containsAll(old.keys), "buffered full read sees every key")
    }

    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val engine = LlamaCppEngine(nativeDir)
    val cacheDir = File(System.getenv("OUT") ?: "/tmp/phase4b", "prompt-cache").apply { deleteRecursively(); mkdirs() }
    val adapter = EngineInferenceAdapter(engine, exec.asCoroutineDispatcher(),
        prefixCacheFile = { info, prefix -> com.offlineagent.engine.llamacpp.PrefixCacheFiles.path(cacheDir, info, prefix) })

    suspend fun load(batch: Int = 512) {
        val t0 = System.nanoTime()
        val info = adapter.load(LoadConfig(model, contextLength = ctx, threads = threads, kvCacheType = KvCacheType.F16, flashAttention = FlashAttention.AUTO,
            batchSize = batch, ubatchSize = batch, warmup = true))
        println("== loaded ${info.description} ctx=${info.contextLength} batch=$batch in ${ms(t0)} ms")
    }

    val baseOld = "You are a helpful assistant running fully offline on the user's Android phone. Answer clearly and concisely. " +
        "Use Markdown: headings, bullet lists, tables, **bold** and fenced code blocks where they help. " +
        "If the user writes \"Form submitted: …\", those are the values they entered in your form; continue with them."
    val oldFull = baseOld + "\n\n" + OpenUiPrompt.generate(OpenUiLibrary.DEFAULT)              // Phase 4 Auto, 4B model (≥ 3B)
    val oldCompact = baseOld + "\n\n" + OpenUiPrompt.generate(OpenUiLibrary.DEFAULT.compact()) // Phase 4 Auto, < 3B models
    val newChat = ChatPrompts.system(ChatPrompts.RichUi.CHEAT_SHEET)                           // Phase 4b Auto
    val newUi = ChatPrompts.system(ChatPrompts.level("AUTO", "make me a bar chart of my expenses"))

    load()
    val st = SelfTest.run(adapter)
    println("== self-test after load: ${st.verdict} \"${st.output.trim()}\" ${st.millis} ms")

    // ---------------------------------------------------------------- 2. prompt sizes
    if (want("prompt")) {
        suspend fun tok(s: String) = adapter.countTokens(s) ?: -1
        val a = tok(oldFull); val b = tok(oldCompact); val c = tok(newChat); val d = tok(newUi)
        println("== system prompt tokens: Phase 4 full library $a | Phase 4 compact $b | Phase 4b chat (cheat sheet) $c | Phase 4b when the user asks for UI $d")
        println("   chars: ${oldFull.length} | ${oldCompact.length} | ${newChat.length} | ${newUi.length}")
        check(c in 1..400, "Phase 4b chat system prompt ≤ 400 tokens (got $c)")
        check(c * 4 < a, "cheat sheet is < 1/4 of the full-library prompt")
    }

    data class Run(val text: String, val stats: GenerationStats, val ttftMs: Long, val progressEvents: Int, val view: StreamingTurn.View)
    suspend fun ask(messages: List<ChatMessage>, thinking: Boolean, maxNew: Int): Run {
        val t0 = System.currentTimeMillis()
        val turn = StreamingTurn(t0, "Reading prompt…")
        var stats: GenerationStats? = null
        var progress = 0
        var ttft = -1L
        val sb = StringBuilder()
        var sawThinkingOpen = false
        adapter.generate(GenerationRequest(messages, thinking = thinking, maxNewTokens = maxNew,
            sampling = SamplingParams(temperature = 0f, topK = 1, topP = 1f, minP = 0f, repeatPenalty = 1f, seed = 1))).collect { ev ->
            val v = turn.on(ev, System.currentTimeMillis())
            when (ev) {
                is GenerationEvent.PromptProgress -> progress++
                is GenerationEvent.Token -> { if (ttft < 0 && v.hasVisibleText) ttft = System.currentTimeMillis() - t0; sb.append(ev.text); if (v.thinkingOpen) sawThinkingOpen = true }
                is GenerationEvent.Completed -> stats = ev.stats
                else -> Unit
            }
        }
        if (thinking) check(!sb.contains("<think>") || sawThinkingOpen, "thinking section was open while the model reasoned")
        return Run(sb.toString(), stats!!, ttft, progress, turn.current)
    }
    fun line(label: String, r: Run) = println("   %-46s prompt %5d tok (%4d reused) %6d ms prefill (%5.1f tok/s) | TTFT %6d ms | decode %4.1f tok/s | %d progress events".format(
        label, r.stats.promptTokens, r.stats.reusedTokens, r.stats.promptMillis, r.stats.promptTokensPerSecond, r.ttftMs, r.stats.tokensPerSecond, r.progressEvents))

    // ---------------------------------------------------------------- 3. time to first token
    if (want("ttft")) {
        val q1 = "Explain in two sentences why the sky is blue."
        val q2 = "And why are sunsets red?"
        println("== time to first token (thinking off, greedy; each scenario starts right after load + self-test, like the app)")
        for ((label, sys) in listOf("Phase 4 (full OpenUI library, 4B Auto)" to oldFull, "Phase 4 (compact library, <3B Auto)" to oldCompact, "Phase 4b (cheat sheet)" to newChat)) {
            load(); SelfTest.run(adapter)
            File(cacheDir.path).listFiles()?.forEach { it.delete() } // no disk prefix cache: first message ever
            val r1 = ask(listOf(ChatMessage(Role.SYSTEM, sys), ChatMessage(Role.USER, q1)), thinking = false, maxNew = 48)
            line("$label · turn 1", r1)
            val r2 = ask(listOf(ChatMessage(Role.SYSTEM, sys), ChatMessage(Role.USER, q1), ChatMessage(Role.ASSISTANT, r1.view.answer), ChatMessage(Role.USER, q2)), thinking = false, maxNew = 48)
            line("$label · turn 2 (KV prefix reuse)", r2)
            check(r2.stats.reusedTokens > 0, "$label: turn 2 reuses the KV cache")
            check(r1.progressEvents > 0 || r1.stats.promptMillis < 400, "$label: prefill progress reported")
        }
    }

    // ---------------------------------------------------------------- 4. streaming with thinking on
    if (want("stream")) {
        println("== streaming with thinking ON (Qwen3 thinking mode)")
        load(); SelfTest.run(adapter)
        val r = ask(listOf(ChatMessage(Role.SYSTEM, newChat), ChatMessage(Role.USER, "What is 17 * 23? Answer briefly.")), thinking = true, maxNew = 400)
        line("thinking on", r)
        println("   reasoning: ${r.view.reasoning.take(160).replace('\n', ' ')}…")
        println("   answer:    ${r.view.answer.take(160).replace('\n', ' ')}")
        println("   footer:    ${StreamingTurn.footer(r.view)} · ${StreamingTurn.promptDetail(r.view)}")
        check(r.view.reasoning.isNotBlank(), "reasoning captured separately")
        check(r.ttftMs in 1 until (r.stats.promptMillis + 30_000), "first visible token arrives while thinking (not after the answer)")
    }

    // ---------------------------------------------------------------- 5. batch size
    val batches = System.getenv("BATCHES")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
    if (want("batch") && batches.isNotEmpty()) {
        println("== prefill speed by batch size (~${oldFull.length / 4} token prompt)")
        for (b in batches) {
            load(b)
            val r = ask(listOf(ChatMessage(Role.SYSTEM, oldFull), ChatMessage(Role.USER, "Say OK.")), thinking = false, maxNew = 4)
            println("   batch %4d: %d tok in %d ms = %.1f tok/s".format(b, r.stats.promptTokens, r.stats.promptMillis, r.stats.promptTokensPerSecond))
        }
    }

    adapter.unload()
    exec.shutdown()
    println(if (failures.isEmpty()) "ALL PHASE 4b CHECKS PASSED" else "FAILURES: $failures")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

/** The Phase 4 GGUF reader, kept here only to measure the difference (tiny RandomAccessFile reads, full skip). */
private object OldGguf {
    fun read(file: File): Map<String, Any> = RandomAccessFile(file, "r").use { raf ->
        val b8 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        fun le(n: Int): ByteBuffer { b8.clear(); raf.readFully(b8.array(), 0, n); b8.limit(n); return b8 }
        fun u32() = le(4).int.toLong() and 0xFFFFFFFFL
        fun u64() = le(8).long
        fun skip(n: Long) = raf.seek(raf.filePointer + n)
        fun str(): String { val n = u64(); val b = ByteArray(n.toInt()); raf.readFully(b); return String(b) }
        fun size(t: Int): Long = when (t) { 0, 1, 7 -> 1; 2, 3 -> 2; 4, 5, 6 -> 4; else -> 8 }
        raf.seek(8); u64(); val kv = u64()
        val out = LinkedHashMap<String, Any>()
        for (i in 0 until kv) {
            val key = str(); val type = u32().toInt()
            if (type == 9) {
                val et = u32().toInt(); val n = u64(); out["$key.length"] = n
                if (et == 8) repeat(n.toInt()) { skip(u64()) } else skip(n * size(et))
            } else out[key] = when (type) {
                8 -> str(); 4 -> u32(); 5 -> le(4).int; 6 -> le(4).float; 10, 11 -> u64(); 12 -> le(8).double
                0, 1, 7 -> raf.readByte().toInt(); else -> le(2).short.toInt()
            }
        }
        out
    }
}
