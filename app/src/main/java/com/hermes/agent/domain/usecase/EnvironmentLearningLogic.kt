package com.hermes.agent.domain.usecase

/**
 * Pure logic behind learning what works/doesn't work on this phone's hardware and software from a chat's own tool
 * results, kept Android- and network-free so it's unit-tested. [SendMessageUseCase] drives the actual model call.
 */
object EnvironmentLearningLogic {
    /** Only turns that actually touched the system (ran a shell command or installed something) are worth asking about. */
    private val RELEVANT_TOOLS = setOf("run_command", "install_tool")

    fun shouldAttempt(toolsRan: Boolean, toolNamesUsed: Set<String>): Boolean =
        toolsRan && toolNamesUsed.any { it in RELEVANT_TOOLS }

    /** The model answers "NONE" when this chat taught it nothing new; anything else is the learned note itself. */
    fun parseLearning(response: String): String? {
        val trimmed = response.trim()
        return trimmed.takeUnless { it.isEmpty() || it.equals("none", ignoreCase = true) }
    }

    /**
     * Folds [addition]'s lines into [existing]: exact-duplicate lines (already known) are dropped, and if the result
     * is still over [maxChars] the oldest lines go first, since a growing note otherwise has no bound.
     */
    fun merge(existing: String, addition: String, maxChars: Int = 8_000): String {
        val lines = (existing.lines() + addition.lines()).map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        while (lines.isNotEmpty() && lines.joinToString("\n").length > maxChars) lines.removeAt(0)
        return lines.joinToString("\n")
    }

    /** Wraps the stored notes as a labeled section for the system prompt; blank notes contribute nothing. */
    fun systemPromptSection(notes: String): String? = notes.trim().takeIf { it.isNotEmpty() }?.let {
        "Learned about this phone's own hardware/software environment from earlier chats (found automatically; " +
            "the user can clear this in Settings). Treat it as true for this device unless today's results say otherwise:\n$it"
    }
}
