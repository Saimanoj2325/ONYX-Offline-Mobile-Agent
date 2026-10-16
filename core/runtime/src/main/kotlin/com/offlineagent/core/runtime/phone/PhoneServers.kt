package com.offlineagent.core.runtime.phone

import com.offlineagent.core.runtime.mcp.CallToolResult
import com.offlineagent.core.runtime.mcp.McpServer
import com.offlineagent.core.runtime.mcp.McpTool
import com.offlineagent.core.runtime.mcp.META_PERMISSION
import com.offlineagent.core.runtime.mcp.META_SIDE_EFFECTS
import com.offlineagent.core.runtime.mcp.META_TIMEOUT
import com.offlineagent.core.runtime.mcp.McpResource
import com.offlineagent.core.runtime.mcp.ToolAnnotations
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.validate
import com.offlineagent.core.runtime.tools.Permission
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All app capabilities available to built-in servers. Any port may be null (feature unavailable on this build). */
data class PhonePorts(
    val device: DevicePort? = null,
    val reminders: RemindersPort? = null,
    val apps: AppsPort? = null,
    val comms: CommsPort? = null,
    val pim: PimPort? = null,
    val files: FilesPort? = null,
    val clipboard: ClipboardPort? = null,
    val notifications: NotificationsPort? = null,
    val notes: NotesStorePort? = null,
    val screen: ScreenPort? = null,
)

/**
 * The app's built-in, on-device MCP servers. Every phone capability is a typed MCP tool with a declared permission
 * class (read / sensitive read / device control / leaves the app / screen action) so the agent's policy engine applies
 * the same approval flow as for any other tool. Tools are grouped ([PhoneGroup]) for the Phone actions settings.
 */
