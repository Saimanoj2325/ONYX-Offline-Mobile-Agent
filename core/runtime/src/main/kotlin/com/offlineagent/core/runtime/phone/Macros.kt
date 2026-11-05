package com.offlineagent.core.runtime.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One ancestor of a recorded target (root side first): its view id, class and index in its parent. */
@Serializable
data class AncestorRef(val viewId: String = "", val className: String = "", val index: Int = -1)

/**
 * How a recorded step finds its target again on replay. Phase 6.2 records a rich target: id, texts, hint, class,
 * ancestor chain, the list row's label ("Mom" for a WhatsApp chat row), the list's id, and the position as a
 * fraction of the screen (last resort only). All new fields default, so 6.0/6.1 macros still load.
 */
@Serializable
data class NodeSelector(
    val packageName: String = "",
    val viewId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val hint: String = "",
    val activity: String = "",
    val windowTitle: String = "",
    val ancestors: List<AncestorRef> = emptyList(),
    /** Label of the list row the target sits in (the entity: a chat, contact, song…); may be a {{placeholder}}. */
    val listItemLabel: String = "",
    val listId: String = "",
    val inList: Boolean = false,
    val relX: Float = -1f,
    val relY: Float = -1f,
    val relW: Float = 0f,
    val relH: Float = 0f,
    val editable: Boolean = false,
) {
    /** What a person would call the target. */
    val displayName: String get() = listItemLabel.ifBlank { text.ifBlank { contentDescription.ifBlank { hint.ifBlank { viewId.substringAfterLast('/').replace('_', ' ') } } } }
}

@Serializable
enum class MacroStepKind { OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME, WAIT, LONG_PRESS, IME_ENTER }

@Serializable
data class MacroStep(
    val kind: MacroStepKind,
    val selector: NodeSelector? = null,
    /** TYPE: literal text or "{{name}}" placeholder; OPEN_APP: package; SCROLL: "down"/"up". */
    val value: String = "",
    /** Real pause the user took before this step while recording (replay waits for the screen, up to ~2x this). */
    val waitMillis: Long = 0,
    /** Marked during recording/replay when the target looks irreversible: replay asks before it. */
    val irreversible: Boolean = false,
    /** Phase 6.2: the screen right before / after the step (ids + text hashes, never words). */
    val before: ScreenFingerprint? = null,
    val after: ScreenFingerprint? = null,
    /** TYPE: press the keyboard's Enter/Search key afterwards (asks first, like any submit). */
    val submit: Boolean = false,
)

@Serializable
data class MacroSchedule(
    /** "none" | "daily" | "weekly" | "interval" */
    val kind: String = "none",
    val hour: Int = 9,
    val minute: Int = 0,
    val dayOfWeek: Int = 1,
    val intervalMinutes: Int = 0,
    /** Also run when the phone starts charging (WorkManager constraint trigger). */
    val whenCharging: Boolean = false,
)

/**
 * Optional faster start (e.g. a WhatsApp chat link). Replay tries it first and, when the expected screen appears,
 * skips the first [skipSteps] steps; when the link does not work it replays every step. Never required.
 */
@Serializable
data class DeepLinkStart(
    val kind: String = "",
    val uri: String = "",
    val packageName: String = "",
    val skipSteps: Int = 0,
    val label: String = "",
    val enabled: Boolean = false,
)

/** A suggested {{value}}: a typed text (field "value") or a chosen list item (field "label") at [step] (0-based). */
@Serializable
data class ParamSuggestion(val name: String, val step: Int, val field: String, val example: String)

