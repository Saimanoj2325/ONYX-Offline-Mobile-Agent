package com.offlineagent.core.runtime

import com.offlineagent.core.inference.Role
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.context.ContextConfig
import com.offlineagent.core.runtime.context.ContextManager
import com.offlineagent.core.runtime.context.ConversationSummarizer
import com.offlineagent.core.runtime.context.ConversationSummary
import com.offlineagent.core.runtime.context.LongTermMemory
import com.offlineagent.core.runtime.context.MemoryPage
import com.offlineagent.core.runtime.context.TokenCounter
import com.offlineagent.core.runtime.context.Turn
import com.offlineagent.core.runtime.pdf.CreatePdfTool
import com.offlineagent.core.runtime.pdf.DrawOp
import com.offlineagent.core.runtime.pdf.PdfBlock
import com.offlineagent.core.runtime.pdf.PdfContent
import com.offlineagent.core.runtime.pdf.PdfDocModel
import com.offlineagent.core.runtime.pdf.PdfFont
import com.offlineagent.core.runtime.pdf.PdfLayout
import com.offlineagent.core.runtime.pdf.PdfOutput
import com.offlineagent.core.runtime.pdf.PdfPort
import com.offlineagent.core.runtime.pdf.PdfWriter
import com.offlineagent.core.runtime.pdf.ReportWriter
import com.offlineagent.core.runtime.pdf.Run
import com.offlineagent.core.runtime.pdf.Type1Metrics
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.ui.UiBlock
import com.offlineagent.core.runtime.ui.UiDoc
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.Inflater

class ContextManagerTest {
    private val words = TokenCounter { it.split(Regex("\\s+")).count { w -> w.isNotBlank() } }

    private fun convo(n: Int, wordsPerTurn: Int = 50) = (1..n).map { i ->
        Turn(i.toLong(), if (i % 2 == 1) Role.USER else Role.ASSISTANT, (if (i % 2 == 1) "question $i " else "answer $i ") + "w ".repeat(wordsPerTurn))
    }

    @Test fun shortConversationKeepsEverythingAndSystemFirst() = runTest {
        val cm = ContextManager(words, null, null)
        val r = cm.build("You are helpful.", convo(4), null, "next?", 4096, 512)
        assertEquals(Role.SYSTEM, r.messages.first().role)
        assertEquals("You are helpful.", r.messages.first().content)
        assertEquals(6, r.messages.size) // system + 4 + user
        assertEquals("next?", r.messages.last().content)
        assertEquals(0, r.droppedTurns)
        assertFalse(r.summaryChanged)
    }

    @Test fun overflowWithoutSummarizerDropsOldestAndNeverStartsWithAssistant() = runTest {
        val cm = ContextManager(words, null, null, ContextConfig(safetyMargin = 0, perMessageOverhead = 0))
        val r = cm.build("sys", convo(40), null, "q", contextLength = 1000, maxOutputTokens = 200)
        assertTrue(r.droppedTurns > 0)
        assertTrue(r.promptTokens <= r.budget)
        assertEquals(Role.USER, r.messages[1].role)
        assertTrue(r.messages.dropLast(1).last().content.startsWith("answer 40"))
    }

    @Test fun summarisesOldTurnsWhenPastHighWater() = runTest {
        val calls = mutableListOf<List<Turn>>()
        val summarizer = ConversationSummarizer { prev, turns, _ -> calls += turns; (prev ?: "") + " covered ${turns.first().id}-${turns.last().id}" }
        val cm = ContextManager(words, summarizer, null, ContextConfig(safetyMargin = 0, perMessageOverhead = 0))
        val r = cm.build("sys", convo(30), null, "q", contextLength = 2000, maxOutputTokens = 300)
        assertTrue(r.summaryChanged)
        val s = r.summary!!
        assertTrue(calls.isNotEmpty())
        // summary covers a prefix and pairs stay intact (summary ends on an assistant turn)
        assertEquals(0L, s.upToId % 2)
        assertTrue(r.messages[1].content.startsWith("Summary of our earlier conversation"))
        assertEquals(Role.ASSISTANT, r.messages[2].role)
        // no live turn at or below the summary point is in the prompt
        assertTrue(r.messages.none { it.content.startsWith("question 1 ") })
        assertTrue(r.promptTokens <= r.budget)
    }

