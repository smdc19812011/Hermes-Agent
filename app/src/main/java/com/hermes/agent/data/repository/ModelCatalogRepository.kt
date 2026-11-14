package com.hermes.agent.data.repository

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.model.ApiMode
import com.hermes.agent.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** [supportsTools] is null when the provider doesn't say (only OpenRouter publishes it). */
data class ModelInfo(
    val id: String,
    val label: String?,
    val contextLength: Int?,
    val supportsTools: Boolean? = null,
    val outputsImage: Boolean = false,
    /** Null when the provider doesn't publish input modalities (only OpenRouter does). */
    val inputsImage: Boolean? = null,
    val inputsAudio: Boolean? = null,
    /** Price per prompt token in USD; 0 for free models, null when unknown. */
    val promptPrice: Double? = null
)

@Singleton
class ModelCatalogRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    /** OpenRouter publishes its catalog without a key; everyone else needs one (custom endpoints may not). */
    fun canFetchWithoutKey(type: ProviderType) = type == ProviderType.OPENROUTER || type == ProviderType.CUSTOM

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ModelInfo>>>()

    /** Like [fetch], but reuses a list fetched in the last hour; used on every message, so it must be cheap. */
    suspend fun cached(type: ProviderType, baseUrl: String, apiKey: String): List<ModelInfo> {
        val key = "$type|${baseUrl.trimEnd('/')}"
        cache[key]?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return it.second }
        return fetch(type, baseUrl, apiKey, includeNonChat = true).also { cache[key] = System.currentTimeMillis() to it }
    }

    suspend fun fetch(type: ProviderType, baseUrl: String, apiKey: String, includeNonChat: Boolean = false): List<ModelInfo> = withContext(Dispatchers.IO) {
        val base = baseUrl.trim().trimEnd('/')
        val request = Request.Builder()
            .url("$base/models" + if (type.apiMode == ApiMode.ANTHROPIC) "?limit=1000" else "")
            .apply {
                when {
                    apiKey.isBlank() -> Unit
                    type.apiMode == ApiMode.ANTHROPIC -> header("x-api-key", apiKey.trim()).header("anthropic-version", "2023-06-01")
                    else -> header("Authorization", "Bearer ${apiKey.trim()}")
                }
            }
            .build()
        val client = okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        val body = client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(errorMessage(r.code, text))
            text
        }
        val models = parseCatalog(body, includeNonChat)
        // OpenRouter lists newest first, which is the useful order; other APIs return no meaningful order.
        if (type == ProviderType.OPENROUTER) models else models.sortedBy { it.id.lowercase() }
    }

    private fun errorMessage(code: Int, body: String): String {
        val apiMessage = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        return when (code) {
            401, 403 -> "The API key was rejected (HTTP $code)."
            404 -> "This endpoint has no model list (HTTP 404). Add model IDs by hand."
            else -> "HTTP $code${apiMessage?.let { ": $it" }.orEmpty()}"
        }
    }

    companion object {
        /** Parses an OpenAI-style `{data: [...]}`, Together's bare array, or Anthropic's list; drops non-chat models. */
        fun parseCatalog(body: String, includeNonChat: Boolean = false): List<ModelInfo> =
            parse(JsonParser.parseString(body), includeNonChat).filter { includeNonChat || isChatModel(it) }

        private fun parse(root: JsonElement, includeNonChat: Boolean = false): List<ModelInfo> {
            val array = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.get("data")?.isJsonArray == true -> root.asJsonObject.getAsJsonArray("data")
                else -> throw IOException("Unexpected response from the models endpoint")
            }
            return array.mapNotNull { el ->
                val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: return@mapNotNull null
                ModelInfo(
                    id = id,
                    label = (o.str("name") ?: o.str("display_name"))?.takeIf { it != id },
                    contextLength = o.get("context_length")?.takeIf { it.isJsonPrimitive }?.asInt,
                    supportsTools = o.getAsJsonArray("supported_parameters")?.any { it.isJsonPrimitive && it.asString == "tools" },
                    outputsImage = o.getAsJsonObject("architecture")?.getAsJsonArray("output_modalities")
                        ?.any { it.isJsonPrimitive && it.asString == "image" } == true,
                    inputsImage = o.getAsJsonObject("architecture")?.getAsJsonArray("input_modalities")
                        ?.any { it.isJsonPrimitive && it.asString == "image" },
                    inputsAudio = o.getAsJsonObject("architecture")?.getAsJsonArray("input_modalities")
                        ?.any { it.isJsonPrimitive && it.asString == "audio" },
                    promptPrice = o.getAsJsonObject("pricing")?.get("prompt")?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
                ).takeIf { includeNonChat || o.str("type")?.lowercase() !in NON_CHAT_TYPES }
            }
        }

        private fun isChatModel(m: ModelInfo): Boolean {
            val id = m.id.lowercase()
            return NON_CHAT_ID_MARKERS.none { it in id }
        }

        private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

        private val NON_CHAT_TYPES = setOf("embedding", "embeddings", "image", "audio", "moderation", "rerank", "transcribe")
        private val NON_CHAT_ID_MARKERS = listOf("embed", "whisper", "tts", "dall-e", "moderation", "transcri")
    }
}
