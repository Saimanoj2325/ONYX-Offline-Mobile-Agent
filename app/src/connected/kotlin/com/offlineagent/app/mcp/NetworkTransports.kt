package com.offlineagent.app.mcp

import com.offlineagent.core.mcphttp.StreamableHttpTransport
import com.offlineagent.core.runtime.mcp.McpTransport

/** Connected flavor: MCP Streamable HTTP (+SSE responses) for LAN or remote servers the user adds. */
object NetworkTransports {
    const val AVAILABLE = true
    fun create(url: String, token: String?): McpTransport = StreamableHttpTransport(url.trim(), token?.takeIf { it.isNotBlank() })
}
