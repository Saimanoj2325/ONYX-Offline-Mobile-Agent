package com.offlineagent.tools

import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.ModelLoadException
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.StopReason
import com.offlineagent.core.inference.ThinkSplitter
import com.offlineagent.core.inference.UnloadReason
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Host smoke test of the REAL inference path (LlamaCppEngine -> JNI -> llama.cpp -> GGUF) through the
 * same EngineInferenceAdapter the app uses. Proves the bridge, template, streaming, KV reuse,
 * cancellation and error handling on a Linux JVM; it does not prove Android-specific behaviour.
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0)?.takeIf { it.isNotBlank() } ?: error("arg0 = native lib dir")
    val model = args.getOrNull(1)?.takeIf { it.isNotBlank() } ?: error("arg1 = model .gguf")
    val threads = (System.getenv("THREADS")?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()).coerceIn(1, 8)
    val failures = mutableListOf<String>()
    fun check(cond: Boolean, what: String) { println((if (cond) "  PASS " else "  FAIL ") + what); if (!cond) failures += what }

    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val adapter = EngineInferenceAdapter(LlamaCppEngine(nativeDir), exec.asCoroutineDispatcher())
    val sys = ChatMessage(Role.SYSTEM, "You are a helpful assistant running fully offline. Answer clearly and concisely.")

    suspend fun run(label: String, req: GenerationRequest): Pair<String, GenerationStats> {
        println("\n== $label (thinking=${req.thinking})")
        val sb = StringBuilder()
        var stats: GenerationStats? = null
        adapter.generate(req).collect { ev ->
            when (ev) {
                is GenerationEvent.Token -> { sb.append(ev.text); print(ev.text); System.out.flush() }
                is GenerationEvent.Completed -> stats = ev.stats
                is GenerationEvent.PromptProcessed -> println("[prompt ${ev.promptTokens} tok in ${ev.promptMillis} ms]")
                is GenerationEvent.PromptProgress -> Unit
            }
        }
        val s = stats!!
        println("\n[${s.generatedTokens} tok, %.1f tok/s, prompt %.1f tok/s, stop=${s.stopReason}]".format(s.tokensPerSecond, s.promptTokensPerSecond))
        return sb.toString() to s
    }

    println("== system info\n" + adapter.systemInfo())
    val t0 = System.nanoTime()
    val info = adapter.load(LoadConfig(model, contextLength = 2048, threads = threads))
    println("== loaded in ${(System.nanoTime() - t0) / 1_000_000} ms: ${info.copy(chatTemplate = "<${info.chatTemplate?.length ?: 0} chars>")}")
    check(adapter.state.value is EngineState.Ready, "state Ready after load")
    check(info.contextLength == 2048, "context 2048")
    check(info.chatTemplate?.contains("<|im_start|>") == true, "GGUF has ChatML template")

    val q1 = ChatMessage(Role.USER, "What is the capital of India? Answer in one sentence.")
    val (a1, s1) = run("turn 1", GenerationRequest(listOf(sys, q1), thinking = false, maxNewTokens = 96))
    check(s1.stopReason == StopReason.END_OF_TURN, "turn 1 ends with end-of-turn")
    check(a1.contains("Delhi", ignoreCase = true), "turn 1 mentions Delhi")
    check(!a1.contains("<think>") && !a1.contains("<|im_end|>"), "no template tokens leak into answer")
    val answer1 = ThinkSplitter.stripThinking(a1)

    val q2 = ChatMessage(Role.USER, "And what is the capital of Telangana?")
    val (a2, s2) = run("turn 2 (KV prefix reuse)", GenerationRequest(listOf(sys, q1, ChatMessage(Role.ASSISTANT, answer1), q2), thinking = false, maxNewTokens = 96))
    check(a2.contains("Hyderabad", ignoreCase = true), "turn 2 mentions Hyderabad (multi-turn context works)")
    check(s2.promptTokens > s1.promptTokens, "turn 2 prompt contains history")

    val (a3, s3) = run("unicode", GenerationRequest(listOf(sys, ChatMessage(Role.USER, "Write 'thank you' in Telugu script and Hindi script, nothing else.")), maxNewTokens = 48))
    check(!a3.contains('\uFFFD'), "no broken UTF-8 (U+FFFD) in multi-byte output")
    check(a3.any { it.code in 0x0C00..0x0C7F || it.code in 0x0900..0x097F }, "contains Telugu/Devanagari characters")

    // Cancellation: start a long answer, press "stop" after ~1.5 s.
    println("\n== cancel test")
    val job = async {
        run("long answer", GenerationRequest(listOf(sys, ChatMessage(Role.USER, "Write a detailed 800-word essay about the history of Hyderabad.")), maxNewTokens = 1500))
    }
    adapter.state.first { it is EngineState.Generating }
    delay(1500)
    val tc = System.nanoTime()
    adapter.cancel()
    val (_, sc) = job.await()
    val stopMs = (System.nanoTime() - tc) / 1_000_000
    println("[stopped ${stopMs} ms after cancel()]")
    check(sc.stopReason == StopReason.CANCELLED, "stop button -> CANCELLED")
    check(stopMs < 1000, "generation stopped within 1 s of cancel (was $stopMs ms)")
    check(adapter.state.value is EngineState.Ready, "model still loaded after cancel")

    val (a4, s4) = run("thinking ON", GenerationRequest(listOf(sys, ChatMessage(Role.USER, "Is 17 a prime number? Answer yes or no.")), thinking = true, maxNewTokens = 400))
    val parts = ThinkSplitter.split(a4)
    println("[reasoning ${parts.reasoning.length} chars, answer='${parts.answer.take(80)}', stop=${s4.stopReason}]")
    check(a4.contains("</think>") || s4.stopReason == StopReason.MAX_TOKENS, "thinking mode emits a think block")

    adapter.unload(UnloadReason.LOW_MEMORY)
    check(adapter.state.value == EngineState.Unloaded(UnloadReason.LOW_MEMORY), "unload -> Unloaded(LOW_MEMORY)")

    val bogus = File.createTempFile("bogus", ".gguf").apply { writeText("not a model"); deleteOnExit() }
    val err = runCatching { adapter.load(LoadConfig(bogus.absolutePath, threads = threads)) }.exceptionOrNull()
    println("\n== bad file -> ${err?.javaClass?.simpleName}: ${err?.message}")
    check(err is ModelLoadException, "corrupt GGUF -> ModelLoadException")
    check(adapter.state.value is EngineState.Failed, "state Failed after bad load")

    // Reload works after failure.
    adapter.load(LoadConfig(model, threads = threads))
    check(adapter.state.value is EngineState.Ready, "reload after failure works")
    adapter.unload()

    exec.shutdown()
    println("\n== RESULT: ${if (failures.isEmpty()) "ALL PASS" else "FAILURES: $failures"}")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
