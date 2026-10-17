package com.offlineagent.app.diag

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash safety (Phase 4, P0):
 *  - an uncaught-exception handler writes a crash report before the process dies;
 *  - a *native marker* file is written around risky native work (model load, self-test) and removed afterwards, so a
 *    native crash (SIGSEGV/abort) or an out-of-memory kill during that work is detected on the next launch;
 *  - on Android 11+ the system's last exit reason (crash, native crash, ANR, low memory) is also read;
 *  - [startupCheck] turns any of these into "start in safe mode" + a banner;
 *  - everything stays on the phone: [exportBundle] writes one text file the user can share if they choose.
 */
object Diagnostics {
    private const val TAG = "OfflineAgent"
    private lateinit var dir: File
    private lateinit var appCtx: Context
    private const val MAX_LOG = 256 * 1024
    private val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Extra context added to crash reports (system info, current load config…). */
    @Volatile var context: () -> String = { "" }

    data class Startup(val crashed: Boolean, val reason: String)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        dir = File(ctx.filesDir, "diagnostics").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { writeCrash("Uncaught exception in thread ${t.name}", stack(e)) }
            previous?.uncaughtException(t, e)
        }
    }

    private val logFile get() = File(dir, "diag.log")
    private val markerFile get() = File(dir, "native-marker")
    private val compatFile get() = File(dir, "cpu-compat")

    /** Phase 4b: log lines are appended on a background thread (callers may be on the main thread). */
    private val logWriter = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "diag-log").apply { isDaemon = true } }

    fun log(msg: String) {
        Log.i(TAG, msg)
        if (!::dir.isInitialized) return
        val line = "${synchronized(ts) { ts.format(Date()) }}  $msg\n"
        runCatching { logWriter.execute { appendLine(line) } }
    }

    @Synchronized private fun appendLine(line: String) {
        runCatching {
            val f = logFile
            if (f.length() > MAX_LOG) { f.copyTo(File(dir, "diag.old.log"), overwrite = true); f.writeText("") }
            f.appendText(line)
        }
    }

    // ------------------------------------------------------------------ native marker

    /** Call before native work that could crash the process; [what] is shown after a crash. */
    fun enterNative(what: String) { runCatching { markerFile.writeText("${ts.format(Date())}  $what\n${context()}") }; log("native: $what") }
    fun exitNative() { runCatching { markerFile.delete() } }

    inline fun <T> native(what: String, block: () -> T): T {
        enterNative(what)
        try { return block() } finally { exitNative() }
    }

    // ------------------------------------------------------------------ compatibility CPU kernels

    /** Conservative ggml CPU variant (NEON + dotprod; no SVE/SME/i8mm kernels). */
    const val COMPAT_VARIANT = "android_armv8.2_1"

    fun compatVariant(): String = runCatching { compatFile.takeIf { it.isFile }?.readText()?.trim() }.getOrNull().orEmpty()
    fun setCompatVariant(v: String?) { runCatching { if (v.isNullOrBlank()) compatFile.delete() else compatFile.writeText(v) } }

    // ------------------------------------------------------------------ startup

    fun startupCheck(): Startup {
        val reasons = mutableListOf<String>()
        if (markerFile.isFile) {
            val what = runCatching { markerFile.readText().lineSequence().firstOrNull() }.getOrNull() ?: "native work"
            reasons += "the app stopped during: $what"
            writeCrash("Process died during native work", runCatching { markerFile.readText() }.getOrDefault(""))
            markerFile.delete()
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching { lastExit()?.let { reasons += it } }
        val pending = File(dir, "crash-pending")
        if (pending.isFile) { reasons += runCatching { pending.readText() }.getOrDefault("crash"); pending.delete() }
        val crashed = reasons.isNotEmpty()
        if (crashed) log("startup: last run crashed — ${reasons.joinToString("; ")}")
        return Startup(crashed, reasons.distinct().joinToString("; "))
    }

    private fun lastExit(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = appCtx.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(appCtx.packageName, 0, 1).firstOrNull() ?: return null
        val seenFile = File(dir, "exit-seen")
        val key = "${info.timestamp}"
        if (seenFile.isFile && seenFile.readText() == key) return null
        seenFile.writeText(key)
        val why = when (info.reason) {
            ApplicationExitInfo.REASON_CRASH -> "Java crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "app not responding (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "killed: out of memory"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
            else -> return null // normal exits, user swipe, updates…
        }
        val trace = if (info.reason == ApplicationExitInfo.REASON_ANR) runCatching { info.traceInputStream?.bufferedReader()?.readText()?.take(20000) }.getOrNull() else null
        writeCrash("Previous process exit: $why (${info.description ?: ""}) at ${ts.format(Date(info.timestamp))}", trace ?: "")
        return why
    }

    // ------------------------------------------------------------------ crash files

    private fun stack(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    @Synchronized private fun writeCrash(title: String, body: String) {
        if (!::dir.isInitialized) return
        val f = File(dir, "crash-${System.currentTimeMillis()}.txt")
        f.writeText(buildString {
            append(title).append('\n').append(ts.format(Date())).append('\n')
            append("App ").append(runCatching { appCtx.packageManager.getPackageInfo(appCtx.packageName, 0).versionName }.getOrNull()).append(" · ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
            append("SoC: ").append(if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE).append('\n')
            runCatching { context() }.getOrNull()?.let { append(it).append('\n') }
            append('\n').append(body)
        })
        // Keep the newest 10 reports.
        dir.listFiles { x -> x.name.startsWith("crash-") && x.name.endsWith(".txt") }?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        // Marks the crash for the next launch even when only the uncaught handler ran.
        runCatching { File(dir, "crash-pending").writeText(title) }
    }

    fun crashReports(): List<File> = dir.listFiles { x -> x.name.startsWith("crash-") && x.name.endsWith(".txt") }?.sortedByDescending { it.name } ?: emptyList()

    fun readLog(): String = runCatching { logFile.readText() }.getOrDefault("")

    fun clear() { dir.listFiles()?.filter { it.name != "cpu-compat" && it.name != "exit-seen" }?.forEach { it.delete() } }

    /** One shareable text file: device + system info, settings summary, diagnostics log, crash reports, own logcat. */
    fun exportBundle(extra: String): File {
        val out = File(appCtx.cacheDir, "exports").apply { mkdirs() }.let { File(it, "offline-agent-diagnostics.txt") }
        val logcat = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-t", "3000", "--pid=${android.os.Process.myPid()}").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().takeLast(400_000)
        }.getOrDefault("(logcat unavailable)")
        out.writeText(buildString {
            append("Offline Agent diagnostics — ").append(ts.format(Date())).append('\n')
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append(" · ABIs ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
            append("CPU compatibility mode: ").append(compatVariant().ifBlank { "off" }).append("\n\n")
            append(extra).append("\n\n=== diagnostics log ===\n").append(readLog())
            crashReports().forEach { append("\n\n=== ").append(it.name).append(" ===\n").append(runCatching { it.readText() }.getOrDefault("")) }
            append("\n\n=== logcat (this app only) ===\n").append(logcat)
        })
        return out
    }
}
