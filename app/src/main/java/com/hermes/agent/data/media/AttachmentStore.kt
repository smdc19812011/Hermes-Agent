package com.hermes.agent.data.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.hermes.agent.data.termux.TermuxBridge
import com.hermes.agent.domain.model.Attachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val MAX_ATTACHMENT_BYTES = 100L * 1024 * 1024
/** Termux returns at most 100 KB of stdout per call; 36 KB raw = 48 KB base64 keeps a wide margin. */
const val TERMUX_CHUNK_BYTES = 36 * 1024
/** Pushes travel inside the intent (binder limit ~1 MB, strings count twice): 96 KB raw = 128 KB base64. */
const val PUSH_CHUNK_BYTES = 96 * 1024

/** Files posted in chats, kept under files/attachments/<conversation>/. */
@Singleton
class AttachmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val termux: TermuxBridge
) {
    fun file(a: Attachment): File = File(context.filesDir, a.uri)

    fun shareUri(a: Attachment): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file(a))

    suspend fun saveBytes(conversationId: String, name: String, bytes: ByteArray, mime: String? = null, caption: String = ""): Attachment =
        save(conversationId, name, mime, caption) { it.writeBytes(bytes) }

    suspend fun saveStream(conversationId: String, name: String, input: InputStream, mime: String? = null, caption: String = ""): Attachment =
        save(conversationId, name, mime, caption) { target ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= MAX_ATTACHMENT_BYTES) { "That file is larger than ${MAX_ATTACHMENT_BYTES / 1_048_576} MB." }
                    out.write(buf, 0, n)
                }
            }
        }

    /** Downloads [url] into the chat. */
    suspend fun download(conversationId: String, url: String, name: String? = null, caption: String = ""): Attachment = withContext(Dispatchers.IO) {
        val client = okHttpClient.newBuilder().callTimeout(5, TimeUnit.MINUTES).build()
        val request = Request.Builder().url(url).header("User-Agent", "HermesAgentAndroid/1.6").build()
        client.newCall(request).execute().use { r ->
            check(r.isSuccessful) { "Download failed: HTTP ${r.code}" }
            val body = r.body ?: error("Empty response")
            body.contentLength().takeIf { it > MAX_ATTACHMENT_BYTES }?.let { error("That file is ${it / 1_048_576} MB; the limit is 100 MB.") }
            val headerName = r.header("Content-Disposition")?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").find(it)?.groupValues?.get(1) }
            val fileName = name ?: headerName ?: url.substringBefore('?').trimEnd('/').substringAfterLast('/').ifBlank { "download" }
            val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }?.takeIf { it != "application/octet-stream" }
            saveStream(conversationId, fileName, body.byteStream(), mime, caption)
        }
    }

    /** Copies a file out of Termux's home (which this app can't read directly) in base64 chunks. */
    suspend fun pullFromTermux(conversationId: String, path: String, caption: String = ""): Attachment {
        if (!termux.isInstalled() || !termux.hasPermission()) error("Termux isn't set up. Finish steps 1-3 on the Agent runtime screen.")
        val q = MediaScripts.quotePath(path)
        val size = termux.run(MediaScripts.size(q), "Measure file", 20_000).let { r ->
            r.termuxError?.let { error(it) }
            r.stdout.trim().toLongOrNull() ?: error("No such file in Termux: $path")
        }
        require(size <= MAX_ATTACHMENT_BYTES) { "That file is ${size / 1_048_576} MB; the limit is 100 MB." }
        val name = path.trimEnd('/').substringAfterLast('/')
        return save(conversationId, name, null, caption) { target ->
            target.outputStream().use { out ->
                var index = 0L
                var copied = 0L
                while (copied < size) {
                    val r = termux.run(MediaScripts.chunk(q, index, TERMUX_CHUNK_BYTES), "Copy $name", 30_000)
                    r.termuxError?.let { error(it) }
                    val bytes = Base64.getDecoder().decode(r.stdout.trim())
                    val expected = minOf(TERMUX_CHUNK_BYTES.toLong(), size - copied)
                    check(bytes.size.toLong() == expected) { "Copy of $name was cut short at ${copied + bytes.size} of $size bytes." }
                    out.write(bytes)
                    copied += bytes.size
                    index++
                }
            }
        }
    }

    /** Copies something the user picked (gallery, camera, files) into the chat. */
    suspend fun importUri(conversationId: String, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
        val input = resolver.openInputStream(uri) ?: error("Couldn't read the selected file.")
        input.use { saveStream(conversationId, name, it, resolver.getType(uri)) }
    }

    /** A file the camera can write into, shared through the FileProvider. */
    fun cameraTarget(): Pair<File, Uri> {
        val f = File(context.filesDir, "attachments/camera/photo-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        return f to FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    /** Copies an attachment into Termux (base64 heredoc chunks under the intent size limit); returns the Termux path. */
    suspend fun pushToTermux(a: Attachment, destination: String): String {
        if (!termux.isInstalled() || !termux.hasPermission()) error("Termux isn't set up. Finish steps 1-3 on the Agent runtime screen.")
        val q = MediaScripts.quotePath(destination)
        val file = file(a)
        withContext(Dispatchers.IO) {
            file.inputStream().use { input ->
                val buf = ByteArray(PUSH_CHUNK_BYTES)
                var first = true
                while (true) {
                    // Fill the buffer fully (readNBytes needs Android 13+).
                    var n = 0
                    while (n < buf.size) { val k = input.read(buf, n, buf.size - n); if (k < 0) break; n += k }
                    if (n <= 0 && !first) break
                    val b64 = Base64.getEncoder().encodeToString(buf.copyOf(maxOf(n, 0)))
                    val r = termux.run(MediaScripts.appendChunk(q, b64, first), "Copy ${a.name} to Termux", 30_000)
                    r.termuxError?.let { error(it) }
                    check(r.exitCode == 0) { "Copying into Termux failed: ${r.stderr.take(200)}" }
                    first = false
                    if (n < buf.size) break
                }
            }
        }
        return destination
    }

    private suspend fun save(conversationId: String, name: String, mime: String?, caption: String, write: suspend (File) -> Unit): Attachment =
        withContext(Dispatchers.IO) {
            val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80).ifBlank { "file" }
            val dir = File(context.filesDir, "attachments/${conversationId.replace(Regex("[^A-Za-z0-9-]"), "")}").apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID().toString().take(8)}-$safeName")
            try {
                write(target)
            } catch (e: Exception) {
                target.delete(); throw e
            }
            val head = target.inputStream().use { s -> ByteArray(16).also { s.read(it) } }
            Attachment(
                name = safeName,
                mime = Mime.resolve(mime, safeName, head),
                uri = target.relativeTo(context.filesDir).path,
                size = target.length(),
                caption = caption
            )
        }
}

