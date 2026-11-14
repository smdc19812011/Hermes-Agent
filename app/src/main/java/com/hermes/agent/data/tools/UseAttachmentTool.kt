package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.data.media.AttachmentStore
import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.domain.model.Role
import com.hermes.agent.domain.tools.*
import javax.inject.Inject

class UseAttachmentTool @Inject constructor(
    private val conversations: ConversationRepository,
    private val store: AttachmentStore
) : AgentTool {
    override val name = "use_attachment"
    override val capability = Capability.SHELL
    override val description = "Copy a file the user attached in this chat into Termux (~/attachments/) so run_command tools can work on it " +
        "(ffmpeg, python, unzip…). Without a name it lists the chat's attachments."
    override val parameters = schema("name" to prop("string", "Attachment file name as shown in the chat. Leave out to list them."))

    override fun label(args: JsonObject) = args.string("name")?.let { "Copying $it to Termux" } ?: "Listing attachments"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val files = conversations.getMessages(context.conversationId)
            .flatMap { m -> m.attachments.map { it to m.role } }
            .filter { (a, _) -> !a.isRemote }
        if (files.isEmpty()) return "No files have been attached in this chat."
        val want = args.string("name")
            ?: return files.joinToString("\n") { (a, role) -> "- ${a.name} (${a.mime}, ${a.size / 1024} KB, from ${if (role == Role.USER) "the user" else "the assistant"})" }
        val (match, _) = files.lastOrNull { (a, _) -> a.name.equals(want, ignoreCase = true) }
            ?: files.lastOrNull { (a, _) -> a.name.contains(want, ignoreCase = true) }
            ?: return "No attachment named \"$want\". Attachments: ${files.joinToString { it.first.name }}"
        val path = store.pushToTermux(match, "~/attachments/${match.name}")
        return "Copied ${match.name} (${match.size / 1024} KB) to $path in Termux."
    }
}
