package com.offlineagent.core.runtime.pdf

/** Text measurement for one renderer (Android Paint, or the built-in Type1 metrics of [PdfWriter]). */
fun interface FontMetrics {
    fun width(text: String, font: PdfFont, size: Float): Float
}

/** Page geometry in PDF points (1/72 inch). Default A4. */
data class PageSpec(val width: Float = 595f, val height: Float = 842f, val margin: Float = 54f) {
    val contentWidth get() = width - 2 * margin
    val top get() = margin
    val bottom get() = height - margin - 22f // footer band
}

/** Drawing operations; y is the TOP of the element measured from the top of the page. */
sealed interface DrawOp {
    data class Text(val x: Float, val y: Float, val text: String, val font: PdfFont, val size: Float, val color: PdfColor) : DrawOp
    data class Rect(val x: Float, val y: Float, val w: Float, val h: Float, val fill: PdfColor?, val stroke: PdfColor?) : DrawOp
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: PdfColor, val width: Float = 0.6f) : DrawOp
    data class Image(val x: Float, val y: Float, val w: Float, val h: Float, val image: PdfImage) : DrawOp
}

class LaidOutPage(val ops: MutableList<DrawOp> = ArrayList())

data class TocEntry(val level: Int, val text: String, val page: Int)

data class PdfLayoutResult(val pages: List<LaidOutPage>, val toc: List<TocEntry>, val spec: PageSpec)

/**
 * Pure-Kotlin page layout: title page, optional table of contents, headings (kept with the next block), wrapped rich
 * paragraphs, nested lists, tables with wrapped cells and repeated headers across pages, code blocks, callouts,
 * images scaled to fit, rules, page breaks, and "Page n of N" footers. Renderer-independent: [PdfWriter] (pure Kotlin)
 * and the Android PdfDocument renderer draw the same ops.
 */
class PdfLayout(private val metrics: FontMetrics, private val spec: PageSpec = PageSpec()) {
    private val body = 10.5f
    private val lead = 1.38f
    private val pages = ArrayList<LaidOutPage>()
    private var page = LaidOutPage()
    private var y = 0f
    private val toc = ArrayList<TocEntry>()

    fun layout(doc: PdfDocModel): PdfLayoutResult {
        pages.clear(); toc.clear()
        val bodyStart: Int
        if (doc.titlePage) titlePage(doc)
        // Body pages are laid out first; the TOC (which needs their page numbers) is inserted afterwards.
        val bodyPages = ArrayList<LaidOutPage>()
        newPage()
        bodyStart = pages.size - 1
        if (!doc.titlePage) {
            heading(1, doc.title, record = false)
        }
        for ((i, b) in doc.blocks.withIndex()) block(b, doc.blocks.getOrNull(i + 1))
        bodyPages += pages.subList(bodyStart, pages.size)
        val front = pages.subList(0, bodyStart).toMutableList()

        var tocPages = emptyList<LaidOutPage>()
        if (doc.tableOfContents && toc.size >= 2) {
            val n = tocPageCount(toc.size)
            val shifted = toc.map { it.copy(page = it.page + n) }
            tocPages = tocLayout(shifted)
            toc.clear(); toc += shifted
        }
        val all = ArrayList<LaidOutPage>()
        all += front; all += tocPages; all += bodyPages
        if (doc.pageNumbers) footers(all, doc.title, skipFirst = doc.titlePage)
        return PdfLayoutResult(all, toc.toList(), spec)
    }

    // ------------------------------------------------------------ pages

    private fun newPage() {
        page = LaidOutPage()
        pages += page
        y = spec.top
    }

    private fun ensure(h: Float) { if (y + h > spec.bottom && y > spec.top + 0.5f) newPage() }

    private fun titlePage(doc: PdfDocModel) {
        newPage()
        val w = spec.contentWidth
        y = spec.height * 0.32f
        page.ops += DrawOp.Rect(spec.margin, y - 18f, 60f, 5f, PdfColor.ACCENT, null)
        for (line in wrap(listOf(Run(doc.title, PdfFont.BOLD)), 26f, w)) { drawLine(line, spec.margin, 26f); y += 26f * 1.25f }
        if (doc.subtitle.isNotBlank()) {
            y += 6f
            for (line in wrap(listOf(Run(doc.subtitle, PdfFont.REGULAR, PdfColor.GREY)), 14f, w)) { drawLine(line, spec.margin, 14f); y += 14f * 1.4f }
        }
        y = spec.height * 0.78f
        listOf(doc.author, doc.date).filter { it.isNotBlank() }.forEach {
            page.ops += DrawOp.Text(spec.margin, y, it, PdfFont.REGULAR, 11f, PdfColor.GREY); y += 16f
        }
    }

