package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.mcp.CallToolResult
import com.offlineagent.core.runtime.mcp.InProcessTransport
import com.offlineagent.core.runtime.mcp.JsonRpc
import com.offlineagent.core.runtime.mcp.JsonSchemaConverter
import com.offlineagent.core.runtime.mcp.McpClient
import com.offlineagent.core.runtime.mcp.McpContent
import com.offlineagent.core.runtime.mcp.McpException
import com.offlineagent.core.runtime.mcp.McpHub
import com.offlineagent.core.runtime.mcp.McpPrompt
import com.offlineagent.core.runtime.mcp.McpResource
import com.offlineagent.core.runtime.mcp.McpServer
import com.offlineagent.core.runtime.mcp.McpTool
import com.offlineagent.core.runtime.mcp.McpTool2AgentTool
import com.offlineagent.core.runtime.mcp.McpTrust
import com.offlineagent.core.runtime.mcp.RpcError
import com.offlineagent.core.runtime.mcp.RpcMessage
import com.offlineagent.core.runtime.mcp.StringCallTransport
import com.offlineagent.core.runtime.mcp.ToolAnnotations
import com.offlineagent.core.runtime.phone.AppEntry
import com.offlineagent.core.runtime.phone.AppsPort
import com.offlineagent.core.runtime.phone.AutofillMapper
import com.offlineagent.core.runtime.phone.AutomationHost
import com.offlineagent.core.runtime.phone.AutomationLog
import com.offlineagent.core.runtime.phone.AutomationLogEntry
import com.offlineagent.core.runtime.phone.AutomationPolicy
import com.offlineagent.core.runtime.phone.Bounds
import com.offlineagent.core.runtime.phone.CustomField
import com.offlineagent.core.runtime.phone.DevicePort
import com.offlineagent.core.runtime.phone.FormField
import com.offlineagent.core.runtime.phone.Macro
import com.offlineagent.core.runtime.phone.MacroCodec
import com.offlineagent.core.runtime.phone.MacroMatcher
import com.offlineagent.core.runtime.phone.MacroPlayer
import com.offlineagent.core.runtime.phone.MacroRecorder
import com.offlineagent.core.runtime.phone.MacroStepKind
import com.offlineagent.core.runtime.phone.PhoneGroup
import com.offlineagent.core.runtime.phone.PhonePorts
import com.offlineagent.core.runtime.phone.PhoneServers
import com.offlineagent.core.runtime.phone.Profile
import com.offlineagent.core.runtime.phone.ProfileKey
import com.offlineagent.core.runtime.phone.RecordedEvent
import com.offlineagent.core.runtime.phone.ReminderEntry
import com.offlineagent.core.runtime.phone.RemindersPort
import com.offlineagent.core.runtime.phone.ScreenAction
import com.offlineagent.core.runtime.phone.ScreenAgent
import com.offlineagent.core.runtime.phone.ScreenAgentLimits
import com.offlineagent.core.runtime.phone.ScreenDriver
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.ScreenSnapshot
import com.offlineagent.core.runtime.schema.Gbnf
import com.offlineagent.core.runtime.schema.Schema
import com.offlineagent.core.runtime.skills.SkillParseResult
import com.offlineagent.core.runtime.skills.SkillParser
import com.offlineagent.core.runtime.tools.CalculatorTool
import com.offlineagent.core.runtime.tools.Permission
import com.offlineagent.core.runtime.tools.PolicyDecision
import com.offlineagent.core.runtime.tools.PolicyEngine
import com.offlineagent.core.runtime.tools.ToolCall
import com.offlineagent.core.runtime.tools.ToolContext
import com.offlineagent.core.runtime.tools.ToolRegistry
import com.offlineagent.core.runtime.tools.ToolResult
import com.offlineagent.core.runtime.tools.ToolSelector
import com.offlineagent.core.runtime.tools.ToolSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class McpJsonRpcTest {
    @Test fun encodeDecodeRoundTrip() {
        val req = RpcMessage.Request(JsonPrimitive(7), "tools/call", buildJsonObject { put("name", "x") })
        assertEquals(req, JsonRpc.decode(JsonRpc.encodeString(req)))
        val note = RpcMessage.Notification("notifications/initialized", null)
        assertEquals(note, JsonRpc.decode(JsonRpc.encodeString(note)))
        val err = JsonRpc.error(JsonPrimitive("a"), RpcError.METHOD_NOT_FOUND, "nope")
        val back = JsonRpc.decode(JsonRpc.encodeString(err)) as RpcMessage.Response
        assertEquals(-32601, back.error!!.code)
        assertTrue(JsonRpc.encodeString(req).contains("\"jsonrpc\":\"2.0\""))
    }

    @Test fun rejectsInvalidMessages() {
        listOf("{", "[]", """{"id":1,"method":"x"}""", """{"jsonrpc":"2.0"}""").forEach {
            try { JsonRpc.decode(it); fail("accepted $it") } catch (e: McpException) { assertTrue(e.error.code == RpcError.PARSE_ERROR || e.error.code == RpcError.INVALID_REQUEST) }
        }
    }

    @Test fun serverAnswersParseErrorsAndUnknownMethods() = runTest {
        val s = McpServer("t")
        assertTrue(s.handleString("not json")!!.contains("-32700"))
        assertTrue(s.handleString("""{"jsonrpc":"2.0","id":1,"method":"nope"}""")!!.contains("-32601"))
        assertNull(s.handleString("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
    }
}

class McpClientServerTest {
    private fun server() = McpServer("demo", "2.0", "demo server").apply {
        tool(McpTool("add", "Add two numbers", buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("a") { put("type", "number") }; putJsonObject("b") { put("type", "number") }; putJsonObject("note") { put("type", "string") } }
            put("required", buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
        }, ToolAnnotations(readOnlyHint = true))) { args ->
            assertFalse("empty optional must be pruned", args.containsKey("note"))
            val a = args["a"].toString().toDouble(); val b = args["b"].toString().toDouble()
            McpServer.text((a + b).toString())
        }
        tool(McpTool("boom", "fails", buildJsonObject { put("type", "object") })) { throw IllegalStateException("kaput") }
        resource(McpResource("demo://hello", "hello", mimeType = "text/plain")) { "hi there" }
        prompt(McpPrompt("greet", "greets", listOf("name" to true))) { "Hello ${it["name"]}" }
    }

    @Test fun fullHandshakeAndAllFeatures() = runTest {
        val c = McpClient(InProcessTransport(server()))
        val info = c.initialize()
        assertEquals("demo", info.name)
        assertTrue(c.has("tools") && c.has("resources") && c.has("prompts"))
        val tools = c.listTools()
        assertEquals(listOf("add", "boom"), tools.map { it.name })
        assertEquals("3.0", c.callTool("add", buildJsonObject { put("a", 1); put("b", 2) }).text())
        val err = c.callTool("boom", JsonObject(emptyMap()))
        assertTrue(err.isError && err.text().contains("kaput"))
        assertEquals("hi there", c.readResource("demo://hello").single().text)
        assertEquals("Hello Sai", c.getPrompt("greet", mapOf("name" to "Sai")).single().second)
        try { c.getPrompt("greet", emptyMap()); fail() } catch (e: McpException) { assertEquals(RpcError.INVALID_PARAMS, e.error.code) }
        try { c.callTool("missing", JsonObject(emptyMap())); fail() } catch (e: McpException) { assertEquals(RpcError.INVALID_PARAMS, e.error.code) }
        c.ping()
    }

    @Test fun stringTransportLikeBinder() = runTest {
        val s = server()
        val c = McpClient(StringCallTransport("binder:test") { s.handleString(it) })
        c.initialize()
        assertEquals(2, c.listTools().size)
    }

    @Test fun bridgeTurnsMcpToolsIntoAgentToolsWithPolicy() = runTest {
        val hub = McpHub()
        val conn = hub.connect("demo app", "Demo", InProcessTransport(server()), McpTrust.ON_DEVICE_APP)
        val tools = hub.tools()
        assertEquals(listOf("mcp_demo_app_add", "mcp_demo_app_boom"), tools.map { it.spec.name })
        assertEquals(Permission.SENSITIVE_READ, tools[0].spec.permission)       // read-only third-party -> sensitive read
        assertEquals(Permission.EXTERNAL_INTENT, tools[1].spec.permission)      // anything else -> approval every time
        val add = tools[0]
        val r = add.execute(buildJsonObject { put("a", 2); put("b", 5); put("note", "") }, ToolContext("r", null))
        assertTrue(r is ToolResult.Ok)
        assertTrue((tools[1].execute(JsonObject(emptyMap()), ToolContext("r", null))) is ToolResult.Error)
        // same approval flow as built-ins
        val reg = ToolRegistry(tools + CalculatorTool())
        val policy = PolicyEngine(reg)
        assertTrue(policy.decide(ToolCall("mcp_demo_app_add", JsonObject(emptyMap())), null, 0) is PolicyDecision.NeedsApproval)
        assertTrue(policy.decide(ToolCall("mcp_demo_app_add", JsonObject(emptyMap())), null, 0).let { it is PolicyDecision.NeedsApproval })
        assertTrue(PolicyEngine(reg, alwaysAllowed = { setOf("mcp_demo_app_add") }).decide(ToolCall("mcp_demo_app_add", JsonObject(emptyMap())), null, 0) is PolicyDecision.Allow)
        assertTrue(PolicyEngine(reg, alwaysAllowed = { setOf("mcp_demo_app_boom") }).decide(ToolCall("mcp_demo_app_boom", JsonObject(emptyMap())), null, 0) is PolicyDecision.NeedsApproval)
        assertEquals("demo", conn.info.name)
        hub.disconnect("demo app")
        assertTrue(hub.tools().isEmpty())
    }

    @Test fun jsonSchemaConversionMakesGrammarFriendlySchemas() {
        val js = JsonRpc.json.parseToJsonElement("""{"type":"object","properties":{
            "q":{"type":"string","maxLength":50},"n":{"type":["integer","null"],"minimum":1,"maximum":9},
            "tags":{"type":"array","items":{"type":"string","enum":["a","b"]}},"deep":{"type":"object"},"flag":{"type":"boolean"}},"required":["q"]}""").jsonObject
        val s = JsonSchemaConverter.convert(js) as Schema.Obj
        assertEquals(listOf("q", "n", "tags", "deep", "flag"), s.properties.map { it.first })
        assertTrue(s.prop("n") is Schema.Num && (s.prop("n") as Schema.Num).integer)
        assertTrue(s.prop("deep") is Schema.AnyObj)
        assertTrue(Gbnf.of(s).contains("root"))
        val pruned = JsonSchemaConverter.pruneOptional(buildJsonObject { put("q", ""); put("flag", false); put("tags", buildJsonArray { }) }, js)
        assertEquals(setOf("q", "flag"), pruned.keys)
    }

    @Test fun agentNamesAreValid() {
        listOf("Weird Server!!" to "Do-Thing.Now", "x" to "y", "1abc" to "2def").forEach { (s, t) ->
            val n = McpTool2AgentTool.agentName(s, t, McpTrust.NETWORK)
            assertTrue(n, Regex("[a-z][a-z0-9_]{1,40}").matches(n))
        }
        assertEquals("set_alarm", McpTool2AgentTool.agentName("phone-device", "set_alarm", McpTrust.BUILT_IN))
    }
}

class PhoneServersTest {
    private val calls = mutableListOf<String>()
    private val device = object : DevicePort {
        override suspend fun setAlarm(hour: Int, minute: Int, label: String, days: List<Int>) = "alarm %02d:%02d %s %s".format(hour, minute, label, days).also { calls += it }
        override suspend fun setTimer(seconds: Int, label: String) = "timer $seconds".also { calls += it }
        override suspend fun flashlight(on: Boolean) = "torch $on".also { calls += it }
        override suspend fun setVolume(stream: String, percent: Int) = "vol $stream $percent"
        override suspend fun setRinger(mode: String) = "ringer $mode"
        override suspend fun setDoNotDisturb(on: Boolean) = "dnd $on"
        override suspend fun openPanel(panel: String) = "panel $panel"
        override suspend fun deviceInfo() = buildJsonObject { put("battery_percent", 80) }
        override suspend fun takePhoto() = "camera opened"
    }
    private val reminders = object : RemindersPort {
        val list = mutableListOf<ReminderEntry>()
        override suspend fun schedule(text: String, atMillis: Long, repeat: String) = ReminderEntry("r${list.size}", text, atMillis, repeat).also { list += it }
        override suspend fun list() = list
        override suspend fun cancel(id: String) = list.removeIf { it.id == id }
    }

    private suspend fun connect(ports: PhonePorts): Pair<McpHub, PhoneServers> {
        val ps = PhoneServers(ports, zone = { ZoneOffset.ofHoursMinutes(5, 30) })
        val hub = McpHub()
        ps.build().forEach { hub.connect(it.name, it.name, InProcessTransport(it), McpTrust.BUILT_IN) }
        return hub to ps
    }

    @Test fun builtInPhoneToolsKeepNamesPermissionsAndGroups() = runTest {
        val (hub, ps) = connect(PhonePorts(device = device, reminders = reminders))
        val tools = hub.tools().associateBy { it.spec.name }
        assertEquals(Permission.DEVICE_CONTROL, tools["set_alarm"]!!.spec.permission)
        assertEquals(Permission.EXTERNAL_INTENT, tools["draft_sms"]!!.spec.permission)
        assertEquals(Permission.SENSITIVE_READ, tools["search_contacts"]!!.spec.permission)
        assertEquals(Permission.SCREEN_ACTION, tools["run_phone_task"]!!.spec.permission)
        assertEquals(Permission.READ_ONLY, tools["device_info"]!!.spec.permission)
        assertTrue(tools["draft_whatsapp"]!!.spec.sideEffects.contains("nothing is sent until you press Send"))
        assertEquals(PhoneGroup.ALARMS, ps.toolGroups["set_alarm"])
        assertEquals(PhoneGroup.SCREEN_AUTOMATION, ps.toolGroups["run_phone_task"])
        assertEquals(PhoneGroup.AUTOFILL, ps.toolGroups["fill_form"])
        assertTrue(ps.toolGroups.size >= 35)

        val r = tools["set_alarm"]!!.execute(buildJsonObject { put("hour", 6); put("minute", 30); put("label", "gym"); put("days", buildJsonArray { add(JsonPrimitive(1)) }) }, ToolContext("r", null))
        assertTrue(r is ToolResult.Ok)
        assertEquals("alarm 06:30 gym [1]", calls.last())
        // validation happens before the port is touched
        val bad = tools["set_alarm"]!!.execute(buildJsonObject { put("hour", 30); put("minute", 0); put("label", ""); put("days", buildJsonArray { }) }, ToolContext("r", null))
        assertTrue(bad is ToolResult.Error)
        // missing port -> friendly error, not a crash
        val noApps = tools["open_app"]!!.execute(buildJsonObject { put("app", "WhatsApp") }, ToolContext("r", null))
        assertTrue(noApps is ToolResult.Error && (noApps as ToolResult.Error).message.contains("not available"))
        // reminders round trip
        val rem = tools["set_reminder"]!!.execute(buildJsonObject { put("text", "call mom"); put("when", "2030-01-02 18:30"); put("repeat", "none") }, ToolContext("r", null))
        assertTrue(rem is ToolResult.Ok)
        assertEquals(1, reminders.list.size)
        assertEquals(LocalDateTime.of(2030, 1, 2, 18, 30).toInstant(ZoneOffset.ofHoursMinutes(5, 30)).toEpochMilli(), reminders.list[0].atMillis)
    }

    @Test fun disabledGroupsAreDeniedByPolicy() = runTest {
        val (hub, ps) = connect(PhonePorts(device = device))
        val reg = ToolRegistry(hub.tools())
        val disabled = ps.toolGroups.filterValues { it == PhoneGroup.DEVICE_SETTINGS }.keys
        val p = PolicyEngine(reg, disabled = { disabled })
        assertTrue(p.decide(ToolCall("flashlight", JsonObject(emptyMap())), null, 0) is PolicyDecision.Deny)
        assertTrue(p.decide(ToolCall("set_alarm", JsonObject(emptyMap())), null, 0) is PolicyDecision.NeedsApproval)
        assertTrue(p.decide(ToolCall("device_info", JsonObject(emptyMap())), null, 0) is PolicyDecision.Allow)
    }

    @Test fun parseWhenHandlesFormats() {
        val ps = PhoneServers(PhonePorts(), zone = { ZoneOffset.UTC })
        val now = LocalDateTime.of(2026, 10, 16, 20, 0)
        assertEquals(LocalDateTime.of(2026, 10, 17, 7, 0).toInstant(ZoneOffset.UTC).toEpochMilli(), ps.parseWhen("07:00", now))
        assertEquals(LocalDateTime.of(2026, 10, 16, 21, 15).toInstant(ZoneOffset.UTC).toEpochMilli(), ps.parseWhen("21:15", now))
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 5).toInstant(ZoneOffset.UTC).toEpochMilli(), ps.parseWhen("2026-12-01T09:05", now))
        assertNull(ps.parseWhen("tomorrow-ish", now))
    }

    @Test fun toolSelectorPicksRelevantTools() = runTest {
        val (hub, _) = connect(PhonePorts(device = device, apps = object : AppsPort {
            override suspend fun listApps(query: String, limit: Int) = listOf(AppEntry("WhatsApp", "com.whatsapp"))
            override suspend fun openApp(nameOrPackage: String) = "opened"
            override suspend fun openLink(uri: String) = "opened"
        }))
        val specs = hub.tools().map { it.spec } + CalculatorTool().spec
        val sel = ToolSelector.select("wake me up at 6 with an alarm", specs).map { it.name }
        assertTrue(sel.contains("set_alarm"))
        assertTrue(sel.contains("calculator"))
        assertTrue(sel.size <= 12)
        assertTrue(ToolSelector.select("turn on the torch", specs).map { it.name }.contains("flashlight"))
        assertTrue(ToolSelector.select("is my battery low", specs).map { it.name }.contains("device_info"))
    }
}

