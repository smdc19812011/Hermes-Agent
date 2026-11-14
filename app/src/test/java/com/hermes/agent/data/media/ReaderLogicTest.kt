package com.hermes.agent.data.media

import com.hermes.agent.data.repository.ModelCatalogRepository
import com.hermes.agent.data.repository.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderLogicTest {
    private val catalog by lazy {
        ModelCatalogRepository.parseCatalog(javaClass.classLoader!!.getResource("openrouter-models.json")!!.readText(), includeNonChat = true)
    }

    @Test
    fun knowsWhichModelsCanSee() {
        val nemotron = catalog.first { it.id == "nvidia/nemotron-3-ultra-550b-a55b:free" }
        assertEquals(false, nemotron.inputsImage)
        assertEquals(0.0, nemotron.promptPrice)
        assertTrue(catalog.first { it.id == "nvidia/nemotron-3.5-super-vl:free" }.inputsImage == true)
    }

    @Test
    fun ranksFreeTrustedVisionModelsForATextOnlyUser() {
        val ranked = ReaderLogic.rankVisionModels(catalog, preferred = listOf("nvidia/nemotron-3-ultra-550b-a55b:free"))
        assertEquals(4, ranked.size)
        assertTrue(ranked.toString(), ranked.all { id -> catalog.first { it.id == id }.inputsImage == true })
        assertTrue("free trusted first: $ranked", ranked[0].endsWith(":free") && listOf("gemini", "gemma", "nemotron", "qwen", "llama", "mistral").any { it in ranked[0] })
        assertTrue(ranked.none { it.startsWith("openrouter/") })
    }

    @Test
    fun prefersAVisionModelTheUserAlreadyUses() {
        val models = listOf(
            ModelInfo("free/gemma-vision:free", null, null, inputsImage = true, promptPrice = 0.0),
            ModelInfo("paid/mine", null, null, inputsImage = true, promptPrice = 0.000002),
            ModelInfo("text/only", null, null, inputsImage = false, promptPrice = 0.0)
        )
        assertEquals(listOf("paid/mine", "free/gemma-vision:free"), ReaderLogic.rankVisionModels(models, preferred = listOf("text/only", "paid/mine")))
    }

    private fun zip(name: String, entries: Map<String, String>): File =
        File(Files.createTempDirectory("office").toFile(), name).also { f ->
            ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() } }
        }

    @Test
    fun extractsOfficeText() {
        val docx = zip("a.docx", mapOf("word/document.xml" to
            "<w:document><w:body><w:p><w:r><w:t>Quarterly report</w:t></w:r></w:p><w:p><w:r><w:t>Revenue &amp; costs</w:t><w:tab/><w:t>up</w:t></w:r></w:p></w:body></w:document>"))
        assertEquals("Quarterly report\nRevenue & costs up", ReaderLogic.officeText(docx))
        val pptx = zip("b.pptx", mapOf(
            "ppt/slides/slide2.xml" to "<p:sld><a:p><a:r><a:t>Second</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide1.xml" to "<p:sld><a:p><a:r><a:t>First</a:t></a:r></a:p></p:sld>",
            "ppt/slides/slide10.xml" to "<p:sld><a:p><a:r><a:t>Tenth</a:t></a:r></a:p></p:sld>"
        ))
        assertEquals("First\n\nSecond\n\nTenth", ReaderLogic.officeText(pptx))
        assertNull(ReaderLogic.officeText(zip("c.zip", mapOf("readme.txt" to "hi"))))
    }

    @Test
    fun listsArchives() {
        val listing = ReaderLogic.archiveListing(zip("d.zip", mapOf("src/a.kt" to "x", "README.md" to "hello")))!!
        assertTrue(listing, listing.startsWith("2 entries") && listing.contains("src/a.kt") && listing.contains("README.md (5 bytes)"))
        assertNull(ReaderLogic.archiveListing(File.createTempFile("not", ".zip").apply { writeText("nope") }))
    }

    @Test
    fun spacesVideoFrames() {
        assertEquals(listOf(5.0, 15.0, 25.0), ReaderLogic.evenlySpaced(30.0, 3))
        assertEquals(listOf(0.0), ReaderLogic.evenlySpaced(0.0, 6))
    }
}