@Serializable
data class Macro(
    val id: String,
    val name: String,
    val description: String = "",
    val startPackage: String = "",
    val steps: List<MacroStep> = emptyList(),
    val params: List<String> = emptyList(),
    val schedule: MacroSchedule = MacroSchedule(),
    val createdAt: Long = 0,
    /** 1 = recorded before 6.2 (text-only targets), 2 = rich targets + fingerprints. */
    val version: Int = 1,
    val deepLink: DeepLinkStart? = null,
    val suggestions: List<ParamSuggestion> = emptyList(),
) {
    val hasIrreversibleSteps: Boolean get() = steps.any { it.irreversible || it.kind == MacroStepKind.IME_ENTER || it.submit }

    fun toJson(): String = MacroCodec.json.encodeToString(serializer(), this)

    /** Turns a suggestion into a real parameter ({{name}} in the step's text or list label). */
    fun applySuggestion(p: ParamSuggestion): Macro {
        val st = steps.getOrNull(p.step) ?: return this
        val ph = "{{${p.name}}}"
        val changed = when (p.field) {
            "value" -> st.copy(value = ph)
            "label" -> st.selector?.let { sel ->
                val textWasLabel = MacroText.norm(sel.text) == MacroText.norm(sel.listItemLabel) && sel.text.isNotBlank()
                st.copy(selector = sel.copy(listItemLabel = ph, text = if (textWasLabel) ph else sel.text))
            } ?: st
            else -> st
        }
        return copy(steps = steps.toMutableList().also { it[p.step] = changed }, params = if (p.name in params) params else params + p.name,
            suggestions = suggestions.filterNot { it == p })
    }

    /** Every {{name}} still used by a step (params no step uses are dropped by the editor). */
    fun usedParams(): List<String> {
        val rx = Regex("\\{\\{\\s*([A-Za-z0-9_ -]+?)\\s*}}")
        val out = LinkedHashSet<String>()
        for (st in steps) {
            listOf(st.value, st.selector?.listItemLabel.orEmpty(), st.selector?.text.orEmpty()).forEach { v -> rx.findAll(v).forEach { out += it.groupValues[1] } }
        }
        return out.toList()
    }

    /** A skill that runs this macro, so it shows up in Skills and can be triggered by /id or by phrases. */
    fun toSkillMarkdown(): String = """
        |---
        |id: macro-${id.take(40)}
        |name: ${name.replace("\n", " ").take(60)}
        |description: ${(description.ifBlank { "Replays the recorded macro \"$name\"" }).replace("\n", " ").take(150)}
        |triggers: [${name.lowercase().replace(Regex("[^a-z0-9 ]"), "").take(40)}]
        |tools: [run_macro]
        |max_steps: 2
        |---
        |Call run_macro with name "$id"${if (params.isNotEmpty()) " and params in this order: " + params.joinToString() + " (take the values from the request)" else " and an empty params list"}.
        |Then report what happened in one sentence.
        |""".trimMargin()
}

object MacroCodec {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(s: String): Macro = json.decodeFromString(Macro.serializer(), s)
}

/**
 * Raw events from the accessibility service while recording. [screen] is the whole window at that moment (paths
 * assigned) and [path] the event source's child-index path in it; both are optional (shallow events still work).
 */
sealed interface RecordedEvent {
    val time: Long
    data class WindowChanged(val packageName: String, override val time: Long, val activity: String = "", val screen: ScreenSnapshot? = null) : RecordedEvent
    data class Clicked(val node: ScreenNode, val packageName: String, override val time: Long, val screen: ScreenSnapshot? = null, val path: List<Int>? = null) : RecordedEvent
    data class LongClicked(val node: ScreenNode, val packageName: String, override val time: Long, val screen: ScreenSnapshot? = null, val path: List<Int>? = null) : RecordedEvent
    data class TextChanged(val node: ScreenNode, val packageName: String, val text: String, override val time: Long, val screen: ScreenSnapshot? = null, val path: List<Int>? = null) : RecordedEvent
    data class Scrolled(val packageName: String, val direction: String, override val time: Long, val listId: String = "") : RecordedEvent
    data class GlobalBack(override val time: Long) : RecordedEvent
    /** The keyboard's Enter / Search key (only seen when the keyboard sends key events). */
    data class ImeEnter(val packageName: String, override val time: Long) : RecordedEvent
    /** A settled screen (throttled window-content updates): gives each step its before/after fingerprint. */
    data class ScreenSeen(val screen: ScreenSnapshot, override val time: Long) : RecordedEvent
}

