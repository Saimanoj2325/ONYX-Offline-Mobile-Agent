package com.offlineagent.core.agents

import com.offlineagent.core.runtime.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlanTest {
    private fun n(id: String, vararg deps: String) = TaskNode(id, AgentRole.DEVICE, id, dependsOn = deps.toList())

    @Test fun levelsGroupIndependentNodes() {
        val p = TaskPlan("g", listOf(n("a"), n("b"), n("c", "a", "b"), n("d", "c")))
        assertEquals(listOf(listOf("a", "b"), listOf("c"), listOf("d")), p.levels()!!.map { l -> l.map { it.id } })
        assertTrue(p.problems().isEmpty())
    }

    @Test fun detectsCyclesUnknownDepsAndDuplicates() {
        assertTrue(TaskPlan("g", listOf(n("a", "b"), n("b", "a"))).problems().any { "cycle" in it })
        assertTrue(TaskPlan("g", listOf(n("a", "zzz"))).problems().any { "unknown" in it })
        assertTrue(TaskPlan("g", listOf(n("a"), n("a"))).problems().any { "duplicate" in it })
    }

    @Test fun assembledPlanHasParallelRootsAndWriterLast() {
        val p = PlannerAgent.assemble("x", listOf(PlannerAgent.Step(AgentRole.DEVICE, "device_info", "read", emptyList()),
            PlannerAgent.Step(AgentRole.EXECUTOR, "flashlight", "torch", listOf(1))), "rules")
        val levels = p.levels()!!.map { l -> l.map { it.id }.toSet() }
        assertEquals(setOf("ctx", "mem"), levels[0]); assertEquals(setOf("s1"), levels[1]); assertEquals(setOf("s2"), levels[2]); assertEquals(setOf("answer"), levels[3])
    }

    @Test fun checkpointJsonRoundTrip() {
        val cp = TaskCheckpoint("r1", "req", TaskStatus.RUNNING, PlannerAgent.assemble("req", emptyList(), "rules"),
            listOf(NodeState("ctx", AgentRole.CONTEXT, "g", NodeStatus.DONE, 1, "out")), mapOf("context" to "c"))
        assertEquals(cp, TaskCheckpoint.fromJson(cp.toJson()))
    }

    @Test fun rulePlannerMapsKnownIntents() {
        val specs = FakeTools().specs()
        assertEquals(listOf("device_info"), RulePlanner.steps("How much battery do I have?", specs).map { it.tool })
        val torch = RulePlanner.steps("turn the torch off", specs).single()
        assertEquals(AgentRole.EXECUTOR, torch.agent); assertEquals("false", torch.args["on"].toString())
        assertTrue(RulePlanner.steps("tell me a joke", specs).isEmpty())
    }
}

class DagSchedulerTest {
    private fun plan(vararg nodes: TaskNode) = TaskPlan("g", nodes.toList())
    private fun board(p: TaskPlan) = Blackboard("r", "g").also { it.setPlan(p) }

    @Test fun independentNodesRunInParallel() = runBlocking {
        val p = plan(TaskNode("a", AgentRole.DEVICE, "a"), TaskNode("b", AgentRole.DEVICE, "b"), TaskNode("c", AgentRole.WRITER, "c", dependsOn = listOf("a", "b")))
        val b = board(p)
        val bothStarted = CompletableDeferred<Unit>(); val started = AtomicInteger()
        val order = mutableListOf<String>()
        withTimeout(5_000) {
            DagScheduler(3).run(p, b, { false }) { n ->
                if (n.id != "c") { if (started.incrementAndGet() == 2) bothStarted.complete(Unit); bothStarted.await() } // deadlocks unless parallel
                synchronized(order) { order += n.id }
                b.updateNode(n.id) { it.copy(status = NodeStatus.DONE) }
            }
        }
        assertEquals("c", order.last()); assertEquals(3, order.size)
    }

