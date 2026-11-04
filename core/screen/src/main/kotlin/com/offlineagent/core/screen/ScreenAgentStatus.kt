package com.offlineagent.core.screen

/**
 * Phase 6.1: what stands between the user and working in-app automation, for the banner in Chat and the Agents tab.
 * Pure: the app feeds it the Agents toggle, Android's enabled-services list, whether the service is actually bound
 * (OfflineAccessibilityService.instance), the Settings kill switch and the Screen-automation group.
 */
object ScreenAgentStatus {
    enum class Problem { NONE, SERVICE_OFF, SERVICE_NOT_RUNNING, KILL_SWITCH, GROUP_OFF, AGENT_OFF }
    enum class Fix(val label: String) {
        OPEN_ACCESSIBILITY("Open Accessibility settings"),
        OPEN_BATTERY("Battery: set to Unrestricted"),
        TURN_ON_AUTOMATION("Turn automation back on"),
        ENABLE_SCREEN_GROUP("Allow screen automation"),
        TURN_ON_AGENT("Turn on Screen agent"),
    }
    data class Status(val problem: Problem, val title: String = "", val detail: String = "", val fixes: List<Fix> = emptyList(), val dismissable: Boolean = false) {
        val ok: Boolean get() = problem == Problem.NONE
    }

    fun of(
        available: Boolean,
        agentOn: Boolean,
        serviceEnabledInSettings: Boolean,
        serviceConnected: Boolean,
        automationOn: Boolean,
        screenGroupOn: Boolean,
    ): Status {
        if (!available) return Status(Problem.NONE)
        if (agentOn) {
            if (!serviceConnected && !serviceEnabledInSettings) return Status(Problem.SERVICE_OFF,
                "Screen agent is on, but its accessibility service is off",
                "ONYX can only open apps, not tap or type in them. Turn on \"Offline Agent screen automation\" in Android Settings → Accessibility.",
                listOf(Fix.OPEN_ACCESSIBILITY))
            if (!serviceConnected) return Status(Problem.SERVICE_NOT_RUNNING,
                "Accessibility service is enabled but not connected",
                "Android (or battery optimisation) stopped it. Turn \"Offline Agent screen automation\" off and on again in Accessibility, and set ONYX's battery use to Unrestricted so it is not killed.",
                listOf(Fix.OPEN_ACCESSIBILITY, Fix.OPEN_BATTERY))
            if (!automationOn) return Status(Problem.KILL_SWITCH,
                "Phone automation is switched off",
                "The kill switch in Settings → Phone actions stops every screen step.", listOf(Fix.TURN_ON_AUTOMATION))
            if (!screenGroupOn) return Status(Problem.GROUP_OFF,
                "Screen automation is not allowed yet",
                "Settings → Phone actions → Screen automation is off, so every screen step would be refused.", listOf(Fix.ENABLE_SCREEN_GROUP))
            return Status(Problem.NONE)
        }
        if (serviceConnected) return Status(Problem.AGENT_OFF,
            "Accessibility service is on, but Screen agent is off",
            "Requests like \"open WhatsApp and type hi\" will only open the app. Turn on Screen agent (Agents tab) to let ONYX tap and type.",
            listOf(Fix.TURN_ON_AGENT), dismissable = true)
        return Status(Problem.NONE)
    }
}