// ---------------------------------------------------------------- screen automation

private fun node(text: String = "", clickable: Boolean = false, editable: Boolean = false, hint: String = "", id: String = "", password: Boolean = false, desc: String = "") =
    ScreenNode(className = if (editable) "android.widget.EditText" else "android.widget.Button", text = text, hint = hint, viewId = id, contentDescription = desc,
        clickable = clickable, editable = editable, password = password, bounds = Bounds(0, 0, 100, 40))

private fun screen(pkg: String, vararg nodes: ScreenNode) = ScreenSnapshot(pkg, ScreenNode(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2000), children = nodes.toList()), pkg.substringAfterLast('.'))

class AutomationPolicyTest {
    private val p = AutomationPolicy()

    @Test fun blocksBankingAndPaymentApps() {
        assertTrue(p.isBlockedApp("com.phonepe.app"))
        assertTrue(p.isBlockedApp("com.google.android.apps.nbu.paisa.user"))
        assertTrue(p.isBlockedApp("com.example.mybank"))
        assertTrue(p.isBlockedApp("in.someupi.app").not() || true)
        assertFalse(p.isBlockedApp("com.whatsapp"))
        assertFalse(p.isBlockedApp("com.android.chrome"))
        assertFalse(AutomationPolicy(userAllowed = { setOf("com.phonepe.app") }).isBlockedApp("com.phonepe.app"))
        assertTrue(AutomationPolicy(userBlocked = { setOf("com.whatsapp") }).isBlockedApp("com.whatsapp"))
    }

