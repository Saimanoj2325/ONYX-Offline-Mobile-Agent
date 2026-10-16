package com.offlineagent.core.runtime.mcp

import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** How much a server is trusted. Built-in servers declare exact permissions; third-party ones are capped. */
enum class McpTrust { BUILT_IN, ON_DEVICE_APP, NETWORK }

/** Key in a tool's `_meta` that built-in servers use to declare the app permission class of a tool. */
const val META_PERMISSION = "offlineagent/permission"
const val META_SIDE_EFFECTS = "offlineagent/sideEffects"
const val META_TIMEOUT = "offlineagent/timeoutMs"

/** Converts an MCP tool's JSON Schema (arbitrary) into the app's grammar-friendly [Schema] subset. */
object JsonSchemaConverter {
    fun convert(js: JsonObject, depth: Int = 0): Schema {
        val desc = (js["description"] as? JsonPrimitive)?.contentOrNull
        val enum = (js["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val type = when (val t = js["type"]) {
            is JsonPrimitive -> t.content
            is JsonArray -> t.mapNotNull { (it as? JsonPrimitive)?.content }.firstOrNull { it != "null" }
            else -> if (js["properties"] != null) "object" else if (enum != null) "string" else null
        }
        fun num(k: String) = (js[k] as? JsonPrimitive)?.doubleOrNull
        return when (type) {
            "string" -> Schema.Str(desc, maxLength = num("maxLength")?.toInt()?.coerceIn(1, 20_000) ?: 2000, minLength = num("minLength")?.toInt() ?: 0, enum = enum)
            "integer", "number" -> Schema.Num(desc, num("minimum"), num("maximum"), integer = type == "integer")
            "boolean" -> Schema.Bool(desc)
            "array" -> Schema.Arr(
                (js["items"] as? JsonObject)?.let { if (depth < 4) convert(it, depth + 1) else Schema.Str() } ?: Schema.Str(),
                desc, minItems = num("minItems")?.toInt() ?: 0, maxItems = num("maxItems")?.toInt()?.coerceIn(1, 50) ?: 20,
            )
            "object" -> {
                val props = js["properties"] as? JsonObject
                if (props == null || depth >= 4) Schema.AnyObj(desc)
                else Schema.Obj(props.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to convert(it, depth + 1) } }, desc)
            }
            else -> Schema.Str(desc, maxLength = 2000)
        }
    }

    fun required(js: JsonObject): Set<String> = (js["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet() ?: emptySet()

    /**
     * Our grammars make every property required (small models are more reliable that way); before calling the server,
     * drop optional properties the model left empty ("" / [] / {}).
     */
    fun pruneOptional(args: JsonObject, js: JsonObject): JsonObject {
        val req = required(js)
        return JsonObject(args.filter { (k, v) -> k in req || !isEmpty(v) })
    }

    private fun isEmpty(v: JsonElement) = when (v) {
        is JsonPrimitive -> v.isString && v.content.isEmpty()
        is JsonArray -> v.isEmpty()
        is JsonObject -> v.isEmpty()
        else -> true
    }
}

/** Exposes one MCP tool as an agent [Tool] (same validation, policy and approval flow as built-in tools). */
class McpTool2AgentTool(
    private val client: McpClient,
    private val tool: McpTool,
    serverId: String,
    trust: McpTrust,
) : Tool {
    override val spec: ToolSpec

    init {
        val declared = (tool.meta?.get(META_PERMISSION) as? JsonPrimitive)?.contentOrNull?.let { p -> runCatching { Permission.valueOf(p) }.getOrNull() }
        val readOnly = tool.annotations.readOnlyHint == true
        val permission = when (trust) {
            McpTrust.BUILT_IN -> declared ?: if (readOnly) Permission.READ_ONLY else Permission.EXTERNAL_INTENT
            // Third-party servers: read-only tools still count as reading personal data (approval unless always-allowed);
            // anything else is treated as leaving the app (approval every time).
            McpTrust.ON_DEVICE_APP, McpTrust.NETWORK -> if (readOnly && tool.annotations.openWorldHint != true) Permission.SENSITIVE_READ else Permission.EXTERNAL_INTENT
        }
        val effects = (tool.meta?.get(META_SIDE_EFFECTS) as? JsonPrimitive)?.contentOrNull
            ?: when {
                tool.annotations.destructiveHint == true -> "May change or delete data in \"$serverId\"."
                readOnly -> "Reads data from \"$serverId\"."
                else -> "Runs \"${tool.name}\" in \"$serverId\"."
            }
        val input = JsonSchemaConverter.convert(tool.inputSchema).let { it as? Schema.Obj ?: Schema.Obj(emptyList()) }
        spec = ToolSpec(
            name = agentName(serverId, tool.name, trust),
            description = (tool.annotations.title?.let { "$it: " } ?: "") + tool.description.take(400),
            input = input,
            permission = permission,
            sideEffects = effects,
            timeoutMs = (tool.meta?.get(META_TIMEOUT) as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 30_000,
        )
    }

    val mcpName: String get() = tool.name

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val r = client.callTool(tool.name, JsonSchemaConverter.pruneOptional(args, tool.inputSchema), spec.timeoutMs)
        val text = r.text().take(6000)
        if (r.isError) ToolResult.Error(text.ifBlank { "the tool reported an error" })
        else ToolResult.Ok(r.structured ?: buildJsonObject { put("result", text) }, text.take(120))
    } catch (e: McpException) {
        ToolResult.Error(e.error.message)
    }

    companion object {
        /** Built-in servers keep their tool names ("set_alarm"); others get a server prefix ("mcp_notes_search"). */
        fun agentName(serverId: String, tool: String, trust: McpTrust): String {
            val clean = { s: String -> s.lowercase().replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_') }
            val raw = if (trust == McpTrust.BUILT_IN) clean(tool) else "mcp_" + clean(serverId).take(12) + "_" + clean(tool)
            val n = raw.take(41).trimEnd('_')
            return if (n.firstOrNull()?.isLetter() == true && n.length >= 2) n else "mcp_$n".take(41)
        }
    }
}

data class McpConnection(val id: String, val displayName: String, val trust: McpTrust, val client: McpClient, val info: ServerInfo, val tools: List<McpTool2AgentTool>)

/**
 * Holds live MCP connections and turns their tools into agent tools. Thread-safe. The app adds the built-in servers at
 * start-up and user-enabled on-device (binder) servers / LAN servers (connected flavor) on demand.
 */
class McpHub {
    private val mutex = Mutex()
    private val connections = LinkedHashMap<String, McpConnection>()

    suspend fun connect(id: String, displayName: String, transport: McpTransport, trust: McpTrust): McpConnection = mutex.withLock {
        connections.remove(id)?.client?.close()
        val client = McpClient(transport)
        val info = client.initialize()
        val tools = if (info.capabilities.containsKey("tools")) client.listTools().map { McpTool2AgentTool(client, it, id, trust) } else emptyList()
        McpConnection(id, displayName, trust, client, info, tools).also { connections[id] = it }
    }

    suspend fun disconnect(id: String) = mutex.withLock { connections.remove(id)?.client?.close() }

    suspend fun all(): List<McpConnection> = mutex.withLock { connections.values.toList() }

    /** Agent tools of every connection, de-duplicated by name (first wins). */
    suspend fun tools(): List<Tool> = mutex.withLock {
        val seen = HashSet<String>()
        connections.values.flatMap { it.tools }.filter { seen.add(it.spec.name) }
    }
}

internal fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
