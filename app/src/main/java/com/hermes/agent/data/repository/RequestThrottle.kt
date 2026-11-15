package com.hermes.agent.data.repository

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
