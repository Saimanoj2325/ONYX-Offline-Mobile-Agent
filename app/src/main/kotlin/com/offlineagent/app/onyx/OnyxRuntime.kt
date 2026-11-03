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
        // Phase 5b: token streaming for live agent thinking / the Writer's answer.
        override suspend fun stream(system: String, user: String, grammar: String?, maxTokens: Int, onPartial: (String) -> Unit): String {
            app.engineController.ensure(ModelRole.CHAT)
            return InferenceAgentLlm(app.inference).stream(system, user, grammar, maxTokens, onPartial)
        }
    }

    /** Phase 5b: the first-run model onboarding was dismissed (shared by Home and Chat so it shows once). */
    val onboardingDismissed = MutableStateFlow(false)
    val queue = InferenceQueue(sharedLlm)

    /** Phase 6: on-phone benchmark (Agents → Models). */
    val benchmark by lazy { Benchmark(app) }

    /** Phase 6: thermal / battery decision applied to the loaded model (threads, batch, speculation, idle unload). */
    private val _power = MutableStateFlow<com.offlineagent.core.agents.PowerGovernor.Decision?>(null)
    val power: StateFlow<com.offlineagent.core.agents.PowerGovernor.Decision?> = _power.asStateFlow()
    @Volatile private var applied: com.offlineagent.core.agents.PowerGovernor.Decision? = null
    @Volatile private var lastModel: Any? = null

    /** Phase 6: unloads the model after N idle minutes (Settings; shorter on low battery). Never during a task. */
    val idleUnloader by lazy {
        com.offlineagent.core.agents.IdleUnloader(app.appScope, System::currentTimeMillis,
            timeoutMs = {
                val min = app.cachedSettings.idleUnloadMinutes
                if (min <= 0) 0L else minOf(min * 60_000L, _power.value?.idleUnloadMs ?: Long.MAX_VALUE)
            },
            busy = { isRunning || app.agents.isRunning || app.indexer.isBusy || com.offlineagent.app.work.LongTasks.current.value != null ||
                app.inference.state.value is com.offlineagent.core.inference.EngineState.Generating },
            loaded = { app.inference.state.value is com.offlineagent.core.inference.EngineState.Ready },
            unload = { why -> com.offlineagent.app.diag.Diagnostics.log("idle unload: $why"); app.inference.unload(com.offlineagent.core.inference.UnloadReason.LOW_MEMORY) },
        )
    }

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
            // Phase 5b: follow downloads / deletes / switches so Home and the agents see a new model immediately.
            launch { app.models.models.collect { l -> _hasChatModel.value = l.any { it.role == ModelRole.CHAT.name && it.active && java.io.File(it.path).isFile } } }
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
                runCatching { applyPower() }
            }
        }
        // Phase 6: battery + thermal governor every minute (and on every thermal / saver change): live thread / speculation
        // changes between requests, idle-unload timeout.
        app.appScope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { applyPower() }
                kotlinx.coroutines.delay(60_000)
            }
        }
        app.appScope.launch {
            app.inference.state.collect { st ->
                if (st is com.offlineagent.core.inference.EngineState.Generating || st is com.offlineagent.core.inference.EngineState.Ready) idleUnloader.touch()
                // A fresh load already used the current decision (EngineController reads it): remember it as applied.
                if (st is com.offlineagent.core.inference.EngineState.Ready && st.model !== lastModel) { lastModel = st.model; applied = _power.value }
            }
        }
        app.appScope.launch { monitor.online.collect { if (it) runCatching { if (dao.openOutbox().isNotEmpty()) OutboxWorker.schedule(app) } } }
    }

    /** Recomputes the power decision; pushes thread / speculation changes to the loaded model when they changed. */
    suspend fun applyPower(force: Boolean = false) {
        val (pct, charging) = monitor.battery()
        val base = app.cachedSettings.threads.takeIf { it > 0 } ?: com.offlineagent.engine.llamacpp.DeviceInfo.recommendedThreads()
        val d = com.offlineagent.core.agents.PowerGovernor.decide(monitor.thermal.value, monitor.powerSave.value, base, pct, charging, app.cachedSettings.batchSize)
        _power.value = d
        val prev = applied
        if (app.inference.state.value !is com.offlineagent.core.inference.EngineState.Ready) return
        if (!force && prev != null && prev.threads == d.threads && prev.speculation == d.speculation && prev.pollLevel == d.pollLevel) return
        val s = app.cachedSettings
        app.inference.setThreads(d.threads, minOf(s.pollLevel, d.pollLevel))
        app.inference.setSpeculation(if (s.ngramSpec && d.speculation && !s.safeMode && !s.speculative) s.ngramDraft else 0)
        applied = d
        idleUnloader.schedule()
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
    fun run(request: String): String? = start(request).error

    data class StartResult(val runId: String?, val error: String?)

    // Boards of this process's runs (Chat looks them up by runId to show the live trace inside the message).
    private val _boards = MutableStateFlow<Map<String, Blackboard>>(emptyMap())
    val boards: StateFlow<Map<String, Blackboard>> = _boards.asStateFlow()
    fun board(runId: String?): Blackboard? = runId?.let { _boards.value[it] }
    /** Read-only board of a saved run (older chat messages after a restart). */
    suspend fun loadBoard(runId: String): Blackboard? = board(runId) ?: withContext(Dispatchers.IO) { taskStore.load(runId)?.let { Blackboard.fromCheckpoint(it) } }

    /**
     * Phase 5b: starts a task from Home or from Chat. [conversation] = earlier chat turns (follow-ups), [onFinished]
     * is ALWAYS called once — also when the run is stopped — with the final checkpoint.
     */
    fun start(request: String, conversation: String = "", onFinished: (suspend (TaskCheckpoint) -> Unit)? = null): StartResult {
        if (isRunning) return StartResult(null, "ONYX is already working on a task.")
        if (app.agents.isRunning) return StartResult(null, "The chat agent is running; wait for it to finish.")
        val runId = "task-" + UUID.randomUUID().toString().take(8)
        val board = Blackboard(runId, request, conversation = conversation)
        _current.value = board
        _boards.value = (_boards.value + (runId to board)).entries.toList().takeLast(30).associate { it.key to it.value }
        activeRunId = runId
        TaskResumeWorker.schedule(app, WATCHDOG_MIN) // resumes it if the process dies mid-run
        job = app.appScope.launch {
            refreshModelState()
            val cp = try { orchestrator().run(request, board) }
            catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.NonCancellable) { onFinished?.invoke(board.checkpoint()) }
                throw e
            }
            finished(cp)
            onFinished?.invoke(cp)
        }
        return StartResult(runId, null)
    }

    /** Resume from the Tasks screen (in-process) or from [TaskResumeWorker] (after process death). */
    fun resume(runId: String): Job? {
        if (isRunning) return null
        activeRunId = runId
        val j = app.appScope.launch {
            val cp = taskStore.load(runId) ?: return@launch
            refreshModelState()
            val board = Blackboard(runId, cp.request, conversation = cp.conversation)
            _current.value = board
            _boards.value = _boards.value + (runId to board)
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