/** Password detection that also looks at the input type (some apps don't set isPassword). */
object SecretFields {
    private const val CLASS_MASK = 0x0000000f
    private const val VAR_MASK = 0x00000ff0
    fun isPassword(n: ScreenNode): Boolean {
        if (n.password) return true
        val cls = n.inputType and CLASS_MASK
        val v = n.inputType and VAR_MASK
        return (cls == 1 && (v == 0x80 || v == 0x90 || v == 0xe0)) || (cls == 2 && v == 0x10) ||
            Regex("(?i)(password|passcode|\\bpin\\b|cvv)").containsMatchIn(n.hint + " " + n.viewId.substringAfterLast('/'))
    }
}

/**
 * Turns a demonstration into replayable steps: an OPEN_APP for the first app, one TAP / LONG_PRESS per click with a
 * rich target, ONE TYPE per field (its final value, even when the user tapped away and came back), scrolls merged
 * (and programmatic scrolls right after a tap/window change ignored), Back (key event or "returned to an earlier
 * screen"), Home, and the user's own pauses as waits. ONYX's own windows are ignored. Password and one-time-code
 * fields are never recorded. Typed texts and chosen list items become suggested {{values}}.
 */
class MacroRecorder(
    private val ownPackage: String,
    private val policy: AutomationPolicy = AutomationPolicy(),
    /**
     * Phase 6.1: the home screen / launcher (the app finds it with PackageManager.resolveActivity(ACTION_MAIN +
     * CATEGORY_HOME), never by name). Launcher windows are never a starting app: a tap on a home-screen icon becomes
     * OPEN_APP of that app, returning to the home screen becomes HOME, everything else there is dropped.
     */
    private val isHome: (String) -> Boolean = { false },
    /** Launcher icon label → package with a launch intent ("WhatsApp" → com.whatsapp); null when unknown. */
    private val appForLabel: (String) -> String? = { null },
) {
    private val steps = ArrayList<MacroStep>()
    private var startPackage = ""
    private var lastTime = 0L
    private var lastPackage = ""
    private var lastActionTime = 0L
    private var lastWindowTime = 0L
    private var lastBackTime = -10_000L
    private var lastScreen: ScreenSnapshot? = null
    private var lastScreenTime = 0L

    val recorded: List<MacroStep> get() = steps.toList()

    private fun pkgOf(e: RecordedEvent) = when (e) {
        is RecordedEvent.WindowChanged -> e.packageName; is RecordedEvent.Clicked -> e.packageName; is RecordedEvent.LongClicked -> e.packageName
        is RecordedEvent.TextChanged -> e.packageName; is RecordedEvent.Scrolled -> e.packageName; is RecordedEvent.GlobalBack -> lastPackage
        is RecordedEvent.ImeEnter -> e.packageName; is RecordedEvent.ScreenSeen -> e.screen.packageName
    }

    private fun fp(pkg: String): ScreenFingerprint? = lastScreen?.takeIf { it.packageName == pkg }?.let { ScreenFingerprint.of(it) }

    /** Adds a step; the previous step's "after" is the screen this one starts on. */
    private fun push(st: MacroStep) {
        val before = st.before
        if (before != null) steps.lastOrNull()?.let { prev -> if (prev.after == null) steps[steps.size - 1] = prev.copy(after = before) }
        steps += st
    }

    fun add(e: RecordedEvent) {
        val pkg = pkgOf(e)
        if (MacroApps.isSystemChrome(pkg, ownPackage)) return
        val time = e.time
        if (e is RecordedEvent.ScreenSeen) {
            if (!isHome(pkg)) { lastScreen = e.screen; lastScreenTime = time }
            return
        }
        if (e is RecordedEvent.WindowChanged && e.screen != null && !isHome(pkg)) { lastScreen = e.screen; lastScreenTime = time }
        if (pkg.isNotEmpty() && isHome(pkg)) { onHome(e, time); return }
        if (startPackage.isEmpty() && pkg.isNotEmpty()) {
            startPackage = pkg
            steps += MacroStep(MacroStepKind.OPEN_APP, value = pkg)
        } else if (pkg.isNotEmpty() && pkg != lastPackage && e is RecordedEvent.WindowChanged && steps.lastOrNull()?.kind != MacroStepKind.OPEN_APP) {
            // switching apps mid-demonstration: replay must open that app too (only when not caused by a tap)
            if (steps.lastOrNull()?.kind != MacroStepKind.TAP) steps += MacroStep(MacroStepKind.OPEN_APP, value = pkg)
        }
        val gap = if (lastTime == 0L) 0L else (time - lastTime).coerceIn(0, 10_000)
        when (e) {
            is RecordedEvent.Clicked -> {
                val sel = TargetCapture.selector(e.node, pkg, e.screen, e.path)
                push(MacroStep(MacroStepKind.TAP, sel, waitMillis = gap, irreversible = policy.isIrreversibleTarget(e.node), before = fp(pkg)))
                lastActionTime = time
            }
            is RecordedEvent.LongClicked -> {
                val sel = TargetCapture.selector(e.node, pkg, e.screen, e.path)
                // A long-press also fires a click on some views: replace a TAP on the same target recorded just now.
                val last = steps.lastOrNull()
                if (last != null && last.kind == MacroStepKind.TAP && sameTarget(last.selector, sel) && time - lastActionTime < 800) steps.removeAt(steps.size - 1)
                push(MacroStep(MacroStepKind.LONG_PRESS, sel, waitMillis = gap, irreversible = policy.isIrreversibleTarget(e.node), before = fp(pkg)))
                lastActionTime = time
            }
            is RecordedEvent.TextChanged -> {
                if (SecretFields.isPassword(e.node) || policy.isOtpField(e.node)) return
                val sel = TargetCapture.selector(e.node, pkg, e.screen, e.path).copy(editable = true)
                // Final value per field: update the earlier TYPE of this field when only taps on that same field came between.
                for (i in steps.indices.reversed()) {
                    val st = steps[i]
                    if (st.kind == MacroStepKind.TYPE && sameField(st.selector, sel)) { steps[i] = st.copy(value = e.text); lastTime = time; return }
                    if (!(st.kind == MacroStepKind.TAP && sameField(st.selector, sel))) break
                }
                push(MacroStep(MacroStepKind.TYPE, sel, e.text, waitMillis = gap, before = fp(pkg)))
                lastActionTime = time
            }
            is RecordedEvent.Scrolled -> {
                // Apps scroll by themselves right after a tap or a new screen (a chat jumps to its last message): not the user.
                if (time - lastActionTime < 700 || time - lastWindowTime < 1000) { lastTime = time; return }
                val last = steps.lastOrNull()
                if (!(last != null && last.kind == MacroStepKind.SCROLL && last.value == e.direction)) push(MacroStep(MacroStepKind.SCROLL, NodeSelector(pkg, viewId = e.listId, listId = e.listId), e.direction, waitMillis = gap, before = fp(pkg)))
            }
            is RecordedEvent.GlobalBack -> { push(MacroStep(MacroStepKind.BACK, waitMillis = gap, before = fp(pkg))); lastBackTime = time; lastActionTime = time }
            is RecordedEvent.ImeEnter -> {
                val last = steps.lastOrNull()
                if (last != null && last.kind == MacroStepKind.TYPE) steps[steps.size - 1] = last.copy(submit = true)
                else push(MacroStep(MacroStepKind.IME_ENTER, waitMillis = gap, before = fp(pkg)))
                lastActionTime = time
            }
            is RecordedEvent.WindowChanged -> {
                // Back without a key event (gesture nav on some phones): we came back to a screen seen earlier, with no tap.
                val now = e.screen?.let { ScreenFingerprint.of(it) }
                if (now != null && pkg == lastPackage && time - lastActionTime > 1000 && time - lastBackTime > 1000 && steps.size > 1) {
                    val earlier = steps.dropLast(1).any { it.before?.packageName == pkg && (it.before?.similarity(now) ?: 0.0) >= 0.85 }
                    val differsFromLast = (steps.last().before?.similarity(now) ?: 0.0) < 0.85
                    if (earlier && differsFromLast) { push(MacroStep(MacroStepKind.BACK, waitMillis = gap, before = null)); lastBackTime = time }
                }
                lastWindowTime = time
            }
            is RecordedEvent.ScreenSeen -> Unit
        }
        lastTime = time
        if (pkg.isNotEmpty()) lastPackage = pkg
    }

    private fun sameField(a: NodeSelector?, b: NodeSelector): Boolean {
        a ?: return false
        if (a.packageName != b.packageName) return false
        if (a.viewId.isNotBlank() || b.viewId.isNotBlank()) return a.viewId == b.viewId && a.hint == b.hint
        return a.className == b.className && a.hint == b.hint && a.ancestors == b.ancestors
    }

    private fun sameTarget(a: NodeSelector?, b: NodeSelector) = a != null && a.viewId == b.viewId && a.text == b.text && a.listItemLabel == b.listItemLabel && a.contentDescription == b.contentDescription

    /** Launcher events: icon tap → OPEN_APP(app); coming back to the home screen mid-demo → HOME; the rest is dropped. */
    private fun onHome(e: RecordedEvent, time: Long) {
        when (e) {
            is RecordedEvent.Clicked -> {
                val label = e.node.label.ifBlank { e.node.contentDescription }.trim()
                val target = label.takeIf { it.isNotBlank() }?.let(appForLabel)?.takeIf { !isHome(it) && !MacroApps.isSystemChrome(it, ownPackage) }
                if (target != null) {
                    if (startPackage.isEmpty()) startPackage = target
                    if (!(steps.lastOrNull()?.kind == MacroStepKind.OPEN_APP && steps.last().value == target)) steps += MacroStep(MacroStepKind.OPEN_APP, value = target)
                    lastPackage = target // the app's own window event that follows is not a second switch
                    lastTime = time
                    lastActionTime = time
                }
            }
            is RecordedEvent.WindowChanged -> {
                // Pressing Home in the middle of a demonstration (before: nothing recorded yet → nothing to replay).
                if (steps.isNotEmpty() && steps.last().kind != MacroStepKind.HOME) steps += MacroStep(MacroStepKind.HOME)
                lastPackage = e.packageName
                lastTime = time
            }
            else -> Unit
        }
    }

    /**
     * Builds the macro. [variables] (name → literal value) turns matching typed texts and list labels into
     * {{placeholders}} right away; everything else typed or chosen is offered as a suggestion in the editor.
     */
    fun build(id: String, name: String, description: String, variables: Map<String, String> = emptyMap(), now: Long = System.currentTimeMillis()): Macro {
        // The final screen is the last step's "after".
        val finalSteps0 = steps.toMutableList()
        if (finalSteps0.isNotEmpty() && finalSteps0.last().after == null && lastScreen != null && lastScreenTime >= lastActionTime)
            finalSteps0[finalSteps0.size - 1] = finalSteps0.last().copy(after = ScreenFingerprint.of(lastScreen))
        val params = ArrayList<String>()
        val finalSteps = finalSteps0.map { s ->
            val sel = s.selector
            when {
                s.kind == MacroStepKind.TYPE -> {
                    val v = variables.entries.firstOrNull { it.value == s.value }
                    if (v != null) { if (v.key !in params) params += v.key; s.copy(value = "{{${v.key}}}") } else s
                }
                sel != null && sel.listItemLabel.isNotBlank() -> {
                    val v = variables.entries.firstOrNull { it.value.equals(sel.listItemLabel, true) }
                    if (v != null) { if (v.key !in params) params += v.key; s.copy(selector = sel.copy(listItemLabel = "{{${v.key}}}", text = if (sel.text.equals(sel.listItemLabel, true)) "{{${v.key}}}" else sel.text)) } else s
                }
                else -> s
            }
        }.dropLastWhile { it.kind == MacroStepKind.HOME }
        val m = Macro(id, name, description, startPackage, finalSteps, params, createdAt = now, version = 2)
        return m.copy(suggestions = MacroParams.suggest(m), deepLink = DeepLinks.suggest(m))
    }

    companion object {
        fun selector(n: ScreenNode, pkg: String) = NodeSelector(pkg, n.viewId, n.text.take(80).takeIf { !n.editable } ?: "", n.contentDescription.take(80), n.className, n.hint.take(80))
    }
}

