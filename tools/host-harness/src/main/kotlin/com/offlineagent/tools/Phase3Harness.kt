package com.offlineagent.tools

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.ContextPlanner
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.FlashAttention
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.GgufMetadata
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.ModelInfo
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.SamplingParams
import com.offlineagent.core.runtime.agent.AgentLoop
import com.offlineagent.core.runtime.agent.ApprovalGate
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.agent.RunCheckpoint
import com.offlineagent.core.runtime.agent.RunStatus
import com.offlineagent.core.runtime.agent.RunStore
import com.offlineagent.core.runtime.agent.StepKind
import com.offlineagent.core.runtime.context.ContextConfig
import com.offlineagent.core.runtime.context.ContextManager
import com.offlineagent.core.runtime.context.ConversationSummarizer
import com.offlineagent.core.runtime.context.TokenCounter
import com.offlineagent.core.runtime.context.Turn
import com.offlineagent.core.runtime.mcp.InProcessTransport
import com.offlineagent.core.runtime.mcp.McpHub
import com.offlineagent.core.runtime.mcp.McpTrust
import com.offlineagent.core.runtime.pdf.PdfContent
import com.offlineagent.core.runtime.pdf.PdfDocModel
import com.offlineagent.core.runtime.pdf.PdfWriter
import com.offlineagent.core.runtime.pdf.ReportWriter
import com.offlineagent.core.runtime.phone.AutomationHost
import com.offlineagent.core.runtime.phone.AutomationPolicy
import com.offlineagent.core.runtime.phone.Bounds
import com.offlineagent.core.runtime.phone.DevicePort
import com.offlineagent.core.runtime.phone.PhonePorts
import com.offlineagent.core.runtime.phone.PhoneServers
import com.offlineagent.core.runtime.phone.ScreenAction
import com.offlineagent.core.runtime.phone.ScreenAgent
import com.offlineagent.core.runtime.phone.ScreenAgentLimits
import com.offlineagent.core.runtime.phone.ScreenDriver
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.ScreenSnapshot
import com.offlineagent.core.runtime.tools.CalculatorTool
import com.offlineagent.core.runtime.tools.DateTimeTool
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.ToolRegistry
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Phase 3 host checks with REAL models through the real JNI bridge (KleidiAI-enabled CPU variants):
 *  speed (TTFT, prompt tok/s, decode tok/s), KV-cache types, 16K context needle test, prompt-prefix KV cache
 *  (in-memory and on-disk across a reload), context shifting, speculative decoding with a draft model,
 *  conversation summarisation, phone tools through the built-in MCP servers, the screen agent on a fake form,
 *  and section-by-section report -> PDF.
 * Env: MODEL (chat gguf), DRAFT (draft gguf, optional), THREADS, CTX (default 16384), ONLY (comma list), OUT dir.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: error("MODEL")
    val draft = System.getenv("DRAFT")
    val threads = (System.getenv("THREADS")?.toIntOrNull() ?: 2).coerceIn(1, 8)
    val ctx = System.getenv("CTX")?.toIntOrNull() ?: 16384
    val only = System.getenv("ONLY")?.split(',')?.toSet()
    val outDir = File(System.getenv("OUT") ?: "/tmp/phase3").apply { mkdirs() }
    val failures = mutableListOf<String>()
    fun check(cond: Boolean, what: String) { println((if (cond) "  PASS " else "  FAIL ") + what); if (!cond) failures += what }
    fun want(s: String) = only == null || s in only
    fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val engine = LlamaCppEngine(nativeDir)
    val cacheDir = File(outDir, "prompt-cache").apply { deleteRecursively(); mkdirs() }
    val adapter = EngineInferenceAdapter(engine, exec.asCoroutineDispatcher(),
        prefixCacheFile = { info, prefix -> com.offlineagent.engine.llamacpp.PrefixCacheFiles.path(cacheDir, info, prefix) })
    println("== system: " + adapter.systemInfo().lineSequence().filter { it.contains("KLEIDIAI") || it.startsWith("Device") || it.startsWith("Backends") || it.contains("CPU :") }.joinToString(" | ").take(600))

    val arch = GgufMetadata.arch(File(model))
    println("== GGUF: $arch")
    arch?.let {
        val plan = ContextPlanner.plan(8L * 1024 * 1024 * 1024 * 92 / 100, it)
        println("== 8 GB phone plan: default ctx ${plan.recommendedContext}, KV ${plan.recommendedKv}, max output ${plan.maxOutputTokens}")
        plan.options.forEach { o -> println("   %6d tokens: %s%s".format(o.tokens, o.estimate?.let { e -> ContextPlanner.gb(e.totalBytes) + " (KV " + ContextPlanner.gb(e.kvBytes) + ")" } ?: "?", o.warning?.let { w -> " — $w" } ?: "")) }
    }

    suspend fun load(c: Int, kv: KvCacheType, draftPath: String? = null, m: String = model): ModelInfo {
        val t0 = System.nanoTime()
        val info = adapter.load(LoadConfig(m, contextLength = c, threads = threads, kvCacheType = kv, flashAttention = FlashAttention.ON,
            batchSize = 512, ubatchSize = 512, draftModelPath = draftPath, draftTokens = 6, warmup = true))
        println("== loaded ${info.description} ctx=${info.contextLength} kv=$kv draft=${draftPath != null} canShift=${info.canShift} in ${ms(t0)} ms")
        return info
    }

    data class Run(val text: String, val stats: GenerationStats, val ttftMs: Long)
    suspend fun gen(messages: List<ChatMessage>, maxNew: Int, sampling: SamplingParams = SamplingParams(temperature = 0f, repeatPenalty = 1.0f), cache: Boolean = true): Run {
        val sb = StringBuilder(); var stats: GenerationStats? = null
        val t0 = System.nanoTime(); var ttft = -1L
        adapter.generate(GenerationRequest(messages, thinking = false, maxNewTokens = maxNew, sampling = sampling, cachePrefix = cache)).collect { ev ->
            when (ev) {
                is GenerationEvent.Token -> { if (ttft < 0) ttft = ms(t0); sb.append(ev.text) }
                is GenerationEvent.Completed -> stats = ev.stats
                else -> Unit
            }
        }
        return Run(sb.toString(), stats!!, ttft)
    }
    fun line(label: String, r: Run) = println("   %-34s TTFT %6d ms | prompt %5d tok @ %6.1f tok/s | decode %4d tok @ %5.2f tok/s | prefix=%d shifts=%d%s".format(
        label, r.ttftMs, r.stats.promptTokens, r.stats.promptTokensPerSecond, r.stats.generatedTokens, r.stats.tokensPerSecond,
        r.stats.prefixCache, r.stats.contextShifts, if (r.stats.draftedTokens > 0) " | draft acc %.0f%% (%d/%d)".format(r.stats.acceptanceRate * 100, r.stats.acceptedTokens, r.stats.draftedTokens) else ""))

    val sysLong = "You are an offline assistant on an Android phone. " + (1..60).joinToString(" ") { "Rule $it: answer clearly, cite facts from the user's data, keep privacy, never send anything without approval." }
    val q = "In two sentences, why does on-device AI help privacy?"

    // ---------------------------------------------------------------- 1. speed + KV types
    if (want("speed")) {
        println("\n== 1. Speed by KV-cache type (ctx 4096, greedy, 96 new tokens)")
        for (kv in listOf(KvCacheType.F16, KvCacheType.Q8_0, KvCacheType.Q4_0)) {
            load(4096, kv)
            val r = gen(listOf(ChatMessage(Role.SYSTEM, "You are concise."), ChatMessage(Role.USER, q)), 96, cache = false)
            line("kv=$kv", r)
            check(r.text.isNotBlank() && r.stats.stopReason.name != "ERROR", "generation works with KV $kv")
        }
    }

    // ---------------------------------------------------------------- 2. prompt-prefix cache
    if (want("prefix")) {
        println("\n== 2. Prompt-prefix KV cache (system prompt ~${sysLong.length / 4} tokens)")
        load(ctx, KvCacheType.Q8_0)
        val cold = gen(listOf(ChatMessage(Role.SYSTEM, sysLong), ChatMessage(Role.USER, q)), 24); line("cold (evaluate + save)", cold)
        val warm = gen(listOf(ChatMessage(Role.SYSTEM, sysLong), ChatMessage(Role.USER, "Name one benefit of offline apps.")), 24); line("warm (KV already holds prefix)", warm)
        load(ctx, KvCacheType.Q8_0) // reload = like an app restart
        val disk = gen(listOf(ChatMessage(Role.SYSTEM, sysLong), ChatMessage(Role.USER, "What is a GGUF file?")), 24); line("after reload (loaded from disk)", disk)
        check(cold.stats.prefixCache == 2, "cold run evaluated and saved the prefix (state=${cold.stats.prefixCache})")
        check(warm.stats.prefixCache == 0, "second run reused the in-memory prefix")
        check(disk.stats.prefixCache == 1, "after reload the prefix KV was loaded from disk")
        check(disk.ttftMs < cold.ttftMs, "first token after reload is faster than cold (${disk.ttftMs} vs ${cold.ttftMs} ms)")
        println("   prefix cache files: " + cacheDir.listFiles()?.joinToString { "${it.name.take(8)}… ${it.length() / 1024} KB" })
    }

    // ---------------------------------------------------------------- 3. long context needle
    if (want("long")) {
        val target = (System.getenv("LONG_TOKENS")?.toIntOrNull() ?: (ctx * 3 / 4))
        val longKv = System.getenv("LONG_KV")?.let { KvCacheType.valueOf(it) } ?: KvCacheType.Q8_0
        println("\n== 3. Long context: ~$target-token document at ctx $ctx ($longKv KV, flash attention)")
        load(ctx, longKv)
        // Digit-free filler, so the only number in the document is the needle's code.
        val topics = listOf("inventory checks", "safety drills", "loading dock rules", "visitor badges", "night shift handover", "fire exits",
            "forklift training", "cold storage", "returns handling", "packing standards", "cleaning rota", "first aid kits")
        val filler = (0 until 4000).joinToString(" ") { i -> "This part of the logistics manual explains ${topics[i % topics.size]} for the ${listOf("north", "south", "east", "west")[i % 4]} warehouse in plain language." }
        // HAYSTACK=<text file>: use real, varied prose (e.g. the project docs) instead of the repetitive filler.
        val hay = System.getenv("HAYSTACK")?.let { File(it).readText().replace(Regex("\\s+"), " ").trim() }
        val words = if (hay != null) generateSequence { hay.split(' ') }.take(20).flatten().toList() else filler.split(' ')
        var doc = ""; var n = 0
        val needle = "IMPORTANT: The locker code for the Hyderabad office is 4719."
        val sb = StringBuilder()
        for (w in words) { sb.append(w).append(' '); n++; if (n % 2000 == 0 && engine.countTokens(sb.toString())!! > target) break }
        doc = sb.toString()
        val mid = doc.length / 2
        doc = doc.substring(0, mid) + "\n$needle\n" + doc.substring(mid)
        val docTokens = engine.countTokens(doc) ?: 0
        val r = gen(listOf(ChatMessage(Role.SYSTEM, "Answer using the document."), ChatMessage(Role.USER, "Document:\n$doc\n\nQuestion: What is the locker code for the Hyderabad office? Reply with the number only.")), 16, cache = false)
        line("needle @ $docTokens doc tokens", r)
        check(r.text.contains("4719"), "found the needle in a ${docTokens}-token prompt (answer: ${r.text.trim().take(40)})")
        check(r.stats.promptTokens > ctx / 2, "prompt really was long (${r.stats.promptTokens} tokens)")
    }

    // ---------------------------------------------------------------- 4. context shifting
    if (want("shift")) {
        println("\n== 4. Context shifting (ctx 1024, generate 1400 tokens)")
        load(1024, KvCacheType.Q8_0)
        val r = gen(listOf(ChatMessage(Role.SYSTEM, "You are a storyteller."), ChatMessage(Role.USER, "Count from 1 to 3000, one number per line, no other text.")), 1400,
            sampling = SamplingParams(temperature = 0f, repeatPenalty = 1.0f))
        line("shift run", r)
        check(r.stats.contextShifts > 0, "window slid ${r.stats.contextShifts}x instead of stopping at the context limit")
        check(r.stats.generatedTokens > 1024, "generated past the context size (${r.stats.generatedTokens} tokens)")
    }

    // ---------------------------------------------------------------- 5. speculative decoding
    if (want("spec") && draft != null) {
        println("\n== 5. Speculative decoding (target ${File(model).name}, draft ${File(draft).name}, 6 draft tokens, greedy)")
        val prompt = listOf(ChatMessage(Role.SYSTEM, "You are concise."), ChatMessage(Role.USER, "List the first 12 months of the year with one fact each."))
        load(4096, KvCacheType.Q8_0)
        val base = gen(prompt, 160, cache = false); line("target only", base)
        load(4096, KvCacheType.Q8_0, draft)
        val spec = gen(prompt, 160, cache = false); line("target + draft", spec)
        check(spec.stats.draftedTokens > 0, "draft model proposed tokens")
        val same = base.text.take(200) == spec.text.take(200)
        check(same, "greedy output identical with speculation (first 200 chars)")
        if (!same) println("   base: ${base.text.take(200)}\n   spec: ${spec.text.take(200)}")
        println("   speed-up: %.2fx (%.2f -> %.2f tok/s)".format(spec.stats.tokensPerSecond / base.stats.tokensPerSecond.coerceAtLeast(0.01), base.stats.tokensPerSecond, spec.stats.tokensPerSecond))
    }

    // ---------------------------------------------------------------- 6. summarisation via ContextManager
    if (want("summary")) {
        println("\n== 6. ContextManager: rolling summary with the real model")
        load(4096, KvCacheType.Q8_0)
        val llm = InferenceAgentLlm(adapter)
        val summarizer = ConversationSummarizer { prev, turns, words ->
            val (sys, user) = ContextManager.summaryPrompt(prev, turns, words); llm.complete(sys, user, null, words * 2)
        }
        val counter = TokenCounter { engine.countTokens(it) ?: TokenCounter.estimate(it) }
        val facts = listOf("My name is Sai and I study at CBIT in Hyderabad.", "My exam is on 12 November.", "I prefer Telugu movies.", "My laptop is a ThinkPad.")
        // Varied, realistic assistant replies (a copy-paste reply repeated 30x made the 1.7B model loop under greedy decoding).
        val replies = listOf(
            "Nice to meet you, Sai! CBIT is a good college. Tell me what you are working on and I will help you plan it.",
            "Got it, your exam is on 12 November. We can make a revision timetable that leaves the last two days for practice papers.",
            "Telugu movies, noted. If you want, I can suggest a few classics for a weekend break after the exam.",
            "A ThinkPad is a solid choice for coding. Keep it updated and back up your notes regularly.")
        val turns = facts.flatMapIndexed { i, f -> listOf(Turn(i * 2L + 1, Role.USER, f + " " + "Some extra chatter about the day. ".repeat(40)), Turn(i * 2L + 2, Role.ASSISTANT, replies[i])) }
        val cm = ContextManager(counter, summarizer, null, ContextConfig(summaryWords = 80))
        val t0 = System.nanoTime()
        val plan = cm.build("You are helpful.", turns, null, "When is my exam and where do I study?", contextLength = 2048, maxOutputTokens = 256)
        println("   summary (${ms(t0)} ms): ${plan.summary?.text?.take(300)}")
        check(plan.summaryChanged, "old turns were folded into a summary")
        check(plan.promptTokens <= plan.budget, "prompt fits the window (${plan.promptTokens}/${plan.budget})")
        val ans = gen(plan.messages, 60, sampling = SamplingParams(seed = 42), cache = false) // app default sampling
        println("   answer: ${ans.text.trim().take(200)}")
        check(ans.text.contains("12") || ans.text.contains("November", true), "answer still knows the exam date from the summary/history")
    }

    // ---------------------------------------------------------------- 7. phone tools via built-in MCP servers
    if (want("phone")) {
        println("\n== 7. Phone tools through built-in MCP servers (agent loop, real model)")
        load(4096, KvCacheType.Q8_0)
        val calls = mutableListOf<String>()
        val device = object : DevicePort {
            override suspend fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>) = "Alarm set for %02d:%02d".format(hour, minute).also { calls += "alarm $hour:$minute" }
            override suspend fun setTimer(seconds: Int, label: String) = "Timer $seconds s".also { calls += "timer $seconds" }
            override suspend fun flashlight(on: Boolean) = "torch $on".also { calls += "torch $on" }
            override suspend fun setVolume(stream: String, percent: Int) = "ok"
            override suspend fun setRinger(mode: String) = "ok"
            override suspend fun setDoNotDisturb(on: Boolean) = "ok"
            override suspend fun openPanel(panel: String) = "ok"
            override suspend fun deviceInfo() = buildJsonObject { put("battery_percent", 42); put("charging", false) }
            override suspend fun takePhoto() = "ok"
        }
        val hub = McpHub()
        val ps = PhoneServers(PhonePorts(device = device))
        ps.build().forEach { hub.connect(it.name, it.name, InProcessTransport(it), McpTrust.BUILT_IN) }
        val reg = ToolRegistry(hub.tools() + CalculatorTool() + DateTimeTool())
        val store = object : RunStore { val m = HashMap<String, RunCheckpoint>(); override suspend fun save(checkpoint: RunCheckpoint) { m[checkpoint.runId] = checkpoint }; override suspend fun load(runId: String) = m[runId] }
        val approvals = mutableListOf<String>()
        val loop = AgentLoop(InferenceAgentLlm(adapter), reg, PolicyEngine(reg), store, ApprovalGate { _, spec, _, _ -> approvals += spec.name; true })
        for ((req, expect) in listOf("Set an alarm for 6:30 in the morning" to "alarm 6:30", "Turn on the flashlight" to "torch true", "Start a timer for 5 minutes" to "timer 300")) {
            val t0 = System.nanoTime()
            val cp = loop.start("r-" + req.hashCode(), null, req, null)
            val tools = cp.steps.filter { it.kind == StepKind.TOOL_CALL }.map { "${it.tool} ${it.input}" }
            println("   \"$req\" -> ${cp.status} in ${ms(t0)} ms; calls=$tools")
            check(cp.status == RunStatus.COMPLETED && calls.contains(expect), "\"$req\" performed '$expect' (approval asked: ${approvals.lastOrNull()})")
        }
        val battery = loop.start("r-batt", null, "How much battery do I have left?", null)
        println("   battery -> ${battery.finalAnswer?.take(120)}")
        check(battery.finalAnswer?.contains("42") == true, "device_info read without approval and answered 42%")
    }

    // ---------------------------------------------------------------- 8. screen agent on a fake form
    if (want("screen")) {
        println("\n== 8. Screen agent on a simulated internship form (real model chooses actions)")
        if (adapter.state.value !is com.offlineagent.core.inference.EngineState.Ready) load(4096, KvCacheType.Q8_0)
        fun n(text: String = "", hint: String = "", editable: Boolean = false, clickable: Boolean = false, value: String = "") =
            ScreenNode(className = if (editable) "android.widget.EditText" else "android.widget.TextView", text = if (editable) value else text, hint = hint,
                editable = editable, clickable = clickable, bounds = Bounds(0, 0, 500, 60))
        val values = HashMap<Int, String>()
        var submitted = false
        fun snap(): ScreenSnapshot {
            val nodes = mutableListOf(n("Internship application"), n(hint = "Full name", editable = true, value = values[2] ?: ""),
                n(hint = "Email", editable = true, value = values[3] ?: ""), n(hint = "College", editable = true, value = values[4] ?: ""), n("Submit", clickable = true))
            if (submitted) return ScreenSnapshot("com.example.jobs", ScreenNode(bounds = Bounds(0, 0, 1080, 2000), children = listOf(n("Thank you! Your application was received."))), "Jobs")
            return ScreenSnapshot("com.example.jobs", ScreenNode(bounds = Bounds(0, 0, 1080, 2000), children = nodes), "Jobs")
        }
        val driver = object : ScreenDriver {
            override suspend fun snapshot() = snap()
            override suspend fun perform(a: ScreenAction, s: ScreenSnapshot): Boolean {
                when (a) { is ScreenAction.Type -> values[a.element] = a.text; is ScreenAction.Tap -> if (a.element == 5) submitted = true; else -> Unit }
                return true
            }
        }
        val asked = mutableListOf<String>()
        val host = object : AutomationHost {
            override fun preview(step: Int, text: String) = println("     step $step: $text")
            override suspend fun approve(step: Int, action: String, reason: String): Boolean { asked += action; return true }
            override suspend fun ask(question: String): String? { println("     asked: $question"); return "skip" }
            override fun finished(summary: String) {}
        }
        val t0 = System.nanoTime()
        val out = ScreenAgent(InferenceAgentLlm(adapter), driver, host, AutomationPolicy(), { }, ScreenAgentLimits(maxSteps = 10, settleMillis = 0))
            .run("Fill in the internship application and submit it", "Full name: Sai Manoj Kura; Email: sai@example.com; College: CBIT Hyderabad")
        println("   result in ${ms(t0)} ms: $out; fields=$values")
        check(values.values.any { it.contains("Sai") } && values.values.any { it.contains("@") }, "agent typed the name and email into the right fields")
        check(!submitted || asked.isNotEmpty(), "submit only happened after approval")
        check(submitted, "form was submitted (after approval)")
    }

    // ---------------------------------------------------------------- 9. report -> PDF
    if (want("pdf")) {
        println("\n== 9. Write a report section by section, render to PDF")
        if (adapter.state.value !is com.offlineagent.core.inference.EngineState.Ready) load(4096, KvCacheType.Q8_0)
        val writer = ReportWriter(InferenceAgentLlm(adapter), sectionTokens = 350)
        val t0 = System.nanoTime()
        val outline = writer.outline("A short report on how students can prepare for campus placements", 4)
        println("   outline: ${outline.title} / ${outline.sections.map { it.heading }}")
        val md = writer.write("campus placement preparation", outline, onProgress = { println("     ${it.section}/${it.total} ${it.phase}") })
        val (bytes, layout) = PdfWriter.render(PdfDocModel(outline.title, PdfContent.fromMarkdown(md.substringAfter('\n')), subtitle = outline.subtitle, date = java.time.LocalDate.now().toString()))
        val f = File(outDir, "phase3-report.pdf").apply { writeBytes(bytes) }
        println("   ${md.length} chars of Markdown -> ${layout.pages.size} pages, ${bytes.size / 1024} KB in ${ms(t0)} ms -> ${f.absolutePath}")
        check(outline.sections.size >= 3, "outline has sections")
        check(layout.pages.size >= 3, "multi-page PDF (title + contents + body)")
        File(outDir, "phase3-report.md").writeText(md)
    }

    adapter.unload()
    exec.shutdown()
    println("\n== ${if (failures.isEmpty()) "ALL PASS" else "FAILURES: ${failures.size}"}")
    failures.forEach { println("   - $it") }
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
