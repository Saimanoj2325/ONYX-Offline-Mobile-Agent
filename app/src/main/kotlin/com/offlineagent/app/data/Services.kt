package com.offlineagent.app.data

import com.offlineagent.core.data.storedMessages
import com.offlineagent.core.data.AgentDao
import com.offlineagent.core.data.ChatDao
import com.offlineagent.core.data.MemoryEntity
import com.offlineagent.core.data.MiscDao
import com.offlineagent.core.data.ModelEntity
import com.offlineagent.core.data.NoteEntity
import com.offlineagent.core.data.RoomRunStore
import com.offlineagent.core.data.SettingsRepository
import com.offlineagent.core.data.SkillDao
import com.offlineagent.core.data.SkillEntity
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.InferenceAdapter
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.ModelInfo
import com.offlineagent.core.runtime.agent.AgentEvent
import com.offlineagent.core.runtime.agent.AgentLimits
import com.offlineagent.core.runtime.agent.AgentLoop
import com.offlineagent.core.runtime.agent.ApprovalGate
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.agent.RunCheckpoint
import com.offlineagent.core.runtime.rag.KeywordScorer
import com.offlineagent.core.runtime.skills.BuiltinSkills
import com.offlineagent.core.runtime.skills.Skill
import com.offlineagent.core.runtime.skills.SkillParseResult
import com.offlineagent.core.runtime.skills.SkillParser
import com.offlineagent.core.runtime.tools.CalculatorTool
import com.offlineagent.core.runtime.tools.CalendarEventTool
import com.offlineagent.core.runtime.tools.CanvasPort
import com.offlineagent.core.runtime.tools.CreateNoteTool
import com.offlineagent.core.runtime.tools.DateTimeTool
import com.offlineagent.core.runtime.tools.DocInfo
import com.offlineagent.core.runtime.tools.DocumentPort
import com.offlineagent.core.runtime.tools.IntentPort
import com.offlineagent.core.runtime.tools.MemoryPort
import com.offlineagent.core.runtime.tools.MemoryReadTool
import com.offlineagent.core.runtime.tools.MemoryWriteTool
import com.offlineagent.core.runtime.tools.NotesPort
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.ReadDocumentTool
import com.offlineagent.core.runtime.tools.RenderUiTool
import com.offlineagent.core.runtime.tools.SearchDocsTool
import com.offlineagent.core.runtime.tools.SearchHit
import com.offlineagent.core.runtime.tools.ShareTextTool
import com.offlineagent.core.runtime.tools.SummarizeTool
import com.offlineagent.core.runtime.tools.SummarizerPort
import com.offlineagent.core.runtime.tools.ToolCall
import com.offlineagent.core.runtime.tools.ToolRegistry
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.ui.UiDoc
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import com.offlineagent.core.runtime.ui.UiSerializer
import com.offlineagent.engine.llamacpp.DeviceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import com.offlineagent.core.data.AppSettings
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** What the user should know about the engine: self-test outcome, safe mode, compatibility mode. */
data class EngineHealth(
    val selfTest: com.offlineagent.core.inference.SelfTest.Result? = null,
    val safeMode: Boolean = false,
    val compatCpu: Boolean = false,
    /** Banner text (null = all good). */
    val message: String? = null,
    /** The fix needs an app restart (compatibility CPU kernels are chosen when the native library starts). */
    val needsRestart: Boolean = false,
)

/**
 * Loads the right model for a request (chat or vision).
 *
 * Phase 4 (P0 garbage-output/crash fix):
 *  - defaults: 8K context (4K on ≤ 6 GB phones), f16 KV; 16K+ only with the "large context" opt-in; the window is
 *    lowered automatically when the estimate would not fit in RAM (an oversized f16/32K load was a likely crash);
 *  - safe mode: 4K, f16 KV, flash attention off, no prompt cache, no draft model, no core pinning;
 *  - every load runs under a native crash marker and is followed by a greedy [SelfTest]; degenerate output switches to
 *    safe mode and reloads; if it is still degenerate, the conservative CPU kernel variant is selected for the next
 *    start (needs a restart);
 *  - never throws anything but a readable exception; never runs on the main thread.
 */