    @Test fun failedDependencySkipsDependantsButTolerantNodeRuns() = runBlocking {
        val p = plan(TaskNode("a", AgentRole.DEVICE, "a"), TaskNode("b", AgentRole.EXECUTOR, "b", dependsOn = listOf("a")),
            TaskNode("c", AgentRole.DEVICE, "c", dependsOn = listOf("b")), TaskNode("w", AgentRole.WRITER, "w", dependsOn = listOf("a", "b", "c")))
        val b = board(p); val ran = mutableListOf<String>()
        DagScheduler().run(p, b, { it.agent == AgentRole.WRITER }) { n ->
            ran += n.id
            b.updateNode(n.id) { it.copy(status = if (n.id == "a") NodeStatus.FAILED else NodeStatus.DONE) }
        }
        assertEquals(listOf("a", "w"), ran)
        assertEquals(NodeStatus.SKIPPED, b.node("b")!!.status); assertEquals(NodeStatus.SKIPPED, b.node("c")!!.status)
    }

    @Test fun parallelismIsBounded() = runBlocking {
        val nodes = (1..6).map { TaskNode("n$it", AgentRole.DEVICE, "n") }
        val p = plan(*nodes.toTypedArray()); val b = board(p)
        val active = AtomicInteger(); val max = AtomicInteger()
        DagScheduler(2).run(p, b, { false }) { n ->
            max.accumulateAndGet(active.incrementAndGet()) { x, y -> maxOf(x, y) }; delay(20); active.decrementAndGet()
            b.updateNode(n.id) { it.copy(status = NodeStatus.DONE) }
        }
        assertEquals(2, max.get())
    }
}

class InferenceQueueTest {
    @Test fun serializesModelCallsFromParallelAgents() = runBlocking {
        val llm = FakeLlm(); val q = InferenceQueue(llm)
        (1..8).map { i -> async { q.complete(InferenceQueue.Priority.NORMAL, "k$i", "s", "u", null, 10) } }.awaitAll()
        assertEquals(1, llm.maxActive.get()); assertEquals(8, q.stats.value.completed)
    }

    private fun order(vararg queued: Pair<InferenceQueue.Priority, String>): List<String?> = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val q = InferenceQueue(FakeLlm())
        val first = launch { q.run(InferenceQueue.Priority.NORMAL, "writer") { gate.await() } } // holds the model
        delay(20)
        val jobs = queued.map { (p, k) -> launch { q.run(p, k) { } }.also { delay(5) } }
        delay(20); gate.complete(Unit); first.join(); jobs.forEach { it.join() }
        q.grantOrder.toList()
    }

    @Test fun samePrefixAsRunningJobGoesFirstAmongEqualPriority() {
        // "writer" is still in the KV cache → it jumps ahead of the older "other" job; background stays last.
        assertEquals(listOf("writer", "writer", "other", "bg"),
            order(InferenceQueue.Priority.BACKGROUND to "bg", InferenceQueue.Priority.NORMAL to "other", InferenceQueue.Priority.NORMAL to "writer"))
    }

    @Test fun higherPriorityBeatsPrefixReuseThenFifo() {
        assertEquals(listOf("writer", "ui", "other", "writer", "bg"),
            order(InferenceQueue.Priority.BACKGROUND to "bg", InferenceQueue.Priority.NORMAL to "other", InferenceQueue.Priority.NORMAL to "writer", InferenceQueue.Priority.INTERACTIVE to "ui"))
    }

    @Test fun pauseHoldsJobsUntilResume() = runBlocking {
        val q = InferenceQueue(FakeLlm()); q.pause("hot")
        val r = async { q.complete(InferenceQueue.Priority.INTERACTIVE, null, "s", "u", null, 5) }
        delay(50); assertFalse(r.isCompleted); assertTrue(q.stats.value.paused)
        q.resume(); assertEquals("Your battery is at 81%.", r.await())
    }

    @Test fun cancelledWaiterIsRemoved() = runBlocking {
        val gate = CompletableDeferred<Unit>(); val q = InferenceQueue(FakeLlm())
        val holder = launch { q.run(InferenceQueue.Priority.NORMAL, "a") { gate.await() } }
        delay(10)
        val waiting = launch { q.run(InferenceQueue.Priority.NORMAL, "b") { } }
        delay(10); waiting.cancel(); waiting.join()
        gate.complete(Unit); holder.join()
        assertEquals(listOf<String?>("a"), q.grantOrder.toList())
        assertEquals("x", q.run(InferenceQueue.Priority.NORMAL, "c") { "x" }) // queue still usable
    }
}

