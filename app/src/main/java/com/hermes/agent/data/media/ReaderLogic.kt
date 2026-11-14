package com.hermes.agent.data.media

import com.hermes.agent.data.repository.ModelInfo
import java.io.File
import java.util.zip.ZipFile

/** Pure parts of attachment reading, kept off Android so they can be unit-tested. */
object ReaderLogic {
    private val TRUSTED_FAMILIES = listOf("gemini", "gemma", "nemotron", "qwen", "llama", "mistral", "claude", "gpt")

    /**
     * Vision models to try, best first: ones the user already lists, then free models from well-known families,
     * then other free ones, then the cheapest paid. Routers and placeholder prices are skipped.
     */
    fun rankVisionModels(catalog: List<ModelInfo>, preferred: List<String>, limit: Int = 4): List<String> {
        val vision = catalog.filter { it.inputsImage == true && !it.id.startsWith("openrouter/") && (it.promptPrice ?: 0.0) >= 0 }
        val listed = preferred.filter { id -> vision.any { it.id == id } }
        val free = vision.filter { it.promptPrice == 0.0 || it.id.endsWith(":free") }
        val trustedFree = free.filter { m -> TRUSTED_FAMILIES.any { it in m.id.lowercase() } }
        val paid = vision.filter { it !in free }.sortedBy { it.promptPrice ?: Double.MAX_VALUE }
        return (listed + trustedFree.map { it.id } + free.map { it.id } + paid.map { it.id }).distinct().take(limit)
    }

    fun rankAudioModels(catalog: List<ModelInfo>, limit: Int = 3): List<String> =
        catalog.filter { it.inputsAudio == true && !it.id.startsWith("openrouter/") && (it.promptPrice ?: 0.0) >= 0 }
            .sortedWith(compareBy({ it.promptPrice != 0.0 }, { it.promptPrice ?: Double.MAX_VALUE }))
            .map { it.id }.take(limit)

    fun imagePrompt(name: String, question: String?) = question?.let {
        "The user attached the image \"$name\". Answer this about it precisely, quoting any text exactly: $it"
    } ?: ("Describe the image \"$name\" in detail for someone who can't see it: the scene, people and objects, colours, " +
        "setting, time of day, and anything notable. Transcribe all visible text exactly. Be factual; don't guess beyond what's visible.")

    fun pdfPrompt(name: String, pages: IntRange, total: Int, question: String?) =
        "These are pages ${pages.first + 1}-${pages.last + 1} of $total from the PDF \"$name\". " +
            (question?.let { "Answer precisely from them: $it" }
                ?: "Transcribe all text faithfully, keeping headings, lists and tables (as markdown), and briefly describe any figures or images.")

    fun videoPrompt(name: String, timestamps: List<Double>, question: String?) =
        "These are ${timestamps.size} frames from the video \"$name\" at ${timestamps.joinToString { "%.1fs".format(it) }}. " +
            (question?.let { "Answer from them: $it" }
                ?: "Describe what happens across the video, the setting, people and objects, and transcribe any visible text.")

    /** Text of Word, PowerPoint, Excel and OpenDocument files, from their XML parts. */
    fun officeText(file: File, maxChars: Int = 40_000): String? {
        val zip = runCatching { ZipFile(file) }.getOrNull() ?: return null
        zip.use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val parts = when {
                "word/document.xml" in names -> listOf("word/document.xml")
                names.any { it.startsWith("ppt/slides/slide") } -> names.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
                    .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
                "xl/sharedStrings.xml" in names -> listOf("xl/sharedStrings.xml")
                "content.xml" in names -> listOf("content.xml") // OpenDocument
                else -> return null
            }
            val text = parts.joinToString("\n\n") { part ->
                val xml = z.getInputStream(z.getEntry(part)).bufferedReader().readText()
                xml.replace(Regex("</(w:p|a:p|text:p|text:h|si)>"), "\n")
                    .replace(Regex("<w:tab/>|<w:br/>"), " ")
                    .replace(Regex("<[^>]+>"), "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                    .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            }
            return text.take(maxChars).ifBlank { null }
        }
    }

    /** Entry list of ZIP-family archives (zip, jar, apk). */
    fun archiveListing(file: File, max: Int = 200): String? = runCatching {
        ZipFile(file).use { z ->
            val entries = z.entries().asSequence().toList()
            val shown = entries.take(max).joinToString("\n") { e -> "- ${e.name}${if (e.isDirectory) "" else " (${e.size} bytes)"}" }
            "${entries.size} entries:\n$shown" + if (entries.size > max) "\n…and ${entries.size - max} more" else ""
        }
    }.getOrNull()

    fun evenlySpaced(durationSeconds: Double, count: Int): List<Double> {
        if (durationSeconds <= 0) return listOf(0.0)
        val n = count.coerceAtLeast(1)
        return (0 until n).map { i -> durationSeconds * (i + 0.5) / n }
    }
}