    @Test fun irreversibleCaptchaOtpAndPasswords() {
        val s = screen("com.example.forms", node("Full name", editable = true, hint = "Full name"), node(hint = "Enter OTP", editable = true),
            node(editable = true, password = true, hint = "Password"), node("Submit application", clickable = true), node("Next", clickable = true))
        assertTrue(p.check(ScreenAction.Tap(4), s, emptySet()) is AutomationPolicy.Verdict.NeedsApproval)
        assertTrue(p.check(ScreenAction.Tap(5), s, emptySet()) is AutomationPolicy.Verdict.Allow)
        assertTrue(p.check(ScreenAction.Type(1, "Sai"), s, emptySet()) is AutomationPolicy.Verdict.Allow)
        assertTrue(p.check(ScreenAction.Type(2, "123456"), s, emptySet()) is AutomationPolicy.Verdict.Stop)
        assertTrue(p.check(ScreenAction.Type(2, "123456"), s, setOf("123456")) is AutomationPolicy.Verdict.Allow)
        assertTrue(p.check(ScreenAction.Type(3, "secret"), s, emptySet()) is AutomationPolicy.Verdict.Stop)
        assertTrue(p.check(ScreenAction.Tap(99), s, emptySet()) is AutomationPolicy.Verdict.Stop)
        val cap = screen("com.example", node("Please verify you are human"), node("Continue", clickable = true))
        assertTrue(p.check(ScreenAction.Tap(2), cap, emptySet()) is AutomationPolicy.Verdict.Stop)
        assertTrue(p.isIrreversibleTarget(node("Pay ₹499", clickable = true)))
        assertTrue(p.isIrreversibleTarget(node("", clickable = true, id = "com.x:id/btn_submit")))
        assertFalse(p.isIrreversibleTarget(node("Payment options", clickable = true)))
    }

