package com.offlineagent.core.openui

/**
 * Form round-trip: the renderer collects values for a `Form`, [FormModel.validate] applies OpenUI's rules
 * (required, email, min:N, max:N, minLength:N, maxLength:N, pattern:REGEX, url, numeric), and
 * [FormSubmission.toUserMessage] turns them into the next user turn. [FormSubmission.parse] reads it back
 * (history display, tests).
 */
data class FormField(
    val name: String,
    val label: String,
    val kind: String, // Input | TextArea | Select | CheckBoxGroup | RadioGroup | Slider | SwitchGroup
    val inputType: String = "text",
    val options: List<Pair<String, String>> = emptyList(), // value to label
    val rules: List<String> = emptyList(),
    val default: Any? = null,
    val placeholder: String? = null,
    val hint: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
)

object FormModel {
    fun fields(form: UiNode): List<FormField> = form.nodes("fields").mapNotNull { fc ->
        val input = fc.node("input") ?: return@mapNotNull null
        val label = fc.str("label") ?: input.str("name") ?: ""
        val rules = input.list("rules").map { it.toString() }
        when (input.type) {
            "Input" -> FormField(input.str("name") ?: label, label, "Input", input.str("type") ?: "text", rules = rules, placeholder = input.str("placeholder"), hint = fc.str("hint"))
            "TextArea" -> FormField(input.str("name") ?: label, label, "TextArea", rules = rules, placeholder = input.str("placeholder"), hint = fc.str("hint"))
            "Select" -> FormField(input.str("name") ?: label, label, "Select", options = input.nodes("items").map { (it.str("value") ?: "") to (it.str("label") ?: it.str("value") ?: "") },
                rules = rules, placeholder = input.str("placeholder"), hint = fc.str("hint"))
            "CheckBoxGroup" -> {
                val items = input.nodes("items")
                FormField(input.str("name") ?: label, label, "CheckBoxGroup", options = items.map { (it.str("name") ?: it.str("label") ?: "") to (it.str("label") ?: "") },
                    rules = rules, default = items.filter { it.bool("defaultChecked") == true }.map { it.str("name") ?: it.str("label") ?: "" }.toSet(), hint = fc.str("hint"))
            }
            "RadioGroup" -> FormField(input.str("name") ?: label, label, "RadioGroup", options = input.nodes("items").map { (it.str("value") ?: it.str("label") ?: "") to (it.str("label") ?: "") },
                rules = rules, default = input.str("defaultValue"), hint = fc.str("hint"))
            "Slider" -> FormField(input.str("name") ?: label, label, "Slider", rules = rules, default = input.num("defaultValue") ?: input.num("min"),
                min = input.num("min") ?: 0.0, max = input.num("max") ?: 100.0, step = input.num("step"), hint = fc.str("hint"))
            "SwitchGroup" -> {
                val items = input.nodes("items")
                FormField("switches", label, "SwitchGroup", options = items.map { (it.str("name") ?: it.str("label") ?: "") to (it.str("label") ?: "") },
                    default = items.filter { it.bool("checked") == true || it.bool("defaultChecked") == true }.map { it.str("name") ?: it.str("label") ?: "" }.toSet())
            }
            else -> null
        }
    }

    /** Initial values: defaults, else "" / empty set. */
    fun initialValues(fields: List<FormField>): Map<String, Any> = fields.associate { f ->
        f.name to (f.default ?: when (f.kind) { "CheckBoxGroup", "SwitchGroup" -> emptySet<String>(); else -> "" })
    }

    /** Returns field name -> error message for invalid fields (empty map = valid). */
    fun validate(fields: List<FormField>, values: Map<String, Any?>): Map<String, String> {
        val errs = LinkedHashMap<String, String>()
        for (f in fields) {
            val v = values[f.name]
            val s = when (v) { null -> ""; is Collection<*> -> if (v.isEmpty()) "" else v.joinToString(","); is Double -> UiNode.fmt(v); else -> v.toString() }
            for (r in f.rules) {
                val key = r.substringBefore(':').trim().lowercase()
                val arg = r.substringAfter(':', "").trim()
                val msg: String? = when (key) {
                    "required" -> if (s.isBlank()) "${f.label} is required" else null
                    "email" -> if (s.isNotBlank() && !EMAIL.matches(s)) "Enter a valid email" else null
                    "url" -> if (s.isNotBlank() && !(s.startsWith("http://") || s.startsWith("https://"))) "Enter a valid URL" else null
                    "numeric" -> if (s.isNotBlank() && s.toDoubleOrNull() == null) "Enter a number" else null
                    "minlength" -> arg.toIntOrNull()?.let { n -> if (s.isNotEmpty() && s.length < n) "At least $n characters" else null }
                    "maxlength" -> arg.toIntOrNull()?.let { n -> if (s.length > n) "At most $n characters" else null }
                    "min" -> arg.toDoubleOrNull()?.let { n -> s.toDoubleOrNull()?.let { x -> if (x < n) "Must be ≥ ${UiNode.fmt(n)}" else null } ?: if (f.inputType == "text" && s.isNotEmpty() && s.length < n) "At least ${n.toInt()} characters" else null }
                    "max" -> arg.toDoubleOrNull()?.let { n -> s.toDoubleOrNull()?.let { x -> if (x > n) "Must be ≤ ${UiNode.fmt(n)}" else null } }
                    "pattern" -> runCatching { Regex(arg) }.getOrNull()?.let { re -> if (s.isNotEmpty() && !re.matches(s)) "Invalid format" else null }
                    else -> null
                }
                if (msg != null) { errs[f.name] = msg; break }
            }
        }
        return errs
    }

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
}

object FormSubmission {
    const val HEADER = "Form submitted:"

    /** The next user turn. Readable for the user and the model; machine-readable for [parse]. */
    fun toUserMessage(formName: String, fields: List<FormField>, values: Map<String, Any?>): String = buildString {
        append(HEADER).append(' ').append(formName).append('\n')
        for (f in fields) {
            val v = values[f.name]
            val shown = when (v) {
                is Collection<*> -> v.map { x -> f.options.firstOrNull { it.first == x }?.second ?: x.toString() }.joinToString(", ").ifBlank { "(none)" }
                is Double -> UiNode.fmt(v)
                null -> ""
                else -> if (f.kind == "Select" || f.kind == "RadioGroup") f.options.firstOrNull { it.first == v.toString() }?.second ?: v.toString() else v.toString()
            }.replace("\n", " ⏎ ")
            val masked = if (f.inputType == "password" && shown.isNotEmpty()) "••••" else shown
            append("- ").append(f.label).append(" [").append(f.name).append("]: ").append(masked).append('\n')
        }
    }.trimEnd()

    data class Parsed(val form: String, val values: Map<String, String>)

    fun parse(message: String): Parsed? {
        val lines = message.lines()
        val head = lines.firstOrNull()?.takeIf { it.startsWith(HEADER) } ?: return null
        val form = head.removePrefix(HEADER).trim()
        val values = LinkedHashMap<String, String>()
        val re = Regex("^- .*? \\[([^\\]]+)]: ?(.*)$")
        for (l in lines.drop(1)) re.find(l)?.let { values[it.groupValues[1]] = it.groupValues[2].replace(" ⏎ ", "\n") }
        return Parsed(form, values)
    }
}
