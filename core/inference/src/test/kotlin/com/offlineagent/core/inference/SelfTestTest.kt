package com.offlineagent.core.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfTestTest {
    @Test fun garbageFromTheDeviceReportIsDegenerate() {
        // Real outputs reported from the phone running the Phase 3 APK.
        listOf(
            "oughtoughtoughtoughtoughtoughtought",
            "THTHTHTHTHTHTHTHTHTH",
            "ações ités ações ités ações ités ações ités",
            "",
            "     ",
            "ाेाेाेाेाेाेाेाेाे",
        ).forEach { assertEquals(it, SelfTest.Verdict.DEGENERATE, SelfTest.judge(it).verdict) }
    }

    @Test fun normalAnswersPass() {
        listOf("5", "5.", "The answer is 5.", "**5**", "Five", "2 + 3 = 5").forEach { assertEquals(it, SelfTest.Verdict.PASS, SelfTest.judge(it).verdict) }
    }

    @Test fun wrongButSaneAnswerIsNotDegenerate() {
        val r = SelfTest.judge("6")
        assertEquals(SelfTest.Verdict.WRONG_ANSWER, r.verdict)
        assertEquals(true, r.ok)
        assertNull(SelfTest.degenerateReason("I think the result is six, but let me check."))
        assertEquals(SelfTest.Verdict.WRONG_ANSWER, SelfTest.judge("15").verdict)
    }

    @Test fun requestIsGreedyAndUncached() {
        assertEquals(0f, SelfTest.REQUEST.sampling.temperature)
        assertEquals(false, SelfTest.REQUEST.cachePrefix)
        assertEquals(false, SelfTest.REQUEST.thinking)
    }
}
