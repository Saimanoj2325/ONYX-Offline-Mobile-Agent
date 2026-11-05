package com.offlineagent.core.runtime

import com.offlineagent.core.inference.Role
import com.offlineagent.core.runtime.agent.AgentLimits
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.agent.AgentLoop
import com.offlineagent.core.runtime.agent.ApprovalGate
import com.offlineagent.core.runtime.chat.AnswerPdfExporter
import com.offlineagent.core.runtime.chat.FollowUp
import com.offlineagent.core.runtime.chat.SessionContext
import com.offlineagent.core.runtime.chat.SessionHistory
import com.offlineagent.core.runtime.chat.StoredMessage
import com.offlineagent.core.runtime.context.ContextConfig
import com.offlineagent.core.runtime.context.ContextManager
import com.offlineagent.core.runtime.context.ConversationSummarizer
import com.offlineagent.core.runtime.context.TokenCounter
import com.offlineagent.core.runtime.pdf.PdfBlock
import com.offlineagent.core.runtime.pdf.PdfDocModel
import com.offlineagent.core.runtime.pdf.PdfOutput
import com.offlineagent.core.runtime.pdf.PdfPort
import com.offlineagent.core.runtime.pdf.ReportWriter
import com.offlineagent.core.runtime.tools.CalculatorTool
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.6.2.1: follow-ups keep the conversation. Sai's bug: "what are transformers" → answer → "make it as a pdf" produced an
 * unrelated PDF, because the PDF / tool / agent routes built their prompts from the latest message alone.
 */
class SessionContextTest {
    private val answer = "Transformers are neural networks built on **self-attention**. Each token attends to every other token, " +
        "so long-range context is captured in parallel.\n\n- Encoder: reads the input\n- Decoder: writes the output\n\nThey power GPT and BERT."

    /** A chat as Room stores it (the "database" that survives restarts). */
    private class FakeRoom {
        val rows = mutableListOf<StoredMessage>()
        private var next = 1L
        fun add(role: String, text: String, kind: String? = null, reasoning: String = "", runId: String? = null, error: String? = null): Long {
            val id = next++; rows += StoredMessage(id, role, text, kind, reasoning, null, error, runId); return id
        }
        fun snapshot(): List<StoredMessage> = rows.toList()
    }

    private class FakePdf : PdfPort {
        val docs = mutableListOf<Pair<PdfDocModel, String>>()
        override suspend fun createPdf(doc: PdfDocModel, fileName: String): PdfOutput { docs += doc to fileName; return PdfOutput("content://x/$fileName", fileName, 1, 1000) }
        override suspend fun canvasMarkdown(chatId: Long?) = null
    }

    /** Records every prompt; never expected to be called on the plain export path. */
    private class RecordingLlm(val reply: (String, String) -> String = { _, _ -> "ok" }) : AgentLlm {
        val calls = mutableListOf<Pair<String, String>>()
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String { calls += system to user; return reply(system, user) }
    }

