package com.offlineagent.core.runtime.tools

import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.ValidationError
import com.offlineagent.core.runtime.schema.validate
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a tool can affect. Drives the policy engine (approval) and the UI badges. */
enum class Permission(val label: String) {
    /** Reads app-local data (documents, memory) or computes. No side effects. */
    READ_ONLY("read-only"),
    /** Draws in the app's own Canvas. */
    UI("updates canvas"),
    /** Writes app-local data (notes, memory). */
    WRITE_LOCAL("writes app data"),
    /** Hands data to another app via an Android intent (share sheet, calendar). Always needs approval. */
    EXTERNAL_INTENT("leaves the app"),
}

data class ToolSpec(
    val name: String,
    val description: String,
    val input: Schema.Obj,
    val permission: Permission,
    /** Human description of side effects, shown in the approval dialog. */
    val sideEffects: String = "none",
    val timeoutMs: Long = 15_000,
    /** Output shape description for the model (results are JSON objects). */
    val outputDescription: String = "",
) {
    init { require(Regex("[a-z][a-z0-9_]{1,40}").matches(name)) { "bad tool name $name" } }
}

data class ToolCall(val tool: String, val args: JsonObject)

sealed interface ToolResult {
    val json: JsonObject

    data class Ok(override val json: JsonObject, val summary: String = "") : ToolResult
    data class Error(val message: String, val retryable: Boolean = true) : ToolResult {
        override val json: JsonObject get() = buildJsonObject { put("error", message) }
    }
}

/** Per-call context handed to tools by the agent runtime. */
data class ToolContext(val runId: String, val chatId: Long?)

interface Tool {
    val spec: ToolSpec
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult
}

class ToolRegistry(tools: List<Tool> = emptyList()) {
    private val map = LinkedHashMap<String, Tool>()

    init { tools.forEach { register(it) } }

    fun register(tool: Tool) {
        require(tool.spec.name !in map) { "duplicate tool ${tool.spec.name}" }
        map[tool.spec.name] = tool
    }

    operator fun get(name: String): Tool? = map[name]
    val specs: List<ToolSpec> get() = map.values.map { it.spec }
    val names: Set<String> get() = map.keys

    /** Validates a proposed call. Returns the errors (empty = OK). */
    fun validate(call: ToolCall): List<ValidationError> {
        val tool = map[call.tool] ?: return listOf(ValidationError("tool", "unknown tool '${call.tool}'"))
        return tool.spec.input.validate(call.args, "args")
    }
}

/** Builder helpers for tool schemas. */
fun objSchema(vararg props: Pair<String, Schema>, description: String? = null) = Schema.Obj(props.toList(), description)
fun JsonObject.str(name: String): String = (this[name] as? JsonPrimitive)?.content ?: ""
fun JsonObject.int(name: String, default: Int): Int = (this[name] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: default
fun okJson(vararg pairs: Pair<String, Any?>): ToolResult.Ok = ToolResult.Ok(buildJsonObject {
    pairs.forEach { (k, v) ->
        when (v) {
            null -> put(k, kotlinx.serialization.json.JsonNull)
            is String -> put(k, v)
            is Number -> put(k, v)
            is Boolean -> put(k, v)
            is JsonElement -> put(k, v)
            else -> put(k, v.toString())
        }
    }
})
