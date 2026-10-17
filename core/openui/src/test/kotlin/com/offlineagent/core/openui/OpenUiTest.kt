package com.offlineagent.core.openui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenUiParserTest {
    private val resolver = OpenUiResolver()

    private val dashboard = """
        root = Stack([header, layout], "column", "l")
        header = TextContent("Revenue Dashboard", "large-heavy")
        layout = Stack([metricCard, chartCard], "row", "l", "stretch", "start", true)
        metricCard = Card([metricHeader, metricDelta], "card")
        metricHeader = CardHeader("Total Revenue", "Last 6 months")
        metricDelta = TextCallout("success", "Up 8.4%", "Compared to the previous 6 months")
        chartCard = Card([CardHeader("Monthly Revenue"), revenueChart])
        revenueChart = BarChart(monthLabels, [revenueSeries], "grouped", "Month", "Revenue (USD)")
        monthLabels = ["Oct", "Nov", "Dec"]
        revenueSeries = Series("Revenue", [198000, 205000, 214000])
    """.trimIndent()

    @Test fun parsesLiteralsAndCalls() {
        val p = OpenUiParser.parse("""a = Foo("x\"y\n", -1.5e2, true, null, [1, 2], {k: "v", "q": 3}, @ToAssistant("hi"), ${'$'}flag, other)""")
        val call = p.statements.single().value as OuValue.Call
        assertEquals("Foo", call.name)
        assertEquals(OuValue.Str("x\"y\n"), call.args[0])
        assertEquals(OuValue.Num(-150.0), call.args[1])
        assertEquals(OuValue.Bool(true), call.args[2])
        assertEquals(OuValue.Null, call.args[3])
        assertEquals(OuValue.Arr(listOf(OuValue.Num(1.0), OuValue.Num(2.0))), call.args[4])
        assertEquals(OuValue.Obj(mapOf("k" to OuValue.Str("v"), "q" to OuValue.Num(3.0))), call.args[5])
        assertEquals(OuValue.At("ToAssistant", listOf(OuValue.Str("hi"))), call.args[6])
        assertEquals(OuValue.StateRef("\$flag"), call.args[7])
        assertEquals(OuValue.Ref("other"), call.args[8])
        assertTrue(p.invalid.isEmpty())
    }

    @Test fun resolvesForwardReferencesAndDefaults() {
        val r = resolver.resolve(OpenUiParser.parse(dashboard))
        val root = r.root!!
        assertEquals("Stack", root.type)
        assertEquals("l", root.str("gap"))
        val layout = root.nodes("children")[1]
        assertEquals("row", layout.str("direction"))
        assertEquals(true, layout.bool("wrap"))
        val chart = layout.nodes("children")[1].nodes("children")[1]
        assertEquals("BarChart", chart.type)
        assertEquals(listOf("Oct", "Nov", "Dec"), chart.list("labels"))
        assertEquals(listOf(198000.0, 205000.0, 214000.0), chart.nodes("series")[0].list("values"))
        assertEquals("Revenue (USD)", chart.str("yLabel"))
        // CardHeader(title) only: subtitle optional, null
        assertNull(layout.nodes("children")[1].nodes("children")[0].str("subtitle"))
        assertTrue(r.errors.filter { !it.warning }.toString(), r.errors.none { !it.warning })
    }

    @Test fun lastDefinitionWinsAndMergePatches() {
        val p = OpenUiParser.parse("root = Stack([t])\nt = TextContent(\"one\")\nt = TextContent(\"two\")")
        assertEquals("two", resolver.resolve(p).root!!.nodes("children")[0].str("text"))
        val merged = p.merge(OpenUiParser.parse("t = TextContent(\"three\")\nextra = TextContent(\"unused\")"))
        assertEquals("three", resolver.resolve(merged).root!!.nodes("children")[0].str("text"))
        assertTrue(merged.toSource().startsWith("root = Stack([t])"))
    }

    @Test fun streamingRendersIncrementallyAtEveryPrefix() {
        val sp = OpenUiStreamParser()
        var sawShellBeforeData = false
        var maxNodes = 0
        // Feed in small chunks, as tokens arrive; every snapshot must resolve without throwing.
        dashboard.chunked(7).forEach { chunk ->
            sp.append(chunk)
            val r = resolver.resolve(sp.snapshot(), streaming = true)
            val n = r.root?.walk()?.count() ?: 0
            if (r.root != null && sp.text.length < dashboard.length / 3) sawShellBeforeData = true
            assertTrue("node count must not shrink much while streaming", n >= maxNodes - 2)
            maxNodes = maxOf(maxNodes, n)
        }
        assertTrue(sawShellBeforeData)
        val final = resolver.resolve(sp.finish())
        assertEquals(resolver.resolve(OpenUiParser.parse(dashboard)).root, final.root)
    }

    @Test fun partialLineIsClosedVirtually() {
        val sp = OpenUiStreamParser().append("root = Stack([title, tbl])\ntitle = TextContent(\"Hel")
        val r = resolver.resolve(sp.snapshot(), streaming = true)
        assertEquals("Hel", r.root!!.nodes("children")[0].str("text"))
        // Partial table: columns arrived, rows not yet
        sp.append("lo\")\ntbl = Table([Col(\"A\"), Col(\"B\")], [[\"x\", 1], [\"y\"")
        val t = resolver.resolve(sp.snapshot(), streaming = true).root!!.nodes("children")[1]
        assertEquals("Table", t.type)
        assertEquals(2, t.nodes("columns").size)
        assertEquals(listOf("x", 1.0), t.list("rows")[0])
        assertEquals(listOf("y"), t.list("rows")[1])
    }

    @Test fun invalidLinesDegradeAndNeverCrash() {
        val src = """
            Here is your plan:
            root = Stack([title, broken, missing, unknown])
            title = TextContent("Plan", "large-heavy")
            broken = Card([TextContent("Day 1 — beach"), ))
            unknown = FancyWidget("Shown as text", 3)
            dangling = TextContent("never referenced")
        """.trimIndent()
        val p = OpenUiParser.parse(src)
        assertEquals(1, p.invalid.count { it.assignedName == "broken" })
        val r = resolver.resolve(p)
        val kids = r.root!!.nodes("children")
        assertEquals(listOf("TextContent", "TextContent", "TextContent"), kids.map { it.type })
        assertEquals("Day 1 — beach", kids[1].str("text")) // broken statement → its string literals
        assertEquals("Shown as text", kids[2].str("text")) // unknown component → text
        assertTrue(r.errors.any { it.code == "unknown-component" })
        assertTrue(r.errors.any { it.code == "unresolved" && it.statement == "missing" })
        assertEquals(listOf("Here is your plan:"), r.proseLines)
        // Garbage of every kind: never throws
        listOf("", "=", "a =", "a = (", "a = Foo(\"", "root = Stack([a,,b])", "x = [1, 2", "\"\"\"", "root = @", "root = Stack([root])",
            "root = Stack([a])\na = Stack([root])", "a = 1e", "a = -", "a = {k", "a = Foo(k: 1, j = \"2\")")
            .forEach { s -> resolver.resolve(OpenUiParser.parse(s)); resolver.resolve(OpenUiStreamParser().append(s).snapshot(), true) }
    }

    @Test fun cycleIsReportedNotInfinite() {
        val r = resolver.resolve(OpenUiParser.parse("root = Stack([a])\na = Card([b])\nb = Card([a])"))
        assertTrue(r.errors.any { it.message.contains("circular") })
    }

    @Test fun missingRequiredDropsComponentWhenFinished() {
        val r = resolver.resolve(OpenUiParser.parse("root = Stack([c, t])\nc = Callout()\nt = TextContent(\"ok\")"))
        assertEquals(listOf("TextContent"), r.root!!.nodes("children").map { it.type })
        assertTrue(r.errors.any { it.code == "missing-required" })
    }

    @Test fun wrongEnumFallsBackToDefault() {
        val n = resolver.resolve(OpenUiParser.parse("root = Stack([t], \"diagonal\")\nt = TextContent(\"x\", \"huge\")")).root!!
        assertEquals("column", n.str("direction"))
        assertEquals("default", n.nodes("children")[0].str("size"))
    }

    @Test fun noRootFallsBackToFirstStack() {
        val r = resolver.resolve(OpenUiParser.parse("title = TextContent(\"T\")\npage = Stack([title])"))
        assertEquals("Stack", r.root!!.type)
        assertTrue(r.errors.any { it.code == "no-root" && it.warning })
    }

    @Test fun namedArgsAndAliasesAreTolerated() {
        val n = resolver.resolve(OpenUiParser.parse("root = Stack([Text(text: \"hi\"), Heading(\"H\", \"sub\")])")).root!!
        assertEquals(listOf("TextContent", "CardHeader"), n.nodes("children").map { it.type })
        assertEquals("hi", n.nodes("children")[0].str("text"))
    }

    @Test fun everyComponentResolves() {
        val src = """
            root = Stack([tabs, acc, steps, sep, card, md, cal, tcal, img, imgb, code, tbl, bar, line, area, hbar, pie, form, btns, fu, list, tags, tag])
            tabs = Tabs([TabItem("a", "A", [TextContent("in tab")]), TabItem("b", "B", [TextContent("b")])])
            acc = Accordion([AccordionItem("x", "X", [TextContent("acc")])])
            steps = Steps([StepsItem("One", "do one"), StepsItem("Two")])
            sep = Separator()
            card = Card([CardHeader("Title", "Sub")], "sunk")
            md = MarkDownRenderer("**bold**")
            cal = Callout("warning", "Careful", "Hot")
            tcal = TextCallout("danger", "Stop")
            img = Image("A cat", "attachment:1")
            imgb = ImageBlock("attachment:2", "Dog")
            code = CodeBlock("kotlin", "fun main() {}")
            tbl = Table([Col("Name"), Col("Score", "number")], [["A", 1], ["B", 2.5]])
            bar = BarChart(["x", "y"], [Series("s", [1, 2])], "stacked")
            line = LineChart(["x", "y"], [Series("s", [1, 2])])
            area = AreaChart(["x", "y"], [Series("s", [1, 2])])
            hbar = HorizontalBarChart(["x", "y"], [Series("s", [1, 2])])
            pie = PieChart([Slice("a", 30), Slice("b", 70)], "donut")
            form = Form("f", [FormControl("Name", Input("name")), FormControl("Bio", TextArea("bio")), FormControl("Pick", Select("pick", [SelectItem("1", "One")])), FormControl("Level", Slider("level", "discrete", 1, 5, 1, 3)), FormControl("Opts", CheckBoxGroup("opts", [CheckBoxItem("A", "a", "a")])), FormControl("R", RadioGroup("r", [RadioItem("X", "x", "x")])), FormControl("S", SwitchGroup([SwitchItem("Dark", "d", "dark")]))])
            btns = Buttons([Button("Go", @ToAssistant("go now")), Button("Link", @OpenUrl("https://example.com"), "secondary"), Button("Many", Action([@ToAssistant("a"), @Set(${'$'}x, 1)]))])
            fu = FollowUpBlock([FollowUpItem("Tell me more")])
            list = ListBlock([ListItem("Item", "sub", {src: "attachment:1", alt: "pic"}, "Open", @ToAssistant("open item"))])
            tags = TagBlock(["a", "b"])
            tag = Tag("New", null, "sm", "success")
        """.trimIndent()
        val r = resolver.resolve(OpenUiParser.parse(src))
        val types = r.root!!.walk().map { it.type }.toSet()
        val expected = OpenUiLibrary.DEFAULT.names.toSet()
        assertEquals("every library component must resolve; errors=${r.errors}", expected - types, emptySet<String>())
        val buttons = r.root.walk().filter { it.type == "Button" }.toList()
        assertEquals(OuAction.ToAssistant("go now"), buttons[0].action("action"))
        assertEquals(OuAction.OpenUrl("https://example.com"), buttons[1].action("action"))
        assertTrue(buttons[2].action("action") is OuAction.Many)
        val li = r.root.walk().first { it.type == "ListItem" }
        assertEquals(mapOf("src" to "attachment:1", "alt" to "pic"), li.props["image"])
        assertEquals(30.0, r.root.walk().first { it.type == "Slice" }.num("value"))
    }

    @Test fun stringActionsFollowOpenUiConventions() {
        assertEquals(OuAction.Submit("contact"), OuAction.fromString("submit:contact"))
        assertEquals(OuAction.ToAssistant("Cancel"), OuAction.fromString("action:cancel_contact", "Cancel"))
        assertEquals(OuAction.ToAssistant("Explain more"), OuAction.fromString("Explain more"))
        val b = resolver.resolve(OpenUiParser.parse("root = Stack([Button(\"Retry\")])")).root!!.nodes("children")[0]
        assertEquals(OuAction.ToAssistant("Retry"), b.action("action"))
    }
}