/** Phase 6.2: which typed texts / chosen list items could be {{values}}. */
object MacroParams {
    private val CHAT_APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging", "com.instagram.android",
        "com.facebook.orca", "org.thoughtcrime.securesms", "com.samsung.android.messaging", "com.android.mms")
    private val CHAT_LIST = Regex("(?i)(conversation|chat|contact|thread|inbox)")

    fun isChatList(sel: NodeSelector) = sel.packageName in CHAT_APPS || CHAT_LIST.containsMatchIn(sel.listId)

    fun suggest(m: Macro): List<ParamSuggestion> {
        val used = HashSet(m.params)
        val out = ArrayList<ParamSuggestion>()
        fun name(base: String): String { var n = base; var i = 2; while (n in used) n = base + i++; used += n; return n }
        m.steps.forEachIndexed { i, st ->
            val sel = st.selector
            if (st.kind == MacroStepKind.TYPE && st.value.isNotBlank() && !st.value.contains("{{")) {
                val hint = (sel?.hint.orEmpty() + " " + sel?.viewId.orEmpty()).lowercase()
                val base = when { "search" in hint -> "search"; "message" in hint || "entry" in hint || "compose" in hint -> "message"; "name" in hint -> "name"; else -> "text" }
                out += ParamSuggestion(name(base), i, "value", st.value.take(60))
            }
            if ((st.kind == MacroStepKind.TAP || st.kind == MacroStepKind.LONG_PRESS) && sel != null && sel.listItemLabel.isNotBlank() && !sel.listItemLabel.contains("{{"))
                out += ParamSuggestion(name(if (isChatList(sel)) "chat" else "item"), i, "label", sel.listItemLabel.take(60))
        }
        return out
    }
}

