package com.offlineagent.app.ui.chat

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.ModelRole
import com.offlineagent.core.data.AppSettings
import com.offlineagent.core.data.CanvasVersionEntity
import com.offlineagent.core.data.ChatEntity
import com.offlineagent.core.data.MessageEntity
import com.offlineagent.core.data.storedMessages
import com.offlineagent.core.inference.BusyException
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.ImageData
import com.offlineagent.core.inference.PromptTooLongException
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.StopReason
import com.offlineagent.core.openui.deduped
import com.offlineagent.core.inference.ThinkSplitter
import com.offlineagent.core.runtime.agent.RunCheckpoint
import com.offlineagent.core.runtime.agent.RunStatus
import com.offlineagent.core.runtime.agent.STRUCTURED_SAMPLING
import com.offlineagent.core.runtime.rag.RagPrompt
import com.offlineagent.core.runtime.rag.RetrievedChunk
import com.offlineagent.core.runtime.skills.Skill
import com.offlineagent.core.runtime.skills.SkillMatcher
import com.offlineagent.core.runtime.ui.AnswerMode
import com.offlineagent.core.runtime.ui.CanvasPrompts
import com.offlineagent.core.runtime.ui.CanvasRouter
import com.offlineagent.core.runtime.ui.OutputPreference
import com.offlineagent.core.runtime.ui.PartialJson
import com.offlineagent.core.runtime.ui.UiDoc
import com.offlineagent.core.runtime.ui.UiMarkdown
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import com.offlineagent.core.runtime.ui.UiSerializer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

data class Citation(val n: Int, val title: String, val text: String)

/** What is being generated right now (kept in memory; persisted to Room once finished). */
data class Streaming(
    val mode: AnswerMode,
    val text: String = "",
    val reasoning: String = "",
    val partialDoc: UiDoc? = null,
    val tokens: Int = 0,
    val tokensPerSecond: Double = 0.0,
    val phase: String = "Thinking…",
    // ---- Phase 4b streaming
    /** The model is thinking right now: the Thought section is expanded and streams live. */
    val thinking: Boolean = false,
    /** Prompt reading progress ("Reading prompt… done/total"). */
    val promptDone: Int = 0,
    val promptTotal: Int = 0,
    /** Request → first visible token (ms), 0 = not yet. */
    val firstTokenMillis: Long = 0,
    /** "212 tok · 3.6 tok/s · first token 4.2 s". */
    val footer: String = "",
)

/** Copies a [com.offlineagent.core.runtime.chat.StreamingTurn.View] into the bubble state. */
fun Streaming.withView(v: com.offlineagent.core.runtime.chat.StreamingTurn.View): Streaming = copy(
    text = v.answer, reasoning = v.reasoning, thinking = v.thinkingOpen, phase = v.status,
    tokens = v.tokens, tokensPerSecond = v.tokensPerSecond, promptDone = v.promptDone, promptTotal = v.promptTotal,
    firstTokenMillis = v.firstTokenMillis, footer = com.offlineagent.core.runtime.chat.StreamingTurn.footer(v),
)

data class ChatUi(
    val chatId: Long? = null,
    val pendingImages: List<File> = emptyList(),
    val streaming: Streaming? = null,
    val canvasOpen: Boolean = false,
    val notice: String? = null,
    val citation: Citation? = null,
    val skillSuggestion: Skill? = null,
    val unfinishedRun: RunCheckpoint? = null,
    /** Prompt tokens of the last request / context window (chat token counter). */
    val contextUsed: Int = 0,
    val contextSize: Int = 0,
    /** Exported PDF being previewed. */
    val pdf: com.offlineagent.app.pdf.PdfExporter.ExportedPdf? = null,
    /** Composer tool/mode toggle (Phase 4 single-chat UI). */
    val mode: com.offlineagent.core.runtime.chat.ComposerMode = com.offlineagent.core.runtime.chat.ComposerMode.CHAT,
    /** User message being edited (edit-and-resend). */
    val editing: MessageEntity? = null,
    /** Phase 4b: per-message thinking toggle in the composer (null = use the Settings default). Reset after sending. */
    val thinkOverride: Boolean? = null,
    /** Phase 5b: multi-agent mode for Chat-mode messages (AUTO = task-like requests go to the ONYX agents). */
    val agents: AgentsMode = AgentsMode.AUTO,
    /** Phase 6: temporary chat — neither reads nor writes memory (saved memories, chat history, summaries index). */
    val temporary: Boolean = false,
)