class OpenUiMessageTest {
    @Test fun plainMarkdownHasNoProgram() {
        val s = OpenUiMessage.split("# Title\n\nSome **text**\n\n```kotlin\nval x = Foo(1)\n```")
        assertEquals(1, s.size)
        assertTrue(s[0] is Segment.Markdown)
        assertFalse(OpenUiMessage.hasProgram("Hello! x = 1 is an equation."))
    }

    @Test fun fencedProgramBetweenProse() {
        val text = "Here you go:\n\n```openui-lang\nroot = Stack([t])\nt = TextContent(\"Hi\")\n```\n\nAnything else?"
        val s = OpenUiMessage.split(text)
        assertEquals(3, s.size)
        assertEquals("Here you go:", (s[0] as Segment.Markdown).text)
        assertEquals(Segment.Program("root = Stack([t])\nt = TextContent(\"Hi\")", true), s[1])
        assertEquals("Anything else?", (s[2] as Segment.Markdown).text)
        assertEquals("Here you go:\n\n## Hi\n\nAnything else?".replace("## Hi", "Hi"), OpenUiMessage.toPlainText(text))
    }

    @Test fun openFenceWhileStreamingIsLiveProgram() {
        val s = OpenUiMessage.split("Sure.\n```openui-lang\nroot = Stack([t", streaming = true)
        assertEquals(Segment.Program("root = Stack([t", false), s[1])
        val done = OpenUiMessage.split("Sure.\n```openui-lang\nroot = Stack([t", streaming = false)
        assertTrue((done[1] as Segment.Program).closed)
    }

