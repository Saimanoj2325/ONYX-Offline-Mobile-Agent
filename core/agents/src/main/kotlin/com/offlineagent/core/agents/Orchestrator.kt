package com.offlineagent.core.agents

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Runs a [TaskPlan] DAG: every node whose dependencies are all finished starts at once (bounded by [maxParallel]),
 * so independent branches (Context ∥ Memory ∥ several Device reads) overlap. Model calls inside them still go one
 * at a time through the [InferenceQueue]; tool/IO work runs truly in parallel.
 * A node whose dependency failed is SKIPPED unless it is [tolerant] (the Writer always runs, to explain what happened).
 */
class DagScheduler(private val maxParallel: Int = 3) {
    suspend fun run(plan: TaskPlan, board: Blackboard, tolerant: (TaskNode) -> Boolean, execute: suspend (TaskNode) -> Unit) = coroutineScope {
        val byId = plan.nodes.associateBy { it.id }
        val started = mutableSetOf<String>()
        plan.nodes.filter { board.node(it.id)?.status?.isTerminal == true }.forEach { started += it.id }
        val done = Channel<String>(Channel.UNLIMITED)
        val sem = Semaphore(maxParallel)
        var inFlight = 0
        while (true) {
            var progressed = true
            while (progressed) {
                progressed = false
                for (n in plan.nodes) {
                    if (n.id in started) continue
                    val deps = n.dependsOn.mapNotNull { board.node(it) }
                    if (!deps.all { it.status.isTerminal }) continue
                    started += n.id
                    if (!tolerant(n) && deps.any { !it.status.satisfiesDependants }) {
                        board.updateNode(n.id) { it.copy(status = NodeStatus.SKIPPED, error = "skipped: a step it needs did not finish") }
                        progressed = true // may unblock (skip) further nodes
                        continue
                    }
                    inFlight++
                    launch { try { sem.withPermit { execute(byId.getValue(n.id)) } } finally { done.send(n.id) } }
                }
            }
            if (inFlight == 0) break
            done.receive(); inFlight--
        }
    }
}

data class OrchestratorConfig(
    val maxAttempts: Int = 2,
    val maxParallel: Int = 3,
    val llmVerification: Boolean = true,
)

/**
 * Central orchestrator. Plans (Planner, with rule fallback), then delegates each DAG node to the specialised agent,
 * verifies results (Verifier), retries with the error fed back, re-delegates when an agent keeps failing (e.g. a failed
 * Device read is answered from Memory), checkpoints after every node and can [resume] after process death.
 */
class Orchestrator(private val deps: AgentDeps, private val config: OrchestratorConfig = OrchestratorConfig()) {
    private val planner = PlannerAgent(deps)
    private val verifier = VerificationAgent(deps, config.llmVerification)
    private val agents: Map<AgentRole, Agent> = listOf(
        MemoryAgent(deps), ContextAgent(deps), DeviceAgent(deps), ExecutionAgent(deps), WriterAgent(deps),
    ).associateBy { it.role }

    /** Where a node goes after its own agent failed every attempt. Actions are never re-delegated (no surprise side effects). */
    private fun fallbackFor(node: TaskNode): AgentRole? = when (node.agent) {
        AgentRole.DEVICE -> AgentRole.MEMORY
        else -> null
    }

    suspend fun run(request: String, board: Blackboard): TaskCheckpoint = guarded(board) {
        board.working(AgentRole.ORCHESTRATOR, "Understanding your request")
        board.working(AgentRole.PLANNER, "Planning")
        val plan = planner.plan(request, board).let { p -> if (p.problems().isEmpty()) p else PlannerAgent.assemble(request, emptyList(), "fallback") }
        board.working(AgentRole.PLANNER, null)
        board.setPlan(plan)
        board.setStatus(TaskStatus.RUNNING)
        deps.store.save(board.checkpoint())
        execute(plan, board)
    }

    /** Continues a checkpointed run: finished nodes are kept, the rest run again (actions ask for approval again). */
    suspend fun resume(cp: TaskCheckpoint, board: Blackboard): TaskCheckpoint = guarded(board) {
        val plan = cp.plan
        board.restore(cp)
        if (plan == null) {
            board.working(AgentRole.PLANNER, "Planning")
            val p = planner.plan(cp.request, board)
            board.working(AgentRole.PLANNER, null)
            board.setPlan(p)
            execute(p, board)
        } else execute(plan, board)
    }

