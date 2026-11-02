package com.offlineagent.app.onyx

import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.okJson
import com.offlineagent.core.runtime.tools.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Connected flavor: network-dependent tools. Each is badged "Needs network"; when offline the orchestrator puts the
 * (already approved) call in the outbox and WorkManager runs it once a validated connection is back.
 */
object NetworkTools {
    val names: Set<String> = setOf("fetch_url")

    fun create(): List<Tool> = listOf(FetchUrl)

    private object FetchUrl : Tool {
        override val spec = ToolSpec("fetch_url", "Download a web page (HTTPS) and return its text (first 8000 characters).",
            Schema.Obj(listOf("url" to Schema.Str("https:// address", 500, 9))), Permission.EXTERNAL_INTENT, "Contacts a website over the internet.", timeoutMs = 30_000)

        override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
            val url = args.str("url").trim()
            if (!url.startsWith("https://")) return@withContext ToolResult.Error("Only https:// links", retryable = false)
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("User-Agent", "ONYX-Android") }
                val code = c.responseCode
                if (code >= 400) return@withContext ToolResult.Error("HTTP $code", retryable = code >= 500 || code == 429)
                val body = c.inputStream.bufferedReader().use { r -> CharArray(200_000).let { buf -> val n = r.read(buf); if (n > 0) String(buf, 0, n) else "" } }
                val text = body.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("&nbsp;|&#160;"), " ").replace(Regex("\\s+"), " ").trim()
                okJson("url" to url, "text" to text.take(8000))
            } catch (e: java.io.IOException) { ToolResult.Error("Network error: ${e.message}", retryable = true) }
        }
    }
}