/** Phase 6.2: optional deep-link starts (only where the app has a reliable one). */
object DeepLinks {
    val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")

    /** A WhatsApp macro that opens a chat from the chat list can start with the chat link instead (needs a number). */
    fun suggest(m: Macro): DeepLinkStart? {
        val pkg = m.steps.firstOrNull()?.takeIf { it.kind == MacroStepKind.OPEN_APP }?.value ?: return null
        if (pkg !in WHATSAPP) return null
        val tapIdx = m.steps.indexOfFirst { (it.kind == MacroStepKind.TAP) && it.selector?.listItemLabel?.isNotBlank() == true }
        if (tapIdx <= 0) return null
        if (m.steps.subList(1, tapIdx).any { it.kind != MacroStepKind.TAP && it.kind != MacroStepKind.SCROLL && it.kind != MacroStepKind.WAIT }) return null
        return DeepLinkStart("whatsapp_chat", "", pkg, tapIdx + 1, m.steps[tapIdx].selector!!.listItemLabel, enabled = false)
    }

    /** "+91 98480 22338" → "https://wa.me/919848022338" (null when it isn't a plausible international number). */
    fun whatsappUri(phone: String, defaultCountry: String = "91"): String? {
        var d = phone.filter { it.isDigit() || it == '+' }
        if (d.isBlank()) return null
        d = when {
            d.startsWith("+") -> d.drop(1)
            d.startsWith("00") -> d.drop(2)
            d.length == 10 -> defaultCountry + d
            d.length == 11 && d.startsWith("0") -> defaultCountry + d.drop(1)
            else -> d
        }.filter { it.isDigit() }
        return if (d.length in 8..15) "https://wa.me/$d" else null
    }
}

