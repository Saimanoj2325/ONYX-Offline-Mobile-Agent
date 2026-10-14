package com.offlineagent.core.runtime.ui

import com.offlineagent.core.runtime.schema.validate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A validated Intelligent-UI document. */
data class UiDoc(val title: String, val blocks: List<UiBlock>, val version: Int = UiSchema.VERSION)

sealed interface UiBlock {
    val type: String

    data class Markdown(val text: String) : UiBlock { override val type = "markdown" }
    data class Heading(val text: String, val level: Int) : UiBlock { override val type = "heading" }
    data class Callout(val tone: String, val title: String, val text: String) : UiBlock { override val type = "callout" }
    data class KeyValue(val title: String, val items: List<Pair<String, String>>) : UiBlock { override val type = "keyvalue" }
    data class Table(val title: String, val columns: List<String>, val rows: List<List<String>>) : UiBlock { override val type = "table" }
    data class ListBlock(val ordered: Boolean, val items: List<String>) : UiBlock { override val type = "list" }
    data class Checklist(val title: String, val items: List<CheckItem>) : UiBlock { override val type = "checklist" }
    data class Steps(val title: String, val items: List<Step>) : UiBlock { override val type = "steps" }
    data class Chart(val kind: String, val title: String, val labels: List<String>, val series: List<Series>) : UiBlock { override val type = "chart" }
    data class Code(val language: String, val code: String) : UiBlock { override val type = "code" }
    data class Flashcards(val title: String, val cards: List<Card>) : UiBlock { override val type = "flashcards" }
    data class Quiz(val title: String, val questions: List<Question>) : UiBlock { override val type = "quiz" }
    data class Image(val ref: String, val caption: String) : UiBlock { override val type = "image" }
    data class Comparison(val title: String, val options: List<String>, val criteria: List<Criterion>) : UiBlock { override val type = "comparison" }
    data class Actions(val buttons: List<Action>) : UiBlock { override val type = "actions" }
}

data class CheckItem(val text: String, val done: Boolean)
data class Step(val title: String, val detail: String)
data class Series(val name: String, val values: List<Double>)
data class Card(val front: String, val back: String)
data class Question(val question: String, val options: List<String>, val answer: Int, val explanation: String)
data class Criterion(val name: String, val values: List<String>)
data class Action(val label: String, val prompt: String)

sealed interface UiParseResult {
    data class Success(val doc: UiDoc, val warnings: List<String>) : UiParseResult
    /** Not usable as UI: show [fallbackMarkdown] as a normal markdown answer instead. */
    data class Failure(val errors: List<String>, val fallbackMarkdown: String) : UiParseResult
}

/**
 * Trusted parser/validator for model-produced UI JSON. Never throws. Repairs what is safe to repair (ragged table rows,
 * chart series of the wrong length, out-of-range quiz answers), drops invalid blocks with a warning, and fails over to
 * markdown when nothing usable is left.
 */
object UiParser {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(text: String): UiParseResult {
        val candidate = extractJson(text) ?: return UiParseResult.Failure(listOf("no JSON object found"), fallbackFor(text))
        val root = runCatching { json.parseToJsonElement(candidate) }.getOrNull() as? JsonObject
            ?: return UiParseResult.Failure(listOf("malformed JSON"), fallbackFor(text))
        return parseElement(root, text)
    }

