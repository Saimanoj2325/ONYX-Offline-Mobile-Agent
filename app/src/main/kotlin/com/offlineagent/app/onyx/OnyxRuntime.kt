package com.offlineagent.app.onyx

import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.automation.OfflineAccessibilityService
import com.offlineagent.app.data.IntentRequest
import com.offlineagent.app.data.ModelRole
import com.offlineagent.app.phone.Notifications
import com.offlineagent.core.agents.AgentDeps
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Blackboard
import com.offlineagent.core.agents.ContextProvider
import com.offlineagent.core.agents.DeviceContext
import com.offlineagent.core.agents.InferenceQueue
import com.offlineagent.core.agents.MemoryHit
import com.offlineagent.core.agents.MemoryStore
import com.offlineagent.core.agents.Orchestrator
import com.offlineagent.core.agents.OrchestratorConfig
import com.offlineagent.core.agents.OutboxProcessor
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.RuntimePlan
import com.offlineagent.core.agents.RuntimeSelector
import com.offlineagent.core.agents.TaskCheckpoint
import com.offlineagent.core.agents.TaskStatus
import com.offlineagent.core.agents.Thermal
import com.offlineagent.core.agents.ThermalGovernor
import com.offlineagent.core.agents.ToolGateway
import com.offlineagent.core.agents.riskOf
import com.offlineagent.core.data.AgentMemoryEntity
import com.offlineagent.core.data.AuditEntity
import com.offlineagent.core.data.Phase5Database
import com.offlineagent.core.data.RoomOutboxStore
import com.offlineagent.core.data.RoomTaskStore
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A confirmation the user must answer on the bottom sheet. */
data class OnyxApproval(val request: ApprovalRequest, val canAlwaysAllow: Boolean, val answer: CompletableDeferred<Boolean>)

/**
 * Phase 5 wiring: the multi-agent [Orchestrator] on the app's ONE loaded model, its Android ports, the Phase 5 Room
 * database (memories, task checkpoints, outbox, audit), WorkManager jobs (resume after process death, outbox when
 * connected) and the device-aware runtime governor.
 */
class OnyxRuntime(private val app: OfflineAgentApp) {
    val prefs = OnyxPrefs(app)
    val monitor = DeviceMonitor(app)
    val db by lazy { Phase5Database.create(app) }
    val dao get() = db.dao()
    val taskStore by lazy { RoomTaskStore(dao) }
    private val outboxStore by lazy { RoomOutboxStore(dao) }

    /** Phase 5 device tools (joined into the shared tool registry in OfflineAgentApp). */
    val extraTools: List<Tool> by lazy { Phase5Tools(app) { i -> app.launcher.launch(i, "settings") }.all() + NetworkTools.create() }

