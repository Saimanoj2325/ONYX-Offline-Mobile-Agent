package com.offlineagent.core.apps

import com.offlineagent.core.agents.Risk

/**
 * Scoped approvals: "allow ONYX to use <App> for THIS task" so the user is not asked for every intent into the same app.
 *
 * Rules (enforced here, tested in ApprovalScopesTest):
 *  - a grant is per task run AND per target (an app package, or a read tool such as notif_list), with a use budget;
 *  - only MEDIUM and HIGH requests can be covered; CRITICAL (irreversible: replies, sharing into messaging apps,
 *    delete/pay-like notification actions) is NEVER covered and always asks per step;
 *  - a grant never leaks to another run, another app, or past its budget; [clear] drops a run's grants when it ends.
 */
class ApprovalScopes(private val defaultUses: Int = 10) {
    data class Grant(val runId: String, val target: String, val label: String, val usesLeft: Int)

    private val grants = HashMap<Pair<String, String>, Grant>()

    @Synchronized
    fun grant(runId: String, target: String, label: String = target, uses: Int = defaultUses) {
        grants[runId to target] = Grant(runId, target, label, uses)
    }

    /** True (and one use consumed) when an active grant covers this request. */
    @Synchronized
    fun tryUse(runId: String, target: String?, risk: Risk): Boolean {
        if (target == null || !scopable(risk)) return false
        val g = grants[runId to target] ?: return false
        if (g.usesLeft <= 0) { grants.remove(runId to target); return false }
        grants[runId to target] = g.copy(usesLeft = g.usesLeft - 1)
        return true
    }

    @Synchronized fun active(runId: String): List<Grant> = grants.values.filter { it.runId == runId && it.usesLeft > 0 }
    @Synchronized fun clear(runId: String) { grants.keys.removeAll { it.first == runId } }
    @Synchronized fun clearAll() = grants.clear()

    companion object {
        fun scopable(risk: Risk) = risk == Risk.MEDIUM || risk == Risk.HIGH
    }
}
