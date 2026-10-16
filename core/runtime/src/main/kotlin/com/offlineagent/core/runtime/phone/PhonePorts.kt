package com.offlineagent.core.runtime.phone

import kotlinx.serialization.json.JsonObject

/**
 * On-device capabilities ("Phone actions"). Each group can be switched on/off by the user in Settings → Phone
 * actions; a group that needs an Android runtime permission or special access asks for it there, never silently.
 */
enum class PhoneGroup(val title: String, val description: String, val androidAccess: String) {
    ALARMS("Alarms, timers & reminders", "Set alarms and timers in your Clock app; on-device reminders.", "SET_ALARM (normal), POST_NOTIFICATIONS"),
    CALENDAR_CONTACTS("Calendar & contacts", "Look up contacts and upcoming events; prepare new events.", "READ_CONTACTS, READ_CALENDAR (runtime)"),
    APPS_LINKS("Open apps & links", "Launch installed apps and open deep links.", "launcher-app visibility (<queries>)"),
    MESSAGING("Message & call drafts", "Prepare SMS, WhatsApp, email or a call. You always press Send/Call yourself.", "none (intents)"),
    CAMERA("Camera", "Take a photo with your camera app and attach it to the chat.", "none (camera app intent)"),
    DEVICE_SETTINGS("Flashlight, volume, ringer, DND, quick settings", "Toggle the torch, change volume/ringer, Do Not Disturb, open Wi-Fi/Bluetooth/location panels.", "ACCESS_NOTIFICATION_POLICY (special access for DND)"),
    DEVICE_INFO("Battery, storage & device info", "Read battery level, free storage, RAM and phone model.", "none"),
    CLIPBOARD("Clipboard", "Read or set the clipboard (only while the app is open).", "none"),
    FILES("Files in folders you pick", "Search, read, organise and create files only inside folders you grant.", "Storage Access Framework (per folder)"),
    NOTIFICATIONS("Read notifications", "Summarise recent notifications.", "Notification access (opt-in special access)"),
    SCREEN_AUTOMATION("Screen automation", "Read the screen and tap/type in other apps for multi-step tasks, with approval before anything irreversible.", "Accessibility service (opt-in, disclosed)"),
    AUTOFILL("Form autofill", "Fill forms in any app or browser from your encrypted on-device profile.", "Autofill service (opt-in)"),
}

class PhoneActionException(message: String, val group: PhoneGroup? = null) : Exception(message)

data class AppEntry(val label: String, val packageName: String)
data class ContactEntry(val name: String, val phones: List<String>, val emails: List<String>)
data class EventEntry(val title: String, val startMillis: Long, val endMillis: Long, val location: String, val allDay: Boolean, val calendar: String)
data class ReminderEntry(val id: String, val text: String, val atMillis: Long, val repeat: String)
data class FileEntry(val id: String, val name: String, val folder: String, val mime: String, val size: Long, val modified: Long, val isFolder: Boolean)
data class FolderGrant(val id: String, val name: String)
data class NotificationEntry(val app: String, val title: String, val text: String, val postedAt: Long)

interface DevicePort {
    suspend fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>): String
    suspend fun setTimer(seconds: Int, label: String): String
    suspend fun flashlight(on: Boolean): String
    /** [stream]: media | ring | alarm | notification; [percent] 0..100 */
    suspend fun setVolume(stream: String, percent: Int): String
    /** normal | vibrate | silent */
    suspend fun setRinger(mode: String): String
    suspend fun setDoNotDisturb(on: Boolean): String
    /** wifi | bluetooth | location | internet | nfc | volume | battery | display | dnd */
    suspend fun openPanel(panel: String): String
    suspend fun deviceInfo(): JsonObject
    /** Opens the camera app; returns a note (the photo is attached to the chat when the user takes it). */
    suspend fun takePhoto(): String
}

interface RemindersPort {
    suspend fun schedule(text: String, atMillis: Long, repeat: String): ReminderEntry
    suspend fun list(): List<ReminderEntry>
    suspend fun cancel(id: String): Boolean
}

interface AppsPort {
    suspend fun listApps(query: String, limit: Int): List<AppEntry>
    suspend fun openApp(nameOrPackage: String): String
    suspend fun openLink(uri: String): String
}

interface CommsPort {
    suspend fun draftSms(to: String, body: String): String
    suspend fun draftWhatsApp(to: String, text: String): String
    suspend fun draftEmail(to: String, subject: String, body: String): String
    suspend fun dial(number: String): String
}

interface PimPort {
    suspend fun searchContacts(query: String, limit: Int): List<ContactEntry>
    suspend fun events(fromMillis: Long, toMillis: Long, limit: Int): List<EventEntry>
    suspend fun prepareEvent(title: String, startMillis: Long, endMillis: Long, location: String, description: String): String
}

interface FilesPort {
    suspend fun folders(): List<FolderGrant>
    suspend fun search(query: String, folderId: String?, limit: Int): List<FileEntry>
    suspend fun list(folderId: String, limit: Int): List<FileEntry>
    suspend fun readText(fileId: String, maxChars: Int): String
    suspend fun writeText(folderId: String, name: String, text: String): FileEntry
    suspend fun createFolder(parentId: String, name: String): FileEntry
    suspend fun move(fileId: String, targetFolderId: String): FileEntry
    suspend fun rename(fileId: String, newName: String): FileEntry
    suspend fun copy(fileId: String, targetFolderId: String): FileEntry
}

interface ClipboardPort {
    suspend fun read(): String?
    suspend fun write(text: String)
}

interface NotificationsPort {
    val enabled: Boolean
    suspend fun recent(limit: Int, app: String?): List<NotificationEntry>
}

interface NotesStorePort {
    suspend fun search(query: String, limit: Int): List<Pair<String, String>>
    suspend fun create(title: String, body: String): Long
}

/** Screen automation entry points (implemented with the accessibility service). */
interface ScreenPort {
    /** Compact description of what is on screen now (elements with ids), or an explanation why not available. */
    suspend fun describeScreen(): String
    /** Runs the multi-step screen agent for [goal] (optionally starting in [app]); returns its final report. */
    suspend fun runTask(goal: String, app: String): String
    /** Fills the form on screen from the profile vault (approval before submit). */
    suspend fun fillForm(): String
    suspend fun runMacro(name: String, params: Map<String, String>): String
    suspend fun listMacros(): List<Pair<String, String>>
}
