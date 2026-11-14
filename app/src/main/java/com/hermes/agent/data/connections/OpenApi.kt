package com.hermes.agent.data.connections

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Turns an OpenAPI 3 or Swagger 2 document (JSON) into tools, one per operation. */
object OpenApi {
    data class Spec(val baseUrl: String, val tools: List<RemoteTool>)

    private val METHODS = listOf("get", "post", "put", "patch", "delete")
    const val MAX_TOOLS = 80

    fun parse(json: String, specUrl: String): Spec {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("The OpenAPI document isn't JSON. Only JSON specs are supported.")
        val paths = root.getAsJsonObject("paths") ?: throw IllegalArgumentException("No paths in the OpenAPI document.")
        val tools = mutableListOf<RemoteTool>()
        val used = mutableSetOf<String>()
        for ((path, item) in paths.entrySet()) {
            val pathItem = item.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val shared = pathItem.getAsJsonArray("parameters") ?: JsonArray()
            for (method in METHODS) {
                val op = pathItem.getAsJsonObject(method) ?: continue
                if (tools.size >= MAX_TOOLS) break
                var name = (op.str("operationId") ?: "${method}_$path").replace(Regex("[^a-zA-Z0-9_-]+"), "_").replace(Regex("_+"), "_").trim('_').take(40)
                while (!used.add(name)) name += "_"
                val properties = JsonObject()
                val required = JsonArray()
                (shared + (op.getAsJsonArray("parameters") ?: JsonArray())).forEach { p0 ->
                    val p = resolve(root, p0, 0).takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    val pName = p.str("name") ?: return@forEach
                    if (p.str("in") !in setOf("path", "query", "header")) return@forEach
                    val schema = (p.get("schema")?.let { resolve(root, it, 0) }?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy()
                        ?: JsonObject().apply { addProperty("type", p.str("type") ?: "string") }) // Swagger 2 puts type on the parameter
                    schema.addProperty("description", listOfNotNull("(${p.str("in")})", p.str("description")).joinToString(" ").take(300))
                    properties.add(pName, schema)
                    if (p.get("required")?.asBoolean == true || p.str("in") == "path") required.add(pName)
                }
                bodySchema(root, op)?.let { body ->
                    properties.add("body", body)
                    if (op.getAsJsonObject("requestBody")?.get("required")?.asBoolean == true) required.add("body")
                }
                val description = listOfNotNull(op.str("summary"), op.str("description")).distinct().joinToString(". ")
                    .ifBlank { "${method.uppercase()} $path" }.take(500)
                tools += RemoteTool(
                    name, "$description (${method.uppercase()} $path)",
                    JsonObject().apply { addProperty("type", "object"); add("properties", properties); add("required", required) }.toString(),
                    readOnly = method == "get", method = method.uppercase(), path = path
                )
            }
        }
        return Spec(baseUrl(root, specUrl), tools)
    }

    private fun bodySchema(root: JsonObject, op: JsonObject): JsonElement? {
        op.getAsJsonObject("requestBody")?.let { rb0 ->
            val rb = resolve(root, rb0, 0).asJsonObject
            val content = rb.getAsJsonObject("content") ?: return null
            val json = content.entrySet().firstOrNull { it.key.contains("json") }?.value?.asJsonObject ?: return null
            return json.get("schema")?.let { resolve(root, it, 0) }
        }
        // Swagger 2: an "in: body" parameter
        return op.getAsJsonArray("parameters")?.map { resolve(root, it, 0) }
            ?.firstOrNull { it.isJsonObject && it.asJsonObject.str("in") == "body" }?.asJsonObject?.get("schema")?.let { resolve(root, it, 0) }
    }

