package com.offlineagent.core.screen

import com.offlineagent.core.runtime.phone.AutomationHost
import com.offlineagent.core.runtime.phone.AutomationPolicy
import com.offlineagent.core.runtime.phone.Bounds
import com.offlineagent.core.runtime.phone.DeepLinks
import com.offlineagent.core.runtime.phone.Macro
import com.offlineagent.core.runtime.phone.MacroCodec
import com.offlineagent.core.runtime.phone.MacroDescribe
import com.offlineagent.core.runtime.phone.MacroPlayer
import com.offlineagent.core.runtime.phone.MacroRecorder
import com.offlineagent.core.runtime.phone.MacroReplayConfig
import com.offlineagent.core.runtime.phone.MacroStepKind
import com.offlineagent.core.runtime.phone.MatchStrategy
import com.offlineagent.core.runtime.phone.RecordedEvent
import com.offlineagent.core.runtime.phone.ScreenNode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.2: macro record → replay by matching (not coordinates), on the fake phone. */
class MacroReplayTest {
    private class Host(var approve: Boolean = true) : AutomationHost {
        val approvals = mutableListOf<String>()
        val highlights = mutableListOf<Pair<Int, String>>()
        var done = ""
        override fun preview(step: Int, text: String) {}
        override suspend fun approve(step: Int, action: String, reason: String): Boolean { approvals += action; return approve }
        override suspend fun ask(question: String): String? = null
        override fun finished(summary: String) { done = summary }
        override suspend fun highlight(step: Int, bounds: Bounds, text: String) { highlights += step to text }
    }

    private val cfg = MacroReplayConfig(screenTimeoutMs = 300, effectTimeoutMs = 60, pollMs = 10, maxScrolls = 6, highlightMs = 0, settleMs = 0)

    /** Records a demonstration on the fake phone exactly like the accessibility service would (full window + source path). */
    private class Demo(val phone: FakePhone) {
        val rec = MacroRecorder("com.onyx.agent", AutomationPolicy())
        var t = 1_000L
        suspend fun window() { val s = phone.capture()!!.snapshot; t += 500; rec.add(RecordedEvent.WindowChanged(s.packageName, t, s.activity, s)) }
        suspend fun seen() { val s = phone.capture()!!.snapshot; t += 100; rec.add(RecordedEvent.ScreenSeen(s, t)) }
        suspend fun tap(label: String) {
            val v = ScreenView("rec", phone.capture()!!)
            val el = Grounding.find(v, label).first().element
            // The click event's source is the clickable row, not its text.
            val target = v.elements.filter { it.node.clickable && el.node.path.take(it.node.path.size) == it.node.path }.maxByOrNull { it.node.path.size } ?: el
            t += 1200
            rec.add(RecordedEvent.Clicked(target.node, v.pkg, t, v.snapshot, target.node.path))
            phone.perform(target.node, NodeOp.CLICK)
            seen()
        }
        suspend fun type(fieldHint: String, vararg progressive: String) {
            for (p in progressive) {
                val v = ScreenView("rec", phone.capture()!!)
                val f = Grounding.find(v, fieldHint, "input").first().element
                phone.perform(f.node, NodeOp.SET_TEXT, p)
                t += 150
                val after = phone.capture()!!.snapshot
                rec.add(RecordedEvent.TextChanged(after.elements.first { it.node.path == f.node.path }.node, v.pkg, p, t, after, f.node.path))
            }
            seen()
        }
    }

    private suspend fun recordHiMom(phone: FakePhone, wa: FakeWhatsApp, send: Boolean = false): Macro {
        val d = Demo(phone)
        d.window()
        d.tap("Mom")
        d.type("Message", "h", "hi", "hi Mom")
        if (send) d.tap("Send")
        wa.back(); wa.input = ""
        return d.rec.build("m1", "Hi Mom", "")
    }

    private fun player(phone: FakePhone, host: Host, openLink: suspend (String, String) -> Boolean = { _, _ -> false }) =
        MacroPlayer(FakeDriver(phone), host, AutomationPolicy(), { }, sleep = { }, config = cfg, appLabel = { if (it == "com.whatsapp") "WhatsApp" else it }, openLink = openLink)

