package com.offlineagent.core.screen

import com.offlineagent.core.agents.Risk
import com.offlineagent.core.runtime.phone.ScreenElement
import com.offlineagent.core.runtime.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The screen engine behind the screen_* tools. Holds the snapshot history (for ref scoping), the last action (for
 * idempotent retries: a retried step is RE-VERIFIED, never re-performed), and the last extraction (cross-app
 * transfer placeholders). Every action passes: feature switch → kill switch → [ScreenSafetyPolicy] (block / take
 * over) → ref staleness → per-step confirmation for irreversible targets → overlay preview → perform → audit → diff
 * → subgoal check.
 */
class ScreenSession(
    private val port: ScreenPort,
    private val host: ScreenHost,
    val policy: ScreenSafetyPolicy,
    private val audit: ScreenAudit,
    private val kill: KillSwitch,
    val cards: AppCardStore,
    private val llm: () -> ScreenLlm?,
    private val config: () -> ScreenConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Set by the toolkit: consumes a per-step approval the user already gave on the sheet. */
    var criticalPreApproved: (tool: String, args: JsonObject) -> Boolean = { _, _ -> false }

    private var counter = 0
    private val views = ArrayDeque<ScreenView>()
    val latest: ScreenView? get() = views.lastOrNull()
    private val extracted = LinkedHashMap<String, String>()
    val lastExtract: Map<String, String> get() = extracted

    private data class LastAct(val key: String, val at: Long, val desc: String, val before: ScreenView, val element: ScreenElement?)
    private var lastAct: LastAct? = null

    // ------------------------------------------------------------------ basics
    private fun err(code: String, msg: String, retry: Boolean = false) = ToolResult.Error("$code: $msg", retry)

    private fun guard(): ToolResult.Error? = when {
        !config().enabled -> err("DISABLED", "the autonomous screen agent is switched off (Agents → Screen agent). It exists only in sideloaded builds.")
        kill.engaged() -> err("STOPPED", "screen automation was stopped by you (kill switch). Turn it back on to continue.")
        else -> null
    }

    /** Captures the live screen. Same structure as the latest snapshot → same snapshot id (refs stay valid). */
    suspend fun current(): ScreenView? {
        val f = port.capture() ?: return null
        val l = latest
        val sig = ScreenView.signatureOf(f)
        return if (l != null && l.signature == sig) ScreenView(l.id, f).also { views.removeLast(); views.addLast(it) } else register(f)
    }

    private fun register(f: ScreenFrame): ScreenView {
        val v = ScreenView("s${++counter}", f)
        views.addLast(v)
        while (views.size > 8) views.removeFirst()
        return v
    }

    suspend fun currentPackage(): String? = (latest ?: current())?.pkg

    private val serviceOff get() = err("SERVICE_OFF", "I can't read the screen: turn on ONYX in Android Settings → Accessibility (Phone actions → Screen automation).")

    /** Safety gate on the live screen; handles "take over". Returns the view to act on, or an error. */
    private suspend fun safeView(): Pair<ScreenView?, ToolResult.Error?> {
        var v = current() ?: return null to serviceOff
        repeat(2) {
            when (val verdict = policy.screenVerdict(v.frame)) {
                SafetyVerdict.Allow -> return v to null
                is SafetyVerdict.Block -> { audit.record("blocked screen", "app=${v.pkg} ${verdict.reason}", Risk.HIGH); return null to err("BLOCKED", verdict.reason) }
                is SafetyVerdict.TakeOver -> {
                    audit.record("take over requested", "app=${v.pkg} ${verdict.reason}", Risk.MEDIUM)
                    if (!host.takeOver(verdict.reason)) return null to err("TAKEN_OVER", "stopped: ${verdict.reason}. Ask me to continue when you are done.")
                    v = current() ?: return null to serviceOff
                }
            }
        }
        return null to err("TAKEN_OVER", "the screen still needs you (login, captcha or secure window)")
    }

    fun resolvePlaceholders(text: String): Pair<String, String?> {
        var missing: String? = null
        val out = Regex("\\{\\{\\s*([^}]+?)\\s*}}").replace(text) { m ->
            val k = m.groupValues[1]
            extracted.entries.firstOrNull { it.key.equals(k, true) }?.value ?: run { missing = k; m.value }
        }
        return out to missing
    }

    private fun desc(action: String, e: ScreenElement?, text: String) = when (action) {
        "tap" -> "Tap \"${e?.node?.label?.take(40)}\""
        "long_press" -> "Long-press \"${e?.node?.label?.take(40)}\""
        "type" -> "Type ${text.length} chars into \"${(e?.node?.hint?.ifBlank { null } ?: e?.nearbyLabel?.ifBlank { null } ?: e?.node?.label).orEmpty().take(40)}\""
        "enter" -> "Press Enter (submit)"
        "scroll_down" -> "Scroll down"; "scroll_up" -> "Scroll up"; "back" -> "Press Back"; "home" -> "Go home"
        else -> action
    }

    private suspend fun waitFor(text: String, present: Boolean, timeoutMs: Long): Pair<Boolean, ScreenView?> {
        val poll = config().waitPollMs.coerceAtLeast(10)
        val tries = (timeoutMs / poll).coerceAtLeast(0) + 1
        var v: ScreenView? = null
        for (i in 0 until tries) {
            v = current() ?: return false to null
            if (v.contains(text) == present) return true to v
            if (i < tries - 1) delay(poll)
        }
        return false to v
    }

    private fun subgoal(passed: Boolean, issue: String) = buildJsonObject { put("passed", passed); put("issue", issue) }

    // ------------------------------------------------------------------ reads
    suspend fun snapshot(mode: String, root: String): ToolResult {
        guard()?.let { return it }
        val (v, e) = safeView(); if (v == null) return e!!
        val card = cards.get(v.pkg)
        val cfg = config()
        val text = when {
            root.isNotBlank() -> v.drill(root, card, cfg.maxSnapshotChars, policy = policy) ?: return err("NOT_FOUND", "no region $root in snapshot ${v.id}; use a ref from the skeleton", true)
            mode == "full" -> v.full(card, cfg.maxSnapshotChars * 2, policy)
            else -> v.skeleton(card, cfg.maxSnapshotChars, policy = policy)
        }
        audit.record("screen_snapshot", "app=${v.pkg} elements=${v.elements.size} hash=${v.signature} mode=${if (root.isNotBlank()) "drill" else mode}", Risk.MEDIUM)
        return ToolResult.Ok(buildJsonObject { put("snapshot", v.id); put("package", v.pkg); put("elements", v.elements.size); put("regions", v.regions.size) }, text)
    }

    suspend fun find(text: String, role: String): ToolResult {
        guard()?.let { return it }
        val (v, e) = safeView(); if (v == null) return e!!
        val ms = Grounding.find(v, text, role.ifBlank { null })
        val lines = ms.take(10).map { v.line(it.element, policy) }
        return ToolResult.Ok(buildJsonObject {
            put("snapshot", v.id); put("count", ms.size)
            put("refs", buildJsonArray { ms.take(10).forEach { add(kotlinx.serialization.json.JsonPrimitive(v.ref(it.element))) } })
        }, ScreenData.wrap(v.pkg, if (lines.isEmpty()) "No element matches \"${ScreenData.label(text)}\"." else "Matches in ${v.id}:\n" + lines.joinToString("\n")))
    }

    suspend fun waitUntil(text: String, gone: Boolean, timeoutMs: Long): ToolResult {
        guard()?.let { return it }
        val t = resolvePlaceholders(text).first
        val (ok, v) = waitFor(t, !gone, timeoutMs.coerceIn(0, 20_000))
        if (v == null) return serviceOff
        val what = if (gone) "\"${ScreenData.label(t)}\" to disappear" else "\"${ScreenData.label(t)}\" to appear"
        return ToolResult.Ok(buildJsonObject { put("snapshot", v.id); put("found", ok); put("subgoal", subgoal(ok, if (ok) "" else "timed out waiting for $what")) },
            if (ok) "Done waiting for $what (${v.id})." else "Timed out after ${timeoutMs} ms waiting for $what.")
    }

    suspend fun assert(text: String, present: Boolean): ToolResult {
        guard()?.let { return it }
        val t = resolvePlaceholders(text).first
        val v = current() ?: return serviceOff
        val ok = v.contains(t) == present
        val issue = if (ok) "" else "expected \"${ScreenData.label(t)}\" to be ${if (present) "visible" else "gone"} in ${v.pkg}"
        return ToolResult.Ok(buildJsonObject { put("snapshot", v.id); put("passed", ok); put("subgoal", subgoal(ok, issue)) },
            if (ok) "Check passed: \"${ScreenData.label(t)}\" is ${if (present) "visible" else "not visible"}." else "Check failed: $issue.")
    }

    suspend fun extract(fields: List<String>): ToolResult {
        guard()?.let { return it }
        val (v, e) = safeView(); if (v == null) return e!!
        val r = Extractor.extract(v, fields, llm(), cards.get(v.pkg))
        extracted.putAll(r.values)
        audit.record("screen_extract", "app=${v.pkg} fields=${fields.size} found=${r.values.size} model=${r.usedModel}", Risk.MEDIUM)
        val data = Extractor.json(r)
        val issue = if (r.missing.isEmpty()) "" else "not visible on screen: ${r.missing.joinToString()}"
        return ToolResult.Ok(buildJsonObject {
            put("snapshot", v.id); put("package", v.pkg); put("data", data)
            put("missing", buildJsonArray { r.missing.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("subgoal", subgoal(r.missing.isEmpty(), issue))
        }, ScreenData.wrap(v.pkg, "Extracted from ${v.snapshot.appLabel}: $data" + if (issue.isNotBlank()) "\n($issue)" else "") +
            "\nUse {{field}} in screen_transfer / screen_act text to insert these values exactly.")
    }

    // ------------------------------------------------------------------ app switching
    suspend fun openApp(app: String): ToolResult {
        guard()?.let { return it }
        val pkg = port.resolveApp(app) ?: return err("NOT_INSTALLED", "no launchable app matches \"$app\"")
        (policy.appVerdict(pkg) as? SafetyVerdict.Block)?.let { audit.record("refused open", "app=$pkg ${it.reason}", Risk.HIGH); return err("BLOCKED", it.reason) }
        host.preview("Open ${port.appLabel(pkg)}")
        if (!port.launch(pkg)) return err("LAUNCH_FAILED", "Android refused to open $pkg", true)
        audit.record("screen_open_app", "app=$pkg", Risk.HIGH)
        awaitPackage(pkg) ?: return err("LAUNCH_TIMEOUT", "${port.appLabel(pkg)} did not come to the front", true)
        val (sv, e) = safeView(); if (sv == null) return e!!
        return ToolResult.Ok(buildJsonObject { put("opened", pkg); put("snapshot", sv.id) },
            "Opened ${port.appLabel(pkg)}.\n" + sv.skeleton(cards.get(pkg), config().maxSnapshotChars, policy = policy))
    }

    private suspend fun awaitPackage(pkg: String): ScreenView? {
        val poll = config().waitPollMs.coerceAtLeast(10)
        repeat(((config().defaultWaitMs / poll) + 1).toInt()) {
            port.awaitIdle(config().settleMs)
            val v = current()
            if (v != null && v.pkg == pkg) return v
            delay(poll)
        }
        return null
    }

    // ------------------------------------------------------------------ acting
    private val REF = Regex("^@?(?:(s\\d+):)?@?e(\\d+)$")

    /** Resolves a ref against the snapshot it belongs to; STALE when the live screen changed since. */
    private fun resolveRef(ref: String, live: ScreenView, refView: ScreenView?): Pair<ScreenElement?, ToolResult.Error?> {
        val m = REF.matchEntire(ref.trim()) ?: return null to err("BAD_REF", "\"$ref\" is not an element ref (use @e12 from screen_snapshot)")
        val snapId = m.groupValues[1].ifBlank { null }
        val base = refView ?: return null to err("NO_SNAPSHOT", "take a screen_snapshot first", true)
        if (snapId != null && views.none { it.id == snapId }) return null to err("STALE_REF", "$ref belongs to an old snapshot; take a new screen_snapshot", true)
        if (snapId != null && snapId != base.id) return null to err("STALE_REF", "$ref is from $snapId but the screen is now ${live.id}; take a new screen_snapshot", true)
        if (base.signature != live.signature) return null to err("STALE_REF", "the screen changed since ${base.id}; take a new screen_snapshot (refs are snapshot-scoped)", true)
        val el = live.element(m.groupValues[2].toInt()) ?: return null to err("NOT_FOUND", "$ref is not on screen ${live.id}", true)
        return el to null
    }

    /**
     * screen_act. [key] identifies this exact call (tool + args) for idempotent retries and per-step pre-approval.
     */
    suspend fun act(ref: String, target: String, action: String, text: String, expect: String, key: String, args: JsonObject, tool: String = "screen_act"): ToolResult {
        guard()?.let { return it }
        val (resolvedText, missing) = resolvePlaceholders(text)
        if (missing != null) return err("NO_VALUE", "no extracted value for {{$missing}}; run screen_extract first")
        val expectText = resolvePlaceholders(expect).first
        val now = clock()
        lastAct?.let { p ->
            if (p.key == key && now - p.at <= config().retryWindowMs) {
                criticalPreApproved(tool, args) // drop a sheet approval for this retry: nothing is performed again
                return verifyOnly(p, expectText)
            }
        }
        val refView = latest
        val (live, e) = safeView(); if (live == null) return e!!
        val needsTarget = action in setOf("tap", "long_press", "type")
        val element: ScreenElement? = when {
            ref.isNotBlank() -> resolveRef(ref, live, refView).let { (el, er) -> el ?: return er!! }
            target.isNotBlank() -> {
                val ms = Grounding.find(live, target, if (action == "type") "input" else null)
                when (ms.size) {
                    1 -> ms.single().element
                    0 -> if (needsTarget) return err("NOT_FOUND", "nothing labelled \"${ScreenData.label(target)}\" on ${live.id}", true) else null
                    else -> return err("AMBIGUOUS_TARGET", "\"${ScreenData.label(target)}\" matches ${ms.size} elements: " + ms.take(5).joinToString { live.line(it.element) } + " — use a ref", true)
                }
            }
            needsTarget && action == "type" -> live.elements.filter { it.node.editable && it.node.focused }.singleOrNull()
                ?: live.elements.filter { it.node.editable }.singleOrNull() ?: return err("AMBIGUOUS_TARGET", "say which field to type into", true)
            needsTarget -> return err("NO_TARGET", "give a ref or a target label")
            else -> null
        }
        return actOn(live, element, action, resolvedText, expectText, key, args, tool)
    }

    internal suspend fun actOn(live: ScreenView, element: ScreenElement?, action: String, text: String, expect: String, key: String, args: JsonObject, tool: String): ToolResult {
        val n = element?.node
        if (n != null && !n.enabled) return err("DISABLED_TARGET", "that control is disabled")
        if (action == "type") {
            if (n == null || !n.editable) return err("NOT_EDITABLE", "that element is not a text field")
            policy.needsUserInput(n)?.let { reason ->
                audit.record("take over requested", "app=${live.pkg} $reason", Risk.HIGH)
                return if (host.takeOver(reason)) ToolResult.Ok(buildJsonObject { put("done_by_user", true); put("subgoal", subgoal(true, "")) }, "You filled that field yourself.")
                else err("TAKEN_OVER", "stopped: $reason")
            }
        }
        val d = desc(action, element, text)
        val irreversible = action == "enter" || ((action == "tap" || action == "long_press") && n != null && policy.isIrreversible(n))
        if (irreversible) {
            val pre = criticalPreApproved(tool, args)
            if (!pre) {
                val ok = host.confirm("$d in ${port.appLabel(live.pkg)}", "this may send, submit, pay or delete something")
                audit.record(if (ok) "confirmed step" else "declined step", "app=${live.pkg} $d", Risk.CRITICAL)
                if (!ok) return err("DECLINED", "you declined \"$d\". Nothing was sent.")
            }
        }
        if (kill.engaged()) return err("STOPPED", "stopped by you")
        host.preview(d)
        val ok = when (action) {
            "tap" -> port.perform(n!!, NodeOp.CLICK)
            "long_press" -> port.perform(n!!, NodeOp.LONG_CLICK)
            "type" -> port.perform(n!!, NodeOp.SET_TEXT, text) || (port.setClipboard(text) && port.perform(n, NodeOp.FOCUS) && port.perform(n, NodeOp.PASTE, text))
            "enter" -> (n ?: live.elements.firstOrNull { it.node.focused && it.node.editable }?.node)?.let { port.perform(it, NodeOp.IME_ENTER) } ?: false
            "scroll_down", "scroll_up" -> (n ?: live.elements.firstOrNull { it.node.scrollable }?.node)?.let {
                port.perform(it, if (action == "scroll_up") NodeOp.SCROLL_BACKWARD else NodeOp.SCROLL_FORWARD) } ?: false
            "back" -> port.global(GlobalOp.BACK)
            "home" -> port.global(GlobalOp.HOME)
            else -> return err("BAD_ACTION", "unknown action $action")
        }
        audit.record(tool + " " + action, "app=${live.pkg} target=\"${n?.label?.take(40).orEmpty()}\" ${if (ok) "ok" else "failed"}", if (irreversible) Risk.CRITICAL else Risk.HIGH)
        if (!ok) return err("ACTION_FAILED", "Android refused \"$d\" (element gone or not actionable); take a new snapshot", true)
        port.awaitIdle(config().settleMs)
        return result(d, live, expect, reverified = false, key = key, element = element)
    }

    private suspend fun verifyOnly(p: LastAct, expect: String): ToolResult = result(p.desc, p.before, expect, reverified = true, key = p.key, element = p.element, at = p.at)

    /**
     * Builds the diff + subgoal result. When the expected outcome was NOT seen, the call is remembered: the Verifier
     * will retry the node with the same arguments, and that retry only re-checks the screen (the tap/send already
     * happened — repeating it could send twice).
     */
    private suspend fun result(d: String, before: ScreenView, expect: String, reverified: Boolean, key: String, element: ScreenElement?, at: Long = clock()): ToolResult {
        var after = current() ?: return serviceOff
        var met: Boolean? = null
        if (expect.isNotBlank()) {
            val (ok, v) = waitFor(expect, true, config().defaultWaitMs)
            met = ok; if (v != null) after = v
            if (ok && before.pkg == after.pkg && !reverified) learn(before, d, expect)
        }
        lastAct = if (met == false) LastAct(key, at, d, before, element) else null
        val diff = ScreenDiff.between(before, after, policy)
        val note = if (after.pkg != before.pkg) when (val av = policy.appVerdict(after.pkg)) {
            is SafetyVerdict.Block -> "\nWARNING: the step opened ${after.pkg}, which is blocked (${av.reason}). I will not act there."
            else -> "\nThe step switched to ${port.appLabel(after.pkg)}; operating it needs your OK."
        } else ""
        val sg = met?.let { subgoal(it, if (it) "" else "expected \"${ScreenData.label(expect)}\" after \"$d\" but it is not on screen") }
        return ToolResult.Ok(buildJsonObject {
            put("done", d); put("snapshot", after.id); put("package", after.pkg); put("reverified", reverified)
            putJsonObject("diff") { put("appeared", diff.appeared.size); put("gone", diff.disappeared.size); put("changed", diff.changed.size); diff.appChanged?.let { put("app", it) } }
            if (sg != null) put("subgoal", sg)
        }, (if (reverified) "Re-checked (not repeated): " else "Did: ") + d + " → screen ${after.id}\n" + ScreenData.wrap(after.pkg, diff.render()) + note +
            (met?.let { if (it) "\nExpected outcome seen: \"${ScreenData.label(expect)}\"." else "\nExpected outcome NOT seen: \"${ScreenData.label(expect)}\"." } ?: ""))
    }

    private fun learn(before: ScreenView, d: String, expect: String) {
        runCatching { cards.learn(before.pkg, "$d → shows \"${expect.take(40)}\"") }
    }

    // ------------------------------------------------------------------ cross-app transfer
    suspend fun transfer(text: String, toApp: String, target: String, method: String, key: String): ToolResult {
        guard()?.let { return it }
        val (value, missing) = resolvePlaceholders(text)
        if (missing != null) return err("NO_VALUE", "no extracted value for {{$missing}}; run screen_extract in the source app first")
        if (value.isBlank()) return err("EMPTY", "nothing to transfer")
        val pkg = port.resolveApp(toApp) ?: return err("NOT_INSTALLED", "no launchable app matches \"$toApp\"")
        (policy.appVerdict(pkg) as? SafetyVerdict.Block)?.let { audit.record("refused transfer", "app=$pkg ${it.reason}", Risk.HIGH); return err("BLOCKED", it.reason) }
        if (method == "share") {
            host.preview("Share ${value.length} chars to ${port.appLabel(pkg)}")
            val ok = port.share(value, pkg)
            audit.record("screen_transfer share", "to=$pkg chars=${value.length} ${if (ok) "ok" else "failed"}", Risk.CRITICAL)
            return if (ok) ToolResult.Ok(buildJsonObject { put("method", "share"); put("to", pkg); put("subgoal", subgoal(true, "")) },
                "Shared ${value.length} characters to ${port.appLabel(pkg)}. It shows its own composer; nothing is sent until you (or a confirmed step) press Send.")
            else err("SHARE_FAILED", "${port.appLabel(pkg)} does not accept shared text", false)
        }
        if (currentPackage() != pkg || current()?.pkg != pkg) {
            host.preview("Switch to ${port.appLabel(pkg)}")
            if (!port.launch(pkg)) return err("LAUNCH_FAILED", "could not open $pkg", true)
            audit.record("screen_open_app", "app=$pkg (transfer)", Risk.HIGH)
            awaitPackage(pkg) ?: return err("LAUNCH_TIMEOUT", "${port.appLabel(pkg)} did not come to the front", true)
        }
        val (live, e) = safeView(); if (live == null) return e!!
        val field = if (target.isBlank()) live.elements.filter { it.node.editable && it.node.focused }.singleOrNull() ?: live.elements.filter { it.node.editable }.singleOrNull()
            else Grounding.find(live, target, "input").singleOrNull()?.element ?: Grounding.unique(live, target)?.takeIf { it.node.editable }
        if (field == null) return err("NOT_FOUND", "no single text field ${if (target.isBlank()) "" else "\"${ScreenData.label(target)}\" "}in ${port.appLabel(pkg)}", true)
        policy.needsUserInput(field.node)?.let { return err("BLOCKED", "I never transfer into this field: $it") }
        if (field.node.text.contains(value)) return transferOk(live, field, "already there", value)
        host.preview("Put ${value.length} chars into \"${field.node.hint.ifBlank { field.node.label }.take(30)}\" in ${port.appLabel(pkg)}")
        var used = "type"
        var ok = method != "paste" && port.perform(field.node, NodeOp.SET_TEXT, value) && fieldHas(field, value)
        if (!ok && method != "type") {
            used = "paste"
            ok = port.setClipboard(value) && port.perform(field.node, NodeOp.FOCUS) && port.perform(field.node, NodeOp.PASTE, value) && fieldHas(field, value)
        }
        audit.record("screen_transfer $used", "to=$pkg chars=${value.length} ${if (ok) "ok" else "failed"}", Risk.HIGH)
        lastAct = null
        return if (ok) transferOk(current() ?: live, field, used, value) else err("TRANSFER_FAILED", "could not put the text into the field (typing and clipboard paste both failed)", true)
    }

    private suspend fun fieldHas(field: ScreenElement, value: String): Boolean {
        port.awaitIdle(config().settleMs)
        val v = current() ?: return false
        return v.elements.any { it.node.editable && it.node.path == field.node.path && it.node.text.contains(value) }
    }

    private fun transferOk(v: ScreenView, field: ScreenElement, used: String, value: String) = ToolResult.Ok(buildJsonObject {
        put("method", used); put("to", v.pkg); put("snapshot", v.id); put("chars", value.length); put("subgoal", subgoal(true, ""))
    }, "Put ${value.length} characters into \"${ScreenData.label(field.node.hint.ifBlank { field.nearbyLabel.ifBlank { field.node.label } }, 40)}\" in ${port.appLabel(v.pkg)} ($used). " +
        "Not sent: sending is a separate step that you confirm.")

    // ------------------------------------------------------------------ one model-chosen step (grounded early exit)
    suspend fun doStep(instruction: String, expect: String, key: String, args: JsonObject): ToolResult {
        guard()?.let { return it }
        val model = llm() ?: return err("NO_MODEL", "load a chat model so I can choose the step, or use screen_act with a target")
        val (live, e) = safeView(); if (live == null) return e!!
        var decision: GroundedExit.Decision? = null
        val user = "Step: ${instruction.take(200)}\n" + live.full(cards.get(live.pkg), 3000, policy) + "\nAction:"
        val out = model.stream(GroundedExit.SYSTEM, user, 40) { partial ->
            val d = GroundedExit.check(if (partial.trimStart().startsWith("Action:", true)) partial else "Action: $partial", live)
            if (d != null) { decision = d; true } else false
        }
        val d = decision ?: GroundedExit.parse(if (out.trimStart().startsWith("Action:", true)) out else "Action: $out", live)
            ?: return err("NO_ACTION", "the model did not name a valid step: \"${out.take(80)}\"", true)
        if (d.action == "done") return ToolResult.Ok(buildJsonObject { put("done", true); put("early_exit", d.early) }, "The model says this step is already done.")
        if ((d.action == "tap" || d.action == "long_press" || d.action == "type") && d.element == null) {
            val c = Grounding.find(live, d.label)
            return err(if (c.isEmpty()) "NOT_FOUND" else "AMBIGUOUS_TARGET", "\"${ScreenData.label(d.label)}\" matches ${c.size} elements; use screen_act with a ref", true)
        }
        val r = actOn(live, d.element, d.action, d.text, resolvePlaceholders(expect).first, key, args, "screen_do")
        return if (r is ToolResult.Ok) ToolResult.Ok(JsonObject(r.json + ("early_exit" to kotlinx.serialization.json.JsonPrimitive(d.early))), r.summary + if (d.early) "\n(grounded early exit)" else "") else r
    }
}
