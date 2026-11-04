package com.offlineagent.core.apps

import java.net.URLEncoder

/**
 * Platform-neutral description of an Android Intent. [:app] turns it into a real `android.content.Intent`
 * (AndroidAppPorts.toAndroid). Built only by [IntentBuilder], which runs every safety check first.
 */
data class AppIntent(
    val action: String,
    val data: String? = null,
    val type: String? = null,
    /** Target package (Intent.setPackage); null = system picks / chooser. */
    val pkg: String? = null,
    val categories: Set<String> = emptySet(),
    val extras: Map<String, String> = emptyMap(),
    /** Files to grant read access to (EXTRA_STREAM), already resolved by [FileResolver] — never from model text. */
    val streams: List<SharedFile> = emptyList(),
    /** Wrap in Intent.createChooser with this title (user picks the app). */
    val chooserTitle: String? = null,
    /** Use PackageManager.getLaunchIntentForPackage(launchPackage) instead of the fields above. */
    val launchPackage: String? = null,
) {
    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
        const val ACTION_SENDTO = "android.intent.action.SENDTO"
        const val ACTION_DIAL = "android.intent.action.DIAL"
        const val ACTION_WEB_SEARCH = "android.intent.action.WEB_SEARCH"
        const val ACTION_SEARCH = "android.intent.action.SEARCH"
        const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
        const val ACTION_PROCESS_TEXT = "android.intent.action.PROCESS_TEXT"
        const val CATEGORY_BROWSABLE = "android.intent.category.BROWSABLE"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_SUBJECT = "android.intent.extra.SUBJECT"
        /** SearchManager.QUERY */
        const val EXTRA_QUERY = "query"
    }
}

/** A file ONYX itself owns (export, download, generated PDF) exposed through its FileProvider. */
data class SharedFile(val ref: String, val uri: String, val mime: String, val name: String, val sizeBytes: Long = -1)

/** Known in-app search deep links (no API exposes other apps' deep links; this table is curated). `{q}` = URL-encoded query. */
object DeepLinks {
    val SEARCH: Map<String, String> = mapOf(
        "com.google.android.youtube" to "https://www.youtube.com/results?search_query={q}",
        "com.google.android.apps.youtube.music" to "https://music.youtube.com/search?q={q}",
        "com.google.android.apps.maps" to "geo:0,0?q={q}",
        "com.android.vending" to "market://search?q={q}&c=apps",
        "com.spotify.music" to "spotify:search:{q}",
        "in.amazon.mShop.android.shopping" to "https://www.amazon.in/s?k={q}",
        "com.amazon.mShop.android.shopping" to "https://www.amazon.in/s?k={q}",
        "com.flipkart.android" to "https://www.flipkart.com/search?q={q}",
        "org.wikipedia" to "https://en.wikipedia.org/w/index.php?search={q}",
        "com.reddit.frontpage" to "https://www.reddit.com/search/?q={q}",
        "com.netflix.mediaclient" to "https://www.netflix.com/search?q={q}",
        "com.twitter.android" to "https://x.com/search?q={q}",
        "com.linkedin.android" to "https://www.linkedin.com/search/results/all/?keywords={q}",
        "com.github.android" to "https://github.com/search?q={q}",
    )

    fun search(pkg: String, query: String): String? = SEARCH[pkg]?.replace("{q}", enc(query))

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

sealed interface BuildResult {
    data class Ok(val intent: AppIntent, val summary: String) : BuildResult
    data class Rejected(val reason: String, val blocked: Boolean = false) : BuildResult
}

/**
 * Builds intents for the AppBridge tools. Every result passes [vet] (package, action and URI checks of
 * [AppSafetyPolicy]) — there is no way to build an intent around it.
 */
class IntentBuilder(private val policy: AppSafetyPolicy) {

    fun open(app: AppEntry): BuildResult = vet(AppIntent(AppIntent.ACTION_MAIN, launchPackage = app.pkg, pkg = app.pkg, categories = setOf(AppIntent.CATEGORY_LAUNCHER)), "Open ${app.label}", app)

    /** Share [text] and/or [files] into [app]; null app = Android's share sheet (the user picks). */
    fun share(app: AppEntry?, text: String, files: List<SharedFile>, subject: String = ""): BuildResult {
        app?.blocked?.let { return BuildResult.Rejected(it.reason, blocked = true) }
        if (text.isBlank() && files.isEmpty()) return BuildResult.Rejected("Nothing to share: give text or a file.")
        val multiple = files.size > 1
        val mime = when {
            files.isEmpty() -> "text/plain"
            files.map { it.mime }.distinct().size == 1 -> files.first().mime
            files.all { it.mime.startsWith("image/") } -> "image/*"
            else -> "*/*"
        }
        if (app != null) {
            val need = when {
                multiple -> Capability.SEND_MULTIPLE
                files.isEmpty() -> Capability.SEND_TEXT
                mime.startsWith("image/") -> Capability.SEND_IMAGE
                else -> Capability.SEND_FILE
            }
            val ok = app.can(need) || (need == Capability.SEND_IMAGE && app.can(Capability.SEND_FILE))
            if (!ok) return BuildResult.Rejected("${app.label} does not accept ${need.title.removePrefix("receive ")}. Leave app empty to let the user pick an app.")
        }
        val extras = buildMap {
            if (text.isNotBlank()) put(AppIntent.EXTRA_TEXT, text)
            if (subject.isNotBlank()) put(AppIntent.EXTRA_SUBJECT, subject)
        }
        val i = AppIntent(if (multiple) AppIntent.ACTION_SEND_MULTIPLE else AppIntent.ACTION_SEND, type = mime, pkg = app?.pkg, extras = extras, streams = files,
            chooserTitle = if (app == null) "Share with" else null)
        val what = listOfNotNull(if (text.isNotBlank()) "text (${text.length} chars)" else null, if (files.isNotEmpty()) files.joinToString { it.name } else null).joinToString(" + ")
        return vet(i, "Share $what ${if (app != null) "to ${app.label}" else "via the share sheet"}", app)
    }

