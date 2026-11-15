package com.hermes.agent.ui.chat

import com.hermes.agent.domain.model.ToolActivity

enum class AvatarState { IDLE, THINKING, TYPING, TOOL, SKILL, DELEGATING, GENERATING, READING, CONNECTING, WAITING, ERROR }

data class AvatarStatus(val state: AvatarState, val text: String, val delegateAgentName: String? = null)

/** What the Hermes avatar shows, worked out from the chat's streaming state. Pure, so it's unit-tested. */
object AvatarLogic {
    private val GENERATORS = setOf("generate_image", "text_to_speech", "post_file", "image_search")
    private val READERS = setOf("read", "read_attachment", "use_attachment")

    fun status(
        isLoading: Boolean,
        tools: List<ToolActivity>,
        waitingForApproval: Boolean,
        msSinceToken: Long,
        msSinceFailure: Long
    ): AvatarStatus {
        if (!isLoading) {
            return if (msSinceFailure < 5_000) AvatarStatus(AvatarState.ERROR, "Something went wrong") else AvatarStatus(AvatarState.IDLE, "Ready")
        }
        if (waitingForApproval) return AvatarStatus(AvatarState.WAITING, "Waiting for your OK")
        val running = tools.filter { !it.completed }
        // A sub-agent's own tools run while its delegate_task is open; the conversation with the agent is the headline.
        running.lastOrNull { it.tool == "delegate_task" }?.let { d ->
            val who = d.label.substringBefore(':').removePrefix("Sub-agent").trim()
            return AvatarStatus(AvatarState.DELEGATING, if (who.isEmpty()) "Talking to a sub-agent" else "Talking to $who", who.ifEmpty { null })
        }
        val t = running.lastOrNull()
        if (t != null) {
            val label = t.label.removePrefix("↳ ").substringAfter(": ", t.label).takeIf { t.label.startsWith("↳") } ?: t.label
            return when {
                t.tool == "read_skill" -> AvatarStatus(AvatarState.SKILL, "Using skill ${label.removePrefix("Reading skill ").trim()}")
                t.tool == "save_skill" -> AvatarStatus(AvatarState.SKILL, label)
                t.tool == "create_agent" -> AvatarStatus(AvatarState.DELEGATING, label)
                t.tool in GENERATORS -> AvatarStatus(AvatarState.GENERATING, if (t.tool == "generate_image") "Generating an image" else label)
                t.tool in READERS -> AvatarStatus(AvatarState.READING, label)
                "__" in t.tool -> AvatarStatus(AvatarState.CONNECTING, label)
                else -> AvatarStatus(AvatarState.TOOL, label)
            }
        }
        return if (msSinceToken < 1_500) AvatarStatus(AvatarState.TYPING, "Typing…") else AvatarStatus(AvatarState.THINKING, "Thinking…")
    }
}
