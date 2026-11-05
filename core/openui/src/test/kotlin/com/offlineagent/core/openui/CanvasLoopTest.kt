package com.offlineagent.core.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.2.2: the looping canvas from Sai's recording (Qwen3 1.7B, "summary of our previous chats"). */
class CanvasLoopTest {
    private val block = """
        root = TextContent("Previous Chats Summary", "large-heavy")
        prevChats = ["Chat 1: [text]", "Chat 2: [text]", "Chat 3: [text]"]
        root = Stack([root, prevChats])
    """.trimIndent()
    private val looped = (1..4).joinToString("\n") { block }

    @Test fun repeatedStatementsAreDroppedAndSelfReferenceKeepsTheLastValidTree() {
        val p = OpenUiParser.parse(looped).deduped()
        assertEquals(3, p.statements.size)
        assertEquals(listOf("root_prev1", "prevChats", "root"), p.statements.map { it.name })
        assertEquals("root = Stack([root_prev1, prevChats])", p.statements.last().raw)
        // No cycle: the tree resolves (title + list) instead of flickering between the two.
        val r = OpenUiResolver().resolve(OpenUiParser.parse(looped))
        assertNotNull(r.root)
        assertEquals("Stack", r.root!!.type)
        assertTrue(r.errors.none { it.message.contains("circular") })
    }

    @Test fun streamingViewIsStableWhileTheLoopRepeats() {
        // Every prefix after the first full block renders the same thing (no flicker between title and list).
        val first = OpenUiStreaming.view(block + "\n", streaming = true)
        val texts = (block.length + 1..looped.length step 7).map { n ->
            (OpenUiStreaming.view(looped.substring(0, n), streaming = true) as? ProgramView.Components)?.let { OpenUiText.render(it.resolved.root) }
        }
        val firstText = (first as ProgramView.Components).let { OpenUiText.render(it.resolved.root) }
        assertTrue(texts.all { it == firstText })
    }

    @Test fun placeholderLiteralsAreNotShown() {
        val r = OpenUiResolver().resolve(OpenUiParser.parse(looped))
        val text = OpenUiText.render(r.root)
        assertFalse(text, text.contains("[text]"))
        assertFalse(text, text.contains("Chat 1"))
        assertTrue(text.contains("Previous Chats Summary"))
        assertTrue(r.placeholders >= 3)
        // Only a title is left: the app shows a friendly message instead of this canvas.
        assertTrue(r.onlyPlaceholders)
    }

    @Test fun realContentIsKeptAndNotFlagged() {
        val src = """
            root = Stack([t, tbl])
            t = TextContent("Trip budget", "large-heavy")
            tbl = Table([Col("Item", "string"), Col("Cost", "number")], [["Train [2A]", 2400], ["Hotel", 3000], ["Food", "[amount]"]])
        """.trimIndent()
        val r = OpenUiResolver().resolve(OpenUiParser.parse(src))
        val text = OpenUiText.render(r.root)
        assertTrue(text, text.contains("Train [2A]")) // a real bracket is not a placeholder
        assertFalse(text.contains("[amount]"))
        assertFalse(r.onlyPlaceholders)
    }

    @Test fun placeholderCleaner() {
        assertNull(Placeholders.clean("Chat 1: [text]"))
        assertNull(Placeholders.clean("[insert summary here]"))
        assertNull(Placeholders.clean("TBD"))
        assertNull(Placeholders.clean("Lorem ipsum dolor sit amet"))
        assertEquals("Hello", Placeholders.clean("Hello <name>"))
        assertEquals("Python", Placeholders.clean("Python"))
        // Real values that look a bit like filler are kept.
        assertEquals("Todo", Placeholders.clean("Todo"))
        assertEquals("N/A", Placeholders.clean("N/A"))
        assertEquals("[x] Done", Placeholders.clean("[x] Done"))
        assertEquals("Text", Placeholders.clean("Text"))
    }

    @Test fun canvasSheetOnlyUsesRealComponentsWithTheirArgumentOrderAndIsSmall() {
        val lib = OpenUiLibrary.DEFAULT
        val calls = Regex("\\b([A-Z][A-Za-z]+)\\(([^()]*)").findAll(OpenUiPrompt.CANVAS_SHEET).toList()
        assertTrue(calls.size > 10)
        for (c in calls) {
            val spec = lib[c.groupValues[1]]
            assertNotNull("unknown component ${c.groupValues[1]}", spec)
            val args = c.groupValues[2].split(',').map { it.trim().removeSuffix("?") }.filter { it.isNotEmpty() && it.first().isLowerCase() && it.all { ch -> ch.isLetter() } }
            args.forEachIndexed { i, a -> if (i < spec!!.params.size && spec.params.any { it.name == a }) assertEquals("${spec.name} arg $i", spec.params[i].name, a) }
        }
        // The example in the sheet is a valid canvas.
        val example = OpenUiPrompt.CANVAS_SHEET.lines().filter { it.matches(Regex("^(root|title|tbl) = .*")) }.joinToString("\n")
        assertNotNull(OpenUiResolver().resolve(OpenUiParser.parse(example)).root)
        // About a fifth of the full generated program prompt.
        val full = OpenUiPrompt.generate(lib, OpenUiPrompt.Options(mode = OpenUiPrompt.Mode.PROGRAM)).length
        assertTrue("sheet ${OpenUiPrompt.CANVAS_SHEET.length} vs full $full", OpenUiPrompt.CANVAS_SHEET.length * 3 < full)
    }
}
