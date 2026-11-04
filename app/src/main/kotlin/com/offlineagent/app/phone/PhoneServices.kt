package com.offlineagent.app.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.offlineagent.app.MainActivity
import com.offlineagent.core.runtime.phone.FileEntry
import com.offlineagent.core.runtime.phone.FilesPort
import com.offlineagent.core.runtime.phone.FolderGrant
import com.offlineagent.core.runtime.phone.NotificationEntry
import com.offlineagent.core.runtime.phone.NotificationsPort
import com.offlineagent.core.runtime.phone.PhoneActionException
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.ReminderEntry
import com.offlineagent.core.runtime.phone.RemindersPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit

object Notifications {
    const val CH_REMINDERS = "reminders"
    const val CH_AGENT = "agent_runs"
    const val CH_AUTOMATION = "automation"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_AGENT, "Assistant tasks", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_AUTOMATION, "Automations & macros", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(ctx: Context, extra: String? = null): PendingIntent = PendingIntent.getActivity(
        ctx, extra?.hashCode() ?: 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).apply { extra?.let { putExtra("open", it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun post(ctx: Context, channel: String, id: Int, title: String, text: String, extra: String? = null) {
        val n = NotificationCompat.Builder(ctx, channel).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setContentIntent(openApp(ctx, extra)).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) } // POST_NOTIFICATIONS may be denied: then silently nothing
    }
}

// ---------------------------------------------------------------- reminders (WorkManager, fully offline)

@Serializable
data class StoredReminder(val id: String, val text: String, val atMillis: Long, val repeat: String)

class ReminderStore(ctx: Context) {
    private val file = File(ctx.filesDir, "reminders.json")
    private val json = Json { ignoreUnknownKeys = true }
    @Synchronized fun all(): List<StoredReminder> = runCatching { json.decodeFromString(ListSerializer(StoredReminder.serializer()), file.readText()) }.getOrDefault(emptyList())
    @Synchronized fun save(list: List<StoredReminder>) { file.writeText(json.encodeToString(ListSerializer(StoredReminder.serializer()), list)) }
}

class WorkManagerReminders(private val ctx: Context) : RemindersPort {
    private val store = ReminderStore(ctx)

    override suspend fun schedule(text: String, atMillis: Long, repeat: String): ReminderEntry = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val delay = (atMillis - System.currentTimeMillis()).coerceAtLeast(0)
        val data = workDataOf("id" to id, "text" to text)
        val wm = WorkManager.getInstance(ctx)
        when (repeat) {
            "daily", "weekly" -> wm.enqueueUniquePeriodicWork("reminder-$id", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ReminderWorker>(if (repeat == "daily") 1 else 7, TimeUnit.DAYS).setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag("reminder").build())
            else -> wm.enqueue(OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).setInputData(data).addTag("reminder").addTag("reminder-$id").build())
        }
        store.save(store.all() + StoredReminder(id, text, atMillis, repeat))
        ReminderEntry(id, text, atMillis, repeat)
    }

    override suspend fun list(): List<ReminderEntry> = store.all().filter { it.repeat != "none" || it.atMillis > System.currentTimeMillis() - 60_000 }
        .map { ReminderEntry(it.id, it.text, it.atMillis, it.repeat) }

    override suspend fun cancel(id: String): Boolean {
        val all = store.all()
        if (all.none { it.id == id }) return false
        WorkManager.getInstance(ctx).cancelUniqueWork("reminder-$id")
        WorkManager.getInstance(ctx).cancelAllWorkByTag("reminder-$id")
        store.save(all.filter { it.id != id })
        return true
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Notifications.ensureChannels(applicationContext)
        val text = inputData.getString("text") ?: return Result.success()
        Notifications.post(applicationContext, Notifications.CH_REMINDERS, (inputData.getString("id") ?: text).hashCode(), "Reminder", text)
        return Result.success()
    }
}

// ---------------------------------------------------------------- files in user-picked folders (SAF)

