package com.offlineagent.engine.llamacpp

import com.offlineagent.core.inference.ThreadPolicy
import java.io.File

/** Reads CPU topology from sysfs to pick the inference thread count (performance cores only). */
object DeviceInfo {
    /** sysfs/procfs values never change while the app runs: read once (keeps these cheap on any thread). */
    private val freqs: List<Long> by lazy { readCpuMaxFreqsKhz() }
    private val ram: Long by lazy { readTotalRam() }

    fun cpuMaxFreqsKhz(): List<Long> = freqs

    private fun readCpuMaxFreqsKhz(): List<Long> {
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

    /** Performance-core ids for thread pinning (empty if unknown). */
    fun performanceCoreIds(): List<Int> = ThreadPolicy.performanceCoreIds(cpuMaxFreqsKhz())

    /** Total physical RAM in bytes (from /proc/meminfo; ActivityManager gives the same on Android). */
    fun totalRamBytes(): Long = ram

    private fun readTotalRam(): Long = runCatching {
        File("/proc/meminfo").readLines().first { it.startsWith("MemTotal:") }
            .split(Regex("\\s+"))[1].toLong() * 1024
    }.getOrDefault(4L shl 30)

    /** CPU feature flags relevant to llama.cpp kernels (dotprod, i8mm, sve, sme) from /proc/cpuinfo. */
    fun cpuFeatures(): Set<String> = runCatching {
        File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
            ?.substringAfter(':')?.trim()?.split(' ')?.filter { it in setOf("asimddp", "i8mm", "sve", "sve2", "sme", "sme2", "bf16", "fphp") }?.toSet()
    }.getOrNull() ?: emptySet()
}
