package com.offlineagent.core.openui

/** Prop types, printed in the generated system prompt the same way OpenUI's prompt generator prints them. */
sealed interface PType {
    fun text(): String

    data object Str : PType { override fun text() = "string" }
    data object Num : PType { override fun text() = "number" }
    data object Bool : PType { override fun text() = "boolean" }
    data class Enum(val values: List<String>) : PType { override fun text() = values.joinToString(" | ") { "\"$it\"" } }
    /** A component slot; [allowed] null = any component. */
    data class Comp(val allowed: List<String>? = null) : PType { override fun text() = allowed?.joinToString(" | ") ?: "Component" }
    data class Arr(val of: PType) : PType {
        override fun text() = when (of) {
            is Comp -> if ((of.allowed?.size ?: 1) == 1) "${of.text()}[]" else "(${of.text()})[]"
            is Enum, Cell -> "(${of.text()})[]"
            else -> "${of.text()}[]"
        }
    }
    /** A button action: `@ToAssistant("…")`, `@OpenUrl("…")`, or OpenUI's string form `"submit:form"` / `"action:id"`. */
    data object Action : PType { override fun text() = "string | @ToAssistant | @OpenUrl" }
    data class Shape(val desc: String) : PType { override fun text() = desc }
    /** Primitive cell (table rows). */
    data object Cell : PType { override fun text() = "string | number | boolean" }
}

data class Param(val name: String, val type: PType, val required: Boolean = true, val default: Any? = null)

data class ComponentSpec(
    val name: String,
    val params: List<Param>,
    val description: String,
    val group: String,
    /** Shown in the compact prompt (small models). */
    val core: Boolean = true,
) {
    fun signature(): String = "$name(" + params.joinToString(", ") { p ->
        p.name + (if (p.required) "" else "?") + ": " + p.type.text()
    } + ")" + if (description.isNotBlank()) " — $description" else ""
}

/**
 * The component library. Names, positional prop order and enum values follow OpenUI's default React library
 * (`@openuidev/react-ui` genui-lib: openuiLibrary + openuiChatLibrary, MIT) so prompts and model outputs written for
 * OpenUI work here, rendered natively with Compose.
 */
class OpenUiLibrary(val components: List<ComponentSpec>, val root: String = "Stack") {
    private val byName = components.associateBy { it.name }
    operator fun get(name: String): ComponentSpec? = byName[name] ?: byName[ALIASES[name] ?: ""]
    val names: List<String> get() = components.map { it.name }
    fun compact(): OpenUiLibrary = OpenUiLibrary(components.filter { it.core }, root)

