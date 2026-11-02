package com.offlineagent.core.agents

import kotlinx.serialization.Serializable

/** Android PowerManager thermal status (THERMAL_STATUS_NONE = 0 … SHUTDOWN = 6). */
@Serializable
enum class Thermal { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN;
    companion object { fun fromAndroid(v: Int) = entries.getOrElse(v) { NONE } }
}

@Serializable
data class DeviceSnapshot(
    val totalRamMb: Long,
    val availRamMb: Long,
    val lowRamDevice: Boolean = false,
    val cpuCores: Int,
    val performanceCores: Int,
    val soc: String = "",
    val sdkInt: Int = 0,
    val thermal: Thermal = Thermal.NONE,
    val powerSave: Boolean = false,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
)

enum class DeviceTier(val label: String) { ENTRY("Entry (≤4 GB)"), MID("Mid (6 GB)"), HIGH("High (8 GB)"), FLAGSHIP("Flagship (12 GB+)") }

data class ModelPick(val catalogId: String, val name: String, val why: String, val primary: Boolean)

data class RuntimePlan(
    val tier: DeviceTier,
    val contextLength: Int,
    val threads: Int,
    val batch: Int,
    val picks: List<ModelPick>,
    val notes: List<String>,
)

/**
 * Picks model / context / threads from RAM, cores and power state. Phones report less RAM than the box says
 * (8 GB → ~7.3 GB), so tiers use generous cut-offs. Numbers match the Phase 3/4 defaults (8K f16 KV on 8 GB).
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

    fun plan(s: DeviceSnapshot): RuntimePlan {
        val tier = tier(s)
        val notes = mutableListOf<String>()
        var ctx = when (tier) { DeviceTier.ENTRY -> 2048; DeviceTier.MID -> 4096; DeviceTier.HIGH -> 8192; DeviceTier.FLAGSHIP -> 16384 }
        if (s.availRamMb in 1 until 2048) { ctx = minOf(ctx, 4096); notes += "Only ${s.availRamMb} MB free right now: context capped at 4K." }
        val baseThreads = s.performanceCores.coerceIn(1, 8)
        val gov = ThermalGovernor.decide(s.thermal, s.powerSave, baseThreads)
        notes += gov.notes
        val picks = when (tier) {
            DeviceTier.ENTRY -> listOf(
                ModelPick("qwen3-0.6b", "Qwen3 0.6B", "fits in 3–4 GB; short answers", true),
                ModelPick("gemma3-1b", "Gemma 3 1B (Q4_K_M)", "a little smarter, still small", false))
            DeviceTier.MID -> listOf(
                ModelPick("qwen3-1.7b", "Qwen3 1.7B (Q4_K_M)", "best balance for 6 GB", true),
                ModelPick("gemma3-1b", "Gemma 3 1B (Q4_K_M)", "faster, weaker at tools", false))
            DeviceTier.HIGH -> listOf(
                ModelPick("qwen3-1.7b", "Qwen3 1.7B (Q4_K_M)", "fast, reliable tool calls and planning", true),
                ModelPick("llama3.2-3b", "Llama 3.2 3B (Q4_K_M)", "better writing; ~2 GB, slower", false),
                ModelPick("qwen3-4b", "Qwen3 4B (Q4_K_M)", "smartest that fits, but slow and warm on 8 GB", false))
            DeviceTier.FLAGSHIP -> listOf(
                ModelPick("qwen3-4b", "Qwen3 4B (Q4_K_M)", "smartest; 12 GB+ has room for it + 16K context", true),
                ModelPick("qwen3-1.7b", "Qwen3 1.7B (Q4_K_M)", "about twice as fast", false),
                ModelPick("llama3.2-3b", "Llama 3.2 3B (Q4_K_M)", "alternative writer", false))
        }
        return RuntimePlan(tier, ctx, gov.threads, if (tier == DeviceTier.ENTRY) 128 else 256, picks, notes)
    }
}

/**
 * Reacts to heat and battery saver: fewer threads when warm, pause new model jobs when SEVERE or worse (the running
 * token stream finishes; queued agent steps wait until the phone cools down).
 */
object ThermalGovernor {
    data class Decision(val threads: Int, val pauseInference: Boolean, val notes: List<String>)

    fun decide(thermal: Thermal, powerSave: Boolean, baseThreads: Int): Decision {
        val notes = mutableListOf<String>()
        var t = baseThreads
        when {
            thermal >= Thermal.SEVERE -> { notes += "Phone is hot (${thermal.name.lowercase()}): new model work is paused until it cools."; t = 1 }
            thermal == Thermal.MODERATE -> { t = maxOf(1, baseThreads / 2); notes += "Phone is warm: using $t threads." }
            thermal == Thermal.LIGHT -> { t = maxOf(1, baseThreads - 1) }
            else -> Unit
        }
        if (powerSave && thermal < Thermal.SEVERE) { t = maxOf(1, minOf(t, 2)); notes += "Battery saver is on: using $t threads." }
        return Decision(t, thermal >= Thermal.SEVERE, notes)
    }
}
