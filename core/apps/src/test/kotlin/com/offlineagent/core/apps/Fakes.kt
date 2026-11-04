package com.offlineagent.core.apps

import com.offlineagent.core.agents.Risk

/** A fake phone: launchable apps + which probes each one answers. */
class FakePackages(
    val apps: MutableList<RawApp> = mutableListOf(
        RawApp("com.whatsapp", "WhatsApp"),
        RawApp("com.google.android.youtube", "YouTube"),
        RawApp("com.google.android.apps.maps", "Maps"),
        RawApp("com.android.chrome", "Chrome"),
        RawApp("com.google.android.gm", "Gmail"),
        RawApp("com.spotify.music", "Spotify"),
        RawApp("com.phonepe.app", "PhonePe"),
        RawApp("com.google.android.apps.nbu.paisa.user", "Google Pay"),
        RawApp("com.x8bit.bitwarden", "Bitwarden"),
        RawApp("com.google.android.apps.authenticator2", "Authenticator"),
        RawApp("com.android.settings", "Settings"),
        RawApp("com.offlineagent.app", "ONYX"),
        RawApp("com.example.notes", "Keep Notes"),
        RawApp("com.android.vending", "Play Store"),
        RawApp("com.example.bank.mobile", "Cool Mobile"),
    ),
    val caps: MutableMap<Capability, MutableSet<String>> = mutableMapOf(
        Capability.SEND_TEXT to mutableSetOf("com.whatsapp", "com.google.android.gm", "com.example.notes", "com.android.chrome"),
        Capability.SEND_IMAGE to mutableSetOf("com.whatsapp", "com.google.android.gm"),
        Capability.SEND_FILE to mutableSetOf("com.google.android.gm"),
        Capability.SEND_MULTIPLE to mutableSetOf("com.google.android.gm"),
        Capability.VIEW_WEB to mutableSetOf("com.android.chrome", "com.google.android.youtube"),
        Capability.VIEW_GEO to mutableSetOf("com.google.android.apps.maps"),
        Capability.VIEW_MAILTO to mutableSetOf("com.google.android.gm"),
        Capability.WEB_SEARCH to mutableSetOf("com.android.chrome"),
        Capability.MEDIA_SEARCH to mutableSetOf("com.spotify.music"),
    ),
) : PackageSource {
    var queries = 0
    val resolvable = mutableSetOf<String?>() // packages for which resolves() is true; null entry = default handler exists
    init { resolvable += listOf(null, "com.whatsapp", "com.google.android.youtube", "com.google.android.apps.maps", "com.android.chrome", "com.google.android.gm", "com.spotify.music", "com.android.vending") }
    override fun launchableApps(): List<RawApp> { queries++; return apps.toList() }
    override fun handlers(probe: IntentProbe): Set<String> { queries++; return caps[probe.capability].orEmpty() }
    override fun resolves(intent: AppIntent): Boolean = intent.pkg in resolvable
}

class FakeLauncher : AppLauncherPort {
    val started = mutableListOf<AppIntent>()
    override suspend fun start(intent: AppIntent): String { started += intent; return "opened" }
}

class FakeClipboard(var clip: ClipText? = null) : ClipboardPort {
    override suspend fun read() = clip
    override suspend fun write(text: String, sensitive: Boolean) { clip = ClipText(text, null, sensitive) }
}

class FakeActuator(override var enabled: Boolean = true) : NotificationActuator {
    val invoked = mutableListOf<Pair<String, Int>>()
    val replies = mutableListOf<List<Any>>()
    override suspend fun invoke(key: String, actionIndex: Int) { invoked += key to actionIndex }
    override suspend fun reply(key: String, actionIndex: Int, resultKey: String, text: String) { replies += listOf(key, actionIndex, resultKey, text) }
}

class FakeAudit : BridgeAudit {
    val entries = mutableListOf<Triple<String, String, Risk>>()
    override suspend fun record(action: String, detail: String, risk: Risk) { entries += Triple(action, detail, risk) }
}

fun testPolicy(userBlocked: Set<String> = emptySet()) = AppSafetyPolicy.default(setOf("com.offlineagent.app"), { userBlocked })

fun waNotification(key: String = "0|com.whatsapp|1|null|10123", text: String = "Are we still on for 7?") = RawNotification(
    key = key, pkg = "com.whatsapp", appLabel = "WhatsApp", postedAt = 1_000_000L, title = "Ravi", text = text,
    messages = listOf("Ravi" to "hey", "Ravi" to text),
    actions = listOf(
        RawAction("Reply", listOf(RawRemoteInput("key_text_reply", "Reply", allowFreeForm = true)), semantic = 1),
        RawAction("Mark as read", semantic = 2),
    ),
)
