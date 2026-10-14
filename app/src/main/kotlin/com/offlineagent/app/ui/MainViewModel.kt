package com.offlineagent.app.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.StoredModel
import com.offlineagent.app.data.formatBytes
import com.offlineagent.core.inference.BusyException
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.GenerationStats
import com.offlineagent.core.inference.GgufFile
import com.offlineagent.core.inference.LoadConfig
import com.offlineagent.core.inference.PromptTooLongException
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.StopReason
import com.offlineagent.core.inference.ThinkSplitter
import com.offlineagent.core.inference.UnloadReason
import com.offlineagent.engine.llamacpp.DeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiMessage(
    val id: Long,
    val role: Role,
    val text: String,
    val reasoning: String = "",
    val thinkingInProgress: Boolean = false,
    val streaming: Boolean = false,
    val stats: GenerationStats? = null,
    val liveTokensPerSecond: Double = 0.0,
    val error: String? = null,
)

data class ImportProgress(val copied: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (copied.toFloat() / total).coerceIn(0f, 1f) else null
}

data class Settings(
    val contextLength: Int = LoadConfig.DEFAULT_CONTEXT_LENGTH,
    val threads: Int = DeviceInfo.recommendedThreads(),
    val thinking: Boolean = false,
)

data class UiState(
    val storedModel: StoredModel? = null,
    val importing: ImportProgress? = null,
    val messages: List<UiMessage> = emptyList(),
    val generating: Boolean = false,
    val settings: Settings = Settings(),
    val recommendedThreads: Int = DeviceInfo.recommendedThreads(),
    val cpuCount: Int = Runtime.getRuntime().availableProcessors(),
    val notice: String? = null,
    val systemInfo: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as OfflineAgentApp
    private val inference = graph.inference
    private val models = graph.models

    private val _ui = MutableStateFlow(UiState(storedModel = models.current()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val engine: StateFlow<EngineState> =
        inference.state.stateIn(viewModelScope, SharingStarted.Eagerly, inference.state.value)

    private var importJob: Job? = null
    private var genJob: Job? = null
    private var nextId = 1L

    init {
        viewModelScope.launch {
            inference.state.collect { s ->
                if (s is EngineState.Unloaded && s.reason == UnloadReason.LOW_MEMORY) {
                    notice("Model was unloaded because the phone ran low on memory. Tap Load to continue.")
                }
            }
        }
    }

    fun notice(msg: String?) = _ui.update { it.copy(notice = msg) }

    // ---------------- Model management ----------------

    fun importModel(uri: Uri) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            // free the old model first: the copy may replace it on disk
            if (engine.value is EngineState.Ready) inference.unload(UnloadReason.USER)
            _ui.update { it.copy(importing = ImportProgress(0, -1)) }
            try {
                val stored = models.import(uri) { c, t -> _ui.update { it.copy(importing = ImportProgress(c, t)) } }
                _ui.update { it.copy(storedModel = stored, importing = null) }
                notice("Model copied to app storage (${formatBytes(stored.sizeBytes)}). Tap Load.")
            } catch (e: CancellationException) {
                _ui.update { it.copy(importing = null) }
                throw e
            } catch (e: Throwable) {
                _ui.update { it.copy(importing = null, storedModel = models.current()) }
                notice("Import failed: ${e.message}")
            }
        }
    }

    fun cancelImport() { importJob?.cancel() }

    fun loadModel() {
        val stored = _ui.value.storedModel ?: return notice("Pick a .gguf model first.")
        val s = _ui.value.settings
        val needed = GgufFile.estimatedRuntimeBytes(stored.sizeBytes, s.contextLength)
        val avail = availableRam()
        viewModelScope.launch {
            if (avail in 1 until needed) {
                notice("Warning: only ${formatBytes(avail)} RAM free, model needs about ${formatBytes(needed)}. Close other apps if loading fails.")
            }
            try {
                inference.load(LoadConfig(stored.file.absolutePath, contextLength = s.contextLength, threads = s.threads))
            } catch (e: Throwable) {
                notice("Could not load model: ${e.message}")
            }
        }
    }

    fun unloadModel() {
        viewModelScope.launch { inference.unload(UnloadReason.USER) }
    }

    fun deleteModel() {
        viewModelScope.launch {
            inference.unload(UnloadReason.USER)
            models.delete()
            _ui.update { it.copy(storedModel = null) }
        }
    }

    fun loadSystemInfo() {
        viewModelScope.launch { _ui.update { it.copy(systemInfo = inference.systemInfo()) } }
    }

    fun setContextLength(n: Int) = _ui.update { it.copy(settings = it.settings.copy(contextLength = n)) }
    fun setThreads(n: Int) = _ui.update { it.copy(settings = it.settings.copy(threads = n.coerceIn(1, 8))) }
    fun setThinking(on: Boolean) = _ui.update { it.copy(settings = it.settings.copy(thinking = on)) }

    private fun availableRam(): Long = runCatching {
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    }.getOrDefault(-1L)

    // ---------------- Chat ----------------

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || genJob?.isActive == true) return
        if (engine.value !is EngineState.Ready) return notice("Load a model first (Model tab).")

        val thinking = _ui.value.settings.thinking
        val userMsg = UiMessage(nextId++, Role.USER, prompt)
        val replyId = nextId++
        val history = buildHistory(_ui.value.messages + userMsg)
        _ui.update {
            it.copy(
                messages = it.messages + userMsg + UiMessage(replyId, Role.ASSISTANT, "", streaming = true),
                generating = true,
            )
        }

        genJob = viewModelScope.launch {
            val splitter = ThinkSplitter()
            try {
                inference.generate(
                    GenerationRequest(history, thinking = thinking, maxNewTokens = if (thinking) 1536 else 768),
                ).collect { ev ->
                    when (ev) {
                        is GenerationEvent.PromptProcessed -> Unit
                        is GenerationEvent.Token -> {
                            val p = splitter.append(ev.text)
                            updateMessage(replyId) {
                                it.copy(
                                    text = p.answer, reasoning = p.reasoning, thinkingInProgress = p.thinkingInProgress,
                                    liveTokensPerSecond = ev.tokensPerSecond,
                                )
                            }
                        }
                        is GenerationEvent.Completed -> {
                            val p = splitter.current()
                            updateMessage(replyId) {
                                it.copy(
                                    text = p.answer.trimEnd(), reasoning = p.reasoning, thinkingInProgress = false,
                                    streaming = false, stats = ev.stats,
                                    error = if (ev.stats.stopReason == StopReason.ERROR) "Generation stopped because of an engine error." else null,
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BusyException) {
                updateMessage(replyId) { it.copy(streaming = false, error = "Engine busy, try again in a moment.") }
            } catch (e: PromptTooLongException) {
                updateMessage(replyId) { it.copy(streaming = false, error = "Your message is too long for the context window.") }
            } catch (e: Throwable) {
                updateMessage(replyId) { it.copy(streaming = false, error = e.message ?: e.toString()) }
            } finally {
                updateMessage(replyId) { if (it.streaming) it.copy(streaming = false) else it }
                _ui.update { it.copy(generating = false) }
            }
        }
    }

    /** Stop button: ask native code to abort; the flow then completes with StopReason.CANCELLED. */
    fun stop() {
        inference.cancel()
    }

    fun clearChat() {
        if (genJob?.isActive == true) return
        _ui.update { it.copy(messages = emptyList()) }
    }

    private fun buildHistory(msgs: List<UiMessage>): List<ChatMessage> {
        val turns = msgs.filter { it.error == null && (it.role == Role.USER || it.text.isNotBlank()) }
            .map { ChatMessage(it.role, it.text) }
        return listOf(ChatMessage(Role.SYSTEM, SYSTEM_PROMPT)) + turns
    }

    private inline fun updateMessage(id: Long, crossinline f: (UiMessage) -> UiMessage) {
        _ui.update { s -> s.copy(messages = s.messages.map { if (it.id == id) f(it) else it }) }
    }

    override fun onCleared() {
        inference.cancel()
        super.onCleared()
    }

    companion object {
        const val SYSTEM_PROMPT =
            "You are a helpful assistant running fully offline on the user's Android phone. " +
                "Answer clearly and concisely."
    }
}
