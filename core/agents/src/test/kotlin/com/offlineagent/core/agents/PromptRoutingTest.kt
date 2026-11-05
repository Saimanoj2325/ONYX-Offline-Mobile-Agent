package com.offlineagent.core.agents

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.2: approvals show where the user is (overlay on top of the current app vs ONYX's own sheet). */
class PromptRoutingTest {
    private class Fakes {
        val shown = mutableListOf<Pair<PromptSurface, UserPrompt>>()
        var overlayWorks = true
        var answer = PromptReply(PromptAnswer.ALLOW_ONCE)
        fun presenters() = PromptSurface.entries.associateWith { s ->
            PromptPresenter { p -> if (s == PromptSurface.ACCESSIBILITY_OVERLAY && !overlayWorks) null else { shown += s to p; answer } }
        }
    }

    private fun prompt(kind: PromptKind = PromptKind.APPROVE, risk: Risk = Risk.HIGH, scope: Boolean = true, irreversible: Boolean = false) =
        UserPrompt("p1", kind, "Screen agent", "Tap 'Archive' in WhatsApp", "WhatsApp", risk, "", allowTaskScope = scope, irreversible = irreversible)

    private val background = SurfaceState(onyxInForeground = false, accessibilityOverlay = true, systemOverlay = false, notifications = true)

    @Test fun onyxInFrontUsesTheInAppSheet() = runBlocking {
        val f = Fakes()
        val r = PromptRouter({ background.copy(onyxInForeground = true) }, f.presenters())
        assertTrue(r.ask(prompt()).allowed)
        assertEquals(PromptSurface.IN_APP_SHEET, r.last)
    }

    @Test fun anotherAppInFrontGetsTheAccessibilityOverlay() = runBlocking {
        val f = Fakes()
        val r = PromptRouter({ background }, f.presenters())
        r.ask(prompt())
        assertEquals(PromptSurface.ACCESSIBILITY_OVERLAY, r.last)
        assertTrue(f.shown.single().second.allowTaskScope) // "Allow for this task" offered where the scope allows it
    }

    @Test fun fallsBackToSystemOverlayThenNotification() = runBlocking {
        val f = Fakes()
        f.overlayWorks = false // the accessibility overlay could not be added
        val r1 = PromptRouter({ background.copy(systemOverlay = true) }, f.presenters())
        r1.ask(prompt()); assertEquals(PromptSurface.SYSTEM_OVERLAY, r1.last)
        val r2 = PromptRouter({ background.copy(accessibilityOverlay = false, systemOverlay = false) }, f.presenters())
        r2.ask(prompt()); assertEquals(PromptSurface.NOTIFICATION, r2.last)
        val r3 = PromptRouter({ SurfaceState(false, false, false, false) }, f.presenters())
        r3.ask(prompt()); assertEquals(PromptSurface.IN_APP_SHEET, r3.last) // last resort
    }

    @Test fun neverCoversASecureOrPasswordScreen() = runBlocking {
        val f = Fakes()
        val sensitive = background.copy(sensitiveScreen = true, sensitiveFieldY = 0.3f)
        val r = PromptRouter({ sensitive }, f.presenters())
        r.ask(prompt())
        assertEquals("approvals go to the heads-up notification", PromptSurface.NOTIFICATION, r.last)
        r.ask(prompt(kind = PromptKind.HANDOFF))
        assertEquals(PromptSurface.ACCESSIBILITY_OVERLAY, r.last)
        val pill = f.shown.last().second
        assertTrue("hand-off becomes a compact 'Your turn' pill", pill.compact)
        assertEquals(0.3f, pill.sensitiveFieldY)
    }

    @Test fun irreversibleStepsAlwaysAskAndNeverOfferTheTaskScope() = runBlocking {
        val f = Fakes()
        val r = PromptRouter({ background }, f.presenters())
        r.ask(prompt(risk = Risk.CRITICAL, scope = true))
        assertFalse(f.shown.last().second.allowTaskScope)
        r.ask(prompt(risk = Risk.HIGH, scope = true, irreversible = true))
        assertFalse(f.shown.last().second.allowTaskScope)
        assertEquals(2, f.shown.size) // asked both times
    }

    @Test fun stopAndDenyAreNotApprovals() = runBlocking {
        val f = Fakes()
        val r = PromptRouter({ background }, f.presenters())
        f.answer = PromptReply(PromptAnswer.STOP); assertFalse(r.ask(prompt()).allowed); assertTrue(r.ask(prompt()).stop)
        f.answer = PromptReply(PromptAnswer.DENY); assertFalse(r.ask(prompt()).allowed)
        f.answer = PromptReply(PromptAnswer.RESUME); assertTrue(r.ask(prompt(kind = PromptKind.HANDOFF)).allowed)
        // a yes/no-only gate keeps the old meaning: yes to a scope request grants the scope
        val legacy = ApprovalGate { true }
        val req = ApprovalRequest("r", "n", "screen_act", "t", "d", kotlinx.serialization.json.JsonObject(emptyMap()), Risk.HIGH, "", false, offerTaskScope = true)
        assertTrue(legacy.decide(req).forTask)
        assertFalse(legacy.decide(req.copy(offerTaskScope = false)).forTask)
    }
}
