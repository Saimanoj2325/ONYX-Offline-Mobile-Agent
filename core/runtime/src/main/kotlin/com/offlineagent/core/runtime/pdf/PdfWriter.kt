package com.offlineagent.core.runtime.pdf

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.Deflater

/**
 * Widths (1/1000 em) of the PDF standard-14 Helvetica / Helvetica-Bold / Courier fonts for ASCII, from Adobe's AFM
 * files. Oblique variants share the upright widths. Unknown characters use an average width.
 */
object Type1Metrics : FontMetrics {
    private val HELV = intArrayOf(
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278, // 32-47
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556, // 48-63
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, // 64-79
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556, // 80-95
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, // 96-111
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,       // 112-126
    )
    private val HELV_BOLD = intArrayOf(
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 333, 333, 584, 584, 584, 611,
        975, 722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 333, 278, 333, 584, 556,
        333, 556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611,
        611, 611, 389, 556, 333, 611, 556, 778, 556, 556, 500, 389, 280, 389, 584,
    )
    private val EXTRA = mapOf('•' to 350, '–' to 556, '—' to 1000, '‘' to 222, '’' to 222, '“' to 333, '”' to 333, '…' to 1000, '·' to 278, '€' to 556, '©' to 737, '®' to 737, '°' to 400)

    fun charWidth(c: Char, font: PdfFont): Int {
        if (font.isMono) return 600
        val table = if (font == PdfFont.BOLD || font == PdfFont.BOLD_ITALIC) HELV_BOLD else HELV
        val code = c.code
        return if (code in 32..126) table[code - 32] else EXTRA[c] ?: 556
    }

    override fun width(text: String, font: PdfFont, size: Float): Float {
        var w = 0
        for (c in text) w += charWidth(c, font)
        return w * size / 1000f
    }
}

/**
 * Minimal, dependency-free PDF 1.4 writer for [PdfLayoutResult]s. Uses the standard-14 Type1 fonts (WinAnsi
 * encoding: Latin text; other scripts print as '?'), Flate-compressed content streams and JPEG images (DCTDecode).
 * The Android app renders with android.graphics.pdf.PdfDocument instead (full Unicode via system fonts); this writer
 * is used on the JVM (tests, host harness) and as a fallback.
 */
class PdfWriter(private val compress: Boolean = true) {
    private val win1252: Charset = Charset.forName("windows-1252")

