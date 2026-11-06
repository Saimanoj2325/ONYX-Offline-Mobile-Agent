package com.offlineagent.core.runtime.chat

/**
 * 0.6.2.3 typing-time prefill: which composer drafts are read ahead, and the footer line after Send.
 *
 * Only the plain Markdown chat route is read ahead: its prompt is built by one shared function for Send and for the
 * prefill, so the KV prefix matches. Agents, the tool agent, phone actions, automation, reports, skills, PDF exports and
 * real canvases build other prompts (or run tools) and are never touched while typing. Reading ahead only ever
 * evaluates a prompt: no token is generated and no tool can run.
 */
object ReadAheadRoute {
    fun isPlainChat(
        text: String,
        mode: ComposerMode,
        followUp: FollowUp.Kind,
        /** The Screen agent would take it (in-app request with the Screen agent on). */
        screenTask: Boolean,
        /** The ONYX agents would take it (Agents chip / task-like request in Chat mode). */
        agents: Boolean,
        /** Canvas mode, but the question is answered in chat ("Answered in chat"). */
        canvasInChat: Boolean,
    ): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.startsWith("/")) return false
        if (followUp != FollowUp.Kind.NONE || screenTask) return false
        return when (mode) {
            ComposerMode.CHAT -> !agents
            ComposerMode.CANVAS -> canvasInChat
            else -> false
        }
    }
}

object ReadAheadFooter {
    /**
     * "prefilled while typing: 512 tok · rest 9 tok in 0.4 s" (+ "· reasoned ahead: 180 tok"); empty when typing did
     * nothing for this message.
     */
    fun line(prefilledWhileTyping: Int, restTokens: Int, restMillis: Long, aheadTokens: Int = 0): String {
        if (prefilledWhileTyping <= 0 && aheadTokens <= 0) return ""
        return buildString {
            append("prefilled while typing: ").append(prefilledWhileTyping).append(" tok")
            append(" · rest ").append(restTokens.coerceAtLeast(0)).append(" tok in ").append(StreamingTurn.seconds(restMillis))
            if (aheadTokens > 0) append(" · reasoned ahead: ").append(aheadTokens).append(" tok")
        }
    }
}