    // ---------------------------------------------------------------- one model, one queue
    private val sharedLlm = object : AgentLlm {
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
            app.engineController.ensure(ModelRole.CHAT) // loads the chat model once; every agent shares it
            return InferenceAgentLlm(app.inference).complete(system, user, grammar, maxTokens)
        }
    }
    val queue = InferenceQueue(sharedLlm)

    private val _plan = MutableStateFlow<RuntimePlan?>(null)
    val runtimePlan: StateFlow<RuntimePlan?> = _plan.asStateFlow()
    private val _hasChatModel = MutableStateFlow(false)
    val hasChatModel: StateFlow<Boolean> = _hasChatModel.asStateFlow()

    // ---------------------------------------------------------------- runs
    private val _current = MutableStateFlow<Blackboard?>(null)
    val current: StateFlow<Blackboard?> = _current.asStateFlow()
    private val _pending = MutableStateFlow<OnyxApproval?>(null)
    val pending: StateFlow<OnyxApproval?> = _pending.asStateFlow()
    @Volatile private var job: Job? = null
    @Volatile private var activeRunId: String? = null
    val isRunning: Boolean get() = job?.isActive == true
    fun isActive(runId: String) = isRunning && activeRunId == runId

    fun start() {
        app.appScope.launch(Dispatchers.IO) {
            prefs.load()
            withContext(Dispatchers.Main) { monitor.start() }
            refreshDevice()
            _hasChatModel.value = runCatching { app.models.active(ModelRole.CHAT) != null }.getOrDefault(false)
            // Interrupted runs (process died mid-task) → WorkManager resumes them; open outbox items → drain when online.
            runCatching { if (taskStore.interrupted().isNotEmpty()) TaskResumeWorker.schedule(app, 0) }
            runCatching { if (dao.openOutbox().isNotEmpty()) OutboxWorker.schedule(app) }
        }
        // Thermal / battery-saver governor: pause new model work when the phone is hot.
        app.appScope.launch {
            combine(monitor.thermal, monitor.powerSave, prefs.values) { t, ps, p -> Triple(t, ps, p.thermalGuard) }.collect { (t, ps, guard) ->
                val d = ThermalGovernor.decide(t, ps, 4)
                if (guard && d.pauseInference) queue.pause("Phone is hot (${t.name.lowercase()})") else queue.resume()
                refreshDevice()
            }
        }
        app.appScope.launch { monitor.online.collect { if (it) runCatching { if (dao.openOutbox().isNotEmpty()) OutboxWorker.schedule(app) } } }
    }

    suspend fun refreshDevice() = withContext(Dispatchers.IO) { runCatching { _plan.value = RuntimeSelector.plan(monitor.snapshot()) } }
    suspend fun refreshModelState() { _hasChatModel.value = runCatching { app.models.active(ModelRole.CHAT) != null }.getOrDefault(false) }

    private fun deps(): AgentDeps = AgentDeps(
        queue = if (_hasChatModel.value) queue else null,
        tools = gateway, memory = memory, context = context,
        approvals = { r -> askUser(r) },
        network = { monitor.online.value },
        outbox = { runId, tool, args, risk ->
            OutboxProcessor(outboxStore, gateway, { monitor.online.value }).enqueue(runId, tool, args, risk).also { OutboxWorker.schedule(app) }
        },
        audit = { runId, actor, action, detail, risk -> audit(runId, actor, action, detail, risk) },
        store = taskStore,
    )

    private fun orchestrator() = Orchestrator(deps(), OrchestratorConfig(llmVerification = prefs.values.value.llmVerification))

    /** Starts a multi-agent task. Returns null (with a reason) when the model is busy with another job. */
    fun run(request: String): String? {
        if (isRunning) return "ONYX is already working on a task."
        if (app.agents.isRunning) return "The chat agent is running; wait for it to finish."
        val runId = "task-" + UUID.randomUUID().toString().take(8)
        val board = Blackboard(runId, request)
        _current.value = board
        activeRunId = runId
        TaskResumeWorker.schedule(app, WATCHDOG_MIN) // resumes it if the process dies mid-run
        job = app.appScope.launch {
            refreshModelState()
            val cp = orchestrator().run(request, board)
            finished(cp)
        }
        return null
    }

    /** Resume from the Tasks screen (in-process) or from [TaskResumeWorker] (after process death). */
    fun resume(runId: String): Job? {
        if (isRunning) return null
        activeRunId = runId
        val j = app.appScope.launch {
            val cp = taskStore.load(runId) ?: return@launch
            refreshModelState()
            val board = Blackboard(runId, cp.request)
            _current.value = board
            finished(orchestrator().resume(cp, board))
        }
        job = j
        return j
    }

    private suspend fun finished(cp: TaskCheckpoint) {
        if (taskStore.interrupted().isEmpty()) WorkManager.getInstance(app).cancelUniqueWork(TaskResumeWorker.NAME)
        if (cp.status == TaskStatus.COMPLETED && prefs.values.value.voiceReplies) cp.answer?.let { speakRequests.tryEmit(it) }
    }

    val speakRequests = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)

    fun cancel() { job?.cancel(); _pending.value?.answer?.complete(false); _pending.value = null; app.inference.cancel() }
    fun dismissCurrent() { if (!isRunning) _current.value = null }

    private suspend fun askUser(r: ApprovalRequest): Boolean {
        val spec = gateway.spec(r.tool)
        val always = spec != null && r.risk < Risk.HIGH && spec.permission.canAlwaysAllow
        if (always && r.tool in app.cachedSettings.alwaysAllowedTools) return true
        val a = OnyxApproval(r, always, CompletableDeferred())
        _pending.value = a
        Notifications.post(app, Notifications.CH_AGENT, 5050, "ONYX needs your OK", "${r.title} — ${r.risk.label} risk", "onyx")
        // A background (WorkManager) resume cannot wait forever: no answer in 9 minutes = declined.
        val ok = withTimeoutOrNull(9 * 60_000L) { a.answer.await() } ?: false
        _pending.value = null
        return ok
    }

    fun answer(allow: Boolean, always: Boolean) {
        val p = _pending.value ?: return
        if (allow && always && p.canAlwaysAllow) app.appScope.launch { app.settings.update { it.copy(alwaysAllowedTools = it.alwaysAllowedTools + p.request.tool) } }
        p.answer.complete(allow)
        _pending.value = null
    }

    suspend fun audit(runId: String, actor: String, action: String, detail: String, risk: Risk) =
        dao.insertAudit(AuditEntity(at = System.currentTimeMillis(), runId = runId, actor = actor, action = action, detail = detail.take(1000), risk = risk.name))

    // ---------------------------------------------------------------- tool gateway
    private val modelTools = setOf("summarize", "run_phone_task", "fill_form", "create_pdf")

    fun networkTools(): Set<String> = NetworkTools.names + app.mcp.networkToolNames()

    val gateway = object : ToolGateway {
        private fun tools(): List<Tool> = app.agents.registry(null).let { reg -> reg.names.mapNotNull { reg[it] } }
        override fun specs(): List<ToolSpec> = tools().map { it.spec }
        override fun spec(name: String): ToolSpec? = app.agents.registry(null)[name]?.spec
        override fun requiresNetwork(name: String) = name in networkTools()
        override fun usesModel(name: String) = name in modelTools
        override fun risk(name: String, args: JsonObject): Risk = spec(name)?.let { s -> riskOf(s).let { if (requiresNetwork(name) && it < Risk.MEDIUM) Risk.MEDIUM else it } } ?: Risk.HIGH
        override suspend fun execute(name: String, args: JsonObject): ToolResult {
            val t = app.agents.registry(null)[name] ?: return ToolResult.Error("tool $name is not available", retryable = false)
            return withTimeoutOrNull(t.spec.timeoutMs) { t.execute(args, ToolContext("onyx", null)) } ?: ToolResult.Error("$name timed out")
        }
    }

    // ---------------------------------------------------------------- memory (user-controlled)
    val memory = object : MemoryStore {
        override val enabled: Boolean get() = prefs.values.value.memoryEnabled
        private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        override suspend fun recall(query: String, limit: Int): List<MemoryHit> = withContext(Dispatchers.IO) {
            if (!enabled) return@withContext emptyList()
            val q = words(query)
            val mine = dao.memories().map { m -> m to (words(m.text) intersect q).size + if (m.pinned) 1 else 0 }
                .filter { it.second > 0 }.sortedByDescending { it.second }.take(limit)
                .map { (m, s) -> MemoryHit("memory", m.category, m.text, s.toDouble()) }
            val docs = if (app.cachedSettings.useDocuments) runCatching { app.retriever.search(query, 3) }.getOrDefault(emptyList())
                .map { MemoryHit("document", it.candidate.documentTitle, it.candidate.text, it.score.toDouble()) } else emptyList()
            (mine + docs).take(limit)
        }
        override suspend fun remember(text: String, source: String) {
            // Only explicit "remember …" requests become memories automatically; everything else stays in task history.
            val m = Regex("^(?:please\\s+)?remember(?:\\s+that)?\\s+(.{3,400})", RegexOption.IGNORE_CASE).find(text.removePrefix("Asked: ").trim()) ?: return
            val now = System.currentTimeMillis()
            dao.upsertMemory(AgentMemoryEntity(text = m.groupValues[1].trim(), category = "fact", source = source, createdAt = now, updatedAt = now))
        }
    }

    // ---------------------------------------------------------------- context
    val context = object : ContextProvider {
        override suspend fun snapshot(includeScreen: Boolean): DeviceContext {
            val (pct, charging) = monitor.battery()
            val zone = ZoneId.systemDefault()
            val fg = OfflineAccessibilityService.lastForegroundPackage?.let { pkg ->
                runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            }
            val screen = if (includeScreen && OfflineAccessibilityService.isEnabled(app)) runCatching { app.automation.describeScreen() }.getOrNull() else null
            return DeviceContext(ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")), zone.id, pct, charging,
                monitor.powerSave.value, monitor.online.value, monitor.network.value, monitor.thermal.value.name.lowercase(), fg, screen)
        }
    }

    // ---------------------------------------------------------------- memory screen actions
    suspend fun exportMemories(): String = withContext(Dispatchers.IO) {
        val arr = kotlinx.serialization.json.buildJsonArray {
            dao.memories().forEach { m -> add(kotlinx.serialization.json.buildJsonObject {
                put("text", kotlinx.serialization.json.JsonPrimitive(m.text)); put("category", kotlinx.serialization.json.JsonPrimitive(m.category))
                put("pinned", kotlinx.serialization.json.JsonPrimitive(m.pinned)); put("created", kotlinx.serialization.json.JsonPrimitive(m.createdAt))
            }) }
        }
        arr.toString()
    }

    companion object { const val WATCHDOG_MIN = 3L }
}

