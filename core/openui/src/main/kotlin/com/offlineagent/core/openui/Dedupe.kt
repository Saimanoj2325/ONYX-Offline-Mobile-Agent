package com.offlineagent.core.openui

/**
 * 0.6.2.2: what small models do when they loop on a canvas. Sai's Qwen3 1.7B recording streamed
 *
 *     root = TextContent("Previous Chats Summary", "large-heavy")
 *     prevChats = ["Chat 1: [text]", "Chat 2: [text]", "Chat 3: [text]"]
 *     root = Stack([root, prevChats])
 *     root = TextContent("Previous Chats Summary", "large-heavy")   ← the same three lines, forever
 *
 * "Last definition wins" turned the self-reference `root = Stack([root, …])` into a cycle, so the canvas flickered
 * between the title and the placeholder list on every new line. [Program.deduped] makes that program stable:
 *  - a statement identical to an earlier one is dropped (a loop adds nothing);
 *  - a re-assignment that refers to its own name (`root = Stack([root, x])`) refers to the PREVIOUS definition,
 *    which is kept under a fresh name (`root_prev1`), so the result is the last valid tree instead of a cycle.
 */
fun Program.deduped(): Program {
    if (statements.size < 2) return this
    val seen = HashSet<String>()
    val out = ArrayList<Statement>(statements.size)
    var renames = 0
    for (s in statements) {
        val key = OpenUiDedupe.normalize(s.raw)
        // Only complete statements are compared: the trailing, still-streaming line may be a prefix of an earlier one.
        if (s.complete && !seen.add(key)) continue
        // The trailing, still-streaming line repeating an earlier statement: ignore it until it differs.
        if (!s.complete && seen.any { it.startsWith(key) }) continue
        val earlierIdx = out.indexOfLast { it.name == s.name }
        if (earlierIdx >= 0 && OpenUiDedupe.refersTo(s.value, s.name)) {
            val prevName = OpenUiDedupe.freshName(s.name, out.map { it.name }.toSet() + statements.map { it.name }, ++renames)
            val earlier = out[earlierIdx]
            out[earlierIdx] = earlier.copy(name = prevName, raw = "$prevName = ${earlier.value.toSource()}")
            val v = OpenUiDedupe.renameRefs(s.value, s.name, prevName)
            out += s.copy(value = v, raw = "${s.name} = ${v.toSource()}")
        } else out += s
    }
    return if (out.size == statements.size && renames == 0) this else Program(out, invalid)
}

/** Canonical openui-lang source of a value (used when [deduped] rewrites a statement). */
fun OuValue.toSource(): String = when (this) {
    is OuValue.Str -> OpenUiDedupe.quote(v)
    is OuValue.Num -> UiNode.fmt(v)
    is OuValue.Bool -> v.toString()
    OuValue.Null -> "null"
    is OuValue.Arr -> items.joinToString(", ", "[", "]") { it.toSource() }
    is OuValue.Obj -> entries.entries.joinToString(", ", "{", "}") { (k, x) -> OpenUiDedupe.quote(k) + ": " + x.toSource() }
    is OuValue.Ref -> name
    is OuValue.StateRef -> name
    is OuValue.Call -> args.joinToString(", ", "$name(", ")") { it.toSource() }
    is OuValue.At -> args.joinToString(", ", "@$name(", ")") { it.toSource() }
}

object OpenUiDedupe {
    private val WS = Regex("\\s+")
    fun normalize(raw: String): String = raw.trim().replace(WS, " ")

    fun refersTo(v: OuValue, name: String): Boolean = when (v) {
        is OuValue.Ref -> v.name == name
        is OuValue.Arr -> v.items.any { refersTo(it, name) }
        is OuValue.Obj -> v.entries.values.any { refersTo(it, name) }
        is OuValue.Call -> v.args.any { refersTo(it, name) }
        is OuValue.At -> v.args.any { refersTo(it, name) }
        else -> false
    }

    fun renameRefs(v: OuValue, from: String, to: String): OuValue = when (v) {
        is OuValue.Ref -> if (v.name == from) OuValue.Ref(to) else v
        is OuValue.Arr -> v.copy(items = v.items.map { renameRefs(it, from, to) })
        is OuValue.Obj -> v.copy(entries = v.entries.mapValues { renameRefs(it.value, from, to) })
        is OuValue.Call -> v.copy(args = v.args.map { renameRefs(it, from, to) })
        is OuValue.At -> v.copy(args = v.args.map { renameRefs(it, from, to) })
        else -> v
    }

    fun freshName(base: String, taken: Set<String>, start: Int): String {
        var n = start
        while ("${base}_prev$n" in taken) n++
        return "${base}_prev$n"
    }

    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\t' -> append("\\t"); '\r' -> Unit
            else -> append(c)
        }
        append('"')
    }
}

/**
 * 0.6.2.2: template filler a small model writes when it has no real content ("Chat 1: [text]", "<name>", "Lorem
 * ipsum", "TBD"). The resolver renders these as nothing; a canvas left with no real content fails with a friendly
 * message instead of showing the placeholders.
 */
object Placeholders {
    private val TOKEN = Regex(
        "\\[(?:text|name|value|content|summary|title|description|details?|item|items|date|time|number|amount|placeholder|topic|answer|" +
            "insert[^\\]]{0,30}|your [^\\]]{0,30}|add [^\\]]{0,30}|enter [^\\]]{0,30}|\\.\\.\\.|…|tbd)\\]" +
            "|<(?:text|name|value|content|summary|title|description|details?|item|placeholder|insert[^>]{0,30}|your [^>]{0,30})>" +
            "|\\{\\{[^}]{0,40}\\}\\}",
        RegexOption.IGNORE_CASE,
    )
    private val WHOLE = Regex(
        "^(?:\\.{3}|…|tbd|placeholder|lorem ipsum.*|sample text|example text|your text here|insert .{0,30} here|xxx+)$",
        RegexOption.IGNORE_CASE,
    )
    /** What is left of "Chat 1: [text]" once the token is gone: a bare label ending in a colon / dash. */
    private val BARE_LABEL = Regex("^[\\p{L}\\p{N} #._-]{0,30}[:\\-–—]\\s*$")

    fun contains(s: String): Boolean = TOKEN.containsMatchIn(s) || WHOLE.matches(s.trim())

    /** The string with placeholder filler removed, or null when nothing real is left. */
    fun clean(s: String): String? {
        val t = s.trim()
        if (t.isEmpty()) return s
        if (WHOLE.matches(t)) return null
        if (!TOKEN.containsMatchIn(t)) return s
        val rest = TOKEN.replace(t, "").replace(Regex("\\s{2,}"), " ").trim()
        if (rest.isEmpty() || BARE_LABEL.matches(rest) || rest.all { !it.isLetterOrDigit() }) return null
        return rest
    }
}
