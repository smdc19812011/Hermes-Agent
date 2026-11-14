package com.hermes.agent.data.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.hermes.agent.domain.model.Provider
import com.hermes.agent.domain.model.ProviderType
import com.hermes.agent.domain.tools.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Drives LLMRepository against a fake server speaking each provider's real streaming wire format. */
class LLMRepositoryToolCallingTest {
    private lateinit var server: MockWebServer
    private val repo = LLMRepository(OkHttpClient(), Gson())

    private val fakeTool = object : AgentTool {
        override val name = "scan_lan"
        override val description = "Find devices"
        override val parameters = schema("subnet" to prop("string", "CIDR"))
        override val capability = Capability.NETWORK
        override suspend fun run(args: JsonObject, context: ToolContext) = "ok"
    }

    @Before fun start() { server = MockWebServer().apply { start() } }
    @After fun stop() { server.shutdown() }

    private fun provider(type: ProviderType) = Provider(
        id = "p", name = "P", type = type, apiKey = "key", baseUrl = server.url("/v1").toString(),
        models = listOf("m"), isEnabled = true, priority = 0
    )

    private fun sse(vararg events: Pair<String?, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (type, data) -> (type?.let { "event: $it\n" } ?: "") + "data: $data\n\n" })

    private fun stream(type: ProviderType, turns: List<ChatTurn>, tools: List<AgentTool> = listOf(fakeTool)) = runBlocking {
        repo.streamTurn(provider(type), "m", turns, "sys", tools, 30, "conv-1").toList()
    }

    private fun lastRequestJson(): JsonObject = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject

    @Test
    fun openAiAssemblesToolCallFromFragments() {
        server.enqueue(sse(
            null to """{"choices":[{"delta":{"content":"Let me check."}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"scan_lan","arguments":""}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"subnet\":"}}]}}]}""",
            null to """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"192.168.1.0/24\"}"}}]}}]}""",
            null to """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            null to "[DONE]"
        ))
        val events = stream(ProviderType.OPENROUTER, listOf(ChatTurn.User("scan my lan")))
        assertEquals(StreamEvent.Text("Let me check."), events[0])
        assertEquals(
            StreamEvent.ToolCalls(listOf(ToolCall("call_1", "scan_lan", """{"subnet":"192.168.1.0/24"}"""))),
            events.last()
        )
        val body = lastRequestJson()
        assertEquals("scan_lan", body.getAsJsonArray("tools")[0].asJsonObject.getAsJsonObject("function").get("name").asString)
        assertEquals("system", body.getAsJsonArray("messages")[0].asJsonObject.get("role").asString)
    }

    @Test
    fun openAiSendsToolResultsInWireFormat() {
        server.enqueue(sse(null to """{"choices":[{"delta":{"content":"Found 3 devices."}}]}""", null to "[DONE]"))
        stream(ProviderType.OPENAI, listOf(
            ChatTurn.User("scan"),
            ChatTurn.Assistant("", listOf(ToolCall("call_1", "scan_lan", "{}"))),
            ChatTurn.ToolResult("call_1", "scan_lan", "3 devices")
        ))
        val messages = lastRequestJson().getAsJsonArray("messages")
        val assistant = messages[2].asJsonObject
        assertTrue(assistant.get("content").isJsonNull)
        assertEquals("call_1", assistant.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
        val tool = messages[3].asJsonObject
        assertEquals("tool", tool.get("role").asString)
        assertEquals("call_1", tool.get("tool_call_id").asString)
        assertEquals("3 devices", tool.get("content").asString)
    }

    @Test
    fun anthropicAssemblesToolUseAndGroupsResults() {
        server.enqueue(sse(
            "message_start" to """{"type":"message_start","message":{"id":"msg"}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Checking."}}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"scan_lan","input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"subnet\": \"10.0"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":".0.0/24\"}"}}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use"}}""",
            "message_stop" to """{"type":"message_stop"}"""
        ))
        val events = stream(ProviderType.ANTHROPIC, listOf(
            ChatTurn.User("first"),
            ChatTurn.Assistant("Running two tools", listOf(ToolCall("a", "scan_lan", """{"subnet":"x"}"""), ToolCall("b", "scan_lan", "{}"))),
            ChatTurn.ToolResult("a", "scan_lan", "r1"),
            ChatTurn.ToolResult("b", "scan_lan", "r2")
        ))
        assertEquals(StreamEvent.Text("Checking."), events[0])
        assertEquals(StreamEvent.ToolCalls(listOf(ToolCall("toolu_1", "scan_lan", """{"subnet": "10.0.0.0/24"}"""))), events.last())

        val body = lastRequestJson()
        assertEquals("sys", body.get("system").asString)
        assertEquals("scan_lan", body.getAsJsonArray("tools")[0].asJsonObject.get("name").asString)
        val messages = body.getAsJsonArray("messages")
        assertEquals(3, messages.size()) // user, assistant, one user message carrying both results
        val toolUse = messages[1].asJsonObject.getAsJsonArray("content")[1].asJsonObject
        assertEquals("tool_use", toolUse.get("type").asString)
        assertEquals("x", toolUse.getAsJsonObject("input").get("subnet").asString)
        val results = messages[2].asJsonObject.getAsJsonArray("content")
        assertEquals(listOf("a", "b"), results.map { it.asJsonObject.get("tool_use_id").asString })
    }

    @Test
    fun hermesSendsSessionHeaderAndOnlyTheNewTurn() {
        server.enqueue(sse(
            "hermes.tool.progress" to """{"tool":"terminal","emoji":"💻","label":"nmap -sn 192.168.1.0/24","toolCallId":"t1","status":"running"}""",
            null to """{"choices":[{"delta":{"content":"Done"}}]}""",
            null to "[DONE]"
        ))
        val events = stream(ProviderType.HERMES, listOf(ChatTurn.User("old"), ChatTurn.Assistant("old reply"), ChatTurn.User("new")), tools = emptyList())
        val tool = (events[0] as StreamEvent.Tool).activity
        assertEquals("nmap -sn 192.168.1.0/24", tool.label)
        val request = server.takeRequest()
        assertEquals("conv-1", request.getHeader("X-Hermes-Session-Id"))
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        val users = body.getAsJsonArray("messages").filter { it.asJsonObject.get("role").asString == "user" }
        assertEquals(listOf("new"), users.map { it.asJsonObject.get("content").asString })
        assertFalse(body.has("tools"))
    }

    @Test(expected = java.io.IOException::class)
    fun midStreamErrorFails() {
        server.enqueue(sse(null to """{"error":{"message":"No endpoints found that support tool use"}}"""))
        stream(ProviderType.OPENROUTER, listOf(ChatTurn.User("hi")))
    }

    @Test
    fun httpErrorCarriesProviderMessage() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"No endpoints found that support tool use"}}"""))
        val e = runCatching { stream(ProviderType.OPENROUTER, listOf(ChatTurn.User("hi"))) }.exceptionOrNull()
        assertTrue(e?.message, e?.message?.contains("support tool use") == true)
    }

    @Test
    fun openRouterImageModelsGetImagesBack() {
        server.enqueue(sse(
            null to """{"choices":[{"delta":{"content":"Here you go."}}]}""",
            null to """{"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}]}}]}""",
            null to "[DONE]"
        ))
        val events = runBlocking {
            repo.streamTurn(provider(ProviderType.OPENROUTER), "google/gemini-3-pro-image", listOf(ChatTurn.User("a cat")), null, emptyList(), 30, "c").toList()
        }
        assertEquals(StreamEvent.Image("data:image/png;base64,iVBORw0KGgo="), events.last())
        val body = lastRequestJson()
        assertEquals(listOf("image", "text"), body.getAsJsonArray("modalities").map { it.asString })
    }

    @Test
    fun textModelsDontAskForImages() {
        server.enqueue(sse(null to """{"choices":[{"delta":{"content":"hi"}}]}""", null to "[DONE]"))
        stream(ProviderType.OPENROUTER, listOf(ChatTurn.User("hi")), tools = emptyList())
        assertFalse(lastRequestJson().has("modalities"))
    }

    @Test
    fun openAiSendsImagesFilesAndAudioAsContentParts() {
        server.enqueue(sse(null to """{"choices":[{"delta":{"content":"A cat."}}]}""", null to "[DONE]"))
        val parts = listOf(
            InputPart.Image("image/jpeg", "AAAA"),
            InputPart.Document("r.pdf", "application/pdf", "JVBE"),
            InputPart.Audio("mp3", "SUQz")
        )
        stream(ProviderType.OPENROUTER, listOf(ChatTurn.User("What is this?", parts)), tools = emptyList())
        val content = lastRequestJson().getAsJsonArray("messages").last().asJsonObject.getAsJsonArray("content")
        assertEquals(listOf("image_url", "file", "input_audio", "text"), content.map { it.asJsonObject.get("type").asString })
        assertEquals("data:image/jpeg;base64,AAAA", content[0].asJsonObject.getAsJsonObject("image_url").get("url").asString)
        assertEquals("data:application/pdf;base64,JVBE", content[1].asJsonObject.getAsJsonObject("file").get("file_data").asString)
        assertEquals("mp3", content[2].asJsonObject.getAsJsonObject("input_audio").get("format").asString)
        assertEquals("What is this?", content[3].asJsonObject.get("text").asString)
    }

    @Test
    fun anthropicSendsImageAndDocumentBlocks() {
        server.enqueue(sse("message_stop" to """{"type":"message_stop"}"""))
        stream(ProviderType.ANTHROPIC, listOf(ChatTurn.User("", listOf(InputPart.Image("image/png", "iVBO"), InputPart.Document("a.pdf", "application/pdf", "JVBE")))), tools = emptyList())
        val content = lastRequestJson().getAsJsonArray("messages")[0].asJsonObject.getAsJsonArray("content")
        assertEquals(listOf("image", "document", "text"), content.map { it.asJsonObject.get("type").asString })
        val src = content[0].asJsonObject.getAsJsonObject("source")
        assertEquals("base64", src.get("type").asString)
        assertEquals("image/png", src.get("media_type").asString)
        assertEquals("iVBO", src.get("data").asString)
    }

    @Test
    fun plainTextStaysAPlainString() {
        server.enqueue(sse(null to """{"choices":[{"delta":{"content":"hi"}}]}""", null to "[DONE]"))
        stream(ProviderType.OPENAI, listOf(ChatTurn.User("hello")), tools = emptyList())
        assertTrue(lastRequestJson().getAsJsonArray("messages").last().asJsonObject.get("content").isJsonPrimitive)
    }
}