class OrchestratorTest {
    @Test fun withoutModelRulePlanRunsDeviceReadAndTemplateAnswer() = runBlocking {
        val h = Harness(llm = null)
        val b = Blackboard("r1", "What's my battery level?")
        val cp = h.orchestrator.run(b.request, b)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals("rules", cp.plan!!.source)
        assertEquals(listOf("ctx", "mem", "s1", "answer"), cp.plan!!.nodes.map { it.id })
        assertTrue(cp.nodes.all { it.status == NodeStatus.DONE })
        assertTrue(cp.answer!!.contains("battery"))
        assertEquals(listOf("device_info"), h.tools.calls.map { it.first })
        assertEquals(cp, h.store.saved["r1"]!!.copy(updatedAt = cp.updatedAt))
        assertTrue(h.store.saves >= 5) // checkpoint after every node
        assertTrue(b.eventLog().any { it is ActivityEvent.PlanReady })
    }

    @Test fun modelPlanParallelReadsAndApprovedActionWithSerializedInference() = runBlocking {
        val llm = FakeLlm(plan = """{"steps":[{"tool":"device_info","goal":"read battery","after":[]},{"tool":"recent_notifications","goal":"read notifications","after":[]},{"tool":"flashlight","goal":"torch on","after":[1]}]}""",
            answer = "Battery 81%. Flashlight is on.")
        val h = Harness(llm)
        val b = Blackboard("r2", "battery, notifications and turn on the torch")
        val cp = h.orchestrator.run(b.request, b)
        assertEquals(TaskStatus.COMPLETED, cp.status); assertEquals("model", cp.plan!!.source)
        assertEquals(1, llm.maxActive.get()) // one model, serialized
        val names = h.tools.calls.map { it.first }
        assertTrue(names.containsAll(listOf("device_info", "recent_notifications", "flashlight")))
        assertTrue(names.indexOf("flashlight") > names.indexOf("device_info")) // DAG order respected
        assertEquals(setOf("recent_notifications", "flashlight"), h.approvals.map { it.tool }.toSet()) // sensitive read + action
        assertEquals(Risk.MEDIUM, h.approvals.first { it.tool == "flashlight" }.risk)
        assertTrue(h.audit.any { it.contains("approved flashlight") })
        assertEquals(true, cp.nodes.first { it.id == "answer" }.verified)
        assertEquals("Battery 81%. Flashlight is on.", cp.answer)
    }

    @Test fun deniedActionIsNotExecutedAndWriterStillAnswers() = runBlocking {
        val h = Harness(llm = null, approve = false)
        val b = Blackboard("r3", "turn on the flashlight")
        val cp = h.orchestrator.run(b.request, b)
        assertEquals(NodeStatus.DENIED, cp.nodes.first { it.id == "s1" }.status)
        assertTrue(h.tools.calls.none { it.first == "flashlight" })
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertTrue(cp.answer!!.contains("DENIED"))
    }

    @Test fun networkActionWhileOfflineGoesToOutbox() = runBlocking {
        val llm = FakeLlm(plan = """{"steps":[{"tool":"fetch_url","goal":"fetch the page","after":[]}]}""", answer = "Queued the fetch for when you're online.")
        val h = Harness(llm, online = false)
        val b = Blackboard("r4", "fetch example.com page")
        val cp = h.orchestrator.run(b.request, b)
        assertEquals(NodeStatus.QUEUED_OFFLINE, cp.nodes.first { it.id == "s1" }.status)
        assertTrue(h.tools.calls.none { it.first == "fetch_url" })
        val item = h.outboxStore.items.values.single()
        assertEquals("fetch_url", item.tool); assertEquals(OutboxStatus.QUEUED, item.status)
        assertTrue(b.eventLog().any { it is ActivityEvent.Message && it.message is AgentMessage.QueuedOffline })
    }

