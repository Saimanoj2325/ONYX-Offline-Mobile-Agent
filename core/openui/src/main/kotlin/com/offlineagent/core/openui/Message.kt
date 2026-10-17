package com.offlineagent.core.openui

/**
 * An assistant message is Markdown prose with optional OpenUI Lang programs in ```openui-lang fences (OpenUI's
 * "inline mode"), or — in Canvas mode — one bare program. Plain Markdown (no OpenUI) is the fallback and renders as
 * before.
 */
sealed interface Segment {
    data class Markdown(val text: String) : Segment
    /** [closed] = the fence (or the stream) has ended. */
    data class Program(val source: String, val closed: Boolean) : Segment
}

object OpenUiMessage {
    private val FENCE_LANGS = setOf("openui-lang", "openui", "oui", "openuilang", "openui_lang")
    private val STMT = Regex("^\\s*[A-Za-z_][A-Za-z0-9_]*\\s*=\\s*(@?[A-Z][A-Za-z0-9]*\\(|\\[|\")")

    /** Splits [text] into Markdown and OpenUI segments. [streaming] = more text may follow (an open fence is a live program). */
    fun split(text: String, streaming: Boolean = false): List<Segment> {
        if (looksLikeBareProgram(text)) return listOf(Segment.Program(text.trim('\n'), closed = !streaming))
        val out = mutableListOf<Segment>()
        val md = StringBuilder()
        val lines = text.split('\n')
        var i = 0
        fun flushMd() { if (md.isNotBlank()) out += Segment.Markdown(md.toString().trim('\n')); md.clear() }
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            val lang = if (t.startsWith("```")) t.removePrefix("```").trim().lowercase() else null
            if (lang != null && lang in FENCE_LANGS) {
                flushMd()
                val body = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim() == "```") { closed = true; break }
                    body.append(lines[j]).append('\n')
                    j++
                }
                // At the end of the stream an unclosed fence is closed implicitly.
                out += Segment.Program(body.toString().trimEnd('\n'), closed = closed || !streaming)
                i = j + 1
                continue
            }
            // A "```" fence of another language may contain an openui program written by a small model: keep as markdown.
            md.append(line).append('\n')
            i++
        }
        flushMd()
        return out
    }

    /** True when the whole message is a program (Canvas mode or a model that skipped the fences). */
    fun looksLikeBareProgram(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return false
        val first = lines.first()
        if (first.startsWith("root") && Regex("^root\\s*=\\s*[A-Z@]").containsMatchIn(first)) return true
        val stmts = lines.count { STMT.containsMatchIn(it) }
        return stmts >= 2 && stmts * 10 >= lines.size * 8 // ≥ 80 % of the lines are statements
    }

    /** True if [text] contains any OpenUI program. */
    fun hasProgram(text: String): Boolean = split(text).any { it is Segment.Program }

    /** Plain-text view of a message for history / copy / PDF: programs are summarised as their visible text. */
    fun toPlainText(text: String, lib: OpenUiLibrary = OpenUiLibrary.DEFAULT): String = split(text).joinToString("\n\n") { s ->
        when (s) {
            is Segment.Markdown -> s.text
            is Segment.Program -> OpenUiText.render(OpenUiResolver(lib).resolve(OpenUiParser.parse(s.source)).root)
        }
    }.trim()
}

/** Text/Markdown rendering of a resolved tree (copy, PDF export, model history, accessibility). */
object OpenUiText {
    fun render(node: UiNode?): String {
        if (node == null) return ""
        val sb = StringBuilder()
        walk(node, sb)
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun walk(n: UiNode, sb: StringBuilder) {
        fun line(s: String?) { if (!s.isNullOrBlank()) sb.append(s).append("\n\n") }
        when (n.type) {
            "TextContent" -> line(if (n.str("size")?.startsWith("large") == true) "## ${n.str("text")}" else n.str("text"))
            "MarkDownRenderer" -> line(n.str("textMarkdown"))
            "CardHeader" -> { line(n.str("title")?.let { "### $it" }); line(n.str("subtitle")) }
            "Callout", "TextCallout" -> line("> **${n.str("title") ?: ""}** ${n.str("description") ?: ""}".trimEnd())
            "CodeBlock" -> line("```${n.str("language") ?: ""}\n${n.str("codeString") ?: ""}\n```")
            "Table" -> {
                val cols = n.nodes("columns").map { it.str("label") ?: "" }
                val rows = n.list("rows").map { r -> (r as? List<*>)?.map { c -> cell(c) } ?: emptyList() }
                if (cols.isNotEmpty()) {
                    sb.append("| ").append(cols.joinToString(" | ")).append(" |\n|").append(cols.joinToString("|") { "---" }).append("|\n")
                    rows.forEach { r -> sb.append("| ").append(cols.indices.joinToString(" | ") { r.getOrNull(it) ?: "" }).append(" |\n") }
                    sb.append('\n')
                }
            }
            "BarChart", "LineChart", "AreaChart", "HorizontalBarChart" -> {
                val labels = n.list("labels").map { it.toString() }
                n.nodes("series").forEach { s -> line("${s.str("category")}: " + labels.zip(s.list("values")).joinToString(", ") { (l, v) -> "$l ${cell(v)}" }) }
            }
            "PieChart" -> line(n.nodes("slices").joinToString(", ") { "${it.str("category")} ${it.str("value")}" })
            "Steps" -> n.nodes("items").forEachIndexed { i, s -> sb.append("${i + 1}. **${s.str("title")}** ${s.str("details") ?: ""}\n") }.also { sb.append('\n') }
            "ListBlock" -> n.nodes("items").forEachIndexed { i, s -> sb.append("${i + 1}. ${s.str("title")}${s.str("subtitle")?.let { " — $it" } ?: ""}\n") }.also { sb.append('\n') }
            "TagBlock" -> line(n.list("tags").joinToString(" · "))
            "Tag" -> line(n.str("text"))
            "FollowUpBlock" -> Unit // suggestions are not content
            "Image", "ImageBlock" -> line("[image: ${n.str("alt") ?: n.str("src") ?: ""}]")
            "Form" -> {
                line("**Form: ${n.str("name")}**")
                n.nodes("fields").forEach { f -> sb.append("- ${f.str("label")}\n") }
                sb.append('\n')
            }
            "TabItem", "AccordionItem" -> { line("**${n.str("trigger")}**"); n.nodes("content").forEach { walk(it, sb) } }
            else -> for (v in n.props.values) when (v) {
                is UiNode -> walk(v, sb)
                is List<*> -> v.filterIsInstance<UiNode>().forEach { walk(it, sb) }
                else -> Unit
            }
        }
    }

    private fun cell(c: Any?): String = when (c) { is Double -> UiNode.fmt(c); null -> ""; else -> c.toString() }
}
