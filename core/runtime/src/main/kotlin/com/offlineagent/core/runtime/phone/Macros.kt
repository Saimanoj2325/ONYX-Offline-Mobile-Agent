package com.offlineagent.core.runtime.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How a recorded step finds its target again on replay (most specific first). */
@Serializable
data class NodeSelector(
    val packageName: String = "",
    val viewId: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val className: String = "",
    val hint: String = "",
)

@Serializable
enum class MacroStepKind { OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME, WAIT }

@Serializable
data class MacroStep(
    val kind: MacroStepKind,
    val selector: NodeSelector? = null,
    /** TYPE: literal text or "{{name}}" placeholder; OPEN_APP: package; SCROLL: "down"/"up". */
    val value: String = "",
    val waitMillis: Long = 0,
    /** Marked during recording/replay when the target looks irreversible: replay asks before it. */
    val irreversible: Boolean = false,
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
) {
    val hasIrreversibleSteps: Boolean get() = steps.any { it.irreversible }

    fun toJson(): String = MacroCodec.json.encodeToString(serializer(), this)

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

/** Raw events from the accessibility service while recording. */
sealed interface RecordedEvent {
    data class WindowChanged(val packageName: String, val time: Long) : RecordedEvent
    data class Clicked(val node: ScreenNode, val packageName: String, val time: Long) : RecordedEvent
    data class TextChanged(val node: ScreenNode, val packageName: String, val text: String, val time: Long) : RecordedEvent
    data class Scrolled(val packageName: String, val direction: String, val time: Long) : RecordedEvent
    data class GlobalBack(val time: Long) : RecordedEvent
}

/**
 * Turns a demonstration into replayable steps: an OPEN_APP for the first app, one TAP per click, ONE TYPE per field
 * (the final text, not every keystroke), scrolls merged, and the user's own app (the recorder overlay) ignored.
 * Typed values that the user marks as variables become {{placeholders}}. Password fields are never recorded.
 */
class MacroRecorder(private val ownPackage: String, private val policy: AutomationPolicy = AutomationPolicy()) {
    private val steps = ArrayList<MacroStep>()
    private var startPackage = ""
    private var lastTime = 0L
    private var lastPackage = ""

    val recorded: List<MacroStep> get() = steps.toList()

    fun add(e: RecordedEvent) {
        val pkg = when (e) {
            is RecordedEvent.WindowChanged -> e.packageName; is RecordedEvent.Clicked -> e.packageName
            is RecordedEvent.TextChanged -> e.packageName; is RecordedEvent.Scrolled -> e.packageName; is RecordedEvent.GlobalBack -> lastPackage
        }
        if (pkg == ownPackage || pkg.startsWith("com.android.systemui")) return
        val time = when (e) {
            is RecordedEvent.WindowChanged -> e.time; is RecordedEvent.Clicked -> e.time; is RecordedEvent.TextChanged -> e.time
            is RecordedEvent.Scrolled -> e.time; is RecordedEvent.GlobalBack -> e.time
        }
        if (startPackage.isEmpty() && pkg.isNotEmpty()) {
            startPackage = pkg
            steps += MacroStep(MacroStepKind.OPEN_APP, value = pkg)
        } else if (pkg.isNotEmpty() && pkg != lastPackage && e is RecordedEvent.WindowChanged && steps.lastOrNull()?.kind != MacroStepKind.OPEN_APP) {
            // switching apps mid-demonstration: replay must open that app too (only when not caused by a tap)
            if (steps.lastOrNull()?.kind != MacroStepKind.TAP) steps += MacroStep(MacroStepKind.OPEN_APP, value = pkg)
        }
        val gap = if (lastTime == 0L) 0L else (time - lastTime).coerceIn(0, 10_000)
        when (e) {
            is RecordedEvent.Clicked -> steps += MacroStep(MacroStepKind.TAP, selector(e.node, pkg), waitMillis = gap.coerceAtMost(3000), irreversible = policy.isIrreversibleTarget(e.node))
            is RecordedEvent.TextChanged -> {
                if (e.node.password) return
                val sel = selector(e.node, pkg)
                val last = steps.lastOrNull()
                if (last != null && last.kind == MacroStepKind.TYPE && last.selector == sel) steps[steps.size - 1] = last.copy(value = e.text)
                else steps += MacroStep(MacroStepKind.TYPE, sel, e.text, waitMillis = gap.coerceAtMost(3000))
            }
            is RecordedEvent.Scrolled -> {
                val last = steps.lastOrNull()
                if (!(last != null && last.kind == MacroStepKind.SCROLL && last.value == e.direction)) steps += MacroStep(MacroStepKind.SCROLL, value = e.direction)
            }
            is RecordedEvent.GlobalBack -> steps += MacroStep(MacroStepKind.BACK)
            is RecordedEvent.WindowChanged -> Unit
        }
        lastTime = time
        if (pkg.isNotEmpty()) lastPackage = pkg
    }

    /** Replace literal typed values with placeholders: values -> parameter names. */
    fun build(id: String, name: String, description: String, variables: Map<String, String> = emptyMap(), now: Long = System.currentTimeMillis()): Macro {
        val params = ArrayList<String>()
        val finalSteps = steps.map { s ->
            if (s.kind == MacroStepKind.TYPE) {
                val v = variables.entries.firstOrNull { it.value == s.value }
                if (v != null) { if (v.key !in params) params += v.key; s.copy(value = "{{${v.key}}}") } else s
            } else s
        }
        return Macro(id, name, description, startPackage, finalSteps, params, createdAt = now)
    }

    companion object {
        fun selector(n: ScreenNode, pkg: String) = NodeSelector(pkg, n.viewId, n.text.take(80).takeIf { !n.editable } ?: "", n.contentDescription.take(80), n.className, n.hint.take(80))
    }
}

/** Finds a recorded step's target in a live snapshot. */
object MacroMatcher {
    fun find(sel: NodeSelector, s: ScreenSnapshot): ScreenElement? {
        val cands = s.elements
        fun score(n: ScreenNode): Int {
            var sc = 0
            if (sel.viewId.isNotBlank() && n.viewId == sel.viewId) sc += 5
            if (sel.text.isNotBlank() && n.text.equals(sel.text, true)) sc += 4
            if (sel.contentDescription.isNotBlank() && n.contentDescription.equals(sel.contentDescription, true)) sc += 4
            if (sel.hint.isNotBlank() && n.hint.equals(sel.hint, true)) sc += 3
            if (sel.className.isNotBlank() && n.className == sel.className) sc += 1
            if (sel.text.isNotBlank() && sc < 4 && n.label.contains(sel.text, true)) sc += 2
            return sc
        }
        val best = cands.maxByOrNull { score(it.node) } ?: return null
        val sc = score(best.node)
        // Need more than a class-name match.
        return if (sc >= 3) best else null
    }

    fun fill(value: String, params: Map<String, String>): String =
        Regex("\\{\\{([A-Za-z0-9_ -]+)}}").replace(value) { m -> params[m.groupValues[1].trim()] ?: m.value }
}

/** Replays a macro through the same [ScreenDriver] + [AutomationHost] + [AutomationPolicy] as the screen agent. */
class MacroPlayer(
    private val driver: ScreenDriver,
    private val host: AutomationHost,
    private val policy: AutomationPolicy,
    private val log: AutomationLog,
    private val findTimeoutMillis: Long = 6000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    suspend fun play(m: Macro, params: Map<String, String>, unattended: Boolean = false): String {
        val missing = m.params.filter { params[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return "Missing values for: ${missing.joinToString()}".also { host.finished(it) }
        m.steps.forEachIndexed { i, st ->
            val step = i + 1
            if (st.waitMillis > 0) sleep(st.waitMillis.coerceAtMost(3000))
            val action: ScreenAction
            var snap: ScreenSnapshot? = null
            when (st.kind) {
                MacroStepKind.OPEN_APP -> action = ScreenAction.OpenApp(st.value)
                MacroStepKind.BACK -> action = ScreenAction.Back
                MacroStepKind.HOME -> action = ScreenAction.Home
                MacroStepKind.WAIT -> action = ScreenAction.Wait(st.waitMillis)
                MacroStepKind.SCROLL -> action = ScreenAction.Scroll(st.value.ifBlank { "down" })
                MacroStepKind.TAP, MacroStepKind.TYPE -> {
                    val sel = st.selector ?: return fail("step $step has no target", step)
                    var el: ScreenElement? = null
                    val deadline = clock() + findTimeoutMillis
                    while (el == null && clock() < deadline) {
                        snap = driver.snapshot()
                        el = snap?.let { MacroMatcher.find(sel, it) }
                        if (el == null) sleep(400)
                    }
                    if (el == null || snap == null) return fail("could not find \"${sel.text.ifBlank { sel.contentDescription.ifBlank { sel.viewId } }}\" on screen (step $step)", step)
                    action = if (st.kind == MacroStepKind.TAP) ScreenAction.Tap(el.id) else ScreenAction.Type(el.id, MacroMatcher.fill(st.value, params))
                }
            }
            val s = snap ?: driver.snapshot()
            if (s != null) {
                when (val v = policy.check(action, s, params.values.toSet())) {
                    is AutomationPolicy.Verdict.Stop -> return fail(v.reason, step)
                    is AutomationPolicy.Verdict.NeedsApproval -> {
                        if (unattended) return fail("step \"${action.describe(s)}\" needs your approval — open the app to finish", step)
                        if (!host.approve(step, action.describe(s), v.reason)) return fail("you declined \"${action.describe(s)}\"", step)
                    }
                    AutomationPolicy.Verdict.Allow -> host.preview(step, action.describe(s))
                }
                if (st.irreversible && action !is ScreenAction.Tap) { /* marked steps are taps; nothing else */ }
                val ok = driver.perform(action, s)
                log.add(AutomationLogEntry(clock(), step, s.packageName, action.describe(s), if (ok) "ok (macro ${m.name})" else "failed (macro ${m.name})"))
                if (!ok) return fail("step $step failed", step)
            } else return fail("cannot read the screen", step)
            sleep(500)
        }
        return "Macro \"${m.name}\" finished (${m.steps.size} steps).".also { host.finished(it) }
    }

    private fun fail(why: String, step: Int) = "Macro stopped at step $step: $why.".also { host.finished(it) }
}
