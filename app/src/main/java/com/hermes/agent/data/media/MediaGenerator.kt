package com.hermes.agent.data.media

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.data.repository.ModelCatalogRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class GeneratedMedia(val bytes: ByteArray, val mime: String?, val model: String, val provider: String, val text: String = "")

/** Image and speech generation through whichever configured provider supports it. */
@Singleton
class MediaGenerator @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
    private val catalog: ModelCatalogRepository
) {
    private val client get() = okHttpClient.newBuilder().callTimeout(3, TimeUnit.MINUTES).build()

    /** Null when no enabled provider can make images; the caller then falls back to Termux tools. */
    suspend fun imageTarget(): Pair<Provider, String>? {
        val s = settings.settings.first()
        val enabled = providers.getEnabled().filter { !it.isHermes && it.type != ProviderType.ANTHROPIC }
        enabled.firstOrNull { it.id == s.imageProviderId }?.let { p ->
            return p to s.imageModel.ifBlank { defaultImageModel(p) ?: return null }
        }
        for (p in enabled) defaultImageModel(p)?.let { return p to it }
        return null
    }

    private suspend fun defaultImageModel(p: Provider): String? = when (p.type) {
        ProviderType.OPENROUTER -> runCatching {
            catalog.fetch(p.type, p.effectiveBaseUrl, p.apiKey, includeNonChat = true).firstOrNull { it.outputsImage }?.id
        }.getOrNull()
        ProviderType.OPENAI -> "gpt-image-1"
        else -> null
    }

    suspend fun generateImage(prompt: String, size: String?): GeneratedMedia {
        val (provider, model) = imageTarget()
            ?: throw IllegalStateException("No provider that can generate images is set up. Choose one under Settings → Media, or create the image with Termux tools.")
        return if (provider.type == ProviderType.OPENROUTER) openRouterImage(provider, model, prompt)
        else imagesEndpoint(provider, model, prompt, size)
    }

    /** OpenRouter returns images on the chat endpoint when asked for the image modality. */
    private suspend fun openRouterImage(p: Provider, model: String, prompt: String): GeneratedMedia = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", JsonArray().apply { add(JsonObject().apply { addProperty("role", "user"); addProperty("content", prompt) }) })
            add("modalities", JsonArray().apply { add("image"); add("text") })
        }
        val root = post(p, "/chat/completions", body)
        val message = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("message")
            ?: throw IOException("The model returned no message.")
        val url = message.getAsJsonArray("images")?.firstOrNull()?.asJsonObject?.getAsJsonObject("image_url")?.get("url")?.asString
            ?: throw IOException("$model answered without an image: ${message.get("content")?.takeIf { it.isJsonPrimitive }?.asString?.take(200)}")
        val (bytes, mime) = decodeDataUrl(url) ?: (download(url) to null)
        GeneratedMedia(bytes, mime, model, p.name, message.get("content")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty())
    }

    /** OpenAI-style POST /images/generations (OpenAI, Together, and compatible endpoints). */
    private suspend fun imagesEndpoint(p: Provider, model: String, prompt: String, size: String?): GeneratedMedia = withContext(Dispatchers.IO) {
        val body = JsonObject().apply {
            addProperty("model", model); addProperty("prompt", prompt); addProperty("n", 1)
            size?.let { addProperty("size", it) }
        }
        val item = post(p, "/images/generations", body).getAsJsonArray("data")?.firstOrNull()?.asJsonObject
            ?: throw IOException("The provider returned no image.")
        val bytes = item.get("b64_json")?.asString?.let { Base64.getDecoder().decode(it) }
            ?: item.get("url")?.asString?.let(::download)
            ?: throw IOException("The provider returned neither image data nor a link.")
        GeneratedMedia(bytes, null, model, p.name, item.get("revised_prompt")?.asString.orEmpty())
    }

    /** Null when no provider is set for speech; the caller then falls back to espeak in Termux. */
    suspend fun speechTarget(): Provider? {
        val s = settings.settings.first()
        val enabled = providers.getEnabled()
        return enabled.firstOrNull { it.id == s.ttsProviderId } ?: enabled.firstOrNull { it.type == ProviderType.OPENAI }
    }

    suspend fun speech(text: String, voice: String?): GeneratedMedia = withContext(Dispatchers.IO) {
        val p = speechTarget() ?: throw IllegalStateException("No speech provider is set up.")
        val s = settings.settings.first()
        val model = s.ttsModel.ifBlank { "gpt-4o-mini-tts" }
        val body = JsonObject().apply {
            addProperty("model", model); addProperty("input", text)
            addProperty("voice", voice ?: s.ttsVoice.ifBlank { "alloy" }); addProperty("response_format", "mp3")
        }
        val request = Request.Builder().url("${p.effectiveBaseUrl.trimEnd('/')}/audio/speech")
            .header("Authorization", "Bearer ${p.apiKey}").post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Speech failed: HTTP ${r.code} ${r.body?.string()?.take(200).orEmpty()}")
            GeneratedMedia(r.body!!.bytes(), "audio/mpeg", model, p.name)
        }
    }

    private fun post(p: Provider, path: String, body: JsonObject): JsonObject {
        val request = Request.Builder().url(p.effectiveBaseUrl.trimEnd('/') + path)
            .header("Authorization", "Bearer ${p.apiKey}")
            .apply { if (p.type == ProviderType.OPENROUTER) header("X-Title", "Hermes Agent Android") }
            .post(body.toString().toRequestBody(JSON)).build()
        client.newCall(request).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JsonParser.parseString(text).asJsonObject.getAsJsonObject("error").get("message").asString }.getOrNull()
                throw IOException("HTTP ${r.code}: ${msg ?: text.take(200)}")
            }
            return JsonParser.parseString(text).asJsonObject
        }
    }

    private fun download(url: String): ByteArray = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw IOException("Couldn't fetch the generated file: HTTP ${r.code}")
        r.body!!.bytes()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** "data:image/png;base64,...." -> bytes and mime; null for ordinary URLs. */
        fun decodeDataUrl(url: String): Pair<ByteArray, String>? {
            val m = Regex("^data:([^;,]+)(;base64)?,(.*)$", RegexOption.DOT_MATCHES_ALL).find(url) ?: return null
            val data = m.groupValues[3]
            val bytes = if (m.groupValues[2].isNotEmpty()) Base64.getDecoder().decode(data.trim()) else java.net.URLDecoder.decode(data, "UTF-8").toByteArray()
            return bytes to m.groupValues[1]
        }
    }
}
