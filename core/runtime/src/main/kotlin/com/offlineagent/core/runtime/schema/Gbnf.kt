package com.offlineagent.core.runtime.schema

/**
 * Converts a [Schema] into a llama.cpp GBNF grammar whose root rule accepts exactly one compact JSON value of that
 * shape. With this grammar attached to sampling, the model physically cannot produce malformed JSON, unknown
 * properties, wrong enum values or too many array items. (String lengths and numeric ranges are not encoded in the
 * grammar; Kotlin validation checks those.)
 */
object Gbnf {

    private val PRIMITIVES = """
ws ::= " "?
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
number ::= "-"? unumber
unumber ::= ("0" | [1-9] [0-9]{0,9}) ("." [0-9]{1,6})?
integer ::= "-"? uinteger
uinteger ::= "0" | [1-9] [0-9]{0,8}
boolean ::= "true" | "false"
json-value ::= json-object | json-array | string | number | boolean | "null"
json-object ::= "{" ws ( string ws ":" ws json-value ( "," ws string ws ":" ws json-value ){0,15} )? ws "}"
json-array ::= "[" ws ( json-value ( "," ws json-value ){0,15} )? ws "]"
""".trim()

    /** Grammar for [schema] at the root. */
    fun of(schema: Schema): String = Builder().build(schema)

    /** Literal GBNF string for a JSON string token, e.g. `"\"name\""`. */
    fun jsonStringLiteral(value: String): String {
        val json = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private class Builder {
        private val rules = LinkedHashMap<String, String>()   // name -> body
        private val byBody = HashMap<String, String>()         // body -> name (dedupe)
        private var counter = 0

        fun build(schema: Schema): String {
            val root = ref(schema, "root-value")
            val sb = StringBuilder()
            sb.append("root ::= ").append(root).append('\n')
            rules.forEach { (n, b) -> sb.append(n).append(" ::= ").append(b).append('\n') }
            sb.append(PRIMITIVES).append('\n')
            return sb.toString()
        }

        private fun define(hint: String, body: String): String {
            byBody[body]?.let { return it }
            val name = sanitize(hint) + "-" + (counter++)
            rules[name] = body
            byBody[body] = name
            return name
        }

        private fun sanitize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "r" }.take(24)

        /** Returns an expression (rule name or inline) for [s]. */
        fun ref(s: Schema, hint: String): String = when (s) {
            is Schema.Str -> s.enum?.let { e -> "(" + e.joinToString(" | ") { jsonStringLiteral(it) } + ")" } ?: "string"
            is Schema.Num -> when {
                s.integer && (s.min ?: -1.0) >= 0 -> "uinteger"
                s.integer -> "integer"
                (s.min ?: -1.0) >= 0 -> "unumber"
                else -> "number"
            }
            is Schema.Bool -> "boolean"
            is Schema.Const -> jsonStringLiteral(s.value)
            is Schema.AnyObj -> "json-object"
            is Schema.Arr -> {
                val item = ref(s.items, hint + "-item")
                val max = s.maxItems.coerceAtLeast(1)
                val min = s.minItems.coerceIn(0, max)
                val body = if (max == 1) {
                    if (min == 0) "\"[\" ws ( $item )? ws \"]\"" else "\"[\" ws $item ws \"]\""
                } else if (min == 0) {
                    "\"[\" ws ( $item ( \",\" ws $item ){0,${max - 1}} )? ws \"]\""
                } else {
                    "\"[\" ws $item ( \",\" ws $item ){${min - 1},${max - 1}} ws \"]\""
                }
                define(hint + "-arr", body)
            }
            is Schema.Obj -> {
                val parts = s.properties.mapIndexed { i, (name, ps) ->
                    val v = ref(ps, name)
                    (if (i == 0) "" else "\",\" ws ") + jsonStringLiteral(name) + " ws \":\" ws " + v
                }
                define(hint, "\"{\" ws " + parts.joinToString(" ") + " ws \"}\"")
            }
            is Schema.OneOf -> {
                val alts = s.options.mapIndexed { i, o ->
                    val h = (o as? Schema.Obj)?.properties?.firstOrNull { it.second is Schema.Const }
                        ?.let { (it.second as Schema.Const).value } ?: "$hint-$i"
                    ref(o, h)
                }
                define(hint + "-oneof", alts.joinToString(" | "))
            }
        }
    }
}
