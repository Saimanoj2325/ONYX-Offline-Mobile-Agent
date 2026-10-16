package com.offlineagent.mcp

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.offlineagent.core.runtime.mcp.McpServer
import com.offlineagent.core.runtime.mcp.McpTransport
import com.offlineagent.core.runtime.mcp.StringCallTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** Constants of the on-device MCP binding (see docs/MCP_ANDROID_SPEC.md). */
object McpAndroid {
    /** Intent action a server Service declares in its intent-filter. */
    const val ACTION_MCP_SERVER = "com.offlineagent.mcp.SERVER"
    /** Permission a server Service requires (android:permission); only apps holding it can bind. */
    const val PERMISSION_USE_MCP = "com.offlineagent.permission.USE_MCP_SERVERS"
    /** <meta-data> on the server Service: human readable name / description. */
    const val META_NAME = "com.offlineagent.mcp.NAME"
    const val META_DESCRIPTION = "com.offlineagent.mcp.DESCRIPTION"
    const val BINDING_VERSION = 1
}

data class DiscoveredMcpServer(val component: ComponentName, val name: String, val description: String, val appLabel: String) {
    val id: String get() = component.flattenToShortString()
}

/**
 * Base class for an on-device MCP server app: subclass, build your [McpServer] in [createServer], declare the
 * Service with the [McpAndroid.ACTION_MCP_SERVER] intent-filter and android:permission=[McpAndroid.PERMISSION_USE_MCP].
 */
abstract class McpBinderService : Service() {
    private val server by lazy { createServer() }
    abstract fun createServer(): McpServer

    private val binder = object : IMcpServer.Stub() {
        override fun bindingVersion(): Int = McpAndroid.BINDING_VERSION
        override fun handle(jsonRpcMessage: String?): String? {
            val msg = jsonRpcMessage ?: return null
            if (msg.length > MAX_MESSAGE_CHARS) return """{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"message too large"}}"""
            return runBlocking(Dispatchers.Default) { server.handleString(msg) }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object { const val MAX_MESSAGE_CHARS = 512 * 1024 }
}

/** Client side: discovery + binding. */
object McpBinderClient {
    fun discover(context: Context): List<DiscoveredMcpServer> {
        val pm = context.packageManager
        val intent = Intent(McpAndroid.ACTION_MCP_SERVER)
        @Suppress("DEPRECATION")
        val found = pm.queryIntentServices(intent, PackageManager.GET_META_DATA)
        return found.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (si.packageName == context.packageName) return@mapNotNull null
            val meta = si.metaData
            DiscoveredMcpServer(
                ComponentName(si.packageName, si.name),
                meta?.getString(McpAndroid.META_NAME) ?: si.loadLabel(pm).toString(),
                meta?.getString(McpAndroid.META_DESCRIPTION) ?: "",
                si.applicationInfo.loadLabel(pm).toString(),
            )
        }
    }

    /** Binds to [server] and returns a transport plus an unbind function. */
    suspend fun connect(context: Context, server: DiscoveredMcpServer, timeoutMs: Long = 10_000): Pair<McpTransport, () -> Unit> {
        var conn: ServiceConnection? = null
        val binder: IMcpServer = withTimeout(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        if (cont.isActive) cont.resume(IMcpServer.Stub.asInterface(service))
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {}
                }
                conn = c
                val ok = context.bindService(Intent(McpAndroid.ACTION_MCP_SERVER).setComponent(server.component), c, Context.BIND_AUTO_CREATE)
                if (!ok) cont.resumeWith(Result.failure(IllegalStateException("cannot bind to ${server.id} (missing permission?)")))
                cont.invokeOnCancellation { runCatching { context.unbindService(c) } }
            }
        }
        if (binder.bindingVersion() != McpAndroid.BINDING_VERSION) throw IllegalStateException("unsupported binding version")
        val transport = StringCallTransport("binder:${server.id}") { msg -> withContext(Dispatchers.IO) { binder.handle(msg) } }
        return transport to { conn?.let { runCatching { context.unbindService(it) } }; Unit }
    }
}
