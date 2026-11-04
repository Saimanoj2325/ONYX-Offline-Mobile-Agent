package com.offlineagent.core.screen

import com.offlineagent.core.agents.AgentDeps
import com.offlineagent.core.agents.ApprovalGate
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.Blackboard
import com.offlineagent.core.agents.ContextProvider
import com.offlineagent.core.agents.DeviceContext
import com.offlineagent.core.agents.InMemoryTaskStore
import com.offlineagent.core.agents.InferenceQueue
import com.offlineagent.core.agents.MemoryHit
import com.offlineagent.core.agents.MemoryStore
import com.offlineagent.core.agents.Orchestrator
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.agents.Route
import com.offlineagent.core.agents.TaskStatus
import com.offlineagent.core.apps.SettingsPages
import com.offlineagent.core.runtime.agent.AgentLlm
import com.offlineagent.core.runtime.phone.ScreenSnapshot
import com.offlineagent.core.runtime.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ToolResult.ok(): ToolResult.Ok = this as? ToolResult.Ok ?: error("expected Ok, got $this")
private fun ToolResult.errText(): String = (this as? ToolResult.Error)?.message ?: error("expected Error, got $this")
private fun ToolResult.Ok.passed(): Boolean? = (json["subgoal"] as? JsonObject)?.get("passed")?.let { (it as JsonPrimitive).content.toBoolean() }
private fun args(vararg p: Pair<String, String>) = buildJsonObject { p.forEach { (k, v) -> put(k, v) } }

/** Phase 6.1: screen_task (the in-app loop), Settings page allowlist, status banner, and routing end to end. */
class ScreenTaskTest {
    @Test fun typesInTheAppAndStopsBeforeSendingUnlessAsked() = runBlocking {
        val chat = ChatApp()
        val llm = ScriptLlm("Action: type \"hi\" into \"Message\"", "Action: tap \"Send\"")
        val r = Rig(chat, llm = llm)
        val out = r.s.task("type hi to Ravi (stop before sending)", "", 10, "k", JsonObject(emptyMap())).ok()
        assertEquals("hi", chat.input)
        assertEquals(listOf("Hi!", "When will my order arrive?"), chat.messages) // nothing sent
        assertTrue(out.summary, out.summary.contains("Stopped before \"Tap \"Send\"\""))
        assertEquals(true, out.passed())
        assertTrue(r.host.confirms.isEmpty())
        assertTrue(llm.systems.first().startsWith("You operate an Android app step by step"))
        assertTrue(llm.users[1], llm.users[1].contains("1. Type 2 chars into \"Message\""))
    }

    @Test fun sendsOnlyWhenTheGoalSaysSoAndStillConfirms() = runBlocking {
        val chat = ChatApp()
        val r = Rig(chat, llm = ScriptLlm("Action: type \"hi\" into \"Message\"", "Action: tap \"Send\"", "Action: done"))
        val out = r.s.task("send hi to Ravi", "", 10, "k", JsonObject(emptyMap())).ok()
        assertEquals("hi", chat.messages.last())
        assertEquals(1, r.host.confirms.size) // per-step confirmation of Send
        assertEquals(true, out.passed())
        assertTrue(InAppGoal.allowsCommit("send hi to Ravi"))
        assertFalse(InAppGoal.allowsCommit("type hi to Mom, don't send"))
        assertFalse(InAppGoal.allowsCommit("type hi to Mom"))
    }

    @Test fun opensTheAppFirstAndOpeningAloneIsNotSuccess() = runBlocking {
        val shop = ShopApp(); val chat = ChatApp()
        val r = Rig(shop, chat, llm = ScriptLlm("Action: done"))
        val out = r.s.task("type hi to Ravi", "ChatApp", 10, "k", JsonObject(emptyMap())).ok()
        assertTrue(r.phone.performed.contains("launch:com.example.chat"))
        assertEquals(false, out.passed())   // the model said "done" without doing anything in the app
        assertEquals("", chat.input)
        // no model → a clear error, nothing performed
        val r2 = Rig(chat)
        assertTrue(r2.s.task("type hi", "", 5, "k", JsonObject(emptyMap())).errText().startsWith("NO_MODEL"))
    }

