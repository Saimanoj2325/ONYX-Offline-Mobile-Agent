package com.offlineagent.core.agents

import com.offlineagent.core.models.CatalogModel
import com.offlineagent.core.models.ModelCatalog
import com.offlineagent.core.models.ModelFit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/** Android PowerManager thermal status (THERMAL_STATUS_NONE = 0 … SHUTDOWN = 6). */
@Serializable
enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;
    companion object { fun fromAndroid(v: Int) = entries.getOrElse(v) { NONE } }
}

@Serializable
data class DeviceSnapshot(
    val totalRamMb: Long,
    /** FREE RAM right now (ActivityManager.MemoryInfo.availMem). Phase 6: tiers and model picks use this. */
    val availRamMb: Long,
    val lowRamDevice: Boolean = false,
    val cpuCores: Int,
    /** Big (performance) cores = inference threads (A78 ×4 on a Snapdragon 782G). */
    val performanceCores: Int,
    val soc: String = "",
    val sdkInt: Int = 0,
    val thermal: Thermal = Thermal.NONE,
    val powerSave: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    // ---- Phase 6
    /** Free bytes in app-internal storage (models live there); -1 = unknown. */
    val freeStorageMb: Long = -1,
    /** /proc/cpuinfo "Features": asimddp (dotprod), i8mm, sve… */
    val cpuFeatures: Set<String> = emptySet(),
) {
    val dotprod get() = "asimddp" in cpuFeatures || "dotprod" in cpuFeatures
    val i8mm get() = "i8mm" in cpuFeatures
}

enum class DeviceTier(val label: String) { ENTRY("Entry (≤4 GB)"), MID("Mid (6 GB)"), HIGH("High (8 GB)"), FLAGSHIP("Flagship (12 GB+)") }

data class ModelPick(
    val catalogId: String, val name: String, val why: String, val primary: Boolean,
    /** Phase 6: estimated RAM in use at the plan's context, and whether it fits the FREE RAM right now. */
    val residentMb: Long = 0, val fit: ModelFit.Verdict = ModelFit.Verdict.OK, val warning: String? = null,
)

data class RuntimePlan(
    val tier: DeviceTier,
    val contextLength: Int,
    val threads: Int,
    val batch: Int,
    val picks: List<ModelPick>,
    val notes: List<String>,
    /** Phase 6 */
    val ubatch: Int = batch,
    val idleUnloadMs: Long = PowerGovernor.DEFAULT_IDLE_UNLOAD_MS,
    val speculation: Boolean = true,
)

/**
 * Phase 6 device-adaptive runtime selection. Tier comes from TOTAL RAM (what class of phone this is); the model picks
 * are then filtered by FREE RAM right now ([ModelFit]), so a 12 GB phone with 3 GB free gets the same safe default as an
 * 8 GB phone, and a big model is only offered when the memory is really there. Threads = big cores, scaled down by heat,
 * battery saver and low battery ([PowerGovernor]).
 *
 * Default model on every tier ≥ 6 GB = Qwen3 1.7B Q4_K_M (~2.2 GB resident at 8K, within the 2.5 GB rule for 8 GB phones).
 */
object RuntimeSelector {
    fun tier(s: DeviceSnapshot): DeviceTier {
        val gb = s.totalRamMb / 1024.0
        return when {
            s.lowRamDevice || gb < 4.8 -> DeviceTier.ENTRY
            gb < 6.8 -> DeviceTier.MID
            gb < 10.5 -> DeviceTier.HIGH
            else -> DeviceTier.FLAGSHIP
        }
    }

    private data class Cand(val model: CatalogModel, val why: String)

    private fun candidates(tier: DeviceTier): List<Cand> = with(ModelCatalog) {
        when (tier) {
            DeviceTier.ENTRY -> listOf(
                Cand(QWEN3_06B, "fits in 3–4 GB phones; short answers"),
                Cand(QWEN35_08B, "tiny KV cache: longer chats on low RAM"),
                Cand(LFM25_12B, "a little smarter, still under 1 GB"),
                Cand(GEMMA3_1B, "small and quick"))
            DeviceTier.MID -> listOf(
                Cand(QWEN3_17B, "best balance for 6 GB phones"),
                Cand(LFM25_12B, "faster and lighter, decent tool calls"),
                Cand(QWEN35_2B, "small KV cache for long chats"))
            DeviceTier.HIGH -> listOf(
                Cand(QWEN3_17B, "fast, reliable tool calls and planning; ~2.2 GB at 8K"),
                Cand(QWEN35_2B, "long chats: KV cache 9× smaller"),
                Cand(QWEN3_4B_2507, "quality mode for writing, slower; only with ~4 GB free"),
                Cand(GEMMA4_E2B, "Google's on-device model; only with ~4 GB free"))
            DeviceTier.FLAGSHIP -> listOf(
                Cand(QWEN3_17B, "fastest reliable agent model (battery-friendly)"),
                Cand(QWEN3_4B_2507, "quality mode: better writing and tools, ~2× slower"),
                Cand(LFM25_8B_A1B, "biggest that runs well here: 8B MoE, ~1.5B active; needs ~6 GB free"),
                Cand(QWEN35_4B, "quality + long context (small KV)"),
                Cand(GEMMA4_E2B, "alternative on-device model"))
        }
    }

