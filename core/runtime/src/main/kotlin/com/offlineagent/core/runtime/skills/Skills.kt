package com.offlineagent.core.runtime.skills

import com.offlineagent.core.runtime.ui.UiSchema

/**
 * A Skill is a local markdown file with a small YAML-like front matter:
 *
 * ```
 * ---
 * id: study-plan
 * name: Study plan from my PDFs
 * description: Builds a day-by-day study plan from the imported documents
 * triggers: [study plan, exam prep, revision plan]
 * tools: [search_docs, read_document, render_ui]
 * output: [heading, table, checklist, callout]
 * max_steps: 5
 * ---
 * 1. Search the documents for the main topics ...
 * ```
 *
 * The body is plain instructions for the model. `tools` is an allow-list enforced by the policy engine; `output`
 * limits the UI components (and therefore the grammar) of the final canvas.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>,
    val tools: List<String>,
    val output: List<String>,
    val maxSteps: Int,
    val instructions: String,
    val builtIn: Boolean = false,
    val source: String = "",
) {
    val wantsUi: Boolean get() = output.isNotEmpty()
}

sealed interface SkillParseResult {
    data class Ok(val skill: Skill, val warnings: List<String>) : SkillParseResult
    data class Error(val errors: List<String>) : SkillParseResult
}

object SkillParser {
    const val MAX_STEPS_LIMIT = 8

    fun parse(markdown: String, knownTools: Set<String>? = null, builtIn: Boolean = false): SkillParseResult {
        val text = markdown.replace("\r\n", "\n").trimStart('\uFEFF').trim()
        if (!text.startsWith("---")) return SkillParseResult.Error(listOf("missing front matter (start the file with ---)"))
        val end = text.indexOf("\n---", 3)
        if (end < 0) return SkillParseResult.Error(listOf("front matter is not closed with ---"))
        val front = parseFrontMatter(text.substring(3, end))
        val body = text.substring(end + 4).trim()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val name = front.scalar("name").orEmpty()
        if (name.isBlank()) errors += "name is required"
        val id = front.scalar("id")?.takeIf { it.isNotBlank() } ?: slug(name)
        if (!Regex("[a-z0-9][a-z0-9-]{0,40}").matches(id)) errors += "id must be lowercase letters, digits and dashes"
        if (body.isBlank()) errors += "instructions (the text after the front matter) are required"

        var tools = front.list("tools").map { it.trim() }.filter { it.isNotEmpty() }
        if (knownTools != null) {
            val unknown = tools.filter { it !in knownTools }
            if (unknown.isNotEmpty()) { warnings += "unknown tools ignored: ${unknown.joinToString()}"; tools = tools - unknown.toSet() }
        }
        var output = front.list("output").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val badOut = output.filter { it !in UiSchema.blockSchemas }
        if (badOut.isNotEmpty()) { warnings += "unknown UI components ignored: ${badOut.joinToString()}"; output = output - badOut.toSet() }

        val maxSteps = (front.scalar("max_steps")?.toIntOrNull() ?: 5).let {
            if (it !in 1..MAX_STEPS_LIMIT) warnings += "max_steps clamped to 1..$MAX_STEPS_LIMIT"
            it.coerceIn(1, MAX_STEPS_LIMIT)
        }
        if (errors.isNotEmpty()) return SkillParseResult.Error(errors)
        return SkillParseResult.Ok(
            Skill(
                id = id, name = name.trim(), description = front.scalar("description").orEmpty().trim(),
                triggers = front.list("triggers").map { it.trim().lowercase() }.filter { it.isNotEmpty() },
                tools = tools, output = output, maxSteps = maxSteps, instructions = body, builtIn = builtIn, source = text,
            ),
            warnings,
        )
    }

    fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)

    private class Front(val map: Map<String, Any>) {
        fun scalar(k: String): String? = map[k] as? String
        @Suppress("UNCHECKED_CAST")
        fun list(k: String): List<String> = when (val v = map[k]) {
            is List<*> -> v as List<String>
            is String -> if (v.isBlank()) emptyList() else v.split(',')
            else -> emptyList()
        }
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"").removeSurrounding("'")

    private fun parseFrontMatter(src: String): Front {
        val map = LinkedHashMap<String, Any>()
        var currentListKey: String? = null
        for (raw in src.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val t = line.trim()
            if (t.startsWith("- ") && currentListKey != null) {
                @Suppress("UNCHECKED_CAST")
                (map.getOrPut(currentListKey) { mutableListOf<String>() } as? MutableList<String>)?.add(unquote(t.removePrefix("- ")))
                continue
            }
            val colon = t.indexOf(':')
            if (colon <= 0) continue
            val key = t.substring(0, colon).trim().lowercase()
            val value = t.substring(colon + 1).trim()
            when {
                value.isEmpty() -> { map[key] = mutableListOf<String>(); currentListKey = key }
                value.startsWith("[") && value.endsWith("]") -> {
                    map[key] = value.removeSurrounding("[", "]").split(',').map { unquote(it) }.filter { it.isNotEmpty() }
                    currentListKey = null
                }
                else -> { map[key] = unquote(value); currentListKey = null }
            }
        }
        return Front(map)
    }

    /** Serialises a skill back to its markdown file format (for export / editing). */
    fun toMarkdown(s: Skill): String = buildString {
        append("---\n")
        append("id: ").append(s.id).append('\n')
        append("name: ").append(s.name).append('\n')
        append("description: ").append(s.description).append('\n')
        append("triggers: [").append(s.triggers.joinToString(", ")).append("]\n")
        append("tools: [").append(s.tools.joinToString(", ")).append("]\n")
        append("output: [").append(s.output.joinToString(", ")).append("]\n")
        append("max_steps: ").append(s.maxSteps).append('\n')
        append("---\n").append(s.instructions.trim()).append('\n')
    }
}

/** Picks a skill for a message: explicit "/skill-id …" wins, otherwise the longest matching trigger phrase. */
object SkillMatcher {
    data class Match(val skill: Skill, val request: String, val explicit: Boolean)

    fun match(message: String, skills: List<Skill>): Match? {
        val m = message.trim()
        if (m.startsWith("/")) {
            val id = m.drop(1).substringBefore(' ').lowercase()
            skills.firstOrNull { it.id == id }?.let { return Match(it, m.substringAfter(' ', "").trim().ifEmpty { it.description }, true) }
        }
        val low = m.lowercase()
        return skills.flatMap { s -> s.triggers.filter { t -> t.length >= 3 && low.contains(t) }.map { s to it.length } }
            .maxByOrNull { it.second }?.let { Match(it.first, m, false) }
    }
}

/** The skills shipped with the app (markdown files in resources/skills). */
object BuiltinSkills {
    val FILES = listOf("study-plan.md", "flashcards.md", "compare-options.md", "daily-planner.md", "explain-concept.md")

    fun load(knownTools: Set<String>? = null): List<Skill> = FILES.mapNotNull { f ->
        val text = BuiltinSkills::class.java.getResourceAsStream("/skills/$f")?.bufferedReader()?.use { it.readText() } ?: return@mapNotNull null
        (SkillParser.parse(text, knownTools, builtIn = true) as? SkillParseResult.Ok)?.skill
    }
}
