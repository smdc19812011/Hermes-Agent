package com.hermes.agent.data.repository

import com.hermes.agent.ui.providers.rankLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun parsesRealOpenRouterCatalog() {
        val body = javaClass.classLoader!!.getResource("openrouter-models.json")!!.readText()
        val models = ModelCatalogRepository.parseCatalog(body)
        assertTrue("expected hundreds of models, got ${models.size}", models.size > 100)
        assertTrue(models.all { it.id.contains('/') })
        assertTrue("context lengths should be read", models.count { it.contextLength != null } > models.size / 2)
        assertTrue("labels should be read", models.any { it.label != null })
    }

    @Test
    fun parsesOpenAiShapeAndDropsNonChatModels() {
        val body = """{"object":"list","data":[
            {"id":"gpt-5","object":"model"},
            {"id":"text-embedding-3-large","object":"model"},
            {"id":"whisper-1","object":"model"},
            {"id":"tts-1-hd","object":"model"},
            {"id":"o4-mini","object":"model"}]}"""
        assertEquals(listOf("gpt-5", "o4-mini"), ModelCatalogRepository.parseCatalog(body).map { it.id })
    }

    @Test
    fun parsesTogetherBareArrayAndFiltersByType() {
        val body = """[
            {"id":"meta-llama/Llama-4-Maverick","display_name":"Llama 4 Maverick","type":"chat","context_length":1048576},
            {"id":"BAAI/bge-large","type":"embedding"},
            {"id":"black-forest-labs/FLUX","type":"image"}]"""
        val models = ModelCatalogRepository.parseCatalog(body)
        assertEquals(1, models.size)
        assertEquals("Llama 4 Maverick", models[0].label)
        assertEquals(1048576, models[0].contextLength)
    }

    @Test
    fun parsesAnthropicList() {
        val body = """{"data":[{"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5","created_at":"2026-01-01T00:00:00Z"}],
            "has_more":false,"first_id":"claude-opus-5-5","last_id":"claude-opus-5-5"}"""
        val models = ModelCatalogRepository.parseCatalog(body)
        assertEquals(listOf("claude-opus-5-5"), models.map { it.id })
        assertEquals("Claude Opus 5.5", models[0].label)
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsUnexpectedShape() {
        ModelCatalogRepository.parseCatalog("""{"error":{"message":"nope"}}""")
    }

    @Test
    fun ranksReadNaturally() {
        assertEquals(
            listOf("Primary", "Secondary", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd"),
            listOf(1, 2, 3, 4, 11, 12, 13, 21, 22, 23).map(::rankLabel)
        )
    }
}
