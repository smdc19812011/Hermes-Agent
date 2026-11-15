package com.hermes.agent.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What OpenRouter's own "/auth/key" endpoint reports about the key this provider uses. */
data class OpenRouterUsage(
    val usageUsd: Double?,
    val limitUsd: Double?,
    val isFreeTier: Boolean?,
    val rateLimitRequests: Int?,
    val rateLimitInterval: String?
)

/** Pure parsing of the "/auth/key" response, kept free of networking so it's unit-tested. */
object OpenRouterUsageParsing {
    fun parse(body: String): OpenRouterUsage {
        val data = JsonParser.parseString(body).asJsonObject.getAsJsonObject("data") ?: JsonObject()
        val rateLimit = data.getAsJsonObject("rate_limit")
        return OpenRouterUsage(
            usageUsd = data.get("usage")?.takeIf { it.isJsonPrimitive }?.asDouble,
            limitUsd = data.get("limit")?.takeIf { it.isJsonPrimitive }?.asDouble, // null in the response means "no limit"
            isFreeTier = data.get("is_free_tier")?.takeIf { it.isJsonPrimitive }?.asBoolean,
            rateLimitRequests = rateLimit?.get("requests")?.takeIf { it.isJsonPrimitive }?.asInt,
            rateLimitInterval = rateLimit?.get("interval")?.takeIf { it.isJsonPrimitive }?.asString
        )
    }
}

/**
 * Connects to OpenRouter's own account/key-usage reporting (GET /auth/key, the same API key the provider already
 * uses) so the app can show its configured rate limit and overall usage, not just what this app itself has counted.
 * Only OpenRouter publishes this exact endpoint, so it's a no-op for every other provider type.
 */
@Singleton
class OpenRouterUsageRepository @Inject constructor(private val okHttpClient: OkHttpClient) {
    private val cache = ConcurrentHashMap<String, Pair<Long, OpenRouterUsage>>()

    /** Cached for a minute so the per-minute counter next to it doesn't trigger a fresh network call on every tick. */
    suspend fun usage(provider: Provider): OpenRouterUsage? {
        if (provider.type != ProviderType.OPENROUTER || provider.apiKey.isBlank()) return null
        cache[provider.id]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        return runCatching { fetch(provider) }.getOrNull()?.also { cache[provider.id] = System.currentTimeMillis() to it }
    }

    private suspend fun fetch(provider: Provider): OpenRouterUsage = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("${provider.effectiveBaseUrl.trimEnd('/')}/auth/key")
            .header("Authorization", "Bearer ${provider.apiKey.trim()}")
            .build()
        val client = okHttpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        client.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            OpenRouterUsageParsing.parse(body)
        }
    }
}
