package com.offlineagent.core.runtime.pdf

import com.offlineagent.core.runtime.markdown.Inline
import com.offlineagent.core.runtime.markdown.ListItem
import com.offlineagent.core.runtime.markdown.Markdown
import com.offlineagent.core.runtime.markdown.MdBlock
import com.offlineagent.core.runtime.ui.UiBlock
import com.offlineagent.core.runtime.ui.UiDoc

enum class PdfFont { REGULAR, BOLD, ITALIC, BOLD_ITALIC, MONO, MONO_BOLD;
    val isMono get() = this == MONO || this == MONO_BOLD
    fun bold() = when (this) { REGULAR -> BOLD; ITALIC -> BOLD_ITALIC; MONO -> MONO_BOLD; else -> this }
    fun italic() = when (this) { REGULAR -> ITALIC; BOLD -> BOLD_ITALIC; else -> this }
}

/** 0xRRGGBB */
@JvmInline value class PdfColor(val rgb: Int) {
    val r get() = (rgb shr 16 and 0xFF) / 255f
    val g get() = (rgb shr 8 and 0xFF) / 255f
    val b get() = (rgb and 0xFF) / 255f
    companion object {
        val BLACK = PdfColor(0x111111); val GREY = PdfColor(0x666666); val LIGHT = PdfColor(0xF2F2F2)
        val RULE = PdfColor(0xCCCCCC); val ACCENT = PdfColor(0x1A5FB4); val CODE_BG = PdfColor(0xF5F5F5)
        val NOTE_BG = PdfColor(0xEAF2FB); val WARN_BG = PdfColor(0xFFF4E0)
    }
}

data class Run(val text: String, val font: PdfFont = PdfFont.REGULAR, val color: PdfColor = PdfColor.BLACK)

/** An image already encoded (JPEG or PNG bytes) with its pixel size. */
class PdfImage(val bytes: ByteArray, val width: Int, val height: Int, val isJpeg: Boolean)

/** Document blocks the layout engine understands. Built from Markdown, a Canvas document or a chat. */
sealed interface PdfBlock {
    data class Heading(val level: Int, val text: String) : PdfBlock
    data class Paragraph(val runs: List<Run>, val indent: Float = 0f) : PdfBlock
    data class Bullet(val depth: Int, val marker: String, val runs: List<Run>) : PdfBlock
    data class Table(val header: List<String>, val rows: List<List<String>>, val title: String = "") : PdfBlock
    data class Code(val language: String, val code: String) : PdfBlock
    data class Quote(val runs: List<Run>) : PdfBlock
    data class Callout(val tone: String, val title: String, val text: String) : PdfBlock
    data class Image(val image: PdfImage?, val caption: String) : PdfBlock
    data object Rule : PdfBlock
    data object PageBreak : PdfBlock
}

data class PdfDocModel(
    val title: String,
    val blocks: List<PdfBlock>,
    val subtitle: String = "",
    val author: String = "",
    val date: String = "",
    val titlePage: Boolean = true,
    val tableOfContents: Boolean = true,
    val pageNumbers: Boolean = true,
)

/** Converts Markdown / Canvas documents / chats into [PdfBlock]s. Pure Kotlin. */
object PdfContent {
    fun fromMarkdown(md: String, images: (String) -> PdfImage? = { null }): List<PdfBlock> =
        Markdown.parse(md).flatMap { block(it, 0, images) }

    private fun block(b: MdBlock, depth: Int, images: (String) -> PdfImage?): List<PdfBlock> = when (b) {
        is MdBlock.Heading -> listOf(PdfBlock.Heading(b.level, Markdown.plain(b.inlines)))
        is MdBlock.Paragraph -> paragraphOrImage(b.inlines, images)
        is MdBlock.Code -> listOf(PdfBlock.Code(b.language, b.code))
        is MdBlock.Quote -> listOf(PdfBlock.Quote(b.blocks.flatMap { (it as? MdBlock.Paragraph)?.let { p -> runs(p.inlines) + Run(" ") } ?: emptyList() }))
        is MdBlock.ListBlock -> b.items.flatMapIndexed { i, item -> listItem(item, if (b.ordered) "${b.start + i}." else bulletFor(depth), depth, images) }
        is MdBlock.Table -> listOf(PdfBlock.Table(b.header.map { Markdown.plain(it) }, b.rows.map { r -> r.map { Markdown.plain(it) } }))
        is MdBlock.Math -> listOf(PdfBlock.Code("math", b.tex))
        MdBlock.Rule -> listOf(PdfBlock.Rule)
    }

    private fun paragraphOrImage(inl: List<Inline>, images: (String) -> PdfImage?): List<PdfBlock> {
        // ![caption](ref) is parsed by the inline parser as text "!" + link; detect it.
        val text = Markdown.plain(inl)
        val m = Regex("^!\\[([^]]*)]\\(([^)]+)\\)$").find(text.trim())
        if (m != null) return listOf(PdfBlock.Image(images(m.groupValues[2]), m.groupValues[1]))
        if (inl.size == 2 && (inl[0] as? Inline.Text)?.text?.endsWith("!") == true && inl[1] is Inline.Link) {
            val l = inl[1] as Inline.Link
            return listOf(PdfBlock.Image(images(l.url), Markdown.plain(l.text)))
        }
        return listOf(PdfBlock.Paragraph(runs(inl)))
    }

