package com.offlineagent.core.screen

import com.offlineagent.core.agents.AgentDeps
import com.offlineagent.core.agents.AgentRole
import com.offlineagent.core.agents.ApprovalGate
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Blackboard
import com.offlineagent.core.agents.ContextProvider
import com.offlineagent.core.agents.DeviceContext
import com.offlineagent.core.agents.InMemoryTaskStore
import com.offlineagent.core.agents.InferenceQueue
import com.offlineagent.core.agents.MemoryHit
import com.offlineagent.core.agents.MemoryStore
import com.offlineagent.core.agents.NodeOutcome
import com.offlineagent.core.agents.NodeStatus
import com.offlineagent.core.agents.Orchestrator
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.TaskNode
import com.offlineagent.core.agents.TaskStatus
import com.offlineagent.core.agents.ToolGateway
import com.offlineagent.core.agents.VerificationAgent
import com.offlineagent.core.agents.riskOf
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gateway over the screen toolkit, like OnyxRuntime.gateway in the app (risk from the toolkit). */
class ScreenGateway(private val kit: ScreenToolkit) : ToolGateway {
    private val tools = kit.tools().associateBy { it.spec.name }
    val calls = mutableListOf<String>()
    override fun specs(): List<ToolSpec> = tools.values.map { it.spec }
    override fun risk(name: String, args: JsonObject): Risk = kit.risk(name, args) ?: spec(name)?.let { riskOf(it) } ?: Risk.HIGH
    override suspend fun execute(name: String, args: JsonObject): ToolResult { calls += name; return tools.getValue(name).execute(args, ToolContext("onyx", null)) }
}

/** Scripted model for the two-app task: plans the four screen steps, fills each tool's arguments, writes the answer. */
class TransferLlm : AgentLlm {
    val prompts = mutableListOf<Pair<String, String>>()
    override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
        prompts += system.take(60) to user
        return when {
            system.startsWith("You plan") -> """{"steps":[
                {"tool":"screen_open_app","goal":"Open ShopApp","after":[]},
                {"tool":"screen_extract","goal":"Copy the order ID from the order page","after":[1]},
                {"tool":"screen_transfer","goal":"Paste it into the chat with Ravi in ChatApp","after":[2]},
                {"tool":"screen_assert","goal":"Check the order ID is in the message box","after":[3]}]}"""
            system.startsWith("Fill the arguments for the tool screen_open_app") -> """{"app":"ShopApp"}"""
            system.startsWith("Fill the arguments for the tool screen_extract") -> """{"fields":["order id"]}"""
            system.startsWith("Fill the arguments for the tool screen_transfer") -> """{"text":"My order {{order id}} has not arrived","to_app":"ChatApp","target":"Message","method":"auto"}"""
            system.startsWith("Fill the arguments for the tool screen_assert") -> """{"text":"{{order id}}","present":true}"""
            system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
            else -> "I copied order 405-7781234 from ShopApp into your chat with Ravi in ChatApp. It is not sent yet."
        }
    }
}

class CrossAppWorkflowTest {
    private fun deps(kit: ScreenToolkit, gw: ToolGateway, llm: AgentLlm, asked: MutableList<ApprovalRequest>, audit: MutableList<String>) = AgentDeps(
        queue = InferenceQueue(llm), tools = gw,
        memory = object : MemoryStore { override val enabled = false; override suspend fun recall(query: String, limit: Int) = emptyList<MemoryHit>() },
        context = object : ContextProvider { override suspend fun snapshot(includeScreen: Boolean) = DeviceContext("2026-11-03T10:00", "Asia/Kolkata", online = true) },
        approvals = kit.approvals.wrap(ApprovalGate { r -> asked += r; true }),
        network = { true }, outbox = { _, _, _, _ -> "x" }, audit = { _, actor, action, _, _ -> audit += "$actor:$action" }, store = InMemoryTaskStore(),
    )