    fun plan(s: DeviceSnapshot): RuntimePlan {
        val tier = tier(s)
        val notes = mutableListOf<String>()
        var ctx = when (tier) { DeviceTier.ENTRY -> 2048; DeviceTier.MID -> 4096; DeviceTier.HIGH, DeviceTier.FLAGSHIP -> 8192 }
        if (s.availRamMb in 1 until 2048) { ctx = minOf(ctx, 4096); notes += "Only ${s.availRamMb} MB free right now: context capped at 4K." }
        if (s.availRamMb in 1 until 1200) { ctx = 2048; notes += "Very little free RAM: 2K context and the smallest model." }
        val baseThreads = s.performanceCores.coerceIn(1, 8)
        val baseBatch = if (tier == DeviceTier.ENTRY) 128 else 256
        val gov = PowerGovernor.decide(s.thermal, s.powerSave, baseThreads, s.batteryPercent, s.charging, baseBatch)
        notes += gov.notes
        if (s.cpuFeatures.isNotEmpty() && !s.dotprod) notes += "This CPU has no dot-product instructions: small models only, expect slower replies."
        if (s.freeStorageMb in 0 until 3072) notes += "Low storage (${s.freeStorageMb} MB free): downloads that would leave < 1.5 GB are blocked."

        val avail = if (s.availRamMb > 0) s.availRamMb else s.totalRamMb / 2
        var cands = candidates(tier)
        if (s.cpuFeatures.isNotEmpty() && !s.dotprod) cands = cands.filter { it.model.sizeClass <= CatalogModel.SizeClass.SMALL }
        val fits = cands.map { c -> c to ModelFit.check(c.model, ctx, s.totalRamMb, avail) }
        // Primary = first candidate that fits comfortably (OK); else first that fits at all; else the smallest model of the
        // tier (or the entry default) with a warning.
        val primary = fits.firstOrNull { it.second.verdict == ModelFit.Verdict.OK && it.first.model.sizeClass <= CatalogModel.SizeClass.SMALL }
            ?: fits.firstOrNull { it.second.ok && it.first.model.sizeClass <= CatalogModel.SizeClass.SMALL }
            ?: fits.minByOrNull { it.second.residentMb }!!
        if (!primary.second.ok) notes += primary.second.message
        val picks = buildList {
            add(pick(primary.first, primary.second, true))
            fits.filter { it !== primary && it.second.ok }.forEach { add(pick(it.first, it.second, false)) }
        }
        val hidden = fits.filter { !it.second.ok && it !== primary }
        if (hidden.isNotEmpty()) notes += "Hidden until more RAM is free: " + hidden.joinToString { it.first.model.name }
        return RuntimePlan(tier, ctx, gov.threads, gov.batch, picks, notes, ubatch = gov.batch, idleUnloadMs = gov.idleUnloadMs, speculation = gov.speculation)
    }

    private fun pick(c: Cand, f: ModelFit.Fit, primary: Boolean) =
        ModelPick(c.model.id, c.model.name, c.why, primary, f.residentMb, f.verdict, f.message.takeIf { f.verdict != ModelFit.Verdict.OK })

    /** Warn-before-load check for a model the user picked by hand (null = fine). */
    fun loadWarning(model: CatalogModel, s: DeviceSnapshot, ctx: Int): String? {
        val f = ModelFit.check(model, ctx, s.totalRamMb, if (s.availRamMb > 0) s.availRamMb else s.totalRamMb / 2)
        return f.message.takeIf { f.verdict != ModelFit.Verdict.OK }
    }
}

/**
 * Phase 6 battery/thermal governor: fewer threads and a smaller batch when warm, on battery saver or on low battery;
 * pause new model work at SEVERE+; shorter idle-unload timeout when the battery is low (the model's pages and the
 * thread pool's busy-polling cost power even when idle).
 */
object PowerGovernor {
    const val DEFAULT_IDLE_UNLOAD_MS = 10 * 60_000L
    const val LOW_BATTERY_IDLE_UNLOAD_MS = 2 * 60_000L
    const val LOW_BATTERY = 15