    private fun tocPageCount(entries: Int): Int {
        val perPage = ((spec.bottom - spec.top - 40f) / 17f).toInt().coerceAtLeast(1)
        return (entries + perPage - 1) / perPage
    }

    private fun tocLayout(entries: List<TocEntry>): List<LaidOutPage> {
        val out = ArrayList<LaidOutPage>()
        var p = LaidOutPage().also { out += it }
        var yy = spec.top
        p.ops += DrawOp.Text(spec.margin, yy, "Contents", PdfFont.BOLD, 18f, PdfColor.BLACK)
        yy += 40f
        for (e in entries) {
            if (yy + 17f > spec.bottom) { p = LaidOutPage().also { out += it }; yy = spec.top }
            val indent = (e.level - 1).coerceAtLeast(0) * 14f
            val font = if (e.level == 1) PdfFont.BOLD else PdfFont.REGULAR
            val num = (e.page + 1).toString()
            val numW = metrics.width(num, PdfFont.REGULAR, 10.5f)
            val maxText = spec.contentWidth - indent - numW - 24f
            val text = ellipsize(e.text, font, 10.5f, maxText)
            p.ops += DrawOp.Text(spec.margin + indent, yy, text, font, 10.5f, PdfColor.BLACK)
            val tw = metrics.width(text, font, 10.5f)
            // dotted leader
            val dotsStart = spec.margin + indent + tw + 6f
            val dotsEnd = spec.margin + spec.contentWidth - numW - 6f
            if (dotsEnd > dotsStart) {
                val dotW = metrics.width(".", PdfFont.REGULAR, 10.5f).coerceAtLeast(1f)
                val dots = ".".repeat(((dotsEnd - dotsStart) / dotW).toInt().coerceAtMost(400))
                p.ops += DrawOp.Text(dotsStart, yy, dots, PdfFont.REGULAR, 10.5f, PdfColor.RULE)
            }
            p.ops += DrawOp.Text(spec.margin + spec.contentWidth - numW, yy, num, PdfFont.REGULAR, 10.5f, PdfColor.BLACK)
            yy += 17f
        }
        return out
    }

    private fun footers(all: List<LaidOutPage>, title: String, skipFirst: Boolean) {
        val total = all.size
        all.forEachIndexed { i, p ->
            if (skipFirst && i == 0) return@forEachIndexed
            val fy = spec.height - spec.margin + 4f
            p.ops += DrawOp.Line(spec.margin, fy - 6f, spec.width - spec.margin, fy - 6f, PdfColor.RULE, 0.5f)
            val label = "Page ${i + 1} of $total"
            val lw = metrics.width(label, PdfFont.REGULAR, 8.5f)
            p.ops += DrawOp.Text(spec.width - spec.margin - lw, fy, label, PdfFont.REGULAR, 8.5f, PdfColor.GREY)
            p.ops += DrawOp.Text(spec.margin, fy, ellipsize(title, PdfFont.REGULAR, 8.5f, spec.contentWidth * 0.6f), PdfFont.REGULAR, 8.5f, PdfColor.GREY)
        }
    }

    // ------------------------------------------------------------ blocks

    private fun block(b: PdfBlock, next: PdfBlock?) {
        when (b) {
            is PdfBlock.Heading -> heading(b.level, b.text, record = true, next = next)
            is PdfBlock.Paragraph -> paragraph(b.runs, spec.margin + b.indent, spec.contentWidth - b.indent, body)
            is PdfBlock.Bullet -> bullet(b)
            is PdfBlock.Table -> table(b)
            is PdfBlock.Code -> code(b)
            is PdfBlock.Quote -> quote(b)
            is PdfBlock.Callout -> callout(b)
            is PdfBlock.Image -> image(b)
            PdfBlock.Rule -> { ensure(14f); y += 6f; page.ops += DrawOp.Line(spec.margin, y, spec.margin + spec.contentWidth, y, PdfColor.RULE); y += 8f }
            PdfBlock.PageBreak -> newPage()
        }
    }

