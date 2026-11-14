package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.data.media.AttachmentStore
import com.hermes.agent.data.media.MediaReader
import com.hermes.agent.data.repository.ConversationRepository
import com.hermes.agent.domain.tools.*
import javax.inject.Inject

class ReadAttachmentTool @Inject constructor(
    private val conversations: ConversationRepository,
    private val store: AttachmentStore,
    private val reader: MediaReader
) : AgentTool {
    override val name = "read_attachment"
    override val capability = Capability.MEDIA
    override val description = "Look at a file attached in this chat and answer a question about it, even if you can't see images yourself: " +
        "a vision model reads images, PDF pages and video frames, audio is transcribed, and Office/ZIP files are read on the phone. " +
        "Ask a specific question for details (\"what does the sign say?\"), or leave it out for a full description."
    override val parameters = schema(
        "name" to prop("string", "Attachment file name as shown in the chat."),
        "question" to prop("string", "Optional question about the file."),
        "pages" to prop("string", "For PDFs: page range like \"9-16\" (8 pages per call)."),
        required = listOf("name")
    )

    override fun label(args: JsonObject) = "Reading ${args.string("name")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val want = args.string("name") ?: throw IllegalArgumentException("name is required")
        val files = conversations.getMessages(context.conversationId).flatMap { it.attachments }.filter { !it.isRemote }
        val a = files.lastOrNull { it.name.equals(want, ignoreCase = true) } ?: files.lastOrNull { it.name.contains(want, ignoreCase = true) }
            ?: return "No attachment named \"$want\". Attachments: ${files.joinToString { it.name }.ifEmpty { "none" }}"
        val pages = args.string("pages")?.let { p ->
            val (from, to) = p.split('-').map { it.trim().toInt() }.let { it.first() to (it.getOrNull(1) ?: it.first()) }
            (from - 1)..(to - 1)
        }
        val r = reader.read(a, store.file(a), args.string("question"), pages)
        return "${a.name} (read by ${r.by}):\n${r.text}"
    }
}