    data class Decision(val threads: Int, val batch: Int, val pauseInference: Boolean, val notes: List<String>,
                        val idleUnloadMs: Long = DEFAULT_IDLE_UNLOAD_MS, val speculation: Boolean = true, val pollLevel: Int = 50)

    fun decide(thermal: Thermal, powerSave: Boolean, baseThreads: Int, batteryPercent: Int? = null, charging: Boolean = false, baseBatch: Int = 256): Decision {
        val notes = mutableListOf<String>()
        var t = baseThreads
        var b = baseBatch
        var poll = 50
        when {
            thermal >= Thermal.SEVERE -> { notes += "Phone is hot (${thermal.name.lowercase()}): new model work is paused until it cools."; t = 1; b = minOf(b, 64) }
            thermal == Thermal.MODERATE -> { t = maxOf(1, baseThreads / 2); b = maxOf(64, baseBatch / 2); poll = 0; notes += "Phone is warm: using $t threads." }
            thermal == Thermal.LIGHT -> { t = maxOf(1, baseThreads - 1) }
            else -> Unit
        }
        if (powerSave && thermal < Thermal.SEVERE) { t = maxOf(1, minOf(t, 2)); b = minOf(b, 128); poll = 0; notes += "Battery saver is on: using $t threads." }
        val low = batteryPercent != null && batteryPercent <= LOW_BATTERY && !charging
        if (low && thermal < Thermal.SEVERE) { t = maxOf(1, minOf(t, 2)); b = minOf(b, 128); poll = 0; notes += "Battery is low ($batteryPercent%): using $t threads; the model unloads after 2 idle minutes." }
        return Decision(t, b, thermal >= Thermal.SEVERE, notes, if (low || powerSave) LOW_BATTERY_IDLE_UNLOAD_MS else DEFAULT_IDLE_UNLOAD_MS,
            // speculation verifies extra tokens per step: worth it when cool, wasteful work when throttled
            speculation = thermal < Thermal.MODERATE, pollLevel = poll)
    }
}

/**
 * 0.6.2.3: when typing-time read-ahead (prefill / reasoning ahead) may run. It is speculative work, so it is the first
 * thing to go: never on battery saver, on a low battery (not charging), when the phone is warm or hotter, with the
 * screen off or the app in the background. Returns the reason to skip, or null = allowed.
 */
object ReadAheadGate {
    const val LOW_BATTERY = 20

    fun check(thermal: Thermal, powerSave: Boolean, batteryPercent: Int?, charging: Boolean,
              screenOn: Boolean = true, foreground: Boolean = true, safeMode: Boolean = false): String? = when {
        safeMode -> "safe mode"
        !screenOn -> "screen off"
        !foreground -> "app in background"
        powerSave -> "battery saver"
        batteryPercent != null && batteryPercent <= LOW_BATTERY && !charging -> "battery low ($batteryPercent%)"
        thermal >= Thermal.MODERATE -> "phone is ${thermal.name.lowercase()}"
        else -> null
    }
}

/** Phase 5 API kept for callers: same rules as [PowerGovernor] without the battery level. */
object ThermalGovernor {
    data class Decision(val threads: Int, val pauseInference: Boolean, val notes: List<String>)
    fun decide(thermal: Thermal, powerSave: Boolean, baseThreads: Int): Decision =
        PowerGovernor.decide(thermal, powerSave, baseThreads).let { Decision(it.threads, it.pauseInference, it.notes) }
}

/**
 * Phase 6: unloads the model after [timeoutMs] without inference (0 = never). [touch] on every model use; [busy] (agent
 * run, indexing, automation) blocks unloading. Pure coroutine logic, testable with virtual time.
 */
class IdleUnloader(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val timeoutMs: () -> Long,
    private val busy: () -> Boolean,
    private val loaded: () -> Boolean,
    private val unload: suspend (String) -> Unit,
) {
    @Volatile var lastUse: Long = clock(); private set
    private var job: Job? = null
    var unloads = 0; private set

    fun touch() { lastUse = clock(); schedule() }

    @Synchronized fun schedule() {
        job?.cancel()
        val t = timeoutMs()
        if (t <= 0) return
        job = scope.launch {
            while (true) {
                val wait = lastUse + timeoutMs() - clock()
                if (wait > 0) { delay(wait); continue }
                if (busy()) { delay(30_000); continue }
                if (loaded()) { unloads++; unload("idle for ${timeoutMs() / 60_000} min") }
                break
            }
        }
    }

    fun cancel() { job?.cancel() }
}
