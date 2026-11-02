package com.offlineagent.app.onyx

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.offlineagent.app.phone.Notifications
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.int
import com.offlineagent.core.runtime.tools.okJson
import com.offlineagent.core.runtime.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Phase 5 device tools on real Android APIs (all offline). They join the shared tool registry, so both the old agent
 * loop and the new multi-agent orchestrator can use them, with the same approval policy.
 */
class Phase5Tools(private val ctx: Context, private val openIntent: suspend (Intent) -> Unit) {
    private fun tool(spec: ToolSpec, run: suspend (JsonObject) -> ToolResult) = object : Tool {
        override val spec = spec
        override suspend fun execute(args: JsonObject, ctx: ToolContext) = try { run(args) } catch (e: SecurityException) {
            ToolResult.Error("Android blocked it: ${e.message}", retryable = false)
        } catch (e: Exception) { ToolResult.Error(e.message ?: e.toString()) }
    }
    private fun obj(vararg p: Pair<String, Schema>) = Schema.Obj(p.toList())
    private fun s(max: Int, min: Int = 0, d: String? = null) = Schema.Str(d, max, min)
    private fun n(min: Double, max: Double) = Schema.Num(min = min, max = max, integer = true)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun all(): List<Tool> = listOf(exactAlarm, brightness, postNotification, saveToDownloads, calendarDirect)

    /** AlarmManager exact alarm when allowed (SCHEDULE_EXACT_ALARM), otherwise an inexact one — and it says which. */
    val exactAlarm = tool(ToolSpec("schedule_exact_reminder", "Notify the user at an exact time (AlarmManager, works offline and in Doze).",
        obj("text" to s(200, 1), "time" to s(5, 0, "HH:MM 24h today/tomorrow, or empty"), "in_minutes" to n(0.0, 43_200.0)),
        Permission.DEVICE_CONTROL, "Schedules a notification alarm.")) { a ->
        val zone = ZoneId.systemDefault()
        val at = a.str("time").takeIf { it.matches(Regex("\\d{1,2}:\\d{2}")) }?.let { t ->
            var dt = LocalDateTime.of(LocalDate.now(zone), LocalTime.parse(t.padStart(5, '0')))
            if (dt.isBefore(LocalDateTime.now(zone))) dt = dt.plusDays(1)
            dt.atZone(zone).toInstant().toEpochMilli()
        } ?: (System.currentTimeMillis() + a.int("in_minutes", 0).coerceAtLeast(1) * 60_000L)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val id = (at / 1000).toInt()
        val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, ReminderAlarmReceiver::class.java).putExtra("text", a.str("text")).putExtra("id", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        okJson("scheduled_for" to java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime().toString(), "exact" to exact,
            "note" to if (exact) "exact alarm" else "Exact alarms are not allowed for ONYX (Settings → Apps → Special access → Alarms & reminders); scheduled inexactly (may be a few minutes late).")
    }

    /** Screen brightness via Settings.System (needs the user to grant "Modify system settings"). */
    val brightness = tool(ToolSpec("set_brightness", "Set screen brightness 0-100% (turns off auto-brightness).", obj("percent" to n(0.0, 100.0)),
        Permission.DEVICE_CONTROL, "Changes screen brightness.")) { a ->
        if (!Settings.System.canWrite(ctx)) {
            openIntent(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            return@tool ToolResult.Error("ONYX needs \"Modify system settings\" permission; I opened that screen. Allow it and ask again.", retryable = false)
        }
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, (a.int("percent", 50) * 255 / 100).coerceIn(1, 255))
        okJson("brightness_percent" to a.int("percent", 50))
    }

    val postNotification = tool(ToolSpec("post_notification", "Show a notification on this phone (e.g. a summary to read later).",
        obj("title" to s(80, 1), "text" to s(1000, 1)), Permission.WRITE_LOCAL, "Posts a notification.")) { a ->
        Notifications.post(ctx, Notifications.CH_REMINDERS, (System.currentTimeMillis() % 100000).toInt(), a.str("title"), a.str("text"))
        okJson("posted" to true)
    }

    /** MediaStore Downloads (API 29+, no storage permission for the app's own files). */
    val saveToDownloads = tool(ToolSpec("save_to_downloads", "Save text as a file in the phone's Downloads folder.",
        obj("name" to s(100, 1), "text" to s(50_000, 1)), Permission.DEVICE_CONTROL, "Creates a file in Downloads.")) { a ->
        if (Build.VERSION.SDK_INT < 29) return@tool ToolResult.Error("Needs Android 10+", retryable = false)
        val name = a.str("name").replace(Regex("[\\\\/:*?\"<>|]"), "_").let { if ('.' in it) it else "$it.txt" }
        val uri = withContext(Dispatchers.IO) {
            val cv = ContentValues().apply { put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, "text/plain"); put(MediaStore.Downloads.IS_PENDING, 1) }
            val u = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("MediaStore refused the file")
            ctx.contentResolver.openOutputStream(u)!!.use { it.write(a.str("text").toByteArray()) }
            ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            u
        }
        okJson("saved" to "Downloads/$name", "uri" to uri.toString())
    }

    /** Inserts directly with CalendarContract (WRITE_CALENDAR, opt-in); falls back to explaining the insert intent tool. */
    val calendarDirect = tool(ToolSpec("add_calendar_event_direct", "Add an event straight into the phone calendar (needs calendar permission).",
        obj("title" to s(200, 1), "date" to s(10, 10, "YYYY-MM-DD"), "start" to s(5, 4, "HH:MM"), "duration_minutes" to n(5.0, 1440.0), "location" to s(200)),
        Permission.DEVICE_CONTROL, "Writes an event into your calendar.")) { a ->
        if (!granted(Manifest.permission.WRITE_CALENDAR)) return@tool ToolResult.Error("Calendar write permission is off (Agents → Permissions). I can use add_calendar_event instead, which opens the calendar app.", retryable = false)
        val zone = ZoneId.systemDefault()
        val start = LocalDateTime.of(LocalDate.parse(a.str("date")), LocalTime.parse(a.str("start").padStart(5, '0'))).atZone(zone).toInstant().toEpochMilli()
        val end = start + a.int("duration_minutes", 60) * 60_000L
        val calId = withContext(Dispatchers.IO) {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${CalendarContract.Calendars.VISIBLE} = 1",
                null, "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } ?: return@tool ToolResult.Error("No writable calendar on this phone", retryable = false)
        val uri = withContext(Dispatchers.IO) {
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, a.str("title"))
                put(CalendarContract.Events.DTSTART, start); put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_LOCATION, a.str("location")); put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            })
        } ?: return@tool ToolResult.Error("Calendar refused the event")
        okJson("event_id" to (uri.lastPathSegment ?: ""), "title" to a.str("title"))
    }
}

/** Fires the exact reminder notification. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.ensureChannels(context)
        Notifications.post(context, Notifications.CH_REMINDERS, intent.getIntExtra("id", 1), "Reminder", intent.getStringExtra("text") ?: "Reminder")
    }
}