    @Test fun existingSummaryHidesCoveredTurns() = runTest {
        val cm = ContextManager(words, null, null)
        val r = cm.build("sys", convo(10), ConversationSummary("old stuff", 6), "q", 8192, 512)
        assertFalse(r.summaryChanged)
        assertTrue(r.messages.none { it.content.startsWith("question 5 ") })
        assertTrue(r.messages.any { it.content.startsWith("question 7 ") })
    }

    @Test fun longTermMemoryPagesInRelevantNotesButNotVisibleTurns() = runTest {
        val memory = LongTermMemory { q, exclude, k ->
            listOf(
                MemoryPage("chat: trip", "User's sister Anu lives in Pune", 0.9f, setOf(999L)),
                MemoryPage("this chat", "already visible", 0.8f, setOf(1L, 2L)),
            ).filter { p -> p.turnIds.none { it in exclude } }.take(k)
        }
        val cm = ContextManager(words, null, memory)
        val r = cm.build("sys", convo(4), null, "where does my sister live?", 8192, 512)
        assertEquals(1, r.pages.size)
        assertTrue(r.messages.last().content.contains("Anu lives in Pune"))
        assertTrue(r.messages.last().content.endsWith("where does my sister live?"))
    }

    @Test fun systemPromptIsStableAcrossTurnsForPrefixCaching() = runTest {
        val cm = ContextManager(words, null, null)
        val a = cm.build("SYS", convo(2), null, "one", 4096, 256)
        val b = cm.build("SYS", convo(4), null, "two", 4096, 256)
        assertEquals(a.messages[0], b.messages[0])
        assertEquals(a.messages[1], b.messages[1])
    }

    @Test fun summaryPromptMentionsTurns() {
        val (sys, user) = ContextManager.summaryPrompt("prev", convo(2), 100)
        assertTrue(sys.contains("100 words"))
        assertTrue(user.contains("Existing summary:\nprev"))
        assertTrue(user.contains("User: question 1"))
    }
}

class PdfLayoutTest {
    private val md = """
        # Report title
        Intro paragraph with **bold** and *italic* and `code` and a [link](https://example.com).

        ## Section A
        - first bullet
        - second bullet
          - nested bullet
        1. one
        2. two

        | Name | Score | Notes |
        |---|---|---|
        | Asha | 91 | very long note that should wrap inside its cell because it is longer than the column |
        | Ravi | 78 | ok |

        ```kotlin
        fun main() = println("hello")
        ```

        > a quote

        ---
        ## Section B
    """.trimIndent() + "\n" + (1..80).joinToString("\n\n") { "Paragraph $it " + "lorem ipsum dolor sit amet ".repeat(12) }

    @Test fun markdownBecomesTypedBlocks() {
        val blocks = PdfContent.fromMarkdown(md)
        assertTrue(blocks.first() is PdfBlock.Heading)
        assertTrue(blocks.any { it is PdfBlock.Table && it.rows.size == 2 && it.header == listOf("Name", "Score", "Notes") })
        assertTrue(blocks.any { it is PdfBlock.Code && it.code.contains("println") })
        assertTrue(blocks.any { it is PdfBlock.Bullet && it.depth == 1 })
        assertTrue(blocks.any { it is PdfBlock.Bullet && it.marker == "2." })
        val p = blocks.filterIsInstance<PdfBlock.Paragraph>().first()
        assertTrue(p.runs.any { it.font == PdfFont.BOLD && it.text == "bold" })
        assertTrue(p.runs.any { it.font == PdfFont.MONO })
    }

