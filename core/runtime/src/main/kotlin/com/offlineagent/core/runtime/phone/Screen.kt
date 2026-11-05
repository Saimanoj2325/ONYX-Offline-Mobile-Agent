package com.offlineagent.core.runtime.phone

/** Pixel rectangle on screen. */
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isEmpty get() = width <= 0 || height <= 0
}

/** One node of the accessibility view tree (copied out of AccessibilityNodeInfo, so it is plain data). */
data class ScreenNode(
    val className: String = "",
    val text: String = "",
    val contentDescription: String = "",
    val hint: String = "",
    val viewId: String = "",
    val bounds: Bounds = Bounds(0, 0, 0, 0),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val password: Boolean = false,
    val enabled: Boolean = true,
    val selected: Boolean = false,
    /** Android autofill hints / input type (for form filling). */
    val autofillHints: List<String> = emptyList(),
    val inputType: Int = 0,
    val children: List<ScreenNode> = emptyList(),
    /** Path of child indices from the root (stable within one snapshot). */
    val path: List<Int> = emptyList(),
) {
    val label: String get() = listOf(text, contentDescription, hint).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val interactive: Boolean get() = enabled && (clickable || editable || scrollable || checkable || longClickable)
    val shortClass: String get() = className.substringAfterLast('.')
}

/** An interactive or informative element the agent can refer to by [id]. */
data class ScreenElement(val id: Int, val node: ScreenNode, val nearbyLabel: String)

data class ScreenSnapshot(val packageName: String, val root: ScreenNode, val appLabel: String = packageName, val activity: String = "", val windowTitle: String = "") {
    /** Elements in reading order: interactive nodes plus text-only nodes (as context). ids start at 1. */
    val elements: List<ScreenElement> by lazy {
        val out = ArrayList<ScreenElement>()
        var lastText = ""
        fun walk(n: ScreenNode) {
            if (!n.bounds.isEmpty || n.children.isEmpty()) {
                val informative = n.label.isNotBlank()
                if (n.interactive || (informative && n.children.isEmpty())) {
                    out += ScreenElement(out.size + 1, n, if (n.editable && n.hint.isBlank() && n.text.isBlank()) lastText else "")
                }
                if (informative && !n.editable) lastText = n.label
            }
            n.children.forEach { walk(it) }
        }
        walk(root)
        out
    }

    fun element(id: Int): ScreenElement? = elements.getOrNull(id - 1)?.takeIf { it.id == id }

    val allText: String by lazy { elements.joinToString(" ") { it.node.label }.lowercase() }

    /** Compact, token-cheap description for the model. Passwords are never shown. */
    fun describe(maxChars: Int = 6000): String = buildString {
        append("App: ").append(appLabel).append(" (").append(packageName).append(")\n")
        for (e in elements) {
            val n = e.node
            val kind = when {
                n.editable -> "input"
                n.checkable -> if (n.checked) "checkbox[x]" else "checkbox[ ]"
                n.scrollable -> "scroll"
                n.clickable || n.longClickable -> "button"
                else -> "text"
            }
            val value = when {
                n.password -> if (n.text.isNotEmpty()) "•••• (filled)" else ""
                n.editable -> n.text
                else -> ""
            }
            val line = buildString {
                append('[').append(e.id).append("] ").append(kind)
                val lbl = if (n.editable) listOf(n.hint, e.nearbyLabel, n.contentDescription, n.viewId.substringAfterLast('/')).firstOrNull { it.isNotBlank() } ?: "" else n.label
                if (lbl.isNotBlank()) append(" \"").append(lbl.take(80).replace('\n', ' ')).append('"')
                if (n.editable) append(" value=\"").append(value.take(60)).append('"')
                if (n.password) append(" (password)")
                if (!n.enabled) append(" (disabled)")
                if (n.focused) append(" (focused)")
            }
            if (length + line.length + 1 > maxChars) { append("…(more elements; scroll to see)\n"); break }
            append(line).append('\n')
        }
    }
}

/** What the screen agent (or a macro) does next. */
sealed interface ScreenAction {
    data class Tap(val element: Int) : ScreenAction
    data class Type(val element: Int, val text: String, val submit: Boolean = false) : ScreenAction
    /** [element] = the scroll container (0 = the first scrollable on screen). */
    data class Scroll(val direction: String, val element: Int = 0) : ScreenAction
    /** Phase 6.2: long-press a node (macros record it from TYPE_VIEW_LONG_CLICKED). */
    data class LongPress(val element: Int) : ScreenAction
    /** Phase 6.2: the keyboard's Enter / Search / Go key on a field (ACTION_IME_ENTER, API 30+). */
    data class Enter(val element: Int) : ScreenAction
    data object Back : ScreenAction
    data object Home : ScreenAction
    data class OpenApp(val app: String) : ScreenAction
    data class Wait(val millis: Long) : ScreenAction
    data class AskUser(val question: String) : ScreenAction
    data class Done(val summary: String) : ScreenAction

    fun describe(s: ScreenSnapshot?): String = when (this) {
        is Tap -> "Tap " + target(s, element)
        is Type -> "Type \"${if (looksSecret(text)) "••••" else text.take(60)}\" into " + target(s, element) + if (submit) " and press Enter" else ""
        is Scroll -> "Scroll $direction"
        is LongPress -> "Long-press " + target(s, element)
        is Enter -> "Press Enter in " + target(s, element)
        Back -> "Press Back"
        Home -> "Go to the home screen"
        is OpenApp -> "Open $app"
        is Wait -> "Wait ${millis} ms"
        is AskUser -> "Ask you: $question"
        is Done -> "Finish: $summary"
    }

    companion object {
        fun target(s: ScreenSnapshot?, id: Int): String {
            val e = s?.element(id) ?: return "element [$id]"
            val l = e.node.label.ifBlank { e.nearbyLabel }.ifBlank { e.node.viewId.substringAfterLast('/') }.ifBlank { e.node.shortClass }
            return "\"${l.take(50)}\" [$id]"
        }
        private fun looksSecret(t: String) = t.length in 4..8 && t.all { it.isDigit() }
    }
}
