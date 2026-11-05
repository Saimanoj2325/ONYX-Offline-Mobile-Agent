package com.offlineagent.core.openui

/**
 * System-prompt section generated from the component library, the way OpenUI's `library.prompt()` /
 * lang-core `generatePrompt` does it: syntax rules, component signatures grouped by category, streaming/hoisting
 * rules, examples, and (for chat) "inline mode" — Markdown prose with optional ```openui-lang fences.
 */
object OpenUiPrompt {
    enum class Mode {
        /** Chat: Markdown by default; an ```openui-lang block only when UI helps (tables, charts, forms, choices). */
        INLINE,
        /** Canvas: the whole answer is one program (used with the GBNF grammar). */
        PROGRAM,
    }

    data class Options(
        val mode: Mode = Mode.INLINE,
        val examples: Boolean = true,
        /** Extra rules appended at the end. */
        val extraRules: List<String> = emptyList(),
    )

    fun generate(lib: OpenUiLibrary = OpenUiLibrary.DEFAULT, o: Options = Options()): String = buildString {
        if (o.mode == Mode.PROGRAM) {
            append("You respond using openui-lang, a compact declarative UI language. Your ENTIRE response must be valid openui-lang code — no markdown fences, no explanations.\n\n")
        } else {
            append("## Rich UI (openui-lang)\n\nYou can show native UI components in your answer by writing openui-lang inside a ```openui-lang fenced block.\n\n")
        }
        append("### Syntax Rules\n")
        listOf(
            "Each statement is on its own line: `identifier = Expression`",
            "The first statement is `root = ${lib.root}([...])` (the entry point)",
            "Expressions: strings (\"...\"), numbers, booleans (true/false), arrays ([...]), or component calls TypeName(arg1, arg2, ...)",
            "Arguments are POSITIONAL (order matters, not names). Optional arguments (marked ?) can be omitted from the end",
            "Define `name = ...` on one line and use `name` later; every name must be reachable from root or it will not render",
            "Strings use double quotes with backslash escaping. No operators, no logic",
        ).forEachIndexed { i, r -> append(i + 1).append(". ").append(r).append('\n') }
        append("\n### Component Signatures\n")
        for ((group, comps) in lib.components.groupBy { it.group }) {
            append("#### ").append(group).append('\n')
            comps.forEach { append(it.signature()).append('\n') }
        }
        append("\n### Streaming\nWrite statements TOP-DOWN: root first, then components, then data. The UI renders while you write.\n")
        if (o.examples) {
            append("\n### Examples\n")
            val ex = examples(lib)
            if (o.mode == Mode.INLINE) ex.forEach { (title, code) -> append(title).append(":\n```openui-lang\n").append(code).append("\n```\n\n") }
            else ex.forEach { (title, code) -> append(title).append(":\n").append(code).append("\n\n") }
        }
        append("### Rules\n")
        val rules = mutableListOf<String>()
        if (o.mode == Mode.INLINE) {
            rules += "For normal answers, write plain Markdown. Use an ```openui-lang block only when components clearly help: tables, charts, step-by-step guides, forms that collect input, or choices"
            rules += "You may write a short sentence before or after the block. Never put Markdown inside the block except in TextContent / MarkDownRenderer strings"
            rules += "To ask the user for details, use a Form; its values come back to you as the next user message (\"${FormSubmission.HEADER} …\")"
            if (lib["FollowUpBlock"] != null) rules += "You may end with a FollowUpBlock of 2-3 short follow-up questions the user might ask next"
        } else {
            rules += "ALWAYS start with root = ${lib.root}(...)"
            rules += "Output ONLY openui-lang code, one statement per line"
            rules += "When asked to change the current UI, output the complete updated program (same names for unchanged parts)"
        }
        rules += "Use realistic data. Keep it compact: at most about 12 components"
        rules += o.extraRules
        rules.forEach { append("- ").append(it).append('\n') }
    }.trimEnd()

    /** Examples that only use components present in [lib]. */
    fun examples(lib: OpenUiLibrary): List<Pair<String, String>> = listOf(
        "Table" to """
            root = Stack([title, tbl])
            title = TextContent("Top Languages", "large-heavy")
            tbl = Table(cols, rows)
            cols = [Col("Language", "string"), Col("Users (M)", "number")]
            rows = [["Python", 15.7], ["JavaScript", 14.2], ["Java", 12.1]]
        """.trimIndent(),
        "Bar chart" to """
            root = Stack([title, chart])
            title = TextContent("Q4 Revenue", "large-heavy")
            chart = BarChart(["Oct", "Nov", "Dec"], [s1, s2], "grouped")
            s1 = Series("Product A", [120, 150, 180])
            s2 = Series("Product B", [90, 110, 140])
        """.trimIndent(),
        "Form" to """
            root = Stack([title, form])
            title = TextContent("Plan your trip", "large-heavy")
            form = Form("trip", [cityField, daysField], btns)
            cityField = FormControl("City", Input("city", "e.g. Goa", "text", ["required"]))
            daysField = FormControl("Days", Select("days", [SelectItem("2", "2 days"), SelectItem("4", "4 days")]))
            btns = Buttons([Button("Make plan", "submit:trip", "primary")])
        """.trimIndent(),
    ).filter { (_, code) -> Regex("\\b([A-Z][A-Za-z]+)\\(").findAll(code).all { lib[it.groupValues[1]] != null } }

