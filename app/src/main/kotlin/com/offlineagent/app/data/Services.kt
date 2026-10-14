package com.offlineagent.app.data

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
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** Loads the right model for a request (chat or vision), using the saved settings. Never runs on the main thread. */
class EngineController(
    private val inference: InferenceAdapter,
    private val models: ModelStore,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val _loaded = MutableStateFlow<ModelEntity?>(null)
    val loaded: StateFlow<ModelEntity?> = _loaded.asStateFlow()

    suspend fun threads(): Int = settings.current().threads.takeIf { it > 0 } ?: DeviceInfo.recommendedThreads()

    /** Ensures a model of [role] (CHAT or VISION) is loaded. Returns its info. */
    suspend fun ensure(role: ModelRole): ModelInfo = mutex.withLock {
        val want = models.active(role) ?: throw IllegalStateException(
            if (role == ModelRole.VISION) "No vision model: add one (with its mmproj file) in Models." else "No chat model: import a .gguf in Models.",
        )
        if (role == ModelRole.VISION && want.mmprojPath == null) throw IllegalStateException("The vision model needs its mmproj file (Models → Add projector).")
        val st = inference.state.value
        val cur = _loaded.value
        if (cur?.id == want.id && cur.mmprojPath == want.mmprojPath && (st is EngineState.Ready || st is EngineState.Generating)) {
            return (st as? EngineState.Ready)?.model ?: (st as EngineState.Generating).model
        }
        val s = settings.current()
        val info = inference.load(LoadConfig(want.path, contextLength = s.contextLength, threads = threads(), mmprojPath = if (role == ModelRole.VISION) want.mmprojPath else null))
        _loaded.value = want
        info
    }

    suspend fun load(model: ModelEntity): ModelInfo = mutex.withLock {
        val s = settings.current()
        val info = inference.load(LoadConfig(model.path, contextLength = s.contextLength, threads = threads(),
            mmprojPath = if (model.role == ModelRole.VISION.name) model.mmprojPath else null))
        _loaded.value = model
        info
    }

    fun markUnloaded() { _loaded.value = null }
}

/** Requests to start an Android activity (share sheet, calendar). Collected by MainActivity. */
sealed interface IntentRequest {
    data class Share(val text: String) : IntentRequest
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
) {
    private val store = RoomRunStore(agentDao)
    private val _pending = MutableStateFlow<PendingApproval?>(null)
    val pending: StateFlow<PendingApproval?> = _pending.asStateFlow()
    private val _live = MutableStateFlow<Map<String, String>>(emptyMap()) // runId -> "Thinking…" text
    val live: StateFlow<Map<String, String>> = _live.asStateFlow()
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
        return ToolRegistry(tools)
    }

    val toolNames: Set<String> get() = registry(0).names

    private fun loopFor(chatId: Long?): AgentLoop {
        val reg = registry(chatId)
        val policy = PolicyEngine(reg, alwaysAllowed = { cachedAllowed })
        return AgentLoop(
            InferenceAgentLlm(inference), reg, policy, store,
            ApprovalGate { runId, spec, call, reason ->
                val d = CompletableDeferred<Boolean>()
                _pending.value = PendingApproval(runId, spec, call, reason, d)
                try { d.await() } finally { _pending.value = null }
            },
            AgentLimits(),
            onEvent = { ev ->
                when (ev) {
                    is AgentEvent.Thinking -> _live.update { it + (ev.what.let { w -> (activeRunId ?: "") to w }) }
                    is AgentEvent.Status -> if (ev.checkpoint.status.isTerminal) _live.update { it - ev.checkpoint.runId }
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
    fun start(chatId: Long?, request: String, skill: Skill?, onFinished: suspend (RunCheckpoint) -> Unit): String? {
        if (isRunning) return null
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        job = scope.launch {
            cachedAllowed = settings.current().alwaysAllowedTools
            val result = try {
                engine.ensure(ModelRole.CHAT)
                loopFor(chatId).start(runId, chatId, request, skill)
            } catch (e: kotlinx.coroutines.CancellationException) {
                store.load(runId)?.let { onFinished(it) }
                throw e
            } catch (e: Throwable) {
                store.load(runId)?.copy(status = com.offlineagent.core.runtime.agent.RunStatus.FAILED, error = e.message)?.also { store.save(it) }
                    ?: RunCheckpoint(runId, chatId, skill?.id, request, com.offlineagent.core.runtime.agent.RunStatus.FAILED, error = e.message).also { store.save(it) }
            } finally {
                _live.update { it - runId }
                activeRunId = null
            }
            onFinished(result)
        }
        return runId
    }

    fun resume(runId: String, onFinished: suspend (RunCheckpoint) -> Unit): Boolean {
        if (isRunning) return false
        activeRunId = runId
        job = scope.launch {
            cachedAllowed = settings.current().alwaysAllowedTools
            val cp = store.load(runId) ?: return@launch
            val skill = cp.skillId?.let { id -> skills.parsed().firstOrNull { it.id == id } }
            val result = try {
                engine.ensure(ModelRole.CHAT)
                loopFor(cp.chatId).resume(runId, skill)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) {
                cp.copy(status = com.offlineagent.core.runtime.agent.RunStatus.FAILED, error = e.message).also { store.save(it) }
            } finally { activeRunId = null }
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
