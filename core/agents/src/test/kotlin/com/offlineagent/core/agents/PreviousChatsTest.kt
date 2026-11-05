package com.offlineagent.core.agents

import com.offlineagent.core.agents.memory.ChatHistoryIndex
import com.offlineagent.core.agents.memory.InMemoryHistoryRepo
import com.offlineagent.core.agents.memory.MemoryScope
import com.offlineagent.core.agents.memory.MemorySettings
import com.offlineagent.core.agents.memory.PreviousChats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** 0.6.2.2: "summary of our previous chats" gets the real Threads summaries, not invented "Chat 1: [text]". */
class PreviousChatsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val day = 86_400_000L
    private var now = 1_793_000_000_000L // a fixed instant
    private val index = ChatHistoryIndex(InMemoryHistoryRepo()) { now }

    private fun seed() = runBlocking {
        now -= 3 * day
        index.addExchange(1, "Hyderabad trip", "plan a 2 day trip to Hyderabad", "Day 1: Charminar, Day 2: Golconda Fort", MemoryScope.NORMAL)
        index.addSummary(1, "Hyderabad trip", "Planned a 2-day Hyderabad trip: Charminar, Golconda Fort, Biryani at Paradise.", MemoryScope.NORMAL)
        now += 2 * day // yesterday
        index.addExchange(2, "Kotlin coroutines", "explain flows vs channels", "Flows are cold streams; channels are hot.", MemoryScope.NORMAL)
        index.addExchange(3, "Secret plans", "temporary stuff", "never indexed", MemoryScope.TEMPORARY) // not indexed
        now += day // today
        index.addExchange(9, "Current chat", "can you give me 2-3 lines of summary of our previous chats?", "", MemoryScope.NORMAL)
    }

    @Test fun recognisesQuestionsAboutEarlierChats() {
        listOf("can you give me 2-3 lines of summary of our previous chats?", "summarise our past conversations",
            "what did we talk about yesterday", "recap of our chats").forEach { assertTrue(it, PreviousChats.isAbout(it)) }
        listOf("what is a chat bot", "make a table of planets", "summarise this article").forEach { assertFalse(it, PreviousChats.isAbout(it)) }
    }

    @Test fun previousChatsQuestionGetsThreadSummariesInTheContext() = runBlocking {
        seed()
        val b = PreviousChats.contextFor("can you give me 2-3 lines of summary of our previous chats?", index, chatId = 9,
            scope = MemoryScope.NORMAL, settings = MemorySettings(), budgetTokens = 400, now = now, zone = zone)
        assertEquals(PreviousChats.Status.OK, b.status)
        assertTrue(b.text, b.text.contains("Planned a 2-day Hyderabad trip")) // the Threads summary
        assertTrue(b.text, b.text.contains("Flows are cold streams"))          // a chat without a summary: its newest exchange
        assertFalse(b.text, b.text.contains("Current chat"))                   // not the chat being asked in
        assertFalse(b.text, b.text.contains("never indexed"))                  // temporary chats are never in memory
        assertFalse(b.text.contains("[text]"))
        // Newest first.
        assertTrue(b.text.indexOf("Kotlin coroutines") < b.text.indexOf("Hyderabad trip"))
    }

    @Test fun yesterdayOnlyUsesYesterdaysChats() = runBlocking {
        seed()
        val b = PreviousChats.contextFor("what did we talk about yesterday?", index, 9, MemoryScope.NORMAL, MemorySettings(), 400, now, zone)
        assertTrue(b.text, b.text.contains("Kotlin coroutines"))
        assertFalse(b.text, b.text.contains("Hyderabad"))
        assertTrue(b.text.contains("yesterday"))
    }

    @Test fun temporaryChatAndSwitchesAreRespected() = runBlocking {
        seed()
        val temp = PreviousChats.contextFor("summarise our previous chats", index, 9, MemoryScope.TEMPORARY, MemorySettings(), 400, now, zone)
        assertEquals(PreviousChats.Status.TEMPORARY, temp.status)
        assertFalse(temp.text.contains("Hyderabad"))
        val off = PreviousChats.contextFor("summarise our previous chats", index, 9, MemoryScope.NORMAL, MemorySettings(referenceChatHistory = false), 400, now, zone)
        assertEquals(PreviousChats.Status.OFF, off.status)
        assertFalse(off.text.contains("Hyderabad"))
        val empty = PreviousChats.contextFor("summarise our previous chats", ChatHistoryIndex(InMemoryHistoryRepo()), 9, MemoryScope.NORMAL, MemorySettings(), 400, now, zone)
        assertEquals(PreviousChats.Status.EMPTY, empty.status)
        assertTrue(empty.text.contains("do not invent"))
    }

    @Test fun blockStaysWithinBudget() = runBlocking {
        repeat(30) { i -> index.addSummary(100L + i, "Chat $i", "A long summary ".repeat(40), MemoryScope.NORMAL) }
        val b = PreviousChats.contextFor("summarise our previous chats", index, null, MemoryScope.NORMAL, MemorySettings(), 200, now, zone)
        assertTrue(b.text.length / 4 <= 230)
    }
}
