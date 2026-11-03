package com.offlineagent.core.agents

import com.offlineagent.core.models.ModelCatalog
import com.offlineagent.core.models.ModelFit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 6 M1: device-adaptive tiers from FREE RAM, thermal/battery scaling, idle unload. */
class Phase6RuntimeTest {
    private val sd782g = setOf("fp", "asimd", "fphp", "asimdhp", "asimddp", "asimdrdm") // dotprod, no i8mm
    private fun snap(totalGb: Double, freeMb: Long, cores: Int = 4, thermal: Thermal = Thermal.NONE, saver: Boolean = false,
                     battery: Int? = 80, charging: Boolean = false, storageMb: Long = 20_000, features: Set<String> = sd782g) =
        DeviceSnapshot((totalGb * 1024).toLong(), freeMb, false, 8, cores, "SM7325", 35, thermal, saver, battery, charging, storageMb, features)

    private fun RuntimePlan.primary() = picks.first { it.primary }

    @Test fun fourGbPhoneGetsTinyModelAndSmallContext() {
        val p = RuntimeSelector.plan(snap(3.7, freeMb = 1400))
        assertEquals(DeviceTier.ENTRY, p.tier)
        assertEquals(2048, p.contextLength)
        assertEquals(128, p.batch)
        assertTrue(ModelCatalog.byId(p.primary().catalogId)!!.main.approxBytes < 800L * 1024 * 1024)
        assertTrue(p.picks.none { it.catalogId == "qwen3-1.7b" || it.catalogId == "lfm2.5-8b-a1b" })
    }

    @Test fun sixGbPhoneGets17bAt4k() {
        val p = RuntimeSelector.plan(snap(5.6, freeMb = 2600))
        assertEquals(DeviceTier.MID, p.tier)
        assertEquals(4096, p.contextLength)
        assertEquals("qwen3-1.7b", p.primary().catalogId)
    }

    @Test fun eightGbPhoneDefaultFitsWithHeadroomAndHidesBigModels() {
        val p = RuntimeSelector.plan(snap(7.3, freeMb = 3200))
        assertEquals(DeviceTier.HIGH, p.tier)
        assertEquals(8192, p.contextLength)
        assertEquals(4, p.threads)
        val primary = p.primary()
        assertEquals("qwen3-1.7b", primary.catalogId)
        assertTrue("default must be ≤ ~2.5 GB resident on 8 GB: ${primary.residentMb}", primary.residentMb <= ModelFit.DEFAULT_BUDGET_MB)
        assertEquals(ModelFit.Verdict.OK, primary.fit)
        // 4B at 8K needs ~3.4 GB: with 3.2 GB free it is hidden, not offered
        assertTrue(p.picks.none { it.catalogId == "qwen3-4b-2507" })
        assertTrue(p.notes.any { it.startsWith("Hidden until more RAM is free") })
    }

    @Test fun eightGbPhoneWithLittleFreeRamDegrades() {
        val p = RuntimeSelector.plan(snap(7.3, freeMb = 1700))
        assertEquals(4096, p.contextLength)
        assertTrue(p.primary().residentMb < 1700)
    }

    @Test fun twelveGbPhoneOffersBigMoeOnlyWhenFree() {
        val roomy = RuntimeSelector.plan(snap(11.2, freeMb = 7000)) // OnePlus Nord CE 3 (CPH2569) reports ~11.2 GB
        assertEquals(DeviceTier.FLAGSHIP, roomy.tier)
        assertEquals("qwen3-1.7b", roomy.primary().catalogId)
        assertTrue(roomy.picks.any { it.catalogId == "lfm2.5-8b-a1b" })
        assertTrue(roomy.picks.any { it.catalogId == "qwen3-4b-2507" })
        val busy = RuntimeSelector.plan(snap(11.2, freeMb = 3500)) // same phone, lots of apps open
        assertTrue(busy.picks.none { it.catalogId == "lfm2.5-8b-a1b" })
        assertEquals("qwen3-1.7b", busy.primary().catalogId)
    }

