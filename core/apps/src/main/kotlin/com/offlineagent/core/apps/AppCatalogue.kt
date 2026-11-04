package com.offlineagent.core.apps

import java.text.Normalizer

/** What an installed app can do for ONYX, derived from the intent filters it declares (no screen reading). */
enum class Capability(val id: String, val title: String) {
    LAUNCH("open", "open"),
    SEND_TEXT("share_text", "receive shared text"),
    SEND_IMAGE("share_image", "receive shared images"),
    SEND_FILE("share_file", "receive shared files"),
    SEND_MULTIPLE("share_many", "receive several files"),
    VIEW_WEB("view_web", "open web links"),
    VIEW_GEO("view_geo", "open map locations"),
    VIEW_MAILTO("mailto", "compose email"),
    VIEW_SMS("sms", "compose SMS"),
    VIEW_TEL("tel", "dial numbers"),
    WEB_SEARCH("web_search", "web search"),
    SEARCH("search", "in-app search"),
    MEDIA_SEARCH("media_search", "play media by search"),
    PROCESS_TEXT("process_text", "act on selected text"),
}

/**
 * One intent query run against PackageManager. Every probe needs a matching `<queries>` entry in the app manifest
 * (Android 11+ package visibility) — see docs/PHASE6-APPS.md.
 */
data class IntentProbe(val capability: Capability, val action: String, val scheme: String? = null, val mimeType: String? = null, val category: String? = null)

object Probes {
    val ALL = listOf(
        IntentProbe(Capability.SEND_TEXT, AppIntent.ACTION_SEND, mimeType = "text/plain"),
        IntentProbe(Capability.SEND_IMAGE, AppIntent.ACTION_SEND, mimeType = "image/*"),
        IntentProbe(Capability.SEND_FILE, AppIntent.ACTION_SEND, mimeType = "*/*"),
        IntentProbe(Capability.SEND_MULTIPLE, AppIntent.ACTION_SEND_MULTIPLE, mimeType = "*/*"),
        IntentProbe(Capability.VIEW_WEB, AppIntent.ACTION_VIEW, scheme = "https", category = AppIntent.CATEGORY_BROWSABLE),
        IntentProbe(Capability.VIEW_GEO, AppIntent.ACTION_VIEW, scheme = "geo"),
        IntentProbe(Capability.VIEW_MAILTO, AppIntent.ACTION_SENDTO, scheme = "mailto"),
        IntentProbe(Capability.VIEW_SMS, AppIntent.ACTION_SENDTO, scheme = "smsto"),
        IntentProbe(Capability.VIEW_TEL, AppIntent.ACTION_DIAL, scheme = "tel"),
        IntentProbe(Capability.WEB_SEARCH, AppIntent.ACTION_WEB_SEARCH),
        IntentProbe(Capability.SEARCH, AppIntent.ACTION_SEARCH),
        IntentProbe(Capability.MEDIA_SEARCH, AppIntent.ACTION_MEDIA_PLAY_FROM_SEARCH),
        IntentProbe(Capability.PROCESS_TEXT, AppIntent.ACTION_PROCESS_TEXT, mimeType = "text/plain"),
    )
}

/** Raw data the Android side reads from PackageManager. */
data class RawApp(val pkg: String, val label: String, val versionCode: Long = 0, val system: Boolean = false)

/** Android port: PackageManager queries (implemented in :app with queryIntentActivities). */
interface PackageSource {
    /** MAIN/LAUNCHER activities (the visible app list). */
    fun launchableApps(): List<RawApp>
    /** Packages with an activity matching [probe]. */
    fun handlers(probe: IntentProbe): Set<String>
    /** True when [intent] resolves to at least one activity (restricted to [AppIntent.pkg] when set). */
    fun resolves(intent: AppIntent): Boolean
}

/** Rough kind of app; [PEOPLE] apps (messaging, email, social) get stricter approval for anything shared into them. */
enum class AppKind { PEOPLE, BROWSER, MAPS, MEDIA, STORE, OTHER }

