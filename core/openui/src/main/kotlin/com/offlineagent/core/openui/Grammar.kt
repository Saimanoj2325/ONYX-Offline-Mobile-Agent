package com.offlineagent.core.openui

/**
 * llama.cpp GBNF grammar for a whole OpenUI Lang program (Canvas mode). It guarantees well-formed syntax: the first
 * statement is `root = …`, one statement per line, only library component names (+ `@ToAssistant` / `@OpenUrl`
 * actions), balanced brackets and properly escaped strings. Prop types are still checked by [OpenUiResolver].
 *
 * Inline chat mode (Markdown + optional fenced block) is left unconstrained: llama.cpp's *lazy* grammar (trigger on
 * "```openui-lang") would fit, but the JNI bridge does not expose lazy grammars yet (see docs/PHASE4.md).
 */
object OpenUiGrammar {
    fun gbnf(lib: OpenUiLibrary = OpenUiLibrary.DEFAULT, maxStatements: Int = 40): String {
        val comps = lib.names.sortedByDescending { it.length }.joinToString(" | ") { "\"$it\"" }
                return """
            root ::= "root = " call "\n" stmt{0,${maxStatements - 1}}
            stmt ::= name " = " expr "\n"
            expr ::= call | action | array | string | number | bool | ref
            call ::= comp "(" args? ")"
            action ::= "@" ("ToAssistant" | "OpenUrl") "(" args? ")"
            args ::= expr (", " expr)*
            array ::= "[" (expr (", " expr)*)? "]"
            comp ::= $comps
            ref ::= name
            name ::= [a-z] [a-zA-Z0-9_]{0,30}
            string ::= "\"" char* "\""
            char ::= [^"\\\n] | "\\" ["\\nt/]
            number ::= "-"? [0-9]{1,12} ("." [0-9]{1,6})?
            bool ::= "true" | "false"
        """.trimIndent() + "\n"
    }
}
