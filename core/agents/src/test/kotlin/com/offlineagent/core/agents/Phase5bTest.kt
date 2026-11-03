package com.offlineagent.core.agents

import com.offlineagent.core.runtime.agent.AgentLlm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Scripted model that streams token by token and records full prompts. */
private class StreamingLlm(var plan: String = """{"steps":[]}""", val answerTokens: List<String> = "Your battery is at 81 percent and it is not charging .".split(" ").map { "$it " }) : AgentLlm {
    val systems = java.util.Collections.synchronizedList(mutableListOf<String>())
    val users = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val active = AtomicInteger()
    val maxActive = AtomicInteger()
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String = stream(system, user, grammar, maxTokens) {}
    override suspend fun stream(system: String, user: String, grammar: String?, maxTokens: Int, onPartial: (String) -> Unit): String {
        val now = active.incrementAndGet(); maxActive.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            systems += system; users += user
            val full = when {
                system.startsWith("You plan") -> plan
                user.startsWith("Tool: flashlight") -> """{"on":true}"""
                user.startsWith("Tool: fetch_url") -> """{"url":"https://example.com"}"""
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                else -> null
            }
            if (full != null) { delay(2); onPartial(full); return full }
            val sb = StringBuilder()
            for (t in answerTokens) { delay(3); sb.append(t); onPartial(sb.toString()) }
            return sb.toString().trim()
        } finally { active.decrementAndGet() }
    }
    fun count(prefix: String) = systems.count { it.startsWith(prefix) }
}

private fun harnessWith(llm: AgentLlm, gate: ApprovalGate? = null): Pair<Harness, Orchestrator> {
    val h = Harness(llm)
    val deps = if (gate == null) h.deps else AgentDeps(h.deps.queue, h.tools, h.memory, h.ctx, gate, h.deps.network, h.deps.outbox, h.deps.audit, h.store)
    return h to Orchestrator(deps)
}

class FastPathTest {
    @Test fun plainQuestionTakesDirectRouteWithOneModelCall() = runBlocking {
        val llm = StreamingLlm()
        val (_, o) = harnessWith(llm)
        val b = Blackboard("f1", "what is a black hole, in two lines?")
        val cp = o.run(b.request, b)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(Route.DIRECT, cp.route)
        assertEquals(0, llm.count("You plan")); assertEquals(0, llm.count("Check an assistant"))
        assertEquals(1, llm.systems.size) // the Writer only
        assertEquals(1, b.eventLog().count { it is ActivityEvent.ModelCall })
    }

    @Test fun singleObviousToolStepSkipsThePlannerModel() = runBlocking {
        val llm = StreamingLlm()
        val (h, o) = harnessWith(llm)
        val b = Blackboard("f2", "how's my battery?")
        val cp = o.run(b.request, b)
        assertEquals(Route.RULES, cp.route)
        assertEquals(0, llm.count("You plan"))
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertTrue(h.tools.calls.any { it.first == "device_info" })
        // read-only run, every step DONE → the Verifier accepts on rules alone (no model check)
        assertEquals(0, llm.count("Check an assistant"))
    }

    @Test fun multiStepRequestUsesThePlanner() = runBlocking {
        val llm = StreamingLlm(plan = """{"steps":[{"tool":"device_info","goal":"Read battery","after":[]},{"tool":"flashlight","goal":"Torch on","after":[1]}]}""")
        val (h, o) = harnessWith(llm)
        val b = Blackboard("f3", "check my battery and then turn on the torch")
        val cp = o.run(b.request, b)
        assertEquals(Route.PLANNED, cp.route)
        assertEquals(1, llm.count("You plan"))
        assertTrue(h.tools.calls.map { it.first }.containsAll(listOf("device_info", "flashlight")))
        assertEquals(1, llm.count("Check an assistant")) // an action ran → model double-check of the answer
    }

    @Test fun triageClassifies() {
        assertTrue(Triage.isTaskLike("set a reminder at 7pm to call mom"))
        assertTrue(Triage.isMultiStep("set a reminder at 7pm to call mom and tell me tomorrow's plan"))
        assertFalse(Triage.isTaskLike("explain quantum tunnelling simply"))
        assertFalse(Triage.isMultiStep("how's my battery?"))
    }
}

class StreamingEventsTest {
    @Test fun writerStreamsTokensThrottledAndKeepsFinalThought() = runBlocking {
        val llm = StreamingLlm()
        val h = Harness(llm)
        val b = Blackboard("s1", "what is a black hole?", streamThrottleMs = 20)
        val seen = java.util.Collections.synchronizedList(mutableListOf<String>())
        val watcher = launch { b.live.collect { m -> m["answer"]?.text?.takeIf { it.isNotBlank() }?.let { seen += it } } }
        val cp = Orchestrator(h.deps).run(b.request, b)
        watcher.cancel()
        val ans = cp.nodes.first { it.id == "answer" }
        assertEquals(cp.answer, ans.output)
        assertEquals(ans.output, ans.thought.trim())                       // final streamed text kept on the node
        assertTrue("saw partial text while streaming", seen.any { it.length < ans.output.length && ans.output.startsWith(it.trim()) })
        assertTrue("throttled: ${b.livePublishes}", b.livePublishes < llm.answerTokens.size)
        assertTrue(b.live.value.isEmpty())                                 // cleared when done
        val call = b.eventLog().filterIsInstance<ActivityEvent.ModelCall>().single()
        assertEquals(AgentRole.WRITER, call.agent); assertTrue(call.millis >= 0)
    }

