package com.offlineagent.core.agents

import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.validate
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Everything an agent may use. [queue] is null when no model is loaded: agents then use their rule-based paths. */
class AgentDeps(
    val queue: InferenceQueue?,
    val tools: ToolGateway,
    val memory: MemoryStore,
    val context: ContextProvider,
    val approvals: ApprovalGate,
    val network: NetworkStatus,
    val outbox: OutboxSink,
    val audit: AuditSink,
    val store: TaskStore,
    val clock: () -> Long = System::currentTimeMillis,
)

interface Agent {
    val role: AgentRole
    suspend fun handle(node: TaskNode, board: Blackboard, attempt: Int, lastError: String?): NodeOutcome
}

private fun JsonObject.isErrorResult(): Boolean = this["error"] != null || (this["isError"] as? JsonPrimitive)?.booleanOrNull == true

/** Picks the tools most relevant to a request (keeps planner prompts short on a phone). */
object ToolSelector {
    private fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }.toSet()
    fun relevant(request: String, specs: List<ToolSpec>, limit: Int): List<ToolSpec> {
        val q = words(request)
        return specs.map { s -> s to (words(s.name.replace('_', ' ') + " " + s.description) intersect q).size }
            .sortedByDescending { it.second }.filter { it.second > 0 }.take(limit).map { it.first }
    }
}

// ------------------------------------------------------------------------------------------------ Planner

/**
 * Planner: turns a request into a [TaskPlan] DAG. Shape is always: Context ∥ Memory (parallel roots) → tool nodes
 * (Device reads ∥ each other, Executor actions after the reads they need) → Writer. The model only picks the middle
 * steps under a GBNF grammar (≤4 steps, tool names from an enum); deterministic rules are the fallback.
 */
class PlannerAgent(private val deps: AgentDeps) {
    suspend fun plan(request: String, board: Blackboard): TaskPlan {
        val specs = deps.tools.specs()
        val llmPlan = deps.queue?.let { q ->
            runCatching { llmSteps(q, request, specs) }.onFailure { board.note("Planner model failed (${it.message?.take(80)}); using rules") }.getOrNull()
        }
        val middle = llmPlan?.takeIf { it.isNotEmpty() } ?: RulePlanner.steps(request, specs)
        return assemble(request, middle, if (llmPlan.isNullOrEmpty()) "rules" else "model", screen = RulePlanner.wantsScreen(request))
    }

    data class Step(val agent: AgentRole, val tool: String?, val goal: String, val after: List<Int>, val args: JsonObject = JsonObject(emptyMap()))

