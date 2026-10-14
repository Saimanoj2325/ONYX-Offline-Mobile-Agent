package com.offlineagent.core.runtime.ui

import com.offlineagent.core.runtime.schema.Gbnf

/** How a chat turn should be answered. */
enum class AnswerMode { MARKDOWN, CANVAS_NEW, CANVAS_UPDATE }

/** User-selectable output preference in the composer. */
enum class OutputPreference { AUTO, CHAT, CANVAS }

data class RouteDecision(val mode: AnswerMode, val reason: String)

/**
 * Decides markdown vs Intelligent UI for a request (Auto mode). Deterministic keyword rules instead of an extra model call:
 * on a phone an extra classification pass would cost seconds per message, and the user can always force Chat or Canvas.
 */
object CanvasRouter {
    private val visualWords = listOf(
        "table", "chart", "graph", "plot", "compare", "comparison", " vs ", "versus", "checklist", "check list",
        "plan", "schedule", "timetable", "timeline", "step by step", "step-by-step", "steps to", "roadmap", "quiz",
        "flashcard", "flash card", "diagram", "visual", "dashboard", "pros and cons", "itinerary", "breakdown",
        "canvas", "tracker", "to-do", "todo", "agenda", "budget",
    )
    private val editVerbs = listOf(
        "make", "add", "remove", "delete", "change", "update", "shorten", "shorter", "longer", "replace", "sort",
        "convert", "turn", "rename", "include", "drop", "expand", "simplify", "move", "swap", "insert", "edit", "fix",
    )
    private val canvasRefs = listOf("the table", "the chart", "the canvas", "the list", "the plan", "the quiz", "it ", "this ", "row", "column")

    fun decide(message: String, preference: OutputPreference, hasCanvas: Boolean): RouteDecision {
        val m = " " + message.lowercase().trim() + " "
        val firstWord = m.trim().substringBefore(' ')
        val looksLikeEdit = hasCanvas && (editVerbs.any { firstWord.startsWith(it) } || canvasRefs.any { m.contains(it) } && editVerbs.any { m.contains(" $it") })
        return when (preference) {
            OutputPreference.CHAT -> RouteDecision(AnswerMode.MARKDOWN, "Chat mode selected")
            OutputPreference.CANVAS -> if (hasCanvas && looksLikeEdit) RouteDecision(AnswerMode.CANVAS_UPDATE, "Canvas mode: editing current canvas")
                else RouteDecision(AnswerMode.CANVAS_NEW, "Canvas mode selected")
            OutputPreference.AUTO -> when {
                looksLikeEdit -> RouteDecision(AnswerMode.CANVAS_UPDATE, "Looks like an edit of the open canvas")
                visualWords.any { m.contains(it) } -> RouteDecision(AnswerMode.CANVAS_NEW, "Request asks for structured/visual output")
                else -> RouteDecision(AnswerMode.MARKDOWN, "Conversational request")
            }
        }
    }
}

/** Prompts + grammar for Canvas generation. */
object CanvasPrompts {
    fun system(types: Collection<String> = UiSchema.ALL_TYPES): String =
        """You build compact visual answers for a phone screen. Reply ONLY with one JSON object:
{"v":1,"title":"short title","blocks":[...]}
Each block is one of:
${UiSchema.promptCatalogue(types)}
Rules: pick the components that fit (table for comparisons, chart for numbers, steps for processes, checklist for tasks,
flashcards/quiz for study, callout for key warnings). Keep text short. Use real content, never placeholders.
Chart values must be numbers. Quiz "answer" is the 0-based index of the correct option. At most ${UiSchema.MAX_BLOCKS} blocks.
An optional final "actions" block offers follow-up prompts."""

    fun newCanvasUser(request: String, context: String? = null): String = buildString {
        if (!context.isNullOrBlank()) append(context).append("\n\n")
        append("Request: ").append(request)
    }

    fun updateUser(request: String, current: UiDoc, context: String? = null): String = buildString {
        if (!context.isNullOrBlank()) append(context).append("\n\n")
        append("Current canvas JSON:\n").append(UiSerializer.toJsonString(current)).append("\n\n")
        append("Change request: ").append(request).append("\n")
        append("Return the COMPLETE updated canvas JSON (same format). Keep everything the user did not ask to change.")
    }

    fun grammar(types: Collection<String> = UiSchema.ALL_TYPES): String = Gbnf.of(UiSchema.docSchema(types))
}

/**
 * Best-effort completion of a JSON prefix while it streams, so the Canvas can show finished blocks before the model is
 * done. Cuts back to the last fully closed object/array and appends the missing closers. Returns null if nothing
 * complete exists yet.
 */
object PartialJson {
    fun close(prefix: String): String? {
        val stack = StringBuilder()
        var inString = false
        var escape = false
        var lastCut = -1
        var stackAtCut = ""
        for ((i, c) in prefix.withIndex()) {
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> stack.append(c)
                '}', ']' -> {
                    if (stack.isEmpty()) return null
                    stack.setLength(stack.length - 1)
                    lastCut = i + 1
                    stackAtCut = stack.toString()
                }
            }
        }
        if (lastCut < 0) return null
        if (stackAtCut.isEmpty()) return prefix.substring(0, lastCut)
        val sb = StringBuilder(prefix.substring(0, lastCut).trimEnd().removeSuffix(","))
        for (open in stackAtCut.reversed()) sb.append(if (open == '{') '}' else ']')
        return sb.toString()
    }
}
