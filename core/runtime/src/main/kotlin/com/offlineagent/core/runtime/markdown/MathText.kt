package com.offlineagent.core.runtime.markdown

/**
 * "LaTeX-ish" math: converts common TeX to readable Unicode (α, ², √, ½, ∑, ≤ …). Not a typesetter, but cheap,
 * dependency-free and good enough for the formulas small models write in chat answers.
 */
object MathText {
    private val symbols = linkedMapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\epsilon" to "ε", "\\varepsilon" to "ε",
        "\\zeta" to "ζ", "\\eta" to "η", "\\theta" to "θ", "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ", "\\sigma" to "σ", "\\tau" to "τ", "\\phi" to "φ",
        "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω", "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ",
        "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓", "\\leq" to "≤", "\\le" to "≤",
        "\\geq" to "≥", "\\ge" to "≥", "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡", "\\sim" to "∼",
        "\\propto" to "∝", "\\infty" to "∞", "\\sum" to "∑", "\\prod" to "∏", "\\int" to "∫", "\\oint" to "∮",
        "\\partial" to "∂", "\\nabla" to "∇", "\\forall" to "∀", "\\exists" to "∃", "\\in" to "∈", "\\notin" to "∉",
        "\\subset" to "⊂", "\\subseteq" to "⊆", "\\cup" to "∪", "\\cap" to "∩", "\\emptyset" to "∅", "\\to" to "→",
        "\\rightarrow" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\iff" to "⇔",
        "\\leftrightarrow" to "↔", "\\ldots" to "…", "\\cdots" to "⋯", "\\dots" to "…", "\\degree" to "°", "\\circ" to "∘",
        "\\angle" to "∠", "\\perp" to "⊥", "\\parallel" to "∥", "\\neg" to "¬", "\\land" to "∧", "\\lor" to "∨",
        "\\quad" to "  ", "\\qquad" to "    ", "\\," to " ", "\\;" to " ", "\\!" to "", "\\%" to "%",
        "\\left" to "", "\\right" to "", "\\displaystyle" to "", "\\{" to "{", "\\}" to "}",
    )
    private val sup = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'a' to 'ᵃ', 'b' to 'ᵇ',
        'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'k' to 'ᵏ', 'm' to 'ᵐ', 't' to 'ᵗ', 'T' to 'ᵀ', 'y' to 'ʸ',
    )
    private val sub = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ',
        'n' to 'ₙ', 'o' to 'ₒ', 'x' to 'ₓ', 't' to 'ₜ', 'm' to 'ₘ', 'r' to 'ᵣ',
    )

    fun toUnicode(tex: String): String {
        var s = tex.trim()
        // \frac{a}{b} -> (a)/(b) (parentheses dropped for simple atoms); \sqrt{x} -> √(x)
        repeat(4) {
            s = Regex("\\\\[dt]?frac\\{([^{}]*)}\\{([^{}]*)}").replace(s) { m -> "${wrap(m.groupValues[1])}/${wrap(m.groupValues[2])}" }
            s = Regex("\\\\sqrt\\{([^{}]*)}").replace(s) { m -> "√" + wrap(m.groupValues[1]) }
            s = Regex("\\\\(?:text|mathrm|mathbf|mathit|operatorname)\\{([^{}]*)}").replace(s) { it.groupValues[1] }
        }
        s = Regex("\\\\(hat|bar|vec)\\{([^{}]*)}").replace(s) { m ->
            m.groupValues[2] + when (m.groupValues[1]) { "hat" -> "\u0302"; "bar" -> "\u0304"; else -> "\u20D7" }
        }
        // longest command names first so \leq is not eaten by \le
        for ((k, v) in symbols.entries.sortedByDescending { it.key.length }) {
            s = Regex(Regex.escape(k) + "(?![a-zA-Z])").replace(s, Regex.escapeReplacement(v))
        }
        s = script(s, '^', sup)
        s = script(s, '_', sub)
        return s.replace("{", "").replace("}", "").replace(Regex(" {3,}"), "  ")
    }

    private fun wrap(x: String) = if (x.length <= 1 || x.all { it.isLetterOrDigit() }) x else "($x)"

    private fun script(s: String, mark: Char, table: Map<Char, Char>): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == mark && i + 1 < s.length) {
                val group: String
                if (s[i + 1] == '{') {
                    val end = s.indexOf('}', i + 2)
                    if (end < 0) { out.append(c); i++; continue }
                    group = s.substring(i + 2, end); i = end + 1
                } else { group = s[i + 1].toString(); i += 2 }
                if (group.all { it in table }) group.forEach { out.append(table.getValue(it)) }
                else out.append(mark).append(if (group.length > 1) "($group)" else group)
                continue
            }
            out.append(c); i++
        }
        return out.toString()
    }
}

