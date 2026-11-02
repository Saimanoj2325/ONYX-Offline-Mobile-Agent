package com.offlineagent.core.agents

import com.offlineagent.core.runtime.schema.validate
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.schema.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Phase 5b: every agent model call goes through here. It streams the model's text live into the [Blackboard]
 * (throttled there), marks the agent as "waiting for the model" while another agent holds it, and records an
 * [ActivityEvent.ModelCall] with the real latency. Nothing is simulated: the UI only shows text the model produced.
 */
internal suspend fun modelCall(
    deps: AgentDeps, board: Blackboard, agent: AgentRole, key: String, priority: InferenceQueue.Priority, prefix: String,
    system: String, user: String, grammar: String?, maxTokens: Int, readable: (String) -> String = { it },
): String {
    val q = deps.queue ?: error("no model loaded")
    val t0 = deps.clock()
    var granted = t0
    board.streamWaiting(key, agent)
    var final: String? = null
    try {
        final = q.stream(priority, prefix, system, user, grammar, maxTokens, onStart = { granted = deps.clock(); board.streamStarted(key, agent) }) { partial ->
            board.stream(key, agent, readable(partial))
        }
        return final
    } finally {
        board.streamEnd(key, agent, final?.let(readable))
        val now = deps.clock()
        board.emit(ActivityEvent.ModelCall(agent, key, prefix, now - granted, granted - t0, (final ?: "(interrupted)").take(4000), now))
    }
}

/**
 * Phase 5b fast paths: decide BEFORE calling the planner model whether planning is needed at all.
 *  - [Route.DIRECT]: conversational / knowledge question without device-tool words → Writer only (1 model call).
 *  - [Route.RULES]: exactly one obvious tool step whose arguments the rules already filled → no planner call.
 *  - [Route.PLANNED]: everything else (multi-step, ambiguous, needs argument filling).
 */
object Triage {
    private val TOOL_WORDS = Regex(
        """\b(remind(er|ers)?|alarm|timer|schedule|calendar|event|events|meeting|agenda|appointment|notification|notifications|battery|storage|""" +
            """torch|flashlight|brightness|volume|ringer|dnd|do not disturb|wi-?fi|bluetooth|open|launch|call|text|sms|message|email|send|share|""" +
            """note|notes|document|documents|file|files|pdf|download|downloads|save|create|add|delete|turn on|turn off|switch|set|remember|""" +
            """my plan|tomorrow'?s plan|today'?s plan|plan for|screen|photo|camera|contact|contacts|fetch|url|https?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MULTI = Regex(
        """\b(and then|then|after that|also|as well as)\b|,\s*and\b|\band\b.*\b(remind|set|tell|show|turn|add|save|send|summari[sz]e|list|give)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** True when the request probably needs tools / several agents (used by the chat "Agents: Auto" switch). */
    fun isTaskLike(request: String): Boolean = TOOL_WORDS.containsMatchIn(request)
    fun isMultiStep(request: String): Boolean = MULTI.containsMatchIn(request)

    fun route(request: String, specs: List<ToolSpec>, hasModel: Boolean): Pair<Route, List<PlannerAgent.Step>> {
        val rules = RulePlanner.steps(request, specs)
        if (!hasModel) return Route.FALLBACK to rules
        if (!isTaskLike(request) && rules.isEmpty()) return Route.DIRECT to emptyList()
        if (rules.size == 1 && !isMultiStep(request)) {
            val st = rules.single()
            val spec = specs.firstOrNull { it.name == st.tool }
            val argsOk = spec != null && (spec.input.properties.isEmpty() || (st.args.isNotEmpty() && spec.input.validate(st.args, "args").isEmpty()))
            if (argsOk) return Route.RULES to rules
        }
        return Route.PLANNED to rules
    }
}

/** Turns the planner's partial JSON into readable lines while it streams ("1. device_info — Read battery…"). */
object PlanPreview {
    private val STEP = Regex(""""tool"\s*:\s*"([a-z0-9_]*)"?(?:\s*,\s*"goal"\s*:\s*"((?:[^"\\]|\\.)*)"?)?""")
    private val EMPTY = Regex(""""steps"\s*:\s*\[\s*]""")
    fun readable(partialJson: String): String {
        val steps = STEP.findAll(partialJson).toList()
        if (steps.isEmpty()) return if (EMPTY.containsMatchIn(partialJson)) "No tools needed — answering directly." else "Working out the steps…"
        return steps.mapIndexed { i, m ->
            val tool = m.groupValues[1].ifBlank { "…" }
            val goal = m.groupValues.getOrNull(2).orEmpty()
            "${i + 1}. " + (if (tool == "none") "(no tool)" else tool) + (if (goal.isNotBlank()) " — $goal" else "")
        }.joinToString("\n")
    }
}

/** Compact, model-friendly summary of a finished task: carried into Chat by "Continue in chat" (no re-asking). */
object TaskContext {
    fun forChat(cp: TaskCheckpoint, maxChars: Int = 1800): String = buildString {
        append("Earlier ONYX task: \"").append(cp.request.take(300)).append("\" (").append(cp.status.name.lowercase()).append(")\n")
        val steps = cp.nodes.filter { it.id.startsWith("s") }
        if (steps.isNotEmpty()) {
            append("Steps:\n")
            steps.forEach { n ->
                append("- ").append(n.goal.take(120)).append(" [").append(n.status.name.lowercase()).append("]")
                if (n.output.isNotBlank()) append(": ").append(n.output.replace('\n', ' ').take(260))
                n.error?.let { append(" (").append(it.take(120)).append(")") }
                append('\n')
            }
        }
        cp.facts["memory"]?.takeIf { !it.startsWith("(") }?.let { append("Recalled: ").append(it.replace('\n', ' ').take(300)).append('\n') }
        cp.facts["context"]?.let { append("Device: ").append(it.lineSequence().first().take(200)).append('\n') }
        cp.answer?.let { append("Answer given: ").append(it.take(600)) }
    }.take(maxChars)
}

/** Phase 5b: makes sure every clause of a multi-part request has a step (see [PlannerAgent.plan]). */
object Coverage {
    private val SPLIT = Regex("""\s*(?:[,;]|\band then\b|\bthen\b|\band\b|\balso\b)\s*""", RegexOption.IGNORE_CASE)
    fun clauses(request: String): List<String> =
        request.split(SPLIT).map { it.trim() }.filter { it.split(' ').size >= 2 && ToolSelector.words(it).isNotEmpty() }

