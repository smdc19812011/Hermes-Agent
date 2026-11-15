package com.hermes.agent.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThrottleLogicTest {
    @Test
    fun unlimitedNeverWaits() {
        assertEquals(1_000L, ThrottleLogic.nextAllowedAt(lastSentAtMs = 999_000L, maxPerMinute = 0, nowMs = 1_000L))
        assertEquals(1_000L, ThrottleLogic.nextAllowedAt(lastSentAtMs = 999_000L, maxPerMinute = -3, nowMs = 1_000L))
    }

    @Test
    fun spacesRequestsEvenlyAcrossAMinute() {
        // 6 requests/minute = one every 10s.
        assertEquals(10_000L, ThrottleLogic.nextAllowedAt(lastSentAtMs = 0L, maxPerMinute = 6, nowMs = 0L))
        assertEquals(10_000L, ThrottleLogic.nextAllowedAt(lastSentAtMs = 0L, maxPerMinute = 6, nowMs = 4_000L))
        // Already past the interval: allowed right now, not held back further.
        assertEquals(15_000L, ThrottleLogic.nextAllowedAt(lastSentAtMs = 0L, maxPerMinute = 6, nowMs = 15_000L))
    }

    @Test
    fun recognisesA429ButNotOtherFailures() {
        assertTrue(ThrottleLogic.isRateLimited("HTTP 429: Provider returned error"))
        assertTrue(ThrottleLogic.isRateLimited("rate limited, HTTP 429 from upstream"))
        assertFalse(ThrottleLogic.isRateLimited("HTTP 500: Internal Server Error"))
        assertFalse(ThrottleLogic.isRateLimited(null))
        assertFalse(ThrottleLogic.isRateLimited("connection reset"))
    }

    @Test
    fun waitForSlotDelaysAndStampsOncePerKeyIndependently() = runTest {
        val throttle = RequestThrottle()
        var waited: Long? = null
        // First call for a fresh key goes straight through.
        throttle.waitForSlot("providerA", maxPerMinute = 6) { waited = it }
        assertEquals(null, waited)
        // Immediately calling again for the SAME key must wait; a different key is unaffected.
        throttle.waitForSlot("providerA", maxPerMinute = 6) { waited = it }
        assertTrue(waited != null)
        waited = null
        throttle.waitForSlot("providerB", maxPerMinute = 6) { waited = it }
        assertEquals(null, waited)
    }
}
