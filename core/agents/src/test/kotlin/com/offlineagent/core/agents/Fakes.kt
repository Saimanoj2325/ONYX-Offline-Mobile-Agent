package com.offlineagent.core.agents

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.okJson
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

class FakeTools : ToolGateway {
    val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
    var failDeviceInfo = 0
    var fetchError: ToolResult? = null
    private val specs = listOf(
        ToolSpec("device_info", "Battery level, storage and RAM.", Schema.Obj(emptyList()), Permission.READ_ONLY),
        ToolSpec("recent_notifications", "Recent notifications.", Schema.Obj(listOf("limit" to Schema.Num(min = 1.0, max = 50.0), "app" to Schema.Str(maxLength = 60))), Permission.SENSITIVE_READ),
        ToolSpec("flashlight", "Turn the torch on or off.", Schema.Obj(listOf("on" to Schema.Bool())), Permission.DEVICE_CONTROL, "Switches the flashlight."),
        ToolSpec("fetch_url", "Fetch a web page.", Schema.Obj(listOf("url" to Schema.Str(maxLength = 500, minLength = 5))), Permission.EXTERNAL_INTENT, "Downloads a page."),
        ToolSpec("get_datetime", "Current date and time.", Schema.Obj(emptyList()), Permission.READ_ONLY),
    )
    override fun specs() = specs
    override fun requiresNetwork(name: String) = name == "fetch_url"
    override suspend fun execute(name: String, args: JsonObject): ToolResult {
        calls += name to args
        return when (name) {
            "device_info" -> if (failDeviceInfo-- > 0) ToolResult.Error("battery service unavailable") else okJson("battery" to 81, "charging" to false)
            "flashlight" -> okJson("result" to "Flashlight on")
            "fetch_url" -> fetchError ?: okJson("text" to "page body")
            "recent_notifications" -> okJson("notifications" to "[]")
            else -> okJson("now" to "2026-11-02T22:00")
        }
    }
}

/** Scripted model: answers by which agent prompt it sees. Records max concurrency to prove serialization. */
class FakeLlm(var plan: String = """{"steps":[]}""", var answer: String = "Your battery is at 81%.") : AgentLlm {
    val prompts = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()
    var verifierOk = true
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
        val now = active.incrementAndGet(); maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            kotlinx.coroutines.delay(5)
            prompts += system.take(30)
            return when {
                system.startsWith("You plan") -> plan
                user.startsWith("Tool: flashlight") -> """{"on":true}"""
                user.startsWith("Tool: recent_notifications") -> """{"limit":5,"app":""}"""
                user.startsWith("Tool: fetch_url") -> """{"url":"https://example.com"}"""
                system.startsWith("Check an assistant") -> if (verifierOk) """{"ok":true,"issue":""}""" else """{"ok":false,"issue":"wrong"}"""
                else -> answer
            }
        } finally { active.decrementAndGet() }
    }
}

class FakeContext : ContextProvider {
    val calls = AtomicInteger()
    var online = true
    override suspend fun snapshot(includeScreen: Boolean): DeviceContext { calls.incrementAndGet(); return DeviceContext("2026-11-02T22:00", "Asia/Kolkata", 81, false, online = online, network = "wifi") }
}

class FakeMemory(override var enabled: Boolean = true) : MemoryStore {
    val remembered = mutableListOf<String>()
    override suspend fun recall(query: String, limit: Int) = listOf(MemoryHit("memory", "battery", "User charges the phone at night"))
    override suspend fun remember(text: String, source: String) { remembered += text }
}

class Harness(llm: AgentLlm? = null, approve: Boolean = true, online: Boolean = true) {
    val tools = FakeTools()
    val ctx = FakeContext().also { it.online = online }
    val memory = FakeMemory()
    val store = InMemoryTaskStore()
    val outboxStore = InMemoryOutboxStore()
    val approvals = mutableListOf<ApprovalRequest>()
    val audit = mutableListOf<String>()
    var isOnline = online
    val queue = llm?.let { InferenceQueue(it) }
    val outbox = OutboxProcessor(outboxStore, tools, { isOnline })
    val deps = AgentDeps(queue, tools, memory, ctx, { r -> approvals += r; approve }, { isOnline },
        { run, tool, args, risk -> outbox.enqueue(run, tool, args, risk) }, { _, actor, action, _, _ -> audit += "$actor:$action" }, store)
    val orchestrator = Orchestrator(deps)
}
