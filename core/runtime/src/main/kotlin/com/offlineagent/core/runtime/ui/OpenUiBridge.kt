package com.offlineagent.core.runtime.ui

/**
 * Bridges the Phase 2/3 Canvas JSON documents ([UiDoc]) to OpenUI Lang, so old canvases (and the agent's `render_ui`
 * tool output) render with the same native OpenUI renderer as new answers. One-way and lossless for display.
 * Canvas versions stored by Phase 4 are OpenUI source prefixed with [PREFIX]; older versions are JSON.
 */
object OpenUiBridge {
    const val PREFIX = "openui:\n"

    fun isOpenUi(stored: String) = stored.startsWith(PREFIX)
    fun wrap(source: String) = PREFIX + source.trim()
    fun unwrap(stored: String) = stored.removePrefix(PREFIX)

    /** Stored canvas version (OpenUI or legacy JSON) → OpenUI source, or null if unreadable. */
    fun toOpenUiSource(stored: String): String? =
        if (isOpenUi(stored)) unwrap(stored) else (UiParser.parse(stored) as? UiParseResult.Success)?.doc?.let { fromUiDoc(it) }

    fun fromUiDoc(doc: UiDoc): String {
        val lines = mutableListOf<String>()
        val kids = mutableListOf<String>()
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun n(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
        if (doc.title.isNotBlank()) { kids += "title"; lines += "title = TextContent(${q(doc.title)}, \"large-heavy\")" }
        doc.blocks.forEachIndexed { i, b ->
            val id = "b$i"
            val expr = when (b) {
                is UiBlock.Markdown -> "MarkDownRenderer(${q(b.text)})"
                is UiBlock.Heading -> "TextContent(${q(b.text)}, ${if (b.level <= 1) "\"large-heavy\"" else "\"small-heavy\""})"
                is UiBlock.Callout -> "Callout(${q(when (b.tone) { "warning" -> "warning"; "success" -> "success"; "danger", "error" -> "error"; else -> "info" })}, ${q(b.title)}, ${q(b.text)})"
                is UiBlock.KeyValue -> "Table([Col(\"Item\"), Col(\"Value\")], [${b.items.joinToString(", ") { (k, v) -> "[${q(k)}, ${q(v)}]" }}])"
                is UiBlock.Table -> "Table([${b.columns.joinToString(", ") { "Col(${q(it)})" }}], [${b.rows.joinToString(", ") { r -> "[" + r.joinToString(", ") { q(it) } + "]" }}])"
                is UiBlock.ListBlock -> "MarkDownRenderer(${q(b.items.mapIndexed { k, s -> if (b.ordered) "${k + 1}. $s" else "- $s" }.joinToString("\n"))})"
                is UiBlock.Checklist -> "Form(${q("checklist$i")}, [FormControl(${q(b.title.ifBlank { "Checklist" })}, CheckBoxGroup(${q("items$i")}, [${b.items.mapIndexed { k, c -> "CheckBoxItem(${q(c.text)}, \"\", ${q("c$k")}, ${c.done})" }.joinToString(", ")}]))])"
                is UiBlock.Steps -> "Steps([${b.items.joinToString(", ") { "StepsItem(${q(it.title)}, ${q(it.detail)})" }}])"
                is UiBlock.Chart -> {
                    val comp = when (b.kind) { "line" -> "LineChart"; "pie" -> "PieChart"; else -> "BarChart" }
                    if (comp == "PieChart") "PieChart([${b.labels.zip(b.series.firstOrNull()?.values ?: emptyList()).joinToString(", ") { (l, v) -> "Slice(${q(l)}, ${n(v)})" }}])"
                    else "$comp([${b.labels.joinToString(", ") { q(it) }}], [${b.series.joinToString(", ") { s -> "Series(${q(s.name)}, [${s.values.joinToString(", ") { n(it) }}])" }}])"
                }
                is UiBlock.Code -> "CodeBlock(${q(b.language)}, ${q(b.code)})"
                is UiBlock.Flashcards -> "Accordion([${b.cards.mapIndexed { k, c -> "AccordionItem(${q("f$k")}, ${q(c.front)}, [TextContent(${q(c.back)})])" }.joinToString(", ")}])"
                is UiBlock.Quiz -> "Steps([${b.questions.joinToString(", ") { qq -> "StepsItem(${q(qq.question)}, ${q(qq.options.mapIndexed { k, o -> "${'A' + k}) $o" }.joinToString("  ") + "  — answer: " + (qq.options.getOrNull(qq.answer) ?: ""))})" }}])"
                is UiBlock.Image -> "Image(${q(b.caption.ifBlank { "image" })}, ${q(b.ref)})"
                is UiBlock.Comparison -> "Table([Col(\"Criterion\"), ${b.options.joinToString(", ") { "Col(${q(it)})" }}], [${b.criteria.joinToString(", ") { c -> "[" + (listOf(c.name) + c.values).joinToString(", ") { q(it) } + "]" }}])"
                is UiBlock.Actions -> "FollowUpBlock([${b.buttons.joinToString(", ") { "FollowUpItem(${q(it.prompt.ifBlank { it.label })})" }}])"
            }
            val titled = when (b) {
                is UiBlock.Table -> b.title; is UiBlock.Chart -> b.title; is UiBlock.Checklist -> ""; is UiBlock.Steps -> b.title
                is UiBlock.Flashcards -> b.title; is UiBlock.Quiz -> b.title; is UiBlock.Comparison -> b.title; is UiBlock.KeyValue -> b.title
                else -> ""
            }
            if (titled.isNotBlank()) { kids += "${id}h"; lines += "${id}h = CardHeader(${q(titled)})" }
            kids += id
            lines += "$id = $expr"
        }
        return (listOf("root = Stack([${kids.joinToString(", ")}])") + lines).joinToString("\n")
    }
}
