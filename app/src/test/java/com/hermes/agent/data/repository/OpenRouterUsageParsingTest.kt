package com.hermes.agent.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterUsageParsingTest {
    @Test
    fun parsesAFullKeyInfoResponse() {
        val body = """
            {"data":{"label":"sk-or-...","usage":1.23,"limit":10.0,"is_free_tier":false,
             "rate_limit":{"requests":20,"interval":"10s"}}}
        """.trimIndent()
        val usage = OpenRouterUsageParsing.parse(body)
        assertEquals(1.23, usage.usageUsd!!, 0.0001)
        assertEquals(10.0, usage.limitUsd!!, 0.0001)
        assertEquals(false, usage.isFreeTier)
        assertEquals(20, usage.rateLimitRequests)
        assertEquals("10s", usage.rateLimitInterval)
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
    }
}