    private suspend fun guarded(board: Blackboard, body: suspend () -> TaskCheckpoint): TaskCheckpoint = try {
        body()
    } catch (e: CancellationException) {
        withContext(NonCancellable) { board.finish(TaskStatus.CANCELLED, null, "Stopped"); deps.store.save(board.checkpoint()) }
        throw e
    } catch (e: Exception) {
        board.finish(TaskStatus.FAILED, null, e.message ?: e.toString())
        board.checkpoint().also { deps.store.save(it) }
    }

    private suspend fun execute(plan: TaskPlan, board: Blackboard): TaskCheckpoint {
        board.working(AgentRole.ORCHESTRATOR, "Delegating ${plan.nodes.size} steps")
        DagScheduler(config.maxParallel).run(plan, board, tolerant = { it.agent == AgentRole.WRITER }) { node -> runNode(node, board) }
        val st = board.state.value
        val answerNode = st.nodes["answer"]
        val status = if (answerNode?.status == NodeStatus.DONE) TaskStatus.COMPLETED else TaskStatus.FAILED
        board.finish(status, answerNode?.output?.takeIf { status == TaskStatus.COMPLETED }, if (status == TaskStatus.FAILED) answerNode?.error ?: "No answer" else null)
        val cp = board.checkpoint()
        deps.store.save(cp)
        if (status == TaskStatus.COMPLETED && deps.memory.enabled) runCatching { deps.memory.remember("Asked: ${board.request.take(200)}", "task:${board.runId}") }
        return cp
    }

    private suspend fun runNode(node: TaskNode, board: Blackboard) {
        var role = node.agent
        var lastError: String? = null
        var attempt = board.node(node.id)?.attempts ?: 0
        val now = deps.clock
        board.updateNode(node.id) { it.copy(status = NodeStatus.RUNNING, startedAt = now(), error = null) }
        while (true) {
            attempt++
            val agent = agents[role] ?: error("no agent for $role")
            board.message(AgentMessage.Delegate(AgentRole.ORCHESTRATOR, role, node, attempt))
            board.working(role, node.goal)
            board.updateNode(node.id) { it.copy(attempts = attempt) }
            val outcome = try {
                agent.handle(if (role == node.agent) node else node.copy(agent = role), board, attempt, lastError)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                NodeOutcome.Fail(e.message ?: e.javaClass.simpleName)
            } finally { board.working(role, null) }

            when (outcome) {
                is NodeOutcome.Ok -> {
                    val report = if (node.verify) {
                        board.updateNode(node.id) { it.copy(status = NodeStatus.VERIFYING) }
                        board.working(AgentRole.VERIFIER, "Checking: ${node.goal}")
                        verifier.verify(node.copy(agent = role), outcome, board).also { board.working(AgentRole.VERIFIER, null) }
                    } else null
                    if (report != null) board.message(AgentMessage.Verification(AgentRole.VERIFIER, AgentRole.ORCHESTRATOR, node.id, report.passed, report.issues))
                    // The Writer's last attempt is kept even if unverified (marked), so the user always gets an answer.
                    val lastTry = attempt >= config.maxAttempts
                    if (report == null || report.passed || (node.agent == AgentRole.WRITER && lastTry)) {
                        board.message(AgentMessage.Result(role, AgentRole.ORCHESTRATOR, node.id, outcome.output.take(300), outcome.data))
                        board.updateNode(node.id) { it.copy(status = NodeStatus.DONE, output = outcome.output, verified = report?.passed, endedAt = now(), delegatedTo = role.takeIf { r -> r != node.agent }) }
                        break
                    }
                    lastError = report.issues.joinToString("; ")
                }
                is NodeOutcome.Fail -> {
                    board.message(AgentMessage.Failure(role, AgentRole.ORCHESTRATOR, node.id, outcome.error, outcome.retryable))
                    lastError = outcome.error
                    if (!outcome.retryable) attempt = maxOf(attempt, config.maxAttempts)
                }
                is NodeOutcome.Denied -> {
                    board.updateNode(node.id) { it.copy(status = NodeStatus.DENIED, error = outcome.reason, endedAt = now()) }
                    break
                }
                is NodeOutcome.Queued -> {
                    board.updateNode(node.id) { it.copy(status = NodeStatus.QUEUED_OFFLINE, output = outcome.note, endedAt = now()) }
                    break
                }
            }
            if (attempt >= config.maxAttempts) {
                val fb = fallbackFor(node)
                if (fb != null && role == node.agent) {
                    board.note("${role.title} could not finish \"${node.goal}\"; re-delegating to ${fb.title}")
                    role = fb
                    continue
                }
                board.updateNode(node.id) { it.copy(status = NodeStatus.FAILED, error = lastError, endedAt = now()) }
                break
            }
        }
        deps.store.save(board.checkpoint())
    }
}