    /**
     * Phase 4b: compact chat prompt (≈ 250 tokens instead of ≈ 2 000 for the full library). On a phone CPU every
     * system-prompt token costs prefill time before the first visible token, so Chat mode uses this short cheat sheet;
     * the full generated library ([generate]) is only used in Canvas mode or when the user asks for UI.
     * Every name and argument order here matches [OpenUiLibrary.DEFAULT] (checked by a unit test).
     */
    fun cheatSheet(): String = CHEAT_SHEET

    /** Component calls used in [cheatSheet] (for the library-consistency test). */
    internal val CHEAT_SHEET = """
        |## Optional UI (openui-lang)
        |Answer in Markdown. Only when a table, chart, form or step list really helps, add one ```openui-lang block: one `name = Component(args)` per line, positional args, `root = Stack([...])` first.
        |Components: TextContent(text, size?) · Callout(variant, title, description?) · Table(columns, rows) with Col(label, type?) · BarChart(labels, series) / LineChart(labels, series) with Series(category, values) · PieChart(slices) with Slice(category, value) · Steps(items) with StepsItem(title, details?) · Form(name, fields, buttons?) with FormControl(label, Input(name, placeholder?)) and Buttons([Button(label, "submit:<form name>")]) · FollowUpBlock(items) with FollowUpItem(text)
        |Example:
        |```openui-lang
        |root = Stack([t, tbl])
        |t = TextContent("Top languages", "large-heavy")
        |tbl = Table([Col("Language", "string"), Col("Users (M)", "number")], [["Python", 15.7], ["Java", 12.1]])
        |```
        |Form answers come back as "${FormSubmission.HEADER} …".
    """.trimMargin()

    /**
     * 0.6.2.2: Canvas-mode system prompt for phones. The full generated library ([generate] in PROGRAM mode) is about
     * 2 000 tokens; with a 1.7B model on a phone CPU that alone was most of a minute of prefill whenever its KV cache
     * was not warm. This sheet (~400 tokens) lists the components a canvas really uses (every name and argument order
     * matches [OpenUiLibrary.DEFAULT]; checked by a unit test) and the rules a small model breaks: no placeholders, no
     * re-assigning names, no repeating lines. It is static text, so it is the cached prefix of every canvas request.
     */
    fun canvasSheet(): String = CANVAS_SHEET

    internal val CANVAS_SHEET = """
        |You write openui-lang: a compact UI language. Output ONLY code, one statement per line: `name = Expression`.
        |The first line is `root = Stack([...])`. Arguments are positional. Every name is assigned ONCE and used by a later reference.
        |Components: TextContent(text, size?) size "small" | "default" | "large" | "large-heavy" · Callout(variant, title, description?) variant "info" | "success" | "warning" | "error" · Table(columns, rows) with Col(label, type?) · BarChart(labels, series) / LineChart(labels, series) with Series(category, values) · PieChart(slices) with Slice(category, value) · Steps(items) with StepsItem(title, details?) · ListBlock(items) with ListItem(title, subtitle?) · Form(name, fields, buttons?) with FormControl(label, Input(name, placeholder?)) and Buttons([Button(label, "submit:<form name>")]) · Stack(children)
        |Example:
        |root = Stack([title, tbl])
        |title = TextContent("Top Languages", "large-heavy")
        |tbl = Table([Col("Language", "string"), Col("Users (M)", "number")], [["Python", 15.7], ["Java", 12.1]])
        |Rules:
        |- Use real, specific content from the request and the context. Never write placeholders like "[text]", "Item 1", "TBD" or "...".
        |- If you do not have the facts, write one Callout("info", ...) that says so.
        |- At most about 12 components. Never repeat a line. Stop after the last component.
        |- To change the current UI, write the complete updated program (same names for unchanged parts).
    """.trimMargin()

    /** Canvas update: the current program is shown to the model, which writes the full updated program. */
    fun updateUser(request: String, currentSource: String): String =
        "Current UI (openui-lang):\n$currentSource\n\nChange request: $request\n\nWrite the complete updated program."
}