    private fun heading(level: Int, text: String, record: Boolean, next: PdfBlock? = null) {
        val size = when (level) { 1 -> 20f; 2 -> 15.5f; 3 -> 13f; else -> 11.5f }
        val lines = wrap(listOf(Run(text, PdfFont.BOLD, if (level <= 2) PdfColor.BLACK else PdfColor.ACCENT)), size, spec.contentWidth)
        val h = lines.size * size * 1.25f + size * 0.9f
        // keep with next: require room for the heading plus ~3 lines of what follows
        val keep = if (next != null && next !is PdfBlock.Heading && next !is PdfBlock.PageBreak) body * lead * 3 else 0f
        if (level == 1 && y > spec.top + 0.5f && pageUsed() > 0.55f) newPage() else ensure(h + keep)
        y += if (y <= spec.top + 0.5f) 0f else size * 0.7f
        if (record) toc += TocEntry(level.coerceIn(1, 3), text, pages.size - 1)
        for (l in lines) { drawLine(l, spec.margin, size); y += size * 1.25f }
        if (level <= 2) { page.ops += DrawOp.Line(spec.margin, y + 1f, spec.margin + spec.contentWidth, y + 1f, PdfColor.RULE, if (level == 1) 1f else 0.5f); y += 5f }
        y += size * 0.25f
    }

    private fun pageUsed() = (y - spec.top) / (spec.bottom - spec.top)

    private fun paragraph(runs: List<Run>, x: Float, w: Float, size: Float, after: Float = size * 0.65f) {
        for (l in wrap(runs, size, w)) {
            ensure(size * lead)
            drawLine(l, x, size)
            y += size * lead
        }
        y += after
    }

    private fun bullet(b: PdfBlock.Bullet) {
        val indent = 14f + b.depth * 16f
        val x = spec.margin + indent
        val lines = wrap(b.runs.ifEmpty { listOf(Run(" ")) }, body, spec.contentWidth - indent)
        lines.forEachIndexed { i, l ->
            ensure(body * lead)
            if (i == 0 && b.marker.isNotEmpty()) {
                val mw = metrics.width(b.marker, PdfFont.REGULAR, body)
                page.ops += DrawOp.Text(x - mw - 5f, y, b.marker, PdfFont.REGULAR, body, PdfColor.BLACK)
            }
            drawLine(l, x, body)
            y += body * lead
        }
        y += 2.5f
    }

    private fun quote(b: PdfBlock.Quote) {
        val x = spec.margin + 14f
        val lines = wrap(b.runs.map { it.copy(font = it.font.italic(), color = PdfColor.GREY) }, body, spec.contentWidth - 14f)
        for (l in lines) {
            ensure(body * lead)
            page.ops += DrawOp.Rect(spec.margin + 2f, y - 1f, 2.5f, body * lead, PdfColor.RULE, null)
            drawLine(l, x, body); y += body * lead
        }
        y += body * 0.6f
    }

    private fun callout(b: PdfBlock.Callout) {
        val bg = if (b.tone == "warning" || b.tone == "error") PdfColor.WARN_BG else PdfColor.NOTE_BG
        val inner = spec.contentWidth - 20f
        val titleLines = if (b.title.isBlank()) emptyList() else wrap(listOf(Run(b.title, PdfFont.BOLD)), body, inner)
        val textLines = wrap(listOf(Run(b.text)), body, inner)
        val all = titleLines + textLines
        var i = 0
        while (i < all.size) {
            ensure(body * lead + 12f)
            val fit = (((spec.bottom - y - 12f) / (body * lead)).toInt()).coerceAtLeast(1)
            val chunk = all.subList(i, minOf(all.size, i + fit))
            val h = chunk.size * body * lead + 12f
            page.ops += DrawOp.Rect(spec.margin, y, spec.contentWidth, h, bg, null)
            page.ops += DrawOp.Rect(spec.margin, y, 3f, h, PdfColor.ACCENT, null)
            var yy = y + 6f
            for (l in chunk) { drawLineAt(l, spec.margin + 12f, yy, body); yy += body * lead }
            y += h
            i += chunk.size
            if (i < all.size) newPage()
        }
        y += body * 0.8f
    }

