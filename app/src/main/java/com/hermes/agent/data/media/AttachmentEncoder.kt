package com.hermes.agent.data.media

import com.hermes.agent.domain.model.Attachment
import com.hermes.agent.domain.tools.InputPart
import java.io.File
import java.util.Base64

/** How the user's attachments reach the model: binary parts it can read, plus a text note for everything else. */
data class EncodedAttachments(val note: String, val parts: List<InputPart>)

object AttachmentEncoder {
    private const val MAX_INLINE_TEXT = 200 * 1024
    private const val MAX_BINARY = 20L * 1024 * 1024
    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log", "html", "css", "js", "ts", "kt", "java",
        "py", "sh", "c", "h", "cpp", "rs", "go", "rb", "php", "sql", "gradle", "kts", "properties", "conf", "srt", "vtt"
    )

    /**
     * [binary] false sends images, PDFs and audio as descriptions only (for models that reject them).
     * [shrinkImage] returns smaller image bytes and their mime; injected so this stays testable off-device.
     */
    fun encode(
        items: List<Pair<Attachment, File>>,
        binary: Boolean,
        toolsCanOpen: Boolean,
        shrinkImage: (File, String) -> Pair<ByteArray, String>
    ): EncodedAttachments {
        val notes = mutableListOf<String>()
        val parts = mutableListOf<InputPart>()
        fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
        fun describe(a: Attachment, why: String) = notes.add(
            "[Attached ${kindWord(a)} \"${a.name}\" (${a.mime}, ${size(a.size)})$why" +
                (if (toolsCanOpen) "; use_attachment copies it into Termux for tools like ffmpeg or python" else "") + "]"
        )

        for ((a, file) in items) {
            val ext = a.name.substringAfterLast('.', "").lowercase()
            when {
                a.kind == Attachment.Kind.IMAGE && binary && a.mime != "image/svg+xml" -> {
                    val (bytes, mime) = shrinkImage(file, a.mime)
                    parts += InputPart.Image(mime, b64(bytes))
                    notes += "[Attached image \"${a.name}\"]"
                }
                a.mime == "application/pdf" && binary && a.size <= MAX_BINARY -> {
                    parts += InputPart.Document(a.name, a.mime, b64(file.readBytes()))
                    notes += "[Attached PDF \"${a.name}\"]"
                }
                a.kind == Attachment.Kind.AUDIO && binary && a.size <= MAX_BINARY && audioFormat(a.mime, ext) != null -> {
                    parts += InputPart.Audio(audioFormat(a.mime, ext)!!, b64(file.readBytes()))
                    notes += "[Attached audio \"${a.name}\"]"
                }
                isText(a.mime, ext, file) && a.size <= MAX_INLINE_TEXT ->
                    notes += "Attached file \"${a.name}\":\n```${ext}\n${file.readText().trimEnd()}\n```"
                isText(a.mime, ext, file) -> describe(a, ", too long to include in full")
                else -> describe(a, if (binary) "" else ", described because this model can't read it directly")
            }
        }
        return EncodedAttachments(notes.joinToString("\n"), parts)
    }

    fun audioFormat(mime: String, ext: String): String? = when {
        mime == "audio/wav" || mime == "audio/x-wav" || ext == "wav" -> "wav"
        mime == "audio/mpeg" || ext == "mp3" -> "mp3"
        else -> null
    }

    private fun isText(mime: String, ext: String, file: File): Boolean {
        if (mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")) return true
        if (ext !in TEXT_EXTENSIONS) return false
        // Extension says text: confirm the start decodes as UTF-8 without control bytes.
        val head = file.inputStream().use { s -> ByteArray(4096).let { b -> b.copyOf(maxOf(0, s.read(b))) } }
        return head.none { it.toInt() == 0 } && runCatching {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(head))
        }.isSuccess
    }

    private fun kindWord(a: Attachment) = when (a.kind) {
        Attachment.Kind.IMAGE -> "image"; Attachment.Kind.VIDEO -> "video"; Attachment.Kind.AUDIO -> "audio"; Attachment.Kind.FILE -> "file"
    }

    private fun size(bytes: Long) = if (bytes < 1_048_576) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1_048_576.0)
}
