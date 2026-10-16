package com.offlineagent.app.mcp

import com.offlineagent.core.runtime.mcp.McpTransport

/** Offline flavor: there is deliberately NO network transport in this APK (and no INTERNET permission). */
object NetworkTransports {
    const val AVAILABLE = false
    @Suppress("UNUSED_PARAMETER")
    fun create(url: String, token: String?): McpTransport? = null
}
