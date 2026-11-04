package com.offlineagent.core.apps

/**
 * Phase 6.1: the Settings page allowlist for SCREEN use (docs/research/MIKA-COMPARISON.md §6). The Settings app as a
 * whole stays `system_admin` in the shared blocklist (AppBridge never opens it); the screen agent may enter it only
 * through a page's own entry intent and act only while the window shows an allowlisted page:
 *  - ALLOW: Wi-Fi (saved networks), Bluetooth (paired devices), Display, Sound, Do Not Disturb, Battery saver.
 *  - ASK (every step, never covered by an app scope): Location, Airplane mode, Mobile data, Hotspot.
 *  - BLOCK: every other page — including the top-level Settings home and its search, which can jump anywhere.
 *
 * Pure and English-only (titles are matched after normalisation). An unknown title means BLOCK.
 */
object SettingsPages {
    enum class Access { ALLOW, ASK, BLOCK }

    enum class Page(
        val title: String,
        val access: Access,
        /** Entry intents, tried in order (the first one the phone resolves wins). */
        val actions: List<String>,
        /** Window titles that identify the page (normalised, matched exactly). */
        val titles: List<String>,
        /** Words in a request ("turn on dark mode") that point at this page. */
        val keywords: List<String>,
    ) {
        WIFI("Wi-Fi", Access.ALLOW, listOf("android.settings.WIFI_SETTINGS"),
            listOf("wi-fi", "wifi", "wlan", "internet", "saved networks", "wi-fi preferences"),
            listOf("wi-fi", "wifi", "wlan", "saved network", "wireless network")),
        BLUETOOTH("Bluetooth", Access.ALLOW, listOf("android.settings.BLUETOOTH_SETTINGS"),
            listOf("bluetooth", "connected devices", "paired devices", "previously connected devices", "saved devices"),
            listOf("bluetooth", "paired", "earbuds", "headphones", "headset")),
        DISPLAY("Display", Access.ALLOW, listOf("android.settings.DISPLAY_SETTINGS"),
            listOf("display", "display & brightness", "display and brightness", "dark mode", "dark theme", "screen timeout", "auto screen off",
                "eye comfort", "night light", "font size", "display size", "font size & display size", "font & display size",
                "screen refresh rate", "refresh rate", "brightness", "colors", "colours", "screen color mode"),
            listOf("display", "dark mode", "dark theme", "brightness", "screen timeout", "night light", "eye comfort", "font size", "refresh rate", "screen off")),
        SOUND("Sound & vibration", Access.ALLOW, listOf("android.settings.SOUND_SETTINGS"),
            listOf("sound", "sounds", "sound & vibration", "sounds & vibration", "sound and vibration", "sounds and vibration", "volume",
                "ringtone", "vibration", "vibration & haptics", "sound & vibrate"),
            listOf("sound", "volume", "ringtone", "vibrat", "silent mode", "ring mode")),
        DND("Do Not Disturb", Access.ALLOW, listOf("android.settings.ZEN_MODE_SETTINGS", "android.settings.ZEN_MODE_PRIORITY_SETTINGS"),
            listOf("do not disturb", "priority mode", "modes", "focus mode"),
            listOf("do not disturb", "dnd", "priority mode")),
        BATTERY_SAVER("Battery saver", Access.ALLOW, listOf("android.settings.BATTERY_SAVER_SETTINGS"),
            listOf("battery", "battery saver", "power saving mode", "power saving", "battery saving", "power saver"),
            listOf("battery saver", "power saving", "power saver", "battery")),
        LOCATION("Location", Access.ASK, listOf("android.settings.LOCATION_SOURCE_SETTINGS"),
            listOf("location", "use location", "location services"),
            listOf("location", "gps")),
        AIRPLANE("Airplane mode", Access.ASK, listOf("android.settings.AIRPLANE_MODE_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
            listOf("airplane mode", "aeroplane mode", "flight mode", "network & internet", "network and internet", "connections",
                "connection & sharing", "connection and sharing", "more connections", "other wireless connections"),
            listOf("airplane", "aeroplane", "flight mode")),
        MOBILE_DATA("Mobile data", Access.ASK, listOf("android.settings.DATA_ROAMING_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
            listOf("mobile network", "mobile networks", "mobile data", "sim & mobile network", "sims", "sim card & mobile data",
                "sim cards & mobile data", "mobile network & sim", "sim & network", "cellular"),
            listOf("mobile data", "cellular", "data roaming", "mobile network")),
        HOTSPOT("Hotspot", Access.ASK, listOf("android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS"),
            listOf("hotspot", "hotspot & tethering", "hotspot and tethering", "personal hotspot", "wi-fi hotspot", "portable hotspot", "tethering"),
            listOf("hotspot", "tether")),
    }

    data class Verdict(val access: Access, val page: Page?, val reason: String)

    /** Packages that ARE the Settings app (page-gated for screen use). Permission/installer packages stay hard-blocked elsewhere. */
    val SETTINGS_APPS = setOf("com.android.settings", "com.oplus.settings", "com.coloros.settings", "com.samsung.android.settings", "com.google.android.settings")

    /** Settings words that always block, whatever the title (accessibility, admin, developer options…). */
    val FORBIDDEN = listOf("accessibility", "device admin", "device administrator", "install unknown apps", "unknown sources",
        "developer options", "usb debugging", "wireless debugging", "special app access", "display over other apps",
        "device & app notifications", "notification access", "factory reset", "erase all data", "reset options",
        "face unlock", "app lock", "hidden apps", "private safe", "passwords & accounts", "app permissions", "permission manager",
        "default apps", "digital assistant")

    private val HOME_TITLES = setOf("settings", "search settings", "search")

    val allowedSummary: String = Page.entries.filter { it.access == Access.ALLOW }.joinToString { it.title } +
        "; ask each time: " + Page.entries.filter { it.access == Access.ASK }.joinToString { it.title }

    fun norm(s: String) = s.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ").trim().trimEnd(':')

    fun isSettingsApp(pkg: String) = pkg.lowercase() in SETTINGS_APPS

    /** "Settings > Display", "display settings", "settings: turn on dark mode", "com.android.settings" → true. */
    fun isSettingsRequest(app: String): Boolean {
        val a = norm(app)
        return isSettingsApp(a) || a == "settings" || a.startsWith("settings ") || a.startsWith("settings>") || a.startsWith("settings:") ||
            a.startsWith("settings›") || a.startsWith("settings→") || a.endsWith(" settings") || a.startsWith("android settings")
    }

    /** The allowlisted page a request points at ("Settings > Display" / "turn on dark mode"), or null. */
    fun pageFor(text: String): Page? {
        val t = norm(text).replace(Regex("^(android )?settings\\s*[>:›→/-]?\\s*"), "").replace(Regex("\\s*settings$"), "")
        if (t.isBlank()) return null
        Page.entries.firstOrNull { p -> p.titles.any { it == t } }?.let { return it }
        // Ask pages first for keywords: "turn off mobile data" must not land on Wi-Fi via "data".
        val order = Page.entries.sortedBy { if (it.access == Access.ASK) 0 else 1 }
        return order.firstOrNull { p -> p.keywords.any { k -> Regex("(^|[^a-z])" + Regex.escape(k)).containsMatchIn(t) } }
    }

    /**
     * Classifies a Settings window. [titles] = candidate page titles, top of the screen first (toolbar / collapsing title);
     * [allText] = every visible label, lowercased.
     */
    fun classify(titles: List<String>, allText: String): Verdict {
        val text = allText.lowercase()
        FORBIDDEN.firstOrNull { Regex("(^|[^a-z])" + Regex.escape(it) + "([^a-z]|$)").containsMatchIn(text) }?.let {
            return Verdict(Access.BLOCK, null, "this Settings page shows \"$it\" — ONYX never changes accessibility, security, accounts, admin or developer settings")
        }
        for (raw in titles.take(6)) {
            val t = norm(raw)
            if (t.isBlank()) continue
            if (t in HOME_TITLES) return Verdict(Access.BLOCK, null, "this is the Settings home or search, which can jump anywhere — open a specific page instead ($allowedSummary)")
            val page = Page.entries.firstOrNull { p -> p.titles.any { it == t } } ?: continue
            return when (page.access) {
                Access.ALLOW -> Verdict(Access.ALLOW, page, "Settings › ${page.title} is on the allowlist")
                Access.ASK -> Verdict(Access.ASK, page, "Settings › ${page.title} cuts connectivity or shares your location — every step asks you")
                Access.BLOCK -> Verdict(Access.BLOCK, page, "Settings › ${page.title} is blocked")
            }
        }
        return Verdict(Access.BLOCK, null, "this Settings page is not on the allowlist ($allowedSummary). Back is the only way out")
    }

    /** Controls on an allowed page that still ask every time (an Airplane toggle on the Internet page, Forget network…). */
    fun elementNeedsConfirm(label: String): String? {
        val l = label.lowercase()
        val hit = listOf("airplane", "aeroplane", "flight mode", "mobile data", "hotspot", "tethering", "use location", "location", "forget", "unpair", "data roaming")
            .firstOrNull { Regex("(^|[^a-z])" + Regex.escape(it)).containsMatchIn(l) } ?: return null
        return "\"${label.take(40)}\" ($hit) changes connectivity, location or a saved device — confirm this step"
    }
}