    private suspend fun llmSteps(q: InferenceQueue, request: String, all: List<ToolSpec>): List<Step> {
        val specs = (ToolSelector.relevant(request, all, 10) + all.filter { it.name in setOf("get_datetime", "device_info", "search_docs") }).distinctBy { it.name }
        if (specs.isEmpty()) return emptyList()
        val schema = Schema.Obj(listOf("steps" to Schema.Arr(Schema.Obj(listOf(
            "tool" to Schema.Str(enum = specs.map { it.name } + "none"),
            "goal" to Schema.Str(maxLength = 160, minLength = 3),
            "after" to Schema.Arr(Schema.Num(min = 1.0, max = 4.0, integer = true), maxItems = 3),
        )), maxItems = 4)))
        val system = "You plan phone-assistant tasks. Choose at most 4 tool steps (fewer is better; none if the question needs no tool). " +
            "\"after\" lists earlier step numbers a step needs. Tools:\n" + specs.joinToString("\n") { "- ${it.name}: ${it.description.take(110)}" }
        val out = q.complete(InferenceQueue.Priority.PLANNING, "planner", system, request, Gbnf.of(schema), 300)
        val steps = (AgentJson.parseToJsonElement(out).jsonObject["steps"] as? JsonArray) ?: return emptyList()
        return steps.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val tool = (o["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it != "none" }
            val spec = tool?.let { t -> all.firstOrNull { it.name == t } } ?: return@mapIndexedNotNull null
            val after = (o["after"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }?.filter { it in 1..i } ?: emptyList()
            Step(if (isActionTool(spec)) AgentRole.EXECUTOR else AgentRole.DEVICE, tool, (o["goal"] as? JsonPrimitive)?.content ?: tool, after)
        }
    }

    companion object {
        fun assemble(request: String, middle: List<Step>, source: String, screen: Boolean = false): TaskPlan {
            val roots = listOf(
                TaskNode("ctx", AgentRole.CONTEXT, if (screen) "Read device state and the screen" else "Read device state", verify = false),
                TaskNode("mem", AgentRole.MEMORY, "Recall related memories and documents", verify = false),
            )
            val stepNodes = middle.mapIndexed { i, s ->
                TaskNode("s${i + 1}", s.agent, s.goal, s.tool, s.args, listOf("ctx", "mem") + s.after.map { "s$it" })
            }
            val writer = TaskNode("answer", AgentRole.WRITER, "Compose the answer", dependsOn = (roots + stepNodes).map { it.id })
            return TaskPlan(request, roots + stepNodes + writer, source)
        }
    }
}

/** Deterministic planner used without a model, or when the model's plan is invalid. */
object RulePlanner {
    fun wantsScreen(r: String) = Regex("\\b(screen|on my display|this page|this app)\\b", RegexOption.IGNORE_CASE).containsMatchIn(r)

    fun steps(request: String, specs: List<ToolSpec>): List<PlannerAgent.Step> {
        val r = request.lowercase()
        val have = specs.associateBy { it.name }
        val out = mutableListOf<PlannerAgent.Step>()
        fun add(tool: String, goal: String, args: JsonObject = JsonObject(emptyMap()), after: List<Int> = emptyList()) {
            val s = have[tool] ?: return
            out += PlannerAgent.Step(if (isActionTool(s)) AgentRole.EXECUTOR else AgentRole.DEVICE, tool, goal, after, args)
        }
        if (Regex("battery|storage|ram\\b|device info|phone model").containsMatchIn(r)) add("device_info", "Read battery, storage and device info")
        if (Regex("notification").containsMatchIn(r)) add("recent_notifications", "Read recent notifications", buildJsonObject { put("limit", 10); put("app", "") })
        if (Regex("calendar|schedule|meeting|events? today|agenda").containsMatchIn(r)) add("list_events", "List today's events", buildJsonObject { put("from", ""); put("days", 1) })
        if (Regex("reminders?\\b").containsMatchIn(r) && Regex("list|show|what").containsMatchIn(r)) add("list_reminders", "List reminders")
        if (Regex("flashlight|torch").containsMatchIn(r)) add("flashlight", "Switch the flashlight", buildJsonObject { put("on", !Regex("\\boff\\b").containsMatchIn(r)) })
        if (Regex("\\b(time|date|today|day is it)\\b").containsMatchIn(r) && out.isEmpty()) add("get_datetime", "Get the current date and time")
        if (Regex("document|pdf|my files|notes? about").containsMatchIn(r)) add("search_docs", "Search imported documents", buildJsonObject { put("query", request.take(200)); put("k", 4) })
        return out.take(4)
    }
}

// ------------------------------------------------------------------------------------------------ Memory / Context

class MemoryAgent(private val deps: AgentDeps) : Agent {
    override val role = AgentRole.MEMORY
    override suspend fun handle(node: TaskNode, board: Blackboard, attempt: Int, lastError: String?): NodeOutcome {
        if (!deps.memory.enabled) { board.fact("memory", "(memory is turned off by the user)"); return NodeOutcome.Ok("Memory is off") }
        val hits = deps.memory.recall(if (node.goal.startsWith("Recall related")) board.request else node.goal, 5)
        val text = hits.joinToString("\n") { "- [${it.source}] ${it.title}: ${it.text.take(300)}" }
        board.fact(if (node.id == "mem") "memory" else "memory_${node.id}", text.ifBlank { "(nothing relevant remembered)" })
        return NodeOutcome.Ok(if (hits.isEmpty()) "Nothing relevant remembered" else "${hits.size} memories/passages found")
    }
}

class ContextAgent(private val deps: AgentDeps) : Agent {
    override val role = AgentRole.CONTEXT
    override suspend fun handle(node: TaskNode, board: Blackboard, attempt: Int, lastError: String?): NodeOutcome {
        // Retry without the screen if the screen read failed (re-delegation inside the context role).
        val ctx = deps.context.snapshot(includeScreen = node.goal.contains("screen") && attempt == 1)
        board.fact("context", ctx.summary())
        return NodeOutcome.Ok(ctx.summary().lineSequence().first())
    }
}

// ------------------------------------------------------------------------------------------------ Device / Executor

/** Fills a tool's arguments: given args if valid, else the model under the tool's exact input grammar. */
internal suspend fun fillArgs(deps: AgentDeps, node: TaskNode, spec: ToolSpec, board: Blackboard, lastError: String?): Result<JsonObject> {
    if (node.args.isNotEmpty() && spec.input.validate(node.args, "args").isEmpty()) return Result.success(node.args)
    if (spec.input.properties.isEmpty()) return Result.success(JsonObject(emptyMap()))
    val q = deps.queue ?: return Result.failure(IllegalStateException("load a chat model so I can fill in ${spec.name}'s details"))
    val facts = board.facts().entries.joinToString("\n") { (k, v) -> "$k: ${v.take(500)}" }
    val system = "Fill the arguments for the tool ${spec.name}: ${spec.description}\nUse the user's request and the facts. Output only JSON."
    val user = "Request: ${board.request}\nStep: ${node.goal}\nFacts:\n$facts" + (lastError?.let { "\nThe previous attempt failed: $it. Fix the arguments." } ?: "")
    val out = q.complete(InferenceQueue.Priority.INTERACTIVE, "args:${spec.name}", system, user, Gbnf.of(spec.input), 256)
    val obj = runCatching { AgentJson.parseToJsonElement(out).jsonObject }.getOrElse { return Result.failure(IllegalArgumentException("model returned invalid JSON")) }
    val errs = spec.input.validate(obj, "args")
    return if (errs.isEmpty()) Result.success(obj) else Result.failure(IllegalArgumentException(errs.joinToString { it.toString() }.take(200)))
}

internal suspend fun runTool(deps: AgentDeps, name: String, args: JsonObject): ToolResult =
    if (deps.tools.usesModel(name) && deps.queue != null) deps.queue.run(InferenceQueue.Priority.NORMAL, "tool:$name") { deps.tools.execute(name, args) }
    else deps.tools.execute(name, args)

internal fun ToolResult.toOutcome(): NodeOutcome = when (this) {
    is ToolResult.Ok -> if (json.isErrorResult()) NodeOutcome.Fail(json.toString().take(300)) else NodeOutcome.Ok(summary.ifBlank { json.toString().take(1500) }, json)
    is ToolResult.Error -> NodeOutcome.Fail(message, retryable)
}

/** Shared approval + offline handling for Device (reads) and Executor (actions). */
internal suspend fun gatedCall(deps: AgentDeps, role: AgentRole, node: TaskNode, board: Blackboard, attempt: Int, lastError: String?, alwaysAsk: Boolean): NodeOutcome {
    val name = node.tool ?: return NodeOutcome.Fail("no tool on this step", retryable = false)
    val spec = deps.tools.spec(name) ?: return NodeOutcome.Fail("tool $name is not available (turned off or unknown)", retryable = false)
    val args = fillArgs(deps, node, spec, board, lastError).getOrElse { return NodeOutcome.Fail(it.message ?: "bad arguments", retryable = deps.queue != null) }
    val risk = deps.tools.risk(name, args)
    val needsNet = deps.tools.requiresNetwork(name)
    if (alwaysAsk && risk > Risk.NONE || risk.needsApproval) {
        board.message(AgentMessage.ApprovalAsked(role, AgentRole.ORCHESTRATOR, node.id, name, risk))
        board.setStatus(TaskStatus.AWAITING_APPROVAL)
        deps.store.save(board.checkpoint())
        val ok = deps.approvals.approve(ApprovalRequest(board.runId, node.id, name, node.goal, spec.description, args, risk, spec.sideEffects, needsNet))
        board.setStatus(TaskStatus.RUNNING)
        deps.audit.record(board.runId, role.title, if (ok) "approved $name" else "denied $name", args.toString().take(500), risk)
        if (!ok) return NodeOutcome.Denied("You declined $name")
    }
    if (needsNet && !deps.network.isOnline()) {
        val id = deps.outbox.enqueue(board.runId, name, args, risk)
        deps.audit.record(board.runId, role.title, "queued $name (offline)", args.toString().take(500), risk)
        board.message(AgentMessage.QueuedOffline(role, AgentRole.ORCHESTRATOR, node.id, name, id))
        return NodeOutcome.Queued(id, "$name needs internet: queued, it runs automatically when you are back online")
    }
    val outcome = runTool(deps, name, args).toOutcome()
    if (risk > Risk.NONE) deps.audit.record(board.runId, role.title, "ran $name", (if (outcome is NodeOutcome.Ok) "ok" else outcome.toString()).take(300), risk)
    if (outcome is NodeOutcome.Ok) board.fact("${node.id}:$name", outcome.output.take(1500))
    return outcome
}

class DeviceAgent(private val deps: AgentDeps) : Agent {
    override val role = AgentRole.DEVICE
    override suspend fun handle(node: TaskNode, board: Blackboard, attempt: Int, lastError: String?): NodeOutcome {
        val spec = node.tool?.let { deps.tools.spec(it) }
        if (spec != null && isActionTool(spec)) return NodeOutcome.Fail("${spec.name} changes something: only the Executor may run it", retryable = false)
        return gatedCall(deps, role, node, board, attempt, lastError, alwaysAsk = false)
    }
}

/** Runs ONLY actions the user approved on the confirmation sheet (every non-zero-risk action asks). */
class ExecutionAgent(private val deps: AgentDeps) : Agent {
    override val role = AgentRole.EXECUTOR
    override suspend fun handle(node: TaskNode, board: Blackboard, attempt: Int, lastError: String?): NodeOutcome =
        gatedCall(deps, role, node, board, attempt, lastError, alwaysAsk = true)
}

// ------------------------------------------------------------------------------------------------ Writer / Verifier

class WriterAgent(private val deps: AgentDeps) : Agent {
    override val role = AgentRole.WRITER
    override suspend fun handle(node: TaskNode, board: Blackboard, attempt: Int, lastError: String?): NodeOutcome {
        val st = board.state.value
        val results = st.nodes.values.filter { it.id.startsWith("s") }.joinToString("\n") { n ->
            "- ${n.goal}: ${n.status}${if (n.output.isNotBlank()) " → ${n.output.take(600)}" else ""}${n.error?.let { " (error: $it)" } ?: ""}"
        }
        val q = deps.queue
        if (q == null || attempt > 2) return NodeOutcome.Ok(template(board.request, st, results))
        val system = "You are ONYX, an offline phone assistant. Answer the user briefly and helpfully using ONLY the facts and step results. " +
            "Never claim an action happened unless its step is DONE. Mention queued (offline) or declined steps honestly."
        val user = "Request: ${board.request}\nFacts:\n${st.facts.filterKeys { it == "context" || it == "memory" }.entries.joinToString("\n") { "${it.key}: ${it.value.take(800)}" }}" +
            "\nStep results:\n${results.ifBlank { "(no tools used)" }}" + (lastError?.let { "\nFix this problem in your answer: $it" } ?: "")
        val text = try { q.complete(InferenceQueue.Priority.INTERACTIVE, "writer", system, user, null, 600) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { return NodeOutcome.Ok(template(board.request, st, results) + "\n\n(The model could not write the answer: ${e.message?.take(120)})") }
        return if (text.isBlank()) NodeOutcome.Fail("empty answer") else NodeOutcome.Ok(text)
    }

    private fun template(request: String, st: Blackboard.State, results: String) =
        if (results.isBlank()) "I could not use the model for \"$request\" (no chat model loaded). Load one in Settings → Models."
        else "Here is what I found and did:\n$results"
}

/**
 * Verifier: deterministic rules on every verified node, plus (when a model is loaded and enabled) a grammar-constrained
 * yes/no check of the final answer against the step results.
 */
class VerificationAgent(private val deps: AgentDeps, private val llmCheck: Boolean = true) {
    data class Report(val passed: Boolean, val issues: List<String>)

    fun ruleCheck(node: TaskNode, outcome: NodeOutcome.Ok, board: Blackboard): Report {
        val issues = mutableListOf<String>()
        val out = outcome.output
        if (out.isBlank()) issues += "empty result"
        if (outcome.data?.isErrorResult() == true) issues += "tool reported an error"
        if (out.contains("<think>")) issues += "contains raw thinking"
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size >= 4 && lines.groupingBy { it.trim() }.eachCount().values.any { it >= 4 }) issues += "repetitive output"
        if (node.agent == AgentRole.WRITER) {
            val st = board.state.value
            val failedActions = st.nodes.values.filter { it.agent == AgentRole.EXECUTOR && (it.status == NodeStatus.FAILED || it.status == NodeStatus.DENIED) }
            if (failedActions.isNotEmpty() && Regex("\\b(done|i have|i've|successfully)\\b", RegexOption.IGNORE_CASE).containsMatchIn(out) &&
                failedActions.none { f -> out.contains(f.goal, ignoreCase = true) || out.contains("declin", true) || out.contains("fail", true) || out.contains("could not", true) || out.contains("couldn't", true) })
                issues += "claims success although an action failed or was declined"
        }
        return Report(issues.isEmpty(), issues)
    }

    suspend fun verify(node: TaskNode, outcome: NodeOutcome.Ok, board: Blackboard): Report {
        val rules = ruleCheck(node, outcome, board)
        if (!rules.passed || node.agent != AgentRole.WRITER || !llmCheck) return rules
        val q = deps.queue ?: return rules
        val schema = Schema.Obj(listOf("ok" to Schema.Bool(), "issue" to Schema.Str(maxLength = 160)))
        val res = runCatching {
            q.complete(InferenceQueue.Priority.NORMAL, "verifier", "Check an assistant answer. ok=true if it answers the request and does not contradict the step results.",
                "Request: ${board.request}\nStep results: ${board.state.value.nodes.values.filter { it.id.startsWith("s") }.joinToString { "${it.goal}=${it.status}" }}\nAnswer: ${outcome.output.take(1500)}",
                Gbnf.of(schema), 80)
        }.getOrNull() ?: return rules
        val o = runCatching { AgentJson.parseToJsonElement(res).jsonObject }.getOrNull() ?: return rules
        val ok = (o["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
        return if (ok) rules else Report(false, listOf("model check: " + ((o["issue"] as? JsonPrimitive)?.content ?: "inconsistent answer")))
    }
}
