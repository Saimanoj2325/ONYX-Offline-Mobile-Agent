package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.rag.ChunkCandidate
import com.offlineagent.core.runtime.rag.Chunker
import com.offlineagent.core.runtime.rag.HybridRanker
import com.offlineagent.core.runtime.rag.KeywordScorer
import com.offlineagent.core.runtime.rag.RagPrompt
import com.offlineagent.core.runtime.rag.VectorMath
import com.offlineagent.core.runtime.skills.BuiltinSkills
import com.offlineagent.core.runtime.skills.SkillMatcher
import com.offlineagent.core.runtime.skills.SkillParseResult
import com.offlineagent.core.runtime.skills.SkillParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RagAndSkillsTest {
    private val text = (1..40).joinToString("\n\n") { "Paragraph $it talks about topic number $it. It has a second sentence here. And a third one to pad it out." }

    @Test fun chunksCoverTextWithOverlapAndBoundaries() {
        val chunks = Chunker.chunk(text, maxChars = 300, overlapChars = 60)
        assertTrue(chunks.size > 5)
        chunks.forEach { assertTrue(it.text.length <= 300) }
        // every paragraph number appears in some chunk
        (1..40).forEach { n -> assertTrue("missing $n", chunks.any { it.text.contains("Paragraph $n ") }) }
        // consecutive chunks overlap
        chunks.zipWithNext().forEach { (a, b) -> assertTrue(b.startChar < a.endChar) }
        // no chunk starts mid-word
        chunks.drop(1).forEach { assertTrue(it.text.first().isUpperCase() || it.text.first().isLetterOrDigit()) }
    }

    @Test fun chunkerHandlesEdgeCases() {
        assertTrue(Chunker.chunk("   ").isEmpty())
        assertEquals(1, Chunker.chunk("short text").size)
        assertEquals("hyphenated word", Chunker.normalize("hyphen-\nated word"))
        val noSpaces = "x".repeat(2500)
        assertEquals(3, Chunker.chunk(noSpaces, 1000, 0).size)
    }

    @Test fun cosineAndTopK() {
        val a = floatArrayOf(1f, 0f, 0f); val b = floatArrayOf(0.9f, 0.1f, 0f); val c = floatArrayOf(0f, 1f, 0f)
        assertEquals(1f, VectorMath.cosine(a, a), 1e-6f)
        assertEquals(0f, VectorMath.cosine(a, c), 1e-6f)
        val top = VectorMath.topK(a, listOf("c" to c, "b" to b, "a" to a), 2)
        assertEquals(listOf("a", "b"), top.map { it.first })
        assertArrayEquals(b, VectorMath.fromBytes(VectorMath.toBytes(b)), 0f)
    }

    @Test fun keywordScoringPrefersMatchingDocs() {
        val s = KeywordScorer.score("photosynthesis chlorophyll", listOf("plants use chlorophyll for photosynthesis", "the stock market fell", "photosynthesis"))
        assertTrue(s[0] > s[2] && s[2] > s[1])
        assertEquals("\"photosynthesis\" OR \"light\"", KeywordScorer.ftsQuery("what is photosynthesis and light"))
        assertNull(KeywordScorer.ftsQuery("what is the"))
    }

    @Test fun hybridRankerBlendsVectorAndKeywords() {
        val q = floatArrayOf(1f, 0f)
        val cands = listOf(
            ChunkCandidate(1, 1, "Bio", "photosynthesis in leaves", floatArrayOf(0.2f, 1f)),
            ChunkCandidate(2, 1, "Bio", "unrelated text about cars", floatArrayOf(1f, 0f)),
            ChunkCandidate(3, 2, "Notes", "photosynthesis converts light", floatArrayOf(0.9f, 0.2f)),
        )
        val r = HybridRanker.rank("photosynthesis", q, cands, k = 2)
        assertEquals(3L, r[0].candidate.chunkId)
        val kwOnly = HybridRanker.rank("photosynthesis", null, cands, k = 3)
        assertEquals(2, kwOnly.size) // the car chunk scores 0 and is filtered
        val ctx = RagPrompt.context(r)
        assertTrue(ctx.contains("[1] (Notes)"))
        assertEquals(listOf(2, 1), RagPrompt.citedNumbers("as shown [2] and [1], also [9]", 2))
    }

    @Test fun skillParserParsesFrontMatterAndBody() {
        val md = """
            ---
            name: My Skill
            description: "Does things"
            triggers: [do things, things]
            tools:
              - search_docs
              - launch_rockets
            output: [table, hologram]
            max_steps: 99
            ---
            1. Do it well.
        """.trimIndent()
        val r = SkillParser.parse(md, knownTools = setOf("search_docs", "render_ui")) as SkillParseResult.Ok
        assertEquals("my-skill", r.skill.id)
        assertEquals("Does things", r.skill.description)
        assertEquals(listOf("search_docs"), r.skill.tools)
        assertEquals(listOf("table"), r.skill.output)
        assertEquals(SkillParser.MAX_STEPS_LIMIT, r.skill.maxSteps)
        assertEquals(3, r.warnings.size)
        val round = SkillParser.parse(SkillParser.toMarkdown(r.skill)) as SkillParseResult.Ok
        assertEquals(r.skill.copy(source = ""), round.skill.copy(source = ""))
    }

    @Test fun skillParserRejectsBadFiles() {
        assertTrue(SkillParser.parse("no front matter") is SkillParseResult.Error)
        assertTrue(SkillParser.parse("---\nname: x\n") is SkillParseResult.Error)
        assertTrue(SkillParser.parse("---\nname: \n---\nbody") is SkillParseResult.Error)
        assertTrue(SkillParser.parse("---\nname: ok\n---\n") is SkillParseResult.Error)
    }

    @Test fun builtinSkillsLoadAndMatch() {
        val skills = BuiltinSkills.load(setOf("search_docs", "read_document", "summarize", "render_ui", "calculator", "get_datetime", "memory_read", "create_calendar_event"))
        assertEquals(5, skills.size)
        skills.forEach { assertTrue(it.name, it.wantsUi && it.tools.contains("render_ui")) }
        assertEquals("study-plan", SkillMatcher.match("Make a study plan for my exam", skills)!!.skill.id)
        val explicit = SkillMatcher.match("/compare-options iPhone vs Pixel", skills)!!
        assertTrue(explicit.explicit)
        assertEquals("iPhone vs Pixel", explicit.request)
        assertNull(SkillMatcher.match("hello there", skills))
    }
}
