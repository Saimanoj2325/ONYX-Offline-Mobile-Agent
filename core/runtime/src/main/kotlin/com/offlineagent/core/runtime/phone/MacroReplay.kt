package com.offlineagent.core.runtime.phone

import kotlin.math.max

/** Finds a recorded step's target in a live snapshot (kept for older callers; Phase 6.2 uses [TargetMatcher]). */
object MacroMatcher {
    fun find(sel: NodeSelector, s: ScreenSnapshot): ScreenElement? = TargetMatcher.match(sel, s).element

    fun fill(value: String, params: Map<String, String>): String =
        Regex("\\{\\{([A-Za-z0-9_ -]+)}}").replace(value) { m -> params[m.groupValues[1].trim()] ?: m.value }
}

data class MacroReplayConfig(
    /** How long to wait for a step's screen / target (stretched to 2x the user's own pause, max 15 s). */
    val screenTimeoutMs: Long = 8_000,
    /** How long to wait for a step's effect (new screen, typed text). */
    val effectTimeoutMs: Long = 3_000,
    val pollMs: Long = 300,
    /** Scroll-to-find: this many scrolls down, then twice as many up. */
    val maxScrolls: Int = 8,
    val highlightMs: Long = 900,
    val settleMs: Long = 250,
)

/** What happened at one step (for the debug report). Contains no screen text except the macro's own labels and typing. */
class StepTrace(val index: Int, val description: String) {
    var outcome: String = "not run"
    var strategy: MatchStrategy? = null
    val attempts = ArrayList<MatchAttempt>()
    var scrolls = 0
    var expected = ""
    var seen = ""
    var ms = 0L
    val notes = ArrayList<String>()
}

/**
 * The "Copy debug report" text of a macro run: step, strategies tried with candidate counts, package/window and timing.
 * Screens are described by package, activity, view ids and counts; other people's names are masked ("Mo**"); the only
 * text included is what the macro itself types or targets.
 */
class MacroRunReport(
    val macroName: String, val macroId: String, val version: Int, val stepCount: Int, val startPackage: String,
    val testRun: Boolean, val unattended: Boolean, val startedAt: Long,
) {
    val steps = ArrayList<StepTrace>()
    var ok = false
    var failedStep: Int? = null
    var reason = ""
    var durationMs = 0L
    var deepLink = "not set up"

    fun render(appVersion: String = ""): String = buildString {
        appendLine("ONYX macro debug report" + if (appVersion.isNotBlank()) " ($appVersion)" else "")
        appendLine("Macro: \"$macroName\" ($macroId) · $stepCount steps · format v$version · starts in ${startPackage.ifBlank { "?" }}")
        appendLine("Run: " + (if (ok) "finished" else "FAILED at step ${failedStep ?: "?"} of $stepCount") +
            " · ${durationMs} ms · ${if (testRun) "test run" else if (unattended) "scheduled (unattended)" else "normal run"} · deep link: $deepLink")
        if (reason.isNotBlank()) appendLine("Reason: $reason")
        appendLine("Steps:")
        for (t in steps) {
            appendLine(" ${t.index}. ${t.description} — ${t.outcome} (${t.ms} ms)" +
                (t.strategy?.let { " · matched by ${it.label}" } ?: "") + (if (t.scrolls > 0) " · ${t.scrolls} scrolls" else ""))
            if (t.attempts.isNotEmpty() && (t.strategy == null || t.attempts.size > 1 || t.outcome != "ok"))
                appendLine("    strategies: " + t.attempts.joinToString(" · ") { a -> "${a.strategy.label}: ${a.candidates}" + if (a.note.isNotBlank()) " (${a.note})" else "" })
            val near = t.attempts.flatMap { it.nearMisses }.distinct().take(3)
            if (near.isNotEmpty() && t.outcome != "ok") appendLine("    closest labels: " + near.joinToString { "${it.first} (${it.second})" })
            if (t.expected.isNotBlank()) appendLine("    expected: ${t.expected}")
            if (t.seen.isNotBlank()) appendLine("    seen: ${t.seen}")
            t.notes.forEach { appendLine("    note: $it") }
        }
    }.trimEnd()
}

