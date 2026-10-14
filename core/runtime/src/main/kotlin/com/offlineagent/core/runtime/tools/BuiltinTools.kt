package com.offlineagent.core.runtime.tools

import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.ui.UiDoc
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import com.offlineagent.core.runtime.ui.UiSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.E

// ---------------------------------------------------------------- ports (implemented by the app)

data class SearchHit(val documentId: Long, val title: String, val text: String, val score: Float)
data class DocInfo(val id: Long, val title: String, val chars: Int)

interface DocumentPort {
    suspend fun search(query: String, k: Int): List<SearchHit>
    suspend fun list(): List<DocInfo>
    /** Text of the document whose title best matches [titleOrId] (or null). */
    suspend fun read(titleOrId: String, maxChars: Int): Pair<DocInfo, String>?
}

interface SummarizerPort { suspend fun summarize(text: String, maxWords: Int): String }
interface NotesPort { suspend fun createNote(title: String, body: String): Long }
interface MemoryPort {
    suspend fun read(query: String): List<Pair<String, String>>
    suspend fun write(key: String, value: String)
}
interface CanvasPort {
    /** Shows [doc] in the chat's canvas. [newCanvas]=false creates a new version of the current one. Returns version. */
    suspend fun render(doc: UiDoc, newCanvas: Boolean): Int
    suspend fun current(): UiDoc?
}
interface IntentPort {
    suspend fun shareText(text: String)
    suspend fun createCalendarEvent(title: String, date: LocalDate, start: LocalTime, durationMinutes: Int, description: String)
}

// ---------------------------------------------------------------- tools

class CalculatorTool : Tool {
    override val spec = ToolSpec(
        name = "calculator",
        description = "Evaluate an arithmetic expression exactly (+ - * / ^ %, parentheses, sqrt, sin, cos, tan, log, ln, abs, round, pi, e).",
        input = objSchema("expression" to Schema.Str("e.g. (12.5*4)/3", maxLength = 200, minLength = 1)),
        permission = Permission.READ_ONLY,
        timeoutMs = 1_000,
        outputDescription = "{\"result\": number}",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = try {
        val v = Calculator.evaluate(args.str("expression"))
        okJson("expression" to args.str("expression"), "result" to Calculator.format(v))
    } catch (e: IllegalArgumentException) {
        ToolResult.Error("calculator: ${e.message}")
    }
}

/** Safe recursive-descent evaluator. No variables, no code, bounded input. */
object Calculator {
    fun evaluate(expr: String): Double {
        require(expr.length <= 200) { "expression too long" }
        val p = Parser(expr.replace("×", "*").replace("÷", "/").replace(",", ""))
        val v = p.parseExpression()
        p.skipWs()
        require(p.pos == p.s.length) { "unexpected '${p.s.substring(p.pos).take(10)}'" }
        require(!v.isNaN() && !v.isInfinite()) { "result is not a finite number" }
        return v
    }

    fun format(v: Double): String = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString()
        else "%.10g".format(v).trimEnd('0').trimEnd('.')

    private class Parser(val s: String) {
        var pos = 0
        var depth = 0
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun eat(c: Char): Boolean { skipWs(); if (pos < s.length && s[pos] == c) { pos++; return true }; return false }

        fun parseExpression(): Double {
            require(++depth < 64) { "expression nested too deeply" }
            var v = parseTerm()
            while (true) v = when {
                eat('+') -> v + parseTerm()
                eat('-') -> v - parseTerm()
                else -> { depth--; return v }
            }
        }

        fun parseTerm(): Double {
            var v = parseFactor()
            while (true) v = when {
                eat('*') -> v * parseFactor()
                eat('/') -> { val d = parseFactor(); require(d != 0.0) { "division by zero" }; v / d }
                eat('%') -> { val d = parseFactor(); require(d != 0.0) { "modulo by zero" }; v % d }
                else -> return v
            }
        }

        fun parseFactor(): Double {
            val base = parseUnary()
            return if (eat('^')) Math.pow(base, parseFactor()) else base
        }

        fun parseUnary(): Double = when {
            eat('-') -> -parseUnary()
            eat('+') -> parseUnary()
            else -> parsePrimary()
        }

