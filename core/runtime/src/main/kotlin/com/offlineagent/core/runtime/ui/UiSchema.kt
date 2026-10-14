package com.offlineagent.core.runtime.ui

import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.schema.Schema.Arr
import com.offlineagent.core.runtime.schema.Schema.Bool
import com.offlineagent.core.runtime.schema.Schema.Const
import com.offlineagent.core.runtime.schema.Schema.Num
import com.offlineagent.core.runtime.schema.Schema.Obj
import com.offlineagent.core.runtime.schema.Schema.Str

/**
 * Intelligent-UI component schema, version [VERSION].
 *
 * The model emits `{"v":1,"title":"…","blocks":[{"type":"table",…},…]}`. In Canvas mode sampling is constrained by
 * the GBNF grammar generated from [docSchema], then [UiParser] validates the result in Kotlin and the app renders native
 * Compose components. Adding a component = add a [UiBlock] subclass, its schema here, its parse/serialise case and a
 * renderer. Bump [VERSION] for breaking changes; older stored documents keep their version number.
 */
object UiSchema {
    const val VERSION = 1

    private fun s(desc: String? = null, max: Int = 600) = Str(desc, maxLength = max)
    private fun obj(type: String, vararg props: Pair<String, Schema>) = Obj(listOf("type" to Const(type)) + props.toList())

    val blockSchemas: Map<String, Obj> = linkedMapOf(
        "markdown" to obj("markdown", "text" to s("Markdown paragraph(s)", 2000)),
        "heading" to obj("heading", "text" to s(max = 120), "level" to Num(min = 1.0, max = 3.0, integer = true)),
        "callout" to obj(
            "callout",
            "tone" to Str(enum = listOf("info", "tip", "warning", "success")),
            "title" to s(max = 120), "text" to s(max = 800),
        ),
        "keyvalue" to obj(
            "keyvalue", "title" to s(max = 120),
            "items" to Arr(Obj(listOf("label" to s(max = 80), "value" to s(max = 200))), maxItems = 12, minItems = 1),
        ),
        "table" to obj(
            "table", "title" to s(max = 120),
            "columns" to Arr(s(max = 60), minItems = 1, maxItems = 6),
            "rows" to Arr(Arr(s(max = 160), minItems = 1, maxItems = 6), minItems = 1, maxItems = 12),
        ),
        "list" to obj("list", "ordered" to Bool(), "items" to Arr(s(max = 300), minItems = 1, maxItems = 12)),
        "checklist" to obj(
            "checklist", "title" to s(max = 120),
            "items" to Arr(Obj(listOf("text" to s(max = 200), "done" to Bool())), minItems = 1, maxItems = 15),
        ),
        "steps" to obj(
            "steps", "title" to s(max = 120),
            "items" to Arr(Obj(listOf("title" to s(max = 100), "detail" to s(max = 400))), minItems = 1, maxItems = 10),
        ),
        "chart" to obj(
            "chart",
            "kind" to Str(enum = listOf("bar", "line", "pie")),
            "title" to s(max = 120),
            "labels" to Arr(s(max = 40), minItems = 1, maxItems = 12),
            "series" to Arr(
                Obj(listOf("name" to s(max = 60), "values" to Arr(Num(), minItems = 1, maxItems = 12))),
                minItems = 1, maxItems = 3,
            ),
        ),
        "code" to obj("code", "language" to s(max = 20), "code" to s(max = 3000)),
        "flashcards" to obj(
            "flashcards", "title" to s(max = 120),
            "cards" to Arr(Obj(listOf("front" to s(max = 200), "back" to s(max = 400))), minItems = 1, maxItems = 12),
        ),
        "quiz" to obj(
            "quiz", "title" to s(max = 120),
            "questions" to Arr(
                Obj(
                    listOf(
                        "question" to s(max = 300),
                        "options" to Arr(s(max = 150), minItems = 2, maxItems = 5),
                        "answer" to Num(min = 0.0, max = 4.0, integer = true),
                        "explanation" to s(max = 400),
                    ),
                ),
                minItems = 1, maxItems = 8,
            ),
        ),
        "image" to obj("image", "ref" to s("attachment id, e.g. attachment:1", 40), "caption" to s(max = 200)),
        "comparison" to obj(
            "comparison", "title" to s(max = 120),
            "options" to Arr(s(max = 60), minItems = 2, maxItems = 4),
            "criteria" to Arr(
                Obj(listOf("name" to s(max = 60), "values" to Arr(s(max = 120), minItems = 1, maxItems = 4))),
                minItems = 1, maxItems = 10,
            ),
        ),
        "actions" to obj(
            "actions",
            "buttons" to Arr(Obj(listOf("label" to s(max = 40), "prompt" to s(max = 300))), minItems = 1, maxItems = 4),
        ),
    )

    val ALL_TYPES: List<String> get() = blockSchemas.keys.toList()

    const val MAX_BLOCKS = 12

    /** Schema of a whole document limited to [types] (e.g. a skill's output components). */
    fun docSchema(types: Collection<String> = ALL_TYPES): Obj {
        val chosen = types.filter { it in blockSchemas }.ifEmpty { ALL_TYPES }
        return Obj(
            listOf(
                "v" to Num(min = 1.0, max = VERSION.toDouble(), integer = true),
                "title" to s(max = 120),
                "blocks" to Arr(Schema.OneOf(chosen.map { blockSchemas.getValue(it) }), minItems = 1, maxItems = MAX_BLOCKS),
            ),
        )
    }

    /** Short component catalogue for prompts (the grammar enforces the exact shape anyway). */
    fun promptCatalogue(types: Collection<String> = ALL_TYPES): String =
        types.filter { it in blockSchemas }.joinToString("\n") { "- " + blockSchemas.getValue(it).signature() }
}