/** Token classes for code highlighting. */
enum class CodeToken { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION }

/** Tiny lexer-based syntax highlighter for common languages (no regex backtracking blow-ups, linear time). */
object SyntaxHighlighter {
    private val keywords: Map<String, Set<String>> = run {
        val c = "if else for while do return break continue switch case default goto sizeof struct union enum typedef const static void int char float double long short unsigned signed true false NULL nullptr"
        val java = "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public return short static super switch synchronized this throw throws transient try void volatile while true false null var record"
        val kotlin = "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor data enum external final finally get import init inline internal lateinit open operator out override private protected public sealed set suspend tailrec vararg companion reified"
        val py = "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield self print"
        val js = "break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new return super switch this throw try typeof var void while with yield async await true false null undefined of"
        val sql = "select from where insert into values update set delete create table drop alter join left right inner outer on group by order having limit as and or not null is in like distinct count sum avg min max primary key"
        val sh = "if then else elif fi for while do done case esac function in echo export local return exit"
        mapOf(
            "c" to c, "cpp" to "$c class public private protected virtual template typename namespace using new delete auto bool this override",
            "java" to java, "kotlin" to kotlin, "kt" to kotlin, "python" to py, "py" to py, "javascript" to js, "js" to js,
            "typescript" to "$js interface type enum implements public private readonly", "ts" to "$js interface type enum implements",
            "sql" to sql, "bash" to sh, "sh" to sh, "shell" to sh, "go" to "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil",
            "rust" to "as break const continue crate else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while",
            "dart" to "$java final late required async await",
        ).mapValues { (k, v) -> v.split(' ').filter { it.isNotBlank() }.map { if (k == "sql") it.lowercase() else it }.toSet() }
    }

    data class Span(val start: Int, val end: Int, val token: CodeToken)

    fun highlight(code: String, language: String): List<Span> {
        val lang = language.lowercase()
        val kw = keywords[lang] ?: keywords["kotlin"]!!.union(keywords["python"]!!)
        val hashComments = lang in setOf("python", "py", "bash", "sh", "shell", "ruby", "r", "yaml", "toml")
        val sql = lang == "sql"
        val spans = mutableListOf<Span>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            when {
                (c == '/' && i + 1 < n && code[i + 1] == '/') || (hashComments && c == '#') || (sql && c == '-' && i + 1 < n && code[i + 1] == '-') -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    spans += Span(i, end, CodeToken.COMMENT); i = end
                }
                c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    spans += Span(i, end, CodeToken.COMMENT); i = end
                }
                c == '"' || c == '\'' || c == '`' -> {
                    var j = i + 1
                    while (j < n && code[j] != c && !(code[j] == '\n' && c != '`')) { if (code[j] == '\\') j++; j++ }
                    val end = minOf(n, j + 1)
                    spans += Span(i, end, CodeToken.STRING); i = end
                }
                c.isDigit() -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    spans += Span(i, j, CodeToken.NUMBER); i = j
                }
                c.isLetter() || c == '_' || c == '@' -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                    val word = code.substring(i, j)
                    val token = when {
                        (if (sql) word.lowercase() else word) in kw -> CodeToken.KEYWORD
                        j < n && code[j] == '(' -> CodeToken.FUNCTION
                        word[0].isUpperCase() -> CodeToken.TYPE
                        else -> null
                    }
                    if (token != null) spans += Span(i, j, token)
                    i = j
                }
                else -> i++
            }
        }
        return spans
    }
}
