package com.offlineagent.core.agents

import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 6.1: requests that need taps/typing INSIDE an app ("open WhatsApp and type hi to Mom", "open Settings > Display
 * and turn on dark mode", "in YouTube, search for lofi", "type hi to Mom on WhatsApp"). Opening the app is only the
 * first half of these; an intent opener (app_open) alone must never count as done.
 */
object InAppRequest {
    data class Parsed(val app: String, val goal: String)

    private const val LEAD = """^\s*(?:hey\s+onyx[,\s]+)?(?:please\s+)?(?:can you\s+|could you\s+)?(?:please\s+)?"""
    private const val APP = """(?:the\s+)?([\p{L}\p{N}][\p{L}\p{N} .&'+_>›→:/-]{0,48}?)(?:\s+app)?"""
    private val OPEN_AND = Regex(LEAD + """(?:open|launch|start|go to|goto|switch to|head to)\s+""" + APP +
        """\s*(?:,\s*(?:and\s+)?(?:then\s+)?|\s+and\s+then\s+|\s+and\s+|\s+then\s+|\s*&\s*)(.+)$""", RegexOption.IGNORE_CASE)
    private val IN_APP = Regex(LEAD + """(?:in|on|inside)\s+""" + APP + """\s*,\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val TRAILING_APP = Regex(LEAD + """(.+)\s+(?:on|in|using|inside)\s+""" + APP + """[.!]?\s*$""", RegexOption.IGNORE_CASE)

    /** Verbs that only make sense inside an app's UI. */
    private val IN_APP_VERB = Regex(
        """\b(type|write|enter|tap|click|press|select|choose|pick|toggle|turn on|turn off|switch on|switch off|enable|disable|""" +
            """search(?: for)?|scroll|find|go to|navigate to|open (?:the|my|a|chat|conversation)|message|reply|send|fill|add|change|set|""" +
            """play|pause|like|follow|draft|compose|connect|disconnect|start a|create)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Words that mean the "app" is not an app (open the pdf and summarise it). */
    private val NOT_APP = Regex("""\b(file|pdf|document|doc|link|url|page|photo|image|picture|video|folder|screen|it|this|that)\b""", RegexOption.IGNORE_CASE)
    /** Cross-app copy/paste goes to the planner (screen_extract → screen_transfer), not one in-app loop. */
    private val CROSS_APP = Regex("""\b(copy|paste|extract|transfer)\b""", RegexOption.IGNORE_CASE)

    fun parse(request: String): Parsed? {
        val r = request.trim().trimEnd('.', '!')
        if (r.length < 8 || r.length > 400) return null
        OPEN_AND.find(r)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return it } }
        IN_APP.find(r)?.let { m -> ok(m.groupValues[1], m.groupValues[2])?.let { return it } }
        TRAILING_APP.find(r)?.let { m ->
            val goal = m.groupValues[1]
            // "type hi to Mom on WhatsApp": the goal must START with an in-app verb (not "remind me on Monday").
            if (Regex("""^\s*(type|write|tap|click|press|search|turn on|turn off|switch on|switch off|enable|disable|toggle|scroll|select|send|reply|message|play|draft|compose)\b""", RegexOption.IGNORE_CASE).containsMatchIn(goal))
                ok(m.groupValues[2], goal)?.let { return it }
        }
        return null
    }

    private fun ok(rawApp: String, rawGoal: String): Parsed? {
        val app = rawApp.trim().trim(',', '.').removeSuffix(" app").trim()
        val goal = rawGoal.trim().trim(',', '.')
        if (app.isBlank() || goal.length < 3) return null
        if (app.split(Regex("\\s+")).size > 6) return null
        if (NOT_APP.containsMatchIn(app.substringBefore('>').substringBefore('›'))) return null
        if (Regex("""^(me|my|a|an|some|tomorrow|today|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d)\b""", RegexOption.IGNORE_CASE).containsMatchIn(app)) return null
        if (!IN_APP_VERB.containsMatchIn(goal) || CROSS_APP.containsMatchIn(goal)) return null
        return Parsed(normalizeSettings(app, goal), goal)
    }

    /** "Settings > Display" stays; a bare "Settings" gets the goal as page hint ("Settings > turn on dark mode"). */
    private fun normalizeSettings(app: String, goal: String): String {
        val a = app.lowercase()
        return if (a == "settings" || a == "android settings" || a == "phone settings") "Settings > $goal" else app
    }

    fun isInApp(request: String) = parse(request) != null

    /** Screen tools that actually act inside an app (opening it is not one of them). */
    val IN_APP_TOOLS = setOf("screen_task", "screen_act", "screen_do", "screen_transfer", "run_phone_task", "run_macro", "fill_form")
    /** Tools that only open an app or a link. */
    val OPEN_ONLY_TOOLS = setOf("app_open", "open_app", "screen_open_app", "app_view", "open_link", "open_settings_panel")

    /**
     * The deterministic plan for an in-app request: screen_open_app → screen_task (screen tools v2 on), or the legacy
     * run_phone_task loop (v2 off). Empty when neither is available (screen agent off and Screen automation disabled).
     */
    fun steps(p: Parsed, specs: List<ToolSpec>): List<PlannerAgent.Step> {
        val have = specs.associateBy { it.name }
        fun step(tool: String, goal: String, after: List<Int>, args: JsonObject) = PlannerAgent.Step(
            if (have[tool]?.let(::isActionTool) != false) AgentRole.EXECUTOR else AgentRole.DEVICE, tool, goal, after, args)
        if ("screen_task" in have && "screen_open_app" in have) return listOf(
            step("screen_open_app", "Open ${p.app}", emptyList(), buildJsonObject { put("app", p.app) }),
            step("screen_task", p.goal.take(160), listOf(1), buildJsonObject { put("goal", p.goal.take(300)); put("app", p.app); put("max_steps", 12) }),
        )
        if ("run_phone_task" in have) return listOf(
            step("run_phone_task", "In ${p.app}: ${p.goal}".take(160), emptyList(), buildJsonObject { put("goal", p.goal.take(500)); put("app", p.app.take(120)) }),
        )
        return emptyList()
    }
}

