package com.offlineagent.core.runtime.pdf

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.validate
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.Tool
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSpec
import com.offlineagent.core.runtime.tools.objSchema
import com.offlineagent.core.runtime.tools.okJson
import com.offlineagent.core.runtime.tools.str
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

data class ReportSection(val heading: String, val points: List<String>)
data class ReportOutline(val title: String, val subtitle: String, val sections: List<ReportSection>)

data class ReportProgress(val phase: String, val section: Int, val total: Int)

/**
 * Long-document generation that beats the context window: the model first writes an outline (grammar-constrained
 * JSON), then each section separately. A section prompt only carries the title, the outline and a ~60-word digest of
 * every earlier section — never the full text — so a 20-section report needs no more context than a 2-section one.
 */
class ReportWriter(
    private val llm: AgentLlm,
    private val sectionTokens: Int = 900,
    private val maxSections: Int = 12,
) {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    val outlineSchema = Schema.Obj(listOf(
        "title" to Schema.Str(maxLength = 120, minLength = 3),
        "subtitle" to Schema.Str(maxLength = 160),
        "sections" to Schema.Arr(Schema.Obj(listOf(
            "heading" to Schema.Str(maxLength = 100, minLength = 2),
            "points" to Schema.Arr(Schema.Str(maxLength = 140), minItems = 1, maxItems = 5),
        )), minItems = 2, maxItems = 12),
    ))

    /**
     * [conversation]: 0.6.2.1 — the chat session (earlier turns / summary). A request like "make a detailed pdf on it"
     * is about the earlier exchange, so the outline and every section see it.
     */
    suspend fun outline(topic: String, sections: Int, notes: String = "", conversation: String = ""): ReportOutline {
        val sys = "You plan well-structured reports. Produce a title, a one-line subtitle and $sections sections " +
            "(each with 2-4 key points to cover). If the request refers to the earlier conversation (\"it\", \"this\"), the report is about that. Reply as JSON."
        val user = (if (conversation.isNotBlank()) "${conversation.takeLast(4000)}\n\n" else "") +
            "Topic / request: $topic" + if (notes.isNotBlank()) "\nSource notes:\n${notes.take(3000)}" else ""
        val raw = llm.complete(sys, user, Gbnf.of(outlineSchema), 700)
        return parseOutline(raw) ?: ReportOutline(topic.take(100), "", listOf(ReportSection("Overview", listOf(topic)), ReportSection("Details", listOf(topic))))
    }

    fun parseOutline(raw: String): ReportOutline? = runCatching {
        val o = json.parseToJsonElement(raw.substring(raw.indexOf('{'))) as JsonObject
        if (outlineSchema.validate(o, "outline").isNotEmpty()) return@runCatching null
        ReportOutline(
            o["title"]!!.jsonPrimitive.content, (o["subtitle"] as? JsonPrimitive)?.content ?: "",
            (o["sections"] as JsonArray).map { s ->
                s as JsonObject
                ReportSection(s["heading"]!!.jsonPrimitive.content, (s["points"] as JsonArray).map { it.jsonPrimitive.content })
            }.take(maxSections),
        )
    }.getOrNull()

    /** Writes every section; returns the whole report as Markdown (# title, ## sections). */
    suspend fun write(
        topic: String,
        outline: ReportOutline,
        notes: String = "",
        onProgress: (ReportProgress) -> Unit = {},
        onSection: suspend (index: Int, markdown: String) -> Unit = { _, _ -> },
        conversation: String = "",
    ): String {
        val digests = ArrayList<String>()
        val sb = StringBuilder()
        val plan = outline.sections.mapIndexed { i, s -> "${i + 1}. ${s.heading}: ${s.points.joinToString("; ")}" }.joinToString("\n")
        outline.sections.forEachIndexed { i, s ->
            currentCoroutineContext().ensureActive()
            onProgress(ReportProgress("Writing \"${s.heading}\"", i + 1, outline.sections.size))
            val sys = "You write one section of a report in Markdown. Write 2-5 substantial paragraphs; use bullet lists or a " +
                "small table where it helps. Do NOT write the section heading itself and do not repeat earlier sections."
            val user = buildString {
                if (conversation.isNotBlank()) append(conversation.takeLast(2500)).append("\n\n")
                append("Report: ").append(outline.title).append('\n')
                append("Request: ").append(topic.take(600)).append('\n')
                append("Outline:\n").append(plan).append('\n')
                if (digests.isNotEmpty()) append("Already written (summaries):\n").append(digests.joinToString("\n")).append('\n')
                if (notes.isNotBlank()) append("Source notes:\n").append(notes.take(2500)).append('\n')
                append("\nNow write section ${i + 1}: \"").append(s.heading).append("\" covering: ").append(s.points.joinToString("; "))
            }
            val body = cleanSection(llm.complete(sys, user, null, sectionTokens), s.heading)
            sb.append("## ").append(s.heading).append("\n\n").append(body).append("\n\n")
            onSection(i, body)
            digests += "${i + 1}. ${s.heading}: " + digest(body)
        }
        return "# ${outline.title}\n\n" + sb.toString().trim() + "\n"
    }

    /**
     * Drops a repeated heading the model sometimes puts first ("## 2. Skills", "**Skills**", or inline as
     * "2. **Skills** A solid record…" / "Skills: A solid record…"), keeping any body text that follows it on that line.
     */
    fun cleanSection(text: String, heading: String): String {
        val t = text.trim()
        val first = t.lineSequence().firstOrNull()?.trim().orEmpty()
        val rest = t.substringAfter('\n', "").trim()
        if (first.startsWith("#")) return rest
        val numbering = Regex("^\\s*(\\d+[.)]\\s*)?")
        val h = heading.replace(numbering, "").replace("*", "").trim()
        if (h.isEmpty()) return t
        val raw = first.replace(numbering, "")
        if (raw.replace("*", "").trim().equals(h, ignoreCase = true)) return rest
        fun join(tail: String) = (tail.trim() + if (rest.isNotEmpty()) "\n" + rest else "").trim()
        // "**Heading** body…" / "**Heading:** body…"
        if (raw.startsWith("**") && raw.substring(2).startsWith(h, ignoreCase = true)) {
            val after = raw.substring(2 + h.length)
            val close = after.indexOf("**")
            if (close >= 0 && after.substring(0, close).trim(':', ' ', '.').isEmpty()) return join(after.substring(close + 2).trimStart(':', ' ', '-', '—', '.'))
        }
        // "Heading: body…" / "Heading — body…" (but NOT "Heading is a crucial step…", which is a real sentence)
        if (raw.startsWith(h, ignoreCase = true)) {
            val after = raw.substring(h.length)
            if (after.startsWith(":") || after.startsWith(" —") || after.startsWith(" - ") || after.startsWith("—")) return join(after.trimStart(':', ' ', '-', '—'))
        }
        return t
    }

    /** First ~60 words: enough for later sections to avoid repeating this one. */
    fun digest(text: String): String = text.replace(Regex("[#*`|>-]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }.take(60).joinToString(" ")
}

// ---------------------------------------------------------------- PDF port + tool

data class PdfOutput(val uri: String, val fileName: String, val pages: Int, val bytes: Long)

/** Implemented by the app: renders with android.graphics.pdf.PdfDocument and saves to Downloads (MediaStore/SAF). */
interface PdfPort {
    suspend fun createPdf(doc: PdfDocModel, fileName: String): PdfOutput
    /** Markdown of the chat's current canvas (null when there is none). */
    suspend fun canvasMarkdown(chatId: Long?): Pair<String, List<PdfBlock>>?
}

class CreatePdfTool(private val pdf: PdfPort) : Tool {
    override val spec = ToolSpec(
        "create_pdf",
        "Create a multi-page PDF (title page, contents, headings, lists, tables, code, page numbers) from Markdown, or from the open canvas when source is \"canvas\". Saved to Downloads.",
        objSchema(
            "title" to Schema.Str(maxLength = 120, minLength = 1),
            "markdown" to Schema.Str("document body in Markdown, or empty when source is canvas", maxLength = 60_000),
            "source" to Schema.Str(enum = listOf("markdown", "canvas")),
            "file_name" to Schema.Str("e.g. report.pdf", maxLength = 80),
            "subtitle" to Schema.Str(maxLength = 160),
            "title_page" to Schema.Bool(),
        ),
        Permission.WRITE_LOCAL, sideEffects = "Saves a PDF file to your Downloads folder.", timeoutMs = 120_000,
        outputDescription = "{\"saved\":true,\"file\":...,\"pages\":n}",
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult {
        val title = args.str("title")
        val blocks = if (args.str("source") == "canvas") {
            pdf.canvasMarkdown(ctx.chatId)?.second ?: return ToolResult.Error("there is no canvas in this chat")
        } else {
            val md = args.str("markdown")
            if (md.isBlank()) return ToolResult.Error("markdown is empty")
            PdfContent.fromMarkdown(stripTitle(md, title))
        }
        val name = safeFileName(args.str("file_name").ifBlank { title })
        val titlePage = (args["title_page"] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: true
        val out = pdf.createPdf(PdfDocModel(title, blocks, subtitle = args.str("subtitle"), titlePage = titlePage, tableOfContents = titlePage), name)
        return okJson("saved" to true, "file" to out.fileName, "pages" to out.pages, "uri" to out.uri)
    }

    companion object {
        fun safeFileName(s: String): String {
            val base = s.removeSuffix(".pdf").replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().replace(' ', '-').take(60).ifBlank { "document" }
            return "$base.pdf"
        }

        /** A leading "# Title" equal to the document title would duplicate the title page. */
        fun stripTitle(md: String, title: String): String {
            val first = md.trimStart().lineSequence().firstOrNull() ?: return md
            return if (first.startsWith("# ") && first.removePrefix("# ").trim().equals(title.trim(), true)) md.trimStart().substringAfter('\n') else md
        }
    }
}
