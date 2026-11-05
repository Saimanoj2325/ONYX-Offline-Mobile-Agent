package com.offlineagent.core.openui

/** A resolved component ready to render. Props hold String, Double, Boolean, List<Any?>, Map<String, Any?>, UiNode, OuAction or null. */
data class UiNode(val type: String, val props: Map<String, Any?>, val id: String = "", val partial: Boolean = false) {
    fun str(k: String): String? = when (val v = props[k]) { is String -> v; is Double -> fmt(v); is Boolean -> v.toString(); else -> null }
    fun num(k: String): Double? = (props[k] as? Double) ?: (props[k] as? String)?.toDoubleOrNull()
    fun bool(k: String): Boolean? = props[k] as? Boolean
    fun list(k: String): List<Any?> = props[k] as? List<Any?> ?: emptyList()
    fun nodes(k: String): List<UiNode> = list(k).filterIsInstance<UiNode>()
    fun node(k: String): UiNode? = props[k] as? UiNode
    fun action(k: String): OuAction? = props[k] as? OuAction

    /** Depth-first walk (this node first). */
    fun walk(): Sequence<UiNode> = sequence {
        yield(this@UiNode)
        for (v in props.values) yieldAll(childrenOf(v))
    }

    private fun childrenOf(v: Any?): Sequence<UiNode> = sequence {
        when (v) {
            is UiNode -> yieldAll(v.walk())
            is List<*> -> for (x in v) yieldAll(childrenOf(x))
            else -> Unit
        }
    }

    companion object {
        fun fmt(d: Double): String = if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
    }
}

/** What a button / list item / follow-up does when tapped. */
sealed interface OuAction {
    /** Send [message] as the next user turn (`@ToAssistant`, follow-ups, `"action:…"` strings). */
    data class ToAssistant(val message: String, val context: Any? = null) : OuAction
    /** Submit the enclosing (or named) form; values go back to the model as the next user turn. */
    data class Submit(val form: String?) : OuAction
    /** Links never open automatically (offline app): the renderer copies the URL / asks first. */
    data class OpenUrl(val url: String) : OuAction
    /** Several actions in order (`Action([...])`). */
    data class Many(val actions: List<OuAction>) : OuAction
    /** `@Set`, `@Reset`, `@Run` and custom actions — no-ops in this app (no queries/mutations), kept for completeness. */
    data class Custom(val name: String, val args: List<Any?>) : OuAction

    companion object {
        /** OpenUI default-library string actions: `"submit:contact"`, `"action:cancel"`, or free text (sent as a prompt). */
        fun fromString(s: String, label: String? = null): OuAction = when {
            s.startsWith("submit:") -> Submit(s.removePrefix("submit:").ifBlank { null })
            s == "submit" -> Submit(null)
            s.startsWith("action:") -> ToAssistant(label ?: s.removePrefix("action:").replace('_', ' '))
            s.startsWith("http://") || s.startsWith("https://") -> OpenUrl(s)
            else -> ToAssistant(s)
        }
    }
}

data class OuError(val code: String, val message: String, val statement: String? = null, val warning: Boolean = false)

data class Resolved(val root: UiNode?, val errors: List<OuError>, val proseLines: List<String> = emptyList(), val placeholders: Int = 0) {
    val ok: Boolean get() = root != null

    /**
     * 0.6.2.2: the model wrote template filler ("Chat 1: [text]") and almost nothing real is left once it is hidden
     * (at most a title). The app shows a friendly message instead of an empty / placeholder canvas.
     */
    val onlyPlaceholders: Boolean get() = placeholders > 0 && (root?.walk()?.count { n -> n.props.values.any { it is String && it.isNotBlank() && n.type != "Stack" } } ?: 0) <= 1
}

/**
 * Turns a [Program] into a [UiNode] tree using the library's positional prop order, following OpenUI's rules:
 * entry = `root` (else the first statement that calls the root component), forward references, last definition wins,
 * unreferenced statements are not rendered, wrong optional props fall back to defaults, components missing a required
 * prop are dropped (kept while streaming), unknown components degrade to text. Never throws.
 */
class OpenUiResolver(private val lib: OpenUiLibrary = OpenUiLibrary.DEFAULT) {

    fun resolve(program0: Program, streaming: Boolean = false): Resolved {
        // 0.6.2.2: a looping model repeats statements and writes `root = Stack([root, …])`; see [deduped].
        val program = program0.deduped()
        val errors = mutableListOf<OuError>()
        val stmts = program.byName
        val invalidByName = program.invalid.mapNotNull { l -> l.assignedName?.let { it to l } }.toMap()
        val state = stmts.filterKeys { it.startsWith("$") }
        val entry = entryStatement(program, errors)
        val ctx = Ctx(stmts, invalidByName, state, errors, streaming)
        val root = entry?.let { s -> ctx.component(s.value, s.name, path = setOf(s.name)) }
        // Statements nobody references (and that are not the entry) are dropped, as in OpenUI.
        val prose = program.invalid.filter { it.assignedName == null && it.raw.isNotBlank() }.map { it.raw.trim() }
        if (!streaming) for (l in program.invalid) if (l.assignedName != null) errors += OuError("parse-failed", l.reason, l.assignedName)
        return Resolved(root, errors, prose, ctx.placeholders)
    }

