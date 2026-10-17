package com.offlineagent.core.runtime

import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.StopReason
import com.offlineagent.core.runtime.chat.ChatPrompts
import com.offlineagent.core.runtime.chat.StreamingTurn
import com.offlineagent.core.runtime.chat.StreamingTurn.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4b: the streaming reducer behind the chat bubble, and the chat prompt budget. */
class Phase4bStreamingTest {
    private fun tok(t: String, n: Int, ms: Long = n * 250L) = GenerationEvent.Token(t, n, ms)

    @Test fun promptProgressThenThinkingThenAnswer() {
        val turn = StreamingTurn(startedAtMs = 1_000, initialStatus = "Loading model…")
        assertEquals(Phase.PREPARING, turn.current.phase)
        assertEquals("Loading model…", turn.current.status)
        var v = turn.on(GenerationEvent.PromptProgress(128, 600, 300), 2_000)
        assertEquals(Phase.READING_PROMPT, v.phase)
        assertEquals("Reading prompt… 128/600", v.status)
        assertEquals(128f / 600, v.promptFraction!!, 1e-4f)
        assertFalse(v.hasVisibleText)
        v = turn.on(GenerationEvent.PromptProcessed(900, 4_000), 5_000)
        assertEquals("Writing…", v.status)
        // Thinking streams live and the Thought section is open.
        v = turn.on(tok("<think>\nLet me", 1), 5_400)
        assertEquals(Phase.THINKING, v.phase); assertTrue(v.thinkingOpen); assertEquals("Let me", v.reasoning)
        assertEquals(4_400, v.firstTokenMillis) // request → first visible token
        v = turn.on(tok(" add.", 2), 5_700)
        assertEquals("Let me add.", v.reasoning); assertEquals(4_400, v.firstTokenMillis)
        // Answer starts: the section collapses, the answer streams.
        v = turn.on(tok("</think>\n\n", 3), 6_000)
        assertFalse(v.thinkingOpen)
        v = turn.on(tok("**5**", 4, 1_000), 6_300)
        assertEquals(Phase.ANSWERING, v.phase); assertEquals("**5**", v.answer); assertFalse(v.thinkingOpen)
        assertEquals(4.0, v.tokensPerSecond, 1e-9)
        v = turn.on(GenerationEvent.Completed(GenerationStats(900, 4, 4_000, 1_000, StopReason.END_OF_TURN, reusedTokens = 300)), 6_400)
        assertEquals(Phase.DONE, v.phase); assertEquals("**5**", v.answer); assertEquals("Let me add.", v.reasoning)
        assertEquals("4 tok · 4.0 tok/s · first token 4.4 s", StreamingTurn.footer(v))
        assertEquals("prompt 900 tok (300 cached)", StreamingTurn.promptDetail(v))
    }

    @Test fun plainAnswerWithoutThinkingNeverBlank() {
        val turn = StreamingTurn(0)
        val parts = listOf("Hel", "lo", " **wor", "ld**")
        var acc = ""
        parts.forEachIndexed { i, p ->
            acc += p
            val v = turn.on(tok(p, i + 1), 100L * (i + 1))
            assertEquals(acc, v.answer) // every token is visible immediately
            assertTrue(v.hasVisibleText)
            assertFalse(v.thinkingOpen)
        }
        assertEquals(100, turn.current.firstTokenMillis)
    }

    @Test fun throttleLetsPhaseChangesThroughAndCapsRate() {
        val turn = StreamingTurn(0)
        assertTrue(turn.shouldEmit(0))
        turn.on(tok("a", 1), 10)
        assertTrue("phase change is emitted at once", turn.shouldEmit(10))
        turn.on(tok("b", 2), 20)
        assertFalse(turn.shouldEmit(20))
        assertTrue(turn.shouldEmit(70))
        // At phone speed (one token every ~280 ms) every token is emitted.
        var t = 70L
        repeat(10) { i -> t += 280; turn.on(tok("x", 3 + i), t); assertTrue(turn.shouldEmit(t)) }
    }

    @Test fun cancelledFooter() {
        val turn = StreamingTurn(0)
        turn.on(tok("Hi", 1), 500)
        val v = turn.on(GenerationEvent.Completed(GenerationStats(10, 1, 100, 300, StopReason.CANCELLED)), 800)
        assertTrue(StreamingTurn.footer(v).endsWith("· stopped"))
    }

    @Test fun chatPromptUsesCheatSheetUnlessTheUserAsksForUi() {
        assertEquals(ChatPrompts.RichUi.CHEAT_SHEET, ChatPrompts.level("AUTO", "What is the capital of France?"))
        assertEquals(ChatPrompts.RichUi.FULL, ChatPrompts.level("AUTO", "Make a bar chart of my weekly expenses"))
        assertEquals(ChatPrompts.RichUi.FULL, ChatPrompts.level("AUTO", "build a form to collect RSVPs"))
        assertEquals(ChatPrompts.RichUi.OFF, ChatPrompts.level("OFF", "make a chart"))
        assertEquals(ChatPrompts.RichUi.COMPACT, ChatPrompts.level("COMPACT", "hello"))
        // The same system prompt for ordinary messages: its KV cache is reused turn after turn.
        assertEquals(ChatPrompts.system(ChatPrompts.level("AUTO", "hi")), ChatPrompts.system(ChatPrompts.level("AUTO", "tell me a joke")))
    }

    @Test fun chatPromptBudget() {
        val chat = ChatPrompts.system(ChatPrompts.RichUi.CHEAT_SHEET).length
        val full = ChatPrompts.system(ChatPrompts.RichUi.FULL).length
        val compact = ChatPrompts.system(ChatPrompts.RichUi.COMPACT).length
        // ≈ 3.5 chars/token: the default chat prompt stays under ~450 tokens; Phase 4 used the full/compact library.
        assertTrue("chat system prompt is $chat chars", chat <= 1600)
        assertTrue("full $full / compact $compact / chat $chat", chat * 3 < compact && compact < full)
    }

    @Test fun memoryPagesAreCappedForFastPrefill() = kotlinx.coroutines.runBlocking {
        val mem = object : com.offlineagent.core.runtime.context.LongTermMemory {
            override suspend fun recall(query: String, excludeTurnIds: Set<Long>, k: Int) =
                (1..k).map { com.offlineagent.core.runtime.context.MemoryPage("old chat", "word ".repeat(300), 0.9f, emptySet()) }
        }
        val cm = com.offlineagent.core.runtime.context.ContextManager({ it.length / 4 }, null, mem,
            com.offlineagent.core.runtime.context.ContextConfig(memoryPages = 2, memoryMaxTokens = 320))
        val r = cm.build("sys", emptyList(), null, "question", 8192, 1024)
        assertTrue("pages ${r.pages.size}", r.pages.size <= 1) // each page ≈ 375 tokens > 320 cap → at most what fits
        assertTrue(r.promptTokens < 600)
    }
}
