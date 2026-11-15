package com.hermes.agent.data.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.model.*
import com.hermes.agent.domain.tools.AgentTool
import com.hermes.agent.domain.tools.ChatTurn
import com.hermes.agent.domain.tools.InputPart
import com.hermes.agent.domain.tools.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

sealed class StreamEvent {
    data class Text(val text: String) : StreamEvent()
    data class Tool(val activity: ToolActivity) : StreamEvent()
    data class Approval(val request: ApprovalRequest) : StreamEvent()
    /** The model wants these tools run; sent once, when its turn ends. */
    data class ToolCalls(val calls: List<ToolCall>) : StreamEvent()
    /** An image the model itself produced: a data: URL or a link. */
    data class Image(val url: String) : StreamEvent()
}

/** Parses one provider's SSE stream; holds the partial tool calls between events. */
private interface StreamParser {
    fun onEvent(type: String?, data: String): List<StreamEvent>
    fun onEnd(): List<StreamEvent>
}

@Singleton
class LLMRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    /**
     * Streams one model turn. [tools] are offered to the model; when it calls them the flow ends with
     * [StreamEvent.ToolCalls] and the caller runs them and calls again with the results appended.
     * For Hermes providers [sessionId] goes in X-Hermes-Session-Id; Hermes then keeps the transcript
     * itself and ignores history in the body, so only the newest user turn is sent.
     */
    fun streamTurn(
        provider: Provider,
        model: String,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        tools: List<AgentTool>,
        timeoutSeconds: Int,
        sessionId: String,
        /** Keeps the tool list (history refers to it) but tells the model to answer in text, not call another tool. */
        finalAnswer: Boolean = false
    ): Flow<StreamEvent> {
        val (request, parser) = when (provider.apiMode) {
            ApiMode.OPENAI_COMPATIBLE -> openAIRequest(provider, model, turns, systemPrompt, tools, sessionId, finalAnswer) to OpenAIParser()
            ApiMode.ANTHROPIC -> anthropicRequest(provider, model, turns, systemPrompt, tools, finalAnswer) to AnthropicParser()
        }
        // Agent turns can run for minutes; servers send keepalives, so the read timeout only trips on a dead link.
        val client = okHttpClient.newBuilder().readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS).build()
        return sseFlow(client, request, parser).flowOn(Dispatchers.IO)
    }

    suspend fun answerApproval(provider: Provider, runId: String, choice: String) = withContext(Dispatchers.IO) {
        val body = gson.toJson(mapOf("choice" to choice)).toRequestBody(JSON)
        val request = Request.Builder()
            .url("${provider.effectiveBaseUrl.trimEnd('/')}/runs/$runId/approval")
            .header("Authorization", "Bearer ${provider.apiKey}")
            .post(body)
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException(describeFailure(null, response))
        }
    }

    // ---- OpenAI-compatible ----

    private fun openAIRequest(
        provider: Provider,
        model: String,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        tools: List<AgentTool>,
        sessionId: String,
        finalAnswer: Boolean = false
    ): Request {
        val sent = if (provider.isHermes) turns.filterIsInstance<ChatTurn.User>().takeLast(1) else turns
        val messages = JsonArray().apply {
            if (!systemPrompt.isNullOrBlank()) add(obj("role" to "system", "content" to systemPrompt))
            sent.forEach { turn ->
                when (turn) {
                    is ChatTurn.User -> add(JsonObject().apply { addProperty("role", "user"); add("content", openAIUserContent(turn)) })
                    is ChatTurn.Assistant -> add(JsonObject().apply {
                        addProperty("role", "assistant")
                        if (turn.text.isNotEmpty() || turn.toolCalls.isEmpty()) addProperty("content", turn.text) else add("content", null)
                        if (turn.toolCalls.isNotEmpty()) add("tool_calls", JsonArray().apply {
                            turn.toolCalls.forEach { c ->
                                add(JsonObject().apply {
                                    addProperty("id", c.id)
                                    addProperty("type", "function")
                                    add("function", obj("name" to c.name, "arguments" to c.arguments))
                                })
                            }
                        })
                    })
                    is ChatTurn.ToolResult -> add(obj("role" to "tool", "tool_call_id" to turn.callId, "content" to turn.content))
                }
            }
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("stream", true)
            add("messages", messages)
            // OpenRouter only returns images from image-output models when asked for that modality.
            if (provider.type == ProviderType.OPENROUTER && "image" in model.lowercase()) {
                add("modalities", JsonArray().apply { add("image"); add("text") })
            }
            if (tools.isNotEmpty()) add("tools", JsonArray().apply {
                tools.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", t.name)
                            addProperty("description", t.description)
                            add("parameters", t.parameters)
                        })
                    })
                }
            })
            if (finalAnswer && tools.isNotEmpty()) addProperty("tool_choice", "none")
        }
        return Request.Builder()
            .url("${provider.effectiveBaseUrl.trimEnd('/')}/chat/completions")
            .header("Accept", "text/event-stream")
            .apply {
                if (provider.apiKey.isNotBlank()) header("Authorization", "Bearer ${provider.apiKey}")
                if (provider.type == ProviderType.OPENROUTER) {
                    header("HTTP-Referer", "https://github.com/nousresearch/hermes-agent")
                    header("X-Title", "Hermes Agent Android")
                }
                if (provider.isHermes) header("X-Hermes-Session-Id", sessionId)
            }
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    /** Plain string when there's nothing but text; otherwise OpenAI content parts (also read by OpenRouter and Hermes). */
    private fun openAIUserContent(turn: ChatTurn.User): com.google.gson.JsonElement {
        if (turn.parts.isEmpty()) return com.google.gson.JsonPrimitive(turn.text)
        return JsonArray().apply {
            turn.parts.forEach { part ->
                add(when (part) {
                    is InputPart.Image -> JsonObject().apply {
                        addProperty("type", "image_url")
                        add("image_url", obj("url" to "data:${part.mime};base64,${part.base64}"))
                    }
                    is InputPart.Document -> JsonObject().apply {
                        addProperty("type", "file")
                        add("file", obj("filename" to part.name, "file_data" to "data:${part.mime};base64,${part.base64}"))
                    }
                    is InputPart.Audio -> JsonObject().apply {
                        addProperty("type", "input_audio")
                        add("input_audio", obj("data" to part.base64, "format" to part.format))
                    }
                })
            }
            if (turn.text.isNotBlank()) add(obj("type" to "text", "text" to turn.text))
        }
    }

    /** Anthropic content blocks: images and PDFs as base64 sources; audio isn't accepted, so it's described in text upstream. */
    private fun anthropicUserContent(turn: ChatTurn.User): com.google.gson.JsonElement {
        if (turn.parts.isEmpty()) return com.google.gson.JsonPrimitive(turn.text)
        return JsonArray().apply {
            turn.parts.forEach { part ->
                when (part) {
                    is InputPart.Image -> add(JsonObject().apply {
                        addProperty("type", "image")
                        add("source", obj("type" to "base64", "media_type" to part.mime, "data" to part.base64))
                    })
                    is InputPart.Document -> add(JsonObject().apply {
                        addProperty("type", "document")
                        add("source", obj("type" to "base64", "media_type" to part.mime, "data" to part.base64))
                    })
                    is InputPart.Audio -> Unit
                }
            }
            add(obj("type" to "text", "text" to turn.text.ifBlank { "(see attachment)" }))
        }
    }

    private inner class OpenAIParser : StreamParser {
        private val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>() // id, name, args
        private val textCalls = TextToolCalls.Filter() // models that write tool calls as text instead of using the field

        override fun onEvent(type: String?, data: String): List<StreamEvent> = when (type) {
            "hermes.tool.progress" -> listOfNotNull(parseToolProgress(data)?.let { StreamEvent.Tool(it) })
            "approval.request" -> listOfNotNull(parseApproval(data)?.let { StreamEvent.Approval(it) })
            null, "message" -> parseChunk(data)
            else -> emptyList()
        }

        private fun parseChunk(data: String): List<StreamEvent> {
            if (data == "[DONE]") return emptyList()
            val root = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return emptyList()
            root.getAsJsonObject("error")?.let { throw IOException(it.str("message") ?: "Provider error mid-stream") }
            val delta = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.getAsJsonObject("delta") ?: return emptyList()
            delta.getAsJsonArray("tool_calls")?.forEach { el ->
                val tc = el.asJsonObject
                val index = tc.get("index")?.asInt ?: calls.size
                val acc = calls.getOrPut(index) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                tc.str("id")?.let { if (acc.first.isEmpty()) acc.first.append(it) }
                tc.getAsJsonObject("function")?.let { f ->
                    f.str("name")?.let { if (acc.second.isEmpty()) acc.second.append(it) }
                    f.str("arguments")?.let { acc.third.append(it) }
                }
            }
            val images = delta.getAsJsonArray("images")?.mapNotNull { img ->
                img.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("image_url")?.str("url")?.let { StreamEvent.Image(it) }
            }.orEmpty()
            val text = delta.str("content")?.takeIf { it.isNotEmpty() }?.let { textCalls.feed(it) }?.takeIf { it.isNotEmpty() }
            return listOfNotNull(text?.let { StreamEvent.Text(it) }) + images
        }

        override fun onEnd(): List<StreamEvent> {
            val (leftover, parsed) = textCalls.finish()
            val structured = calls.map { (i, acc) ->
                ToolCall(acc.first.toString().ifEmpty { "call_$i" }, acc.second.toString(), acc.third.toString().ifBlank { "{}" })
            }
            val all = structured + parsed
            return listOfNotNull(leftover.takeIf { it.isNotEmpty() }?.let { StreamEvent.Text(it) }) +
                (if (all.isEmpty()) emptyList() else listOf(StreamEvent.ToolCalls(all)))
        }
    }

    // ---- Anthropic ----

    private fun anthropicRequest(
        provider: Provider,
        model: String,
        turns: List<ChatTurn>,
        systemPrompt: String?,
        tools: List<AgentTool>,
        finalAnswer: Boolean = false
    ): Request {
        val messages = JsonArray()
        var pendingResults: JsonArray? = null
        fun flushResults() {
            pendingResults?.let { messages.add(JsonObject().apply { addProperty("role", "user"); add("content", it) }) }
            pendingResults = null
        }
        turns.forEach { turn ->
            when (turn) {
                is ChatTurn.User -> { flushResults(); messages.add(JsonObject().apply { addProperty("role", "user"); add("content", anthropicUserContent(turn)) }) }
                is ChatTurn.Assistant -> {
                    flushResults()
                    messages.add(JsonObject().apply {
                        addProperty("role", "assistant")
                        add("content", JsonArray().apply {
                            if (turn.text.isNotBlank()) add(obj("type" to "text", "text" to turn.text))
                            turn.toolCalls.forEach { c ->
                                add(JsonObject().apply {
                                    addProperty("type", "tool_use")
                                    addProperty("id", c.id)
                                    addProperty("name", c.name)
                                    add("input", runCatching { JsonParser.parseString(c.arguments).asJsonObject }.getOrDefault(JsonObject()))
                                })
                            }
                        })
                    })
                }
                // Consecutive results travel together in one user message.
                is ChatTurn.ToolResult -> (pendingResults ?: JsonArray().also { pendingResults = it })
                    .add(obj("type" to "tool_result", "tool_use_id" to turn.callId, "content" to turn.content))
            }
        }
        flushResults()

        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 8192)
            addProperty("stream", true)
            if (!systemPrompt.isNullOrBlank()) addProperty("system", systemPrompt)
            add("messages", messages)
            if (tools.isNotEmpty()) add("tools", JsonArray().apply {
                tools.forEach { t ->
                    add(JsonObject().apply {
                        addProperty("name", t.name)
                        addProperty("description", t.description)
                        add("input_schema", t.parameters)
                    })
                }
            })
            if (finalAnswer && tools.isNotEmpty()) add("tool_choice", obj("type" to "none"))
        }
        return Request.Builder()
            .url("${provider.effectiveBaseUrl.trimEnd('/')}/messages")
            .header("x-api-key", provider.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    private class AnthropicParser : StreamParser {
        private val calls = sortedMapOf<Int, Triple<String, String, StringBuilder>>()
        private val textCalls = TextToolCalls.Filter()

        override fun onEvent(type: String?, data: String): List<StreamEvent> {
            val e = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return emptyList()
            return when (e.str("type")) {
                "error" -> throw IOException(e.getAsJsonObject("error")?.str("message") ?: "Anthropic stream error")
                "content_block_start" -> {
                    val block = e.getAsJsonObject("content_block")
                    if (block?.str("type") == "tool_use") {
                        calls[e.get("index").asInt] = Triple(block.str("id").orEmpty(), block.str("name").orEmpty(), StringBuilder())
                    }
                    emptyList()
                }
                "content_block_delta" -> {
                    val delta = e.getAsJsonObject("delta")
                    when (delta?.str("type")) {
                        "text_delta" -> listOfNotNull(delta.str("text")?.let { textCalls.feed(it) }?.takeIf { it.isNotEmpty() }?.let { StreamEvent.Text(it) })
                        "input_json_delta" -> { calls[e.get("index").asInt]?.third?.append(delta.str("partial_json").orEmpty()); emptyList() }
                        else -> emptyList()
                    }
                }
                else -> emptyList()
            }
        }

        override fun onEnd(): List<StreamEvent> {
            val (leftover, parsed) = textCalls.finish()
            val structured = calls.values.map { (id, name, args) -> ToolCall(id, name, args.toString().ifBlank { "{}" }) }
            val all = structured + parsed
            return listOfNotNull(leftover.takeIf { it.isNotEmpty() }?.let { StreamEvent.Text(it) }) +
                (if (all.isEmpty()) emptyList() else listOf(StreamEvent.ToolCalls(all)))
        }
    }

    // ---- shared ----

    private fun parseToolProgress(data: String): ToolActivity? = runCatching {
        val o = JsonParser.parseString(data).asJsonObject
        ToolActivity(
            id = o.str("toolCallId") ?: return null,
            tool = o.str("tool") ?: "tool",
            label = o.str("label") ?: o.str("tool") ?: "tool",
            emoji = o.str("emoji") ?: "",
            completed = o.str("status") == "completed"
        )
    }.getOrNull()

    private fun parseApproval(data: String): ApprovalRequest? = runCatching {
        val o = JsonParser.parseString(data).asJsonObject
        ApprovalRequest(
            runId = o.str("run_id") ?: return null,
            command = o.str("command").orEmpty(),
            description = o.str("description").orEmpty(),
            choices = o.getAsJsonArray("choices")?.map { it.asString } ?: listOf("once", "deny")
        )
    }.getOrNull()

    private fun sseFlow(client: OkHttpClient, request: Request, parser: StreamParser): Flow<StreamEvent> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    parser.onEvent(type, data).forEach { trySend(it) }
                } catch (e: IOException) {
                    close(e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                parser.onEnd().forEach { trySend(it) }
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

        fun obj(vararg pairs: Pair<String, String>) = JsonObject().apply { pairs.forEach { (k, v) -> addProperty(k, v) } }
        fun JsonObject.str(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString
    }
}
