package com.hermes.agent.data.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.hermes.agent.data.repository.LLMRepository
import com.hermes.agent.data.repository.ModelCatalogRepository
import com.hermes.agent.data.repository.ProviderRepository
import com.hermes.agent.data.repository.SettingsRepository
import com.hermes.agent.data.repository.StreamEvent
import com.hermes.agent.domain.model.Attachment
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import com.hermes.agent.domain.tools.ChatTurn
import com.hermes.agent.domain.tools.InputPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class Reading(val text: String, val by: String)

/**
 * Reads attachments for models that can't: a vision model describes images, PDF pages and video frames,
 * speech-to-text transcribes audio, and Office/ZIP files are read on the phone.
 */
@Singleton
class MediaReader @Inject constructor(
    private val providers: ProviderRepository,
    private val settings: SettingsRepository,
    private val catalog: ModelCatalogRepository,
    private val llm: LLMRepository,
    private val okHttpClient: OkHttpClient
) {
    /** Default readings are saved beside the file so later messages don't pay for them again. */
    private fun cacheFile(file: File) = File(file.path + ".reading.txt")

    suspend fun read(a: Attachment, file: File, question: String? = null, pages: IntRange? = null): Reading =
        withContext(Dispatchers.IO) { readOnIo(a, file, question, pages) }

    private suspend fun readOnIo(a: Attachment, file: File, question: String?, pages: IntRange?): Reading {
        if (question == null && pages == null) {
            cacheFile(file).takeIf { it.exists() }?.readText()?.let { cached ->
                return Reading(cached.substringAfter('\n'), cached.substringBefore('\n'))
            }
        }
        val ext = a.name.substringAfterLast('.', "").lowercase()
        val reading = when {
            a.kind == Attachment.Kind.IMAGE -> vision(listOf(imagePart(file, a.mime)), ReaderLogic.imagePrompt(a.name, question))
            a.mime == "application/pdf" -> readPdf(a, file, question, pages)
            a.kind == Attachment.Kind.VIDEO -> readVideo(a, file, question)
            a.kind == Attachment.Kind.AUDIO -> transcribe(a, file)
            ext in setOf("docx", "pptx", "xlsx", "odt", "odp", "ods") ->
                Reading(ReaderLogic.officeText(file) ?: error("Couldn't find text in ${a.name}."), "text extraction on the phone")
            ext in setOf("zip", "jar", "apk") ->
                Reading(ReaderLogic.archiveListing(file) ?: error("${a.name} isn't a readable ZIP archive."), "archive listing on the phone")
            else -> error("${a.name} (${a.mime}) can't be read automatically; use_attachment can hand it to Termux tools.")
        }
        if (question == null && pages == null) cacheFile(file).writeText("${reading.by}\n${reading.text}")
        return reading
    }

    // ---- vision ----

    private suspend fun visionCandidates(): List<Pair<Provider, String>> {
        val s = settings.settings.first()
        val enabled = providers.getEnabled().filter { !it.isHermes }
        enabled.firstOrNull { it.id == s.visionProviderId }?.let { p ->
            if (s.visionModel.isNotBlank()) return listOf(p to s.visionModel)
        }
        val out = mutableListOf<Pair<Provider, String>>()
        for (p in enabled) {
            when (p.type) {
                ProviderType.OPENROUTER -> runCatching { catalog.cached(p.type, p.effectiveBaseUrl, p.apiKey) }.getOrNull()
                    ?.let { models -> ReaderLogic.rankVisionModels(models, p.models).forEach { out += p to it } }
                ProviderType.OPENAI -> out += p to "gpt-4o-mini"
                ProviderType.ANTHROPIC -> out += p to "claude-haiku-5-5"
                else -> Unit
            }
        }
        return out
    }

    /** Tries vision models in order; free ones are often rate-limited, so a failure moves to the next. */
    private suspend fun vision(images: List<InputPart.Image>, prompt: String): Reading {
        val candidates = visionCandidates()
        if (candidates.isEmpty()) error("No vision model is available. Add an OpenRouter, OpenAI or Anthropic provider, or pick one under Settings → Media.")
        var last: Exception? = null
        for ((p, model) in candidates.take(4)) {
            try {
                val text = StringBuilder()
                llm.streamTurn(p, model, listOf(ChatTurn.User(prompt, images)), null, emptyList(), 120, "reader")
                    .collect { if (it is StreamEvent.Text) text.append(it.text) }
                if (text.isNotBlank()) return Reading(text.toString().trim(), model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("Vision models couldn't read it: ${last?.message ?: "empty replies"}")
    }

    private fun imagePart(file: File, mime: String): InputPart.Image {
        val (bytes, type) = ImageShrinker.shrink(file, mime)
        return InputPart.Image(type, Base64.getEncoder().encodeToString(bytes))
    }

    private fun bitmapPart(bitmap: Bitmap): InputPart.Image {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return InputPart.Image("image/jpeg", Base64.getEncoder().encodeToString(out.toByteArray()))
    }

    // ---- PDF and video ----

    private suspend fun readPdf(a: Attachment, file: File, question: String?, pages: IntRange?): Reading {
        val images = mutableListOf<InputPart.Image>()
        var total = 0
        var range = 0..0
        withContext(Dispatchers.IO) {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    total = pdf.pageCount
                    require(total > 0) { "${a.name} has no pages." }
                    val first = (pages?.first ?: 0).coerceIn(0, total - 1)
                    range = first..minOf(total - 1, pages?.last ?: (first + 7), first + 7)
                    for (i in range) pdf.openPage(i).use { page ->
                        val scale = 1400f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        images += bitmapPart(bmp)
                        bmp.recycle()
                    }
                }
            }
        }
        val r = vision(images, ReaderLogic.pdfPrompt(a.name, range, total, question))
        val more = if (range.last < total - 1) "\n[Read pages ${range.first + 1}-${range.last + 1} of $total; read_attachment with pages reads the rest.]" else ""
        return r.copy(text = r.text + more)
    }

    private suspend fun readVideo(a: Attachment, file: File, question: String?): Reading {
        val frames = mutableListOf<InputPart.Image>()
        val times = withContext(Dispatchers.IO) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(file.path)
                val seconds = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000.0
                ReaderLogic.evenlySpaced(seconds, 6).onEach { t ->
                    mmr.getFrameAtTime((t * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { frame ->
                        val scale = 768f / maxOf(frame.width, frame.height)
                        val small = if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
                        frames += bitmapPart(small)
                    }
                }
            } finally {
                mmr.release()
            }
        }
        require(frames.isNotEmpty()) { "Couldn't decode frames from ${a.name}." }
        val r = vision(frames, ReaderLogic.videoPrompt(a.name, times, question))
        return r.copy(text = r.text + "\n[Described from ${frames.size} frames; the soundtrack wasn't transcribed.]")
    }

    // ---- audio ----

    private suspend fun transcribe(a: Attachment, file: File): Reading {
        val s = settings.settings.first()
        val enabled = providers.getEnabled()
        val stt = enabled.firstOrNull { it.id == s.sttProviderId }
            ?: enabled.firstOrNull { it.type == ProviderType.OPENAI || it.type == ProviderType.GROQ }
        if (stt != null) {
            val model = s.sttModel.ifBlank { if (stt.type == ProviderType.GROQ) "whisper-large-v3" else "whisper-1" }
            return withContext(Dispatchers.IO) {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("file", a.name, file.asRequestBody(a.mime.toMediaType()))
                    .build()
                val request = Request.Builder().url("${stt.effectiveBaseUrl.trimEnd('/')}/audio/transcriptions")
                    .header("Authorization", "Bearer ${stt.apiKey}").post(body).build()
                okHttpClient.newBuilder().callTimeout(5, TimeUnit.MINUTES).build().newCall(request).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    check(r.isSuccessful) { "Transcription failed: HTTP ${r.code} ${text.take(200)}" }
                    Reading(com.google.gson.JsonParser.parseString(text).asJsonObject.get("text").asString, model)
                }
            }
        }
        // No speech-to-text provider: an audio-input chat model on OpenRouter (MP3/WAV only).
        val format = AttachmentEncoder.audioFormat(a.mime, a.name.substringAfterLast('.', "").lowercase())
            ?: error("Transcribing ${a.name} needs an OpenAI or Groq provider (only MP3/WAV can go to chat models).")
        val router = enabled.firstOrNull { it.type == ProviderType.OPENROUTER } ?: error("No speech-to-text provider is set up.")
        val models = ReaderLogic.rankAudioModels(catalog.cached(router.type, router.effectiveBaseUrl, router.apiKey))
        val audio = InputPart.Audio(format, Base64.getEncoder().encodeToString(file.readBytes()))
        for (model in models) {
            val text = StringBuilder()
            runCatching {
                llm.streamTurn(router, model, listOf(ChatTurn.User("Transcribe this audio exactly, then summarise it in one line.", listOf(audio))),
                    null, emptyList(), 180, "reader").collect { if (it is StreamEvent.Text) text.append(it.text) }
            }
            if (text.isNotBlank()) return Reading(text.toString().trim(), model)
        }
        error("No audio model could transcribe ${a.name}.")
    }
}
