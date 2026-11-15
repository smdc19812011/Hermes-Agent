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

    /** Parses OpenRouter-style intervals ("10s", "1m", "1h", "1d") into milliseconds; null if unrecognized. */
    fun parseIntervalMs(interval: String?): Long? {
        val match = interval?.trim()?.let { Regex("""^(\d+)([smhd])$""").find(it) } ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val unitMs = when (match.groupValues[2]) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            else -> return null
        }
        return amount * unitMs
    }

    /**
     * The on-screen "N today · M/<window>" line. [today]/[todayLimit] reflect the day's count actually shown —
     * OpenRouter's own "free_model_daily_requests" figure when known, so "today" means OpenRouter's own count rather
     * than just what this app has observed locally; otherwise this app's own local count, with no limit shown.
     * [windowCount]/[windowLabel] are this app's own locally paced window; [rateLimitRequests] is OpenRouter's
     * configured per-window cap, appended when known (its own "rate_limit" field is deprecated and often absent).
     */
    fun formatRequestStats(today: Int, todayLimit: Int?, windowCount: Int, windowLabel: String, rateLimitRequests: Int?): String {
        val todayPart = if (todayLimit != null) "$today/$todayLimit today" else "$today today"
        val limit = rateLimitRequests?.let { " (limit $it)" }.orEmpty()
        return "$todayPart · $windowCount/$windowLabel$limit"
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

    /** How many requests this app has sent to [key] so far today (device's time zone) and in the trailing [windowMs]. */
    fun counts(key: String, nowMs: Long = System.currentTimeMillis(), windowMs: Long = 60_000L): RequestCounts {
        val list = history[key] ?: return RequestCounts(0, 0)
        val snapshot = synchronized(list) { list.toList() }
        return RequestCounts(
            today = snapshot.count { ThrottleLogic.isSameLocalDay(it, nowMs) },
            lastMinute = ThrottleLogic.countInWindow(snapshot, nowMs, windowMs)
        )
    }
}