class EngineController(
    private val inference: InferenceAdapter,
    private val models: ModelStore,
    private val settings: SettingsRepository,
    private val onPrefixCacheInvalid: () -> Unit = {},
    /** Phase 6: current thermal/battery decision (null = unknown). */
    private val powerDecision: () -> com.offlineagent.core.agents.PowerGovernor.Decision? = { null },
) {
    private val mutex = Mutex()
    private val _loaded = MutableStateFlow<ModelEntity?>(null)
    val loaded: StateFlow<ModelEntity?> = _loaded.asStateFlow()
    private val _plan = MutableStateFlow<com.offlineagent.core.inference.ContextPlan?>(null)
    /** Context plan for the loaded (or selected) chat model on this phone. */
    val plan: StateFlow<com.offlineagent.core.inference.ContextPlan?> = _plan.asStateFlow()
    private val _health = MutableStateFlow(EngineHealth(compatCpu = com.offlineagent.app.diag.Diagnostics.compatVariant().isNotBlank()))
    val health: StateFlow<EngineHealth> = _health.asStateFlow()
    @Volatile var lastConfig: LoadConfig? = null
        private set
    /** Settings changed while a model was generating: reload when it finishes. */
    @Volatile private var reloadPending = false

    suspend fun threads(): Int = settings.current().threads.takeIf { it > 0 } ?: DeviceInfo.recommendedThreads()

    /**
     * GGUF header (cached per path/size/mtime). Always on [Dispatchers.IO]: Phase 4b ANR fix — this used to run on the
     * main thread from [config] (reload from the chat screen) and took > 5 s on a phone.
     */
    suspend fun archOf(path: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { com.offlineagent.core.inference.GgufMetadata.arch(File(path)) }.getOrNull()
    }

    suspend fun planFor(path: String?, s: AppSettings): com.offlineagent.core.inference.ContextPlan {
        val arch = path?.let { archOf(it) }
        return com.offlineagent.core.inference.ContextPlanner.plan(DeviceInfo.totalRamBytes(), arch, s.ubatchSize)
            .also { if (path != null) _plan.value = it }
    }

    /** Plan without reading any model file (safe on the main thread). */
    fun planWithoutModel(s: AppSettings): com.offlineagent.core.inference.ContextPlan =
        _plan.value ?: com.offlineagent.core.inference.ContextPlanner.plan(DeviceInfo.totalRamBytes(), null, s.ubatchSize)

    /** Effective KV precision: the user's explicit choice, else f16 (Auto). Safe mode: always f16. */
    fun kvFor(s: AppSettings, modelPath: String?, ctx: Int): com.offlineagent.core.inference.KvCacheType =
        if (s.safeMode) com.offlineagent.core.inference.KvCacheType.F16
        else runCatching { com.offlineagent.core.inference.KvCacheType.valueOf(s.kvCache) }.getOrNull() ?: com.offlineagent.core.inference.KvCacheType.F16

    /** Effective context: the user's choice (16K+ needs the opt-in), or the RAM-based default; 4K in safe mode. */
    fun effectiveContext(s: AppSettings, plan: com.offlineagent.core.inference.ContextPlan): Int {
        val want = if (s.contextLength > 0) s.contextLength else plan.recommendedContext
        val gated = if (want > com.offlineagent.core.inference.ContextPlanner.LARGE_CONTEXT && !s.largeContext) com.offlineagent.core.inference.ContextPlanner.LARGE_CONTEXT else want
        return if (s.safeMode) minOf(gated, SAFE_CONTEXT) else gated
    }

    fun maxOutput(s: AppSettings, plan: com.offlineagent.core.inference.ContextPlan, ctx: Int) =
        (if (s.maxOutputTokens > 0) s.maxOutputTokens else plan.maxOutputTokens).coerceAtMost(ctx / 2).coerceIn(256, 8192)

    private suspend fun config(model: ModelEntity, role: ModelRole): LoadConfig = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        configIo(model, role)
    }

    private suspend fun configIo(model: ModelEntity, role: ModelRole): LoadConfig {
        val s = settings.current()
        val plan = planFor(model.path, s)
        val arch = archOf(model.path)
        val kv = kvFor(s, model.path, effectiveContext(s, plan))
        val fa = if (s.safeMode) com.offlineagent.core.inference.FlashAttention.OFF
            else runCatching { com.offlineagent.core.inference.FlashAttention.valueOf(s.flashAttention) }.getOrDefault(com.offlineagent.core.inference.FlashAttention.AUTO)
        val quantKv = kv != com.offlineagent.core.inference.KvCacheType.F16
        // Lower the window until weights + KV + buffers fit the app's RAM budget (oversized loads get the app killed).
        val ctx = com.offlineagent.core.inference.ContextPlanner.fitContext(DeviceInfo.totalRamBytes(), arch, effectiveContext(s, plan), kv, s.ubatchSize,
            flashAttention = quantKv || fa == com.offlineagent.core.inference.FlashAttention.ON)
        val trained = arch?.trainedContext ?: 0
        val draft = if (!s.safeMode && s.speculative && role == ModelRole.CHAT) models.active(ModelRole.DRAFT)?.path?.takeIf { it != model.path } else null
        val yarn = !s.safeMode && s.yarn && s.largeContext && trained > 0 && ctx > trained
        // Phase 6: thermal / battery saver / low battery scale threads + batch at load time (live changes: OnyxRuntime).
        val power = powerDecision()
        val threads = (if (s.safeMode) threads().coerceIn(1, 4) else threads()).let { t -> power?.let { minOf(t, it.threads) } ?: t }
        val batch = (if (s.safeMode) 512 else s.batchSize).let { b -> power?.let { minOf(b, maxOf(64, it.batch)) } ?: b }
        val ngram = if (!s.safeMode && s.ngramSpec && draft == null && (power?.speculation != false)) s.ngramDraft.coerceIn(1, 16) else 0
        return LoadConfig(
            model.path, contextLength = ctx, threads = threads, batchSize = batch,
            ubatchSize = (if (s.safeMode) 512 else s.ubatchSize).coerceAtMost(batch),
            mmprojPath = if (role == ModelRole.VISION) model.mmprojPath else null, kvCacheType = kv, flashAttention = fa,
            ropeScaling = if (yarn) com.offlineagent.core.inference.RopeScaling.YARN else com.offlineagent.core.inference.RopeScaling.MODEL,
            yarnOriginalContext = if (yarn) trained else 0, cpuIds = if (s.safeMode) emptyList() else DeviceInfo.performanceCoreIds(),
            pollLevel = if (s.safeMode) 0 else minOf(s.pollLevel, power?.pollLevel ?: 100), draftModelPath = draft, draftTokens = s.draftTokens,
            ngramDraft = ngram,
        ).also { lastConfig = it }
    }

    /** Ensures a model of [role] (CHAT or VISION) is loaded. Returns its info. */
    suspend fun ensure(role: ModelRole): ModelInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ensureIo(role) }

    private suspend fun ensureIo(role: ModelRole): ModelInfo = mutex.withLock {
        val want = models.active(role) ?: throw IllegalStateException(
            if (role == ModelRole.VISION) "No vision model: add one (with its mmproj file) in Settings → Models." else "No chat model yet: download one (model menu at the top) or import a .gguf in Settings → Models.",
        )
        if (role == ModelRole.VISION && want.mmprojPath == null) throw IllegalStateException("The vision model needs its mmproj file (Settings → Models → Add projector).")
        val st = inference.state.value
        val cur = _loaded.value
        if (!reloadPending && cur?.id == want.id && cur.mmprojPath == want.mmprojPath && (st is EngineState.Ready || st is EngineState.Generating)) {
            return (st as? EngineState.Ready)?.model ?: (st as EngineState.Generating).model
        }
        loadChecked(want, role)
    }

    suspend fun load(model: ModelEntity): ModelInfo = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadIo(model) }

    private suspend fun loadIo(model: ModelEntity): ModelInfo = mutex.withLock {
        val role = runCatching { ModelRole.valueOf(model.role) }.getOrDefault(ModelRole.CHAT)
        loadChecked(model, if (role == ModelRole.DRAFT) ModelRole.CHAT else role)
    }

    /** Load + self-test + automatic fallback. Caller holds [mutex]. */
    private suspend fun loadChecked(model: ModelEntity, role: ModelRole): ModelInfo {
        reloadPending = false
        var info = loadOnce(model, role)
        if (role == ModelRole.EMBEDDING) return info
        var result = selfTest(model)
        if (result != null && !result.ok) {
            // Broken output: clear caches, switch to safe settings, reload, test again.
            onPrefixCacheInvalid()
            val s = settings.current()
            if (!s.safeMode) {
                settings.update { it.copy(safeMode = true, safeModeReason = "Self-test failed (${result!!.reason}); safe settings applied automatically.") }
                com.offlineagent.app.diag.Diagnostics.log("self-test failed with normal settings → safe mode, reloading")
                info = loadOnce(model, role)
                result = selfTest(model)
            }
            if (result != null && !result.ok) {
                val compat = com.offlineagent.app.diag.Diagnostics.compatVariant().isNotBlank()
                if (!compat) com.offlineagent.app.diag.Diagnostics.setCompatVariant(com.offlineagent.app.diag.Diagnostics.COMPAT_VARIANT)
                _health.value = EngineHealth(result, safeMode = true, compatCpu = true, needsRestart = !compat,
                    message = if (!compat) "The model still gives broken output in safe mode. Restart the app to switch to compatibility CPU kernels."
                        else "The model gives broken output even with compatibility CPU kernels. Try another model file (it may be corrupted) and send the diagnostics log from Settings.")
                return info
            }
        }
        val s = settings.current()
        _health.value = EngineHealth(result, safeMode = s.safeMode, compatCpu = com.offlineagent.app.diag.Diagnostics.compatVariant().isNotBlank(),
            message = when {
                s.safeMode -> "Safe mode: ${s.safeModeReason.ifBlank { "4K context, f16 KV, no prompt cache" }}"
                result?.verdict == com.offlineagent.core.inference.SelfTest.Verdict.WRONG_ANSWER -> null // sane but wrong (tiny models): no banner
                else -> null
            })
        return info
    }

    private suspend fun loadOnce(model: ModelEntity, role: ModelRole): ModelInfo {
        val cfg = config(model, role)
        val desc = "load ${model.name} ctx=${cfg.contextLength} kv=${cfg.kvCacheType} fa=${cfg.flashAttention} threads=${cfg.threads} batch=${cfg.batchSize}/${cfg.ubatchSize}"
        val info = try {
            com.offlineagent.app.diag.Diagnostics.native(desc) { inference.load(cfg) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _loaded.value = null
            com.offlineagent.app.diag.Diagnostics.log("load failed: ${e.message}")
            throw com.offlineagent.core.inference.ModelLoadException("Could not load ${model.name}: ${e.message ?: e}", e)
        }
        _loaded.value = model
        com.offlineagent.app.diag.Diagnostics.log("loaded ${model.name}: ${info.backendInfo.replace('\n', ' ')}")
        return info
    }

    private suspend fun selfTest(model: ModelEntity): com.offlineagent.core.inference.SelfTest.Result? = try {
        com.offlineagent.app.diag.Diagnostics.native("self-test ${model.name}") { com.offlineagent.core.inference.SelfTest.run(inference) }
            .also { com.offlineagent.app.diag.Diagnostics.log("self-test ${it.verdict}: \"${it.output.take(60)}\" (${it.reason}, ${it.millis} ms)") }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        com.offlineagent.app.diag.Diagnostics.log("self-test could not run: ${e.message}")
        null
    }

    /**
     * Apply changed context/KV/flash-attention/threads settings: unload + reload (+ self-test) in the background.
     * If the model is generating, the reload happens on the next request. Never throws.
     */
    suspend fun applySettings(): String {
        val m = _loaded.value ?: return "Saved. Applies when a model loads."
        if (inference.state.value is EngineState.Generating) { reloadPending = true; return "Saved. Applies after the current answer." }
        return try { load(m); "Applied: model reloaded." } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { "Saved, but reload failed: ${e.message}" }
    }

    /** Reload with new settings if something is loaded (after the user changes context/KV/etc.). */
    suspend fun reloadIfLoaded() { applySettings() }

    /** Leaves safe mode (the user's choice); the next load uses their settings again. */
    suspend fun exitSafeMode() {
        settings.update { it.copy(safeMode = false, safeModeReason = "") }
        _health.value = _health.value.copy(safeMode = false, message = null)
        reloadPending = true
    }

    fun markUnloaded() { _loaded.value = null }

    companion object { const val SAFE_CONTEXT = 4096 }
}

