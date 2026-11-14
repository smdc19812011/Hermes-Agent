package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.data.media.AttachmentStore
import com.hermes.agent.data.media.MediaGenerator
import com.hermes.agent.data.media.MediaScripts
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.domain.model.Attachment
import com.hermes.agent.domain.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class ImageResult(val title: String, val url: String, val thumbnail: String?, val width: Int?, val height: Int?, val license: String, val credit: String, val page: String?)

class PostFileTool @Inject constructor(private val store: AttachmentStore) : AgentTool {
    override val name = "post_file"
    override val capability = Capability.MEDIA
    override val description = "Post a file into the chat so the user can view, play and download it: an image, video, audio clip, document or any file. " +
        "The source is a URL (it's downloaded) or a path to a file you made in Termux (e.g. ~/out/clip.mp4 after running ffmpeg via run_command). Max 100 MB."
    override val parameters = schema(
        "source" to prop("string", "https:// URL, or a Termux path such as ~/video.mp4 or /data/data/com.termux/files/home/x.png."),
        "name" to prop("string", "Optional file name to show."),
        "caption" to prop("string", "Optional caption, e.g. credit or license for an image."),
        required = listOf("source")
    )

    override fun label(args: JsonObject) = "Posting ${args.string("name") ?: args.string("source")?.substringAfterLast('/')}"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val source = args.string("source") ?: throw IllegalArgumentException("source is required")
        val caption = args.string("caption").orEmpty()
        val a = if (source.startsWith("http://") || source.startsWith("https://")) {
            val host = source.toHttpUrl().host
            if (NetGuard.isLocal(withContext(Dispatchers.IO) { InetAddress.getByName(host) }) && Capability.NETWORK !in context.enabled) {
                throw SecurityException("$host is on the local network. Turn on Local network access to fetch from it.")
            }
            store.download(context.conversationId, source, args.string("name"), caption)
        } else {
            store.pullFromTermux(context.conversationId, source, caption)
        }
        context.attach(a)
        return "Posted ${a.name} (${a.mime}, ${a.size / 1024} KB) to the chat."
    }
}

class ImageSearchTool @Inject constructor(private val client: OkHttpClient) : AgentTool {
    override val name = "image_search"
    override val capability = Capability.MEDIA
    override val description = "Search openly licensed images (Openverse, Wikimedia Commons). Returns image links with size, license and credit. " +
        "Set post to show the top results in the chat right away, or call post_file with a chosen link."
    override val parameters = schema(
        "query" to prop("string", "What to find."),
        "count" to prop("integer", "Results to return (1-12, default 6)."),
        "post" to prop("integer", "How many of the top results to show in the chat now (0-6, default 0)."),
        required = listOf("query")
    )

    override fun label(args: JsonObject) = "Searching images: \"${args.string("query")}\""

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val query = args.string("query") ?: throw IllegalArgumentException("query is required")
        val count = args.int("count")?.coerceIn(1, 12) ?: 6
        val http = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        fun get(url: String) = http.newCall(Request.Builder().url(url).header("User-Agent", "HermesAgentAndroid/1.6 (image search)").build())
            .execute().use { r -> if (r.isSuccessful) r.body?.string() else null }

        val openverse = "https://api.openverse.org/v1/images/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("page_size", "$count").build().toString()
        var results = runCatching { get(openverse)?.let(::parseOpenverse) }.getOrNull().orEmpty()
        if (results.size < count) {
            val commons = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("format", "json").addQueryParameter("generator", "search")
                .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrsearch", query).addQueryParameter("gsrlimit", "$count")
                .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|size|mime|extmetadata")
                .addQueryParameter("iiurlwidth", "800").build().toString()
            results = (results + runCatching { get(commons)?.let(::parseWikimedia) }.getOrNull().orEmpty()).distinctBy { it.url }
        }
        results = results.take(count)
        if (results.isEmpty()) return@withContext "No openly licensed images found for \"$query\"."

