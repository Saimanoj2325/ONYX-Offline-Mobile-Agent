package com.offlineagent.core.runtime.phone

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Safety rules for anything that acts in other apps (screen agent, macros, autofill). */
class AutomationPolicy(
    /** Packages the user explicitly allowed even though they match the payment/banking rules. */
    private val userAllowed: () -> Set<String> = { emptySet() },
    /** Extra packages the user blocked. */
    private val userBlocked: () -> Set<String> = { emptySet() },
) {
    sealed interface Verdict {
        data object Allow : Verdict
        data class NeedsApproval(val reason: String) : Verdict
        data class Stop(val reason: String) : Verdict
    }

    fun isBlockedApp(pkg: String): Boolean {
        if (pkg in userAllowed()) return false
        if (pkg in userBlocked() || pkg in BLOCKED_PACKAGES) return true
        val p = pkg.lowercase()
        return BLOCKED_PACKAGE_WORDS.any { w -> p.split('.', '_').any { it == w || (w.length >= 4 && it.contains(w)) } }
    }

    fun hasCaptcha(s: ScreenSnapshot): Boolean = CAPTCHA_WORDS.any { it in s.allText }

    fun isOtpField(n: ScreenNode): Boolean {
        val t = (n.hint + " " + n.text + " " + n.contentDescription + " " + n.viewId + " " + n.autofillHints.joinToString(" ")).lowercase()
        return OTP_WORDS.any { it in t } || "smsotpcode" in t
    }

    /** True when tapping [n] would likely commit something (submit, pay, send, delete, book…). */
    fun isIrreversibleTarget(n: ScreenNode): Boolean {
        val t = (n.label + " " + n.viewId.substringAfterLast('/')).lowercase()
        if (t.isBlank()) return false
        return IRREVERSIBLE.any { w -> Regex("(^|[^a-z])" + Regex.escape(w) + "([^a-z]|$)").containsMatchIn(t) }
    }

    /** Check before performing [a] on [s]. [userProvided] = strings the user typed to the agent (OTP rule). */
    fun check(a: ScreenAction, s: ScreenSnapshot, userProvided: Set<String>): Verdict {
        if (isBlockedApp(s.packageName)) return Verdict.Stop("${s.appLabel} is a banking/payment app; automation is blocked there (Settings → Phone actions to change)")
        if (hasCaptcha(s)) return Verdict.Stop("this screen shows a CAPTCHA / human check — please solve it yourself, then ask me to continue")
        return when (a) {
            is ScreenAction.Tap -> {
                val n = s.element(a.element)?.node ?: return Verdict.Stop("element [${a.element}] is not on screen")
                if (!n.enabled) Verdict.Stop("that control is disabled") else if (isIrreversibleTarget(n)) Verdict.NeedsApproval("\"${n.label.take(40)}\" may submit, send, pay or delete something") else Verdict.Allow
            }
            is ScreenAction.Type -> {
                val n = s.element(a.element)?.node ?: return Verdict.Stop("element [${a.element}] is not on screen")
                when {
                    !n.editable -> Verdict.Stop("element [${a.element}] is not a text field")
                    n.password -> Verdict.Stop("I never type passwords — please enter it yourself")
                    isOtpField(n) && a.text !in userProvided -> Verdict.Stop("this is a one-time code field; tell me the code yourself if you want me to type it")
                    a.submit -> Verdict.NeedsApproval("typing and pressing Enter may submit the form")
                    else -> Verdict.Allow
                }
            }
            is ScreenAction.LongPress -> {
                val n = s.element(a.element)?.node ?: return Verdict.Stop("element [${a.element}] is not on screen")
                if (!n.enabled) Verdict.Stop("that control is disabled") else if (isIrreversibleTarget(n)) Verdict.NeedsApproval("long-pressing \"${n.label.take(40)}\" may change or delete something") else Verdict.Allow
            }
            is ScreenAction.Enter -> Verdict.NeedsApproval("pressing Enter may send or submit what was typed")
            is ScreenAction.OpenApp -> Verdict.Allow
            else -> Verdict.Allow
        }
    }

    companion object {
        /** Common Indian + global payment / banking apps. Blocked by default. */
        val BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
            "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.hdfcbank.payzapp",
            "com.axis.mobile", "com.msf.kbank.mobile", "com.mobikwik_new", "com.freecharge.android", "com.amazon.mShop.android.shopping.upi",
            "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.whatsapp.payments",
            "com.dreamplug.androidapp", "com.bharatpe.app", "com.myairtelapp", "com.idfcfirstbank.optimus", "com.kotak811mobilebankingapp.instantsavingsupiscanandpayrecharge",
        )
        val BLOCKED_PACKAGE_WORDS = listOf("bank", "upi", "wallet", "paytm", "phonepe", "payzapp", "netbanking", "banking", "trading", "demat")
        val CAPTCHA_WORDS = listOf("captcha", "i'm not a robot", "i am not a robot", "verify you are human", "verify you're human", "are you a robot", "hcaptcha", "security check: select")
        val OTP_WORDS = listOf("otp", "one-time", "one time password", "verification code", "security code", "2fa", "authenticator code")
        val IRREVERSIBLE = listOf(
            "submit", "send", "pay", "pay now", "place order", "buy", "buy now", "purchase", "checkout", "check out", "confirm", "delete",
            "remove", "transfer", "book", "book now", "reserve", "post", "publish", "apply", "apply now", "sign up", "register", "subscribe",
            "proceed to pay", "complete", "finish", "agree", "accept", "unsubscribe", "cancel order", "cancel booking", "upload", "share",
        )
    }
}

