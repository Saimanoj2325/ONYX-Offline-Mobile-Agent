package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.agent.AgentLimits
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.agent.AgentLoop
import com.offlineagent.core.runtime.agent.ApprovalGate
import com.offlineagent.core.runtime.agent.RunCheckpoint
import com.offlineagent.core.runtime.agent.RunStatus
import com.offlineagent.core.runtime.agent.RunStore
import com.offlineagent.core.runtime.agent.StepKind
import com.offlineagent.core.runtime.agent.StepRecord
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.validate
import com.offlineagent.core.runtime.skills.Skill
import com.offlineagent.core.runtime.tools.Calculator
import com.offlineagent.core.runtime.tools.CalculatorTool
import com.offlineagent.core.runtime.tools.CanvasPort
import com.offlineagent.core.runtime.tools.MemoryPort
import com.offlineagent.core.runtime.tools.MemoryWriteTool
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.PolicyDecision
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.RenderUiTool
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolCall
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolRegistry
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.objSchema
import com.offlineagent.core.runtime.tools.okJson
import com.offlineagent.core.runtime.ui.UiDoc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FakeLlm(private val script: MutableList<String>) : AgentLlm {
    val calls = mutableListOf<Pair<String, String?>>()
    var block: CompletableDeferred<Unit>? = null
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
        calls += user to grammar
        block?.await()
        return if (script.isEmpty()) "{\"thought\":\"done\",\"final\":\"fallback\"}" else script.removeAt(0)
    }
}

class MemStore : RunStore {
    val saved = mutableListOf<RunCheckpoint>()
    val byId = HashMap<String, RunCheckpoint>()
    override suspend fun save(checkpoint: RunCheckpoint) { saved += checkpoint; byId[checkpoint.runId] = checkpoint }
    override suspend fun load(runId: String) = byId[runId]
}

class FakeMemory : MemoryPort {
    val facts = LinkedHashMap<String, String>()
    override suspend fun read(query: String) = facts.toList()
    override suspend fun write(key: String, value: String) { facts[key] = value }
}

class FakeCanvas : CanvasPort {
    val docs = mutableListOf<UiDoc>()
    override suspend fun render(doc: UiDoc, newCanvas: Boolean): Int { docs += doc; return docs.size }
    override suspend fun current() = docs.lastOrNull()
}

class SlowTool : Tool {
    override val spec = ToolSpec("slow", "never finishes", objSchema(), Permission.READ_ONLY, timeoutMs = 50)
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult { delay(10_000); return okJson() }
}

class AgentAndToolsTest {
    private val plan = "{\"steps\":[\"compute\",\"answer\"]}"
    private fun calc(expr: String) = "{\"thought\":\"calc\",\"tool\":\"calculator\",\"args\":{\"expression\":\"$expr\"}}"
    private fun final(t: String) = "{\"thought\":\"ok\",\"final\":\"$t\"}"

    private val memory = FakeMemory()
    private val canvas = FakeCanvas()
    private val registry = ToolRegistry(listOf(CalculatorTool(), MemoryWriteTool(memory), RenderUiTool(canvas, update = false), SlowTool()))
    private val policy = PolicyEngine(registry)

    private fun loop(llm: AgentLlm, store: RunStore, approve: Boolean = true, limits: AgentLimits = AgentLimits()) =
        AgentLoop(llm, registry, policy, store, ApprovalGate { _, _, _, _ -> approve }, limits)

