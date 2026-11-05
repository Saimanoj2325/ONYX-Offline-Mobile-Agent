package com.offlineagent.core.agents

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.chat.SessionContext
import com.offlineagent.core.runtime.chat.SessionHistory
import com.offlineagent.core.runtime.chat.StoredMessage
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.okJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.2.1: follow-ups through the ONYX agents path see the whole session (planner, argument filler, writer). */
class FollowUpContextTest {
    private val a1 = "Transformers are neural networks built on self-attention; they power GPT and BERT."
    private val a2 = "Their main drawback: attention cost grows quadratically with the input length."

    /** Records the user prompt of every model call, keyed by the agent's system prompt start. */
    private class RecordingLlm(val plan: String = """{"steps":[]}""", val answers: ArrayDeque<String> = ArrayDeque()) : AgentLlm {
        val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
            calls += system to user
            return when {
                system.startsWith("You plan") -> plan
                user.startsWith("Tool: create_note") -> """{"title":"Transformers","text":"$TRANSFORMERS_NOTE"}"""
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                else -> answers.removeFirstOrNull() ?: "ok"
            }
        }
        fun writerPrompts() = calls.filter { it.first.startsWith("You are ONYX") }.map { it.second }
        fun argPrompts(tool: String) = calls.filter { it.second.startsWith("Tool: $tool") }.map { it.second }
        companion object { const val TRANSFORMERS_NOTE = "Transformers use self-attention" }
    }

    private class NoteTools : ToolGateway {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        private val specs = listOf(
            ToolSpec("create_note", "Save a note with a title and text.", Schema.Obj(listOf("title" to Schema.Str(maxLength = 80, minLength = 1), "text" to Schema.Str(maxLength = 4000, minLength = 1))),
                Permission.WRITE_LOCAL, "Saves a note."),
            ToolSpec("get_datetime", "Current date and time.", Schema.Obj(emptyList()), Permission.READ_ONLY),
        )
        override fun specs() = specs
        override suspend fun execute(name: String, args: JsonObject): ToolResult { calls += name to args; return okJson("saved" to true) }
    }

    private fun deps(llm: AgentLlm, tools: ToolGateway = NoteTools(), memoryOn: Boolean = true) = AgentDeps(
        InferenceQueue(llm), tools, FakeMemory(memoryOn), FakeContext(), { true }, { true }, { _, _, _, _ -> "q" }, { _, _, _, _, _ -> }, InMemoryTaskStore())

    /** What ChatViewModel.runOnyx sends: the session rebuilt from the chat's stored rows. */
    private fun session(vararg rows: Pair<String, String>, memory: String = ""): String = runBlocking {
        val stored = rows.mapIndexed { i, (role, text) -> StoredMessage(i + 1L, role, text) }
        SessionContext.build(SessionHistory.turns(stored, dropTrailingUser = false), null, memory, 1100).text
    }

    @Test fun threeTurnFollowUpChainThroughTheAgents() = runBlocking {
        val llm = RecordingLlm(answers = ArrayDeque(listOf(a1, a2, "BERT is an encoder-only transformer; its drawback is a 512-token limit.")))
        val orch = Orchestrator(deps(llm))
        // Turn 1: no history yet.
        val cp1 = orch.run("what are transformers", Blackboard("t1", "what are transformers"))
        assertEquals(a1, cp1.answer)
        // Turn 2: pronoun follow-up.
        val s2 = session("user" to "what are transformers", "assistant" to a1)
        val cp2 = orch.run("and its disadvantages?", Blackboard("t2", "and its disadvantages?", conversation = s2))
        assertEquals(a2, cp2.answer)
        assertEquals(s2, cp2.conversation) // persisted with the task (resume after process death keeps it)
        // Turn 3: ellipsis follow-up.
        val s3 = session("user" to "what are transformers", "assistant" to a1, "user" to "and its disadvantages?", "assistant" to a2)
        orch.run("do the same for BERT", Blackboard("t3", "do the same for BERT", conversation = s3))
        val w = llm.writerPrompts()
        assertEquals(3, w.size)
        assertTrue(w[1], w[1].contains("User: what are transformers") && w[1].contains(a1) && w[1].contains("Request: and its disadvantages?"))
        listOf("User: what are transformers", a1, "User: and its disadvantages?", a2, "Request: do the same for BERT").forEach { assertTrue(it, w[2].contains(it)) }
    }

    @Test fun toolArgumentsAreFilledWithTheSessionNotTheBareRequest() = runBlocking {
        val tools = NoteTools()
        val llm = RecordingLlm(plan = """{"steps":[{"tool":"create_note","goal":"Save the earlier answer as a note","after":[]}]}""", answers = ArrayDeque(listOf("Saved it as a note.")))
        val s = session("user" to "what are transformers", "assistant" to a1)
        val cp = Orchestrator(deps(llm, tools)).run("save that as a note", Blackboard("n1", "save that as a note", conversation = s))
        assertEquals(TaskStatus.COMPLETED, cp.status)
        val planner = llm.calls.first { it.first.startsWith("You plan") }.second
        assertTrue(planner, planner.contains(a1))
        val args = llm.argPrompts("create_note").single()
        assertTrue(args, args.contains("User: what are transformers") && args.contains(a1))
        assertEquals("create_note", tools.calls.single().first)
        assertTrue((tools.calls.single().second["text"] as JsonPrimitive).content.startsWith("Transformers use self-attention"))
    }

    @Test fun temporaryChatStillAnswersFollowUpsWithItsOwnHistory() = runBlocking {
        val llm = RecordingLlm(answers = ArrayDeque(listOf(a2)))
        val s = session("user" to "what are transformers", "assistant" to a1) // no memory block in a temporary chat
        val board = Blackboard("tmp", "and its disadvantages?", conversation = s)
        val cp = Orchestrator(deps(llm, memoryOn = false)).run("and its disadvantages?", board)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(a2, cp.answer)
        val w = llm.writerPrompts().single()
        assertTrue(w.contains("User: what are transformers") && w.contains(a1))
        assertTrue(w.contains("memory is turned off"))
    }

    @Test fun memoryRecallForAShortFollowUpUsesTheLastQuestion() {
        val b = Blackboard("m", "and its disadvantages?", conversation = "Conversation so far in this chat (oldest first):\nUser: what are transformers\nAssistant: $a1")
        assertEquals("what are transformers and its disadvantages?", b.recallQuery())
        assertEquals("what are transformers", Blackboard("m2", "what are transformers").recallQuery())
    }

    @Test fun checkpointRoundTripKeepsTheSession() {
        val cp = TaskCheckpoint("r", "do the same for BERT", TaskStatus.RUNNING, conversation = session("user" to "what are transformers", "assistant" to a1))
        val back = TaskCheckpoint.fromJson(cp.toJson())
        assertEquals(cp.conversation, back.conversation)
        assertEquals(cp.conversation, Blackboard.fromCheckpoint(back).conversation)
    }
}