/**
 * Replays a macro through the same [ScreenDriver] + [AutomationHost] + [AutomationPolicy] as the screen agent.
 *
 * Phase 6.2: replay by MATCHING, not coordinates. Before each step it waits for the expected screen (fingerprint
 * similarity) and the target ([TargetMatcher] strategies), scrolls a list both ways to find an off-screen row, acts,
 * then verifies the effect (the recorded "after" screen, a window change, or the typed text). When nothing happened
 * it retries ONCE with the next matching strategy; then it stops with a readable reason (expected vs seen).
 * Irreversible steps (send/pay/submit/delete, Enter) always ask; unattended (scheduled) runs stop before them.
 * Idea sources: Mika's ActionEffect / UntilWait / BatchRunner (Apache-2.0, see NOTICE), re-written for ONYX.
 */
class MacroPlayer(
    private val driver: ScreenDriver,
    private val host: AutomationHost,
    private val policy: AutomationPolicy,
    private val log: AutomationLog,
    findTimeoutMillis: Long = 8000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    /** Phase 6.1: the launcher (an OPEN_APP of it replays as Home). */
    private val isHome: (String) -> Boolean = { false },
    /** Why [pkg] cannot be opened (not installed, no launch screen, hidden by package visibility, blocked), or null. */
    private val openProblem: (String) -> String? = { null },
    private val appLabel: (String) -> String = { it },
    /** Phase 6.2: opens a deep link (uri, package); false when nothing handled it. */
    private val openLink: suspend (String, String) -> Boolean = { _, _ -> false },
    config: MacroReplayConfig? = null,
) {
    private val cfg = config ?: MacroReplayConfig(screenTimeoutMs = findTimeoutMillis)
    /** The last run's report ("Copy debug report"). */
    var lastReport: MacroRunReport? = null
        private set

    private lateinit var rep: MacroRunReport
    private var params: Map<String, String> = emptyMap()
    private var unattended = false
    private var testRun = false
    private var macroName = ""

    private fun polls(timeout: Long) = max(1L, timeout / cfg.pollMs.coerceAtLeast(1)).toInt()
    private fun fp(s: ScreenSnapshot?) = ScreenFingerprint.of(s)

    suspend fun play(m: Macro, params: Map<String, String>, unattended: Boolean = false, testRun: Boolean = false, useDeepLink: Boolean = true): String {
        val t0 = clock()
        rep = MacroRunReport(m.name, m.id, m.version, m.steps.size, m.startPackage, testRun, unattended, t0)
        lastReport = rep
        this.params = params; this.unattended = unattended; this.testRun = testRun; macroName = m.name
        val missing = m.params.filter { params[it].isNullOrBlank() }
        if (missing.isNotEmpty()) return "Missing values for: ${missing.joinToString()}".also { rep.reason = it; host.finished(it) }

        var start = 0
        val dl = m.deepLink
        if (dl != null) rep.deepLink = if (!dl.enabled || dl.uri.isBlank()) "available, off" else "on"
        if (useDeepLink && dl != null && dl.enabled && dl.uri.isNotBlank() && dl.skipSteps in 1..m.steps.size) {
            val opened = runCatching { openLink(MacroMatcher.fill(dl.uri, params), dl.packageName) }.getOrDefault(false)
            val target = m.steps.getOrNull(dl.skipSteps)
            val landed = opened && awaitPackage(dl.packageName) && (target?.before == null || awaitSimilar(target.before, 0.3))
            if (landed) { start = dl.skipSteps; rep.deepLink = "used (skipped the first ${dl.skipSteps} steps)" }
            else rep.deepLink = if (opened) "opened but the expected screen did not appear — replayed every step" else "did not open — replayed every step"
        }

        for (i in start until m.steps.size) {
            val st = m.steps[i]
            val tr = StepTrace(i + 1, MacroDescribe.step(st, appLabel))
            rep.steps += tr
            val ts = clock()
            val why = try { runStep(i + 1, st, tr) } finally { tr.ms = clock() - ts }
            if (why != null) {
                if (tr.outcome == "not run" || tr.outcome == "ok") tr.outcome = "failed"
                return fail(why, i + 1, t0)
            }
            if (tr.outcome == "not run") tr.outcome = "ok"
            sleep(cfg.settleMs)
        }
        rep.ok = true
        rep.durationMs = clock() - t0
        val warn = rep.steps.count { it.notes.any { n -> n.startsWith("screen differs") } }
        return ("Macro \"${m.name}\" finished (${m.steps.size} steps)." + if (warn > 0) " $warn screen(s) looked different from the recording — check the result." else "")
            .also { host.finished(it) }
    }

    private fun fail(why: String, step: Int, t0: Long): String {
        rep.ok = false; rep.failedStep = step; rep.reason = why; rep.durationMs = clock() - t0
        return "Macro stopped at step $step: $why.".also { host.finished(it) }
    }

    // ------------------------------------------------------------------------------------------------ waiting

    private suspend fun awaitPackage(pkg: String): Boolean {
        repeat(polls(cfg.screenTimeoutMs)) { val s = driver.snapshot(); if (s?.packageName == pkg) return true; sleep(cfg.pollMs) }
        return false
    }

    private suspend fun awaitSimilar(want: ScreenFingerprint, min: Double): Boolean {
        repeat(polls(cfg.screenTimeoutMs)) { val s = driver.snapshot(); if (want.similarity(fp(s)) >= min) return true; sleep(cfg.pollMs) }
        return false
    }

    private fun expectedText(st: MacroStep): String {
        val b = st.before ?: return st.selector?.let { "a screen of ${appLabel(it.packageName)} with ${q(it.displayName)}" }.orEmpty()
        return "${appLabel(b.packageName)}" + (if (b.activity.isNotBlank()) " / ${b.activity.substringAfterLast('.')}" else "") +
            (if (b.ids.isNotEmpty()) " (ids like ${b.ids.take(5).joinToString(", ")})" else "")
    }

    private fun seenText(st: MacroStep, s: ScreenSnapshot?): String {
        s ?: return "nothing (the screen could not be read)"
        val now = fp(s)!!
        val b = st.before
        val shared = if (b != null && b.ids.isNotEmpty()) " · ${b.ids.count { it in now.ids }} of ${b.ids.size} expected ids present · similarity ${"%.2f".format(b.similarity(now))}" else ""
        return "${appLabel(s.packageName)}" + (if (s.activity.isNotBlank()) " / ${s.activity.substringAfterLast('.')}" else "") + " · ${s.elements.size} elements$shared"
    }

    private fun q(s: String) = "\"" + s.replace("\n", " ").take(40) + "\""

    /** Waits for the expected screen + target; scrolls a list both ways when the row is off screen. */
    private suspend fun awaitTarget(st: MacroStep, sel: NodeSelector, tr: StepTrace): Pair<ScreenSnapshot?, MatchResult?> {
        val timeout = (max(cfg.screenTimeoutMs, st.waitMillis * 2)).coerceAtMost(15_000)
        val n = polls(timeout)
        var scrolled = false
        var last: ScreenSnapshot? = null
        var lastRes: MatchResult? = null
        for (k in 0 until n) {
            val s = driver.snapshot()
            if (s == null) { sleep(cfg.pollMs); continue }
            last = s
            val res = TargetMatcher.match(sel, s, params, allowBounds = !st.irreversible)
            lastRes = res
            val sim = st.before?.similarity(fp(s))
            // "Wait for the expected screen": a match on a clearly different screen is accepted only after a third of the timeout.
            val screenOk = sim == null || sim >= 0.3 || k >= n / 3
            if (res.element != null && screenOk) return s to res
            val filledLabel = MacroMatcher.fill(sel.listItemLabel, params)
            if (res.element == null && !scrolled && (sel.inList || filledLabel.isNotBlank()) && s.packageName == sel.packageName && (sim == null || sim >= 0.25) &&
                s.elements.any { it.node.scrollable }) {
                scrolled = true
                val found = scrollToFind(st, sel, s, tr)
                if (found != null) return found
                continue
            }
            sleep(cfg.pollMs)
        }
        return last to lastRes
    }

    private fun container(sel: NodeSelector, s: ScreenSnapshot): ScreenElement? =
        s.elements.firstOrNull { sel.listId.isNotBlank() && it.node.scrollable && it.node.viewId == sel.listId }
            ?: s.elements.filter { it.node.scrollable }.maxByOrNull { it.node.bounds.width.toLong() * it.node.bounds.height }

    private suspend fun scrollToFind(st: MacroStep, sel: NodeSelector, s0: ScreenSnapshot, tr: StepTrace): Pair<ScreenSnapshot, MatchResult>? {
        var cur = s0
        for (dir in listOf("down", "up")) {
            var prev = fp(cur)
            val limit = if (dir == "down") cfg.maxScrolls else cfg.maxScrolls * 2
            for (k in 0 until limit) {
                val c = container(sel, cur) ?: return null
                val ok = driver.perform(ScreenAction.Scroll(dir, c.id), cur)
                tr.scrolls++
                sleep(cfg.pollMs)
                cur = driver.snapshot() ?: return null
                val res = TargetMatcher.match(sel, cur, params, allowBounds = !st.irreversible)
                if (res.element != null) { tr.notes += "found after scrolling $dir"; return cur to res }
                val now = fp(cur)
                if (!ok || (prev?.similarity(now) ?: 0.0) >= 0.999) break // end of the list
                prev = now
            }
        }
        return null
    }

    private data class Effect(val ok: Boolean, val unchanged: Boolean, val note: String = "")

    private suspend fun awaitEffect(st: MacroStep, before: ScreenSnapshot, el: ScreenElement, typed: String): Effect {
        val beforeFp = fp(before)!!
        val n = polls(cfg.effectTimeoutMs)
        val rb = st.before; val ra = st.after
        val expectChange = rb != null && ra != null && ra.similarity(rb) < 0.95
        for (k in 0 until n) {
            val now = driver.snapshot()
            if (now != null) {
                if (st.kind == MacroStepKind.TYPE) {
                    val field = now.elements.firstOrNull { it.node.editable && it.node.path == el.node.path && el.node.path.isNotEmpty() }
                        ?: TargetMatcher.match(st.selector ?: NodeSelector(), now, params).element
                    if (field == null || field.node.text.contains(typed) || typed.isBlank()) return Effect(true, false)
                } else {
                    val nowFp = fp(now)!!
                    val changed = now.packageName != before.packageName || now.activity != before.activity || beforeFp.similarity(nowFp) < 0.98
                    when {
                        rb == null || ra == null -> if (changed) return Effect(true, false) else if (k == n - 1) return Effect(true, true, "no recorded result to compare")
                        !expectChange -> return Effect(true, !changed)
                        changed -> {
                            val sim = ra.similarity(nowFp)
                            if (sim >= 0.4 || (now.packageName != before.packageName && now.packageName == ra.packageName)) return Effect(true, false)
                            if (k == n - 1) return Effect(false, false, "screen differs from the recording after this step (similarity ${"%.2f".format(sim)})")
                        }
                    }
                }
            }
            sleep(cfg.pollMs)
        }
        return Effect(false, true)
    }

    // ------------------------------------------------------------------------------------------------ acting

    /** Policy + approvals. Null = go ahead; otherwise the reason to stop. */
    private suspend fun gate(action: ScreenAction, s: ScreenSnapshot, step: Int, marked: Boolean): String? {
        var v = policy.check(action, s, params.values.toSet())
        if (marked && v is AutomationPolicy.Verdict.Allow) v = AutomationPolicy.Verdict.NeedsApproval("this step was recorded as one that may send, submit, pay or delete")
        when (v) {
            is AutomationPolicy.Verdict.Stop -> return v.reason
            is AutomationPolicy.Verdict.NeedsApproval -> {
                if (unattended) return "step \"${action.describe(s)}\" needs your approval — open the app to finish"
                if (!host.approve(step, action.describe(s), v.reason)) return "you declined \"${action.describe(s)}\""
            }
            AutomationPolicy.Verdict.Allow -> host.preview(step, action.describe(s))
        }
        return null
    }

    private fun logIt(step: Int, s: ScreenSnapshot, action: ScreenAction, ok: Boolean) =
        log.add(AutomationLogEntry(clock(), step, s.packageName, action.describe(s), if (ok) "ok (macro $macroName)" else "failed (macro $macroName)"))

    private fun failText(action: ScreenAction, s: ScreenSnapshot) = when (action) {
        is ScreenAction.OpenApp -> "could not open ${appLabel(action.app)} (${action.app}) — Android has no launch screen for it, or it is hidden from ONYX"
        is ScreenAction.Tap -> "tapping \"${action.describe(s).removePrefix("Tap ").take(40)}\" did nothing (the control is gone or not tappable)"
        is ScreenAction.LongPress -> "long-pressing that control did nothing"
        is ScreenAction.Type -> "could not type into that field"
        is ScreenAction.Enter -> "the keyboard's Enter key could not be pressed there"
        ScreenAction.Home -> "could not go to the home screen"
        ScreenAction.Back -> "could not press Back"
        else -> "${action.describe(s)} failed"
    }

    private suspend fun simple(step: Int, action: ScreenAction, tr: StepTrace): String? {
        val s = driver.snapshot() ?: return "cannot read the screen"
        gate(action, s, step, false)?.let { return it }
        val ok = driver.perform(action, s)
        logIt(step, s, action, ok)
        if (!ok) return failText(action, s)
        tr.outcome = "ok"
        return null
    }

    private suspend fun runStep(step: Int, st: MacroStep, tr: StepTrace): String? {
        when (st.kind) {
            MacroStepKind.WAIT -> { sleep(st.waitMillis.coerceIn(0, 10_000)); tr.outcome = "ok"; return null }
            MacroStepKind.OPEN_APP -> {
                if (st.value.isBlank()) return "step $step has no app to open"
                if (isHome(st.value)) return simple(step, ScreenAction.Home, tr)
                openProblem(st.value)?.let { why -> return "can't open ${appLabel(st.value)} (${st.value}): $why" }
                val r = simple(step, ScreenAction.OpenApp(st.value), tr)
                if (r == null && !awaitPackage(st.value)) tr.notes += "${appLabel(st.value)} did not come to the front within ${cfg.screenTimeoutMs} ms"
                return r
            }
            MacroStepKind.BACK -> return simple(step, ScreenAction.Back, tr)
            MacroStepKind.HOME -> return simple(step, ScreenAction.Home, tr)
            MacroStepKind.SCROLL -> {
                // Best effort: a later tap scrolls to find its own target anyway.
                val s = driver.snapshot() ?: return "cannot read the screen"
                val c = container(st.selector ?: NodeSelector(), s)
                val ok = driver.perform(ScreenAction.Scroll(st.value.ifBlank { "down" }, c?.id ?: 0), s)
                logIt(step, s, ScreenAction.Scroll(st.value.ifBlank { "down" }), ok)
                tr.outcome = if (ok) "ok" else "skipped (nothing to scroll)"
                return null
            }
            MacroStepKind.TAP, MacroStepKind.LONG_PRESS, MacroStepKind.TYPE, MacroStepKind.IME_ENTER -> return target(step, st, tr)
        }
    }

    private fun build(st: MacroStep, el: ScreenElement): ScreenAction = when (st.kind) {
        MacroStepKind.TAP -> ScreenAction.Tap(el.id)
        MacroStepKind.LONG_PRESS -> ScreenAction.LongPress(el.id)
        MacroStepKind.TYPE -> ScreenAction.Type(el.id, MacroMatcher.fill(st.value, params), submit = st.submit)
        else -> ScreenAction.Enter(el.id)
    }

    private suspend fun target(step: Int, st: MacroStep, tr: StepTrace): String? {
        val sel = st.selector
        if (sel == null && st.kind != MacroStepKind.IME_ENTER) return "step $step has no target"
        var snap: ScreenSnapshot?
        var res: MatchResult?
        if (sel == null) {
            snap = driver.snapshot()
            val f = snap?.elements?.firstOrNull { it.node.editable && it.node.focused } ?: snap?.elements?.singleOrNull { it.node.editable }
            res = MatchResult(f, null, emptyList())
        } else {
            val r = awaitTarget(st, sel, tr)
            snap = r.first; res = r.second
        }
        res?.attempts?.let { tr.attempts.addAll(it) }
        tr.strategy = res?.strategy
        val el = res?.element
        if (el == null || snap == null) {
            tr.outcome = "not found"
            tr.expected = expectedText(st); tr.seen = seenText(st, snap)
            val what = sel?.displayName?.let(::q)?.let { MacroMatcher.fill(it, params) } ?: "a text field to press Enter in"
            return "could not find $what on screen (step $step) — expected ${tr.expected.ifBlank { "the recorded screen" }}, but saw ${tr.seen}"
        }
        val marked = st.irreversible
        val typed = if (st.kind == MacroStepKind.TYPE) MacroMatcher.fill(st.value, params) else ""
        var action = build(st, el)
        gate(action, snap, step, marked)?.let { tr.outcome = "stopped"; return it }
        if (testRun) { host.highlight(step, el.node.bounds, action.describe(snap)); sleep(cfg.highlightMs) }
        var used = el
        var ok = driver.perform(action, snap)
        logIt(step, snap, action, ok)
        if (!ok) {
            // The node refused: try the next strategy's match once.
            val retry = retryMatch(st, sel, el, res?.strategy) ?: return failText(action, snap).also { tr.outcome = "failed" }
            used = retry.second; action = build(st, used)
            if (testRun) { host.highlight(step, used.node.bounds, action.describe(retry.first)); sleep(cfg.highlightMs) }
            ok = driver.perform(action, retry.first)
            logIt(step, retry.first, action, ok)
            if (!ok) { tr.outcome = "failed"; return failText(action, retry.first) }
            snap = retry.first
            tr.notes += "first match refused the action; used ${tr.strategy?.label ?: "the next match"}"
        }
        var eff = awaitEffect(st, snap, used, typed)
        if (!eff.ok && st.kind == MacroStepKind.TYPE) {
            val again = driver.snapshot()
            if (again != null) {
                val f = again.elements.firstOrNull { it.node.editable && it.node.path == used.node.path } ?: used
                driver.perform(ScreenAction.Type(f.id, typed), again)
                eff = awaitEffect(st, again, f, typed)
            }
            if (!eff.ok) {
                val shown = driver.snapshot()?.elements?.firstOrNull { it.node.editable && it.node.path == used.node.path }?.node?.text.orEmpty()
                // Some apps never report a field's text: an empty field is noted, other text in it is a failure.
                if (shown.isNotBlank()) { tr.outcome = "failed"; tr.expected = "the field to contain the typed text"; return "the text did not appear in ${q(sel?.displayName ?: "the field")} (it shows something else)" }
                tr.notes += "the typed text is not visible in the field (the app may hide it)"
                eff = Effect(true, false)
            }
        }
        if (!eff.ok && eff.unchanged) {
            val risky = marked || st.kind == MacroStepKind.IME_ENTER || st.submit || policy.isIrreversibleTarget(used.node)
            tr.expected = expectedAfter(st); tr.seen = seenText(st, driver.snapshot())
            if (risky) { tr.outcome = "no effect"; return "${q(sel?.displayName ?: "that step")} did not show the expected result, and I did not retry because it may send or submit something — expected ${tr.expected}" }
            val retry = retryMatch(st, sel, used, tr.strategy)
            if (retry != null) {
                val a2 = build(st, retry.second)
                gate(a2, retry.first, step, marked)?.let { tr.outcome = "stopped"; return it }
                if (testRun) { host.highlight(step, retry.second.node.bounds, a2.describe(retry.first)); sleep(cfg.highlightMs) }
                val ok2 = driver.perform(a2, retry.first)
                logIt(step, retry.first, a2, ok2)
                tr.notes += "nothing happened; retried with the next strategy"
                if (ok2) eff = awaitEffect(st, retry.first, retry.second, typed)
            }
            if (!eff.ok) {
                tr.outcome = "no effect"; tr.seen = seenText(st, driver.snapshot())
                return "tapping ${q(MacroMatcher.fill(sel?.displayName ?: "", params))} changed nothing — expected ${tr.expected}, but saw ${tr.seen}"
            }
        }
        if (!eff.ok) tr.notes += eff.note.ifBlank { "screen differs from the recording after this step" }
        else if (eff.note.isNotBlank()) tr.notes += eff.note
        tr.outcome = "ok"
        return null
    }

    private fun expectedAfter(st: MacroStep): String {
        val a = st.after ?: return "a visible change"
        return appLabel(a.packageName) + (if (a.activity.isNotBlank()) " / ${a.activity.substringAfterLast('.')}" else "") +
            (if (a.ids.isNotEmpty()) " (ids like ${a.ids.take(5).joinToString(", ")})" else "")
    }

    /** Next strategy after [used], excluding the element that did not work. */
    private suspend fun retryMatch(st: MacroStep, sel: NodeSelector?, el: ScreenElement, used: MatchStrategy?): Pair<ScreenSnapshot, ScreenElement>? {
        sel ?: return null
        val s = driver.snapshot() ?: return null
        val next = used?.let { u -> TargetMatcher.ORDER.getOrNull(TargetMatcher.ORDER.indexOf(u) + 1) } ?: return null
        val r = TargetMatcher.match(sel, s, params, exclude = setOf(el.node.path), from = next, allowBounds = !st.irreversible)
        val found = r.element?.takeIf { it.node.path != el.node.path || it.node.path.isEmpty() && it.id != el.id } ?: return null
        lastReport?.steps?.lastOrNull()?.let { t -> t.attempts.addAll(r.attempts); t.strategy = r.strategy }
        return s to found
    }
}
