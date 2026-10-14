package com.offlineagent.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {
    @Test
    fun utf8SplitAcrossTokens() {
        val bytes = "నమస్తే 👋!".toByteArray(Charsets.UTF_8)
        val d = Utf8StreamDecoder()
        val out = StringBuilder()
        // feed one byte at a time: worst case for multi-byte characters
        for (b in bytes) out.append(d.feed(byteArrayOf(b)))
        out.append(d.flush())
        assertEquals("నమస్తే 👋!", out.toString())
    }

    @Test
    fun utf8HoldsIncompleteEmoji() {
        val emoji = "👋".toByteArray(Charsets.UTF_8) // 4 bytes
        val d = Utf8StreamDecoder()
        assertEquals("", d.feed(emoji.copyOfRange(0, 3)))
        assertEquals("👋", d.feed(emoji.copyOfRange(3, 4)))
    }

    @Test
    fun truncatedCharacterAtEndIsDroppedNotReplaced() {
        val telugu = "తె".toByteArray(Charsets.UTF_8) // 6 bytes, 2 chars
        val d = Utf8StreamDecoder()
        val out = d.feed(telugu.copyOfRange(0, 4)) + d.flush() // stream ends mid-character
        assertEquals("త", out)
        assertFalse(out.contains('\uFFFD'))
    }

    @Test
    fun thinkSplitterStreaming() {
        val s = ThinkSplitter()
        assertEquals(ThinkSplitter.Parts("", "", false), s.append("<thi"))
        var p = s.append("nk>\nLet me")
        assertTrue(p.thinkingInProgress)
        assertEquals("Let me", p.reasoning)
        p = s.append(" think.</think>\n\nHello")
        assertFalse(p.thinkingInProgress)
        assertEquals("Let me think.", p.reasoning)
        assertEquals("Hello", p.answer)
    }

    @Test
    fun emptyThinkBlockAndPlainText() {
        assertEquals("Hi", ThinkSplitter.split("<think>\n\n</think>\n\nHi").answer)
        assertEquals("Hi there", ThinkSplitter.split("Hi there").answer)
        assertEquals("ans", ThinkSplitter.stripThinking("<think>x</think>ans"))
    }

    @Test
    fun threadPolicy() {
        // 1 prime + 3 big + 4 little (e.g. Snapdragon 7 Gen x) -> 4
        assertEquals(4, ThreadPolicy.performanceCores(listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_600_000, 2_600_000, 2_600_000, 2_900_000)))
        // 2 big + 6 little (e.g. Dimensity 7050) -> 2
        assertEquals(2, ThreadPolicy.performanceCores(listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_600_000, 2_600_000)))
        // 8 identical cores -> 4
        assertEquals(4, ThreadPolicy.performanceCores(List(8) { 2_000_000L }))
        // unreadable sysfs -> fallback 4
        assertEquals(4, ThreadPolicy.performanceCores(emptyList()))
        assertEquals(4, ThreadPolicy.performanceCores(List(8) { 0L }))
        // tiny device
        assertEquals(2, ThreadPolicy.performanceCores(listOf(1_500_000L, 1_500_000)))
    }

    @Test
    fun ggufHeader() {
        val ok = byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte(), 3, 0, 0, 0)
        assertTrue(GgufFile.hasValidHeader(ok))
        assertFalse(GgufFile.hasValidHeader("PK\u0003\u0004abcd".toByteArray()))
        assertFalse(GgufFile.hasValidHeader(ok.copyOf(5)))
        assertTrue(GgufFile.looksLikeGgufName("Qwen3-0.6B-Q8_0.GGUF"))
        assertFalse(GgufFile.looksLikeGgufName("model.bin"))
    }

    @Test
    fun loadConfigValidation() {
        assertEquals(2048, LoadConfig("/x").contextLength)
        try { LoadConfig("/x", threads = 0); throw AssertionError() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun promptFitterDropsOldestTurnsKeepsSystemAndLast() {
        val sys = ChatMessage(Role.SYSTEM, "s")
        val msgs = listOf(sys) + (1..10).flatMap { listOf(ChatMessage(Role.USER, "u$it" + "x".repeat(300)), ChatMessage(Role.ASSISTANT, "a$it" + "y".repeat(300))) } + ChatMessage(Role.USER, "last")
        val fitted = PromptFitter.fitByCharacters(msgs, contextLength = 512, maxNewTokens = 256)
        assertEquals(Role.SYSTEM, fitted.first().role)
        assertEquals("last", fitted.last().content)
        assertTrue(fitted.size < msgs.size)
        assertTrue(fitted.drop(1).first().role == Role.USER)
        assertEquals(null, PromptFitter.dropOldestTurn(listOf(sys, ChatMessage(Role.USER, "only"))))
    }
}
