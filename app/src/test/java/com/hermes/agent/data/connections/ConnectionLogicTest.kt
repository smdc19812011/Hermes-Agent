package com.hermes.agent.data.connections

import com.google.gson.JsonParser
import com.hermes.agent.data.agents.SkillFormat
import com.hermes.agent.domain.model.Skill
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ConnectionLogicTest {
    private val spec = """
    {"openapi":"3.0.0","servers":[{"url":"/v2"}],
     "paths":{
      "/pets/{petId}":{"parameters":[{"${'$'}ref":"#/components/parameters/PetId"}],
        "get":{"operationId":"getPet","summary":"Get a pet","parameters":[{"name":"fields","in":"query","schema":{"type":"string"}}]},
        "put":{"operationId":"updatePet","requestBody":{"required":true,"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Pet"}}}}}},
      "/pets":{"post":{"summary":"Add pet","requestBody":{"content":{"application/json":{"schema":{"${'$'}ref":"#/components/schemas/Pet"}}}}}}},
     "components":{"parameters":{"PetId":{"name":"petId","in":"path","required":true,"schema":{"type":"integer"}}},
      "schemas":{"Pet":{"type":"object","properties":{"name":{"type":"string"},"owner":{"${'$'}ref":"#/components/schemas/Owner"}}},
                 "Owner":{"type":"object","properties":{"pet":{"${'$'}ref":"#/components/schemas/Pet"}}}}}}
    """

    @Test
    fun openApiOperationsBecomeToolsWithResolvedSchemas() {
        val parsed = OpenApi.parse(spec, "https://api.test/docs/openapi.json")
        assertEquals("https://api.test/v2", parsed.baseUrl)
        assertEquals(listOf("getPet", "updatePet", "post_pets"), parsed.tools.map { it.name })
        val get = parsed.tools[0]
        assertTrue(get.readOnly)
        val props = JsonParser.parseString(get.schema).asJsonObject.getAsJsonObject("properties")
        assertEquals(setOf("petId", "fields"), props.keySet())
        assertTrue(!parsed.tools[1].schema.contains("\$ref")) // recursive refs are cut off, not left dangling
        assertEquals(listOf("petId", "body"), JsonParser.parseString(parsed.tools[1].schema).asJsonObject.getAsJsonArray("required").map { it.asString })
    }

    @Test
    fun requestsFillPathQueryAndBodyAndStayUnderTheBaseUrl() {
        val tools = OpenApi.parse(spec, "https://api.test/openapi.json").tools
        val args = JsonParser.parseString("""{"petId":"7 a","fields":"name","body":{"name":"Rex"}}""").asJsonObject
        val get = OpenApi.buildRequest("https://api.test/v2", tools[0], args)
        assertEquals("https://api.test/v2/pets/7%20a?fields=name", get.url.toString())
        val put = OpenApi.buildRequest("https://api.test/v2", tools[1], args)
        assertEquals("PUT", put.method)
        val buffer = okio.Buffer(); put.body!!.writeTo(buffer)
        assertEquals("""{"name":"Rex"}""", buffer.readUtf8())
        val generic = OpenApi.buildRequest("https://api.test/v2/", OpenApi.REQUEST_TOOL,
            JsonParser.parseString("""{"method":"get","path":"/items","query":{"q":"x y"}}""").asJsonObject)
        assertEquals("https://api.test/v2/items?q=x%20y", generic.url.toString())
        assertTrue(runCatching { OpenApi.buildRequest("https://api.test", OpenApi.REQUEST_TOOL,
            JsonParser.parseString("""{"method":"GET","path":"https://evil.test/x"}""").asJsonObject) }.isFailure)
        assertTrue(runCatching { OpenApi.buildRequest("https://api.test", tools[0], JsonParser.parseString("{}").asJsonObject) }.isFailure)
    }

    @Test
    fun pkceMatchesTheRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("auth.test").build().toString()
        val auth = OAuth.authorizeUrl(OAuthState(clientId = "c1", authUrl = url + "authorize", scopes = "read write", resource = "https://mcp.test/mcp"), "st", "v")
        val parsed = okhttp3.HttpUrl.Builder().scheme("https").host("x").build().resolve(auth)!!
        assertEquals("S256", parsed.queryParameter("code_challenge_method"))
        assertEquals(OAuth.REDIRECT_URI, parsed.queryParameter("redirect_uri"))
        assertEquals("read write", parsed.queryParameter("scope"))
        assertEquals("https://mcp.test/mcp", parsed.queryParameter("resource"))
    }

    @Test
    fun mcpSignInDiscoversRegistersExchangesAndRefreshes() {
        val server = MockWebServer().apply { start() }
        try {
            val base = server.url("/").toString().trimEnd('/')
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.path) {
                    "/meta" -> MockResponse().setBody("""{"resource":"$base/mcp","authorization_servers":["$base/as"],"scopes_supported":["files"]}""")
                    "/.well-known/oauth-authorization-server/as" -> MockResponse().setBody(
                        """{"authorization_endpoint":"$base/as/authorize","token_endpoint":"$base/as/token","registration_endpoint":"$base/as/register"}""")
                    "/as/register" -> MockResponse().setResponseCode(201).setBody("""{"client_id":"dyn-1"}""")
                    "/as/token" -> {
                        val form = request.body.readUtf8()
                        if ("grant_type=authorization_code" in form && "code_verifier=ver" in form && "client_id=dyn-1" in form)
                            MockResponse().setBody("""{"access_token":"A1","refresh_token":"R1","expires_in":3600}""")
                        else if ("grant_type=refresh_token" in form && "refresh_token=R1" in form)
                            MockResponse().setBody("""{"access_token":"A2","expires_in":3600}""")
                        else MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"bad"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            val http = OkHttpClient()
            val found = OAuth.discover(http, "$base/mcp", "Bearer resource_metadata=\"$base/meta\"", OAuthState())
            assertEquals("dyn-1", found.clientId)
            assertEquals("$base/as/token", found.tokenUrl)
            assertEquals("files", found.scopes)
            assertEquals("$base/mcp", found.resource)
            val signedIn = OAuth.exchangeCode(http, found.copy(pendingVerifier = "ver", pendingState = "s"), "code-1")
            assertEquals("A1", signedIn.accessToken); assertEquals("", signedIn.pendingState)
            assertTrue(signedIn.expiresAt > System.currentTimeMillis())
            val refreshed = OAuth.refresh(http, signedIn)
            assertEquals("A2", refreshed.accessToken); assertEquals("R1", refreshed.refreshToken) // kept when not rotated
            val bad = runCatching { OAuth.exchangeCode(http, found.copy(pendingVerifier = "wrong"), "c") }.exceptionOrNull()
            assertTrue(bad!!.message!!.contains("bad"))
        } finally { server.shutdown() }
    }

    @Test
    fun connectionsRoundTripAndNameToolsSafely() {
        val c = Connection(name = "My GitHub!", type = ConnectionType.REST, url = "https://api.github.com", auth = AuthType.OAUTH,
            oauth = OAuthState(clientId = "x", accessToken = "a", expiresAt = 5), scope = ToolScope.ON_DEMAND, approval = ApprovalPolicy.ALWAYS,
            tools = listOf(RemoteTool("list repos", "d", "{}", true, "GET", "/user/repos")), disabledTools = setOf("list repos"))
        assertEquals(c, Connection.fromJson(c.toJson()))
        assertTrue(c.activeTools.isEmpty())
        assertEquals("my_github__list_repos", Connection.toolName(c.name, "list repos"))
        assertTrue(Connection.toolName("x".repeat(80), "y".repeat(80)).length <= 64)
        assertNull(Connection.fromJson("{}"))
    }

    @Test
    fun localServersRunBehindAPinnedSupergateway() {
        val script = ConnectionRepository.localServerScript("npx -y @scope/server --dir 'my files'", 18123)
        assertTrue(script.contains("npx -y supergateway@4.2.2 --stdio 'npx -y @scope/server --dir '\\''my files'\\''' --outputTransport streamableHttp --port 18123 --host 127.0.0.1 --stateful"))
        assertEquals("http://127.0.0.1:18123/mcp", Connection(name = "fs", type = ConnectionType.MCP_LOCAL, port = 18123).endpoint)
    }

    @Test
    fun skillsKeepTheConnectionsTheyNeed() {
        val skill = Skill("triage", "Triage issues", "Steps", listOf("GitHub", "Linear"))
        assertEquals(skill, SkillFormat.parse(SkillFormat.render(skill), "x"))
        assertEquals(listOf("a", "b"), SkillFormat.parse("---\nname: s\nconnections: a, 'b'\n---\nbody", "s").connections)
    }
}