    fun repair(request: String, steps: List<PlannerAgent.Step>, rules: List<PlannerAgent.Step>, specs: List<ToolSpec>, max: Int = 4): List<PlannerAgent.Step> {
        val out = steps.toMutableList()
        // Complete rule arguments win over empty/model ones for the same tool; missing rule steps are added.
        for (r in rules) {
            val i = out.indexOfFirst { it.tool == r.tool }
            if (i >= 0) { if (r.args.isNotEmpty()) out[i] = out[i].copy(args = r.args, goal = if (out[i].goal.length > 80) r.goal else out[i].goal) }
            else if (out.size < max) out += r.copy(after = emptyList())
        }
        for (c in clauses(request)) {
            if (out.size >= max) break
            val best = ToolSelector.best(c, specs)
            if (best.isEmpty() || best.any { b -> out.any { it.tool == b.name } }) continue
            val t = best.first()
            out += PlannerAgent.Step(if (isActionTool(t)) AgentRole.EXECUTOR else AgentRole.DEVICE, t.name, c.take(160), emptyList())
        }
        return out
    }
}

/** Phase 5b: deterministic argument fixes the model gets wrong on small models (e.g. "7pm" filled as the current time). */
object ArgRepair {
    private val TIME = Regex("""\b(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)""", RegexOption.IGNORE_CASE)
    private val TIME24 = Regex("""\bat\s+(\d{1,2})[:.](\d{2})\b""", RegexOption.IGNORE_CASE)

    /** The single clock time named in the request as "HH:mm" (null when none or ambiguous). */
    fun requestTime(request: String): String? {
        val m = TIME.findAll(request).toList()
        if (m.size == 1) {
            var h = m[0].groupValues[1].toInt(); val min = m[0].groupValues[2].ifBlank { "0" }.toInt()
            val pm = m[0].groupValues[3].lowercase().startsWith("p")
            if (h !in 1..12 || min !in 0..59) return null
            if (pm && h != 12) h += 12; if (!pm && h == 12) h = 0
            return "%02d:%02d".format(h, min)
        }
        if (m.isEmpty()) TIME24.findAll(request).toList().singleOrNull()?.let { t ->
            val h = t.groupValues[1].toInt(); val min = t.groupValues[2].toInt()
            if (h in 0..23 && min in 0..59) return "%02d:%02d".format(h, min)
        }
        return null
    }