/** Platform side of screen automation (the accessibility service). */
interface ScreenDriver {
    suspend fun snapshot(): ScreenSnapshot?
    /** Performs [a]; false if it could not (element gone, gesture rejected). */
    suspend fun perform(a: ScreenAction, s: ScreenSnapshot): Boolean
    /** Optional: a short description of a screenshot from the vision model (null when unavailable/disabled). */
    suspend fun describeScreenshot(goal: String): String? = null
}

/** UI side: overlay with step preview, pause/stop, approvals and questions. */
interface AutomationHost {
    /** Show the upcoming step in the floating overlay. */
    fun preview(step: Int, text: String)
    /** Approval for an irreversible step: suspends until the user answers. */
    suspend fun approve(step: Int, action: String, reason: String): Boolean
    /** Ask the user a question (e.g. an OTP or a missing detail); null = user declined. */
    suspend fun ask(question: String): String?
    fun finished(summary: String)
    /** Phase 6.2 macro "Test run": outline the matched element on the overlay before acting (no-op by default). */
    suspend fun highlight(step: Int, bounds: Bounds, text: String) {}
}

data class AutomationLogEntry(val time: Long, val step: Int, val app: String, val action: String, val outcome: String)

fun interface AutomationLog { fun add(e: AutomationLogEntry) }

enum class AutomationState { RUNNING, PAUSED, STOPPED }

data class ScreenAgentLimits(val maxSteps: Int = 25, val maxFailures: Int = 3, val settleMillis: Long = 700, val actionTokens: Int = 200)

/**
 * The on-device screen agent: observe (view tree [+ screenshot description]) → let the model choose ONE action
 * (grammar-constrained JSON whose element ids are restricted to what is on screen) → safety policy → preview in the
 * overlay → approval if irreversible → perform → log. Pausable and stoppable at any step (kill switch).
 */
