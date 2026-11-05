package com.offlineagent.core.screen

import com.offlineagent.core.agents.ApprovalGate
import com.offlineagent.core.agents.ApprovalRequest
import com.offlineagent.core.agents.PromptAnswer
import com.offlineagent.core.agents.PromptReply
import com.offlineagent.core.agents.Risk
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.ScreenSnapshot
import com.offlineagent.core.runtime.tools.ToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun ToolResult.ok(): ToolResult.Ok = this as? ToolResult.Ok ?: error("expected Ok, got $this")
private fun ToolResult.errText(): String = (this as? ToolResult.Error)?.message ?: error("expected Error, got $this")

/** A login / OTP / UPI screen for hand-off tests. */
private class GateApp(var kind: String) : FakeApp("com.example.gate", "Gate") {
    override fun tree(): ScreenNode = when (kind) {
        "password" -> node("android.widget.FrameLayout", kids = arrayOf(text("Sign in"), node("android.widget.EditText", hint = "Email", edit = true, click = true),
            node("android.widget.EditText", hint = "Password", edit = true, click = true, password = true), button("Log in")))
        "otp_hi" -> node("android.widget.FrameLayout", kids = arrayOf(text("ओटीपी दर्ज करें"), node("android.widget.EditText", hint = "", edit = true, click = true), button("जारी रखें")))
        "upi_te" -> node("android.widget.FrameLayout", kids = arrayOf(text("యూపీఐ పిన్ నమోదు చేయండి"), node("android.widget.EditText", hint = "", edit = true, click = true)))
        "captcha_hi" -> node("android.widget.FrameLayout", kids = arrayOf(text("मैं रोबोट नहीं हूँ"), button("Verify")))
        "shop" -> node("android.widget.FrameLayout", kids = arrayOf(text("Payment methods"), button("Add card"), button("Your orders")))
        else -> node("android.widget.FrameLayout", kids = arrayOf(text("Home"), button("Next")))
    }
    override fun perform(n: ScreenNode, op: NodeOp, text: String) = true
}

class ScreenReliabilityTest {
    private fun view(app: FakeApp) = ScreenView("t", ScreenFrame(ScreenSnapshot(app.pkg, withPaths(app.tree()), app.label), app.secure))

    @Test fun handoffDetectorCoversPasswordOtpUpiCaptchaInThreeLanguages() {
        assertEquals(HandoffKind.PASSWORD, HandoffDetector.check(view(GateApp("password")))?.kind)
        assertEquals(HandoffKind.OTP, HandoffDetector.check(view(GateApp("otp_hi")))?.kind)
        assertEquals(HandoffKind.PAYMENT, HandoffDetector.check(view(GateApp("upi_te")))?.kind)
        assertEquals(HandoffKind.CAPTCHA, HandoffDetector.check(view(GateApp("captcha_hi")))?.kind)
        assertNull("'Payment methods' in a shop is not a payment screen", HandoffDetector.check(view(GateApp("shop"))))
        assertNull(HandoffDetector.check(view(GateApp("home"))))
        assertEquals(HandoffKind.SECURE, HandoffDetector.check(view(GateApp("home").also { it.secure = true }))?.kind)
        // the safety policy also hands UPI PIN screens to the user
        assertTrue(DefaultScreenSafetyPolicy("com.onyx.agent", shared = null).screenVerdict(view(GateApp("upi_te")).frame) is SafetyVerdict.TakeOver)
    }

    @Test fun screenTaskHandsOffAndSaysYourTurn() = runBlocking {
        val gate = GateApp("password")
        val r = Rig(gate, llm = ScriptLlm("Action: tap \"Log in\""))
        r.host.takeOverAnswer = false
        val e = r.s.task("log in", "", 5, "k", JsonObject(emptyMap())).errText()
        assertTrue(e, e.startsWith("TAKEN_OVER"))
        assertTrue(r.host.takeOvers.single().contains("a password"))
        assertTrue(r.phone.performed.isEmpty())
        assertTrue(r.s.lastTaskReport!!.render().contains("Hand-offs: PASSWORD"))
    }

    @Test fun stableRefsSurviveARereadButSendNeverDoes() = runBlocking {
        val chat = ChatApp()
        val r = Rig(chat)
        r.s.snapshot("full", "")
        val entry = Grounding.find(r.s.latest!!, "Message", "input").first().element
        chat.messages += "New message arrived" // screen changed behind our back
        val ok = r.s.act("@e${entry.id}", "", "type", "hello", "", "k1", JsonObject(emptyMap())).ok()
        assertTrue(ok.summary, ok.summary.startsWith("Did: Type"))
        assertEquals("hello", chat.input)
        assertEquals(1, r.s.relocations)
        // irreversible targets always need a fresh look (existing STALE rule)
        r.s.snapshot("full", "")
        val send = Grounding.unique(r.s.latest!!, "Send")!!
        chat.messages += "another"
        assertTrue(r.s.act("@e${send.id}", "", "tap", "", "", "k2", JsonObject(emptyMap())).errText().startsWith("STALE_REF"))
    }