    @Test fun recordsRichTargetsAndOneTypePerField() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        val m = recordHiMom(phone, wa)
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TAP, MacroStepKind.TYPE), m.steps.map { it.kind })
        val tap = m.steps[1].selector!!
        assertEquals("Mom", tap.listItemLabel)                    // the chat row is known by its name
        assertEquals("com.whatsapp:id/conversations_list", tap.listId)
        assertTrue(tap.inList && tap.ancestors.isNotEmpty() && tap.relX >= 0)
        assertEquals("hi Mom", m.steps[2].value)                   // final value, not every keystroke
        assertNotNull(m.steps[1].before); assertNotNull(m.steps[1].after)
        assertEquals("com.whatsapp.Conversation", m.steps[1].after!!.activity)
        // fingerprints and the saved file never contain the other chats' message previews
        assertFalse(m.toJson().contains("secret message"))
        assertEquals("Tap chat 'Mom' in WhatsApp", MacroDescribe.step(m.steps[1]) { "WhatsApp" })
        assertEquals("Type 'hi Mom' into 'Message'", MacroDescribe.step(m.steps[2]))
        // suggestions: the chosen chat and the typed text
        assertEquals(listOf("chat" to "label", "message" to "value"), m.suggestions.map { it.name to it.field })
        // round-trips through JSON (old macros without the new fields still decode)
        assertEquals(m, MacroCodec.decode(m.toJson()))
        assertEquals(0, MacroCodec.decode("""{"id":"x","name":"old","steps":[{"kind":"TAP","selector":{"text":"Mom"}}]}""").steps.first().selector!!.ancestors.size)
    }

    @Test fun replaysTheSameChatAfterTheListIsReordered() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        val m = recordHiMom(phone, wa)
        wa.chats = mutableListOf("Mom", "Teja", "Ravi", "Dad", "Office group", "Priya", "Anil") // Mom moved to the top
        val host = Host()
        val out = player(phone, host).play(m, emptyMap())
        assertTrue(out, out.startsWith("Macro \"Hi Mom\" finished"))
        assertEquals("Mom", wa.openChat)
        assertEquals("hi Mom", wa.input)
    }

    @Test fun findsAnOffScreenChatByScrolling() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        val m = recordHiMom(phone, wa)
        wa.chats = (1..14).map { "Friend $it" }.toMutableList().also { it.add(12, "Mom") }
        val p = player(phone, Host())
        val out = p.play(m, emptyMap())
        assertTrue(out, out.contains("finished"))
        assertEquals("Mom", wa.openChat)
        val tapTrace = p.lastReport!!.steps[1]
        assertTrue("scrolled ${tapTrace.scrolls}", tapTrace.scrolls > 0)
        // and when the list starts scrolled past it, it scrolls back up
        wa.screen = "list"; wa.offset = 0; wa.chats = (listOf("Mom") + (1..14).map { "Friend $it" }).toMutableList(); wa.offset = 10
        val out2 = player(phone, Host()).play(m, emptyMap())
        assertTrue(out2, out2.contains("finished")); assertEquals("Mom", wa.openChat)
    }

    @Test fun changedLayoutIsMatchedById() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        val m = recordHiMom(phone, wa)
        wa.layoutV2 = true // wrapper added, hint "Type a message", Send before the field — same ids
        val p = player(phone, Host())
        val out = p.play(m, emptyMap())
        assertTrue(out, out.contains("finished"))
        assertEquals("hi Mom", wa.input)
        assertEquals(MatchStrategy.ID_TEXT, p.lastReport!!.steps[2].strategy)
    }

    @Test fun passwordsAndCodesAreNeverRecorded() {
        val rec = MacroRecorder("com.onyx.agent", AutomationPolicy())
        val pw = ScreenNode(className = "android.widget.EditText", hint = "Password", editable = true, password = true, viewId = "app:id/pw")
        val hiddenType = ScreenNode(className = "android.widget.EditText", hint = "Enter PIN", editable = true, inputType = 0x12, viewId = "app:id/pin") // number password
        val otp = ScreenNode(className = "android.widget.EditText", hint = "Enter OTP", editable = true, viewId = "app:id/otp")
        val user = ScreenNode(className = "android.widget.EditText", hint = "Username", editable = true, viewId = "app:id/user")
        rec.add(RecordedEvent.WindowChanged("com.example.login", 0))
        rec.add(RecordedEvent.TextChanged(pw, "com.example.login", "hunter2", 10))
        rec.add(RecordedEvent.TextChanged(hiddenType, "com.example.login", "4321", 20))
        rec.add(RecordedEvent.TextChanged(otp, "com.example.login", "123456", 30))
        rec.add(RecordedEvent.TextChanged(user, "com.example.login", "s", 40))
        rec.add(RecordedEvent.TextChanged(user, "com.example.login", "sai", 50))
        val m = rec.build("m", "login", "")
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TYPE), m.steps.map { it.kind })
        assertEquals("sai", m.steps[1].value)
        val json = m.toJson()
        assertFalse(json.contains("hunter2")); assertFalse(json.contains("4321")); assertFalse(json.contains("123456"))
    }

    @Test fun typedTextKeepsTheFinalValueEvenAfterTappingBackIntoTheField() {
        val rec = MacroRecorder("com.onyx.agent", AutomationPolicy())
        val f = ScreenNode(className = "android.widget.EditText", hint = "Message", editable = true, viewId = "com.whatsapp:id/entry")
        rec.add(RecordedEvent.WindowChanged("com.whatsapp", 0))
        rec.add(RecordedEvent.TextChanged(f, "com.whatsapp", "hel", 10))
        rec.add(RecordedEvent.Clicked(f, "com.whatsapp", 20))               // tapped the field again
        rec.add(RecordedEvent.TextChanged(f, "com.whatsapp", "hello there", 30))
        val m = rec.build("m", "x", "")
        assertEquals(1, m.steps.count { it.kind == MacroStepKind.TYPE })
        assertEquals("hello there", m.steps.first { it.kind == MacroStepKind.TYPE }.value)
    }

    @Test fun failuresSayWhatWasExpectedAndWhatWasSeen() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        val m = recordHiMom(phone, wa)
        wa.chats.remove("Mom"); wa.chats.add("Moms club")
        val p = player(phone, Host())
        val out = p.play(m, emptyMap())
        assertTrue(out, out.startsWith("Macro stopped at step 2: could not find \"Mom\" on screen (step 2) — expected WhatsApp / HomeActivity"))
        assertTrue(out, out.contains("but saw WhatsApp / HomeActivity"))
        val rep = p.lastReport!!.render("0.6.2-test")
        assertTrue(rep, rep.contains("FAILED at step 2 of 3"))
        assertTrue(rep, rep.contains("list item label: 0"))
        assertTrue(rep, rep.contains("screen position: 0 (skipped (list row"))
        assertFalse("no message previews in the report", rep.contains("secret message"))
        assertFalse("other chats are masked", rep.contains("Moms club"))

        // a tap that changes nothing is retried once, then reported
        val wa2 = FakeWhatsApp(); val phone2 = FakePhone(wa2)
        val m2 = recordHiMom(phone2, wa2)
        wa2.frozen = true
        val out2 = player(phone2, Host()).play(m2, emptyMap())
        assertTrue(out2, out2.contains("changed nothing — expected WhatsApp / Conversation"))
    }

    @Test fun parametersRunTheMacroForAnotherChatAndMessage() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        var m = recordHiMom(phone, wa)
        m.suggestions.forEach { sg -> m = m.applySuggestion(m.suggestions.first { it.name == sg.name }) }
        assertEquals(listOf("chat", "message"), m.params)
        assertEquals("{{chat}}", m.steps[1].selector!!.listItemLabel)
        assertEquals("{{message}}", m.steps[2].value)
        val host = Host()
        assertEquals("Missing values for: chat", player(phone, host).play(m, mapOf("message" to "yo")))
        val out = player(phone, host).play(m, mapOf("chat" to "Dad", "message" to "yo"))
        assertTrue(out, out.contains("finished"))
        assertEquals("Dad", wa.openChat); assertEquals("yo", wa.input)
        // the deep-link start is not offered for a chat that changes every run (editor), but suggest() still finds the tap
        assertEquals(2, DeepLinks.suggest(m)!!.skipSteps)
    }

    @Test fun sendAlwaysAsksAndScheduledRunsStopBeforeIt() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        val m = recordHiMom(phone, wa, send = true)
        assertTrue(m.steps.last().irreversible)
        wa.sent.clear()
        val unattended = player(phone, Host()).play(m, emptyMap(), unattended = true)
        assertTrue(unattended, unattended.contains("needs your approval"))
        assertTrue(wa.sent.isEmpty())
        wa.back()
        val host = Host(approve = true)
        val out = player(phone, host).play(m, emptyMap(), testRun = true)
        assertTrue(out, out.contains("finished"))
        assertEquals(listOf("Mom" to "hi Mom"), wa.sent)
        assertEquals(1, host.approvals.size)
        assertEquals(3, host.highlights.size) // test run outlines each matched element before acting
    }

    @Test fun deepLinkIsAnOptionalFasterStart() = runBlocking {
        val wa = FakeWhatsApp(); val phone = FakePhone(wa)
        var m = recordHiMom(phone, wa)
        val dl = m.deepLink!!
        assertEquals("whatsapp_chat", dl.kind); assertEquals("Mom", dl.label); assertEquals(2, dl.skipSteps)
        assertEquals("https://wa.me/919848022338", DeepLinks.whatsappUri("+91 98480 22338"))
        assertEquals("https://wa.me/919848022338", DeepLinks.whatsappUri("9848022338"))
        m = m.copy(deepLink = dl.copy(uri = DeepLinks.whatsappUri("9848022338")!!, enabled = true))
        val links = mutableListOf<String>()
        val p = player(phone, Host()) { uri, _ -> links += uri; wa.openByLink("Mom"); true }
        val out = p.play(m, emptyMap())
        assertTrue(out, out.contains("finished"))
        assertEquals(listOf("https://wa.me/919848022338"), links)
        assertTrue(p.lastReport!!.deepLink, p.lastReport!!.deepLink.startsWith("used"))
        assertEquals("hi Mom", wa.input)
        // a link that does nothing falls back to the recorded steps
        wa.back(); wa.input = ""
        val p2 = player(phone, Host()) { _, _ -> false }
        assertTrue(p2.play(m, emptyMap()).contains("finished"))
        assertEquals("Mom", wa.openChat); assertTrue(p2.lastReport!!.deepLink.startsWith("did not open"))
    }
}