    fun write(layout: PdfLayoutResult, title: String = "", author: String = ""): ByteArray {
        val spec = layout.spec
        val objects = ArrayList<ByteArray>() // index i => object number i+1
        fun add(content: ByteArray): Int { objects += content; return objects.size }
        fun reserve(): Int { objects += ByteArray(0); return objects.size }
        fun set(n: Int, content: ByteArray) { objects[n - 1] = content }

        val catalog = reserve()
        val pagesObj = reserve()
        val fontNames = mapOf(
            PdfFont.REGULAR to "Helvetica", PdfFont.BOLD to "Helvetica-Bold", PdfFont.ITALIC to "Helvetica-Oblique",
            PdfFont.BOLD_ITALIC to "Helvetica-BoldOblique", PdfFont.MONO to "Courier", PdfFont.MONO_BOLD to "Courier-Bold",
        )
        val fontObj = fontNames.mapValues { (_, base) ->
            add("<< /Type /Font /Subtype /Type1 /BaseFont /$base /Encoding /WinAnsiEncoding >>".toByteArray())
        }
        val fontRes = fontObj.entries.joinToString(" ") { (f, n) -> "/F${f.ordinal} $n 0 R" }
        val imageIds = HashMap<PdfImage, Int>()
        val pageIds = ArrayList<Int>()
        for (p in layout.pages) {
            val usedImages = LinkedHashMap<PdfImage, String>()
            val cs = StringBuilder()
            for (op in p.ops) when (op) {
                is DrawOp.Rect -> {
                    val y = spec.height - op.y - op.h
                    op.fill?.let { cs.append(rgb(it, "rg")).append(f(op.x)).append(' ').append(f(y)).append(' ').append(f(op.w)).append(' ').append(f(op.h)).append(" re f\n") }
                    op.stroke?.let { cs.append("0.5 w ").append(rgb(it, "RG")).append(f(op.x)).append(' ').append(f(y)).append(' ').append(f(op.w)).append(' ').append(f(op.h)).append(" re S\n") }
                }
                is DrawOp.Line -> cs.append(f(op.width)).append(" w ").append(rgb(op.color, "RG"))
                    .append(f(op.x1)).append(' ').append(f(spec.height - op.y1)).append(" m ")
                    .append(f(op.x2)).append(' ').append(f(spec.height - op.y2)).append(" l S\n")
                is DrawOp.Text -> {
                    val baseline = spec.height - op.y - op.size * 0.8f
                    cs.append("BT ").append(rgb(op.color, "rg")).append("/F").append(op.font.ordinal).append(' ').append(f(op.size)).append(" Tf ")
                        .append(f(op.x)).append(' ').append(f(baseline)).append(" Td (").append(escape(op.text)).append(") Tj ET\n")
                }
                is DrawOp.Image -> {
                    if (!op.image.isJpeg) continue
                    val name = usedImages.getOrPut(op.image) { "Im${usedImages.size}" }
                    cs.append("q ").append(f(op.w)).append(" 0 0 ").append(f(op.h)).append(' ').append(f(op.x)).append(' ')
                        .append(f(spec.height - op.y - op.h)).append(" cm /").append(name).append(" Do Q\n")
                }
            }
            val raw = cs.toString().toByteArray(win1252)
            val content = stream(raw)
            val contentId = add(content)
            val xo = usedImages.entries.joinToString(" ") { (img, name) ->
                val id = imageIds.getOrPut(img) {
                    add(streamRaw("/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", img.bytes))
                }
                "/$name $id 0 R"
            }
            val res = "<< /Font << $fontRes >>" + (if (xo.isNotEmpty()) " /XObject << $xo >>" else "") + " >>"
            pageIds += add("<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 ${f(spec.width)} ${f(spec.height)}] /Resources $res /Contents $contentId 0 R >>".toByteArray())
        }
        set(pagesObj, "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>".toByteArray())
        set(catalog, "<< /Type /Catalog /Pages $pagesObj 0 R >>".toByteArray())
        val info = add("<< /Title (${escape(title)}) /Author (${escape(author)}) /Producer (Offline Agent) >>".toByteArray(win1252))

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = LongArray(objects.size)
        objects.forEachIndexed { i, body ->
            offsets[i] = out.size().toLong()
            out.write("${i + 1} 0 obj\n".toByteArray()); out.write(body); out.write("\nendobj\n".toByteArray())
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format("%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray())
        return out.toByteArray()
    }

    private fun stream(data: ByteArray): ByteArray = if (compress) streamRaw("/Filter /FlateDecode", deflate(data)) else streamRaw("", data)

    private fun streamRaw(dict: String, data: ByteArray): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("<< $dict /Length ${data.size} >>\nstream\n".toByteArray())
        o.write(data)
        o.write("\nendstream".toByteArray())
        return o.toByteArray()
    }

    private fun deflate(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_SPEED)
        d.setInput(b); d.finish()
        val o = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) { val n = d.deflate(buf); o.write(buf, 0, n) }
        d.end()
        return o.toByteArray()
    }

    private fun f(v: Float): String = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, v)
    private fun rgb(c: PdfColor, op: String) = "${f(c.r)} ${f(c.g)} ${f(c.b)} $op "

    private fun escape(s: String): String {
        val enc = win1252.newEncoder()
        val sb = StringBuilder()
        for (ch in s) {
            val c = if (enc.canEncode(ch)) ch else '?'
            when (c) { '(' , ')', '\\' -> sb.append('\\').append(c); '\n', '\r' -> sb.append(' '); else -> sb.append(c) }
        }
        return sb.toString()
    }

    companion object {
        /** Lays out and writes [doc] in one go using the built-in Type1 metrics. */
        fun render(doc: PdfDocModel, spec: PageSpec = PageSpec()): Pair<ByteArray, PdfLayoutResult> {
            val layout = PdfLayout(Type1Metrics, spec).layout(doc)
            return PdfWriter().write(layout, doc.title, doc.author) to layout
        }
    }
}