        val post = args.int("post")?.coerceIn(0, 6) ?: 0
        results.take(post).forEach { r ->
            context.attach(Attachment(r.title.take(60), "image/*", r.thumbnail ?: r.url, caption = "${r.license} · ${r.credit}"))
        }
        results.mapIndexed { i, r ->
            "${i + 1}. ${r.title} (${r.width ?: "?"}×${r.height ?: "?"}, ${r.license}, ${r.credit})\n   image: ${r.url}" +
                (r.page?.let { "\n   page: $it" } ?: "")
        }.joinToString("\n") + if (post > 0) "\nShowed the first ${minOf(post, results.size)} in the chat." else ""
    }

    companion object {
        fun parseOpenverse(json: String): List<ImageResult> =
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("results").map { it.asJsonObject }.map { o ->
                ImageResult(
                    title = o.str("title") ?: "Untitled", url = o.str("url")!!, thumbnail = o.str("thumbnail"),
                    width = o.get("width")?.takeIf { it.isJsonPrimitive }?.asInt, height = o.get("height")?.takeIf { it.isJsonPrimitive }?.asInt,
                    license = "CC ${o.str("license")?.uppercase().orEmpty()}".trim(), credit = o.str("creator") ?: "unknown",
                    page = o.str("foreign_landing_url")
                )
            }

        fun parseWikimedia(json: String): List<ImageResult> {
            val pages = JsonParser.parseString(json).asJsonObject.getAsJsonObject("query")?.getAsJsonObject("pages") ?: return emptyList()
            return pages.entrySet().mapNotNull { (_, el) ->
                val p = el.asJsonObject
                val ii = p.getAsJsonArray("imageinfo")?.firstOrNull()?.asJsonObject ?: return@mapNotNull null
                if (ii.str("mime")?.startsWith("image/") != true) return@mapNotNull null
                val meta = ii.getAsJsonObject("extmetadata")
                fun metaValue(k: String) = meta?.getAsJsonObject(k)?.str("value")?.let { htmlToText(it) }
                ImageResult(
                    title = p.str("title")?.removePrefix("File:")?.substringBeforeLast('.') ?: "Untitled",
                    url = ii.str("url")!!, thumbnail = ii.str("thumburl"),
                    width = ii.get("width")?.asInt, height = ii.get("height")?.asInt,
                    license = metaValue("LicenseShortName") ?: "see page", credit = metaValue("Artist")?.take(80) ?: "Wikimedia Commons",
                    page = ii.str("descriptionurl")
                )
            }
        }

        private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    }
}

class GenerateImageTool @Inject constructor(
    private val generator: MediaGenerator,
    private val store: AttachmentStore
) : AgentTool {
    override val name = "generate_image"
    override val capability = Capability.MEDIA
    override val description = "Create an image from a text description with the image model configured in Settings → Media (or one picked automatically " +
        "from the user's providers) and post it in the chat. If no image provider is available, make the image in Termux instead " +
        "(python with Pillow, ImageMagick) and post it with post_file."
    override val parameters = schema(
        "prompt" to prop("string", "Detailed description of the image."),
        "size" to prop("string", "Optional size for providers that take one, e.g. 1024x1024."),
        required = listOf("prompt")
    )

    override fun label(args: JsonObject) = "Generating an image"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val prompt = args.string("prompt") ?: throw IllegalArgumentException("prompt is required")
        val media = generator.generateImage(prompt, args.string("size"))
        val ext = when (media.mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> "png" }
        val a = store.saveBytes(context.conversationId, "image-${System.currentTimeMillis() / 1000}.$ext", media.bytes, media.mime,
            caption = "Generated by ${media.model}")
        context.attach(a)
        return "Generated an image with ${media.model} (${media.provider}) and posted it." +
            media.text.takeIf { it.isNotBlank() }?.let { "\nModel note: ${it.take(300)}" }.orEmpty()
    }
}

class TextToSpeechTool @Inject constructor(
    private val generator: MediaGenerator,
    private val store: AttachmentStore,
    private val termux: TermuxBridge
) : AgentTool {
    override val name = "text_to_speech"
    override val capability = Capability.MEDIA
    override val description = "Turn text into spoken audio and post it in the chat. Uses the speech provider from Settings → Media (OpenAI-compatible), " +
        "or espeak in Termux when none is set (install it with install_tool espeak if missing)."
    override val parameters = schema(
        "text" to prop("string", "What to say (up to 4000 characters)."),
        "voice" to prop("string", "Optional voice name for the provider (e.g. alloy, nova), or an espeak voice (e.g. en-us, es)."),
        required = listOf("text")
    )

    override fun label(args: JsonObject) = "Speaking ${args.string("text")?.take(30)}…"

    override suspend fun run(args: JsonObject, context: ToolContext): String {
        val text = args.string("text")?.take(4000) ?: throw IllegalArgumentException("text is required")
        val stamp = System.currentTimeMillis() / 1000
        if (generator.speechTarget() != null) {
            val media = generator.speech(text, args.string("voice"))
            val a = store.saveBytes(context.conversationId, "speech-$stamp.mp3", media.bytes, media.mime, caption = "Spoken by ${media.model}")
            context.attach(a)
            return "Posted spoken audio from ${media.model}."
        }
        if (!termux.isInstalled() || !termux.hasPermission()) {
            throw IllegalStateException("No speech provider is set in Settings → Media, and Termux isn't set up for espeak.")
        }
        val path = "~/.hermes-android/media/speech-$stamp.wav"
        val r = termux.run(MediaScripts.espeak(text, args.string("voice"), path), "Speak", 60_000)
        r.termuxError?.let { throw IllegalStateException(it) }
        if (r.exitCode == 127) throw IllegalStateException("espeak isn't installed. Call install_tool with espeak, then try again.")
        if (r.exitCode != 0) throw IllegalStateException("espeak failed: ${r.stderr.take(200)}")
        val a = store.pullFromTermux(context.conversationId, path, caption = "Spoken by espeak")
        context.attach(a)
        return "Posted spoken audio made with espeak."
    }
}