    @Test fun multiPageLayoutWithTitleTocAndPageNumbers() {
        val doc = PdfDocModel("My Report", PdfContent.fromMarkdown(md), subtitle = "Sub", date = "16 Oct 2026")
        val r = PdfLayout(Type1Metrics).layout(doc)
        assertTrue("pages=${r.pages.size}", r.pages.size >= 5)
        val texts = r.pages.map { p -> p.ops.filterIsInstance<DrawOp.Text>().map { it.text } }
        assertTrue(texts[0].contains("My Report"))
        assertTrue(texts[1].contains("Contents"))
        assertTrue(texts.last().any { it == "Page ${r.pages.size} of ${r.pages.size}" })
        assertTrue(texts[0].none { it.startsWith("Page ") }) // no footer on title page
        // TOC lists headings with the page they actually landed on
        val secB = r.toc.first { it.text == "Section B" }
        assertTrue(texts[secB.page].contains("Section B"))
        // everything stays inside the page box
        r.pages.forEach { p -> p.ops.filterIsInstance<DrawOp.Text>().forEach { t ->
            assertTrue(t.x >= 0 && t.x + Type1Metrics.width(t.text, t.font, t.size) <= r.spec.width - r.spec.margin + 1f)
            assertTrue(t.y >= 0 && t.y <= r.spec.height)
        } }
    }

    @Test fun wrapNeverExceedsWidthAndSplitsLongWords() {
        val layout = PdfLayout(Type1Metrics)
        val lines = layout.wrap(listOf(Run("short words then " + "x".repeat(400) + " end")), 10f, 200f)
        lines.forEach { l -> assertTrue(l.sumOf { Type1Metrics.width(it.text, it.font, 10f).toDouble() } <= 200.5) }
        assertTrue(lines.size > 3)
    }

    @Test fun tableHeaderRepeatsOnNextPage() {
        val rows = (1..120).map { listOf("row $it", "value $it") }
        val r = PdfLayout(Type1Metrics).layout(PdfDocModel("T", listOf(PdfBlock.Table(listOf("Key", "Val"), rows)), titlePage = false))
        assertTrue(r.pages.size >= 2)
        r.pages.forEach { p -> assertTrue(p.ops.filterIsInstance<DrawOp.Text>().any { it.text == "Key" }) }
    }

    @Test fun pdfWriterProducesAValidPdf() {
        val (bytes, layout) = PdfWriter.render(PdfDocModel("Writer test (with parens)", PdfContent.fromMarkdown(md)))
        val s = String(bytes, Charsets.ISO_8859_1)
        assertTrue(s.startsWith("%PDF-1.4"))
        assertTrue(s.trimEnd().endsWith("%%EOF"))
        assertTrue(s.contains("/Count ${layout.pages.size}"))
        // xref offsets point at "n 0 obj"
        val xref = s.substringAfter("startxref\n").substringBefore("\n").trim().toInt()
        assertTrue(s.startsWith("xref", xref))
        val firstOffset = Regex("\\n(\\d{10}) 00000 n").find(s, xref)!!.groupValues[1].toInt()
        assertTrue(s.startsWith("1 0 obj", firstOffset))
        // content stream inflates and contains the title text, escaped
        val start = s.indexOf("stream\n", s.indexOf("/FlateDecode")) + 7
        val len = Regex("/Length (\\d+)").find(s, s.indexOf("/FlateDecode"))!!.groupValues[1].toInt()
        val inf = Inflater().apply { setInput(bytes, start, len) }
        val out = ByteArray(1 shl 20); val n = inf.inflate(out)
        assertTrue(String(out, 0, n, Charsets.ISO_8859_1).contains("Writer test \\(with parens\\)"))
    }

