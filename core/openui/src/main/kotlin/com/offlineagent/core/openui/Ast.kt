package com.offlineagent.core.openui

/**
 * OpenUI Lang syntax tree. A program is a list of `identifier = Expression` statements (one per line).
 * See https://www.openui.com/docs/openui-lang/specification (OpenUI Lang 1.0 / v0.5).
 */
sealed interface OuValue {
    data class Str(val v: String) : OuValue
    data class Num(val v: Double) : OuValue
    data class Bool(val v: Boolean) : OuValue
    data object Null : OuValue
    data class Arr(val items: List<OuValue>, val partial: Boolean = false) : OuValue
    data class Obj(val entries: Map<String, OuValue>, val partial: Boolean = false) : OuValue
    /** Reference to another statement (forward references allowed). */
    data class Ref(val name: String) : OuValue
    /** `$variable` (reactive state). Rendered with its declared default. */
    data class StateRef(val name: String) : OuValue
    /** `Component(args…)` — positional arguments. [partial] = the closing paren has not streamed in yet. */
    data class Call(val name: String, val args: List<OuValue>, val partial: Boolean = false) : OuValue
    /** `@Name(args…)` — built-in function or action (`@ToAssistant`, `@OpenUrl`, `@Set`, `@Reset`, `@Run`, custom). */
    data class At(val name: String, val args: List<OuValue>, val partial: Boolean = false) : OuValue
}

/** One parsed statement. [complete] = the whole line had streamed in (not the trailing line of a stream). */
data class Statement(val name: String, val value: OuValue, val raw: String, val complete: Boolean = true)

/** A line that is not valid OpenUI Lang. Lines that are plain prose degrade to text in the renderer. */
data class InvalidLine(val raw: String, val reason: String) {
    /** `name = …` that failed to parse (vs. plain prose the model wrote inside the block). */
    val assignedName: String? get() = ASSIGN.find(raw)?.groupValues?.get(1)

    companion object { internal val ASSIGN = Regex("^\\s*(\\$?[A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)") }
}

/** Parsed program. Statements keep source order; "last definition wins" for duplicate names. */
data class Program(val statements: List<Statement>, val invalid: List<InvalidLine> = emptyList()) {
    val byName: Map<String, Statement> by lazy { statements.associateBy { it.name } }

    /** Incremental editing: statements of [patch] replace/add to this program (last definition wins). */
    fun merge(patch: Program): Program {
        val names = patch.statements.map { it.name }.toSet()
        return Program(statements.filter { it.name !in names } + patch.statements, patch.invalid)
    }

    /** Canonical source (one statement per line) — used to store canvases and to show the model the current UI. */
    fun toSource(): String = statements.joinToString("\n") { it.raw.trim() }

    val isEmpty: Boolean get() = statements.isEmpty()
}