    @Test fun plannerStreamIsShownAsReadableSteps() {
        val partial = """{"steps":[{"tool":"list_events","goal":"Get tomorrow's events","after":[]},{"tool":"schedule_exact_rem"""
        val r = PlanPreview.readable(partial)
        assertTrue(r.startsWith("1. list_events — Get tomorrow's events")); assertTrue(r.contains("2. schedule_exact_rem"))
        assertEquals("No tools needed — answering directly.", PlanPreview.readable("""{"steps":[]}"""))
    }

    @Test fun plannerThoughtIsStoredAsFact() = runBlocking {
        val llm = StreamingLlm(plan = """{"steps":[{"tool":"device_info","goal":"Read battery","after":[]}]}""")
        val h = Harness(llm)
        val b = Blackboard("s2", "check battery and then tell me the time")
        Orchestrator(h.deps).run(b.request, b)
        assertTrue(b.facts()["planner_thought"]!!.startsWith("1. device_info — Read battery"))
    }
}

class ContinueInChatTest {
    @Test fun taskContextCarriesPlanResultsAndAnswer() = runBlocking {
        val h = Harness(llm = null)
        val b = Blackboard("c1", "battery status please")
        val cp = h.orchestrator.run(b.request, b)
        val ctx = TaskContext.forChat(cp)
        assertTrue(ctx.contains("battery status please"))
        assertTrue(ctx.contains("Read battery, storage and device info [done]"))
        assertTrue(ctx.contains("81"))                 // the tool output
        assertTrue(ctx.contains("Answer given:"))
        assertTrue(ctx.length <= 1800)
    }

    @Test fun conversationReachesPlannerAndWriter() = runBlocking {
        val llm = StreamingLlm(plan = """{"steps":[{"tool":"device_info","goal":"Read battery","after":[]}]}""")
        val h = Harness(llm)
        val b = Blackboard("c2", "check my battery again and then tell me", conversation = "User: how's my battery?\nONYX: 81%")
        val cp = Orchestrator(h.deps).run(b.request, b)
        assertTrue(llm.users.first().contains("Earlier conversation"))                       // planner
        assertTrue(llm.users.last { it.contains("Request:") && it.contains("Step results") }.contains("ONYX: 81%")) // writer
        assertEquals("User: how's my battery?\nONYX: 81%", cp.conversation)
    }
}

class NoDeadlockTest {
    @Test fun queueIsReentrant() = runBlocking {
        val q = InferenceQueue(FakeLlm(answer = "inner"))
        val out = withTimeout(2_000) { q.run(InferenceQueue.Priority.NORMAL, "tool:summarize") { q.complete(InferenceQueue.Priority.NORMAL, "x", "s", "u", null, 5) } }
        assertEquals("inner", out)
        assertFalse(q.stats.value.running)
    }

    @Test fun parallelActionsWithPendingApprovalsNeverDeadlockAndAskOneAtATime() = runBlocking {
        val llm = StreamingLlm(plan = """{"steps":[{"tool":"flashlight","goal":"Torch on","after":[]},{"tool":"fetch_url","goal":"Fetch page","after":[]}]}""")
        val open = AtomicInteger(); val maxOpen = AtomicInteger(); val asked = AtomicInteger()
        val gate = ApprovalGate { _ -> val n = open.incrementAndGet(); maxOpen.accumulateAndGet(n) { a, b -> maxOf(a, b) }; asked.incrementAndGet(); delay(40); open.decrementAndGet(); true }
        val (h, o) = harnessWith(llm, gate)
        val b = Blackboard("d1", "turn on the torch and then fetch https://example.com")
        val cp = withTimeout(5_000) { o.run(b.request, b) }
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(2, asked.get()); assertEquals(1, maxOpen.get())       // one sheet at a time
        assertEquals(1, llm.maxActive.get())                               // one model call at a time
        assertTrue(h.tools.calls.map { it.first }.containsAll(listOf("flashlight", "fetch_url")))
        assertNull(b.state.value.awaiting)
    }