    fun parseElement(root: JsonObject, original: String = root.toString()): UiParseResult {
        val warnings = mutableListOf<String>()
        val version = (root["v"] as? JsonPrimitive)?.intOrNull ?: UiSchema.VERSION
        if (version > UiSchema.VERSION) return UiParseResult.Failure(listOf("unsupported UI schema version $version"), fallbackFor(original))
        val title = (root["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(120) ?: ""
        val blocksJson = root["blocks"] as? JsonArray
            ?: return UiParseResult.Failure(listOf("missing blocks array"), fallbackFor(original))
        val blocks = mutableListOf<UiBlock>()
        blocksJson.forEachIndexed { i, el ->
            if (blocks.size >= UiSchema.MAX_BLOCKS) { warnings += "blocks after #${UiSchema.MAX_BLOCKS} dropped"; return@forEachIndexed }
            val o = el as? JsonObject ?: run { warnings += "block $i is not an object"; return@forEachIndexed }
            val type = (o["type"] as? JsonPrimitive)?.content
            val schema = UiSchema.blockSchemas[type] ?: run { warnings += "block $i: unknown type '$type'"; return@forEachIndexed }
            val normalized = normalize(type!!, o, warnings, i)
            val errs = schema.validate(normalized, "blocks[$i]")
            if (errs.isNotEmpty()) { warnings += errs.take(3).map { it.toString() }; return@forEachIndexed }
            blocks += toBlock(type, normalized)
        }
        if (blocks.isEmpty()) return UiParseResult.Failure(listOf("no valid blocks") + warnings, fallbackFor(original))
        return UiParseResult.Success(UiDoc(title, blocks, version), warnings)
    }

    /** Strips code fences / chatter around the outermost JSON object. */
    fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private fun fallbackFor(text: String): String {
        val t = text.trim()
        // Raw JSON is useless to read: show a short notice instead of a wall of braces.
        return if (t.startsWith("{") || t.startsWith("```")) "_The model's structured answer could not be displayed as components._\n\n" +
            "```json\n" + t.removePrefix("```json").removeSuffix("```").trim().take(1500) + "\n```" else t
    }

    // ---- repairs (safe, lossy only in obviously-broken cases) ----
    private fun normalize(type: String, o: JsonObject, warnings: MutableList<String>, i: Int): JsonObject {
        // Fill missing simple fields with defaults so a nearly-right block survives.
        val schema = UiSchema.blockSchemas.getValue(type)
        val m = o.toMutableMap()
        for ((name, ps) in schema.properties) {
            if (name !in m) {
                val def: JsonElement? = when (ps) {
                    is com.offlineagent.core.runtime.schema.Schema.Str -> if (ps.enum == null) JsonPrimitive("") else JsonPrimitive(ps.enum.first())
                    is com.offlineagent.core.runtime.schema.Schema.Bool -> JsonPrimitive(false)
                    is com.offlineagent.core.runtime.schema.Schema.Num -> if (name == "level") JsonPrimitive(2) else null
                    else -> null
                }
                if (def != null) { m[name] = def; warnings += "block $i: '$name' missing, defaulted" }
            }
        }
        (m.keys - schema.properties.map { it.first }.toSet()).forEach { m.remove(it) }
        when (type) {
            "table" -> {
                val cols = (m["columns"] as? JsonArray)?.size ?: 0
                val rows = m["rows"] as? JsonArray
                if (cols > 0 && rows != null) {
                    m["rows"] = JsonArray(rows.mapNotNull { r ->
                        val cells = (r as? JsonArray)?.map { it as? JsonPrimitive ?: JsonPrimitive(it.toString()) } ?: return@mapNotNull null
                        JsonArray(cells.take(cols) + List((cols - cells.size).coerceAtLeast(0)) { JsonPrimitive("") })
                    }.take(12))
                }
            }
            "chart" -> {
                val n = (m["labels"] as? JsonArray)?.size ?: 0
                val series = m["series"] as? JsonArray
                if (n > 0 && series != null) {
                    m["series"] = JsonArray(series.mapNotNull { s ->
                        val so = s as? JsonObject ?: return@mapNotNull null
                        val vals = (so["values"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return@mapNotNull null
                        val fixed = vals.take(n) + List((n - vals.size).coerceAtLeast(0)) { 0.0 }
                        if (vals.size != n) warnings += "block $i: series length fixed to $n"
                        JsonObject(so + ("values" to JsonArray(fixed.map { JsonPrimitive(it) })))
                    })
                }
            }
            "quiz" -> {
                val qs = m["questions"] as? JsonArray
                if (qs != null) m["questions"] = JsonArray(qs.map { q ->
                    val qo = q as? JsonObject ?: return@map q
                    val nOpt = (qo["options"] as? JsonArray)?.size ?: 0
                    val a = (qo["answer"] as? JsonPrimitive)?.intOrNull ?: 0
                    if (nOpt > 0 && a !in 0 until nOpt) JsonObject(qo + ("answer" to JsonPrimitive(0))) else qo
                })
            }
            "heading" -> {
                val l = (m["level"] as? JsonPrimitive)?.intOrNull
                if (l != null && l !in 1..3) m["level"] = JsonPrimitive(l.coerceIn(1, 3))
            }
        }
        return JsonObject(m)
    }

    private fun JsonElement?.str(): String = (this as? JsonPrimitive)?.content ?: ""
    private fun JsonElement?.arr(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private fun JsonElement?.strs(): List<String> = arr().map { it.str() }

    private fun toBlock(type: String, o: JsonObject): UiBlock = when (type) {
        "markdown" -> UiBlock.Markdown(o["text"].str())
        "heading" -> UiBlock.Heading(o["text"].str(), (o["level"] as JsonPrimitive).intOrNull ?: 2)
        "callout" -> UiBlock.Callout(o["tone"].str(), o["title"].str(), o["text"].str())
        "keyvalue" -> UiBlock.KeyValue(o["title"].str(), o["items"].arr().map { (it as JsonObject)["label"].str() to it["value"].str() })
        "table" -> UiBlock.Table(o["title"].str(), o["columns"].strs(), o["rows"].arr().map { it.strs() })
        "list" -> UiBlock.ListBlock(o["ordered"]!!.jsonPrimitive.booleanOrNull ?: false, o["items"].strs())
        "checklist" -> UiBlock.Checklist(o["title"].str(), o["items"].arr().map {
            CheckItem((it as JsonObject)["text"].str(), it["done"]!!.jsonPrimitive.booleanOrNull ?: false)
        })
        "steps" -> UiBlock.Steps(o["title"].str(), o["items"].arr().map { Step((it as JsonObject)["title"].str(), it["detail"].str()) })
        "chart" -> UiBlock.Chart(o["kind"].str(), o["title"].str(), o["labels"].strs(), o["series"].arr().map {
            Series((it as JsonObject)["name"].str(), it["values"].arr().map { v -> (v as JsonPrimitive).doubleOrNull ?: 0.0 })
        })
        "code" -> UiBlock.Code(o["language"].str(), o["code"].str())
        "flashcards" -> UiBlock.Flashcards(o["title"].str(), o["cards"].arr().map { Card((it as JsonObject)["front"].str(), it["back"].str()) })
        "quiz" -> UiBlock.Quiz(o["title"].str(), o["questions"].arr().map {
            val q = it as JsonObject
            Question(q["question"].str(), q["options"].strs(), (q["answer"] as JsonPrimitive).intOrNull ?: 0, q["explanation"].str())
        })
        "image" -> UiBlock.Image(o["ref"].str(), o["caption"].str())
        "comparison" -> UiBlock.Comparison(o["title"].str(), o["options"].strs(), o["criteria"].arr().map {
            Criterion((it as JsonObject)["name"].str(), it["values"].strs())
        })
        "actions" -> UiBlock.Actions(o["buttons"].arr().map { Action((it as JsonObject)["label"].str(), it["prompt"].str()) })
        else -> error("unreachable: $type")
    }
}

/** Serialises a document back to the exact JSON shape of [UiSchema] (property order included). */
object UiSerializer {
    fun toJson(doc: UiDoc): JsonObject = buildJsonObject {
        put("v", doc.version)
        put("title", doc.title)
        put("blocks", JsonArray(doc.blocks.map { block(it) }))
    }

    fun toJsonString(doc: UiDoc): String = toJson(doc).toString()

    private fun strs(l: List<String>) = JsonArray(l.map { JsonPrimitive(it) })

    fun block(b: UiBlock): JsonObject = buildJsonObject {
        put("type", b.type)
        when (b) {
            is UiBlock.Markdown -> put("text", b.text)
            is UiBlock.Heading -> { put("text", b.text); put("level", b.level) }
            is UiBlock.Callout -> { put("tone", b.tone); put("title", b.title); put("text", b.text) }
            is UiBlock.KeyValue -> { put("title", b.title); put("items", JsonArray(b.items.map { buildJsonObject { put("label", it.first); put("value", it.second) } })) }
            is UiBlock.Table -> { put("title", b.title); put("columns", strs(b.columns)); put("rows", JsonArray(b.rows.map { strs(it) })) }
            is UiBlock.ListBlock -> { put("ordered", b.ordered); put("items", strs(b.items)) }
            is UiBlock.Checklist -> { put("title", b.title); put("items", JsonArray(b.items.map { buildJsonObject { put("text", it.text); put("done", it.done) } })) }
            is UiBlock.Steps -> { put("title", b.title); put("items", JsonArray(b.items.map { buildJsonObject { put("title", it.title); put("detail", it.detail) } })) }
            is UiBlock.Chart -> {
                put("kind", b.kind); put("title", b.title); put("labels", strs(b.labels))
                put("series", JsonArray(b.series.map { s -> buildJsonObject { put("name", s.name); put("values", buildJsonArray { s.values.forEach { add(JsonPrimitive(trimNum(it))) } }) } }))
            }
            is UiBlock.Code -> { put("language", b.language); put("code", b.code) }
            is UiBlock.Flashcards -> { put("title", b.title); put("cards", JsonArray(b.cards.map { buildJsonObject { put("front", it.front); put("back", it.back) } })) }
            is UiBlock.Quiz -> { put("title", b.title); put("questions", JsonArray(b.questions.map { q -> buildJsonObject { put("question", q.question); put("options", strs(q.options)); put("answer", q.answer); put("explanation", q.explanation) } })) }
            is UiBlock.Image -> { put("ref", b.ref); put("caption", b.caption) }
            is UiBlock.Comparison -> { put("title", b.title); put("options", strs(b.options)); put("criteria", JsonArray(b.criteria.map { c -> buildJsonObject { put("name", c.name); put("values", strs(c.values)) } })) }
            is UiBlock.Actions -> put("buttons", JsonArray(b.buttons.map { buildJsonObject { put("label", it.label); put("prompt", it.prompt) } }))
        }
    }

    private fun trimNum(d: Double): Number = if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong() else d
}

/** Plain-markdown rendering of a document: copy/share, history for the model, and accessibility. */
object UiMarkdown {
    fun render(doc: UiDoc): String = buildString {
        if (doc.title.isNotBlank()) append("# ").append(doc.title).append("\n\n")
        doc.blocks.forEach { append(block(it)).append("\n\n") }
    }.trim()

    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    fun block(b: UiBlock): String = when (b) {
        is UiBlock.Markdown -> b.text
        is UiBlock.Heading -> "#".repeat(b.level + 1) + " " + b.text
        is UiBlock.Callout -> "> **${b.title.ifBlank { b.tone.replaceFirstChar { it.uppercase() } }}:** ${b.text}"
        is UiBlock.KeyValue -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") + b.items.joinToString("\n") { "- **${it.first}:** ${it.second}" }
        is UiBlock.Table -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") +
            "| " + b.columns.joinToString(" | ") { cell(it) } + " |\n" +
            "|" + b.columns.joinToString("|") { " --- " } + "|\n" +
            b.rows.joinToString("\n") { r -> "| " + r.joinToString(" | ") { cell(it) } + " |" }
        is UiBlock.ListBlock -> b.items.mapIndexed { i, s -> if (b.ordered) "${i + 1}. $s" else "- $s" }.joinToString("\n")
        is UiBlock.Checklist -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") + b.items.joinToString("\n") { "- [${if (it.done) "x" else " "}] ${it.text}" }
        is UiBlock.Steps -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") + b.items.mapIndexed { i, s -> "${i + 1}. **${s.title}** — ${s.detail}" }.joinToString("\n")
        is UiBlock.Chart -> "**${b.title.ifBlank { "Chart" }}** (${b.kind} chart)\n\n| | " + b.series.joinToString(" | ") { cell(it.name) } + " |\n|---|" +
            b.series.joinToString("") { "---|" } + "\n" +
            b.labels.mapIndexed { i, l -> "| ${cell(l)} | " + b.series.joinToString(" | ") { s -> fmt(s.values.getOrElse(i) { 0.0 }) } + " |" }.joinToString("\n")
        is UiBlock.Code -> "```${b.language}\n${b.code}\n```"
        is UiBlock.Flashcards -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") + b.cards.joinToString("\n") { "- **Q:** ${it.front}\n  **A:** ${it.back}" }
        is UiBlock.Quiz -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") + b.questions.mapIndexed { i, q ->
            "${i + 1}. ${q.question}\n" + q.options.mapIndexed { j, o -> "   ${'a' + j}) $o" }.joinToString("\n") +
                "\n   _Answer: ${q.options.getOrElse(q.answer) { "?" }}_" + if (q.explanation.isNotBlank()) " — ${q.explanation}" else ""
        }.joinToString("\n")
        is UiBlock.Image -> "_[image ${b.ref}]_ ${b.caption}"
        is UiBlock.Comparison -> (if (b.title.isNotBlank()) "**${b.title}**\n\n" else "") +
            "| | " + b.options.joinToString(" | ") { cell(it) } + " |\n|---|" + b.options.joinToString("") { "---|" } + "\n" +
            b.criteria.joinToString("\n") { c -> "| **${cell(c.name)}** | " + b.options.indices.joinToString(" | ") { cell(c.values.getOrElse(it) { "" }) } + " |" }
        is UiBlock.Actions -> b.buttons.joinToString("  ") { "[${it.label}]" }
    }

    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d)
}