class ScreenAgent(
    private val llm: AgentLlm,
    private val driver: ScreenDriver,
    private val host: AutomationHost,
    private val policy: AutomationPolicy,
    private val log: AutomationLog,
    private val limits: ScreenAgentLimits = ScreenAgentLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { isLenient = true }
    private val _state = MutableStateFlow(AutomationState.RUNNING)
    val state: StateFlow<AutomationState> = _state.asStateFlow()
    fun pause() { if (_state.value == AutomationState.RUNNING) _state.value = AutomationState.PAUSED }
    fun resume() { if (_state.value == AutomationState.PAUSED) _state.value = AutomationState.RUNNING }
    fun stop() { _state.value = AutomationState.STOPPED }

    private val userProvided = HashSet<String>()

    fun actionSchema(s: ScreenSnapshot): Schema {
        val ids = s.elements.map { it.id.toDouble() }
        val maxId = (ids.maxOrNull() ?: 1.0)
        val el = Schema.Num("element id", 1.0, maxId, integer = true)
        val th = Schema.Str("short reason", maxLength = 160)
        fun alt(vararg p: Pair<String, Schema>) = Schema.Obj(listOf("thought" to th) + p.toList())
        return Schema.OneOf(listOf(
            alt("action" to Schema.Const("tap"), "element" to el),
            alt("action" to Schema.Const("type"), "element" to el, "text" to Schema.Str(maxLength = 300), "submit" to Schema.Bool()),
            alt("action" to Schema.Const("scroll"), "direction" to Schema.Str(enum = listOf("down", "up"))),
            alt("action" to Schema.Const("back")),
            alt("action" to Schema.Const("open_app"), "app" to Schema.Str(maxLength = 80, minLength = 1)),
            alt("action" to Schema.Const("ask_user"), "question" to Schema.Str(maxLength = 200, minLength = 3)),
            alt("action" to Schema.Const("done"), "summary" to Schema.Str(maxLength = 400)),
        ))
    }

    fun parse(raw: String): Pair<String, ScreenAction>? = runCatching {
        val o = json.parseToJsonElement(raw.substring(raw.indexOf('{'))) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull ?: ""
        fun i(k: String) = s(k).toDoubleOrNull()?.toInt() ?: -1
        val a: ScreenAction = when (s("action")) {
            "tap" -> ScreenAction.Tap(i("element"))
            "type" -> ScreenAction.Type(i("element"), s("text"), (o["submit"] as? JsonPrimitive)?.booleanOrNull ?: false)
            "scroll" -> ScreenAction.Scroll(s("direction").ifBlank { "down" })
            "back" -> ScreenAction.Back
            "open_app" -> ScreenAction.OpenApp(s("app"))
            "ask_user" -> ScreenAction.AskUser(s("question"))
            "done" -> ScreenAction.Done(s("summary"))
            else -> return null
        }
        s("thought") to a
    }.getOrNull()

    private fun system(): String =
        "You operate an Android phone for the user by choosing ONE action at a time on the current screen. " +
            "Elements are listed as [id] kind \"label\". Use only ids from the list. Prefer filling fields before tapping buttons. " +
            "Never invent personal details: if something needed is not in the task or notes, use ask_user. " +
            "Never try to solve CAPTCHAs or guess codes. When the goal is reached (or impossible), use done with a short summary."

    suspend fun run(goal: String, notes: String = ""): String {
        val history = ArrayList<String>()
        var failures = 0
        var step = 0
        try {
            while (step < limits.maxSteps) {
                currentCoroutineContext().ensureActive()
                if (_state.value == AutomationState.PAUSED) host.preview(step, "Paused — tap Resume to continue")
                _state.first { it != AutomationState.PAUSED }
                if (_state.value == AutomationState.STOPPED) return finish("Stopped by you after $step steps.", step)
                val snap = driver.snapshot() ?: return finish("I can't read the screen (is the accessibility service on?).", step)
                if (policy.isBlockedApp(snap.packageName)) return finish("Stopped: ${snap.appLabel} is a banking/payment app and automation is blocked there.", step)
                if (policy.hasCaptcha(snap)) {
                    log(step, snap, "detected CAPTCHA", "handed to user")
                    val ans = host.ask("This screen has a CAPTCHA / human check. Please solve it yourself, then type 'continue' (or 'stop').")
                    if (ans == null || !ans.trim().equals("continue", true)) return finish("Stopped at a CAPTCHA (I never solve those).", step)
                    continue
                }
                val vision = driver.describeScreenshot(goal)
                val user = buildString {
                    append("Goal: ").append(goal).append('\n')
                    if (notes.isNotBlank()) append("Notes from the user: ").append(notes.take(1500)).append('\n')
                    if (history.isNotEmpty()) append("Done so far:\n").append(history.takeLast(8).joinToString("\n")).append('\n')
                    append("Screen:\n").append(snap.describe())
                    if (vision != null) append("\nWhat the screenshot shows: ").append(vision.take(800))
                    append("\nNext action:")
                }
                val raw = llm.complete(system(), user, Gbnf.of(actionSchema(snap)), limits.actionTokens)
                val (thought, action) = parse(raw) ?: run {
                    failures++
                    if (failures >= limits.maxFailures) return finish("I could not decide on valid actions; stopped.", step)
                    return@run null
                } ?: continue
                step++
                when (action) {
                    is ScreenAction.Done -> { log(step, snap, action.describe(snap), "done"); return finish(action.summary.ifBlank { "Done." }, step) }
                    is ScreenAction.AskUser -> {
                        host.preview(step, action.describe(snap))
                        val ans = host.ask(action.question) ?: return finish("Stopped: you declined to answer.", step)
                        userProvided += ans.trim()
                        history += "$step. asked \"${action.question}\" → user answered"
                        log(step, snap, "ask user", "answered")
                        continue
                    }
                    else -> Unit
                }
                val desc = action.describe(snap)
                when (val v = policy.check(action, snap, userProvided)) {
                    is AutomationPolicy.Verdict.Stop -> { log(step, snap, desc, "blocked: ${v.reason}"); return finish("Stopped: ${v.reason}.", step) }
                    is AutomationPolicy.Verdict.NeedsApproval -> {
                        host.preview(step, "Needs your OK: $desc")
                        if (!host.approve(step, desc, v.reason)) {
                            log(step, snap, desc, "declined by user")
                            return finish("Stopped before \"$desc\" — you declined. Everything before it was done.", step)
                        }
                    }
                    AutomationPolicy.Verdict.Allow -> host.preview(step, desc)
                }
                if (_state.value == AutomationState.STOPPED) return finish("Stopped by you after ${step - 1} steps.", step)
                val ok = driver.perform(action, snap)
                log(step, snap, desc, if (ok) "ok" else "failed")
                history += "$step. $desc" + (if (thought.isNotBlank()) " ($thought)" else "") + if (ok) "" else " — FAILED"
                if (!ok) { failures++; if (failures >= limits.maxFailures) return finish("Too many failed actions; stopped.", step) } else failures = 0
                delay(limits.settleMillis)
            }
            return finish("Reached the step limit (${limits.maxSteps}); stopped.", step)
        } catch (e: CancellationException) {
            host.finished("Cancelled.")
            throw e
        }
    }

    private fun log(step: Int, s: ScreenSnapshot, action: String, outcome: String) =
        log.add(AutomationLogEntry(clock(), step, s.packageName, action, outcome))

    private fun finish(summary: String, steps: Int): String {
        host.finished(summary)
        return "$summary ($steps step${if (steps == 1) "" else "s"})"
    }
}