    @Test fun bareProgramDetected() {
        assertTrue(OpenUiMessage.looksLikeBareProgram("root = Stack([a])\na = TextContent(\"x\")"))
        assertTrue(OpenUiMessage.looksLikeBareProgram("root = St"))
        assertFalse(OpenUiMessage.looksLikeBareProgram("The root = cause of this is…\nmore prose"))
        assertEquals(1, OpenUiMessage.split("root = Stack([a])\na = TextContent(\"x\")").size)
    }

    @Test fun plainTextOfTableAndChart() {
        val t = OpenUiMessage.toPlainText("```openui-lang\nroot = Stack([Table([Col(\"A\"), Col(\"B\")], [[\"x\", 1]]), BarChart([\"m\"], [Series(\"s\", [5])])])\n```")
        assertTrue(t, t.contains("| A | B |") && t.contains("| x | 1 |") && t.contains("s: m 5"))
    }
}

class OpenUiFormTest {
    private val src = """
        root = Stack([form])
        form = Form("trip", [city, email, days, prefs, level, notes, pw], btns)
        city = FormControl("City", Input("city", "e.g. Goa", "text", ["required", "minLength:2"]))
        email = FormControl("Email", Input("email", "you@x.com", "email", ["email"]))
        days = FormControl("Days", Select("days", [SelectItem("2", "2 days"), SelectItem("4", "4 days")], "Pick", ["required"]))
        prefs = FormControl("Likes", CheckBoxGroup("likes", [CheckBoxItem("Beaches", "", "beach", true), CheckBoxItem("Food", "", "food")]))
        level = FormControl("Budget", Slider("budget", "discrete", 1, 5, 1, 3))
        notes = FormControl("Notes", TextArea("notes", "Anything else", 3, ["maxLength:20"]))
        pw = FormControl("PIN", Input("pin", "", "password", ["numeric"]))
        btns = Buttons([Button("Plan", "submit:trip", "primary")])
    """.trimIndent()

