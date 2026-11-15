package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.data.media.AttachmentStore
import com.hermes.agent.domain.tools.*
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_READ_RESULT_CHARS = 30_000

/** Numbers lines like `cat -n`, so the model can refer to a line without needing a separate line-number lookup. */
private fun numbered(content: String): String {
    val lines = content.split('\n')
    val width = lines.size.toString().length
    return lines.mapIndexed { i, line -> "${(i + 1).toString().padStart(width)}\t$line" }.joinToString("\n")
}

@Singleton
class ReadFileTool @Inject constructor(
    private val store: AttachmentStore,
    private val tracker: FileEditTracker
) : AgentTool {
    override val name = "read_file"
    override val capability = Capability.SHELL
    override val description = "Read a text file from Termux (source code, config, logs…), with line numbers. " +
        "Read a file with this before changing it with edit_file or overwriting it with write_file."
    override val parameters = schema(
        "path" to prop("string", "Termux path, e.g. ~/project/main.py or an absolute path."),
        required = listOf("path")
    )
    override fun label(args: JsonObject) = "Reading ${args.string("path")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val path = args.string("path") ?: throw IllegalArgumentException("path is required")
        val content = store.readTextFromTermux(path)
        tracker.record(context.conversationId, path, content)
        val numberedText = numbered(content)
        return if (numberedText.length > MAX_READ_RESULT_CHARS) numberedText.take(MAX_READ_RESULT_CHARS) +
            "\n[truncated; the file is longer — ask to read a narrower range if you need the rest]"
        else numberedText
    }
}

@Singleton
class EditFileTool @Inject constructor(
    private val store: AttachmentStore,
    private val tracker: FileEditTracker
) : AgentTool {
    override val name = "edit_file"
    override val capability = Capability.SHELL
    override val needsApproval = true
    override val description = "Change part of a text file by replacing exact text, like find-and-replace. " +
        "Read the file with read_file first. old_string must match the file's current text exactly (whitespace included) and, " +
        "unless replace_all is set, must occur only once — include a line or two of surrounding context to pin down the spot. " +
        "The user sees a diff and approves it."
    override val parameters = schema(
        "path" to prop("string", "Termux path of the file to change."),
        "old_string" to prop("string", "Exact text to find, copied from read_file's output (without its line-number column)."),
        "new_string" to prop("string", "Text to put in its place."),
        "replace_all" to prop("boolean", "Replace every occurrence instead of requiring exactly one. Default false."),
        required = listOf("path", "old_string", "new_string")
    )
    override fun label(args: JsonObject) = "Editing ${args.string("path")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val path = args.string("path") ?: throw IllegalArgumentException("path is required")
        val oldString = args.get("old_string")?.takeIf { it.isJsonPrimitive }?.asString ?: throw IllegalArgumentException("old_string is required")
        val newString = args.get("new_string")?.takeIf { it.isJsonPrimitive }?.asString ?: throw IllegalArgumentException("new_string is required")
        val replaceAll = args.get("replace_all")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        if (!tracker.hasRead(context.conversationId, path)) error("Read $path with read_file before editing it.")
        val current = store.readTextFromTermux(path)
        if (!tracker.matches(context.conversationId, path, current)) {
            error("$path changed since you last read it. Read it again with read_file before editing.")
        }
        val result = FileEditLogic.applyEdit(path, current, oldString, newString, replaceAll)
        store.writeTextToTermux(result.newContent, path)
        tracker.record(context.conversationId, path, result.newContent)
        return result.diff
    }
}

@Singleton
class WriteFileTool @Inject constructor(
    private val store: AttachmentStore,
    private val tracker: FileEditTracker
) : AgentTool {
    override val name = "write_file"
    override val capability = Capability.SHELL
    override val needsApproval = true
    override val description = "Create a new text file, or replace an existing one's whole content. " +
        "For a small change to a file that already exists, use edit_file instead — it's safer and the user sees exactly what changed. " +
        "An existing file must be read with read_file first. The user sees a diff and approves it."
    override val parameters = schema(
        "path" to prop("string", "Termux path of the file to write."),
        "content" to prop("string", "The file's full new content."),
        required = listOf("path", "content")
    )
    override fun label(args: JsonObject) = "Writing ${args.string("path")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val path = args.string("path") ?: throw IllegalArgumentException("path is required")
        val content = args.get("content")?.takeIf { it.isJsonPrimitive }?.asString ?: throw IllegalArgumentException("content is required")
        val exists = store.fileExistsInTermux(path)
        val before = if (exists) {
            if (!tracker.hasRead(context.conversationId, path)) error("$path already exists. Read it with read_file first, or use edit_file for a small change.")
            val current = store.readTextFromTermux(path)
            if (!tracker.matches(context.conversationId, path, current)) error("$path changed since you last read it. Read it again before overwriting it.")
            current
        } else null
        val diff = FileEditLogic.diffWholeFile(path, before, content)
        store.writeTextToTermux(content, path)
        tracker.record(context.conversationId, path, content)
        return diff
    }
}