class PhoneServers(private val ports: PhonePorts, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val groupOf = LinkedHashMap<String, PhoneGroup>()

    /** tool name -> group (for Settings and the policy's "disabled" set). */
    val toolGroups: Map<String, PhoneGroup> get() = groupOf

    private class Def(val server: McpServer, val group: PhoneGroup, val groups: MutableMap<String, PhoneGroup>)

    private fun Def.tool(
        name: String, description: String, input: Schema.Obj, permission: Permission, sideEffects: String = "none",
        timeoutMs: Long = 15_000, readOnly: Boolean = permission == Permission.READ_ONLY || permission == Permission.SENSITIVE_READ,
        handler: suspend (JsonObject) -> CallToolResult,
    ) {
        groups[name] = group
        server.tool(
            McpTool(name, description, input.toJsonSchema(), ToolAnnotations(readOnlyHint = readOnly, destructiveHint = false, openWorldHint = false),
                buildJsonObject { put(META_PERMISSION, permission.name); put(META_SIDE_EFFECTS, sideEffects); put(META_TIMEOUT, timeoutMs.toString()) }),
        ) { args ->
            val errs = input.validate(args, "arguments")
            if (errs.isNotEmpty()) return@tool McpServer.text("invalid arguments: " + errs.take(3).joinToString("; "), isError = true)
            try { handler(args) } catch (e: PhoneActionException) { McpServer.text(e.message ?: "not available", isError = true) }
        }
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: ""
    private fun JsonObject.i(k: String, d: Int) = (this[k] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: d
    private fun JsonObject.b(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
    private fun ok(msg: String) = McpServer.text(msg)
    private fun str(desc: String? = null, max: Int = 200, min: Int = 0, enum: List<String>? = null) = Schema.Str(desc, max, min, enum)
    private fun num(min: Double, max: Double, desc: String? = null) = Schema.Num(desc, min, max, integer = true)
    private fun obj(vararg p: Pair<String, Schema>) = Schema.Obj(p.toList())
    private fun need(p: Any?, group: PhoneGroup): Nothing? = if (p == null) throw PhoneActionException("${group.title} is not available in this build", group) else null

    private val fmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
    private fun time(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone()).format(fmt)

    /** "2026-10-17 18:30", "2026-10-17T18:30", "18:30" (today, or tomorrow if past) -> epoch millis. */
    fun parseWhen(s: String, now: LocalDateTime = LocalDateTime.now(zone())): Long? {
        val t = s.trim().replace('T', ' ')
        val dt = runCatching { LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) }.getOrNull()
            ?: runCatching {
                val lt = LocalTime.parse(t.padStart(5, '0'))
                val today = now.toLocalDate().atTime(lt)
                if (today.isAfter(now)) today else today.plusDays(1)
            }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atTime(9, 0) }.getOrNull()
            ?: return null
        return dt.atZone(zone()).toInstant().toEpochMilli()
    }

    fun build(): List<McpServer> = listOf(device(), apps(), comms(), pim(), files(), clipboardAndNotes(), notifications(), screen())

    // ------------------------------------------------------------ servers

    private fun device(): McpServer {
        val s = McpServer("phone-device", instructions = "Alarms, timers, reminders, torch, volume, ringer, DND, settings panels, device info, camera.")
        val d = ports.device; val r = ports.reminders
        Def(s, PhoneGroup.ALARMS, groupOf).apply {
            tool("set_alarm", "Set an alarm in the phone's Clock app.", obj("hour" to num(0.0, 23.0), "minute" to num(0.0, 59.0), "label" to str(max = 60),
                "days" to Schema.Arr(num(1.0, 7.0, "1=Mon … 7=Sun"), "repeat days, empty = once", maxItems = 7)),
                Permission.DEVICE_CONTROL, "Creates an alarm in your Clock app.") { a ->
                need(d, group); ok(d!!.setAlarm(a.i("hour", 7), a.i("minute", 0), a.s("label"), (a["days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() } ?: emptyList()))
            }
            tool("set_timer", "Start a countdown timer in the Clock app.", obj("seconds" to num(1.0, 86_400.0), "label" to str(max = 60)),
                Permission.DEVICE_CONTROL, "Starts a timer in your Clock app.") { a -> need(d, group); ok(d!!.setTimer(a.i("seconds", 60), a.s("label"))) }
            tool("set_reminder", "Remind the user with a notification at a time (on-device, works offline).",
                obj("text" to str(max = 200, min = 1), "when" to str("YYYY-MM-DD HH:MM or HH:MM", max = 20, min = 4), "repeat" to str(enum = listOf("none", "daily", "weekly"))),
                Permission.WRITE_LOCAL, "Schedules a reminder notification on this phone.") { a ->
                need(r, group)
                val at = parseWhen(a.s("when")) ?: return@tool McpServer.text("could not understand the time '${a.s("when")}' (use YYYY-MM-DD HH:MM)", true)
                val e = r!!.schedule(a.s("text"), at, a.s("repeat").ifBlank { "none" })
                ok("Reminder set for ${time(e.atMillis)} (id ${e.id})")
            }
            tool("list_reminders", "List upcoming reminders set in this app.", obj(), Permission.READ_ONLY) {
                need(r, group)
                McpServer.json(buildJsonObject { put("reminders", buildJsonArray { r!!.list().forEach { e -> add(buildJsonObject { put("id", e.id); put("text", e.text); put("when", time(e.atMillis)); put("repeat", e.repeat) }) } }) })
            }
            tool("cancel_reminder", "Cancel a reminder by id.", obj("id" to str(max = 64, min = 1)), Permission.WRITE_LOCAL, "Cancels a reminder.") { a ->
                need(r, group); ok(if (r!!.cancel(a.s("id"))) "cancelled" else "no reminder with that id")
            }
        }
        Def(s, PhoneGroup.DEVICE_SETTINGS, groupOf).apply {
            tool("flashlight", "Turn the torch on or off.", obj("on" to Schema.Bool()), Permission.DEVICE_CONTROL, "Switches the flashlight.") { a -> need(d, group); ok(d!!.flashlight(a.b("on"))) }
            tool("set_volume", "Set a volume stream to a percentage.", obj("stream" to str(enum = listOf("media", "ring", "alarm", "notification")), "percent" to num(0.0, 100.0)),
                Permission.DEVICE_CONTROL, "Changes the volume.") { a -> need(d, group); ok(d!!.setVolume(a.s("stream"), a.i("percent", 50))) }
            tool("set_ringer_mode", "Set the ringer to normal, vibrate or silent.", obj("mode" to str(enum = listOf("normal", "vibrate", "silent"))),
                Permission.DEVICE_CONTROL, "Changes the ringer mode.") { a -> need(d, group); ok(d!!.setRinger(a.s("mode"))) }
            tool("set_do_not_disturb", "Turn Do Not Disturb on or off (needs DND access).", obj("on" to Schema.Bool()),
                Permission.DEVICE_CONTROL, "Changes Do Not Disturb.") { a -> need(d, group); ok(d!!.setDoNotDisturb(a.b("on"))) }
            tool("open_settings_panel", "Open a quick-settings panel so the user can toggle it (apps cannot toggle Wi-Fi/Bluetooth/location directly).",
                obj("panel" to str(enum = listOf("wifi", "bluetooth", "location", "internet", "nfc", "volume", "battery", "display", "dnd"))),
                Permission.DEVICE_CONTROL, "Opens a system settings panel.") { a -> need(d, group); ok(d!!.openPanel(a.s("panel"))) }
        }
        Def(s, PhoneGroup.DEVICE_INFO, groupOf).apply {
            tool("device_info", "Battery level and charging state, free/total storage, RAM, phone model and Android version.", obj(), Permission.READ_ONLY) {
                need(d, group); McpServer.json(d!!.deviceInfo())
            }
        }
        Def(s, PhoneGroup.CAMERA, groupOf).apply {
            tool("take_photo", "Open the camera to take a photo that is attached to this chat.", obj(), Permission.DEVICE_CONTROL, "Opens your camera app.", timeoutMs = 120_000) {
                need(d, group); ok(d!!.takePhoto())
            }
        }
        return s
    }

    private fun apps(): McpServer {
        val s = McpServer("phone-apps", instructions = "Find and open installed apps and deep links.")
        val a = ports.apps
        Def(s, PhoneGroup.APPS_LINKS, groupOf).apply {
            tool("list_apps", "Find installed apps by name.", obj("query" to str(max = 60), "limit" to num(1.0, 30.0)), Permission.READ_ONLY) { x ->
                need(a, group)
                McpServer.json(buildJsonObject { put("apps", buildJsonArray { a!!.listApps(x.s("query"), x.i("limit", 10)).forEach { e -> add(buildJsonObject { put("name", e.label); put("package", e.packageName) }) } }) })
            }
            tool("open_app", "Launch an installed app by name or package.", obj("app" to str(max = 120, min = 1)), Permission.EXTERNAL_INTENT, "Opens another app.") { x ->
                need(a, group); ok(a!!.openApp(x.s("app")))
            }
            tool("open_link", "Open a deep link or URL in the app that handles it (e.g. geo:, tel:, an app link).", obj("uri" to str(max = 1000, min = 3)),
                Permission.EXTERNAL_INTENT, "Opens the link in another app.") { x -> need(a, group); ok(a!!.openLink(x.s("uri"))) }
        }
        return s
    }

    private fun comms(): McpServer {
        val s = McpServer("phone-messages", instructions = "Prepare messages and calls. Nothing is ever sent automatically: the user presses Send or Call.")
        val c = ports.comms
        Def(s, PhoneGroup.MESSAGING, groupOf).apply {
            tool("draft_sms", "Open the SMS app with a prepared message (user presses Send).", obj("to" to str("phone number", 40), "body" to str(max = 1000, min = 1)),
                Permission.EXTERNAL_INTENT, "Opens your SMS app with this draft; nothing is sent until you press Send.") { x -> need(c, group); ok(c!!.draftSms(x.s("to"), x.s("body"))) }
            tool("draft_whatsapp", "Open WhatsApp with a prepared message (user presses Send).", obj("to" to str("phone number with country code, or empty", 40), "text" to str(max = 1000, min = 1)),
                Permission.EXTERNAL_INTENT, "Opens WhatsApp with this draft; nothing is sent until you press Send.") { x -> need(c, group); ok(c!!.draftWhatsApp(x.s("to"), x.s("text"))) }
            tool("draft_email", "Open the email app with a prepared email (user presses Send).", obj("to" to str(max = 200), "subject" to str(max = 200), "body" to str(max = 4000, min = 1)),
                Permission.EXTERNAL_INTENT, "Opens your email app with this draft; nothing is sent until you press Send.") { x -> need(c, group); ok(c!!.draftEmail(x.s("to"), x.s("subject"), x.s("body"))) }
            tool("dial", "Open the dialer with a number (user presses Call).", obj("number" to str(max = 40, min = 3)),
                Permission.EXTERNAL_INTENT, "Opens the dialer; the call starts only when you press Call.") { x -> need(c, group); ok(c!!.dial(x.s("number"))) }
        }
        return s
    }

    private fun pim(): McpServer {
        val s = McpServer("phone-calendar-contacts", instructions = "Contacts lookup and calendar.")
        val p = ports.pim
        Def(s, PhoneGroup.CALENDAR_CONTACTS, groupOf).apply {
            tool("search_contacts", "Find contacts by name; returns phone numbers and emails.", obj("query" to str(max = 80, min = 1), "limit" to num(1.0, 20.0)),
                Permission.SENSITIVE_READ, "Reads matching contacts from your address book.") { x ->
                need(p, group)
                McpServer.json(buildJsonObject { put("contacts", buildJsonArray { p!!.searchContacts(x.s("query"), x.i("limit", 5)).forEach { c -> add(buildJsonObject {
                    put("name", c.name); put("phones", JsonArray(c.phones.map { JsonPrimitive(it) })); put("emails", JsonArray(c.emails.map { JsonPrimitive(it) }))
                }) } }) })
            }
            tool("list_events", "List calendar events in the next N days (or from a date).", obj("from" to str("YYYY-MM-DD or empty for today", 10), "days" to num(1.0, 60.0)),
                Permission.SENSITIVE_READ, "Reads events from your calendars.") { x ->
                need(p, group)
                val start = if (x.s("from").isBlank()) LocalDate.now(zone()) else runCatching { LocalDate.parse(x.s("from")) }.getOrElse { LocalDate.now(zone()) }
                val from = start.atStartOfDay(zone()).toInstant().toEpochMilli()
                val to = start.plusDays(x.i("days", 7).toLong()).atStartOfDay(zone()).toInstant().toEpochMilli()
                McpServer.json(buildJsonObject { put("events", buildJsonArray { p!!.events(from, to, 50).forEach { e -> add(buildJsonObject {
                    put("title", e.title); put("start", time(e.startMillis)); put("end", time(e.endMillis)); put("location", e.location); put("all_day", e.allDay)
                }) } }) })
            }
            tool("add_calendar_event", "Prepare a calendar event; the user confirms it in the calendar app.",
                obj("title" to str(max = 120, min = 1), "start" to str("YYYY-MM-DD HH:MM", 20, 10), "duration_minutes" to num(5.0, 1440.0), "location" to str(max = 200), "description" to str(max = 1000)),
                Permission.EXTERNAL_INTENT, "Opens your calendar app with a pre-filled event.") { x ->
                need(p, group)
                val st = parseWhen(x.s("start")) ?: return@tool McpServer.text("start must be YYYY-MM-DD HH:MM", true)
                ok(p!!.prepareEvent(x.s("title"), st, st + x.i("duration_minutes", 60) * 60_000L, x.s("location"), x.s("description")))
            }
        }
        return s
    }

    private fun files(): McpServer {
        val s = McpServer("phone-files", instructions = "Files inside folders the user granted with the system folder picker. Nothing outside them is reachable.")
        val f = ports.files
        fun entry(e: FileEntry) = buildJsonObject { put("id", e.id); put("name", e.name); put("folder", e.folder); put("type", if (e.isFolder) "folder" else e.mime); put("size", e.size) }
        Def(s, PhoneGroup.FILES, groupOf).apply {
            tool("list_folders", "Folders the user granted access to.", obj(), Permission.READ_ONLY) {
                need(f, group); McpServer.json(buildJsonObject { put("folders", buildJsonArray { f!!.folders().forEach { g -> add(buildJsonObject { put("id", g.id); put("name", g.name) }) } }) })
            }
            tool("search_files", "Search file names in granted folders.", obj("query" to str(max = 100, min = 1), "folder_id" to str("empty = all granted folders", 500), "limit" to num(1.0, 50.0)),
                Permission.SENSITIVE_READ, "Lists file names in the folders you granted.") { x ->
                need(f, group); McpServer.json(buildJsonObject { put("files", buildJsonArray { f!!.search(x.s("query"), x.s("folder_id").ifBlank { null }, x.i("limit", 20)).forEach { add(entry(it)) } }) })
            }
            tool("list_files", "List a granted folder.", obj("folder_id" to str(max = 500, min = 1), "limit" to num(1.0, 100.0)), Permission.SENSITIVE_READ, "Lists a folder you granted.") { x ->
                need(f, group); McpServer.json(buildJsonObject { put("files", buildJsonArray { f!!.list(x.s("folder_id"), x.i("limit", 50)).forEach { add(entry(it)) } }) })
            }
            tool("read_text_file", "Read a text file (txt, md, csv, json…).", obj("file_id" to str(max = 500, min = 1), "max_chars" to num(100.0, 20_000.0)),
                Permission.SENSITIVE_READ, "Reads the file's text.") { x -> need(f, group); ok(f!!.readText(x.s("file_id"), x.i("max_chars", 4000))) }
            tool("write_text_file", "Create a text file in a granted folder.", obj("folder_id" to str(max = 500, min = 1), "name" to str(max = 120, min = 1), "text" to str(max = 50_000)),
                Permission.DEVICE_CONTROL, "Creates a file in a folder you granted.") { x -> need(f, group); McpServer.json(entry(f!!.writeText(x.s("folder_id"), x.s("name"), x.s("text")))) }
            tool("create_folder", "Create a sub-folder.", obj("parent_id" to str(max = 500, min = 1), "name" to str(max = 120, min = 1)),
                Permission.DEVICE_CONTROL, "Creates a folder.") { x -> need(f, group); McpServer.json(entry(f!!.createFolder(x.s("parent_id"), x.s("name")))) }
            tool("move_file", "Move a file to another granted folder.", obj("file_id" to str(max = 500, min = 1), "target_folder_id" to str(max = 500, min = 1)),
                Permission.DEVICE_CONTROL, "Moves a file.") { x -> need(f, group); McpServer.json(entry(f!!.move(x.s("file_id"), x.s("target_folder_id")))) }
            tool("rename_file", "Rename a file.", obj("file_id" to str(max = 500, min = 1), "new_name" to str(max = 120, min = 1)),
                Permission.DEVICE_CONTROL, "Renames a file.") { x -> need(f, group); McpServer.json(entry(f!!.rename(x.s("file_id"), x.s("new_name")))) }
            tool("copy_file", "Copy a file to a granted folder.", obj("file_id" to str(max = 500, min = 1), "target_folder_id" to str(max = 500, min = 1)),
                Permission.DEVICE_CONTROL, "Copies a file.") { x -> need(f, group); McpServer.json(entry(f!!.copy(x.s("file_id"), x.s("target_folder_id")))) }
        }
        s.resource(McpResource("phone://files/folders", "Granted folders", "Folders shared with the assistant", "application/json")) {
            buildJsonArray { f?.folders()?.forEach { g -> add(buildJsonObject { put("id", g.id); put("name", g.name) }) } }.toString()
        }
        return s
    }

    private fun clipboardAndNotes(): McpServer {
        val s = McpServer("phone-notes-clipboard", instructions = "Notes kept in this app and the clipboard.")
        val c = ports.clipboard; val n = ports.notes
        Def(s, PhoneGroup.CLIPBOARD, groupOf).apply {
            tool("clipboard_read", "Read the current clipboard text.", obj(), Permission.SENSITIVE_READ, "Reads your clipboard.") {
                need(c, group); ok(c!!.read() ?: "(clipboard is empty)")
            }
            tool("clipboard_write", "Copy text to the clipboard.", obj("text" to str(max = 10_000, min = 1)), Permission.WRITE_LOCAL, "Replaces your clipboard.") { x ->
                need(c, group); c!!.write(x.s("text")); ok("copied")
            }
        }
        if (n != null) Def(s, PhoneGroup.DEVICE_INFO, HashMap()).apply {
            // Notes are app data: always available, not a phone group.
            tool("notes_search", "Search notes saved in this app.", obj("query" to str(max = 100), "limit" to num(1.0, 20.0)), Permission.READ_ONLY) { x ->
                McpServer.json(buildJsonObject { put("notes", buildJsonArray { n.search(x.s("query"), x.i("limit", 5)).forEach { (t, b) -> add(buildJsonObject { put("title", t); put("body", b.take(1500)) }) } }) })
            }
        }
        return s
    }

    private fun notifications(): McpServer {
        val s = McpServer("phone-notifications", instructions = "Recent notifications (only when the user enabled notification access).")
        val p = ports.notifications
        Def(s, PhoneGroup.NOTIFICATIONS, groupOf).apply {
            tool("recent_notifications", "Recent notifications (app, title, text) for summaries.", obj("limit" to num(1.0, 50.0), "app" to str("app name filter or empty", 60)),
                Permission.SENSITIVE_READ, "Reads your recent notifications.") { x ->
                need(p, group)
                if (!p!!.enabled) return@tool McpServer.text("Notification access is off. Turn it on in Settings → Phone actions → Read notifications.", true)
                McpServer.json(buildJsonObject { put("notifications", buildJsonArray { p.recent(x.i("limit", 20), x.s("app").ifBlank { null }).forEach { e -> add(buildJsonObject {
                    put("app", e.app); put("title", e.title); put("text", e.text.take(400)); put("time", time(e.postedAt))
                }) } }) })
            }
        }
        return s
    }

    private fun screen(): McpServer {
        val s = McpServer("phone-screen", instructions = "Screen reading and automation via the accessibility service (opt-in).")
        val p = ports.screen
        Def(s, PhoneGroup.SCREEN_AUTOMATION, groupOf).apply {
            tool("read_screen", "Describe what is on the screen right now (texts, buttons, fields).", obj(), Permission.SENSITIVE_READ, "Reads the content of the app on screen.") {
                need(p, group); ok(p!!.describeScreen())
            }
            tool("run_phone_task", "Do a multi-step task in another app by tapping and typing (shows each step; asks before submitting, paying or sending).",
                obj("goal" to str("what to achieve", 500, 5), "app" to str("app to start in, or empty for the current screen", 120)),
                Permission.SCREEN_ACTION, "Taps and types in another app on your behalf, step by step, with approval before anything irreversible.", timeoutMs = 900_000) { x ->
                need(p, group); ok(p!!.runTask(x.s("goal"), x.s("app")))
            }
            tool("run_macro", "Replay a recorded macro (taught by demonstration).", obj("name" to str(max = 80, min = 1), "params" to Schema.Arr(str(max = 200), "values for the macro's {{placeholders}} in order", maxItems = 10)),
                Permission.SCREEN_ACTION, "Replays recorded taps/typing in another app.", timeoutMs = 600_000) { x ->
                need(p, group)
                val macros = p!!.listMacros()
                val vals = (x["params"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
                ok(p.runMacro(x.s("name"), vals.mapIndexed { i, v -> "p$i" to v }.toMap() + mapOf("_list" to macros.joinToString { it.first })))
            }
        }
        Def(s, PhoneGroup.AUTOFILL, groupOf).apply {
            tool("fill_form", "Fill the form on screen from the user's on-device profile (review before submit).", obj(),
                Permission.SCREEN_ACTION, "Types your saved profile details into the form on screen.", timeoutMs = 300_000) {
                need(p, group); ok(p!!.fillForm())
            }
        }
        return s
    }
}
