package com.hermes.agent.data.connections

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

enum class ConnectionType(val displayName: String, val hint: String) {
    MCP_HTTP("MCP server (URL)", "A remote MCP server's Streamable HTTP address, e.g. https://example.com/mcp"),
    MCP_LOCAL("MCP server (run in Termux)", "A stdio MCP server command, run in Termux through supergateway, e.g. npx -y @modelcontextprotocol/server-everything"),
    REST("REST API", "An API base URL. Add an OpenAPI (JSON) address to get one tool per operation; otherwise the AI gets a general request tool.")
}

enum class AuthType(val displayName: String) {
    NONE("None"), BEARER("Bearer token / API key"), HEADER("Custom header"), BASIC("Username & password"), OAUTH("OAuth 2 sign-in")
}

/** Which chats see a connection's tools without being asked. */
enum class ToolScope(val displayName: String, val description: String) {
    ALL_CHATS("Every chat", "All chats can use its tools."),
    ON_DEMAND("Agents & skills only", "Only agents and skills that list this connection switch its tools on.")
}

enum class ApprovalPolicy(val displayName: String) {
    WRITES("Ask before actions (read-only tools run freely)"), ALWAYS("Ask before every call"), NEVER("Never ask")
}

/** A tool a connection offers; [schema] is its JSON Schema as text. For REST, [method] and [path] say how to call it. */
data class RemoteTool(
    val name: String,
    val description: String,
    val schema: String,
    val readOnly: Boolean = false,
    val method: String? = null,
    val path: String? = null
)

data class OAuthState(
    val clientId: String = "",
    val clientSecret: String = "",
    val authUrl: String = "",
    val tokenUrl: String = "",
    val scopes: String = "",
    /** Resource indicator (RFC 8707); the MCP server URL for MCP sign-in. */
    val resource: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    /** Epoch ms; 0 = unknown. */
    val expiresAt: Long = 0,
    /** Set while the browser sign-in is open, so it survives the app being closed meanwhile. */
    val pendingState: String = "",
    val pendingVerifier: String = ""
) {
    val signedIn: Boolean get() = accessToken.isNotBlank()
}

data class Connection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ConnectionType = ConnectionType.MCP_HTTP,
    val url: String = "",
    val command: String = "",
    val port: Int = 0,
    val openApiUrl: String = "",
    val auth: AuthType = AuthType.NONE,
    /** Bearer token, header value or password, depending on [auth]. */
    val secret: String = "",
    /** Header name for HEADER, user name for BASIC. */
    val authName: String = "",
    val oauth: OAuthState = OAuthState(),
    val scope: ToolScope = ToolScope.ALL_CHATS,
    val approval: ApprovalPolicy = ApprovalPolicy.WRITES,
    val enabled: Boolean = true,
    val tools: List<RemoteTool> = emptyList(),
    val disabledTools: Set<String> = emptySet(),
    val lastError: String = "",
    val refreshedAt: Long = 0
) {
    val activeTools: List<RemoteTool> get() = tools.filter { it.name !in disabledTools }

    /** The URL tools are called on: a local server runs on 127.0.0.1 behind supergateway. */
    val endpoint: String get() = if (type == ConnectionType.MCP_LOCAL) "http://127.0.0.1:$port/mcp" else url.trim()

    fun toJson(): String = JsonObject().apply {
        addProperty("id", id); addProperty("name", name); addProperty("type", type.name)
        addProperty("url", url); addProperty("command", command); addProperty("port", port); addProperty("openApiUrl", openApiUrl)
        addProperty("auth", auth.name); addProperty("secret", secret); addProperty("authName", authName)
        add("oauth", JsonObject().apply {
            with(oauth) {
                addProperty("clientId", clientId); addProperty("clientSecret", clientSecret); addProperty("authUrl", authUrl)
                addProperty("tokenUrl", tokenUrl); addProperty("scopes", scopes); addProperty("resource", resource)
                addProperty("accessToken", accessToken); addProperty("refreshToken", refreshToken); addProperty("expiresAt", expiresAt)
                addProperty("pendingState", pendingState); addProperty("pendingVerifier", pendingVerifier)
            }
        })
        addProperty("scope", scope.name); addProperty("approval", approval.name); addProperty("enabled", enabled)
        add("tools", JsonArray().apply {
            tools.forEach { t ->
                add(JsonObject().apply {
                    addProperty("name", t.name); addProperty("description", t.description); addProperty("schema", t.schema)
                    addProperty("readOnly", t.readOnly); t.method?.let { addProperty("method", it) }; t.path?.let { addProperty("path", it) }
                })
            }
        })
        add("disabledTools", JsonArray().apply { disabledTools.forEach { add(it) } })
        addProperty("lastError", lastError); addProperty("refreshedAt", refreshedAt)
    }.toString()

    companion object {
        fun fromJson(text: String): Connection? = runCatching {
            val o = JsonParser.parseString(text).asJsonObject
            fun JsonObject.s(k: String) = get(k)?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
            fun JsonObject.l(k: String) = get(k)?.takeIf(JsonElement::isJsonPrimitive)?.asLong ?: 0L
            val oa = o.getAsJsonObject("oauth") ?: JsonObject()
            Connection(
                id = o.s("id"), name = o.s("name"),
                type = ConnectionType.entries.firstOrNull { it.name == o.s("type") } ?: ConnectionType.MCP_HTTP,
                url = o.s("url"), command = o.s("command"), port = o.l("port").toInt(), openApiUrl = o.s("openApiUrl"),
                auth = AuthType.entries.firstOrNull { it.name == o.s("auth") } ?: AuthType.NONE,
                secret = o.s("secret"), authName = o.s("authName"),
                oauth = OAuthState(
                    oa.s("clientId"), oa.s("clientSecret"), oa.s("authUrl"), oa.s("tokenUrl"), oa.s("scopes"), oa.s("resource"),
                    oa.s("accessToken"), oa.s("refreshToken"), oa.l("expiresAt"), oa.s("pendingState"), oa.s("pendingVerifier")
                ),
                scope = ToolScope.entries.firstOrNull { it.name == o.s("scope") } ?: ToolScope.ALL_CHATS,
                approval = ApprovalPolicy.entries.firstOrNull { it.name == o.s("approval") } ?: ApprovalPolicy.WRITES,
                enabled = o.get("enabled")?.asBoolean ?: true,
                tools = o.getAsJsonArray("tools")?.mapNotNull { e ->
                    val t = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    RemoteTool(t.s("name"), t.s("description"), t.s("schema").ifEmpty { "{\"type\":\"object\"}" }, t.get("readOnly")?.asBoolean ?: false,
                        t.s("method").ifEmpty { null }, t.s("path").ifEmpty { null })
                }.orEmpty(),
                disabledTools = o.getAsJsonArray("disabledTools")?.map { it.asString }?.toSet().orEmpty(),
                lastError = o.s("lastError"), refreshedAt = o.l("refreshedAt")
            ).takeIf { it.id.isNotEmpty() }
        }.getOrNull()

        /** OpenAI-compatible tool names allow [a-zA-Z0-9_-] up to 64 characters. */
        fun toolName(connectionName: String, tool: String): String {
            val prefix = connectionName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20).ifEmpty { "conn" }
            return "${prefix}__${tool.replace(Regex("[^a-zA-Z0-9_-]+"), "_")}".take(64)
        }
    }
}