        fun parsePrimary(): Double {
            skipWs()
            if (eat('(')) { val v = parseExpression(); require(eat(')')) { "missing ')'" }; return v }
            val start = pos
            if (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) {
                while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
                if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E') && pos + 1 < s.length && (s[pos + 1].isDigit() || s[pos + 1] == '-')) {
                    pos += 2; while (pos < s.length && s[pos].isDigit()) pos++
                }
                return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
            }
            while (pos < s.length && s[pos].isLetter()) pos++
            val name = s.substring(start, pos).lowercase()
            require(name.isNotEmpty()) { if (pos < s.length) "unexpected '${s[pos]}'" else "unexpected end" }
            when (name) { "pi" -> return PI; "e" -> return E }
            require(eat('(')) { "unknown name '$name'" }
            val a = parseExpression()
            require(eat(')')) { "missing ')'" }
            return when (name) {
                "sqrt" -> { require(a >= 0) { "sqrt of negative" }; Math.sqrt(a) }
                "sin" -> Math.sin(a); "cos" -> Math.cos(a); "tan" -> Math.tan(a)
                "log" -> { require(a > 0) { "log of non-positive" }; Math.log10(a) }
                "ln" -> { require(a > 0) { "ln of non-positive" }; Math.log(a) }
                "abs" -> Math.abs(a); "round" -> Math.round(a).toDouble()
                "floor" -> Math.floor(a); "ceil" -> Math.ceil(a)
                else -> throw IllegalArgumentException("unknown function '$name'")
            }
        }
    }
}

class DateTimeTool(private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val spec = ToolSpec(
        "get_datetime", "Current local date, weekday and time on the phone.",
        objSchema(), Permission.READ_ONLY, timeoutMs = 500, outputDescription = "{\"date\",\"weekday\",\"time\"}",
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val n = now()
        return okJson("date" to n.toLocalDate().toString(), "weekday" to n.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() },
            "time" to n.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
    }
}

class SearchDocsTool(private val docs: DocumentPort) : Tool {
    override val spec = ToolSpec(
        "search_docs", "Search the user's imported documents (offline RAG). Returns the most relevant passages with titles.",
        objSchema("query" to Schema.Str("what to look for", maxLength = 200, minLength = 2), "k" to Schema.Num(min = 1.0, max = 6.0, integer = true)),
        Permission.READ_ONLY, timeoutMs = 20_000, outputDescription = "{\"results\":[{\"n\",\"title\",\"text\"}]}",
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val hits = docs.search(args.str("query"), args.int("k", 4).coerceIn(1, 6))
        if (hits.isEmpty()) return okJson("results" to JsonArray(emptyList()), "note" to "no matching passages (are documents imported?)")
        return ToolResult.Ok(buildJsonObject {
            put("results", buildJsonArray {
                hits.forEachIndexed { i, h -> add(buildJsonObject { put("n", i + 1); put("title", h.title); put("text", h.text.take(600)) }) }
            })
        }, "${hits.size} passages")
    }
}

class ReadDocumentTool(private val docs: DocumentPort) : Tool {
    override val spec = ToolSpec(
        "read_document", "Read the beginning of one imported document by (part of) its title. Use \"list\" as title to list documents.",
        objSchema("title" to Schema.Str(maxLength = 120, minLength = 1), "max_chars" to Schema.Num(min = 200.0, max = 4000.0, integer = true)),
        Permission.READ_ONLY, timeoutMs = 10_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title")
        if (title.equals("list", true) || title == "*") {
            val all = docs.list()
            return ToolResult.Ok(buildJsonObject { put("documents", buildJsonArray { all.forEach { add(buildJsonObject { put("title", it.title); put("chars", it.chars) }) } }) })
        }
        val (info, text) = docs.read(title, args.int("max_chars", 2000).coerceIn(200, 4000))
            ?: return ToolResult.Error("no document matching '$title'. Available: " + docs.list().joinToString { it.title }.take(300))
        return okJson("title" to info.title, "text" to text)
    }
}

class SummarizeTool(private val summarizer: SummarizerPort, private val docs: DocumentPort) : Tool {
    override val spec = ToolSpec(
        "summarize", "Summarise text, or a whole imported document when 'source' is its title.",
        objSchema("source" to Schema.Str("document title or the text itself", maxLength = 4000, minLength = 2), "max_words" to Schema.Num(min = 20.0, max = 300.0, integer = true)),
        Permission.READ_ONLY, timeoutMs = 120_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val src = args.str("source")
        val text = docs.read(src, 6000)?.second ?: src
        val s = summarizer.summarize(text, args.int("max_words", 120).coerceIn(20, 300))
        return okJson("summary" to s)
    }
}

