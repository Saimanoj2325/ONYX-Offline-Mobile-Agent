package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.validate
import com.offlineagent.core.runtime.ui.AnswerMode
import com.offlineagent.core.runtime.ui.CanvasPrompts
import com.offlineagent.core.runtime.ui.CanvasRouter
import com.offlineagent.core.runtime.ui.OutputPreference
import com.offlineagent.core.runtime.ui.PartialJson
import com.offlineagent.core.runtime.ui.UiBlock
import com.offlineagent.core.runtime.ui.UiMarkdown
import com.offlineagent.core.runtime.ui.UiParseResult
import com.offlineagent.core.runtime.ui.UiParser
import com.offlineagent.core.runtime.ui.UiSchema
import com.offlineagent.core.runtime.ui.UiSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiSchemaTest {
    private val full = """
        {"v":1,"title":"Phones","blocks":[
          {"type":"heading","text":"Compare","level":2},
          {"type":"table","title":"Specs","columns":["Phone","RAM"],"rows":[["A","8 GB"],["B","12 GB"]]},
          {"type":"chart","kind":"bar","title":"Score","labels":["A","B"],"series":[{"name":"score","values":[7,9]}]},
          {"type":"checklist","title":"Todo","items":[{"text":"Buy","done":false}]},
          {"type":"quiz","title":"Q","questions":[{"question":"2+2?","options":["3","4"],"answer":1,"explanation":"basic"}]},
          {"type":"actions","buttons":[{"label":"More","prompt":"Add a row for phone C"}]}
        ]}
    """.trimIndent()

    @Test fun parsesValidDocument() {
        val r = UiParser.parse(full) as UiParseResult.Success
        assertEquals("Phones", r.doc.title)
        assertEquals(6, r.doc.blocks.size)
        val t = r.doc.blocks[1] as UiBlock.Table
        assertEquals(listOf("Phone", "RAM"), t.columns)
        assertEquals(2, t.rows.size)
        val c = r.doc.blocks[2] as UiBlock.Chart
        assertEquals(listOf(7.0, 9.0), c.series[0].values)
        assertTrue(r.warnings.isEmpty())
    }

    @Test fun roundTripSerialisationIsStable() {
        val doc = (UiParser.parse(full) as UiParseResult.Success).doc
        val again = (UiParser.parse(UiSerializer.toJsonString(doc)) as UiParseResult.Success).doc
        assertEquals(doc, again)
    }

    @Test fun serialisedDocValidatesAgainstSchema() {
        val doc = (UiParser.parse(full) as UiParseResult.Success).doc
        val errs = UiSchema.docSchema().validate(UiSerializer.toJson(doc))
        assertTrue(errs.toString(), errs.isEmpty())
    }

    @Test fun stripsCodeFencesAndChatter() {
        val r = UiParser.parse("Sure! ```json\n{\"v\":1,\"title\":\"x\",\"blocks\":[{\"type\":\"markdown\",\"text\":\"hi\"}]}\n```")
        assertTrue(r is UiParseResult.Success)
    }

    @Test fun repairsRaggedTableAndChart() {
        val r = UiParser.parse("""{"v":1,"title":"t","blocks":[
            {"type":"table","title":"","columns":["a","b"],"rows":[["1"],["1","2","3"]]},
            {"type":"chart","kind":"line","title":"","labels":["x","y","z"],"series":[{"name":"s","values":[1]}]}]}""") as UiParseResult.Success
        val t = r.doc.blocks[0] as UiBlock.Table
        assertEquals(listOf(listOf("1", ""), listOf("1", "2")), t.rows)
        val c = r.doc.blocks[1] as UiBlock.Chart
        assertEquals(3, c.series[0].values.size)
        assertTrue(r.warnings.any { it.contains("series length") })
    }

    @Test fun dropsUnknownAndInvalidBlocksButKeepsValidOnes() {
        val r = UiParser.parse("""{"v":1,"title":"t","blocks":[{"type":"video","url":"http://x"},{"type":"chart","kind":"radar","title":"","labels":["a"],"series":[{"name":"s","values":[1]}]},{"type":"markdown","text":"ok"}]}""") as UiParseResult.Success
        assertEquals(1, r.doc.blocks.size)
        assertTrue(r.warnings.size >= 2)
    }

    @Test fun defaultsMissingSimpleFields() {
        val r = UiParser.parse("""{"v":1,"title":"t","blocks":[{"type":"callout","text":"careful"}]}""") as UiParseResult.Success
        val c = r.doc.blocks[0] as UiBlock.Callout
        assertEquals("info", c.tone)
    }

    @Test fun clampsQuizAnswer() {
        val r = UiParser.parse("""{"v":1,"title":"t","blocks":[{"type":"quiz","title":"","questions":[{"question":"q","options":["a","b"],"answer":7,"explanation":""}]}]}""") as UiParseResult.Success
        assertEquals(0, (r.doc.blocks[0] as UiBlock.Quiz).questions[0].answer)
    }

    @Test fun malformedFallsBackToMarkdown() {
        val plain = UiParser.parse("Just a normal answer with **bold**.") as UiParseResult.Failure
        assertEquals("Just a normal answer with **bold**.", plain.fallbackMarkdown)
        val broken = UiParser.parse("{\"v\":1,\"title\":\"x\",\"blocks\":[{\"type\":") as UiParseResult.Failure
        assertTrue(broken.fallbackMarkdown.contains("could not be displayed"))
        val empty = UiParser.parse("{\"v\":1,\"title\":\"x\",\"blocks\":[]}")
        assertTrue(empty is UiParseResult.Failure)
        val future = UiParser.parse("{\"v\":9,\"title\":\"x\",\"blocks\":[{\"type\":\"markdown\",\"text\":\"a\"}]}")
        assertTrue(future is UiParseResult.Failure)
    }

    @Test fun markdownExportContainsTableAndChecklist() {
        val md = UiMarkdown.render((UiParser.parse(full) as UiParseResult.Success).doc)
        assertTrue(md.contains("| Phone | RAM |"))
        assertTrue(md.contains("- [ ] Buy"))
        assertTrue(md.startsWith("# Phones"))
    }

    @Test fun grammarIsWellFormed() {
        val g = CanvasPrompts.grammar()
        assertTrue(g.startsWith("root ::= "))
        // every referenced rule is defined
        val defined = Regex("^([a-z0-9-]+) ::=", RegexOption.MULTILINE).findAll(g).map { it.groupValues[1] }.toSet()
        val bodies = g.lines().filter { it.contains("::=") }.map { it.substringAfter("::=") }
        val referenced = bodies.flatMap { b ->
            Regex("\"(?:\\\\.|[^\"\\\\])*\"|\\[(?:\\\\.|[^\\]\\\\])*]|([a-z][a-z0-9-]*)").findAll(b).mapNotNull { it.groups[1]?.value }.toList()
        }.toSet()
        assertTrue("undefined: ${referenced - defined}", (referenced - defined).isEmpty())
        UiSchema.ALL_TYPES.forEach { assertTrue(it, g.contains("\\\"$it\\\"")) }
    }

    @Test fun gbnfLiteralsEscapeQuotes() {
        assertEquals("\"\\\"a\\\"\"", Gbnf.jsonStringLiteral("a"))
        val g = Gbnf.of(Schema.Obj(listOf("k" to Schema.Str(enum = listOf("x", "y")), "n" to Schema.Arr(Schema.Num(), maxItems = 1))))
        assertTrue(g, g.contains(Gbnf.jsonStringLiteral("x") + " | " + Gbnf.jsonStringLiteral("y")))
        assertTrue(g, g.contains("( unumber )?") || g.contains("( number )?"))
    }

    @Test fun partialJsonClosesCompletedBlocks() {
        val prefix = """{"v":1,"title":"t","blocks":[{"type":"markdown","text":"one"},{"type":"table","title":"x","colu"""
        val closed = PartialJson.close(prefix)
        assertNotNull(closed)
        val r = UiParser.parse(closed!!) as UiParseResult.Success
        assertEquals(1, r.doc.blocks.size)
        assertNull(PartialJson.close("{\"v\":1,\"title\":\"no blocks yet"))
        Json.parseToJsonElement(closed) as JsonObject
    }

    @Test fun routerPicksModes() {
        assertEquals(AnswerMode.MARKDOWN, CanvasRouter.decide("What is the capital of India?", OutputPreference.AUTO, false).mode)
        assertEquals(AnswerMode.CANVAS_NEW, CanvasRouter.decide("Compare iPhone 15 vs Pixel 8 in a table", OutputPreference.AUTO, false).mode)
        assertEquals(AnswerMode.CANVAS_UPDATE, CanvasRouter.decide("make the table shorter", OutputPreference.AUTO, true).mode)
        assertEquals(AnswerMode.CANVAS_UPDATE, CanvasRouter.decide("Add a chart", OutputPreference.AUTO, true).mode)
        assertEquals(AnswerMode.MARKDOWN, CanvasRouter.decide("make a table", OutputPreference.CHAT, true).mode)
        assertEquals(AnswerMode.CANVAS_NEW, CanvasRouter.decide("hello", OutputPreference.CANVAS, false).mode)
        assertFalse(CanvasRouter.decide("thanks!", OutputPreference.AUTO, true).mode == AnswerMode.CANVAS_UPDATE)
    }
}
