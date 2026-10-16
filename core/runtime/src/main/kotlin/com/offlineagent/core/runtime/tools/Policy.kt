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
    /** Tools switched off by the user (Phone actions screen); denied outright. */
    private val disabled: () -> Set<String> = { emptySet() },
) {
    fun decide(call: ToolCall, allowedTools: Set<String>?, sideEffectsSoFar: Int): PolicyDecision {
        val tool = registry[call.tool] ?: return PolicyDecision.Deny("unknown tool '${call.tool}'")
        if (allowedTools != null && call.tool !in allowedTools) return PolicyDecision.Deny("tool '${call.tool}' is not allowed by this skill")
        if (call.tool in disabled()) return PolicyDecision.Deny("'${call.tool}' is switched off in Settings → Phone actions")
        return when (tool.spec.permission) {
            Permission.READ_ONLY, Permission.UI -> PolicyDecision.Allow
            Permission.WRITE_LOCAL -> when {
                sideEffectsSoFar >= maxSideEffectsPerRun -> PolicyDecision.Deny("side-effect budget for this run used up")
                call.tool in alwaysAllowed() -> PolicyDecision.Allow
                else -> PolicyDecision.NeedsApproval(tool.spec.sideEffects)
            }
            Permission.EXTERNAL_INTENT, Permission.SCREEN_ACTION -> if (sideEffectsSoFar >= maxSideEffectsPerRun) PolicyDecision.Deny("side-effect budget for this run used up")
                else PolicyDecision.NeedsApproval(tool.spec.sideEffects)
            Permission.SENSITIVE_READ -> if (call.tool in alwaysAllowed()) PolicyDecision.Allow else PolicyDecision.NeedsApproval(tool.spec.sideEffects)
            Permission.DEVICE_CONTROL -> when {
                sideEffectsSoFar >= maxSideEffectsPerRun -> PolicyDecision.Deny("side-effect budget for this run used up")
                call.tool in alwaysAllowed() -> PolicyDecision.Allow
                else -> PolicyDecision.NeedsApproval(tool.spec.sideEffects)
            }
        }
    }

    fun hasSideEffects(tool: String): Boolean = registry[tool]?.spec?.permission?.hasSideEffects == true
}