    @Test fun calculatorEvaluatesSafely() {
        assertEquals(14.0, Calculator.evaluate("2+3*4"), 0.0)
        assertEquals(20.0, Calculator.evaluate("(2+3)*4"), 0.0)
        assertEquals(512.0, Calculator.evaluate("2^3^2"), 0.0)
        assertEquals(3.0, Calculator.evaluate("sqrt(9)"), 0.0)
        assertEquals(-1.0, Calculator.evaluate("-(1)"), 0.0)
        assertEquals("0.3333333333", Calculator.format(Calculator.evaluate("1/3")))
        listOf("1/0", "2+", "exec(1)", "x", "((((1", "a".repeat(201)).forEach {
            try { Calculator.evaluate(it); fail("should reject $it") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test fun registryValidatesArguments() {
        assertTrue(registry.validate(ToolCall("calculator", buildJsonObject { put("expression", "1+1") })).isEmpty())
        assertFalse(registry.validate(ToolCall("calculator", buildJsonObject { put("expression", 5) })).isEmpty())
        assertFalse(registry.validate(ToolCall("calculator", buildJsonObject { })).isEmpty())
        assertFalse(registry.validate(ToolCall("calculator", buildJsonObject { put("expression", "1"); put("evil", true) })).isEmpty())
        assertEquals("tool", registry.validate(ToolCall("rm_rf", buildJsonObject { })).single().path)
    }

    @Test fun schemaValidationRanges() {
        val s = Schema.Obj(listOf("k" to Schema.Num(min = 1.0, max = 6.0, integer = true), "e" to Schema.Str(enum = listOf("a"))))
        assertTrue(s.validate(buildJsonObject { put("k", 3); put("e", "a") }).isEmpty())
        assertEquals(1, s.validate(buildJsonObject { put("k", 9); put("e", "a") }).size)
        assertEquals(1, s.validate(buildJsonObject { put("k", 2.5); put("e", "a") }).size)
        assertEquals(1, s.validate(buildJsonObject { put("k", 2); put("e", "b") }).size)
    }

    @Test fun policyEngineRules() {
        val calc = ToolCall("calculator", buildJsonObject { put("expression", "1") })
        val mem = ToolCall("memory_write", buildJsonObject { put("key", "a"); put("value", "b") })
        assertEquals(PolicyDecision.Allow, policy.decide(calc, null, 0))
        assertTrue(policy.decide(mem, null, 0) is PolicyDecision.NeedsApproval)
        assertTrue(policy.decide(mem, setOf("calculator"), 0) is PolicyDecision.Deny)
        assertTrue(policy.decide(mem, null, 3) is PolicyDecision.Deny)
        assertTrue(policy.decide(ToolCall("nope", JsonObject(emptyMap())), null, 0) is PolicyDecision.Deny)
        val trusting = PolicyEngine(registry, alwaysAllowed = { setOf("memory_write") })
        assertEquals(PolicyDecision.Allow, trusting.decide(mem, null, 0))
    }

    @Test fun happyPathPlanToolFinal() = runTest {
        val store = MemStore()
        val r = loop(FakeLlm(mutableListOf(plan, calc("6*7"), final("42"))), store).start("r1", 1, "What is 6*7?", null)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals("42", r.finalAnswer)
        assertEquals(listOf("compute", "answer"), r.plan)
        assertEquals(listOf(StepKind.PLAN, StepKind.TOOL_CALL, StepKind.FINAL), r.steps.map { it.kind })
        assertTrue(r.steps[1].output.contains("\"42\""))
        assertTrue(store.saved.size >= 4) // checkpoint after every step
        assertEquals(RunStatus.PLANNING, store.saved.first().status)
    }

    @Test fun iterationLimitIsEnforced() = runTest {
        val outs = mutableListOf(plan) + (1..20).map { calc("$it+1") }
        val r = loop(FakeLlm(outs.toMutableList()), MemStore(), limits = AgentLimits(maxIterations = 3)).start("r2", null, "loop", null)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals(3, r.iteration)
        assertEquals(3, r.steps.count { it.kind == StepKind.TOOL_CALL })
    }

    @Test fun invalidOutputsFailAfterRetryLimit() = runTest {
        val r = loop(FakeLlm(mutableListOf(plan, "garbage", "{not json")), MemStore()).start("r3", null, "x", null)
        assertEquals(RunStatus.FAILED, r.status)
        assertEquals(2, r.steps.count { it.kind == StepKind.ERROR })
    }

    @Test fun invalidArgumentsAreRejectedThenRecovered() = runTest {
        val bad = "{\"thought\":\"t\",\"tool\":\"calculator\",\"args\":{\"expr\":\"1\"}}"
        val r = loop(FakeLlm(mutableListOf(plan, bad, calc("1+1"), final("2"))), MemStore()).start("r4", null, "x", null)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertTrue(r.steps.first { it.kind == StepKind.ERROR }.error!!.contains("invalid arguments"))
    }

    @Test fun approvalDeclinedDoesNotExecute() = runTest {
        val write = "{\"thought\":\"t\",\"tool\":\"memory_write\",\"args\":{\"key\":\"name\",\"value\":\"Sai\"}}"
        val r = loop(FakeLlm(mutableListOf(plan, write, final("ok"))), MemStore(), approve = false).start("r5", null, "remember", null)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertTrue(memory.facts.isEmpty())
        assertEquals("declined", r.steps.first { it.kind == StepKind.APPROVAL }.output)
    }

    @Test fun approvalGrantedExecutes() = runTest {
        val write = "{\"thought\":\"t\",\"tool\":\"memory_write\",\"args\":{\"key\":\"name\",\"value\":\"Sai\"}}"
        val store = MemStore()
        val r = loop(FakeLlm(mutableListOf(plan, write, final("ok"))), store, approve = true).start("r6", null, "remember", null)
        assertEquals("Sai", memory.facts["name"])
        assertEquals(1, r.sideEffects)
        assertTrue(store.saved.any { it.status == RunStatus.AWAITING_APPROVAL })
    }

    @Test fun skillToolAllowListIsEnforced() = runTest {
        val skill = Skill("s", "S", "", emptyList(), tools = listOf("calculator"), output = emptyList(), maxSteps = 4, instructions = "x")
        val llm = FakeLlm(mutableListOf(plan, "{\"thought\":\"t\",\"tool\":\"memory_write\",\"args\":{\"key\":\"a\",\"value\":\"b\"}}", final("done")))
        val r = loop(llm, MemStore()).start("r7", null, "x", skill)
        assertTrue(r.steps.any { it.error?.contains("unknown tool") == true || it.error?.contains("not allowed") == true })
        // the grammar offered to the model only contains allowed tools
        val actionGrammar = llm.calls[1].second!!
        assertTrue(actionGrammar.contains("calculator"))
        assertFalse(actionGrammar.contains("memory_write"))
    }

    @Test fun toolTimeoutIsAnError() = runTest {
        val r = loop(FakeLlm(mutableListOf(plan, "{\"thought\":\"t\",\"tool\":\"slow\",\"args\":{}}", final("x"))), MemStore()).start("r8", null, "x", null)
        assertTrue(r.steps.any { it.error?.contains("timed out") == true })
        assertEquals(RunStatus.COMPLETED, r.status)
    }

    @Test fun repeatedIdenticalCallIsBlocked() = runTest {
        val r = loop(FakeLlm(mutableListOf(plan, calc("1+1"), calc("1+1"), final("2"))), MemStore()).start("r9", null, "x", null)
        assertEquals(1, r.steps.count { it.kind == StepKind.TOOL_CALL })
        assertTrue(r.steps.any { it.error?.contains("repeated") == true })
    }

    @Test fun cancellationIsCheckpointed() = runTest {
        val store = MemStore()
        val llm = FakeLlm(mutableListOf(plan))
        val job = launch { loop(llm, store).start("r10", null, "x", null) }
        testScheduler.advanceUntilIdle()
        // plan done, now block the next LLM call and cancel
        assertEquals(RunStatus.COMPLETED, store.byId["r10"]!!.status)
        val llm2 = object : AgentLlm { override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String = awaitCancellation() }
        val job2 = launch { AgentLoop(llm2, registry, policy, store, { _, _, _, _ -> true }).start("r11", null, "x", null) }
        testScheduler.runCurrent() // (advanceUntilIdle would fire the LLM timeout in virtual time)
        job2.cancel()
        job2.join()
        assertEquals(RunStatus.CANCELLED, store.byId["r11"]!!.status)
        job.join()
    }

    @Test fun resumeContinuesFromCheckpointWithoutReplanning() = runTest {
        val store = MemStore()
        store.save(
            RunCheckpoint(
                "r12", null, null, "What is 2+2 then +1?", RunStatus.RUNNING, plan = listOf("add", "answer"), iteration = 1,
                steps = listOf(
                    StepRecord(0, StepKind.PLAN, output = "add\nanswer"),
                    StepRecord(1, StepKind.TOOL_CALL, tool = "calculator", input = "{\"expression\":\"2+2\"}", output = "{\"result\":\"4\"}"),
                ),
            ),
        )
        val llm = FakeLlm(mutableListOf(calc("4+1"), final("5")))
        val r = loop(llm, store).resume("r12", null)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertEquals("5", r.finalAnswer)
        assertEquals(3, r.iteration)
        assertTrue(llm.calls.first().first.contains("2+2")) // earlier observation is in the prompt
        assertFalse(llm.calls.any { it.second?.contains("steps") == true && it.first.startsWith("Task:") && !it.first.contains("Plan:") })
    }

    @Test fun terminalRunIsNotResumed() = runTest {
        val store = MemStore()
        store.save(RunCheckpoint("r13", null, null, "x", RunStatus.COMPLETED, finalAnswer = "done"))
        val llm = FakeLlm(mutableListOf())
        assertEquals("done", loop(llm, store).resume("r13", null).finalAnswer)
        assertTrue(llm.calls.isEmpty())
    }

    @Test fun skillWithUiComposesCanvasWhenModelFinishesEarly() = runTest {
        val skill = Skill("s", "S", "", emptyList(), tools = listOf("calculator", "render_ui"), output = listOf("markdown", "table"), maxSteps = 3, instructions = "make a table")
        val ui = "{\"v\":1,\"title\":\"T\",\"blocks\":[{\"type\":\"markdown\",\"text\":\"hello\"}]}"
        val r = loop(FakeLlm(mutableListOf(plan, final("short"), ui)), MemStore()).start("r14", 5, "x", skill)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertTrue(r.canvasRendered)
        assertEquals("T", canvas.docs.last().title)
        assertNotNull(r.steps.firstOrNull { it.kind == StepKind.COMPOSE })
    }

    @Test fun uiSkillFinishesWhenCanvasRendered() = runTest {
        val skill = Skill("s", "S", "", emptyList(), tools = listOf("calculator", "render_ui"), output = listOf("markdown"), maxSteps = 5, instructions = "x")
        val render = "{\"thought\":\"t\",\"tool\":\"render_ui\",\"args\":{\"v\":1,\"title\":\"R\",\"blocks\":[{\"type\":\"markdown\",\"text\":\"hi\"}]}}"
        val llm = FakeLlm(mutableListOf(plan, render, calc("1+1")))
        val r = loop(llm, MemStore()).start("r15", 1, "x", skill)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertTrue(r.canvasRendered)
        assertEquals(2, llm.calls.size) // plan + one action, then stop
    }

    @Test fun failingToolsAfterSuccessCompleteInsteadOfFail() = runTest {
        val bad = "{\"thought\":\"t\",\"tool\":\"calculator\",\"args\":{\"expression\":\"sleep(1)\"}}"
        val bad2 = "{\"thought\":\"t\",\"tool\":\"calculator\",\"args\":{\"expression\":\"nap(2)\"}}"
        val r = loop(FakeLlm(mutableListOf(plan, calc("2+2"), bad, bad2)), MemStore()).start("r16", null, "x", null)
        assertEquals(RunStatus.COMPLETED, r.status)
        assertTrue(r.finalAnswer!!.contains("4"))
    }

    @Test fun firstActionMustBeAToolWhenPlanNamesOne() = runTest {
        val llm = FakeLlm(mutableListOf("{\"steps\":[\"use the calculator\"]}", calc("2*3"), final("6")))
        loop(llm, MemStore()).start("r17", null, "multiply", null)
        val firstActionGrammar = llm.calls[1].second!!
        assertFalse(firstActionGrammar.contains("\\\"final\\\""))
        val secondActionGrammar = llm.calls[2].second!!
        assertTrue(secondActionGrammar.contains("\\\"final\\\""))
        val agent = AgentLoop(llm, registry, policy, MemStore(), { _, _, _, _ -> true })
        assertEquals(setOf("calculator"), agent.mentionedTools("Compute the total", registry.specs))
        assertTrue(agent.mentionedTools("say hi", registry.specs).isEmpty())
    }

    @Test fun renderUiToolRejectsInvalidDocs() = runTest {
        val tool = RenderUiTool(canvas, update = false)
        val bad = tool.execute(buildJsonObject { put("v", 1); put("title", "x"); put("blocks", JsonPrimitive("nope")) }, ToolContext("r", null))
        assertTrue(bad is ToolResult.Error)
    }
}
