package com.hermes.agent.domain.model

data class AgentSettings(
    val globalSystemPrompt: String = "",
    val globalRoutingStrategy: RoutingStrategy = RoutingStrategy.PRIORITY,
    val globalUnrestrictedMode: UnrestrictedMode = UnrestrictedMode.OFF,
    val customUnrestrictedPrompt: String = DEFAULT_UNRESTRICTED_PROMPT,
    val maxProviderAttempts: Int = 3,
    val requestTimeoutSeconds: Int = 60,
    val showProviderBadge: Boolean = true,
    val theme: AppTheme = AppTheme.DARK
)

enum class AppTheme { SYSTEM, LIGHT, DARK }

sealed class StreamChunk {
    data class ProviderSelected(val providerId: String, val providerName: String, val model: String) : StreamChunk()
    data class Token(val text: String) : StreamChunk()
    data class ProviderFailed(val providerName: String, val message: String) : StreamChunk()
    data class Failed(val message: String) : StreamChunk()
    data object Done : StreamChunk()
}