    @Test fun failingDeviceReadIsRetriedThenReDelegatedToMemory() = runBlocking {
        val h = Harness(llm = null)
        h.tools.failDeviceInfo = 5
        val b = Blackboard("r5", "battery status please")
        val cp = h.orchestrator.run(b.request, b)
        val s1 = cp.nodes.first { it.id == "s1" }
        assertEquals(NodeStatus.DONE, s1.status); assertEquals(AgentRole.MEMORY, s1.delegatedTo); assertEquals(3, s1.attempts)
        assertEquals(2, h.tools.calls.count { it.first == "device_info" })
        assertTrue(b.eventLog().any { it is ActivityEvent.Note && it.text.contains("re-delegating") })
    }

    @Test fun verifierRejectsBadAnswerAndWriterRetries() = runBlocking {
        val llm = FakeLlm(answer = "x\nx\nx\nx")
        val h = Harness(llm)
        val b = Blackboard("r6", "hello there")
        val cp = h.orchestrator.run(b.request, b)
        val ans = cp.nodes.first { it.id == "answer" }
        assertEquals(2, ans.attempts); assertEquals(false, ans.verified) // kept, but marked unverified
        assertTrue(b.eventLog().any { it is ActivityEvent.Message && (it.message as? AgentMessage.Verification)?.passed == false })
    }

    @Test fun writerFallsBackToTemplateWhenModelFails() = runBlocking {
        val llm = object : com.offlineagent.core.runtime.agent.AgentLlm {
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String =
                if (system.startsWith("You plan")) """{"steps":[{"tool":"device_info","goal":"read battery","after":[]}]}""" else error("model unloaded (low memory)")
        }
        val h = Harness(llm)
        val b = Blackboard("r11", "check the battery and then tell me") // multi-step → planner route (5b)
        val cp = h.orchestrator.run(b.request, b)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertTrue(cp.answer!!.contains("read battery")); assertTrue(cp.answer!!.contains("could not write"))
    }

    @Test fun memoryOptOutRecallsAndStoresNothing() = runBlocking {
        val h = Harness(llm = null); h.memory.enabled = false
        val b = Blackboard("r7", "battery?")
        h.orchestrator.run(b.request, b)
        assertTrue(b.facts()["memory"]!!.contains("turned off")); assertTrue(h.memory.remembered.isEmpty())
    }

    @Test fun cancellationIsCheckpointed() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(llm = null)
        val deps = AgentDeps(null, h.tools, h.memory, object : ContextProvider {
            override suspend fun snapshot(includeScreen: Boolean): DeviceContext { gate.await(); error("unreachable") }
        }, { true }, { true }, { _, _, _, _ -> "" }, { _, _, _, _, _ -> }, h.store)
        val b = Blackboard("r8", "battery")
        val job = launch { Orchestrator(deps).run(b.request, b) }
        delay(50); job.cancel(); job.join()
        assertEquals(TaskStatus.CANCELLED, h.store.saved["r8"]!!.status)
    }
}

class ResumeTest {
    @Test fun resumeSkipsFinishedNodesAndRunsTheRest() = runBlocking {
        val h = Harness(llm = null)
        val plan = PlannerAgent.assemble("battery?", RulePlanner.steps("battery?", h.tools.specs()), "rules")
        // Simulated process death: ctx + mem finished, s1 was RUNNING, answer pending.
        val cp = TaskCheckpoint("r9", "battery?", TaskStatus.RUNNING, plan, listOf(
            NodeState("ctx", AgentRole.CONTEXT, "ctx", NodeStatus.DONE, 1, "ok"),
            NodeState("mem", AgentRole.MEMORY, "mem", NodeStatus.DONE, 1, "ok"),
            NodeState("s1", AgentRole.DEVICE, "read", NodeStatus.RUNNING, 1),
            NodeState("answer", AgentRole.WRITER, "answer"),
        ), mapOf("context" to "Time: x", "memory" to "-"))
        h.store.save(cp)
        val b = Blackboard("r9", "battery?")
        val out = h.orchestrator.resume(h.store.load("r9")!!, b)
        assertEquals(TaskStatus.COMPLETED, out.status)
        assertEquals(0, h.ctx.calls.get()) // context not re-read
        assertEquals(listOf("device_info"), h.tools.calls.map { it.first })
        assertEquals(2, out.nodes.first { it.id == "s1" }.attempts) // attempt count carried over
        assertNotNull(out.answer)
    }

