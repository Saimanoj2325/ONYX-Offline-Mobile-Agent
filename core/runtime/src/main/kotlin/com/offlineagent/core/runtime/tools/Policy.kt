package com.offlineagent.core.runtime.tools

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class NeedsApproval(val reason: String) : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
}

/**
 * Trusted gatekeeper between a validated tool call proposed by the model and its execution.
 *  - unknown tool / tool not allowed by the active skill -> Deny
 *  - READ_ONLY and UI -> Allow
 *  - WRITE_LOCAL -> NeedsApproval unless the user chose "always allow" for that tool
 *  - EXTERNAL_INTENT -> NeedsApproval every time (data would leave the app)
 *  - more than [maxSideEffectsPerRun] side-effecting calls in one run -> Deny (runaway protection)
 */
class PolicyEngine(
    private val registry: ToolRegistry,
    private val alwaysAllowed: () -> Set<String> = { emptySet() },
    private val maxSideEffectsPerRun: Int = 3,
) {
    fun decide(call: ToolCall, allowedTools: Set<String>?, sideEffectsSoFar: Int): PolicyDecision {
        val tool = registry[call.tool] ?: return PolicyDecision.Deny("unknown tool '${call.tool}'")
        if (allowedTools != null && call.tool !in allowedTools) return PolicyDecision.Deny("tool '${call.tool}' is not allowed by this skill")
        return when (tool.spec.permission) {
            Permission.READ_ONLY, Permission.UI -> PolicyDecision.Allow
            Permission.WRITE_LOCAL -> when {
                sideEffectsSoFar >= maxSideEffectsPerRun -> PolicyDecision.Deny("side-effect budget for this run used up")
                call.tool in alwaysAllowed() -> PolicyDecision.Allow
                else -> PolicyDecision.NeedsApproval(tool.spec.sideEffects)
            }
            Permission.EXTERNAL_INTENT -> if (sideEffectsSoFar >= maxSideEffectsPerRun) PolicyDecision.Deny("side-effect budget for this run used up")
                else PolicyDecision.NeedsApproval(tool.spec.sideEffects)
        }
    }

    fun hasSideEffects(tool: String): Boolean =
        registry[tool]?.spec?.permission.let { it == Permission.WRITE_LOCAL || it == Permission.EXTERNAL_INTENT }
}
