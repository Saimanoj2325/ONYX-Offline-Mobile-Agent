package com.offlineagent.app.automation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.offlineagent.core.apps.SettingsPages

/**
 * Phase 6.1: the phone's home screen app(s), found through PackageManager (ACTION_MAIN + CATEGORY_HOME), never by a
 * hard-coded name — OxygenOS uses com.android.launcher, Pixel com.google.android.apps.nexuslauncher, and the user may
 * have installed another launcher. Settings' FallbackHome (priority < 0) and the system resolver are not launchers.
 * Also maps a home-screen icon label to the app behind it, and explains why an app cannot be opened.
 */
class LauncherInfo(private val ctx: Context) {
    @Volatile private var cache: Pair<Long, Set<String>>? = null

    fun homePackages(): Set<String> {
        cache?.let { (at, v) -> if (System.currentTimeMillis() - at < 60_000) return v }
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val out = LinkedHashSet<String>()
        runCatching {
            @Suppress("DEPRECATION")
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.let { out += it }
        }
        runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(home, 0).filter { it.priority >= 0 }.forEach { out += it.activityInfo.packageName }
        }
        out.removeAll(setOf("android", ctx.packageName) + SettingsPages.SETTINGS_APPS)
        cache = System.currentTimeMillis() to out
        return out
    }

    fun isHome(pkg: String): Boolean = pkg.isNotBlank() && pkg in homePackages()

    /** "WhatsApp" (a home-screen icon label) → com.whatsapp; null when no launchable app has that label. */
    fun appForLabel(label: String): String? {
        val l = label.trim()
        if (l.isBlank()) return null
        val pm = ctx.packageManager
        val apps = runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        }.getOrDefault(emptyList())
        return (apps.firstOrNull { it.first.equals(l, true) } ?: apps.firstOrNull { l.length >= 3 && it.first.startsWith(l, true) })?.second
    }

    fun label(pkg: String): String = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /** Why [pkg] cannot be started with a launch intent, or null when it can. */
    fun openProblem(pkg: String, blocked: (String) -> Boolean): String? {
        if (isHome(pkg)) return null // replayed as Home
        if (blocked(pkg)) return "it is a banking / payment app, and automation is blocked there (Settings → Phone actions)"
        val pm = ctx.packageManager
        if (pm.getLaunchIntentForPackage(pkg) != null) return null
        val installed = runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false)
        return if (installed) "it has no launch screen, so it cannot be opened directly"
        else "it is not installed (or Android hides it from ONYX)"
    }
}
