package com.hermes.agent.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterUsageParsingTest {
    @Test
    fun parsesAFullKeyInfoResponse() {
        val body = """
            {"data":{"label":"sk-or-...","usage":1.23,"limit":10.0,"is_free_tier":false,
             "rate_limit":{"requests":20,"interval":"10s"},
             "free_model_daily_requests":{"limit":50,"remaining":38,"used":12}}}
        """.trimIndent()
        val usage = OpenRouterUsageParsing.parse(body)
        assertEquals(1.23, usage.usageUsd!!, 0.0001)
        assertEquals(10.0, usage.limitUsd!!, 0.0001)
        assertEquals(false, usage.isFreeTier)
        assertEquals(20, usage.rateLimitRequests)
        assertEquals("10s", usage.rateLimitInterval)
        assertEquals(12, usage.freeModelDailyUsed)
        assertEquals(50, usage.freeModelDailyLimit)
    }

    @Test
    fun aNullLimitMeansUnlimitedNotZero() {
        val body = """{"data":{"usage":0.0,"limit":null,"is_free_tier":true,"rate_limit":{"requests":1,"interval":"1d"}}}"""
        val usage = OpenRouterUsageParsing.parse(body)
        assertNull(usage.limitUsd)
        assertEquals(true, usage.isFreeTier)
    }

    @Test
    fun missingFieldsDontCrashTheParse() {
        val usage = OpenRouterUsageParsing.parse("""{"data":{}}""")
        assertNull(usage.usageUsd)
        assertNull(usage.rateLimitRequests)
        assertNull(usage.rateLimitInterval)
        assertNull(usage.freeModelDailyUsed)
        assertNull(usage.freeModelDailyLimit)
    }

    @Test
    fun ignoresTheDeprecatedRateLimitSentinelOfMinusOne() {
        // OpenRouter's docs say rate_limit.requests "will always return -1" now that it's deprecated; treat that as unknown
        // rather than showing "limit -1" on screen.
        val body = """{"data":{"rate_limit":{"requests":-1,"interval":"1h"}}}"""
        val usage = OpenRouterUsageParsing.parse(body)
        assertNull(usage.rateLimitRequests)
        assertNull(usage.rateLimitInterval)
    }

    @Test
    fun parsesTheFreeModelDailyRequestCounterOpenRoutersOwnTodayFigure() {
        val body = """{"data":{"free_model_daily_requests":{"limit":50,"remaining":38,"used":12}}}"""
        val usage = OpenRouterUsageParsing.parse(body)
        assertEquals(12, usage.freeModelDailyUsed)
        assertEquals(50, usage.freeModelDailyLimit)
    }
}
