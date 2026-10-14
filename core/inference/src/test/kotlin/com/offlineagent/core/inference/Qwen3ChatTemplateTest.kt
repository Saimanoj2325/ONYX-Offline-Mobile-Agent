package com.offlineagent.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3ChatTemplateTest {
    private val sys = ChatMessage(Role.SYSTEM, "You are helpful.")

    @Test
    fun singleTurnThinkingOff() {
        val p = Qwen3ChatTemplate.render(listOf(sys, ChatMessage(Role.USER, "Hi")), thinking = false)
        assertEquals(
            "<|im_start|>system\nYou are helpful.<|im_end|>\n" +
                "<|im_start|>user\nHi /no_think<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            p,
        )
    }

    @Test
    fun singleTurnThinkingOn() {
        val p = Qwen3ChatTemplate.render(listOf(ChatMessage(Role.USER, "Hi")), thinking = true)
        assertEquals("<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n", p)
    }

    @Test
    fun multiTurnDropsPastReasoningAndOnlyLastUserGetsNoThink() {
        val p = Qwen3ChatTemplate.render(
            listOf(
                ChatMessage(Role.USER, "a"),
                ChatMessage(Role.ASSISTANT, "<think>\nhmm\n</think>\n\nanswer A"),
                ChatMessage(Role.USER, "b"),
            ),
            thinking = false,
        )
        assertEquals(
            "<|im_start|>user\na<|im_end|>\n" +
                "<|im_start|>assistant\nanswer A<|im_end|>\n" +
                "<|im_start|>user\nb /no_think<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n",
            p,
        )
    }

    @Test
    fun noDoubleNoThink() {
        val p = Qwen3ChatTemplate.render(listOf(ChatMessage(Role.USER, "x /no_think")), thinking = false)
        assertEquals(1, Regex("/no_think").findAll(p).count())
    }

    @Test(expected = IllegalArgumentException::class)
    fun lastMessageMustBeUser() {
        Qwen3ChatTemplate.render(listOf(ChatMessage(Role.ASSISTANT, "x")), thinking = false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun systemMustBeFirst() {
        Qwen3ChatTemplate.render(listOf(ChatMessage(Role.USER, "x"), sys, ChatMessage(Role.USER, "y")), false)
    }

    @Test
    fun detectsChatMl() {
        assertTrue(Qwen3ChatTemplate.matches("{% for m in messages %}<|im_start|>{{m.role}}<|im_end|>"))
        // SmolVLM uses <|im_start|> but <end_of_utterance>: not ChatML
        assertFalse(Qwen3ChatTemplate.matches("<|im_start|>{{ role }}: <end_of_utterance>"))
        assertFalse(Qwen3ChatTemplate.matches("[INST]"))
        assertFalse(Qwen3ChatTemplate.matches(null))
    }
}
