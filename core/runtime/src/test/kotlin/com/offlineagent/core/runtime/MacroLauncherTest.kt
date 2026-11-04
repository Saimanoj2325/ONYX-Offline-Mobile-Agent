package com.offlineagent.core.runtime

import com.offlineagent.core.runtime.phone.AutomationHost
import com.offlineagent.core.runtime.phone.AutomationLogEntry
import com.offlineagent.core.runtime.phone.AutomationPolicy
import com.offlineagent.core.runtime.phone.Bounds
import com.offlineagent.core.runtime.phone.Macro
import com.offlineagent.core.runtime.phone.MacroApps
import com.offlineagent.core.runtime.phone.MacroCodec
import com.offlineagent.core.runtime.phone.MacroPlayer
import com.offlineagent.core.runtime.phone.MacroRecorder
import com.offlineagent.core.runtime.phone.MacroStep
import com.offlineagent.core.runtime.phone.MacroStepKind
import com.offlineagent.core.runtime.phone.NodeSelector
import com.offlineagent.core.runtime.phone.RecordedEvent
import com.offlineagent.core.runtime.phone.ScreenAction
import com.offlineagent.core.runtime.phone.ScreenDriver
import com.offlineagent.core.runtime.phone.ScreenNode
import com.offlineagent.core.runtime.phone.ScreenSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6.1: macros recorded from the home screen (OnePlus: "Open com.android.launcher → failed" at step 1). */
class MacroLauncherTest {
    private val launcher = "com.android.launcher" // what OxygenOS reports; detected via CATEGORY_HOME in the app, not by name
    private val isHome: (String) -> Boolean = { it == launcher }
    private val labels = mapOf("WhatsApp" to "com.whatsapp", "Chrome" to "com.android.chrome")
    private val appForLabel: (String) -> String? = { labels[it] }

    private fun n(text: String = "", desc: String = "", click: Boolean = true, edit: Boolean = false, hint: String = "") =
        ScreenNode(className = if (edit) "android.widget.EditText" else "android.widget.TextView", text = text, contentDescription = desc, hint = hint,
            clickable = click, editable = edit, bounds = Bounds(0, 0, 100, 40))

    private fun recorder() = MacroRecorder("com.offlineagent.app", AutomationPolicy(), isHome, appForLabel)