/**
 * Only folders the user granted with ACTION_OPEN_DOCUMENT_TREE are reachable (persisted URI permissions). File ids are
 * document URIs; the model never sees or chooses arbitrary paths.
 */
class SafFilesPort(private val ctx: Context) : FilesPort {
    private val resolver get() = ctx.contentResolver

    override suspend fun folders(): List<FolderGrant> = resolver.persistedUriPermissions.filter { it.isReadPermission }.map { p ->
        val docId = DocumentsContract.getTreeDocumentId(p.uri)
        FolderGrant(DocumentsContract.buildDocumentUriUsingTree(p.uri, docId).toString(), docId.substringAfterLast(':').ifBlank { docId })
    }

    private fun treeFor(doc: Uri): Uri? {
        val s = doc.toString()
        return resolver.persistedUriPermissions.map { it.uri }.firstOrNull { s.startsWith(it.toString()) }
    }

    private fun checkGranted(id: String): Uri {
        val u = Uri.parse(id)
        if (treeFor(u) == null) throw PhoneActionException("That file is outside the folders you granted (Settings → Phone actions → Files).", PhoneGroup.FILES)
        return u
    }

    private val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    private fun children(folder: Uri, folderName: String): List<FileEntry> {
        val tree = treeFor(folder) ?: return emptyList()
        val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder))
        val out = ArrayList<FileEntry>()
        resolver.query(kids, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0); val mime = c.getString(2) ?: ""
                out += FileEntry(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), c.getString(1) ?: "", folderName, mime,
                    if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4), mime == DocumentsContract.Document.MIME_TYPE_DIR)
            }
        }
        return out
    }

    override suspend fun list(folderId: String, limit: Int) = withContext(Dispatchers.IO) {
        val u = checkGranted(folderId); children(u, DocumentsContract.getDocumentId(u).substringAfterLast('/')).take(limit)
    }

    override suspend fun search(query: String, folderId: String?, limit: Int): List<FileEntry> = withContext(Dispatchers.IO) {
        val roots = if (folderId != null) listOf(checkGranted(folderId)) else folders().map { Uri.parse(it.id) }
        if (roots.isEmpty()) throw PhoneActionException("No folders granted yet: Settings → Phone actions → Files → Add folder.", PhoneGroup.FILES)
        val q = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<FileEntry>()
        val queue = ArrayDeque(roots.map { it to 0 })
        var visited = 0
        while (queue.isNotEmpty() && out.size < limit && visited < 2000) {
            val (folder, depth) = queue.removeFirst()
            for (e in children(folder, DocumentsContract.getDocumentId(folder).substringAfterLast('/'))) {
                visited++
                if (e.isFolder && depth < 6) queue.addLast(Uri.parse(e.id) to depth + 1)
                if (q.all { e.name.lowercase().contains(it) }) out += e
            }
        }
        out.take(limit)
    }

    override suspend fun readText(fileId: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        val u = checkGranted(fileId)
        val type = resolver.getType(u) ?: ""
        if (!(type.startsWith("text/") || type in setOf("application/json", "application/xml", "text/csv")))
            throw PhoneActionException("Only text files can be read directly ($type). Import PDFs in Docs to search them.")
        resolver.openInputStream(u)?.use { it.bufferedReader().readText().take(maxChars) } ?: ""
    }

    private fun entryOf(u: Uri, folder: String): FileEntry {
        resolver.query(u, cols, null, null, null)?.use { c -> if (c.moveToFirst()) {
            val mime = c.getString(2) ?: ""
            return FileEntry(u.toString(), c.getString(1) ?: "", folder, mime, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4), mime == DocumentsContract.Document.MIME_TYPE_DIR)
        } }
        throw PhoneActionException("File not found")
    }

    override suspend fun writeText(folderId: String, name: String, text: String) = withContext(Dispatchers.IO) {
        val parent = checkGranted(folderId)
        val mime = when { name.endsWith(".md") -> "text/markdown"; name.endsWith(".csv") -> "text/csv"; name.endsWith(".json") -> "application/json"; else -> "text/plain" }
        val u = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw PhoneActionException("Could not create $name")
        resolver.openOutputStream(u, "wt")?.use { it.write(text.toByteArray()) }
        entryOf(u, folderId)
    }

    override suspend fun createFolder(parentId: String, name: String) = withContext(Dispatchers.IO) {
        val parent = checkGranted(parentId)
        val u = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: throw PhoneActionException("Could not create folder")
        entryOf(u, parentId)
    }

    override suspend fun move(fileId: String, targetFolderId: String) = withContext(Dispatchers.IO) {
        val f = checkGranted(fileId); val t = checkGranted(targetFolderId)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeFor(f)!!, DocumentsContract.getDocumentId(f).substringBeforeLast('/'))
        val moved = runCatching { DocumentsContract.moveDocument(resolver, f, parent, t) }.getOrNull()
            ?: throw PhoneActionException("This storage provider cannot move files; try copy_file instead")
        entryOf(moved, targetFolderId)
    }

    override suspend fun rename(fileId: String, newName: String) = withContext(Dispatchers.IO) {
        val f = checkGranted(fileId)
        val r = DocumentsContract.renameDocument(resolver, f, newName) ?: throw PhoneActionException("Could not rename")
        entryOf(r, "")
    }

    override suspend fun copy(fileId: String, targetFolderId: String) = withContext(Dispatchers.IO) {
        val f = checkGranted(fileId); val t = checkGranted(targetFolderId)
        val src = entryOf(f, "")
        val dest = DocumentsContract.createDocument(resolver, t, src.mime.ifBlank { "application/octet-stream" }, src.name) ?: throw PhoneActionException("Could not copy")
        resolver.openInputStream(f)?.use { i -> resolver.openOutputStream(dest)?.use { o -> i.copyTo(o) } }
        entryOf(dest, targetFolderId)
    }

    companion object {
        fun persist(ctx: Context, tree: Uri) {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        fun revoke(ctx: Context, tree: Uri) = runCatching {
            ctx.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
}

// ---------------------------------------------------------------- notifications (opt-in listener, memory only)

class NotificationCaptureService : NotificationListenerService() {
    override fun onListenerConnected() { connected = true; runCatching { com.offlineagent.app.onyx.apps.AppBridgeNotifications.seed(this, activeNotifications) } }
    override fun onListenerDisconnected() { connected = false }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { sbn?.let { com.offlineagent.app.onyx.apps.AppBridgeNotifications.removed(it) } } // Phase 6
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        com.offlineagent.app.onyx.apps.AppBridgeNotifications.posted(this, n) // Phase 6 AppBridge (actions/replies, memory only)
        if (n.packageName == packageName || n.isOngoing) return
        val ex = n.notification.extras
        val title = ex.getCharSequence("android.title")?.toString() ?: ""
        val text = (ex.getCharSequence("android.bigText") ?: ex.getCharSequence("android.text"))?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(n.packageName, 0)).toString() }.getOrDefault(n.packageName)
        buffer.addFirst(NotificationEntry(label, title, text, n.postTime))
        while (buffer.size > MAX) buffer.pollLast()
    }

    companion object {
        const val MAX = 200
        /** Kept in memory only (never written to disk); cleared when the process dies. */
        val buffer = ConcurrentLinkedDeque<NotificationEntry>()
        @Volatile var connected = false

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, NotificationCaptureService::class.java).flattenToString())
        }
    }
}

class AndroidNotificationsPort(private val ctx: Context) : NotificationsPort {
    override val enabled: Boolean get() = NotificationCaptureService.isEnabled(ctx)
    override suspend fun recent(limit: Int, app: String?): List<NotificationEntry> {
        if (!enabled) throw PhoneActionException("Notification access is off: Settings → Phone actions → Read notifications.", PhoneGroup.NOTIFICATIONS)
        return NotificationCaptureService.buffer.filter { app == null || it.app.contains(app, true) }.take(limit)
    }
}
