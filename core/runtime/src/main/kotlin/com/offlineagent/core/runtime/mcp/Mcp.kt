package com.offlineagent.core.runtime.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

const val MCP_PROTOCOL_VERSION = "2025-06-18"
val MCP_SUPPORTED_VERSIONS = setOf("2025-06-18", "2025-03-26", "2024-11-05")

/**
 * A bidirectional message channel to one MCP server. Implementations: [InProcessTransport] (built-in servers, tests),
 * the Android binder/AIDL transport (other on-device apps) and, in the "connected" flavor only, Streamable HTTP.
 */
interface McpTransport {
    /** Starts delivering incoming messages to [onMessage]. */
    suspend fun start(onMessage: suspend (JsonObject) -> Unit)
    suspend fun send(message: JsonObject)
    suspend fun close()
    val description: String
}

// ---------------------------------------------------------------- data

data class ToolAnnotations(
    val title: String? = null,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpTool(val name: String, val description: String, val inputSchema: JsonObject, val annotations: ToolAnnotations = ToolAnnotations(), val meta: JsonObject? = null)
data class McpResource(val uri: String, val name: String, val description: String = "", val mimeType: String? = null)
data class McpPrompt(val name: String, val description: String = "", val arguments: List<Pair<String, Boolean>> = emptyList())

sealed interface McpContent {
    data class Text(val text: String) : McpContent
    data class Image(val data: String, val mimeType: String) : McpContent
    data class Resource(val uri: String, val text: String?, val mimeType: String?) : McpContent
}

data class CallToolResult(val content: List<McpContent>, val isError: Boolean, val structured: JsonObject? = null) {
    fun text(): String = content.joinToString("\n") {
        when (it) { is McpContent.Text -> it.text; is McpContent.Image -> "[image ${it.mimeType}]"; is McpContent.Resource -> it.text ?: it.uri }
    }
}

data class ServerInfo(val name: String, val version: String, val protocolVersion: String, val capabilities: JsonObject, val instructions: String?)

object McpCodec {
    fun tool(o: JsonObject): McpTool {
        val a = o["annotations"] as? JsonObject
        fun b(k: String) = (a?.get(k) as? JsonPrimitive)?.booleanOrNull
        return McpTool(
            (o["name"] as JsonPrimitive).content, (o["description"] as? JsonPrimitive)?.content ?: "",
            o["inputSchema"] as? JsonObject ?: buildJsonObject { put("type", "object") },
            ToolAnnotations((a?.get("title") as? JsonPrimitive)?.content, b("readOnlyHint"), b("destructiveHint"), b("idempotentHint"), b("openWorldHint")),
            o["_meta"] as? JsonObject,
        )
    }

    fun encodeTool(t: McpTool): JsonObject = buildJsonObject {
        put("name", t.name); put("description", t.description); put("inputSchema", t.inputSchema)
        put("annotations", buildJsonObject {
            t.annotations.title?.let { put("title", it) }
            t.annotations.readOnlyHint?.let { put("readOnlyHint", it) }
            t.annotations.destructiveHint?.let { put("destructiveHint", it) }
            t.annotations.idempotentHint?.let { put("idempotentHint", it) }
            t.annotations.openWorldHint?.let { put("openWorldHint", it) }
        })
        t.meta?.let { put("_meta", it) }
    }

    fun content(e: JsonElement): McpContent? {
        val o = e as? JsonObject ?: return null
        return when ((o["type"] as? JsonPrimitive)?.content) {
            "text" -> McpContent.Text((o["text"] as? JsonPrimitive)?.content ?: "")
            "image" -> McpContent.Image((o["data"] as? JsonPrimitive)?.content ?: "", (o["mimeType"] as? JsonPrimitive)?.content ?: "image/png")
            "resource" -> (o["resource"] as? JsonObject)?.let { r ->
                McpContent.Resource((r["uri"] as? JsonPrimitive)?.content ?: "", (r["text"] as? JsonPrimitive)?.content, (r["mimeType"] as? JsonPrimitive)?.content)
            }
            else -> null
        }
    }

    fun encodeContent(c: McpContent): JsonObject = when (c) {
        is McpContent.Text -> buildJsonObject { put("type", "text"); put("text", c.text) }
        is McpContent.Image -> buildJsonObject { put("type", "image"); put("data", c.data); put("mimeType", c.mimeType) }
        is McpContent.Resource -> buildJsonObject {
            put("type", "resource")
            put("resource", buildJsonObject { put("uri", c.uri); c.text?.let { put("text", it) }; c.mimeType?.let { put("mimeType", it) } })
        }
    }
}

// ---------------------------------------------------------------- client

/** MCP client: initialize handshake, tools, resources, prompts, ping. One instance per server connection. */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "offline-agent",
    private val clientVersion: String = "0.3.0",
    private val requestTimeoutMs: Long = 30_000,
) {
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcMessage.Response>>()
    private val startLock = Mutex()
    @Volatile private var started = false
    var server: ServerInfo? = null
        private set
    /** Server-initiated notifications (e.g. notifications/tools/list_changed). */
    var onNotification: (RpcMessage.Notification) -> Unit = {}

    private suspend fun ensureStarted() = startLock.withLock {
        if (started) return@withLock
        transport.start { msg -> dispatch(msg) }
        started = true
    }

    private suspend fun dispatch(msg: JsonObject) {
        when (val m = runCatching { JsonRpc.decode(msg) }.getOrNull() ?: return) {
            is RpcMessage.Response -> pending.remove(key(m.id))?.complete(m)
            is RpcMessage.Notification -> onNotification(m)
            // Servers may ping the client; we support no other client-side features (sampling/roots/elicitation).
            is RpcMessage.Request -> transport.send(JsonRpc.encode(
                if (m.method == "ping") RpcMessage.Response(m.id, JsonObject(emptyMap()), null)
                else JsonRpc.error(m.id, RpcError.METHOD_NOT_FOUND, "client does not support ${m.method}"),
            ))
        }
    }

    private fun key(id: JsonElement) = (id as? JsonPrimitive)?.content ?: id.toString()

    suspend fun request(method: String, params: JsonObject? = null, timeoutMs: Long = requestTimeoutMs): JsonObject {
        ensureStarted()
        val id = JsonPrimitive(nextId.getAndIncrement())
        val d = CompletableDeferred<RpcMessage.Response>()
        pending[key(id)] = d
        try {
            transport.send(JsonRpc.encode(RpcMessage.Request(id, method, params)))
            val r = try { withTimeout(timeoutMs) { d.await() } } catch (e: TimeoutCancellationException) {
                throw McpException(RpcError(RpcError.INTERNAL_ERROR, "$method timed out after $timeoutMs ms"))
            }
            r.error?.let { throw McpException(it) }
            return r.result as? JsonObject ?: JsonObject(emptyMap())
        } finally {
            pending.remove(key(id))
        }
    }

    suspend fun notify(method: String, params: JsonObject? = null) {
        ensureStarted()
        transport.send(JsonRpc.encode(RpcMessage.Notification(method, params)))
    }

    suspend fun initialize(): ServerInfo {
        val r = request("initialize", buildJsonObject {
            put("protocolVersion", MCP_PROTOCOL_VERSION)
            put("capabilities", buildJsonObject { })
            put("clientInfo", buildJsonObject { put("name", clientName); put("version", clientVersion) })
        })
        val version = (r["protocolVersion"] as? JsonPrimitive)?.content ?: ""
        if (version !in MCP_SUPPORTED_VERSIONS) {
            throw McpException(RpcError(RpcError.INVALID_REQUEST, "unsupported MCP protocol version '$version'"))
        }
        val si = r["serverInfo"] as? JsonObject
        val info = ServerInfo(
            (si?.get("name") as? JsonPrimitive)?.content ?: "server", (si?.get("version") as? JsonPrimitive)?.content ?: "",
            version, r["capabilities"] as? JsonObject ?: JsonObject(emptyMap()), (r["instructions"] as? JsonPrimitive)?.content,
        )
        server = info
        notify("notifications/initialized")
        return info
    }

    fun has(capability: String) = server?.capabilities?.containsKey(capability) == true

    suspend fun listTools(): List<McpTool> = paged("tools/list", "tools") { McpCodec.tool(it) }
    suspend fun listResources(): List<McpResource> = paged("resources/list", "resources") {
        McpResource((it["uri"] as JsonPrimitive).content, (it["name"] as? JsonPrimitive)?.content ?: "", (it["description"] as? JsonPrimitive)?.content ?: "", (it["mimeType"] as? JsonPrimitive)?.content)
    }
    suspend fun listPrompts(): List<McpPrompt> = paged("prompts/list", "prompts") { p ->
        McpPrompt((p["name"] as JsonPrimitive).content, (p["description"] as? JsonPrimitive)?.content ?: "",
            (p["arguments"] as? JsonArray)?.mapNotNull { a -> (a as? JsonObject)?.let { (it["name"] as JsonPrimitive).content to ((it["required"] as? JsonPrimitive)?.booleanOrNull ?: false) } } ?: emptyList())
    }

    private suspend fun <T> paged(method: String, field: String, map: (JsonObject) -> T): List<T> {
        val out = ArrayList<T>()
        var cursor: String? = null
        var guard = 0
        do {
            val r = request(method, cursor?.let { c -> buildJsonObject { put("cursor", c) } })
            (r[field] as? JsonArray)?.forEach { e -> (e as? JsonObject)?.let { runCatching { map(it) }.getOrNull()?.let(out::add) } }
            cursor = (r["nextCursor"] as? JsonPrimitive)?.content
        } while (cursor != null && ++guard < 50)
        return out
    }

    suspend fun callTool(name: String, arguments: JsonObject, timeoutMs: Long = requestTimeoutMs): CallToolResult {
        val r = request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }, timeoutMs)
        return CallToolResult(
            (r["content"] as? JsonArray)?.mapNotNull { McpCodec.content(it) } ?: emptyList(),
            (r["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
            r["structuredContent"] as? JsonObject,
        )
    }

    suspend fun readResource(uri: String): List<McpContent.Resource> {
        val r = request("resources/read", buildJsonObject { put("uri", uri) })
        return (r["contents"] as? JsonArray)?.mapNotNull { e ->
            (e as? JsonObject)?.let { McpContent.Resource((it["uri"] as? JsonPrimitive)?.content ?: uri, (it["text"] as? JsonPrimitive)?.content, (it["mimeType"] as? JsonPrimitive)?.content) }
        } ?: emptyList()
    }

    suspend fun getPrompt(name: String, args: Map<String, String>): List<Pair<String, String>> {
        val r = request("prompts/get", buildJsonObject { put("name", name); put("arguments", JsonObject(args.mapValues { JsonPrimitive(it.value) })) })
        return (r["messages"] as? JsonArray)?.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val role = (o["role"] as? JsonPrimitive)?.content ?: "user"
            val c = o["content"]?.let { McpCodec.content(it) }
            role to ((c as? McpContent.Text)?.text ?: "")
        } ?: emptyList()
    }

    suspend fun ping() { request("ping", timeoutMs = 5_000) }

    suspend fun close() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        transport.close()
    }
}