    @Test fun serviceNotConnectedIsReportedBeforeOpening() = runBlocking {
        val shop = ShopApp(); val chat = ChatApp()
        val r = Rig(shop, chat, llm = ScriptLlm())
        r.phone.serviceOn = false
        assertTrue(r.s.openApp("ChatApp").errText().startsWith("SERVICE_OFF"))
        assertTrue(r.s.task("type hi", "ChatApp", 5, "k", JsonObject(emptyMap())).errText().startsWith("SERVICE_OFF"))
        assertFalse(r.phone.performed.any { it.startsWith("launch:") })
    }

    @Test fun stuckLoopsAndKillSwitchStop() = runBlocking {
        val chat = ChatApp()
        val r = Rig(chat, llm = ScriptLlm(*Array(10) { "Action: scroll down" }))
        assertTrue(r.s.task("find the first message", "", 10, "k", JsonObject(emptyMap())).errText().startsWith("STUCK"))
        val k = Rig(chat, llm = ScriptLlm("Action: tap \"Video call\""))
        k.killed = true
        assertTrue(k.s.task("x y z", "", 3, "k", JsonObject(emptyMap())).errText().startsWith("STOPPED"))
    }

    // ---------------------------------------------------------------- Settings allowlist

    @Test fun settingsPagesAreAllowAskOrBlock() {
        assertEquals(SettingsPages.Page.DISPLAY, SettingsPages.pageFor("Settings > Display"))
        assertEquals(SettingsPages.Page.DISPLAY, SettingsPages.pageFor("Settings > turn on dark mode"))
        assertEquals(SettingsPages.Page.WIFI, SettingsPages.pageFor("wifi settings"))
        assertEquals(SettingsPages.Page.BLUETOOTH, SettingsPages.pageFor("Settings > connect my earbuds"))
        assertEquals(SettingsPages.Page.SOUND, SettingsPages.pageFor("Settings > Sound & vibration"))
        assertEquals(SettingsPages.Page.DND, SettingsPages.pageFor("Settings > Do Not Disturb"))
        assertEquals(SettingsPages.Page.BATTERY_SAVER, SettingsPages.pageFor("Settings > battery saver"))
        assertEquals(SettingsPages.Page.MOBILE_DATA, SettingsPages.pageFor("Settings > turn off mobile data"))
        assertEquals(SettingsPages.Page.HOTSPOT, SettingsPages.pageFor("Settings > hotspot"))
        assertEquals(SettingsPages.Page.AIRPLANE, SettingsPages.pageFor("Settings > airplane mode"))
        assertEquals(SettingsPages.Page.LOCATION, SettingsPages.pageFor("Settings > location"))
        for (p in listOf("Settings", "Settings > Accounts", "Settings > Developer options", "Settings > Accessibility", "Settings > Apps"))
            assertNull(p, SettingsPages.pageFor(p))
        fun c(vararg t: String) = SettingsPages.classify(t.toList(), t.joinToString(" ").lowercase()).access
        assertEquals(SettingsPages.Access.ALLOW, c("Wi-Fi", "Saved networks"))
        assertEquals(SettingsPages.Access.ALLOW, c("Bluetooth", "Paired devices"))
        assertEquals(SettingsPages.Access.ALLOW, c("Display & brightness", "Dark mode"))
        assertEquals(SettingsPages.Access.ALLOW, c("Sound & vibration"))
        assertEquals(SettingsPages.Access.ALLOW, c("Do Not Disturb"))
        assertEquals(SettingsPages.Access.ALLOW, c("Battery saver"))
        assertEquals(SettingsPages.Access.ASK, c("Location"))
        assertEquals(SettingsPages.Access.ASK, c("Airplane mode"))
        assertEquals(SettingsPages.Access.ASK, c("Mobile network", "Mobile data"))
        assertEquals(SettingsPages.Access.ASK, c("Hotspot & tethering"))
        assertEquals(SettingsPages.Access.BLOCK, c("Settings", "Search settings", "Display"))
        assertEquals(SettingsPages.Access.BLOCK, c("Developer options", "USB debugging"))
        assertEquals(SettingsPages.Access.BLOCK, c("Accessibility"))
        assertEquals(SettingsPages.Access.BLOCK, c("Apps", "Force stop"))
        assertEquals(SettingsPages.Access.BLOCK, c("Google", "Manage your account"))
        assertTrue(SettingsPages.isSettingsRequest("Settings > Display") && SettingsPages.isSettingsRequest("display settings") && !SettingsPages.isSettingsRequest("WhatsApp"))
        // AppBridge (intents) still never opens Settings; only the screen policy is page-gated
        val shared = com.offlineagent.core.apps.AppSafetyPolicy.default()
        assertFalse(shared.checkPackage("com.android.settings").allowed)
        assertTrue(shared.isPageGatedSettings("com.android.settings"))
        assertFalse(shared.isPageGatedSettings("com.android.permissioncontroller"))
    }

