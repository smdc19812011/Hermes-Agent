package com.hermes.agent.data.connections

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** The server wants a sign-in; [wwwAuthenticate] carries its resource-metadata pointer (MCP authorization spec). */
class McpAuthRequired(val wwwAuthenticate: String?) : IOException("This server needs you to sign in.")

/** What tools/call returned: text for the model plus any images it sent. */
data class McpResult(val text: String, val images: List<Pair<String, ByteArray>>, val isError: Boolean)

/**
 * Minimal MCP client over Streamable HTTP (https://modelcontextprotocol.io/specification/2025-06-18/basic/transports):
 * JSON-RPC by POST, answered with JSON or an SSE stream, with the Mcp-Session-Id header kept between calls.
 * Blocking; call it off the main thread.
 */
class McpClient(
    private val http: OkHttpClient,
    private val url: String,
    /** Auth headers, asked for on every request so refreshed tokens are picked up. */
    private val headers: () -> Map<String, String>
) {
    private val ids = AtomicLong(1)
    private var sessionId: String? = null
    private var protocol = PROTOCOL
    @Volatile private var initialized = false

    fun listTools(): List<RemoteTool> {
        ensureInitialized()
        val out = mutableListOf<RemoteTool>()
        var cursor: String? = null
        do {
            val result = request("tools/list", JsonObject().apply { cursor?.let { addProperty("cursor", it) } })
            result.getAsJsonArray("tools")?.forEach { e ->
                val t = e.asJsonObject
                out += RemoteTool(
                    name = t.get("name").asString,
                    description = (t.str("description") ?: t.str("title")).orEmpty().take(1000),
                    schema = (t.get("inputSchema")?.takeIf { it.isJsonObject } ?: JsonObject().apply { addProperty("type", "object") }).toString(),
                    readOnly = t.getAsJsonObject("annotations")?.get("readOnlyHint")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                )
            }
            cursor = result.str("nextCursor")
        } while (cursor != null && out.size < 300)
        return out
    }

    fun callTool(name: String, arguments: JsonObject): McpResult {
        ensureInitialized()
        val result = request("tools/call", JsonObject().apply { addProperty("name", name); add("arguments", arguments) })
        return parseCallResult(result)
    }

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return
        sessionId = null
        val result = request("initialize", JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply { addProperty("name", "hermes-agent-android"); addProperty("version", "1.9") })
        }, initializing = true)
        protocol = result.str("protocolVersion") ?: PROTOCOL
        initialized = true
        notify("notifications/initialized")
    }

    private fun notify(method: String) {
        val body = JsonObject().apply { addProperty("jsonrpc", "2.0"); addProperty("method", method) }
        http.newCall(build(body)).execute().use { r -> if (r.code == 401) throw McpAuthRequired(r.header("WWW-Authenticate")) }
    }

    private fun build(body: JsonObject): Request = Request.Builder().url(url)
        .header("Accept", "application/json, text/event-stream")
        .header("MCP-Protocol-Version", protocol)
        .apply { sessionId?.let { header("Mcp-Session-Id", it) }; headers().forEach { (k, v) -> header(k, v) } }
        .post(body.toString().toRequestBody(JSON))
        .build()

    private fun request(method: String, params: JsonObject, initializing: Boolean = false, retried: Boolean = false): JsonObject {
        val id = ids.getAndIncrement()
        val body = JsonObject().apply {
            addProperty("jsonrpc", "2.0"); addProperty("id", id); addProperty("method", method); add("params", params)
        }
        http.newCall(build(body)).execute().use { r ->
            if (r.code == 401) throw McpAuthRequired(r.header("WWW-Authenticate"))
            // An expired session answers 404: start a new one once.
            if (r.code == 404 && sessionId != null && !initializing && !retried) {
                initialized = false
                ensureInitialized()
                return request(method, params, retried = true)
            }
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${r.body?.string().orEmpty().take(300)}")
            if (initializing) r.header("Mcp-Session-Id")?.let { sessionId = it }
            val message = readResponse(r, id) ?: throw IOException("The server closed the stream without answering $method.")
            message.getAsJsonObject("error")?.let { e -> throw IOException("${e.str("message") ?: "MCP error"} (${e.get("code")})") }
            return message.getAsJsonObject("result") ?: JsonObject()
        }
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
        private val JSON = "application/json".toMediaType()

        private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

        /** Finds the JSON-RPC response with [id] in a JSON body or an SSE stream, skipping server notifications. */
        fun readResponse(r: Response, id: Long): JsonObject? {
            val body = r.body ?: return null
            val isSse = r.header("Content-Type").orEmpty().startsWith("text/event-stream")
            if (!isSse) return matching(JsonParser.parseString(body.string()), id)
            val source = body.source()
            val data = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                when {
                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
                    line.isEmpty() && data.isNotEmpty() -> {
                        val found = runCatching { matching(JsonParser.parseString(data.toString()), id) }.getOrNull()
                        data.clear()
                        if (found != null) return found
                    }
                }
            }
            return data.takeIf { it.isNotEmpty() }?.let { runCatching { matching(JsonParser.parseString(it.toString()), id) }.getOrNull() }
        }

        private fun matching(e: JsonElement, id: Long): JsonObject? = when {
            e.isJsonArray -> e.asJsonArray.firstNotNullOfOrNull { matching(it, id) }
            e.isJsonObject && e.asJsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString == id.toString() -> e.asJsonObject
            else -> null
        }

        fun parseCallResult(result: JsonObject): McpResult {
            val texts = mutableListOf<String>()
            val images = mutableListOf<Pair<String, ByteArray>>()
            (result.getAsJsonArray("content") ?: JsonArray()).forEach { e ->
                val c = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                when (c.str("type")) {
                    "text" -> c.str("text")?.let { texts += it }
                    "image" -> runCatching { java.util.Base64.getDecoder().decode(c.str("data")) }.getOrNull()
                        ?.let { images += (c.str("mimeType") ?: "image/png") to it }
                    "resource" -> c.getAsJsonObject("resource")?.let { res -> texts += res.str("text") ?: "[resource ${res.str("uri")}]" }
                    "resource_link" -> texts += "[link ${c.str("name") ?: ""} ${c.str("uri")}]"
                    "audio" -> texts += "[audio, ${c.str("mimeType")}]"
                }
            }
            if (texts.isEmpty()) result.get("structuredContent")?.let { texts += it.toString() }
            if (images.isNotEmpty()) texts += "[${images.size} image(s) were posted to the chat]"
            return McpResult(texts.joinToString("\n").ifBlank { "(no content)" }, images, result.get("isError")?.asBoolean == true)
        }
    }
}
