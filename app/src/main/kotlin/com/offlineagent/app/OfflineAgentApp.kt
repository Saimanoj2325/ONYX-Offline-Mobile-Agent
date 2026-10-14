package com.offlineagent.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.util.Log
import com.offlineagent.app.data.ModelRepository
import com.offlineagent.core.inference.EngineState
import com.offlineagent.core.inference.InferenceAdapter
import com.offlineagent.core.inference.UnloadReason
import com.offlineagent.engine.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual DI root. One [InferenceAdapter] per process (a model is hundreds of MB; never two).
 * Later phases add the agent runtime / Room database here.
 */
class OfflineAgentApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var inference: InferenceAdapter
        private set
    lateinit var models: ModelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        inference = LlamaCppEngine.createAdapter(this)
        models = ModelRepository(this).also { it.cleanupPartials() }
    }

    /**
     * Low memory: free the model rather than get the whole app killed. On Android 14+ only
     * UI_HIDDEN / BACKGROUND / COMPLETE are delivered; RUNNING_CRITICAL still arrives on older versions.
     * UI_HIDDEN alone (user switched apps briefly) does NOT unload.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val critical = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        if (critical) unloadForMemory("onTrimMemory($level)")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        unloadForMemory("onLowMemory")
    }

    private fun unloadForMemory(why: String) {
        val s = inference.state.value
        if (s is EngineState.Ready || s is EngineState.Generating) {
            Log.w("OfflineAgent", "Unloading model: $why")
            appScope.launch { inference.unload(UnloadReason.LOW_MEMORY) }
        }
    }
}
