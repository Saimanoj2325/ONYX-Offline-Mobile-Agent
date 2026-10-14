package com.offlineagent.core.runtime.agent

import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.skills.Skill
import com.offlineagent.core.runtime.tools.PolicyDecision
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.ToolCall
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolRegistry
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.ui.CanvasPrompts
import com.offlineagent.core.runtime.ui.UiSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// ---------------------------------------------------------------- persisted model

enum class RunStatus { PLANNING, RUNNING, AWAITING_APPROVAL, COMPLETED, FAILED, CANCELLED;
    val isTerminal get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

enum class StepKind { PLAN, TOOL_CALL, APPROVAL, ERROR, FINAL, COMPOSE }

data class StepRecord(
    val index: Int,
    val kind: StepKind,
    val tool: String? = null,
    val thought: String = "",
    val input: String = "",
    val output: String = "",
    val error: String? = null,
    val startedAt: Long = 0,
    val durationMs: Long = 0,
)

/** Everything needed to resume a run after process death. Saved after every step. */
data class RunCheckpoint(
    val runId: String,
    val chatId: Long?,
    val skillId: String?,
    val request: String,
    val status: RunStatus,
    val plan: List<String> = emptyList(),
    val iteration: Int = 0,
    val steps: List<StepRecord> = emptyList(),
    val finalAnswer: String? = null,
    val consecutiveErrors: Int = 0,
    val sideEffects: Int = 0,
    val canvasRendered: Boolean = false,
    val error: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

// ---------------------------------------------------------------- ports

/** One grammar-constrained completion. Implemented over InferenceAdapter in the app; faked in tests. */
interface AgentLlm {
    suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String
}

interface RunStore {
    suspend fun save(checkpoint: RunCheckpoint)
    suspend fun load(runId: String): RunCheckpoint?
}

/** Asks the user. Suspends until they answer (the run is checkpointed as AWAITING_APPROVAL meanwhile). */
fun interface ApprovalGate {
    suspend fun approve(runId: String, spec: ToolSpec, call: ToolCall, reason: String): Boolean
}

sealed interface AgentEvent {
    data class Status(val checkpoint: RunCheckpoint) : AgentEvent
    data class Thinking(val what: String) : AgentEvent
}

data class AgentLimits(
    val maxIterations: Int = 6,
    val maxConsecutiveErrors: Int = 2,
    val planTokens: Int = 200,
    val actionTokens: Int = 900,
    val composeTokens: Int = 1200,
    val observationChars: Int = 700,
    val llmTimeoutMs: Long = 180_000,
)

/**
 * The agent loop. The LLM only PROPOSES (a plan, then one JSON action per iteration, sampled under a GBNF grammar
 * that only admits the allowed tools with their exact argument shapes); trusted Kotlin code validates arguments,
 * applies the policy (approval for consequential actions), executes with a timeout and feeds the observation back.
 *
 * Bounded: max iterations, max consecutive errors, per-tool timeouts, LLM timeouts, side-effect budget, repeated-call
 * detection. Cancellable at any suspension point. Checkpointed after every step and resumable via [resume].
 */
class AgentLoop(
    private val llm: AgentLlm,
    private val registry: ToolRegistry,
    private val policy: PolicyEngine,
    private val store: RunStore,
    private val approvals: ApprovalGate,
    private val limits: AgentLimits = AgentLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onEvent: (AgentEvent) -> Unit = {},
) {
    private val json = Json { isLenient = true }

    suspend fun start(runId: String, chatId: Long?, request: String, skill: Skill?): RunCheckpoint {
        val now = clock()
        val cp = RunCheckpoint(runId, chatId, skill?.id, request, RunStatus.PLANNING, createdAt = now, updatedAt = now)
        save(cp)
        return drive(cp, skill)
    }

    /** Continues a non-terminal run from its last checkpoint (after process death or an app restart). */
    suspend fun resume(runId: String, skill: Skill?): RunCheckpoint {
        val cp = store.load(runId) ?: error("run $runId not found")
        if (cp.status.isTerminal) return cp
        // A pending approval is asked again: the previous answer (if any) was lost with the process.
        return drive(cp.copy(status = if (cp.plan.isEmpty()) RunStatus.PLANNING else RunStatus.RUNNING), skill)
    }

    private suspend fun save(cp: RunCheckpoint) {
        withContext(NonCancellable) { store.save(cp) }
        onEvent(AgentEvent.Status(cp))
    }

    private fun allowedTools(skill: Skill?): List<ToolSpec> {
        val names = skill?.tools?.toSet()
        return registry.specs.filter { names == null || it.name in names }
    }

    private suspend fun drive(start: RunCheckpoint, skill: Skill?): RunCheckpoint {
        var cp = start
        val tools = allowedTools(skill)
        val maxIter = minOf(limits.maxIterations, skill?.maxSteps ?: limits.maxIterations)
        try {
            if (cp.status == RunStatus.PLANNING) {
                onEvent(AgentEvent.Thinking("Planning"))
                val t0 = clock()
                val plan = makePlan(cp.request, skill, tools)
                cp = cp.copy(
                    status = RunStatus.RUNNING, plan = plan,
                    steps = cp.steps + StepRecord(cp.steps.size, StepKind.PLAN, output = plan.joinToString("\n"), startedAt = t0, durationMs = clock() - t0),
                    updatedAt = clock(),
                )
                save(cp)
            }
            val actionGrammar = Gbnf.of(actionSchema(tools, skill))
            // Small models like to "answer" straight away instead of using the tool their own plan names. Until the
            // first successful tool call, if the plan/request names a tool, the grammar offers ONLY tool calls.
            val toolOnlyGrammar by lazy { Gbnf.of(actionSchema(tools, skill, allowFinal = false)) }
            val namedTools = mentionedTools(cp.request + "\n" + cp.plan.joinToString("\n"), tools)
            val seenCalls = HashSet<String>()
            cp.steps.filter { it.kind == StepKind.TOOL_CALL && it.error == null }.forEach { seenCalls += it.tool + it.input }

            while (cp.iteration < maxIter) {
                currentCoroutineContext().ensureActive()
                onEvent(AgentEvent.Thinking("Step ${cp.iteration + 1} of at most $maxIter"))
                val t0 = clock()
                val mustUseTool = namedTools.isNotEmpty() && cp.steps.none { it.kind == StepKind.TOOL_CALL && it.error == null } && cp.iteration < 2
                val raw = withTimeout(limits.llmTimeoutMs) {
                    llm.complete(actionSystem(skill, tools), actionUser(cp), if (mustUseTool) toolOnlyGrammar else actionGrammar, limits.actionTokens)
                }
                val action = parseAction(raw)
                cp = cp.copy(iteration = cp.iteration + 1, updatedAt = clock())
                if (action == null) {
                    cp = recordError(cp, null, raw.take(300), "model output was not a valid action", t0)
                    if (cp.consecutiveErrors >= limits.maxConsecutiveErrors) return fail(cp, "too many invalid model outputs")
                    continue
                }
                if (action.final != null) {
                    cp = cp.copy(
                        finalAnswer = action.final,
                        steps = cp.steps + StepRecord(cp.steps.size, StepKind.FINAL, thought = action.thought, output = action.final, startedAt = t0, durationMs = clock() - t0),
                    )
                    save(cp)
                    break
                }
                val call = ToolCall(action.tool!!, action.args ?: JsonObject(emptyMap()))
                val key = call.tool + call.args.toString()
                if (key in seenCalls) {
                    cp = recordError(cp, call, call.args.toString(), "repeated identical call; use the earlier result or finish", t0)
                    if (cp.consecutiveErrors >= limits.maxConsecutiveErrors) break // move on to compose/final
                    continue
                }
                cp = executeCall(cp, call, action.thought, skill, t0)
                if (cp.status == RunStatus.FAILED) return cp
                val ok = cp.steps.last().error == null
                if (ok) seenCalls += key
                // A UI skill is done once its canvas is rendered ("finish with render_ui").
                if (ok && skill?.wantsUi == true && (call.tool == "render_ui" || call.tool == "canvas_update")) break
                if (cp.consecutiveErrors >= limits.maxConsecutiveErrors) {
                    // Keep what already worked: only fail when nothing useful happened.
                    if (cp.canvasRendered || cp.steps.any { it.kind == StepKind.TOOL_CALL && it.error == null }) break
                    return fail(cp, "tool calls kept failing: ${cp.steps.last().error}")
                }
            }
            // Skills promise UI output: if the model never rendered it, compose the canvas from the observations.
            if (skill != null && skill.wantsUi && !cp.canvasRendered && registry["render_ui"] != null) {
                cp = compose(cp, skill)
            }
            val done = cp.copy(
                status = RunStatus.COMPLETED,
                finalAnswer = cp.finalAnswer ?: if (cp.canvasRendered) "Done — see the canvas." else lastObservation(cp),
                updatedAt = clock(),
            )
            save(done)
            return done
        } catch (e: TimeoutCancellationException) {
            return fail(cp, "the model took too long (timeout)")
        } catch (e: CancellationException) {
            val c = cp.copy(status = RunStatus.CANCELLED, updatedAt = clock())
            save(c)
            throw e
        } catch (e: Exception) {
            return fail(cp, e.message ?: e.toString())
        }
    }

    private suspend fun fail(cp: RunCheckpoint, why: String): RunCheckpoint {
        val f = cp.copy(status = RunStatus.FAILED, error = why, updatedAt = clock())
        save(f)
        return f
    }

    private suspend fun recordError(cp: RunCheckpoint, call: ToolCall?, input: String, err: String, t0: Long): RunCheckpoint {
        val c = cp.copy(
            consecutiveErrors = cp.consecutiveErrors + 1,
            steps = cp.steps + StepRecord(cp.steps.size, StepKind.ERROR, tool = call?.tool, input = input, error = err, startedAt = t0, durationMs = clock() - t0),
            updatedAt = clock(),
        )
        save(c)
        return c
    }

    private suspend fun executeCall(cp0: RunCheckpoint, call: ToolCall, thought: String, skill: Skill?, t0: Long): RunCheckpoint {
        var cp = cp0
        val errs = registry.validate(call)
        if (errs.isNotEmpty()) return recordError(cp, call, call.args.toString(), "invalid arguments: " + errs.take(3).joinToString("; "), t0)
        val tool = registry[call.tool]!!
        when (val d = policy.decide(call, skill?.tools?.toSet(), cp.sideEffects)) {
            is PolicyDecision.Deny -> return recordError(cp, call, call.args.toString(), "denied by policy: ${d.reason}", t0)
            is PolicyDecision.NeedsApproval -> {
                cp = cp.copy(status = RunStatus.AWAITING_APPROVAL, updatedAt = clock())
                save(cp)
                val ok = approvals.approve(cp.runId, tool.spec, call, d.reason)
                cp = cp.copy(
                    status = RunStatus.RUNNING,
                    steps = cp.steps + StepRecord(cp.steps.size, StepKind.APPROVAL, tool = call.tool, input = call.args.toString(), output = if (ok) "approved" else "declined", startedAt = t0, durationMs = clock() - t0),
                    updatedAt = clock(),
                )
                save(cp)
                if (!ok) {
                    // Declining is not an error: tell the model and let it finish without that action.
                    return cp.copy(steps = cp.steps + StepRecord(cp.steps.size, StepKind.TOOL_CALL, tool = call.tool, thought = thought, input = call.args.toString(), output = "{\"declined\":\"the user declined this action; do not retry it\"}", startedAt = t0))
                        .also { save(it) }
                }
            }
            PolicyDecision.Allow -> Unit
        }
        val start = clock()
        val result: ToolResult = try {
            withTimeout(tool.spec.timeoutMs) { tool.execute(call.args, ToolContext(cp.runId, cp.chatId)) }
        } catch (e: TimeoutCancellationException) {
            ToolResult.Error("tool timed out after ${tool.spec.timeoutMs} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.Error("tool failed: ${e.message ?: e.javaClass.simpleName}")
        }
        val step = StepRecord(
            cp.steps.size, StepKind.TOOL_CALL, tool = call.tool, thought = thought, input = call.args.toString(),
            output = result.json.toString(), error = (result as? ToolResult.Error)?.message, startedAt = start, durationMs = clock() - start,
        )
        cp = cp.copy(
            steps = cp.steps + step,
            consecutiveErrors = if (result is ToolResult.Error) cp.consecutiveErrors + 1 else 0,
            sideEffects = cp.sideEffects + if (result is ToolResult.Ok && policy.hasSideEffects(call.tool)) 1 else 0,
            canvasRendered = cp.canvasRendered || (result is ToolResult.Ok && (call.tool == "render_ui" || call.tool == "canvas_update")),
            updatedAt = clock(),
        )
        save(cp)
        return cp
    }

    private suspend fun compose(cp0: RunCheckpoint, skill: Skill): RunCheckpoint {
        var cp = cp0
        onEvent(AgentEvent.Thinking("Building the canvas"))
        val t0 = clock()
        val types = skill.output.ifEmpty { UiSchema.ALL_TYPES }
        val ctxText = "Task: ${cp.request}\nFindings:\n" + observations(cp, 1500)
        val raw = withTimeout(limits.llmTimeoutMs) {
            llm.complete(CanvasPrompts.system(types) + "\n\nSkill instructions:\n" + skill.instructions, ctxText, CanvasPrompts.grammar(types), limits.composeTokens)
        }
        val obj = runCatching { json.parseToJsonElement(raw) as JsonObject }.getOrNull()
        val call = ToolCall("render_ui", obj ?: JsonObject(emptyMap()))
        val tool = registry["render_ui"]!!
        val result = if (obj == null) ToolResult.Error("canvas JSON did not parse") else
            runCatching { withTimeout(tool.spec.timeoutMs) { tool.execute(obj, ToolContext(cp.runId, cp.chatId)) } }.getOrElse { ToolResult.Error(it.message ?: "render failed") }
        cp = cp.copy(
            steps = cp.steps + StepRecord(cp.steps.size, StepKind.COMPOSE, tool = call.tool, input = raw.take(4000), output = result.json.toString(), error = (result as? ToolResult.Error)?.message, startedAt = t0, durationMs = clock() - t0),
            canvasRendered = result is ToolResult.Ok, updatedAt = clock(),
        )
        save(cp)
        return cp
    }

    // ---------------------------------------------------------------- prompts & schemas

    private suspend fun makePlan(request: String, skill: Skill?, tools: List<ToolSpec>): List<String> {
        val schema = Schema.Obj(listOf("steps" to Schema.Arr(Schema.Str(maxLength = 160), minItems = 1, maxItems = 5)))
        val sys = buildString {
            append("You plan tasks for an offline phone assistant. Write 2-5 short steps (imperative, under 15 words each). ")
            append("Available tools: ").append(tools.joinToString { it.name }).append(".")
            if (skill != null) append("\nSkill \"").append(skill.name).append("\":\n").append(skill.instructions)
            append("\nReply with JSON {\"steps\":[...]}")
        }
        val raw = withTimeout(limits.llmTimeoutMs) { llm.complete(sys, "Task: $request", Gbnf.of(schema), limits.planTokens) }
        val steps = runCatching {
            ((json.parseToJsonElement(raw) as JsonObject)["steps"] as JsonArray).map { (it as JsonPrimitive).content.take(160) }
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }
        return steps.ifEmpty { listOf("Answer the request") }
    }

    /** One alternative per allowed tool ({thought, tool, args}) plus {thought, final}. */
    /** Tools the text refers to (by name, spaced name, or a few obvious keywords). render/canvas tools excluded. */
    fun mentionedTools(text: String, tools: List<ToolSpec>): Set<String> {
        val t = text.lowercase()
        val keywords = mapOf(
            "calculator" to listOf("calculat", "compute"),
            "search_docs" to listOf("search my", "my documents", "my notes", "my pdf", "the documents", "search the doc"),
            "read_document" to listOf("read the document", "read my"),
            "get_datetime" to listOf("today's date", "current date", "current time", "what day"),
            "memory_read" to listOf("my preferences", "remember about me"),
        )
        return tools.map { it.name }.filter { n ->
            n != "render_ui" && n != "canvas_update" &&
                (t.contains(n) || t.contains(n.replace('_', ' ')) || keywords[n].orEmpty().any { t.contains(it) })
        }.toSet()
    }

    fun actionSchema(tools: List<ToolSpec>, skill: Skill?, allowFinal: Boolean = true): Schema {
        val thought = Schema.Str("one short sentence", maxLength = 300)
        val alts = tools.map { t ->
            val input = if ((t.name == "render_ui" || t.name == "canvas_update") && skill != null && skill.output.isNotEmpty()) UiSchema.docSchema(skill.output) else t.input
            Schema.Obj(listOf("thought" to thought, "tool" to Schema.Const(t.name), "args" to input))
        } + if (allowFinal) listOf(Schema.Obj(listOf("thought" to thought, "final" to Schema.Str("answer for the user (markdown)", maxLength = 3000)))) else emptyList()
        return Schema.OneOf(alts)
    }

    private fun actionSystem(skill: Skill?, tools: List<ToolSpec>): String = buildString {
        append("You are an offline assistant on the user's phone that completes tasks with tools.\n")
        append("Each turn reply with ONE JSON object: either a tool call {\"thought\":...,\"tool\":NAME,\"args\":{...}} ")
        append("or, when the task is complete, {\"thought\":...,\"final\":\"answer for the user\"}.\nTools:\n")
        tools.forEach { t -> append("- ").append(t.name).append(t.input.signature()).append(": ").append(t.description).append('\n') }
        if (tools.any { it.name == "render_ui" }) append("render_ui args are a canvas document {\"v\":1,\"title\":...,\"blocks\":[...]}.\n")
        if (skill != null) append("\nFollow this skill (\"").append(skill.name).append("\"):\n").append(skill.instructions).append('\n')
        append("Do not repeat a call that already succeeded. Use only facts from observations or the request.")
    }

    private fun actionUser(cp: RunCheckpoint): String = buildString {
        append("Task: ").append(cp.request).append('\n')
        append("Plan:\n"); cp.plan.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
        val obs = observations(cp, limits.observationChars)
        append(if (obs.isBlank()) "No tool results yet.\n" else "Results so far:\n$obs\n")
        append("Next action:")
    }

    private fun observations(cp: RunCheckpoint, perStep: Int): String = buildString {
        cp.steps.filter { it.kind == StepKind.TOOL_CALL || it.kind == StepKind.ERROR }.takeLast(5).forEach { s ->
            append("- ").append(s.tool ?: "model").append(' ').append(s.input.take(160)).append(" -> ")
            append(s.error?.let { "ERROR: $it" } ?: s.output.take(perStep)).append('\n')
        }
    }

    private fun lastObservation(cp: RunCheckpoint): String =
        cp.steps.lastOrNull { it.kind == StepKind.TOOL_CALL && it.error == null }?.output?.take(1500) ?: "No answer was produced."

    data class Action(val thought: String, val tool: String?, val args: JsonObject?, val final: String?)

    fun parseAction(raw: String): Action? {
        val o = runCatching { json.parseToJsonElement(raw.trim().let { t -> t.substring(t.indexOf('{').coerceAtLeast(0)) }) as JsonObject }.getOrNull() ?: return null
        val thought = (o["thought"] as? JsonPrimitive)?.content ?: ""
        (o["final"] as? JsonPrimitive)?.let { return Action(thought, null, null, it.content) }
        val tool = (o["tool"] as? JsonPrimitive)?.content ?: return null
        val args = o["args"] as? JsonObject ?: buildJsonObject { }
        return Action(thought, tool, args, null)
    }
}

/** Convenience for tests and the history screen. */
fun RunCheckpoint.summary(): String = buildJsonObject {
    put("status", status.name); put("iteration", iteration); put("steps", steps.size)
}.toString()
