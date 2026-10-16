package com.offlineagent.app.phone

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.offlineagent.app.data.IntentRequest
import com.offlineagent.app.data.formatBytes
import com.offlineagent.core.data.MiscDao
import com.offlineagent.core.data.NoteEntity
import com.offlineagent.core.runtime.phone.AppEntry
import com.offlineagent.core.runtime.phone.AppsPort
import com.offlineagent.core.runtime.phone.ClipboardPort
import com.offlineagent.core.runtime.phone.CommsPort
import com.offlineagent.core.runtime.phone.ContactEntry
import com.offlineagent.core.runtime.phone.DevicePort
import com.offlineagent.core.runtime.phone.EventEntry
import com.offlineagent.core.runtime.phone.NotesStorePort
import com.offlineagent.core.runtime.phone.PhoneActionException
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.PimPort
import com.offlineagent.core.runtime.rag.KeywordScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Starts activities on behalf of tools. Activities must be started from the foreground UI on Android 10+, so requests go
 * through MainActivity (collected from [requests]); tools then report "opened". When the app is in the background the
 * request still reaches the activity once the user returns.
 */
class ActivityLauncher {
    private val _requests = MutableSharedFlow<IntentRequest>(extraBufferCapacity = 8)
    val requests: SharedFlow<IntentRequest> = _requests
    suspend fun launch(intent: Intent, what: String) { _requests.emit(IntentRequest.Raw(intent, what)) }
}

private fun Context.has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

class AndroidDevicePort(private val ctx: Context, private val launcher: ActivityLauncher, private val onPhoto: suspend () -> String) : DevicePort {
    override suspend fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply {
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { d -> (d % 7) + 1 })) // 1=Mon..7=Sun -> Calendar.MONDAY=2..SUNDAY=1
            }
        launcher.launch(i, "Clock app")
        return "Alarm set for %02d:%02d%s in your Clock app".format(hour, minute, if (label.isNotBlank()) " ($label)" else "")
    }

    override suspend fun setTimer(seconds: Int, label: String): String {
        launcher.launch(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label) }, "Clock app")
        return "Timer started for ${seconds / 60} min ${seconds % 60} s"
    }

    override suspend fun flashlight(on: Boolean): String = withContext(Dispatchers.Default) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw PhoneActionException("This phone has no flashlight", PhoneGroup.DEVICE_SETTINGS)
        cm.setTorchMode(id, on)
        if (on) "Flashlight on" else "Flashlight off"
    }

    private fun stream(s: String) = when (s) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION; else -> AudioManager.STREAM_MUSIC }

    override suspend fun setVolume(stream: String, percent: Int): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val st = stream(stream)
        val max = am.getStreamMaxVolume(st)
        try { am.setStreamVolume(st, (max * percent.coerceIn(0, 100) + 50) / 100, AudioManager.FLAG_SHOW_UI) }
        catch (e: SecurityException) { throw PhoneActionException("Android needs Do Not Disturb access to change that volume (Settings → Phone actions).", PhoneGroup.DEVICE_SETTINGS) }
        return "${stream.replaceFirstChar { it.uppercase() }} volume set to $percent%"
    }

    override suspend fun setRinger(mode: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val m = when (mode) { "silent" -> AudioManager.RINGER_MODE_SILENT; "vibrate" -> AudioManager.RINGER_MODE_VIBRATE; else -> AudioManager.RINGER_MODE_NORMAL }
        if (m == AudioManager.RINGER_MODE_SILENT && !nm.isNotificationPolicyAccessGranted)
            throw PhoneActionException("Silent mode needs Do Not Disturb access: Settings → Phone actions → Flashlight, volume… → Grant DND access.", PhoneGroup.DEVICE_SETTINGS)
        am.ringerMode = m
        return "Ringer set to $mode"
    }

    override suspend fun setDoNotDisturb(on: Boolean): String {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            launcher.launch(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), "DND access settings")
            throw PhoneActionException("Do Not Disturb access is not granted yet — I opened the setting; allow Offline Agent there and ask again.", PhoneGroup.DEVICE_SETTINGS)
        }
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return if (on) "Do Not Disturb on (priority only)" else "Do Not Disturb off"
    }

    override suspend fun openPanel(panel: String): String {
        val action = when (panel) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "internet" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            "nfc" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            "volume" -> if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "dnd" -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        launcher.launch(Intent(action), "$panel settings")
        return "Opened the $panel panel — toggle it there (Android does not let apps switch it directly)"
    }

    override suspend fun deviceInfo(): JsonObject = withContext(Dispatchers.Default) {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val stat = StatFs(Environment.getDataDirectory().path)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        buildJsonObject {
            put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            put("charging", bm.isCharging)
            put("storage_free", formatBytes(stat.availableBytes)); put("storage_total", formatBytes(stat.totalBytes))
            put("ram_total", formatBytes(mi.totalMem)); put("ram_available", formatBytes(mi.availMem))
            put("model", "${Build.MANUFACTURER} ${Build.MODEL}"); put("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        }
    }

    override suspend fun takePhoto(): String = onPhoto()
}

class AndroidAppsPort(private val ctx: Context, private val launcher: ActivityLauncher) : AppsPort {
    private fun launchable(): List<AppEntry> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        return pm.queryIntentActivities(i, 0).map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }.distinctBy { it.packageName }.sortedBy { it.label.lowercase() }
    }

    override suspend fun listApps(query: String, limit: Int): List<AppEntry> = withContext(Dispatchers.Default) {
        val all = launchable()
        if (query.isBlank()) return@withContext all.take(limit)
        val q = query.lowercase()
        val direct = all.filter { it.label.lowercase().contains(q) || it.packageName.contains(q) }
        (direct.ifEmpty {
            val scores = KeywordScorer.score(query, all.map { it.label })
            all.indices.filter { scores[it] > 0 }.sortedByDescending { scores[it] }.map { all[it] }
        }).take(limit)
    }

    override suspend fun openApp(nameOrPackage: String): String {
        val match = listApps(nameOrPackage, 1).firstOrNull() ?: throw PhoneActionException("No installed app matches \"$nameOrPackage\"")
        val i = ctx.packageManager.getLaunchIntentForPackage(match.packageName) ?: throw PhoneActionException("${match.label} cannot be opened directly")
        launcher.launch(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), match.label)
        return "Opened ${match.label}"
    }

    override suspend fun openLink(uri: String): String {
        val u = Uri.parse(uri.trim())
        val scheme = u.scheme?.lowercase() ?: throw PhoneActionException("Not a link: $uri")
        // Never hand file:// or content:// from the model to other apps; javascript: makes no sense here.
        if (scheme in setOf("file", "content", "javascript", "intent")) throw PhoneActionException("Links with $scheme: are not allowed")
        launcher.launch(Intent(Intent.ACTION_VIEW, u).addCategory(Intent.CATEGORY_BROWSABLE), uri)
        return "Opened $uri in the app that handles it"
    }
}