sealed interface IntentRequest {
    data class Share(val text: String) : IntentRequest
    /** Any approved intent prepared by a phone tool (alarm, app launch, draft message, settings panel…). */
    data class Raw(val intent: android.content.Intent, val what: String) : IntentRequest
    /** Share / open a generated file (PDF export). */
    data class ShareFile(val uri: android.net.Uri, val mime: String, val view: Boolean) : IntentRequest
    data class Calendar(val title: String, val beginMillis: Long, val endMillis: Long, val description: String) : IntentRequest
}

class AppIntents : IntentPort {
    private val _requests = MutableSharedFlow<IntentRequest>(extraBufferCapacity = 4)
    val requests: SharedFlow<IntentRequest> = _requests
    override suspend fun shareText(text: String) { _requests.emit(IntentRequest.Share(text)) }
    override suspend fun createCalendarEvent(title: String, date: LocalDate, start: LocalTime, durationMinutes: Int, description: String) {
        val begin = date.atTime(start).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        _requests.emit(IntentRequest.Calendar(title, begin, begin + durationMinutes * 60_000L, description))
    }
}

/** The canvas of one chat: versions in Room; render() adds a version and makes it current. */
class ChatCanvasPort(private val chats: ChatDao, private val chatId: Long) : CanvasPort {
    override suspend fun render(doc: UiDoc, newCanvas: Boolean): Int =
        chats.addCanvasVersion(chatId, UiSerializer.toJsonString(doc), if (newCanvas) "Agent: new canvas" else "Agent: update", System.currentTimeMillis())

