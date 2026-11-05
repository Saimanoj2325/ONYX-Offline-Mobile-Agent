package com.offlineagent.core.agents

/**
 * Phase 6.2: where an approval / question / hand-off is shown. Sai had to switch back to ONYX for every approval
 * while the screen agent, a macro or AppBridge was working in another app. Now the prompt is drawn ON TOP of the
 * current app (the accessibility service's TYPE_ACCESSIBILITY_OVERLAY, no SYSTEM_ALERT_WINDOW needed); without the
 * accessibility service it falls back to a SYSTEM_ALERT_WINDOW overlay (if the user granted "display over other
 * apps"), then to a heads-up notification with Allow / Deny actions. The in-app ApprovalSheet is used only when ONYX
 * itself is in front. Pure Kotlin so the routing is JVM-tested.
 */
enum class PromptKind { APPROVE, ASK, HANDOFF }

enum class PromptAnswer { ALLOW_ONCE, ALLOW_TASK, DENY, STOP, RESUME, TIMEOUT }

enum class PromptSurface { IN_APP_SHEET, ACCESSIBILITY_OVERLAY, SYSTEM_OVERLAY, NOTIFICATION }

data class UserPrompt(
    val id: String,
    val kind: PromptKind,
    /** "Screen agent", "Macro", "AppBridge", "ONYX". */
    val source: String,
    /** The action in plain words ("Tap 'Send' in WhatsApp"). */
    val action: String,
    val appLabel: String = "",
    val risk: Risk = Risk.MEDIUM,
    val reason: String = "",
    /** "Allow for this task" is offered (the scope system allows it for this request). */
    val allowTaskScope: Boolean = false,
    /** Send / pay / submit / delete: asks every time, never covered by a task approval. */
    val irreversible: Boolean = false,
    /** Set by the router: draw a small pill docked away from the sensitive field instead of a card. */
    val compact: Boolean = false,
    /** Where the focused sensitive field is (fraction of screen height, 0 top … 1 bottom); the pill docks on the other side. */
    val sensitiveFieldY: Float = -1f,
)

data class PromptReply(val answer: PromptAnswer, val text: String? = null) {
    val allowed: Boolean get() = answer == PromptAnswer.ALLOW_ONCE || answer == PromptAnswer.ALLOW_TASK || answer == PromptAnswer.RESUME
    val forTask: Boolean get() = answer == PromptAnswer.ALLOW_TASK
    val stop: Boolean get() = answer == PromptAnswer.STOP
}

/** What the phone can show right now. */
data class SurfaceState(
    val onyxInForeground: Boolean,
    val accessibilityOverlay: Boolean,
    val systemOverlay: Boolean,
    val notifications: Boolean,
    /** The current screen is secure (FLAG_SECURE) or a password / one-time-code field is focused. */
    val sensitiveScreen: Boolean = false,
    val sensitiveFieldY: Float = -1f,
)

object PromptRouting {
    /** Irreversible steps never get "Allow for this task". */
    fun sanitize(p: UserPrompt): UserPrompt = if (p.irreversible || p.risk >= Risk.CRITICAL) p.copy(allowTaskScope = false) else p

    /**
     * ONYX in front → its own sheet. A secure / password screen is never covered: an approval goes to the heads-up
     * notification, a hand-off ("Your turn") becomes a compact pill docked away from the field. Otherwise the
     * accessibility overlay, then the system overlay, then the notification.
     */
    fun order(p: UserPrompt, s: SurfaceState): List<PromptSurface> {
        if (s.onyxInForeground) return listOf(PromptSurface.IN_APP_SHEET)
        val out = ArrayList<PromptSurface>()
        if (s.sensitiveScreen && p.kind != PromptKind.HANDOFF) {
            if (s.notifications) out += PromptSurface.NOTIFICATION
        } else {
            if (s.accessibilityOverlay) out += PromptSurface.ACCESSIBILITY_OVERLAY
            if (s.systemOverlay) out += PromptSurface.SYSTEM_OVERLAY
            if (s.notifications) out += PromptSurface.NOTIFICATION
        }
        out += PromptSurface.IN_APP_SHEET // last resort: ONYX's own sheet (plus its "needs your OK" notification)
        return out.distinct()
    }

    fun prepare(p: UserPrompt, s: SurfaceState): UserPrompt {
        val q = sanitize(p)
        return if (s.sensitiveScreen && q.kind == PromptKind.HANDOFF && !s.onyxInForeground) q.copy(compact = true, sensitiveFieldY = s.sensitiveFieldY) else q
    }
}

/** One surface; returns null when it could not be shown (overlay refused, notifications blocked) so the next is tried. */
fun interface PromptPresenter { suspend fun show(p: UserPrompt): PromptReply? }

/** Routes each prompt to the first surface that can show it. [last] says where the latest one went (tests, audit). */
class PromptRouter(
    private val state: () -> SurfaceState,
    private val presenters: Map<PromptSurface, PromptPresenter>,
) {
    @Volatile var last: PromptSurface? = null
        private set

    suspend fun ask(p: UserPrompt): PromptReply {
        val s = state()
        val q = PromptRouting.prepare(p, s)
        for (surface in PromptRouting.order(q, s)) {
            val pr = presenters[surface] ?: continue
            val r = runCatching { pr.show(q) }.getOrNull() ?: continue
            last = surface
            return r
        }
        last = null
        return PromptReply(PromptAnswer.DENY)
    }

    /** Approve / deny convenience for gates that only need a yes/no (and the task-scope flag). */
    suspend fun approve(p: UserPrompt): PromptReply = ask(p.copy(kind = PromptKind.APPROVE))
}
