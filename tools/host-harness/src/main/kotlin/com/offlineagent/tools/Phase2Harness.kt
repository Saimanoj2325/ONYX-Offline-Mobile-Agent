package com.offlineagent.tools

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EmbeddingService
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.ImageData
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.Role
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.agent.AgentLoop
import com.offlineagent.core.runtime.agent.ApprovalGate
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.agent.RunCheckpoint
import com.offlineagent.core.runtime.agent.RunStatus
import com.offlineagent.core.runtime.agent.RunStore
import com.offlineagent.core.runtime.agent.STRUCTURED_SAMPLING
import com.offlineagent.core.runtime.agent.StepKind
import com.offlineagent.core.runtime.rag.ChunkCandidate
import com.offlineagent.core.runtime.rag.Chunker
import com.offlineagent.core.runtime.rag.HybridRanker
import com.offlineagent.core.runtime.rag.RagPrompt
import com.offlineagent.core.runtime.skills.BuiltinSkills
import com.offlineagent.core.runtime.tools.CalculatorTool
import com.offlineagent.core.runtime.tools.CanvasPort
import com.offlineagent.core.runtime.tools.DateTimeTool
import com.offlineagent.core.runtime.tools.DocInfo
import com.offlineagent.core.runtime.tools.DocumentPort
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.ReadDocumentTool
import com.offlineagent.core.runtime.tools.RenderUiTool
import com.offlineagent.core.runtime.tools.SearchDocsTool
import com.offlineagent.core.runtime.tools.SearchHit
import com.offlineagent.core.runtime.tools.ToolRegistry
import com.offlineagent.core.runtime.ui.CanvasPrompts
import com.offlineagent.core.runtime.ui.UiDoc
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import com.offlineagent.engine.llamacpp.LlamaEmbeddingEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Phase 2 host checks with REAL models through the real JNI bridge:
 *  1. grammar-constrained Canvas UI JSON -> UiParser success
 *  2. agent loop with a real model: plan + grammar-constrained tool call (calculator) + final
 *  3. embeddings (bge-small) + hybrid retrieval picks the right passage; grounded answer with [n] citation
 *  4. (optional) image input via libmtmd with a small VLM + mmproj
 * Env: MODEL (chat gguf), EMBED (embedding gguf), VLM + MMPROJ (optional), THREADS.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: args.getOrNull(1) ?: error("MODEL")
    val embedPath = System.getenv("EMBED")
    val vlm = System.getenv("VLM")
    val mmproj = System.getenv("MMPROJ")
    val only = System.getenv("ONLY")?.split(',')?.toSet()
    val threads = (System.getenv("THREADS")?.toIntOrNull() ?: 2).coerceIn(1, 8)
    val failures = mutableListOf<String>()
    fun check(cond: Boolean, what: String) { println((if (cond) "  PASS " else "  FAIL ") + what); if (!cond) failures += what }
    fun want(s: String) = only == null || s in only

    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val engine = LlamaCppEngine(nativeDir)
    val adapter = EngineInferenceAdapter(engine, exec.asCoroutineDispatcher())
    val info = adapter.load(LoadConfig(model, contextLength = 4096, threads = threads))
    println("== loaded ${info.description} (${File(model).name})")

    // ---------------------------------------------------------------- 1. Canvas JSON under grammar
    if (want("canvas")) {
        println("\n== 1. Canvas (grammar-constrained UI JSON)")
        val llm = InferenceAgentLlm(adapter)
        val prompts = listOf(
            "Compare Python, Java and JavaScript for a beginner: a comparison of ease, speed and jobs, and a bar chart of an ease score out of 10.",
            "Make a 4-step checklist to prepare for a job interview.",
        )
        for (p in prompts) {
            val t0 = System.nanoTime()
            val raw = llm.complete(CanvasPrompts.system(), CanvasPrompts.newCanvasUser(p), CanvasPrompts.grammar(), 900)
            val ms = (System.nanoTime() - t0) / 1_000_000
            println(raw.take(1200))
            val r = UiParser.parse(raw)
            check(r is UiParseResult.Success, "canvas JSON parses+validates (${ms} ms, ${raw.length} chars): ${p.take(40)}")
            if (r is UiParseResult.Success) println("   blocks: " + r.doc.blocks.joinToString { it.type } + (if (r.warnings.isNotEmpty()) "  warnings=${r.warnings}" else ""))
        }
        // update in place
        val first = UiParser.parse(llm.complete(CanvasPrompts.system(), CanvasPrompts.newCanvasUser("A table of 4 planets with their diameter in km."), CanvasPrompts.grammar(), 700))
        if (first is UiParseResult.Success) {
            val upd = llm.complete(CanvasPrompts.system(), CanvasPrompts.updateUser("Add a bar chart of the diameters.", first.doc), CanvasPrompts.grammar(), 900)
            val r2 = UiParser.parse(upd)
            check(r2 is UiParseResult.Success && r2.doc.blocks.any { it.type == "chart" }, "canvas update adds a chart")
            if (r2 is UiParseResult.Success) println("   updated blocks: " + r2.doc.blocks.joinToString { it.type })
        } else check(false, "first canvas for update test")
    }

    // ---------------------------------------------------------------- 2. Agent with real tool call
    if (want("agent")) {
        println("\n== 2. Agent loop (plan -> grammar tool call -> observe -> final)")
        val canvasDocs = mutableListOf<UiDoc>()
        val canvas = object : CanvasPort {
            override suspend fun render(doc: UiDoc, newCanvas: Boolean): Int { canvasDocs += doc; return canvasDocs.size }
            override suspend fun current() = canvasDocs.lastOrNull()
        }
        val registry = ToolRegistry(listOf(CalculatorTool(), DateTimeTool(), RenderUiTool(canvas, update = false)))
        val store = object : RunStore {
            val m = HashMap<String, RunCheckpoint>()
            override suspend fun save(checkpoint: RunCheckpoint) { m[checkpoint.runId] = checkpoint }
            override suspend fun load(runId: String) = m[runId]
        }
        val loop = AgentLoop(InferenceAgentLlm(adapter), registry, PolicyEngine(registry), store, ApprovalGate { _, _, _, _ -> true })
        val t0 = System.nanoTime()
        val r = loop.start("h1", null, "What is 17.5% of 2480? Use the calculator tool, then answer with the number.", null)
        println("   status=${r.status} iterations=${r.iteration} in ${(System.nanoTime() - t0) / 1_000_000} ms")
        println("   plan=${r.plan}")
        r.steps.forEach { println("   step ${it.index} ${it.kind} ${it.tool ?: ""} in=${it.input.take(120)} out=${it.output.take(160)} err=${it.error ?: ""}") }
        val calc = r.steps.firstOrNull { it.kind == StepKind.TOOL_CALL && it.tool == "calculator" && it.error == null }
        check(calc != null, "agent made a valid calculator tool call")
        check(calc?.output?.contains("434") == true, "calculator returned 434")
        check(r.status == RunStatus.COMPLETED, "agent run completed")
        check(r.finalAnswer?.contains("434") == true, "final answer contains 434")

        println("\n== 2b. Skill run (compare-options -> canvas)")
        val skill = BuiltinSkills.load(registry.names).first { it.id == "compare-options" }.copy(tools = listOf("calculator", "render_ui"))
        val r2 = loop.start("h2", null, "Compare tea and coffee for a student who studies at night.", skill)
        r2.steps.forEach { println("   step ${it.index} ${it.kind} ${it.tool ?: ""} err=${it.error ?: ""} out=${it.output.take(100)}") }
        check(r2.status == RunStatus.COMPLETED && r2.canvasRendered, "skill run rendered a canvas (${canvasDocs.lastOrNull()?.blocks?.joinToString { it.type }})")
    }

    // ---------------------------------------------------------------- 3. Embeddings + RAG
    if (want("rag") && embedPath != null) {
        println("\n== 3. Embeddings + hybrid retrieval + grounded answer")
        val emb = EmbeddingService(LlamaEmbeddingEngine(nativeDir), Executors.newSingleThreadExecutor { Thread(it, "embed-engine") }.asCoroutineDispatcher())
        emb.ensureLoaded(embedPath, threads)
        println("   embedding dim=${emb.dimension}")
        val doc = """
            The Chaitanya Library opens at 8 am and closes at 9 pm on weekdays. On Sundays it is closed.

            Students can borrow up to four books at a time for fourteen days. Late returns cost five rupees per day.

            The physics lab is on the second floor of Block C. Lab coats are mandatory and food is not allowed.

            Photosynthesis converts light energy into chemical energy; chlorophyll in the leaves absorbs mostly red and blue light.

            The canteen serves idli and dosa for breakfast from 7:30 am, and lunch from 12:30 pm to 2:30 pm.
        """.trimIndent()
        val chunks = Chunker.chunk(doc, maxChars = 200, overlapChars = 0)
        val t0 = System.nanoTime()
        val cands = chunks.map { c -> ChunkCandidate(c.index.toLong(), 1, "Campus guide", c.text, emb.embed(c.text)) }
        println("   embedded ${cands.size} chunks in ${(System.nanoTime() - t0) / 1_000_000} ms")
        val queries = mapOf(
            "How many books can I take out and what is the fine?" to "four books",
            "When can I eat lunch?" to "lunch",
            "Which colours of light do leaves absorb?" to "chlorophyll",
            "Where is the physics lab?" to "Block C",
        )
        for ((q, expect) in queries) {
            val top = HybridRanker.rank(q, emb.embed(q), cands, k = 2)
            println("   '$q' -> ${top.joinToString { "%.2f(v%.2f k%.2f) %s".format(it.score, it.vectorScore, it.keywordScore, it.candidate.text.take(40)) }}")
            check(top.firstOrNull()?.candidate?.text?.contains(expect) == true, "retrieval top-1 for '$q'")
        }
        // pure-vector check (no keyword overlap): semantic match
        val vecOnly = HybridRanker.rank("closing time", emb.embed("At what hour does the reading room shut?"), cands, k = 1, vectorWeight = 1f)
        check(vecOnly.firstOrNull()?.candidate?.text?.contains("Library") == true, "pure vector similarity finds the library hours")
        // grounded answer with citation
        val top = HybridRanker.rank("How many books can I borrow?", emb.embed("How many books can I borrow?"), cands, k = 3)
        val sb = StringBuilder()
        adapter.generate(GenerationRequest(listOf(
            ChatMessage(Role.SYSTEM, "You are a helpful offline assistant.\n\n" + RagPrompt.context(top)),
            ChatMessage(Role.USER, "How many books can I borrow?"),
        ), maxNewTokens = 120)).collect { if (it is GenerationEvent.Token) sb.append(it.text) }
        println("   answer: ${sb.toString().trim()}")
        check(sb.contains("4") || sb.contains("four", ignoreCase = true), "grounded answer says four")
        println("   citations used: ${RagPrompt.citedNumbers(sb.toString(), top.size)}")
        emb.unload()
    }

    adapter.unload()

    // ---------------------------------------------------------------- 4. Vision via libmtmd
    if (want("vision") && vlm != null && mmproj != null) {
        println("\n== 4. Image input (libmtmd)")
        val vinfo = adapter.load(LoadConfig(vlm, contextLength = 4096, threads = threads, mmprojPath = mmproj))
        check(vinfo.supportsImages, "vision projector attached")
        println("   template: ${vinfo.chatTemplate?.take(80)?.replace("\n", " ")}")
        val tmpl = com.offlineagent.core.inference.PromptTemplates.forModel(vinfo, engine)
        println("   prompt via ${tmpl.name}: " + tmpl.render(listOf(ChatMessage(Role.USER, "hi", images = listOf(ImageData(ByteArray(3), 1, 1)))), false).replace("\n", "\\n"))
        val img = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, 256, 256)
        g.color = Color.RED; g.fillOval(48, 48, 160, 160)
        g.dispose()
        val rgb = ByteArray(256 * 256 * 3)
        for (y in 0 until 256) for (x in 0 until 256) {
            val p = img.getRGB(x, y); val i = (y * 256 + x) * 3
            rgb[i] = (p shr 16 and 0xFF).toByte(); rgb[i + 1] = (p shr 8 and 0xFF).toByte(); rgb[i + 2] = (p and 0xFF).toByte()
        }
        val sb = StringBuilder()
        val t0 = System.nanoTime()
        var promptMs = 0L
        var promptTok = 0
        adapter.generate(GenerationRequest(listOf(
            ChatMessage(Role.USER, "What shape and colour is in this image? Answer in one short sentence.", images = listOf(ImageData(rgb, 256, 256))),
        ), maxNewTokens = 40, sampling = STRUCTURED_SAMPLING)).collect {
            when (it) { is GenerationEvent.Token -> sb.append(it.text); is GenerationEvent.PromptProcessed -> { promptMs = it.promptMillis; promptTok = it.promptTokens }; else -> Unit }
        }
        println("   answer: ${sb.toString().trim()}  (image+prompt ${promptTok} tokens in ${promptMs} ms, total ${(System.nanoTime() - t0) / 1_000_000} ms)")
        check(sb.contains("red", ignoreCase = true) || sb.contains("circle", ignoreCase = true), "VLM sees a red circle")
        // a text-only turn after an image must work (KV reset path)
        val sb2 = StringBuilder()
        adapter.generate(GenerationRequest(listOf(ChatMessage(Role.USER, "Say hello.")), maxNewTokens = 20)).collect { if (it is GenerationEvent.Token) sb2.append(it.text) }
        check(sb2.isNotBlank(), "text turn after image works: ${sb2.toString().trim().take(40)}")
        adapter.unload()
    }

    exec.shutdownNow()
    println("\n== RESULT: ${if (failures.isEmpty()) "ALL PASS" else "FAILURES: $failures"}")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