data class AppEntry(
    val pkg: String,
    val label: String,
    val capabilities: Set<Capability>,
    val kind: AppKind,
    /** Non-null when [AppSafetyPolicy] blocks the app: ONYX never opens, shares to, or acts on it. */
    val blocked: AppSafetyPolicy.SafetyVerdict.Blocked? = null,
    val versionCode: Long = 0,
) {
    fun can(c: Capability) = c in capabilities
}

/**
 * App catalogue built from PackageManager: launchable apps + capabilities from a fixed list of intent probes. Rebuilt
 * on PACKAGE_ADDED/REMOVED/REPLACED (the app calls [refresh]). In memory only — it is cheap to rebuild (~15 queries).
 */
class AppCatalogue(private val source: PackageSource, private val policy: AppSafetyPolicy) {
    @Volatile private var snapshot: List<AppEntry> = emptyList()
    @Volatile var version: Int = 0; private set
    @Volatile private var built = false

    val entries: List<AppEntry> get() { ensure(); return snapshot }

    fun ensure() { if (!built) refresh() }

    @Synchronized
    fun refresh(): List<AppEntry> {
        val apps = source.launchableApps().distinctBy { it.pkg }
        val caps = HashMap<String, MutableSet<Capability>>()
        for (p in Probes.ALL) {
            val pkgs = runCatching { source.handlers(p) }.getOrDefault(emptySet())
            for (pkg in pkgs) caps.getOrPut(pkg) { mutableSetOf() } += p.capability
        }
        snapshot = apps.map { a ->
            val c = (caps[a.pkg] ?: mutableSetOf()).also { it += Capability.LAUNCH }
            val v = policy.checkPackage(a.pkg, a.label) as? AppSafetyPolicy.SafetyVerdict.Blocked
            AppEntry(a.pkg, a.label, if (v != null) emptySet() else c.toSet(), kindOf(a.pkg, c), v, a.versionCode)
        }.sortedBy { it.label.lowercase() }
        built = true
        version++
        return snapshot
    }

    fun entry(pkg: String): AppEntry? = entries.firstOrNull { it.pkg == pkg }

    data class Match(val entry: AppEntry, val score: Double)

