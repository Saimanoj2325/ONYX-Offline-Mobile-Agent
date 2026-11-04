package com.offlineagent.core.screen

import com.offlineagent.core.agents.Risk
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.ScreenSnapshot

/**
 * One capture of the foreground window. [secure] = the window has FLAG_SECURE (screenshots come back black); the
 * tree may still be readable, but ONYX treats such screens as "hand over to the user" (Open-AutoGLM's rule).
 */
data class ScreenFrame(val snapshot: ScreenSnapshot, val secure: Boolean = false, val capturedAt: Long = 0L) {
    val packageName: String get() = snapshot.packageName
}

/** Node-level operations done through the accessibility API (no coordinates, no pointer when avoidable). */
enum class NodeOp { CLICK, LONG_CLICK, SET_TEXT, PASTE, FOCUS, SCROLL_FORWARD, SCROLL_BACKWARD, IME_ENTER }

enum class GlobalOp { BACK, HOME }

/**
 * Platform side (the app implements it on OfflineAccessibilityService + PackageManager + ClipboardManager).
 * Everything here is mechanical: no policy, no approvals. Those live in [ScreenSession].
 */
interface ScreenPort {
    /** Current foreground window, or null when the accessibility service is off. */
    suspend fun capture(): ScreenFrame?
    /** Performs [op] on [node] (resolved by its path in the live tree). False when the node is gone or refused. */
    suspend fun perform(node: ScreenNode, op: NodeOp, text: String = ""): Boolean
    suspend fun global(op: GlobalOp): Boolean
    /** Package name for an app label or package ("WhatsApp" → com.whatsapp); null when not installed/launchable. */
    suspend fun resolveApp(app: String): String?
    /** Starts [pkg] with its launch intent (app switching). */
    suspend fun launch(pkg: String): Boolean
    suspend fun setClipboard(text: String): Boolean
    /** ACTION_SEND text to [pkg] (share-sheet channel; the target app shows its own composer). */
    suspend fun share(text: String, pkg: String): Boolean
    /** Waits until the window stops changing (TYPE_WINDOW_CONTENT_CHANGED idle) or [timeoutMs]. */
    suspend fun awaitIdle(timeoutMs: Long)
    /** Human label of [pkg] for approval sheets. */
    fun appLabel(pkg: String): String = pkg
    /** Phase 6.1: opens a Settings page by its entry intent (first action the phone resolves). False when none works. */
    suspend fun openSettings(actions: List<String>): Boolean = false
    /** Package of the phone's Settings app (what a Settings page shows up as). */
    fun settingsPackage(): String = "com.android.settings"
}

/** UI side: floating overlay (step preview, per-step Allow/Deny, take-over hand-off). */
interface ScreenHost {
    fun preview(text: String)
    /** Per-step confirmation of an irreversible step (send/submit/pay/delete). Suspends until answered. */
    suspend fun confirm(action: String, reason: String): Boolean
    /** "Take over": the user does this part (login, captcha, secure screen) and taps Continue (true) or Stop (false). */
    suspend fun takeOver(reason: String): Boolean
}

/** Audit sink (the app maps it to onyx audit_log + the automation log). Never receives raw screen text. */
fun interface ScreenAudit { suspend fun record(action: String, detail: String, risk: Risk) }

/** Kill switch: the overlay Stop button or the Settings switch. Checked before every action. */
fun interface KillSwitch {
    fun engaged(): Boolean
    /** Why it is engaged, in words the user can act on (which switch to turn back on). */
    fun reason(): String = "screen automation was stopped by you (kill switch). Turn it back on to continue."
}

/** Optional model access for screen_extract (fallback) and screen_do (grounded early exit). */
interface ScreenLlm {
    suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String
    /**
     * Streams text; [onPartial] gets the accumulated text and returns true to STOP generating right now
     * (grounded early exit). Returns the text produced up to that point.
     */
    suspend fun stream(system: String, user: String, maxTokens: Int, onPartial: (String) -> Boolean): String
}

/** Settings the user controls. [enabled] is the "Autonomous screen agent (sideload only)" switch. */
data class ScreenConfig(
    val enabled: Boolean = true,
    /** Steps covered by one per-app scope approval before ONYX asks again. */
    val stepsPerScope: Int = 30,
    val settleMs: Long = 600,
    val waitPollMs: Long = 250,
    val defaultWaitMs: Long = 4_000,
    val maxSnapshotChars: Int = 2_400,
    /** Idempotent retry window: the same act re-sent within this time is re-verified, not re-performed. */
    val retryWindowMs: Long = 60_000,
)
