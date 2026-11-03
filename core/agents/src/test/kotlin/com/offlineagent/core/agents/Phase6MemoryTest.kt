package com.offlineagent.core.agents

import com.offlineagent.core.agents.memory.Bm25Index
import com.offlineagent.core.agents.memory.ChatHistoryIndex
import com.offlineagent.core.agents.memory.ContactRanking
import com.offlineagent.core.agents.memory.InMemoryContactRepo
import com.offlineagent.core.agents.memory.InMemoryHistoryRepo
import com.offlineagent.core.agents.memory.InMemoryRoutineRepo
import com.offlineagent.core.agents.memory.InMemorySavedMemoryRepo
import com.offlineagent.core.agents.memory.MemoryBook
import com.offlineagent.core.agents.memory.MemoryCommand
import com.offlineagent.core.agents.memory.MemoryContext
import com.offlineagent.core.agents.memory.MemoryExtractor
import com.offlineagent.core.agents.memory.MemoryKind
import com.offlineagent.core.agents.memory.MemoryScope
import com.offlineagent.core.agents.memory.MemorySettings
import com.offlineagent.core.agents.memory.RollingSummary
import com.offlineagent.core.agents.memory.RoutineBook
import com.offlineagent.core.agents.memory.RoutineStep
import com.offlineagent.core.agents.memory.Text
import com.offlineagent.core.runtime.agent.AgentLlm
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 M2: saved memories, cross-chat history, forget, temporary chats, frequent contacts, routines. */
class Phase6MemoryTest {
    private var now = 1_800_000_000_000L
    private val clock = { now }
    private val on = MemorySettings()

    // ---------------------------------------------------------------- extraction
    @Test fun extractsDurableFactsFromChat() {
        val f = MemoryExtractor.extract("Hi! My name is Sai Manoj. I live in Hyderabad and I'm a student at CBIT. I prefer short answers.")
        val texts = f.map { it.text }
        assertTrue(texts.toString(), texts.any { it == "User's name is Sai Manoj" })
        assertTrue(texts.toString(), texts.any { it.startsWith("Lives in Hyderabad") })
        assertTrue(texts.toString(), texts.any { it.contains("student") })
        assertTrue(texts.toString(), texts.any { it == "Prefers short answers" })
        assertEquals("name", f.first { it.text.startsWith("User's name") }.slot)
    }

    @Test fun ignoresQuestionsHypotheticalsAndChitChat() {
        assertTrue(MemoryExtractor.extract("Do I live in Hyderabad?").isEmpty())
        assertTrue(MemoryExtractor.extract("What if I work at Google?").isEmpty())
        assertTrue(MemoryExtractor.extract("ok thanks").isEmpty())
        assertTrue(MemoryExtractor.extract("what's the weather like").isEmpty())
    }

    @Test fun explicitRememberAndForgetCommands() {
        assertEquals(MemoryCommand.Remember("User's sister's birthday is on 4 May"), MemoryExtractor.command("Remember that my sister's birthday is on 4 May"))
        assertEquals(MemoryCommand.Remember("Prefers tea over coffee"), MemoryExtractor.command("please remember I prefer tea over coffee"))
        assertEquals(MemoryCommand.Forget("my sister's birthday"), MemoryExtractor.command("forget about my sister's birthday"))
        assertEquals(MemoryCommand.Forget("tea"), MemoryExtractor.command("delete the memory about tea"))
        // reminders and tool requests are not memory commands
        assertNull(MemoryExtractor.command("remember to buy milk at 6"))
        assertNull(MemoryExtractor.command("delete the file report.pdf"))
    }

