package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.markdown.Align
import com.offlineagent.core.runtime.markdown.CodeToken
import com.offlineagent.core.runtime.markdown.Inline
import com.offlineagent.core.runtime.markdown.Markdown
import com.offlineagent.core.runtime.markdown.MathText
import com.offlineagent.core.runtime.markdown.MdBlock
import com.offlineagent.core.runtime.markdown.SyntaxHighlighter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun headingsParagraphsAndInlines() {
        val b = Markdown.parse("# Title\n\nSome **bold** and *italic* and `code` and ~~gone~~.\nSecond line.")
        assertEquals(2, b.size)
        assertEquals(1, (b[0] as MdBlock.Heading).level)
        val inl = (b[1] as MdBlock.Paragraph).inlines
        assertTrue(inl.any { it is Inline.Bold })
        assertTrue(inl.any { it is Inline.Italic })
        assertTrue(inl.any { it is Inline.Code && it.code == "code" })
        assertTrue(inl.any { it is Inline.Strike })
        assertEquals("Some bold and italic and code and gone. Second line.", Markdown.plain(inl))
    }

    @Test fun listsNestedAndTasks() {
        val b = Markdown.parse("- one\n- two\n  - nested\n- [x] done\n\n1. first\n2. second")
        val ul = b[0] as MdBlock.ListBlock
        assertFalse(ul.ordered)
        assertEquals(3, ul.items.size)
        assertTrue(ul.items[1].blocks.any { it is MdBlock.ListBlock })
        assertEquals(true, ul.items[2].task)
        val ol = b[1] as MdBlock.ListBlock
        assertTrue(ol.ordered)
        assertEquals(2, ol.items.size)
    }

    @Test fun tablesWithAlignmentAndEscapedPipes() {
        val b = Markdown.parse("| A | B |\n|:--|--:|\n| 1 | x\\|y |\n| 2 |")
        val t = b[0] as MdBlock.Table
        assertEquals(listOf(Align.LEFT, Align.RIGHT), t.align)
        assertEquals(2, t.rows.size)
        assertEquals("x|y", Markdown.plain(t.rows[0][1]))
        assertEquals(2, t.rows[1].size) // padded
    }

    @Test fun codeBlocksClosedAndStreaming() {
        val closed = Markdown.parse("```kotlin\nval x = 1\n```\nafter")
        assertEquals(MdBlock.Code("kotlin", "val x = 1", true), closed[0])
        val open = Markdown.parse("text\n```py\nprint(1)\n# still streaming")
        val code = open[1] as MdBlock.Code
        assertFalse(code.closed)
        assertTrue(code.code.contains("still streaming"))
    }

    @Test fun quotesRulesLinksMathCitations() {
        val b = Markdown.parse("> quoted **text**\n\n---\n\nSee [docs](https://x.y) and \$x^2\$ per [1].\n\n$$\n\\frac{a}{b}\n$$")
        assertTrue(b[0] is MdBlock.Quote)
        assertEquals(MdBlock.Rule, b[1])
        val p = (b[2] as MdBlock.Paragraph).inlines
        assertTrue(p.any { it is Inline.Link && it.url == "https://x.y" })
        assertTrue(p.any { it is Inline.Math && it.tex == "x^2" })
        assertTrue(p.any { it is Inline.Citation && it.n == 1 })
        assertEquals("\\frac{a}{b}", (b[3] as MdBlock.Math).tex)
    }

    @Test fun pricesAreNotMath() {
        val p = (Markdown.parse("It costs \$5 or \$10.")[0] as MdBlock.Paragraph).inlines
        assertTrue(p.none { it is Inline.Math })
    }

    @Test fun unclosedEmphasisIsLiteral() {
        val p = (Markdown.parse("2 * 3 = 6 and **open")[0] as MdBlock.Paragraph).inlines
        assertEquals("2 * 3 = 6 and **open", Markdown.plain(p))
    }

    @Test fun mathToUnicode() {
        assertEquals("x² + y²", MathText.toUnicode("x^2 + y^2"))
        assertEquals("α ≤ β", MathText.toUnicode("\\alpha \\leq \\beta"))
        assertEquals("√(x+1)", MathText.toUnicode("\\sqrt{x+1}"))
        assertEquals("a/b", MathText.toUnicode("\\frac{a}{b}"))
        assertEquals("H₂O", MathText.toUnicode("H_2O"))
        assertEquals("∑ᵢ xᵢ", MathText.toUnicode("\\sum_i x_i"))
    }

    @Test fun syntaxHighlightFindsKeywordsStringsComments() {
        val code = "fun main() { val s = \"hi\" // note\n println(42) }"
        val spans = SyntaxHighlighter.highlight(code, "kotlin")
        fun tok(t: CodeToken) = spans.filter { it.token == t }.map { code.substring(it.start, it.end) }
        assertTrue(tok(CodeToken.KEYWORD).containsAll(listOf("fun", "val")))
        assertEquals(listOf("\"hi\""), tok(CodeToken.STRING))
        assertEquals(listOf("// note"), tok(CodeToken.COMMENT))
        assertEquals(listOf("42"), tok(CodeToken.NUMBER))
        val py = SyntaxHighlighter.highlight("# c\ndef f(): return 'x'", "python")
        assertTrue(py.any { it.token == CodeToken.COMMENT })
    }

    @Test fun largeInputParsesQuickly() {
        val big = (1..300).joinToString("\n") { "- item **$it** with `code` and [$it](u)" }
        val t0 = System.nanoTime()
        repeat(10) { Markdown.parse(big) }
        val ms = (System.nanoTime() - t0) / 1_000_000 / 10
        assertTrue("parse took $ms ms", ms < 200)
    }
}
