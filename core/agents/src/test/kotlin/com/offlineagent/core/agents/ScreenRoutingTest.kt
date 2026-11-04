package com.offlineagent.core.agents

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.chat.ComposerMode
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.okJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.1: in-app requests go to the screen agent, never stop at an intent opener. */
class ScreenRoutingTest {
    private fun str(max: Int = 300) = Schema.Str(maxLength = max)
    private val appOpen = ToolSpec("app_open", "Open an installed app (launcher screen).", Schema.Obj(listOf("app" to str(120))), Permission.EXTERNAL_INTENT, "Opens an app.")
    private val screenOpen = ToolSpec("screen_open_app", "Open or switch to an installed app by name and read its screen.", Schema.Obj(listOf("app" to str(80))), Permission.EXTERNAL_INTENT, "Opens another app.")
    private val screenTask = ToolSpec("screen_task", "Do a task INSIDE an app by tapping and typing.", Schema.Obj(listOf("goal" to str(), "app" to str(80),
        "max_steps" to Schema.Num(min = 1.0, max = 25.0, integer = true))), Permission.SCREEN_ACTION, "Taps and types in another app.")
    private val legacy = ToolSpec("run_phone_task", "Do a multi-step task in another app by tapping and typing.", Schema.Obj(listOf("goal" to str(500), "app" to str(120))),
        Permission.SCREEN_ACTION, "Taps and types.")
    private val device = ToolSpec("device_info", "Battery level, storage and RAM.", Schema.Obj(emptyList()), Permission.READ_ONLY)

    @Test fun parsesInAppRequests() {
        assertEquals(InAppRequest.Parsed("WhatsApp", "type hi to Mom (stop before sending)"), InAppRequest.parse("open WhatsApp and type hi to Mom (stop before sending)"))
        assertEquals("Settings > Display", InAppRequest.parse("Open Settings > Display and turn on dark mode")?.app)
        assertEquals("turn on dark mode", InAppRequest.parse("Open Settings > Display and turn on dark mode")?.goal)
        assertEquals("YouTube", InAppRequest.parse("in YouTube, search for lofi beats")?.app)
        assertEquals(InAppRequest.Parsed("WhatsApp", "type hi to Mom"), InAppRequest.parse("type hi to Mom on WhatsApp"))
        assertEquals("Settings > turn on dark mode", InAppRequest.parse("turn on dark mode in settings")?.app)
        assertEquals("Amazon", InAppRequest.parse("please open the Amazon app, then search for earbuds")?.app)
        // not in-app: a plain open, a document, a reminder, a question
        for (r in listOf("open WhatsApp", "open the pdf and summarise it", "remind me on Monday to call mom", "what is the battery level",
            "open it and tell me what it says", "set a reminder at 7pm and show my calendar"))
            assertNull(r, InAppRequest.parse(r))
    }

    @Test fun triageRoutesInAppRequestsToScreenOpenThenScreenTask() {
        val (route, steps) = Triage.route("open WhatsApp and type hi to Mom", listOf(appOpen, screenOpen, screenTask, device), hasModel = true)
        assertEquals(Route.SCREEN, route)
        assertEquals(listOf("screen_open_app", "screen_task"), steps.map { it.tool })
        assertEquals("WhatsApp", (steps[0].args["app"] as JsonPrimitive).content)
        assertEquals("type hi to Mom", (steps[1].args["goal"] as JsonPrimitive).content)
        assertEquals(listOf(1), steps[1].after)
        assertTrue(steps.all { it.agent == AgentRole.EXECUTOR })
        // also without a model (screen_task reports NO_MODEL itself, but app_open is never the plan)
        assertEquals(Route.SCREEN, Triage.route("open WhatsApp and type hi to Mom", listOf(appOpen, screenOpen, screenTask), hasModel = false).first)
    }

    @Test fun legacyLoopWhenScreenToolsV2AreOffAndPlainOpenOtherwise() {
        val (route, steps) = Triage.route("open WhatsApp and type hi to Mom", listOf(appOpen, legacy), hasModel = true)
        assertEquals(Route.SCREEN, route)
        assertEquals(listOf("run_phone_task"), steps.map { it.tool })
        // nothing that can act in apps: no screen route (the planner runs; the Writer must then be honest)
        assertEquals(Route.PLANNED, Triage.route("open WhatsApp and type hi to Mom", listOf(appOpen, device), hasModel = true).first)
        // a plain "open X" is not a screen task
        assertTrue(Triage.route("open WhatsApp", listOf(appOpen, screenOpen, screenTask), hasModel = true).first != Route.SCREEN)
    }

    @Test fun chatTurnsGoToTheScreenAgentOnlyWhenItIsOn() {
        assertTrue(ChatRouting.screenTask("open WhatsApp and type hi to Mom", ComposerMode.CHAT, screenAgentOn = true))
        assertTrue(ChatRouting.screenTask("open Settings > Display and turn on dark mode", ComposerMode.AGENT, screenAgentOn = true))
        assertTrue(ChatRouting.screenTask("open WhatsApp and type hi to Mom", ComposerMode.PHONE, screenAgentOn = true))
        assertFalse(ChatRouting.screenTask("open WhatsApp and type hi to Mom", ComposerMode.CHAT, screenAgentOn = false))
        assertFalse(ChatRouting.screenTask("open WhatsApp and type hi to Mom", ComposerMode.CANVAS, screenAgentOn = true))
        assertFalse(ChatRouting.screenTask("what's the capital of France", ComposerMode.CHAT, screenAgentOn = true))
    }