    override suspend fun current(): UiDoc? {
        val v = chats.chat(chatId)?.canvasVersion ?: return null
        if (v == 0) return null
        val json = chats.canvasVersion(chatId, v)?.docJson ?: return null
        return (UiParser.parse(json) as? UiParseResult.Success)?.doc
    }
}

class RoomMemoryPort(private val dao: MiscDao) : MemoryPort {
    override suspend fun read(query: String): List<Pair<String, String>> {
        val all = dao.memory()
        val scores = KeywordScorer.score(query, all.map { it.key + " " + it.value })
        val hits = all.zip(scores).filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (hits.ifEmpty { all.take(8) }).map { it.key to it.value }
    }
    override suspend fun write(key: String, value: String) = dao.upsertMemory(MemoryEntity(key, value, System.currentTimeMillis()))
}

class RoomNotesPort(private val dao: MiscDao) : NotesPort {
    override suspend fun createNote(title: String, body: String) = dao.insertNote(NoteEntity(title = title, body = body, createdAt = System.currentTimeMillis()))
}

class RetrieverDocumentPort(private val retriever: Retriever, private val docs: com.offlineagent.core.data.DocumentDao) : DocumentPort {
    override suspend fun search(query: String, k: Int) = retriever.search(query, k).map {
        SearchHit(it.candidate.documentId, it.candidate.documentTitle, it.candidate.text, it.score)
    }
    override suspend fun list() = docs.documents().filter { it.status == "READY" }.map { DocInfo(it.id, it.title, it.chars) }
    override suspend fun read(titleOrId: String, maxChars: Int) = retriever.documentText(titleOrId, maxChars)?.let { (d, t) -> DocInfo(d.id, d.title, d.chars) to t }
}