    /** "set a reminder at 7pm to call mom and tell me …" → "call mom". */
    fun reminderText(request: String): String {
        val m = Regex("""\bto\s+(.+?)(?:\s*(?:,|;|\band\b|\bthen\b)|$)""", RegexOption.IGNORE_CASE).findAll(request)
            .map { it.groupValues[1].trim() }.firstOrNull { it.isNotBlank() && TIME.find(it) == null }
        return (m ?: request).take(120)
    }

    /** Fills a reminder-like tool's arguments by property name (time / text|message|label / in_minutes / others neutral). */
    fun fillByName(spec: ToolSpec, time: String, text: String): JsonObject = buildJsonObject {
        val (h, mi) = time.split(':').map { it.toInt() }
        spec.input.properties.forEach { (k, sch) ->
            when {
                k == "time" -> put(k, time)
                k in setOf("text", "message", "label", "title") -> put(k, text)
                k == "hour" -> put(k, h)
                k == "minute" -> put(k, mi)
                sch is Schema.Num -> put(k, (sch.min ?: 0.0).let { if (sch.integer || it % 1.0 == 0.0) it.toInt() else it } as Number)
                sch is Schema.Bool -> put(k, false)
                sch is Schema.Str -> put(k, "")
            }
        }
    }

    /** Overrides a wrongly filled time with the one the user said. */
    fun fix(spec: ToolSpec, args: JsonObject, request: String): JsonObject {
        val t = requestTime(request) ?: return args
        val props = spec.input.properties.map { it.first }.toSet()
        val out = args.toMutableMap()
        if ("time" in props && spec.input.prop("time") is Schema.Str && (args["time"] as? JsonPrimitive)?.contentOrNull != t) out["time"] = JsonPrimitive(t)
        if ("hour" in props && "minute" in props) {
            val (h, m) = t.split(':').map { it.toInt() }
            out["hour"] = JsonPrimitive(h); out["minute"] = JsonPrimitive(m)
        }
        return JsonObject(out)
    }
}

/** Phase 5b Verifier rule: the answer must not claim an action that no DONE step performed (small models do this). */
object ClaimCheck {
    private val CLAIMS = listOf(
        Regex("""\b(torch|flashlight)\b[^.]*\b(is on|turned on|switched on|on now)\b|\bturned (on )?the (torch|flashlight)""", RegexOption.IGNORE_CASE) to listOf("flashlight"),
        Regex("""\bnote\b[^.]*\b(saved|created|added)\b|\bsaved (a|the|your) note""", RegexOption.IGNORE_CASE) to listOf("note"),
        Regex("""\breminder\b[^.]*\b(set|scheduled|created|added)\b|\b(set|scheduled) (a|the|your) reminder""", RegexOption.IGNORE_CASE) to listOf("remind"),
        Regex("""\balarm\b[^.]*\b(set|scheduled)\b""", RegexOption.IGNORE_CASE) to listOf("alarm", "remind"),
        Regex("""\b(added|saved) (it )?to (your )?calendar|\bevent\b[^.]*\b(added|created)\b""", RegexOption.IGNORE_CASE) to listOf("calendar", "event"),
        Regex("""\b(message|email|sms)\b[^.]*\b(sent|drafted)\b""", RegexOption.IGNORE_CASE) to listOf("sms", "email", "message", "whatsapp", "share"),
    )

    fun issues(answer: String, plan: TaskPlan?, nodes: Map<String, NodeState>): List<String> {
        val done = plan?.nodes.orEmpty().filter { n -> n.tool != null && nodes[n.id]?.status == NodeStatus.DONE }.mapNotNull { it.tool }
        return CLAIMS.mapNotNull { (re, toolWords) ->
            val m = re.find(answer) ?: return@mapNotNull null
            if (done.any { t -> toolWords.any { t.contains(it) } }) null
            else "claims \"${m.value.take(40)}\" but no step did that — say it was not done"
        }
    }
}