    @Test fun settingsHomeIsBlockedAndAllowedPagesWork() = runBlocking {
        val st = SettingsApp()
        val r = Rig(st)
        assertTrue(r.s.openApp("Settings").errText().startsWith("BLOCKED"))           // no page named
        assertTrue(r.s.openApp("Settings > Accounts").errText().startsWith("BLOCKED"))
        assertTrue(r.s.snapshot("skeleton", "").errText().startsWith("BLOCKED"))       // the home page itself
        val open = r.s.openApp("Settings > Display").ok()
        assertTrue(r.phone.performed.contains("settings:display"))
        assertEquals("Display", (open.json["page"] as JsonPrimitive).content)
        r.s.act("", "Dark mode", "tap", "", "", "k1", JsonObject(emptyMap())).ok()
        assertTrue(st.darkMode)
        assertTrue(r.host.confirms.isEmpty())
        assertEquals(Risk.HIGH, r.kit.risk("screen_open_app", args("app" to "Settings > Display")))
        assertEquals(Risk.HIGH, r.kit.risk("screen_act", args("action" to "tap", "target" to "Dark mode")))
        // a blocked page that comes up is refused
        st.page = "developer"
        assertTrue(r.s.act("", "USB debugging", "tap", "", "", "k2", JsonObject(emptyMap())).errText().startsWith("BLOCKED"))
    }

    @Test fun askPagesConfirmEveryStep() = runBlocking {
        val st = SettingsApp()
        val r = Rig(st)
        assertEquals(Risk.CRITICAL, r.kit.risk("screen_open_app", args("app" to "Settings > Location")))
        val open = r.s.openApp("Settings > Location").ok()
        assertTrue(open.summary, open.summary.contains("Every step on this page asks you first"))
        assertEquals(Risk.CRITICAL, r.kit.risk("screen_act", args("action" to "tap", "target" to "Use location")))
        r.s.act("", "Use location", "tap", "", "", "k1", JsonObject(emptyMap())).ok()
        assertEquals(1, r.host.confirms.size)
        assertFalse(st.location)
        r.host.confirmAnswer = false
        assertTrue(r.s.act("", "Use location", "tap", "", "", "k2", JsonObject(emptyMap())).errText().startsWith("DECLINED"))
        assertFalse(st.location)
        // risky controls on an allowed page ask too
        val frame = ScreenFrame(ScreenSnapshot("com.android.settings", withPaths(node(kids = arrayOf(text("Internet"), node("android.widget.Switch", text = "Airplane mode", click = true))))))
        assertTrue(r.policy.confirmReason(frame, frame.snapshot.elements.last().node) != null)
        assertNull(r.policy.confirmReason(frame, frame.snapshot.elements.first().node))
    }

    // ---------------------------------------------------------------- status banner

