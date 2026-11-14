package com.hermes.agent.data.media

import com.hermes.agent.data.runtime.HermesScripts
import com.hermes.agent.domain.model.Attachment
import com.hermes.agent.domain.tools.InputPart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class AttachmentInputTest {
    private val dir = Files.createTempDirectory("attach").toFile()
    private val fakeShrink: (File, String) -> Pair<ByteArray, String> = { f, mime -> f.readBytes().copyOf(4) to mime }

    private fun file(name: String, mime: String, bytes: ByteArray) =
        Attachment(name, mime, name, bytes.size.toLong()) to File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun routesEachKindToTheRightInput() {
        val items = listOf(
            file("photo.jpg", "image/jpeg", ByteArray(100) { 7 }),
            file("report.pdf", "application/pdf", "%PDF-1.7 body".toByteArray()),
            file("memo.mp3", "audio/mpeg", "ID3 audio".toByteArray()),
            file("data.csv", "text/csv", "a,b\n1,2\n".toByteArray()),
            file("main.kt", "application/octet-stream", "fun main() = println(1)".toByteArray()),
            file("clip.mp4", "video/mp4", ByteArray(2048))
        )
        val enc = AttachmentEncoder.encode(items, binary = true, toolsCanOpen = true, shrinkImage = fakeShrink)
        assertEquals(listOf(InputPart.Image::class, InputPart.Document::class, InputPart.Audio::class), enc.parts.map { it::class })
        assertEquals("mp3", (enc.parts[2] as InputPart.Audio).format)
        assertEquals(4, Base64.getDecoder().decode((enc.parts[0] as InputPart.Image).base64).size) // went through the shrinker
        assertTrue(enc.note, enc.note.contains("Attached file \"data.csv\":\n```csv\na,b\n1,2\n```"))
        assertTrue("code files are inlined even with a generic type", enc.note.contains("fun main() = println(1)"))
        assertTrue(enc.note, enc.note.contains("[Attached video \"clip.mp4\" (video/mp4") && enc.note.contains("use_attachment"))
    }

    @Test
    fun withoutVisionEverythingBecomesText() {
        val items = listOf(file("photo.png", "image/png", ByteArray(50)), file("report.pdf", "application/pdf", "%PDF".toByteArray()))
        val enc = AttachmentEncoder.encode(items, binary = false, toolsCanOpen = false, shrinkImage = fakeShrink)
        assertTrue(enc.parts.isEmpty())
        assertTrue(enc.note, enc.note.contains("photo.png") && enc.note.contains("can't read it directly"))
        assertTrue(!enc.note.contains("use_attachment"))
    }

    @Test
    fun binaryWithTextExtensionIsNotInlined() {
        val enc = AttachmentEncoder.encode(listOf(file("weird.txt", "application/octet-stream", byteArrayOf(0, 1, 2, 3))), true, false, fakeShrink)
        assertTrue(enc.note, enc.note.startsWith("[Attached file \"weird.txt\""))
    }

    /** The exact push script, run through bash: chunks append into one identical file. */
    @Test
    fun pushToTermuxReassemblesExactly() {
        val home = Files.createTempDirectory("push-home").toFile()
        val payload = ByteArray(PUSH_CHUNK_BYTES * 2 + 77) { (it * 13 % 256).toByte() }
        val q = MediaScripts.quotePath("~/attachments/my file.bin")
        payload.toList().chunked(PUSH_CHUNK_BYTES).forEachIndexed { i, chunk ->
            val script = MediaScripts.appendChunk(q, Base64.getEncoder().encodeToString(chunk.toByteArray()), first = i == 0)
            val p = ProcessBuilder("bash").directory(home).apply { environment()["HOME"] = home.absolutePath }.start()
            p.outputStream.use { it.write(HermesScripts.wrapForStdin(script).toByteArray()) }
            assertTrue(p.waitFor(20, TimeUnit.SECONDS))
            assertEquals(p.errorStream.bufferedReader().readText(), 0, p.exitValue())
        }
        assertArrayEquals(payload, File(home, "attachments/my file.bin").readBytes())
    }
}
