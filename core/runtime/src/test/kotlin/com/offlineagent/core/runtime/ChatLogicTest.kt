package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.chat.ChatLogic
import com.offlineagent.core.runtime.chat.ComposerMode
import com.offlineagent.core.runtime.chat.ConversationRef
import com.offlineagent.core.runtime.chat.MsgRef
import com.offlineagent.core.runtime.chat.TurnEvent
import com.offlineagent.core.runtime.chat.TurnReducer
import com.offlineagent.core.runtime.chat.TurnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLogicTest {
    private val msgs = listOf(
        MsgRef(1, "user", "hi"), MsgRef(2, "assistant", "hello"),
        MsgRef(3, "user", "table please"), MsgRef(4, "assistant", "```openui-lang\nroot = Stack([])\n```"),
        MsgRef(5, "user", "thanks"), MsgRef(6, "assistant", "welcome"),
    )

    @Test fun searchMatchesAllWordsIgnoringCaseAndAccents() {
        val all = listOf(ConversationRef(1, "Trip to Goa", 10, "beaches"), ConversationRef(2, "Café menu ideas", 30), ConversationRef(3, "Exam plan", 20, "data structures"))
        assertEquals(listOf(2L), ChatLogic.filterConversations(all, "cafe").map { it.id })
        assertEquals(listOf(3L), ChatLogic.filterConversations(all, "DATA plan").map { it.id })
        assertEquals(listOf(2L, 3L, 1L), ChatLogic.filterConversations(all, "  ").map { it.id })
        assertTrue(ChatLogic.filterConversations(all, "goa xyz").isEmpty())
    }

    @Test fun editResendDeletesFromEditedMessageOn() {
        assertEquals(listOf(3L, 4L, 5L, 6L), ChatLogic.editResendDeletes(msgs, 3))
        assertEquals(emptyList<Long>(), ChatLogic.editResendDeletes(msgs, 4)) // assistant: not editable
        assertEquals(emptyList<Long>(), ChatLogic.editResendDeletes(msgs, 99))
    }

    @Test fun regenerateFindsPrecedingUserTurn() {
        val (user, del) = ChatLogic.regenerateTarget(msgs, 4)!!
        assertEquals(3L, user.id)
        assertEquals(listOf(4L, 5L, 6L), del)
        assertNull(ChatLogic.regenerateTarget(msgs, 1))
        assertNull(ChatLogic.regenerateTarget(listOf(MsgRef(9, "assistant", "x")), 9))
    }

    @Test fun autoScrollOnlyNearBottomAndNotWhileDragging() {
        assertTrue(ChatLogic.shouldAutoScroll(9, 10, false))
        assertTrue(ChatLogic.shouldAutoScroll(8, 10, false))
        assertFalse(ChatLogic.shouldAutoScroll(3, 10, false))
        assertFalse(ChatLogic.shouldAutoScroll(9, 10, true))
        assertFalse(ChatLogic.shouldAutoScroll(0, 0, false))
    }

    @Test fun groupsByDay() {
        val now = System.currentTimeMillis()
        assertEquals("Today", ChatLogic.group(now, now))
        assertEquals("Older", ChatLogic.group(now - 30L * 86_400_000, now))
        assertEquals("Previous 7 days", ChatLogic.group(now - 3L * 86_400_000, now))
    }

    @Test fun greetingAndSuggestions() {
        assertTrue(ChatLogic.greeting(9).startsWith("Good morning"))
        assertTrue(ChatLogic.greeting(20, "Sai").contains("Sai"))
        assertEquals(4, ChatLogic.SUGGESTIONS.size)
        assertEquals(ComposerMode.CANVAS, ComposerMode.parse("CANVAS"))
        assertEquals(ComposerMode.CHAT, ComposerMode.parse("bogus"))
    }

    @Test fun turnReducerTransitions() {
        var s: TurnState = TurnState.Idle
        s = TurnReducer.reduce(s, TurnEvent.Phase("Loading model…"))
        assertTrue(TurnReducer.isBusy(s))
        s = TurnReducer.reduce(s, TurnEvent.Delta("Hel", "", 1, 5.0))
        s = TurnReducer.reduce(s, TurnEvent.Phase("ignored while streaming"))
        assertEquals("Hel", (s as TurnState.Streaming).text)
        s = TurnReducer.reduce(s, TurnEvent.Delta("Hello", "", 2, 5.0))
        s = TurnReducer.reduce(s, TurnEvent.Finished(stopped = true))
        assertEquals(TurnState.Done("Hello", true), s)
        assertFalse(TurnReducer.isBusy(s))
        assertEquals(TurnState.Error("x"), TurnReducer.reduce(s, TurnEvent.Failed("x")))
        assertEquals(TurnState.Idle, TurnReducer.reduce(s, TurnEvent.Reset))
    }
}