    @Test fun recordingThatStartsOnTheHomeScreenOpensTheTappedAppInstead() {
        val rec = recorder()
        rec.add(RecordedEvent.WindowChanged(launcher, 0))                     // recorder pressed Home: launcher in front
        rec.add(RecordedEvent.Scrolled(launcher, "down", 5))                  // swiping home pages: dropped
        rec.add(RecordedEvent.Clicked(n("WhatsApp"), launcher, 10))           // icon tap → OPEN_APP com.whatsapp
        rec.add(RecordedEvent.WindowChanged("com.whatsapp", 20))
        rec.add(RecordedEvent.Clicked(n("Mom"), "com.whatsapp", 30))
        rec.add(RecordedEvent.TextChanged(n(edit = true, hint = "Message"), "com.whatsapp", "hi", 40))
        val m = rec.build("m1", "Hi mom", "")
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TAP, MacroStepKind.TYPE), m.steps.map { it.kind })
        assertEquals("com.whatsapp", m.steps[0].value)
        assertEquals("com.whatsapp", m.startPackage)
        assertFalse(m.toJson().contains(launcher))
    }

    @Test fun homeMidDemonstrationBecomesHomeAndSystemUiIsIgnored() {
        val rec = recorder()
        rec.add(RecordedEvent.WindowChanged("com.whatsapp", 0))
        rec.add(RecordedEvent.Clicked(n("Mom"), "com.whatsapp", 10))
        rec.add(RecordedEvent.WindowChanged("com.android.systemui", 15))     // notification shade
        rec.add(RecordedEvent.WindowChanged(launcher, 20))                   // user pressed Home
        rec.add(RecordedEvent.Clicked(n(desc = "Chrome"), launcher, 30))      // label only in contentDescription
        rec.add(RecordedEvent.WindowChanged("com.android.chrome", 40))
        rec.add(RecordedEvent.Clicked(n("New tab"), "com.android.chrome", 50))
        rec.add(RecordedEvent.WindowChanged(launcher, 60))                   // trailing Home is dropped
        val m = rec.build("m2", "two apps", "")
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TAP, MacroStepKind.HOME, MacroStepKind.OPEN_APP, MacroStepKind.TAP), m.steps.map { it.kind })
        assertEquals("com.android.chrome", m.steps[3].value)
        assertEquals("com.whatsapp", m.startPackage)
    }

    @Test fun unknownLauncherIconIsDroppedNotRecordedAsALauncherStep() {
        val rec = recorder()
        rec.add(RecordedEvent.WindowChanged(launcher, 0))
        rec.add(RecordedEvent.Clicked(n("Some widget"), launcher, 10))
        rec.add(RecordedEvent.WindowChanged("com.example.notes", 20))       // the app opened anyway: recorded as OPEN_APP
        rec.add(RecordedEvent.Clicked(n("New note"), "com.example.notes", 30))
        val m = rec.build("m3", "notes", "")
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TAP), m.steps.map { it.kind })
        assertEquals("com.example.notes", m.steps[0].value)
    }

    @Test fun savedLauncherMacroIsMigratedOnLoad() {
        // Exactly what 6.0 saved: "New macro, 11 steps in com.android.launcher".
        val old = Macro("m4", "New macro", startPackage = launcher, steps = listOf(
            MacroStep(MacroStepKind.OPEN_APP, value = launcher),
            MacroStep(MacroStepKind.TAP, NodeSelector(launcher, text = "WhatsApp"), waitMillis = 800),
            MacroStep(MacroStepKind.TAP, NodeSelector("com.whatsapp", text = "Mom")),
            MacroStep(MacroStepKind.TYPE, NodeSelector("com.whatsapp", hint = "Message"), "hi"),
            MacroStep(MacroStepKind.OPEN_APP, value = launcher),
            MacroStep(MacroStepKind.TAP, NodeSelector(launcher, text = "Mystery widget")),
            MacroStep(MacroStepKind.SCROLL, NodeSelector(launcher), "down"),
        ))
        val fixed = MacroApps.migrate(MacroCodec.decode(old.toJson()), "com.offlineagent.app", isHome, appForLabel)
        assertEquals(listOf(MacroStepKind.OPEN_APP, MacroStepKind.TAP, MacroStepKind.TYPE, MacroStepKind.HOME, MacroStepKind.TAP), fixed.steps.map { it.kind })
        assertEquals("com.whatsapp", fixed.steps[0].value)
        assertEquals("com.whatsapp", fixed.startPackage)
        assertTrue(fixed.steps.none { it.kind == MacroStepKind.OPEN_APP && it.value == launcher })
        // a macro without launcher steps is returned as the same instance (nothing re-saved)
        val clean = Macro("m5", "ok", startPackage = "com.whatsapp", steps = listOf(MacroStep(MacroStepKind.OPEN_APP, value = "com.whatsapp")))
        assertSame(clean, MacroApps.migrate(clean, "com.offlineagent.app", isHome, appForLabel))
    }

    private class Driver(val openOk: Boolean = true) : ScreenDriver {
        val performed = mutableListOf<ScreenAction>()
        override suspend fun snapshot() = ScreenSnapshot("com.offlineagent.app", ScreenNode(className = "android.widget.FrameLayout", bounds = Bounds(0, 0, 1080, 2000)))
        override suspend fun perform(a: ScreenAction, s: ScreenSnapshot): Boolean { performed += a; return a !is ScreenAction.OpenApp || openOk }
    }
    private val host = object : AutomationHost {
        override fun preview(step: Int, text: String) {}
        override suspend fun approve(step: Int, action: String, reason: String) = true
        override suspend fun ask(question: String): String? = null
        override fun finished(summary: String) {}
    }

    @Test fun runnerReplaysALauncherStepAsHomeAndExplainsOpenFailures() = runTest {
        val log = mutableListOf<AutomationLogEntry>()
        val d = Driver()
        val m = Macro("m6", "home", steps = listOf(MacroStep(MacroStepKind.OPEN_APP, value = launcher)))
        val ok = MacroPlayer(d, host, AutomationPolicy(), { log += it }, sleep = { }, isHome = isHome).play(m, emptyMap())
        assertTrue(ok, ok.contains("finished"))
        assertEquals(listOf<ScreenAction>(ScreenAction.Home), d.performed)

        // openProblem (no launch intent / not installed / blocked) stops BEFORE trying, with the reason
        val noLaunch = Macro("m7", "x", steps = listOf(MacroStep(MacroStepKind.OPEN_APP, value = "com.example.service")))
        val r1 = MacroPlayer(Driver(), host, AutomationPolicy(), { }, sleep = { }, openProblem = { "it has no launch screen, so it cannot be opened directly" },
            appLabel = { "Example" }).play(noLaunch, emptyMap())
        assertEquals("Macro stopped at step 1: can't open Example (com.example.service): it has no launch screen, so it cannot be opened directly.", r1)

        // Android refusing the launch is also explained (no more bare "step 1 failed")
        val r2 = MacroPlayer(Driver(openOk = false), host, AutomationPolicy(), { }, sleep = { }, appLabel = { "WhatsApp" })
            .play(Macro("m8", "x", steps = listOf(MacroStep(MacroStepKind.OPEN_APP, value = "com.whatsapp"))), emptyMap())
        assertTrue(r2, r2.startsWith("Macro stopped at step 1: could not open WhatsApp (com.whatsapp)"))
        assertFalse(r2.contains("step 1 failed"))
    }
}