/** Phase 6.2: steps in plain words for the editor and the overlay. */
object MacroDescribe {
    fun step(st: MacroStep, appLabel: (String) -> String = { it }): String {
        val sel = st.selector
        val app = sel?.packageName?.takeIf { it.isNotBlank() }?.let(appLabel)
        val inApp = if (app != null) " in $app" else ""
        fun q(s: String) = "'" + s.replace("\n", " ").take(40) + "'"
        return when (st.kind) {
            MacroStepKind.OPEN_APP -> "Open ${appLabel(st.value)}"
            MacroStepKind.TAP, MacroStepKind.LONG_PRESS -> {
                val verb = if (st.kind == MacroStepKind.TAP) "Tap" else "Long-press"
                val what = when {
                    sel == null -> "something"
                    sel.listItemLabel.isNotBlank() -> (if (MacroParams.isChatList(sel)) "chat " else "item ") + q(sel.listItemLabel)
                    sel.text.isNotBlank() -> q(sel.text)
                    sel.contentDescription.isNotBlank() -> q(sel.contentDescription) + " button"
                    sel.editable && sel.hint.isNotBlank() -> "the " + q(sel.hint) + " field"
                    sel.viewId.isNotBlank() -> q(sel.viewId.substringAfterLast('/').replace('_', ' '))
                    else -> "a ${sel.className.substringAfterLast('.').ifBlank { "control" }}"
                }
                "$verb $what$inApp" + if (st.irreversible) " (asks first)" else ""
            }
            MacroStepKind.TYPE -> "Type ${q(st.value)}" + (sel?.let { s -> s.hint.ifBlank { s.contentDescription }.takeIf { it.isNotBlank() }?.let { " into " + q(it) } } ?: "") +
                (if (st.submit) " and press Enter (asks first)" else "")
            MacroStepKind.SCROLL -> "Scroll ${st.value.ifBlank { "down" }}$inApp"
            MacroStepKind.BACK -> "Press Back"
            MacroStepKind.HOME -> "Go to the home screen"
            MacroStepKind.WAIT -> "Wait ${(st.waitMillis + 500) / 1000} s"
            MacroStepKind.IME_ENTER -> "Press Enter on the keyboard (asks first)"
        }
    }
}

