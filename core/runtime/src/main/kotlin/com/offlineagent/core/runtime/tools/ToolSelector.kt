package com.offlineagent.core.runtime.tools

import com.offlineagent.core.runtime.rag.KeywordScorer

/**
 * Speed: every tool offered to the model costs prompt tokens (its signature) and grammar size. With 40+ phone/MCP
 * tools that would slow every agent step, so a request is offered only the tools that look relevant (BM25 over names,
 * descriptions and hand-written synonyms) plus a small always-on core.
 */
object ToolSelector {
    val CORE = setOf("calculator", "get_datetime", "render_ui", "canvas_update")

    private val synonyms = mapOf(
        "set_alarm" to "alarm wake morning clock", "set_timer" to "timer countdown minutes seconds", "set_reminder" to "remind reminder remember later notify",
        "flashlight" to "torch flashlight light dark", "set_volume" to "volume loud quiet sound mute", "set_ringer_mode" to "silent vibrate ringer mute phone",
        "set_do_not_disturb" to "dnd disturb focus", "open_settings_panel" to "wifi wi-fi bluetooth location gps internet data nfc settings",
        "device_info" to "battery storage space ram memory phone model android charging", "take_photo" to "photo picture camera selfie capture",
        "open_app" to "open launch start app", "open_link" to "link url map navigate website deep", "list_apps" to "installed apps",
        "draft_sms" to "sms text message", "draft_whatsapp" to "whatsapp message", "draft_email" to "email mail gmail compose", "dial" to "call phone ring dial",
        "search_contacts" to "contact number phone email of", "list_events" to "calendar schedule events meetings today tomorrow week agenda free busy",
        "add_calendar_event" to "calendar event meeting schedule add", "create_calendar_event" to "calendar event meeting schedule add",
        "search_files" to "file files find document folder pdf download", "read_text_file" to "read file open file contents", "write_text_file" to "save file write file create file",
        "move_file" to "move organise organize file folder sort", "rename_file" to "rename file", "create_folder" to "folder create organise", "list_folders" to "folders granted",
        "clipboard_read" to "clipboard copied paste", "clipboard_write" to "copy clipboard", "recent_notifications" to "notifications notification missed messages alerts summary",
        "read_screen" to "screen what is on screen this page", "run_phone_task" to "app book order fill form in app tap automate do it for me",
        "fill_form" to "fill form autofill application internship details", "run_macro" to "macro routine replay automation",
        "create_pdf" to "pdf export document report print save as pdf", "search_docs" to "documents my notes pdf search", "summarize" to "summarise summarize summary",
        "create_note" to "note save note jot", "share_text" to "share send to", "memory_write" to "remember about me preference", "memory_read" to "remember my preferences",
    )

    fun select(request: String, specs: List<ToolSpec>, max: Int = 10): List<ToolSpec> {
        if (specs.size <= max) return specs
        val docs = specs.map { it.name.replace('_', ' ') + " " + it.description + " " + (synonyms[it.name] ?: "") }
        val scores = KeywordScorer.score(request, docs)
        val ranked = specs.indices.filter { scores[it] > 0f }.sortedByDescending { scores[it] }.map { specs[it] }
        val core = specs.filter { it.name in CORE }
        val explicit = specs.filter { request.contains(it.name) }
        return (explicit + ranked.take(max) + core).distinctBy { it.name }.take(max + core.size)
    }
}