    private fun plainText(blocks: List<PdfBlock>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is PdfBlock.Heading -> b.text
            is PdfBlock.Paragraph -> b.runs.joinToString("") { it.text }
            is PdfBlock.Bullet -> b.runs.joinToString("") { it.text }
            else -> ""
        }
    }

    @Test fun makeItAsAPdfExportsThePreviousAnswerVerbatim() = runTest {
        val db = FakeRoom()
        db.add("user", "what are transformers"); db.add("assistant", answer); db.add("user", "make it as a pdf")
        val turns = SessionHistory.turns(db.snapshot())
        assertEquals(2, turns.size) // the request itself is not history
        val plan = FollowUp.plan("make it as a pdf", turns)
        assertEquals(FollowUp.Kind.EXPORT, plan.kind)
        assertEquals(answer, plan.target!!.answer)
        val pdf = FakePdf()
        val llm = RecordingLlm()
        val out = AnswerPdfExporter(pdf).export(plan)
        assertNotNull(out)
        val (doc, name) = pdf.docs.single()
        assertEquals("Transformers", doc.title)
        assertEquals("Transformers.pdf", name)
        val text = plainText(doc.blocks)
        assertTrue(text, text.contains("Transformers are neural networks built on self-attention"))
        assertTrue(text.contains("Encoder: reads the input"))
        assertTrue(text.contains("They power GPT and BERT."))
        assertTrue(out!!.chatMessage().startsWith(FollowUp.EXPORT_NOTICE))
        assertTrue("no model call: nothing is regenerated", llm.calls.isEmpty())
    }

    @Test fun exportRequestsAreRecognisedAndQuestionsAboutPdfsAreNot() {
        for (m in listOf("make it as a pdf", "export this", "pdf", "as pdf please", "convert that to pdf", "save it as a pdf", "can you make this a PDF?"))
            assertEquals(m, FollowUp.Kind.EXPORT, FollowUp.classify(m))
        for (m in listOf("make a detailed pdf on it", "make it a longer pdf with examples"))
            assertEquals(m, FollowUp.Kind.REWRITE_EXPORT, FollowUp.classify(m))
        for (m in listOf("make a pdf on photosynthesis", "what is a pdf file format and how does it work", "translate it", "summarise that", "in Telugu", "and its disadvantages?"))
            assertEquals(m, FollowUp.Kind.NONE, FollowUp.classify(m))
    }

    @Test fun resolvesTheReferencedAnswerAndTitles() {
        val db = FakeRoom()
        db.add("user", "what are transformers"); db.add("assistant", answer)
        db.add("user", "explain photosynthesis"); db.add("assistant", "Plants turn light into sugar.")
        val turns = SessionHistory.turns(db.snapshot(), dropTrailingUser = false)
        assertEquals("Plants turn light into sugar.", FollowUp.resolve("make it as a pdf", turns)!!.answer)
        assertEquals(answer, FollowUp.resolve("make the transformers answer a pdf", turns)!!.answer)
        assertEquals(answer, FollowUp.resolve("pdf of the first one", turns)!!.answer)
        assertEquals("Transformers", FollowUp.titleFrom("what are transformers"))
        assertEquals("Photosynthesis", FollowUp.titleFrom("explain photosynthesis"))
        assertEquals("Attention in LLMs", FollowUp.titleFrom("Can you tell me about attention in LLMs?"))
        assertEquals("How does BERT work", FollowUp.titleFrom("how does BERT work?"))
        // An exported-PDF notice is never "it".
        db.add("user", "make it as a pdf"); db.add("assistant", "${FollowUp.EXPORT_NOTICE} (\"Photosynthesis\").")
        assertEquals("Plants turn light into sugar.", FollowUp.resolve("export it", SessionHistory.turns(db.snapshot(), false))!!.answer)
    }

    @Test fun detailedPdfRegeneratesWithTheEarlierQaAsContext() = runTest {
        val db = FakeRoom()
        db.add("user", "what are transformers"); db.add("assistant", answer); db.add("user", "make a detailed pdf on it")
        val rows = db.snapshot()
        assertEquals(FollowUp.Kind.REWRITE_EXPORT, FollowUp.plan("make a detailed pdf on it", SessionHistory.turns(rows)).kind)
        val session = SessionContext.build(SessionHistory.turns(rows), SessionHistory.latestSummary(rows), "", 1100).text
        val llm = RecordingLlm { sys, _ -> if (sys.startsWith("You plan")) """{"title":"Transformers in depth","subtitle":"","sections":[{"heading":"Attention","points":["a"]},{"heading":"Uses","points":["b"]}]}""" else "Body." }
        val w = ReportWriter(llm)
        val outline = w.outline("make a detailed pdf on it", 2, conversation = session)
        w.write("make a detailed pdf on it", outline, conversation = session)
        assertEquals("Transformers in depth", outline.title)
        assertEquals(3, llm.calls.size)
        llm.calls.forEach { (_, user) ->
            assertTrue(user, user.contains("User: what are transformers"))
            assertTrue(user.contains("Transformers are neural networks built on **self-attention**"))
        }
    }

    @Test fun threeTurnFollowUpChainThroughChatSeesEveryTurn() = runTest {
        val db = FakeRoom()
        db.add("user", "what are transformers"); db.add("assistant", answer)
        db.add("user", "and its disadvantages?"); db.add("assistant", "Attention is quadratic in sequence length, so long inputs are costly.")
        db.add("user", "do the same for BERT")
        val turns = SessionHistory.turns(db.snapshot())
        val built = ContextManager(TokenCounter.ESTIMATE, null, null).build("You are ONYX.", turns, null, "do the same for BERT", 4096, 512)
        val roles = built.messages.map { it.role }
        assertEquals(listOf(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT, Role.USER), roles)
        assertEquals("what are transformers", built.messages[1].content)
        assertEquals(answer, built.messages[2].content) // verbatim
        assertEquals("and its disadvantages?", built.messages[3].content)
        assertTrue(built.messages[4].content.startsWith("Attention is quadratic"))
        assertTrue(built.messages.last().content.endsWith("do the same for BERT"))
        // The same chain as a session block (agents / tools / skills routes).
        val block = SessionContext.build(turns, null, "", 1100).text
        listOf("User: what are transformers", "Assistant: Transformers are neural networks", "User: and its disadvantages?", "Assistant: Attention is quadratic")
            .forEach { assertTrue(it, block.contains(it)) }
        assertFalse("the latest message is sent separately", block.contains("do the same for BERT"))
    }

    @Test fun olderTurnsBecomeARollingSummaryAndTheLatestAnswerAlwaysStays() = runTest {
        val db = FakeRoom()
        repeat(12) { i -> db.add("user", "question $i " + "x".repeat(400)); db.add("assistant", "answer $i " + "y".repeat(800)) }
        db.add("user", "and the next one?")
        var folds = 0
        val summarizer = ConversationSummarizer { prev, turns, _ -> folds++; (prev?.let { "$it; " } ?: "") + "talked about ${turns.size} messages" }
        val r = SessionContext.build(SessionHistory.turns(db.snapshot()), null, "", 1100, summarizer = summarizer)
        assertTrue(r.summaryChanged); assertTrue(folds > 0)
        assertTrue(r.text, r.text.contains("Summary of earlier messages: "))
        assertTrue(r.text.contains("answer 11 "))
        assertTrue(r.tokens <= 1100 + 50)
        // Without a summarizer (no model loaded) the newest turns that fit are kept; an oversized last answer is cut, never dropped.
        val huge = FakeRoom().apply { add("user", "what are transformers"); add("assistant", "z".repeat(20_000)); add("user", "make it shorter") }
        val r2 = SessionContext.build(SessionHistory.turns(huge.snapshot()), null, "", 450)
        assertTrue(r2.text.contains("Assistant: zzz")); assertTrue(r2.text.contains("…(cut)"))
    }

    @Test fun historyIsRehydratedFromRoomAfterRestartAndModelReload() = runTest {
        val db = FakeRoom()
        db.add("user", "what are transformers"); db.add("assistant", answer)
        db.add("system", "User asked about transformers; ONYX explained attention.", kind = SessionHistory.KIND_SUMMARY, runId = "upTo:2")
        db.add("user", "and its disadvantages?"); db.add("assistant", "Quadratic attention cost.")
        db.add("user", "do the same for BERT")
        // "Before": built while the model was loaded (exact counter) …
        val before = SessionContext.build(SessionHistory.turns(db.snapshot()), SessionHistory.latestSummary(db.snapshot()), "", 1100, counter = { it.length / 4 + 1 })
        // … app restart / idle unload: a new process only has the Room rows and no model (estimated tokens, no summarizer).
        val rowsAfterRestart = db.snapshot().map { it.copy() }
        val after = SessionContext.build(SessionHistory.turns(rowsAfterRestart), SessionHistory.latestSummary(rowsAfterRestart), "", 1100)
        assertEquals(before.text, after.text)
        assertTrue(after.text.contains("Summary of earlier messages: User asked about transformers"))
        assertTrue(after.text.contains("User: and its disadvantages?"))
        assertTrue(after.text.contains("Assistant: Quadratic attention cost."))
        assertFalse("turns folded into the summary are not repeated", after.text.contains("User: what are transformers"))
        // Failed answers and summary rows are not turns.
        db.add("assistant", "", error = "engine error")
        assertEquals(2, SessionHistory.turns(db.snapshot(), dropTrailingUser = false).count { it.role == Role.ASSISTANT })
    }

    @Test fun memoryBlockIsAddedAndATemporaryChatStillHasItsHistory() = runTest {
        val turns = SessionHistory.turns(listOf(StoredMessage(1, "user", "what are transformers"), StoredMessage(2, "assistant", answer), StoredMessage(3, "user", "shorter")))
        val normal = SessionContext.build(turns, null, "Saved memories:\n- Sai studies AIML at CBIT", 1100).text
        assertTrue(normal.startsWith("Saved memories:"))
        assertTrue(normal.contains("User: what are transformers"))
        val temporary = SessionContext.build(turns, null, "", 1100).text // temporary chat: no memory block
        assertFalse(temporary.contains("Saved memories"))
        assertTrue(temporary.contains("Assistant: Transformers are neural networks"))
        assertNull(FollowUp.plan("shorter", turns).target) // not an export: answered by the chat with the history
    }

    @Test fun onyxAnswersAreKeptVerbatimWithTaskNotes() {
        val rows = listOf(StoredMessage(1, "user", "what are transformers"),
            StoredMessage(2, "assistant", answer, kind = SessionHistory.KIND_ONYX, reasoning = "Earlier ONYX task: \"what are transformers\" (completed)\nAnswer given: Transformers are…"),
            StoredMessage(3, "user", "make it as a pdf"))
        val t = SessionHistory.turns(rows)
        assertTrue(t[1].text.startsWith(answer))
        assertTrue(t[1].text.contains("task notes: Earlier ONYX task"))
        assertEquals(answer, FollowUp.pdfBody(FollowUp.plan("make it as a pdf", t).target!!.answer, "Transformers"))
    }

    @Test fun legacyToolAgentPromptsCarryTheSession() = runTest {
        val llm = FakeLlm(mutableListOf("{\"steps\":[\"answer\"]}", "{\"thought\":\"ok\",\"final\":\"BERT is an encoder-only transformer.\"}"))
        val reg = ToolRegistry(listOf(CalculatorTool()))
        val session = "Conversation so far in this chat (oldest first):\nUser: what are transformers\nAssistant: Transformers use self-attention."
        val cp = AgentLoop(llm, reg, PolicyEngine(reg), MemStore(), ApprovalGate { _, _, _, _ -> true }, AgentLimits()).start("r1", 1, "do the same for BERT", null, session)
        assertEquals("BERT is an encoder-only transformer.", cp.finalAnswer)
        assertTrue(llm.calls.size >= 2)
        llm.calls.forEach { (user, _) ->
            assertTrue(user, user.contains("User: what are transformers"))
            assertTrue(user.contains("do the same for BERT"))
        }
    }

    @Test fun budgetGrowsWithTheContextWindow() {
        assertTrue(SessionContext.budgetFor(2048) < SessionContext.budgetFor(4096))
        assertTrue(SessionContext.budgetFor(4096) < SessionContext.budgetFor(8192))
        assertEquals(4000, SessionContext.budgetFor(32768))
        assertEquals(ContextConfig().perMessageOverhead, 8)
    }
}
