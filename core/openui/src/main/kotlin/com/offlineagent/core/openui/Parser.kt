package com.offlineagent.core.openui

/**
 * OpenUI Lang parser. Never throws: bad statements become [InvalidLine]s.
 *
 * Streaming: [OpenUiStreamParser] re-uses the statements that are already complete and only re-parses the trailing,
 * still-growing statement in *partial* mode (open strings/arrays/calls are closed virtually), so the UI shell renders
 * as soon as `root = Stack([...` arrives and fills in as the model writes the following lines.
 */
object OpenUiParser {

    /** Parses a complete program text. */
    fun parse(text: String): Program {
        val p = OpenUiStreamParser()
        p.append(text)
        return p.finish()
    }

    /** Parses one statement (`name = expr`). [partial] = the statement may be cut off (stream still running). */
    fun parseStatement(raw: String, partial: Boolean = false): Any /* Statement | InvalidLine */ {
        val line = raw.trim()
        if (line.isEmpty()) return InvalidLine(raw, "empty")
        val m = InvalidLine.ASSIGN.find(line) ?: return InvalidLine(raw, "not an assignment")
        val name = m.groupValues[1]
        val rhs = line.substring(m.range.last + 1)
        if (rhs.isBlank()) return if (partial) Statement(name, OuValue.Null, raw, complete = false) else InvalidLine(raw, "missing expression")
        val c = Cursor(rhs, partial)
        return try {
            val v = c.expr()
            c.ws()
            if (!c.eof()) {
                // Tolerate a trailing comment / semicolon, reject anything else (operators are not supported).
                val rest = rhs.substring(c.i).trim()
                if (rest.startsWith("//") || rest == ";") Statement(name, v, raw, !partial)
                else InvalidLine(raw, "unexpected '${rest.take(12)}'")
            } else Statement(name, v, raw, !partial)
        } catch (e: ParseError) {
            InvalidLine(raw, e.message ?: "syntax error")
        }
    }

    private class ParseError(msg: String) : Exception(msg)

    /** Recursive-descent cursor. In partial mode, EOF inside a construct closes it instead of failing. */
    private class Cursor(val s: String, val partial: Boolean) {
        var i = 0
        var depth = 0
        fun eof() = i >= s.length
        fun ws() { while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++ }
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun fail(msg: String): Nothing = throw ParseError(msg)

        fun expr(): OuValue {
            if (++depth > 64) fail("nesting too deep")
            try {
                ws()
                if (eof()) { if (partial) return OuValue.Null; fail("unexpected end") }
                val c = peek()
                return when {
                    c == '"' || c == '\'' -> OuValue.Str(string(c))
                    c == '[' -> array()
                    c == '{' -> obj()
                    c == '-' || c.isDigit() -> number()
                    c == '@' -> { i++; val n = ident(); call(n, at = true) }
                    c == '$' -> { i++; OuValue.StateRef("$" + ident()) }
                    c.isLetter() || c == '_' -> {
                        val n = ident()
                        ws()
                        when {
                            peek() == '(' -> call(n, at = false)
                            n == "true" -> OuValue.Bool(true)
                            n == "false" -> OuValue.Bool(false)
                            n == "null" || n == "undefined" -> OuValue.Null
                            else -> OuValue.Ref(n)
                        }
                    }
                    else -> fail("unexpected '$c'")
                }
            } finally { depth-- }
        }

        fun ident(): String {
            val st = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            if (st == i) fail("identifier expected")
            return s.substring(st, i)
        }

        fun number(): OuValue {
            val st = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || ((s[i] == '+' || s[i] == '-') && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
            val t = s.substring(st, i)
            return t.toDoubleOrNull()?.let { OuValue.Num(it) } ?: if (partial && eof()) OuValue.Null else fail("bad number '$t'")
        }

        fun string(q: Char): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (eof()) { if (partial) return sb.toString(); fail("unterminated string") }
                val c = s[i++]
                when {
                    c == q -> return sb.toString()
                    c == '\\' -> {
                        if (eof()) { if (partial) return sb.toString(); fail("bad escape") }
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                val cp = hex.toIntOrNull(16)
                                if (hex.length == 4 && cp != null) { sb.append(cp.toChar()); i += 4 }
                                else if (partial && i + hex.length >= s.length) return sb.toString()
                                else sb.append("\\u")
                            }
                            else -> sb.append(e) // \" \\ \/ and unknown escapes keep the char
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun array(): OuValue {
            i++ // [
            val items = mutableListOf<OuValue>()
            while (true) {
                ws()
                if (eof()) { if (partial) return OuValue.Arr(items, partial = true); fail("unclosed [") }
                if (peek() == ']') { i++; return OuValue.Arr(items) }
                items += expr()
                ws()
                if (eof()) { if (partial) return OuValue.Arr(items, partial = true); fail("unclosed [") }
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return OuValue.Arr(items) }
                    else -> fail("expected , or ] in array")
                }
            }
        }

