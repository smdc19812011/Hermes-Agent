package com.hermes.agent.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OpenAIChatRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val stream: Boolean = true
)

data class OpenAIMessage(
    val role: String,
    val content: String
)

data class OpenAIChatResponse(
    val choices: List<OpenAIChoice>?
)

data class OpenAIChoice(
    val delta: OpenAIDelta?
)

data class OpenAIDelta(
    val content: String?
)

data class AnthropicRequest(
    val model: String,
    @SerializedName("max_tokens") val maxTokens: Int = 8192,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val stream: Boolean = true
)

data class AnthropicMessage(
    val role: String,
    val content: String
)

data class AnthropicStreamEvent(
    val type: String?,
    val delta: AnthropicDelta?,
    val error: AnthropicError?
)

data class AnthropicDelta(
    val text: String?
)

data class AnthropicError(
    val message: String?
)
