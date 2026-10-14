package com.offlineagent.core.runtime.markdown

/**
 * Small, fast CommonMark-ish parser for chat answers (no external dependency, JVM-testable).
 * Supports: ATX headings, paragraphs, bold/italic/strike, inline code, links, inline/block math ($…$, $$…$$, \(..\), \[..\]),
 * fenced code blocks (unterminated fences are treated as code until the end, which keeps streaming output stable),
 * block quotes, ordered/unordered/task lists (with nesting by indentation), GFM tables, horizontal rules.
 * Parsing a few KB takes well under a millisecond, so the UI simply re-parses while streaming (throttled).
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val inlines: List<Inline>) : MdBlock
    data class Paragraph(val inlines: List<Inline>) : MdBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Table(val header: List<List<Inline>>, val align: List<Align>, val rows: List<List<List<Inline>>>) : MdBlock
    data class Math(val tex: String) : MdBlock
    data object Rule : MdBlock
}

data class ListItem(val blocks: List<MdBlock>, val task: Boolean? = null)

enum class Align { LEFT, CENTER, RIGHT }

sealed interface Inline {
    data class Text(val text: String) : Inline
    data class Bold(val children: List<Inline>) : Inline
    data class Italic(val children: List<Inline>) : Inline
    data class Strike(val children: List<Inline>) : Inline
    data class Code(val code: String) : Inline
    data class Link(val text: List<Inline>, val url: String) : Inline
    data class Math(val tex: String) : Inline
    /** [n] citation markers produced by RAG answers. */
    data class Citation(val n: Int) : Inline
    data object LineBreak : Inline
}

object Markdown {
    fun parse(text: String): List<MdBlock> = BlockParser(text.replace("\r\n", "\n").replace('\t', ' ').lines()).parseAll()

