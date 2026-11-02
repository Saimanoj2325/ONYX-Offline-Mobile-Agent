package com.offlineagent.app.onyx

import com.offlineagent.core.runtime.tools.Tool

/** Offline flavor: no network tools at all (INTERNET is used only by the model downloader). */
object NetworkTools {
    fun create(): List<Tool> = emptyList()
    val names: Set<String> = emptySet()
}