    @Test fun extractInAppAThenPasteInAppBThroughTheOrchestrator() = runBlocking {
        val shop = ShopApp(); val chat = ChatApp()
        val rig = Rig(shop, chat)
        rig.phone.foreground = "com.example.launcher".let { shop.pkg } // starts anywhere; open_app switches
        val gw = ScreenGateway(rig.kit)
        val asked = mutableListOf<ApprovalRequest>(); val audit = mutableListOf<String>()
        val llm = TransferLlm()
        val board = Blackboard("run-1", "Open ShopApp, copy the order ID and paste it into my chat with Ravi in ChatApp")
        val cp = Orchestrator(deps(rig.kit, gw, llm, asked, audit)).run(board.request, board)

        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(listOf("screen_open_app", "screen_extract", "screen_transfer", "screen_assert"), gw.calls)
        cp.nodes.filter { it.id.startsWith("s") }.forEach { assertEquals(it.goal, NodeStatus.DONE, it.status) }
        // the value crossed apps exactly (no model copying), and nothing was sent
        assertEquals("My order 405-7781234 has not arrived", chat.input)
        assertEquals(2, chat.messages.size)
        // scoped approvals: one per app for the whole task (open ShopApp, operate ChatApp); reads were covered
        assertEquals(listOf("Let ONYX operate ShopApp for this task", "Let ONYX operate ChatApp for this task"), asked.map { it.title })
        assertTrue(rig.audit.count { it.startsWith("auto-approved") } >= 2)
        // every screen action is audited; screen text never reaches the audit log
        assertTrue(rig.audit.any { it.startsWith("screen_open_app") } && rig.audit.any { it.startsWith("screen_transfer type") })
        assertFalse(rig.audit.any { it.contains("405-7781234") })
        // the extracted screen data reached the model only inside the untrusted-data wrapper
        val writer = llm.prompts.last { !it.first.startsWith("Check") }.second
        assertTrue(writer, writer.contains(ScreenData.OPEN))
        assertTrue(cp.answer!!.contains("not sent"))
    }

    @Test fun irreversibleSendInsideAPlanAsksEveryTime() = runBlocking {
        val chat = ChatApp().also { it.input = "On my way" }
        val rig = Rig(chat)
        rig.s.snapshot("full", "")
        val gw = ScreenGateway(rig.kit)
        val asked = mutableListOf<ApprovalRequest>()
        val llm = object : AgentLlm {
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int) = when {
                system.startsWith("You plan") -> """{"steps":[{"tool":"screen_act","goal":"Tap Send in ChatApp","after":[]}]}"""
                system.startsWith("Fill the arguments for the tool screen_act") -> """{"ref":"","target":"Send","action":"tap","text":"","expect":"On my way"}"""
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                else -> "Sent your message."
            }
        }
        val board = Blackboard("run-2", "Tap send in the chat app screen")
        val cp = Orchestrator(deps(rig.kit, gw, llm, asked, mutableListOf())).run(board.request, board)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(1, asked.size)
        assertEquals(Risk.CRITICAL, asked.single().risk)
        assertTrue(asked.single().title.startsWith("Confirm this step in ChatApp"))
        assertTrue("sheet approval covers the step; overlay not asked again", rig.host.confirms.isEmpty())
        assertEquals(listOf("Hi!", "When will my order arrive?", "On my way"), chat.messages)
    }

    @Test fun failedSubgoalIsAVerifierIssueAndTheRetryDoesNotRepeatTheTap() = runBlocking {
        val chat = ChatApp().also { it.input = "hello" }
        val rig = Rig(chat)
        val gw = ScreenGateway(rig.kit)
        val llm = object : AgentLlm {
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int) = when {
                system.startsWith("You plan") -> """{"steps":[{"tool":"screen_act","goal":"Send and wait for the read receipt","after":[]}]}"""
                system.startsWith("Fill the arguments for the tool screen_act") -> """{"ref":"","target":"Send","action":"tap","text":"","expect":"Seen"}"""
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                else -> "I sent it but did not see it being read."
            }
        }
        val board = Blackboard("run-3", "Tap send in the chat app screen and wait until it is seen")
        val cp = Orchestrator(deps(rig.kit, gw, llm, mutableListOf(), mutableListOf())).run(board.request, board)
        val step = cp.nodes.first { it.id == "s1" }
        assertEquals(NodeStatus.FAILED, step.status)
        assertTrue(step.error, step.error!!.contains("subgoal not met"))
        assertEquals("tool called twice (attempt + verifier retry)", 2, gw.calls.size)
        assertEquals("but the Send tap happened once", 1, rig.phone.performed.count { it.contains("CLICK:Send") })
        assertEquals(3, chat.messages.size)
    }

    @Test fun verifierTurnsSubgoalFailureIntoAnIssue() {
        val v = VerificationAgent(deps(Rig(ChatApp()).kit, ScreenGateway(Rig(ChatApp()).kit), TransferLlm(), mutableListOf(), mutableListOf()), llmCheck = false)
        val data = buildJsonObject { putJsonObject("subgoal") { put("passed", false); put("issue", "expected \"Sent\"") } }
        val r = v.ruleCheck(TaskNode("s1", AgentRole.EXECUTOR, "send"), NodeOutcome.Ok("Did: tap", data), Blackboard("r", "x"))
        assertFalse(r.passed)
        assertEquals(listOf("subgoal not met: expected \"Sent\""), r.issues)
        val ok = buildJsonObject { putJsonObject("subgoal") { put("passed", true); put("issue", "") } }
        assertTrue(v.ruleCheck(TaskNode("s1", AgentRole.EXECUTOR, "send"), NodeOutcome.Ok("Did: tap", ok), Blackboard("r", "x")).passed)
    }
}