    @Test fun canvasDocumentsPrint() {
        val doc = UiDoc("Plan", listOf(
            UiBlock.Heading("Week", 1), UiBlock.Table("Days", listOf("Day", "Task"), listOf(listOf("Mon", "Read"))),
            UiBlock.Checklist("Todo", listOf(com.offlineagent.core.runtime.ui.CheckItem("buy milk", true))),
            UiBlock.Chart("bar", "Scores", listOf("A", "B"), listOf(com.offlineagent.core.runtime.ui.Series("s", listOf(1.0, 2.5)))),
            UiBlock.Actions(listOf(com.offlineagent.core.runtime.ui.Action("x", "y"))),
        ))
        val blocks = PdfContent.fromCanvas(doc)
        assertTrue(blocks.any { it is PdfBlock.Bullet && it.marker == "[x]" })
        assertTrue(blocks.any { it is PdfBlock.Table && it.rows.any { r -> r.contains("2.50") } })
        assertEquals(PdfWriter.render(PdfDocModel("Plan", blocks)).second.pages.isNotEmpty(), true)
    }
}

class ReportWriterTest {
    private class ScriptLlm : AgentLlm {
        val users = mutableListOf<String>()
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
            users += user
            return if (grammar != null) """{"title":"Solar power in India","subtitle":"A short study","sections":[
                {"heading":"Background","points":["history"]},{"heading":"Costs","points":["prices","subsidy"]},{"heading":"Outlook","points":["2030"]}]}"""
            else "## repeated heading\nSection text about ${user.substringAfterLast("section ").take(20)} " + "words ".repeat(80)
        }
    }

    @Test fun writesSectionBySectionWithDigestsNotFullText() = runTest {
        val llm = ScriptLlm()
        val w = ReportWriter(llm)
        val outline = w.outline("solar power", 3)
        assertEquals(3, outline.sections.size)
        val progress = mutableListOf<Int>()
        val md = w.write("solar power", outline, onProgress = { progress += it.section })
        assertEquals(listOf(1, 2, 3), progress)
        assertTrue(md.startsWith("# Solar power in India"))
        assertEquals(3, Regex("^## ", RegexOption.MULTILINE).findAll(md).count())
        assertFalse(md.contains("repeated heading"))
        // section 3's prompt carries digests of 1 and 2, but stays small (no full previous text)
        val last = llm.users.last()
        assertTrue(last.contains("Already written"))
        assertTrue(last.length < 2500)
    }

    @Test fun badOutlineFallsBack() = runTest {
        val llm = object : AgentLlm { override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int) = "nonsense" }
        assertEquals(2, ReportWriter(llm).outline("x", 3).sections.size)
    }

    @Test fun createPdfToolValidatesAndNames() = runTest {
        var got: PdfDocModel? = null
        val port = object : PdfPort {
            override suspend fun createPdf(doc: PdfDocModel, fileName: String): PdfOutput { got = doc; return PdfOutput("content://x/$fileName", fileName, 3, 1000) }
            override suspend fun canvasMarkdown(chatId: Long?) = null
        }
        val tool = CreatePdfTool(port)
        val r = tool.execute(buildJsonObject {
            put("title", "Trip plan"); put("markdown", "# Trip plan\n\nDay 1"); put("source", "markdown"); put("file_name", "trip/plan?"); put("subtitle", ""); put("title_page", true)
        }, ToolContext("r", 1))
        assertTrue(r is ToolResult.Ok)
        assertEquals(JsonPrimitive("tripplan.pdf"), (r as ToolResult.Ok).json["file"])
        assertNull(got!!.blocks.firstOrNull { it is PdfBlock.Heading && it.text == "Trip plan" }) // de-duplicated title
        val noCanvas = tool.execute(buildJsonObject { put("title", "x"); put("markdown", ""); put("source", "canvas"); put("file_name", ""); put("subtitle", ""); put("title_page", false) }, ToolContext("r", 1))
        assertTrue(noCanvas is ToolResult.Error)
        assertNotNull(CreatePdfTool.safeFileName(""))
    }
}