    @Test fun autoSaveMarksInferredAndChipShows() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        val u = book.onUserMessage("I'm allergic to peanuts, by the way.", "chat:1", MemoryScope.NORMAL, on)
        assertTrue(u.changed); assertEquals("Memory updated", u.chip())
        val m = book.all().single()
        assertEquals("Is allergic to peanuts", m.text)
        assertTrue(m.inferred); assertTrue(m.confidence in 0.9..1.0); assertEquals(MemoryKind.FACT, m.kind)
        // explicit "remember" is not inferred
        book.onUserMessage("remember that I take the 8:15 metro", "chat:1", MemoryScope.NORMAL, on)
        assertFalse(book.all().first { it.text.contains("metro") }.inferred)
    }

    @Test fun slotReplacesOldValueAndDuplicatesMerge() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        book.onUserMessage("I live in Chennai.", "chat:1", MemoryScope.NORMAL, on)
        now += 1000
        book.onUserMessage("I live in Hyderabad now.", "chat:2", MemoryScope.NORMAL, on)
        val homes = book.all().filter { it.slot == "home" }
        assertEquals(1, homes.size); assertTrue(homes.single().text.contains("Hyderabad"))
        book.onUserMessage("I prefer short answers.", "chat:2", MemoryScope.NORMAL, on)
        book.onUserMessage("I prefer short answers!", "chat:3", MemoryScope.NORMAL, on)
        assertEquals(1, book.all().count { it.text.contains("short answers") })
    }

    @Test fun savedMemoriesOffStillHonoursExplicitCommands() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        val off = MemorySettings(savedMemories = false)
        assertFalse(book.onUserMessage("My name is Sai.", "chat:1", MemoryScope.NORMAL, off).changed)
        assertTrue(book.onUserMessage("remember my locker code is in the red diary", "chat:1", MemoryScope.NORMAL, off).changed)
        assertEquals(1, book.all().size)
    }

    @Test fun forgetDeletesMatchingMemories() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        book.onUserMessage("remember my sister's birthday is on 4 May", "u", MemoryScope.NORMAL, on)
        book.onUserMessage("I prefer tea over coffee.", "u", MemoryScope.NORMAL, on)
        val u = book.onUserMessage("forget my sister's birthday", "u", MemoryScope.NORMAL, on)
        assertEquals(1, u.forgotten.size)
        assertEquals("Memory updated · forgot 1", u.chip())
        assertTrue(book.all().none { it.text.contains("birthday") })
        assertEquals(1, book.all().size)
        book.wipe(); assertTrue(book.all().isEmpty())
    }

    @Test fun confirmEditDeleteAndRelevance() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        book.onUserMessage("I usually go to the gym at 6 am. I'm vegetarian.", "chat:1", MemoryScope.NORMAL, on)
        val gym = book.all().first { it.text.contains("gym") }
        assertTrue(gym.inferred)
        book.confirm(gym.id)
        assertFalse(book.all().first { it.id == gym.id }.inferred)
        book.edit(gym.id, "Goes to the gym at 7 am on weekdays")
        assertEquals("Goes to the gym at 7 am on weekdays", book.all().first { it.id == gym.id }.text)
        val rel = book.relevant("suggest a dinner recipe, I am vegetarian", 3)
        assertEquals("Is vegetarian", rel.first().text)
        book.delete(gym.id)
        assertTrue(book.all().none { it.id == gym.id })
    }

    // ---------------------------------------------------------------- temporary chat
    @Test fun temporaryChatNeitherReadsNorWrites() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        val hist = ChatHistoryIndex(InMemoryHistoryRepo(), clock)
        assertFalse(MemoryScope.TEMPORARY.reads); assertFalse(MemoryScope.TEMPORARY.writes)
        assertFalse(book.onUserMessage("My name is Sai. remember I like cricket", "chat:9", MemoryScope.TEMPORARY, on).changed)
        assertFalse(book.onUserMessage("remember I like cricket", "chat:9", MemoryScope.TEMPORARY, on).changed)
        hist.addExchange(9, "Secret", "my exam roll number is 1601-23-748", "Noted.", MemoryScope.TEMPORARY)
        assertTrue(book.all().isEmpty())
        assertTrue(hist.search("exam roll number", 3).isEmpty())
    }

    // ---------------------------------------------------------------- cross-chat retrieval
    @Test fun referencesHistoryAcrossChatsAndExcludesCurrentChat() = runBlocking {
        val hist = ChatHistoryIndex(InMemoryHistoryRepo(), clock)
        hist.addExchange(1, "Hackathon plan", "We are building ONYX, an offline Android agent, for the MLH hackathon", "Great — start with the planner.", MemoryScope.NORMAL)
        hist.addExchange(2, "Dinner", "suggest a paneer recipe", "Try paneer butter masala.", MemoryScope.NORMAL)
        hist.addExchange(3, "Exam", "my DBMS exam is on Friday", "Good luck!", MemoryScope.NORMAL)
        hist.addSummary(1, "Hackathon plan", "User and team chose Kotlin + llama.cpp; demo due Sunday.", MemoryScope.NORMAL)
        val hits = hist.search("what did we decide for the hackathon demo?", 3, excludeChatId = 5)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.chatId == 1L })
        assertTrue(hits.any { it.summary })
        assertTrue(hist.search("hackathon", 3, excludeChatId = 1).none { it.chatId == 1L })
        // forgetting a phrase removes it from history retrieval too
        assertTrue(hist.forget("DBMS") >= 1)
        assertTrue(hist.search("DBMS exam", 3).isEmpty())
    }

    @Test fun memoryBlockRespectsTokenBudget() = runBlocking {
        val book = MemoryBook(InMemorySavedMemoryRepo(), clock)
        repeat(30) { i -> book.save("Fact number $i about the user and their very detailed preferences for things", MemoryKind.NOTE, false, 1.0, "u") }
        val hist = ChatHistoryIndex(InMemoryHistoryRepo(), clock)
        repeat(10) { i -> hist.addExchange(i.toLong(), "Chat $i", "detailed preferences number $i " + "x ".repeat(200), "ok", MemoryScope.NORMAL) }
        val block = MemoryContext.build(book.all(), hist.search("detailed preferences", 5), budgetTokens = 200)
        assertTrue(block.tokens in 1..200)
        assertTrue(Text.approxTokens(block.text) <= 220)
        assertTrue(block.memories.isNotEmpty())
        assertEquals("", MemoryContext.build(emptyList(), emptyList(), 200).text)
    }

    @Test fun bm25RanksRareTermsHigher() {
        val idx = Bm25Index<String>()
        idx.add("a", "the phone battery is low today")
        idx.add("b", "battery battery battery")
        idx.add("c", "Hyderabad biryani recipe")
        assertEquals("c", idx.search("biryani", 3).first().item)
        idx.removeIf { it == "c" }
        assertTrue(idx.search("biryani", 3).isEmpty())
    }

    @Test fun rollingSummaryOverflowAndFallback() {
        val turns = (1..20).map { RollingSummary.Turn(if (it % 2 == 1) "user" else "assistant", "Turn $it. " + "word ".repeat(40)) }
        val drop = RollingSummary.overflow(turns, windowTokens = 300)
        assertTrue(drop in 1 until turns.size)
        val s = RollingSummary.extractive("", turns.take(drop))
        assertTrue(s.contains("Turn 1."))
        assertTrue(s.length <= 900)
    }

    // ---------------------------------------------------------------- contacts
    @Test fun frequentContactsRankByFrequencyAndRecency() = runBlocking {
        val c = ContactRanking(InMemoryContactRepo(), clock)
        repeat(5) { c.record("Anu Sharma", "+91 98480 11111", "sms") }
        now += 40 * 86_400_000L // 40 days later
        c.record("Anil Kumar", "+91 90000 22222", "call")
        c.record("Anil Kumar", "+91 90000 22222", "call")
        val top = c.top(2)
        assertEquals(2, top.size)
        // Anil is fresher; Anu is older but more frequent — frecency picks Anil now
        assertEquals("Anil Kumar", top.first().name)
        assertEquals("Anil Kumar", c.resolve("an")!!.name)
        assertEquals("Anu Sharma", c.resolve("anu")!!.name)
        assertEquals(5, top.first { it.name == "Anu Sharma" }.uses)
        // same number, different spelling → same contact
        c.record("Anu", "098480 11111", "whatsapp")
        assertEquals(6, c.top(5).first { it.key == "9848011111" }.uses)
        c.wipe(); assertTrue(c.top(5).isEmpty())
    }

    // ---------------------------------------------------------------- routines
    @Test fun routineBecomesActiveAfterRepeatedSuccessAndMatches() = runBlocking {
        val rb = RoutineBook(InMemoryRoutineRepo(), clock)
        val steps = listOf(RoutineStep("device_info", "Read battery"), RoutineStep("flashlight", "Turn on torch"))
        val r1 = rb.recordSuccess("check my battery and turn on the torch", steps)!!
        assertFalse(r1.active)
        assertNull(rb.match("check my battery and turn on the torch please", setOf("device_info", "flashlight")))
        val r2 = rb.recordSuccess("please check my battery and then turn on the torch", steps)!!
        assertEquals(r1.id, r2.id); assertTrue(r2.active)
        assertNotNull(rb.match("check battery and turn the torch on", setOf("device_info", "flashlight")))
        assertNull(rb.match("check battery and turn the torch on", setOf("device_info"))) // tool gone
        assertNull(rb.match("what is the capital of France", setOf("device_info", "flashlight")))
        rb.rename(r2.id, "Night check"); assertEquals("Night check", rb.all().single().name)
        rb.setEnabled(r2.id, false); assertNull(rb.match("check battery and turn the torch on", setOf("device_info", "flashlight")))
        rb.delete(r2.id); assertTrue(rb.all().isEmpty())
        assertNull(rb.recordSuccess("one step", steps.take(1)))
    }

    /** Planner model is called for the first two runs; the third identical-kind request reuses the routine. */
    @Test fun orchestratorReusesRoutineAndSkipsPlanning() = runBlocking {
        val llm = object : AgentLlm {
            var plannerCalls = 0
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String = when {
                system.startsWith("You plan") -> { plannerCalls++; """{"steps":[{"tool":"device_info","goal":"Read battery","after":[]},{"tool":"recent_notifications","goal":"Read notifications","after":[]}]}""" }
                system.startsWith("Fill the arguments") -> """{"limit":5,"app":""}"""
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                else -> "Battery 81%, no new notifications."
            }
        }
        val h = Harness(llm)
        val routines = RoutineBook(InMemoryRoutineRepo(), clock)
        val deps = AgentDeps(h.deps.queue, h.tools, h.memory, h.ctx, h.deps.approvals, h.deps.network, h.deps.outbox, h.deps.audit, h.store, routines = routines)
        val requests = listOf("check my battery and show my notifications", "check the battery and show notifications please",
            "please check battery and show my notifications")
        val sources = requests.mapIndexed { i, r -> val b = Blackboard("r$i", r); Orchestrator(deps).run(r, b).plan!!.source }
        assertEquals(2, llm.plannerCalls)
        assertTrue(sources.toString(), sources[2].startsWith("routine:"))
        assertEquals(1, routines.all().single().uses)
    }

    @Test fun plannerGetsSavedMemoryWithinBudget() = runBlocking {
        val users = mutableListOf<String>()
        val llm = object : AgentLlm {
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
                if (system.startsWith("You plan")) { users += user; return """{"steps":[]}""" }
                return "ok"
            }
        }
        val h = Harness(llm)
        val mem = object : MemoryStore {
            override val enabled = true
            override suspend fun recall(query: String, limit: Int) = emptyList<MemoryHit>()
            override suspend fun profile(query: String, budgetTokens: Int) = "Known about the user (saved memories):\n- Prefers short answers"
        }
        val deps = AgentDeps(h.deps.queue, h.tools, mem, h.ctx, h.deps.approvals, h.deps.network, h.deps.outbox, h.deps.audit, h.store)
        val req = "check my battery and the notifications and also turn on the flashlight"
        PlannerAgent(deps).plan(req, Blackboard("p", req))
        assertTrue(users.single().contains("Prefers short answers"))
        assertTrue(users.single().contains("Tools:"))
    }
}
