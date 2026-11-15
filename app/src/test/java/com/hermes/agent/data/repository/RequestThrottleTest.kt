package com.hermes.agent.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

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
    fun retriesAnyProviderErrorExceptOnesAWaitCanNeverFix() {
        // Worth a minute and another try: rate limits, server errors, and anything with no HTTP code at all
        // (a dropped connection, a timeout, a malformed response) — those are exactly the transient cases.
        assertTrue(ThrottleLogic.isRetriableProviderError("HTTP 429: Provider returned error"))
        assertTrue(ThrottleLogic.isRetriableProviderError("rate limited, HTTP 429 from upstream"))
        assertTrue(ThrottleLogic.isRetriableProviderError("HTTP 500: Internal Server Error"))
        assertTrue(ThrottleLogic.isRetriableProviderError("HTTP 503: Service Unavailable"))
        assertTrue(ThrottleLogic.isRetriableProviderError("connection reset"))
        assertTrue(ThrottleLogic.isRetriableProviderError("timeout"))
        // Not worth retrying: waiting a minute won't fix a bad key or a model that doesn't exist.
        assertFalse(ThrottleLogic.isRetriableProviderError("HTTP 401: invalid API key"))
        assertFalse(ThrottleLogic.isRetriableProviderError("HTTP 403: Forbidden"))
        assertFalse(ThrottleLogic.isRetriableProviderError("HTTP 404: no endpoints found for model"))
        assertFalse(ThrottleLogic.isRetriableProviderError(null))
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

    @Test
    fun sameLocalDayUsesTheDeviceTimeZoneNotUtc() {
        val zone = ZoneId.of("Asia/Manila") // UTC+8, so 2 AM local is still the previous UTC day
        val lateEveningUtc = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC == 2023-11-15 06:13:20 in Manila
        val justAfterMidnightLocal = lateEveningUtc + 3 * 60 * 60_000L // +3h is still 2023-11-15 in Manila
        assertTrue(ThrottleLogic.isSameLocalDay(lateEveningUtc, justAfterMidnightLocal, zone))
        assertFalse(ThrottleLogic.isSameLocalDay(lateEveningUtc, justAfterMidnightLocal, ZoneId.of("UTC")))
    }

    @Test
    fun countInWindowCountsOnlyWhatsStillInRangeAndNotFromTheFuture() {
        val now = 100_000L
        val timestamps = listOf(now - 70_000, now - 59_000, now - 1_000, now, now + 5_000)
        assertEquals(3, ThrottleLogic.countInWindow(timestamps, now, windowMs = 60_000))
    }

    @Test
    fun formatsTheOnScreenLineWithAndWithoutAKnownLimit() {
        assertEquals("12 today · 3/min", ThrottleLogic.formatRequestStats(12, null, 3, "min", null))
        assertEquals("12/50 today · 7/10s (limit 20)", ThrottleLogic.formatRequestStats(12, 50, 7, "10s", 20))
    }

    @Test
    fun parsesOpenRouterStyleIntervalsIntoMillis() {
        assertEquals(10_000L, ThrottleLogic.parseIntervalMs("10s"))
        assertEquals(60_000L, ThrottleLogic.parseIntervalMs("1m"))
        assertEquals(2 * 3_600_000L, ThrottleLogic.parseIntervalMs("2h"))
        assertEquals(86_400_000L, ThrottleLogic.parseIntervalMs("1d"))
        assertEquals(null, ThrottleLogic.parseIntervalMs(null))
        assertEquals(null, ThrottleLogic.parseIntervalMs("garbage"))
    }

    @Test
    fun throttleTracksRequestCountsAlongsideThePacing() = runTest {
        val throttle = RequestThrottle()
        assertEquals(RequestCounts(0, 0), throttle.counts("p"))
        repeat(3) { throttle.waitForSlot("p", maxPerMinute = 0) { } }
        val counts = throttle.counts("p")
        assertEquals(3, counts.today)
        assertEquals(3, counts.lastMinute)
        // A different provider's traffic is counted separately.
        assertEquals(RequestCounts(0, 0), throttle.counts("other"))
    }
}
