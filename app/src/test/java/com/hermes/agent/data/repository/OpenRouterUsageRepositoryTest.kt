package com.hermes.agent.data.repository

import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OpenRouterUsageRepositoryTest {
    private lateinit var server: MockWebServer
    private val repo = OpenRouterUsageRepository(OkHttpClient())

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun provider(type: ProviderType = ProviderType.OPENROUTER, key: String = "sk-or-test") = Provider(
        id = "p1", name = "P", type = type, apiKey = key, baseUrl = server.url("/v1").toString(), models = listOf("m"), isEnabled = true, priority = 0
    )

    @Test
    fun fetchesAndAuthenticatesWithTheProviderSOwnKey() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"usage":2.5,"rate_limit":{"requests":20,"interval":"10s"}}}"""))
        val usage = repo.usage(provider())
        assertEquals(2.5, usage!!.usageUsd!!, 0.0001)
        assertEquals(20, usage.rateLimitRequests)
        val request = server.takeRequest()
        assertEquals("/v1/auth/key", request.path)
        assertEquals("Bearer sk-or-test", request.getHeader("Authorization"))
    }

    @Test
    fun cachesForAMinuteInsteadOfCallingEveryTime() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"usage":1.0}}"""))
        val p = provider()
        repo.usage(p)
        repo.usage(p) // same provider again, right away: should reuse the cached result, not a second request
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aFailedRequestReturnsNullInsteadOfThrowing() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertNull(repo.usage(provider()))
    }

    @Test
    fun onlyOpenRouterIsSupported() = runBlocking {
        assertNull(repo.usage(provider(ProviderType.OPENAI)))
        assertEquals(0, server.requestCount) // never even tried to call out for a provider this endpoint doesn't apply to
    }

    @Test
    fun noKeyMeansNoCall() = runBlocking {
        assertNull(repo.usage(provider(key = "")))
        assertEquals(0, server.requestCount)
    }
}
