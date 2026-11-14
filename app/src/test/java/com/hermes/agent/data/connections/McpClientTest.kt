package com.hermes.agent.data.connections

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "event: message\ndata: $it\n\n" })
    private fun client(token: String = "t0k") = McpClient(http, server.url("/mcp").toString()) { mapOf("Authorization" to "Bearer $token") }
    private fun body(req: okhttp3.mockwebserver.RecordedRequest) = JsonParser.parseString(req.body.readUtf8()).asJsonObject

    @Test
    fun handshakeListsToolsAcrossPagesAndSseAndKeepsTheSession() {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-03-26","capabilities":{}}}""").setHeader("Mcp-Session-Id", "sess-1"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(sse(
            """{"jsonrpc":"2.0","method":"notifications/message","params":{"level":"info"}}""",
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"search","description":"Find things","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"annotations":{"readOnlyHint":true}}],"nextCursor":"p2"}}"""
        ))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"create","inputSchema":{"type":"object"}}]}}"""))

        val tools = client().listTools()
        assertEquals(listOf("search", "create"), tools.map { it.name })
        assertTrue(tools[0].readOnly); assertFalse(tools[1].readOnly)
        assertEquals("q", JsonParser.parseString(tools[0].schema).asJsonObject.getAsJsonObject("properties").keySet().single())

        val init = server.takeRequest()
        assertEquals("initialize", body(init).get("method").asString)
        assertEquals("Bearer t0k", init.getHeader("Authorization"))
        assertTrue(init.getHeader("Accept")!!.contains("text/event-stream"))
        assertNull(init.getHeader("Mcp-Session-Id"))
        val initialized = server.takeRequest()
        assertEquals("notifications/initialized", body(initialized).get("method").asString)
        assertEquals("sess-1", initialized.getHeader("Mcp-Session-Id"))
        assertEquals("2025-03-26", initialized.getHeader("MCP-Protocol-Version")) // the server's choice is used from then on
        server.takeRequest()
        val page2 = server.takeRequest()
        assertEquals("p2", body(page2).getAsJsonObject("params").get("cursor").asString)
    }

    @Test
    fun callReturnsTextImagesAndErrors() {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}"""))
        server.enqueue(MockResponse().setResponseCode(202))
        val png = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"content":[{"type":"text","text":"done"},{"type":"image","data":"$png","mimeType":"image/png"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":3,"result":{"isError":true,"content":[{"type":"text","text":"bad input"}]}}"""))
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"error":{"code":-32602,"message":"Unknown tool"}}"""))

        val c = client()
        val ok = c.callTool("make", JsonObject().apply { addProperty("x", 1) })
        assertEquals("done\n[1 image(s) were posted to the chat]", ok.text)
        assertArrayEquals(byteArrayOf(1, 2, 3), ok.images.single().second)
        assertTrue(c.callTool("make", JsonObject()).isError)
        val e = runCatching { c.callTool("nope", JsonObject()) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("Unknown tool"))
        server.takeRequest(); server.takeRequest()
        val call = body(server.takeRequest())
        assertEquals("tools/call", call.get("method").asString)
        assertEquals(1, call.getAsJsonObject("params").getAsJsonObject("arguments").get("x").asInt)
    }

    @Test
    fun unauthorizedCarriesTheResourceMetadataPointer() {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", """Bearer resource_metadata="https://x.test/.well-known/oauth-protected-resource""""))
        val e = runCatching { client().listTools() }.exceptionOrNull()
        assertTrue(e is McpAuthRequired)
        assertEquals("https://x.test/.well-known/oauth-protected-resource", OAuth.resourceMetadataUrl((e as McpAuthRequired).wwwAuthenticate))
    }

    @Test
    fun expiredSessionIsRenewedOnce() {
        server.enqueue(json("""{"jsonrpc":"2.0","id":1,"result":{}}""").setHeader("Mcp-Session-Id", "old"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":2,"result":{"tools":[]}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json("""{"jsonrpc":"2.0","id":4,"result":{}}""").setHeader("Mcp-Session-Id", "new"))
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(json("""{"jsonrpc":"2.0","id":5,"result":{"tools":[{"name":"a","inputSchema":{}}]}}"""))
        val c = client()
        c.listTools()
        assertEquals(listOf("a"), c.listTools().map { it.name })
        repeat(6) { server.takeRequest() }
        assertEquals("new", server.takeRequest().getHeader("Mcp-Session-Id"))
    }
}
