package com.offlineagent.core.mcphttp

import com.offlineagent.core.runtime.mcp.JsonRpc
import com.offlineagent.core.runtime.mcp.McpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Parses a text/event-stream body into the `data:` payloads of its events. */
object SseParser {
    fun events(reader: BufferedReader, onEvent: (event: String, data: String) -> Unit) {
        var event = "message"
        val data = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            when {
                line.isEmpty() -> {
                    if (data.isNotEmpty()) onEvent(event, data.toString().removeSuffix("\n"))
                    event = "message"; data.clear()
                }
                line.startsWith(":") -> Unit // comment / keep-alive
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> data.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                else -> Unit // id:, retry: and unknown fields are ignored
            }
        }
        if (data.isNotEmpty()) onEvent(event, data.toString().removeSuffix("\n"))
    }
}

/**
 * MCP "Streamable HTTP" transport (spec 2025-03-26 / 2025-06-18): every client message is an HTTP POST to one endpoint;
 * the server answers with application/json or a text/event-stream carrying one or more JSON-RPC messages. The
 * Mcp-Session-Id header from initialize is echoed on later requests; DELETE ends the session. Bearer token optional.
 */
class StreamableHttpTransport(
    private val endpoint: String,
    private val bearerToken: String? = null,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 120_000,
) : McpTransport {
    override val description = "http:$endpoint"
    private var sink: (suspend (JsonObject) -> Unit)? = null
    @Volatile var sessionId: String? = null
        private set
    @Volatile private var protocolVersion: String? = null

    init {
        val u = URL(endpoint)
        require(u.protocol == "http" || u.protocol == "https") { "MCP endpoint must be http(s)" }
    }

    override suspend fun start(onMessage: suspend (JsonObject) -> Unit) { sink = onMessage }

    override suspend fun send(message: JsonObject) {
        val incoming = withContext(Dispatchers.IO) { post(message) }
        for (m in incoming) {
            // Remember the negotiated protocol version (sent as MCP-Protocol-Version on later requests).
            (m["result"] as? JsonObject)?.get("protocolVersion")?.let { protocolVersion = it.toString().trim('"') }
            sink?.invoke(m)
        }
    }

    private fun post(message: JsonObject): List<JsonObject> {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            sessionId?.let { c.setRequestProperty("Mcp-Session-Id", it) }
            protocolVersion?.let { c.setRequestProperty("MCP-Protocol-Version", it) }
            bearerToken?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            c.outputStream.use { it.write(message.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            c.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            if (code == 202 || code == 204) return emptyList()
            if (code == 404 && sessionId != null) { sessionId = null; throw java.io.IOException("MCP session expired (404); reconnect") }
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader()?.use { it.readText() }?.take(300) ?: ""
                throw java.io.IOException("MCP server HTTP $code $err")
            }
            val type = c.contentType ?: ""
            val out = ArrayList<JsonObject>()
            c.inputStream.use { ins ->
                val reader = BufferedReader(InputStreamReader(ins, Charsets.UTF_8))
                if (type.startsWith("text/event-stream")) {
                    SseParser.events(reader) { _, data -> runCatching { JsonRpc.json.parseToJsonElement(data).jsonObject }.getOrNull()?.let(out::add) }
                } else {
                    val text = reader.readText()
                    if (text.isNotBlank()) {
                        val el = JsonRpc.json.parseToJsonElement(text)
                        if (el is JsonObject) out += el else if (el is kotlinx.serialization.json.JsonArray) el.forEach { (it as? JsonObject)?.let(out::add) }
                    }
                }
            }
            return out
        } finally {
            c.disconnect()
        }
    }

    override suspend fun close() {
        val sid = sessionId ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(endpoint).openConnection() as HttpURLConnection
                c.requestMethod = "DELETE"; c.setRequestProperty("Mcp-Session-Id", sid); c.connectTimeout = 3000
                c.responseCode; c.disconnect()
            }
        }
        sessionId = null
        sink = null
    }
}