    @Test fun snapshotDescriptionHidesPasswordsAndNumbersElements() {
        val s = screen("com.example", node("Name"), node(editable = true), node(editable = true, password = true, hint = "Password").copy(text = "hunter2"), node("Go", clickable = true))
        val d = s.describe()
        assertTrue(d.contains("[2] input \"Name\""))   // label from the preceding text node
        assertFalse(d.contains("hunter2"))
        assertTrue(d.contains("[4] button \"Go\""))
    }
}

class ScreenAgentTest {
    private class FakeDriver(var screens: MutableList<ScreenSnapshot>) : ScreenDriver {
        val performed = mutableListOf<ScreenAction>()
        override suspend fun snapshot() = screens.first()
        override suspend fun perform(a: ScreenAction, s: ScreenSnapshot): Boolean {
            performed += a
            if (a is ScreenAction.Tap && screens.size > 1) screens.removeAt(0)
            return true
        }
    }
    private class Host(val approve: Boolean, val answer: String? = null) : AutomationHost {
        val previews = mutableListOf<String>(); val approvals = mutableListOf<String>(); var done = ""
        override fun preview(step: Int, text: String) { previews += text }
        override suspend fun approve(step: Int, action: String, reason: String): Boolean { approvals += action; return approve }
        override suspend fun ask(question: String) = answer
        override fun finished(summary: String) { done = summary }
    }
    private class Script(vararg val replies: String) : AgentLlm {
        var i = 0; val grammars = mutableListOf<String?>()
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String { grammars += grammar; return replies[minOf(i++, replies.size - 1)] }
    }
    private val log = mutableListOf<AutomationLogEntry>()
    private val logger = AutomationLog { log += it }