    private fun code(b: PdfBlock.Code) {
        val size = 8.8f
        val lh = size * 1.35f
        val inner = spec.contentWidth - 16f
        val lines = b.code.trimEnd().lines().flatMap { raw ->
            val line = raw.replace("\t", "    ")
            if (line.isEmpty()) listOf("") else hardWrap(line, PdfFont.MONO, size, inner)
        }
        var i = 0
        y += 2f
        while (i < lines.size || i == 0) {
            ensure(lh + 10f)
            val fit = (((spec.bottom - y - 10f) / lh).toInt()).coerceAtLeast(1)
            val chunk = if (lines.isEmpty()) listOf("") else lines.subList(i, minOf(lines.size, i + fit))
            val h = chunk.size * lh + 10f
            page.ops += DrawOp.Rect(spec.margin, y, spec.contentWidth, h, PdfColor.CODE_BG, PdfColor.RULE)
            var yy = y + 5f
            for (l in chunk) { if (l.isNotEmpty()) page.ops += DrawOp.Text(spec.margin + 8f, yy, l, PdfFont.MONO, size, PdfColor.BLACK); yy += lh }
            y += h
            i += chunk.size
            if (i >= lines.size) break
            newPage()
        }
        y += body * 0.8f
    }

    private fun image(b: PdfBlock.Image) {
        val img = b.image
        if (img == null) {
            paragraph(listOf(Run("[image: ${b.caption.ifBlank { "unavailable" }}]", PdfFont.ITALIC, PdfColor.GREY)), spec.margin, spec.contentWidth, body)
            return
        }
        val maxW = spec.contentWidth
        val maxH = (spec.bottom - spec.top) * 0.55f
        val scale = minOf(maxW / img.width, maxH / img.height, 1.5f)
        val w = img.width * scale; val h = img.height * scale
        ensure(h + if (b.caption.isBlank()) 6f else 22f)
        val x = spec.margin + (maxW - w) / 2
        page.ops += DrawOp.Image(x, y, w, h, img)
        y += h + 4f
        if (b.caption.isNotBlank()) {
            for (l in wrap(listOf(Run(b.caption, PdfFont.ITALIC, PdfColor.GREY)), 9f, maxW)) {
                val lw = l.sumOf { metrics.width(it.text, it.font, 9f).toDouble() }.toFloat()
                drawLine(l, spec.margin + (maxW - lw) / 2, 9f); y += 9f * 1.3f
            }
        }
        y += body * 0.8f
    }

    private fun table(t: PdfBlock.Table) {
        if (t.title.isNotBlank()) heading(4, t.title, record = false, next = t)
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
        if (cols == 0) return
        val size = if (cols > 5) 8.5f else 9.5f
        val pad = 4f
        val w = spec.contentWidth
        // Column widths from content (capped), proportional, with a minimum.
        val natural = FloatArray(cols) { c ->
            val cells = listOf(t.header.getOrElse(c) { "" }) + t.rows.map { it.getOrElse(c) { "" } }
            cells.maxOf { s -> minOf(metrics.width(s, PdfFont.REGULAR, size), w * 0.6f) } + 2 * pad
        }
        val minW = minOf(w / cols, 42f)
        val totalNatural = natural.sum()
        val widths = if (totalNatural <= w) FloatArray(cols) { natural[it] * (w / totalNatural) }
        else {
            val raw = FloatArray(cols) { maxOf(minW, natural[it] * (w / totalNatural)) }
            val s = raw.sum(); FloatArray(cols) { raw[it] * (w / s) }
        }
        fun rowLines(cells: List<String>, font: PdfFont) = (0 until cols).map { c -> wrap(listOf(Run(cells.getOrElse(c) { "" }, font)), size, widths[c] - 2 * pad) }
        val lh = size * 1.3f
        val headerLines = if (t.header.any { it.isNotBlank() }) rowLines(t.header, PdfFont.BOLD) else null
        fun headerHeight() = headerLines?.let { it.maxOf { l -> l.size } * lh + 2 * pad } ?: 0f
        fun drawRow(lines: List<List<List<Run>>>, bg: PdfColor?, bold: Boolean) {
            val h = lines.maxOf { it.size.coerceAtLeast(1) } * lh + 2 * pad
            if (bg != null) page.ops += DrawOp.Rect(spec.margin, y, w, h, bg, null)
            var x = spec.margin
            for (c in 0 until cols) {
                var yy = y + pad
                for (l in lines[c]) { drawLineAt(if (bold) l.map { it.copy(font = PdfFont.BOLD) } else l, x + pad, yy, size); yy += lh }
                x += widths[c]
            }
            page.ops += DrawOp.Line(spec.margin, y + h, spec.margin + w, y + h, PdfColor.RULE, 0.5f)
            y += h
        }
        ensure(headerHeight() + lh + 2 * pad + 4f)
        headerLines?.let { drawRow(it, PdfColor.LIGHT, true) }
        t.rows.forEachIndexed { i, r ->
            val lines = rowLines(r, PdfFont.REGULAR)
            val h = lines.maxOf { it.size.coerceAtLeast(1) } * lh + 2 * pad
            if (y + h > spec.bottom) {
                newPage()
                headerLines?.let { drawRow(it, PdfColor.LIGHT, true) } // repeat header on the new page
            }
            drawRow(lines, if (i % 2 == 1) PdfColor(0xFAFAFA) else null, false)
        }
        y += body * 0.9f
    }