/** Phase 5b: the composer's "Agents" switch. */
enum class AgentsMode(val label: String) { AUTO("Agents: auto"), ON("Agents: on"), OFF("Agents: off") }

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val g = app as OfflineAgentApp
    private val chats = g.db.chats()
    private val json = Json { ignoreUnknownKeys = true }

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = _ui.asStateFlow()

    val engine: StateFlow<EngineState> = g.inference.state
    val settings: StateFlow<AppSettings> = g.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val chatList: StateFlow<List<ChatEntity>> = chats.observeChats().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val chatIdFlow = MutableStateFlow<Long?>(null)
    val messages: StateFlow<List<MessageEntity>> = chatIdFlow.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.observeMessages(id) }
        .map { l -> l.filter { it.kind != KIND_SUMMARY } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chat: StateFlow<ChatEntity?> = chatIdFlow.flatMapLatest { id -> if (id == null) flowOf(null) else chats.observeChat(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val canvasVersions: StateFlow<List<CanvasVersionEntity>> = chatIdFlow.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.observeCanvasVersions(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val agentLive = g.agents.live
    /** Phase 4b: partial model text of the running agent step (runId → text). */
    val agentPartial = g.agents.partial
    /** Phase 4b: partial model text of the running automation step. */
    val automationPartial: StateFlow<String> get() = g.automationPartial
    val skills = g.skills.skills.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var genJob: Job? = null
    private val attachDir = File(app.filesDir, "attachments").apply { mkdirs() }

    init {
        viewModelScope.launch {
            val last = g.settings.current().lastChatId
            if (last > 0 && chats.chat(last) != null) openChat(last)
            g.agents.unfinished().firstOrNull()?.let { r -> g.agents.checkpoint(r.runId)?.let { cp -> _ui.update { it.copy(unfinishedRun = cp) } } }
        }
    }

    fun notice(s: String?) = _ui.update { it.copy(notice = s) }

    fun exitSafeMode() = g.appScope.launch { g.engineController.exitSafeMode(); notice(g.engineController.applySettings()) }

    /** Writes the diagnostics bundle and opens the share sheet (the user decides where it goes). */
    fun shareDiagnostics() = g.appScope.launch {
        try {
            val info = runCatching { g.inference.systemInfo() }.getOrDefault("")
            val f = com.offlineagent.app.diag.Diagnostics.exportBundle("System info:\n$info\n\nLast load: ${g.engineController.lastConfig}\nSelf-test: ${g.engineController.health.value.selfTest}")
            val ctx = getApplication<Application>()
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            g.launcher.launch(android.content.Intent.createChooser(send, "Share diagnostics"), "share diagnostics")
        } catch (e: Throwable) { notice("Export failed: ${e.message}") }
    }

    // ------------------------------------------------------------ chats

    fun openChat(id: Long?) {
        if (genJob?.isActive == true) return notice("Wait for the current answer to finish (or press Stop).")
        chatIdFlow.value = id
        _ui.update { it.copy(chatId = id, canvasOpen = false, streaming = null, pendingImages = emptyList(), temporary = if (id == null) false else it.temporary) }
        viewModelScope.launch {
            g.settings.update { it.copy(lastChatId = id ?: 0) }
            if (id != null) { val t = runCatching { g.onyx.longMemory.isTemporary(id) }.getOrDefault(false); _ui.update { it.copy(temporary = t) } }
        }
    }

    fun newChat() = openChat(null)

    /** Phase 6: "Temporary chat" — for a new chat before the first message, or switch the open chat. */
    fun toggleTemporary() = viewModelScope.launch {
        val on = !_ui.value.temporary
        _ui.update { it.copy(temporary = on) }
        _ui.value.chatId?.let { runCatching { g.onyx.longMemory.setTemporary(it, on) } }
        notice(if (on) "Temporary chat: nothing is read from or saved to memory." else "Memory is on for this chat.")
    }

    /** Phase 6: "Memory updated" chips (user message id → text). */
    val memoryChips get() = g.onyx.longMemory.chips

    fun deleteChat(id: Long) = viewModelScope.launch {
        chats.deleteChat(id)
        runCatching { g.onyx.longMemory.onChatDeleted(id) } // Phase 6: its history, summary and chat state go too
        if (_ui.value.chatId == id) openChat(null)
    }

    fun renameChat(id: Long, title: String) = viewModelScope.launch { chats.rename(id, title.take(80)) }

    private suspend fun ensureChat(firstText: String): Long {
        _ui.value.chatId?.let { return it }
        val now = System.currentTimeMillis()
        val id = chats.insertChat(ChatEntity(title = firstText.lines().first().take(48).ifBlank { "New chat" }, createdAt = now, updatedAt = now))
        if (_ui.value.temporary) runCatching { g.onyx.longMemory.setTemporary(id, true) }
        chatIdFlow.value = id
        _ui.update { it.copy(chatId = id) }
        g.settings.update { it.copy(lastChatId = id) }
        return id
    }

    // ------------------------------------------------------------ settings shortcuts

    fun setOutput(p: OutputPreference) = viewModelScope.launch { g.settings.update { it.copy(output = p) } }
    fun setThinking(on: Boolean) = viewModelScope.launch { g.settings.update { it.copy(thinking = on) } }

    /** Composer "Think" chip: flips thinking for the next message only. */
    fun toggleThinkOnce() = _ui.update { it.copy(thinkOverride = !(it.thinkOverride ?: settings.value.thinking)) }
    fun setUseDocuments(on: Boolean) = viewModelScope.launch { g.settings.update { it.copy(useDocuments = on) } }

    // ------------------------------------------------------------ attachments

    fun attachImage(uri: Uri) = viewModelScope.launch {
        try {
            val f = withContext(Dispatchers.IO) { copyDownscaled(uri) }
            _ui.update { it.copy(pendingImages = (it.pendingImages + f).takeLast(2)) }
        } catch (e: Throwable) { notice("Could not read the image: ${e.message}") }
    }

    fun removePendingImage(f: File) = _ui.update { it.copy(pendingImages = it.pendingImages - f) }

    fun cameraTarget(): File = File(attachDir, "cam-${UUID.randomUUID()}.jpg")

    fun attachCameraFile(f: File) = viewModelScope.launch {
        val out = withContext(Dispatchers.IO) { if (!f.isFile || f.length() == 0L) null else copyDownscaled(Uri.fromFile(f)).also { f.delete() } } ?: return@launch
        _ui.update { it.copy(pendingImages = (it.pendingImages + out).takeLast(2)) }
    }

    /** Copies the picked photo into app storage, downscaled to ≤1280 px (JPEG). */
    private fun copyDownscaled(uri: Uri): File {
        val r = getApplication<Application>().contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2
        val bmp = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("not an image")
        val scaled = scaleTo(bmp, 1280)
        val f = File(attachDir, "img-${UUID.randomUUID()}.jpg")
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f
    }

    private fun scaleTo(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val s = maxSide.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
    }

    /** Decodes an attachment to packed RGB for the vision encoder (≤768 px: mtmd tiles internally). */
    private fun toImageData(f: File): ImageData {
        val bmp = scaleTo(BitmapFactory.decodeFile(f.absolutePath) ?: error("cannot decode ${f.name}"), 768)
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val rgb = ByteArray(px.size * 3)
        for (i in px.indices) {
            val p = px[i]
            rgb[i * 3] = (p shr 16 and 0xFF).toByte(); rgb[i * 3 + 1] = (p shr 8 and 0xFF).toByte(); rgb[i * 3 + 2] = (p and 0xFF).toByte()
        }
        return ImageData(rgb, bmp.width, bmp.height)
    }

    fun attachmentsOf(m: MessageEntity): List<File> =
        m.attachmentsJson?.let { runCatching { json.parseToJsonElement(it).jsonArray.map { e -> File(e.jsonPrimitive.content) } }.getOrNull() } ?: emptyList()

    /** "attachment:N" -> N-th image attached in this chat (1-based, chat order). */
    fun resolveImage(ref: String): File? {
        val n = ref.substringAfter(':').toIntOrNull() ?: return null
        return messages.value.flatMap { attachmentsOf(it) }.getOrNull(n - 1)
    }

    fun citationsOf(m: MessageEntity): List<Citation> = m.citationsJson?.let {
        runCatching {
            json.parseToJsonElement(it).jsonArray.map { e ->
                val o = e.jsonObject
                Citation(o["n"]!!.jsonPrimitive.content.toInt(), o["title"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content)
            }
        }.getOrNull()
    } ?: emptyList()

    fun showCitation(c: Citation?) = _ui.update { it.copy(citation = c) }

    fun onTextChanged(text: String) {
        val match = if (text.length > 6 && !text.startsWith("/")) SkillMatcher.match(text, parsedSkillsCache) else null
        if (_ui.value.skillSuggestion?.id != match?.skill?.id) _ui.update { it.copy(skillSuggestion = match?.skill) }
        if (_ui.value.editing == null && !busy) typeAhead.onDraft(text)
    }

    // ------------------------------------------------------------ 0.6.2.3: typing-time prefill (reading ahead)

    /** Composer screen stopped (app in background / screen off) or started again. */
    fun onComposerVisible(visible: Boolean) { composerVisible = visible; if (visible) typeAhead.resume() else typeAhead.pause() }

    @Volatile private var pendingHandoff: com.offlineagent.core.inference.TypeAheadController.Handoff? = null

    private fun supportsThinking(): Boolean {
        val m = (g.inference.state.value as? EngineState.Ready)?.model ?: return false
        return com.offlineagent.core.inference.ChatMlTemplate.hasHybridThinking(m.chatTemplate) && m.chatTemplate != null
    }

    /** Engine calls of the read-ahead: lowest queue priority, preempted by any agent job or chat request. */
    private val aheadEngine = object : com.offlineagent.core.inference.AheadEngine {
        override suspend fun prefill(request: GenerationRequest) =
            g.onyx.queue.tryRunPreemptible("chat-prefill", onPreempt = { g.inference.preemptPrefill() }) { g.inference.prefill(request) }
        override suspend fun reasonAhead(request: GenerationRequest, maxTokens: Int, biasWords: List<String>, bias: Float) =
            g.onyx.queue.tryRunPreemptible("chat-prefill", onPreempt = { g.inference.preemptPrefill() }) { g.inference.reasonAhead(request, maxTokens, biasWords, bias) }
    }

    /** The request Send would build for [draft] when its route is the plain Markdown chat; null otherwise. */
    private suspend fun prefillRequest(draft: String): GenerationRequest? {
        val text = draft.trim()
        val u = _ui.value
        if (text.isEmpty() || u.pendingImages.isNotEmpty() || u.editing != null || busy) return null
        val chatId = u.chatId
        val route = com.offlineagent.core.runtime.chat.ReadAheadRoute.isPlainChat(
            text = text, mode = u.mode,
            followUp = com.offlineagent.core.runtime.chat.FollowUp.plan(text, if (chatId == null) emptyList() else turns(chatId)).kind,
            screenTask = com.offlineagent.core.agents.ChatRouting.screenTask(text, u.mode, g.onyx.screen.enabled()),
            agents = u.mode == com.offlineagent.core.runtime.chat.ComposerMode.CHAT && useAgents(text) && !com.offlineagent.core.agents.memory.PreviousChats.isAbout(text),
            canvasInChat = u.mode == com.offlineagent.core.runtime.chat.ComposerMode.CANVAS &&
                com.offlineagent.core.runtime.chat.CanvasRouting.answerInChat(text, hasCanvas = chatId != null && runCatching { currentCanvasSource(chatId) != null }.getOrDefault(false)),
        )
        if (!route) return null
        val s = g.settings.current()
        val rag = if (s.useDocuments && g.retriever.hasDocuments()) runCatching { g.retriever.search(text, 4).filter { it.score >= 0.25f } }.getOrDefault(emptyList()) else emptyList()
        val thinking = (u.thinkOverride ?: s.thinking)
        return try { markdownPrompt(chatId, text, emptyList(), rag, thinking, forPrefill = true).request }
        catch (e: SkipPrefill) { null }
    }

    private fun aheadGate(): String? {
        val s = settings.value
        if (!s.readAhead) return "off in Settings"
        val m = g.onyx.monitor
        val (pct, charging) = runCatching { m.battery() }.getOrDefault(null to false)
        val pm = getApplication<Application>().getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        return com.offlineagent.core.agents.ReadAheadGate.check(m.thermal.value, m.powerSave.value, pct, charging,
            screenOn = pm.isInteractive, foreground = composerVisible, safeMode = s.safeMode)
    }

    @Volatile private var composerVisible = true

    /** A model for the read-ahead: the loaded chat model, or a warm load when RAM clearly allows it (never a swap). */
    private suspend fun warmForTyping(): Boolean {
        val st = g.inference.state.value
        if (st is EngineState.Ready) return g.engineController.loaded.value?.role == "CHAT"
        if (st !is EngineState.Unloaded) return false
        val want = runCatching { g.models.active(ModelRole.CHAT) }.getOrNull() ?: return false
        val am = getApplication<Application>().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val size = java.io.File(want.path).length()
        if (mi.lowMemory || mi.availMem < size * 3 / 2 + 768L * 1024 * 1024) return false
        return runCatching { g.engineController.ensure(ModelRole.CHAT); true }.getOrDefault(false)
    }

    private val typeAhead = com.offlineagent.core.inference.TypeAheadController(
        scope = viewModelScope, clock = System::currentTimeMillis, engine = aheadEngine,
        build = { d -> prefillRequest(d) }, gate = { aheadGate() }, ensureModel = { warmForTyping() },
        config = { val s = settings.value; com.offlineagent.core.inference.TypeAheadController.Config(enabled = s.readAhead && !s.safeMode, reasonAhead = s.reasonAhead && supportsThinking()) },
    )
    val typeAheadStatus get() = typeAhead.status

    private var parsedSkillsCache: List<Skill> = emptyList()
    init { viewModelScope.launch { skills.collect { parsedSkillsCache = g.skills.parsed() } } }

    // ------------------------------------------------------------ sending

    val busy: Boolean get() = sending || genJob?.isActive == true || g.agents.isRunning || g.onyx.isRunning

    /** Set synchronously on the main thread before anything else, so a double tap / IME action can't send twice. */
    @Volatile private var sending = false
    private var lastSent: Pair<String, Long>? = null

    fun send(rawText: String, forceSkill: Skill? = null) {
        val text = rawText.trim()
        val images = _ui.value.pendingImages
        if ((text.isEmpty() && images.isEmpty()) || busy) return
        val now0 = System.currentTimeMillis()
        if (images.isEmpty() && lastSent?.let { (t, at) -> t == text && now0 - at < DUPLICATE_WINDOW_MS } == true) return
        lastSent = text to now0
        sending = true
        turnStartedAt = now0
        pendingHandoff = typeAhead.onSend(text)
        val think = _ui.value.thinkOverride
        _ui.update { it.copy(pendingImages = emptyList(), skillSuggestion = null, thinkOverride = null) }
        genJob = viewModelScope.launch {
          try {
            val chatId = ensureChat(text.ifBlank { "Photo" })
            val now = System.currentTimeMillis()
            val userMsgId = chats.insertMessage(MessageEntity(chatId = chatId, role = "user", text = text,
                attachmentsJson = if (images.isEmpty()) null else JsonArray(images.map { JsonPrimitive(it.absolutePath) }).toString(), createdAt = now))
            chats.touch(chatId, now)
            // Phase 6: saved memories (auto-extract / remember / forget) + the "Memory updated" chip.
            val upd = if (text.isNotBlank()) runCatching { g.onyx.longMemory.onUserMessage(chatId, userMsgId, text) }.getOrNull() else null
            if (upd != null && upd.explicit && images.isEmpty()) {
                // A pure "remember …" / "forget …" message is answered directly (no model call).
                val reply = when {
                    upd.forgotten.isNotEmpty() -> "Done — I forgot ${upd.forgotten.size} memor${if (upd.forgotten.size == 1) "y" else "ies"}: " + upd.forgotten.joinToString("; ") { it.text }
                    upd.saved.isNotEmpty() -> "Got it — I'll remember: ${upd.saved.first().text}. You can see or delete it in Memory."
                    _ui.value.temporary -> "This is a temporary chat, so nothing is saved to memory."
                    else -> "I couldn't find a memory matching that."
                }
                chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = reply, createdAt = System.currentTimeMillis()))
                return@launch
            }

            // Skills: explicit /skill-id, or the suggestion chip
            val explicit = if (forceSkill != null) SkillMatcher.Match(forceSkill, text, true) else if (text.startsWith("/")) SkillMatcher.match(text, g.skills.parsed()) else null
            if (explicit != null) {
                if (explicit.skill.id == "write-report") runReport(chatId, explicit.request) else runAgent(chatId, explicit.request, explicit.skill)
                return@launch
            }
            respond(chatId, text, images, _ui.value.mode, think)
          } finally {
            sending = false
          }
        }
    }

    /** Answers the last user turn of [chatId] according to the composer [mode]. Used by send, regenerate and edit. */
    /** When the current turn started (send tap): time-to-first-token is measured from here. */
    @Volatile private var turnStartedAt = 0L

    private suspend fun respond(chatId: Long, text: String, images: List<File>, mode: com.offlineagent.core.runtime.chat.ComposerMode, thinkOverride: Boolean? = null) {
        if (turnStartedAt == 0L) turnStartedAt = System.currentTimeMillis()
        // 0.6.2.1: "make it as a pdf" / "export this" (any mode, any route) → a PDF of the earlier answer's text, titled
        // from its question; nothing is regenerated. "make a detailed pdf on it" → the report writer, with the session.
        if (images.isEmpty() && text.isNotBlank()) {
            val fu = com.offlineagent.core.runtime.chat.FollowUp.plan(text, turns(chatId))
            when (fu.kind) {
                com.offlineagent.core.runtime.chat.FollowUp.Kind.EXPORT -> { exportAnswerPdf(chatId, fu); return }
                com.offlineagent.core.runtime.chat.FollowUp.Kind.REWRITE_EXPORT -> { runReport(chatId, text); return }
                else -> Unit
            }
        }
        // Phase 6.1: with the Screen agent on, an in-app request ("open WhatsApp and type hi to Mom") always goes to the
        // ONYX orchestrator, whose screen route runs screen_open_app → screen_task. The legacy chat agent and plain chat
        // only had intent openers (app_open), so they opened the app and stopped.
        if (images.isEmpty() && com.offlineagent.core.agents.ChatRouting.screenTask(text, mode, g.onyx.screen.enabled())) { runOnyx(chatId, text); return }
        when (mode) {
            com.offlineagent.core.runtime.chat.ComposerMode.AGENT -> { runAgent(chatId, text, null); return }
            com.offlineagent.core.runtime.chat.ComposerMode.PHONE -> { runAgent(chatId, "Use the phone action tools for this request (ask for approval where needed): $text", null); return }
            com.offlineagent.core.runtime.chat.ComposerMode.PDF_REPORT -> { runReport(chatId, text); return }
            com.offlineagent.core.runtime.chat.ComposerMode.AUTOMATION -> { runAutomation(chatId, text); return }
            else -> Unit
        }
        // Phase 5b: multi-agent mode inside Chat (default AUTO: task-like requests → planner/agents, results stream here).
        // 0.6.2.2: questions about earlier chats are answered from the Threads summaries (plain chat), never by the planner.
        if (mode == com.offlineagent.core.runtime.chat.ComposerMode.CHAT && images.isEmpty() && useAgents(text) &&
            !com.offlineagent.core.agents.memory.PreviousChats.isAbout(text)) { runOnyx(chatId, text); return }
        val s = g.settings.current()
        // 0.6.2.2: in Canvas mode a plain question ("summary of our previous chats", "what is …") is answered as chat,
        // with an "Answered in chat" note; tables / charts / plans / forms and edits of the current canvas stay in Canvas.
        val canvas = mode == com.offlineagent.core.runtime.chat.ComposerMode.CANVAS && images.isEmpty() &&
            !com.offlineagent.core.runtime.chat.CanvasRouting.answerInChat(text, hasCanvas = runCatching { currentCanvasSource(chatId) != null }.getOrDefault(false))
        val chatNote = if (mode == com.offlineagent.core.runtime.chat.ComposerMode.CANVAS && images.isEmpty() && !canvas) com.offlineagent.core.runtime.chat.CanvasRouting.NOTE else null
        try {
            _ui.update { it.copy(streaming = Streaming(if (canvas) AnswerMode.CANVAS_NEW else AnswerMode.MARKDOWN, phase = if (images.isNotEmpty()) "Loading vision model…" else "Loading model…")) }
            g.engineController.ensure(if (images.isNotEmpty()) ModelRole.VISION else ModelRole.CHAT)
            val rag = if (s.useDocuments && images.isEmpty() && g.retriever.hasDocuments()) {
                _ui.update { it.copy(streaming = it.streaming?.copy(phase = "Searching your documents…")) }
                g.retriever.search(text, 4).filter { it.score >= 0.25f }
            } else emptyList()
            if (canvas) answerCanvasOpenUi(chatId, text, rag)
            else answerMarkdown(chatId, text, images, rag, thinkOverride ?: s.thinking, note = chatNote)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val msg = when (e) {
                is BusyException -> "The model is busy. Try again in a moment."
                is PromptTooLongException -> "The conversation is too long for the context window. Start a new chat or raise the context size."
                else -> e.message ?: e.toString()
            }
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", error = msg, createdAt = System.currentTimeMillis()))
        } finally {
            turnStartedAt = 0L
            pendingHandoff = null
            _ui.update { it.copy(streaming = null) }
        }
    }

    // ------------------------------------------------------------ message actions

    fun setMode(m: com.offlineagent.core.runtime.chat.ComposerMode) = _ui.update { it.copy(mode = m) }

    // ------------------------------------------------------------ Phase 5b: ONYX agents in Chat

    fun cycleAgentsMode() = _ui.update { it.copy(agents = AgentsMode.entries[(it.agents.ordinal + 1) % AgentsMode.entries.size]) }

    private fun useAgents(text: String): Boolean = when (_ui.value.agents) {
        AgentsMode.ON -> true
        AgentsMode.OFF -> false
        AgentsMode.AUTO -> com.offlineagent.core.agents.Triage.isTaskLike(text)
    }

    /** Runs the multi-agent orchestrator for a chat turn; the trace and the streaming answer render inside the message. */
    private suspend fun runOnyx(chatId: Long, text: String) {
        // 0.6.2.1: the whole session (recent turns verbatim, rolling summary, memory block) within the token budget —
        // it used to be the last 6 turns cut to 500 characters each, without memory or summary.
        val conversation = session(chatId, text).text
        val msgId = chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", kind = KIND_ONYX, createdAt = System.currentTimeMillis()))
        val done = kotlinx.coroutines.CompletableDeferred<com.offlineagent.core.agents.TaskCheckpoint>()
        val temporary = runCatching { g.onyx.longMemory.isTemporary(chatId) }.getOrDefault(false)
        val res = g.onyx.start(text, conversation, { cp -> done.complete(cp) }, temporary = temporary)
        val runId = res.runId ?: run {
            chats.message(msgId)?.let { chats.updateMessage(it.copy(error = res.error ?: "Could not start the agents.")) }
            return
        }
        chats.message(msgId)?.let { chats.updateMessage(it.copy(runId = runId)) }
        val cp = try { done.await() } catch (e: CancellationException) { g.onyx.cancel(); throw e }
        finishOnyxMessage(msgId, cp)
        if (!temporary && !cp.answer.isNullOrBlank()) g.appScope.launch {
            runCatching { g.onyx.longMemory.onExchange(chatId, chats.chat(chatId)?.title ?: "Chat", text, cp.answer.orEmpty()) }
        }
    }

    private suspend fun finishOnyxMessage(msgId: Long, cp: com.offlineagent.core.agents.TaskCheckpoint) {
        val m = chats.message(msgId) ?: return
        val calls = g.onyx.board(cp.runId)?.eventLog()?.count { it is com.offlineagent.core.agents.ActivityEvent.ModelCall } ?: 0
        val secs = ((cp.updatedAt - cp.createdAt) / 100) / 10.0
        chats.updateMessage(m.copy(
            text = cp.answer ?: "",
            // What later chat turns see (plan, step results, answer): follow-ups never need to re-ask.
            reasoning = com.offlineagent.core.agents.TaskContext.forChat(cp),
            statsText = listOfNotNull(cp.route?.label, "$calls model call${if (calls == 1) "" else "s"}", "${secs}s").joinToString(" · "),
            error = when (cp.status) {
                com.offlineagent.core.agents.TaskStatus.FAILED -> "Task failed: ${cp.error ?: "unknown error"}"
                com.offlineagent.core.agents.TaskStatus.CANCELLED -> "Stopped."
                else -> null
            },
        ))
        chats.touch(m.chatId, System.currentTimeMillis())
    }

    /**
     * "Continue in chat" from Home: opens a new chat that already contains the request and the agents' result (with
     * the plan, step outputs and context as hidden context for follow-ups). Nothing is re-asked or re-run.
     */
    fun continueFromTask(runId: String) {
        if (busy) return notice("Wait for the current answer to finish (or press Stop).")
        viewModelScope.launch {
            val cp = g.onyx.board(runId)?.checkpoint() ?: g.onyx.taskStore.load(runId) ?: return@launch notice("That task is no longer available.")
            openChat(null)
            val chatId = ensureChat(cp.request)
            val now = System.currentTimeMillis()
            chats.insertMessage(MessageEntity(chatId = chatId, role = "user", text = cp.request, createdAt = now))
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = cp.answer ?: "", kind = KIND_ONYX, runId = cp.runId,
                reasoning = com.offlineagent.core.agents.TaskContext.forChat(cp), statsText = "continued from Home", createdAt = now + 1))
            chats.touch(chatId, now)
        }
    }

    /** Regenerate an assistant answer: delete it (and anything after) and answer the same user turn again. */
    fun regenerate(assistantId: Long) {
        val chatId = _ui.value.chatId ?: return
        if (busy) return notice("Wait for the current answer to finish (or press Stop).")
        sending = true
        genJob = viewModelScope.launch {
            try {
                val all = chats.messages(chatId).filter { it.kind != KIND_SUMMARY }
                val (user, del) = com.offlineagent.core.runtime.chat.ChatLogic.regenerateTarget(all.map { com.offlineagent.core.runtime.chat.MsgRef(it.id, it.role, it.text, it.kind) }, assistantId) ?: return@launch
                del.forEach { chats.deleteMessage(it) }
                val um = all.first { it.id == user.id }
                turnStartedAt = System.currentTimeMillis()
                respond(chatId, um.text, attachmentsOf(um), _ui.value.mode)
            } finally { sending = false }
        }
    }

    fun startEdit(m: MessageEntity) = _ui.update { it.copy(editing = m) }
    fun cancelEdit() = _ui.update { it.copy(editing = null) }

    /** Edit-and-resend: the edited user message and everything after it are replaced by the new turn. */
    fun submitEdit(newText: String) {
        val m = _ui.value.editing ?: return
        val chatId = _ui.value.chatId ?: return
        if (busy) return notice("Wait for the current answer to finish (or press Stop).")
        _ui.update { it.copy(editing = null) }
        viewModelScope.launch {
            val all = chats.messages(chatId).filter { it.kind != KIND_SUMMARY }
            com.offlineagent.core.runtime.chat.ChatLogic.editResendDeletes(all.map { com.offlineagent.core.runtime.chat.MsgRef(it.id, it.role, it.text) }, m.id).forEach { chats.deleteMessage(it) }
            lastSent = null
            send(newText)
        }
    }

    // ------------------------------------------------------------ models (top-bar picker)

    val chatModels = g.models.models.map { l -> l.filter { it.role == ModelRole.CHAT.name } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectModel(m: com.offlineagent.core.data.ModelEntity) = g.appScope.launch {
        g.models.activate(m)
        if (g.engineController.loaded.value != null) notice(g.engineController.applySettings())
    }

    // ------------------------------------------------------------ automation & macros (inline cards)

    private suspend fun runAutomation(chatId: Long, goal: String) {
        val started = System.currentTimeMillis()
        val id = chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", kind = KIND_AUTOMATION, reasoning = "Automation: ${goal.take(60)}", createdAt = started))
        val conv = runCatching { session(chatId, goal, budgetTokens = 300).text }.getOrDefault("")
        val result = try { g.automation.runTask(com.offlineagent.core.runtime.chat.SessionContext.goalWithContext(goal, conv), "") } catch (e: CancellationException) { "Stopped." } catch (e: Throwable) { "Could not run: ${e.message}" }
        val log = g.automationLog.recent.value.filter { it.time >= started }.joinToString("\n") { "${it.step}. ${it.app}: ${it.action} → ${it.outcome}" }
        chats.message(id)?.let { chats.updateMessage(it.copy(text = result, uiJson = log)) }
    }

    fun runMacro(m: com.offlineagent.core.runtime.phone.Macro) {
        if (busy) return notice("Wait for the current answer to finish.")
        sending = true
        genJob = viewModelScope.launch {
            try {
                val chatId = ensureChat("Macro: ${m.name}")
                val started = System.currentTimeMillis()
                val id = chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", kind = KIND_AUTOMATION, reasoning = "Macro: ${m.name}", createdAt = started))
                val result = try { g.automation.play(m, emptyList(), unattended = false) } catch (e: CancellationException) { "Stopped." } catch (e: Throwable) { "Could not run: ${e.message}" }
                val log = g.automationLog.recent.value.filter { it.time >= started }.joinToString("\n") { "${it.step}. ${it.app}: ${it.action} → ${it.outcome}" }
                chats.message(id)?.let { chats.updateMessage(it.copy(text = result, uiJson = log)) }
            } finally { sending = false }
        }
    }

    private suspend fun history(chatId: Long, maxTurns: Int = 8): List<ChatMessage> {
        val msgs = chats.messages(chatId).filter { it.error == null && it.kind != KIND_SUMMARY }.takeLast(maxTurns * 2 + 1)
        return msgs.dropLast(1).mapNotNull { m ->
            when {
                m.role == "user" -> ChatMessage(Role.USER, m.text.ifBlank { "(photo)" })
                m.kind == "canvas" && m.uiJson != null -> ChatMessage(Role.ASSISTANT, "(I showed a canvas: ${m.text})")
                m.kind == KIND_ONYX && m.reasoning.isNotBlank() -> ChatMessage(Role.ASSISTANT, m.reasoning.take(1500))
                m.text.isNotBlank() -> ChatMessage(Role.ASSISTANT, m.text.take(1500))
                else -> null
            }
        }.dropWhile { it.role == Role.ASSISTANT }
    }

    private fun citationsJson(rag: List<RetrievedChunk>) = buildJsonArray {
        rag.forEachIndexed { i, r -> add(buildJsonObject { put("n", i + 1); put("title", r.candidate.documentTitle); put("text", r.candidate.text); put("documentId", r.candidate.documentId) }) }
    }.toString()

    /**
     * Live turns of the chat (oldest first), without the request just added. 0.6.2.1: always rebuilt from Room (never
     * from the engine's KV cache), so the conversation survives a model reload, an idle unload and an app restart.
     */
    private suspend fun turns(chatId: Long): List<com.offlineagent.core.runtime.context.Turn> =
        com.offlineagent.core.runtime.chat.SessionHistory.turns(chats.storedMessages(chatId))

    private suspend fun latestSummary(chatId: Long): com.offlineagent.core.runtime.context.ConversationSummary? =
        com.offlineagent.core.runtime.chat.SessionHistory.latestSummary(chats.storedMessages(chatId))

    private fun contextLength(): Int = (g.inference.state.value as? EngineState.Ready)?.model?.contextLength ?: 4096

    /**
     * 0.6.2.1: the session block for every route that builds its own prompt (agents, tool agent, report, canvas,
     * automation): recent turns verbatim, the rolling summary once the window fills (persisted like the chat path's),
     * and the saved-memory / chat-history block (none in a temporary chat).
     */
    private suspend fun session(chatId: Long, request: String, budgetTokens: Int? = null, summarize: Boolean = true, memoryBudget: Int? = null): com.offlineagent.core.runtime.chat.SessionContext.Result {
        val s = g.settings.current()
        val ctx = contextLength()
        val budget = budgetTokens ?: com.offlineagent.core.runtime.chat.SessionContext.budgetFor(ctx)
        val temporary = runCatching { g.onyx.longMemory.isTemporary(chatId) }.getOrDefault(false)
        val memBudget = memoryBudget ?: if (ctx <= 4096) 200 else 320
        val mem = if (temporary || memBudget < 40) "" else runCatching { g.onyx.longMemory.contextFor(request, chatId, budgetTokens = memBudget).text }.getOrDefault("")
        val rows = chats.storedMessages(chatId)
        val llm = com.offlineagent.core.runtime.agent.InferenceAgentLlm(g.inference)
        val loaded = g.inference.state.value is EngineState.Ready
        val res = com.offlineagent.core.runtime.chat.SessionContext.build(
            com.offlineagent.core.runtime.chat.SessionHistory.turns(rows), com.offlineagent.core.runtime.chat.SessionHistory.latestSummary(rows), mem, budget,
            counter = { t -> g.inference.countTokens(t) ?: com.offlineagent.core.runtime.context.TokenCounter.estimate(t) },
            // Summarising needs the model; without one loaded the newest turns that fit are kept (nothing is lost: Room keeps all).
            // 0.6.2.2: callers on the critical path (canvas) pass summarize = false: each fold is a whole extra model
            // call (prefill + up to 300 tokens) BEFORE the answer's first token — minutes at phone speed.
            summarizer = if (summarize && s.autoSummarize && loaded) com.offlineagent.core.runtime.context.ConversationSummarizer { prev, ts, words ->
                val (sys, user) = com.offlineagent.core.runtime.context.ContextManager.summaryPrompt(prev, ts, words)
                llm.complete(sys, user, null, words * 2)
            } else null,
        )
        if (res.summaryChanged) res.summary?.let { sum ->
            chats.insertMessage(MessageEntity(chatId = chatId, role = "system", text = sum.text, kind = KIND_SUMMARY,
                runId = com.offlineagent.core.runtime.chat.SessionHistory.summaryRunId(sum), createdAt = System.currentTimeMillis()))
        }
        return res
    }

    /** 0.6.2.1: "make it as a pdf" → the referenced answer, as written, in a PDF titled from its question. */
    private suspend fun exportAnswerPdf(chatId: Long, plan: com.offlineagent.core.runtime.chat.FollowUp.Plan) {
        _ui.update { it.copy(streaming = Streaming(AnswerMode.MARKDOWN, phase = "Rendering PDF…")) }
        try {
            val out = com.offlineagent.core.runtime.chat.AnswerPdfExporter(g.pdf, g.pdf::imageResolver) { t -> com.offlineagent.app.pdf.PdfExporter.safeName(t) + ".pdf" }.export(plan)
                ?: throw IllegalStateException("there is no earlier answer to export")
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = out.chatMessage(), statsText = "pdf · from the earlier answer", createdAt = System.currentTimeMillis()))
            chats.touch(chatId, System.currentTimeMillis())
            _ui.update { it.copy(pdf = g.pdf.last) }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", error = "PDF export failed: ${e.message}", createdAt = System.currentTimeMillis()))
        } finally {
            turnStartedAt = 0L
            _ui.update { it.copy(streaming = null) }
        }
    }

    private fun contextManager(s: AppSettings, temporary: Boolean = false): com.offlineagent.core.runtime.context.ContextManager {
        val llm = com.offlineagent.core.runtime.agent.InferenceAgentLlm(g.inference)
        return com.offlineagent.core.runtime.context.ContextManager(
            counter = { t -> g.inference.countTokens(t) ?: com.offlineagent.core.runtime.context.TokenCounter.estimate(t) },
            summarizer = if (s.autoSummarize) com.offlineagent.core.runtime.context.ConversationSummarizer { prev, turns, words ->
                _ui.update { it.copy(streaming = it.streaming?.copy(phase = "Summarising older messages…")) }
                val (sys, user) = com.offlineagent.core.runtime.context.ContextManager.summaryPrompt(prev, turns, words)
                llm.complete(sys, user, null, words * 2)
            } else null,
            memory = if (s.longTermMemory && !temporary) g.chatMemory else null,
            // Phase 4b: memory pages are re-read on every turn; keep them small (time to first token).
            config = com.offlineagent.core.runtime.context.ContextConfig(memoryPages = 2, memoryMaxTokens = 320),
        )
    }

    /** 0.6.2.3: the chat prompt, built ONE way for Send and for typing-time prefill (so the KV prefix matches). */
    private class MdPrompt(val request: GenerationRequest, val built: com.offlineagent.core.runtime.context.ContextPlanResult, val ctx: Int, val temporary: Boolean)

    /** Thrown by the prefill-time summariser: Send would fold old turns first (a model call), so do not read ahead now. */
    private class SkipPrefill : RuntimeException()

    /**
     * Builds the exact [GenerationRequest] the Markdown chat route sends. [forPrefill] = typing-time read-ahead: nothing is
     * persisted, no summary model call is made (the build is abandoned instead), and [chatId] may be null (new chat).
     */
    private suspend fun markdownPrompt(chatId: Long?, text: String, images: List<File>, rag: List<RetrievedChunk>, thinking: Boolean, forPrefill: Boolean): MdPrompt {
        val s = g.settings.current()
        // Phase 4b: short, stable system prompt (cheat sheet; the full OpenUI library only when the user asks for UI) so
        // it is cheap to prefill and its KV cache is reused turn after turn. Document passages go into the user turn.
        val system = com.offlineagent.core.runtime.chat.ChatPrompts.system(com.offlineagent.core.runtime.chat.ChatPrompts.level(s.richUi, text))
        val info = (g.inference.state.value as? EngineState.Ready)?.model
        val ctx = info?.contextLength ?: 4096
        val plan = g.engineController.planWithoutModel(s)
        val maxOut = if (thinking) g.engineController.maxOutput(s, plan, ctx).coerceAtLeast(1536) else g.engineController.maxOutput(s, plan, ctx)
        val imgs = withContext(Dispatchers.Default) { images.map { toImageData(it) } }
        // Phase 6: saved memories + snippets from other chats (token budget), nothing in a temporary chat.
        val temporary = if (chatId == null) _ui.value.temporary else runCatching { g.onyx.longMemory.isTemporary(chatId) }.getOrDefault(false)
        // 0.6.2.2: device-aware memory budget (part of the new, uncached prompt); questions about earlier chats get the
        // Threads summaries of recent chats instead of BM25 snippets (which share no words with "our previous chats").
        val dyn = com.offlineagent.core.runtime.chat.PromptBudget.dynamicTokens(promptTier(s), prefillMeter.tokensPerSecond)
        val memBudget = minOf(if (ctx <= 4096) 200 else 320, (dyn * 0.4).toInt())
        val memBlock = if (com.offlineagent.core.agents.memory.PreviousChats.isAbout(text))
            runCatching { g.onyx.longMemory.previousChats(text, chatId, budgetTokens = minOf(if (ctx <= 4096) 450 else 700, (dyn * 0.6).toInt())).text }.getOrDefault("")
        else if (temporary) "" else runCatching { g.onyx.longMemory.contextFor(text, chatId, budgetTokens = memBudget).text }.getOrDefault("")
        val extra = listOf(memBlock, if (rag.isNotEmpty()) RagPrompt.context(rag) else "").filter { it.isNotBlank() }.joinToString("\n\n")
        val cm = if (forPrefill) prefillContextManager(s, temporary) else contextManager(s, temporary)
        val built = cm.build(system, if (chatId == null) emptyList() else turns(chatId), if (chatId == null) null else latestSummary(chatId),
            text.ifBlank { "Describe this image." }, ctx, maxOut, extraContext = extra)
        val msgs = built.messages.toMutableList()
        if (imgs.isNotEmpty()) msgs[msgs.size - 1] = msgs.last().copy(images = imgs)
        return MdPrompt(GenerationRequest(msgs, thinking = thinking && images.isEmpty(), maxNewTokens = maxOut), built, ctx, temporary)
    }

    /** Same as [contextManager] but read-only: no summary call (it aborts the read-ahead) and no "Summarising…" status. */
    private fun prefillContextManager(s: AppSettings, temporary: Boolean): com.offlineagent.core.runtime.context.ContextManager =
        com.offlineagent.core.runtime.context.ContextManager(
            counter = { t -> g.inference.countTokens(t) ?: com.offlineagent.core.runtime.context.TokenCounter.estimate(t) },
            summarizer = if (s.autoSummarize) com.offlineagent.core.runtime.context.ConversationSummarizer { _, _, _ -> throw SkipPrefill() } else null,
            memory = if (s.longTermMemory && !temporary) g.chatMemory else null,
            config = com.offlineagent.core.runtime.context.ContextConfig(memoryPages = 2, memoryMaxTokens = 320),
        )

    private suspend fun answerMarkdown(chatId: Long, text: String, images: List<File>, rag: List<RetrievedChunk>, thinking: Boolean, note: String? = null) {
        val s = g.settings.current()
        val mp = markdownPrompt(chatId, text, images, rag, thinking, forPrefill = false)
        val built = mp.built
        val ctx = mp.ctx
        val temporary = mp.temporary
        if (built.summaryChanged) built.summary?.let { sum ->
            chats.insertMessage(MessageEntity(chatId = chatId, role = "system", text = sum.text, kind = KIND_SUMMARY, runId = "upTo:${sum.upToId}", createdAt = System.currentTimeMillis()))
            if (s.longTermMemory && !temporary) viewModelScope.launch { runCatching { g.chatMemory.add("summary of \"${chats.chat(chatId)?.title}\"", sum.text, emptySet()) } }
            // (c) the rolling summary also feeds "reference chat history" for other chats
            if (!temporary) g.appScope.launch { runCatching { g.onyx.longMemory.history.addSummary(chatId, chats.chat(chatId)?.title ?: "Chat", sum.text,
                com.offlineagent.core.agents.memory.MemoryScope.NORMAL) } }
        }
        _ui.update { it.copy(contextUsed = built.promptTokens, contextSize = ctx) }
        // 0.6.2.3: what the composer read (and, optionally, reasoned) while the user typed this message.
        val handoff = pendingHandoff.also { pendingHandoff = null }
        var req = mp.request
        val notes = handoff?.reasoning
        if (notes != null && req.thinking && supportsThinking()) {
            req = req.copy(assistantPrefix = notes.finalPrefix(text), assistantPrefixShown = notes.shownFor(text), closeThinkOnEarlyEnd = true)
        }
        val status = if (images.isNotEmpty()) "Looking at the image…" else "Reading prompt…"
        _ui.update { it.copy(streaming = Streaming(AnswerMode.MARKDOWN, phase = status)) }
        // Phase 4b: every event goes through the streaming reducer; the bubble shows prompt progress, then the live
        // reasoning (expanded), then the answer token by token (≤ 20 UI updates/s, every token at phone speeds).
        val turn = com.offlineagent.core.runtime.chat.StreamingTurn(turnStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), status)
        var stats: com.offlineagent.core.inference.GenerationStats? = null
        val prepMs = turnStartedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0L
        // 0.6.2.2: a sentence / n-gram loop is cut off instead of running to the token cap.
        val guard = com.offlineagent.core.runtime.chat.LoopGuard(com.offlineagent.core.runtime.chat.LoopGuard.Mode.TEXT)
        g.inference.generate(req).collect { ev ->
            val now = System.currentTimeMillis()
            val v = turn.on(ev, now)
            if (ev is GenerationEvent.Token && guard.feed(ev.text) != null) g.inference.cancel()
            if (ev is GenerationEvent.Completed) { stats = ev.stats; recordPrefill(ev.stats) }
            if (turn.shouldEmit(now)) _ui.update { it.copy(streaming = it.streaming?.withView(v)) }
        }
        val v = turn.current
        val st = stats
        val aheadLine = if (st != null && handoff != null) com.offlineagent.core.runtime.chat.ReadAheadFooter.line(
            handoff.prefilledWhileTyping(st.reusedTokens), st.promptTokens - st.reusedTokens, st.promptMillis, notes?.tokens ?: 0) else ""
        if (st != null && handoff != null) android.util.Log.i("OfflineAgent", "read-ahead: ${handoff.prefilledWhileTyping(st.reusedTokens)} tok while typing " +
            "(evaluated ${handoff.evaluatedWhileTyping}), rest ${st.promptTokens - st.reusedTokens} tok in ${st.promptMillis} ms, ahead notes ${notes?.tokens ?: 0}")
        chats.insertMessage(MessageEntity(
            chatId = chatId, role = "assistant", text = v.answer.trim(), reasoning = v.reasoning,
            citationsJson = if (rag.isNotEmpty()) citationsJson(rag) else null,
            statsText = st?.let { listOf(statsLine(v, prepMs, note, guard.stopped), aheadLine).filter { x -> x.isNotBlank() }.joinToString(" · ") } ?: note,
            error = if (st?.stopReason == StopReason.ERROR) "Generation stopped because of an engine error." else null,
            createdAt = System.currentTimeMillis(),
        ))
        chats.touch(chatId, System.currentTimeMillis())
        st?.let { stt -> _ui.update { it.copy(contextUsed = (stt.contextUsed.takeIf { c -> c > 0 } ?: (it.contextUsed + stt.generatedTokens))) } }
        if (!temporary && v.answer.isNotBlank()) {
            val all = chats.messages(chatId)
            val ans = all.lastOrNull { it.role == "assistant" }
            val q = all.lastOrNull { it.role == "user" }
            if (ans != null && q != null) {
                if (s.longTermMemory) viewModelScope.launch {
                    runCatching { g.chatMemory.add("chat \"${chats.chat(chatId)?.title}\"", "User: ${q.text.take(600)}\nAssistant: ${ans.text.take(900)}", setOf(q.id, ans.id)) }
                }
                // Phase 6: index for cross-chat retrieval, then (idle, BACKGROUND priority) look for facts the rules missed.
                g.appScope.launch {
                    runCatching { g.onyx.longMemory.onExchange(chatId, chats.chat(chatId)?.title ?: "Chat", q.text, ans.text) }
                    runCatching { g.onyx.longMemory.modelExtract(chatId, q.id, q.text) }
                }
            }
        }
    }

    /**
     * Canvas mode: the whole answer is one OpenUI program (llama.cpp grammar keeps the syntax valid), versioned per chat.
     *
     * 0.6.2.2 (Sai's "first token 241 s" + looping canvas): the system prompt is the short static canvas sheet (~400
     * tokens instead of the ~2 000-token generated library), so its KV prefix is cached and cheap even when cold; the
     * dynamic part (earlier chats / memory, session, documents, request) comes after it and is held to a device-aware
     * budget; the session block no longer runs the rolling summariser inline; output is capped, sampled with a
     * repetition penalty and cut by a loop guard; repeated statements are deduped and placeholder filler is not shown.
     */
    private suspend fun answerCanvasOpenUi(chatId: Long, text: String, rag: List<RetrievedChunk>) {
        val current = currentCanvasSource(chatId)
        val s = g.settings.current()
        val count: suspend (String) -> Int = { t -> g.inference.countTokens(t) ?: com.offlineagent.core.runtime.context.TokenCounter.estimate(t) }
        val request = if (current != null) com.offlineagent.core.openui.OpenUiPrompt.updateUser(text, current) else text
        val dyn = com.offlineagent.core.runtime.chat.PromptBudget.dynamicTokens(promptTier(s), prefillMeter.tokensPerSecond)
        val previous = if (com.offlineagent.core.agents.memory.PreviousChats.isAbout(text))
            runCatching { g.onyx.longMemory.previousChats(text, chatId, budgetTokens = (dyn * 0.5).toInt().coerceIn(120, 450)).text }.getOrDefault("") else ""
        val split = com.offlineagent.core.runtime.chat.PromptBudget.split(dyn, count(request) + count(previous), memoryWant = if (previous.isBlank()) 200 else 0, historyWant = 700)
        val sess = if (split.historyTokens + split.memoryTokens < 60) "" else
            session(chatId, text, budgetTokens = split.historyTokens + split.memoryTokens, summarize = false, memoryBudget = split.memoryTokens).text
        val ragText = if (rag.isNotEmpty()) RagPrompt.context(rag, 500) else ""
        val (system, user) = com.offlineagent.core.runtime.chat.PromptBudget.staticFirst(
            com.offlineagent.core.openui.OpenUiPrompt.canvasSheet(), listOf(ragText, previous, sess), request)
        val lib = com.offlineagent.core.openui.OpenUiLibrary.DEFAULT
        val canvasStatus = if (current != null) "Updating canvas…" else "Designing canvas…"
        _ui.update { it.copy(streaming = Streaming(AnswerMode.CANVAS_NEW, phase = canvasStatus)) }
        val sb = StringBuilder()
        var lastEmit = 0L
        var stats: com.offlineagent.core.inference.GenerationStats? = null
        val prepMs = turnStartedAt.takeIf { it > 0 }?.let { System.currentTimeMillis() - it } ?: 0L
        val turn = com.offlineagent.core.runtime.chat.StreamingTurn(turnStartedAt.takeIf { it > 0 } ?: System.currentTimeMillis(), canvasStatus)
        val guard = com.offlineagent.core.runtime.chat.LoopGuard(com.offlineagent.core.runtime.chat.LoopGuard.Mode.LINES)
        g.inference.generate(GenerationRequest(
            listOf(ChatMessage(Role.SYSTEM, system), ChatMessage(Role.USER, user)),
            thinking = false, maxNewTokens = CANVAS_MAX_TOKENS, sampling = CANVAS_SAMPLING,
            grammar = com.offlineagent.core.openui.OpenUiGrammar.gbnf(lib, maxStatements = 24),
        )).collect { ev ->
            when (ev) {
                is GenerationEvent.Token -> {
                    if (guard.stopped) return@collect // a loop was cut: ignore what is still in flight
                    sb.append(ev.text)
                    if (guard.feed(ev.text) != null) g.inference.cancel()
                    val v = turn.on(ev, System.currentTimeMillis())
                    val t = System.nanoTime()
                    // The program streams as raw text until it resolves, then as live components (OpenUiStreaming).
                    if (t - lastEmit > 80_000_000) { lastEmit = t; _ui.update { it.copy(streaming = it.streaming?.withView(v)?.copy(text = sb.toString(), phase = canvasStatus)) } }
                }
                is GenerationEvent.Completed -> { stats = ev.stats; recordPrefill(ev.stats); turn.on(ev, System.currentTimeMillis()) }
                else -> {
                    val v = turn.on(ev, System.currentTimeMillis())
                    if (turn.shouldEmit(System.currentTimeMillis())) _ui.update { it.copy(streaming = it.streaming?.withView(v)?.copy(text = sb.toString())) }
                }
            }
        }
        // A loop cut mid-line: drop the partial repeated line.
        val raw = (if (guard.stopped) sb.substring(0, sb.lastIndexOf("\n").coerceAtLeast(0)) else sb.toString()).trim()
        val now = System.currentTimeMillis()
        val statsText = stats?.let { statsLine(turn.current, prepMs, null, guard.stopped) }
        val program = com.offlineagent.core.openui.OpenUiParser.parse(raw).deduped()
        val merged = (if (current != null) com.offlineagent.core.openui.OpenUiParser.parse(current).merge(program) else program).deduped()
        val resolved = com.offlineagent.core.openui.OpenUiResolver(lib).resolve(merged)
        val userCancelled = stats?.stopReason == StopReason.CANCELLED && !guard.stopped
        if (resolved.root == null || resolved.onlyPlaceholders) {
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = if (raw.isBlank() || resolved.onlyPlaceholders) "" else "```openui-lang\n$raw\n```",
                error = when {
                    userCancelled -> null
                    resolved.onlyPlaceholders -> "The model only wrote placeholder text (like \"[text]\"), so I didn't show it. Ask for specific content, or ask it as a question and I'll answer in chat."
                    else -> "The canvas could not be built from the model's output."
                }, statsText = statsText, createdAt = now))
            return
        }
        val source = merged.toSource()
        val v = chats.addCanvasVersion(chatId, com.offlineagent.core.runtime.ui.OpenUiBridge.wrap(source), (if (current != null) "Edit: " else "Created: ") + text.take(60), now)
        chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = com.offlineagent.core.openui.OpenUiText.render(resolved.root).take(2000), kind = "canvas",
            uiJson = com.offlineagent.core.runtime.ui.OpenUiBridge.wrap(source), canvasVersion = v,
            citationsJson = if (rag.isNotEmpty()) citationsJson(rag) else null, statsText = statsText, createdAt = now))
    }

    /** 0.6.2.2: footer + prompt size (+ cached part), prep time before generation, loop stop and routing note. */
    private fun statsLine(v: com.offlineagent.core.runtime.chat.StreamingTurn.View, prepMs: Long, note: String?, loopStopped: Boolean): String =
        listOf(
            note.orEmpty(),
            com.offlineagent.core.runtime.chat.StreamingTurn.footer(v).let { if (loopStopped) it.replace(" · stopped", "") else it },
            com.offlineagent.core.runtime.chat.StreamingTurn.promptDetail(v).let { d -> if (d.isNotBlank() && v.promptMillis > 0) "$d in ${com.offlineagent.core.runtime.chat.StreamingTurn.seconds(v.promptMillis)}" else d },
            if (prepMs >= 2000) "prep ${com.offlineagent.core.runtime.chat.StreamingTurn.seconds(prepMs)}" else "",
            if (loopStopped) "stopped a repeating loop" else "",
        ).filter { it.isNotBlank() }.joinToString(" · ")

    private fun recordPrefill(st: com.offlineagent.core.inference.GenerationStats) {
        prefillMeter.record(st.promptTokens - st.reusedTokens, st.promptMillis)
        android.util.Log.i("OfflineAgent", "prompt ${st.promptTokens} tok (${st.reusedTokens} cached) in ${st.promptMillis} ms, first token ${st.firstTokenMillis} ms")
    }

    private fun promptTier(s: AppSettings): com.offlineagent.core.runtime.chat.PromptBudget.Tier = when (g.engineController.planWithoutModel(s).tier) {
        com.offlineagent.core.inference.RamTier.TINY, com.offlineagent.core.inference.RamTier.SMALL -> com.offlineagent.core.runtime.chat.PromptBudget.Tier.ENTRY
        com.offlineagent.core.inference.RamTier.MEDIUM -> com.offlineagent.core.runtime.chat.PromptBudget.Tier.MID
        com.offlineagent.core.inference.RamTier.LARGE -> com.offlineagent.core.runtime.chat.PromptBudget.Tier.HIGH
        com.offlineagent.core.inference.RamTier.XL -> com.offlineagent.core.runtime.chat.PromptBudget.Tier.FLAGSHIP
    }

    /** OpenUI source of the chat's current canvas version (legacy JSON canvases are bridged). */
    suspend fun currentCanvasSource(chatId: Long): String? {
        val v = chats.chat(chatId)?.canvasVersion ?: 0
        if (v == 0) return null
        return chats.canvasVersion(chatId, v)?.docJson?.let { com.offlineagent.core.runtime.ui.OpenUiBridge.toOpenUiSource(it) }
    }

    // ------------------------------------------------------------ PDF export / report skill

    fun exportChatPdf() {
        val id = _ui.value.chatId ?: return notice("Nothing to export yet.")
        viewModelScope.launch {
            try { g.pdf.exportChat(id); _ui.update { it.copy(pdf = g.pdf.last) } } catch (e: Throwable) { notice("PDF export failed: ${e.message}") }
        }
    }

    fun exportCanvasPdf() {
        val id = _ui.value.chatId ?: return
        viewModelScope.launch {
            try { g.pdf.exportCanvas(id); _ui.update { it.copy(pdf = g.pdf.last) } } catch (e: Throwable) { notice("PDF export failed: ${e.message}") }
        }
    }

    fun closePdf() = _ui.update { it.copy(pdf = null) }

    fun previewLastPdf() { val p = g.pdf.last ?: return notice("No PDF yet"); _ui.update { it.copy(pdf = p) } }

    /** "Write a report / PDF" skill: outline -> section-by-section writing -> multi-page PDF in Downloads. */
    private suspend fun runReport(chatId: Long, request: String) {
        val taskId = "report-$chatId-${System.currentTimeMillis()}"
        com.offlineagent.app.work.LongTasks.start(g, com.offlineagent.app.work.LongTask(taskId, "Writing your report", "Planning…"))
        _ui.update { it.copy(streaming = Streaming(AnswerMode.MARKDOWN, phase = "Planning the report…")) }
        try {
            g.engineController.ensure(ModelRole.CHAT)
            val s = g.settings.current()
            val notes = if (s.useDocuments && g.retriever.hasDocuments()) g.retriever.search(request, 6).joinToString("\n\n") { "[${it.candidate.documentTitle}] ${it.candidate.text}" } else ""
            // Phase 4b: each outline/section call streams its text into the bubble while it is written.
            var lastPartial = 0L
            val writer = com.offlineagent.core.runtime.pdf.ReportWriter(com.offlineagent.core.runtime.agent.InferenceAgentLlm(g.inference,
                onPartial = { partial ->
                    val t = System.currentTimeMillis()
                    if (t - lastPartial > 100) { lastPartial = t; _ui.update { it.copy(streaming = it.streaming?.copy(text = partial)) } }
                },
                onPrompt = { done, total -> _ui.update { it.copy(streaming = it.streaming?.copy(promptDone = done, promptTotal = total)) } },
            ))
            // 0.6.2.1: the report sees the session ("make a detailed pdf on it" is about the earlier exchange).
            val conv = session(chatId, request).text
            val outline = writer.outline(request, 6, notes, conv)
            val md = writer.write(request, outline, notes, conversation = conv, onProgress = { p ->
                val msg = "Section ${p.section}/${p.total}: ${p.phase}"
                _ui.update { it.copy(streaming = it.streaming?.copy(phase = msg, text = "", promptDone = 0, promptTotal = 0)) }
                com.offlineagent.app.work.LongTasks.update(g, com.offlineagent.app.work.LongTask(taskId, "Writing your report", msg, p.section.toFloat() / p.total))
            })
            _ui.update { it.copy(streaming = it.streaming?.copy(phase = "Rendering PDF…")) }
            val body = md.substringAfter('\n') // the writer starts with "# title"
            val out = g.pdf.createPdf(com.offlineagent.core.runtime.pdf.PdfDocModel(outline.title, com.offlineagent.core.runtime.pdf.PdfContent.fromMarkdown(body, g.pdf::imageResolver), subtitle = outline.subtitle),
                com.offlineagent.app.pdf.PdfExporter.safeName(outline.title) + ".pdf")
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = md + "\n\n---\n**PDF saved:** Downloads/OfflineAgent/${out.fileName} (${out.pages} pages)",
                statsText = "report · ${outline.sections.size} sections", createdAt = System.currentTimeMillis()))
            _ui.update { it.copy(pdf = g.pdf.last) }
        } catch (e: CancellationException) { throw e } catch (e: Throwable) {
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", error = "Report failed: ${e.message}", createdAt = System.currentTimeMillis()))
        } finally {
            com.offlineagent.app.work.LongTasks.finish(g, taskId)
            _ui.update { it.copy(streaming = null) }
        }
    }

    private suspend fun answerCanvas(chatId: Long, text: String, mode: AnswerMode, rag: List<RetrievedChunk>) {
        val current = if (mode == AnswerMode.CANVAS_UPDATE) currentDoc(chatId) else null
        val effectiveMode = if (current == null) AnswerMode.CANVAS_NEW else mode
        val ctx = buildString {
            if (rag.isNotEmpty()) append(RagPrompt.context(rag, 500))
            val prev = session(chatId, text, budgetTokens = 700).text
            if (prev.isNotBlank()) append("\n").append(prev)
        }
        val user = if (effectiveMode == AnswerMode.CANVAS_UPDATE) CanvasPrompts.updateUser(text, current!!, ctx) else CanvasPrompts.newCanvasUser(text, ctx)
        _ui.update { it.copy(streaming = Streaming(effectiveMode, phase = if (effectiveMode == AnswerMode.CANVAS_UPDATE) "Updating canvas…" else "Designing canvas…"), canvasOpen = true) }
        val sb = StringBuilder()
        var lastParse = 0L
        var stats: com.offlineagent.core.inference.GenerationStats? = null
        g.inference.generate(GenerationRequest(
            listOf(ChatMessage(Role.SYSTEM, CanvasPrompts.system()), ChatMessage(Role.USER, user)),
            thinking = false, maxNewTokens = 1400, sampling = STRUCTURED_SAMPLING, grammar = CanvasPrompts.grammar(),
        )).collect { ev ->
            when (ev) {
                is GenerationEvent.Token -> {
                    sb.append(ev.text)
                    val t = System.nanoTime()
                    if (t - lastParse > 300_000_000) { // live preview of finished blocks, ~3×/s
                        lastParse = t
                        val partial = withContext(Dispatchers.Default) { PartialJson.close(sb.toString())?.let { (UiParser.parse(it) as? UiParseResult.Success)?.doc } }
                        _ui.update { it.copy(streaming = it.streaming?.copy(partialDoc = partial ?: it.streaming.partialDoc, tokens = ev.generatedTokens, tokensPerSecond = ev.tokensPerSecond)) }
                    }
                }
                is GenerationEvent.Completed -> stats = ev.stats
                else -> Unit
            }
        }
        val raw = sb.toString()
        val now = System.currentTimeMillis()
        val statsText = stats?.let { "%d tok · %.1f tok/s".format(it.generatedTokens, it.tokensPerSecond) }
        if (stats?.stopReason == StopReason.CANCELLED) {
            chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "Canvas generation stopped.", statsText = statsText, createdAt = now))
            return
        }
        when (val r = withContext(Dispatchers.Default) { UiParser.parse(raw) }) {
            is UiParseResult.Success -> {
                val note = if (effectiveMode == AnswerMode.CANVAS_UPDATE) "Edit: ${text.take(60)}" else "Created: ${text.take(60)}"
                val v = chats.addCanvasVersion(chatId, UiSerializer.toJsonString(r.doc), note, now)
                val summary = r.doc.title.ifBlank { "Canvas" } + " — " + r.doc.blocks.joinToString { it.type }
                chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = summary, kind = "canvas",
                    uiJson = UiSerializer.toJsonString(r.doc), canvasVersion = v,
                    citationsJson = if (rag.isNotEmpty()) citationsJson(rag) else null, statsText = statsText, createdAt = now))
                _ui.update { it.copy(canvasOpen = true) }
            }
            is UiParseResult.Failure -> chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = r.fallbackMarkdown,
                statsText = statsText, error = null, createdAt = now))
        }
    }

    suspend fun currentDoc(chatId: Long): UiDoc? {
        val v = chats.chat(chatId)?.canvasVersion ?: 0
        if (v == 0) return null
        return chats.canvasVersion(chatId, v)?.docJson?.let { (UiParser.parse(it) as? UiParseResult.Success)?.doc }
    }

    fun stop() {
        g.inference.cancel()
        if (g.agents.isRunning) g.agents.cancel()
        if (g.onyx.isRunning) g.onyx.cancel()
    }

    // ------------------------------------------------------------ canvas

    fun setCanvasOpen(open: Boolean) = _ui.update { it.copy(canvasOpen = open) }

    fun selectCanvasVersion(v: Int) {
        val id = _ui.value.chatId ?: return
        viewModelScope.launch { if (chats.canvasVersion(id, v) != null) chats.setCanvasVersion(id, v) }
    }

    fun undoCanvas() { val c = chat.value ?: return; if (c.canvasVersion > 1) selectCanvasVersion(c.canvasVersion - 1) }
    fun redoCanvas() { val c = chat.value ?: return; if (c.canvasVersion < (canvasVersions.value.maxOfOrNull { it.version } ?: 0)) selectCanvasVersion(c.canvasVersion + 1) }

    fun canvasMarkdown(doc: UiDoc) = UiMarkdown.render(doc)

    // ------------------------------------------------------------ agent / skills

    fun runSkill(skill: Skill, request: String) = send(request.ifBlank { skill.description }, forceSkill = skill)

    private suspend fun runAgent(chatId: Long, request: String, skill: Skill?) {
        val msgId = chats.insertMessage(MessageEntity(chatId = chatId, role = "assistant", text = "", kind = "agent",
            reasoning = skill?.name ?: "Agent", createdAt = System.currentTimeMillis()))
        val conv = session(chatId, request).text
        val runId = g.agents.start(chatId, request, skill, conv) { cp -> finishAgentMessage(msgId, cp) }
        if (runId == null) {
            chats.message(msgId)?.let { chats.updateMessage(it.copy(error = "Another agent run is in progress.")) }
            return
        }
        chats.message(msgId)?.let { chats.updateMessage(it.copy(runId = runId)) }
    }

    private suspend fun finishAgentMessage(msgId: Long, cp: RunCheckpoint) {
        val m = chats.message(msgId) ?: return
        chats.updateMessage(m.copy(
            text = cp.finalAnswer ?: "",
            error = when (cp.status) { RunStatus.FAILED -> "Run failed: ${cp.error}"; RunStatus.CANCELLED -> "Run cancelled."; else -> null },
            canvasVersion = if (cp.canvasRendered) chats.chat(m.chatId)?.canvasVersion else null,
        ))
        if (cp.canvasRendered && _ui.value.chatId == m.chatId) _ui.update { it.copy(canvasOpen = true) }
    }

    fun resumeUnfinished() {
        val cp = _ui.value.unfinishedRun ?: return
        _ui.update { it.copy(unfinishedRun = null) }
        viewModelScope.launch {
            val chatId = cp.chatId
            if (chatId != null && chats.chat(chatId) != null) openChat(chatId)
            val msg = chatId?.let { id -> chats.messages(id).lastOrNull { it.runId == cp.runId } }
            val conv = if (chatId != null && chats.chat(chatId) != null) runCatching { session(chatId, cp.request).text }.getOrDefault("") else ""
            val ok = g.agents.resume(cp.runId, conv) { done -> if (msg != null) finishAgentMessage(msg.id, done) }
            if (!ok) notice("Another run is in progress.")
        }
    }

    fun discardUnfinished() {
        val cp = _ui.value.unfinishedRun ?: return
        _ui.update { it.copy(unfinishedRun = null) }
        viewModelScope.launch {
            g.agents.checkpoint(cp.runId)?.let { RoomRunStoreSave(it.copy(status = RunStatus.CANCELLED, error = "Discarded after restart")) }
        }
    }

    private suspend fun RoomRunStoreSave(cp: RunCheckpoint) = com.offlineagent.core.data.RoomRunStore(g.db.agent()).save(cp)

    fun observeSteps(runId: String) = g.agents.observeSteps(runId)

    override fun onCleared() {
        g.inference.cancel()
        super.onCleared()
    }

    companion object {
        const val KIND_SUMMARY = "summary"
        const val DUPLICATE_WINDOW_MS = 1500L
        const val KIND_AUTOMATION = "automation"
        /** Phase 5b: a multi-agent (ONYX) turn: runId → live trace; reasoning = task context for follow-ups. */
        const val KIND_ONYX = "onyx"
        const val SYSTEM_PROMPT = com.offlineagent.core.runtime.chat.ChatPrompts.BASE
        /** 0.6.2.2: a compact canvas (≤ ~12 components) is 300–500 tokens; 1 600 let a loop run for minutes. */
        const val CANVAS_MAX_TOKENS = 768
        /** 0.6.2.2: structured sampling + a light repetition penalty (llama.cpp penalties over the last 64 tokens). */
        val CANVAS_SAMPLING = STRUCTURED_SAMPLING.copy(repeatPenalty = 1.1f)
        /** 0.6.2.2: this phone's measured prompt speed (shared by every chat screen). */
        val prefillMeter = com.offlineagent.core.runtime.chat.PrefillMeter()
    }
}