    private val form = OpenUiResolver().resolve(OpenUiParser.parse(src)).root!!.nodes("children")[0]

    @Test fun fieldsDefaultsAndValidation() {
        val fields = FormModel.fields(form)
        assertEquals(listOf("city", "email", "days", "likes", "budget", "notes", "pin"), fields.map { it.name })
        val init = FormModel.initialValues(fields)
        assertEquals(setOf("beach"), init["likes"])
        assertEquals(3.0, init["budget"])
        val errs = FormModel.validate(fields, init + mapOf("email" to "bad", "notes" to "x".repeat(25), "pin" to "12a"))
        assertEquals(setOf("city", "email", "days", "notes", "pin"), errs.keys)
        assertTrue(FormModel.validate(fields, init + mapOf("city" to "Goa", "days" to "4", "pin" to "1234")).isEmpty())
        assertEquals("At least 2 characters", FormModel.validate(fields, init + mapOf("city" to "G", "days" to "2"))["city"])
    }

    @Test fun submitRoundTrip() {
        val fields = FormModel.fields(form)
        val values = FormModel.initialValues(fields) + mapOf("city" to "Goa", "days" to "4", "likes" to setOf("beach", "food"), "notes" to "veg\nfood", "pin" to "1234")
        val msg = FormSubmission.toUserMessage("trip", fields, values)
        assertTrue(msg, msg.startsWith("Form submitted: trip\n- City [city]: Goa"))
        assertTrue(msg.contains("- Days [days]: 4 days"))
        assertTrue(msg.contains("- Likes [likes]: Beaches, Food"))
        assertTrue(msg.contains("- PIN [pin]: ••••")) // passwords are never echoed into the chat
        val parsed = FormSubmission.parse(msg)!!
        assertEquals("trip", parsed.form)
        assertEquals("Goa", parsed.values["city"])
        assertEquals("veg\nfood", parsed.values["notes"])
        assertEquals("3", parsed.values["budget"])
        assertNull(FormSubmission.parse("hello"))
        val submit = form.node("buttons")!!.nodes("buttons")[0].action("action")
        assertEquals(OuAction.Submit("trip"), submit)
    }
}

