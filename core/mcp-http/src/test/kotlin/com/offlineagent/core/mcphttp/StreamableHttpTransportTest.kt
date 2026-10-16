package com.offlineagent.core.mcphttp

import com.offlineagent.core.runtime.mcp.McpClient
import com.offlineagent.core.runtime.mcp.McpServer
import com.offlineagent.core.runtime.mcp.McpTool
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader
import java.net.InetSocketAddress

class StreamableHttpTransportTest {
    private fun serve(sse: Boolean): Pair<HttpServer, MutableList<String?>> {
        val mcp = McpServer("lan-demo").apply {
            tool(McpTool("echo", "echo", buildJsonObject { put("type", "object") })) { McpServer.text("echo:" + it.toString()) }
        }
        val sessions = mutableListOf<String?>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/mcp") { ex ->
            sessions += ex.requestHeaders.getFirst("Mcp-Session-Id")
            if (ex.requestMethod == "DELETE") { ex.sendResponseHeaders(200, -1); ex.close(); return@createContext }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val reply = runBlocking { mcp.handleString(body) }
            if (body.contains("\"initialize\"")) ex.responseHeaders.add("Mcp-Session-Id", "sess-42")
            if (reply == null) { ex.sendResponseHeaders(202, -1); ex.close(); return@createContext }
            val bytes = if (sse) {
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ": keepalive\n\nevent: message\ndata: $reply\n\n".toByteArray()
            } else {
                ex.responseHeaders.add("Content-Type", "application/json")
                reply.toByteArray()
            }
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        return http to sessions
    }

    private fun roundTrip(sse: Boolean) = runBlocking {
        val (http, sessions) = serve(sse)
        try {
            val t = StreamableHttpTransport("http://127.0.0.1:${http.address.port}/mcp")
            val c = McpClient(t)
            assertEquals("lan-demo", c.initialize().name)
            assertEquals("sess-42", t.sessionId)
            assertEquals(listOf("echo"), c.listTools().map { it.name })
            assertTrue(c.callTool("echo", JsonObject(mapOf())).text().startsWith("echo:"))
            c.close()
            assertEquals(null, sessions.first())
            assertTrue(sessions.drop(1).all { it == "sess-42" })
        } finally { http.stop(0) }
    }

    @Test fun jsonResponses() = roundTrip(sse = false)
    @Test fun sseResponses() = roundTrip(sse = true)

    @Test fun sseParserHandlesMultilineAndComments() {
        val got = mutableListOf<Pair<String, String>>()
        SseParser.events(BufferedReader(StringReader(": hi\nevent: message\ndata: {\"a\":\ndata: 1}\n\ndata: x\n"))) { e, d -> got += e to d }
        assertEquals(listOf("message" to "{\"a\":\n1}", "message" to "x"), got)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNonHttp() { StreamableHttpTransport("file:///etc/passwd") }
}