    @Test fun cancelWhileWaitingForApprovalSurfacesCancelled() = runBlocking {
        val asked = CompletableDeferred<Unit>()
        val gate = ApprovalGate { _ -> asked.complete(Unit); CompletableDeferred<Boolean>().await() } // the user never answers
        val (h, o) = harnessWith(StreamingLlm(), gate)
        val b = Blackboard("d2", "turn on the torch")
        val job = async { runCatching { o.run(b.request, b) } }
        withTimeout(2_000) { asked.await() }
        assertEquals(TaskStatus.AWAITING_APPROVAL, b.state.value.status)
        assertTrue(b.state.value.awaiting != null)
        job.cancel(); job.join()
        val st = b.state.value
        assertEquals(TaskStatus.CANCELLED, st.status)
        assertNull(st.awaiting)
        assertTrue(st.nodes.values.none { it.status == NodeStatus.RUNNING || it.status == NodeStatus.VERIFYING })
        assertEquals(TaskStatus.CANCELLED, h.store.saved["d2"]!!.status)
        assertTrue(h.tools.calls.none { it.first == "flashlight" })
    }

    @Test fun retryReasonIsSurfacedWhileRetrying() = runBlocking {
        val h = Harness(llm = null)
        h.tools.failDeviceInfo = 1
        val slow = object : ToolGateway by h.tools { override suspend fun execute(name: String, args: kotlinx.serialization.json.JsonObject) = run { delay(30); h.tools.execute(name, args) } }
        val o = Orchestrator(AgentDeps(null, slow, h.memory, h.ctx, h.deps.approvals, h.deps.network, h.deps.outbox, h.deps.audit, h.store))
        val b = Blackboard("d3", "battery status please")
        val reasons = java.util.Collections.synchronizedList(mutableListOf<String>())
        val w = launch { b.state.collect { s -> s.nodes["s1"]?.retryReason?.let { reasons += it } } }
        val cp = o.run(b.request, b)
        delay(10); w.cancel()
        assertEquals(NodeStatus.DONE, cp.nodes.first { it.id == "s1" }.status)
        assertTrue(reasons.any { it.contains("battery service unavailable") })
        assertNull(cp.nodes.first { it.id == "s1" }.retryReason)
    }

    @Test fun pausedQueueStillCancels() = runBlocking {
        val q = InferenceQueue(FakeLlm())
        q.pause("hot")
        val j = launch { q.complete(InferenceQueue.Priority.NORMAL, "x", "s", "u", null, 5) }
        delay(20); j.cancel(); j.join()
        q.resume()
        assertEquals("Your battery is at 81%.", withTimeout(1_000) { q.complete(InferenceQueue.Priority.NORMAL, "x", "s", "u", null, 5) })
        assertEquals(0, q.stats.value.queued)
    }
}

class PlanRepairTest {
    private val specs = FakeTools().specs()

    @Test fun requestTimeAndReminderText() {
        assertEquals("19:00", ArgRepair.requestTime("set a reminder at 7pm to call mom and tell me tomorrow's plan"))
        assertEquals("07:30", ArgRepair.requestTime("wake me at 7:30 am"))
        assertEquals("21:15", ArgRepair.requestTime("remind me at 21:15 to stretch"))
        assertNull(ArgRepair.requestTime("between 5pm and 7pm"))
        assertEquals("call mom", ArgRepair.reminderText("set a reminder at 7pm to call mom and tell me tomorrow's plan"))
    }

    @Test fun wrongTimeFromModelIsRepaired() {
        val spec = com.offlineagent.core.runtime.tools.ToolSpec("schedule_exact_reminder", "Notify at a time.",
            com.offlineagent.core.runtime.schema.Schema.Obj(listOf("text" to com.offlineagent.core.runtime.schema.Schema.Str(), "time" to com.offlineagent.core.runtime.schema.Schema.Str(maxLength = 5))),
            com.offlineagent.core.runtime.tools.Permission.DEVICE_CONTROL)
        val fixed = ArgRepair.fix(spec, kotlinx.serialization.json.buildJsonObject { put("text", kotlinx.serialization.json.JsonPrimitive("call mom")); put("time", kotlinx.serialization.json.JsonPrimitive("18:30")) }, "remind me at 7pm to call mom")
        assertEquals("19:00", (fixed["time"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun coverageAddsTheStepThePlannerDropped() {
        val model = listOf(PlannerAgent.Step(AgentRole.DEVICE, "device_info", "check battery, turn on the torch", emptyList()))
        val out = Coverage.repair("check my battery and turn on the torch", model, emptyList(), specs)
        assertEquals(listOf("device_info", "flashlight"), out.map { it.tool })
        assertEquals(AgentRole.EXECUTOR, out[1].agent)
        // already covered → nothing added
        assertEquals(1, Coverage.repair("how's my battery?", model, emptyList(), specs).size)
    }

    @Test fun claimCheckCatchesInventedActions() {
        val plan = PlannerAgent.assemble("x", listOf(PlannerAgent.Step(AgentRole.DEVICE, "device_info", "battery", emptyList())), "model")
        val nodes = plan.nodes.associate { it.id to NodeState(it.id, it.agent, it.goal, NodeStatus.DONE) }
        assertTrue(ClaimCheck.issues("Battery: 64%. Torch is on. Note saved: \"done\".", plan, nodes).size == 2)
        assertTrue(ClaimCheck.issues("Your battery is at 64%.", plan, nodes).isEmpty())
    }
}