    private class Tools(private val specs: List<ToolSpec>) : ToolGateway {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, JsonObject>>())
        override fun specs() = specs
        override fun requiresNetwork(name: String) = false
        override suspend fun execute(name: String, args: JsonObject): ToolResult {
            calls += name to args
            return when (name) {
                "screen_open_app" -> okJson("opened" to "com.whatsapp")
                "screen_task" -> ToolResult.Ok(kotlinx.serialization.json.buildJsonObject {
                    put("steps", JsonPrimitive(2)); put("subgoal", kotlinx.serialization.json.buildJsonObject { put("passed", JsonPrimitive(true)); put("issue", JsonPrimitive("")) })
                }, "Did in WhatsApp: Tap \"Mom\"; Type 2 chars into \"Message\". Stopped before \"Tap Send\".")
                "app_open" -> okJson("opened" to "WhatsApp")
                else -> okJson("ok" to true)
            }
        }
    }

    private class Llm(val plan: String, val answer: String) : AgentLlm {
        val systems = mutableListOf<String>()
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
            systems += system
            return when {
                system.startsWith("You plan") -> plan
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                user.startsWith("Tool: app_open") -> """{"app":"WhatsApp"}"""
                else -> answer
            }
        }
    }

    private fun deps(tools: ToolGateway, llm: AgentLlm, approvals: MutableList<ApprovalRequest>) = AgentDeps(InferenceQueue(llm), tools, FakeMemory(), FakeContext(),
        { r -> approvals += r; true }, { true }, { _, _, _, _ -> "q" }, { _, _, _, _, _ -> }, InMemoryTaskStore())

    @Test fun orchestratorRunsScreenStepsAndNeverAppOpenForInAppRequests() = runTest {
        val tools = Tools(listOf(appOpen, screenOpen, screenTask, device))
        val llm = Llm("""{"steps":[{"tool":"app_open","goal":"Open WhatsApp","after":[]}]}""", "I typed hi to Mom in WhatsApp and stopped before sending.")
        val approvals = mutableListOf<ApprovalRequest>()
        val board = Blackboard("r1", "open WhatsApp and type hi to Mom")
        val cp = Orchestrator(deps(tools, llm, approvals)).run(board.request, board)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(Route.SCREEN, cp.route)
        assertEquals(listOf("screen_open_app", "screen_task"), tools.calls.map { it.first })
        assertFalse("no planner call for a screen route", llm.systems.any { it.startsWith("You plan") })
        assertEquals(listOf("screen_open_app", "screen_task"), approvals.map { it.tool })
    }

    @Test fun appOpenAloneIsNotReportedAsDone() = runTest {
        // Screen tools unavailable (agent off + Screen automation off): the planner picks app_open; the Writer claims success.
        val tools = Tools(listOf(appOpen, device))
        val llm = Llm("""{"steps":[{"tool":"app_open","goal":"Open WhatsApp","after":[]}]}""", "Done! I opened WhatsApp and typed hi to Mom.")
        val board = Blackboard("r2", "open WhatsApp and type hi to Mom")
        val cp = Orchestrator(deps(tools, llm, mutableListOf())).run(board.request, board)
        assertEquals(listOf("app_open"), tools.calls.map { it.first })
        assertNotNull(cp.answer)
        val answer = cp.answer!!
        assertTrue(answer, answer.contains("I did not \"type hi to Mom\" inside WhatsApp"))
        // the Writer was told up front that the in-app part did not happen
        assertTrue(llm.systems.any { it.contains("no step tapped or typed in it") })
        // verifier rule on its own
        val issues = InAppCheck.issues("open WhatsApp and type hi to Mom", "Done! I opened WhatsApp and typed hi.", cp.plan, cp.nodes.associateBy { it.id })
        assertEquals(1, issues.size)
        assertTrue(InAppCheck.issues("open WhatsApp and type hi to Mom", "I only opened WhatsApp; I could not type.", cp.plan, cp.nodes.associateBy { it.id }).isEmpty())
    }

    @Test fun modelPlanThatOnlyOpensTheAppIsRepairedWithScreenSteps() = runTest {
        // Request the parser cannot split ("…then…" inside a longer sentence) still gets repaired when the plan is opener-only.
        val tools = Tools(listOf(appOpen, screenOpen, screenTask, device))
        val llm = Llm("""{"steps":[{"tool":"app_open","goal":"Open WhatsApp","after":[]},{"tool":"device_info","goal":"battery","after":[]}]}""", "ok")
        val board = Blackboard("r3", "x")
        val plan = PlannerAgent(deps(tools, llm, mutableListOf())).plan("open WhatsApp and type hi to Mom", board)
        val toolsInPlan = plan.nodes.mapNotNull { it.tool }
        assertEquals(listOf("screen_open_app", "screen_task", "device_info"), toolsInPlan)
    }
}