    @Test fun actScrollsToFindANamedTarget() = runBlocking {
        val wa = FakeWhatsApp((1..12).map { "Friend $it" }.toMutableList().also { it.add(9, "Mom") })
        val r = Rig(wa)
        val out = r.s.act("", "Mom", "tap", "", "", "k", JsonObject(emptyMap())).ok()
        assertTrue(out.summary, out.summary.contains("Mom"))
        assertEquals("Mom", wa.openChat)
        assertTrue(r.s.scrolls > 0)
    }

    @Test fun fastStepsDoNamedTargetsWithoutTheModel() = runBlocking {
        val wa = FakeWhatsApp()
        val llm = ScriptLlm()
        val r = Rig(wa, llm = llm)
        val out = r.s.task("open chat Mom and type 'hi' into Message", "", 8, "k", JsonObject(emptyMap())).ok()
        assertEquals("Mom", wa.openChat)
        assertEquals("hi", wa.input)
        assertEquals(0, llm.users.size)                          // no model call at all
        assertEquals(2, (out.json["fast_steps"] as JsonPrimitive).content.toInt())
        assertTrue(r.s.lastTaskReport!!.render().contains("fast step"))
        // parsing: commits are never fast steps; unquoted typing is left to the model
        assertEquals(listOf("tap"), FastSteps.parse("tap Archived and send it").map { it.action })
        assertTrue(FastSteps.parse("type hi to Ravi (stop before sending)").isEmpty())
        assertTrue(FastSteps.parse("send hi to Ravi").isEmpty())
        assertTrue(FastSteps.parse("open WhatsApp", "WhatsApp").isEmpty())
    }

    @Test fun compactScreenMergesListRows() {
        val v = view(FakeWhatsApp())
        val t = CompactScreen.render(v, null)
        assertTrue(t, t.contains("row \"Ravi\" · \"secret message to Ravi\""))
        assertTrue(t, t.contains("(list can scroll"))
        assertTrue(t.length < v.full().length)
    }

    @Test fun guardsWarnThenStop() {
        val g = TaskGuards()
        assertNull(g.afterAction(false, "A"))
        assertEquals(GuardLevel.WARN, g.afterAction(false, "A")!!.level)
        assertNull(g.afterAction(false, "A").takeIf { it?.level == GuardLevel.STOP })
        assertEquals(GuardLevel.STOP, g.afterAction(false, "A")!!.level)
        val c = TaskGuards()
        listOf("A", "B", "A", "B").forEach { assertNull(c.afterAction(true, it)?.takeIf { v -> v.level == GuardLevel.STOP }) }
        assertEquals("CYCLE", c.afterAction(true, "A")!!.code)
        val rpt = TaskGuards()
        assertNull(rpt.beforeAction("S", "tap|1")); assertNull(rpt.beforeAction("S", "tap|1"))
        assertEquals("REPEAT", rpt.beforeAction("S", "tap|1")!!.code)
    }

    @Test fun allowOnceDoesNotGrantTheAppScopeButAllowForTaskDoes() = runBlocking {
        val r = Rig(ChatApp())
        var answer = PromptAnswer.ALLOW_ONCE
        var asked = 0
        val inner = object : ApprovalGate {
            override suspend fun approve(request: ApprovalRequest) = decide(request).allowed
            override suspend fun decide(request: ApprovalRequest): PromptReply { asked++; assertTrue(request.offerTaskScope); return PromptReply(answer) }
        }
        val gate = r.kit.approvals.wrap(inner)
        fun req(n: Int) = ApprovalRequest("run1", "n$n", "screen_act", "Tap Video call", "", buildJsonObject { put("target", "Video call"); put("action", "tap"); put("n", n) },
            Risk.HIGH, "", false)
        assertTrue(gate.approve(req(1))); assertTrue(gate.approve(req(2)))
        assertEquals("Allow once asks again next time", 2, asked)
        answer = PromptAnswer.ALLOW_TASK
        assertTrue(gate.approve(req(3))); assertTrue(gate.approve(req(4)))
        assertEquals("Allow for this task covers the next steps", 3, asked)
        assertNotNull(r.kit.approvals.scope("run1", "com.example.chat"))
        // irreversible steps never offer the task scope and always ask
        val crit = object : ApprovalGate { override suspend fun approve(request: ApprovalRequest): Boolean { assertFalse(request.offerTaskScope); return true } }
        val g2 = r.kit.approvals.wrap(crit)
        assertTrue(g2.approve(req(5).copy(risk = Risk.CRITICAL, title = "Tap Send")))
    }
}