object MediaScripts {
    /** Termux-side path: "~/x" and relative paths are under $HOME; quoted so spaces and $ are literal. */
    fun quotePath(path: String): String {
        val p = path.trim()
        return when {
            p.startsWith("~/") -> "\"\$HOME\"/" + com.hermes.agent.data.runtime.HermesScripts.shellQuote(p.removePrefix("~/"))
            p.startsWith("/") -> com.hermes.agent.data.runtime.HermesScripts.shellQuote(p)
            else -> "\"\$HOME\"/" + com.hermes.agent.data.runtime.HermesScripts.shellQuote(p)
        }
    }

    fun size(quoted: String) = "[ -f $quoted ] && stat -c %s $quoted"

    /** One chunk as unwrapped base64, stderr silenced so all 100 KB of the result goes to stdout. */
    fun chunk(quoted: String, index: Long, chunkBytes: Int) =
        "dd if=$quoted bs=$chunkBytes skip=$index count=1 2>/dev/null | base64 -w0"

    /** Writes (first) or appends one base64 chunk to [quoted] via a quoted heredoc. */
    fun appendChunk(quoted: String, base64: String, first: Boolean) = listOf(
        "mkdir -p \"\$(dirname $quoted)\"",
        "base64 -d ${if (first) ">" else ">>"} $quoted <<'HERMES_B64_EOF'",
        base64,
        "HERMES_B64_EOF"
    ).joinToString("\n")

    /** Writes speech to [path] with espeak; exit 127 when espeak isn't installed. */
    fun espeak(text: String, voice: String?, path: String): String {
        val q = quotePath(path)
        val v = voice?.takeIf { Regex("^[A-Za-z0-9+_-]{1,20}$").matches(it) } ?: "en-us"
        return listOf(
            "command -v espeak >/dev/null || exit 127",
            "mkdir -p \"\$(dirname $q)\"",
            "espeak -v $v -w $q ${com.hermes.agent.data.runtime.HermesScripts.shellQuote(text)}"
        ).joinToString("\n")
    }
}

object Mime {
    private val byExtension = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
        "bmp" to "image/bmp", "svg" to "image/svg+xml", "heic" to "image/heic",
        "mp4" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/opus",
        "m4a" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
        "pdf" to "application/pdf", "zip" to "application/zip", "txt" to "text/plain", "md" to "text/markdown",
        "csv" to "text/csv", "json" to "application/json", "html" to "text/html", "apk" to "application/vnd.android.package-archive"
    )

    /** Content sniffing wins over a generic or missing type: servers often say octet-stream for images. */
    fun resolve(declared: String?, name: String, head: ByteArray): String =
        sniff(head) ?: declared?.takeIf { it.contains('/') && it != "application/octet-stream" }
        ?: byExtension[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun sniff(b: ByteArray): String? {
        fun at(i: Int, vararg v: Int) = v.withIndex().all { (k, x) -> b.getOrNull(i + k)?.toInt()?.and(0xff) == x }
        fun ascii(i: Int, s: String) = s.withIndex().all { (k, c) -> b.getOrNull(i + k)?.toInt() == c.code }
        return when {
            at(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(4, "ftyp") && (ascii(8, "M4A") || ascii(8, "M4B")) -> "audio/mp4"
            ascii(4, "ftyp") -> "video/mp4"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            ascii(0, "ID3") || at(0, 0xFF, 0xFB) || at(0, 0xFF, 0xF3) -> "audio/mpeg"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "%PDF") -> "application/pdf"
            else -> null
        }
    }
}
