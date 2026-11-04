package com.offlineagent.core.apps

import java.net.URLDecoder

/**
 * The ONE app-safety blocklist of ONYX, shared by AppBridge (catalogue, intents, share, notifications) and the screen
 * tools. Rules live in the classpath file `com/offlineagent/core/apps/app_safety_blocklist.txt` (see its header for the
 * format); the user can add packages on top ([userBlocked]).
 *
 * API (all pure, cheap, thread-safe):
 *  - [checkPackage] (pkg, label?, use) → [SafetyVerdict]: may ONYX touch this app for this kind of [Use]?
 *  - [checkUri] (uri) → verdict: UPI / pay-app schemes, payment hosts, UPI pay parameters (pa=…@…), USSD codes in tel:,
 *    otpauth:, intent:/file:/content:/javascript:/data: are blocked; percent-encoded tricks are decoded first.
 *  - [checkAction] (Android intent action) → verdict: settings/admin/installer/role actions are blocked.
 *  - [isSensitiveNotificationSource] → whether a notification's text must be hidden (bank/OTP/password apps).
 *
 * Screen tools should call `checkPackage(pkg, label, Use.SCREEN)` before reading/operating a window: SCREEN is stricter
 * than INTENT (it also blocks [Category.STORE_PURCHASE]).
 */