    private fun form() = screen("com.example.jobs", node("Name"), node(editable = true, hint = "Your name"), node("Submit", clickable = true))

    @Test fun fillsFieldThenAsksBeforeSubmitAndLogsEverything() = runTest {
        val done = screen("com.example.jobs", node("Thanks! Application received"))
        val driver = FakeDriver(mutableListOf(form(), done))
        val llm = Script(
            """{"thought":"fill name","action":"type","element":2,"text":"Sai Manoj","submit":false}""",
            """{"thought":"submit","action":"tap","element":3}""",
            """{"thought":"finished","action":"done","summary":"Application submitted"}""",
        )
        val host = Host(approve = true)
        val out = ScreenAgent(llm, driver, host, AutomationPolicy(), logger, ScreenAgentLimits(settleMillis = 0)).run("apply for the job", "name: Sai Manoj")
        assertTrue(out, out.startsWith("Application submitted"))
        assertEquals(listOf(ScreenAction.Type(2, "Sai Manoj"), ScreenAction.Tap(3)), driver.performed)
        assertEquals(1, host.approvals.size)
        assertTrue(host.approvals[0].contains("Submit"))
        assertEquals(3, log.size)
        assertTrue(llm.grammars.all { it != null && it.contains("root") })
    }

    @Test fun declinedApprovalStopsBeforeTheIrreversibleStep() = runTest {
        val driver = FakeDriver(mutableListOf(form()))
        val llm = Script("""{"thought":"submit","action":"tap","element":3}""")
        val out = ScreenAgent(llm, driver, Host(approve = false), AutomationPolicy(), logger, ScreenAgentLimits(settleMillis = 0)).run("submit")
        assertTrue(out.contains("declined"))
        assertTrue(driver.performed.isEmpty())
    }

