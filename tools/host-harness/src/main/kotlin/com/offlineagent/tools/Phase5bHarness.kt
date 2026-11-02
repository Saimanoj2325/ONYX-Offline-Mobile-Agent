package com.offlineagent.tools

import com.offlineagent.core.agents.ActivityEvent
import com.offlineagent.core.agents.AgentDeps
import com.offlineagent.core.agents.AgentMessage
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Blackboard
import com.offlineagent.core.agents.ContextProvider
import com.offlineagent.core.agents.DeviceContext
import com.offlineagent.core.agents.InMemoryTaskStore
import com.offlineagent.core.agents.InferenceQueue
import com.offlineagent.core.agents.MemoryHit
import com.offlineagent.core.agents.MemoryStore
import com.offlineagent.core.agents.NodeStatus
import com.offlineagent.core.agents.Orchestrator
import com.offlineagent.core.agents.TaskStatus
import com.offlineagent.core.agents.ToolGateway
import com.offlineagent.core.inference.EngineInferenceAdapter
import com.offlineagent.core.inference.FlashAttention
import com.offlineagent.core.inference.KvCacheType
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.okJson
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Phase 5b END-TO-END: the real :core:agents Orchestrator on a REAL GGUF model (llama.cpp via the app's JNI bridge),
 * with fake device tools (calendar, reminders, notes, battery, torch) and a fake memory store. For each prompt it
 * prints the route, the plan, every model call with its real latency and output, step statuses, verification and the
 * final answer, and checks: plan valid, every step finished, verification ran, answer produced.
 * Env: MODEL, THREADS (2), CTX (4096), ONLY (comma list of prompt numbers).
 */
fun main(args: Array<String>): Unit = runBlocking {
    val nativeDir = args.getOrNull(0) ?: error("arg0 native dir")
    val model = System.getenv("MODEL") ?: error("MODEL")
    val threads = System.getenv("THREADS")?.toIntOrNull() ?: 2
    val ctx = System.getenv("CTX")?.toIntOrNull() ?: 4096
    val only = System.getenv("ONLY")?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet()
    val failures = mutableListOf<String>()
    fun check(cond: Boolean, what: String) { println((if (cond) "  PASS " else "  FAIL ") + what); if (!cond) failures += what }

    val exec = Executors.newSingleThreadExecutor { Thread(it, "llm-engine") }
    val adapter = EngineInferenceAdapter(LlamaCppEngine(nativeDir), exec.asCoroutineDispatcher())
    val t0 = System.nanoTime()
    val info = adapter.load(LoadConfig(model, contextLength = ctx, threads = threads, batchSize = 256, ubatchSize = 256, kvCacheType = KvCacheType.F16, flashAttention = FlashAttention.AUTO))
    println("== model ${File(model).name} loaded in ${(System.nanoTime() - t0) / 1_000_000} ms (ctx ${info.contextLength}, threads $threads)")
    val queue = InferenceQueue(InferenceAgentLlm(adapter))

    val prompts = listOf(
        "set a reminder at 7pm to call mom and tell me tomorrow's plan",
        "summarise my notes about the hackathon",
        "how's my battery?",
        "what is the capital of Australia?",
        "check my battery, turn on the torch, and save a note saying torch test done",
    )
    for ((i, prompt) in prompts.withIndex()) {
        if (only != null && (i + 1) !in only) continue
        val tools = FakeDeviceTools()
        val approvals = mutableListOf<ApprovalRequest>()
        val deps = AgentDeps(queue, tools, FakeNotes(), FakeCtx(), { r -> approvals += r; true }, { false }, { _, t, _, _ -> "outbox-$t" },
            { _, _, _, _, _ -> }, InMemoryTaskStore())
        val board = Blackboard("e2e-${i + 1}", prompt)
        val start = System.nanoTime()
        val cp = Orchestrator(deps).run(prompt, board)
        val totalMs = (System.nanoTime() - start) / 1_000_000
        println("\n================ ${i + 1}. \"$prompt\"")
        println("route: ${cp.route?.label} · plan by ${cp.plan?.source} · status ${cp.status} · total ${totalMs} ms")
        cp.plan?.nodes?.forEach { n -> println("  plan ${n.id} [${n.agent.title}] ${n.tool ?: ""} \"${n.goal}\" after=${n.dependsOn}") }
        board.eventLog().filterIsInstance<ActivityEvent.ModelCall>().forEach { c ->
            println("  model ${c.agent.title.padEnd(9)} ${c.key.padEnd(14)} ${c.millis.toString().padStart(6)} ms (waited ${c.waitedMillis} ms): ${c.output.replace('\n', ' ').take(220)}")
        }
        cp.nodes.forEach { n -> println("  step ${n.id.padEnd(6)} ${n.status.name.padEnd(14)} tries=${n.attempts} verified=${n.verified} ${n.output.replace('\n', ' ').take(140)}${n.error?.let { " ERR $it" } ?: ""}") }
        board.eventLog().filterIsInstance<ActivityEvent.Message>().mapNotNull { it.message as? AgentMessage.Verification }
            .forEach { v -> println("  verify ${v.nodeId}: ${if (v.passed) "passed" else "FAILED ${v.issues}"}") }
        println("  tool calls: ${tools.calls}")
        println("  approvals asked: ${approvals.map { "${it.tool}(${it.risk})" }}")
        println("  ANSWER: ${cp.answer?.replace('\n', ' ')?.take(600)}")
        val modelCalls = board.eventLog().count { it is ActivityEvent.ModelCall }
        println("  model calls: $modelCalls")
        check(cp.plan?.problems()?.isEmpty() == true, "${i + 1}: plan valid")
        check(cp.nodes.all { it.status.isTerminal }, "${i + 1}: every step finished")
        check(cp.status == TaskStatus.COMPLETED && !cp.answer.isNullOrBlank(), "${i + 1}: final answer produced")
        check(board.eventLog().any { it is ActivityEvent.Message && it.message is AgentMessage.Verification }, "${i + 1}: verification ran")
        if (cp.nodes.any { it.id.startsWith("s") }) check(cp.nodes.filter { it.id.startsWith("s") }.all { it.status == NodeStatus.DONE }, "${i + 1}: all tool steps DONE")
    }
    adapter.unload(com.offlineagent.core.inference.UnloadReason.values().first())
    exec.shutdown()
    println("\n== ${if (failures.isEmpty()) "ALL CHECKS PASSED" else "FAILURES: $failures"}")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private fun obj(vararg p: Pair<String, Schema>) = Schema.Obj(p.toList())

/** Realistic phone tools with the same names/shapes as the app; they record calls and return canned data. */
private class FakeDeviceTools : ToolGateway {
    val calls = mutableListOf<String>()
    private val specs = listOf(
        ToolSpec("get_datetime", "Current date, time and timezone.", obj(), Permission.READ_ONLY),
        ToolSpec("device_info", "Battery level, charging state, storage and RAM.", obj(), Permission.READ_ONLY),
        ToolSpec("list_events", "List calendar events in the next N days (or from a date).",
            obj("from" to Schema.Str("YYYY-MM-DD or empty for today", maxLength = 10), "days" to Schema.Num(min = 1.0, max = 60.0)), Permission.SENSITIVE_READ),
        // Same names and input schemas as the app's tools (Phase5Tools / BuiltinTools / PhoneServers).
        ToolSpec("schedule_exact_reminder", "Notify the user at an exact time (AlarmManager, works offline and in Doze).",
            obj("text" to Schema.Str(maxLength = 200, minLength = 1), "time" to Schema.Str("HH:MM 24h today/tomorrow, or empty", maxLength = 5), "in_minutes" to Schema.Num(min = 0.0, max = 43_200.0)),
            Permission.DEVICE_CONTROL, "Schedules a notification alarm."),
        ToolSpec("search_docs", "Search the user's imported documents (offline RAG). Returns the most relevant passages with titles.", obj("query" to Schema.Str("what to look for", maxLength = 200, minLength = 2), "k" to Schema.Num(min = 1.0, max = 6.0, integer = true)), Permission.READ_ONLY),
        ToolSpec("create_note", "Save a note/draft inside the app.", obj("title" to Schema.Str(maxLength = 120, minLength = 1), "body" to Schema.Str(maxLength = 4000, minLength = 1)), Permission.WRITE_LOCAL, "Creates a note in this app's private storage."),
        ToolSpec("flashlight", "Turn the torch on or off.", obj("on" to Schema.Bool()), Permission.DEVICE_CONTROL, "Switches the flashlight."),
        ToolSpec("set_alarm", "Set an alarm in the phone's Clock app.", obj("hour" to Schema.Num(min = 0.0, max = 23.0, integer = true), "minute" to Schema.Num(min = 0.0, max = 59.0, integer = true), "label" to Schema.Str(maxLength = 60)), Permission.EXTERNAL_INTENT, "Opens Clock."),
    )
    override fun specs() = specs
    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        calls += "$name$args"
        return when (name) {
            "get_datetime" -> okJson("now" to "Mon 2 Nov 2026 18:30", "timezone" to "Asia/Kolkata")
            "device_info" -> okJson("battery" to 64, "charging" to false, "storageFreeGb" to 41)
            "list_events" -> okJson("events" to "Tue 3 Nov: 09:30 Data structures lab; 13:00 Lunch with Ravi; 16:00 Hackathon team sync (online); 19:30 Gym")
            "schedule_exact_reminder" -> okJson("scheduled_for" to "2026-11-02T${(args["time"] as? kotlinx.serialization.json.JsonPrimitive)?.content}", "text" to (args["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content, "exact" to true)
            "search_docs" -> okJson("passages" to "[hackathon-notes.md] Theme: offline AI for farmers. Team: Sai, Ravi, Meena. Idea: crop disease detection from leaf photos on-device. Todo: collect 500 images by Friday, build Android demo, 3-min pitch video. Judges value offline + privacy.")
            "create_note" -> okJson("saved" to true, "title" to args["title"].toString())
            "flashlight" -> okJson("result" to "Flashlight on")
            else -> okJson("result" to "ok")
        }
    }
}

private class FakeNotes : MemoryStore {
    override val enabled = true
    override suspend fun recall(query: String, limit: Int): List<MemoryHit> {
        val all = listOf(
            MemoryHit("memory", "family", "Mom prefers calls after 7pm IST."),
            MemoryHit("memory", "hackathon", "Hackathon submission deadline is Sunday 8 Nov, 11:59 pm."),
            MemoryHit("memory", "style", "User prefers short answers in simple English."),
        )
        val q = query.lowercase()
        return all.filter { h -> h.text.lowercase().split(Regex("\\W+")).any { it.length > 3 && q.contains(it) } || h.title in q }.take(limit)
    }
}

private class FakeCtx : ContextProvider {
    override suspend fun snapshot(includeScreen: Boolean) = DeviceContext("Mon 2 Nov 2026, 18:30", "Asia/Kolkata", 64, false, online = false, network = "none")
}
