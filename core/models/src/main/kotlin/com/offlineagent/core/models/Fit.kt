package com.offlineagent.core.models

/**
 * Phase 6: "will this model run smoothly on THIS phone right now?" — decided from FREE RAM (ActivityManager.MemoryInfo
 * .availMem), not the marketing RAM size, plus free storage before a download.
 *
 * Resident estimate = weights (mmap, all pages touched over a session) + f16 KV for the context + compute buffers.
 * MoE models are counted at full file size too: their routed experts are spread over the whole file and get paged in
 * over a conversation, so counting only the active part would under-estimate and get the app killed.
 */
object ModelFit {
    const val MB = 1024L * 1024
    /** llama.cpp compute buffers at ubatch 256 for ≤4B models (measured ~220–280 MB on the host harness, rounded up). */
    const val COMPUTE_MB = 300L
    /** RAM the rest of the phone needs to stay smooth (launcher, keyboard, the ONYX UI itself) on top of the model. */
    const val HEADROOM_MB = 700L
    /** The "default model must fit an 8 GB phone with headroom" rule: ≤ ~2.5 GB resident. */
    const val DEFAULT_BUDGET_MB = 2560L

    enum class Verdict { OK, TIGHT, TOO_BIG }

    data class Fit(val verdict: Verdict, val residentMb: Long, val availMb: Long, val message: String) {
        val ok get() = verdict != Verdict.TOO_BIG
    }

    fun kvMb(model: CatalogModel, contextTokens: Int, kvFactor: Double = 1.0): Long =
        ((model.kvBytesPerToken * contextTokens * kvFactor) / MB).toLong()

    fun residentMb(model: CatalogModel, contextTokens: Int, kvFactor: Double = 1.0): Long =
        model.main.approxBytes / MB + kvMb(model, contextTokens, kvFactor) + COMPUTE_MB

    /**
     * OK: fits free RAM with [HEADROOM_MB] to spare. TIGHT: fits free RAM but eats into the headroom (other apps get
     * closed, model may be evicted when switching apps) → warn before loading. TOO_BIG: more than free RAM, or more than
     * 60 % of total RAM → refuse by default (page-fault storm / low-memory kill).
     */
    fun check(model: CatalogModel, contextTokens: Int, totalRamMb: Long, availRamMb: Long, kvFactor: Double = 1.0): Fit {
        val need = residentMb(model, contextTokens, kvFactor)
        val gb = { mb: Long -> "%.1f GB".format(mb / 1024.0) }
        return when {
            need > availRamMb || need > totalRamMb * 6 / 10 -> Fit(Verdict.TOO_BIG, need, availRamMb,
                "${model.name} needs about ${gb(need)} but only ${gb(availRamMb)} is free. Close other apps or pick a smaller model.")
            need + HEADROOM_MB > availRamMb -> Fit(Verdict.TIGHT, need, availRamMb,
                "${model.name} needs about ${gb(need)} of the ${gb(availRamMb)} free: it will run, but other apps may close and it can slow down.")
            else -> Fit(Verdict.OK, need, availRamMb, "${model.name}: about ${gb(need)} in use, ${gb(availRamMb - need)} left free.")
        }
    }
}

/** Phase 6: free-space check before a model download (never fill the phone: keep a reserve for the OS and apps). */
object StorageCheck {
    const val RESERVE_BYTES = 1536L * 1024 * 1024 // 1.5 GB: Android starts failing installs/updates/camera below ~1 GB

    data class Result(val ok: Boolean, val needBytes: Long, val freeBytes: Long, val message: String)

    /** [alreadyDownloaded] = bytes of a partial file that will be resumed (does not need space again). */
    fun canDownload(model: CatalogModel, freeBytes: Long, alreadyDownloaded: Long = 0): Result =
        canDownload(model.totalBytes, freeBytes, alreadyDownloaded, model.name)

    fun canDownload(totalBytes: Long, freeBytes: Long, alreadyDownloaded: Long = 0, name: String = "This model"): Result {
        val need = (totalBytes - alreadyDownloaded).coerceAtLeast(0)
        val gb = { b: Long -> "%.1f GB".format(b / (1024.0 * 1024 * 1024)) }
        return if (freeBytes - need >= RESERVE_BYTES) Result(true, need, freeBytes, "$name: ${gb(need)} to download, ${gb(freeBytes - need)} will be left.")
        else Result(false, need, freeBytes, "Not enough storage: $name needs ${gb(need)} plus ${gb(RESERVE_BYTES)} kept free, but only ${gb(freeBytes)} is free. Delete a model or free some space.")
    }
}