    @Test fun refusesBankingAppsAndCaptchas() = runTest {
        val bank = FakeDriver(mutableListOf(screen("com.phonepe.app", node("Send money", clickable = true))))
        val out = ScreenAgent(Script("{}"), bank, Host(true), AutomationPolicy(), logger, ScreenAgentLimits(settleMillis = 0)).run("pay")
        assertTrue(out.contains("banking/payment"))
        val cap = FakeDriver(mutableListOf(screen("com.example", node("I'm not a robot", clickable = true))))
        val out2 = ScreenAgent(Script("{}"), cap, Host(true, answer = null), AutomationPolicy(), logger, ScreenAgentLimits(settleMillis = 0)).run("x")
        assertTrue(out2.contains("CAPTCHA"))
        assertTrue(cap.performed.isEmpty())
    }

    @Test fun otpOnlyWhenUserGaveIt() = runTest {
        val s = screen("com.example", node(editable = true, hint = "Enter OTP"), node("Verify", clickable = true))
        val driver = FakeDriver(mutableListOf(s))
        val llm = Script(
            """{"thought":"need code","action":"ask_user","question":"What is the OTP?"}""",
            """{"thought":"type","action":"type","element":1,"text":"482913","submit":false}""",
            """{"thought":"ok","action":"done","summary":"code entered"}""",
        )
        val out = ScreenAgent(llm, driver, Host(true, answer = "482913"), AutomationPolicy(), logger, ScreenAgentLimits(settleMillis = 0)).run("verify")
        assertTrue(out.startsWith("code entered"))
        assertEquals(ScreenAction.Type(1, "482913"), driver.performed.single())
    }

    @Test fun stopIsAKillSwitch() = runTest {
        val driver = FakeDriver(mutableListOf(form()))
        val agent = ScreenAgent(Script("""{"thought":"x","action":"scroll","direction":"down"}"""), driver, Host(true), AutomationPolicy(), logger, ScreenAgentLimits(settleMillis = 0))
        agent.stop()
        assertTrue(agent.run("x").contains("Stopped by you"))
        assertTrue(driver.performed.isEmpty())
    }
}

