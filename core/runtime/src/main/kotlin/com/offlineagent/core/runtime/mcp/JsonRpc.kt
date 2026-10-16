package com.offlineagent.core.runtime.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** JSON-RPC 2.0 messages (the wire format of the Model Context Protocol). */
sealed interface RpcMessage {
    data class Request(val id: JsonElement, val method: String, val params: JsonObject?) : RpcMessage
    data class Notification(val method: String, val params: JsonObject?) : RpcMessage
    data class Response(val id: JsonElement, val result: JsonElement?, val error: RpcError?) : RpcMessage
}

data class RpcError(val code: Int, val message: String, val data: JsonElement? = null) {
    companion object {
        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}

class McpException(val error: RpcError) : Exception("MCP error ${error.code}: ${error.message}")

object JsonRpc {
    val json = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = false; explicitNulls = false }

    fun encode(m: RpcMessage): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        when (m) {
            is RpcMessage.Request -> { put("id", m.id); put("method", m.method); m.params?.let { put("params", it) } }
            is RpcMessage.Notification -> { put("method", m.method); m.params?.let { put("params", it) } }
            is RpcMessage.Response -> {
                put("id", m.id)
                if (m.error != null) put("error", buildJsonObject {
                    put("code", m.error.code); put("message", m.error.message); m.error.data?.let { put("data", it) }
                }) else put("result", m.result ?: JsonObject(emptyMap()))
            }
        }
    }

    fun encodeString(m: RpcMessage): String = encode(m).toString()

    /** Parses one message. Throws [McpException] with PARSE_ERROR / INVALID_REQUEST on bad input. */
    fun decode(text: String): RpcMessage {
        val el = try { json.parseToJsonElement(text) } catch (e: Exception) { throw McpException(RpcError(RpcError.PARSE_ERROR, "parse error")) }
        return decode(el as? JsonObject ?: throw McpException(RpcError(RpcError.INVALID_REQUEST, "not an object")))
    }

    fun decode(o: JsonObject): RpcMessage {
        if ((o["jsonrpc"] as? JsonPrimitive)?.content != "2.0") throw McpException(RpcError(RpcError.INVALID_REQUEST, "jsonrpc must be \"2.0\""))
        val id = o["id"]?.takeIf { it !is JsonNull }
        val method = (o["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val params = o["params"] as? JsonObject
        return when {
            method != null && id != null -> RpcMessage.Request(id, method, params)
            method != null -> RpcMessage.Notification(method, params)
            id != null -> {
                val e = o["error"] as? JsonObject
                RpcMessage.Response(id, o["result"], e?.let {
                    RpcError((it["code"] as? JsonPrimitive)?.intOrNull ?: RpcError.INTERNAL_ERROR, (it["message"] as? JsonPrimitive)?.content ?: "error", it["data"])
                })
            }
            else -> throw McpException(RpcError(RpcError.INVALID_REQUEST, "neither request, notification nor response"))
        }
    }

    fun error(id: JsonElement, code: Int, message: String) = RpcMessage.Response(id, null, RpcError(code, message))
}