    @Test fun warnBeforeLoadingTooBigModel() {
        val s = snap(7.3, freeMb = 3000)
        assertNotNull(RuntimeSelector.loadWarning(ModelCatalog.LFM25_8B_A1B, s, 4096))
        assertNull(RuntimeSelector.loadWarning(ModelCatalog.QWEN3_06B, s, 4096))
        val f = ModelFit.check(ModelCatalog.LFM25_8B_A1B, 4096, 7475, 3000)
        assertEquals(ModelFit.Verdict.TOO_BIG, f.verdict)
        assertEquals(ModelFit.Verdict.TIGHT, ModelFit.check(ModelCatalog.QWEN3_17B, 8192, 7475, 2600).verdict)
    }

    @Test fun cpuWithoutDotprodGetsSmallModelsOnly() {
        val p = RuntimeSelector.plan(snap(11.2, freeMb = 7000, features = setOf("fp", "asimd")))
        assertTrue(p.picks.all { ModelCatalog.byId(it.catalogId)!!.sizeClass <= com.offlineagent.core.models.CatalogModel.SizeClass.SMALL })
        assertTrue(p.notes.any { "dot-product" in it })
    }

    @Test fun lowStorageNoted() {
        assertTrue(RuntimeSelector.plan(snap(7.3, freeMb = 3000, storageMb = 2000)).notes.any { "Low storage" in it })
    }

    @Test fun thermalScalesThreadsBatchAndSpeculation() {
        val cool = PowerGovernor.decide(Thermal.NONE, false, 4)
        assertEquals(4, cool.threads); assertEquals(256, cool.batch); assertTrue(cool.speculation); assertFalse(cool.pauseInference)
        val light = PowerGovernor.decide(Thermal.LIGHT, false, 4)
        assertEquals(3, light.threads)
        val warm = PowerGovernor.decide(Thermal.MODERATE, false, 4)
        assertEquals(2, warm.threads); assertEquals(128, warm.batch); assertFalse(warm.speculation); assertEquals(0, warm.pollLevel)
        val hot = PowerGovernor.decide(Thermal.SEVERE, false, 4)
        assertTrue(hot.pauseInference); assertEquals(1, hot.threads)
    }

    @Test fun batterySaverAndLowBatteryScaleDownAndShortenIdleUnload() {
        val saver = PowerGovernor.decide(Thermal.NONE, true, 4)
        assertEquals(2, saver.threads); assertEquals(128, saver.batch)
        assertEquals(PowerGovernor.LOW_BATTERY_IDLE_UNLOAD_MS, saver.idleUnloadMs)
        val low = PowerGovernor.decide(Thermal.NONE, false, 4, batteryPercent = 9, charging = false)
        assertEquals(2, low.threads); assertTrue(low.notes.any { "Battery is low" in it })
        val lowCharging = PowerGovernor.decide(Thermal.NONE, false, 4, batteryPercent = 9, charging = true)
        assertEquals(4, lowCharging.threads); assertEquals(PowerGovernor.DEFAULT_IDLE_UNLOAD_MS, lowCharging.idleUnloadMs)
        // plan carries it through
        val p = RuntimeSelector.plan(snap(7.3, freeMb = 3200, battery = 10))
        assertEquals(2, p.threads); assertEquals(128, p.batch)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun idleUnloadAfterTimeoutButNotWhileBusy() = runTest {
        var loaded = true
        var busy = false
        val reasons = mutableListOf<String>()
        val u = IdleUnloader(backgroundScope, { testScheduler.currentTime }, { 10 * 60_000L }, { busy }, { loaded }) { reasons += it; loaded = false }
        u.touch()
        advanceTimeBy(9 * 60_000L); runCurrent()
        assertTrue(loaded)
        u.touch() // used again: timer restarts
        advanceTimeBy(9 * 60_000L); runCurrent()
        assertTrue(loaded)
        busy = true
        advanceTimeBy(5 * 60_000L); runCurrent()
        assertTrue("busy work blocks unloading", loaded)
        busy = false
        advanceTimeBy(31_000L); runCurrent()
        assertFalse(loaded)
        assertEquals(1, u.unloads)
        assertTrue(reasons.single().contains("idle"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun idleUnloadDisabledWithZeroTimeout() = runTest {
        var loaded = true
        val u = IdleUnloader(backgroundScope, { testScheduler.currentTime }, { 0L }, { false }, { loaded }) { loaded = false }
        u.touch()
        advanceTimeBy(60 * 60_000L); runCurrent()
        assertTrue(loaded)
    }
}