    /** Open a link / deep link with its default handler or with [app]. */
    fun view(uri: String, app: AppEntry?): BuildResult {
        val u = uri.trim()
        app?.blocked?.let { return BuildResult.Rejected(it.reason, blocked = true) }
        if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(u)) return BuildResult.Rejected("Not a link (needs a scheme like https:, geo:, tel:): $u")
        val scheme = u.substringBefore(':').lowercase()
        val i = when (scheme) {
            "mailto", "smsto", "sms" -> AppIntent(AppIntent.ACTION_SENDTO, data = u, pkg = app?.pkg)
            "tel" -> AppIntent(AppIntent.ACTION_DIAL, data = u, pkg = app?.pkg)
            else -> AppIntent(AppIntent.ACTION_VIEW, data = u, pkg = app?.pkg, categories = if (scheme == "http" || scheme == "https") setOf(AppIntent.CATEGORY_BROWSABLE) else emptySet())
        }
        return vet(i, "Open $u${app?.let { " in ${it.label}" } ?: ""}", app)
    }

    /** Search inside [app] (curated deep link → ACTION_WEB_SEARCH → ACTION_SEARCH → MEDIA_PLAY_FROM_SEARCH), or a web search. */
    fun search(query: String, app: AppEntry?): BuildResult {
        val q = query.trim()
        app?.blocked?.let { return BuildResult.Rejected(it.reason, blocked = true) }
        if (q.isEmpty()) return BuildResult.Rejected("Empty search query.")
        if (app == null) return vet(AppIntent(AppIntent.ACTION_WEB_SEARCH, extras = mapOf(AppIntent.EXTRA_QUERY to q)), "Web search \"$q\"", null)
        DeepLinks.search(app.pkg, q)?.let { link ->
            return vet(AppIntent(AppIntent.ACTION_VIEW, data = link, pkg = app.pkg,
                categories = if (link.startsWith("http")) setOf(AppIntent.CATEGORY_BROWSABLE) else emptySet()), "Search \"$q\" in ${app.label}", app)
        }
        val i = when {
            app.can(Capability.WEB_SEARCH) -> AppIntent(AppIntent.ACTION_WEB_SEARCH, pkg = app.pkg, extras = mapOf(AppIntent.EXTRA_QUERY to q))
            app.can(Capability.SEARCH) -> AppIntent(AppIntent.ACTION_SEARCH, pkg = app.pkg, extras = mapOf(AppIntent.EXTRA_QUERY to q))
            app.can(Capability.MEDIA_SEARCH) -> AppIntent(AppIntent.ACTION_MEDIA_PLAY_FROM_SEARCH, pkg = app.pkg, extras = mapOf(AppIntent.EXTRA_QUERY to q))
            else -> return BuildResult.Rejected("${app.label} has no search entry point ONYX knows. Open it with app_open instead.")
        }
        return vet(i, "Search \"$q\" in ${app.label}", app)
    }

    /** The single gate: blocked app, blocked action, blocked link → Rejected(blocked = true). */
    fun vet(i: AppIntent, summary: String, app: AppEntry?): BuildResult {
        app?.blocked?.let { return BuildResult.Rejected(it.reason, blocked = true) }
        (i.pkg ?: i.launchPackage)?.let { p -> (policy.checkPackage(p, app?.label) as? AppSafetyPolicy.SafetyVerdict.Blocked)?.let { return BuildResult.Rejected(it.reason, true) } }
        (policy.checkAction(i.action) as? AppSafetyPolicy.SafetyVerdict.Blocked)?.let { return BuildResult.Rejected(it.reason, true) }
        i.data?.let { d -> (policy.checkUri(d) as? AppSafetyPolicy.SafetyVerdict.Blocked)?.let { return BuildResult.Rejected(it.reason, true) } }
        // Shared text can carry a pay link too ("pay me: upi://pay?pa=…"): never hand it over.
        i.extras.values.forEach { v -> if (Regex("upi:(//)?pay|upi%3a", RegexOption.IGNORE_CASE).containsMatchIn(v)) return BuildResult.Rejected("Blocked for safety: the text contains a UPI payment link.", true) }
        return BuildResult.Ok(i, summary)
    }
}