    @Test fun resumeWithoutPlanReplans() = runBlocking {
        val h = Harness(llm = null)
        val out = h.orchestrator.resume(TaskCheckpoint("r10", "battery?", TaskStatus.PLANNING), Blackboard("r10", "battery?"))
        assertEquals(TaskStatus.COMPLETED, out.status); assertNull(out.error)
    }
}

class OutboxTest {
    private var now = 1_000_000L
    private fun setup(online: Boolean): Triple<InMemoryOutboxStore, FakeTools, OutboxProcessor> {
        val store = InMemoryOutboxStore(); val tools = FakeTools(); var on = online
        return Triple(store, tools, OutboxProcessor(store, tools, { on }, { now }))
    }

    @Test fun offlineDrainDoesNothing() = runBlocking {
        val (store, tools, p) = setup(false)
        p.enqueue("r", "fetch_url", kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive("https://a.b")) }, Risk.HIGH)
        val r = p.drain()
        assertTrue(r.offline); assertTrue(r.needsRetry); assertTrue(tools.calls.isEmpty()); assertEquals(OutboxStatus.QUEUED, store.items.values.single().status)
    }

    @Test fun onlineDrainRunsInOrderAndCompletes() = runBlocking {
        val (store, tools, p) = setup(true)
        p.enqueue("r", "fetch_url", kotlinx.serialization.json.JsonObject(emptyMap()), Risk.HIGH); now++
        p.enqueue("r", "get_datetime", kotlinx.serialization.json.JsonObject(emptyMap()), Risk.NONE)
        val finished = mutableListOf<String>()
        val r = p.drain { finished += it.tool }
        assertEquals(2, r.done); assertFalse(r.needsRetry); assertEquals(listOf("fetch_url", "get_datetime"), finished)
        assertTrue(store.items.values.all { it.status == OutboxStatus.DONE && it.result != null })
    }

    @Test fun retryableFailureBacksOffThenFailsAfterMaxAttempts() = runBlocking {
        val (store, tools, p) = setup(true)
        tools.fetchError = ToolResult.Error("timeout", retryable = true)
        p.enqueue("r", "fetch_url", kotlinx.serialization.json.JsonObject(emptyMap()), Risk.HIGH)
        var r = p.drain()
        val item = store.items.values.single()
        assertEquals(OutboxStatus.QUEUED, item.status); assertEquals(1, item.attempts); assertEquals(now + 30_000, item.nextAttemptAt); assertTrue(r.needsRetry)
        r = p.drain(); assertEquals(1, store.items.values.single().attempts) // not due yet
        repeat(4) { now += 3_600_000; p.drain() }
        assertEquals(OutboxStatus.FAILED, store.items.values.single().status); assertEquals(5, store.items.values.single().attempts)
        assertEquals(60_000L, OutboxProcessor.backoffMs(2)); assertEquals(3_600_000L, OutboxProcessor.backoffMs(20))
    }

    @Test fun nonRetryableFailureFailsImmediately() = runBlocking {
        val (store, tools, p) = setup(true)
        tools.fetchError = ToolResult.Error("404", retryable = false)
        p.enqueue("r", "fetch_url", kotlinx.serialization.json.JsonObject(emptyMap()), Risk.HIGH)
        assertEquals(1, p.drain().failed); assertEquals(OutboxStatus.FAILED, store.items.values.single().status)
    }

    @Test fun interruptedRunningItemIsRetried() = runBlocking {
        val (store, tools, p) = setup(true)
        val id = p.enqueue("r", "get_datetime", kotlinx.serialization.json.JsonObject(emptyMap()), Risk.NONE)
        store.update(store.items[id]!!.copy(status = OutboxStatus.RUNNING, attempts = 1)) // process died mid-run
        assertEquals(1, p.drain().done)
    }
}

