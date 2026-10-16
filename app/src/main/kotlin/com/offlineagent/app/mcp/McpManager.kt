package com.offlineagent.app.mcp

import android.content.Context
import android.util.Log
import com.offlineagent.app.BuildConfig
import com.offlineagent.core.runtime.mcp.InProcessTransport
import com.offlineagent.core.runtime.mcp.McpHub
import com.offlineagent.core.runtime.mcp.McpTransport
import com.offlineagent.core.runtime.mcp.McpTrust
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.PhonePorts
import com.offlineagent.core.runtime.phone.PhoneServers
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.mcp.DiscoveredMcpServer
import com.offlineagent.mcp.McpBinderClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

data class McpServerStatus(val id: String, val name: String, val kind: String, val connected: Boolean, val tools: List<String>, val error: String? = null)

/**
 * Owns every MCP connection:
 *  - built-in on-device servers (phone actions) over an in-process transport (trust BUILT_IN, exact permissions)
 *  - other on-device apps over binder (AIDL), only those the user enabled (trust ON_DEVICE_APP)
 *  - LAN/remote Streamable-HTTP servers, only in the "connected" flavor (trust NETWORK)
 * Their tools are merged into the agent registry with the normal approval flow.
 */
class McpManager(private val ctx: Context, ports: PhonePorts) {
    val hub = McpHub()
    val phone = PhoneServers(ports)
    private val unbinders = ConcurrentHashMap<String, () -> Unit>()
    @Volatile var tools: List<Tool> = emptyList()
        private set
    private val _status = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val status: StateFlow<List<McpServerStatus>> = _status.asStateFlow()

    /** All built-in phone tool names (known to the skill parser even when their group is off). */
    val phoneToolNames: Set<String> get() = phone.toolGroups.keys

    fun disabledTools(enabledGroups: Set<String>): Set<String> =
        phone.toolGroups.filterValues { it.name !in enabledGroups }.keys

    suspend fun startBuiltIns() {
        for (s in phone.build()) runCatching { hub.connect(s.name, s.name, InProcessTransport(s), McpTrust.BUILT_IN) }
            .onFailure { Log.w("McpManager", "built-in ${s.name} failed", it) }
        refresh()
    }

    fun discoverOnDevice(): List<DiscoveredMcpServer> = runCatching { McpBinderClient.discover(ctx) }.getOrDefault(emptyList())

    suspend fun connectOnDevice(server: DiscoveredMcpServer): Result<Unit> = runCatching {
        val (transport, unbind) = McpBinderClient.connect(ctx, server)
        try { hub.connect(server.id, server.name, transport, McpTrust.ON_DEVICE_APP) } catch (e: Throwable) { unbind(); throw e }
        unbinders[server.id] = unbind
        refresh()
    }

    /** Connected flavor only: Streamable HTTP endpoint (LAN or remote). The offline flavor has no network code at all. */
    suspend fun connectNetwork(url: String, token: String?): Result<Unit> = runCatching {
        if (!BuildConfig.NETWORK_MCP) throw IllegalStateException("This is the offline build: it has no internet permission. Install the \"connected\" build for network MCP servers.")
        val transport: McpTransport = NetworkTransports.create(url, token) ?: throw IllegalStateException("Network MCP is not available in this build")
        hub.connect(url, url, transport, McpTrust.NETWORK)
        refresh()
    }

    suspend fun disconnect(id: String) {
        hub.disconnect(id)
        unbinders.remove(id)?.invoke()
        refresh()
    }

    suspend fun refresh() {
        tools = hub.tools()
        _status.value = hub.all().map { c ->
            McpServerStatus(c.id, c.displayName, when (c.trust) { McpTrust.BUILT_IN -> "Built-in"; McpTrust.ON_DEVICE_APP -> "On-device app"; McpTrust.NETWORK -> "Network" }, true, c.tools.map { it.spec.name })
        }
    }

    fun groupOf(tool: String): PhoneGroup? = phone.toolGroups[tool]
}
