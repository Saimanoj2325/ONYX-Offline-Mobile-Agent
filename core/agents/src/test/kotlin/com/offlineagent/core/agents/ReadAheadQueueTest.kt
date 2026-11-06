package com.offlineagent.core.agents

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.2.3 typing-time prefill: lowest queue priority, preempted by any real job, skipped by the power governor. */
class ReadAheadQueueTest {

    @Test fun priorityJobPreemptsReadAhead() = runBlocking<Unit> {
        val q = InferenceQueue(FakeLlm())
        val started = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        var preempted = 0
        val ahead = async {
            q.tryRunPreemptible("chat-prefill", onPreempt = { preempted++; stop.complete(Unit) }) {
                started.complete(Unit)
                stop.await()   // a long prefill: only the preemption ends it
                "partial"
            }
        }
        started.await()
        val answer = withTimeout(2_000) { q.complete(InferenceQueue.Priority.INTERACTIVE, "writer", "sys", "user", null, 10) }
        assertEquals("Your battery is at 81%.", answer)
        assertEquals("partial", ahead.await())
        assertEquals(1, preempted)
        assertEquals(1, q.preemptions)
        assertEquals(listOf("chat-prefill", "writer"), q.grantOrder.toList())
    }

    @Test fun readAheadNeverWaitsOrJumpsTheQueue() = runBlocking<Unit> {
        val q = InferenceQueue(FakeLlm())
        val hold = CompletableDeferred<Unit>()
        val job = async { q.run(InferenceQueue.Priority.BACKGROUND, "memory-extract") { hold.await(); 1 } }
        while (!q.stats.value.running) delay(1)
        assertNull("busy model: the read-ahead is skipped, not queued", q.tryRunPreemptible("chat-prefill", {}) { 2 })
        hold.complete(Unit); job.await()
        q.pause("Phone is hot")
        assertNull("paused queue (thermal): skipped", q.tryRunPreemptible("chat-prefill", {}) { 3 })
        q.resume()
        assertEquals(4, q.tryRunPreemptible("chat-prefill", {}) { 4 })
        assertTrue(InferenceQueue.Priority.PREFILL.weight < InferenceQueue.Priority.BACKGROUND.weight)
    }

    @Test fun governorGate() {
        assertNull(ReadAheadGate.check(Thermal.NONE, powerSave = false, batteryPercent = 80, charging = false))
        assertNull(ReadAheadGate.check(Thermal.LIGHT, false, 15, charging = true))
        assertEquals("battery saver", ReadAheadGate.check(Thermal.NONE, powerSave = true, batteryPercent = 90, charging = false))
        assertEquals("battery low (18%)", ReadAheadGate.check(Thermal.NONE, false, 18, charging = false))
        assertEquals("phone is moderate", ReadAheadGate.check(Thermal.MODERATE, false, 90, false))
        assertEquals("phone is severe", ReadAheadGate.check(Thermal.SEVERE, false, 90, false))
        assertEquals("screen off", ReadAheadGate.check(Thermal.NONE, false, 90, false, screenOn = false))
        assertEquals("app in background", ReadAheadGate.check(Thermal.NONE, false, 90, false, foreground = false))
        assertEquals("safe mode", ReadAheadGate.check(Thermal.NONE, false, 90, false, safeMode = true))
    }
}