/**
 * Resumes interrupted multi-agent tasks after process death. Scheduled (a) at every task start with a short delay as
 * a watchdog and (b) at app start when Room holds unfinished runs. If the task is still alive in this process the
 * worker just re-arms itself.
 */
class TaskResumeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OfflineAgentApp
        val onyx = app.onyx
        val runs = onyx.taskStore.interrupted()
        if (runs.isEmpty()) return Result.success()
        if (onyx.isRunning) { schedule(app, OnyxRuntime.WATCHDOG_MIN); return Result.success() }
        for (cp in runs.sortedBy { it.updatedAt }) {
            if (cp.status == TaskStatus.AWAITING_APPROVAL || cp.status == TaskStatus.RUNNING || cp.status == TaskStatus.PLANNING || cp.status == TaskStatus.INTERRUPTED) {
                onyx.audit(cp.runId, "System", "resumed after interruption", cp.request.take(200), Risk.NONE)
                onyx.resume(cp.runId)?.join()
            }
        }
        return Result.success()
    }

    companion object {
        const val NAME = "onyx-task-resume"
        fun schedule(ctx: Context, delayMin: Long) {
            val req = OneTimeWorkRequestBuilder<TaskResumeWorker>().setInitialDelay(delayMin, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, req)
        }
    }
}

/** Runs queued network actions once a validated connection exists (WorkManager NetworkType.CONNECTED). */
class OutboxWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as OfflineAgentApp
        val onyx = app.onyx
        val proc = OutboxProcessor(RoomOutboxStore(onyx.dao), onyx.gateway, { true })
        val r = proc.drain { item ->
            onyx.audit(item.runId, "Outbox", "${item.status.name.lowercase()} ${item.tool}", (item.result ?: item.lastError ?: "").take(300), item.risk)
            Notifications.post(app, Notifications.CH_AGENT, item.id.hashCode(), if (item.status.name == "DONE") "Queued action done" else "Queued action failed",
                "${item.tool}: ${(item.result ?: item.lastError ?: "").take(200)}", "onyx-tasks")
        }
        return if (r.needsRetry) Result.retry() else Result.success()
    }

    companion object {
        const val NAME = "onyx-outbox"
        fun schedule(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, req)
        }
    }
}

