package com.offlineagent.core.screen

import com.offlineagent.core.agents.Risk
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.int
import com.offlineagent.core.runtime.tools.str
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The screen_* tools as normal [ToolSpec]s for the shared registry, so the Phase 5 Planner can mix them with device
 * tools in one task graph, the Executor stays behind ApprovalGate (wrapped by [approvals] for per-app scopes) and
 * every call is audited. One screen → one session; calls are serialized by a mutex (parallel DAG branches never
 * interleave taps).
 */
class ScreenToolkit(
    port: ScreenPort,
    host: ScreenHost,
    policy: ScreenSafetyPolicy,
    audit: ScreenAudit,
    kill: KillSwitch,
    cards: AppCardStore,
    llm: () -> ScreenLlm? = { null },
    config: () -> ScreenConfig = { ScreenConfig() },
    clock: () -> Long = System::currentTimeMillis,
    private val portRef: ScreenPort = port,
) {
    val session = ScreenSession(port, host, policy, audit, kill, cards, llm, config, clock)
    private val mutex = Mutex()

    val approvals = ScreenApprovals(
        targetPackage = { tool, args -> targetPackage(tool, args) },
        policy = policy, audit = audit, appLabel = { portRef.appLabel(it) }, stepsPerScope = { config().stepsPerScope },
    ).also { a -> session.criticalPreApproved = { tool, args -> a.consumeCritical(tool, args) } }

    /** Which app a call will touch (for scoped approvals): the app it opens / transfers to, else the foreground app. */
    suspend fun targetPackage(tool: String, args: JsonObject): String? = when (tool) {
        "screen_open_app" -> portRef.resolveApp(args.str("app"))
        "screen_transfer" -> portRef.resolveApp(args.str("to_app"))
        "screen_app_card" -> null
        else -> session.currentPackage()
    }

    /** Risk per call: reads MEDIUM, actions HIGH, irreversible targets / submit / share CRITICAL (per-step, never scoped). */
    fun risk(name: String, args: JsonObject): Risk? = when (name) {
        "screen_snapshot", "screen_find", "screen_wait", "screen_assert", "screen_extract" -> Risk.MEDIUM
        "screen_app_card" -> Risk.LOW
        "screen_open_app" -> Risk.HIGH
        "screen_transfer" -> if (args.str("method") == "share") Risk.CRITICAL else Risk.HIGH
        "screen_do" -> if (Irreversible.text(args.str("instruction"))) Risk.CRITICAL else Risk.HIGH
        "screen_act" -> {
            val action = args.str("action")
            val v = session.latest
            val el = when {
                v == null -> null
                args.str("ref").isNotBlank() -> Regex("e(\\d+)$").find(args.str("ref").trim())?.groupValues?.get(1)?.toIntOrNull()?.let { v.element(it) }
                args.str("target").isNotBlank() -> Grounding.unique(v, args.str("target"))
                else -> null
            }
            when {
                action == "enter" -> Risk.CRITICAL
                (action == "tap" || action == "long_press") && (el?.let { session.policy.isIrreversible(it.node) } ?: Irreversible.text(args.str("target"))) -> Risk.CRITICAL
                else -> Risk.HIGH
            }
        }
        else -> null
    }

    private fun key(name: String, args: JsonObject) = name + "|" + args.toString()

    private fun tool(spec: ToolSpec, run: suspend (JsonObject) -> ToolResult) = object : Tool {
        override val spec = spec
        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = mutex.withLock {
            try { run(args) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ToolResult.Error(e.message ?: e.toString()) }
        }
    }

    private fun obj(vararg p: Pair<String, Schema>) = Schema.Obj(p.toList())
    private fun s(max: Int, min: Int = 0, d: String? = null, enum: List<String>? = null) = Schema.Str(d, max, min, enum)
    private fun bool(a: JsonObject, k: String, def: Boolean) = (a[k] as? JsonPrimitive)?.booleanOrNull ?: def

    fun tools(): List<Tool> = listOf(
        tool(ToolSpec("screen_snapshot", "Read the app on screen: compact skeleton of regions (@r1…) and elements with refs like @e12; root=@r2 drills into one region.",
            obj("mode" to s(8, d = "skeleton or full", enum = listOf("skeleton", "full")), "root" to s(12, d = "region ref like @r2, or empty")),
            Permission.SENSITIVE_READ, "Reads the content of the app on screen.")) { a -> session.snapshot(a.str("mode"), a.str("root")) },
        tool(ToolSpec("screen_find", "Find elements on screen by text or label (returns refs).",
            obj("text" to s(80, 1), "role" to s(10, enum = listOf("", "button", "input", "text", "checkbox", "list", "image"))),
            Permission.SENSITIVE_READ, "Reads the screen.")) { a -> session.find(a.str("text"), a.str("role")) },
        tool(ToolSpec("screen_wait", "Wait until a text appears (or disappears) on screen, or time out.",
            obj("text" to s(120, 1), "gone" to Schema.Bool(), "timeout_ms" to Schema.Num(min = 0.0, max = 20000.0, integer = true)),
            Permission.SENSITIVE_READ, "Reads the screen.", timeoutMs = 25_000)) { a -> session.waitUntil(a.str("text"), bool(a, "gone", false), a.int("timeout_ms", 4000).toLong()) },
        tool(ToolSpec("screen_assert", "Check that a text is (or is not) visible on screen — verifies a step's expected outcome.",
            obj("text" to s(120, 1), "present" to Schema.Bool()), Permission.SENSITIVE_READ, "Reads the screen.")) { a -> session.assert(a.str("text"), bool(a, "present", true)) },
        tool(ToolSpec("screen_extract", "Extract and copy values from the app on screen into JSON fields (order id, total, name…).",
            obj("fields" to Schema.Arr(s(40, 1), minItems = 1, maxItems = 8)), Permission.SENSITIVE_READ, "Reads values from the screen.")) { a ->
            session.extract((a["fields"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } } ?: emptyList())
        },
        tool(ToolSpec("screen_open_app", "Open or switch to an installed app by name (launch intent) and read its screen.",
            obj("app" to s(80, 1)), Permission.EXTERNAL_INTENT, "Opens another app.")) { a -> session.openApp(a.str("app")) },
        tool(ToolSpec("screen_act", "Tap, long-press, type, press enter, scroll, back or home on the screen. Target an element by ref (@e12) or label; expect = text that should appear after.",
            obj("ref" to s(16, d = "element ref like @e12, or empty"), "target" to s(80, d = "element label when no ref, or empty"),
                "action" to s(12, enum = listOf("tap", "long_press", "type", "enter", "scroll_down", "scroll_up", "back", "home")),
                "text" to s(1000, d = "text to type; {{field}} inserts an extracted value"), "expect" to s(120, d = "text expected after the step, or empty")),
            Permission.SCREEN_ACTION, "Taps or types in another app.", timeoutMs = 30_000)) { a ->
            session.act(a.str("ref"), a.str("target"), a.str("action"), a.str("text"), a.str("expect"), key("screen_act", a), a)
        },
        tool(ToolSpec("screen_transfer", "Paste or type text (e.g. a value copied with screen_extract, {{field}}) into a field in another app, or share it to that app. Does not press Send.",
            obj("text" to s(2000, 1), "to_app" to s(80, 1), "target" to s(80, d = "label/hint of the text field, or empty for the focused field"),
                "method" to s(6, enum = listOf("auto", "type", "paste", "share"))),
            Permission.SCREEN_ACTION, "Puts text into another app.", timeoutMs = 30_000)) { a ->
            session.transfer(a.str("text"), a.str("to_app"), a.str("target"), a.str("method").ifBlank { "auto" }, key("screen_transfer", a))
        },
        tool(ToolSpec("screen_do", "Do ONE step on the screen described in words (the model picks the element; stops early once it is unambiguous).",
            obj("instruction" to s(200, 3), "expect" to s(120)), Permission.SCREEN_ACTION, "Taps or types in another app.", timeoutMs = 60_000)) { a ->
            session.doStep(a.str("instruction"), a.str("expect"), key("screen_do", a), a)
        },
        tool(ToolSpec("screen_app_card", "Read or edit your saved notes (app card) for an app: get, set, append or delete.",
            obj("package" to s(120, d = "package, or empty for the app on screen"), "op" to s(6, enum = listOf("get", "set", "append", "delete")), "text" to s(1000)),
            Permission.WRITE_LOCAL, "Changes ONYX's notes about an app.")) { a ->
            val pkg = a.str("package").ifBlank { session.currentPackage() ?: "" }
            if (pkg.isBlank()) return@tool ToolResult.Error("no app given and none on screen", false)
            when (a.str("op")) {
                "set" -> session.cards.put(pkg, a.str("text"))
                "append" -> session.cards.put(pkg, (session.cards.get(pkg).orEmpty() + "\n- " + a.str("text")).trim())
                "delete" -> session.cards.delete(pkg)
            }
            ToolResult.Ok(buildJsonObject { put("package", pkg); put("card", session.cards.get(pkg).orEmpty()) }, "App card for $pkg:\n" + (session.cards.get(pkg) ?: "(empty)"))
        },
    )

    companion object {
        val NAMES = setOf("screen_snapshot", "screen_find", "screen_wait", "screen_assert", "screen_extract", "screen_open_app", "screen_act",
            "screen_transfer", "screen_do", "screen_app_card")
        /** Tools that call the model inside (run through the InferenceQueue). */
        val MODEL_TOOLS = setOf("screen_extract", "screen_do")
        /** The Phase 3 inner-loop screen agent; hidden from the orchestrator while screen tools v2 are on. */
        val LEGACY = setOf("run_phone_task")
    }
}