/** Phase 6.1: which packages a macro may treat as an app, and the migration of macros recorded before 6.1. */
object MacroApps {
    /** Status bar / notification shade and ONYX itself are never part of a macro. */
    fun isSystemChrome(pkg: String, ownPackage: String): Boolean =
        pkg.isNotEmpty() && (pkg == ownPackage || pkg.startsWith("$ownPackage.") || pkg.startsWith("com.android.systemui"))

    /**
     * Fixes a macro recorded on the home screen (6.0 made "open com.android.launcher" its first step, which has no launch
     * intent): leading launcher OPEN_APPs are dropped, later ones become HOME, a launcher icon TAP becomes OPEN_APP of the
     * app behind the icon (when [appForLabel] knows it), and [Macro.startPackage] becomes the first real app.
     * Returns the macro unchanged (same instance) when there is nothing to fix.
     */
    fun migrate(m: Macro, ownPackage: String, isHome: (String) -> Boolean, appForLabel: (String) -> String? = { null }): Macro {
        fun home(p: String) = p.isNotEmpty() && (isHome(p) || isSystemChrome(p, ownPackage))
        val touches = home(m.startPackage) || m.steps.any { st ->
            (st.kind == MacroStepKind.OPEN_APP && home(st.value)) || (st.selector?.packageName?.let(::home) == true)
        }
        if (!touches) return m
        val out = ArrayList<MacroStep>()
        for (st in m.steps) {
            val selPkg = st.selector?.packageName.orEmpty()
            when {
                st.kind == MacroStepKind.OPEN_APP && home(st.value) -> if (out.isNotEmpty() && out.last().kind != MacroStepKind.HOME) out += MacroStep(MacroStepKind.HOME)
                st.kind == MacroStepKind.TAP && home(selPkg) -> {
                    val label = st.selector?.let { sel -> sel.text.ifBlank { sel.contentDescription } }.orEmpty()
                    val target = label.takeIf { it.isNotBlank() }?.let(appForLabel)?.takeIf { !home(it) }
                    if (target != null) { if (!(out.lastOrNull()?.kind == MacroStepKind.OPEN_APP && out.last().value == target)) out += MacroStep(MacroStepKind.OPEN_APP, value = target, waitMillis = st.waitMillis) }
                    else { if (out.lastOrNull()?.kind != MacroStepKind.HOME) out += MacroStep(MacroStepKind.HOME); out += st }
                }
                (st.kind == MacroStepKind.TYPE || st.kind == MacroStepKind.SCROLL) && home(selPkg) -> Unit // launcher search / page swipes
                else -> out += st
            }
        }
        while (out.firstOrNull()?.kind == MacroStepKind.HOME) out.removeAt(0)
        val start = out.firstOrNull { it.kind == MacroStepKind.OPEN_APP }?.value
            ?: out.firstNotNullOfOrNull { it.selector?.packageName?.takeIf { p -> p.isNotBlank() && !home(p) } } ?: ""
        return m.copy(startPackage = start, steps = out)
    }
}

