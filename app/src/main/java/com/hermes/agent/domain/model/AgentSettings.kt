package com.hermes.agent.domain.model

data class AgentSettings(
    val globalSystemPrompt: String = "",
    val globalRoutingStrategy: RoutingStrategy = RoutingStrategy.PRIORITY,
    val globalUnrestrictedMode: UnrestrictedMode = UnrestrictedMode.OFF,
    val customUnrestrictedPrompt: String = DEFAULT_UNRESTRICTED_PROMPT,
    val maxProviderAttempts: Int = 6,
    val requestTimeoutSeconds: Int = 60,
    val showProviderBadge: Boolean = true,
    val theme: AppTheme = AppTheme.DARK,
    /** What the AI may reach through in-app tools. Empty by default: the user opts in per capability. */
    val deviceAccess: Set<com.hermes.agent.domain.tools.Capability> = emptySet(),
    /** Blank = pick automatically from enabled providers. */
    val imageProviderId: String = "",
    val imageModel: String = "",
    val ttsProviderId: String = "",
    val ttsModel: String = "",
    val ttsVoice: String = "",
    /** Reads attachments for models that can't see them. Blank = automatic. */
    val visionProviderId: String = "",
    val visionModel: String = "",
    val sttProviderId: String = "",
    val sttModel: String = ""
)

enum class AppTheme { SYSTEM, LIGHT, DARK }

data class ToolActivity(
    val id: String,
    val tool: String,
    val label: String,
    val emoji: String,
    val completed: Boolean
) {
    val display: String get() = listOf(emoji, label).filter { it.isNotBlank() }.joinToString(" ")
}

data class ApprovalRequest(
    val runId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
    /** True for the app's own tools (answered in-process); false for Hermes runs (answered over HTTP). */
    val local: Boolean = false
)

sealed class StreamChunk {
    data class ProviderSelected(val providerId: String, val providerName: String, val model: String) : StreamChunk()
    data class Token(val text: String) : StreamChunk()
    data class Tool(val activity: ToolActivity) : StreamChunk()
    data class ApprovalRequested(val providerId: String, val request: ApprovalRequest) : StreamChunk()
    data class ProviderFailed(val providerName: String, val message: String) : StreamChunk()
    data class Failed(val message: String) : StreamChunk()
    data class Notice(val message: String) : StreamChunk()
    data class File(val attachment: Attachment) : StreamChunk()
    data object Done : StreamChunk()
}
