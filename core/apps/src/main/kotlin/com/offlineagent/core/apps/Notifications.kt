package com.offlineagent.core.apps

/** Platform-neutral copy of a StatusBarNotification (built in :app from the listener; memory only, never persisted). */
data class RawNotification(
    val key: String,
    val pkg: String,
    val appLabel: String,
    val postedAt: Long,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val subText: String = "",
    val textLines: List<String> = emptyList(),
    /** MessagingStyle messages (sender, text), oldest first. */
    val messages: List<Pair<String, String>> = emptyList(),
    val conversationTitle: String = "",
    val category: String = "",
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val actions: List<RawAction> = emptyList(),
)

data class RawAction(val title: String, val remoteInputs: List<RawRemoteInput> = emptyList(), val semantic: Int = 0, val contextual: Boolean = false)
data class RawRemoteInput(val resultKey: String, val label: String = "", val allowFreeForm: Boolean = true, val choices: List<String> = emptyList())

enum class NotifActionKind { REPLY, MARK_READ, DELETE, ARCHIVE, MUTE, CALL, LIKE, PAY, OTHER }

data class NotifAction(
    val index: Int,
    val title: String,
    val kind: NotifActionKind,
    /** RemoteInput result key when the action takes free-form text (a reply). */
    val replyKey: String? = null,
    val choices: List<String> = emptyList(),
) {
    val canReply: Boolean get() = replyKey != null
    /** Deleting / paying / sending-like actions are irreversible → always CRITICAL, per step. */
    val irreversible: Boolean get() = kind == NotifActionKind.DELETE || kind == NotifActionKind.PAY || kind == NotifActionKind.REPLY || kind == NotifActionKind.CALL ||
        Regex("\\b(delete|remove|pay|send|transfer|block|report|unsubscribe|accept|decline|approve|confirm|buy|book|call|spam)\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)
}

data class NotifRecord(
    /** Short id the model uses ("n3"); stable while the notification is live. */
    val id: String,
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val actions: List<NotifAction>,
    /** From a banking / OTP / password app (AppSafetyPolicy): text never reaches the model, actions are blocked. */
    val sensitive: Boolean,
    val blockedReason: String? = null,
) {
    val replyAction: NotifAction? get() = actions.firstOrNull { it.canReply }
}

object NotificationParser {
    // Notification.Action.SEMANTIC_ACTION_* values
    const val SEMANTIC_REPLY = 1; const val SEMANTIC_MARK_AS_READ = 2; const val SEMANTIC_DELETE = 4; const val SEMANTIC_ARCHIVE = 5
    const val SEMANTIC_MUTE = 6; const val SEMANTIC_THUMBS_UP = 8; const val SEMANTIC_CALL = 10

    fun kindOf(a: RawAction): NotifActionKind {
        if (a.remoteInputs.any { it.allowFreeForm }) return NotifActionKind.REPLY
        when (a.semantic) {
            SEMANTIC_REPLY -> return NotifActionKind.REPLY
            SEMANTIC_MARK_AS_READ -> return NotifActionKind.MARK_READ
            SEMANTIC_DELETE -> return NotifActionKind.DELETE
            SEMANTIC_ARCHIVE -> return NotifActionKind.ARCHIVE
            SEMANTIC_MUTE -> return NotifActionKind.MUTE
            SEMANTIC_THUMBS_UP -> return NotifActionKind.LIKE
            SEMANTIC_CALL -> return NotifActionKind.CALL
        }
        val t = a.title.lowercase()
        return when {
            Regex("\\b(pay|payment|transfer)\\b").containsMatchIn(t) -> NotifActionKind.PAY
            Regex("\\b(delete|remove|bin|trash)\\b").containsMatchIn(t) -> NotifActionKind.DELETE
            Regex("\\barchive\\b").containsMatchIn(t) -> NotifActionKind.ARCHIVE
            Regex("mark (as )?read|\\bread\\b").containsMatchIn(t) -> NotifActionKind.MARK_READ
            Regex("\\b(mute|snooze)\\b").containsMatchIn(t) -> NotifActionKind.MUTE
            Regex("\\b(call|call back)\\b").containsMatchIn(t) -> NotifActionKind.CALL
            Regex("\\breply\\b").containsMatchIn(t) -> NotifActionKind.REPLY
            else -> NotifActionKind.OTHER
        }
    }

    /** Body text: last MessagingStyle messages, else big text, else inbox lines, else text. */
    fun body(n: RawNotification): String = when {
        n.messages.isNotEmpty() -> n.messages.takeLast(5).joinToString("\n") { (s, t) -> if (s.isBlank()) t else "$s: $t" }
        n.bigText.isNotBlank() -> n.bigText
        n.textLines.isNotEmpty() -> n.textLines.take(6).joinToString("\n")
        else -> n.text
    }.trim()

    /** OTP-looking codes are masked even for non-sensitive apps (SMS app, email). */
    fun redactCodes(text: String): String {
        if (!Regex("\\b(otp|one[- ]time|verification|security code|passcode|login code|auth(entication)? code|\\bcode\\b|pin)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)) return text
        return text.replace(Regex("(?<![\\d₹.,])\\b\\d{4,8}\\b(?![.,]\\d)"), "[code hidden]")
    }

    /** Null for ongoing/foreground-service, group summaries and empty notifications, and ONYX's own. */
    fun parse(id: String, n: RawNotification, policy: AppSafetyPolicy): NotifRecord? {
        if (n.ongoing || n.groupSummary) return null
        val v = policy.checkPackage(n.pkg, n.appLabel, AppSafetyPolicy.Use.NOTIFICATION) as? AppSafetyPolicy.SafetyVerdict.Blocked
        if (v?.category == AppSafetyPolicy.Category.SELF) return null
        val title = n.conversationTitle.ifBlank { n.title }.trim()
        val body = body(n)
        if (title.isBlank() && body.isBlank()) return null
        val sensitive = policy.isSensitiveNotificationSource(n.pkg, n.appLabel)
        val actions = n.actions.mapIndexedNotNull { i, a ->
            if (a.title.isBlank()) return@mapIndexedNotNull null
            val free = a.remoteInputs.firstOrNull { it.allowFreeForm }
            NotifAction(i, a.title.trim(), kindOf(a), free?.resultKey, a.remoteInputs.flatMap { it.choices }.take(5))
        }
        return NotifRecord(id, n.key, n.pkg, n.appLabel, if (sensitive) "" else title, if (sensitive) "" else redactCodes(body), n.postedAt,
            if (v != null) emptyList() else actions, sensitive, v?.reason)
    }
}

/**
 * In-memory store of live notifications (what the listener has seen and not seen removed). Short ids ("n1", "n2" …)
 * are handed to the model instead of the system keys. Thread-safe. Nothing is written to disk.
 */
class NotificationStore(private val policy: AppSafetyPolicy, private val max: Int = 100) {
    private val byKey = LinkedHashMap<String, NotifRecord>()
    private val idOf = HashMap<String, String>()
    private var next = 1

    @Synchronized
    fun posted(n: RawNotification): NotifRecord? {
        val id = idOf.getOrPut(n.key) { "n" + next++ }
        val r = NotificationParser.parse(id, n, policy)
        if (r == null) { byKey.remove(n.key); return null }
        byKey.remove(n.key); byKey[n.key] = r // re-insert = newest last
        while (byKey.size > max) { val k = byKey.keys.first(); byKey.remove(k); idOf.remove(k) }
        return r
    }

    @Synchronized fun removed(key: String) { byKey.remove(key); idOf.remove(key) }
    @Synchronized fun clear() { byKey.clear(); idOf.clear() }

    /** Newest first; [app] filters by label or package (contains, case-insensitive). */
    @Synchronized
    fun list(app: String? = null, limit: Int = 10): List<NotifRecord> = byKey.values.reversed()
        .filter { app.isNullOrBlank() || it.app.contains(app.trim(), true) || it.pkg.contains(app.trim(), true) }
        .take(limit)

    @Synchronized fun get(id: String): NotifRecord? = byKey.values.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    @Synchronized fun size() = byKey.size
}
