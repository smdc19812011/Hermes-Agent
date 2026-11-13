package com.hermes.agent.data.repository

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.hermes.agent.data.remote.dto.*
import com.hermes.agent.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LLMRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    /** Emits text tokens as they arrive; throws on transport or HTTP failure. */
    fun streamCompletion(
        provider: Provider,
        messages: List<Message>,
        systemPrompt: String?,
        timeoutSeconds: Int
    ): Flow<String> {
        val request = when (provider.apiMode) {
            ApiMode.OPENAI_COMPATIBLE -> openAIRequest(provider, messages, systemPrompt)
            ApiMode.ANTHROPIC -> anthropicRequest(provider, messages, systemPrompt)
        }
        val extractor: (String) -> String? = when (provider.apiMode) {
            ApiMode.OPENAI_COMPATIBLE -> ::extractOpenAIToken
            ApiMode.ANTHROPIC -> ::extractAnthropicToken
        }
        val client = okHttpClient.newBuilder()
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()
        return sseFlow(client, request, extractor).flowOn(Dispatchers.IO)
    }

    private fun openAIRequest(provider: Provider, messages: List<Message>, systemPrompt: String?): Request {
        val apiMessages = buildList {
            if (!systemPrompt.isNullOrBlank()) add(OpenAIMessage("system", systemPrompt))
            messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
                .forEach { add(OpenAIMessage(it.role.name.lowercase(), it.content)) }
        }
        val body = gson.toJson(OpenAIChatRequest(model = provider.defaultModel, messages = apiMessages))
        return Request.Builder()
            .url("${provider.effectiveBaseUrl.trimEnd('/')}/chat/completions")
            .header("Accept", "text/event-stream")
            .apply {
                if (provider.apiKey.isNotBlank()) header("Authorization", "Bearer ${provider.apiKey}")
                if (provider.type == ProviderType.OPENROUTER) {
                    header("HTTP-Referer", "https://github.com/nousresearch/hermes-agent")
                    header("X-Title", "Hermes Agent Android")
                }
            }
            .post(body.toRequestBody(JSON))
            .build()
    }

    private fun anthropicRequest(provider: Provider, messages: List<Message>, systemPrompt: String?): Request {
        val anthropicMessages = messages
            .filter { it.role == Role.USER || it.role == Role.ASSISTANT }
            .map { AnthropicMessage(it.role.name.lowercase(), it.content) }
        val body = gson.toJson(
            AnthropicRequest(
                model = provider.defaultModel,
                messages = anthropicMessages,
                system = systemPrompt?.takeIf { it.isNotBlank() }
            )
        )
        return Request.Builder()
            .url("${provider.effectiveBaseUrl.trimEnd('/')}/messages")
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON))
            .build()
    }

    private fun extractOpenAIToken(data: String): String? {
        if (data == "[DONE]") return null
        return runCatching {
            gson.fromJson(data, OpenAIChatResponse::class.java).choices?.firstOrNull()?.delta?.content
        }.getOrNull()
    }

    private fun extractAnthropicToken(data: String): String? = runCatching {
        val event = gson.fromJson(data, AnthropicStreamEvent::class.java)
        if (event.type == "error") throw IOException(event.error?.message ?: "Anthropic stream error")
        if (event.type == "content_block_delta") event.delta?.text else null
    }.getOrElse { if (it is IOException) throw it else null }

    private fun sseFlow(
        client: OkHttpClient,
        request: Request,
        extractor: (String) -> String?
    ): Flow<String> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    extractor(data)?.takeIf { it.isNotEmpty() }?.let { trySend(it) }
                } catch (e: IOException) {
                    close(e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(IOException(describeFailure(t, response), t))
            }
        }
        val source = EventSources.createFactory(client).newEventSource(request, listener)
        awaitClose { source.cancel() }
    }

    private fun describeFailure(t: Throwable?, response: Response?): String {
        if (response == null) return t?.message ?: "Network error"
        val body = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val apiMessage = runCatching {
            val root = JsonParser.parseString(body).asJsonObject
            root.getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        return "HTTP ${response.code}: ${apiMessage ?: body.take(200).ifBlank { response.message }}"
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
