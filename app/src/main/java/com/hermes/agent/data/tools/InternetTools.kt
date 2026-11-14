package com.hermes.agent.data.tools

import com.google.gson.JsonObject
import com.hermes.agent.domain.tools.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_BODY_CHARS = 12_000
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) HermesAgentAndroid/1.3"

/** Rough HTML-to-text so pages fit in the model's context. */
internal fun htmlToText(html: String): String = html
    .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>"), "\n")
    .replace(Regex("<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("[ \\t]+"), " ")
    .replace(Regex("\\n\\s*\\n+"), "\n\n")
    .trim()

@Singleton
class HttpRequestTool @Inject constructor(private val client: OkHttpClient) : AgentTool {
    override val name = "http_request"
    override val capability = Capability.INTERNET
    override val description = "Make an HTTP request and return the status and body. HTML is converted to plain text. " +
        "Use it to read web pages, call APIs, or reach web interfaces of devices on the local network (router admin pages, printers, smart home hubs)."
    override val parameters = schema(
        "url" to prop("string", "Full URL including http:// or https://."),
        "method" to prop("string", "HTTP method, default GET.", listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")),
        "headers" to prop("object", "Optional request headers."),
        "body" to prop("string", "Optional request body (for POST/PUT/PATCH)."),
        required = listOf("url")
    )

    override fun label(args: JsonObject) = "${args.string("method") ?: "GET"} ${args.string("url")}"

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val url = (args.string("url") ?: throw IllegalArgumentException("url is required")).toHttpUrl()
        val target = InetAddress.getByName(url.host)
        if (NetGuard.isLocal(target) && Capability.NETWORK !in context.enabled) {
            throw SecurityException("${url.host} is on the local network. Turn on Local network access in Settings to reach it.")
        }
        val method = args.string("method")?.uppercase() ?: "GET"
        val body = args.string("body")?.toRequestBody(
            (args.getAsJsonObject("headers")?.get("Content-Type")?.asString ?: "application/json").toMediaTypeOrNull()
        )
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .apply {
                args.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) header(k, v.asString) }
                method(method, if (method in setOf("POST", "PUT", "PATCH")) body ?: ByteArray(0).toRequestBody() else body)
            }
            .build()
        val http = client.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
        http.newCall(request).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val raw = if (method == "HEAD") "" else r.body?.string().orEmpty()
            val text = if ("html" in type) htmlToText(raw) else raw
            buildString {
                appendLine("HTTP ${r.code} ${r.message}")
                appendLine("Content-Type: ${type.ifEmpty { "unknown" }}")
                r.header("Location")?.let { appendLine("Location: $it") }
                appendLine()
                append(text.take(MAX_BODY_CHARS))
                if (text.length > MAX_BODY_CHARS) append("\n[truncated: ${text.length - MAX_BODY_CHARS} more characters]")
            }
        }
    }
}

@Singleton
class WebSearchTool @Inject constructor(private val client: OkHttpClient) : AgentTool {
    override val name = "web_search"
    override val capability = Capability.INTERNET
    override val description = "Search the web (DuckDuckGo) and return result titles, links and snippets. Follow up with http_request to read a page."
    override val parameters = schema("query" to prop("string", "What to search for."), required = listOf("query"))

    override fun label(args: JsonObject) = "Searching \"${args.string("query")}\""

    override suspend fun run(args: JsonObject, context: ToolContext): String = withContext(Dispatchers.IO) {
        val query = args.string("query") ?: throw IllegalArgumentException("query is required")
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder().addQueryParameter("q", query).build()
        val html = client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Search failed: HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val results = parseDuckDuckGo(html)
        if (results.isEmpty()) "No results, or the search page format changed. Try http_request on a specific site."
        else results.take(8).mapIndexed { i, (title, link, snippet) -> "${i + 1}. $title\n   $link\n   $snippet" }.joinToString("\n")
    }

    companion object {
        private val RESULT = Regex("(?s)class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>")

        internal fun parseDuckDuckGo(html: String): List<Triple<String, String, String>> =
            RESULT.findAll(html).map { m ->
                val href = m.groupValues[1].replace("&amp;", "&")
                // Result links go through a redirect: //duckduckgo.com/l/?uddg=<encoded target>
                val target = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: href
                Triple(htmlToText(m.groupValues[2]), target, htmlToText(m.groupValues[3]))
            }.toList()
    }
}