    companion object {
        /** Common names small models use instead of the library's (resolved silently). */
        val ALIASES = mapOf(
            "Text" to "TextContent", "Markdown" to "MarkDownRenderer", "MarkdownRenderer" to "MarkDownRenderer",
            "Header" to "CardHeader", "Heading" to "CardHeader", "Code" to "CodeBlock", "Column" to "Col",
            "Tab" to "TabItem", "Step" to "StepsItem", "Chip" to "FollowUpItem", "FollowUps" to "FollowUpBlock",
            "List" to "ListBlock", "Checkbox" to "CheckBoxGroup", "Radio" to "RadioGroup", "Option" to "SelectItem",
            "Divider" to "Separator", "Alert" to "Callout", "Row" to "Stack", "Column_" to "Stack", "Pie" to "PieChart",
        )

        private val GAP = PType.Enum(listOf("none", "xs", "s", "m", "l", "xl", "2xl"))
        private val ALIGN = PType.Enum(listOf("start", "center", "end", "stretch", "baseline"))
        private val JUSTIFY = PType.Enum(listOf("start", "center", "end", "between", "around", "evenly"))
        private val DIR = PType.Enum(listOf("row", "column"))
        val CONTENT = listOf("TextContent", "MarkDownRenderer", "CardHeader", "Callout", "TextCallout", "CodeBlock", "Image",
            "Separator", "BarChart", "LineChart", "AreaChart", "HorizontalBarChart", "PieChart", "Table", "TagBlock", "Form",
            "Buttons", "Steps", "ListBlock", "Tabs", "Accordion", "Card", "Stack", "FollowUpBlock")

        val DEFAULT = OpenUiLibrary(listOf(
            // ---- Layout
            ComponentSpec("Stack", listOf(Param("children", PType.Arr(PType.Comp())), Param("direction", DIR, false, "column"),
                Param("gap", GAP, false, "m"), Param("align", ALIGN, false), Param("justify", JUSTIFY, false), Param("wrap", PType.Bool, false, false)),
                "Flex container. direction \"row\"|\"column\" (default column).", "Layout"),
            ComponentSpec("Tabs", listOf(Param("items", PType.Arr(PType.Comp(listOf("TabItem"))))), "Tabbed container", "Layout"),
            ComponentSpec("TabItem", listOf(Param("value", PType.Str), Param("trigger", PType.Str), Param("content", PType.Arr(PType.Comp()))),
                "value is unique id, trigger is tab label, content is array of components", "Layout"),
            ComponentSpec("Accordion", listOf(Param("items", PType.Arr(PType.Comp(listOf("AccordionItem"))))), "Collapsible sections", "Layout", core = false),
            ComponentSpec("AccordionItem", listOf(Param("value", PType.Str), Param("trigger", PType.Str), Param("content", PType.Arr(PType.Comp()))),
                "value is unique id, trigger is section title", "Layout", core = false),
            ComponentSpec("Steps", listOf(Param("items", PType.Arr(PType.Comp(listOf("StepsItem"))))), "Step-by-step guide", "Layout"),
            ComponentSpec("StepsItem", listOf(Param("title", PType.Str), Param("details", PType.Str, false, "")), "title and details text for one step", "Layout"),
            ComponentSpec("Separator", listOf(Param("orientation", PType.Enum(listOf("horizontal", "vertical")), false, "horizontal")), "Visual divider", "Layout", core = false),
            // ---- Content
            ComponentSpec("Card", listOf(Param("children", PType.Arr(PType.Comp())), Param("variant", PType.Enum(listOf("card", "sunk", "clear")), false, "card"),
                Param("direction", DIR, false, "column"), Param("gap", GAP, false, "m")),
                "Styled container. variant \"card\" (default) | \"sunk\" | \"clear\". Always full width.", "Content"),
            ComponentSpec("CardHeader", listOf(Param("title", PType.Str, false), Param("subtitle", PType.Str, false)), "Header with optional title and subtitle", "Content"),
            ComponentSpec("TextContent", listOf(Param("text", PType.Str), Param("size", PType.Enum(listOf("small", "default", "large", "small-heavy", "large-heavy")), false, "default")),
                "Text block. Supports markdown. \"large-heavy\" for titles.", "Content"),
            ComponentSpec("MarkDownRenderer", listOf(Param("textMarkdown", PType.Str), Param("variant", PType.Enum(listOf("clear", "card", "sunk")), false, "clear")),
                "Renders markdown text (lists, bold, links, tables)", "Content"),
            ComponentSpec("Callout", listOf(Param("variant", PType.Enum(listOf("info", "warning", "error", "success", "neutral"))), Param("title", PType.Str), Param("description", PType.Str, false, "")),
                "Callout banner with variant, title, and description", "Content"),
            ComponentSpec("TextCallout", listOf(Param("variant", PType.Enum(listOf("neutral", "info", "warning", "success", "danger")), false, "neutral"),
                Param("title", PType.Str, false), Param("description", PType.Str, false)), "Text callout with variant, title, and description", "Content", core = false),
            ComponentSpec("Image", listOf(Param("alt", PType.Str), Param("src", PType.Str, false)), "Image with alt text; src may be \"attachment:N\" (the user's Nth photo)", "Content", core = false),
            ComponentSpec("ImageBlock", listOf(Param("src", PType.Str), Param("alt", PType.Str, false)), "Image block", "Content", core = false),
            ComponentSpec("CodeBlock", listOf(Param("language", PType.Str), Param("codeString", PType.Str)), "Syntax-highlighted code block", "Content"),
            // ---- Tables
            ComponentSpec("Table", listOf(Param("columns", PType.Arr(PType.Comp(listOf("Col")))), Param("rows", PType.Arr(PType.Arr(PType.Cell)))), "Data table", "Tables"),
            ComponentSpec("Col", listOf(Param("label", PType.Str), Param("type", PType.Enum(listOf("string", "number", "action")), false, "string")), "Column definition", "Tables"),
            // ---- Charts
            ComponentSpec("BarChart", listOf(Param("labels", PType.Arr(PType.Str)), Param("series", PType.Arr(PType.Comp(listOf("Series")))),
                Param("variant", PType.Enum(listOf("grouped", "stacked")), false, "grouped"), Param("xLabel", PType.Str, false), Param("yLabel", PType.Str, false)),
                "Vertical bars; compare values across categories", "Charts"),
            ComponentSpec("LineChart", listOf(Param("labels", PType.Arr(PType.Str)), Param("series", PType.Arr(PType.Comp(listOf("Series")))),
                Param("variant", PType.Enum(listOf("linear", "natural", "step")), false, "linear"), Param("xLabel", PType.Str, false), Param("yLabel", PType.Str, false)),
                "Lines over categories; trends over time", "Charts"),
            ComponentSpec("AreaChart", listOf(Param("labels", PType.Arr(PType.Str)), Param("series", PType.Arr(PType.Comp(listOf("Series")))),
                Param("variant", PType.Enum(listOf("linear", "natural", "step")), false, "linear"), Param("xLabel", PType.Str, false), Param("yLabel", PType.Str, false)),
                "Filled area under lines; cumulative totals", "Charts", core = false),
            ComponentSpec("HorizontalBarChart", listOf(Param("labels", PType.Arr(PType.Str)), Param("series", PType.Arr(PType.Comp(listOf("Series")))),
                Param("variant", PType.Enum(listOf("grouped", "stacked")), false, "grouped"), Param("xLabel", PType.Str, false), Param("yLabel", PType.Str, false)),
                "Horizontal bars; long labels or rankings", "Charts", core = false),
            ComponentSpec("Series", listOf(Param("category", PType.Str), Param("values", PType.Arr(PType.Num))), "One data series", "Charts"),
            ComponentSpec("PieChart", listOf(Param("slices", PType.Arr(PType.Comp(listOf("Slice")))), Param("variant", PType.Enum(listOf("pie", "donut")), false, "pie")),
                "Part-to-whole proportions", "Charts"),
            ComponentSpec("Slice", listOf(Param("category", PType.Str), Param("value", PType.Num)), "One slice with label and numeric value", "Charts"),
            // ---- Forms
            ComponentSpec("Form", listOf(Param("name", PType.Str), Param("fields", PType.Arr(PType.Comp(listOf("FormControl")))), Param("buttons", PType.Comp(listOf("Buttons")), false)),
                "Form container; submitting sends the values to you as the next user message", "Forms"),
            ComponentSpec("FormControl", listOf(Param("label", PType.Str), Param("input", PType.Comp(listOf("Input", "TextArea", "Select", "Slider", "CheckBoxGroup", "RadioGroup", "SwitchGroup"))),
                Param("hint", PType.Str, false)), "Field with label, input component, and optional hint", "Forms"),
            ComponentSpec("Input", listOf(Param("name", PType.Str), Param("placeholder", PType.Str, false), Param("type", PType.Enum(listOf("text", "email", "password", "number", "url")), false, "text"),
                Param("rules", PType.Arr(PType.Str), false)), "", "Forms"),
            ComponentSpec("TextArea", listOf(Param("name", PType.Str), Param("placeholder", PType.Str, false), Param("rows", PType.Num, false, 3.0), Param("rules", PType.Arr(PType.Str), false)), "", "Forms"),
            ComponentSpec("Select", listOf(Param("name", PType.Str), Param("items", PType.Arr(PType.Comp(listOf("SelectItem")))), Param("placeholder", PType.Str, false), Param("rules", PType.Arr(PType.Str), false)), "", "Forms"),
            ComponentSpec("SelectItem", listOf(Param("value", PType.Str), Param("label", PType.Str)), "Option for Select", "Forms"),
            ComponentSpec("Slider", listOf(Param("name", PType.Str), Param("variant", PType.Enum(listOf("continuous", "discrete")), false, "continuous"), Param("min", PType.Num, false, 0.0),
                Param("max", PType.Num, false, 100.0), Param("step", PType.Num, false), Param("defaultValue", PType.Num, false), Param("rules", PType.Arr(PType.Str), false)), "", "Forms", core = false),
            ComponentSpec("CheckBoxGroup", listOf(Param("name", PType.Str), Param("items", PType.Arr(PType.Comp(listOf("CheckBoxItem")))), Param("rules", PType.Arr(PType.Str), false)), "", "Forms"),
            ComponentSpec("CheckBoxItem", listOf(Param("label", PType.Str), Param("description", PType.Str, false, ""), Param("name", PType.Str, false), Param("defaultChecked", PType.Bool, false, false)), "", "Forms"),
            ComponentSpec("RadioGroup", listOf(Param("name", PType.Str), Param("items", PType.Arr(PType.Comp(listOf("RadioItem")))), Param("defaultValue", PType.Str, false), Param("rules", PType.Arr(PType.Str), false)), "", "Forms", core = false),
            ComponentSpec("RadioItem", listOf(Param("label", PType.Str), Param("description", PType.Str, false, ""), Param("value", PType.Str, false)), "", "Forms", core = false),
            ComponentSpec("SwitchGroup", listOf(Param("items", PType.Arr(PType.Comp(listOf("SwitchItem")))), Param("variant", PType.Enum(listOf("clear", "card", "sunk")), false, "clear")), "Group of switch toggles", "Forms", core = false),
            ComponentSpec("SwitchItem", listOf(Param("label", PType.Str, false), Param("description", PType.Str, false), Param("name", PType.Str, false), Param("value", PType.Str, false),
                Param("checked", PType.Bool, false), Param("defaultChecked", PType.Bool, false, false)), "Individual switch toggle", "Forms", core = false),
            // ---- Buttons & follow-ups
            ComponentSpec("Button", listOf(Param("label", PType.Str), Param("action", PType.Action, false), Param("variant", PType.Enum(listOf("primary", "secondary", "tertiary")), false, "primary"),
                Param("type", PType.Enum(listOf("normal", "destructive")), false, "normal")), "Clickable button. action \"submit:<form>\" submits a form; @ToAssistant(\"text\") sends text as the user's next message", "Buttons"),
            ComponentSpec("Buttons", listOf(Param("buttons", PType.Arr(PType.Comp(listOf("Button")))), Param("direction", DIR, false, "row")), "Group of Button components", "Buttons"),
            ComponentSpec("FollowUpBlock", listOf(Param("items", PType.Arr(PType.Comp(listOf("FollowUpItem"))))), "Clickable follow-up suggestions at the end of a response", "Buttons"),
            ComponentSpec("FollowUpItem", listOf(Param("text", PType.Str)), "Follow-up suggestion; tapping sends text as the user's message", "Buttons"),
            // ---- Data display
            ComponentSpec("ListBlock", listOf(Param("items", PType.Arr(PType.Comp(listOf("ListItem")))), Param("variant", PType.Enum(listOf("number", "image")), false, "number")),
                "A numbered list of items; items with an action are tappable", "Data Display"),
            ComponentSpec("ListItem", listOf(Param("title", PType.Str), Param("subtitle", PType.Str, false), Param("image", PType.Shape("{src: string, alt: string}"), false),
                Param("actionLabel", PType.Str, false), Param("action", PType.Action, false)), "Item in a ListBlock", "Data Display"),
            ComponentSpec("TagBlock", listOf(Param("tags", PType.Arr(PType.Str))), "tags is an array of strings", "Data Display"),
            ComponentSpec("Tag", listOf(Param("text", PType.Str), Param("icon", PType.Str, false), Param("size", PType.Enum(listOf("sm", "md", "lg")), false, "md"),
                Param("variant", PType.Enum(listOf("neutral", "info", "success", "warning", "danger")), false, "neutral")), "Styled tag/badge", "Data Display", core = false),
        ))
    }
}
