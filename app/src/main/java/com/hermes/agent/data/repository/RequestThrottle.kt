package com.hermes.agent.data.repository

import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Pure timing math behind [RequestThrottle] and the provider-error backoff, kept Android-free so it's unit-tested. */
object ThrottleLogic {
    /** When the next request to a provider may go out, given the last one and a "requests per minute" cap (0/negative = unlimited). */
    fun nextAllowedAt(lastSentAtMs: Long, maxPerMinute: Int, nowMs: Long): Long {
        if (maxPerMinute <= 0) return nowMs
        return maxOf(nowMs, lastSentAtMs + 60_000L / maxPerMinute)
    }

    /** HTTP codes a wait can never fix: the key or the request itself is wrong, not the server being busy or full. */
    private val NON_RETRIABLE_CODES = setOf(401, 403, 404)

    /**
     * Whether a failure from a provider (rate limited, overloaded, a 5xx, a dropped connection, a timeout…) is worth
     * waiting a minute and trying again for, rather than giving up on this model right away. An error with no HTTP
     * code at all (a network blip, a malformed response) is treated as worth retrying too.
     */
    fun isRetriableProviderError(message: String?): Boolean {
        if (message == null) return false
        val code = Regex("""\bHTTP\s*(\d{3})\b""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        return code == null || code !in NON_RETRIABLE_CODES
    }

    /** Same calendar day in the device's own time zone — "today" means the user's today, not UTC's. */
    fun isSameLocalDay(aMs: Long, bMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(aMs).atZone(zone).toLocalDate() == Instant.ofEpochMilli(bMs).atZone(zone).toLocalDate()

    /** How many of [timestamps] fall in the trailing [windowMs] ending at [nowMs]. */
    fun countInWindow(timestamps: List<Long>, nowMs: Long, windowMs: Long): Int = timestamps.count { nowMs - it in 0..windowMs }

    /** The on-screen "N today · M/min" line; appends the provider's own configured rate limit when known. */
    fun formatRequestStats(today: Int, lastMinute: Int, rateLimitRequests: Int?, rateLimitInterval: String?): String {
        val limit = rateLimitRequests?.let { reqs -> rateLimitInterval?.let { iv -> " · limit $reqs/$iv" } }.orEmpty()
        return "$today today · $lastMinute/min$limit"
    }
}

data class RequestCounts(val today: Int, val lastMinute: Int)

/**
 * Paces outbound requests per provider to a "requests per minute" setting the user controls, so a free model's own
 * limit is respected before it ever answers with 429, instead of firing every tool round back to back.
 */
@Singleton
class RequestThrottle @Inject constructor() {
    private val lastSentAt = ConcurrentHashMap<String, Long>()
    /** Every request this app has sent to each provider, kept to look back at most a day; resets when the app process does. */
    private val history = ConcurrentHashMap<String, MutableList<Long>>()

    /** Waits if needed so calls keyed by [key] stay spaced to [maxPerMinute]; [onWaiting] fires once, before the wait, with the resume time. */
    suspend fun waitForSlot(key: String, maxPerMinute: Int, onWaiting: suspend (resumeAtMs: Long) -> Unit) {
        val now = System.currentTimeMillis()
        val allowedAt = ThrottleLogic.nextAllowedAt(lastSentAt[key] ?: 0L, maxPerMinute, now)
        if (allowedAt > now) {
            onWaiting(allowedAt)
            kotlinx.coroutines.delay(allowedAt - now)
        }
        val sentAt = System.currentTimeMillis()
        lastSentAt[key] = sentAt
        record(key, sentAt)
    }

    private fun record(key: String, atMs: Long) {
        val list = history.getOrPut(key) { java.util.Collections.synchronizedList(mutableListOf()) }
        synchronized(list) {
            list.add(atMs)
            list.removeAll { atMs - it > 24 * 60 * 60_000L } // a day's worth is all "today" can ever need
        }
    }

    /** How many requests this app has sent to [key] so far today (device's time zone) and in the last 60 seconds. */
    fun counts(key: String, nowMs: Long = System.currentTimeMillis()): RequestCounts {
        val list = history[key] ?: return RequestCounts(0, 0)
        val snapshot = synchronized(list) { list.toList() }
        return RequestCounts(
            today = snapshot.count { ThrottleLogic.isSameLocalDay(it, nowMs) },
            lastMinute = ThrottleLogic.countInWindow(snapshot, nowMs, 60_000L)
        )
    }
}
