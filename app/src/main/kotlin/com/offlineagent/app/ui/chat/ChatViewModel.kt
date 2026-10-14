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
import com.offlineagent.core.inference.BusyException
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.ImageData
import com.offlineagent.core.inference.PromptTooLongException
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.StopReason
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
)

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
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val chat: StateFlow<ChatEntity?> = chatIdFlow.flatMapLatest { id -> if (id == null) flowOf(null) else chats.observeChat(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val canvasVersions: StateFlow<List<CanvasVersionEntity>> = chatIdFlow.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chats.observeCanvasVersions(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val agentLive = g.agents.live
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

    // ------------------------------------------------------------ chats

    fun openChat(id: Long?) {
        if (genJob?.isActive == true) return notice("Wait for the current answer to finish (or press Stop).")
        chatIdFlow.value = id
        _ui.update { it.copy(chatId = id, canvasOpen = false, streaming = null, pendingImages = emptyList()) }
        viewModelScope.launch { g.settings.update { it.copy(lastChatId = id ?: 0) } }
    }

    fun newChat() = openChat(null)

    fun deleteChat(id: Long) = viewModelScope.launch {
        chats.deleteChat(id)
        if (_ui.value.chatId == id) openChat(null)
    }

    fun renameChat(id: Long, title: String) = viewModelScope.launch { chats.rename(id, title.take(80)) }

    private suspend fun ensureChat(firstText: String): Long {
        _ui.value.chatId?.let { return it }
        val now = System.currentTimeMillis()
        val id = chats.insertChat(ChatEntity(title = firstText.lines().first().take(48).ifBlank { "New chat" }, createdAt = now, updatedAt = now))
        chatIdFlow.value = id
        _ui.update { it.copy(chatId = id) }
        g.settings.update { it.copy(lastChatId = id) }
        return id
    }

    // ------------------------------------------------------------ settings shortcuts

    fun setOutput(p: OutputPreference) = viewModelScope.launch { g.settings.update { it.copy(output = p) } }
    fun setThinking(on: Boolean) = viewModelScope.launch { g.settings.update { it.copy(thinking = on) } }
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
        if (!f.isFile || f.length() == 0L) return@launch
        val out = withContext(Dispatchers.IO) { copyDownscaled(Uri.fromFile(f)).also { f.delete() } }
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
    }

    private var parsedSkillsCache: List<Skill> = emptyList()
    init { viewModelScope.launch { skills.collect { parsedSkillsCache = g.skills.parsed() } } }

    // ------------------------------------------------------------ sending

    val busy: Boolean get() = genJob?.isActive == true || g.agents.isRunning

    fun send(rawText: String, forceSkill: Skill? = null) {
        val text = rawText.trim()
        val images = _ui.value.pendingImages
        if ((text.isEmpty() && images.isEmpty()) || busy) return
        _ui.update { it.copy(pendingImages = emptyList(), skillSuggestion = null) }
        genJob = viewModelScope.launch {
            val chatId = ensureChat(text.ifBlank { "Photo" })
            val now = System.currentTimeMillis()
            chats.insertMessage(MessageEntity(chatId = chatId, role = "user", text = text,
                attachmentsJson = if (images.isEmpty()) null else JsonArray(images.map { JsonPrimitive(it.absolutePath) }).toString(), createdAt = now))
            chats.touch(chatId, now)

            // Skills: explicit /skill-id, or the suggestion chip
            val explicit = if (forceSkill != null) SkillMatcher.Match(forceSkill, text, true) else if (text.startsWith("/")) SkillMatcher.match(text, g.skills.parsed()) else null
            if (explicit != null) { runAgent(chatId, explicit.request, explicit.skill); return@launch }

            val s = g.settings.current()
            val hasCanvas = (chats.chat(chatId)?.canvasVersion ?: 0) > 0
            val decision = if (images.isNotEmpty()) com.offlineagent.core.runtime.ui.RouteDecision(AnswerMode.MARKDOWN, "image")
                else CanvasRouter.decide(text, s.output, hasCanvas)
            try {
                _ui.update { it.copy(streaming = Streaming(decision.mode, phase = if (images.isNotEmpty()) "Loading vision model…" else "Loading model…")) }
                g.engineController.ensure(if (images.isNotEmpty()) ModelRole.VISION else ModelRole.CHAT)
                val rag = if (s.useDocuments && decision.mode != AnswerMode.CANVAS_UPDATE && images.isEmpty() && g.retriever.hasDocuments()) {
                    _ui.update { it.copy(streaming = it.streaming?.copy(phase = "Searching your documents…")) }
                    g.retriever.search(text, 4).filter { it.score >= 0.25f }
                } else emptyList()
                when (decision.mode) {
                    AnswerMode.MARKDOWN -> answerMarkdown(chatId, text, images, rag, s.thinking)
                    AnswerMode.CANVAS_NEW, AnswerMode.CANVAS_UPDATE -> answerCanvas(chatId, text, decision.mode, rag)
                }
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
                _ui.update { it.copy(streaming = null) }
            }
        }
    }

    private suspend fun history(chatId: Long, maxTurns: Int = 8): List<ChatMessage> {
        val msgs = chats.messages(chatId).filter { it.error == null }.takeLast(maxTurns * 2 + 1)
        return msgs.dropLast(1).mapNotNull { m ->
            when {
                m.role == "user" -> ChatMessage(Role.USER, m.text.ifBlank { "(photo)" })
                m.kind == "canvas" && m.uiJson != null -> ChatMessage(Role.ASSISTANT, "(I showed a canvas: ${m.text})")
                m.text.isNotBlank() -> ChatMessage(Role.ASSISTANT, m.text.take(1500))
                else -> null
            }
        }.dropWhile { it.role == Role.ASSISTANT }
    }

    private fun citationsJson(rag: List<RetrievedChunk>) = buildJsonArray {
        rag.forEachIndexed { i, r -> add(buildJsonObject { put("n", i + 1); put("title", r.candidate.documentTitle); put("text", r.candidate.text); put("documentId", r.candidate.documentId) }) }
    }.toString()

    private suspend fun answerMarkdown(chatId: Long, text: String, images: List<File>, rag: List<RetrievedChunk>, thinking: Boolean) {
        val system = buildString {
            append(SYSTEM_PROMPT)
            if (rag.isNotEmpty()) append("\n\n").append(RagPrompt.context(rag))
        }
        val imgs = withContext(Dispatchers.Default) { images.map { toImageData(it) } }
        val req = GenerationRequest(
            listOf(ChatMessage(Role.SYSTEM, system)) + history(chatId) + ChatMessage(Role.USER, text.ifBlank { "Describe this image." }, imgs),
            thinking = thinking && images.isEmpty(), maxNewTokens = if (thinking) 1536 else 900,
        )
        _ui.update { it.copy(streaming = Streaming(AnswerMode.MARKDOWN, phase = if (images.isNotEmpty()) "Looking at the image…" else "Reading…")) }
        val splitter = ThinkSplitter()
        var lastEmit = 0L
        var stats: com.offlineagent.core.inference.GenerationStats? = null
        g.inference.generate(req).collect { ev ->
            when (ev) {
                is GenerationEvent.PromptProcessed -> _ui.update { it.copy(streaming = it.streaming?.copy(phase = "Writing…")) }
                is GenerationEvent.Token -> {
                    val p = splitter.append(ev.text)
                    val t = System.nanoTime()
                    if (t - lastEmit > 50_000_000) { // ≤20 UI updates/s: smooth, no jank
                        lastEmit = t
                        _ui.update { it.copy(streaming = it.streaming?.copy(text = p.answer, reasoning = p.reasoning, tokens = ev.generatedTokens, tokensPerSecond = ev.tokensPerSecond)) }
                    }
                }
                is GenerationEvent.Completed -> stats = ev.stats
            }
        }
        val p = splitter.current()
        val st = stats
        chats.insertMessage(MessageEntity(
            chatId = chatId, role = "assistant", text = p.answer.trim(), reasoning = p.reasoning,
            citationsJson = if (rag.isNotEmpty()) citationsJson(rag) else null,
            statsText = st?.let { "%d tok · %.1f tok/s%s".format(it.generatedTokens, it.tokensPerSecond, if (it.stopReason == StopReason.CANCELLED) " · stopped" else "") },
            error = if (st?.stopReason == StopReason.ERROR) "Generation stopped because of an engine error." else null,
            createdAt = System.currentTimeMillis(),
        ))
        chats.touch(chatId, System.currentTimeMillis())
    }

    private suspend fun answerCanvas(chatId: Long, text: String, mode: AnswerMode, rag: List<RetrievedChunk>) {
        val current = if (mode == AnswerMode.CANVAS_UPDATE) currentDoc(chatId) else null
        val effectiveMode = if (current == null) AnswerMode.CANVAS_NEW else mode
        val ctx = buildString {
            if (rag.isNotEmpty()) append(RagPrompt.context(rag, 500))
            val prev = history(chatId, 3).joinToString("\n") { (if (it.role == Role.USER) "User: " else "Assistant: ") + it.content.take(300) }
            if (prev.isNotBlank() && effectiveMode == AnswerMode.CANVAS_NEW) append("\nEarlier conversation:\n").append(prev)
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
        val runId = g.agents.start(chatId, request, skill) { cp -> finishAgentMessage(msgId, cp) }
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
            val ok = g.agents.resume(cp.runId) { done -> if (msg != null) finishAgentMessage(msg.id, done) }
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
        const val SYSTEM_PROMPT =
            "You are a helpful assistant running fully offline on the user's Android phone. Answer clearly and concisely. " +
                "Use Markdown: headings, bullet lists, tables, **bold** and fenced code blocks where they help."
    }
}
