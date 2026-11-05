package com.offlineagent.core.screen

import com.offlineagent.core.runtime.phone.MacroText
import com.offlineagent.core.runtime.phone.ScreenElement
import com.offlineagent.core.runtime.phone.ScreenFingerprint
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.SecretFields
import com.offlineagent.core.runtime.phone.TreeIndex
import kotlin.math.abs
import kotlin.math.min

// Phase 6.2 screen-agent reliability. Ideas adapted from Mika (Apache-2.0, see NOTICE): RefStabilizer (stable
// element refs across re-reads), OperationGuards (no-effect / revisit / cycle guards), ScreenDiagnosis (needs-human
// screens). Re-written for ONYX's ScreenView / ScreenSession; no Mika code is copied verbatim.

// ---------------------------------------------------------------------------------------------- hand-off detector

/** Why the user must do this part. */
enum class HandoffKind(val words: String) { PASSWORD("a password"), OTP("a one-time code"), PAYMENT("a payment / UPI PIN"), CAPTCHA("a CAPTCHA / human check"), SECURE("a protected (secure) screen") }

data class Handoff(val kind: HandoffKind, val cause: String) {
    val reason: String get() = "this screen needs ${kind.words} — please do this part yourself, then tap Resume"
}

/**
 * Detects screens that must be handed to the user: password and one-time-code fields, UPI / payment PIN screens,
 * CAPTCHAs and secure windows, with English, Hindi and Telugu keywords. Conservative on purpose: "Payment methods"
 * in a shopping app is not a payment screen; "Enter UPI PIN", a CVV field or a "Pay ₹499" button is.
 */
object HandoffDetector {
    val OTP = listOf("otp", "one-time password", "one time password", "verification code", "enter the code", "enter code", "6-digit code", "sms code",
        "ओटीपी", "सत्यापन कोड", "वन टाइम पासवर्ड", "कोड दर्ज करें", "ఓటీపీ", "ధృవీకరణ కోడ్", "వన్ టైమ్ పాస్‌వర్డ్", "కోడ్ నమోదు")
    val PAYMENT = listOf("upi pin", "enter upi pin", "enter pin", "enter your pin", "mpin", "confirm payment", "proceed to pay", "payment password", "cvv",
        "यूपीआई पिन", "पिन दर्ज करें", "भुगतान की पुष्टि", "भुगतान करें", "యూపీఐ పిన్", "పిన్ నమోదు", "చెల్లింపును నిర్ధారించండి", "చెల్లించండి")
    val CAPTCHA = listOf("captcha", "i'm not a robot", "i am not a robot", "verify you are human", "verify you're human", "are you a robot", "hcaptcha",
        "कैप्चा", "मैं रोबोट नहीं हूँ", "मैं रोबोट नहीं हूं", "క్యాప్చా", "నేను రోబోట్ కాదు")
    val PASSWORD_WORDS = listOf("password", "passcode", "पासवर्ड", "పాస్‌వర్డ్", "పాస్వర్డ్")
    private val PAY_BUTTON = Regex("(?i)^(pay|पे|చెల్లించు)\\s*(₹|rs\\.?|inr)\\s*[0-9]")

    fun check(view: ScreenView): Handoff? {
        if (view.frame.secure) return Handoff(HandoffKind.SECURE, "secure window")
        val els = view.elements
        val text = view.snapshot.allText
        CAPTCHA.firstOrNull { it in text }?.let { return Handoff(HandoffKind.CAPTCHA, "text: $it") }
        val inputs = els.filter { it.node.editable }
        val pinField = inputs.firstOrNull { val h = (it.node.hint + " " + it.node.viewId + " " + it.node.contentDescription).lowercase(); PAYMENT.any { w -> w in h } }
        if (pinField != null) return Handoff(HandoffKind.PAYMENT, "payment field")
        PAYMENT.firstOrNull { it in text }?.let { w -> if (inputs.isNotEmpty() || w.contains("pin")) return Handoff(HandoffKind.PAYMENT, "text: $w") }
        els.firstOrNull { (it.node.clickable) && PAY_BUTTON.containsMatchIn(it.node.label.trim()) }?.let { return Handoff(HandoffKind.PAYMENT, "pay button") }
        inputs.firstOrNull { Irreversible.isOtpField(it.node) || OTP.any { w -> w in (it.node.hint + " " + it.node.contentDescription).lowercase() } }
            ?.let { return Handoff(HandoffKind.OTP, "one-time code field") }
        if (inputs.isNotEmpty() && OTP.any { it in text } && inputs.all { it.node.text.isBlank() }) return Handoff(HandoffKind.OTP, "one-time code screen")
        inputs.firstOrNull { SecretFields.isPassword(it.node) || PASSWORD_WORDS.any { w -> w in it.node.hint.lowercase() } }
            ?.let { if (it.node.text.isBlank()) return Handoff(HandoffKind.PASSWORD, "empty password field") }
        return null
    }