// ---------------------------------------------------------------- server

class McpToolHandler(val tool: McpTool, val handler: suspend (JsonObject) -> CallToolResult)
class McpResourceHandler(val resource: McpResource, val read: suspend () -> String)
class McpPromptHandler(val prompt: McpPrompt, val render: suspend (Map<String, String>) -> String)

/**
 * Minimal MCP server (tools, resources, prompts). Used for the app's built-in on-device servers, by the sample
 * third-party server app (over binder), and in tests. Transport-agnostic: feed it requests via [handle].
 */
open class McpServer(val name: String, val version: String = "1.0", val instructions: String? = null) {
    private val tools = LinkedHashMap<String, McpToolHandler>()
    private val resources = LinkedHashMap<String, McpResourceHandler>()
    private val prompts = LinkedHashMap<String, McpPromptHandler>()

    fun tool(t: McpTool, handler: suspend (JsonObject) -> CallToolResult) { tools[t.name] = McpToolHandler(t, handler) }
    fun resource(r: McpResource, read: suspend () -> String) { resources[r.uri] = McpResourceHandler(r, read) }
    fun prompt(p: McpPrompt, render: suspend (Map<String, String>) -> String) { prompts[p.name] = McpPromptHandler(p, render) }
    val toolNames: Set<String> get() = tools.keys

