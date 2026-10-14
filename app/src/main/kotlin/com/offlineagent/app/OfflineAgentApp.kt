package com.offlineagent.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.offlineagent.app.data.AgentService
import com.offlineagent.app.data.AppIntents
import com.offlineagent.app.data.DocumentIndexer
import com.offlineagent.app.data.EngineController
import com.offlineagent.app.data.ModelRepository
import com.offlineagent.app.data.ModelStore
import com.offlineagent.app.data.Retriever
import com.offlineagent.app.data.SkillRepository
import com.offlineagent.core.data.AppDatabase
import com.offlineagent.core.data.SettingsRepository
import com.offlineagent.core.inference.EmbeddingService
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.InferenceAdapter
import com.offlineagent.core.inference.UnloadReason
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI root. One [InferenceAdapter] per process (a model is hundreds of MB; never two chat models), one embedding
 * service, one Room database. Agent runs and indexing live in [appScope] so they survive screen changes.
 */
class OfflineAgentApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    override fun onCreate() {
        super.onCreate()
        inference = LlamaCppEngine.createAdapter(this)
        embeddings = LlamaCppEngine.createEmbedder(this)
        db = AppDatabase.create(this)
        settings = SettingsRepository(this)
        legacyModels = ModelRepository(this).also { it.cleanupPartials() }
        models = ModelStore(db.misc(), legacyModels)
        engineController = EngineController(inference, models, settings)
        indexer = DocumentIndexer(this, db.documents(), embeddings, models, appScope)
        retriever = Retriever(db.documents(), embeddings, models)
        val toolNames = setOf(
            "search_docs", "read_document", "summarize", "calculator", "get_datetime", "create_note", "memory_read",
            "memory_write", "share_text", "create_calendar_event", "render_ui", "canvas_update",
        )
        skills = SkillRepository(db.skills(), toolNames)
        agents = AgentService(appScope, inference, engineController, db.agent(), db.chats(), db.misc(), retriever, db.documents(), intents, skills, settings)
        appScope.launch {
            runCatching { models.migrateLegacy() }.onFailure { Log.w(TAG, "legacy model migration failed", it) }
            runCatching { skills.seedBuiltins() }.onFailure { Log.w(TAG, "seeding skills failed", it) }
        }
        appScope.launch {
            inference.state.collect { if (it is EngineState.Unloaded || it is EngineState.Failed) engineController.markUnloaded() }
        }
    }

    /**
     * Low memory: free the models rather than get the whole app killed. On Android 14+ only UI_HIDDEN / BACKGROUND /
     * COMPLETE are delivered. UI_HIDDEN alone (user switched apps briefly) does NOT unload.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (critical) unloadForMemory("onTrimMemory($level)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        unloadForMemory("onLowMemory")
    }

    private fun unloadForMemory(why: String) {
        val s = inference.state.value
        // Never pull the model from under a running agent or indexing job; they hold the user's work.
        if (agents.isRunning || indexer.isBusy) return
        if (s is EngineState.Ready) {
            Log.w(TAG, "Unloading model: $why")
            appScope.launch { inference.unload(UnloadReason.LOW_MEMORY); embeddings.unload() }
        }
    }

    companion object { const val TAG = "OfflineAgent" }
}