class OpenUiPromptTest {
    @Test fun signaturesMatchOpenUiFormat() {
        val p = OpenUiPrompt.generate()
        assertTrue(p.contains("Stack(children: Component[], direction?: \"row\" | \"column\", gap?: \"none\" | \"xs\" | \"s\" | \"m\" | \"l\" | \"xl\" | \"2xl\""))
        assertTrue(p.contains("Table(columns: Col[], rows: string | number | boolean[][]) — Data table") || p.contains("Table(columns: Col[], rows: (string | number | boolean)[][])") || p.contains("Table(columns: Col[], rows:"))
        assertTrue(p.contains("Series(category: string, values: number[]) — One data series"))
        assertTrue(p.contains("```openui-lang"))
        for (c in OpenUiLibrary.DEFAULT.components) assertTrue("missing ${c.name}", p.contains(c.name + "("))
        // Each group is printed once
        assertEquals(1, Regex("#### Charts").findAll(p).count())
    }

    @Test fun compactPromptIsSmallerAndOnlyCore() {
        val full = OpenUiPrompt.generate()
        val compact = OpenUiPrompt.generate(OpenUiLibrary.DEFAULT.compact())
        assertTrue(compact.length < full.length * 0.9)
        assertFalse(compact.contains("Accordion("))
        assertTrue(compact.contains("BarChart("))
        // Examples only use available components
        OpenUiPrompt.examples(OpenUiLibrary.DEFAULT.compact()).forEach { (_, code) ->
            val r = OpenUiResolver(OpenUiLibrary.DEFAULT.compact()).resolve(OpenUiParser.parse(code))
            assertNotNull(r.root); assertTrue(r.errors.none { !it.warning })
        }
    }

    @Test fun programModeHasNoFences() {
        val p = OpenUiPrompt.generate(o = OpenUiPrompt.Options(mode = OpenUiPrompt.Mode.PROGRAM))
        assertFalse(p.contains("```"))
        assertTrue(p.contains("ALWAYS start with root = Stack"))
    }

    @Test fun grammarIsWellFormed() {
        val g = OpenUiGrammar.gbnf()
        val rules = g.lines().filter { it.isNotBlank() }.map { it.substringBefore("::=").trim() }
        assertEquals(rules.size, rules.toSet().size)
        assertEquals("root", rules.first())
        // Every referenced rule is defined
        val defined = rules.toSet()
        val body = g.lines().joinToString(" ") { it.substringAfter("::=") }.replace(Regex("\"(\\\\.|[^\"\\\\])*\""), " ").replace(Regex("\\[[^]]*]"), " ")
        Regex("[a-z][a-z-]*").findAll(body).map { it.value }.forEach { assertTrue("undefined rule $it", it in defined) }
        assertTrue(g.contains("\"BarChart\"") && g.contains("\"FollowUpBlock\""))
    }
}
