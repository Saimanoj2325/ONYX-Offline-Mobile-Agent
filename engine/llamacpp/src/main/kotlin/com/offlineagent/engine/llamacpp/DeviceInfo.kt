package com.offlineagent.engine.llamacpp

import com.offlineagent.core.inference.ThreadPolicy
import java.io.File

/** Reads CPU topology from sysfs to pick the inference thread count (performance cores only). */
object DeviceInfo {
    fun cpuMaxFreqsKhz(): List<Long> {
        val n = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return (0 until maxOf(n, possibleCpuCount())).map { i ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrDefault(0L)
        }
    }

    private fun possibleCpuCount(): Int = runCatching {
        // e.g. "0-7"
        val s = File("/sys/devices/system/cpu/possible").readText().trim()
        s.substringAfterLast('-').toInt() + 1
    }.getOrDefault(0)

    fun recommendedThreads(): Int = ThreadPolicy.performanceCores(cpuMaxFreqsKhz())
}