/** Skills: built-ins seeded from resources (refreshed on app update), user skills editable. */
class SkillRepository(private val dao: SkillDao, private val knownTools: Set<String>) {
    val skills: Flow<List<SkillEntity>> = dao.observeSkills()

    suspend fun seedBuiltins() {
        BuiltinSkills.load(knownTools).forEach { s ->
            val existing = dao.skills().firstOrNull { it.id == s.id }
            if (existing == null || existing.builtIn) dao.upsert(SkillEntity(s.id, s.source, builtIn = true, enabled = existing?.enabled ?: true, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun parsed(): List<Skill> = dao.skills().filter { it.enabled }.mapNotNull {
        (SkillParser.parse(it.markdown, knownTools, it.builtIn) as? SkillParseResult.Ok)?.skill
    }

    /** Validates and saves a user skill. Returns null on success or the error text. */
    suspend fun save(markdown: String, replacingId: String? = null): String? {
        return when (val r = SkillParser.parse(markdown, knownTools)) {
            is SkillParseResult.Error -> r.errors.joinToString("\n")
            is SkillParseResult.Ok -> {
                val clash = dao.skills().firstOrNull { it.id == r.skill.id }
                if (clash != null && clash.builtIn) return "'${r.skill.id}' is a built-in skill id; choose another id"
                if (replacingId != null && replacingId != r.skill.id) dao.delete(replacingId)
                dao.upsert(SkillEntity(r.skill.id, markdown, builtIn = false, updatedAt = System.currentTimeMillis()))
                null
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.skills().firstOrNull { it.id == id }?.let { dao.upsert(it.copy(enabled = enabled)) }
    }

    suspend fun delete(id: String) = dao.delete(id)
}

data class PendingApproval(val runId: String, val spec: ToolSpec, val call: ToolCall, val reason: String, val answer: CompletableDeferred<Boolean>)

/**
 * Runs agent workflows in the application scope (they keep going if the user leaves the chat screen),
 * one at a time. Every step is checkpointed to Room; unfinished runs can be resumed after the process dies.
 */
class AgentService(
    private val scope: CoroutineScope,
    private val inference: InferenceAdapter,
    private val engine: EngineController,
    private val agentDao: AgentDao,
    private val chats: ChatDao,
    private val misc: MiscDao,
    private val retriever: Retriever,
    private val docDao: com.offlineagent.core.data.DocumentDao,
    private val intents: AppIntents,
    private val skills: SkillRepository,
    private val settings: SettingsRepository,
    /** MCP tools (built-in phone servers, on-device binder servers, LAN servers) + create_pdf, refreshed by the app. */
    private val extraTools: () -> List<com.offlineagent.core.runtime.tools.Tool> = { emptyList() },
    /** Tools of phone-action groups the user switched off. */
    private val disabledTools: () -> Set<String> = { emptySet() },
    /** Foreground-service hooks for long runs (title, progress) / finish. */
    private val onLongTask: (runId: String, progress: String?) -> Unit = { _, _ -> },
) {
    private val store = RoomRunStore(agentDao)
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()
    private val _live = MutableStateFlow<Map<String, String>>(emptyMap()) // runId -> "Thinking…" text
    val live: StateFlow<Map<String, String>> = _live.asStateFlow()
    private val _partial = MutableStateFlow<Map<String, String>>(emptyMap()) // runId -> model text streaming right now
    /** Phase 4b: the running step's partial model output, streamed into the agent card. */
    val partial: StateFlow<Map<String, String>> = _partial.asStateFlow()
    @Volatile private var lastPartialAt = 0L

    /** LLM for agent steps: streams partial text + prompt progress into the card (throttled to ~10 updates/s). */
    private fun streamingLlm() = InferenceAgentLlm(inference,
        onPartial = { text ->
            val now = System.currentTimeMillis()
            if (now - lastPartialAt > 100) { lastPartialAt = now; activeRunId?.let { id -> _partial.update { it + (id to text) } } }
        },
        onPrompt = { done, total -> activeRunId?.let { id -> _live.update { it + (id to com.offlineagent.core.runtime.chat.StreamingTurn.readingStatus(done, total)) } } },
    )
    private var job: Job? = null
    @Volatile var activeRunId: String? = null
        private set

    val isRunning: Boolean get() = job?.isActive == true

    fun registry(chatId: Long?): ToolRegistry {
        val docs = RetrieverDocumentPort(retriever, docDao)
        val mem = RoomMemoryPort(misc)
        val llm = InferenceAgentLlm(inference)
        val summarizer = object : SummarizerPort {
            override suspend fun summarize(text: String, maxWords: Int) =
                llm.complete("Summarise the text for the user in at most $maxWords words. Plain prose, no preamble.", text.take(6000), null, (maxWords * 2).coerceAtMost(600))
        }
        val tools = mutableListOf(
            SearchDocsTool(docs), ReadDocumentTool(docs), SummarizeTool(summarizer, docs), CalculatorTool(), DateTimeTool(),
            CreateNoteTool(RoomNotesPort(misc)), MemoryReadTool(mem), MemoryWriteTool(mem), ShareTextTool(intents), CalendarEventTool(intents),
        )
        if (chatId != null) {
            val canvas = ChatCanvasPort(chats, chatId)
            tools += RenderUiTool(canvas, update = false)
            tools += RenderUiTool(canvas, update = true)
        }
        val names = tools.map { it.spec.name }.toMutableSet()
        val disabled = disabledTools()
        for (t in extraTools()) if (t.spec.name !in disabled && names.add(t.spec.name)) tools += t
        return ToolRegistry(tools)
    }

    val toolNames: Set<String> get() = registry(0).names

    private fun loopFor(chatId: Long?): AgentLoop {
        val reg = registry(chatId)
        val policy = PolicyEngine(reg, alwaysAllowed = { cachedAllowed }, disabled = disabledTools)
        return AgentLoop(
            streamingLlm(), reg, policy, store,
            ApprovalGate { runId, spec, call, reason ->
                val d = CompletableDeferred<Boolean>()
                _pending.value = PendingApproval(runId, spec, call, reason, d)
                try { d.await() } finally { _pending.value = null }
            },
            AgentLimits(),
            onEvent = { ev ->
                when (ev) {
                    is AgentEvent.Thinking -> {
                        _live.update { it + (ev.what.let { w -> (activeRunId ?: "") to w }) }
                        activeRunId?.let { id -> _partial.update { it - id }; onLongTask(id, ev.what) }
                    }
                    is AgentEvent.Status -> if (ev.checkpoint.status.isTerminal) { _live.update { it - ev.checkpoint.runId }; _partial.update { it - ev.checkpoint.runId } }
                }
            },
        )
    }

    @Volatile private var cachedAllowed: Set<String> = emptySet()

    fun answerApproval(allow: Boolean, always: Boolean = false) {
        val p = _pending.value ?: return
        if (allow && always) scope.launch { settings.update { it.copy(alwaysAllowedTools = it.alwaysAllowedTools + p.call.tool) } }
        p.answer.complete(allow)
    }

    /** Starts a run; [onFinished] gets the final checkpoint (also on failure). Returns the run id or null if busy. */
    /** [conversation]: 0.6.2.1 — the chat session (turns + summary + memory) the run continues; see SessionContext. */
    fun start(chatId: Long?, request: String, skill: Skill?, conversation: String = "", onFinished: suspend (RunCheckpoint) -> Unit): String? {
        if (isRunning) return null
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        job = scope.launch {
            cachedAllowed = settings.current().alwaysAllowedTools
            onLongTask(runId, "Starting…")
            val result = try {
                engine.ensure(ModelRole.CHAT)
                loopFor(chatId).start(runId, chatId, request, skill, conversation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                store.load(runId)?.let { onFinished(it) }
                throw e
            } catch (e: Throwable) {
                store.load(runId)?.copy(status = com.offlineagent.core.runtime.agent.RunStatus.FAILED, error = e.message)?.also { store.save(it) }
                    ?: RunCheckpoint(runId, chatId, skill?.id, request, com.offlineagent.core.runtime.agent.RunStatus.FAILED, error = e.message).also { store.save(it) }
            } finally {
                _live.update { it - runId }
                _partial.update { it - runId }
                activeRunId = null
                onLongTask(runId, null)
            }
            onFinished(result)
        }
        return runId
    }

    fun resume(runId: String, conversation: String = "", onFinished: suspend (RunCheckpoint) -> Unit): Boolean {
        if (isRunning) return false
        activeRunId = runId
        job = scope.launch {
            cachedAllowed = settings.current().alwaysAllowedTools
            onLongTask(runId, "Resuming…")
            val cp = store.load(runId) ?: run { onLongTask(runId, null); return@launch }
            // 0.6.2.1: the session is not stored with the run; after a restart it is rebuilt from the chat's rows.
            val session = conversation.ifBlank {
                cp.chatId?.let { id -> runCatching {
                    val rows = chats.storedMessages(id)
                    com.offlineagent.core.runtime.chat.SessionContext.build(com.offlineagent.core.runtime.chat.SessionHistory.turns(rows, dropTrailingUser = false),
                        com.offlineagent.core.runtime.chat.SessionHistory.latestSummary(rows), "", 1100).text
                }.getOrDefault("") }.orEmpty()
            }
            val skill = cp.skillId?.let { id -> skills.parsed().firstOrNull { it.id == id } }
            val result = try {
                engine.ensure(ModelRole.CHAT)
                loopFor(cp.chatId).resume(runId, skill, session)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                cp.copy(status = com.offlineagent.core.runtime.agent.RunStatus.FAILED, error = e.message).also { store.save(it) }
            } finally { _live.update { it - runId }; _partial.update { it - runId }; activeRunId = null; onLongTask(runId, null) }
            onFinished(result)
        }
        return true
    }

    fun cancel() {
        _pending.value?.answer?.complete(false)
        inference.cancel()
        job?.cancel()
    }

    suspend fun unfinished() = agentDao.unfinishedRuns()
    suspend fun checkpoint(runId: String) = store.load(runId)
    fun observeRuns() = agentDao.observeRuns()
    fun observeSteps(runId: String) = agentDao.observeSteps(runId)
    suspend fun delete(runId: String) = agentDao.deleteRun(runId)
}

fun File.sizeOrZero() = if (isFile) length() else 0L