    @Test fun statusExplainsEachMismatch() {
        fun s(agent: Boolean, enabled: Boolean, connected: Boolean, auto: Boolean = true, group: Boolean = true) =
            ScreenAgentStatus.of(true, agent, enabled, connected, auto, group)
        assertEquals(ScreenAgentStatus.Problem.SERVICE_OFF, s(true, false, false).problem)
        assertEquals(listOf(ScreenAgentStatus.Fix.OPEN_ACCESSIBILITY), s(true, false, false).fixes)
        assertEquals(ScreenAgentStatus.Problem.SERVICE_NOT_RUNNING, s(true, true, false).problem)
        assertTrue(ScreenAgentStatus.Fix.OPEN_BATTERY in s(true, true, false).fixes)
        assertEquals(ScreenAgentStatus.Problem.KILL_SWITCH, s(true, true, true, auto = false).problem)
        assertEquals(ScreenAgentStatus.Problem.GROUP_OFF, s(true, true, true, group = false).problem)
        assertEquals(ScreenAgentStatus.Problem.AGENT_OFF, s(false, true, true).problem)
        assertEquals(listOf(ScreenAgentStatus.Fix.TURN_ON_AGENT), s(false, true, true).fixes)
        assertTrue(s(true, true, true).ok)
        assertTrue(s(false, false, false).ok)
        assertTrue(ScreenAgentStatus.of(false, true, false, false, true, true).ok) // not in this build
    }

    // ---------------------------------------------------------------- routing end to end (fake phone + fake model)

    private class AgentModel : AgentLlm {
        val systems = mutableListOf<String>()
        override suspend fun complete(system: String, user: String, grammar: String?, maxTokens: Int): String {
            systems += system
            return when {
                system.startsWith("You plan") -> """{"steps":[{"tool":"screen_open_app","goal":"open","after":[]}]}"""
                system.startsWith("Check an assistant") -> """{"ok":true,"issue":""}"""
                else -> "Done in the app."
            }
        }
    }

    private fun deps(kit: ScreenToolkit, gw: ScreenGateway, llm: AgentLlm, asked: MutableList<ApprovalRequest>) = AgentDeps(
        queue = InferenceQueue(llm), tools = gw,
        memory = object : MemoryStore { override val enabled = false; override suspend fun recall(query: String, limit: Int) = emptyList<MemoryHit>() },
        context = object : ContextProvider { override suspend fun snapshot(includeScreen: Boolean) = DeviceContext("2026-11-04T21:00", "Asia/Kolkata", online = true) },
        approvals = kit.approvals.wrap(ApprovalGate { r -> asked += r; true }),
        network = { true }, outbox = { _, _, _, _ -> "x" }, audit = { _, _, _, _, _ -> }, store = InMemoryTaskStore(),
    )

    @Test fun openWhatsAppAndTypeHiToMomRunsScreenOpenThenScreenSteps() = runBlocking {
        val shop = ShopApp(); val chat = ChatApp()
        val rig = Rig(shop, chat, llm = ScriptLlm("Action: type \"hi\" into \"Message\"", "Action: tap \"Send\""))
        val gw = ScreenGateway(rig.kit)
        val asked = mutableListOf<ApprovalRequest>()
        val board = Blackboard("e1", "open ChatApp and type hi to Ravi (stop before sending)")
        val cp = Orchestrator(deps(rig.kit, gw, AgentModel(), asked)).run(board.request, board)
        assertEquals(TaskStatus.COMPLETED, cp.status)
        assertEquals(Route.SCREEN, cp.route)
        assertEquals(listOf("screen_open_app", "screen_task"), gw.calls)
        assertEquals("hi", chat.input)
        assertEquals(2, chat.messages.size)                    // not sent
        assertEquals(1, asked.size)                            // one "operate ChatApp" scope covers both steps
        assertTrue(asked.single().title.contains("operate ChatApp"))
    }

    @Test fun openSettingsDisplayAndTurnOnDarkModeEndToEnd() = runBlocking {
        val st = SettingsApp(); val shop = ShopApp()
        val rig = Rig(shop, st, llm = ScriptLlm("Action: tap \"Dark mode\"", "Action: done"))
        val gw = ScreenGateway(rig.kit)
        val board = Blackboard("e2", "open Settings > Display and turn on dark mode")
        val cp = Orchestrator(deps(rig.kit, gw, AgentModel(), mutableListOf())).run(board.request, board)
        assertEquals(Route.SCREEN, cp.route)
        assertEquals(listOf("screen_open_app", "screen_task"), gw.calls)
        assertTrue(rig.phone.performed.contains("settings:display"))
        assertTrue(st.darkMode)
        assertEquals(TaskStatus.COMPLETED, cp.status)
    }
}
