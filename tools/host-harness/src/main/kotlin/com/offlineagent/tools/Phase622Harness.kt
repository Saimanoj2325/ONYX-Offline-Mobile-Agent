package com.offlineagent.tools

import com.offlineagent.core.agents.memory.ChatHistoryIndex
import com.offlineagent.core.agents.memory.InMemoryHistoryRepo
import com.offlineagent.core.agents.memory.MemoryScope
import com.offlineagent.core.agents.memory.MemorySettings
import com.offlineagent.core.agents.memory.PreviousChats
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.FlashAttention
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.Role
import com.offlineagent.core.openui.OpenUiLibrary
import com.offlineagent.core.openui.OpenUiPrompt
import com.offlineagent.core.runtime.agent.STRUCTURED_SAMPLING
import com.offlineagent.core.runtime.chat.ChatPrompts
import com.offlineagent.core.runtime.chat.PromptBudget
import com.offlineagent.core.runtime.chat.SessionContext
import com.offlineagent.core.runtime.context.Turn
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

/**
 * 0.6.2.2 on a REAL GGUF: prompt sizes (real tokenizer) and prefill time for Sai's Canvas-mode request
 * "can you give me 2-3 lines of summary of our previous chats?" — before (0.6.2.1 canvas prompt: full generated library
 * + 700-token session block) and after (routed to chat with the previous-chats block; and the new canvas prompt for a
 * real canvas request). "cold" = nothing of the prompt in the KV cache; "warm" = static system prefix reused.
 * Env: MODEL, THREADS (2), CTX (4096).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: error("MODEL")
    val threads = System.getenv("THREADS")?.toIntOrNull() ?: 2
    val ctx = System.getenv("CTX")?.toIntOrNull() ?: 4096
    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val engine = LlamaCppEngine(nativeDir)
    val adapter = EngineInferenceAdapter(engine, exec.asCoroutineDispatcher())
    val info = adapter.load(LoadConfig(model, contextLength = ctx, threads = threads, batchSize = 256, ubatchSize = 256, kvCacheType = KvCacheType.F16, flashAttention = FlashAttention.AUTO))
    println("== ${File(model).name} ctx ${info.contextLength} threads $threads")
    val request = "can you give me 2-3 lines of summary of our previous chats?"

    // A realistic chat before the request (what the session block carried): six turns of Q&A.
    val qa = listOf(
        "what are transformers in deep learning" to "Transformers are neural networks built on self-attention. Each token attends to every other token, so the model learns long-range context in parallel. They power GPT, BERT and most modern language models. Key parts: embeddings, multi-head attention, feed-forward layers, residual connections and layer norm.",
        "and its disadvantages?" to "Main disadvantages: attention cost grows with the square of the sequence length, they need a lot of data and compute to train, they are hard to interpret, and long contexts need a lot of memory for the KV cache. Small on-device models trade quality for speed.",
        "make a 3 day study plan for my ML exam" to "Day 1: linear models, gradient descent, regularisation. Day 2: trees, ensembles, SVMs, evaluation metrics. Day 3: neural networks, CNNs, transformers, and a full mock paper in the evening.",
        "what is the difference between precision and recall" to "Precision is the share of predicted positives that are correct; recall is the share of real positives the model finds. Raising the threshold usually raises precision and lowers recall. F1 is their harmonic mean.",
    )
    var id = 0L
    val turns = qa.flatMap { (q, a) -> listOf(Turn(++id, Role.USER, q), Turn(++id, Role.ASSISTANT, a)) }
    val memory = "Known about the user (saved memories):\n- User's name is Sai Manoj\n- Studies AIML at CBIT, Hyderabad\n- Prefers simple Indian English\n" +
        "From earlier chats:\n- [Transformers] User: what are transformers Assistant: Transformers are neural networks built on self-attention…\n- [ML exam plan] User: make a 3 day study plan…"
    val sessBefore = SessionContext.build(turns, null, memory, 700).text

    val lib = OpenUiLibrary.DEFAULT
    val sysBefore = OpenUiPrompt.generate(lib, OpenUiPrompt.Options(mode = OpenUiPrompt.Mode.PROGRAM))
    val userBefore = "$sessBefore\n\nLatest request:\n$request"

    // After: the question is answered in chat; the context is the previous-chats block (Threads summaries).
    val idx = ChatHistoryIndex(InMemoryHistoryRepo())
    idx.addSummary(11, "Transformers", "Explained transformers (self-attention, used in GPT/BERT) and their disadvantages (quadratic attention cost, data and memory hungry).", MemoryScope.NORMAL)
    idx.addSummary(12, "ML exam plan", "Made a 3-day ML exam study plan: linear models; trees and metrics; neural nets and a mock paper.", MemoryScope.NORMAL)
    idx.addExchange(13, "Precision vs recall", qa[3].first, qa[3].second, MemoryScope.NORMAL)
    val prev = PreviousChats.contextFor(request, idx, 99, MemoryScope.NORMAL, MemorySettings(), 450).text
    val sysChat = ChatPrompts.system(ChatPrompts.RichUi.CHEAT_SHEET)
    val userChat = "$prev\n\n$request"
    // After, for a real canvas request: the static sheet + a device-budgeted dynamic part (MID tier, nothing measured).
    val dyn = PromptBudget.dynamicTokens(PromptBudget.Tier.MID)
    val sysCanvas = OpenUiPrompt.canvasSheet()
    val canvasReq = "make a table of my 3 day ML exam plan"
    val split = PromptBudget.split(dyn, 20, 200, 700)
    val sessAfter = SessionContext.build(turns, null, PromptBudget.keepFirst(memory, split.memoryTokens), split.historyTokens + split.memoryTokens).text
    val userCanvas = PromptBudget.staticFirst(sysCanvas, listOf(sessAfter), canvasReq).second

    fun tok(s: String) = engine.countTokens(s) ?: -1
    println("\n== prompt sizes (Qwen3 tokenizer, message text only)")
    println("before  canvas system ${tok(sysBefore)} + user ${tok(userBefore)} (session block ${tok(sessBefore)})")
    println("after   chat   system ${tok(sysChat)} + user ${tok(userChat)} (previous-chats block ${tok(prev)})")
    println("after   canvas system ${tok(sysCanvas)} + user ${tok(userCanvas)} (dynamic budget $dyn, session ${tok(sessAfter)})")

    suspend fun prefill(name: String, sys: String, user: String, grammar: String?): GenerationStats {
        val ev = adapter.generate(GenerationRequest(listOf(ChatMessage(Role.SYSTEM, sys), ChatMessage(Role.USER, user)), thinking = false,
            maxNewTokens = 1, sampling = STRUCTURED_SAMPLING, cachePrefix = false, grammar = grammar)).toList()
        val st = ev.filterIsInstance<GenerationEvent.Completed>().single().stats
        println("%-28s prompt %5d tok, reused %5d, prefill %6d ms (%.1f tok/s new)".format(name, st.promptTokens, st.reusedTokens, st.promptMillis,
            (st.promptTokens - st.reusedTokens) * 1000.0 / st.promptMillis.coerceAtLeast(1)))
        return st
    }
    suspend fun flush() { prefill("(flush)", "x", "y", null) }
    val gBefore = com.offlineagent.core.openui.OpenUiGrammar.gbnf(lib)
    val gAfter = com.offlineagent.core.openui.OpenUiGrammar.gbnf(lib, maxStatements = 24)
    println("\n== prefill on this host (2 vCPU sandbox, NOT a phone)")
    flush(); prefill("before canvas, cold", sysBefore, userBefore, gBefore)
    prefill("before canvas, warm system", sysBefore, "(new turn)\n$userBefore", gBefore)
    flush(); prefill("after chat, cold", sysChat, userChat, null)
    prefill("after chat, warm system", sysChat, "(new turn)\n$userChat", null)
    flush(); prefill("after canvas, cold", sysCanvas, userCanvas, gAfter)
    prefill("after canvas, warm system", sysCanvas, "(new turn)\n$userCanvas", gAfter)
    adapter.unload()
    exec.shutdown()
}