    // ---------------- blocks ----------------
    private val heading = Regex("^ {0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val fence = Regex("^ {0,3}(```+|~~~+)\\s*([\\w+#.-]*).*$")
    private val rule = Regex("^ {0,3}([-*_])( *\\1){2,}\\s*$")
    private val bullet = Regex("^( *)([-*+])\\s+(.*)$")
    private val ordered = Regex("^( *)(\\d{1,9})[.)]\\s+(.*)$")
    private val task = Regex("^\\[([ xX])]\\s+(.*)$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")

    private class BlockParser(val lines: List<String>) {
        var i = 0

        fun parseAll(): List<MdBlock> {
            val out = mutableListOf<MdBlock>()
            while (i < lines.size) {
                parseBlock()?.let { out += it }
            }
            return out
        }

        fun parseBlock(): MdBlock? {
            val line = lines[i]
            if (line.isBlank()) { i++; return null }
            fence.matchEntire(line)?.let { m -> return codeBlock(m.groupValues[1], m.groupValues[2]) }
            val t = line.trim()
            if (t.startsWith("$$") || t.startsWith("\\[")) mathBlock(t)?.let { return it }
            heading.matchEntire(line)?.let { m -> i++; return MdBlock.Heading(m.groupValues[1].length, inlines(m.groupValues[2])) }
            if (rule.matches(line)) { i++; return MdBlock.Rule }
            if (t.startsWith(">")) return quote()
            if (bullet.matches(line) || ordered.matches(line)) return list()
            if (t.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && lines[i + 1].contains('-')) return table()
            return paragraph()
        }

        fun codeBlock(marker: String, lang: String): MdBlock {
            i++
            val sb = StringBuilder()
            while (i < lines.size) {
                val l = lines[i]
                if (l.trim().startsWith(marker.substring(0, 3)) && l.trim().trimStart(marker[0]).isBlank()) {
                    i++
                    return MdBlock.Code(lang, sb.toString().trimEnd('\n'), closed = true)
                }
                sb.append(l).append('\n')
                i++
            }
            return MdBlock.Code(lang, sb.toString().trimEnd('\n'), closed = false)
        }

        fun mathBlock(first: String): MdBlock? {
            val open = if (first.startsWith("$$")) "$$" else "\\["
            val close = if (open == "$$") "$$" else "\\]"
            val rest = first.removePrefix(open)
            if (rest.contains(close)) { i++; return MdBlock.Math(rest.substringBefore(close).trim()) }
            val sb = StringBuilder(rest)
            var j = i + 1
            while (j < lines.size) {
                val l = lines[j]
                if (l.contains(close)) {
                    sb.append('\n').append(l.substringBefore(close))
                    i = j + 1
                    return MdBlock.Math(sb.toString().trim())
                }
                sb.append('\n').append(l)
                j++
            }
            // unterminated (still streaming): render what we have
            i = lines.size
            return MdBlock.Math(sb.toString().trim())
        }

        fun quote(): MdBlock {
            val inner = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                inner += lines[i].trim().removePrefix(">").removePrefix(" ")
                i++
            }
            return MdBlock.Quote(BlockParser(inner).parseAll())
        }

        fun list(): MdBlock {
            val first = lines[i]
            val isOrdered = ordered.matches(first) && !bullet.matches(first)
            val baseIndent = first.length - first.trimStart().length
            val start = if (isOrdered) ordered.matchEntire(first)!!.groupValues[2].toInt() else 1
            val items = mutableListOf<ListItem>()
            while (i < lines.size) {
                val l = lines[i]
                val m = (if (isOrdered) ordered else bullet).matchEntire(l)
                val indent = l.length - l.trimStart().length
                if (m == null || indent != baseIndent) break
                var content = m.groupValues[3]
                var taskState: Boolean? = null
                task.matchEntire(content)?.let { tm -> taskState = tm.groupValues[1] != " "; content = tm.groupValues[2] }
                val itemLines = mutableListOf(content)
                i++
                // continuation + nested lines (indented deeper than the marker)
                while (i < lines.size) {
                    val n = lines[i]
                    val nIndent = n.length - n.trimStart().length
                    if (n.isBlank()) {
                        val next = lines.getOrNull(i + 1)
                        if (next != null && (next.length - next.trimStart().length) > baseIndent && next.isNotBlank()) { itemLines += ""; i++; continue }
                        break
                    }
                    if (nIndent > baseIndent) { itemLines += n.drop(minOf(nIndent, baseIndent + 2)); i++; continue }
                    if (!bullet.matches(n) && !ordered.matches(n) && !n.trim().startsWith("#") && !n.trim().startsWith(">") && !fence.matches(n)) {
                        itemLines += n.trim(); i++; continue   // lazy continuation
                    }
                    break
                }
                items += ListItem(BlockParser(itemLines).parseAll(), taskState)
                // allow a single blank line between items
                if (i < lines.size && lines[i].isBlank()) {
                    val next = lines.getOrNull(i + 1)
                    if (next != null && (if (isOrdered) ordered else bullet).matches(next) && (next.length - next.trimStart().length) == baseIndent) i++
                }
            }
            return MdBlock.ListBlock(isOrdered, start, items)
        }

        fun table(): MdBlock {
            val header = splitRow(lines[i]).map { inlines(it) }
            val align = splitRow(lines[i + 1]).map {
                val s = it.trim()
                when {
                    s.startsWith(":") && s.endsWith(":") -> Align.CENTER
                    s.endsWith(":") -> Align.RIGHT
                    else -> Align.LEFT
                }
            }
            i += 2
            val rows = mutableListOf<List<List<Inline>>>()
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                val cells = splitRow(lines[i]).map { inlines(it) }
                rows += List(header.size) { c -> cells.getOrElse(c) { emptyList() } }
                i++
            }
            return MdBlock.Table(header, List(header.size) { align.getOrElse(it) { Align.LEFT } }, rows)
        }

        fun paragraph(): MdBlock {
            val sb = StringBuilder()
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) break
                if (sb.isNotEmpty() && (heading.matches(l) || fence.matches(l) || rule.matches(l) || l.trim().startsWith(">") ||
                        bullet.matches(l) || ordered.matches(l) || l.trim().startsWith("$$"))) break
                if (sb.isNotEmpty()) sb.append(if (sb.endsWith("  ")) "\u2028" else "\n")
                sb.append(l.trim())
                i++
            }
            return MdBlock.Paragraph(inlines(sb.toString()))
        }
    }

    fun splitRow(row: String): List<String> {
        var r = row.trim()
        if (r.startsWith("|")) r = r.drop(1)
        if (r.endsWith("|") && !r.endsWith("\\|")) r = r.dropLast(1)
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var k = 0
        while (k < r.length) {
            val c = r[k]
            if (c == '\\' && k + 1 < r.length && r[k + 1] == '|') { sb.append('|'); k += 2; continue }
            if (c == '|') { cells += sb.toString().trim(); sb.clear() } else sb.append(c)
            k++
        }
        cells += sb.toString().trim()
        return cells
    }

    // ---------------- inlines ----------------
    fun inlines(text: String): List<Inline> = InlineParser(text).parse(null)

    private class InlineParser(val s: String) {
        var p = 0

        fun parse(until: String?): List<Inline> {
            val out = mutableListOf<Inline>()
            val buf = StringBuilder()
            fun flush() { if (buf.isNotEmpty()) { out += Inline.Text(buf.toString()); buf.clear() } }
            while (p < s.length) {
                if (until != null && s.startsWith(until, p)) { flush(); return out }
                val c = s[p]
                when {
                    c == '\\' && p + 1 < s.length && s[p + 1] == '(' -> {
                        val end = s.indexOf("\\)", p + 2)
                        if (end > 0) { flush(); out += Inline.Math(s.substring(p + 2, end)); p = end + 2 } else { buf.append(c); p++ }
                    }
                    c == '\\' && p + 1 < s.length && s[p + 1] in "\\`*_{}[]()#+-.!|$~" -> { buf.append(s[p + 1]); p += 2 }
                    c == '\n' -> { flush(); out += Inline.Text(" "); p++ }
                    c == '\u2028' -> { flush(); out += Inline.LineBreak; p++ }
                    c == '`' -> {
                        val ticks = s.drop(p).takeWhile { it == '`' }.length
                        val end = s.indexOf("`".repeat(ticks), p + ticks)
                        if (end > 0) { flush(); out += Inline.Code(s.substring(p + ticks, end).trim()); p = end + ticks }
                        else { buf.append("`".repeat(ticks)); p += ticks }
                    }
                    c == '$' && p + 1 < s.length && s[p + 1] != ' ' && s[p + 1] != '$' -> {
                        val end = s.indexOf('$', p + 1)
                        // avoid treating prices like "$5 and $10" as math: require no space before closing $ and non-digit start
                        if (end > p + 1 && s[end - 1] != ' ' && !s[p + 1].isDigit()) {
                            flush(); out += Inline.Math(s.substring(p + 1, end)); p = end + 1
                        } else { buf.append(c); p++ }
                    }
                    (c == '*' || c == '_') && s.startsWith("$c$c", p) -> {
                        val marker = "$c$c"
                        val close = s.indexOf(marker, p + 2)
                        if (close > p + 2) { flush(); p += 2; val kids = parse(marker); p += 2; out += Inline.Bold(kids) }
                        else { buf.append(marker); p += 2 }
                    }
                    (c == '*' || c == '_') -> {
                        val close = s.indexOf(c, p + 1)
                        val leftOk = p + 1 < s.length && !s[p + 1].isWhitespace()
                        val wordInside = c == '_' && p > 0 && s[p - 1].isLetterOrDigit()
                        if (close > p + 1 && leftOk && !wordInside && !s[close - 1].isWhitespace()) {
                            flush(); p++; val kids = parse(c.toString()); p++; out += Inline.Italic(kids)
                        } else { buf.append(c); p++ }
                    }
                    c == '~' && s.startsWith("~~", p) -> {
                        val close = s.indexOf("~~", p + 2)
                        if (close > 0) { flush(); p += 2; val kids = parse("~~"); p += 2; out += Inline.Strike(kids) }
                        else { buf.append("~~"); p += 2 }
                    }
                    c == '[' -> {
                        val cite = Regex("^\\[(\\d{1,2})]").find(s.substring(p))
                        val closeBracket = s.indexOf(']', p)
                        if (cite != null) {
                            flush(); out += Inline.Citation(cite.groupValues[1].toInt()); p += cite.value.length
                        } else if (closeBracket > 0 && closeBracket + 1 < s.length && s[closeBracket + 1] == '(' && s.indexOf(')', closeBracket) > 0) {
                            val urlEnd = s.indexOf(')', closeBracket)
                            flush()
                            val label = InlineParser(s.substring(p + 1, closeBracket)).parse(null)
                            out += Inline.Link(label, s.substring(closeBracket + 2, urlEnd).trim())
                            p = urlEnd + 1
                        } else { buf.append(c); p++ }
                    }
                    else -> { buf.append(c); p++ }
                }
            }
            flush()
            return out
        }
    }

    /** Plain text of inlines (search, accessibility, copy). */
    fun plain(inlines: List<Inline>): String = buildString {
        inlines.forEach {
            when (it) {
                is Inline.Text -> append(it.text)
                is Inline.Bold -> append(plain(it.children))
                is Inline.Italic -> append(plain(it.children))
                is Inline.Strike -> append(plain(it.children))
                is Inline.Code -> append(it.code)
                is Inline.Link -> append(plain(it.text))
                is Inline.Math -> append(MathText.toUnicode(it.tex))
                is Inline.Citation -> append("[${it.n}]")
                Inline.LineBreak -> append('\n')
            }
        }
    }
}
