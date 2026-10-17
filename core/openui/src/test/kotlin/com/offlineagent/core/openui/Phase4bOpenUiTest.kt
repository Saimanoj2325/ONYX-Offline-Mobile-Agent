package com.offlineagent.core.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4b: progressive OpenUI rendering while streaming, and the compact chat cheat sheet. */
class Phase4bOpenUiTest {
    private val program = """
        root = Stack([t, tbl])
        t = TextContent("Top languages", "large-heavy")
        tbl = Table([Col("Language", "string"), Col("Users (M)", "number")], [["Python", 15.7], ["Java", 12.1]])
    """.trimIndent()

    @Test fun partialProgramStreamsAsRawTextUntilItResolves() {
        // Every prefix of the stream must give something to show: raw text before root resolves, components after.
        var sawRaw = false; var sawComponents = false
        for (n in 1..program.length) {
            val v = OpenUiStreaming.view(program.substring(0, n), streaming = true)
            when (v) {
                is ProgramView.Raw -> { sawRaw = true; assertEquals(program.substring(0, n), v.text); assertTrue(!v.final) }
                is ProgramView.Components -> { sawComponents = true; assertTrue(v.resolved.root != null) }
            }
        }
        assertTrue(sawRaw); assertTrue(sawComponents)
        // Once components appear the view never falls back to raw text for longer prefixes of a valid program.
        val firstComp = (1..program.length).first { OpenUiStreaming.view(program.substring(0, it), true) is ProgramView.Components }
        assertTrue((firstComp..program.length).all { OpenUiStreaming.view(program.substring(0, it), true) is ProgramView.Components })
    }

    @Test fun finishedBrokenProgramDegradesToSource() {
        val v = OpenUiStreaming.view("this is ( not openui", streaming = false)
        assertTrue(v is ProgramView.Raw && v.final)
        val ok = OpenUiStreaming.view(program, streaming = false)
        assertTrue(ok is ProgramView.Components)
    }

    @Test fun messageWithOpenFenceKeepsMarkdownVisible() {
        val text = "Here is a table:\n\n```openui-lang\nroot = Stack([t])\nt = TextCon"
        val segs = OpenUiMessage.split(text, streaming = true)
        assertEquals("Here is a table:", (segs[0] as Segment.Markdown).text)
        val prog = segs[1] as Segment.Program
        assertTrue(!prog.closed)
        assertTrue(OpenUiStreaming.view(prog.source, streaming = true) is ProgramView.Raw || OpenUiStreaming.view(prog.source, true) is ProgramView.Components)
    }

    @Test fun cheatSheetOnlyUsesRealComponentsWithTheirArgumentOrder() {
        val lib = OpenUiLibrary.DEFAULT
        val calls = Regex("\\b([A-Z][A-Za-z]+)\\(([^()]*)").findAll(OpenUiPrompt.CHEAT_SHEET).filter { it.groupValues[1] != "Component" }.toList()
        assertTrue(calls.size >= 10)
        for (c in calls) {
            val name = c.groupValues[1]
            val spec = lib[name] ?: error("cheat sheet names unknown component $name")
            // Argument names in the signature form (e.g. "labels, series") must follow the library's positional order.
            val args = c.groupValues[2].split(',').map { it.trim().removeSuffix("?") }.filter { it.matches(Regex("[a-z][A-Za-z]*")) }
            val order = spec.params.map { it.name }
            if (args.isNotEmpty()) assertEquals("argument order of $name", order.filter { it in args }, args.filter { it in order })
        }
        // The example in the cheat sheet parses and resolves with the real library.
        val example = OpenUiMessage.split(OpenUiPrompt.CHEAT_SHEET).filterIsInstance<Segment.Program>().single().source
        assertTrue(OpenUiResolver(lib).resolve(OpenUiParser.parse(example)).root != null)
    }

    @Test fun promptSizeBudget() {
        // ~3.5 chars per token for this kind of text with the Qwen tokenizer (host harness: real counts).
        val cheat = OpenUiPrompt.cheatSheet().length
        val full = OpenUiPrompt.generate(OpenUiLibrary.DEFAULT).length
        assertTrue("cheat sheet is $cheat chars", cheat <= 1300)
        assertTrue("cheat sheet $cheat vs full $full", cheat * 4 < full)
    }
}