class AndroidCommsPort(private val launcher: ActivityLauncher) : CommsPort {
    private fun digits(s: String) = s.filter { it.isDigit() || it == '+' }
    override suspend fun draftSms(to: String, body: String): String {
        launcher.launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(digits(to)))).putExtra("sms_body", body), "SMS app")
        return "SMS draft opened${if (to.isNotBlank()) " to $to" else ""} — press Send to send it"
    }
    override suspend fun draftWhatsApp(to: String, text: String): String {
        val num = digits(to).removePrefix("+")
        val uri = Uri.parse("https://wa.me/" + num + "?text=" + Uri.encode(text))
        launcher.launch(Intent(Intent.ACTION_VIEW, uri), "WhatsApp")
        return "WhatsApp draft opened — press Send in WhatsApp"
    }
    override suspend fun draftEmail(to: String, subject: String, body: String): String {
        launcher.launch(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to))).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body), "email app")
        return "Email draft opened — press Send in your email app"
    }
    override suspend fun dial(number: String): String {
        launcher.launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(digits(number)))), "dialer")
        return "Dialer opened with $number — press Call to call"
    }
}

class AndroidPimPort(private val ctx: Context, private val launcher: ActivityLauncher) : PimPort {
    override suspend fun searchContacts(query: String, limit: Int): List<ContactEntry> = withContext(Dispatchers.IO) {
        if (!ctx.has(Manifest.permission.READ_CONTACTS)) throw PhoneActionException("Contacts permission is off: Settings → Phone actions → Calendar & contacts.", PhoneGroup.CALENDAR_CONTACTS)
        val out = LinkedHashMap<Long, ContactEntry>()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query))
        ctx.contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < limit) out[c.getLong(0)] = ContactEntry(c.getString(1) ?: "", emptyList(), emptyList())
        }
        out.mapValues { (id, e) ->
            val phones = ArrayList<String>(); val emails = ArrayList<String>()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { c -> while (c.moveToNext()) c.getString(0)?.let { phones += it } }
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Email.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID}=?", arrayOf(id.toString()), null)?.use { c -> while (c.moveToNext()) c.getString(0)?.let { emails += it } }
            e.copy(phones = phones.distinct(), emails = emails.distinct())
        }.values.toList()
    }

    override suspend fun events(fromMillis: Long, toMillis: Long, limit: Int): List<EventEntry> = withContext(Dispatchers.IO) {
        if (!ctx.has(Manifest.permission.READ_CALENDAR)) throw PhoneActionException("Calendar permission is off: Settings → Phone actions → Calendar & contacts.", PhoneGroup.CALENDAR_CONTACTS)
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, fromMillis); ContentUris.appendId(b, toMillis)
        val out = ArrayList<EventEntry>()
        ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME), null, null,
            "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && out.size < limit) out += EventEntry(c.getString(0) ?: "(no title)", c.getLong(1), c.getLong(2), c.getString(3) ?: "", c.getInt(4) == 1, c.getString(5) ?: "")
        }
        out
    }

    override suspend fun prepareEvent(title: String, startMillis: Long, endMillis: Long, location: String, description: String): String {
        launcher.launch(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, location).putExtra(CalendarContract.Events.DESCRIPTION, description)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis), "calendar app")
        return "Calendar app opened with \"$title\" — tap Save there"
    }
}

class AndroidClipboardPort(private val ctx: Context) : ClipboardPort {
    override suspend fun read(): String? = withContext(Dispatchers.Main) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }
    override suspend fun write(text: String) = withContext(Dispatchers.Main) {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Offline Agent", text))
    }
}

class RoomNotesStore(private val dao: MiscDao) : NotesStorePort {
    override suspend fun search(query: String, limit: Int): List<Pair<String, String>> {
        val all = dao.notes()
        if (query.isBlank()) return all.take(limit).map { it.title to it.body }
        val s = KeywordScorer.score(query, all.map { it.title + " " + it.body })
        return all.indices.filter { s[it] > 0 }.sortedByDescending { s[it] }.take(limit).map { all[it].title to all[it].body }
    }
    override suspend fun create(title: String, body: String) = dao.insertNote(NoteEntity(title = title, body = body, createdAt = System.currentTimeMillis()))
}
