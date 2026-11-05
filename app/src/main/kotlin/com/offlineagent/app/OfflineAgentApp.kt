package com.offlineagent.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.offlineagent.app.automation.AutomationController
import com.offlineagent.app.automation.FileAutomationLog
import com.offlineagent.app.data.AgentService
import com.offlineagent.app.data.AppIntents
import com.offlineagent.app.data.ChatMemory
import com.offlineagent.app.data.DocumentIndexer
import com.offlineagent.app.data.EngineController
import com.offlineagent.app.data.ModelRepository
import com.offlineagent.app.data.ModelStore
import com.offlineagent.app.data.Retriever
import com.offlineagent.app.data.SkillRepository
import com.offlineagent.app.mcp.McpManager
import com.offlineagent.app.pdf.PdfExporter
import com.offlineagent.app.phone.ActivityLauncher
import com.offlineagent.app.phone.AndroidAppsPort
import com.offlineagent.app.phone.AndroidClipboardPort
import com.offlineagent.app.phone.AndroidCommsPort
import com.offlineagent.app.phone.AndroidDevicePort
import com.offlineagent.app.phone.AndroidNotificationsPort
import com.offlineagent.app.phone.AndroidPimPort
import com.offlineagent.app.phone.Notifications
import com.offlineagent.app.phone.RoomNotesStore
import com.offlineagent.app.phone.SafFilesPort
import com.offlineagent.app.phone.WorkManagerReminders
import com.offlineagent.app.vault.ProfileVault
import com.offlineagent.app.work.LongTask
import com.offlineagent.app.work.LongTasks
import com.offlineagent.core.data.AppDatabase
import com.offlineagent.core.data.AppSettings
import com.offlineagent.core.data.SettingsRepository
import com.offlineagent.core.inference.EmbeddingService
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.InferenceAdapter
import com.offlineagent.core.inference.UnloadReason
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.agent.InferenceAgentLlm
import com.offlineagent.core.runtime.pdf.CreatePdfTool
import com.offlineagent.core.runtime.phone.AutomationPolicy
import com.offlineagent.core.runtime.phone.PhonePorts
import com.offlineagent.core.runtime.phone.PhoneServers
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Manual DI root. One [InferenceAdapter] per process (a model is hundreds of MB; never two chat models), one embedding
 * service, one Room database. Agent runs and indexing live in [appScope] so they survive screen changes.
 * Phase 3 services (MCP, automation, vault, PDF, long-term memory) are created lazily for a fast cold start.
 */
class OfflineAgentApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        // A failing background job (load, reload, indexing…) must never take the whole app down.
        Log.e(TAG, "background job failed", e)
        com.offlineagent.app.diag.Diagnostics.log("background job failed: $e")
    })
    lateinit var inference: InferenceAdapter private set
    lateinit var embeddings: EmbeddingService private set
    lateinit var db: AppDatabase private set
    lateinit var settings: SettingsRepository private set
    lateinit var legacyModels: ModelRepository private set
    lateinit var models: ModelStore private set
    lateinit var engineController: EngineController private set
    lateinit var indexer: DocumentIndexer private set
    lateinit var retriever: Retriever private set
    lateinit var skills: SkillRepository private set
    lateinit var agents: AgentService private set
    val intents = AppIntents()
    val launcher = ActivityLauncher()

    /** Last known settings (kept current by a collector) for code that cannot suspend. */
    @Volatile var cachedSettings: AppSettings = AppSettings()
        private set

    private val _cameraRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** take_photo tool -> MainActivity opens the camera; the photo is attached to the current chat. */
    val cameraRequests: SharedFlow<Unit> = _cameraRequests

    /** Phase 4b: the screen agent's current model output (streamed into the automation card). */
    val automationPartial = kotlinx.coroutines.flow.MutableStateFlow("")

    val vault by lazy { ProfileVault(this) }
    val automationLog by lazy { FileAutomationLog(this) }
    val automation by lazy { AutomationController(this) }
    val pdf by lazy { PdfExporter(this, db.chats()) }
    val modelDownloads by lazy { com.offlineagent.app.models.ModelDownloads(this) }
    val chatMemory by lazy { ChatMemory(this, db.documents(), embeddings, models, retriever) }
    val mcp by lazy {
        McpManager(this, PhonePorts(
            device = AndroidDevicePort(this, launcher) { _cameraRequests.emit(Unit); "Camera opened — take the photo and it will be attached to this chat." },
            reminders = WorkManagerReminders(this), apps = AndroidAppsPort(this, launcher), comms = AndroidCommsPort(launcher),
            pim = AndroidPimPort(this, launcher), files = SafFilesPort(this), clipboard = AndroidClipboardPort(this),
            notifications = AndroidNotificationsPort(this), notes = RoomNotesStore(db.misc()), screen = automation,
        ))
    }
    private val createPdfTool by lazy { CreatePdfTool(pdf) }
    /** Phase 5: multi-agent orchestrator, memories, task checkpoints, outbox, device monitor (see docs/PHASE5.md). */
    val onyx by lazy { com.offlineagent.app.onyx.OnyxRuntime(this) }

    fun automationPolicy() = AutomationPolicy(userAllowed = { cachedSettings.automationAllowedApps }, userBlocked = { cachedSettings.automationBlockedApps })

    /** The loaded chat model as an [AgentLlm], or null (never loads one: used where latency matters, e.g. autofill). */
    fun readyLlmOrNull(): AgentLlm? = if (inference.state.value is EngineState.Ready && engineController.loaded.value?.role == "CHAT") InferenceAgentLlm(inference) else null

    /** Notification "Cancel": stops every long task (agent run, report, automation). */
    fun cancelAllLongTasks() {
        agents.cancel()
        inference.cancel()
        runCatching { automation.stopAll() }
    }

    /** Set when the previous run crashed (banner + safe mode). */
    val lastCrash = kotlinx.coroutines.flow.MutableStateFlow<com.offlineagent.app.diag.Diagnostics.Startup?>(null)
    private lateinit var engineAdapter: com.offlineagent.core.inference.EngineInferenceAdapter

    /**
     * Phase 4b (ANR audit): debug builds flag disk reads/writes, slow calls and network on the main thread. Violations
     * are logged (logcat tag StrictMode) and the first ones are copied to the diagnostics log, so a main-thread file or
     * JNI call shows up in "Share diagnostics" before it becomes an ANR on a slower phone. Never crashes the app.
     */
    private fun enableStrictMode() {
        val counter = java.util.concurrent.atomic.AtomicInteger()
        val b = android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().detectCustomSlowCalls().penaltyLog()
        if (android.os.Build.VERSION.SDK_INT >= 28) b.penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { v ->
            if (counter.incrementAndGet() <= 30) com.offlineagent.app.diag.Diagnostics.log("StrictMode main-thread violation: ${v.javaClass.simpleName} at " +
                v.stackTrace.firstOrNull { it.className.startsWith("com.offlineagent") }?.let { "${it.className}.${it.methodName}:${it.lineNumber}" })
        }
        android.os.StrictMode.setThreadPolicy(b.build())
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
    }

    override fun onCreate() {
        super.onCreate()
        // P0 crash safety first: crash handler, previous-crash detection, compatibility CPU kernels.
        com.offlineagent.app.diag.Diagnostics.init(this)
        com.offlineagent.app.automation.ForegroundTracker.install(this) // Phase 6.2: in-app sheet vs overlay routing
        if (BuildConfig.DEBUG) enableStrictMode()
        com.offlineagent.engine.llamacpp.LlamaNative.cpuVariant = com.offlineagent.app.diag.Diagnostics.compatVariant()
        val startup = runCatching { com.offlineagent.app.diag.Diagnostics.startupCheck() }.getOrNull()
        engineAdapter = LlamaCppEngine.createAdapter(this)
        inference = engineAdapter
        embeddings = LlamaCppEngine.createEmbedder(this)
        db = AppDatabase.create(this)
        settings = SettingsRepository(this)
        legacyModels = ModelRepository(this).also { it.cleanupPartials() }
        models = ModelStore(db.misc(), legacyModels)
        engineController = EngineController(inference, models, settings, onPrefixCacheInvalid = { LlamaCppEngine.clearPrefixCache(this) },
            powerDecision = { runCatching { onyx.power.value }.getOrNull() })
        com.offlineagent.app.diag.Diagnostics.context = {
            "settings: ${cachedSettings.let { "ctx=${it.contextLength} kv=${it.kvCache} fa=${it.flashAttention} large=${it.largeContext} safe=${it.safeMode} threads=${it.threads} batch=${it.batchSize}/${it.ubatchSize} prefixCache=${it.prefixCache} spec=${it.speculative}" }}\n" +
                "last load: ${engineController.lastConfig}"
        }
        // Phase 4b: read /proc + sysfs once, off the main thread (DeviceInfo caches them).
        appScope.launch { runCatching { com.offlineagent.engine.llamacpp.DeviceInfo.totalRamBytes(); com.offlineagent.engine.llamacpp.DeviceInfo.cpuMaxFreqsKhz() } }
        appScope.launch {
            runCatching {
                // Phase 4 settings migration (16K/32K opt-in, f16 KV) also drops every Phase 3 prompt-cache file.
                if (settings.migrate()) LlamaCppEngine.clearPrefixCache(this@OfflineAgentApp)
                if (startup?.crashed == true) {
                    LlamaCppEngine.clearPrefixCache(this@OfflineAgentApp)
                    settings.update { it.copy(safeMode = true, safeModeReason = "Last run crashed (${startup.reason}).") }
                    lastCrash.value = startup
                }
            }.onFailure { Log.w(TAG, "startup safety steps failed", it) }
        }
        indexer = DocumentIndexer(this, db.documents(), embeddings, models, appScope)
        retriever = Retriever(db.documents(), embeddings, models)
        val toolNames = setOf(
            "search_docs", "read_document", "summarize", "calculator", "get_datetime", "create_note", "memory_read",
            "memory_write", "share_text", "create_calendar_event", "render_ui", "canvas_update", "create_pdf", "notes_search",
            // Phase 5 device tools (+ fetch_url in the connected flavor)
            "schedule_exact_reminder", "set_brightness", "post_notification", "save_to_downloads", "add_calendar_event_direct",
        ) + com.offlineagent.app.onyx.NetworkTools.names + PhoneServers(PhonePorts()).apply { build() }.toolGroups.keys
        skills = SkillRepository(db.skills(), toolNames)
        agents = AgentService(
            appScope, inference, engineController, db.agent(), db.chats(), db.misc(), retriever, db.documents(), intents, skills, settings,
            extraTools = { mcp.tools + createPdfTool + onyx.extraTools },
            disabledTools = { mcp.disabledTools(cachedSettings.phoneGroups) },
            onLongTask = { runId, progress ->
                if (progress == null) LongTasks.finish(this, runId)
                else if (LongTasks.current.value?.id == runId) LongTasks.update(this, LongTask(runId, "Assistant is working", progress))
                else LongTasks.start(this, LongTask(runId, "Assistant is working", progress))
            },
        )
        Notifications.ensureChannels(this)
        onyx.start()
        appScope.launch {
            settings.settings.collect {
                cachedSettings = it
                engineAdapter.prefixCacheEnabled = it.prefixCache && !it.safeMode
            }
        }
        appScope.launch {
            runCatching { models.migrateLegacy() }.onFailure { Log.w(TAG, "legacy model migration failed", it) }
            runCatching { skills.seedBuiltins() }.onFailure { Log.w(TAG, "seeding skills failed", it) }
            runCatching { mcp.startBuiltIns() }.onFailure { Log.w(TAG, "MCP start failed", it) }
            // Re-connect on-device MCP servers the user enabled earlier.
            val enabled = settings.current().mcpServers
            if (enabled.isNotEmpty()) mcp.discoverOnDevice().filter { it.id in enabled }.forEach { s -> mcp.connectOnDevice(s) }
        }
        appScope.launch {
            inference.state.collect { if (it is EngineState.Unloaded || it is EngineState.Failed) engineController.markUnloaded() }
        }
    }

    /**
     * Memory management on 8 GB phones. Keep-warm (default): the model stays loaded while the app is in the background
     * and is freed only on real pressure (RUNNING_CRITICAL / BACKGROUND / COMPLETE). With keep-warm off it is also freed
     * when the UI is hidden. Never while an agent run, automation or indexing job is using it.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (critical || (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !cachedSettings.keepWarm)) unloadForMemory("onTrimMemory($level)")
        // The embedding model is small but only needed while indexing/searching: free it first.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !indexer.isBusy) appScope.launch { embeddings.unload() }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        unloadForMemory("onLowMemory")
    }

    private fun unloadForMemory(why: String) {
        val s = inference.state.value
        // Never pull the model from under a running agent, report, automation or indexing job; they hold the user's work.
        if (agents.isRunning || indexer.isBusy || LongTasks.current.value != null) return
        if (s is EngineState.Ready) {
            Log.w(TAG, "Unloading model: $why")
            appScope.launch { inference.unload(UnloadReason.LOW_MEMORY); embeddings.unload() }
        }
    }

    companion object { const val TAG = "OfflineAgent" }
}
