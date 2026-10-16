package com.offlineagent.sample.mcp

import com.offlineagent.core.runtime.mcp.McpPrompt
import com.offlineagent.core.runtime.mcp.McpResource
import com.offlineagent.core.runtime.mcp.McpServer
import com.offlineagent.core.runtime.mcp.McpTool
import com.offlineagent.core.runtime.mcp.ToolAnnotations
import com.offlineagent.mcp.McpBinderService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A complete on-device MCP server in ~60 lines: three tools, one resource, one prompt. */
class StudyToolsMcpService : McpBinderService() {
    override fun createServer(): McpServer = McpServer("study-tools", "1.0", "Offline study helpers").apply {
        tool(McpTool("convert_units", "Convert between common units (km, mi, m, ft, kg, lb, c, f).", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") { put("type", "number") }
                putJsonObject("from") { put("type", "string"); put("enum", buildJsonArray { listOf("km", "mi", "m", "ft", "kg", "lb", "c", "f").forEach { add(JsonPrimitive(it)) } }) }
                putJsonObject("to") { put("type", "string"); put("enum", buildJsonArray { listOf("km", "mi", "m", "ft", "kg", "lb", "c", "f").forEach { add(JsonPrimitive(it)) } }) }
            }
            put("required", buildJsonArray { add(JsonPrimitive("value")); add(JsonPrimitive("from")); add(JsonPrimitive("to")) })
        }, ToolAnnotations(title = "Unit converter", readOnlyHint = true, openWorldHint = false))) { a ->
            val v = (a["value"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return@tool McpServer.text("value must be a number", true)
            val from = (a["from"] as? JsonPrimitive)?.contentOrNull; val to = (a["to"] as? JsonPrimitive)?.contentOrNull
            val r = convert(v, from, to) ?: return@tool McpServer.text("cannot convert $from to $to", true)
            McpServer.text("%.4f %s = %.4f %s".format(v, from, r, to))
        }
        tool(McpTool("cgpa_to_percentage", "Convert a 10-point CGPA to percentage (common Indian formula x 9.5).", buildJsonObject {
            put("type", "object"); putJsonObject("properties") { putJsonObject("cgpa") { put("type", "number"); put("minimum", 0); put("maximum", 10) } }
            put("required", buildJsonArray { add(JsonPrimitive("cgpa")) })
        }, ToolAnnotations(readOnlyHint = true, openWorldHint = false))) { a ->
            val c = (a["cgpa"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: return@tool McpServer.text("cgpa must be a number", true)
            McpServer.text("%.2f CGPA ≈ %.1f%%".format(c, c * 9.5))
        }
        tool(McpTool("pomodoro_plan", "Split study minutes into 25/5 Pomodoro blocks.", buildJsonObject {
            put("type", "object"); putJsonObject("properties") { putJsonObject("minutes") { put("type", "integer"); put("minimum", 25); put("maximum", 600) } }
            put("required", buildJsonArray { add(JsonPrimitive("minutes")) })
        }, ToolAnnotations(readOnlyHint = true, openWorldHint = false))) { a ->
            val m = (a["minutes"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt() ?: 25
            val blocks = m / 30
            McpServer.text("$blocks Pomodoros of 25 min with 5 min breaks (+ a 15 min break every 4).")
        }
        resource(McpResource("study://tips", "Study tips", "Five evidence-based study tips", "text/markdown")) {
            "- Spaced repetition\n- Active recall\n- Interleaving\n- Sleep before exams\n- Teach it to someone"
        }
        prompt(McpPrompt("exam_plan", "A prompt for planning exam revision", listOf("subject" to true, "days" to false))) { args ->
            "Make a ${args["days"] ?: "7"}-day revision plan for ${args["subject"]}, with active recall each day."
        }
    }

    private fun convert(v: Double, from: String?, to: String?): Double? {
        val len = mapOf("km" to 1000.0, "mi" to 1609.344, "m" to 1.0, "ft" to 0.3048)
        val mass = mapOf("kg" to 1.0, "lb" to 0.45359237)
        return when {
            from in len && to in len -> v * len[from]!! / len[to]!!
            from in mass && to in mass -> v * mass[from]!! / mass[to]!!
            from == "c" && to == "f" -> v * 9 / 5 + 32
            from == "f" && to == "c" -> (v - 32) * 5 / 9
            from == to -> v
            else -> null
        }
    }
}