    private fun entryStatement(p: Program, errors: MutableList<OuError>): Statement? {
        p.byName["root"]?.let { return it }
        val referenced = HashSet<String>()
        fun collect(v: OuValue) {
            when (v) {
                is OuValue.Ref -> referenced += v.name
                is OuValue.Arr -> v.items.forEach(::collect)
                is OuValue.Obj -> v.entries.values.forEach(::collect)
                is OuValue.Call -> v.args.forEach(::collect)
                is OuValue.At -> v.args.forEach(::collect)
                else -> Unit
            }
        }
        p.statements.forEach { collect(it.value) }
        val rootCall = p.statements.firstOrNull { (it.value as? OuValue.Call)?.name == lib.root }
            ?: p.statements.firstOrNull { s -> s.name !in referenced && (s.value as? OuValue.Call)?.let { lib[it.name] != null } == true }
        if (rootCall != null) errors += OuError("no-root", "no `root` statement; using `${rootCall.name}`", rootCall.name, warning = true)
        else if (p.statements.isNotEmpty()) errors += OuError("no-root", "no usable entry statement")
        return rootCall
    }

    private inner class Ctx(
        val stmts: Map<String, Statement>,
        val invalid: Map<String, InvalidLine>,
        val state: Map<String, Statement>,
        val errors: MutableList<OuError>,
        val streaming: Boolean,
    ) {
        fun err(code: String, msg: String, at: String?) { if (!streaming) errors += OuError(code, msg, at) }

        /** Placeholder strings hidden so far ("[text]", "TBD", …). */
        var placeholders = 0
        fun text(s: String): String? {
            val c = Placeholders.clean(s)
            if (c != s) placeholders++
            return c
        }

        /** Follows references. Returns null for unresolved refs (pending while streaming). */
        fun deref(v: OuValue, path: Set<String>): Pair<OuValue, Set<String>>? {
            var cur = v
            var p = path
            var hops = 0
            while (cur is OuValue.Ref || cur is OuValue.StateRef) {
                val name = if (cur is OuValue.Ref) cur.name else (cur as OuValue.StateRef).name
                if (name in p || ++hops > 32) { err("runtime-error", "circular reference to $name", name); return null }
                val s = stmts[name]
                if (s == null) {
                    val bad = invalid[name]
                    if (bad != null) return OuValue.Str(proseOf(bad.raw)) to p + name // broken statement → its text
                    if (cur is OuValue.Ref) err("unresolved", "`$name` is never defined", name)
                    return null
                }
                p = p + name
                cur = s.value
            }
            return cur to p
        }

        fun component(v: OuValue, at: String?, path: Set<String>): UiNode? {
            val (d, p) = deref(v, path) ?: return null
            return when (d) {
                is OuValue.Call -> call(d, at, p)
                is OuValue.Str -> text(d.v)?.takeIf { it.isNotBlank() }?.let { UiNode("TextContent", mapOf("text" to it, "size" to "default"), at ?: "") }
                is OuValue.Num -> UiNode("TextContent", mapOf("text" to UiNode.fmt(d.v), "size" to "default"), at ?: "")
                is OuValue.Arr -> {
                    val kids = d.items.mapNotNull { component(it, null, p) }
                    if (kids.isEmpty()) null else UiNode("Stack", mapOf("children" to kids, "direction" to "column", "gap" to "m"), at ?: "", d.partial)
                }
                else -> { err("type-mismatch", "expected a component", at); null }
            }
        }

        fun call(c: OuValue.Call, at: String?, path: Set<String>): UiNode? {
            val spec = lib[c.name]
            if (spec == null) {
                err("unknown-component", "${c.name} is not in the library", at)
                // Degrade to text: keep any strings the model wrote.
                val text = c.args.mapNotNull { (deref(it, path)?.first as? OuValue.Str)?.v }.joinToString(" — ")
                return if (text.isBlank()) null else UiNode("TextContent", mapOf("text" to text, "size" to "default"), at ?: "", c.partial)
            }
            if (c.args.size > spec.params.size) err("excess-args", "${spec.name} takes ${spec.params.size} arguments, got ${c.args.size}", at)
            val props = LinkedHashMap<String, Any?>()
            for ((i, prm) in spec.params.withIndex()) {
                val raw = c.args.getOrNull(i)
                var value: Any? = if (raw == null || raw == OuValue.Null) null else coerce(raw, prm.type, prm.name, at, path)
                if (value == null) value = prm.default
                if (value == null && prm.required) {
                    if (c.partial || streaming) { props[prm.name] = null; continue }
                    err("missing-required", "${spec.name}.${prm.name} is required", at)
                    return null
                }
                props[prm.name] = value
            }
            if (c.name == "Row") props["direction"] = "row"
            // Button label used for "action:…" strings.
            if (spec.name == "Button" || spec.name == "ListItem") {
                val label = props["label"] as? String ?: props["title"] as? String
                val a = props["action"]
                if (a is OuAction.ToAssistant && a.message.isBlank()) props["action"] = OuAction.ToAssistant(label ?: "")
                if (a == null && spec.name == "Button") props["action"] = OuAction.ToAssistant(label ?: "")
            }
            return UiNode(spec.name, props, at ?: "", c.partial)
        }

        fun coerce(v: OuValue, t: PType, prop: String, at: String?, path: Set<String>): Any? {
            if (t is PType.Comp) return component(v, null, path)
            val (d, p) = deref(v, path) ?: return null
            return when (t) {
                PType.Str -> when (d) {
                    is OuValue.Str -> text(d.v); is OuValue.Num -> UiNode.fmt(d.v); is OuValue.Bool -> d.v.toString()
                    else -> { err("type-mismatch", "$prop: expected string", at); null }
                }
                PType.Num -> when (d) {
                    is OuValue.Num -> d.v
                    is OuValue.Str -> d.v.replace(",", "").trim().removeSuffix("%").toDoubleOrNull() ?: run { err("type-mismatch", "$prop: expected number", at); null }
                    else -> { err("type-mismatch", "$prop: expected number", at); null }
                }
                PType.Bool -> when (d) {
                    is OuValue.Bool -> d.v
                    is OuValue.Str -> d.v.lowercase().toBooleanStrictOrNull()
                    else -> null
                }
                is PType.Enum -> {
                    val s = (d as? OuValue.Str)?.v
                    t.values.firstOrNull { it.equals(s, ignoreCase = true) } ?: run { if (s != null) err("type-mismatch", "$prop: '$s' is not one of ${t.values}", at); null }
                }
                is PType.Arr -> {
                    val items = when (d) { is OuValue.Arr -> d.items; else -> listOf(d) } // lenient: single value → list
                    items.mapNotNull { coerce(it, t.of, prop, at, p) }
                }
                PType.Action -> action(d, p)
                PType.Cell -> when (d) {
                    is OuValue.Str -> text(d.v) ?: ""; is OuValue.Num -> d.v; is OuValue.Bool -> d.v
                    is OuValue.Null -> ""
                    else -> plain(d, p)?.toString()
                }
                is PType.Shape -> plain(d, p)
                is PType.Comp -> null // handled above
            }
        }

        fun action(d: OuValue, path: Set<String>): OuAction? = when (d) {
            is OuValue.Str -> OuAction.fromString(d.v)
            is OuValue.At -> {
                val args = d.args.map { a -> deref(a, path)?.first?.let { plain(it, path) } }
                when (d.name) {
                    "ToAssistant" -> OuAction.ToAssistant(args.getOrNull(0)?.toString() ?: "", args.getOrNull(1))
                    "OpenUrl" -> args.getOrNull(0)?.toString()?.let { OuAction.OpenUrl(it) }
                    "Submit" -> OuAction.Submit(args.getOrNull(0)?.toString())
                    else -> OuAction.Custom(d.name, args)
                }
            }
            is OuValue.Call -> if (d.name == "Action") {
                val list = (d.args.firstOrNull()?.let { deref(it, path)?.first } as? OuValue.Arr)?.items.orEmpty()
                val acts = list.mapNotNull { a -> deref(a, path)?.first?.let { action(it, path) } }.flatMap { if (it is OuAction.Many) it.actions else listOf(it) }
                if (acts.isEmpty()) null else OuAction.Many(acts)
            } else null
            else -> null
        }

        fun plain(d: OuValue, path: Set<String>): Any? = when (d) {
            is OuValue.Str -> d.v
            is OuValue.Num -> d.v
            is OuValue.Bool -> d.v
            is OuValue.Null -> null
            is OuValue.Arr -> d.items.map { x -> deref(x, path)?.first?.let { plain(it, path) } }
            is OuValue.Obj -> d.entries.mapValues { (_, x) -> deref(x, path)?.first?.let { plain(it, path) } }
            is OuValue.Call -> component(d, null, path)
            is OuValue.At -> action(d, path)
            is OuValue.Ref, is OuValue.StateRef -> deref(d, path)?.first?.let { plain(it, path) }
        }
    }

    companion object {
        private val STRING_LIT = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        /** Best-effort readable text of a broken statement: its string literals, else the right-hand side. */
        fun proseOf(raw: String): String {
            val rhs = raw.substringAfter('=').trim()
            val lits = STRING_LIT.findAll(rhs).map { it.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n") }.filter { it.isNotBlank() }.toList()
            return if (lits.isNotEmpty()) lits.joinToString(" — ") else rhs
        }
    }
}
