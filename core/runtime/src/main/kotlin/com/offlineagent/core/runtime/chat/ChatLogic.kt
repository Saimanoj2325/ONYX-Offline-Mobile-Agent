package com.offlineagent.core.runtime.chat

/**
 * Pure chat-screen logic (Phase 4 single-chat UI), kept free of Android so it is JVM-tested:
 * composer modes, the drawer's conversation search, edit-and-resend / regenerate targets, streaming state
 * transitions, auto-scroll, greeting + suggestion chips.
 */
enum class ComposerMode(val label: String, val hint: String) {
    CHAT("Chat", "Message"),
    AGENT("Agent", "Ask the agent to use tools…"),
    CANVAS("Canvas", "Describe a table, chart, plan or form…"),
    PDF_REPORT("PDF report", "What should the report be about?"),
    PHONE("Phone actions", "Set an alarm, draft a message, open an app…"),
    AUTOMATION("Automation", "Describe a task to do on the screen…");

    companion object { fun parse(s: String?) = entries.firstOrNull { it.name == s } ?: CHAT }
}

/** Minimal message view the logic needs (id, role, text, creation order). */
data class MsgRef(val id: Long, val role: String, val text: String, val kind: String = "markdown")

data class ConversationRef(val id: Long, val title: String, val updatedAt: Long, val preview: String = "")

object ChatLogic {
    /** Drawer search: every word must appear in the title or preview (case/diacritic-insensitive); newest first. */
    fun filterConversations(all: List<ConversationRef>, query: String): List<ConversationRef> {
        val words = norm(query).split(' ').filter { it.isNotBlank() }
        return all.filter { c -> val hay = norm(c.title + " " + c.preview); words.all { hay.contains(it) } }.sortedByDescending { it.updatedAt }
    }

    /** Groups for the drawer: Today / Yesterday / Previous 7 days / Older. */
    fun group(updatedAt: Long, now: Long): String {
        val day = 86_400_000L
        val startToday = now - Math.floorMod(now + tzOffset(now), day)
        return when {
            updatedAt >= startToday -> "Today"
            updatedAt >= startToday - day -> "Yesterday"
            updatedAt >= startToday - 7 * day -> "Previous 7 days"
            else -> "Older"
        }
    }

    private fun tzOffset(t: Long) = java.util.TimeZone.getDefault().getOffset(t).toLong()

    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /**
     * Edit-and-resend of user message [editedId]: that message and everything after it are removed; the edited text
     * is then sent as a new turn. Returns the ids to delete (empty if [editedId] is not a user message).
     */
    fun editResendDeletes(messages: List<MsgRef>, editedId: Long): List<Long> {
        val i = messages.indexOfFirst { it.id == editedId }
        if (i < 0 || messages[i].role != "user") return emptyList()
        return messages.drop(i).map { it.id }
    }

    /**
     * Regenerate assistant answer [assistantId]: returns the user message it answers and the ids to delete (the
     * answer and anything after it), or null when there is no preceding user message.
     */
    fun regenerateTarget(messages: List<MsgRef>, assistantId: Long): Pair<MsgRef, List<Long>>? {
        val i = messages.indexOfFirst { it.id == assistantId }
        if (i <= 0 || messages[i].role == "user") return null
        val user = messages.subList(0, i).lastOrNull { it.role == "user" } ?: return null
        return user to messages.drop(i).map { it.id }
    }

    /** Auto-scroll only while the user is already at (or near) the bottom; never yank them up/down while reading. */
    fun shouldAutoScroll(lastVisibleIndex: Int, itemCount: Int, userDragging: Boolean): Boolean =
        !userDragging && itemCount > 0 && lastVisibleIndex >= itemCount - 2

    fun greeting(hour: Int, name: String? = null): String {
        val part = when (hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Hello" }
        return if (name.isNullOrBlank()) "$part! How can I help?" else "$part, $name! How can I help?"
    }

    data class Suggestion(val title: String, val prompt: String, val mode: ComposerMode = ComposerMode.CHAT)

    val SUGGESTIONS = listOf(
        Suggestion("Compare two phones", "Compare the Pixel 9a and Galaxy A56 in a table with a chart of battery life"),
        Suggestion("Make a study plan", "Make a 7-day study plan for my data structures exam with daily steps"),
        Suggestion("Explain a concept", "Explain how attention works in transformers, simply, with an example"),
        Suggestion("Plan a trip", "Help me plan a 3-day trip to Goa. Ask me what you need with a form."),
    )
}

/** Streaming state machine for one assistant turn. */
sealed interface TurnState {
    data object Idle : TurnState
    data class Preparing(val phase: String) : TurnState
    data class Streaming(val text: String, val reasoning: String, val tokens: Int, val tokensPerSecond: Double) : TurnState
    data class Done(val text: String, val stopped: Boolean) : TurnState
    data class Error(val message: String) : TurnState
}

sealed interface TurnEvent {
    data class Phase(val phase: String) : TurnEvent
    data class Delta(val answer: String, val reasoning: String, val tokens: Int, val tokensPerSecond: Double) : TurnEvent
    data class Finished(val stopped: Boolean) : TurnEvent
    data class Failed(val message: String) : TurnEvent
    data object Reset : TurnEvent
}

object TurnReducer {
    fun reduce(s: TurnState, e: TurnEvent): TurnState = when (e) {
        is TurnEvent.Reset -> TurnState.Idle
        is TurnEvent.Phase -> when (s) { is TurnState.Streaming -> s; else -> TurnState.Preparing(e.phase) }
        is TurnEvent.Delta -> TurnState.Streaming(e.answer, e.reasoning, e.tokens, e.tokensPerSecond)
        is TurnEvent.Finished -> TurnState.Done((s as? TurnState.Streaming)?.text ?: "", e.stopped)
        is TurnEvent.Failed -> TurnState.Error(e.message)
    }

    fun isBusy(s: TurnState) = s is TurnState.Preparing || s is TurnState.Streaming
}
