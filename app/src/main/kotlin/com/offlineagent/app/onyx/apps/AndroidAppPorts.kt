package com.offlineagent.app.onyx.apps

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.offlineagent.app.phone.ActivityLauncher
import com.offlineagent.app.phone.NotificationCaptureService
import com.offlineagent.core.apps.AppIntent
import com.offlineagent.core.apps.AppLauncherPort
import com.offlineagent.core.apps.ClipText
import com.offlineagent.core.apps.ClipboardPort
import com.offlineagent.core.apps.FileResolver
import com.offlineagent.core.apps.IntentProbe
import com.offlineagent.core.apps.NotificationActuator
import com.offlineagent.core.apps.NotificationStore
import com.offlineagent.core.apps.PackageSource
import com.offlineagent.core.apps.RawAction
import com.offlineagent.core.apps.RawApp
import com.offlineagent.core.apps.RawNotification
import com.offlineagent.core.apps.RawRemoteInput
import com.offlineagent.core.apps.SharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Android implementations of the :core:apps ports. Everything here is thin; the logic is in :core:apps (JVM-tested). */

// ---------------------------------------------------------------- AppIntent → android.content.Intent

fun AppIntent.toAndroid(ctx: Context): Intent? {
    launchPackage?.let { p -> return ctx.packageManager.getLaunchIntentForPackage(p)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    val i = Intent(action)
    when {
        data != null && type != null -> i.setDataAndType(Uri.parse(data), type)
        data != null -> i.data = Uri.parse(data)
        type != null -> i.type = type
    }
    pkg?.let { i.setPackage(it) }
    categories.forEach { i.addCategory(it) }
    extras.forEach { (k, v) -> i.putExtra(k, v) }
    if (streams.isNotEmpty()) {
        val uris = streams.map { Uri.parse(it.uri) }
        if (action == AppIntent.ACTION_SEND_MULTIPLE) i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) else i.putExtra(Intent.EXTRA_STREAM, uris.first())
        i.clipData = ClipData.newRawUri(streams.first().name, uris.first()).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val out = chooserTitle?.let { Intent.createChooser(i, it) } ?: i
    return out.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

// ---------------------------------------------------------------- PackageManager

class AndroidPackageSource(private val ctx: Context) : PackageSource {
    private val pm get() = ctx.packageManager

    @Suppress("DEPRECATION")
    override fun launchableApps(): List<RawApp> {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map { ri ->
            val ai = ri.activityInfo.applicationInfo
            val vc = runCatching { val pi = pm.getPackageInfo(ri.activityInfo.packageName, 0); if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong() }.getOrDefault(0L)
            RawApp(ri.activityInfo.packageName, ri.loadLabel(pm).toString(), vc, (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }
    }

    @Suppress("DEPRECATION")
    override fun handlers(probe: IntentProbe): Set<String> {
        val i = Intent(probe.action)
        when {
            probe.scheme != null && probe.mimeType != null -> i.setDataAndType(Uri.parse("${probe.scheme}:"), probe.mimeType)
            probe.scheme == "https" -> i.data = Uri.parse("https://example.com/")
            probe.scheme == "geo" -> i.data = Uri.parse("geo:0,0?q=x")
            probe.scheme != null -> i.data = Uri.parse("${probe.scheme}:x")
            probe.mimeType != null -> i.type = probe.mimeType
        }
        probe.category?.let { i.addCategory(it) }
        // MATCH_ALL would include every browser for https; default matching is enough for "can this app do it".
        return pm.queryIntentActivities(i, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.toSet()
    }

    @Suppress("DEPRECATION")
    override fun resolves(intent: AppIntent): Boolean {
        intent.launchPackage?.let { return pm.getLaunchIntentForPackage(it) != null }
        val i = intent.copy(chooserTitle = null, streams = emptyList()).toAndroid(ctx) ?: return false
        return pm.queryIntentActivities(i, 0).isNotEmpty()
    }
}

/** Rebuilds the catalogue when apps are installed/removed/updated (debounced by the caller). */
class PackageChangeReceiver(private val onChange: (String?) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { onChange(intent.data?.schemeSpecificPart) }

    fun register(ctx: Context) {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        ContextCompat.registerReceiver(ctx, this, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

// ---------------------------------------------------------------- launcher

class AndroidAppLauncher(private val ctx: Context, private val launcher: ActivityLauncher) : AppLauncherPort {
    override suspend fun start(intent: AppIntent): String {
        val i = intent.toAndroid(ctx) ?: throw IllegalStateException("That app cannot be opened directly.")
        launcher.launch(i, intent.pkg ?: intent.launchPackage ?: intent.action)
        return "opened"
    }
}

// ---------------------------------------------------------------- clipboard

class AndroidClipboard(private val ctx: Context) : ClipboardPort {
    private val cm get() = ctx.getSystemService(ClipboardManager::class.java)

    override suspend fun read(): ClipText? = withContext(Dispatchers.Main) {
        // Android 10+: only the foreground app (or the default IME) can read; otherwise this returns null.
        val clip = runCatching { cm.primaryClip }.getOrNull() ?: return@withContext null
        if (clip.itemCount == 0) return@withContext null
        val desc = clip.description
        val sensitive = Build.VERSION.SDK_INT >= 33 && desc.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        val text = clip.getItemAt(0).coerceToText(ctx)?.toString().orEmpty()
        if (text.isBlank()) null else ClipText(text, null, sensitive)
    }

    override suspend fun write(text: String, sensitive: Boolean) = withContext(Dispatchers.Main) {
        val clip = ClipData.newPlainText("ONYX", text)
        if (sensitive && Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
    }
}

// ---------------------------------------------------------------- files ONYX may share

/** Only files ONYX itself wrote under cache/exports or files/attachments (the FileProvider roots). */
class OnyxFileResolver(private val ctx: Context) : FileResolver {
    override fun resolve(ref: String): SharedFile? {
        val roots = listOf(File(ctx.cacheDir, "exports"), File(ctx.filesDir, "attachments")).map { it.canonicalFile }
        val candidates = if (ref.startsWith("/")) listOf(File(ref)) else roots.map { File(it.parentFile, ref) } + roots.map { File(it, ref) }
        val f = candidates.map { runCatching { it.canonicalFile }.getOrNull() }.firstOrNull { c -> c != null && c.isFile && roots.any { r -> c.path.startsWith(r.path + File.separator) } }
            ?: return null
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        return SharedFile(ref, uri.toString(), mime, f.name, f.length())
    }
}

// ---------------------------------------------------------------- notifications (memory only)

/**
 * Holds the live StatusBarNotifications the listener has seen (for their PendingIntents / RemoteInputs) next to the
 * parsed [NotificationStore]. Fed by [NotificationCaptureService] (one-line hooks). Nothing is written to disk.
 */
object AppBridgeNotifications {
    @Volatile var store: NotificationStore? = null
    private val live = ConcurrentHashMap<String, StatusBarNotification>()

    fun posted(ctx: Context, sbn: StatusBarNotification) {
        val s = store ?: runCatching { (ctx.applicationContext as com.offlineagent.app.OfflineAgentApp).onyx.appBridge; store }.getOrNull() ?: return
        runCatching {
            val raw = toRaw(ctx, sbn)
            if (s.posted(raw) != null) live[sbn.key] = sbn else live.remove(sbn.key)
            while (live.size > 150) live.keys.firstOrNull()?.let { if (s.list(null, 200).none { r -> r.key == it }) live.remove(it) else return@runCatching }
        }
    }

    fun removed(sbn: StatusBarNotification) { live.remove(sbn.key); store?.removed(sbn.key) }
    fun seed(ctx: Context, all: Array<StatusBarNotification>?) { all?.sortedBy { it.postTime }?.forEach { posted(ctx, it) } }

    private fun toRaw(ctx: Context, sbn: StatusBarNotification): RawNotification {
        val n = sbn.notification
        val ex = n.extras
        val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
        @Suppress("DEPRECATION")
        val msgs = (ex.getParcelableArray(Notification.EXTRA_MESSAGES) ?: emptyArray()).mapNotNull { p ->
            (p as? Bundle)?.let { b -> (b.getCharSequence("sender")?.toString() ?: "") to (b.getCharSequence("text")?.toString() ?: "") }
        }.filter { it.second.isNotBlank() }
        val actions = (n.actions ?: emptyArray()).map { a ->
            RawAction(a.title?.toString() ?: "", (a.remoteInputs ?: emptyArray()).map { r ->
                RawRemoteInput(r.resultKey, r.label?.toString() ?: "", r.allowFreeFormInput, r.choices?.map { it.toString() } ?: emptyList())
            }, if (Build.VERSION.SDK_INT >= 28) a.semanticAction else 0, Build.VERSION.SDK_INT >= 29 && a.isContextual)
        }
        return RawNotification(
            key = sbn.key, pkg = sbn.packageName, appLabel = label, postedAt = sbn.postTime,
            title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "",
            text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
            bigText = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "",
            subText = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "",
            textLines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() } ?: emptyList(),
            messages = msgs,
            conversationTitle = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString() ?: "",
            category = n.category ?: "",
            ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            groupSummary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
            actions = actions,
        )
    }

    class Actuator(private val ctx: Context) : NotificationActuator {
        override val enabled: Boolean get() = NotificationCaptureService.isEnabled(ctx)

        private fun action(key: String, index: Int): Notification.Action {
            val sbn = live[key] ?: throw IllegalStateException("That notification is gone (dismissed or replaced). Call notif_list again.")
            return sbn.notification.actions?.getOrNull(index) ?: throw IllegalStateException("That button no longer exists.")
        }

        override suspend fun invoke(key: String, actionIndex: Int) {
            val a = action(key, actionIndex)
            try { a.actionIntent.send() } catch (e: PendingIntent.CanceledException) { throw IllegalStateException("The app cancelled that action.") }
        }

        override suspend fun reply(key: String, actionIndex: Int, resultKey: String, text: String) {
            val a = action(key, actionIndex)
            val inputs = a.remoteInputs?.filter { it.resultKey == resultKey }?.toTypedArray()
            if (inputs.isNullOrEmpty()) throw IllegalStateException("That notification no longer accepts replies.")
            val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, fill, Bundle().apply { putCharSequence(resultKey, text) })
            if (Build.VERSION.SDK_INT >= 28) RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
            try { a.actionIntent.send(ctx, 0, fill) } catch (e: PendingIntent.CanceledException) { throw IllegalStateException("The app cancelled the reply.") }
        }
    }
}