    /** Fuzzy search on label + package + aliases. Best first; blocked apps are kept (marked) so ONYX can explain. */
    fun find(query: String, limit: Int = 5): List<Match> {
        val q = norm(query)
        if (q.isEmpty()) return entries.filter { it.blocked == null }.take(limit).map { Match(it, 0.0) }
        val aliasPkgs = ALIASES[q].orEmpty()
        return entries.map { e -> Match(e, score(q, e) + if (e.pkg in aliasPkgs) 1.0 else 0.0) }
            .filter { it.score >= 0.45 }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.entry.label.length })
            .take(limit)
    }

    sealed interface Resolution {
        data class Found(val entry: AppEntry) : Resolution
        data class Ambiguous(val candidates: List<AppEntry>) : Resolution
        data class NotFound(val query: String) : Resolution
    }

    /** Resolves "WhatsApp" / "com.whatsapp" / "yt" to exactly one app, or says why not. */
    fun resolve(nameOrPackage: String): Resolution {
        val q = nameOrPackage.trim()
        entries.firstOrNull { it.pkg.equals(q, ignoreCase = true) }?.let { return Resolution.Found(it) }
        val m = find(q, 4)
        if (m.isEmpty()) return Resolution.NotFound(q)
        val best = m.first()
        val second = m.getOrNull(1)
        val exactLabel = m.filter { norm(it.entry.label) == norm(q) }
        if (exactLabel.size == 1) return Resolution.Found(exactLabel.first().entry)
        return if (second == null || best.score - second.score >= 0.15 || best.score >= 1.5) Resolution.Found(best.entry)
        else Resolution.Ambiguous(m.map { it.entry })
    }

    companion object {
        fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .lowercase().replace(Regex("[^\\p{L}\\p{N} ]+"), " ").replace(Regex("\\s+"), " ").trim()

        /** Score 0..~1.6: exact label 1.2, prefix 0.95, word-prefix 0.85, contains 0.7, package segment 0.75, subsequence/edit distance lower. */
        fun score(q: String, e: AppEntry): Double {
            val label = norm(e.label)
            val compactQ = q.replace(" ", "")
            val compactL = label.replace(" ", "")
            val pkgSegs = e.pkg.lowercase().split('.', '_')
            return maxOf(
                when {
                    label == q || compactL == compactQ -> 1.2
                    label.startsWith(q) || compactL.startsWith(compactQ) -> 0.95
                    label.split(' ').any { it.startsWith(q) } -> 0.85
                    compactQ.length >= 3 && compactL.contains(compactQ) -> 0.7
                    else -> 0.0
                },
                if (compactQ.length >= 3 && pkgSegs.any { it == compactQ }) 0.75 else 0.0,
                if (compactQ.length >= 3 && isSubsequence(compactQ, compactL)) 0.5 * compactQ.length / compactL.length.coerceAtLeast(1) + 0.2 else 0.0,
                if (compactQ.length >= 4) (1.0 - editDistance(compactQ, compactL).toDouble() / maxOf(compactQ.length, compactL.length)) * 0.8 else 0.0,
            )
        }

        private fun isSubsequence(a: String, b: String): Boolean { var i = 0; for (c in b) if (i < a.length && a[i] == c) i++; return i == a.length }

        fun editDistance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) { val t = d[j]; d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1); prev = t }
            }
            return d[b.length]
        }

        /** Common short names → packages (boost only; the app must still be installed). */
        val ALIASES: Map<String, Set<String>> = mapOf(
            "yt" to setOf("com.google.android.youtube"), "youtube" to setOf("com.google.android.youtube"),
            "wa" to setOf("com.whatsapp"), "whatsapp" to setOf("com.whatsapp"),
            "insta" to setOf("com.instagram.android"), "ig" to setOf("com.instagram.android"),
            "maps" to setOf("com.google.android.apps.maps"), "gmaps" to setOf("com.google.android.apps.maps"),
            "gmail" to setOf("com.google.android.gm"), "mail" to setOf("com.google.android.gm"),
            "chrome" to setOf("com.android.chrome"), "browser" to setOf("com.android.chrome"),
            "play store" to setOf("com.android.vending"), "playstore" to setOf("com.android.vending"),
            "camera" to setOf("com.android.camera", "com.oplus.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera"),
            "photos" to setOf("com.google.android.apps.photos"), "gallery" to setOf("com.google.android.apps.photos", "com.coloros.gallery3d", "com.oneplus.gallery"),
            "messages" to setOf("com.google.android.apps.messaging"), "sms" to setOf("com.google.android.apps.messaging"),
            "telegram" to setOf("org.telegram.messenger"), "spotify" to setOf("com.spotify.music"),
            "x" to setOf("com.twitter.android"), "twitter" to setOf("com.twitter.android"),
            "calculator" to setOf("com.google.android.calculator", "com.oneplus.calculator", "com.coloros.calculator"),
            "clock" to setOf("com.google.android.deskclock", "com.oneplus.deskclock", "com.coloros.alarmclock"),
        )

        val PEOPLE_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "com.android.mms", "com.oneplus.mms", "com.facebook.orca", "com.facebook.katana",
            "com.instagram.android", "com.snapchat.android", "org.thoughtcrime.securesms", "com.discord", "com.Slack",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.truecaller", "com.linkedin.android", "com.twitter.android",
            "com.google.android.apps.dynamite", "com.microsoft.teams", "us.zoom.videomeetings", "com.viber.voip", "jp.naver.line.android",
            "com.reddit.frontpage", "com.threads.android", "com.instagram.barcelona", "com.skype.raider", "com.google.android.talk",
        )

        fun kindOf(pkg: String, caps: Set<Capability>): AppKind = when {
            pkg in PEOPLE_PACKAGES || Capability.VIEW_SMS in caps || Capability.VIEW_MAILTO in caps -> AppKind.PEOPLE
            pkg == "com.android.vending" -> AppKind.STORE
            Capability.VIEW_GEO in caps -> AppKind.MAPS
            Capability.WEB_SEARCH in caps && Capability.VIEW_WEB in caps -> AppKind.BROWSER
            Capability.MEDIA_SEARCH in caps -> AppKind.MEDIA
            else -> AppKind.OTHER
        }
    }
}