    /** Handles one incoming message; returns the response (null for notifications). Never throws. */
    suspend fun handle(msg: JsonObject): JsonObject? {
        val m = try { JsonRpc.decode(msg) } catch (e: McpException) {
            return JsonRpc.encode(RpcMessage.Response(JsonPrimitive(0), null, e.error))
        }
        val req = m as? RpcMessage.Request ?: return null
        val res: RpcMessage.Response = try {
            RpcMessage.Response(req.id, dispatch(req.method, req.params ?: JsonObject(emptyMap())), null)
        } catch (e: McpException) {
            RpcMessage.Response(req.id, null, e.error)
        } catch (e: Exception) {
            RpcMessage.Response(req.id, null, RpcError(RpcError.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName))
        }
        return JsonRpc.encode(res)
    }

    suspend fun handleString(text: String): String? {
        val o = try { JsonRpc.json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: return JsonRpc.encodeString(RpcMessage.Response(JsonPrimitive(0), null, RpcError(RpcError.PARSE_ERROR, "parse error")))
        return handle(o)?.toString()
    }

    private fun bad(msg: String): Nothing = throw McpException(RpcError(RpcError.INVALID_PARAMS, msg))
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.content

    protected open suspend fun dispatch(method: String, p: JsonObject): JsonObject = when (method) {
        "initialize" -> {
            val requested = p.s("protocolVersion") ?: MCP_PROTOCOL_VERSION
            buildJsonObject {
                put("protocolVersion", if (requested in MCP_SUPPORTED_VERSIONS) requested else MCP_PROTOCOL_VERSION)
                put("capabilities", buildJsonObject {
                    if (tools.isNotEmpty()) put("tools", buildJsonObject { put("listChanged", false) })
                    if (resources.isNotEmpty()) put("resources", buildJsonObject { put("listChanged", false) })
                    if (prompts.isNotEmpty()) put("prompts", buildJsonObject { put("listChanged", false) })
                })
                put("serverInfo", buildJsonObject { put("name", name); put("version", version) })
                instructions?.let { put("instructions", it) }
            }
        }
        "ping" -> JsonObject(emptyMap())
        "tools/list" -> buildJsonObject { put("tools", buildJsonArray { tools.values.forEach { add(McpCodec.encodeTool(it.tool)) } }) }
        "tools/call" -> {
            val n = p.s("name") ?: bad("missing tool name")
            val h = tools[n] ?: bad("unknown tool '$n'")
            val args = p["arguments"] as? JsonObject ?: JsonObject(emptyMap())
            // Tool failures are results with isError=true (visible to the model), not protocol errors.
            val r = try { h.handler(args) } catch (e: McpException) { throw e } catch (e: Exception) {
                CallToolResult(listOf(McpContent.Text("error: ${e.message ?: e.javaClass.simpleName}")), true)
            }
            buildJsonObject {
                put("content", buildJsonArray { r.content.forEach { add(McpCodec.encodeContent(it)) } })
                put("isError", r.isError)
                r.structured?.let { put("structuredContent", it) }
            }
        }
        "resources/list" -> buildJsonObject {
            put("resources", buildJsonArray {
                resources.values.forEach { r -> add(buildJsonObject {
                    put("uri", r.resource.uri); put("name", r.resource.name); put("description", r.resource.description)
                    r.resource.mimeType?.let { put("mimeType", it) }
                }) }
            })
        }
        "resources/read" -> {
            val uri = p.s("uri") ?: bad("missing uri")
            val r = resources[uri] ?: throw McpException(RpcError(-32002, "resource not found: $uri"))
            buildJsonObject {
                put("contents", buildJsonArray { add(buildJsonObject { put("uri", uri); r.resource.mimeType?.let { put("mimeType", it) }; put("text", r.read()) }) })
            }
        }
        "prompts/list" -> buildJsonObject {
            put("prompts", buildJsonArray {
                prompts.values.forEach { h -> add(buildJsonObject {
                    put("name", h.prompt.name); put("description", h.prompt.description)
                    put("arguments", buildJsonArray { h.prompt.arguments.forEach { (n, req) -> add(buildJsonObject { put("name", n); put("required", req) }) } })
                }) }
            })
        }
        "prompts/get" -> {
            val n = p.s("name") ?: bad("missing prompt name")
            val h = prompts[n] ?: bad("unknown prompt '$n'")
            val args = (p["arguments"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content ?: "" } ?: emptyMap()
            h.prompt.arguments.filter { it.second && it.first !in args }.firstOrNull()?.let { bad("missing argument '${it.first}'") }
            buildJsonObject {
                put("description", h.prompt.description)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", McpCodec.encodeContent(McpContent.Text(h.render(args)))) }) })
            }
        }
        else -> throw McpException(RpcError(RpcError.METHOD_NOT_FOUND, "method not found: $method"))
    }

    companion object {
        fun text(s: String, isError: Boolean = false) = CallToolResult(listOf(McpContent.Text(s)), isError)
        fun json(o: JsonObject) = CallToolResult(listOf(McpContent.Text(o.toString())), false, o)
    }
}

/** Connects a client directly to an in-process [McpServer] (built-in servers, tests). */
class InProcessTransport(private val server: McpServer) : McpTransport {
    private var sink: (suspend (JsonObject) -> Unit)? = null
    override val description = "in-process:${server.name}"
    override suspend fun start(onMessage: suspend (JsonObject) -> Unit) { sink = onMessage }
    override suspend fun send(message: JsonObject) {
        val reply = server.handle(message) ?: return
        sink?.invoke(reply)
    }
    override suspend fun close() { sink = null }
}

/** Request/response transport over any blocking string call (Android binder: IMcpServer.handle(String)). */
class StringCallTransport(override val description: String, private val call: suspend (String) -> String?) : McpTransport {
    private var sink: (suspend (JsonObject) -> Unit)? = null
    override suspend fun start(onMessage: suspend (JsonObject) -> Unit) { sink = onMessage }
    override suspend fun send(message: JsonObject) {
        val reply = call(message.toString()) ?: return
        val o = runCatching { JsonRpc.json.parseToJsonElement(reply).jsonObject }.getOrNull() ?: return
        sink?.invoke(o)
    }
    override suspend fun close() { sink = null }
}