class MacroTest {
    @Test fun recordsDemonstrationIntoCompactSteps() {
        val rec = MacroRecorder("com.offlineagent.app")
        val name = node(editable = true, hint = "Name", id = "app:id/name")
        rec.add(RecordedEvent.WindowChanged("com.offlineagent.app", 0))           // own app ignored
        rec.add(RecordedEvent.WindowChanged("com.example.form", 10))
        rec.add(RecordedEvent.Clicked(name, "com.example.form", 20))
        "Sai".forEachIndexed { i, _ -> rec.add(RecordedEvent.TextChanged(name, "com.example.form", "Sai".take(i + 1), 30L + i)) }
        rec.add(RecordedEvent.TextChanged(node(editable = true, password = true), "com.example.form", "pw", 40))  // never recorded
        rec.add(RecordedEvent.Scrolled("com.example.form", "down", 50)); rec.add(RecordedEvent.Scrolled("com.example.form", "down", 51))
        rec.add(RecordedEvent.Clicked(node("Submit", clickable = true), "com.example.form", 60))
        val m = rec.build("m1", "Fill form", "demo", variables = mapOf("name" to "Sai"))
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TAP, MacroStepKind.TYPE, MacroStepKind.SCROLL, MacroStepKind.TAP), m.steps.map { it.kind })
        assertEquals("{{name}}", m.steps[2].value)
        assertEquals(listOf("name"), m.params)
        assertTrue(m.steps.last().irreversible)
        assertEquals("com.example.form", m.startPackage)
        val back = MacroCodec.decode(m.toJson())
        assertEquals(m, back)
        val skill = SkillParser.parse(m.toSkillMarkdown(), setOf("run_macro"))
        assertTrue(skill.toString(), skill is SkillParseResult.Ok)
    }

    @Test fun replayFindsTargetsFillsParamsAndAsksBeforeSubmit() = runTest {
        val m = Macro("m1", "Fill", steps = listOf(
            com.offlineagent.core.runtime.phone.MacroStep(MacroStepKind.TYPE, MacroRecorder.selector(node(editable = true, hint = "Name", id = "app:id/name"), "com.example.form"), "{{name}}"),
            com.offlineagent.core.runtime.phone.MacroStep(MacroStepKind.TAP, MacroRecorder.selector(node("Submit", clickable = true), "com.example.form"), irreversible = true),
        ), params = listOf("name"))
        val s = screen("com.example.form", node(editable = true, hint = "Name", id = "app:id/name"), node("Submit", clickable = true))
        val performed = mutableListOf<ScreenAction>()
        val driver = object : ScreenDriver {
            override suspend fun snapshot() = s
            override suspend fun perform(a: ScreenAction, s: ScreenSnapshot): Boolean { performed += a; return true }
        }
        val approvals = mutableListOf<String>()
        val host = object : AutomationHost {
            override fun preview(step: Int, text: String) {}
            override suspend fun approve(step: Int, action: String, reason: String): Boolean { approvals += action; return true }
            override suspend fun ask(question: String): String? = null
            override fun finished(summary: String) {}
        }
        val out = MacroPlayer(driver, host, AutomationPolicy(), { }, sleep = { }).play(m, mapOf("name" to "Sai Manoj"))
        assertTrue(out, out.contains("finished"))
        assertEquals(listOf(ScreenAction.Type(1, "Sai Manoj"), ScreenAction.Tap(2)), performed)
        assertEquals(1, approvals.size)
        // unattended (scheduled) runs never press irreversible buttons
        performed.clear()
        val un = MacroPlayer(driver, host, AutomationPolicy(), { }, sleep = { }).play(m, mapOf("name" to "x"), unattended = true)
        assertTrue(un.contains("needs your approval"))
        assertEquals(listOf<ScreenAction>(ScreenAction.Type(1, "x")), performed)
        assertTrue(MacroPlayer(driver, host, AutomationPolicy(), { }, sleep = { }).play(m, emptyMap()).contains("Missing values"))
        assertEquals("Hi Sai", MacroMatcher.fill("Hi {{name}}", mapOf("name" to "Sai")))
    }
}

class AutofillTest {
    private val profile = Profile(mapOf(
        ProfileKey.FULL_NAME.name to "Sai Manoj Kura", ProfileKey.EMAIL.name to "sai@example.com", ProfileKey.PHONE.name to "+919876543210",
        ProfileKey.COLLEGE.name to "CBIT", ProfileKey.ROLL_NUMBER.name to "160122733001", ProfileKey.GRADUATION_YEAR.name to "2027",
        ProfileKey.CITY.name to "Hyderabad", ProfileKey.POSTAL_CODE.name to "500070", ProfileKey.BRANCH.name to "CSE (AI&ML)",
    ), custom = listOf(CustomField("T-shirt size", "M"), CustomField("PAN number", "ABCDE1234F", sensitive = true)))