    /** Sensitive = a password / code field is on screen or the window is secure: overlays must not cover it. */
    fun sensitive(view: ScreenView): Pair<Boolean, Float> {
        if (view.frame.secure) return true to 0.5f
        val f = view.elements.firstOrNull { it.node.editable && (SecretFields.isPassword(it.node) || Irreversible.isOtpField(it.node)) } ?: return false to -1f
        val h = view.snapshot.root.bounds.height.takeIf { it > 0 } ?: return true to 0.5f
        return true to ((f.node.bounds.centerY - view.snapshot.root.bounds.top).toFloat() / h).coerceIn(0f, 1f)
    }
}

// ---------------------------------------------------------------------------------------------- stable refs

/**
 * RefStabilizer idea: a ref the model saw on an earlier read still points at the same element on the new read when
 * that element is clearly the same one: same role + label + view id, unique on both screens, same app, not a reused
 * list row (or the list did not scroll), and it barely moved. Irreversible targets (Send, Pay…) are never carried
 * over — they always need a fresh look.
 */
object StableRefs {
    private fun key(e: ScreenElement) = e.node.role() + "\u0000" + MacroText.norm(if (e.node.editable) e.node.hint else e.node.label) + "\u0000" + e.node.viewId

    fun identifiable(e: ScreenElement) = e.node.label.isNotBlank() || e.node.viewId.isNotBlank() || e.node.hint.isNotBlank()

    fun relocate(old: ScreenView, oldEl: ScreenElement, live: ScreenView, policy: ScreenSafetyPolicy?): ScreenElement? {
        if (old.pkg != live.pkg || !identifiable(oldEl)) return null
        if ((oldEl.node.clickable || oldEl.node.longClickable) && (policy?.isIrreversible(oldEl.node) ?: Irreversible.matches(oldEl.node))) return null
        val k = key(oldEl)
        if (old.elements.count { key(it) == k } != 1) return null
        val cand = live.elements.filter { key(it) == k }
        val c = cand.singleOrNull() ?: return null
        val short = min(live.snapshot.root.bounds.width, live.snapshot.root.bounds.height).takeIf { it > 0 } ?: 1000
        val shift = abs(c.node.bounds.centerX - oldEl.node.bounds.centerX) + abs(c.node.bounds.centerY - oldEl.node.bounds.centerY)
        if (shift > short * 0.15) return null
        val oi = TreeIndex(old.snapshot); val li = TreeIndex(live.snapshot)
        val inList = oi.listAncestor(oldEl.node.path) != null || li.listAncestor(c.node.path) != null
        if (inList && shift > short * 0.02) return null
        return c
    }
}

// ---------------------------------------------------------------------------------------------- loop / stuck guards

enum class GuardLevel { WARN, STOP }
data class GuardVerdict(val code: String, val level: GuardLevel, val message: String)

/**
 * OperationGuards idea: stop a task loop that is going nowhere. Counts actions with no visible effect, returns to the
 * same screen without anything new, two-screen cycles (A→B→A→B) and the same action repeated on the same screen.
 * A WARN goes into the next model prompt as a nudge; a STOP ends the task with a readable reason.
 */
class TaskGuards(private val noEffectWarn: Int = 2, private val noEffectStop: Int = 4, private val revisitStop: Int = 4, private val repeatStop: Int = 3) {
    private var noEffect = 0
    private val visits = HashMap<String, Int>()
    private val trail = ArrayDeque<String>()
    private val repeats = HashMap<String, Int>()
    val fired = ArrayList<GuardVerdict>()

    fun state(fp: ScreenFingerprint?): String = fp?.let { it.packageName + "|" + it.activity + "|" + (it.ids.sorted() + it.texts.sorted()).joinToString(",").hashCode() } ?: "?"

    /** Before an action on [screen]: the same action on the same screen too often → STOP. */
    fun beforeAction(screen: String, actionKey: String): GuardVerdict? {
        val n = repeats.merge("$screen|$actionKey", 1, Int::plus) ?: 1
        return if (n >= repeatStop) note(GuardVerdict("REPEAT", GuardLevel.STOP, "the same step was tried $n times on the same screen without progress")) else null
    }