/**
 * Phase 6.1 Verifier rule: for an in-app request, an answer may not present the task as done when no step acted inside
 * the app (only app_open / screen_open_app ran, or nothing did).
 */
object InAppCheck {
    private val HONEST = Regex("""\b(not|couldn'?t|could not|didn'?t|did not|unable|only opened|wasn'?t|was not|failed|turn on|enable|stopped)\b""", RegexOption.IGNORE_CASE)

    fun inAppDone(plan: TaskPlan?, nodes: Map<String, NodeState>): Boolean =
        plan?.nodes.orEmpty().any { n -> n.tool in InAppRequest.IN_APP_TOOLS && nodes[n.id]?.status == NodeStatus.DONE }

    fun notDone(request: String): String {
        val p = InAppRequest.parse(request) ?: return "The in-app steps were not done."
        return "Note: I did not \"${p.goal.take(80)}\" inside ${p.app} — at most the app was opened. For taps and typing, turn on Screen agent " +
            "(Agents tab) and the \"Offline Agent screen automation\" accessibility service."
    }

    fun issues(request: String, answer: String, plan: TaskPlan?, nodes: Map<String, NodeState>): List<String> {
        val p = InAppRequest.parse(request) ?: return emptyList()
        if (inAppDone(plan, nodes)) return emptyList()
        if (HONEST.containsMatchIn(answer)) return emptyList()
        return listOf("only opened ${p.app} — the in-app part (\"${p.goal.take(60)}\") was NOT done; say so plainly, and that the Screen agent " +
            "(Agents tab) plus the ONYX accessibility service must be on for taps and typing")
    }
}

/** Phase 6.1: which Chat turns go to the screen agent (the ONYX orchestrator's screen route) instead of chat / the legacy agent. */
object ChatRouting {
    private val MODES = setOf(com.offlineagent.core.runtime.chat.ComposerMode.CHAT, com.offlineagent.core.runtime.chat.ComposerMode.AGENT,
        com.offlineagent.core.runtime.chat.ComposerMode.PHONE, com.offlineagent.core.runtime.chat.ComposerMode.AUTOMATION)
    fun screenTask(text: String, mode: com.offlineagent.core.runtime.chat.ComposerMode, screenAgentOn: Boolean): Boolean =
        screenAgentOn && mode in MODES && InAppRequest.isInApp(text)
}
