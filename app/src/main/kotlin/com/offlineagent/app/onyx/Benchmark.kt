package com.offlineagent.app.onyx

import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.data.ModelRole
import com.offlineagent.core.data.BenchResultEntity
import com.offlineagent.core.inference.ChatMessage
import com.offlineagent.core.inference.GenerationEvent
import com.offlineagent.core.inference.GenerationRequest
import com.offlineagent.core.inference.Role
import com.offlineagent.core.inference.SamplingParams
import com.offlineagent.core.inference.UnloadReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 6 on-phone benchmark (Agents → Models → Benchmark): reloads the active chat model (load time), then runs the
 * same protocol as tools/host-harness Phase6Harness: a ~256-token prompt and 64 generated tokens, greedy, once with
 * n-gram speculation off and once on. Reports prompt tok/s, generation tok/s, peak RSS (VmHWM), thermal and battery,
 * and stores every result in onyx-agents.db (bench_results). Nothing leaves the phone.
 */
class Benchmark(private val app: OfflineAgentApp) {
    sealed interface State {
        data object Idle : State
        data class Running(val step: String) : State
        data class Done(val result: BenchResultEntity) : State
        data class Failed(val message: String) : State
    }
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val prompt = (1..40).joinToString(" ") { "item $it is a small offline phone assistant test sentence." } +
        "\nGive 8 short tips for saving phone battery, one per line."

    suspend fun run(): BenchResultEntity? = withContext(Dispatchers.IO) {
        if (_state.value is State.Running) return@withContext null
        if (app.onyx.isRunning || app.agents.isRunning) { _state.value = State.Failed("Wait until the current task finishes."); return@withContext null }
        try {
            val model = app.models.active(ModelRole.CHAT) ?: run { _state.value = State.Failed("No chat model: download one first."); return@withContext null }
            _state.value = State.Running("Loading ${model.name}…")
            app.inference.unload(UnloadReason.USER)
            val t0 = System.nanoTime()
            val info = app.engineController.ensure(ModelRole.CHAT)
            val loadMs = (System.nanoTime() - t0) / 1_000_000
            val greedy = SamplingParams(temperature = 0f, topK = 1, topP = 1f, repeatPenalty = 1f, seed = 1)
            suspend fun once(spec: Int): com.offlineagent.core.inference.GenerationStats {
                app.inference.setSpeculation(spec)
                val ev = app.inference.generate(GenerationRequest(listOf(ChatMessage(Role.SYSTEM, "You are a benchmark."), ChatMessage(Role.USER, prompt)),
                    thinking = false, maxNewTokens = 64, sampling = greedy, cachePrefix = false)).toList()
                return ev.filterIsInstance<GenerationEvent.Completed>().single().stats
            }
            _state.value = State.Running("Prompt + generation (speculation off)…")
            val off = once(0)
            _state.value = State.Running("Generation with n-gram speculation…")
            // a different first token would make the prompt fully cached: force a fresh prompt pass is not needed for gen speed
            val on = once(app.cachedSettings.ngramDraft.coerceIn(1, 16))
            // restore the user's setting
            app.inference.setSpeculation(if (app.cachedSettings.ngramSpec) app.cachedSettings.ngramDraft else 0)
            val (pct, _) = app.onyx.monitor.battery()
            val r = BenchResultEntity(
                at = System.currentTimeMillis(), model = model.name, threads = info.threads, contextLength = info.contextLength, loadMs = loadMs,
                promptTokens = off.promptTokens, promptTps = off.promptTokensPerSecond, genTokens = off.generatedTokens, genTps = off.tokensPerSecond,
                specGenTps = on.tokensPerSecond, specAccepted = on.acceptedTokens, specDrafted = on.draftedTokens,
                peakRssMb = rss("VmHWM"), thermal = app.onyx.monitor.thermal.value.name, battery = pct ?: -1,
                note = "kv=${info.kvCacheType} ${com.offlineagent.engine.llamacpp.DeviceInfo.cpuFeatures().joinToString(",")}",
            )
            app.onyx.db.phase6().insertBench(r)
            _state.value = State.Done(r)
            r
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: e.toString()); null
        }
    }

    private fun rss(key: String): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("$key:") }.split(Regex("\\s+"))[1].toLong() / 1024
    }.getOrDefault(-1)
}