    /** After an action: [changed] = the screen visibly changed; [screen] = the state now. */
    fun afterAction(changed: Boolean, screen: String): GuardVerdict? {
        if (!changed) {
            noEffect++
            if (noEffect >= noEffectStop) return note(GuardVerdict("NO_EFFECT", GuardLevel.STOP, "$noEffect actions in a row changed nothing on screen"))
            if (noEffect >= noEffectWarn) return note(GuardVerdict("NO_EFFECT", GuardLevel.WARN, "your last $noEffect actions changed nothing — pick a different element, scroll, or go back"))
            return null
        }
        noEffect = 0
        if (trail.lastOrNull() != screen) { trail.addLast(screen); while (trail.size > 6) trail.removeFirst() }
        val v = visits.merge(screen, 1, Int::plus) ?: 1
        if (trail.size >= 5) {
            val t = trail.toList().takeLast(5)
            if (t[0] == t[2] && t[2] == t[4] && t[1] == t[3] && t[0] != t[1]) return note(GuardVerdict("CYCLE", GuardLevel.STOP, "going back and forth between the same two screens"))
        }
        if (v >= revisitStop) return note(GuardVerdict("REVISIT", GuardLevel.STOP, "came back to the same screen $v times without getting closer"))
        if (v == revisitStop - 1) return note(GuardVerdict("REVISIT", GuardLevel.WARN, "this screen was already visited ${v - 1} times — try another way"))
        return null
    }

    private fun note(v: GuardVerdict) = v.also { fired += it }
}

// ---------------------------------------------------------------------------------------------- compact description

/**
 * A smaller screen description for a 1.7B model: one line per useful element, nested duplicates removed (a row and
 * its own title count once), list rows merged into "row 'Mom' · 'hi there'" with ≤ 2 texts, decorative unlabelled
 * images dropped, long texts cut, "(more below — scroll down)" when the list can scroll. Lines keep the @eN refs.
 */
object CompactScreen {
    fun render(v: ScreenView, policy: ScreenSafetyPolicy?, maxChars: Int = 1800, card: String? = null): String {
        val idx = TreeIndex(v.snapshot)
        val rows = HashMap<List<Int>, MutableList<ScreenElement>>()
        val out = ArrayList<String>()
        val consumed = HashSet<Int>()
        // Group leaf texts under their clickable row when the row sits in a list.
        for (e in v.elements) {
            if (!idx.hasPaths) break
            val row = idx.rowOf(e.node.path) ?: continue
            rows.getOrPut(row.path) { ArrayList() } += e
        }
        var scrollable = false
        for (e in v.elements) {
            if (e.id in consumed) continue
            val n = e.node
            if (n.scrollable) { scrollable = true; continue }
            if (!n.interactive && n.label.isBlank()) continue
            if (n.role() == "image" && n.label.isBlank()) continue
            val row = if (idx.hasPaths) idx.rowOf(n.path) else null
            if (row != null && (n.clickable || row.clickable)) {
                val members = rows[row.path].orEmpty()
                val head = members.firstOrNull { it.node.clickable || it.node.longClickable } ?: e
                val texts = members.map { it.node.label }.filter { it.isNotBlank() && !MacroText.volatile(MacroText.norm(it)) }.distinct().take(2)
                members.forEach { consumed += it.id }
                out += "${v.ref(head)} row " + texts.joinToString(" · ") { "\"" + ScreenData.label(it, 40) + "\"" }
                continue
            }
            out += v.line(e, policy).let { if (it.length > 110) it.take(107) + "…\"" else it }
        }
        val body = buildString {
            for (l in out) { if (length + l.length + 1 > maxChars) { append("…(more; scroll or drill down)\n"); break }; append(l).append('\n') }
            if (scrollable) append("(list can scroll — more may be below)\n")
        }
        val head = "Screen ${v.id} · ${v.snapshot.appLabel} (${v.pkg})" + (if (v.snapshot.activity.isNotBlank()) " · " + v.snapshot.activity.substringAfterLast('.') else "") + "\n" +
            (if (!card.isNullOrBlank()) "App notes: " + card.take(300).replace('\n', ' ') + "\n" else "")
        return ScreenData.wrap(v.pkg, head + body.trimEnd())
    }
}

// ---------------------------------------------------------------------------------------------- deterministic fast steps

/**
 * Fewer model calls: simple clauses of the goal that name their target ("tap 'Archived'", "open chat Mom",
 * "type 'hi' into Message", "search for pizza") are done directly when the target is found exactly once on screen.
 * Anything that commits (send/pay/submit…) is never a fast step; ambiguous or missing targets fall back to the model.
 */
object FastSteps {
    data class Clause(val action: String, val target: String = "", val text: String = "", val raw: String = "")