        fun obj(): OuValue {
            i++ // {
            val m = LinkedHashMap<String, OuValue>()
            while (true) {
                ws()
                if (eof()) { if (partial) return OuValue.Obj(m, true); fail("unclosed {") }
                if (peek() == '}') { i++; return OuValue.Obj(m) }
                val key = when (peek()) { '"', '\'' -> string(peek()); else -> ident() }
                ws()
                if (eof()) { if (partial) return OuValue.Obj(m, true); fail("unclosed {") }
                if (peek() != ':') fail("expected : in object")
                i++
                m[key] = expr()
                ws()
                if (eof()) { if (partial) return OuValue.Obj(m, true); fail("unclosed {") }
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return OuValue.Obj(m) }
                    else -> fail("expected , or } in object")
                }
            }
        }

        fun call(name: String, at: Boolean): OuValue {
            ws()
            if (eof() && partial) return if (at) OuValue.At(name, emptyList(), true) else OuValue.Call(name, emptyList(), true)
            if (peek() != '(') fail("expected ( after $name")
            i++
            val args = mutableListOf<OuValue>()
            fun done(p: Boolean) = if (at) OuValue.At(name, args, p) else OuValue.Call(name, args, p)
            while (true) {
                ws()
                if (eof()) { if (partial) return done(true); fail("unclosed $name(") }
                if (peek() == ')') { i++; return done(false) }
                // Named arguments (`label: "x"` / `label = "x"`) are not OpenUI Lang, but small models write them: keep the value.
                val save = i
                if (peek().isLetter()) {
                    val n = runCatching { ident() }.getOrNull()
                    ws()
                    if (n != null && (peek() == ':' || (peek() == '=' && s.getOrNull(i + 1) != '='))) { i++ } else i = save
                }
                args += expr()
                ws()
                if (eof()) { if (partial) return done(true); fail("unclosed $name(") }
                when (peek()) {
                    ',' -> i++
                    ')' -> { i++; return done(false) }
                    else -> fail("expected , or ) in $name(…)")
                }
            }
        }
    }
}

/**
 * Incremental parser for a token stream. Feed text with [append]; [snapshot] returns the program so far (complete
 * statements + the trailing partial one); [finish] parses the remainder strictly.
 *
 * Statement boundaries: a newline outside strings at bracket depth 0. A newline at depth > 0 still ends the statement
 * when the next line clearly starts a new one (`name = …`), so one unclosed bracket can't swallow the rest.
 */
class OpenUiStreamParser {
    private val buf = StringBuilder()
    private var consumed = 0
    private val done = mutableListOf<Statement>()
    private val invalid = mutableListOf<InvalidLine>()

    fun append(text: String): OpenUiStreamParser { buf.append(text); scan(); return this }

    val text: String get() = buf.toString()

    private fun scan() {
        var start = consumed
        var depth = 0
        var inStr = false
        var q = '"'
        var esc = false
        var i = consumed
        while (i < buf.length) {
            val c = buf[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == q -> inStr = false
                    c == '\n' -> if (startsStatement(i + 1)) { inStr = false; depth = 0 } // runaway string
                }
                if (c == '\n' && !inStr && depth == 0) { emit(start, i); start = i + 1 }
            } else {
                when (c) {
                    '"', '\'' -> if (c == '"' || isQuoteStart(i)) { inStr = true; q = c }
                    '[', '(', '{' -> depth++
                    ']', ')', '}' -> if (depth > 0) depth--
                    '\n' -> if (depth == 0 || startsStatement(i + 1)) { emit(start, i); start = i + 1; depth = 0 }
                }
            }
            i++
        }
        consumed = start
    }

    /** A single quote starts a string only after `(`, `[`, `,`, `=`, `:` (apostrophes inside prose are not strings). */
    private fun isQuoteStart(i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && (buf[j] == ' ' || buf[j] == '\t')) j--
        return j >= 0 && buf[j] in "([,=:"
    }

    /** True when the text at [from] is a complete `name =` prefix (needs the line to have streamed far enough). */
    private fun startsStatement(from: Int): Boolean {
        var e = from
        while (e < buf.length && buf[e] != '\n' && e - from < 80) e++
        val line = buf.substring(from, e)
        return InvalidLine.ASSIGN.containsMatchIn(line)
    }

    private fun emit(start: Int, end: Int) {
        val raw = buf.substring(start, end).trimEnd('\r')
        if (raw.isBlank()) return
        when (val r = OpenUiParser.parseStatement(raw, partial = false)) {
            is Statement -> done += r
            is InvalidLine -> invalid += r
        }
    }

    /** Program so far: finished statements + the trailing one parsed leniently (not added to [Program.invalid]). */
    fun snapshot(): Program {
        val tail = buf.substring(consumed)
        if (tail.isBlank()) return Program(done.toList(), invalid.toList())
        return when (val r = OpenUiParser.parseStatement(tail, partial = true)) {
            is Statement -> Program(done + r, invalid.toList())
            else -> Program(done.toList(), invalid.toList())
        }
    }

    /** End of stream: the trailing statement is parsed strictly — but a cut-off last line is still kept (closed). */
    fun finish(): Program {
        val tail = buf.substring(consumed)
        if (tail.isNotBlank()) {
            when (val strict = OpenUiParser.parseStatement(tail, partial = false)) {
                is Statement -> done += strict
                is InvalidLine -> when (val lenient = OpenUiParser.parseStatement(tail, partial = true)) {
                    // Generation stopped mid-statement (max tokens / Stop): keep what is there rather than losing it.
                    is Statement -> if (lenient.value != OuValue.Null) done += lenient.copy(complete = true) else invalid += strict
                    else -> invalid += strict
                }
            }
            consumed = buf.length
        }
        return Program(done.toList(), invalid.toList())
    }
}
