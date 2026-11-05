package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.chat.CanvasRouting
import com.offlineagent.core.runtime.chat.LoopGuard
import com.offlineagent.core.runtime.chat.PrefillMeter
import com.offlineagent.core.runtime.chat.PromptBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.2.2: Canvas routing, loop guard and the device-aware prompt budget. */
class CanvasGuardsTest {
    // ------------------------------------------------------------------ routing
    @Test fun canvasModeQuestionsRouteToChat() {
        listOf(
            "can you give me 2-3 lines of summary of our previous chats?",
            "what is quantum computing",
            "explain how a transformer works",
            "Who won the 2011 world cup?",
            "summarise our previous chats",
            "what did we talk about yesterday",
            "what is a pie chart?",
            "explain the steps to make tea",
            "hi",
        ).forEach { assertTrue(it, CanvasRouting.answerInChat(it)) }
    }

    @Test fun designRequestsStayInCanvas() {
        listOf(
            "make a table of the planets and their moons",
            "create a bar chart of my monthly expenses",
            "a weekly study plan for my exams",
            "build a signup form with name and email",
            "dashboard for my fitness goals",
            "can you make a comparison table of iPhone 15 vs Pixel 8?",
            "weekly gym routine for beginners",
        ).forEach { assertFalse(it, CanvasRouting.answerInChat(it)) }
    }

    @Test fun editsOfTheCurrentCanvasStayInCanvas() {
        assertFalse(CanvasRouting.answerInChat("add a row for Friday", hasCanvas = true))
        assertFalse(CanvasRouting.answerInChat("make it a pie chart", hasCanvas = true))
        assertTrue(CanvasRouting.answerInChat("what does this table mean?", hasCanvas = true))
    }

    // ------------------------------------------------------------------ loop guard
    private val loopBlock = "root = TextContent(\"Previous Chats Summary\", \"large-heavy\")\n" +
        "prevChats = [\"Chat 1: [text]\", \"Chat 2: [text]\", \"Chat 3: [text]\"]\n" +
        "root = Stack([root, prevChats])\n"

    /** Feeds [text] in small token-like pieces; returns how many characters went in before the guard fired. */
    private fun run(g: LoopGuard, text: String, piece: Int = 5): Int {
        var i = 0
        while (i < text.length) {
            val p = text.substring(i, minOf(text.length, i + piece))
            i += p.length
            if (g.feed(p) != null) return i
        }
        return -1
    }

    @Test fun loopGuardStopsARepeatingDslStream() {
        val g = LoopGuard(LoopGuard.Mode.LINES)
        val stream = loopBlock.repeat(20) // what the phone streamed until the token cap
        val at = run(g, stream)
        assertTrue("guard never fired", at > 0)
        // It stops on the first repeated statement: within the second block, long before the cap.
        assertTrue("stopped at $at", at <= loopBlock.length + 70)
        assertNotNull(g.reason)
    }

    @Test fun loopGuardStopsWhenTheSameNameIsAssignedAgainAndAgain() {
        val g = LoopGuard(LoopGuard.Mode.LINES)
        val stream = (1..10).joinToString("") { "root = Stack([root, item$it])\n" }
        assertTrue(run(g, stream) > 0)
        assertTrue(g.reason!!.contains("root"))
    }

    @Test fun loopGuardLetsAValidProgramThrough() {
        val g = LoopGuard(LoopGuard.Mode.LINES)
        val prog = """
            root = Stack([title, tbl, chart])
            title = TextContent("Q4 Revenue", "large-heavy")
            tbl = Table([Col("Month", "string"), Col("Revenue", "number")], [["Oct", 120], ["Nov", 150], ["Dec", 180]])
            chart = BarChart(["Oct", "Nov", "Dec"], [s1])
            s1 = Series("Revenue", [120, 150, 180])
        """.trimIndent() + "\n"
        assertEquals(-1, run(g, prog))
    }

    @Test fun textLoopGuardStopsRepeatedSentencesButNotTables() {
        val g = LoopGuard(LoopGuard.Mode.TEXT)
        val looping = "Here is the summary. " + "I hope this helps you with your studies. ".repeat(10)
        assertTrue(run(g, looping, 3) > 0)

        val t = LoopGuard(LoopGuard.Mode.TEXT)
        val table = "| Day | Plan |\n|---|---|\n" + (1..30).joinToString("") { "| Day $it | Revise chapter $it |\n" }
        assertEquals(-1, run(t, table, 4))
        val prose = LoopGuard(LoopGuard.Mode.TEXT)
        assertEquals(-1, run(prose, "Hyderabad is the capital of Telangana. It is known for biryani, the Charminar and its IT industry. ".repeat(1), 4))
    }

    // ------------------------------------------------------------------ prompt budget
    @Test fun promptOrderIsStaticFirst() {
        val (sys, user) = PromptBudget.staticFirst("STATIC SYSTEM", listOf("memory: likes tea", "", "session: earlier turns"), "make a table")
        assertEquals("STATIC SYSTEM", sys)
        assertTrue(user.startsWith("memory: likes tea"))
        assertTrue(user.endsWith("Latest request:\nmake a table"))
        assertEquals("make a table", PromptBudget.staticFirst("S", listOf("", " "), "make a table").second)
    }

    @Test fun budgetIsDeviceAwareAndTrimsHistoryBeforeMemory() {
        // Entry phone, nothing measured: ~12 tok/s × 20 s.
        assertEquals(240, PromptBudget.dynamicTokens(PromptBudget.Tier.ENTRY))
        // A measured 30 tok/s → 600 tokens of new prompt.
        assertEquals(600, PromptBudget.dynamicTokens(PromptBudget.Tier.ENTRY, measuredTps = 30.0))
        val split = PromptBudget.split(dynamicBudget = 600, requestTokens = 40, memoryWant = 400, historyWant = 2000)
        assertTrue(split.memoryTokens <= (560 * 0.4).toInt())
        assertEquals(560, split.memoryTokens + split.historyTokens)
        // Short history leaves its room to memory.
        val s2 = PromptBudget.split(600, 40, memoryWant = 400, historyWant = 50)
        assertEquals(50, s2.historyTokens); assertEquals(400, s2.memoryTokens)

        val history = (1..50).joinToString("\n") { "user: message number $it about exams" }
        val trimmed = PromptBudget.keepNewest(history, 60)
        assertTrue(trimmed.endsWith("message number 50 about exams"))
        assertFalse(trimmed.contains("message number 1 about"))
        assertTrue((trimmed.length + 3) / 4 <= 60)
        assertTrue(PromptBudget.keepFirst("best\nsecond\nthird", 3).startsWith("best"))
        assertEquals("", PromptBudget.keepNewest(history, 0))
    }

    @Test fun prefillMeterLearnsThePhoneSpeed() {
        val m = PrefillMeter()
        assertNull(m.tokensPerSecond)
        m.record(10, 1000) // too small to count
        assertNull(m.tokensPerSecond)
        m.record(1200, 60_000)
        assertEquals(20.0, m.tokensPerSecond!!, 0.01)
    }
}