    private fun listItem(item: ListItem, marker: String, depth: Int, images: (String) -> PdfImage?): List<PdfBlock> {
        val out = ArrayList<PdfBlock>()
        val m = when (item.task) { true -> "[x]"; false -> "[ ]"; null -> marker }
        var first = true
        for (b in item.blocks) {
            when (b) {
                is MdBlock.Paragraph -> { out += PdfBlock.Bullet(depth, if (first) m else "", runs(b.inlines)); first = false }
                is MdBlock.ListBlock -> out += block(b, depth + 1, images)
                else -> out += block(b, depth + 1, images)
            }
        }
        if (first) out += PdfBlock.Bullet(depth, m, emptyList())
        return out
    }

    private fun bulletFor(depth: Int) = when (depth % 3) { 0 -> "•"; 1 -> "–"; else -> "·" }

    fun runs(inl: List<Inline>, font: PdfFont = PdfFont.REGULAR): List<Run> = inl.flatMap { i ->
        when (i) {
            is Inline.Text -> listOf(Run(i.text, font))
            is Inline.Bold -> runs(i.children, font.bold())
            is Inline.Italic -> runs(i.children, font.italic())
            is Inline.Strike -> runs(i.children, font)
            is Inline.Code -> listOf(Run(i.code, PdfFont.MONO))
            is Inline.Link -> runs(i.text, font).map { it.copy(color = PdfColor.ACCENT) } +
                if (Markdown.plain(i.text) != i.url && i.url.isNotBlank()) listOf(Run(" (${i.url})", font, PdfColor.GREY)) else emptyList()
            is Inline.Math -> listOf(Run(i.tex, PdfFont.ITALIC))
            is Inline.Citation -> listOf(Run("[${i.n}]", font, PdfColor.ACCENT))
            Inline.LineBreak -> listOf(Run("\n", font))
            else -> listOf(Run(Markdown.plain(listOf(i)), font))
        }
    }

    /** Canvas document -> PDF blocks (every component type has a print form). */
    fun fromCanvas(doc: UiDoc, images: (String) -> PdfImage? = { null }): List<PdfBlock> = doc.blocks.flatMap { b ->
        when (b) {
            is UiBlock.Markdown -> fromMarkdown(b.text, images)
            is UiBlock.Heading -> listOf(PdfBlock.Heading(b.level.coerceIn(1, 3), b.text))
            is UiBlock.Callout -> listOf(PdfBlock.Callout(b.tone, b.title, b.text))
            is UiBlock.KeyValue -> listOf(PdfBlock.Table(listOf("Item", "Value"), b.items.map { listOf(it.first, it.second) }, b.title))
            is UiBlock.Table -> listOf(PdfBlock.Table(b.columns, b.rows, b.title))
            is UiBlock.ListBlock -> b.items.mapIndexed { i, s -> PdfBlock.Bullet(0, if (b.ordered) "${i + 1}." else "•", listOf(Run(s))) }
            is UiBlock.Checklist -> titled(b.title) + b.items.map { PdfBlock.Bullet(0, if (it.done) "[x]" else "[ ]", listOf(Run(it.text))) }
            is UiBlock.Steps -> titled(b.title) + b.items.mapIndexed { i, s -> PdfBlock.Bullet(0, "${i + 1}.", listOf(Run(s.title, PdfFont.BOLD), Run(if (s.detail.isBlank()) "" else " — " + s.detail))) }
            is UiBlock.Chart -> titled(b.title.ifBlank { "Chart (${b.kind})" }) +
                PdfBlock.Table(listOf("") + b.series.map { it.name }, b.labels.mapIndexed { i, l -> listOf(l) + b.series.map { s -> s.values.getOrNull(i)?.let { v -> fmt(v) } ?: "" } })
            is UiBlock.Code -> listOf(PdfBlock.Code(b.language, b.code))
            is UiBlock.Flashcards -> titled(b.title) + PdfBlock.Table(listOf("Front", "Back"), b.cards.map { listOf(it.front, it.back) })
            is UiBlock.Quiz -> titled(b.title) + b.questions.flatMapIndexed { i, q ->
                listOf(PdfBlock.Paragraph(listOf(Run("${i + 1}. ${q.question}", PdfFont.BOLD)))) +
                    q.options.mapIndexed { j, o -> PdfBlock.Bullet(1, "${'a' + j})", listOf(Run(o, if (j == q.answer) PdfFont.BOLD else PdfFont.REGULAR))) } +
                    (if (q.explanation.isNotBlank()) listOf(PdfBlock.Paragraph(listOf(Run("Answer: ${'a' + q.answer} — ${q.explanation}", PdfFont.ITALIC, PdfColor.GREY)), 18f)) else emptyList())
            }
            is UiBlock.Image -> listOf(PdfBlock.Image(images(b.ref), b.caption))
            is UiBlock.Comparison -> titled(b.title) + PdfBlock.Table(listOf("Criterion") + b.options, b.criteria.map { listOf(it.name) + it.values })
            is UiBlock.Actions -> emptyList() // buttons have no meaning on paper
        }
    }

    private fun titled(t: String) = if (t.isBlank()) emptyList() else listOf(PdfBlock.Heading(3, t))
    private fun fmt(v: Double) = if (v == Math.floor(v) && kotlin.math.abs(v) < 1e12) v.toLong().toString() else "%.2f".format(v)

    /** A whole chat transcript (user/assistant markdown) as a document. */
    fun fromChat(turns: List<Pair<String, String>>, images: (String) -> PdfImage? = { null }): List<PdfBlock> = turns.flatMap { (role, text) ->
        listOf(PdfBlock.Paragraph(listOf(Run(if (role == "user") "You" else "Assistant", PdfFont.BOLD, PdfColor.ACCENT)))) +
            (if (role == "user") listOf(PdfBlock.Quote(listOf(Run(text)))) else fromMarkdown(text, images)) + PdfBlock.Rule
    }
}