class VerificationTest {
    private val v = VerificationAgent(Harness().deps, llmCheck = false)

    @Test fun rulesCatchEmptyErrorThinkingAndRepetition() {
        val b = Blackboard("r", "q"); val n = TaskNode("s1", AgentRole.DEVICE, "g")
        assertFalse(v.ruleCheck(n, NodeOutcome.Ok(""), b).passed)
        assertFalse(v.ruleCheck(n, NodeOutcome.Ok("x", kotlinx.serialization.json.buildJsonObject { put("error", kotlinx.serialization.json.JsonPrimitive("boom")) }), b).passed)
        assertFalse(v.ruleCheck(n, NodeOutcome.Ok("<think>hmm</think> ok"), b).passed)
        assertTrue(v.ruleCheck(n, NodeOutcome.Ok("Battery 81%"), b).passed)
    }

    @Test fun writerMayNotClaimSuccessForDeniedAction() {
        val b = Blackboard("r", "torch on")
        b.setPlan(PlannerAgent.assemble("torch on", listOf(PlannerAgent.Step(AgentRole.EXECUTOR, "flashlight", "Switch the flashlight", emptyList())), "rules"))
        b.updateNode("s1") { it.copy(status = NodeStatus.DENIED) }
        val w = TaskNode("answer", AgentRole.WRITER, "a")
        assertFalse(v.ruleCheck(w, NodeOutcome.Ok("Done! I have turned it on."), b).passed)
        assertTrue(v.ruleCheck(w, NodeOutcome.Ok("You declined, so the torch stays off."), b).passed)
    }
}

class RuntimeSelectorTest {
    private fun snap(ramGb: Double, thermal: Thermal = Thermal.NONE, saver: Boolean = false, avail: Long = 3000) =
        DeviceSnapshot((ramGb * 1024).toLong(), avail, false, 8, 4, "SM8550", 35, thermal, saver)

    @Test fun tiersAndPicks() {
        assertEquals(DeviceTier.ENTRY, RuntimeSelector.tier(snap(3.6)))
        assertEquals(DeviceTier.MID, RuntimeSelector.tier(snap(5.5)))
        val p8 = RuntimeSelector.plan(snap(7.3)) // a typical 8 GB phone reports ~7.3 GB
        assertEquals(DeviceTier.HIGH, p8.tier); assertEquals(8192, p8.contextLength); assertEquals(4, p8.threads)
        assertEquals("qwen3-1.7b", p8.picks.first { it.primary }.catalogId)
        // Phase 6: the fast 1.7B stays the primary on 12 GB phones too (battery/speed); 4B-class is an option.
        val p12 = RuntimeSelector.plan(snap(11.2, avail = 6000)) // 12 GB phones (e.g. OnePlus CPH2569) report ~11.2 GB
        assertEquals("qwen3-1.7b", p12.picks.first { it.primary }.catalogId)
        assertTrue(p12.picks.any { it.catalogId == "qwen3-4b-2507" })
        assertEquals(4096, RuntimeSelector.plan(snap(7.3, avail = 1500)).contextLength)
    }

    @Test fun thermalAndBatterySaverReduceThreadsOrPause() {
        assertEquals(2, ThermalGovernor.decide(Thermal.MODERATE, false, 4).threads)
        assertTrue(ThermalGovernor.decide(Thermal.SEVERE, false, 4).pauseInference)
        assertFalse(ThermalGovernor.decide(Thermal.LIGHT, false, 4).pauseInference)
        assertEquals(2, ThermalGovernor.decide(Thermal.NONE, true, 6).threads)
        assertEquals(Thermal.CRITICAL, Thermal.fromAndroid(4))
    }
}
