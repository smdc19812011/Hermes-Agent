package com.hermes.agent.data.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Pure timing math behind [RequestThrottle] and the 429 backoff, kept Android-free so it's unit-tested. */
object ThrottleLogic {
    /** When the next request to a provider may go out, given the last one and a "requests per minute" cap (0/negative = unlimited). */
    fun nextAllowedAt(lastSentAtMs: Long, maxPerMinute: Int, nowMs: Long): Long {
        if (maxPerMinute <= 0) return nowMs
        return maxOf(nowMs, lastSentAtMs + 60_000L / maxPerMinute)
    }

    /** OpenRouter and most others report a rate limit as "HTTP 429: …"; this is deliberately forgiving about the rest. */
    fun isRateLimited(message: String?): Boolean = message != null && Regex("""\bHTTP\s*429\b""").containsMatchIn(message)
}

/**
 * Paces outbound requests per provider to a "requests per minute" setting the user controls, so a free model's own
 * limit is respected before it ever answers with 429, instead of firing every tool round back to back.
 */
@Singleton
class RequestThrottle @Inject constructor() {
    private val lastSentAt = ConcurrentHashMap<String, Long>()

    /** Waits if needed so calls keyed by [key] stay spaced to [maxPerMinute]; [onWaiting] fires once, before the wait, with the resume time. */
    suspend fun waitForSlot(key: String, maxPerMinute: Int, onWaiting: suspend (resumeAtMs: Long) -> Unit) {
        val now = System.currentTimeMillis()
        val allowedAt = ThrottleLogic.nextAllowedAt(lastSentAt[key] ?: 0L, maxPerMinute, now)
        if (allowedAt > now) {
            onWaiting(allowedAt)
            kotlinx.coroutines.delay(allowedAt - now)
        }
        lastSentAt[key] = System.currentTimeMillis()
    }
}