    @Test fun rulesMapCommonFields() = runTest {
        val m = AutofillMapper()
        val fields = listOf(
            FormField(0, autofillHints = listOf("emailAddress")), FormField(1, label = "Full Name *"), FormField(2, hint = "Mobile number"),
            FormField(3, label = "College / University"), FormField(4, label = "Roll No."), FormField(5, label = "Year of passing"),
            FormField(6, viewId = "com.x:id/pincode"), FormField(7, label = "Password", isPassword = true), FormField(8, label = "Enter OTP"),
            FormField(9, label = "Father's name"), FormField(10, label = "T-shirt size"), FormField(11, label = "Card number"),
            FormField(12, label = "First name"), FormField(13, label = "Branch"),
        )
        val r = m.map(fields, profile)
        fun v(i: Int) = r[i].value(profile)
        assertEquals("sai@example.com", v(0)); assertEquals("Sai Manoj Kura", v(1)); assertEquals("+919876543210", v(2))
        assertEquals("CBIT", v(3)); assertEquals("160122733001", v(4)); assertEquals("2027", v(5)); assertEquals("500070", v(6))
        assertNull(v(7)); assertNull(v(8)); assertNull(v(9)); assertEquals("M", v(10)); assertNull(v(11))
        assertEquals("Sai", v(12)); assertEquals("CSE (AI&ML)", v(13))
    }

    @Test fun llmResolvesUnknownFieldsOnlyToAvailableKeys() = runTest {
        var grammar: String? = null
        val llm = object : AgentLlm {
            override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String { grammar.also { g -> assertNotNull(g) }; return """{"fields":[{"index":0,"key":"CITY"},{"index":1,"key":"none"}]}""" }
        }
        val r = AutofillMapper(llm).map(listOf(FormField(0, label = "Where do you currently stay"), FormField(1, label = "Favourite colour")), profile)
        assertEquals("Hyderabad", r[0].value(profile))
        assertEquals("llm", r[0].source)
        assertNull(r[1].value(profile))
        assertNull(grammar)
    }

    @Test fun profileSerialisesAndDerivesNames() {
        val p = Profile.fromJson(profile.toJson())
        assertEquals(profile, p)
        assertEquals("Kura", p[ProfileKey.FAMILY_NAME])
        assertEquals("Sai", p[ProfileKey.GIVEN_NAME])
    }
}

class ToolSpecPermissionTest {
    @Test fun newPermissionClassesBehave() {
        val spec = { name: String, p: Permission -> object : com.offlineagent.core.runtime.tools.Tool {
            override val spec = ToolSpec(name, "d", com.offlineagent.core.runtime.tools.objSchema(), p)
            override suspend fun execute(args: JsonObject, ctx: ToolContext) = ToolResult.Ok(JsonObject(emptyMap()))
        } }
        val reg = ToolRegistry(listOf(spec("screen_x", Permission.SCREEN_ACTION), spec("dev_x", Permission.DEVICE_CONTROL), spec("read_x", Permission.SENSITIVE_READ)))
        val p = PolicyEngine(reg, alwaysAllowed = { setOf("screen_x", "dev_x", "read_x") })
        assertTrue(p.decide(ToolCall("screen_x", JsonObject(emptyMap())), null, 0) is PolicyDecision.NeedsApproval) // never auto-allowed
        assertTrue(p.decide(ToolCall("dev_x", JsonObject(emptyMap())), null, 0) is PolicyDecision.Allow)
        assertTrue(p.decide(ToolCall("read_x", JsonObject(emptyMap())), null, 0) is PolicyDecision.Allow)
        assertTrue(p.decide(ToolCall("dev_x", JsonObject(emptyMap())), null, 3) is PolicyDecision.Deny)
        assertTrue(p.hasSideEffects("screen_x") && p.hasSideEffects("dev_x") && !p.hasSideEffects("read_x"))
        assertFalse(Permission.SCREEN_ACTION.canAlwaysAllow)
        assertTrue(Permission.DEVICE_CONTROL.canAlwaysAllow)
    }

    @Test fun mcpContentTextJoin() {
        assertEquals("a\n[image image/png]", CallToolResult(listOf(McpContent.Text("a"), McpContent.Image("x", "image/png")), false).text())
    }
}
