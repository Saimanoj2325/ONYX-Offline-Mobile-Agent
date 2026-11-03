package com.offlineagent.tools

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.FlashAttention
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.SamplingParams
import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Phase 6 M1 on a REAL GGUF: n-gram (prompt-lookup) self-speculation in the JNI — identical output on/off (greedy and
 * seeded sampling, with and without a JSON grammar) and the measured speed. Also the "benchmark screen" numbers
 * (prompt tok/s, gen tok/s, load time, peak RSS) the app shows, measured the same way on the host.
 * Env: MODEL, THREADS (2), CTX (4096), DRAFT (8), ONLY (spec|bench).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: error("MODEL")
    val threads = System.getenv("THREADS")?.toIntOrNull() ?: 2
    val ctx = System.getenv("CTX")?.toIntOrNull() ?: 4096
    val draft = System.getenv("DRAFT")?.toIntOrNull() ?: 8
    val only = System.getenv("ONLY")?.takeIf { it.isNotBlank() }
    val failures = mutableListOf<String>()
    fun check(cond: Boolean, what: String) { println((if (cond) "  PASS " else "  FAIL ") + what); if (!cond) failures += what }

    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val adapter = EngineInferenceAdapter(LlamaCppEngine(nativeDir), exec.asCoroutineDispatcher())
    val t0 = System.nanoTime()
    val info = adapter.load(LoadConfig(model, contextLength = ctx, threads = threads, batchSize = 256, ubatchSize = 256, kvCacheType = KvCacheType.F16, flashAttention = FlashAttention.AUTO))
    val loadMs = (System.nanoTime() - t0) / 1_000_000
    println("== model ${File(model).name} loaded in $loadMs ms (ctx ${info.contextLength}, threads $threads); VmHWM ${rssMb("VmHWM")} MB")

    suspend fun gen(sys: String, user: String, sampling: SamplingParams, grammar: String?, max: Int): Pair<String, GenerationStats> {
        val ev = adapter.generate(GenerationRequest(listOf(ChatMessage(Role.SYSTEM, sys), ChatMessage(Role.USER, user)), thinking = false,
            maxNewTokens = max, sampling = sampling, cachePrefix = false, grammar = grammar)).toList()
        val text = ev.filterIsInstance<GenerationEvent.Token>().joinToString("") { it.text }
        return text to ev.filterIsInstance<GenerationEvent.Completed>().single().stats
    }

    if (only == null || only == "spec") {
        val contact = "Contact card: Name: Priya Raghavan. Phone: +91 98480 22331. Email: priya.raghavan@example.in. " +
            "Address: Flat 402, Sai Residency, Road No. 12, Banjara Hills, Hyderabad 500034. Notes: prefers WhatsApp after 6 pm; birthday 14 March."
        val para = "On Monday the team met at the CBIT library to plan the hackathon. On Monday evening they listed the tools they need: " +
            "a phone with 8 GB of RAM, a laptop, and a charger. Monday's meeting ended with three tasks: build the app, test it on a OnePlus phone, " +
            "and write the README. Everyone agreed to meet again next Monday at 4 pm in the same room."
        val code = "fun fib(n: Int): Long {\n    if (n < 2) return n.toLong()\n    var a = 0L\n    var b = 1L\n    for (i in 2..n) {\n        val c = a + b\n        a = b\n        b = c\n    }\n    return b\n}"
        val jsonSchema = Schema.Obj(listOf("name" to Schema.Str(maxLength = 60), "phone" to Schema.Str(maxLength = 30), "email" to Schema.Str(maxLength = 60),
            "address" to Schema.Str(maxLength = 160), "notes" to Schema.Str(maxLength = 160)))
        data class Case(val name: String, val sys: String, val user: String, val grammar: String?, val max: Int)
        val cases = listOf(
            Case("json-args", "Extract the contact as JSON. Copy values exactly.", contact, Gbnf.of(jsonSchema), 160),
            Case("rewrite", "Rewrite the text exactly, only replacing every 'Monday' with 'Tuesday'. Output only the text.", para, null, 160),
            Case("code-edit", "Return the full Kotlin function unchanged, with a one-line KDoc comment added above it. Output only code.", code, null, 160),
            Case("prose", "You are a helpful assistant.", "Write a short paragraph about monsoon evenings in Hyderabad.", null, 120),
        )
        val samplings = listOf("greedy" to SamplingParams(temperature = 0f, topK = 1, topP = 1f, repeatPenalty = 1f, seed = 1),
            "seeded" to SamplingParams(temperature = 0.7f, topK = 20, topP = 0.8f, repeatPenalty = 1f, seed = 42))
        println("\n== n-gram self-speculation (draft $draft, n=2..4)")
        println("case        sampling  off tok/s   on tok/s  speed-up  drafted accepted  identical")
        val rows = mutableListOf<String>()
        for (c in cases) for ((sn, sp) in samplings) {
            check(adapter.setSpeculation(0), "spec off accepted")
            val (offText, off) = gen(c.sys, c.user, sp, c.grammar, c.max)
            check(adapter.setSpeculation(draft), "spec on accepted")
            val (onText, on) = gen(c.sys, c.user, sp, c.grammar, c.max)
            adapter.setSpeculation(0)
            val same = offText == onText
            val row = "%-11s %-8s %9.2f %10.2f %8.2fx %8d %8d  %s".format(c.name, sn, off.tokensPerSecond, on.tokensPerSecond,
                if (off.tokensPerSecond > 0) on.tokensPerSecond / off.tokensPerSecond else 0.0, on.draftedTokens, on.acceptedTokens, same)
            println(row); rows += row
            check(same, "${c.name}/$sn identical output with speculation on/off")
            if (!same) { println("    off: ${offText.take(200)}"); println("    on : ${onText.take(200)}") }
        }
        File(System.getenv("OUT") ?: "/tmp").resolve("phase6-spec-${File(model).nameWithoutExtension}.txt").writeText(rows.joinToString("\n"))
    }

    if (only == null || only == "bench") {
        // Same protocol as the app's benchmark screen (Agents → Models → Benchmark): ~256-token prompt, 64 generated tokens.
        println("\n== benchmark (pp ~256, tg 64)")
        val prompt = (1..40).joinToString(" ") { "item $it is a small offline phone assistant test sentence." }
        for (spec in listOf(false, true)) {
            adapter.setSpeculation(if (spec) draft else 0)
            val (_, st) = gen("You are a benchmark.", prompt + "\nGive 8 short tips for saving phone battery, one per line.", SamplingParams(temperature = 0f, topK = 1, repeatPenalty = 1f, seed = 1), null, 64)
            println("spec=%-5s prompt %4d tok @ %6.1f tok/s · gen %3d tok @ %5.2f tok/s · drafted %d accepted %d · VmHWM %d MB".format(
                spec, st.promptTokens, st.promptTokensPerSecond, st.generatedTokens, st.tokensPerSecond, st.draftedTokens, st.acceptedTokens, rssMb("VmHWM")))
        }
        adapter.setSpeculation(0)
    }

    adapter.unload()
    exec.shutdown()
    println(if (failures.isEmpty()) "\nALL PHASE 6 M1 CHECKS PASSED" else "\nFAILED: ${failures.size}\n" + failures.joinToString("\n"))
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

internal fun rssMb(key: String): Long = runCatching {
    File("/proc/self/status").readLines().first { it.startsWith("$key:") }.split(Regex("\\s+"))[1].toLong() / 1024
}.getOrDefault(-1)