class CreateNoteTool(private val notes: NotesPort) : Tool {
    override val spec = ToolSpec(
        "create_note", "Save a note/draft inside the app.",
        objSchema("title" to Schema.Str(maxLength = 120, minLength = 1), "body" to Schema.Str(maxLength = 4000, minLength = 1)),
        Permission.WRITE_LOCAL, sideEffects = "Creates a note in this app's private storage.", timeoutMs = 5_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        okJson("note_id" to notes.createNote(args.str("title"), args.str("body")), "saved" to true)
}

class MemoryReadTool(private val mem: MemoryPort) : Tool {
    override val spec = ToolSpec(
        "memory_read", "Look up facts the user previously approved saving (preferences, names, goals).",
        objSchema("query" to Schema.Str(maxLength = 120, minLength = 1)), Permission.READ_ONLY, timeoutMs = 5_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = ToolResult.Ok(buildJsonObject {
        put("facts", buildJsonArray { mem.read(args.str("query")).take(8).forEach { (k, v) -> add(buildJsonObject { put("key", k); put("value", v) }) } })
    })
}

class MemoryWriteTool(private val mem: MemoryPort) : Tool {
    override val spec = ToolSpec(
        "memory_write", "Remember a fact about the user for later (requires the user's approval).",
        objSchema("key" to Schema.Str(maxLength = 60, minLength = 1), "value" to Schema.Str(maxLength = 400, minLength = 1)),
        Permission.WRITE_LOCAL, sideEffects = "Stores this fact in the app's on-device memory.", timeoutMs = 5_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        mem.write(args.str("key"), args.str("value")); return okJson("saved" to true)
    }
}

/** render_ui (new canvas) and canvas_update (new version of the open canvas). Args are a full UI document. */
class RenderUiTool(private val canvas: CanvasPort, private val update: Boolean, types: Collection<String> = UiSchema.ALL_TYPES) : Tool {
    override val spec = ToolSpec(
        if (update) "canvas_update" else "render_ui",
        if (update) "Replace the open canvas with an updated version (full document)." else "Show the result as rich UI components in the Canvas.",
        UiSchema.docSchema(types), Permission.UI, sideEffects = "Updates the canvas in this chat.", timeoutMs = 5_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult =
        when (val r = UiParser.parseElement(args)) {
            is UiParseResult.Success -> {
                val v = canvas.render(r.doc, newCanvas = !update || canvas.current() == null)
                okJson("rendered" to true, "version" to v, "blocks" to r.doc.blocks.size)
            }
            is UiParseResult.Failure -> ToolResult.Error("invalid UI document: " + r.errors.take(3).joinToString("; "))
        }
}

class ShareTextTool(private val intents: IntentPort) : Tool {
    override val spec = ToolSpec(
        "share_text", "Open Android's share sheet with this text (the user picks the app).",
        objSchema("text" to Schema.Str(maxLength = 4000, minLength = 1)),
        Permission.EXTERNAL_INTENT, sideEffects = "Opens the share sheet; text leaves this app only if you pick a target.", timeoutMs = 10_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult { intents.shareText(args.str("text")); return okJson("opened" to "share_sheet") }
}

class CalendarEventTool(private val intents: IntentPort) : Tool {
    override val spec = ToolSpec(
        "create_calendar_event", "Prepare a calendar event in the phone's calendar app (the user confirms it there).",
        objSchema(
            "title" to Schema.Str(maxLength = 120, minLength = 1),
            "date" to Schema.Str("YYYY-MM-DD", maxLength = 10, minLength = 10),
            "start" to Schema.Str("HH:MM 24h", maxLength = 5, minLength = 4),
            "duration_minutes" to Schema.Num(min = 5.0, max = 720.0, integer = true),
            "description" to Schema.Str(maxLength = 1000),
        ),
        Permission.EXTERNAL_INTENT, sideEffects = "Opens your calendar app with a pre-filled event.", timeoutMs = 10_000,
    )
    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val date = runCatching { LocalDate.parse(args.str("date")) }.getOrNull() ?: return ToolResult.Error("date must be YYYY-MM-DD")
        val start = runCatching { LocalTime.parse(args.str("start").padStart(5, '0')) }.getOrNull() ?: return ToolResult.Error("start must be HH:MM")
        intents.createCalendarEvent(args.str("title"), date, start, args.int("duration_minutes", 60), args.str("description"))
        return okJson("opened" to "calendar_app")
    }
}