    /** Inlines local $refs a few levels deep; deeper ones become plain objects so strict schema checkers accept the tool. */
    fun resolve(root: JsonObject, e: JsonElement, depth: Int): JsonElement {
        if (e.isJsonArray) return JsonArray().apply { e.asJsonArray.forEach { add(resolve(root, it, depth)) } }
        if (!e.isJsonObject) return e
        val o = e.asJsonObject
        o.str("\$ref")?.let { ref ->
            if (depth >= 4 || !ref.startsWith("#/")) return JsonObject().apply { addProperty("type", "object") }
            val target = ref.removePrefix("#/").split('/').fold(root as JsonElement?) { acc, part ->
                acc?.takeIf { it.isJsonObject }?.asJsonObject?.get(part.replace("~1", "/").replace("~0", "~"))
            } ?: return JsonObject().apply { addProperty("type", "object") }
            return resolve(root, target, depth + 1)
        }
        return JsonObject().apply { o.entrySet().forEach { (k, v) -> add(k, resolve(root, v, depth)) } }
    }

    private fun baseUrl(root: JsonObject, specUrl: String): String {
        root.getAsJsonArray("servers")?.firstOrNull()?.asJsonObject?.str("url")?.let { server ->
            return runCatching { specUrl.toHttpUrl().resolve(server)?.toString() }.getOrNull()?.trimEnd('/') ?: server.trimEnd('/')
        }
        root.str("host")?.let { host ->
            val scheme = root.getAsJsonArray("schemes")?.firstOrNull()?.asString ?: "https"
            return "$scheme://$host${root.str("basePath").orEmpty()}".trimEnd('/')
        }
        return runCatching { specUrl.toHttpUrl().resolve("/")!!.toString().trimEnd('/') }.getOrDefault("")
    }

    /** Builds the HTTP call for an imported operation or the general request tool. Paths must stay under [baseUrl]. */
    fun buildRequest(baseUrl: String, tool: RemoteTool, args: JsonObject): Request {
        val method = (tool.method ?: args.str("method") ?: "GET").uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { "Unsupported method $method." }
        var path = tool.path ?: (args.str("path") ?: "/")
        require(!path.contains("://") && !path.startsWith("//")) { "Give a path under the API's base URL, not a full URL." }
        val schemaProps = runCatching { JsonParser.parseString(tool.schema).asJsonObject.getAsJsonObject("properties") }.getOrNull() ?: JsonObject()
        val query = mutableListOf<Pair<String, String>>()
        val headers = mutableListOf<Pair<String, String>>()
        if (tool.path != null) {
            for ((key, schema) in schemaProps.entrySet()) {
                if (key == "body") continue
                val value = args.get(key)?.let { if (it.isJsonPrimitive) it.asString else it.toString() } ?: continue
                when {
                    schema.asJsonObject.str("description").orEmpty().startsWith("(path)") ->
                        path = path.replace("{$key}", java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20"))
                    schema.asJsonObject.str("description").orEmpty().startsWith("(header)") -> headers += key to value
                    else -> query += key to value
                }
            }
            require(!Regex("\\{[^}]+}").containsMatchIn(path)) { "Missing path parameters for $path." }
        } else {
            args.getAsJsonObject("query")?.entrySet()?.forEach { (k, v) -> query += k to (if (v.isJsonPrimitive) v.asString else v.toString()) }
        }
        val url = (baseUrl.trimEnd('/') + "/" + path.trimStart('/')).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val body = args.get("body")?.let { b -> if (b.isJsonPrimitive) b.asString else b.toString() }
        return Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            when {
                method == "GET" || method == "HEAD" -> method(method, null)
                else -> method(method, (body ?: "").toRequestBody("application/json".toMediaType()))
            }
        }.build()
    }

    /** The tool REST connections always get, for endpoints the spec doesn't cover (or when there's no spec). */
    val REQUEST_TOOL = RemoteTool(
        "request",
        "Call this API. The connection's base URL and sign-in are added for you; give a path relative to it.",
        JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                add("method", JsonObject().apply { addProperty("type", "string"); add("enum", JsonArray().apply { listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { add(it) } }) })
                add("path", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Path under the base URL, e.g. /v1/items/42") })
                add("query", JsonObject().apply { addProperty("type", "object"); addProperty("description", "Query parameters") })
                add("body", JsonObject().apply { addProperty("type", "string"); addProperty("description", "JSON request body") })
            })
            add("required", JsonArray().apply { add("method"); add("path") })
        }.toString()
    )

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private operator fun JsonArray.plus(other: JsonArray) = JsonArray().also { a -> forEach { a.add(it) }; other.forEach { a.add(it) } }
}
