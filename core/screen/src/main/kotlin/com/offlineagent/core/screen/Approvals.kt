package com.offlineagent.core.screen

import com.offlineagent.core.agents.ApprovalGate
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Risk
import kotlinx.serialization.json.JsonObject

/**
 * Scoped approvals for screen tools (research A5.6): instead of asking 20 times, the user approves
 * "operate <App> for this task (≤ N steps)" once per app per task. Reads (MEDIUM) need a READ scope, actions (HIGH)
 * an OPERATE scope — a scope is only granted by an explicit answer on the sheet for that level. CRITICAL steps
 * (send/submit/pay/delete, share) ALWAYS ask, every time, and are never covered by a scope. A new app appearing
 * mid-task is not covered by the old app's scope, so ONYX pauses and asks. Blocked apps are refused without asking.
 *
 * Wraps the app's normal [ApprovalGate] (the ApprovalSheet); non-screen tools pass through unchanged.
 */
class ScreenApprovals(
    private val targetPackage: suspend (tool: String, args: JsonObject) -> String?,
    private val policy: ScreenSafetyPolicy,
    private val audit: ScreenAudit?,
    private val appLabel: (String) -> String = { it },
    private val stepsPerScope: () -> Int = { 30 },
    private val clock: () -> Long = System::currentTimeMillis,
    private val tokenTtlMs: Long = 120_000,
) {
    enum class Level { READ, OPERATE }
    data class Scope(val pkg: String, val level: Level, val steps: Int)

    private val scopes = LinkedHashMap<String, MutableMap<String, Scope>>()
    /** One-shot per-step approvals → when they were given (they expire after [tokenTtlMs]). */
    private val criticalTokens = HashMap<String, Long>()
    private val lock = Any()

    fun scope(runId: String, pkg: String): Scope? = synchronized(lock) { scopes[runId]?.get(pkg) }

    /** One-shot: the user approved exactly this CRITICAL call on the sheet; the tool consumes it instead of asking twice. */
    fun consumeCritical(tool: String, args: JsonObject): Boolean = synchronized(lock) {
        val at = criticalTokens.remove(key(tool, args)) ?: return false
        clock() - at <= tokenTtlMs
    }

    private fun key(tool: String, args: JsonObject) = tool + "|" + args.toString()

    fun wrap(inner: ApprovalGate): ApprovalGate = object : ApprovalGate {
        override suspend fun approve(request: ApprovalRequest): Boolean = decide(request, inner)
        // Non-screen tools keep the inner gate's full answer (AppBridge scopes: Allow once / for this task).
        override suspend fun decide(request: ApprovalRequest): com.offlineagent.core.agents.PromptReply =
            if (request.tool !in ScreenToolkit.NAMES) inner.decide(request)
            else com.offlineagent.core.agents.PromptReply(if (decide(request, inner)) com.offlineagent.core.agents.PromptAnswer.ALLOW_ONCE else com.offlineagent.core.agents.PromptAnswer.DENY)
    }

    suspend fun decide(r: ApprovalRequest, inner: ApprovalGate): Boolean {
        if (r.tool !in ScreenToolkit.NAMES) return inner.approve(r)
        val pkg = runCatching { targetPackage(r.tool, r.args) }.getOrNull()
        if (pkg.isNullOrBlank()) return inner.approve(r)
        val label = appLabel(pkg)
        (policy.appVerdict(pkg) as? SafetyVerdict.Block)?.let { b ->
            audit?.record("refused ${r.tool}", "app=$pkg blocked: ${b.reason}", r.risk)
            return false
        }
        if (r.risk >= Risk.CRITICAL) {
            val ok = inner.approve(r.copy(title = "Confirm this step in $label: ${r.title}",
                description = "This step may send, submit, pay, share or delete something. ONYX asks for every such step — it is never covered by an app approval.\n" + r.description,
                offerTaskScope = false))
            if (ok) synchronized(lock) {
                criticalTokens[key(r.tool, r.args)] = clock()
                criticalTokens.entries.removeIf { clock() - it.value > tokenTtlMs }
            }
            audit?.record(if (ok) "approved step ${r.tool}" else "declined step ${r.tool}", "app=$pkg per-step", r.risk)
            return ok
        }
        val need = if (r.risk >= Risk.HIGH) Level.OPERATE else Level.READ
        val max = stepsPerScope()
        val covered: Int? = synchronized(lock) {
            val s = scopes[r.runId]?.get(pkg)
            if (s != null && s.level >= need && s.steps < max) { scopes.getValue(r.runId)[pkg] = s.copy(steps = s.steps + 1); s.steps + 1 } else null
        }
        if (covered != null) {
            audit?.record("auto-approved ${r.tool}", "app=$pkg scope step $covered/$max", r.risk)
            return true
        }
        val what = if (need == Level.OPERATE) "operate" else "read"
        // Phase 6.2: "Allow once" covers just this step; "Allow for this task" grants the app scope.
        val reply = inner.decide(r.copy(
            title = "Let ONYX $what $label for this task",
            description = "Covers up to $max screen steps in $label until this task ends (${r.title}). " +
                "Sending, paying, submitting or deleting still asks every time. Stop any time from the overlay.",
            offerTaskScope = true,
        ))
        val ok = reply.allowed
        if (ok && !reply.forTask) {
            audit?.record("approved once ${r.tool}", "app=$pkg (no scope)", r.risk)
            return true
        }
        if (ok) synchronized(lock) {
            val m = scopes.getOrPut(r.runId) { HashMap() }
            val old = m[pkg]
            m[pkg] = Scope(pkg, if (old != null && old.level > need) old.level else need, 1)
            while (scopes.size > 20) scopes.remove(scopes.keys.first())
        }
        audit?.record(if (ok) "granted scope: $what $pkg" else "declined scope: $what $pkg", "run=${r.runId} tool=${r.tool}", r.risk)
        return ok
    }
}