    // ------------------------------------------------------------ text

    private fun drawLine(line: List<Run>, x: Float, size: Float) = drawLineAt(line, x, y, size)

    private fun drawLineAt(line: List<Run>, x0: Float, yy: Float, size: Float) {
        var x = x0
        for (r in line) {
            if (r.text.isEmpty()) continue
            page.ops += DrawOp.Text(x, yy, r.text, r.font, size, r.color)
            x += metrics.width(r.text, r.font, size)
        }
    }

    /** Greedy word wrap over styled runs; words longer than a line are split by characters. "\n" forces a break. */
    fun wrap(runs: List<Run>, size: Float, maxWidth: Float): List<List<Run>> {
        val lines = ArrayList<List<Run>>()
        var cur = ArrayList<Run>()
        var curW = 0f
        fun push(text: String, r: Run) {
            if (text.isEmpty()) return
            val last = cur.lastOrNull()
            if (last != null && last.font == r.font && last.color == r.color) cur[cur.size - 1] = last.copy(text = last.text + text)
            else cur += r.copy(text = text)
        }
        fun breakLine() {
            if (cur.isNotEmpty()) { val l = cur.last(); cur[cur.size - 1] = l.copy(text = l.text.trimEnd()) }
            lines += cur; cur = ArrayList(); curW = 0f
        }
        for (r in runs) {
            val tokens = Regex("\\n|[^\\s\\n]+|[ \\t]+").findAll(r.text).map { it.value }
            for (tok in tokens) {
                if (tok == "\n") { breakLine(); continue }
                val isSpace = tok.isBlank()
                if (isSpace) {
                    if (cur.isEmpty()) continue
                    val sw = metrics.width(" ", r.font, size)
                    if (curW + sw <= maxWidth) { push(" ", r); curW += sw }
                    continue
                }
                val tw = metrics.width(tok, r.font, size)
                if (curW + tw <= maxWidth) { push(tok, r); curW += tw; continue }
                if (cur.isNotEmpty()) breakLine()
                if (tw <= maxWidth) { push(tok, r); curW = tw; continue }
                for (piece in hardWrap(tok, r.font, size, maxWidth)) {
                    if (cur.isNotEmpty()) breakLine()
                    push(piece, r); curW = metrics.width(piece, r.font, size)
                }
            }
        }
        if (cur.isNotEmpty() || lines.isEmpty()) breakLine()
        return lines
    }

    private fun hardWrap(s: String, font: PdfFont, size: Float, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            var end = start + 1
            while (end < s.length && metrics.width(s.substring(start, end + 1), font, size) <= maxWidth) end++
            out += s.substring(start, end)
            start = end
        }
        return out
    }

    private fun ellipsize(s: String, font: PdfFont, size: Float, maxW: Float): String {
        if (metrics.width(s, font, size) <= maxW) return s
        var e = s.length
        while (e > 1 && metrics.width(s.substring(0, e) + "…", font, size) > maxW) e--
        return s.substring(0, e).trimEnd() + "…"
    }
}
