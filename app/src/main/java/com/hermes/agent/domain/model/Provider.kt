package com.hermes.agent.domain.model

import com.hermes.agent.data.local.entity.ProviderEntity

enum class ProviderType(val displayName: String, val baseUrl: String, val apiMode: ApiMode) {
    HERMES("Hermes Agent", "http://127.0.0.1:8642/v1", ApiMode.OPENAI_COMPATIBLE),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", ApiMode.OPENAI_COMPATIBLE),
    OPENAI("OpenAI", "https://api.openai.com/v1", ApiMode.OPENAI_COMPATIBLE),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1", ApiMode.ANTHROPIC),
    GROQ("Groq", "https://api.groq.com/openai/v1", ApiMode.OPENAI_COMPATIBLE),
    TOGETHER("Together AI", "https://api.together.xyz/v1", ApiMode.OPENAI_COMPATIBLE),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", ApiMode.OPENAI_COMPATIBLE),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", ApiMode.OPENAI_COMPATIBLE),
    CUSTOM("Custom", "", ApiMode.OPENAI_COMPATIBLE);
}

enum class ApiMode { OPENAI_COMPATIBLE, ANTHROPIC }

data class Provider(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    val baseUrl: String,
    /** Tried in order: primary first, then each fallback, before routing moves to the next provider. */
    val models: List<String>,
    val isEnabled: Boolean,
    val priority: Int,
    val avgResponseMs: Long = 0L
) {
    val defaultModel: String get() = models.firstOrNull().orEmpty()
    val effectiveBaseUrl: String get() = baseUrl.ifBlank { type.baseUrl }
    val apiMode: ApiMode get() = type.apiMode
    val isHermes: Boolean get() = type == ProviderType.HERMES
}

fun ProviderEntity.toDomain() = Provider(
    id = id,
    name = name,
    type = ProviderType.valueOf(type),
    apiKey = apiKey,
    baseUrl = baseUrl,
    // Rows from before ordered models existed carry only defaultModel.
    models = models?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: listOf(defaultModel),
    isEnabled = isEnabled,
    priority = priority,
    avgResponseMs = avgResponseMs
)

fun Provider.toEntity() = ProviderEntity(
    id = id,
    name = name,
    type = type.name,
    apiKey = apiKey,
    baseUrl = baseUrl,
    defaultModel = defaultModel,
    models = models.joinToString("\n"),
    isEnabled = isEnabled,
    priority = priority,
    avgResponseMs = avgResponseMs
)