    private val TAP = Regex("""(?i)^(?:please\s+)?(?:tap|click|press|select|choose|open)\s+(?:on\s+)?(?:the\s+)?(?:(?:chat|conversation|contact|tab|item|button|option)\s+(?:with\s+|named\s+|called\s+)?)?["“']?(.+?)["”']?(?:\s+(?:chat|tab|button|option))?$""")
    private val TYPE_INTO = Regex("""(?i)^(?:type|write|enter)\s+["“'](.+?)["”']\s+(?:in|into|in the|into the)\s+(?:the\s+)?["“']?(.+?)["”']?(?:\s+(?:field|box))?$""")
    private val TYPE_Q = Regex("""(?i)^(?:type|write|enter)\s+["“'](.+?)["”']$""")
    private val SEARCH = Regex("""(?i)^search\s+(?:for\s+)?["“']?(.+?)["”']?$""")
    private val SCROLL = Regex("""(?i)^scroll\s+(down|up)$""")
    private val SPLIT = Regex("""(?i)\s*(?:,\s*then\s+|\s+and\s+then\s+|\s+then\s+|\s+and\s+|;\s*|,\s*)\s*""")

    fun parse(goal: String, appLabel: String = ""): List<Clause> = parseAll(goal, appLabel).first

    /** Clauses + whether EVERY part of the goal was understood (then the task can finish without asking the model). */
    fun parseAll(goal: String, appLabel: String = ""): Pair<List<Clause>, Boolean> {
        val g = goal.replace(Regex("\\(.*?\\)"), " ").trim().trimEnd('.', '!')
        val parts = SPLIT.split(g).map { it.trim() }.filter { it.isNotBlank() }
        val out = ArrayList<Clause>()
        var understood = 0
        for (p in parts) {
            if (Irreversible.text(p.substringBefore(' '))) break // "send …", "pay …": never a fast step, and stop parsing there
            val c = TYPE_INTO.matchEntire(p)?.let { Clause("type", it.groupValues[2], it.groupValues[1], p) }
                ?: TYPE_Q.matchEntire(p)?.let { Clause("type", "", it.groupValues[1], p) }
                ?: SEARCH.matchEntire(p)?.let { Clause("search", "", it.groupValues[1], p) }
                ?: SCROLL.matchEntire(p)?.let { Clause("scroll_" + it.groupValues[1].lowercase(), raw = p) }
                ?: TAP.matchEntire(p)?.let { m -> Clause("tap", m.groupValues[1].trim(), raw = p) }
                ?: break // first clause we cannot read: the model takes over from here
            understood++
            if (c.action == "tap" && (c.target.isBlank() || (appLabel.isNotBlank() && MacroText.norm(c.target).let { t -> t == MacroText.norm(appLabel) || t == MacroText.norm(appLabel) + " app" }))) continue
            out += c
        }
        return out to (understood == parts.size && out.isNotEmpty())
    }
}

// ---------------------------------------------------------------------------------------------- debug report

data class TaskStep(val action: String, val fast: Boolean, val effect: String, val ms: Long)

/**
 * "Copy debug report" for a screen_task run: goal, app, each step (fast or model), its effect, guards, hand-offs,
 * model calls, scrolls, timing, final package / window. Typed text appears only as a character count.
 */
class TaskReport(val goal: String, val app: String, val startedAt: Long) {
    val steps = ArrayList<TaskStep>()
    val guards = ArrayList<String>()
    val handoffs = ArrayList<String>()
    val notes = ArrayList<String>()
    var modelCalls = 0
    var fastSteps = 0
    var scrolls = 0
    var ok = false
    var reason = ""
    var durationMs = 0L
    var finalScreen = ""
    var stoppedBefore: String? = null

    fun finish(ok: Boolean, reason: String, now: Long, screen: String? = null) {
        this.ok = ok; this.reason = reason; durationMs = now - startedAt; if (screen != null) finalScreen = screen
    }

    fun render(appVersion: String = ""): String = buildString {
        appendLine("ONYX screen agent debug report" + if (appVersion.isNotBlank()) " ($appVersion)" else "")
        appendLine("Goal: ${goal.take(200)}" + if (app.isNotBlank()) " · app: $app" else "")
        appendLine("Result: ${if (ok) "done" else "NOT done"} · ${steps.size} actions (${fastSteps} fast, ${modelCalls} model calls) · $scrolls scrolls · ${durationMs} ms")
        if (reason.isNotBlank()) appendLine("Reason: ${reason.take(400)}")
        stoppedBefore?.let { appendLine("Stopped before: $it (needs your OK)") }
        if (finalScreen.isNotBlank()) appendLine("Final screen: $finalScreen")
        steps.forEachIndexed { i, s -> appendLine(" ${i + 1}. ${s.action} — ${if (s.fast) "fast step" else "model"} · effect ${s.effect} · ${s.ms} ms") }
        if (guards.isNotEmpty()) appendLine("Guards: " + guards.joinToString(" | "))
        if (handoffs.isNotEmpty()) appendLine("Hand-offs: " + handoffs.joinToString(" | "))
        notes.forEach { appendLine("note: $it") }
    }.trimEnd()
}