class AppSafetyPolicy(
    val rules: List<Rule>,
    /** Packages of ONYX itself (application id incl. flavor/build-type suffixes). Always blocked. */
    private val selfPackages: Set<String> = emptySet(),
    /** Extra packages the user blocked in Settings (exact names). */
    private val userBlocked: () -> Set<String> = { emptySet() },
) {
    enum class Category(val title: String, val screenOnly: Boolean = false) {
        PAYMENT("payment / banking / UPI app"),
        PASSWORD_MANAGER("password manager"),
        AUTHENTICATOR("authenticator / OTP app"),
        SELF("ONYX itself"),
        SYSTEM_ADMIN("system settings / admin / installer"),
        STORE_PURCHASE("app store purchase screens", screenOnly = true),
        USER("app you blocked"),
        UNSAFE_LINK("unsafe link type"),
    }

    /** What ONYX wants to do with the app: hand it an intent (open/share/view/search/notification action) or operate its screen. */
    enum class Use { INTENT, NOTIFICATION, SCREEN }

    enum class Kind { PACKAGE, PREFIX, WORD, LABEL, SCHEME, HOST, ACTION }

    data class Rule(val category: Category, val kind: Kind, val value: String)

    sealed interface SafetyVerdict {
        val allowed: Boolean
        data object Allowed : SafetyVerdict { override val allowed = true }
        data class Blocked(val category: Category, val reason: String) : SafetyVerdict { override val allowed = false }
    }

    private val byKind: Map<Kind, List<Rule>> = rules.groupBy { it.kind }
    private val exact: Map<String, Rule> = byKind[Kind.PACKAGE].orEmpty().associateBy { it.value.lowercase() }

    fun checkPackage(pkg: String, label: String? = null, use: Use = Use.INTENT): SafetyVerdict {
        val p = pkg.trim().lowercase()
        if (p.isEmpty()) return SafetyVerdict.Allowed
        if (p in selfPackages.map { it.lowercase() } || selfPackages.any { p.startsWith(it.lowercase() + ".") })
            return blocked(Category.SELF, pkg)
        if (userBlocked().any { it.equals(pkg, ignoreCase = true) }) return blocked(Category.USER, pkg)
        val hit = exact[p]
            ?: byKind[Kind.PREFIX].orEmpty().firstOrNull { p.startsWith(it.value.lowercase()) }
            ?: run {
                val segs = p.split('.', '_', '-').toSet()
                byKind[Kind.WORD].orEmpty().firstOrNull { it.value.lowercase() in segs }
            }
            ?: label?.let { l ->
                val words = l.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()
                byKind[Kind.LABEL].orEmpty().firstOrNull { it.value.lowercase() in words }
            }
        if (hit == null) return SafetyVerdict.Allowed
        if (hit.category.screenOnly && use != Use.SCREEN) return SafetyVerdict.Allowed
        return blocked(hit.category, label ?: pkg)
    }

    fun isBlocked(pkg: String, label: String? = null, use: Use = Use.INTENT) = !checkPackage(pkg, label, use).allowed

    /** Notification text from these apps is never shown to the model (OTPs, balances, passwords). */
    fun isSensitiveNotificationSource(pkg: String, label: String? = null): Boolean =
        (checkPackage(pkg, label, Use.NOTIFICATION) as? SafetyVerdict.Blocked)?.category.let {
            it == Category.PAYMENT || it == Category.PASSWORD_MANAGER || it == Category.AUTHENTICATOR
        }

    fun checkAction(action: String): SafetyVerdict {
        val a = action.trim()
        val hit = byKind[Kind.ACTION].orEmpty().firstOrNull { it.value.equals(a, ignoreCase = true) }
        return if (hit != null) blocked(hit.category, a) else SafetyVerdict.Allowed
    }

    fun checkUri(raw: String): SafetyVerdict {
        val variants = decodeVariants(raw.trim())
        for (u in variants) {
            val v = checkOne(u)
            if (!v.allowed) return v
        }
        return SafetyVerdict.Allowed
    }

    private fun checkOne(u: String): SafetyVerdict {
        val lower = u.lowercase()
        // Pay links hidden anywhere inside another link (redirect parameters, intent fragments): upi://, upi:pay …
        if (Regex("(^|[^a-z])upi:(//)?pay").containsMatchIn(lower)) return blocked(Category.PAYMENT, "UPI payment link")
        val scheme = Regex("^([a-z][a-z0-9+.-]*):").find(lower)?.groupValues?.get(1)
            ?: return SafetyVerdict.Allowed // relative/plain text: callers decide (tools require a scheme)
        if (scheme in HARD_SCHEMES) return blocked(Category.UNSAFE_LINK, "$scheme: links are not allowed")
        byKind[Kind.SCHEME].orEmpty().firstOrNull { it.value == scheme }?.let { return blocked(it.category, "$scheme: links") }
        if (scheme == "tel" && Regex("[*#]|%2a|%23").containsMatchIn(lower.removePrefix("tel:")))
            return blocked(Category.PAYMENT, "USSD / MMI codes (e.g. *99# UPI) cannot be dialled by ONYX")
        if (scheme == "android-app") {
            val pkg = lower.removePrefix("android-app://").substringBefore('/')
            val v = checkPackage(pkg); if (!v.allowed) return v
        }
        val host = Regex("^[a-z][a-z0-9+.-]*://([^/?#:@]*@)?([^/?#:]+)").find(lower)?.groupValues?.get(2).orEmpty()
        if (host.isNotEmpty()) byKind[Kind.HOST].orEmpty().firstOrNull { host == it.value || host.endsWith("." + it.value) }
            ?.let { return blocked(it.category, "payment site $host") }
        // UPI pay parameters (pa=<vpa>@<psp>) inside any link
        val query = lower.substringAfter('?', "")
        if (query.isNotEmpty() && Regex("(^|&)pa=[^&]*@").containsMatchIn(query) && Regex("(^|&)(pn|am|tn|cu|mc)=").containsMatchIn(query))
            return blocked(Category.PAYMENT, "UPI payment parameters")
        return SafetyVerdict.Allowed
    }

    private fun decodeVariants(u: String): List<String> {
        val out = linkedSetOf(u)
        var cur = u
        repeat(3) {
            val d = runCatching { URLDecoder.decode(cur.replace("+", "%2B"), "UTF-8") }.getOrDefault(cur)
            if (d == cur) return out.toList()
            out += d; cur = d
        }
        return out.toList()
    }

    private fun blocked(c: Category, what: String) = SafetyVerdict.Blocked(c, when (c) {
        Category.SELF -> "ONYX cannot drive itself ($what)"
        Category.UNSAFE_LINK -> "Blocked: $what"
        Category.USER -> "You blocked $what for ONYX"
        else -> "Blocked for safety: $what is a ${c.title}. ONYX never opens, shares to, or operates these apps or links — do it yourself."
    })

    companion object {
        /** Never handed to another app from model output (file/content leak, script, arbitrary components). */
        val HARD_SCHEMES = setOf("intent", "file", "content", "javascript", "data", "about", "chrome", "vbscript")

        const val RESOURCE = "/com/offlineagent/core/apps/app_safety_blocklist.txt"

        fun parse(text: String): List<Rule> = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val cat = runCatching { Category.valueOf(parts[0].uppercase()) }.getOrNull() ?: return@mapNotNull null
            val kind = runCatching { Kind.valueOf(parts[1].uppercase()) }.getOrNull() ?: return@mapNotNull null
            Rule(cat, kind, if (kind == Kind.ACTION) parts[2] else parts[2].lowercase())
        }.toList()

        val defaultRules: List<Rule> by lazy {
            val text = AppSafetyPolicy::class.java.getResourceAsStream(RESOURCE)?.bufferedReader()?.use { it.readText() }
                ?: error("missing $RESOURCE")
            parse(text)
        }

        fun default(selfPackages: Set<String> = emptySet(), userBlocked: () -> Set<String> = { emptySet() }) =
            AppSafetyPolicy(defaultRules, selfPackages, userBlocked)
    }
}
